// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import ts from "typescript";
import { checkInitialBaseline, initialBaselineSourceViolation } from "./check-initial-baseline.mjs";

test("0.2 基线只拒绝明确退役的协议与数据兼容实现", () => {
  for (const source of [
    "ja-context-v1",
    'jsonrpc: "2.0"',
    "JA-RPC v2",
    "Tauri 2",
    "mutation_version + 1",
    "UNKNOWN",
    "// LEGACY_SHARED",
    "/* JA-RPC v1 */",
    "<!-- assistant_public_text -->",
    "-- ja_agent_store_records",
  ]) {
    assert.equal(initialBaselineSourceViolation(source), null);
  }
});

test("历史兼容实现不得重新进入生产源码", () => {
  for (const source of [
    "StorageBaseline",
    "DatabaseMigrationRecovery",
    "LEGACY_UNAVAILABLE",
    "legacy_unavailable",
    "profileOrigin",
    "LegacySessionWorkspaceMigration",
    "LegacyAgentStoreBackfill",
    "LEGACY_SHARED",
    "legacy_shared",
    "legacySharedWorkspaceId",
    "assistant_public_text",
    "ja_agent_store_schema",
    "JA-RPC v1",
    "contracts/ja-rpc/v1/schema.json",
    "contracts/golden/v1/valid/core.jsonl",
  ]) {
    assert.ok(initialBaselineSourceViolation(source) !== null, source);
  }
});

/**
 * 验证首版基线与 v2 合同，并逐个探测四个 Java extension 源码根确实受兼容规则扫描。
 * @author kongweiguang
 */
test("真实目录检查只允许 0.2 基线并扫描全部四个 Java extension", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "ja-baseline-policy-"));
  try {
    const extensionRoots = [
      "ja-agent/extensions/ja-agent-store-local/src/main",
      "ja-agent/extensions/ja-agent-store-pg/src/main",
      "ja-agent/extensions/ja-agent-skills-local/src/main",
      "ja-agent/extensions/ja-agent-skills-pg/src/main",
    ];
    for (const directory of [
      "ja-agent/ja-app-server/src/main/resources/db/migration",
      "ja-agent/ja-agent-core/src/main",
      ...extensionRoots,
      "apps/desktop/src",
      "crates/ja-runtime/src",
      "apps/cli/src",
      "apps/desktop/src-tauri/src",
      "contracts/ja-rpc/v2",
      "contracts/golden/v2",
    ]) {
      await mkdir(path.join(root, directory), { recursive: true });
    }
    const migrations = path.join(root, "ja-agent/ja-app-server/src/main/resources/db/migration");
    await writeFile(
      path.join(migrations, "V1__kernel.sql"),
      "CREATE TABLE current_state(id TEXT);",
    );
    assert.deepEqual(await checkInitialBaseline(root), []);
    for (const extensionRoot of extensionRoots) {
      const probe = path.join(root, extensionRoot, "BaselineProbe.java");
      await writeFile(
        probe,
        'final class BaselineProbe { String retired = "LegacyAgentStoreBackfill"; }',
      );
      assert.deepEqual(await checkInitialBaseline(root), [
        `${path.join(extensionRoot, "BaselineProbe.java")}: 首版不得恢复 LegacyAgentStoreBackfill`,
      ]);
      await rm(probe);
    }
    await writeFile(path.join(migrations, "V2__legacy_import.sql"), "SELECT 1;");
    await mkdir(path.join(root, "contracts/ja-rpc/v1"));
    assert.equal((await checkInitialBaseline(root)).length, 2);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

/**
 * 将 Vite 页面中的精确绝对导入解析到现有适配器，同时保持其它 unresolved 诊断有效。
 * @author kongweiguang
 */
test("Knip 将 close behavior 浏览器导入映射到真实 save 导出", async () => {
  const configText = await readFile(new URL("../knip.jsonc", import.meta.url), "utf8");
  const parsed = ts.parseConfigFileTextToJson("knip.jsonc", configText);
  assert.equal(parsed.error, undefined);
  const config = parsed.config;
  const browserSpecifier = "/src/api/tauri/closeBehavior.ts";

  assert.ok(config.entry.includes("scripts/e2e/windows-desktop-smoke.mjs"));
  assert.deepEqual(config.paths?.[browserSpecifier], [
    "apps/desktop/src/api/tauri/closeBehavior.ts",
  ]);
  assert.equal(config.ignoreUnresolved?.includes(browserSpecifier), false);

  const adapter = await readFile(
    new URL("../apps/desktop/src/api/tauri/closeBehavior.ts", import.meta.url),
    "utf8",
  );
  const runner = await readFile(
    new URL("./e2e/windows-desktop-smoke.mjs", import.meta.url),
    "utf8",
  );
  assert.match(adapter, /export async function saveCloseBehavior\s*\(/u);
  assert.match(runner, /import\(\s*["']\/src\/api\/tauri\/closeBehavior\.ts["']\s*\)/u);
  assert.match(runner, /await saveCloseBehavior\("exit"\)/u);
});
