#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fsSync from "node:fs";
import fs from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER_FILE = path.join(HERE, "mineclient-bridge-mcp.mjs");
const PREPARED_PARENT = path.win32.join(
  fsSync.realpathSync.native(os.tmpdir()),
  "mineclient-bridge-self-test-runs"
);
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64"
);
let lastClientStderr = "";

if (process.argv[2] === "--fixture-bridge") {
  await runFixtureBridge(process.argv[3]);
} else {
  await runSelfTest();
}

async function runSelfTest() {
  const suffix = `${process.pid}-${Date.now().toString(36)}`;
  const namespace = `self-test-${suffix}`;
  const launchRun = `mineclient-launch-${suffix}`;
  const invalidLaunchRun = `mineclient-invalid-${suffix}`;
  const manualRun = `mineclient-register-${suffix}`;
  const exitRun = `mineclient-exit-${suffix}`;
  const overlapRun = `mineclient-overlap-${suffix}`;
  const launchRoot = path.join(PREPARED_PARENT, launchRun);
  const invalidLaunchRoot = path.join(PREPARED_PARENT, invalidLaunchRun);
  const manualRoot = path.join(PREPARED_PARENT, `mineclient-manual-${suffix}`);
  const stateRoot = path.join(HERE, namespace);
  const manualToken = `manual-token-${suffix}`;
  const createdRoots = [launchRoot, invalidLaunchRoot, manualRoot];

  let client;
  let manualBridge;
  let fixturePid = null;
  const allToolResponses = [];

  try {
    await fs.mkdir(PREPARED_PARENT, { recursive: true });
    await createPreparedFixture(launchRoot, launchRun, `FixtureDesktop-${launchRun}`, false);
    await createPreparedFixture(invalidLaunchRoot, invalidLaunchRun, `FixtureDesktop-${invalidLaunchRun}`, true);

    const manualRuntime = path.join(manualRoot, "runtime");
    const manualEvidence = path.join(manualRoot, "evidence");
    const rejectRuntime = path.join(manualRoot, "reject-runtime");
    const rejectEvidence = path.join(manualRoot, "reject-evidence");
    const exitRuntime = path.join(manualRoot, "exit-runtime");
    const exitEvidence = path.join(manualRoot, "exit-evidence");
    await Promise.all([
      fs.mkdir(manualRuntime, { recursive: true }),
      fs.mkdir(manualEvidence, { recursive: true }),
      fs.mkdir(rejectRuntime, { recursive: true }),
      fs.mkdir(rejectEvidence, { recursive: true }),
      fs.mkdir(exitRuntime, { recursive: true }),
      fs.mkdir(exitEvidence, { recursive: true })
    ]);

    const manualIdentity = {
      run_id: manualRun,
      process_id: process.pid,
      desktop_name: `ManualDesktop-${manualRun}`,
      runtime_root: manualRuntime,
      evidence_root: manualEvidence
    };
    manualBridge = await createBridgeServer({
      identity: manualIdentity,
      token: manualToken,
      closeOnRequest: false
    });

    client = createMcpClient({
      NODE_ENV: "test",
      MINECLIENT_BRIDGE_TEST_NAMESPACE: namespace,
      MINECLIENT_BRIDGE_PREPARED_ROOT_PARENT: PREPARED_PARENT,
      MINECLIENT_BRIDGE_TEST_LAUNCH_TIMEOUT_MS: "15000",
      MINECLIENT_BRIDGE_TEST_POLL_INTERVAL_MS: "50"
    });

    const initialized = await client.request("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "mineclient-bridge-self-test", version: "1.1.0" }
    });
    assert.equal(initialized.result.serverInfo.name, "mineclient-bridge");
    assert.equal(initialized.result.serverInfo.version, "1.1.0");

    const listed = await client.request("tools/list", {});
    const toolNames = listed.result.tools.map((tool) => tool.name);
    assert.deepEqual(toolNames, [
      "minecraft_client_launch",
      "minecraft_client_register",
      "minecraft_client_status",
      "minecraft_client_frame",
      "minecraft_client_query",
      "minecraft_client_input",
      "minecraft_client_close"
    ]);
    const inputTool = listed.result.tools.find((tool) => tool.name === "minecraft_client_input");
    const inputKinds = inputTool.inputSchema.oneOf
      .map((schema) => schema.properties.kind.const)
      .sort();
    assert.deepEqual(inputKinds, [
      "command",
      "key",
      "look",
      "mouse",
      "raw_key",
      "release_all",
      "text"
    ]);
    const commandSchema = inputTool.inputSchema.oneOf.find(
      (schema) => schema.properties.kind.const === "command"
    );
    assert.equal(commandSchema.properties.command.maxLength, 512);

    const commonReject = {
      run_id: `mineclient-reject-${suffix}`,
      token: manualToken,
      process_id: process.pid,
      desktop_name: `RejectDesktop-${suffix}`,
      runtime_root: rejectRuntime,
      evidence_root: rejectEvidence
    };
    await expectToolError(client, "minecraft_client_register", {
      ...commonReject,
      base_url: "http://192.0.2.10:34567"
    }, /loopback/);

    await expectToolError(client, "minecraft_client_register", {
      ...manualIdentity,
      base_url: manualBridge.baseUrl,
      token: manualToken,
      process_id: process.pid + 1
    }, /process_id/);

    await expectToolError(client, "minecraft_client_register", {
      ...commonReject,
      base_url: manualBridge.baseUrl,
      process_id: process.pid
    }, /run_id/);

    const manualDescriptor = {
      ...manualIdentity,
      base_url: manualBridge.baseUrl,
      token: manualToken
    };
    const registeredResult = await client.callTool("minecraft_client_register", manualDescriptor);
    allToolResponses.push(registeredResult);
    const registered = textPayload(registeredResult);
    assert.equal(registered.registered, true);
    assert.equal(registered.session.run_id, manualRun);
    assert.equal(Object.hasOwn(registered.session, "token"), false);
    assert.equal(JSON.stringify(registeredResult).includes(manualToken), false);

    const manualDescriptorFile = path.join(stateRoot, `${manualRun}.json`);
    const storedManual = JSON.parse(await fs.readFile(manualDescriptorFile, "utf8"));
    assert.deepEqual(storedManual, manualDescriptor);

    const statusResult = await client.callTool("minecraft_client_status", { run_id: manualRun });
    allToolResponses.push(statusResult);
    const status = textPayload(statusResult);
    assert.equal(status.bridge.run_id, manualRun);
    assert.equal(status.bridge.token_echo, "[redacted]");
    assert.equal(JSON.stringify(statusResult).includes(manualToken), false);

    const frameResult = await client.callTool("minecraft_client_frame", { run_id: manualRun });
    allToolResponses.push(frameResult);
    assert.equal(frameResult.isError, false);
    const image = frameResult.content.find((block) => block.type === "image");
    const identityText = frameResult.content.find((block) => block.type === "text");
    assert.equal(image.mimeType, "image/png");
    assert.deepEqual(Buffer.from(image.data, "base64"), PNG);
    assert.match(identityText.text, new RegExp(`run_id=${manualRun}`));

    await expectToolError(client, "minecraft_client_register", {
      run_id: overlapRun,
      base_url: "http://127.0.0.1:65534",
      token: manualToken,
      process_id: process.pid + 10,
      desktop_name: `OverlapDesktop-${suffix}`,
      runtime_root: manualRuntime,
      evidence_root: manualEvidence
    }, /roots overlap/);

    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: manualRun,
      kind: "release_all"
    }));
    await fs.unlink(manualDescriptorFile);

    await expectToolError(client, "minecraft_client_launch", {
      run_id: invalidLaunchRun,
      prepared_root: invalidLaunchRoot
    }, /final-preflight/);
    assert.equal(
      await exists(path.join(invalidLaunchRoot, "game", "config", "mineclient-bridge.json")),
      false
    );

    const launchResult = await client.callTool(
      "minecraft_client_launch",
      { run_id: launchRun, prepared_root: launchRoot },
      20_000
    );
    allToolResponses.push(launchResult);
    if (launchResult.isError) {
      const diagnostics = {};
      for (const name of [
        "fixture-launch-started.txt",
        "fixture-launch-error.txt",
        "fixture-child-pid.txt",
        "fixture-stdout.log",
        "fixture-stderr.log"
      ]) {
        const file = path.join(launchRoot, name);
        if (await exists(file)) {
          diagnostics[name] = await fs.readFile(file, "utf8");
        }
      }
      throw new Error(
        `Launch fixture failed: ${launchResult.content[0]?.text ?? "unknown"} diagnostics=${JSON.stringify(diagnostics)}`
      );
    }
    const launched = textPayload(launchResult);
    assert.equal(launched.readiness, "ready");
    assert.equal(launched.run_id, launchRun);
    assert.equal(launched.desktop_name, `FixtureDesktop-${launchRun}`);
    assert.equal(launched.runtime_root, path.join(launchRoot, "game"));
    assert.equal(launched.evidence_root, path.join(launchRoot, "game", "captures"));

    const bridgeConfigPath = path.join(launchRoot, "game", "config", "mineclient-bridge.json");
    const bridgeConfig = JSON.parse(await fs.readFile(bridgeConfigPath, "utf8"));
    const bridgeTokenPath = path.join(launchRoot, "game", "config", "mineclient-bridge.token");
    const bridgeToken = await fs.readFile(bridgeTokenPath, "utf8");
    assert.equal(bridgeConfig.enabled, true);
    assert.equal(bridgeConfig.host, "127.0.0.1");
    assert(Number.isInteger(bridgeConfig.port) && bridgeConfig.port > 0);
    assert.deepEqual(Object.keys(bridgeConfig).sort(), ["enabled", "host", "port"]);
    assert(bridgeToken.length >= 32);
    assert.equal(JSON.stringify(launchResult).includes(bridgeToken), false);

    const launchedDescriptorFile = path.join(stateRoot, `${launchRun}.json`);
    const launchedDescriptor = JSON.parse(await fs.readFile(launchedDescriptorFile, "utf8"));
    assert.equal(launchedDescriptor.token, bridgeToken);
    assert.equal(launchedDescriptor.process_id, launched.process_id);
    assert.equal(Object.keys(launchedDescriptor).length, 7);

    const launchedStatusResult = await client.callTool("minecraft_client_status", { run_id: launchRun });
    allToolResponses.push(launchedStatusResult);
    assert.equal(JSON.stringify(launchedStatusResult).includes(bridgeToken), false);

    const launchedFrameResult = await client.callTool("minecraft_client_frame", { run_id: launchRun });
    allToolResponses.push(launchedFrameResult);
    assert.deepEqual(
      Buffer.from(launchedFrameResult.content.find((block) => block.type === "image").data, "base64"),
      PNG
    );

    const queryExpectations = [
      ["capabilities", undefined, "/control/capabilities"],
      ["state", 32, "/control/state?radius=32"],
      ["screen", undefined, "/control/screen"],
      ["keymaps", undefined, "/control/keymaps"]
    ];
    for (const [kind, radius, expectedPath] of queryExpectations) {
      const args = { run_id: launchRun, kind };
      if (radius !== undefined) {
        args.radius = radius;
      }
      const result = await client.callTool("minecraft_client_query", args);
      allToolResponses.push(result);
      const payload = textPayload(result);
      assert.equal(payload.kind, kind);
      assert.equal(payload.result.path, expectedPath);
      assert.equal(JSON.stringify(result).includes(bridgeToken), false);
    }
    await expectToolError(client, "minecraft_client_query", {
      run_id: launchRun,
      kind: "state",
      radius: 33
    }, /radius/);

    for (const action of ["press", "release", "tap"]) {
      allToolResponses.push(await client.callTool("minecraft_client_input", {
        run_id: launchRun,
        kind: "key",
        mapping: "key.jump",
        action
      }));
    }
    for (const [key, action] of [
      ["enter", "tap"],
      ["key.keyboard.escape", "press"],
      ["f1", "release"]
    ]) {
      allToolResponses.push(await client.callTool("minecraft_client_input", {
        run_id: launchRun,
        kind: "raw_key",
        key,
        action
      }));
    }
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "look",
      yaw: 12.5,
      pitch: -4,
      relative: true
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "mouse",
      x: 120,
      y: 80,
      button: 1,
      action: "click"
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "mouse",
      x: 120,
      y: 80,
      scrollY: -1,
      action: "scroll"
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "text",
      text: "MineClient Bridge",
      submit: true
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "text",
      text: "/say submitted through chat",
      submit: true
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: "/say submitted directly"
    }));
    const maximumCommand = "x".repeat(256);
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: maximumCommand
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: `/${maximumCommand}`
    }));
    await expectToolError(client, "minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: ""
    }, /command/);
    await expectToolError(client, "minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: `/${"x".repeat(257)}`
    }, /1 to 256/);
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "release_all"
    }));

    const fixtureLog = path.join(launchRoot, "game", "captures", "fixture-requests.jsonl");
    await waitFor(() => existsWithContent(fixtureLog), 3000, "fixture request log");
    const fixtureRequests = (await fs.readFile(fixtureLog, "utf8"))
      .trim()
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => JSON.parse(line));
    const keyBodies = fixtureRequests
      .filter((entry) => entry.path === "/control/key")
      .map((entry) => entry.body);
    assert.deepEqual(keyBodies, [
      { mapping: "key.jump", action: "down" },
      { mapping: "key.jump", action: "up" },
      { mapping: "key.jump", action: "click" }
    ]);
    assert.deepEqual(
      fixtureRequests
        .filter((entry) => entry.path === "/control/raw-key")
        .map((entry) => entry.body),
      [
        { key: "enter", action: "click" },
        { key: "key.keyboard.escape", action: "down" },
        { key: "f1", action: "up" }
      ]
    );
    assert.deepEqual(
      fixtureRequests.find((entry) => entry.path === "/control/look").body,
      { yaw: 12.5, pitch: -4, relative: true }
    );
    assert.deepEqual(
      fixtureRequests.find(
        (entry) => entry.path === "/control/mouse" && entry.body?.action === "click"
      ).body,
      { x: 120, y: 80, action: "click", button: 1 }
    );
    assert.deepEqual(
      fixtureRequests.find(
        (entry) => entry.path === "/control/mouse" && entry.body?.action === "scroll"
      ).body,
      { x: 120, y: 80, action: "scroll", scrollY: -1 }
    );
    assert.deepEqual(
      fixtureRequests
        .filter((entry) => entry.path === "/control/text")
        .map((entry) => entry.body),
      [
        { text: "MineClient Bridge", submit: true },
        { text: "/say submitted through chat", submit: true }
      ]
    );
    assert.deepEqual(
      fixtureRequests
        .filter((entry) => entry.path === "/control/command")
        .map((entry) => entry.body),
      [
        { command: "/say submitted directly" },
        { command: maximumCommand },
        { command: `/${maximumCommand}` }
      ]
    );
    const heldRawKeyIndex = fixtureRequests.findIndex(
      (entry) => entry.path === "/control/raw-key" && entry.body?.action === "down"
    );
    const releaseAllIndex = fixtureRequests.findIndex(
      (entry) => entry.path === "/control/release-all"
    );
    assert(heldRawKeyIndex >= 0);
    assert(releaseAllIndex > heldRawKeyIndex);

    const launchedClose = await client.callTool(
      "minecraft_client_close",
      { run_id: launchRun },
      20_000
    );
    allToolResponses.push(launchedClose);
    assert.equal(textPayload(launchedClose).closed, true);
    await waitFor(
      () => exists(path.join(launchRoot, "game", "captures", "fixture-closed.marker")),
      3000,
      "fixture close marker"
    );
    assert.equal(await exists(launchedDescriptorFile), false);

    const fixturePidPath = path.join(launchRoot, "fixture-child-pid.txt");
    if (await exists(fixturePidPath)) {
      fixturePid = Number.parseInt((await fs.readFile(fixturePidPath, "utf8")).trim(), 10);
    }

    manualIdentity.run_id = exitRun;
    manualIdentity.desktop_name = `ExitDesktop-${exitRun}`;
    manualIdentity.runtime_root = exitRuntime;
    manualIdentity.evidence_root = exitEvidence;
    manualBridge.requests.length = 0;
    const exitDescriptor = {
      ...manualIdentity,
      base_url: manualBridge.baseUrl,
      token: manualToken
    };
    const exitRegistered = await client.callTool("minecraft_client_register", exitDescriptor);
    allToolResponses.push(exitRegistered);
    assert.equal(textPayload(exitRegistered).registered, true);

    await client.end();
    client = null;
    assert(
      manualBridge.requests.some((entry) => entry.path === "/control/release-all"),
      "server exit must best-effort release registered reachable sessions"
    );

    for (const response of allToolResponses) {
      const serialized = JSON.stringify(response);
      assert.equal(serialized.includes(manualToken), false);
      assert.equal(serialized.includes(bridgeToken), false);
    }

    assert.equal(await processIsAlive(fixturePid), false);
    assert.equal(clientStderrWasEmpty(), true);
    process.stdout.write(
      `SELF_TEST_OK tools=7 launch=ready register=exact frame=png query=4 input=15 close=pid-exit exit=release secrets=redacted\n`
    );

    function clientStderrWasEmpty() {
      return client === null ? lastClientStderr.length === 0 : client.stderrText.length === 0;
    }
  } finally {
    if (client) {
      lastClientStderr = client.stderrText;
      await client.end().catch(() => client.kill());
    }
    if (manualBridge) {
      await manualBridge.close();
    }
    if (await processIsAlive(fixturePid)) {
      try {
        process.kill(fixturePid, "SIGTERM");
      } catch {
        // The test-created fixture may have exited between the liveness check and termination.
      }
    }
    for (const root of createdRoots) {
      await safeRemoveTree(root, PREPARED_PARENT, "mineclient-");
    }
    await safeRemoveTree(stateRoot, HERE, "self-test-");
  }
}

