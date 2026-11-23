# Losthorde / Dead Streets

Experimental Paper 26.2 / Java 25 zombie population and migration plugin, with an optional file-based Metropolis BUILD population integration.

**R11 is an active development checkpoint, not yet a test-ready release.** It adds DisplayMotion-rendered special infected, original editable Blockbench models, the Popper death burst, and a stricter non-zombie hostile spawn policy. The Paper 26.2 + DisplayMotion 0.4.0 headless smoke test now loads all four models and exercises model spawn, follow, hitbox registration, click callbacks, and every animation clip. Connected-client appearance and real melee feel still need in-game verification. Do not install it in a valuable world.

## Required server dependency

Dead Streets R11 requires **[DisplayMotion 0.4.0](https://github.com/Jan1k1/displaymotion/releases/tag/v0.4.0)**. Install `DisplayMotion-0.4.0.jar` in the server's `plugins` folder alongside Dead Streets. DisplayMotion includes PacketEvents; do not install a separate PacketEvents jar just for this integration. Dead Streets intentionally has no vanilla-model fallback if DisplayMotion is missing.

The four original, editable Blockbench models live in `src/main/resources/models/` (see [docs/MODELS.md](docs/MODELS.md) for the editing and animation contract): Husk Runner, Drowned Popper, low-profile Crawler, and three-block Hulk. DisplayMotion converts their solid-color geometry to vanilla blocks, so clients need no resource pack or mod and existing block texture packs can reskin those materials. The model renderer hides each special infected's vanilla body and uses a model hitbox to route melee hits back to the server-side zombie.

## Current development scope

- Persistent daylight-immune walkers with a durable global admission budget.
- Configurable natural mob pass-through rates; non-zombie hostile mobs are blocked for all spawn reasons except admin commands and player spawn eggs.
- Vanilla spawner policy: `ALLOW_ALL` or `CONVERT_TO_ZOMBIES`. When the Dead Streets proximity-wave controller is enabled, converted spawners use its immediate first wave, randomized 30–60-second follow-up waves, nearby cap, and floating countdown. If that controller is disabled, converted vanilla spawners operate as ordinary zombie spawners.
- Persistent shared virtual hordes with larger configured populations, sound-sensitive migration, physical emergence only in unobserved chunks, and eight-way per-player arrival directions, estimated ETA, population and temperament.
- Scragglers: a configurable subset of managed walkers gets independent local wander goals and may join a nearby shared horde. The wander manager does not spawn entities or generate chunks.
- Blood Moon: one scheduled night every seven in-game days, with full-screen warning/wave notices and a configurable duration capped at sunrise. Choose its timer presentation with `/deadstreets bloodmoon display bossbar` or `/deadstreets bloodmoon display scoreboard`; the default is its own Bukkit BossBar.
- Optional Metropolis BUILD population records. Metropolis writes a file-only sidecar after a BUILD has fully pasted; Dead Streets reads its district, footprint, and candidate interior columns, then validates safe indoor standing positions before spawning. Population activation remains disabled by default until the no-pop-in gate is passed.

## Repositories

- Dead Streets: this repository.
- Metropolis exporter integration: the separate `deadstreets-building-population-export` development branch in the Metropolis repository. No Metropolis assets or source are copied into this repository.

## Special infected\n\n- Husk Runner: fast, forward-leaning model with idle, walk, run, attack and death animations.\n- Drowned Popper: swollen model with an inflate/burst animation; death causes a modest-radius, non-block-damaging burst and releases 1–4 Crawlers.\n- Crawler: low horizontal body with crawl/lunge animations.\n- Hulk: broad, asymmetrical three-block brute with walk, attack and roar animations.\n\nOnly scragglers and physical horde members are eligible for random subtype assignment. Popper offspring are guaranteed Crawlers and cannot chain into more Poppers.\n\n## Build and verification

Run `bash test_core.sh` with Java 25. For Paper packaging, run `mvn -B -ntp package` with access to the Paper 26.2 and JitPack Maven repositories. The current GitHub Actions attempts have failed before any job steps started, so they provide no compile or test evidence.

Even after a successful build, connected-client visibility, real zombie pathfinding/combat, real Metropolis export-to-consumer activation, spawner conversion, long-run MSPT, and restart/power-loss behavior remain acceptance gates.
