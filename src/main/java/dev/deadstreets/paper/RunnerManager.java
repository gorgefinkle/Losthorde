package dev.deadstreets.paper;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/** Husk-derived runner gameplay variant. Its visible body is supplied by DisplayMotion. */
final class RunnerManager {
    private final DeadStreetsPlugin plugin;
    private final NamespacedKey key;

    RunnerManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "special_infected_runner_v1");
    }

    boolean enabled() { return plugin.getConfig().getBoolean("special-infected.runner.enabled", true); }
    boolean isRunner(Zombie zombie) {
        return zombie.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    void assign(Zombie zombie) {
        if (!enabled() || isRunner(zombie)) return;
        double chance = clamp(plugin.getConfig().getDouble("special-infected.runner.spawn-chance", 0.03), 0, 1);
        if (ThreadLocalRandom.current().nextDouble() < chance) apply(zombie);
    }

    void refresh(Zombie zombie) {
        if (isRunner(zombie)) applyAttributes(zombie);
    }

    private void apply(Zombie zombie) {
        zombie.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        applyAttributes(zombie);
    }

    private void applyAttributes(Zombie zombie) {
        set(zombie, Attribute.SCALE, 1.0);
        set(zombie, Attribute.MAX_HEALTH, clamp(plugin.getConfig().getDouble("special-infected.runner.max-health", 26.0), 16.0, 60.0));
        set(zombie, Attribute.MOVEMENT_SPEED, clamp(plugin.getConfig().getDouble("special-infected.runner.movement-speed", 0.31), 0.18, 0.55));
        set(zombie, Attribute.ATTACK_DAMAGE, clamp(plugin.getConfig().getDouble("special-infected.runner.attack-damage", 5.5), 2.0, 16.0));
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
        if (!enabled()) return "Runner is disabled in config.";
        if (!plugin.budget().reserve(force)) return plugin.budget().denialReason() + " No test Runner was spawned.";
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
            if (!force) plugin.budget().rollback();
            return "No safe spawn position found in already-loaded nearby chunks.";
        }
        try {
            Zombie zombie = destination.getWorld().spawn(destination, Zombie.class, created -> {
                plugin.markTestWalker(created);
                apply(created);
                if (!force) plugin.budget().tag(created);
                plugin.visuals().attachSoon(created);
            });
            return (force ? "DEV FORCE: spawned uncounted test Runner " : "Spawned test Runner ") + zombie.getUniqueId() + " nearby.";
        } catch (RuntimeException failure) {
            if (!force) plugin.budget().rollback();
            return "Test Runner spawn failed: " + failure.getMessage();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