async function createPreparedFixture(root, runId, desktopName, mismatchRoot) {
  const game = path.join(root, "game");
  const launchDirectory = path.join(root, "launch");
  await fs.mkdir(game, { recursive: true });
  await fs.mkdir(launchDirectory, { recursive: true });
  await fs.copyFile(fileURLToPath(import.meta.url), path.join(root, "fixture-bridge.mjs"));
  await fs.writeFile(path.join(launchDirectory, "java-arguments.txt"), "# self-test fixture\n", "utf8");
  await fs.writeFile(
    path.join(root, "final-preflight.json"),
    `${JSON.stringify({
      schema_version: 1,
      run_id: runId,
      root: mismatchRoot ? `${root}-mismatch` : root,
      desktop: desktopName
    }, null, 2)}\n`,
    "utf8"
  );

  const launchScript = [
    "$ErrorActionPreference='Stop'",
    `$node='${powerShellQuote(process.execPath)}'`,
    "$root=$PSScriptRoot",
    "$test=Join-Path $root 'fixture-bridge.mjs'",
    "try {",
    "  Set-Content -LiteralPath (Join-Path $root 'fixture-launch-started.txt') -Value 'started' -Encoding ascii",
    "  $child=Start-Process -FilePath $node -ArgumentList @($test,'--fixture-bridge',$root) -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root 'fixture-stdout.log') -RedirectStandardError (Join-Path $root 'fixture-stderr.log') -PassThru",
    "  Set-Content -LiteralPath (Join-Path $root 'fixture-child-pid.txt') -Value $child.Id -Encoding ascii",
    "} catch {",
    "  ($_ | Out-String) | Set-Content -LiteralPath (Join-Path $root 'fixture-launch-error.txt') -Encoding utf8",
    "  throw",
    "}"
  ].join("\r\n");
  await fs.writeFile(path.join(root, "launch.ps1"), `${launchScript}\r\n`, "utf8");
}

