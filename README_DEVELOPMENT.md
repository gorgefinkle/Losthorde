# Developer notes: R03

Repository root is the full standalone source. Java 25 + Paper 26.2 required. `./test_core.sh` runs 239 core assertions, and `mvn -B -ntp -DskipTests package` builds `DeadStreets_R03.jar` when Maven can resolve Paper API and transitive dependencies.

The optional Metropolis adapter patch targets **only** the owner-supplied R14_5D full-source baseline. See `integrations/metropolis/R14_5D_population_flags.patch`. Set `deadstreets.export-build-flags: true` in Metropolis only on a backed-up development world. It writes one TSV BUILD record after a placed BUILD parcel completes, with no block edits. The independent Dead Streets importer is separately opt-in with `population.activation.enabled: true`. Neither side is enabled by default.

The R03 importer has passed a genuine Paper smoke test using a **synthetic** marker, including restart persistence. Full in-game no-pop-in and actual Metropolis end-to-end acceptance tests remain pending; don't enable the experimental integration on a valuable world.
