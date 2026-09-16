#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { test } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER_FILE = path.join(HERE, "mineclient-bridge-mcp.mjs");
const MAX_LINE_BYTES = 1024 * 1024;
const TOOL_NAMES = [
  "minecraft_client_launch",
  "minecraft_client_register",
  "minecraft_client_status",
  "minecraft_client_frame",
  "minecraft_client_query",
  "minecraft_client_input",
  "minecraft_client_close"
];

function request(id, method, params = {}) {
  return { jsonrpc: "2.0", id, method, params };
}

function line(message) {
  return `${JSON.stringify(message)}\n`;
}

// Injected chunks exercise exact parser boundaries that OS pipes may otherwise coalesce.
async function runServer({ input = "", script, expectedExit = 0 }) {
  const namespace = `self-test-framing-${randomUUID()}`;
  const stateRoot = path.resolve(HERE, namespace);
  assert.equal(path.dirname(stateRoot), HERE);
  const args = script === undefined ? [SERVER_FILE] : [
    "--input-type=module", "-e",
    `await import(${JSON.stringify(pathToFileURL(SERVER_FILE).href)});\n${script}\n` +
      'process.stdin.destroy(); process.stdin.emit("end");'
  ];
  const child = spawn(process.execPath, args, {
    env: {
      ...process.env,
      NODE_ENV: "test",
      MINECLIENT_BRIDGE_TEST_NAMESPACE: namespace
    },
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true
  });
  const stdout = [];
  const stderr = [];
  let timeout;
  let timedOut = false;
  const closed = new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("close", (code, signal) => resolve({ code, signal }));
  });
  child.stdout.on("data", (chunk) => stdout.push(chunk));
  child.stderr.on("data", (chunk) => stderr.push(chunk));
  child.stdin.on("error", (error) => {
    if (error.code !== "EPIPE" && error.code !== "ERR_STREAM_DESTROYED") {
      stderr.push(Buffer.from(String(error)));
    }
  });
  try {
    timeout = setTimeout(() => {
      timedOut = true;
      child.kill();
    }, 10_000);
    if (script === undefined) {
      child.stdin.end(input);
    }
    const result = await closed;
    assert.equal(timedOut, false, "MCP transport test timed out");
    assert.equal(result.signal, null);
    assert.equal(result.code, expectedExit);
    assert.equal(Buffer.concat(stderr).toString("utf8"), "", "MCP stderr must remain clean");
    const output = Buffer.concat(stdout).toString("utf8");
    assert(output.endsWith("\n"), "Every MCP response must end with LF");
    assert.equal(output.includes("Content-Length:"), false, "MCP stdout cannot use header framing");
    return output.slice(0, -1).split("\n").map((body) => {
      assert.notEqual(body.trim(), "", "MCP stdout cannot contain blank lines or diagnostics");
      const message = JSON.parse(body);
      assert.equal(message.jsonrpc, "2.0");
      return message;
    });
  } finally {
    clearTimeout(timeout);
    if (child.exitCode === null && child.signalCode === null) {
      child.kill();
      await closed.catch(() => {});
    }
    // These transport-only sessions never register clients; refuse nonempty cleanup.
    await fs.rmdir(stateRoot).catch((error) => {
      if (error.code !== "ENOENT") {
        throw error;
      }
    });
    await assert.rejects(fs.stat(stateRoot), { code: "ENOENT" });
  }
}

test("stdio initialize and coalesced tools/list expose the seven standard tools", async () => {
  const messages = await runServer({
    input: line(request(1, "initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "ndjson-regression", version: "1" }
    })) + line({ jsonrpc: "2.0", method: "notifications/initialized" }) +
      line(request(2, "tools/list"))
  });
  assert.equal(messages.length, 2, "Notifications must not produce a response");
  assert.deepEqual(messages.map((message) => message.id), [1, 2]);
  assert.deepEqual(messages[0].result.serverInfo, { name: "mineclient-bridge", version: "1.1.5" });
  assert.deepEqual(messages[1].result.tools.map((tool) => tool.name), TOOL_NAMES);
});

test("fragmented UTF-8 survives byte splits and adjacent CRLF/blank lines", async () => {
  const method = "unknown-\u4e2d\u6587-\ud83d\ude80";
  const payload = line(request(3, method));
  const messages = await runServer({
    script: `const bytes = Buffer.from(${JSON.stringify(payload)});\n` +
      'for (const byte of bytes) process.stdin.emit("data", Buffer.from([byte]));\n' +
      `process.stdin.emit("data", Buffer.from(${JSON.stringify("\n\r\n" + line(request(4, "initialize")).replace(/\n$/, "\r\n") + line(request(5, "tools/list")))}));`
  });
  assert.deepEqual(messages.map((message) => message.id), [3, 4, 5]);
  assert.equal(messages[0].error.code, -32601);
  assert.equal(messages[0].error.message, `Method not found: ${method}`);
  assert.equal(messages[1].result.serverInfo.name, "mineclient-bridge");
  assert.deepEqual(messages[2].result.tools.map((tool) => tool.name), TOOL_NAMES);
});

test("malformed JSON reports a bounded error and the next line still works", async () => {
  const messages = await runServer({ input: "{broken\n" + line(request(6, "tools/list")) });
  assert.equal(messages.length, 2);
  assert.deepEqual(messages[0], {
    jsonrpc: "2.0", id: null, error: { code: -32700, message: "Invalid JSON" }
  });
  assert.equal(messages[1].id, 6);
});

for (const terminated of [true, false]) {
  test(`exact 1 MiB line is accepted ${terminated ? "complete" : "before a separate LF"}`, async () => {
    const messages = await runServer({
      script: `const base = JSON.stringify(${JSON.stringify(request(7, "initialize"))});\n` +
        `const bytes = Buffer.from(base + " ".repeat(${MAX_LINE_BYTES} - Buffer.byteLength(base)));\n` +
        (terminated
          ? 'process.stdin.emit("data", Buffer.concat([bytes, Buffer.from("\\n")]));'
          : 'process.stdin.emit("data", bytes); process.stdin.emit("data", Buffer.from("\\n"));')
    });
    assert.equal(messages.length, 1);
    assert.equal(messages[0].id, 7);
    assert.equal(messages[0].result.serverInfo.name, "mineclient-bridge");
  });

  test(`oversized ${terminated ? "complete" : "unterminated"} line is rejected by byte count`, async () => {
    const messages = await runServer({
      expectedExit: 1,
      script: `const base = JSON.stringify(${JSON.stringify(request(8, "initialize"))});\n` +
        `const bytes = Buffer.from(base + " ".repeat(${MAX_LINE_BYTES} + 1 - Buffer.byteLength(base)));\n` +
        `process.stdin.emit("data", ${terminated ? 'Buffer.concat([bytes, Buffer.from("\\n")])' : "bytes"});`
    });
    assert.deepEqual(messages, [{
      jsonrpc: "2.0", id: null,
      error: { code: -32700, message: "MCP message line is too large" }
    }]);
  });
}

test("multibyte payload limit counts UTF-8 bytes, not characters", async () => {
  const messages = await runServer({
    expectedExit: 1,
    script: `const message = ${JSON.stringify(request(9, "initialize"))};\n` +
      `message.params.padding = "\\u4e2d".repeat(${Math.ceil(MAX_LINE_BYTES / 3)});\n` +
      'process.stdin.emit("data", Buffer.from(JSON.stringify(message) + "\\n"));'
  });
  assert.deepEqual(messages, [{
    jsonrpc: "2.0", id: null,
    error: { code: -32700, message: "MCP message line is too large" }
  }]);
});