function powerShellQuote(value) {
  return String(value).replaceAll("'", "''");
}

async function runFixtureBridge(root) {
  if (typeof root !== "string" || path.win32.basename(root).length === 0) {
    throw new Error("Fixture root is required");
  }
  const config = JSON.parse(
    await fs.readFile(path.join(root, "game", "config", "mineclient-bridge.json"), "utf8")
  );
  const token = await fs.readFile(
    path.join(root, "game", "config", "mineclient-bridge.token"),
    "utf8"
  );
  const identity = {
    run_id: process.env.MINECLIENT_BRIDGE_RUN_ID,
    process_id: process.pid,
    desktop_name: process.env.MINECLIENT_BRIDGE_DESKTOP_NAME,
    runtime_root: process.env.MINECLIENT_BRIDGE_RUNTIME_ROOT,
    evidence_root: process.env.MINECLIENT_BRIDGE_EVIDENCE_ROOT
  };
  await fs.mkdir(identity.evidence_root, { recursive: true });
  const requestLog = path.join(identity.evidence_root, "fixture-requests.jsonl");
  const closeMarker = path.join(identity.evidence_root, "fixture-closed.marker");
  const bridge = await createBridgeServer({
    identity,
    token,
    host: config.host,
    port: config.port,
    closeOnRequest: true,
    record(entry) {
      fsSync.appendFileSync(requestLog, `${JSON.stringify(entry)}\n`, "utf8");
    },
    onClose() {
      fsSync.writeFileSync(closeMarker, "closed\n", "utf8");
    }
  });
  await bridge.closed;
}

