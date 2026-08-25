# MineClient Bridge 1.1.1

- Added normal in-world mouse button and scroll input through Minecraft and NeoForge's internal event paths.
- Named `KeyMapping` taps now activate only the requested mapping, even when it is unbound or shares a physical key with another mapping.
- Fixed MCP mouse `press`/`release` translation and allowed coordinate-free world scroll and button actions.
- Retained loopback bearer authentication, exact session identity, bounded payloads, Minecraft-thread dispatch, and release-all cleanup.
