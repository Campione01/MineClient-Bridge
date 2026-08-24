#!/usr/bin/env node

import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import fsSync from "node:fs";
import fs from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const SERVER_NAME = "mineclient-bridge";
const SERVER_VERSION = "1.0.0";
const SERVER_DIR = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_PREPARED_ROOT_PARENT = path.win32.join(os.tmpdir(), "mineclient-bridge-runs");
const PREPARED_ROOT_ADAPTER = Object.freeze({
  name: "isolated-prepared-root-v1",
  rootParent: configuredWindowsPath(
    "MINECLIENT_BRIDGE_PREPARED_ROOT_PARENT",
    DEFAULT_PREPARED_ROOT_PARENT
  ),
  preflightFile: "final-preflight.json",
  launcherFile: "launch.ps1",
  javaArgumentsFile: path.win32.join("launch", "java-arguments.txt")
});
const DEFAULT_LAUNCH_TIMEOUT_MS = 240_000;
const DEFAULT_POLL_INTERVAL_MS = 500;
const DEFAULT_CLOSE_TIMEOUT_MS = 15_000;
const MAX_RPC_BODY_BYTES = 1 * 1024 * 1024;
const MAX_JSON_RESPONSE_BYTES = 1 * 1024 * 1024;
const MAX_FRAME_BYTES = 32 * 1024 * 1024;
const MAX_PREFLIGHT_BYTES = 1 * 1024 * 1024;
const MAX_LAUNCH_SCRIPT_BYTES = 2 * 1024 * 1024;
const MAX_JAVA_ARGUMENTS_BYTES = 16 * 1024 * 1024;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const DESCRIPTOR_KEYS = [
  "run_id",
  "base_url",
  "token",
  "process_id",
  "desktop_name",
  "runtime_root",
  "evidence_root"
];

const testNamespace = getTestNamespace();
const STATE_DIR = testNamespace
  ? path.join(SERVER_DIR, testNamespace)
  : configuredWindowsPath(
      "MINECLIENT_BRIDGE_STATE_DIR",
      path.win32.join(process.env.LOCALAPPDATA || os.homedir(), "MineClientBridge", "mcp-sessions")
    );
const launchTimeoutMs = getTestNumber("MINECLIENT_BRIDGE_TEST_LAUNCH_TIMEOUT_MS", DEFAULT_LAUNCH_TIMEOUT_MS);
const pollIntervalMs = getTestNumber("MINECLIENT_BRIDGE_TEST_POLL_INTERVAL_MS", DEFAULT_POLL_INTERVAL_MS);

const tools = [
  {
    name: "minecraft_client_launch",
    description: "Launch one isolated prepared Minecraft client root through its fixed adapter launcher and register it after authenticated readiness checks.",
    inputSchema: {
      type: "object",
      properties: {
        run_id: { type: "string", description: "Prepared run identifier; it must equal the prepared root basename." },
        prepared_root: { type: "string", description: "Absolute prepared root below D:\\Codex\\.codex\\tmp." }
      },
      required: ["run_id", "prepared_root"],
      additionalProperties: false
    },
    annotations: { destructiveHint: true, openWorldHint: false }
  },
  {
    name: "minecraft_client_register",
    description: "Register an already-running authenticated loopback Minecraft client bridge after exact process and isolation identity checks.",
    inputSchema: {
      type: "object",
      properties: {
        run_id: { type: "string" },
        base_url: { type: "string", description: "Canonical numeric loopback HTTP origin with an explicit port." },
        token: { type: "string", description: "Bearer token. It is persisted locally but never returned." },
        process_id: { type: "integer", minimum: 1 },
        desktop_name: { type: "string" },
        runtime_root: { type: "string" },
        evidence_root: { type: "string" }
      },
      required: DESCRIPTOR_KEYS,
      additionalProperties: false
    },
    annotations: { destructiveHint: true, openWorldHint: false }
  },
  {
    name: "minecraft_client_status",
    description: "Read status for one registered Minecraft client after exact bridge identity revalidation.",
    inputSchema: runIdSchema(),
    annotations: { readOnlyHint: true, openWorldHint: false }
  },
  {
    name: "minecraft_client_frame",
    description: "Capture the current PNG frame from one registered Minecraft client.",
    inputSchema: runIdSchema(),
    annotations: { readOnlyHint: true, openWorldHint: false }
  },
  {
    name: "minecraft_client_query",
    description: "Read one fixed client capability, state, screen, or keymap view after exact bridge identity revalidation.",
    inputSchema: {
      oneOf: [
        {
          type: "object",
          properties: {
            run_id: { type: "string" },
            kind: { enum: ["capabilities", "screen", "keymaps"] }
          },
          required: ["run_id", "kind"],
          additionalProperties: false
        },
        {
          type: "object",
          properties: {
            run_id: { type: "string" },
            kind: { const: "state" },
            radius: { type: "integer", minimum: 1, maximum: 32 }
          },
          required: ["run_id", "kind"],
          additionalProperties: false
        }
      ]
    },
    annotations: { readOnlyHint: true, openWorldHint: false }
  },
  {
    name: "minecraft_client_input",
    description: "Send one bounded key, look, mouse, text, or release-all action to one registered Minecraft client.",
    inputSchema: {
      oneOf: [
        {
          type: "object",
          properties: {
            run_id: { type: "string" },
            kind: { const: "key" },
            mapping: { type: "string" },
            action: { enum: ["press", "release", "tap"] }
          },
          required: ["run_id", "kind", "mapping", "action"],
          additionalProperties: false
        },
        {
          type: "object",
          properties: {
            run_id: { type: "string" },
            kind: { const: "look" },
            yaw: { type: "number" },
            pitch: { type: "number" },
            relative: { type: "boolean" }
          },
          required: ["run_id", "kind", "yaw", "pitch", "relative"],
          additionalProperties: false
        },
        {
          type: "object",
          properties: {
            run_id: { type: "string" },
            kind: { const: "mouse" },
            x: { type: "number" },
            y: { type: "number" },
            button: { type: "integer", minimum: 0, maximum: 7 },
            action: { enum: ["move", "press", "release", "click", "scroll"] },
            scrollY: { type: "number" }
          },
          required: ["run_id", "kind", "x", "y", "action"],
          additionalProperties: false
        },
        {
          type: "object",
          properties: {
            run_id: { type: "string" },
            kind: { const: "release_all" }
          },
          required: ["run_id", "kind"],
          additionalProperties: false
        },
        {
          type: "object",
          properties: {
            run_id: { type: "string" },
            kind: { const: "text" },
            text: { type: "string", maxLength: 512 },
            submit: { type: "boolean" }
          },
          required: ["run_id", "kind", "text", "submit"],
          additionalProperties: false
        }
      ]
    },
    annotations: { destructiveHint: true, openWorldHint: false }
  },
  {
    name: "minecraft_client_close",
    description: "Release all input, gracefully close, and forget one exact registered Minecraft client session.",
    inputSchema: runIdSchema(),
    annotations: { destructiveHint: true, openWorldHint: false }
  }
];

