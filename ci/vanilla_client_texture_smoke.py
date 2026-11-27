#!/usr/bin/env python3
"""Launch an unmodified Minecraft 26.2 client against the R28 Paper test server.

The only local resource pack in this test is a deliberately loud replacement for
minecraft:entity/zombie/zombie. The server-delivered BetterModel pack does not
contain that PNG; it contributes only an items-atlas alias. A visible Crawler
therefore proves the vanilla client resolved the alias through its own pack stack.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import time
import uuid


USERNAME = "DeadStreetsBot"
PROBE_PACK = "deadstreets-texture-probe"
SERVER_ADDRESS = "127.0.0.1:25566"


def offline_uuid(name: str) -> str:
    raw = hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest()
    return str(uuid.UUID(bytes=raw, version=3))


def nbt_string(name: str, value: str) -> bytes:
    key = name.encode("utf-8")
    val = value.encode("utf-8")
    return b"\x08" + struct.pack(">H", len(key)) + key + struct.pack(">H", len(val)) + val


def nbt_byte(name: str, value: int) -> bytes:
    key = name.encode("utf-8")
    return b"\x01" + struct.pack(">H", len(key)) + key + struct.pack(">b", value)


def write_servers_dat(path: Path, server_address: str) -> None:
    # Modern servers.dat stores the per-server pack decision as acceptTextures.
    # Byte 1 means automatically accept this test server's resource pack.
    entry = (
        nbt_string("name", "Dead Streets texture inheritance test")
        + nbt_string("ip", server_address)
        + nbt_byte("acceptTextures", 1)
        + b"\x00"
    )
    key = b"servers"
    nbt = (
        b"\x0a\x00\x00"
        + b"\x09" + struct.pack(">H", len(key)) + key
        + b"\x0a" + struct.pack(">i", 1)
        + entry
        + b"\x00"
    )
    path.write_bytes(nbt)


def write_probe_pack(game_directory: Path) -> None:
    from PIL import Image, ImageDraw

    root = game_directory / "resourcepacks" / PROBE_PACK
    texture = root / "assets" / "minecraft" / "textures" / "entity" / "zombie" / "zombie.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    (root / "pack.mcmeta").write_text(
        json.dumps({
            "pack": {
                "pack_format": 88,
                "description": "Dead Streets CI: local zombie texture inheritance probe",
                "supported_formats": [88, 88],
                "min_format": 88,
                "max_format": 88
            }
        }),
        encoding="utf-8"
    )

    # 4-pixel blocks in colors absent from the vanilla zombie skin. The same
    # probe is used by the normal entity renderer and by the Crawler's atlas alias.
    palette = [
        (255, 0, 210),   # vivid magenta
        (0, 255, 45),    # lime
        (0, 220, 255),   # cyan
        (255, 130, 0),   # orange
        (35, 35, 255),   # blue
        (255, 245, 0),   # yellow
        (255, 255, 255), # white
        (20, 10, 35),    # near-black
    ]
    image = Image.new("RGB", (64, 64))
    pixels = image.load()
    for y in range(64):
        for x in range(64):
            band = ((x // 4) + (y // 4) + ((x // 16) * 2)) % len(palette)
            pixels[x, y] = palette[band]
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 63, 63), outline=(255, 255, 255), width=2)
    draw.line((0, 31, 63, 31), fill=(10, 10, 10), width=2)
    draw.line((31, 0, 31, 63), fill=(10, 10, 10), width=2)
    image.save(texture, format="PNG")

    game_directory.mkdir(parents=True, exist_ok=True)
    (game_directory / "options.txt").write_text(
        'resourcePacks:["vanilla","file/' + PROBE_PACK + '"]\n'
        'incompatibleResourcePacks:[]\n'
        'joinedFirstServer:true\n',
        encoding="utf-8"
    )
    write_servers_dat(game_directory / "servers.dat", SERVER_ADDRESS)


def tail(path: Path, count: int = 100) -> str:
    if not path.exists():
        return f"(missing: {path})"
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-count:])


def xdotool(*args: str) -> None:
    subprocess.run(["xdotool", *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def find_window() -> str:
    for pattern in ("Minecraft", "minecraft"):
        result = subprocess.run(
            ["xdotool", "search", "--onlyvisible", "--name", pattern],
            check=False, capture_output=True, text=True
        )
        ids = result.stdout.split()
        if ids:
            return ids[-1]
    raise RuntimeError("Could not find the visible vanilla Minecraft window")


def run_client(game_directory: Path, server_log: Path, server_address: str, timeout: int) -> None:
    import minecraft_launcher_lib

    game_directory = game_directory.resolve()
    client_log = game_directory / "logs" / "latest.log"
    screenshot_dir = game_directory / "screenshots"
    screenshot_dir.mkdir(parents=True, exist_ok=True)
    prior_shots = set(screenshot_dir.glob("*.png"))

    options = minecraft_launcher_lib.utils.generate_test_options()
    options.update({
        "username": USERNAME,
        "uuid": offline_uuid(USERNAME),
        "token": "0",
        "gameDirectory": str(game_directory),
        "quickPlayMultiplayer": server_address,
        "customResolution": True,
        "resolutionWidth": "1280",
        "resolutionHeight": "720",
        "launcherName": "DeadStreetsVanillaClientSmoke",
        "jvmArguments": ["-Xms1G", "-Xmx2G"]
    })
    command = minecraft_launcher_lib.command.get_minecraft_command(
        "26.2", str(game_directory), options
    )
    command.append("--offlineDeveloperMode")

    env = os.environ.copy()
    env["LIBGL_ALWAYS_SOFTWARE"] = "1"
    env.setdefault("MESA_GL_VERSION_OVERRIDE", "4.5")
    launcher_log = game_directory / "launcher.stdout.log"
    started = time.monotonic()
    with launcher_log.open("wb") as output:
        process = subprocess.Popen(command, cwd=game_directory, env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            success_marker = f"Player {USERNAME} successfully loaded the integrated Dead Streets model pack."
            request_marker = f"Requested the integrated BetterModel pack for {USERNAME}."
            accepted = False
            prompt_fallback_sent = False
            deadline = time.monotonic() + timeout

            while time.monotonic() < deadline:
                server_text = server_log.read_text(encoding="utf-8", errors="replace") if server_log.exists() else ""
                if success_marker in server_text:
                    accepted = True
                    break
                if process.poll() is not None:
                    raise RuntimeError(
                        "Vanilla client exited before loading the server pack.\n"
                        + tail(client_log) + "\nLauncher:\n" + tail(launcher_log)
                    )
                # The server's saved-server entry should auto-accept the pack. If a
                # 26.2 client still presents a consent screen for Quick Play, press
                # the focused default action once and continue waiting for the
                # server-side SUCCESSFULLY_LOADED acknowledgement.
                if request_marker in server_text and not prompt_fallback_sent:
                    if time.monotonic() - started > 35:
                        window = find_window()
                        xdotool("windowfocus", "--sync", window)
                        xdotool("key", "Return")
                        prompt_fallback_sent = True
                time.sleep(1)

            if not accepted:
                raise RuntimeError(
                    f"Vanilla client did not report SUCCESSFULLY_LOADED for the server pack within {timeout}s.\n"
                    "Server log:\n" + tail(server_log) +
                    "\nClient log:\n" + tail(client_log) +
                    "\nLauncher log:\n" + tail(launcher_log)
                )

            # The server pack is now active in an ordinary, unmodified 26.2
            # client. Spawn a Crawler in front of this player and capture it.
            window = find_window()
            xdotool("windowfocus", "--sync", window)
            xdotool("key", "t")
            xdotool("type", "--clearmodifiers", "--delay", "25", "/ds spawn crawler -f")
            xdotool("key", "Return")

            spawn_marker = "Client texture smoke command: DEV FORCE: spawned uncounted test Crawler"
            spawn_deadline = time.monotonic() + 45
            while time.monotonic() < spawn_deadline:
                server_text = server_log.read_text(encoding="utf-8", errors="replace") if server_log.exists() else ""
                if spawn_marker in server_text:
                    break
                if process.poll() is not None:
                    raise RuntimeError("Vanilla client exited before the test Crawler spawned.\n" + tail(client_log))
                time.sleep(1)
            else:
                raise RuntimeError(
                    "The /ds spawn crawler test command did not complete.\n"
                    "Server log:\n" + tail(server_log) +
                    "\nClient log:\n" + tail(client_log)
                )

            time.sleep(4)
            xdotool("key", "F2")
            screenshot_deadline = time.monotonic() + 15
            screenshot = None
            while time.monotonic() < screenshot_deadline:
                new_shots = sorted(set(screenshot_dir.glob("*.png")) - prior_shots)
                if new_shots:
                    screenshot = new_shots[-1]
                    break
                time.sleep(0.5)
            if screenshot is None:
                raise RuntimeError("Minecraft did not create an F2 screenshot.\n" + tail(client_log))

            from PIL import Image
            with Image.open(screenshot).convert("RGB") as captured:
                if captured.width < 800 or captured.height < 500:
                    raise RuntimeError(f"Unexpected client screenshot size: {captured.size}")
                # The probe texture contains pure magenta 4x4 blocks, which are not
                # present in vanilla's zombie skin. Requiring enough of those pixels
                # proves the model actually sampled the player's local PNG instead
                # of merely accepting a pack or displaying a missing-texture tile.
                magenta_pixels = sum(
                    1 for red, green, blue in captured.getdata()
                    if red >= 240 and green <= 20 and blue >= 180
                )
                if magenta_pixels < 35:
                    raise RuntimeError(
                        f"Local zombie texture override was not visibly rendered: "
                        f"found only {magenta_pixels} distinctive magenta pixels in {screenshot}"
                    )

            print(f"CLIENT_TEXTURE_SMOKE_PASS: vanilla 26.2 accepted the server pack, spawned a Crawler, and rendered {magenta_pixels} distinctive local-override pixels.")
            print(f"Client screenshot: {screenshot}")
            print(f"Local probe texture: {game_directory / 'resourcepacks' / PROBE_PACK / 'assets/minecraft/textures/entity/zombie/zombie.png'}")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--game-directory", required=True)
    parser.add_argument("--server-log")
    parser.add_argument("--server-address", default=SERVER_ADDRESS)
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()

    game_directory = Path(args.game_directory)
    if args.prepare:
        import minecraft_launcher_lib
        print("Installing vanilla Minecraft 26.2 client and assets...")
        minecraft_launcher_lib.install.install_minecraft_version("26.2", str(game_directory))
        write_probe_pack(game_directory)
        print(f"Prepared vanilla client and local texture probe under {game_directory}")
    if args.run:
        if not args.server_log:
            parser.error("--server-log is required with --run")
        run_client(game_directory, Path(args.server_log), args.server_address, args.timeout)
    if not args.prepare and not args.run:
        parser.error("Specify --prepare and/or --run")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"CLIENT_TEXTURE_SMOKE_FAILURE: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
