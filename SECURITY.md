# Security Policy

## Scope

MineClient Bridge grants an authenticated local process the ability to observe and operate a Minecraft client. Treat its bearer token as a password for that client session.

The bridge binds only to loopback, rejects non-loopback requests, and requires a bearer token for every control endpoint. An authorized caller can submit Minecraft commands and internal keyboard events as well as normal keymap, mouse, screen, and text input. Minecraft and the connected server apply their normal command and gameplay permissions.

The bridge does not expose shell execution, scripts, direct file access, outbound network requests, or operating-system input injection. Raw keys are delivered to Minecraft's `KeyboardHandler` for the bridge-owned client window.

## Operator Responsibilities

- Do not publish, commit, record, or share `mineclient-bridge.token` or MCP session descriptors.
- Use a separate token and runtime root for each automated client.
- Call release-all after an interrupted operation.
- Disable the bridge when local automation is not needed.
- Grant the token only to agents that may execute the same Minecraft commands and client actions as the player.
- Install releases only from the project's official distribution pages.

## Reporting

Report security issues through the repository's private security advisory feature. Do not post active tokens, private runtime paths, or exploit details in a public issue.