let inputBuffer = Buffer.alloc(0);
let requestQueue = Promise.resolve();
let shutdownPromise = null;
const knownSecrets = new Set();

process.stdin.on("data", (chunk) => {
  inputBuffer = Buffer.concat([inputBuffer, chunk]);
  parseMessages();
});

process.stdin.on("end", () => {
  void shutdown(0, false);
});

process.once("SIGINT", () => {
  void shutdown(130, true);
});

process.once("SIGTERM", () => {
  void shutdown(143, true);
});

process.once("beforeExit", () => {
  if (!shutdownPromise) {
    void shutdown(0, false);
  }
});

function runIdSchema() {
  return {
    type: "object",
    properties: { run_id: { type: "string" } },
    required: ["run_id"],
    additionalProperties: false
  };
}

function getTestNamespace() {
  const value = process.env.MINECLIENT_BRIDGE_TEST_NAMESPACE;
  if (!value) {
    return null;
  }
  if (process.env.NODE_ENV !== "test" || !/^self-test-[A-Za-z0-9_-]{1,48}$/.test(value)) {
    throw new Error("Invalid test state namespace");
  }
  return value;
}

function getTestNumber(name, fallback) {
  const value = process.env[name];
  if (process.env.NODE_ENV !== "test" || value === undefined) {
    return fallback;
  }
  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed < 25 || parsed > fallback) {
    throw new Error(`Invalid ${name}`);
  }
  return parsed;
}

function parseMessages() {
  while (true) {
    const headerEnd = inputBuffer.indexOf("\r\n\r\n");
    if (headerEnd < 0) {
      if (inputBuffer.length > 8192) {
        sendError(null, -32700, "MCP frame header is too large");
        process.stdin.pause();
        void shutdown(1, true);
      }
      return;
    }

    const header = inputBuffer.subarray(0, headerEnd).toString("ascii");
    const match = /(?:^|\r\n)Content-Length:\s*(\d+)\s*(?:\r\n|$)/i.exec(header);
    if (!match) {
      inputBuffer = inputBuffer.subarray(headerEnd + 4);
      sendError(null, -32700, "MCP frame is missing Content-Length");
      continue;
    }

    const length = Number.parseInt(match[1], 10);
    if (!Number.isSafeInteger(length) || length < 0 || length > MAX_RPC_BODY_BYTES) {
      sendError(null, -32700, "MCP frame body length is invalid");
      process.stdin.pause();
      void shutdown(1, true);
      return;
    }

    const bodyStart = headerEnd + 4;
    const bodyEnd = bodyStart + length;
    if (inputBuffer.length < bodyEnd) {
      return;
    }

    const body = inputBuffer.subarray(bodyStart, bodyEnd).toString("utf8");
    inputBuffer = inputBuffer.subarray(bodyEnd);

    let request;
    try {
      request = JSON.parse(body);
    } catch {
      sendError(null, -32700, "Invalid JSON");
      continue;
    }

    requestQueue = requestQueue.then(() => handleMessage(request)).catch(() => {
      sendError(request?.id ?? null, -32603, "Internal MCP error");
    });
  }
}

async function handleMessage(request) {
  if (!isPlainObject(request)) {
    sendError(null, -32600, "Invalid request");
    return;
  }

  const { id, method, params } = request;
  const isNotification = id === undefined || id === null;

  try {
    switch (method) {
      case "initialize":
        if (!isNotification) {
          sendResult(id, {
            protocolVersion: params?.protocolVersion ?? "2024-11-05",
            capabilities: { tools: {} },
            serverInfo: { name: SERVER_NAME, version: SERVER_VERSION }
          });
        }
        break;
      case "notifications/initialized":
      case "initialized":
        break;
      case "tools/list":
        if (!isNotification) {
          sendResult(id, { tools });
        }
        break;
      case "tools/call":
        if (!isNotification) {
          sendResult(id, await callTool(params));
        }
        break;
      default:
        if (!isNotification) {
          sendError(id, -32601, `Method not found: ${String(method)}`);
        }
        break;
    }
  } catch (error) {
    if (!isNotification) {
      const requestSecret = typeof params?.arguments?.token === "string" ? params.arguments.token : null;
      sendResult(id, {
        content: [{ type: "text", text: errorToText(error, requestSecret) }],
        isError: true
      });
    }
  }
}

async function callTool(params) {
  if (!isPlainObject(params) || typeof params.name !== "string") {
    throw new Error("tools/call requires a tool name");
  }
  const args = params.arguments ?? {};
  if (!isPlainObject(args)) {
    throw new Error("Tool arguments must be an object");
  }

  switch (params.name) {
    case "minecraft_client_launch":
      return textResult(await launchClient(args));
    case "minecraft_client_register":
      return textResult(await registerClient(args));
    case "minecraft_client_status":
      return textResult(await clientStatus(args));
    case "minecraft_client_frame":
      return clientFrame(args);
    case "minecraft_client_query":
      return textResult(await clientQuery(args));
    case "minecraft_client_input":
      return textResult(await clientInput(args));
    case "minecraft_client_close":
      return textResult(await closeClient(args));
    default:
      throw new Error(`Unknown tool: ${params.name}`);
  }
}

