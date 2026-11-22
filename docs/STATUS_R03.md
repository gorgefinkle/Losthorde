# Dead Streets R03 — verification and open work

## Source provenance

R03 continues the R02 Java 25 Paper 26.2 source checkpoint; no additional game assets are required. Tested against the provided `paper-26.2-129.jar` and the matching `paper-api-26.2.build.129-stable.jar`. Metropolis source reference: owner-provided `Metropolis_R14_5D_DoubleRoad_COMPLETE_DEV_PACKAGE.zip`.

## Tests actually performed

1. **Pure Java harness:** 239 assertions on randomized spawner cycles, cooldown/cap behavior, horde danger and temperament, deterministic marker population, negative-coordinate/multichunk allocation, and warning-gate logic.
2. **Genuine Paper 26.2-129 startup:** plugin loaded and enabled, server reached `Done`, then stopped normally. The test environment could not reach external Mojang/Paper version services, producing unrelated network warnings.
3. **Synthetic metadata activation:** A *separate test-only dummy Metropolis plugin* exported one RESIDENTIAL build flag with a fixed population of four. After `forceload add 0 0`, Dead Streets reported `slices=1, zombies=4` and the server ran without plugin exceptions.
4. **Restart persistence:** After saving and restarting with the same world and marker, Dead Streets reported `slices=0, zombies=0` for this session and exactly four zombie entities were present. No repeat activation was observed.

**Not tested:** visual no-pop-in with real players, actual Metropolis exporter end-to-end, many-player load, spawner physical gameplay, and automatic migrating hordes.

## Implementation notes

`PopulationPlan` deterministically assigns each of a build's rolled walkers to exactly one chunk inside its footprint. `PopulationManager` reads bounded TSV sidecar files from the Metropolis data directory, and records activation progress in the chunk's PersistentDataContainer *before* each attempted entity spawn. An unplaceable zombie is skipped rather than materializing inside a block or duplicating on retry. Loaded and already-observed chunks are never backfilled in sight; delayed activation waits until a later safe load. Startup reads no unloaded chunks and does not force-load any.

The importer uses a bounded queue, small synchronous TSV region cache, and per-tick allocation budget. The timing of ChunkLoadEvent vs. chunk packet delivery **still requires an instrumented server test with actual clients**, so activation defaults to OFF.

## Next development gates

- Test with the real patched Metropolis R14_5D exporter during pregeneration and reloading, checking writer duplication and missing flags.
- Test multiplayer first-load visibility and fast-travel no-pop-in before enabling marker activation by default.
- Build one shared migrating-horde registry and physical approach/retreat path; then instrument horde alerts and hearing reaction.
- Stress memory and TPS with players spread over many cities; don't force chunks for horde movement.
