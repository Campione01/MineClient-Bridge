# 1.1.4

- Use newline-delimited JSON on the MCP stdio transport, as MCP stdio clients
  expect. The 1.1.3 adapter framed each message with a Content-Length header.
- Declare plain object input schemas for `minecraft_client_query` and
  `minecraft_client_input`, so MCP clients that validate the tool list strictly
  load all seven tools. The server still rejects fields that do not belong to
  the chosen kind.
- The NeoForge mod is unchanged apart from its version. No server installation
  is needed.
