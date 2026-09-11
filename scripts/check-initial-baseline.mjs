// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

const sourceRoots = [
  "app-server/src/main",
  "apps/desktop/src",
  "crates/ja-runtime/src",
  "src-tauri/src",
];
const textExtensions = new Set([".java", ".rs", ".ts", ".tsx", ".json", ".xml", ".sql"]);
const retiredTokens =
  /\b(?:StorageBaseline|DatabaseMigrationRecovery|LEGACY_UNAVAILABLE|legacy_unavailable|profileOrigin)\b|ja-context-v[2-9]\d*\b|ja-context-summary-v[2-9]\d*\b|ja-ui-preferences-v(?:[2-9]\d*|1\d+)\b|__JA_TIMELINE_STORE_V(?:[2-9]\d*|1\d+)__/;

/** 只约束生产实现，不禁止负例测试保存旧输入，也不混淆第三方协议版本。 */
export function initialBaselineSourceViolation(source) {
  return retiredTokens.exec(source)?.[0] ?? null;
}

/** 仅遍历明确的源码目录且不跟随链接，避免扫描用户数据、缓存或构建产物。 */
async function sourceFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await sourceFiles(target)));
    else if (entry.isFile() && textExtensions.has(path.extname(entry.name))) files.push(target);
  }
  return files;
}

/** 只允许当前建库与已确认的会话策略初始化资源，禁止引入未审定的历史转换或备用协议。 */
export async function checkInitialBaseline(root) {
  const violations = [];
  const migrationRoot = path.join(root, "app-server/src/main/resources/db/migration");
  const migrations = await readdir(migrationRoot);
  const expectedMigrations = [
    "V1__kernel.sql",
    "V2__thread_subagent_policies.sql",
    "V3__subagent_reasoning.sql",
  ];
  if (
    migrations.length !== expectedMigrations.length ||
    expectedMigrations.some((name) => !migrations.includes(name))
  ) {
    violations.push("数据库只允许 V1 建库、V2 子智能体会话策略与 V3 思考等级资源");
  }
  const protocolEntries = await readdir(path.join(root, "contracts/ja-rpc"), {
    withFileTypes: true,
  });
  if (!protocolEntries.some((entry) => entry.isDirectory() && entry.name === "v1")) {
    violations.push("缺少 JA-RPC 首版 v1 合同目录");
  }
  if (
    protocolEntries.some(
      (entry) => entry.isDirectory() && /^v\d+$/.test(entry.name) && entry.name !== "v1",
    )
  ) {
    violations.push("JA-RPC 只允许首版 v1 合同目录");
  }
  for (const relativeRoot of sourceRoots) {
    for (const file of await sourceFiles(path.join(root, relativeRoot))) {
      const retired = initialBaselineSourceViolation(await readFile(file, "utf8"));
      if (retired !== null)
        violations.push(`${path.relative(root, file)}: 首版不得恢复 ${retired}`);
    }
  }
  return violations;
}
