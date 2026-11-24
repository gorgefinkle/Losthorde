# Native infected implementation

Dead Streets R13 uses vanilla entity rendering only. There are no Blockbench files, display entities, disguise libraries, model plugins, custom texture atlases or client resource packs required for infected visuals.

## Crawler

The Crawler is a real Bukkit/Paper Zombie with its normal scale (1.0) and a fixed `Pose.SWIMMING` applied using Paper's `setPose(Pose.SWIMMING, true)`. The fixed flag prevents the normal mob pose update from immediately changing it back to `STANDING`. The vanilla client renders the zombie's own skin and swimming-pose limb animation. Paper uses the corresponding native pose dimensions for its entity bounds.

The pose is applied on assignment, explicit test spawn, Popper offspring creation, and entity-chunk refresh. The Crawler retains configurable max health, movement speed and attack damage, persistence, and daylight immunity.

## Hulk

The Hulk is a real Bukkit/Paper Zombie with the native `Attribute.SCALE` base value set to `1.53846` by default. With a standard 1.95-block zombie as the baseline, this produces a target height of approximately 3 blocks. Minecraft scales the entity's actual bounding box along with its rendered model; pathfinding, collision and melee reach continue to use the real entity rather than a separate hitbox or display.

The scale is configurable up to the three-block target. The manager reapplies it when managed entities load from chunks. Health, movement speed, attack damage, attack knockback and knockback resistance remain configurable.

The explicit `/ds spawn hulk` test checks three blocks of vertical clearance before selecting a spawn position, so it does not deliberately place the larger collision box inside a two-block tunnel.

## Other infected

Runner and Popper retain their gameplay attributes and event behavior. In this dependency-free build they currently use ordinary vanilla Zombie rendering; they do not have separate custom models or textures. Popper death still produces its burst and releases Crawlers.

## Runtime verification

The CI smoke test starts Paper 26.2 with Dead Streets as the only plugin. It creates temporary native Zombies, applies the Crawler pose and Hulk scale, verifies the pose enum, checks the Hulk's real bounding-box height against 3 blocks, then removes the temporary entities. This tests the server entity state without relying on any external renderer or resource-pack service.

A live client test is still necessary to judge whether the swimming pose reads as the intended crawler and whether the scaled Hulk's proportions and navigation feel right in the actual world.
