# Infected implementation

Dead Streets R14 keeps real server-side zombie entities and uses BetterModel 3.7.1 as an optional visual layer for the Crawler and Hulk.

## Crawler

The Crawler is a real Bukkit/Paper Zombie with a fixed Pose.SWIMMING to retain the compact native dimensions. The previous pose-only implementation failed visually: vanilla Minecraft continued to render an upright zombie with its head tilted. R14 replaces that visual with the deadstreets_crawler Blockbench model and its looping crawl animation. The model has a horizontal body, forward-reaching arms, and trailing legs.

Health, movement speed, attack damage, persistence, and daylight immunity remain properties of the real Zombie. Popper offspring use the same model path.

## Hulk

The Hulk remains a real Zombie with native Attribute.SCALE set to 1.53846 by default, giving it a measured three-block bounding box. BetterModel attaches the broad deadstreets_hulk model and looping heavy_walk animation. The entity's native scale is also applied to the model tracker, so visual scale follows the actual Hulk size.

The explicit /ds spawn hulk test checks three blocks of vertical clearance before selecting a spawn position.

## Identity and health display

Generated first/last names remain stored in the zombie's persistent data. They are no longer exposed as globally visible vanilla custom names, and health is no longer printed to chat.

Every three ticks (configurable), Dead Streets asks each online player for their currently targeted entity within the configured range. If that one target is a managed zombie, the plugin creates or updates a private-to-that-player TextDisplay above it with the generated name and a health bar. Looking away, losing range, or changing targets removes the display. Work is proportional to online players, not horde size: there is no scan over every zombie and no per-zombie line-of-sight loop.

Configuration:

    zombies:
      target-display:
        enabled: true
        range: 9
        update-interval-ticks: 3

## Runtime verification

CI starts Paper 26.2 with Dead Streets as the only plugin, checks the Crawler's fixed compact pose, checks the Hulk's native three-block bounding box, and verifies both Blockbench files are packaged. A separate live-client test is still required to confirm BetterModel resource-pack delivery, the horizontal crawler silhouette, and animation quality.
