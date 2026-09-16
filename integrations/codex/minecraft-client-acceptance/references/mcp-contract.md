# MineClient Bridge MCP Contract

## Tools

- `minecraft_client_launch`: starts one prepared isolated client root and registers its verified bridge identity.
- `minecraft_client_register`: attaches an already-running exact client after bridge and PID verification.
- `minecraft_client_status`: returns identity, world/screen state, dimensions, and held mappings.
- `minecraft_client_frame`: returns the current presented frame as PNG.
- `minecraft_client_query`: reads capabilities, player/world state, GUI/widget/slot state, or key mappings.
- `minecraft_client_input`: sends one bounded keymap, raw key, look, mouse, text, command, or release-all action.
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

Use `kind: command` for explicit Minecraft commands. The command may include or omit its leading slash. Use `kind: raw_key` for internal keyboard events that are not represented by a named `KeyMapping`, such as `enter`, `escape`, or `f1`. Raw keys go through the target client's `KeyboardHandler`, not operating-system input.

`kind: key` drives the mapping's own bound key through that device's handler, so a gameplay mod that reads its controls from `InputEvent.Key` or `InputEvent.MouseButton` responds, and every mapping sharing that key reacts just as it would for the player. Pass `exact: true` to activate only the named mapping instead.

Key, raw-key and world-mouse replies carry `mod_input_event`, and `status` carries `mouse` and `mod_input_events`. `event_cancelled_by_mod: true` records that a mod consumed that input on purpose, which is a different outcome from input that was never delivered; do not report the first as a delivery failure. `event_fired: false` is not proof of the second either: a screen that consumes a key returns before Minecraft publishes the event, exactly as it does for a device. The screen-scope `mouse`, `look`, `text`, `command` and `release_all` routes drive no device handler and carry no `mod_input_event`.

MCP observation is request/response, not a continuous video stream. Motion-dependent tests may request successive frames or retain a short client-owned framebuffer recording.

## Evidence Boundary

MCP frames prove visible client presentation. MCP input proves that a configured client mapping was exercised. Neither alone proves server damage, packet authority, persistence, multiplayer tracking, or cleanup; record those through their owning runtime.
