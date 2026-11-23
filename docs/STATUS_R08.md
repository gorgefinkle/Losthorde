# Dead Streets R08 — active development checkpoint

**Not ready for player acceptance testing.** The current branch now passes the repository's Java 25 core harness and Paper 26.2 Maven package workflow, but runtime and integration acceptance remain incomplete.

## Implemented in Dead Streets source/config

- Configurable natural and chunk-generation mob pass-through policy for skeletons, creepers, witches, spiders, animals, villagers/traders, ambient mobs, and other hostile/creature categories. Existing mobs are not bulk-deleted; intentional spawn reasons such as spawn eggs are outside this policy.
- Vanilla spawner modes:
  - `ALLOW_ALL`: leave vanilla spawners untouched.
  - `CONVERT_TO_ZOMBIES`: convert encountered loaded, generated, and newly player-placed spawners while recording their original mob type. When proximity waves are enabled, the plugin suppresses native SPAWNER events and owns the immediate first wave plus randomized 30–60-second waves. With proximity waves disabled, converted vanilla zombie spawners operate normally.
  - Returning to `ALLOW_ALL` restores recorded original types when those spawners are processed again.
- Larger shared virtual horde defaults, virtual migration after partial materialization, eight-way compass alerts, nearest-physical-member direction selection, and approximate ETA/population/temperament display.
- Initial Blood Moon event system: configurable seven-day cadence, daytime warning, a dedicated red boss bar, chat announcements, sleep prevention during the event, and three waves of two large shared migrating horde groups. The boss bar now counts down remaining event duration (default 10 minutes), and the event ends only when that duration expires; the timer survives midnight and restart. Failed wave placement retries rather than consuming a wave.
- `/deadstreets horde tp [uuid]`: diagnostic teleport to an active horde, using an already-generated chunk only and searching for safe ground.
- Display coexistence policy: Dead Streets does not set or replace player scoreboards, write to the action bar, or send titles. Horde and Blood Moon notices use chat; the Blood Moon timer uses its own unkeyed Bukkit BossBar instance (so it remains separate from other plugins' bars). Spawner countdowns are world-space TextDisplay entities, tagged with a Dead Streets PDC key and removed when their spawner cycle ends or the plugin shuts down. There is no hard or soft dependency on LOSTLootz and no direct calls into its internals.
- Scraggler assignment to a configurable subset of managed walkers. Scragglers receive local wander goals, investigate a fraction of recent sprint/door/block-break/explosion sounds, persist as normal entities, and can join nearby hordes without being respawned or teleported.
- Optional Metropolis BUILD population consumer with deterministic per-building rolls, interior-candidate validation, visibility checks, a durable write-ahead admission journal, bounded chunk retries, and a global walker budget.
- The consumer now accepts the agreed schema-v1 per-world JSONL file at `deadstreets-build-flags-v1/<world-uuid>/buildings.jsonl`, including required world UUID and `worldSeed`, and retains compatibility with earlier regional TSV shards. It reads the durable sidecar from the sibling `plugins/Metropolis` data folder; Metropolis does not need to be installed or enabled when Dead Streets consumes an already-exported file.
- Proximity-spawner checkpoints now include queued-but-not-yet-created wave counts and are written when a wave is queued or admitted, reducing restart loss/duplication windows.
- The export contract is documented in `docs/DEAD_STREETS_POPULATION_EXPORT.md`. Metropolis implementation is explicitly out of scope for this branch; no Metropolis source or branches are being modified.

## Deliberately disabled / gated

- `population.activation.enabled: false` remains the default until the completed-building export and the real Paper chunk visibility timing are verified with connected clients.
- Hordes are enabled in the development config but remain experimental; use only a disposable backup world.
- No claim is made that physical walkers have production-quality navigation, combat, or no-pop-in under real client packet timing.

## Build and test evidence

1. GitHub Actions run [#85](https://github.com/gorgefinkle/Losthorde/actions/runs/45253550447) completed successfully for commit `2bbf5fd`: Java 25 core test harness passed and Paper 26.2 Maven package build passed.
2. This verifies compilation, packaging, and the existing pure-core harness only. It does not verify live Paper entity behavior or all gameplay paths.
3. The JSONL parser/consumer compiles, but there has not yet been a real Metropolis exporter-to-consumer test against a generated Paper world.
4. Connected-client no-visible-pop-in, multiplayer horde behavior, physical pathfinding/combat, spawner mode transitions, long-run MSPT, restart persistence, and power-loss recovery remain unverified.
5. Scraggler persistence currently relies on ordinary Paper entity persistence plus PDC identity; there is no separate durable virtual scraggler ledger proving exact no-duplication semantics across abnormal shutdowns.

## Next acceptance gate

Do not ask for an in-game test yet. Next verify the JSONL parser/consumer with a real exporter-produced file, run the real exporter-to-consumer test on a disposable generated world, and continue the runtime audits for horde movement, visibility, spawner transitions, and persistence. Only after those gates should the package be handed over for player testing.
