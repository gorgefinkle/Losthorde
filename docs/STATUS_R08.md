# Dead Streets R08 — active development checkpoint

**Not ready for installation or player testing.** The current branch contains substantial implementation, but no successful Java 25 harness run, Paper 26.2 Maven package build, or connected-client acceptance test is available.

## Implemented in Dead Streets source/config

- Configurable natural and chunk-generation mob pass-through policy for skeletons, creepers, witches, spiders, animals, villagers/traders, ambient mobs, and other hostile/creature categories. Existing mobs are not bulk-deleted; intentional spawn reasons such as spawn eggs are outside this policy.
- Vanilla spawner modes:
  - `ALLOW_ALL`: leave vanilla spawners untouched.
  - `CONVERT_TO_ZOMBIES`: convert encountered loaded, generated, and newly player-placed spawners while recording their original mob type. When proximity waves are enabled, the plugin suppresses native SPAWNER events and owns the immediate first wave plus randomized 30–60-second waves. With proximity waves disabled, converted vanilla zombie spawners operate normally.
  - Returning to `ALLOW_ALL` restores recorded original types when those spawners are processed again.
- Larger shared virtual horde defaults, virtual migration after partial materialization, eight-way compass alerts, nearest-physical-member direction selection, and approximate ETA/population/temperament display.
- `/deadstreets horde tp [uuid]`: diagnostic teleport to an active horde, using an already-generated chunk only and searching for safe ground.
- Scraggler assignment to a configurable subset of managed walkers. Scragglers receive local wander goals, investigate a fraction of recent sprint/door/block-break/explosion sounds, persist as normal entities, and can join nearby hordes without being respawned or teleported.
- Optional Metropolis BUILD population consumer with deterministic per-building rolls, interior-candidate validation, visibility checks, a durable write-ahead admission journal, bounded chunk retries, and a global walker budget.
- The consumer now accepts the agreed schema-v1 per-world JSONL file at `deadstreets-build-flags-v1/<world-uuid>/buildings.jsonl`, including required world UUID and `worldSeed`, and retains compatibility with earlier regional TSV shards.
- The export contract is documented in `docs/DEAD_STREETS_POPULATION_EXPORT.md`. Metropolis implementation is explicitly out of scope for this branch; no Metropolis source or branches are being modified.

## Deliberately disabled / gated

- `population.activation.enabled: false` remains the default until the completed-building export and the real Paper chunk visibility timing are verified with connected clients.
- Hordes are enabled in the development config but remain experimental; use only a disposable backup world.
- No claim is made that physical walkers have production-quality navigation, combat, or no-pop-in under real client packet timing.

## Build and test evidence

1. A local JDK 21 mirror of the pure-core logic previously passed 335 focused assertions (254 core, 12 arrival, 23 lifecycle, 46 observation). This is a smoke check only, not the repository's Java 25 run.
2. Paper 26.2 Maven packaging has not completed successfully.
3. Recent GitHub Actions attempts fail before any workflow step executes. The latest job reports `runner_id: 0`, an empty runner name, and `steps: []`; GitHub provides no job logs. This indicates the job was never assigned a runner, so it is **not evidence of a source compile error and not a successful build**.
4. The earlier local exporter-to-parser stub smoke was not a real Metropolis/Paper world integration test. The current JSONL parser change has not yet been verified by a successful Java 25 Maven build.
5. Connected-client no-visible-pop-in, multi-player horde behavior, physical pathfinding/combat, spawner mode transitions, long-run MSPT, restart persistence, and power-loss recovery remain unverified.

## Next acceptance gate

Do not ask for an in-game test yet. First obtain a successful Java 25 core harness run and Paper 26.2 package build, verify the JSONL parser/consumer, and run the real exporter-to-consumer test on a disposable generated world. Only after those gates should the package be handed over for player testing.
