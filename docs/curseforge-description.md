# MineClient Bridge

MineClient Bridge is a client-side NeoForge 1.21.1 mod that gives authorized local tools a bounded way to observe and operate a Minecraft client. It is intended for automated testing, accessibility experiments, controlled demonstrations, and development workflows that need real client input and framebuffer evidence.

## Features

- Captures the current presented framebuffer as PNG.
- Reads bounded client, player, world, inventory, effect, crosshair, entity, GUI-widget, and container-slot state.
- Discovers and operates named Minecraft key mappings.
- Supports bounded view, GUI mouse, active-screen text, and internal raw-key input.
- Submits authorized Minecraft commands through the normal client command path.
- Releases held input and can request a graceful client shutdown.
- Includes an optional MCP server and Codex acceptance skill in the source repository.

## Safety And Privacy

The bridge binds to `127.0.0.1` and rejects non-loopback requests. Every control endpoint requires a generated 256-bit bearer token, stored separately from the normal JSON configuration and never logged. Requests, responses, text, commands, and raw-key names have fixed size limits, and held keys are released during shutdown.

MineClient Bridge has no telemetry and does not send client data to an external service. It exposes no shell, script, filesystem, outbound-network, operating-system input, or direct world-edit API. Authorized commands and client input can cause normal in-game actions, so the token should be treated as a local password.

## Installation

1. Install NeoForge for Minecraft 1.21.1.
2. Place the MineClient Bridge JAR in the client's `mods` folder.
3. Start the client. Configuration and token files are created on first launch.

The mod is client-only and has no gameplay-mod dependency. It is enabled by default on `127.0.0.1:38121` and can be disabled in `config/mineclient-bridge.json`.

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.197 or newer within the 21.1 line
- Java 21
- Client side only

The optional MCP prepared-root launcher currently targets Windows background desktops. The NeoForge mod itself does not require the MCP.

## Live Client Evidence

![Live Minecraft client framebuffer captured through MineClient Bridge](https://raw.githubusercontent.com/Campione01/MineClient-Bridge/main/docs/assets/mineclient-bridge-live-client-smoke.png)

This is a real 960x540 framebuffer captured during a mod-compatibility smoke test. The gameplay model belongs to a separate test mod; MineClient Bridge adds no in-game overlay.

The project avatar is an original AI-assisted illustration and does not represent a separate in-game UI.
