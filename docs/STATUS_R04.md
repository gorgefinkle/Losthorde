# Dead Streets R04 — virtual migrating horde checkpoint

**Experimental. Do not use on a valuable world.** Target: Paper 26.2 build 129, Java 25.

## Verified

- Genuine Paper API Java 25 compilation of all R04 classes.
- 239 R03 core checks + 21 R04 horde-state checks pass.
- Real Paper 26.2 server startup and shutdown with `hordes.enabled: true` and no online players; command `/deadstreets horde status` succeeds, logs show no plugin exception.
- Live server persistence smoke with one injected virtual group: Paper loaded a group of 85 Frenzied walkers; no physical zombies were created without players, the virtual anchor moved from X=500 to X≈512, and its record was saved cleanly on shutdown. This is a virtual-lifecycle test, **not** a real-client spawning test.
- Single versioned plugin JAR, full editable source and test harness included in release archive.

## New in R04

- Server-wide virtual horde records, saved to `plugins/DeadStreets/hordes-v1.txt` atomically.
- Hordes can be seeded in unobserved wilderness, and advance slowly while unloaded. Offline time is clamped; no instant travel after long outages.
- Optional ambient horde creation (one shared global cooldown per world; capped group count); not one horde per player.
- Activation only in already-loaded chunks with no viewers. Chunk-load path additionally protects nearby players; no forced chunk loads.
- Once materialized, zombies remain persistent physical entities. Virtual pending count decreases on materialization, living horde count decreases on death.
- Noise from mining, sprinting, doors and explosions changes virtual heading toward the sound. Nearby physical tagged walkers can path toward the disturbance.
- Automatic per-player, per-horde arrival warning with estimated population, temperament and population-weighted green/yellow/orange/red danger; optional zombie cue.
- Shared materialization budget across both periodic attempts and chunk-load attempts.

## Not yet verified or complete

- No real multiplayer/elytra visibility proof. Paper's chunk-load and entity-load callback timing requires an in-game test. Existing unobserved-chunk checks do not prove the no-pop-in invariant on all clients.
- No verified road-aware long-range migration yet; virtual headings are coarse. No Metropolis roadway graph dependency added.
- Physical walkers are **not** reabsorbed into the virtual horde when their chunks unload; they remain saved entities and consequently do not migrate through unloaded territory. This safe but limited behavior is intentional in R04.
- Metropolis exporter integration continues from R03: optional sidecar only; real generation end-to-end not yet verified.
- Crash between physical entity creation and durable chunk save can lose or diverge counts; exact crash-atomic handoff is not proved.
- No multi-player simulated movement, field of view, or actual horde-combat runtime test yet. Sound and pathfinding behavior compiled but is not gameplay-verified.

## Test configuration and commands

`hordes.enabled` is **false** by default. Enable only on a test server. Defaults: every 420s ambient check, 24–100 initial population, 12 concurrent groups/world, 0.75 blocks/s virtual travel, 8 new physical horde walkers/s.

- `/deadstreets status`
- `/deadstreets horde status`
- `/deadstreets horde spawn 85 agitated` (run as player; creates a *virtual* group 320–480 blocks away and does not force-load terrain)
- `/deadstreets testwarning 85 frenzied`

Spawner rules remain unchanged from R03: 16-block proximity, immediate 2–5 walkers, subsequent waves every 30–60s, 16 nearby cap and floating countdown.

## Acceptance gates before main-world testing

1. Player approaches virtual group normally: no sudden visible pop-in, warning triggered once per horde when approaching.
2. Two players approach from opposite directions: same horde, at most one warning per encounter per player, no duplicate activation.
3. Elytra fast travel, teleportation, simulation-distance/view-distance mismatch, late chunk/entity loading: defer rather than reveal zombies inside view.
4. Restart while a group is partly physical: no duplicate zombie creation; population and temperament preserved.
5. Stress distinct cities, multiple spectators and concurrent chunk loading while monitoring active zombies and MSPT.