async function createBridgeServer(options) {
  const requests = [];
  let resolveClosed;
  const closed = new Promise((resolve) => {
    resolveClosed = resolve;
  });
  const server = http.createServer(async (request, response) => {
    try {
      if (request.headers.authorization !== `Bearer ${options.token}`) {
        writeJson(response, 401, { error: "unauthorized" });
        return;
      }
      const url = new URL(request.url, "http://127.0.0.1");
      const body = await readRequestBody(request);
      const record = {
        method: request.method,
        path: `${url.pathname}${url.search}`,
        body: body ?? null
      };
      requests.push(record);
      options.record?.(record);

      if (request.method === "GET" && url.pathname === "/control/status") {
        writeJson(response, 200, {
          ...options.identity,
          ready: true,
          token_echo: options.token,
          diagnostic: `authenticated:${options.token}`
        });
        return;
      }
      if (request.method === "GET" && url.pathname === "/control/frame") {
        response.writeHead(200, {
          "content-type": "image/png",
          "content-length": PNG.length
        });
        response.end(PNG);
        return;
      }
      if (
        request.method === "GET" &&
        ["/control/capabilities", "/control/state", "/control/screen", "/control/keymaps"].includes(url.pathname)
      ) {
        writeJson(response, 200, {
          path: `${url.pathname}${url.search}`,
          ok: true,
          secret_token: options.token
        });
        return;
      }
      if (
        request.method === "POST" &&
        [
          "/control/key",
          "/control/raw-key",
          "/control/look",
          "/control/mouse",
          "/control/text",
          "/control/command",
          "/control/release-all"
        ].includes(url.pathname)
      ) {
        writeJson(response, 200, { accepted: true, token_echo: options.token });
        return;
      }
      if (request.method === "POST" && url.pathname === "/control/close") {
        writeJson(response, 200, { closed: true, token_echo: options.token });
        if (options.closeOnRequest) {
          options.onClose?.();
          setImmediate(() => server.close());
        }
        return;
      }
      writeJson(response, 404, { error: "not_found" });
    } catch {
      if (!response.headersSent) {
        writeJson(response, 500, { error: "fixture_failure" });
      } else {
        response.destroy();
      }
    }
  });
  server.once("close", () => resolveClosed());
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen({
      host: options.host ?? "127.0.0.1",
      port: options.port ?? 0,
      exclusive: true
    }, resolve);
  });
  const address = server.address();
  assert(address && typeof address !== "string");
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    requests,
    closed,
    async close() {
      if (!server.listening) {
        return;
      }
      await new Promise((resolve) => server.close(resolve));
    }
  };
}