async function launchClient(args) {
  assertExactKeys(args, ["run_id", "prepared_root"]);
  const runId = validateRunId(args.run_id);
  const prepared = await validatePreparedRoot(runId, args.prepared_root);

  const existing = await tryReadDescriptor(runId);
  if (existing) {
    if (
      existing.runtime_root !== prepared.runtimeRoot ||
      existing.evidence_root !== prepared.evidenceRoot ||
      existing.desktop_name !== prepared.desktopName
    ) {
      throw new Error(`run_id ${runId} is registered to a different prepared root`);
    }
    await revalidateSession(existing);
    return launchResult(existing);
  }

  await ensureNormalDirectory(path.join(prepared.runtimeRoot, "config"));
  await assertDescriptorAvailable({
    run_id: runId,
    base_url: "http://127.0.0.1:1",
    token: "launch-validation-placeholder",
    process_id: 1,
    desktop_name: prepared.desktopName,
    runtime_root: prepared.runtimeRoot,
    evidence_root: prepared.evidenceRoot
  }, { ignoreEndpoint: true });

  const configPath = path.join(prepared.runtimeRoot, "config", "mineclient-bridge.json");
  const tokenPath = path.join(prepared.runtimeRoot, "config", "mineclient-bridge.token");
  if (await pathExists(configPath) || await pathExists(tokenPath)) {
    throw new Error(`Bridge config or token already exists for run_id ${runId}; prepared roots are one-shot`);
  }

  const reservation = await reserveLoopbackPort();
  const token = randomBytes(32).toString("base64url");
  knownSecrets.add(token);
  const baseUrl = `http://127.0.0.1:${reservation.port}`;
  const bridgeConfig = {
    enabled: true,
    host: "127.0.0.1",
    port: reservation.port
  };

  let configWritten = false;
  let tokenWritten = false;
  let launcherStarted = false;
  let launcherState = null;
  try {
    await writeExclusiveJson(configPath, bridgeConfig);
    configWritten = true;
    await assertNormalFile(configPath, 1, MAX_JSON_RESPONSE_BYTES);
    await writeExclusiveText(tokenPath, token);
    tokenWritten = true;
    await assertNormalFile(tokenPath, 32, 512);

    await reservation.release();
    launcherState = await spawnPreparedLauncher(prepared);
    launcherStarted = true;

    const deadline = Date.now() + launchTimeoutMs;
    while (Date.now() < deadline) {
      if (launcherState.exited && launcherState.exitCode !== 0) {
        throw new Error(`Prepared launcher exited before readiness for run_id ${runId}`);
      }

      try {
        const status = await requestBridgeStatus({ base_url: baseUrl, token }, Math.min(2000, pollIntervalMs * 4));
        const processId = validatePositiveProcessId(status.process_id, "bridge status process_id");
        const descriptor = {
          run_id: runId,
          base_url: baseUrl,
          token,
          process_id: processId,
          desktop_name: prepared.desktopName,
          runtime_root: prepared.runtimeRoot,
          evidence_root: prepared.evidenceRoot
        };
        assertBridgeIdentity(descriptor, status);
        await delay(Math.min(150, pollIntervalMs));
        await revalidateSession(descriptor, Math.min(2000, pollIntervalMs * 4));
        await saveDescriptor(descriptor);
        return launchResult(descriptor);
      } catch (error) {
        if (isIdentityError(error)) {
          throw error;
        }
      }

      await delay(pollIntervalMs);
    }

    throw new Error(`Minecraft client did not become ready within ${launchTimeoutMs} ms for run_id ${runId}`);
  } catch (error) {
    if (configWritten && !launcherStarted) {
      await removeOwnedConfig(configPath, bridgeConfig);
    }
    if (tokenWritten && !launcherStarted) {
      await removeOwnedText(tokenPath, token);
    }
    throw error;
  } finally {
    await reservation.release();
  }
}

async function registerClient(args) {
  assertExactKeys(args, DESCRIPTOR_KEYS);
  const descriptor = await validateRegistrationDescriptor(args);
  knownSecrets.add(descriptor.token);
  await assertDescriptorAvailable(descriptor);
  const status = await revalidateSession(descriptor);
  await saveDescriptor(descriptor);
  return {
    registered: true,
    session: publicDescriptor(descriptor),
    bridge: sanitizeForOutput(status, descriptor.token)
  };
}

async function clientStatus(args) {
  const runId = parseRunIdOnly(args);
  const descriptor = await readDescriptor(runId);
  const status = await revalidateSession(descriptor);
  return {
    session: publicDescriptor(descriptor),
    bridge: sanitizeForOutput(status, descriptor.token)
  };
}

async function clientFrame(args) {
  const runId = parseRunIdOnly(args);
  const descriptor = await readDescriptor(runId);
  await revalidateSession(descriptor);
  const frame = await requestPng(descriptor, "/control/frame");
  return {
    content: [
      {
        type: "image",
        data: frame.toString("base64"),
        mimeType: "image/png"
      },
      {
        type: "text",
        text: `run_id=${descriptor.run_id} process_id=${descriptor.process_id} desktop_name=${descriptor.desktop_name}`
      }
    ],
    isError: false
  };
}

async function clientQuery(args) {
  const parsed = parseQuery(args);
  const descriptor = await readDescriptor(parsed.runId);
  await revalidateSession(descriptor);
  const result = await requestJson(descriptor, "GET", parsed.endpoint, undefined);
  return {
    run_id: descriptor.run_id,
    process_id: descriptor.process_id,
    kind: parsed.kind,
    result: sanitizeForOutput(result, descriptor.token)
  };
}

async function clientInput(args) {
  const parsed = parseInput(args);
  const descriptor = await readDescriptor(parsed.runId);
  await revalidateSession(descriptor);

  let result;
  if (parsed.kind === "release_all") {
    result = await requestJson(descriptor, "POST", "/control/release-all", undefined);
  } else {
    result = await requestJson(descriptor, "POST", parsed.endpoint, parsed.body);
  }

  return {
    accepted: true,
    run_id: descriptor.run_id,
    process_id: descriptor.process_id,
    kind: parsed.kind,
    bridge: sanitizeForOutput(result, descriptor.token)
  };
}

async function closeClient(args) {
  const runId = parseRunIdOnly(args);
  const descriptor = await readDescriptor(runId);
  if (!isProcessAlive(descriptor.process_id)) {
    await removeDescriptor(runId);
    return {
      closed: true,
      already_exited: true,
      process_exited: true,
      run_id: descriptor.run_id,
      process_id: descriptor.process_id,
      desktop_name: descriptor.desktop_name
    };
  }
  try {
    await revalidateSession(descriptor);
  } catch (error) {
    if (!isProcessAlive(descriptor.process_id)) {
      await removeDescriptor(runId);
      return {
        closed: true,
        already_exited: true,
        process_exited: true,
        run_id: descriptor.run_id,
        process_id: descriptor.process_id,
        desktop_name: descriptor.desktop_name
      };
    }
    throw error;
  }
  await requestJson(descriptor, "POST", "/control/release-all", undefined);
  await revalidateSession(descriptor);
  const result = await requestJson(descriptor, "POST", "/control/close", undefined);
  const deadline = Date.now() + DEFAULT_CLOSE_TIMEOUT_MS;
  while (Date.now() < deadline && isProcessAlive(descriptor.process_id)) {
    await delay(100);
  }
  if (isProcessAlive(descriptor.process_id)) {
    throw new Error(`Client process ${descriptor.process_id} did not exit after the close request`);
  }
  await removeDescriptor(runId);
  return {
    closed: true,
    process_exited: true,
    run_id: descriptor.run_id,
    process_id: descriptor.process_id,
    desktop_name: descriptor.desktop_name,
    bridge: sanitizeForOutput(result, descriptor.token)
  };
}

