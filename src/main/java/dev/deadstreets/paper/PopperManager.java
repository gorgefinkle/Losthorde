package dev.deadstreets.paper;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/** Drowned-derived popper. Its death burst is visual-only with modest player damage and crawler spawns. */
final class PopperManager {
    private final DeadStreetsPlugin plugin;
    private final NamespacedKey key;

    PopperManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "special_infected_popper_v1");
    }

    boolean enabled() { return plugin.getConfig().getBoolean("special-infected.popper.enabled", true); }
    boolean isPopper(Zombie zombie) {
        return zombie.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    void assign(Zombie zombie) {
        if (!enabled() || isPopper(zombie)) return;
        double chance = clamp(plugin.getConfig().getDouble("special-infected.popper.spawn-chance", 0.02), 0, 1);
        if (ThreadLocalRandom.current().nextDouble() < chance) apply(zombie);
    }

    void refresh(Zombie zombie) {
        if (isPopper(zombie)) applyAttributes(zombie);
    }

    private void apply(Zombie zombie) {
        zombie.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        applyAttributes(zombie);
    }

    private void applyAttributes(Zombie zombie) {
        set(zombie, Attribute.SCALE, 1.0);
        set(zombie, Attribute.MAX_HEALTH, clamp(plugin.getConfig().getDouble("special-infected.popper.max-health", 32.0), 18.0, 80.0));
        set(zombie, Attribute.MOVEMENT_SPEED, clamp(plugin.getConfig().getDouble("special-infected.popper.movement-speed", 0.13), 0.06, 0.25));
        set(zombie, Attribute.ATTACK_DAMAGE, clamp(plugin.getConfig().getDouble("special-infected.popper.attack-damage", 4.0), 2.0, 12.0));
        AttributeInstance maxHealth = zombie.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && zombie.getHealth() > maxHealth.getValue()) zombie.setHealth(maxHealth.getValue());
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        zombie.setShouldBurnInDay(false);
    }

    void onDeath(Zombie zombie) {
        if (!isPopper(zombie) || !plugin.getConfig().getBoolean("special-infected.popper.death-burst.enabled", true)) return;
        Location at = zombie.getLocation().clone();
        double radius = clamp(plugin.getConfig().getDouble("special-infected.popper.death-burst.radius", 3.5), 1.0, 8.0);
        double damage = clamp(plugin.getConfig().getDouble("special-infected.popper.death-burst.damage", 4.0), 0.0, 12.0);

        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 1, 0.0, 0.0, 0.0, 0.0);
        at.getWorld().spawnParticle(Particle.CLOUD, at, 28, 0.35, 0.25, 0.35, 0.025);
        at.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, at, 10, 0.45, 0.35, 0.45, 0.08);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 0.9f, 0.72f);

        if (damage > 0.0) {
            for (Entity entity : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
                if (!(entity instanceof Player player) || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                if (player.getLocation().distanceSquared(at) <= radius * radius) player.damage(damage, zombie);
            }
        }

        int crawlers = ThreadLocalRandom.current().nextInt(1, 5);
        int spawned = plugin.crawlers().spawnFromPopper(at, crawlers);
        plugin.getLogger().fine("Popper burst at " + at + " released " + spawned + "/" + crawlers + " Crawlers.");
    }

    private static void set(Zombie zombie, Attribute attribute, double value) {
        AttributeInstance instance = zombie.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    String spawnTest(Player player) {
        if (!enabled()) return "Popper is disabled in config.";
        if (!plugin.budget().reserve()) return "Walker budget is full; no test Popper was spawned.";
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
            Zombie zombie = destination.getWorld().spawn(destination, Zombie.class, created -> {
                plugin.markTestWalker(created);
                apply(created);
                plugin.budget().tag(created);
                plugin.visuals().attachSoon(created);
            });
            return "Spawned test Popper " + zombie.getUniqueId() + " nearby.";
        } catch (RuntimeException failure) {
            plugin.budget().rollback();
            return "Test Popper spawn failed: " + failure.getMessage();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
