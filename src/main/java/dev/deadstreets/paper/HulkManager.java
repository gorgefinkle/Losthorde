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
 * Dependency-free Hulk: a real vanilla Zombie scaled to approximately three blocks tall.
 * Native rendering, collision, AI and combat all use the same scaled entity.
 */
final class HulkManager {
    private final DeadStreetsPlugin plugin;
    private final NamespacedKey hulkKey;
    private static final double THREE_BLOCK_SCALE = 1.53846; // 1.95-block zombie -> ~3.0 blocks
    private long assigned;

    HulkManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        this.hulkKey = new NamespacedKey(plugin, "special_infected_hulk_v1");
    }

    boolean enabled() { return plugin.getConfig().getBoolean("special-infected.hulk.enabled", true); }

    void assign(Zombie zombie) {
        if (!enabled() || isHulk(zombie)) return;
        double chance = clamp(plugin.getConfig().getDouble("special-infected.hulk.spawn-chance", 0.02), 0, 1);
        if (ThreadLocalRandom.current().nextDouble() < chance) apply(zombie);
    }

    boolean isHulk(Zombie zombie) {
        return zombie.getPersistentDataContainer().has(hulkKey, PersistentDataType.BYTE);
    }

    void refresh(Zombie zombie) {
        if (isHulk(zombie)) applyAttributes(zombie);
    }

    void prepareTest(Zombie zombie) {
        zombie.getPersistentDataContainer().set(hulkKey, PersistentDataType.BYTE, (byte) 1);
        applyAttributes(zombie);
    }

    private void apply(Zombie zombie) {
        zombie.getPersistentDataContainer().set(hulkKey, PersistentDataType.BYTE, (byte) 1);
        assigned++;
        applyAttributes(zombie);
    }

    private void applyAttributes(Zombie zombie) {
        double scale = clamp(plugin.getConfig().getDouble("special-infected.hulk.scale", THREE_BLOCK_SCALE), 1.0, THREE_BLOCK_SCALE);
        set(zombie, Attribute.SCALE, scale);
        set(zombie, Attribute.MAX_HEALTH, clamp(plugin.getConfig().getDouble("special-infected.hulk.max-health", 50.0), 20.0, 200.0));
        set(zombie, Attribute.MOVEMENT_SPEED, clamp(plugin.getConfig().getDouble("special-infected.hulk.movement-speed", 0.16), 0.05, 0.30));
        set(zombie, Attribute.ATTACK_DAMAGE, clamp(plugin.getConfig().getDouble("special-infected.hulk.attack-damage", 12.0), 4.0, 40.0));
        set(zombie, Attribute.ATTACK_KNOCKBACK, clamp(plugin.getConfig().getDouble("special-infected.hulk.attack-knockback", 1.6), 0.0, 5.0));
        set(zombie, Attribute.KNOCKBACK_RESISTANCE, clamp(plugin.getConfig().getDouble("special-infected.hulk.knockback-resistance", 0.70), 0.0, 1.0));
        AttributeInstance health = zombie.getAttribute(Attribute.MAX_HEALTH);
        double maximum = health == null ? 50.0 : health.getValue();
        if (zombie.getHealth() > maximum) zombie.setHealth(maximum);
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        zombie.setShouldBurnInDay(false);
    }

    private static void set(Zombie zombie, Attribute attribute, double value) {
        AttributeInstance instance = zombie.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    String spawnTest(Player player, boolean force) {
        if (!enabled()) return "Hulk is disabled in config.";
        if (!plugin.budget().reserve(force)) return plugin.budget().denialReason() + " No test Hulk was spawned.";
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
                    var upper = origin.getWorld().getBlockAt(x, y + 2, z);
                    var floor = origin.getWorld().getBlockAt(x, y - 1, z);
                    if (feet.isPassable() && head.isPassable() && upper.isPassable()
                            && !feet.isLiquid() && !head.isLiquid() && !upper.isLiquid()
                            && floor.getType().isSolid()) {
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
                if (!isHulk(created)) prepareTest(created);
                if (!force) plugin.budget().tag(created);
            });
            return (force ? "DEV FORCE: spawned uncounted test Hulk " : "Spawned test Hulk ") + zombie.getUniqueId() + " at native three-block scale (" + String.format(java.util.Locale.ROOT, "%.3f", zombie.getBoundingBox().getHeight()) + " blocks tall).";
        } catch (RuntimeException failure) {
            if (!force) plugin.budget().rollback();
            return "Test Hulk spawn failed: " + failure.getMessage();
        }
    }

    long assignedCount() { return assigned; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
