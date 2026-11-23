package dev.deadstreets.paper;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Crawler visual/behavior feasibility prototype.
 * Uses the native zombie model's swimming pose on land, held in that pose while alive.
 * This preserves vanilla textures and avoids client packs. It is intentionally a prototype:
 * whether the land-swim pose reads as a convincing ground crawl must be judged in-client.
 */
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
        double chance = clamp(plugin.getConfig().getDouble("special-infected.crawler.spawn-chance", 0.035), 0, 1);
        if (ThreadLocalRandom.current().nextDouble() < chance) prepareTest(zombie);
        if (isCrawler(zombie)) apply(zombie);
    }
    void refresh(Zombie zombie) {
        if (!isCrawler(zombie)) return;
        applyAttributes(zombie);
        // Keep the vanilla swimming pose active on land; this is the visual crawl experiment.
        zombie.setSwimming(true);
    }
    private void apply(Zombie zombie) {
        applyAttributes(zombie);
        zombie.setSwimming(true);
    }
    private void applyAttributes(Zombie zombie) {
        set(zombie, Attribute.SCALE, clamp(plugin.getConfig().getDouble("special-infected.crawler.scale", 0.78), 0.55, 1.0));
        set(zombie, Attribute.MAX_HEALTH, clamp(plugin.getConfig().getDouble("special-infected.crawler.max-health", 24.0), 10.0, 60.0));
        set(zombie, Attribute.MOVEMENT_SPEED, clamp(plugin.getConfig().getDouble("special-infected.crawler.movement-speed", 0.23), 0.08, 0.35));
        set(zombie, Attribute.ATTACK_DAMAGE, clamp(plugin.getConfig().getDouble("special-infected.crawler.attack-damage", 6.0), 2.0, 16.0));
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

    String spawnTest(Player player) {
        if (!enabled()) return "Crawler prototype is disabled in config.";
        if (!plugin.budget().reserve()) return "Walker budget is full; no test Crawler was spawned.";
        Location origin = player.getLocation();
        Location destination = null;
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
            plugin.budget().rollback();
            return "No safe spawn position found in already-loaded nearby chunks.";
        }
        try {
            Location at = destination;
            Zombie zombie = at.getWorld().spawn(at, Zombie.class, created -> {
                prepareTest(created);
                plugin.markWalker(created);
                plugin.budget().tag(created);
                refresh(created);
            });
            return "Spawned test Crawler " + zombie.getUniqueId() + " nearby. It uses the vanilla swimming pose on land.";
        } catch (RuntimeException failure) {
            plugin.budget().rollback();
            return "Test Crawler spawn failed: " + failure.getMessage();
        }
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