function parseRunIdOnly(args) {
  assertExactKeys(args, ["run_id"]);
  return validateRunId(args.run_id);
}

function parseInput(args) {
  if (typeof args.kind !== "string") {
    throw new Error("kind must be one of key, look, mouse, text, release_all");
  }
  const runId = validateRunId(args.run_id);

  switch (args.kind) {
    case "key": {
      assertExactKeys(args, ["run_id", "kind", "mapping", "action"]);
      const mapping = validateBoundedString(args.mapping, "mapping", 256);
      const actionMap = { press: "down", release: "up", tap: "click" };
      const wireAction = actionMap[args.action];
      if (!wireAction) {
        throw new Error("key action must be press, release, or tap");
      }
      return {
        runId,
        kind: "key",
        endpoint: "/control/key",
        body: { mapping, action: wireAction }
      };
    }
    case "look": {
      assertExactKeys(args, ["run_id", "kind", "yaw", "pitch", "relative"]);
      const yaw = validateFiniteNumber(args.yaw, "yaw");
      const pitch = validateFiniteNumber(args.pitch, "pitch");
      if (typeof args.relative !== "boolean") {
        throw new Error("relative must be a boolean");
      }
      return {
        runId,
        kind: "look",
        endpoint: "/control/look",
        body: { yaw, pitch, relative: args.relative }
      };
    }
    case "mouse": {
      const allowed = ["run_id", "kind", "x", "y", "button", "action", "scrollY"];
      assertAllowedKeys(args, allowed);
      for (const required of ["run_id", "kind", "x", "y", "action"]) {
        if (!Object.hasOwn(args, required)) {
          throw new Error(`mouse input is missing ${required}`);
        }
      }
      const action = args.action;
      if (!["move", "press", "release", "click", "scroll"].includes(action)) {
        throw new Error("mouse action must be move, press, release, click, or scroll");
      }
      const body = {
        x: validateFiniteNumber(args.x, "x"),
        y: validateFiniteNumber(args.y, "y"),
        action
      };
      if (["press", "release", "click"].includes(action)) {
        body.button = validateMouseButton(args.button);
        if (Object.hasOwn(args, "scrollY")) {
          throw new Error(`scrollY is not valid for mouse action ${action}`);
        }
      } else if (action === "scroll") {
        body.scrollY = validateFiniteNumber(args.scrollY, "scrollY");
        if (Object.hasOwn(args, "button")) {
          throw new Error("button is not valid for mouse action scroll");
        }
      } else if (Object.hasOwn(args, "button") || Object.hasOwn(args, "scrollY")) {
        throw new Error("button and scrollY are not valid for mouse action move");
      }
      return { runId, kind: "mouse", endpoint: "/control/mouse", body };
    }
    case "release_all":
      assertExactKeys(args, ["run_id", "kind"]);
      return { runId, kind: "release_all" };
    case "text":
      assertExactKeys(args, ["run_id", "kind", "text", "submit"]);
      if (typeof args.submit !== "boolean") {
        throw new Error("submit must be a boolean");
      }
      return {
        runId,
        kind: "text",
        endpoint: "/control/text",
        body: { text: validateInputText(args.text, "text", true), submit: args.submit }
      };
    default:
      throw new Error("kind must be one of key, look, mouse, text, release_all");
  }
}

function parseQuery(args) {
  if (!isPlainObject(args)) {
    throw new Error("arguments must be an object");
  }
  const runId = validateRunId(args.run_id);
  switch (args.kind) {
    case "capabilities":
      assertExactKeys(args, ["run_id", "kind"]);
      return { runId, kind: args.kind, endpoint: "/control/capabilities" };
    case "screen":
      assertExactKeys(args, ["run_id", "kind"]);
      return { runId, kind: args.kind, endpoint: "/control/screen" };
    case "keymaps":
      assertExactKeys(args, ["run_id", "kind"]);
      return { runId, kind: args.kind, endpoint: "/control/keymaps" };
    case "state": {
      assertAllowedKeys(args, ["run_id", "kind", "radius"]);
      const actual = Object.keys(args);
      if (!actual.includes("run_id") || !actual.includes("kind")) {
        throw new Error("state query requires run_id and kind");
      }
      if (!Object.hasOwn(args, "radius")) {
        return { runId, kind: args.kind, endpoint: "/control/state" };
      }
      if (!Number.isInteger(args.radius) || args.radius < 1 || args.radius > 32) {
        throw new Error("state radius must be an integer from 1 through 32");
      }
      return { runId, kind: args.kind, endpoint: `/control/state?radius=${args.radius}` };
    }
    default:
      throw new Error("query kind must be capabilities, state, screen, or keymaps");
  }
}

async function validateRegistrationDescriptor(value) {
  const descriptor = {
    run_id: validateRunId(value.run_id),
    base_url: validateLoopbackBaseUrl(value.base_url),
    token: validateToken(value.token),
    process_id: validatePositiveProcessId(value.process_id, "process_id"),
    desktop_name: validateDesktopName(value.desktop_name),
    runtime_root: validateAbsoluteRoot(value.runtime_root, "runtime_root"),
    evidence_root: validateAbsoluteRoot(value.evidence_root, "evidence_root")
  };
  await assertNormalDirectory(descriptor.runtime_root);
  await assertNormalDirectory(descriptor.evidence_root);
  return descriptor;
}

function validateStoredDescriptor(value, fileName) {
  if (!isPlainObject(value)) {
    throw new Error(`Invalid descriptor record ${fileName}`);
  }
  assertExactKeys(value, DESCRIPTOR_KEYS, `descriptor record ${fileName}`);
  const descriptor = {
    run_id: validateRunId(value.run_id),
    base_url: validateLoopbackBaseUrl(value.base_url),
    token: validateToken(value.token),
    process_id: validatePositiveProcessId(value.process_id, "process_id"),
    desktop_name: validateDesktopName(value.desktop_name),
    runtime_root: validateAbsoluteRoot(value.runtime_root, "runtime_root"),
    evidence_root: validateAbsoluteRoot(value.evidence_root, "evidence_root")
  };
  if (fileName !== `${descriptor.run_id}.json`) {
    throw new Error(`Descriptor filename does not match run_id ${descriptor.run_id}`);
  }
  knownSecrets.add(descriptor.token);
  return descriptor;
}

