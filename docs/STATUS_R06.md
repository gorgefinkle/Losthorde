# Dead Streets R06 — R05 audit repair checkpoint

**Status: experimental. No real player/client or large-multiplayer gameplay acceptance test has been completed.**
Target: Paper 26.2-129 / Java 25. No Metropolis source, schematics, or private integration patch are included.

## Fixed in code

1. **Horde arrival deadlock:** virtual horde advances to the observed-area boundary; activation is allowed only in an unobserved chunk at that boundary. Physical members get local pathfinding goals up to 1024 blocks away and a short bounded look-ahead corridor of already-generated chunks. A 10-chunk view distance now has a viable path from virtual group to a 150-block warning threshold. This is a policy simulation, not proof of actual pathfinding.
2. **One shared visibility decision:** both initial Metropolis population activation and horde materialization use the same 12-second, all-player ObservationGuard. No chunk-load-event exception to the guard. Transport/packet ordering remains unproven with clients; both systems stay off by default.
3. **Bounded queues:** an unloaded chunk no longer removes only the dedupe key while leaving a stale queue element. Both the primary queue and delayed retries have explicit caps. Late markers are rechecked at a bounded interval while chunks are loaded and unobserved.
4. **Global persistent-walker admission cap:** one durable, fail-closed quota for new spawner, population and horde zombies, default 1800. A reservation is recorded before creation; regular death releases a slot. No existing zombie is killed to enforce a new cap. Already-unloaded legacy R05 entities cannot be counted precisely at migration time.
5. **Horde slot reuse:** fully physical hordes no longer consume new-virtual-group slots. Virtual pending portions can retire after a long period with no nearby players; physical entities are not removed. Entirely virtual abandoned records are removed.
6. **Crash duplicate prevention:** horde pending count is written ahead of physical creation, and Metropolis per-build/per-chunk population ordinals use a forced-to-disk append-only journal independent of Paper chunk/entity storage. Crash during a handoff can lose a slot/walker, but should not replay an already journaled ordinal. Full cross-file/OS-power-failure atomicity is not guaranteed.
7. **Late Metropolis spawners:** bounded, recurring scan of already-loaded nearby chunks catches zombie spawners pasted after their initial chunk-load event. No chunks are generated or force-loaded for scanning.
8. **Display:** warning subtitle now explicitly identifies estimated population and temperament; danger color still comes from both.
9. **Noise:** one event sets horde investigation state but bounds extra direct physical navigation to 32 walkers per event.

## Verified

- Java 25 core harness: 239 + 23 + 46 + 11 = **319 assertions**.
- Genuine Paper 26.2 API: all Java classes compile, single JAR produced, server startup/status/shutdown succeeded in isolated world.
- R06 synthetic late-Metropolis-marker test: when a flag was appended *after server startup*, 20 walkers were activated and recorded in the journal; global quota went from 24 to 44.
- Restarting and re-adding the same marker created **zero** additional zombies: the journal remained at one slice and the quota remained at 44.
- No actual Metropolis code/asset files were included in this package; synthetic test used a separate fake Metropolis plugin on a disposable world.

## Remaining acceptance gates / limitations

- **Connected clients required** to prove no-visible-pop-in during chunk first delivery, fast elytra travel, teleports, and opposing multiplayer approaches. Observation exclusion is conservative but cannot guarantee client state without testing it.
- No genuine player-observed horde crossing, combat, pathfinding around complex Metropolis bridges/forests, or sound perception has been verified. The 11 R06 arrival checks use a modeled physical walking rate, not a real pathfinder.
- **Road-aware migration is not implemented.** Local walkers use actual vanilla Paper pathfinding; virtual movement follows a heading. Integration of Metropolis roads should be versioned privately.
- The optional real Metropolis exporter-to-Dead-Streets marker workflow has not been verified end-to-end; marker consumer was tested only with synthetic data.
- 30 connected multiplayer clients, long-session MSPT profiling, and process-kill/power-loss fault-injection remain untested. Write-ahead journaling reduces duplicate creation risk but cannot make Minecraft region writes atomic.
- The initial quota migration counts loaded pre-R06 tagged walkers; persistent old zombies not loaded during upgrade are not provably captured in its total. Upgrade old test worlds cautiously.
- An immediate spawner first wave is emitted on the manager's one-second tick and may wait for the global budget; not necessarily the exact movement packet tick.

## Default safety posture

`hordes.enabled: false`, `population.activation.enabled: false`. Spawner waves remain enabled. Use only a disposable backed-up test world to enable experimental features.
