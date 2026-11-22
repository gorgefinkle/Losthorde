# Dead Streets R05 — Independent source audit (2026-11-22)

**Verdict: HOLD gameplay acceptance; do not enable horde migration or Metropolis population activation on valuable worlds.** This audit does not claim connected-player validation.

Reviewed current `main` at `eb8a251925fb0c34a9785d437d49c87d9bb4e88b`. Audited source files match Git blob SHAs, including `HordeManager.java`, `PopulationManager.java`, and `SpawnerManager.java`.

## Blocking functional findings

1. **Virtual hordes stop before warning range.** `HordeManager.tick()` refuses movement whenever `ObservationGuard.protectedPoint()` returns true (HordeManager ~174). With view distance 10, the guard protects a 224-block radius (`ObservationGuard` ~56), while the alert starts at 150 blocks. Independent JDK 25 reproduction with a stationary player at (0,0), an 85-walker horde at (350,0), and heading toward the player advanced for 600 seconds: it stopped at x=224, outside warning range.
2. **The first physical walker can strand an entire horde.** `HordeState.advance()` ~62 stops the virtual position whenever any member is physical; `HordeManager.steerPhysical()` ~339 only gives local pathfinding goals within 192 blocks of a player. `HordeManager.materialize()` can spawn in unobserved terrain well outside that radius. Reproduction: first member materialized at x=350; after 600 simulated seconds, virtual x still 350, 84 pending, no plugin steering possible at that range.
3. **Metropolis first-chunk-load activation has weaker visibility protection.** `PopulationManager.activate(chunk,true)` ~137-138 checks `getPlayersSeeingChunk()`, but skips the wider `isObserved()` exclusion used by later attempts and by R05 hordes. Packet/entity timing and actual no-pop-in were not verified. Do not treat either path as a proven guarantee.
4. **Population queue cap can be bypassed under chunk churn.** `PopulationManager.onChunkUnload()` ~80 removes a key from `pendingSet` without removing the queued occurrence from `pending`. A reload can append that same chunk again; capacity is checked only using `pendingSet.size()` (~88). A sustained generation/unload/reload workload can accumulate stale queue entries beyond the configured cap.

## Important multiplayer and long-session risks

5. **No global cap on persistent zombie entities.** Every zombie is configured persistent and is not despawned at distance (`DeadStreetsPlugin.markWalker` and `onSpawn`). The spawner's 16-walker cap counts only zombies near that particular spawner within a 12-block radius (`SpawnerManager.countZombies()` ~177). Walkers that leave its radius can be replaced indefinitely. Per-second spawn budgets limit throughput, not total active/saved population. A busy server can accumulate large numbers over time.
6. **Horde slots can saturate indefinitely.** The default 12-per-world max checks all surviving horde records (`countWorld` ~214). There is no aging/retirement/merging policy for groups that wandered elsewhere or have all physical members unloaded, so ambient horde creation can stop.
7. **Crash-atomic handoff is unproven.** `materialize()` decrements the virtual count and saves a sidecar after entity creation; Minecraft chunk/entity storage commits independently. A hard crash during the handoff can lose or duplicate populations; the existing graceful-restart tests do not cover this.
8. **Spawner detection may miss late schematic placement.** Registration happens at `ChunkLoadEvent`, player `BlockPlaceEvent`, and plugin startup scanning. A spawner block introduced later by Metropolis schematic paste in an already-loaded chunk has no explicit registration hook until a new load.
9. **Initial Metropolis marker generation is not tested end-to-end.** Markers written after the first chunk-load scan may not be reconsidered until another chunk load. Current importer is disabled by default.
10. **No road-following or complex-terrain migration.** Virtual movement is directional and ignores roads/topography, while local physical steering chooses nearby terrain. This remains an incomplete feature rather than a regression.

## Minor presentation mismatches

- Horde warning subtitle currently uses `~85 Walkers | Agitated | HIGH`, not the agreed literal labels `Est. Population` and `Temperament`.
- The instantaneous spawner wave is polled once per second and can be delayed by the shared spawn budget, so it is not guaranteed to appear in the exact activation tick.

## Verification repeated during audit

- Java 25 core suite passed: 239 + 23 + 46 = **308 assertions**.
- Reviewed GitHub source matches local R05 source by Git blob hashes.
- The *packaged* `DeadStreets_R05.jar` hash differs from the older R05 server-smoke copy; the only differing JAR entry is `HordeManager.class`.
- A fresh offline Paper 26.2-129 test using **the exact packaged R05 JAR** reached ready state, executed `/deadstreets status`, and shut down with exit code 0. External Mojang/Paper update DNS failures were unrelated. **No connected player or real horde approach was tested.**
- Two stand-alone core reproducibility checks demonstrate blockers 1 and 2 deterministically.

## Fix / acceptance order

1. Unify a coherent physical/virtual arrival state machine that allows the horde to reach the player and warning range without popping walkers into view.
2. Unify initial-population and horde observation rules; design fail-closed pre-delivery activation behavior and confirm with actual clients.
3. Bound *both* the population queue and total persistent zombie population across busy multiplayer worlds.
4. Make horde slots reusable without arbitrary visible despawns; address missing/unloaded physical members.
5. Validate the full Metropolis marker-export lifecycle and spawner registration timing.
6. Add targeted tests for horde arrival, teleport/elytra, 2+ players, unloaded/reloaded entities, and hard restart; test road bias separately.

**No gameplay code was changed by this audit.**
