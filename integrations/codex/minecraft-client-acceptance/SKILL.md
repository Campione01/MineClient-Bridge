---
name: minecraft-client-acceptance
description: Operate isolated Minecraft clients through MineClient Bridge for functional and visual acceptance. Use for real key, mouse, GUI, camera, model, animation, particle, shader, and cleanup checks that require a live client.
---

# Minecraft Client Acceptance

Use the `mineclient_bridge` MCP as the interactive operator surface. Do not drive the user's input desktop, use operating-system cursor injection, or create a new one-off control script for each test.

## Session Workflow

1. Start a fresh prepared client with `minecraft_client_launch`, or attach only to an already-running client whose exact identity is known with `minecraft_client_register`.
2. Require one exact tuple before observing or changing the client: `run_id`, Java PID, native desktop, runtime root, evidence root, and loopback bridge identity.
3. Use `minecraft_client_status`, then `minecraft_client_frame`. Inspect the returned frame; a nonempty PNG is evidence transport, not a pass conclusion.
4. Use `minecraft_client_query` for capabilities, world/player state, GUI widgets, inventory slots, and configured key mappings.
5. Use `minecraft_client_input` for mapped keys, camera movement, GUI pointer input, text, and release-all. Prefer explicit press/release for hold timing.
6. Observe again after every meaningful transition. Record visible behavior separately from server-owned facts.
7. Always release held input after an error. Finish with `minecraft_client_close`, then verify that the exact PID exits and the owned desktop is gone.

Read [references/mcp-contract.md](references/mcp-contract.md) for tool names, identity fields, and evidence limits.

## Parallel Test Batches

- Classify a large batch by mod, gameplay module, feature owner, and hard dependency component before dispatch.
- Give each subagent a disjoint test set and one client session. Keep one coordinator responsible for product edits, checklist mutation, deployment, and final judgment.
- Run only as many real clients as the machine has already proven safe. Each client needs a unique run ID, Java PID, native desktop, runtime root, evidence root, and bridge port.
- A subagent must never operate another lane or fall back to the user's desktop. Its result must include exact session identity, observed frames/state, conclusion, and cleanup outcome.
- Refill a released slot with the next independent group. One lane's product or bridge failure must not stop healthy lanes.
- Use fresh MCP processes for client-owning subagents so session state and secrets are not shared across lanes.

## Acceptance Rules

- Exercise real production input and state transitions. Do not manufacture internal state merely to make a check pass.
- Bind each visual check to a written expectation or reference and leave an explicit visible conclusion.
- A clear captured image can survive a later nonvisual warning, but it cannot prove server damage, packets, persistence, or cleanup.
- One failure blocks only its own claim. Preserve unrelated valid results and continue.
- Close a claim only after its required evidence route passes, then remove it from the active checklist immediately.

## Refusal Conditions

- Refuse mismatched PID, run ID, desktop, runtime/evidence roots, non-loopback endpoints, stale tokens, or unreleased input.
- Treat a bridge identity or attach error as a tooling problem. Make only the smallest evidence-backed correction, then return to the product test.
- If the MCP is not loaded after installation, restart Codex to load it. Do not replace it with ad hoc keyboard or mouse scripts.
