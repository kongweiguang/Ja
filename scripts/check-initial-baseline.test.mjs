// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { checkInitialBaseline, initialBaselineSourceViolation } from "./check-initial-baseline.mjs";

test("首版保留真实协议与业务计数，不把所有版本重置", () => {
  for (const source of [
    "ja-context-v1",
    "ja-context-summary-v1",
    "ja-ui-preferences-v1",
    'jsonrpc: "2.0"',
    "Tauri 2",
    "mutation_version + 1",
    "UNKNOWN",
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
    "ja-context-v3",
    "ja-context-summary-v2",
    "ja-ui-preferences-v10",
    "ja-ui-preferences-v2",
    "__JA_TIMELINE_STORE_V3__",
  ]) {
    assert.equal(initialBaselineSourceViolation(source), source);
  }
});

/** 固定已审定的策略初始化例外，避免放宽为任意迁移均可打包。 */
test("真实目录检查允许子智能体策略初始化，拒绝额外迁移和旧合同", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "ja-baseline-policy-"));
  try {
    for (const directory of [
      "app-server/src/main/resources/db/migration",
      "apps/desktop/src",
      "crates/ja-runtime/src",
      "src-tauri/src",
      "contracts/ja-rpc/v1",
    ]) {
      await mkdir(path.join(root, directory), { recursive: true });
    }
    const migrations = path.join(root, "app-server/src/main/resources/db/migration");
    await writeFile(
      path.join(migrations, "V1__kernel.sql"),
      "CREATE TABLE current_state(id TEXT);",
    );
    assert.equal((await checkInitialBaseline(root)).length, 1);
    await writeFile(path.join(migrations, "V2__thread_subagent_policies.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V3__subagent_reasoning.sql"), "SELECT 1;");
    assert.deepEqual(await checkInitialBaseline(root), []);
    await writeFile(path.join(migrations, "V4__history.sql"), "SELECT 1;");
    await mkdir(path.join(root, "contracts/ja-rpc/v2"));
    assert.equal((await checkInitialBaseline(root)).length, 2);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