async function readRequestBody(request) {
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > 1024 * 1024) {
      throw new Error("request too large");
    }
    chunks.push(chunk);
  }
  if (total === 0) {
    return undefined;
  }
  return JSON.parse(Buffer.concat(chunks, total).toString("utf8"));
}

function writeJson(response, status, value) {
  const body = Buffer.from(JSON.stringify(value), "utf8");
  response.writeHead(status, {
    "content-type": "application/json",
    "content-length": body.length
  });
  response.end(body);
}

function createMcpClient(extraEnvironment) {
  const child = spawn(process.execPath, [SERVER_FILE], {
    env: { ...process.env, ...extraEnvironment },
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true
  });
  let buffer = Buffer.alloc(0);
  let nextId = 1;
  let stderrText = "";
  let ended = false;
  const pending = new Map();

  child.stdout.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
      const headerEnd = buffer.indexOf("\r\n\r\n");
      if (headerEnd < 0) {
        break;
      }
      const header = buffer.subarray(0, headerEnd).toString("ascii");
      const match = /Content-Length:\s*(\d+)/i.exec(header);
      assert(match, "MCP response must include Content-Length");
      const length = Number.parseInt(match[1], 10);
      const bodyStart = headerEnd + 4;
      const bodyEnd = bodyStart + length;
      if (buffer.length < bodyEnd) {
        break;
      }
      const message = JSON.parse(buffer.subarray(bodyStart, bodyEnd).toString("utf8"));
      buffer = buffer.subarray(bodyEnd);
      const waiting = pending.get(message.id);
      if (waiting) {
        pending.delete(message.id);
        clearTimeout(waiting.timer);
        waiting.resolve(message);
      }
    }
  });
  child.stderr.on("data", (chunk) => {
    stderrText += chunk.toString("utf8");
    lastClientStderr = stderrText;
  });
  child.once("exit", (code) => {
    ended = true;
    for (const waiting of pending.values()) {
      clearTimeout(waiting.timer);
      waiting.reject(new Error(`MCP exited with code ${code}: ${stderrText}`));
    }
    pending.clear();
  });

  return {
    get stderrText() {
      return stderrText;
    },
    request(method, params, timeoutMs = 10_000) {
      const id = nextId++;
      const message = { jsonrpc: "2.0", id, method, params };
      const body = JSON.stringify(message);
      child.stdin.write(`Content-Length: ${Buffer.byteLength(body, "utf8")}\r\n\r\n${body}`);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new Error(`MCP request timed out: ${method}`));
        }, timeoutMs);
        pending.set(id, { resolve, reject, timer });
      });
    },
    async callTool(name, args, timeoutMs = 10_000) {
      const response = await this.request("tools/call", { name, arguments: args }, timeoutMs);
      assert.equal(response.error, undefined);
      return response.result;
    },
    async end() {
      if (ended) {
        return;
      }
      child.stdin.end();
      await Promise.race([
        new Promise((resolve) => child.once("exit", resolve)),
        new Promise((_, reject) => setTimeout(() => reject(new Error("MCP did not exit")), 5000))
      ]);
      lastClientStderr = stderrText;
    },
    kill() {
      child.kill();
    }
  };
}

