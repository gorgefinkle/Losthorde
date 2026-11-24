# Dead Streets infected visuals

Dead Streets uses FreeMinecraftModels (FMM) 2.16.0 for all four special infected. Each is an articulated Blockbench model attached to a real Bukkit zombie. The zombie remains the authoritative entity for AI, targeting, navigation, collision, health, damage, and persistence; FMM supplies the client-side geometry, texture and animation.

| Infected | Texture | Model and animation |
|---|---|---|
| Runner | Vanilla Husk texture | Forward-leaning runner; idle, run, attack, damaged, death |
| Popper | Vanilla Drowned texture | Swollen drowned silhouette; idle, walk, attack, inflate, damaged, burst/death |
| Crawler | Vanilla Zombie texture | Low elongated crawler; idle, crawl, lunge/attack, damaged, death |
| Hulk | Vanilla Zombie texture | Broad three-block brute; idle, walk, attack, roar, damaged, death |

## Model source and installation

Editable .bbmodel files are embedded under `src/main/resources/fmm/`. On Dead Streets startup, they are copied into:

```
plugins/FreeMinecraftModels/models/deadstreets/
```

If FMM has not imported the four model IDs, Dead Streets requests one FMM reload. On each successful `FmmReloadedEvent`, it reacquires the model instances from surviving zombies; it does not keep stale FMM references across reloads.

FMM 2.16.0 and ResourcePackManager 2.4.13 are required server dependencies. RSPM merges and hosts the FMM-generated pack, including its remote fallback when server self-hosting is unreachable. Players need to accept the server resource-pack prompt. No client mod or manually hosted pack is required.

## Geometry and collision

The model's reserved top-level `hitbox` bone defines its collision and click envelope:

- Runner: approximately 0.875 × 0.875 × 2.06 blocks.
- Popper: approximately 1 × 1 × 2.125 blocks.
- Crawler: approximately 0.75 × 2.125 × 0.625 blocks.
- Hulk: approximately 1.875 × 1.875 × 3 blocks.

The hidden zombie driver remains at unit scale. This prevents the prior R11 native scale modifiers from scaling the already-sized FMM geometry a second time. The Crawler retains Paper's fixed swimming pose to keep its backing entity low to the ground.

## Animation and combat

FMM automatically selects idle/walk states from the backing entity's horizontal movement. Runner maps its movement state to `run`; Crawler maps it to `crawl`. Zombie attacks trigger the model's action-layer `attack` animation. Accepted damage triggers a short articulated `damaged` reaction and FMM's native tint flash. FMM's death handling plays `death`; the Popper's death state is authored as its burst animation.

The model click surface follows the authored hitbox. Contact damage from the model itself is disabled, so ordinary zombie combat remains authoritative and is not doubled.

## Runtime lifecycle

- Chunk unload removes Dead Streets' local reference; FMM owns its own entity/model lifecycle.
- FMM reload invalidates old model instances; Dead Streets reattaches on the reload-complete event.
- Dead Streets shutdown unhides the real zombie body for current viewers and leaves the persistent zombie entity intact.
- A connected client is still required to visually verify UV orientation, proportions, animation quality, damage feedback, and the resource-pack delivery path.
