# MineClient Bridge MCP

This directory contains a dependency-free stdio MCP server for MineClient Bridge.

The stdio transport is UTF-8 newline-delimited JSON (NDJSON), not Content-Length
framing. Each incoming line is limited to 1 MiB before its LF terminator, including
when the line is still incomplete. Stdout contains only JSON-RPC messages.

## Requirements

- Node.js 20 or newer.
- MineClient Bridge installed in the target Minecraft client.
- Windows for `minecraft_client_launch`, because the prepared launcher uses a native background Desktop.

The other tools operate only on an explicitly registered, authenticated loopback bridge session. The current path contract uses normalized local Windows paths.

## Codex Configuration

```toml
[mcp_servers.mineclient_bridge]
command = '<absolute-path-to-node.exe>'
args = ['<absolute-path-to-repository>\mcp\mineclient-bridge-mcp.mjs']
startup_timeout_sec = 120

[mcp_servers.mineclient_bridge.env]
MINECLIENT_BRIDGE_PREPARED_ROOT_PARENT = '<absolute-parent-for-prepared-runs>'
MINECLIENT_BRIDGE_STATE_DIR = '<private-absolute-session-state-directory>'
MINECLIENT_BRIDGE_POWERSHELL = '<absolute-path-to-pwsh.exe-or-powershell.exe>'
```

Restart Codex after changing MCP configuration.

PowerShell 7 (`pwsh.exe`) is recommended when a prepared launcher references non-ASCII paths. Windows PowerShell 5.1 remains usable for ASCII-only launch inputs.

## Tools

- `minecraft_client_launch`
- `minecraft_client_register`
- `minecraft_client_status`
- `minecraft_client_frame`
- `minecraft_client_query`
- `minecraft_client_input`
- `minecraft_client_close`

`minecraft_client_input` accepts these input kinds: `key`, `raw_key`, `look`, `mouse`, `text`, `command`, and `release_all`. Use `raw_key` with a Minecraft key name or short alias such as `key.keyboard.enter`, `escape`, or `f1`. Repeated raw-key presses and releases are idempotent. A tap completes an existing press or sends a new press/release pair, always ends released, and repeated taps remain independent. Named `key` taps target only the requested mapping, including unbound mappings. `mouse` supports world button actions and coordinate-free world scroll as well as GUI coordinates. Session cleanup releases every tracked raw key. Use `command` with up to 256 command characters with or without a leading slash. All paths use the authenticated in-client bridge and do not inject operating-system input.

Launch roots are one-shot and must contain `final-preflight.json`, `launch.ps1`, and `launch/java-arguments.txt`. The launcher must preserve the MCP-provided `MINECLIENT_BRIDGE_*` environment variables when it starts Java. Every session is revalidated against its run ID, process ID, desktop, runtime root, evidence root, and authenticated bridge status.

Production descriptors are stored outside the repository. They contain the session token and must remain in a private user-owned directory. The MCP never returns or logs the token.

## Test

```powershell
npm test
```

The self-test covers all seven tools, launch and registration identity, PNG transport, bounded queries and inputs including commands and raw keys, release-before-close behavior, exact PID exit, and secret redaction.

The transport gate also covers initialize/tools/list over real stdio, clean output,
fragmented UTF-8, coalesced requests, CRLF, malformed JSON and complete/incomplete
line-size limits. Do not deploy the MCP adapter before this full test command
passes. After deployment, run the same tests from the installed directory and
verify that its server SHA-256 matches the tested source.
