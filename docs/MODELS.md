# Infected rendering (R23)

Dead Streets uses BetterModel for custom Crawler and Hulk geometry/animation. It does not ship or retrieve a zombie skin PNG.

## What changed after R22

R22 registered the zombie entity texture in a separate pack, but the client still rendered checkerboards. That approach is retired.

R23 uses BetterModel's official `PluginStartReloadEvent` and `PackZipper` API. On the first reload, BetterModel imports the Blockbench models. On the following reload, Dead Streets injects the rewritten item-model JSON and the zombie sprite source into BetterModel's own generated pack before it is zipped.

The injected model definitions reference `minecraft:entity/zombie/zombie`. No `assets/minecraft/textures/entity/zombie/zombie.png` is included, so the client's active resource-pack stack remains responsible for supplying that texture. The final integrated pack is then delivered automatically by the server; players do not manually install anything or alter their local `.minecraft/resourcepacks` folder.

The server-side audit checks:
- 9 Crawler and 9 Hulk item definitions resolve to 18 generated model files
- every generated model texture points to the vanilla zombie texture identifier
- the item atlas contains the zombie sprite source
- no zombie texture PNG is shipped

This audit proves the generated pack structure, not a successful real-client render. A Minecraft client test is still required to confirm the final atlas bake and texture inheritance with the user's active texture pack.

## Infected models

- Runner: native zombie presentation with increased movement speed.
- Popper: native zombie presentation with slow movement and the death burst/crawler release gameplay.
- Crawler: custom horizontal body and looping crawl animation, with the native low-profile pose retained for its hitbox/gameplay.
- Hulk: custom broad/heavy body geometry and heavy-walk animation, while retaining its approximately three-block gameplay scale.

BetterModel remains optional. If it is absent or disabled, infected retain their native zombie fallback appearance and gameplay.
