# R02 checkpoint status

- **Compiled** standalone Dead Streets Paper plugin using Oracle Java 25 and locally extracted `paper-api-26.2.build.129-stable.jar` supplied through the Paper server bundle.
- **Compiled** modified Metropolis R14_5D classes against Paper + original Metropolis dependencies during local development; no on-server validation.
- **23 core assertions passed** in the standalone harness. These are core model assertions, not multiplayer or runtime integration tests.
- Runtime Metropolis integration currently supports a **read-only** marker probe only. Marker consumption and real spawn are NOT implemented.
- Metropolis export is opt-in and disabled by default. Metadata is written as per-region TSV; duplicate records across generator retries are possible and **must be deduplicated** by the consumer.
- Standalone plugin R02 JAR includes `dev.deadstreets.core.MetropolisFlag` and `dev.deadstreets.paper.MetropolisFlagProbe` (rebuilt after adding them).
- Maven dependency resolution and Paper server startup in this repository CI are not yet proven.

## Remaining engineering gates

1. Reproducible Maven/GitHub Actions build.
2. Full Paper runtime smoke test for spawners, ticking and sunlight.
3. Metropolis marker activation before chunk visibility, including rapid Elytra and multiplayer approaches.
4. Hordes and their virtual/physical transitions with no duplication.
5. Road bias, sound behavior, arrival warnings, and multi-player TPS profiling.

No server tests are claimed.