async function expectToolError(client, name, args, pattern) {
  const result = await client.callTool(name, args);
  assert.equal(result.isError, true, `${name} should return a tool error`);
  const text = result.content.find((block) => block.type === "text")?.text ?? "";
  assert.match(text, pattern);
  assert.equal(text.includes(args.token ?? "__no_token__"), false);
}

function textPayload(result) {
  const text = result.content.find((block) => block.type === "text");
  assert(text, "tool result must include text content");
  if (result.isError) {
    throw new Error(`Unexpected tool error: ${text.text}`);
  }
  return JSON.parse(text.text);
}

async function exists(target) {
  try {
    await fs.lstat(target);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") {
      return false;
    }
    throw error;
  }
}

async function existsWithContent(target) {
  try {
    return (await fs.stat(target)).size > 0;
  } catch (error) {
    if (error?.code === "ENOENT") {
      return false;
    }
    throw error;
  }
}

async function waitFor(predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function processIsAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) {
    return false;
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function safeRemoveTree(target, expectedParent, requiredPrefix) {
  const resolvedTarget = path.win32.resolve(target);
  const resolvedParent = path.win32.resolve(expectedParent);
  const relative = path.win32.relative(resolvedParent, resolvedTarget);
  const name = path.win32.basename(resolvedTarget);
  if (
    relative === "" ||
    relative === ".." ||
    relative.startsWith(`..${path.win32.sep}`) ||
    path.win32.isAbsolute(relative) ||
    !name.startsWith(requiredPrefix)
  ) {
    throw new Error(`Refusing unsafe self-test cleanup target: ${resolvedTarget}`);
  }
  await fs.rm(resolvedTarget, { recursive: true, force: true });
}
