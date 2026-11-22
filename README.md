# Losthorde / Dead Streets

Experimental Paper 26.2 / Java 25 zombie population and migration plugin with an optional Metropolis population marker integration.

**R04 is a test-only checkpoint**, not a completed Walking Dead-style simulation. See [R04 status](docs/STATUS_R04.md) for verified behavior, unfinished functionality, and acceptance gates.

Current systems:
- Persistent daylight-immune zombies and 16-block proximity spawners (instant wave, randomized 30–60-second waves and floating countdown).
- A deterministic per-build Metropolis marker reader, disabled by default pending no-pop-in verification.
- Persistent shared virtual hordes, sound-sensitive movement, loaded/unobserved-chunk activation, and colored arrival warnings. **Horde migration is opt-in and experimental.**

### Build

Run `bash test_core.sh` with Java 25. For Maven compile against the Paper 26.2 API dependency specified in `pom.xml` using `mvn package` (requires access to Paper's Maven repository).

For offline local compilation, use the `build_offline.sh` helper with `PAPER_API_JAR` set to a resolved Paper API JAR and `JAVA_HOME` set to Java 25. The script embeds only compiled plugin code and config, not the API dependency.

Do not install two DeadStreets JAR versions simultaneously. Do not enable experimental migration or Metropolis activation in a valuable world until in-game checks pass.
