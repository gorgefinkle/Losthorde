# R08 acceptance plan — do not test until build gate passes

This is the planned disposable-world acceptance run after a successful Java 25 core harness and Paper 26.2 package build. It is not a request to test the current branch.

## 1. Build gate

- Run `bash test_core.sh` on JDK 25.
- Run `mvn -B -ntp clean package` against Paper 26.2.
- Confirm plugin loads and disables cleanly on a fresh Paper 26.2 server.
- Confirm no Metropolis dependency is required for the ordinary horde/spawner/scraggler features.

## 2. Metropolis export without Dead Streets running

- Enable `deadstreets.exportBuildPopulationFlags: true` in Metropolis config.
- Generate a disposable world with Metropolis while Dead Streets is absent/disabled.
- Confirm `plugins/Metropolis/deadstreets-build-flags-v1/<world-uuid>/` contains BUILD rows.
- Check rows include only supported actual district contexts, stable IDs, inclusive footprint/height bounds, and candidate interior columns.
- Confirm sliced/overflow buildings emit exactly one row after all slices finish; placeholders and non-BUILD parcels emit none.
- Use `/deadstreets flags` after installing Dead Streets to check nearby markers, candidate count, and deterministic population estimate.

## 3. Building-owned populations

- On a backup copy, set `population.activation.enabled: true`.
- Approach a generated block slowly, then leave and return. No zombie may appear in visible chunks or suddenly be added to an empty building.
- Verify walkers are admitted only at validated indoor floor positions with a nearby roof; empty candidate lists must not fall back to outdoor spawning.
- Verify each BUILD has a stable population across restarts and chunk unload/reload; no duplicate walkers.
- Verify observed chunks retry only after the observation guard clears.

## 4. Horde and scraggler behavior

- Use `/deadstreets horde spawn 180 agitated`; verify virtual population continues migrating after the first physical members materialize.
- Verify no chunk generation, no visible pop-in, no stuck virtual remainder, and physical walkers can navigate toward the player.
- Verify warning includes eight-way relative direction, estimated ETA, approximate population, temperament, and danger color.
- Verify `/deadstreets horde tp` and `/deadstreets horde tp <uuid>` only target existing hordes and never generate terrain.
- Observe independent scragglers leaving building interiors, wandering loaded terrain, and joining a nearby shared horde without spawning a duplicate entity.

## 5. Vanilla mobs and spawners

- Confirm natural and chunk-generation non-zombie mob pass-through rates match config.
- `ALLOW_ALL`: existing, generated, and player-placed vanilla spawners preserve their normal mob types and behavior.
- `CONVERT_TO_ZOMBIES`: existing/generated/player-placed spawners become zombie spawners. With proximity waves enabled, vanilla SPAWNER events are suppressed to avoid duplicate waves; first wave is immediate, later waves randomized 30–60 seconds, and countdown is visible.
- Disable proximity waves while conversion mode remains active: converted vanilla spawners should operate normally.
- Switch back to `ALLOW_ALL`: original mob types should be restored for spawners converted by this implementation.
- Break converted spawners and confirm no orphaned displays or duplicate state.

## 6. Persistence and load

- Restart with loaded and unloaded chunks; confirm horde populations, physical membership, scraggler identity, spawner cycles, and building journals do not duplicate.
- Run a long session with multiple players, observe MSPT/TPS, memory, chunk tickets, and entity count.
- Test player logout/rejoin, death, chunk unload/reload, and server restart during active hordes.
- Power-loss durability remains a separate final gate.
