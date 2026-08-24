# Security Policy

## Scope

MineClient Bridge grants an authenticated local process the ability to observe and operate a Minecraft client. Treat its bearer token as a password for that client session.

The bridge binds only to loopback, rejects non-loopback requests, and requires a bearer token for every control endpoint. It does not expose arbitrary shell execution, scripts, direct file access, or unrestricted command submission. Normal key, mouse, screen, and text input can still trigger gameplay and GUI behavior.

## Operator Responsibilities

- Do not publish, commit, record, or share `mineclient-bridge.token` or MCP session descriptors.
- Use a separate token and runtime root for each automated client.
- Call release-all after an interrupted operation.
- Disable the bridge when local automation is not needed.
- Install releases only from the project's official distribution pages.

## Reporting

Report security issues through the repository's private security advisory feature. Do not post active tokens, private runtime paths, or exploit details in a public issue.
