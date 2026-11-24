# Dead Streets infected visuals

Dead Streets R15 uses BetterModel 3.7.1 for the special Crawler and Hulk visuals. BetterModel is a soft dependency: the plugin still starts without it and leaves the native zombie visible as a fallback. With BetterModel present, the plugin must preserve the source entity spawn lifecycle and let BetterModel's own hide option suppress the vanilla body; it must not hide the source through Bukkit hideEntity.

| Infected | Visual | Gameplay entity |
|---|---|---|
| Crawler | Horizontal, animated Blockbench zombie; crawl animation | Native Zombie with fixed swimming-pose dimensions |
| Hulk | Broad, thick-framed zombie; heavy_walk animation | Native Zombie scaled to approximately three blocks |
| Runner | Existing fast infected implementation | Existing server-side entity |
| Popper | Existing Popper implementation and death burst | Native zombie-family entity; releases 1–4 Crawlers |

## Assets and installation

The editable Blockbench sources are embedded in the Dead Streets JAR:

- bettermodel/models/deadstreets_crawler.bbmodel
- bettermodel/models/deadstreets_hulk.bbmodel

When BetterModel is enabled, Dead Streets copies these assets into plugins/BetterModel/models/ and requests /bettermodel reload. BetterModel builds and publishes its own resource pack. Players must accept/download that pack to see the custom models. No separate client mod is required.

The model geometry uses a Minecraft-style humanoid silhouette and zombie palette (green skin, blue-green shirt, purple trousers), rather than concrete or display-block constructions. The source texture is embedded in each Blockbench file.

## Entity and model lifecycle

The Bukkit Zombie remains authoritative for AI, navigation, targeting, collision, health, damage, persistence, and death. BetterModel supplies only the visual model and animation. The Crawler keeps the compact fixed swimming-pose dimensions for its gameplay hitbox; the custom model provides the horizontal crawling silhouette that the vanilla renderer did not provide.

Model trackers are reacquired after the BetterModel asset reload. Dead Streets discards its tracker references on entity death and chunk unload, and restores the original entity visibility on plugin shutdown. If BetterModel is absent or the model asset has not loaded, the vanilla zombie remains visible instead of becoming invisible.

## Visual verification

The CI Paper smoke test validates server-side entity state and verifies that both .bbmodel assets are packaged. It cannot validate client-side UV orientation, proportions, animation playback, or resource-pack delivery. Those still require a connected client with the BetterModel pack accepted.
