# 1.1.5

## Input event reporting

A bridge response said an input was accepted, but nothing said whether the client
published a NeoForge input event for it, or whether a mod cancelled that event. When a
gameplay mod deliberately takes over a button, the visible result is the same as input
that never arrived, and the operator has no way to tell them apart. That ambiguity is
what this release removes.

Every key, raw-key and world-mouse response now carries `mod_input_event`, listing each
event the dispatch published and whether a mod cancelled it. `/control/status` gains
`mouse` (grab and button state) and `mod_input_events`: running counts plus the last
observed `InputEvent.MouseButton.Pre`, `InputEvent.Key`, `InputEvent.MouseScrollingEvent`
and `InputEvent.InteractionKeyMappingTriggered`.

`event_fired: false` is not by itself proof that input was lost. Minecraft returns before
publishing a key event when a screen consumes the key, exactly as it does for a physical
keyboard.

## Named mappings produce real input events

`POST /control/key` borrowed the mapping onto a spare keyboard key and called
`KeyMapping.click()`. That updates `isDown()` and `consumeClick()`, so a mod that polls
its mapping each tick responded, but it publishes no `InputEvent.Key` or
`InputEvent.MouseButton` at all, so a mod that reads its controls from those events never
saw the input. A mapping is now driven through the handler its bound key would use:
`MouseHandler.onPress` for a mouse-bound mapping and `KeyboardHandler.keyPress` for a
keyboard-bound one.

Passing `"exact": true` keeps the previous targeting, where the mapping is borrowed onto
an unused keyboard key so only that mapping reacts; it now fires a real key event while
doing so, and an unbound mapping always takes this route. Two cases are refused rather
than doing something surprising: a borrowed key cannot be held, because the mapping is
only bound to it for the length of one event; and a mouse-bound mapping is not accepted
while a screen is open, because the screen route would click whatever the pointer sits on
instead of activating the mapping.

`exact` defaults to false, so a named tap behaves like the device the player would
actually use, including for other mappings that share the same key.

## Mouse grab in an isolated session

`MouseHandler.grabMouse()` is gated on `Minecraft.isWindowActive()`, and an isolated
client never takes operating system focus. In practice the flag stays at its startup
value of true, because a window that is never focused never receives a focus callback, so
the grab has been working; but it would be lost for good if that window ever gained and
then lost focus, and Minecraft refuses to continue an attack or to turn the player while
the mouse is ungrabbed. `grabMouse` now ignores the focus gate in an isolated session.
The redirect is scoped to that one method, so window focus keeps its normal meaning
everywhere else, and the native cursor is still never captured: the existing mixin on
`InputConstants.grabOrReleaseMouse` suppresses that call under the same condition.

## MCP adapter

`minecraft_client_input` accepts an optional boolean `exact` for `kind: "key"`.
