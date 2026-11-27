package dev.deadstreets.paper;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Durable, conservative admission counter for ALL plugin-created permanent walkers.
 * Reserve synchronously BEFORE spawning. A crash may consume an unused slot but cannot
 * manufacture another zombie from an already consumed horde/parcel ordinal.
 * No entity is removed when the limit is reached. Regular zombie deaths release slots.
 */
final class WalkerBudget {
    private final DeadStreetsPlugin plugin;
    private final NamespacedKey counted;
    private int reserved;
    private long denied;
    private boolean healthy = true;
    WalkerBudget(DeadStreetsPlugin plugin) {
        this.plugin = plugin;
        this.counted = new NamespacedKey(plugin, "quota_reserved_v1");
    }
    int reserved() { return reserved; }
    long denied() { return denied; }
    int maximum() { return Math.max(32, Math.min(100_000, plugin.getConfig().getInt("zombies.max-total-managed-walkers", 1800))); }
    private Path path() { return plugin.getDataFolder().toPath().resolve("walker-budget-v1.txt"); }
    void load() {
        if (Files.isRegularFile(path())) {
            try {
                reserved = Integer.parseInt(Files.readString(path(),StandardCharsets.UTF_8).trim());
                if (reserved < 0 || reserved > 100_000_000) throw new NumberFormatException("out of range");
            } catch(Exception e) {
                healthy = false;
                plugin.getLogger().severe("Walker budget is invalid; FAILING CLOSED: " + e.getMessage());
            }
        }
        // Old R05 zombies lacked this quota tag. Count only loaded old instances as a
        // conservative best-effort migration; unattended old unloaded entities cannot be counted.
        int legacy=0;
        for(var world:Bukkit.getWorlds()) for(var chunk:world.getLoadedChunks()) {
            if(!chunk.isEntitiesLoaded())continue;
            for(var entity:chunk.getEntities()) if(entity instanceof Zombie z && plugin.isManagedWalker(z)
                && !z.getPersistentDataContainer().has(counted,PersistentDataType.BYTE)) legacy++;
        }
        if(legacy>0) {
            reserved += legacy;
            if(!save()) healthy = false;
            for(var world:Bukkit.getWorlds()) for(var chunk:world.getLoadedChunks()) {
                if(!chunk.isEntitiesLoaded())continue;
                for(var entity:chunk.getEntities()) if(entity instanceof Zombie z && plugin.isManagedWalker(z)
                        && !z.getPersistentDataContainer().has(counted,PersistentDataType.BYTE)) tag(z);
            }
            plugin.getLogger().warning("Counted " + legacy + " loaded pre-R06 managed zombies against quota; unloaded legacy mobs require a migration audit.");
        }
    }
    String denialReason() {
        if (!healthy) return "Walker budget persistence is unhealthy; normal admissions are fail-closed. Check the server log and walker-budget-v1.txt.";
        if (reserved >= maximum()) return "Walker budget is full (" + reserved + "/" + maximum() + ").";
        return "";
    }
    boolean reserve() { return reserve(false); }
    boolean reserve(boolean force) {
        // Developer force-through test spawns bypass quota and persistence admission.
        // They are deliberately not tagged/accounted, so they cannot corrupt the durable count.
        if (force) return true;
        if(!healthy || reserved>=maximum()) {denied++; return false;}
        reserved++;
        if(!save()) {reserved--; healthy=false;denied++;return false;}
        return true;
    }
    void tag(Zombie z) { z.getPersistentDataContainer().set(counted,PersistentDataType.BYTE,(byte)1); }
    void rollback() { if(reserved>0) {reserved--;if(!save())healthy=false;} }
    void onDeath(Zombie zombie) {
        if(zombie.getPersistentDataContainer().has(counted,PersistentDataType.BYTE)) {
            zombie.getPersistentDataContainer().remove(counted);
            rollback();
        }
    }
    void stop() {if(!save())plugin.getLogger().severe("Walker quota was not checkpointed; admission disabled on next start if corrupt.");}
    private boolean save() {
        try {
            Files.createDirectories(path().getParent());
            Path tmp=path().resolveSibling("walker-budget-v1.txt.tmp");
            Files.writeString(tmp,Integer.toString(reserved),StandardCharsets.UTF_8);
            try {Files.move(tmp,path(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException ex) {Files.move(tmp,path(),StandardCopyOption.REPLACE_EXISTING);}
            return true;
        } catch(IOException e) {plugin.getLogger().severe("Walker budget persistence failed; freezing new admissions: " + e.getMessage());return false;}
    }
}
