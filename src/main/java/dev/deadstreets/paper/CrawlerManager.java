package dev.deadstreets.paper;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/** Native Zombie gameplay with an optional horizontal, animated BetterModel visual. */
final class CrawlerManager {
    private final DeadStreetsPlugin plugin;
    private final NamespacedKey crawlerKey;

    CrawlerManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        this.crawlerKey = new NamespacedKey(plugin, "special_infected_crawler_v1");
    }

    boolean enabled() { return plugin.getConfig().getBoolean("special-infected.crawler.enabled", true); }
    boolean isCrawler(Zombie zombie) {
        return zombie.getPersistentDataContainer().has(crawlerKey, PersistentDataType.BYTE);
    }
    void prepareTest(Zombie zombie) {
        zombie.getPersistentDataContainer().set(crawlerKey, PersistentDataType.BYTE, (byte) 1);
    }
    void assign(Zombie zombie) {
        if (!enabled() || isCrawler(zombie)) return;
        double chance = clamp(plugin.getConfig().getDouble("special-infected.crawler.spawn-chance", 0.02), 0, 1);
        if (ThreadLocalRandom.current().nextDouble() < chance) prepareTest(zombie);
        if (isCrawler(zombie)) apply(zombie);
    }
    void refresh(Zombie zombie) {
        if (!isCrawler(zombie)) return;
        applyAttributes(zombie);
        applyCrawlPose(zombie);
    }
    private void apply(Zombie zombie) {
        applyAttributes(zombie);
        applyCrawlPose(zombie);
    }
    private void applyCrawlPose(Zombie zombie) {
        // The custom BetterModel supplies the horizontal body and crawl animation.
        // Keep the native zombie upright so any visual fallback is an ordinary vanilla zombie.
        plugin.models().attach(zombie, "deadstreets_crawler", "crawl");
    }
    private void applyAttributes(Zombie zombie) {
        // Keep native zombie dimensions; Pose.SWIMMING supplies the low profile.
        set(zombie, Attribute.SCALE, 1.0);
        set(zombie, Attribute.MAX_HEALTH, clamp(plugin.getConfig().getDouble("special-infected.crawler.max-health", 24.0), 10.0, 60.0));
        set(zombie, Attribute.MOVEMENT_SPEED, clamp(plugin.getConfig().getDouble("special-infected.crawler.movement-speed", 0.20), 0.08, 0.35));
        set(zombie, Attribute.ATTACK_DAMAGE, clamp(plugin.getConfig().getDouble("special-infected.crawler.attack-damage", 5.0), 2.0, 16.0));
        AttributeInstance maxHealth = zombie.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && zombie.getHealth() > maxHealth.getValue()) zombie.setHealth(maxHealth.getValue());
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        zombie.setShouldBurnInDay(false);
    }
    private static void set(Zombie zombie, Attribute attribute, double value) {
        AttributeInstance instance = zombie.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    String spawnTest(Player player, boolean force) {
        if (!enabled()) return "Crawler is disabled in config.";
        if (!plugin.budget().reserve(force)) return plugin.budget().denialReason() + " No test Crawler was spawned.";
        Location origin = player.getLocation();
        Location destination = null;
        // Prefer a clear spot directly in front of the player so the test result cannot
        // silently appear behind them or on the far side of a nearby obstruction.
        var forward = origin.getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) forward.setZ(1);
        forward.normalize().multiply(3.0);
        int frontX = (int) Math.floor(origin.getX() + forward.getX());
        int frontZ = (int) Math.floor(origin.getZ() + forward.getZ());
        int frontY = origin.getBlockY();
        if (origin.getWorld().isChunkLoaded(frontX >> 4, frontZ >> 4)) {
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                int y = frontY + dy;
                var feet = origin.getWorld().getBlockAt(frontX, y, frontZ);
                var head = origin.getWorld().getBlockAt(frontX, y + 1, frontZ);
                var floor = origin.getWorld().getBlockAt(frontX, y - 1, frontZ);
                if (feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()
                        && floor.getType().isSolid()) {
                    destination = new Location(origin.getWorld(), frontX + 0.5, y, frontZ + 0.5);
                    break;
                }
            }
        }
        for (int radius = 2; radius <= 4 && destination == null; radius++) {
            for (int dx = -radius; dx <= radius && destination == null; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = origin.getBlockX() + dx, z = origin.getBlockZ() + dz, y = origin.getBlockY();
                    if (!origin.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
                    var feet = origin.getWorld().getBlockAt(x, y, z);
                    var head = origin.getWorld().getBlockAt(x, y + 1, z);
                    var floor = origin.getWorld().getBlockAt(x, y - 1, z);
                    if (feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid() && floor.getType().isSolid()) {
                        destination = new Location(origin.getWorld(), x + 0.5, y, z + 0.5);
                        break;
                    }
                }
            }
        }
        if (destination == null) {
            if (!force) plugin.budget().rollback();
            return "No safe spawn position found in already-loaded nearby chunks.";
        }
        try {
            Location at = destination;
            Zombie zombie = at.getWorld().spawn(at, Zombie.class, created -> {
                plugin.markTestWalker(created);
                prepareTest(created);
                refresh(created);
                if (!force) plugin.budget().tag(created);
            });
            boolean trackerCreated = plugin.models().attach(zombie, "deadstreets_crawler", "crawl");
            Location actual = zombie.getLocation();
            return (force ? "DEV FORCE: spawned uncounted test Crawler " : "Spawned test Crawler ")
                    + zombie.getUniqueId() + " at " + actual.getWorld().getName() + " "
                    + actual.getBlockX() + " " + actual.getBlockY() + " " + actual.getBlockZ()
                    + ". BetterModel tracker: " + (trackerCreated ? "created; viewer packets requested (client render not yet verified)" : "unavailable; vanilla zombie fallback remains visible")
                    + ". It was placed in front of you when a safe spot was available.";
        } catch (RuntimeException failure) {
            if (!force) plugin.budget().rollback();
            return "Test Crawler spawn failed: " + failure.getMessage();
        }
    }

    /** Creates 1–4 low-profile crawlers from a Popper, using only nearby already-loaded safe positions. */
    int spawnFromPopper(Location center, int count) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Location destination = null;
            for (int attempt = 0; attempt < 24 && destination == null; attempt++) {
                int x = center.getBlockX() + ThreadLocalRandom.current().nextInt(-3, 4);
                int z = center.getBlockZ() + ThreadLocalRandom.current().nextInt(-3, 4);
                if (!center.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int delta : new int[]{0, 1, -1, 2, -2}) {
                    int y = center.getBlockY() + delta;
                    if (y <= center.getWorld().getMinHeight() + 1 || y >= center.getWorld().getMaxHeight() - 2) continue;
                    var feet = center.getWorld().getBlockAt(x, y, z);
                    var head = center.getWorld().getBlockAt(x, y + 1, z);
                    var floor = center.getWorld().getBlockAt(x, y - 1, z);
                    if (feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()
                            && floor.getType().isSolid()) {
                        destination = new Location(center.getWorld(), x + 0.5, y, z + 0.5);
                        break;
                    }
                }
            }
            if (destination == null || !plugin.budget().reserve()) break;
            try {
                destination.getWorld().spawn(destination, Zombie.class, created -> plugin.markPopperCrawler(created));
                spawned++;
            } catch (RuntimeException failure) {
                plugin.budget().rollback();
                plugin.getLogger().warning("Popper crawler spawn failed: " + failure.getMessage());
            }
        }
        return spawned;
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
