# Dead Streets R08 — active development checkpoint

**Not ready for installation or player testing.** The branch contains substantial new implementation, but no successful Java 25 harness run, Paper 26.2 Maven build, or connected-client acceptance test is available.

## Implemented in source/config

- Configurable natural mob pass-through policy for skeletons, creepers, witches, spiders, animals, villagers/traders, ambient mobs, and other hostile mobs.
- Vanilla spawner modes:
  - `ALLOW_ALL`: leave vanilla spawners alone.
  - `CONVERT_TO_ZOMBIES`: convert loaded and newly placed spawners. When the Dead Streets proximity controller is enabled, its immediate wave and randomized 30–60-second waves own spawning and vanilla SPAWNER events are suppressed to prevent double waves. If the proximity controller is disabled, the converted vanilla spawner runs normally.
- Larger shared virtual horde population defaults, continued virtual migration after partial materialization, eight-way compass alerts relative to each player, and nearest-physical-member direction selection.
- `/deadstreets horde tp [uuid]`: diagnostic teleport to an active horde anchor, loading only an already-generated chunk and searching for safe ground.
- Scraggler assignment to a configurable subset of managed walkers. Scragglers get occasional local wander goals using loaded terrain only, persist as normal entities, and can join a nearby horde as an already-physical member.
- Metropolis marker v2 parsing, deterministic per-build population rolls, explicit candidate-column assignment, safe indoor floor/roof validation, write-ahead admission journal, chunk retry after observation protection, and regression harness cases.
- Separate Metropolis exporter branch `deadstreets-building-population-export`: writes a sidecar only after a BUILD's complete paste/decay lifecycle; includes world identity/seed, stable build ID, actual district type, schematic footprint, vertical bounds, and candidate interior columns. It does not spawn entities, mutate blocks, request chunks, or require Dead Streets to be running during generation.

## Deliberately disabled / gated

- `population.activation.enabled: false` remains the default until chunk visibility timing and the actual Metropolis-to-Dead-Streets path are verified with connected clients.
- Hordes are enabled in the development config but remain experimental; use only a disposable backup world.
- No claim is made that physical walkers have production-quality navigation, combat, sound response, or no-pop-in under real client packet timing.

## Known acceptance gaps

1. Java 25 core harness has not been run successfully in this environment.
2. Paper 26.2 Maven packaging has not completed successfully.
3. The GitHub Actions jobs returned failure before any steps were reported (no runner steps/logs); this is not evidence of a source compile failure, but it is also not a successful build.
4. The Metropolis exporter and Dead Streets consumer have not been exercised together in a freshly generated world.
5. Connected-client no-visible-pop-in, multi-player horde behavior, physical pathfinding/combat, spawner mode transitions, long-run MSPT, restart persistence, and power-loss recovery remain unverified.

## Next acceptance gate

Do not ask for an in-game test yet. First obtain a successful Java 25 core harness run and Paper 26.2 package build, then run the real exporter-to-consumer test on a disposable generated world. Only after those gates should the package be handed over for player testing.
