# Infected rendering (R22)

Dead Streets uses BetterModel for custom Crawler and Hulk geometry/animation. It does not ship a zombie skin PNG.

## Why R21 showed checkerboards

BetterModel renders Blockbench geometry through item-display models. Those models sample the Minecraft **items atlas**. The zombie skin normally belongs to the entity renderer and is not guaranteed to be present in that atlas. R21 rewrote model texture references to the entity texture identifier but did not register that texture as an item-atlas sprite, so the model renderer could not sample it.

## R22 texture bridge

The generated model pack adds a `minecraft:single` sprite source to `assets/minecraft/atlases/items.json`, with resource `minecraft:entity/zombie/zombie`. The model JSON references the sprite with the same identifier. Minecraft merges atlas sources from active resource packs and resolves the referenced PNG through the client's resource-pack stack. The pack contains no zombie PNG and does not replace the `minecraft:entity/zombie/zombie` texture. This is intended to let the client's existing zombie texture pack remain authoritative.

This is a reasoned resource-pack fix, not yet a claim of successful in-game rendering. CI verifies the model references and atlas registration, but only a Minecraft client test can prove the texture atlas reload and final visual result on a real client. If R22 still shows a checkerboard, capture the client log around resource reload; do not fall back to another unverified texture alias.

- Runner: native zombie presentation with increased movement speed.
- Popper: native zombie presentation with slow movement and the death burst/crawler release gameplay.
- Crawler: custom horizontal body and looping crawl animation, with the native low-profile pose retained for its hitbox/gameplay.
- Hulk: custom broad/heavy body geometry and heavy-walk animation, while retaining its approximately three-block gameplay scale.

BetterModel remains optional. If it is absent or disabled, infected retain their native zombie fallback appearance and gameplay.
