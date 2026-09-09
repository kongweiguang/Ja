// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  assertOwnedTemporaryPath,
  buildLaunchEnvironment,
  createLargePngFixture,
  inspectNativeImageRequest,
  parseArguments,
  validateImageHistoryReport,
} from "./image-history-production.mjs";

/** 构造唯一完整的真窗报告基线，使每个负例只改变一个验收不变量。 */
function validReport() {
  return {
    schemaVersion: 1,
    status: "passed",
    runtime: { platform: "win32", surface: "tauri_webview2", boundary: "debug_jar" },
    provider: { kind: "deterministic_loopback", externalCalls: 0 },
    image: { sizeBytes: 409_600, validPng: true },
    successTurn: {
      submittedViaUi: true,
      base64Preserved: true,
      contextLimitVisible: false,
      immediateThumbnail: { naturalWidth: 320, scheme: "ja-attachment" },
    },
    preview: { opened: true, decoded: true },
    reload: { sameThread: true, thumbnail: { naturalWidth: 320 } },
    failureTurn: {
      terminalState: "failed",
      base64Preserved: true,
      attachmentRetained: true,
      contextLimitVisible: false,
    },
  };
}

test("参数要求显式 JAR 与持久证据目录", () => {
  const options = parseArguments([
    "--evidence-directory",
    "target/evidence",
    "--jar",
    "app-server/target/ja-app-server.jar",
  ]);
  assert.match(options.evidenceDirectory, /target[\\/]evidence$/u);
  assert.match(options.jar, /app-server[\\/]target[\\/]ja-app-server\.jar$/u);
  assert.throws(() => parseArguments(["--evidence-directory", "target/evidence"]), /--jar/u);
});

test("自包含 PNG 有效且稳定超过 300 KiB", () => {
  const fixture = createLargePngFixture();
  assert.equal(fixture.bytes.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
  assert.ok(fixture.bytes.length >= 300 * 1024);
  assert.deepEqual({ width: fixture.width, height: fixture.height }, { width: 320, height: 320 });
  assert.equal(fixture.bytes.includes(Buffer.from("IHDR")), true);
  assert.equal(fixture.bytes.includes(Buffer.from("IDAT")), true);
  assert.equal(fixture.bytes.includes(Buffer.from("IEND")), true);
});

test("Provider 请求保持原生 input_image Base64 字节完全一致", () => {
  const fixture = createLargePngFixture();
  const payload = {
    input: [
      {
        role: "user",
        content: [
          { type: "input_text", text: "__JA_IMAGE_HISTORY_SUCCESS__" },
          {
            type: "input_image",
            image_url: `data:image/png;base64,${fixture.bytes.toString("base64")}`,
            detail: "auto",
          },
        ],
      },
    ],
  };
  assert.deepEqual(
    inspectNativeImageRequest(payload, "__JA_IMAGE_HISTORY_SUCCESS__", fixture.bytes),
    {
      kind: "turn",
      byteLength: fixture.bytes.length,
      base64Preserved: true,
      detail: "auto",
    },
  );
  const changed = structuredClone(payload);
  changed.input[0].content[1].image_url = "data:image/png;base64,iVBORw0KGgo=";
  assert.throws(
    () => inspectNativeImageRequest(changed, "__JA_IMAGE_HISTORY_SUCCESS__", fixture.bytes),
    /changed/u,
  );
});

test("启动环境固定隔离目录并移除真实 Provider 开关", () => {
  const previous = process.env.JA_E2E_REAL_PROVIDER;
  process.env.JA_E2E_REAL_PROVIDER = "1";
  try {
    const env = buildLaunchEnvironment({
      directories: {
        roaming: "R:/isolated/roaming",
        local: "R:/isolated/local",
        profile: "R:/isolated/profile",
        runtime: "R:/isolated/runtime",
        workspace: "R:/isolated/workspace",
        webview: "R:/isolated/webview",
      },
      java: "C:/jdk25/bin/java.exe",
      jar: "R:/isolated/ja-app-server.jar",
      frontendPort: 41001,
      cdpPort: 41002,
      cargoTargetDirectory: "R:/isolated/cargo-target",
    });
    assert.equal(env.USERPROFILE, "R:/isolated/profile");
    assert.equal(env.WEBVIEW2_USER_DATA_FOLDER, "R:/isolated/webview");
    assert.equal(env.JA_DEBUG_JAR, "R:/isolated/ja-app-server.jar");
    assert.equal(env.JA_E2E_REAL_PROVIDER, undefined);
    assert.match(env.NO_PROXY, /(?:^|,)127\.0\.0\.1(?:,|$)/u);
    assert.match(env.NO_PROXY, /(?:^|,)localhost(?:,|$)/u);
    assert.match(env.NO_PROXY, /(?:^|,)::1(?:,|$)/u);
    assert.match(env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS, /remote-debugging-port=41002/u);
  } finally {
    if (previous === undefined) delete process.env.JA_E2E_REAL_PROVIDER;
    else process.env.JA_E2E_REAL_PROVIDER = previous;
  }
});

test("递归清理仅允许 OS temp 的具体子目录", () => {
  const owned = join(tmpdir(), "ja-image-history-owned");
  assert.equal(assertOwnedTemporaryPath(owned, "owned"), owned);
  assert.throws(() => assertOwnedTemporaryPath(tmpdir(), "root"), /child/u);
  assert.throws(() => assertOwnedTemporaryPath(process.cwd(), "workspace"), /child/u);
});

test("报告闭集拒绝 blob 缩略图、上下文误判和失败图片丢失", () => {
  assert.deepEqual(validateImageHistoryReport(validReport()), { passed: true, failures: [] });
  const report = validReport();
  report.successTurn.immediateThumbnail.scheme = "blob";
  report.successTurn.contextLimitVisible = true;
  report.reload.sameThread = false;
  report.failureTurn.attachmentRetained = false;
  const verdict = validateImageHistoryReport(report);
  assert.equal(verdict.passed, false);
  assert.deepEqual(
    verdict.failures.filter((failure) =>
      [
        "immediate-native-scheme",
        "success-context-limit",
        "reload-thread",
        "failed-image-retained",
      ].includes(failure),
    ),
    ["success-context-limit", "immediate-native-scheme", "reload-thread", "failed-image-retained"],
  );
});
