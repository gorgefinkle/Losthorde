package dev.deadstreets.paper;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.concurrent.ThreadLocalRandom;

/** Persistent generated identities and attacker-facing chat health bars. */
final class ZombieIdentityManager {
    private static final String[] FIRST = {"Aaron","Abigail","Adam","Aiden","Alex","Alice","Amelia","Andrew","Anna","Anthony","Arthur","Ashley","Benjamin","Blake","Caleb","Cameron","Caroline","Charles","Charlotte","Chloe","Christopher","Claire","Daniel","David","Dylan","Eleanor","Elijah","Elizabeth","Emily","Emma","Ethan","Evelyn","Gabriel","Grace","Hannah","Harper","Henry","Isabella","Jack","Jacob","James","Jason","Jessica","John","Joseph","Joshua","Julia","Liam","Lillian","Logan","Lucas","Madison","Marcus","Margaret","Mason","Matthew","Maya","Michael","Nathan","Natalie","Nicholas","Noah","Oliver","Olivia","Owen","Patrick","Rachel","Rebecca","Riley","Robert","Samuel","Sarah","Scott","Sofia","Stephen","Thomas","Victoria","William","Zachary","Zoe"};
    private static final String[] LAST = {"Adams","Anderson","Baker","Bennett","Brooks","Campbell","Carter","Clark","Collins","Cooper","Davis","Edwards","Evans","Foster","Garcia","Gray","Green","Hall","Harris","Hayes","Henderson","Hill","Howard","Hughes","Jackson","James","Jenkins","Johnson","Kelly","King","Lewis","Long","Martin","Miller","Mitchell","Morgan","Morris","Murphy","Nelson","Parker","Patterson","Perez","Phillips","Price","Reed","Richardson","Rivera","Roberts","Robinson","Rogers","Ross","Russell","Sanders","Scott","Smith","Stewart","Taylor","Thomas","Thompson","Turner","Walker","Ward","Washington","Watson","White","Williams","Wilson","Wood","Wright","Young"};
    private final DeadStreetsPlugin plugin;
    private final NamespacedKey firstKey;
    private final NamespacedKey lastKey;

    ZombieIdentityManager(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        firstKey = new NamespacedKey(plugin, "identity_first_v1");
        lastKey = new NamespacedKey(plugin, "identity_last_v1");
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
        zombie.setCustomName(ChatColor.WHITE + first + " " + last);
        zombie.setCustomNameVisible(true);
    }

    void showHealthAfterHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie) || !plugin.isManagedWalker(zombie)
                || !plugin.getConfig().getBoolean("zombies.chat-health-bars.enabled", true)) return;
        Entity damager = event.getDamager();
        Player attacker = damager instanceof Player p ? p
                : damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;
        if (attacker == null) return;
        Player recipient = attacker;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!zombie.isValid() || zombie.isDead() || !recipient.isOnline()) return;
            ensureIdentity(zombie);
            var attr = zombie.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double maximum = attr == null ? 20.0 : attr.getValue();
            double current = Math.max(0.0, zombie.getHealth());
            int filled = Math.max(0, Math.min(10, (int) Math.ceil(10.0 * current / Math.max(1.0, maximum))));
            String bar = ChatColor.RED + "■".repeat(filled) + ChatColor.DARK_GRAY + "■".repeat(10 - filled);
            String name = ChatColor.stripColor(zombie.getCustomName());
            recipient.sendMessage(ChatColor.DARK_GRAY + "[Dead Streets] " + ChatColor.WHITE + name
                    + ChatColor.GRAY + "  " + bar + ChatColor.WHITE + " "
                    + String.format(java.util.Locale.ROOT, "%.1f/%.1f HP", current, maximum));
        });
    }
}
