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
