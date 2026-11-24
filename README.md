# Losthorde / Dead Streets

Experimental Paper 26.2 / Java 25 zombie population and migration plugin, with an optional file-based Metropolis BUILD population integration.

**R13 is a dependency-free native-mob test build.** It removes FreeMinecraftModels, ResourcePackManager, DisplayMotion, Blockbench assets and custom display renderers. The special infected are now real server-side vanilla zombie-family entities; the server's own renderer supplies their appearance and animations.

## Server requirements

- Paper 26.2
- Java 25
- Dead Streets R13

No companion model, disguise, display, resource-pack-hosting or client mod is required. Metropolis integration is optional and file-based. The optional Blood Moon texture pack is a separate setting and is not needed for infected rendering.

## Native infected

- **Crawler:** A real vanilla Zombie held in Paper's fixed `Pose.SWIMMING`. It uses the vanilla zombie texture and native swimming-pose animation/bounding dimensions, giving it a low horizontal silhouette. Its health, movement speed and attack damage remain configurable.
- **Hulk:** A real vanilla Zombie with the native `minecraft:scale` attribute set to `1.53846` by default. A standard 1.95-block zombie therefore becomes approximately three blocks tall, and Minecraft scales its actual collision box along with its rendered body. It retains native zombie texture, movement, attack and hurt feedback. Its health, speed, damage, attack knockback and knockback resistance remain configurable.
- **Runner:** Keeps its faster movement and combat tuning, but currently uses the ordinary vanilla zombie appearance.
- **Popper:** Keeps its death burst, modest player damage and 1–4 Crawler release, but currently uses the ordinary vanilla zombie appearance.

There are no fake display entities, client-side model substitutes, downloaded textures, pack prompts or custom hitboxes for the Crawler or Hulk. The entity the player sees is the entity the server controls and collides with.

### Hulk clearance

A three-block Hulk needs three blocks of vertical clearance. The `/ds spawn hulk` test now searches for a safe position with that headroom before it reserves and spawns the test entity. Tight tunnels and ordinary doorways can block a Hulk; that is a consequence of its real enlarged collision box, not a visual-only effect.

## Current development scope

- Persistent daylight-immune walkers with a durable global admission budget.
- Configurable natural mob pass-through rates; non-zombie hostile mobs are blocked for all spawn reasons except admin commands and player spawn eggs.
- Vanilla spawner policy: `ALLOW_ALL` or `CONVERT_TO_ZOMBIES`. When the Dead Streets proximity-wave controller is enabled, converted spawners use its immediate first wave, randomized 30–60-second follow-up waves, nearby cap, and floating countdown.
- Persistent shared virtual hordes with sound-sensitive migration, physical emergence only in unobserved chunks, and eight-way per-player arrival directions, estimated ETA, population and temperament.
- Scragglers: a configurable subset of managed walkers gets independent local wander goals and may join a nearby shared horde.
- Blood Moon: one scheduled night every seven in-game days, with full-screen warning/wave notices and a configurable duration capped at sunrise.
- Optional Metropolis BUILD population records. Metropolis writes a file-only sidecar after a BUILD has fully pasted; Dead Streets reads its district, footprint, and candidate interior columns, then validates safe indoor standing positions.

## Repositories

- Dead Streets: this repository.
- Metropolis exporter integration: the separate `deadstreets-building-population-export` development branch in the Metropolis repository. No Metropolis assets or source are copied into this repository.

## Build and verification

Run `bash test_core.sh` with Java 25. Build with `mvn -B -ntp clean package`; only the Paper API is required at runtime.

CI launches Paper 26.2 with **Dead Streets as the only plugin** and checks the Crawler's swimming pose and the Hulk's measured native bounding-box height. This headless test confirms entity state and collision dimensions; a connected client is still needed to judge the actual silhouette and feel in-game. Test on a disposable world first.
