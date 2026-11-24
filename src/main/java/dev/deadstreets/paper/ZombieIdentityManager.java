package dev.deadstreets.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Persistent generated identities with a per-viewer, aim-to-reveal health plate.
 * Only each player's currently targeted entity is inspected; this never scans a horde.
 */
final class ZombieIdentityManager {
    private static final String[] FIRST = {
        "Aaron","Abigail","Adam","Aiden","Alex","Alice","Amelia","Andrew","Anna","Anthony","Arthur","Ashley",
        "Benjamin","Blake","Caleb","Cameron","Caroline","Charles","Charlotte","Chloe","Christopher","Claire",
        "Daniel","David","Dylan","Eleanor","Elijah","Elizabeth","Emily","Emma","Ethan","Evelyn","Gabriel","Grace",
        "Hannah","Harper","Henry","Isabella","Jack","Jacob","James","Jason","Jessica","John","Joseph","Joshua",
        "Julia","Liam","Lillian","Logan","Lucas","Madison","Marcus","Margaret","Mason","Matthew","Maya","Michael",
        "Nathan","Natalie","Nicholas","Noah","Oliver","Olivia","Owen","Patrick","Rachel","Rebecca","Riley","Robert",
        "Samuel","Sarah","Scott","Sofia","Stephen","Thomas","Victoria","William","Zachary","Zoe","Adrian","Alana",
        "Amir","Beatrice","Calvin","Celia","Darius","Diana","Emmett","Farah","Felix","Georgia","Hector","Iris",
        "Jamal","June","Kieran","Leah","Malcolm","Naomi","Nico","Rosa","Simon","Talia","Wesley","Yasmin"
    };
    private static final String[] LAST = {
        "Adams","Anderson","Baker","Bennett","Brooks","Campbell","Carter","Clark","Collins","Cooper","Davis",
        "Edwards","Evans","Foster","Garcia","Gray","Green","Hall","Harris","Hayes","Henderson","Hill","Howard",
        "Hughes","Jackson","James","Jenkins","Johnson","Kelly","King","Lewis","Long","Martin","Miller","Mitchell",
        "Morgan","Morris","Murphy","Nelson","Parker","Patterson","Perez","Phillips","Price","Reed","Richardson",
        "Rivera","Roberts","Robinson","Rogers","Ross","Russell","Sanders","Scott","Smith","Stewart","Taylor",
        "Thomas","Thompson","Turner","Walker","Ward","Washington","Watson","White","Williams","Wilson","Wood",
        "Wright","Young","Bishop","Caldwell","Chavez","Dawson","Ellis","Fletcher","Griffin","Holloway","Ibrahim",
        "Kim","Lawson","Mercer","Navarro","Owens","Patel","Quinn","Ramirez","Sullivan","Torres","Vaughn"
    };

    private final DeadStreetsPlugin plugin;
    private final NamespacedKey firstKey;
    private final NamespacedKey lastKey;
    private final Map<UUID, TextDisplay> plates = new HashMap<>();
    private int taskId = -1;

    ZombieIdentityManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        firstKey = new NamespacedKey(plugin, "identity_first_v1");
        lastKey = new NamespacedKey(plugin, "identity_last_v1");
    }

    void start() {
        if (taskId != -1) return;
        int interval = Math.max(1, Math.min(10, plugin.getConfig().getInt("zombies.target-display.update-interval-ticks", 3)));
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tickViewers, 1L, interval);
    }

    void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
        for (TextDisplay display : plates.values()) if (display.isValid()) display.remove();
        plates.clear();
    }

    void onQuit(Player player) {
        TextDisplay display = plates.remove(player.getUniqueId());
        if (display != null && display.isValid()) display.remove();
    }

    void ensureIdentity(Zombie zombie) {
        var pdc = zombie.getPersistentDataContainer();
        String first = pdc.get(firstKey, PersistentDataType.STRING);
        String last = pdc.get(lastKey, PersistentDataType.STRING);
        if (first == null || last == null) {
            first = FIRST[ThreadLocalRandom.current().nextInt(FIRST.length)];
            last = LAST[ThreadLocalRandom.current().nextInt(LAST.length)];
            pdc.set(firstKey, PersistentDataType.STRING, first);
            pdc.set(lastKey, PersistentDataType.STRING, last);
        }
        // Keep the identity in persistent data, never as a globally visible vanilla nametag.
        zombie.setCustomName(null);
        zombie.setCustomNameVisible(false);
    }

    private void tickViewers() {
        if (!plugin.getConfig().getBoolean("zombies.target-display.enabled", true)) {
            for (UUID id : java.util.List.copyOf(plates.keySet())) removePlate(id);
            return;
        }
        int range = Math.max(3, Math.min(24, plugin.getConfig().getInt("zombies.target-display.range", 9)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            Entity target = player.getTargetEntity(range);
            if (target instanceof Zombie zombie && plugin.isManagedWalker(zombie) && zombie.isValid() && !zombie.isDead()) {
                ensureIdentity(zombie);
                updatePlate(player, zombie);
            } else {
                removePlate(player.getUniqueId());
            }
        }
    }

    private void updatePlate(Player viewer, Zombie zombie) {
        TextDisplay display = plates.get(viewer.getUniqueId());
        if (display == null || !display.isValid() || display.getWorld() != viewer.getWorld()) {
            removePlate(viewer.getUniqueId());
            Location at = plateLocation(zombie);
            display = at.getWorld().spawn(at, TextDisplay.class, created -> {
                created.setVisibleByDefault(false);
                created.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                created.setSeeThrough(true);
                created.setShadowed(true);
                created.setDefaultBackground(false);
                created.setAlignment(TextDisplay.TextAlignment.CENTER);
                created.setLineWidth(240);
                created.setPersistent(false);
                created.setInvulnerable(true);
                created.setGravity(false);
            });
            plates.put(viewer.getUniqueId(), display);
            viewer.showEntity(plugin, display);
        }

        display.teleport(plateLocation(zombie));
        String name = identity(zombie);
        double max = zombie.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                ? 20.0 : zombie.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        double current = Math.max(0.0, zombie.getHealth());
        int segments = 12;
        int filled = Math.max(0, Math.min(segments, (int) Math.ceil(segments * current / Math.max(1.0, max))));
        String bar = "§c" + "▰".repeat(filled) + "§8" + "▱".repeat(segments - filled)
                + "§7  " + String.format(java.util.Locale.ROOT, "%.0f%%", 100.0 * current / Math.max(1.0, max));
        display.setText("§f" + name + "\\n" + bar);
    }

    private Location plateLocation(Zombie zombie) {
        return zombie.getLocation().clone().add(0.0, zombie.getBoundingBox().getHeight() + 0.28, 0.0);
    }

    private String identity(Zombie zombie) {
        var pdc = zombie.getPersistentDataContainer();
        String first = pdc.get(firstKey, PersistentDataType.STRING);
        String last = pdc.get(lastKey, PersistentDataType.STRING);
        return (first == null ? "Unknown" : first) + " " + (last == null ? "Survivor" : last);
    }

    private void removePlate(UUID viewerId) {
        TextDisplay display = plates.remove(viewerId);
        if (display != null && display.isValid()) display.remove();
    }
}
