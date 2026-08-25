# MineClient Bridge 1.1.0

- Added authenticated Minecraft command submission through HTTP and MCP, with or without a leading slash and without requiring an open chat screen.
- Added internal raw-key input for keyboard controls such as Enter, Escape, and F1 that may not have a named `KeyMapping`.
- Raw-key state is tracked on the Minecraft thread, with idempotent press/release behavior and explicit release events during release-all, close, and bridge shutdown.
- Slash-prefixed text submitted through an active chat screen now follows Minecraft's normal chat behavior instead of being blocked by the bridge.
- Kept loopback, bearer-token, exact-session identity, request-size, and input-validation boundaries; raw keys stay inside Minecraft and never use operating-system input injection.
