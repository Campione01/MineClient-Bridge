# Background Input Isolation: 1.1.3

## Completed Checks

- Java unit tests: 11 tests, no failures or errors.
- MCP self-test: old/missing/duplicate Bridge artifacts and incomplete runtime
  isolation capability are rejected before background input is sent.
- A real NeoForge 1.21.1 client loaded the required input mixins on a named
  non-input Win32 Desktop, with Iris enabled.
- The live registration-block counter was 2 before the first synthetic input.
- Pause-menu mouse navigation, chat text, Ctrl+A/C/V, world mouse clicks,
  inventory input, Shift press/release and release-all were exercised through MCP.
- Virtual clipboard write/read counters were 1/1; the test text was restored
  by Minecraft paste. Final held keys and held mappings were empty.
- Across 2,689 read-only foreground samples over approximately five minutes,
  the test Java process was never foreground and cursor clipping bounds did
  not change. The input Desktop remained Default. The exact test process exited
  and its named Desktop was confirmed absent.

## Scope And Limits

This verifies the tested background input paths, not a Windows security sandbox
for arbitrary third-party native code. Ordinary foreground clients keep native
input. The tested ROTP core also cancels native cursor capture at the same entry,
so the Bridge cursor-operation counter stayed zero in this combined run; it must
not be cited as an independently exercised Bridge-only capture test.

The user's other foreground applications remained active. The system clipboard
sequence changed during the observation interval, so this run does not claim an
unchanged system clipboard or attribute those changes to a particular process.
Only sequence numbers were observed; clipboard contents were neither read nor
written by the observer.

No unsafe old-version reproduction, physical input injection, foreground focus
change, Desktop switch or system language/driver change was used.
