# Public repository boundary

This project is independent from Metropolis.

Allowed here:
- Dead Streets' own source code, tests, Maven build scripts, config and releases.
- A generic, implementation-independent description of how Dead Streets consumes optional district/build population metadata.
- References to compatible plugin names and general public APIs.

Not allowed here:
- Metropolis source code or source modifications/patches.
- Metropolis schematics, structure assets, test-world exports, archives or generated resources.
- Proprietary server bundles, unofficial redistributions of Minecraft/Mojang binaries or user-supplied Metropolis JARs.

Keep the Metropolis writer and any integration implementation on the Metropolis side in private storage; communicate only through a documented data interface. The Dead Streets reader itself is independent Dead Streets source, not Metropolis's source.

If Metropolis-related material accidentally lands here, remove it from the branch and review all reachable Git history before treating the repository as clean.
