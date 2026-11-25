# Infected rendering (R21)

Dead Streets uses BetterModel for the custom Crawler and Hulk geometry/animation, while keeping the texture entirely client-native.

The generated Dead Streets resource pack is a **model-definition pack only**:
- It contains the 18 BetterModel item definitions and 18 model JSON files needed by the Crawler and Hulk.
- Every model JSON points to `minecraft:entity/zombie/zombie`.
- It contains no PNG files and does not replace any texture in the `minecraft` namespace.
- The model UVs are mapped to the vanilla 64×64 zombie skin atlas.

Minecraft resolves that texture through each client's active resource-pack stack. A player using a pack that changes the vanilla zombie skin therefore sees that skin on the custom infected too. Dead Streets does not ship or force its own infected skin.

- Runner: native zombie presentation with increased movement speed.
- Popper: native zombie presentation with slow movement and the death burst/crawler release gameplay.
- Crawler: custom horizontal body and looping crawl animation, with the native low-profile pose retained for its hitbox/gameplay.
- Hulk: custom broad/heavy body geometry and heavy-walk animation, while retaining its approximately three-block gameplay scale.

BetterModel remains optional. If it is absent or disabled, the infected retain their native zombie fallback appearance and gameplay. Dead Streets does not modify or replace other BetterModel model assets.
