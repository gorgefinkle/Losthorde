# Dead Streets development

This repository contains the independent Dead Streets Paper plugin, Java 25 build configuration, core tests, and public compatibility documentation.

**Do not publish Metropolis source code, integration patches that modify Metropolis, schematic assets, server JARs, archives, generator resources, or proprietary test-world exports here.**

Metropolis integration is optional. This plugin has its own marker parser, deterministic population planner, and opt-in chunk activation code. Integration with a user's Metropolis installation is described only at the interface level; the writer implementation and its modifications belong in a separate private workspace or repository.

The current R04 plugin requires Java 25 and Paper 26.2. Run `bash test_core.sh` for the core checks, then `mvn -B -ntp package` to build when Paper's Maven API dependency resolves.

Activation and migration are experimental and disabled by default. Before enabling them on a valuable world, verify initial marker delivery, multiplayer chunk visibility, elytra/teleport no-pop-in, restart persistence, and horde handoff behavior on a disposable test world.

See `docs/STATUS_R04.md` for the current detailed verification status.
