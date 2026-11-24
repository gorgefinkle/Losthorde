# Dead Streets infected visuals

Dead Streets uses two rendering paths:

- **Vanilla zombie rendering** for the Crawler and Hulk. These infected retain the real Minecraft zombie skin, native model, native walk/swim/attack animations, and the vanilla hurt flash.
- **DisplayMotion models** for the Runner and Popper, where their distinct Husk and Drowned silhouettes justify custom articulated models.

| Infected | Rendering | Appearance and animation |
|---|---|---|
| Runner | DisplayMotion | Husk-based custom model; `idle`, `walk`, `run`, `attack`, `death` |
| Popper | DisplayMotion | Drowned-based custom model; `idle`, `walk`, `attack`, `inflate`, `burst`, `death` |
| Crawler | Native vanilla Zombie | Real zombie texture and model in the fixed low-profile swimming pose; native crawl/swim locomotion, attack movement, and hurt flash |
| Hulk | Native vanilla Zombie | Real zombie texture and model at the three-block target scale; native walk/attack movement and hurt flash |

## DisplayMotion models

The editable `.bbmodel` assets for Runner and Popper are embedded in the plugin JAR. DisplayMotion 0.4.0 is a required server dependency.

DisplayMotion converts cube faces into vanilla block displays using the closest solid vanilla block to the average color of each face. It does **not** render a full pixel-art skin texture like Minecraft's native entity renderer. For that reason, Crawler and Hulk intentionally do not use DisplayMotion: their vanilla zombie skin and native damage/locomotion feedback are more important than custom block-display geometry.

The Crawler and Hulk `.bbmodel` files are retained in the repository as historical design references only; they are not loaded or shown in-game.

## Runtime behavior

The server-side Zombie remains responsible for AI, targeting, collision, health, damage, and persistence.

- Runner and Popper use DisplayMotion displays and hide their underlying vanilla Zombie from viewers.
- Crawler uses Paper's fixed `SWIMMING` pose and reduced native scale.
- Hulk uses native zombie rendering and its three-block target scale.
- Native-rendered infected keep Minecraft's normal damage tint/flash and built-in entity animation.
- Popper death still plays its burst animation, applies modest damage in a small radius without block damage, and releases 1–4 Crawlers.
