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

/** 固定已审定的迁移版本集合，避免把首版检查放宽为任意历史转换均可打包。 */
test("真实目录检查只允许已审定的会话恢复、Workspace 身份与操作回执迁移", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "ja-baseline-policy-"));
  try {
    for (const directory of [
      "app-server/src/main/resources/db/migration",
      "apps/desktop/src",
      "crates/ja-runtime/src",
      "apps/cli/src",
      "apps/desktop/src-tauri/src",
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
    await writeFile(
      path.join(migrations, "V4__conversation_recovery_usage_projection.sql"),
      "SELECT 1;",
    );
    await writeFile(path.join(migrations, "V6__conversation_current_path_reask.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V7__session_workspace_identity.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V8__client_operation_receipts.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V9__goal_progress_counter.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V10__unbounded_round_counters.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V11__execution_cursor_without_budget.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V12__drop_execution_budgets.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V13__plan_evaluation_attempts.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V14__assistant_public_text_pages.sql"), "SELECT 1;");
    await writeFile(path.join(migrations, "V15__input_operation_receipts.sql"), "SELECT 1;");
    assert.deepEqual(await checkInitialBaseline(root), []);
    await writeFile(path.join(migrations, "V8__unreviewed.sql"), "SELECT 1;");
    await mkdir(path.join(root, "contracts/ja-rpc/v2"));
    assert.equal((await checkInitialBaseline(root)).length, 2);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
