# Dead Streets R05 — bounded horde arrival checkpoint

Target: Paper 26.2 build 129, Java 25. **Experimental; do not install on a valuable world.**

## Improvements over R04

- A world-wide ObservationGuard remembers each player's recent observed corridor for 12 seconds. It checks their view distance, current position and a bounded 40-tick velocity projection, and aggregates all players (including spectators) into one conservative exclusion zone.
- The same exclusion zone now applies to ordinary loaded chunks and asynchronous chunk-load callbacks. R04's shorter 80-block fresh-load exception was removed.
- Teleports preserve the previous observed area even if they happen within the same second. The destination receives immediate protection.
- Optional **bounded prefetch** loads at most 2 already-generated chunks per second outside all observed areas; it never generates new terrain and holds at most 8 global chunk tickets for 30 seconds. It is a bounded approximation, not a guarantee about client packet ordering. Without this, typical server view distances can make horde appearances impossible under the no-pop-in rule.
- Local physical walkers can receive low-frequency forward movement goals along their horde's direction or a sound investigation direction. No long-range forced pathfinder routing or player homing.
- Once physical walkers exist, the remaining virtual population stops moving away from them. Virtual movement resumes only when the physical subset has been killed.
- Arrival warnings can use proximity to actual tagged physical walkers, not only the virtual anchor. Warning approach threshold was reduced so slow walker movement can be detected.

## Verified in this development environment

- Pure Java 25 tests: R03 core 239 + horde state 23 + observation guard 46 = 308 checks, including overlapping 30-player observations and rapid travel.
- Genuine Paper 26.2 API compilation with the user's supplied API JAR and locally resolved runtime dependencies.
- Paper 26.2-129 offline-server startup: plugin enabled with `hordes.enabled: true` and `ambient-creation: false`, `/deadstreets status` executed, and shutdown completed normally. Network errors contacting Mojang and the Paper version service were unrelated.
- R04 persisted virtual horde lifecycle was previously tested; R05 modifies activation, not the virtual file schema.

## Still unverified and disabled by default

- Physical horde arrivals with a connected Minecraft client, elytra/teleport timing and multiple players seeing the same chunk.
- Whether Paper's chunk/entity callback ordering consistently keeps zombies invisible until first client delivery.
- Successful plugin-ticket handoff to ordinary player-owned chunks, Paper entity AI pathfinding across complex Metropolis roads, traffic, narrow bridges and buildings.
- High-player-count TPS and chunk-preparation cost; Java harness has deterministic 30-player visibility cases but **not** 30 connected players.
- Strict transactionality between zombie entity save and virtual pending-count state after a hard crash.
- Metropolis initial build marker end-to-end generation and road-aware migration.

## Defaults

`hordes.enabled: false`; `population.activation.enabled: false`. Turning on hordes enables `prefetch.enabled: true` (max 8 global tickets, 2 async requests per second, 30-second lease). Preexisting physical walkers remain persistent and chunk-owned; look-ahead tickets release normally after lease expiry.

## Developer acceptance targets

1. Client approaches one horde from an unobserved generated chunk: physical zombies enter view by walking, without pop-in.
2. Two clients observe opposite sides of one horde: no duplicate population or unexpected spawns in already-observed chunks.
3. Elytra and teleport tests with many players; inspect packet timing and loaded chunk tracking.
4. After restart, physical member count + pending count matches saved horde population.
5. High-load test with 30+ players and multiple worlds, monitoring average/max MSPT and look-ahead tickets.

Do not label R05 as multiplayer-proven based only on an offline startup and deterministic Java tests.
