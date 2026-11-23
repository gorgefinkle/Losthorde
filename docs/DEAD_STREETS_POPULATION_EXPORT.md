# Dead Streets BUILD population export contract

Metropolis may export completed logical `SchematicType.BUILD` placements without depending on Dead Streets.

## File location

`plugins/Metropolis/deadstreets-build-flags-v1/<world-uuid>/buildings.jsonl`

UTF-8 JSON Lines: one complete JSON object per line. Append only after a logical building has finished placement, including every slice/overflow section. One stable `buildingId` per logical building; retries must not append duplicates. Dead Streets also retains support for the earlier regional TSV format.

## Required schema (version 1)

```json
{
  "schemaVersion": 1,
  "buildingId": "stable-building-id",
  "worldId": "world-uuid",
  "worldSeed": 123456789,
  "districtType": "HIGHRISE",
  "footprint": {
    "minX": 120, "maxX": 145,
    "minY": 62, "maxY": 110,
    "minZ": 200, "maxZ": 225
  },
  "interiorCandidates": [
    {"x": 125, "y": 68, "z": 208},
    {"x": 132, "y": 75, "z": 215},
    {"x": 140, "y": 82, "z": 220}
  ]
}
```

All fields shown are required, including `worldSeed`. `interiorCandidates` may be an empty array, but should contain up to 64 distinct, interior candidate standing positions where possible. Coordinates are block coordinates for the zombie's feet; Dead Streets rechecks floor, clearance, roof, liquids, chunk-loaded state, and player visibility before materialization.

The district type must be the actual district assigned to the parcel, not inferred from a schematic filename. Population min/max values are intentionally configured by Dead Streets and are not authoritative in this export.

## Consumer behavior

Dead Streets reads the file only when its optional population activation feature is enabled. It validates world UUID and seed, deduplicates by building ID, intersects the footprint with already-loaded chunks, and never force-loads terrain. Physical zombies are admitted only after its visibility and global population-budget checks.

## Metropolis responsibilities

No zombie spawning, no mob-policy changes, no Dead Streets dependency, no chunk forcing, and no blocking file I/O on the generation thread. Export completion must be independent of whether Dead Streets is installed or running.
```
