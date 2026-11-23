# Dead Streets R08 — active development checkpoint

**Not ready for installation or player testing.** This branch contains a partial implementation of the next scope. No successful build or runtime test is claimed.

## Implemented in source/config so far

- Added `vanilla-spawners.mode`:
  - `ALLOW_ALL` leaves vanilla spawners alone.
  - `CONVERT_TO_ZOMBIES` changes loaded and newly placed spawners to zombie spawners.
  - Conversion is independent of the Dead Streets proximity-wave feature toggle.
- Added configurable natural-spawn pass-through chances for skeleton variants, creepers, witches, spiders, animals, villagers/traders, and ambient mobs. These apply only to NATURAL spawn reasons; player/admin/plugin placements are not blanket-cancelled.
- Raised the configured ambient horde population range and materialization rates, shortened the ambient interval, enabled natural horde creation by default, and shortened the initial approach distance.
- Fixed the virtual horde remainder so it continues migrating after some members have materialized; added a core regression assertion for this case.
- Updated Maven/plugin version metadata to R08.

## Not implemented yet — do not treat config as proof

- Independent persistent scraggler simulation and safe physical emergence.
- Full building-interior population assignment and real Metropolis exporter-to-consumer verification.
- Horde teleport command.
- End-to-end audit of horde population accounting, physical/virtual convergence, and no-visible-pop-in under connected clients.
- A complete configuration migration/upgrade test from R06.

## Verification

GitHub Actions workflow runs were created by pushes but failed before any job steps ran (runner ID 0, no steps/logs available). Therefore this does **not** establish a Java compile or test failure, but equally it provides no successful build evidence. The Java 25 harness and Paper 26.2 package still need to be run in a functioning build environment.

## Acceptance gate

Do not install this branch as a test build yet. Complete the remaining systems, run Java 25 core tests and Paper 26.2 Maven packaging, then validate spawner conversion in both modes and natural spawn filtering on a disposable server. Connected-client visibility and genuine Metropolis integration remain separate acceptance tests.


## R08 follow-up implementation (still unverified)
- Added independent scraggler wandering for a configurable share of managed walkers; the wander manager does not spawn entities or generate chunks.
- Added /deadstreets horde tp [uuid], which only loads an already-generated anchor chunk and searches for safe ground.
- Added eight-way per-player approach direction, preferring the nearest tracked physical member over the virtual anchor.
- Metropolis integration is on the separate Metropolis branch deadstreets-building-population-export. Its exporter emits v2 records after completed BUILD paste/decay, including sampled indoor standing coordinates. The consumer validates them before spawning.
- Added v2 marker parsing and core tests for explicit interior-coordinate assignment. Real exporter-to-consumer operation, Paper runtime visibility ordering, and pathfinding still require end-to-end validation.
- Converted vanilla spawners use the Dead Streets proximity-wave controller without also emitting vanilla SPAWNER-reason zombies. Other natural hostile monsters receive a separate low pass-through rate.
