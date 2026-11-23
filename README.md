# Losthorde / Dead Streets

Experimental Paper 26.2 / Java 25 zombie population and migration plugin, with an optional file-based Metropolis BUILD population integration.

**R08 is still a development checkpoint, not a test-ready release.** It contains new code for persistent scragglers, shared migrating hordes, vanilla spawn policy, and building-owned populations, but there is no successful Java/Paper build or connected-client acceptance run yet. Do not install it in a valuable world.

## Current development scope

- Persistent daylight-immune walkers with a durable global admission budget.
- Configurable natural mob pass-through rates, including a low rate for other hostile mobs.
- Vanilla spawner policy: `ALLOW_ALL` or `CONVERT_TO_ZOMBIES`. When the Dead Streets proximity-wave controller is enabled, converted spawners use its immediate first wave, randomized 30–60-second follow-up waves, nearby cap, and floating countdown. If that controller is disabled, converted vanilla spawners operate as ordinary zombie spawners.
- Persistent shared virtual hordes with larger configured populations, sound-sensitive migration, physical emergence only in unobserved chunks, and eight-way per-player arrival directions.
- Scragglers: a configurable subset of managed walkers gets independent local wander goals and may join a nearby shared horde. The wander manager does not spawn entities or generate chunks.
- Optional Metropolis BUILD population records. Metropolis writes a file-only sidecar after a BUILD has fully pasted; Dead Streets reads its district, footprint, and candidate interior columns, then validates safe indoor standing positions before spawning. Population activation remains disabled by default until the no-pop-in gate is passed.

## Repositories

- Dead Streets: this repository.
- Metropolis exporter integration: the separate `deadstreets-building-population-export` development branch in the Metropolis repository. No Metropolis assets or source are copied into this repository.

## Build and verification

Run `bash test_core.sh` with Java 25. For Paper packaging, run `mvn -B -ntp package` with access to the Paper 26.2 Maven repository. The current GitHub Actions attempts have failed before any job steps started, so they provide no compile or test evidence.

Even after a successful build, connected-client visibility, real zombie pathfinding/combat, real Metropolis export-to-consumer activation, spawner conversion, long-run MSPT, and restart/power-loss behavior remain acceptance gates.
