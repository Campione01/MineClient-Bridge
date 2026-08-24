# MineClient Bridge MCP

This directory contains a dependency-free stdio MCP server for MineClient Bridge.

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

Launch roots are one-shot and must contain `final-preflight.json`, `launch.ps1`, and `launch/java-arguments.txt`. The launcher must preserve the MCP-provided `MINECLIENT_BRIDGE_*` environment variables when it starts Java. Every session is revalidated against its run ID, process ID, desktop, runtime root, evidence root, and authenticated bridge status.

Production descriptors are stored outside the repository. They contain the session token and must remain in a private user-owned directory. The MCP never returns or logs the token.

## Test

```powershell
node .\self-test.mjs
```

The self-test covers all seven tools, launch and registration identity, PNG transport, bounded queries and inputs, release-before-close behavior, exact PID exit, and secret redaction.
