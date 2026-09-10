# 1.1.3

- Use process-local virtual mouse capture, keyboard polling and clipboard state
  for isolated background clients, without grabbing the shared OS pointer.
- Reject physical input callbacks in isolated sessions while retaining the
  normal Minecraft event path for authenticated synthetic input.
- Preserve Shift/Ctrl/Alt/Super state in synthetic keyboard and mouse events.
- Report isolation mode and counters in client status. The MCP launcher verifies
  isolation support before starting a background client and before sending input.
- Keep ordinary foreground clients unchanged. No server installation is needed.
