# Infected rendering (R20)

Dead Streets intentionally does not install BetterModel, ship custom model textures, or request a resource pack.

Runner, Popper, Hulk, and Crawler use native Minecraft Zombie entities. Their client-side texture is the vanilla zombie texture resolved through the player's active resource-pack stack. This means compatible player resource packs can replace the zombie texture without a Dead Streets pack taking priority.

- Runner: native zombie presentation with increased movement speed.
- Popper: native zombie presentation with slow movement and the death burst/crawler release gameplay.
- Hulk: native zombie model and texture, scaled to approximately three blocks tall; its collision dimensions are refreshed with the native scale attribute.
- Crawler: native zombie model and texture, low-profile swimming pose, native movement and combat.

No BetterModel plugin is required. If BetterModel is installed for another plugin, Dead Streets does not modify or reload it.
