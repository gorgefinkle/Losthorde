# Losthorde / Dead Streets

Standalone Java 25 / Paper 26.2 zombie-horde plugin, with an **optional** external district/build population marker interface. Public source contains no Metropolis generator code, assets, schematics, or integration patches.

**Current release: R05 experimental**. Physical horde arrival with real clients has not yet been verified. The two riskiest features, `hordes.enabled` and `population.activation.enabled`, are **OFF** by default.

Current implementations:
- Persistent daylight-immune walkers.
- Zombie spawners: 16-block activation, instant 2–5 wave, 30–60-second follow-up waves, floating countdown, configurable caps.
- Shared persistent virtual hordes with temperament-dependent noise sensitivity and danger-colored title warnings.
- Conservative multi-player observation guard, limited prefetch of existing chunks, and low-frequency native pathfinder steering for physical walkers.
- Optional deterministic build flags: initial populations across footprint chunks, activation guarded against immediate duplication; not end-to-end verified with real Metropolis generation.

See [R05 verification status](docs/STATUS_R05.md) and [public repository boundary](docs/PUBLIC_REPOSITORY_POLICY.md).

## Building

Run `JAVA_HOME=/path/to/jdk25 bash test_core.sh`. Use `mvn -B -ntp package` where Paper Maven dependencies resolve, or set `JAVA_HOME`, `PAPER_API_JAR` and (if needed) `PAPER_DEPENDENCY_CLASSPATH` and run `bash build_offline.sh`.

Do not use multiple DeadStreets versions simultaneously. Do not enable experimental arrival or Metropolis population activation on a valuable world.