function validateRunId(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$/.test(value)) {
    throw new Error("run_id must contain 1-96 ASCII letters, digits, underscores, or hyphens");
  }
  return value;
}

function validateLoopbackBaseUrl(value) {
  if (typeof value !== "string" || value !== value.trim() || value.length > 128) {
    throw new Error("base_url must be a canonical loopback HTTP origin");
  }
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error("base_url must be a valid URL");
  }
  const host = parsed.hostname.toLowerCase();
  const ipv4Loopback = /^127(?:\.\d{1,3}){3}$/.test(host) &&
    host.split(".").every((part) => Number(part) >= 0 && Number(part) <= 255);
  const ipv6Loopback = host === "[::1]";
  if (
    parsed.protocol !== "http:" ||
    (!ipv4Loopback && !ipv6Loopback) ||
    parsed.username ||
    parsed.password ||
    parsed.pathname !== "/" ||
    parsed.search ||
    parsed.hash ||
    !parsed.port ||
    value !== parsed.origin
  ) {
    throw new Error("base_url must be a canonical numeric loopback HTTP origin with an explicit non-default port");
  }
  return parsed.origin;
}

function validateToken(value) {
  if (
    typeof value !== "string" ||
    value.length < 1 ||
    value.length > 512 ||
    /[\u0000-\u0020\u007f]/.test(value)
  ) {
    throw new Error("token must be a non-empty bearer token without whitespace or control characters");
  }
  return value;
}

function validatePositiveProcessId(value, label) {
  if (!Number.isSafeInteger(value) || value <= 0 || value > 0x7fffffff) {
    throw new Error(`${label} must be a positive 32-bit integer`);
  }
  return value;
}

function validateDesktopName(value) {
  if (
    typeof value !== "string" ||
    value !== value.trim() ||
    value.length < 1 ||
    value.length > 128 ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    throw new Error("desktop_name must be a non-empty bounded string without control characters");
  }
  return value;
}

function validateAbsoluteRoot(value, label) {
  if (
    typeof value !== "string" ||
    value !== value.trim() ||
    !/^[A-Za-z]:\\/.test(value) ||
    value.startsWith("\\\\") ||
    value.includes("\0") ||
    path.win32.normalize(value) !== value
  ) {
    throw new Error(`${label} must be a normalized absolute local Windows path`);
  }
  return value;
}

function validateBoundedString(value, label, maxLength) {
  if (
    typeof value !== "string" ||
    value !== value.trim() ||
    value.length < 1 ||
    value.length > maxLength ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    throw new Error(`${label} must be a non-empty bounded string without control characters`);
  }
  return value;
}

function validateInputText(value, label, allowEmpty) {
  if (
    typeof value !== "string" ||
    value.length > 512 ||
    (!allowEmpty && value.length === 0) ||
    /[\u0000-\u0008\u000a-\u001f\u007f]/.test(value)
  ) {
    throw new Error(`${label} must be bounded text without line breaks or control characters`);
  }
  return value;
}

function configuredWindowsPath(environmentName, fallback) {
  const value = process.env[environmentName] || fallback;
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    !path.win32.isAbsolute(value) ||
    value.startsWith("\\\\") ||
    value.includes("\0") ||
    path.win32.normalize(value) !== value
  ) {
    throw new Error(`${environmentName} must be a normalized absolute local Windows path`);
  }
  return value;
}

