# Development notes

Java 25, Paper 26.2. R05 is a full-source checkpoint, including an offline build helper and core harness. Do not upload Metropolis source or source modifications into this public repository. All integration information is format-level only.

The `ObservationGuard` test is a deterministic **simulation** of multiple player observation radii, not a substitute for real client testing. `HordeManager` uses small, already-generated chunk look-ahead leases to overcome the conflict between normal player view-distance chunk loading and strict no-pop-in margins. It cannot prove client visibility merely from `getPlayersSeeingChunk`.

`hordes.enabled` and `population.activation.enabled` must stay false by default until multiplayer/in-game acceptance tests pass. Check `docs/STATUS_R05.md` for remaining issues.
