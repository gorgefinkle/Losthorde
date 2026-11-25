#!/usr/bin/env python3
"""Build a BetterModel pack that registers the client zombie texture in the item atlas."""
from __future__ import annotations

import json
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

VANILLA_ZOMBIE = "minecraft:entity/zombie/zombie"


def prepare(source: Path, destination: Path) -> tuple[int, int]:
    with ZipFile(source, "r") as archive:
        source_files = {
            name: archive.read(name)
            for name in archive.namelist()
            if not name.endswith("/")
        }

    if "pack.mcmeta" not in source_files:
        raise RuntimeError("BetterModel output is missing pack.mcmeta")

    item_paths = sorted(
        name for name in source_files
        if name.startswith("assets/") and "/items/" in name
        and "deadstreets_" in name and name.endswith(".json")
    )
    if len(item_paths) != 18:
        raise RuntimeError(
            f"Expected 18 Dead Streets bone item definitions (9 crawler + 9 hulk), found {len(item_paths)}"
        )

    output_files: dict[str, bytes] = {"pack.mcmeta": source_files["pack.mcmeta"]}
    # BetterModel renders Blockbench geometry through item-display models. Their
    # textures are sampled from the items atlas; zombie.png normally belongs to
    # the entity renderer and is not registered there. Add it as an atlas sprite.
    # Atlas source definitions merge across active packs, while the resource
    # manager resolves the PNG from the client's effective resource-pack stack.
    atlas_path = "assets/minecraft/atlases/items.json"
    output_files[atlas_path] = (
        json.dumps({"sources": [{"type": "minecraft:single", "resource": VANILLA_ZOMBIE}]}, separators=(",", ":")) + "\n"
    ).encode("utf-8")
    model_paths: set[str] = set()
    crawler_items = sum("deadstreets_crawler_" in path for path in item_paths)
    hulk_items = sum("deadstreets_hulk_" in path for path in item_paths)
    if crawler_items != 9 or hulk_items != 9:
        raise RuntimeError(f"Unexpected infected model split: crawler={crawler_items}, hulk={hulk_items}")

    for item_path in item_paths:
        item = json.loads(source_files[item_path])
        model_ref = item.get("model", {}).get("model")
        if not isinstance(model_ref, str) or ":" not in model_ref:
            raise RuntimeError(f"Invalid BetterModel item definition: {item_path}")
        namespace, model_name = model_ref.split(":", 1)
        model_path = f"assets/{namespace}/models/{model_name}.json"
        if model_path not in source_files:
            raise RuntimeError(f"{item_path} references missing model JSON: {model_path}")
        model_paths.add(model_path)
        output_files[item_path] = source_files[item_path]

    for model_path in sorted(model_paths):
        model = json.loads(source_files[model_path])
        if "parent" in model or "overrides" in model:
            raise RuntimeError(f"Unexpected external model dependency in {model_path}")
        textures = model.get("textures")
        if not isinstance(textures, dict) or not textures:
            raise RuntimeError(f"Missing texture map in {model_path}")

        rewritten = 0
        for key, value in list(textures.items()):
            if not isinstance(value, str) or ":" not in value:
                raise RuntimeError(f"Unexpected texture reference in {model_path}: {key}={value!r}")
            namespace, texture_path = value.split(":", 1)
            if namespace == "bettermodel" and texture_path.startswith("item/"):
                textures[key] = VANILLA_ZOMBIE
                rewritten += 1
            elif value != VANILLA_ZOMBIE:
                raise RuntimeError(f"Unrecognized texture reference in {model_path}: {value}")
        if rewritten == 0 and any(value != VANILLA_ZOMBIE for value in textures.values()):
            raise RuntimeError(f"No texture alias was applied in {model_path}")

        output_files[model_path] = (json.dumps(model, separators=(",", ":")) + "\n").encode("utf-8")

    if len(model_paths) != 18:
        raise RuntimeError(f"Expected 18 model JSON files, found {len(model_paths)}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(destination, "w", ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(output_files):
            archive.writestr(name, output_files[name])

    with ZipFile(destination, "r") as archive:
        names = archive.namelist()
        if any(name.lower().endswith(".png") for name in names):
            raise RuntimeError("Texture-inheriting pack unexpectedly contains PNG textures")
        if atlas_path not in names:
            raise RuntimeError("Output pack is missing the item-atlas registration")
        atlas = json.loads(archive.read(atlas_path))
        if {"type": "minecraft:single", "resource": VANILLA_ZOMBIE} not in atlas.get("sources", []):
            raise RuntimeError("Zombie entity texture is not registered as an item-atlas sprite")
        if len([name for name in names if "/items/" in name and name.endswith(".json")]) != 18:
            raise RuntimeError("Output pack is missing Dead Streets item definitions")
        if len([name for name in names if "/models/" in name and name.endswith(".json")]) != 18:
            raise RuntimeError("Output pack is missing Dead Streets model geometry")
        for name in names:
            if name.endswith(".json") and "/models/" in name:
                model = json.loads(archive.read(name))
                if any(value != VANILLA_ZOMBIE for value in model.get("textures", {}).values()):
                    raise RuntimeError(f"Non-vanilla texture reference remains in {name}")

    return len(item_paths), len(model_paths)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: prepare_bettermodel_vanilla_texture_pack.py INPUT.zip OUTPUT.zip")
    items, models = prepare(Path(sys.argv[1]), Path(sys.argv[2]))
    print(
        f"Texture-inheriting pack ready: {items} item definitions, {models} model JSONs, "
        f"item atlas registers {VANILLA_ZOMBIE}; no PNG assets included."
    )
