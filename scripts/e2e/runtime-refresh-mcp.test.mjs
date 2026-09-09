// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const fixturePath = resolve(
  dirname(fileURLToPath(import.meta.url)),
  "fixtures/runtime-refresh-mcp.mjs",
);

/** 有界轮询协议输出，避免 fixture 自测使用任意 sleep 掩盖通知或请求竞态。 */
async function waitForFrame(frames, predicate, deadline = Date.now() + 5_000) {
  while (Date.now() < deadline) {
    const frame = frames.find(predicate);
    if (frame !== undefined) return frame;
    await new Promise((resolvePromise) => globalThis.setTimeout(resolvePromise, 10));
  }
  throw new Error("timed out waiting for MCP fixture frame");
}

/** 把 stdout 分帧限制在测试 adapter 内，生产 fixture 仍只处理标准 JSONL。 */
function collectFrames(child) {
  const frames = [];
  let buffer = "";
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    buffer += chunk;
    while (buffer.includes("\n")) {
      const newline = buffer.indexOf("\n");
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (line) frames.push(JSON.parse(line));
    }
  });
  return frames;
}

test("runtime refresh MCP publishes list_changed and serves the new schema", async () => {
  const root = await mkdtemp(join(tmpdir(), "ja-runtime-refresh-mcp-"));
  const control = join(root, "control.json");
  const report = join(root, "report.ndjson");
  await writeFile(control, '{"revision":1}\n', "utf8");
  const child = spawn(process.execPath, [fixturePath, control, report], {
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });
  const frames = collectFrames(child);
  const send = (frame) => child.stdin.write(`${JSON.stringify(frame)}\n`);
  try {
    send({ jsonrpc: "2.0", id: 1, method: "initialize", params: {} });
    await waitForFrame(frames, (frame) => frame.id === 1);
    send({ jsonrpc: "2.0", method: "notifications/initialized" });
    send({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} });
    const first = await waitForFrame(frames, (frame) => frame.id === 2);
    assert.equal(first.result.tools[0].inputSchema.properties.value.type, "string");

    await writeFile(control, '{"revision":2}\n', "utf8");
    await waitForFrame(
      frames,
      (frame) => frame.method === "notifications/tools/list_changed",
    );
    send({ jsonrpc: "2.0", id: 3, method: "tools/list", params: {} });
    const second = await waitForFrame(frames, (frame) => frame.id === 3);
    assert.equal(second.result.tools[0].inputSchema.properties.value.type, "integer");

    send({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: { name: "refresh_echo", arguments: { value: 42 } },
    });
    const called = await waitForFrame(frames, (frame) => frame.id === 4);
    assert.equal(called.result.structuredContent.revision, 2);
    assert.equal(called.result.structuredContent.valueType, "number");
  } finally {
    child.stdin.end();
    await new Promise((resolvePromise) => child.once("exit", resolvePromise));
    const reports = (await readFile(report, "utf8"))
      .trim()
      .split("\n")
      .map((line) => JSON.parse(line));
    assert.equal(reports.filter(({ kind }) => kind === "schema_changed").length, 1);
    assert.equal(reports.filter(({ kind }) => kind === "notification").length, 1);
    assert.deepEqual(
      reports.filter(({ kind }) => kind === "tool_call").map(({ revision, valueType }) => ({
        revision,
        valueType,
      })),
      [{ revision: 2, valueType: "number" }],
    );
    await rm(root, { recursive: true, force: true });
  }
});
