package dev.deadstreets.paper;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jan1k.displaymotion.paper.DisplayMotion;
import org.jan1k.displaymotion.paper.ImportedModel;
import org.jan1k.displaymotion.paper.MotionDisplay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Replaces the visible body of special infected with DisplayMotion models while leaving
 * the server-side Zombie in charge of AI, pathfinding, collision, damage and persistence.
 */
final class VisualManager implements Listener {
    private static final String[] MODEL_FILES = {
            "runner_husk", "popper_drowned", "crawler_lowprofile", "hulk_brute"
    };

    private static final class Active {
        final Zombie zombie;
        final String type;
        final MotionDisplay display;
        final Set<UUID> hiddenPlayers = new HashSet<>();
        String animation = "idle";
        int lockUntil;
        boolean dying;
        int removeAt;

        Active(Zombie zombie, String type, MotionDisplay display) {
            this.zombie = zombie;
            this.type = type;
            this.display = display;
        }
    }

    private record HitKey(UUID player, UUID zombie) {}

    private final DeadStreetsPlugin plugin;
    private final Map<String, ImportedModel> models = new HashMap<>();
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<HitKey, Integer> lastHitTick = new HashMap<>();
    private DisplayMotion motion;
    private BukkitTask task;

    VisualManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        motion = DisplayMotion.create(plugin, false);
        try {
            for (String name : MODEL_FILES) {
                String file = "models/" + name + ".bbmodel";
                plugin.saveResource(file, false);
                Path path = plugin.getDataFolder().toPath().resolve(file);
                ImportedModel model = ImportedModel.load(path);
                if (!model.animations().contains("idle") || !model.animations().contains("death")) {
                    throw new IOException("Model " + name + " must include idle and death animations; found " + model.animations());
                }
                models.put(name, model);
                plugin.getLogger().info("Loaded " + name + ": " + model.parts().size() + " parts, animations=" + model.animations());
            }
            if (Boolean.getBoolean("deadstreets.model-smoke")) selfTest();
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().severe("Could not load required Dead Streets infected models: " + failure.getMessage());
            throw new IllegalStateException("Dead Streets model initialization failed", failure);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }


