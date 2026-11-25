package dev.deadstreets.paper;

import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.Chunk;
import java.util.UUID;

/**
 * Compatibility shim retained for internal lifecycle call sites.
 * Dead Streets no longer uses BetterModel or ships custom textures/models.
 * All infected render as native vanilla zombies and inherit the player's active resource packs.
 */
final class BetterModelBridge {
    BetterModelBridge(DeadStreetsPlugin plugin) {}
    void installAssetsAndReload() {}
    boolean assetsLoaded() { return false; }
    boolean attach(Zombie zombie, String modelName, String animation) { return false; }
    void forgetEntity(Zombie zombie) {}
    void forgetChunk(Chunk chunk) {}
    void refreshPlayer(Player player) {}
    void forgetPackPlayer(UUID playerId) {}
    void detachAll() {}
}
