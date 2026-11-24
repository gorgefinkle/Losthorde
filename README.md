# Losthorde / Dead Streets

Experimental Paper 26.2 / Java 25 zombie population and migration plugin, with an optional file-based Metropolis BUILD population integration.

**R12 is an active test build, not a production release.** It replaces the old block-display renderer with FreeMinecraftModels (FMM) and ResourcePackManager (RSPM). All four special infected now use articulated Blockbench geometry with zombie-family pixel textures: Husk Runner, Drowned Popper, zombie-textured Crawler, and zombie-textured three-block Hulk. The server-side zombie remains responsible for AI, pathfinding, collision, health, damage, and persistence.

## Required server plugins

Install these three jars in `plugins/`:

1. [FreeMinecraftModels 2.16.0](https://modrinth.com/plugin/free-minecraft-models) — imports and renders the articulated models.
2. [ResourcePackManager 2.4.13](https://modrinth.com/plugin/resourcepackmanager) — merges and delivers the generated model pack.
3. Dead Streets R12.

No client mods are needed. You do **not** need to create, upload, or host a pack yourself, and you do not need to download any third-party FMM content packs for these embedded models. FMM's current first-run guide asks administrators to run `/fmm initialize`, choose Recommended Setup and approve the server; follow that setup if the installed FMM release prompts for it. FMM generates the model pack; RSPM serves it from the server when reachable and falls back to its free remote hosting when necessary. Players who accept the server resource-pack prompt see the custom models; players who decline or are still downloading keep seeing the real vanilla zombie body instead of an invisible infected. If self-hosting cannot be verified, RSPM's remote fallback uploads the merged resource-pack ZIP plus a random server UUID and pack SHA-1 to MagmaGuy's hosting service; it does not upload world or player data. Its published policy says it does not log client downloads and deletes remote files within 24 hours after shutdown. If you do not want any external upload, RSPM can be configured for self-hosting only, but the server's pack port must be reachable by clients.

FMM and RSPM are required dependencies in this build. DisplayMotion is no longer required.

## Infected model design

The editable source models live in `src/main/resources/fmm/`. During plugin load, Dead Streets stages them in FMM's active model directory before FMM's first import. It requests one FMM reload only if that staging could not be completed or the model IDs are missing. It listens for FMM reload completion and reattaches models to surviving zombies, so a model reload does not leave infected invisible.

- **Runner:** Husk-textured, forward-leaning runner; idle, run, attack, damage and death states.
- **Popper:** Drowned-textured swollen infected; idle, walk, attack, inflate, damage and burst-on-death states. Its death still applies modest non-block-damaging damage and releases 1–4 Crawlers.
- **Crawler:** Low, elongated zombie-textured body; crawl locomotion, lunge attack, articulated hurt reaction and death.
- **Hulk:** Broad, asymmetrical three-block zombie-textured brute; heavy walk, attack, roar, hurt reaction and death.

The model files contain only a tiny transparent import placeholder. After FMM generates its pack, Dead Streets rewrites the model texture references to the matching vanilla client textures (zombie, husk, drowned) and strips the placeholder PNGs before RSPM republishes the pack; Mojang texture files are not bundled in the plugin or pack. The FMM models define the visual size and their hitbox bones. The hidden zombie drivers stay at unit scale so the old R11 scale modifiers cannot make the models too large or too small. The Crawler's underlying zombie retains its fixed swimming pose for its low collision profile.

## Current development scope

- Persistent daylight-immune walkers with a durable global admission budget.
- Configurable natural mob pass-through rates; non-zombie hostile mobs are blocked for all spawn reasons except admin commands and player spawn eggs.
- Vanilla spawner policy: `ALLOW_ALL` or `CONVERT_TO_ZOMBIES`. When the Dead Streets proximity-wave controller is enabled, converted spawners use its immediate first wave, randomized 30–60-second follow-up waves, nearby cap, and floating countdown.
- Persistent shared virtual hordes with sound-sensitive migration, physical emergence only in unobserved chunks, and eight-way per-player arrival directions, estimated ETA, population and temperament.
- Scragglers: a configurable subset of managed walkers gets independent local wander goals and may join a nearby shared horde.
- Blood Moon: one scheduled night every seven in-game days, with full-screen warning/wave notices and a configurable duration capped at sunrise.
- Optional Metropolis BUILD population records. Metropolis writes a file-only sidecar after a BUILD has fully pasted; Dead Streets reads its district, footprint, and candidate interior columns, then validates safe indoor standing positions.

## Repositories

- Dead Streets: this repository.
- Metropolis exporter integration: the separate `deadstreets-building-population-export` development branch in the Metropolis repository. No Metropolis assets or source are copied into this repository.

## Build and verification

Run `bash test_core.sh` with Java 25. Build with `mvn -B -ntp package`; the FMM 2.16.0 API resolves from MagmaGuy's Maven repository.

The R12 runtime smoke test starts Paper 26.2 with FMM and RSPM, imports all four models, attaches them to real zombie entities, and checks the idle, attack, damage and death animation states. A successful headless test does not replace connected-client appearance, melee feel, pack acceptance, or long-run server performance testing. Test on a disposable world first.