    /** Full API smoke test, enabled only with -Ddeadstreets.model-smoke=true. */
    private void selfTest() {
        if (Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("No loaded world for model self-test");
        World world = Bukkit.getWorlds().getFirst();
        Location base = world.getSpawnLocation().clone().add(0, 1, 0);
        int index = 0;
        for (Map.Entry<String, ImportedModel> entry : models.entrySet()) {
            Location at = base.clone().add(index * 0.7, 0, 0);
            Zombie dummy = world.spawn(at, Zombie.class, zombie -> {
                zombie.setAI(false);
                zombie.setSilent(true);
                zombie.setInvulnerable(true);
            });
            MotionDisplay display = null;
            try {
                display = entry.getValue().spawn(motion, at);
                display.follow(dummy, new Vector(0, 0, 0), 2);
                display.hitbox(1.0f, 1.8f);
                display.onClick(click -> { });
                for (String animation : entry.getValue().animations()) {
                    entry.getValue().play(display, animation, 0);
                }
                entry.getValue().play(display, "idle", 0);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("DisplayMotion spawn/animation self-test failed for " + entry.getKey(), failure);
            } finally {
                if (display != null && !display.isRemoved()) display.remove();
                dummy.remove();
            }
            index++;
        }
        plugin.getLogger().info("DisplayMotion model runtime self-test passed for " + models.size()
                + " models and every animation, follow target, and hitbox.");
    }

    void attachSoon(Zombie zombie) {
        if (zombie == null || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> attach(zombie));
    }

    void attach(Zombie zombie) {
        if (zombie == null || !zombie.isValid() || zombie.isDead()) return;
        String type = plugin.specialVisualType(zombie);
        if (type == null) return;
        Active existing = active.get(zombie.getUniqueId());
        if (existing != null && existing.type.equals(type) && !existing.display.isRemoved()) {
            hideFromViewers(existing);
            return;
        }
        if (existing != null) remove(existing);

        ImportedModel model = models.get(type);
        if (model == null) {
            plugin.getLogger().severe("No DisplayMotion model registered for special infected type " + type);
            return;
        }
        MotionDisplay display = model.spawn(motion, zombie.getLocation());
        display.follow(zombie, new Vector(0, 0, 0), 2);
        float width = switch (type) {
            case "hulk_brute" -> 1.8f;
            case "popper_drowned" -> 1.0f;
            case "crawler_lowprofile" -> 1.3f;
            default -> 0.95f;
        };
        float height = switch (type) {
            case "hulk_brute" -> 3.0f;
            case "crawler_lowprofile" -> 0.72f;
            case "popper_drowned" -> 2.15f;
            default -> 2.0f;
        };
        display.hitbox(width, height);
        Active visual = new Active(zombie, type, display);
        display.onClick(click -> meleeHit(visual, click.player(), click.leftClick()));
        active.put(zombie.getUniqueId(), visual);
        model.play(display, "idle");
        hideFromViewers(visual);
    }

    private void meleeHit(Active visual, Player attacker, boolean leftClick) {
        if (!leftClick || visual.dying || !attacker.isOnline() || attacker.getGameMode() == GameMode.SPECTATOR) return;
        Zombie zombie = visual.zombie;
        if (!zombie.isValid() || zombie.isDead() || attacker.getWorld() != zombie.getWorld()) return;
        if (attacker.getLocation().distanceSquared(zombie.getLocation()) > 16.0 || !attacker.hasLineOfSight(zombie)) return;
        int now = Bukkit.getCurrentTick();
        HitKey key = new HitKey(attacker.getUniqueId(), zombie.getUniqueId());
        int previous = lastHitTick.getOrDefault(key, Integer.MIN_VALUE / 2);
        if (now - previous < 9) return;
        lastHitTick.put(key, now);
        AttributeInstance attack = attacker.getAttribute(Attribute.ATTACK_DAMAGE);
        double damage = attack == null ? 1.0 : Math.max(1.0, attack.getValue());
        zombie.damage(damage, attacker);
    }

    void attack(Zombie zombie) {
        Active visual = active.get(zombie.getUniqueId());
        if (visual == null || visual.dying) return;
        String animation = visual.type.equals("crawler_lowprofile") ? "lunge" : "attack";
        if (!models.get(visual.type).animations().contains(animation)) return;
        models.get(visual.type).play(visual.display, animation, 5);
        visual.animation = animation;
        visual.lockUntil = Bukkit.getCurrentTick() + switch (visual.type) {
            case "crawler_lowprofile" -> 10;
            case "popper_drowned" -> 14;
            case "hulk_brute" -> 15;
            default -> 11;
        };
    }

    void onDeath(Zombie zombie) {
        Active visual = active.get(zombie.getUniqueId());
        if (visual == null) return;
        visual.dying = true;
        visual.removeAt = Bukkit.getCurrentTick() + (visual.type.equals("popper_drowned") ? 14 : 20);
        String animation = visual.type.equals("popper_drowned") ? "burst" : "death";
        models.get(visual.type).play(visual.display, animation, 3);
    }

    void hideFrom(Player player) {
        for (Active visual : active.values()) {
            if (visual.zombie.getWorld() != player.getWorld()) continue;
            visual.hiddenPlayers.add(player.getUniqueId());
            player.hideEntity(plugin, visual.zombie);
        }
    }

    void onChunkUnload(Chunk chunk) {
        Iterator<Active> iterator = active.values().iterator();
        while (iterator.hasNext()) {
            Active visual = iterator.next();
            if (visual.zombie.getWorld().getUID().equals(chunk.getWorld().getUID())
                    && (visual.zombie.getLocation().getBlockX() >> 4) == chunk.getX()
                    && (visual.zombie.getLocation().getBlockZ() >> 4) == chunk.getZ()) {
                remove(visual);
                iterator.remove();
            }
        }
    }

    private void hideFromViewers(Active visual) {
        for (Player player : visual.zombie.getWorld().getPlayers()) {
            visual.hiddenPlayers.add(player.getUniqueId());
            player.hideEntity(plugin, visual.zombie);
        }
    }

    private void tick() {
        int now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<UUID, Active>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Active visual = iterator.next().getValue();
            if (visual.dying) {
                if (now >= visual.removeAt) {
                    remove(visual);
                    iterator.remove();
                }
                continue;
            }
            if (!visual.zombie.isValid() || visual.zombie.isDead() || visual.display.isRemoved()) {
                remove(visual);
                iterator.remove();
                continue;
            }
            if (now < visual.lockUntil) continue;
            double speedSquared = visual.zombie.getVelocity().getX() * visual.zombie.getVelocity().getX()
                    + visual.zombie.getVelocity().getZ() * visual.zombie.getVelocity().getZ();
            String desired = switch (visual.type) {
                case "runner_husk" -> speedSquared > 0.0225 ? "run" : speedSquared > 0.0015 ? "walk" : "idle";
                case "crawler_lowprofile" -> speedSquared > 0.0008 ? "crawl" : "idle";
                default -> speedSquared > 0.0015 ? "walk" : "idle";
            };
            if (!desired.equals(visual.animation)) {
                models.get(visual.type).play(visual.display, desired, 7);
                visual.animation = desired;
            }
            if (now % 40 == 0) hideFromViewers(visual);
        }
        if (now % 600 == 0) lastHitTick.entrySet().removeIf(e -> now - e.getValue() > 200);
    }

    private void remove(Active visual) {
        if (!visual.display.isRemoved()) visual.display.remove();
        for (UUID id : visual.hiddenPlayers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.showEntity(plugin, visual.zombie);
        }
        visual.hiddenPlayers.clear();
    }

    void stop() {
        if (task != null) task.cancel();
        for (Active visual : active.values()) remove(visual);
        active.clear();
        lastHitTick.clear();
    }

    int activeCount() { return active.size(); }
}
