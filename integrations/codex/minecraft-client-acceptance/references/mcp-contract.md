# MineClient Bridge MCP Contract

## Tools

- `minecraft_client_launch`: starts one prepared isolated client root and registers its verified bridge identity.
- `minecraft_client_register`: attaches an already-running exact client after bridge and PID verification.
- `minecraft_client_status`: returns identity, world/screen state, dimensions, and held mappings.
- `minecraft_client_frame`: returns the current presented frame as PNG.
- `minecraft_client_query`: reads capabilities, player/world state, GUI/widget/slot state, or key mappings.
- `minecraft_client_input`: sends one bounded key, look, mouse, text submission, or release-all action.
- `minecraft_client_close`: releases input, requests graceful client shutdown, and removes the registered session.

## Required Identity

Every session is loopback-only and bound to:

```text
run_id
process_id
desktop_name
runtime_root
evidence_root
bridge run_id/process_id response
```

The bearer token is secret operational state. Never print it, commit it, place it in a checklist, or include it in evidence.

## Observation Loop

Use `status -> frame -> input -> frame` instead of fixed sleeps. For held actions, issue `press`, observe the intended state, then issue `release` in a cleanup path. Use `release-all` after any error.

MCP observation is request/response, not a continuous video stream. Motion-dependent tests may request successive frames or retain a short client-owned framebuffer recording.

## Evidence Boundary

MCP frames prove visible client presentation. MCP input proves that a configured client mapping was exercised. Neither alone proves server damage, packet authority, persistence, multiplayer tracking, or cleanup; record those through their owning runtime.
