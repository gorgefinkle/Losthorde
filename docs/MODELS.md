# Dead Streets infected models

These are original, editable Blockbench free-model assets used by the required DisplayMotion 0.4.0 runtime dependency.

| File | Visible role | Animation clips |
|---|---|---|
| `runner_husk.bbmodel` | Dry, sand-colored, forward-leaning Husk Runner | `idle`, `walk`, `run`, `attack`, `death` |
| `popper_drowned.bbmodel` | Swollen, drowned-colored Popper with exposed ribs | `idle`, `walk`, `attack`, `inflate`, `burst`, `death` |
| `crawler_lowprofile.bbmodel` | Horizontal, long-armed low-profile Crawler | `idle`, `crawl`, `lunge`, `death` |
| `hulk_brute.bbmodel` | Broad, asymmetrical three-block Hulk | `idle`, `walk`, `attack`, `roar`, `death` |

## Editing

Open the `.bbmodel` files directly in Blockbench. Textures are embedded in each file; no external texture files or client resource pack are required.

DisplayMotion converts each cube into a vanilla block display using the closest solid vanilla block to the average color of that cube's texture faces. This deliberately keeps the models compatible with client texture packs. Use distinct solid-color swatches on separate cubes for material changes; detailed pixel patterns inside one texture will be averaged to a single block material.

Keep animation clip names unchanged because Dead Streets selects them by name. Keep the model's group hierarchy and UUID links intact when editing animations. Keep cube counts modest: each surviving cube is a client-rendered display part, so unnecessary geometry costs client FPS. DisplayMotion merges hidden/contained cubes and adjacent flat-color cubes where possible.

## Runtime behavior

The server-side Zombie remains the gameplay controller for AI, targeting, collision, health, and damage. DisplayMotion supplies the visible articulated model and its clickable hitbox. Dead Streets hides the vanilla Zombie body from each viewer and routes left-clicks on the model hitbox back to the server-side Zombie.

- Runner uses the Husk model and faster movement.
- Popper uses the Drowned model. On death it plays `burst`, applies modest damage in a small radius without block damage, and releases 1–4 Crawlers.
- Crawler uses a swimming-pose gameplay body and the horizontal model.
- Hulk uses a three-block server collision scale and the broad model.

An opt-in server startup self-test can exercise every model's spawn, follow, hitbox, click callback, and animation clips by launching Paper with `-Ddeadstreets.model-smoke=true`. It is disabled during normal server startup.