function isProcessAlive(processId) {
  try {
    process.kill(processId, 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

function validateFiniteNumber(value, label) {
  if (typeof value !== "number" || !Number.isFinite(value) || Math.abs(value) > 1_000_000) {
    throw new Error(`${label} must be a finite number with absolute value at most 1000000`);
  }
  return value;
}

function validateMouseButton(value) {
  if (!Number.isInteger(value) || value < 0 || value > 7) {
    throw new Error("button must be an integer from 0 through 7");
  }
  return value;
}

async function validatePreparedRoot(runId, preparedRootValue) {
  const preparedRoot = validateAbsoluteRoot(preparedRootValue, "prepared_root");
  if (path.win32.basename(preparedRoot) !== runId) {
    throw new Error("prepared_root basename must exactly equal run_id");
  }

  await assertNormalDirectory(PREPARED_ROOT_ADAPTER.rootParent);
  const parentReal = await fs.realpath(PREPARED_ROOT_ADAPTER.rootParent);
  const lexicalRelative = path.win32.relative(PREPARED_ROOT_ADAPTER.rootParent, preparedRoot);
  if (!isStrictDescendant(lexicalRelative)) {
    throw new Error(`prepared_root must resolve below ${PREPARED_ROOT_ADAPTER.rootParent}`);
  }
  await assertNoReparsePath(PREPARED_ROOT_ADAPTER.rootParent, preparedRoot);
  const preparedReal = await fs.realpath(preparedRoot);
  if (!sameWindowsPath(preparedRoot, preparedReal)) {
    throw new Error("prepared_root must not traverse a reparse point");
  }
  const realRelative = path.win32.relative(parentReal, preparedReal);
  if (!isStrictDescendant(realRelative)) {
    throw new Error(`prepared_root must resolve below ${PREPARED_ROOT_ADAPTER.rootParent}`);
  }
  await assertNoReparseTree(preparedRoot);

  const preflightPath = path.join(preparedRoot, PREPARED_ROOT_ADAPTER.preflightFile);
  const launchScript = path.join(preparedRoot, PREPARED_ROOT_ADAPTER.launcherFile);
  const javaArguments = path.join(preparedRoot, PREPARED_ROOT_ADAPTER.javaArgumentsFile);
  await assertNormalFile(preflightPath, 2, MAX_PREFLIGHT_BYTES);
  await assertNormalFile(launchScript, 1, MAX_LAUNCH_SCRIPT_BYTES);
  await assertNormalFile(javaArguments, 1, MAX_JAVA_ARGUMENTS_BYTES);

  let preflight;
  try {
    preflight = JSON.parse(await fs.readFile(preflightPath, "utf8"));
  } catch {
    throw new Error("final-preflight.json must contain valid JSON");
  }
  if (!isPlainObject(preflight)) {
    throw new Error("final-preflight.json must contain an object");
  }
  const desktopName = validateDesktopName(preflight.desktop);
  if (preflight.run_id !== runId || preflight.root !== preparedRoot) {
    throw new Error("final-preflight.json run_id, root, and desktop must exactly match the launch request");
  }

  const runtimeRoot = path.join(preparedRoot, "game");
  await assertNormalDirectory(runtimeRoot);
  const evidenceRoot = path.join(runtimeRoot, "captures");
  return { runId, preparedRoot, desktopName, runtimeRoot, evidenceRoot, launchScript };
}

async function assertNoReparsePath(parent, target) {
  const relative = path.win32.relative(parent, target);
  if (!isStrictDescendant(relative)) {
    throw new Error("Path is outside the allowed prepared-root parent");
  }
  let current = parent;
  for (const part of relative.split(path.win32.sep)) {
    current = path.join(current, part);
    const stat = await fs.lstat(current);
    if (stat.isSymbolicLink()) {
      throw new Error(`Reparse points are not allowed in prepared roots: ${current}`);
    }
  }
}

async function assertNoReparseTree(root) {
  const pending = [root];
  let entriesSeen = 0;
  while (pending.length > 0) {
    const current = pending.pop();
    const entries = await fs.readdir(current, { withFileTypes: true });
    entriesSeen += entries.length;
    if (entriesSeen > 250_000) {
      throw new Error("prepared_root contains too many entries to validate safely");
    }
    for (const entry of entries) {
      if (entry.isSymbolicLink()) {
        throw new Error(`Reparse points are not allowed in prepared roots: ${path.join(current, entry.name)}`);
      }
      if (entry.isDirectory()) {
        pending.push(path.join(current, entry.name));
      }
    }
  }
}

function isStrictDescendant(relative) {
  return relative !== "" && relative !== ".." && !relative.startsWith(`..${path.win32.sep}`) && !path.win32.isAbsolute(relative);
}

function sameWindowsPath(left, right) {
  return stripExtendedPrefix(path.win32.normalize(left)).toLowerCase() ===
    stripExtendedPrefix(path.win32.normalize(right)).toLowerCase();
}

function stripExtendedPrefix(value) {
  return value.startsWith("\\\\?\\") ? value.slice(4) : value;
}

async function reserveLoopbackPort() {
  const server = net.createServer();
  let released = false;
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen({ host: "127.0.0.1", port: 0, exclusive: true }, resolve);
  });
  const address = server.address();
  if (!address || typeof address === "string") {
    server.close();
    throw new Error("Could not reserve a loopback port");
  }
  return {
    port: address.port,
    async release() {
      if (released) {
        return;
      }
      released = true;
      await new Promise((resolve) => server.close(() => resolve()));
    }
  };
}

async function spawnPreparedLauncher(prepared) {
  const systemRoot = process.env.SystemRoot || "C:\\Windows";
  const powershell = configuredWindowsPath(
    "MINECLIENT_BRIDGE_POWERSHELL",
    path.join(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
  );
  await assertExecutableFile(powershell, 16 * 1024 * 1024);

  const environment = {
    ...process.env,
    MINECLIENT_BRIDGE_RUN_ID: prepared.runId,
    MINECLIENT_BRIDGE_DESKTOP_NAME: prepared.desktopName,
    MINECLIENT_BRIDGE_RUNTIME_ROOT: prepared.runtimeRoot,
    MINECLIENT_BRIDGE_EVIDENCE_ROOT: prepared.evidenceRoot
  };
  const stdoutPath = path.join(prepared.preparedRoot, "mcp-launcher.stdout.log");
  const stderrPath = path.join(prepared.preparedRoot, "mcp-launcher.stderr.log");
  const stdout = fsSync.openSync(stdoutPath, "wx", 0o600);
  const stderr = fsSync.openSync(stderrPath, "wx", 0o600);
  let child;
  try {
    child = spawn(
      powershell,
      [
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-WindowStyle",
        "Hidden",
        "-File",
        prepared.launchScript
      ],
      {
        cwd: prepared.preparedRoot,
        env: environment,
        detached: false,
        shell: false,
        stdio: ["ignore", stdout, stderr],
        windowsHide: true
      }
    );
  } catch (error) {
    fsSync.closeSync(stdout);
    fsSync.closeSync(stderr);
    throw error;
  }

  const state = { exited: false, exitCode: null };
  child.once("exit", (code) => {
    state.exited = true;
    state.exitCode = code;
    fsSync.closeSync(stdout);
    fsSync.closeSync(stderr);
  });
  await new Promise((resolve, reject) => {
    child.once("spawn", resolve);
    child.once("error", () => reject(new Error(`Could not start prepared launcher for run_id ${prepared.runId}`)));
  });
  child.unref();
  return state;
}

function launchResult(descriptor) {
  return {
    readiness: "ready",
    run_id: descriptor.run_id,
    process_id: descriptor.process_id,
    desktop_name: descriptor.desktop_name,
    runtime_root: descriptor.runtime_root,
    evidence_root: descriptor.evidence_root
  };
}

async function revalidateSession(descriptor, timeoutMs = 5000) {
  const status = await requestBridgeStatus(descriptor, timeoutMs);
  assertBridgeIdentity(descriptor, status);
  return status;
}

async function requestBridgeStatus(connection, timeoutMs = 5000) {
  const status = await requestJson(connection, "GET", "/control/status", undefined, timeoutMs);
  if (!isPlainObject(status)) {
    throw new IdentityError("Bridge status must be a JSON object");
  }
  return status;
}

function assertBridgeIdentity(descriptor, status) {
  const checks = [
    ["run_id", descriptor.run_id],
    ["process_id", descriptor.process_id],
    ["desktop_name", descriptor.desktop_name],
    ["runtime_root", descriptor.runtime_root],
    ["evidence_root", descriptor.evidence_root]
  ];
  for (const [field, expected] of checks) {
    if (status[field] !== expected) {
      throw new IdentityError(`Bridge identity mismatch for ${field} on run_id ${descriptor.run_id}`);
    }
  }
}

class IdentityError extends Error {}

function isIdentityError(error) {
  return error instanceof IdentityError;
}

async function requestJson(connection, method, endpoint, body, timeoutMs = 5000) {
  const pending = await bridgeFetch(connection, method, endpoint, body, timeoutMs);
  try {
    const bytes = await readResponseLimited(pending.response, MAX_JSON_RESPONSE_BYTES);
    if (bytes.length === 0) {
      return {};
    }
    const contentType = pending.response.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase();
    if (contentType && contentType !== "application/json") {
      throw new Error(`Bridge returned unexpected content type for ${endpoint}`);
    }
    try {
      return JSON.parse(bytes.toString("utf8"));
    } catch {
      throw new Error(`Bridge returned invalid JSON for ${endpoint}`);
    }
  } finally {
    pending.cleanup();
  }
}

async function requestPng(connection, endpoint) {
  const pending = await bridgeFetch(connection, "GET", endpoint, undefined, 10_000);
  try {
    const contentType = pending.response.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase();
    if (contentType !== "image/png") {
      throw new Error("Bridge frame response must be image/png");
    }
    const bytes = await readResponseLimited(pending.response, MAX_FRAME_BYTES);
    if (bytes.length < PNG_SIGNATURE.length || !bytes.subarray(0, PNG_SIGNATURE.length).equals(PNG_SIGNATURE)) {
      throw new Error("Bridge frame response is not a PNG");
    }
    return bytes;
  } finally {
    pending.cleanup();
  }
}

async function bridgeFetch(connection, method, endpoint, body, timeoutMs) {
  const baseUrl = validateLoopbackBaseUrl(connection.base_url);
  const token = validateToken(connection.token);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const headers = { authorization: `Bearer ${token}` };
  if (body !== undefined) {
    headers["content-type"] = "application/json";
  }

  try {
    const response = await fetch(`${baseUrl}${endpoint}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: "error",
      signal: controller.signal
    });
    if (!response.ok) {
      if (response.body) {
        await response.body.cancel().catch(() => {});
      }
      throw new Error(`Bridge HTTP ${response.status} for ${endpoint}`);
    }
    return { response, cleanup: () => clearTimeout(timer) };
  } catch (error) {
    clearTimeout(timer);
    if (error?.name === "AbortError") {
      throw new Error(`Bridge request timed out for ${endpoint}`);
    }
    if (error instanceof Error && error.message.startsWith("Bridge HTTP ")) {
      throw error;
    }
    throw new Error(`Bridge request failed for ${endpoint} at ${baseUrl}`);
  }
}

async function readResponseLimited(response, maxBytes) {
  const declared = response.headers.get("content-length");
  if (declared !== null && Number(declared) > maxBytes) {
    if (response.body) {
      await response.body.cancel().catch(() => {});
    }
    throw new Error("Bridge response exceeds the allowed size");
  }
  if (!response.body) {
    return Buffer.alloc(0);
  }

  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel().catch(() => {});
      throw new Error("Bridge response exceeds the allowed size");
    }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks, total);
}

async function ensureStateDirectory() {
  await fs.mkdir(STATE_DIR, { recursive: true, mode: 0o700 });
  const stat = await fs.lstat(STATE_DIR);
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error("Descriptor state path must be a normal directory");
  }
  const real = await fs.realpath(STATE_DIR);
  if (!sameWindowsPath(real, STATE_DIR)) {
    throw new Error("Descriptor state directory must not be a reparse point");
  }
  await fs.chmod(STATE_DIR, 0o700);
}

function descriptorPath(runId) {
  return path.join(STATE_DIR, `${validateRunId(runId)}.json`);
}

async function listDescriptors() {
  await ensureStateDirectory();
  const entries = await fs.readdir(STATE_DIR, { withFileTypes: true });
  const descriptors = [];
  for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
    if (!entry.name.endsWith(".json")) {
      continue;
    }
    if (!entry.isFile() || entry.isSymbolicLink()) {
      throw new Error(`Descriptor store contains a non-file record ${entry.name}`);
    }
    const file = path.join(STATE_DIR, entry.name);
    const stat = await fs.lstat(file);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > MAX_JSON_RESPONSE_BYTES) {
      throw new Error(`Descriptor record is invalid: ${entry.name}`);
    }
    let parsed;
    try {
      parsed = JSON.parse(await fs.readFile(file, "utf8"));
    } catch {
      throw new Error(`Descriptor record contains invalid JSON: ${entry.name}`);
    }
    descriptors.push(validateStoredDescriptor(parsed, entry.name));
  }
  return descriptors;
}

async function readDescriptor(runId) {
  await ensureStateDirectory();
  const file = descriptorPath(runId);
  let stat;
  try {
    stat = await fs.lstat(file);
  } catch (error) {
    if (error?.code === "ENOENT") {
      throw new Error(`run_id ${runId} is not registered`);
    }
    throw error;
  }
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > MAX_JSON_RESPONSE_BYTES) {
    throw new Error(`Descriptor record is invalid for run_id ${runId}`);
  }
  let parsed;
  try {
    parsed = JSON.parse(await fs.readFile(file, "utf8"));
  } catch {
    throw new Error(`Descriptor record contains invalid JSON for run_id ${runId}`);
  }
  return validateStoredDescriptor(parsed, `${runId}.json`);
}

async function tryReadDescriptor(runId) {
  try {
    return await readDescriptor(runId);
  } catch (error) {
    if (error instanceof Error && error.message === `run_id ${runId} is not registered`) {
      return null;
    }
    throw error;
  }
}

async function saveDescriptor(descriptor) {
  await ensureStateDirectory();
  const destination = descriptorPath(descriptor.run_id);
  if (await pathExists(destination)) {
    throw new Error(`run_id ${descriptor.run_id} is already registered`);
  }
  const temporary = path.join(
    STATE_DIR,
    `.${descriptor.run_id}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`
  );
  try {
    await fs.writeFile(temporary, `${JSON.stringify(descriptor, null, 2)}\n`, {
      encoding: "utf8",
      flag: "wx",
      mode: 0o600
    });
    await fs.chmod(temporary, 0o600);
    await fs.rename(temporary, destination);
    await fs.chmod(destination, 0o600);
  } finally {
    await fs.rm(temporary, { force: true }).catch(() => {});
  }
}

async function removeDescriptor(runId) {
  await fs.unlink(descriptorPath(runId));
}

async function assertDescriptorAvailable(candidate, options = {}) {
  const existing = await listDescriptors();
  for (const descriptor of existing) {
    if (descriptor.run_id === candidate.run_id) {
      throw new Error(`run_id ${candidate.run_id} is already registered`);
    }
    if (!options.ignoreEndpoint) {
      if (descriptor.base_url === candidate.base_url) {
        throw new Error(`base_url is already registered to run_id ${descriptor.run_id}`);
      }
      if (descriptor.process_id === candidate.process_id) {
        throw new Error(`process_id is already registered to run_id ${descriptor.run_id}`);
      }
      if (descriptor.desktop_name === candidate.desktop_name) {
        throw new Error(`desktop_name is already registered to run_id ${descriptor.run_id}`);
      }
    }
    const existingRoots = [
      await rootComparisonKey(descriptor.runtime_root),
      await rootComparisonKey(descriptor.evidence_root)
    ];
    const candidateRoots = [
      await rootComparisonKey(candidate.runtime_root),
      await rootComparisonKey(candidate.evidence_root)
    ];
    if (candidateRoots.some((left) => existingRoots.some((right) => rootsOverlap(left, right)))) {
      throw new Error(`runtime/evidence roots overlap registered run_id ${descriptor.run_id}`);
    }
  }
}

async function rootComparisonKey(value) {
  try {
    return stripExtendedPrefix(path.win32.normalize(await fs.realpath(value))).toLowerCase();
  } catch {
    return stripExtendedPrefix(path.win32.resolve(value)).toLowerCase();
  }
}

function rootsOverlap(left, right) {
  return left === right ||
    left.startsWith(`${right}${path.win32.sep}`) ||
    right.startsWith(`${left}${path.win32.sep}`);
}

function publicDescriptor(descriptor) {
  return {
    run_id: descriptor.run_id,
    base_url: descriptor.base_url,
    process_id: descriptor.process_id,
    desktop_name: descriptor.desktop_name,
    runtime_root: descriptor.runtime_root,
    evidence_root: descriptor.evidence_root
  };
}

async function ensureNormalDirectory(directory) {
  await fs.mkdir(directory, { recursive: true, mode: 0o700 });
  await assertNormalDirectory(directory);
  await fs.chmod(directory, 0o700);
}

async function assertNormalDirectory(directory) {
  const stat = await fs.lstat(directory);
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error(`Expected a normal directory: ${directory}`);
  }
  const real = await fs.realpath(directory);
  if (!sameWindowsPath(real, directory)) {
    throw new Error(`Directory must not resolve through a reparse point: ${directory}`);
  }
}

async function assertNormalFile(file, minBytes, maxBytes) {
  const stat = await fs.lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size < minBytes || stat.size > maxBytes) {
    throw new Error(`Expected a bounded normal file: ${file}`);
  }
  const real = await fs.realpath(file);
  if (!sameWindowsPath(real, file)) {
    throw new Error(`File must not resolve through a reparse point: ${file}`);
  }
}

async function assertExecutableFile(file, maxBytes) {
  const stat = await fs.lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size < 1 || stat.size > maxBytes) {
    throw new Error(`Expected a bounded executable file: ${file}`);
  }
  const resolved = await fs.realpath(file);
  const resolvedStat = await fs.stat(resolved);
  if (!resolvedStat.isFile() || resolvedStat.size < 1 || resolvedStat.size > maxBytes) {
    throw new Error(`Executable target is invalid: ${file}`);
  }
}

async function writeExclusiveJson(file, value) {
  await fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`, {
    encoding: "utf8",
    flag: "wx",
    mode: 0o600
  });
  await fs.chmod(file, 0o600);
}

async function writeExclusiveText(file, value) {
  await fs.writeFile(file, value, {
    encoding: "utf8",
    flag: "wx",
    mode: 0o600
  });
  await fs.chmod(file, 0o600);
}

async function removeOwnedConfig(file, expected) {
  try {
    const parsed = JSON.parse(await fs.readFile(file, "utf8"));
    if (JSON.stringify(parsed) === JSON.stringify(expected)) {
      await fs.unlink(file);
    }
  } catch {
    // Best effort only; never remove a file whose ownership cannot be proven.
  }
}

async function removeOwnedText(file, expected) {
  try {
    const value = await fs.readFile(file, "utf8");
    if (value === expected) {
      await fs.unlink(file);
    }
  } catch {
    // Best effort only; never remove a file whose ownership cannot be proven.
  }
}

async function pathExists(file) {
  try {
    await fs.lstat(file);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") {
      return false;
    }
    throw error;
  }
}

function assertExactKeys(value, expected, label = "arguments") {
  if (!isPlainObject(value)) {
    throw new Error(`${label} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error(`${label} must contain exactly: ${wanted.join(", ")}`);
  }
}

function assertAllowedKeys(value, allowed) {
  if (!isPlainObject(value)) {
    throw new Error("arguments must be an object");
  }
  const allowedSet = new Set(allowed);
  const extra = Object.keys(value).find((key) => !allowedSet.has(key));
  if (extra) {
    throw new Error(`Unexpected argument: ${extra}`);
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function textResult(value) {
  return {
    content: [{ type: "text", text: JSON.stringify(value, null, 2) }],
    isError: false
  };
}

function sanitizeForOutput(value, directSecret = null, depth = 0) {
  if (depth > 20) {
    return "[truncated]";
  }
  if (typeof value === "string") {
    return redactText(value, directSecret);
  }
  if (Array.isArray(value)) {
    return value.slice(0, 1000).map((item) => sanitizeForOutput(item, directSecret, depth + 1));
  }
  if (isPlainObject(value)) {
    const output = {};
    for (const [key, item] of Object.entries(value).slice(0, 1000)) {
      if (/(?:token|authorization|secret|credential|cookie)/i.test(key)) {
        output[key] = "[redacted]";
      } else {
        output[key] = sanitizeForOutput(item, directSecret, depth + 1);
      }
    }
    return output;
  }
  return value;
}

function redactText(value, directSecret = null) {
  let output = String(value);
  const secrets = directSecret ? [directSecret, ...knownSecrets] : [...knownSecrets];
  for (const secret of secrets) {
    if (typeof secret === "string" && secret.length > 0) {
      output = output.split(secret).join("[redacted]");
    }
  }
  return output;
}

function errorToText(error, directSecret = null) {
  const message = error instanceof Error ? error.message : String(error);
  return redactText(message, directSecret);
}

function sendResult(id, result) {
  sendMessage({ jsonrpc: "2.0", id, result });
}

function sendError(id, code, message) {
  sendMessage({ jsonrpc: "2.0", id, error: { code, message } });
}

function sendMessage(message) {
  const body = JSON.stringify(message);
  process.stdout.write(`Content-Length: ${Buffer.byteLength(body, "utf8")}\r\n\r\n${body}`);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function releaseRegisteredSessions() {
  let descriptors;
  try {
    descriptors = await listDescriptors();
  } catch {
    return;
  }
  await Promise.allSettled(descriptors.map(async (descriptor) => {
    try {
      await revalidateSession(descriptor, 1500);
      await requestJson(descriptor, "POST", "/control/release-all", undefined, 1500);
    } catch {
      // Exit cleanup is intentionally silent and exact-session only.
    }
  }));
}

async function shutdown(exitCode, forceExit) {
  if (shutdownPromise) {
    return shutdownPromise;
  }
  shutdownPromise = (async () => {
    await requestQueue.catch(() => {});
    await releaseRegisteredSessions();
    process.exitCode = exitCode;
    if (forceExit) {
      process.stdout.end(() => process.exit(exitCode));
    } else {
      process.stdout.end();
    }
  })();
  return shutdownPromise;
}
