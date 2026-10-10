// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

const sourceRoots = [
  "ja-agent/ja-agent-core/src/main",
  "ja-agent/extensions/ja-agent-store-local/src/main",
  "ja-agent/extensions/ja-agent-store-pg/src/main",
  "ja-agent/extensions/ja-agent-skills-local/src/main",
  "ja-agent/extensions/ja-agent-skills-pg/src/main",
  "ja-agent/ja-app-server/src/main",
  "apps/desktop/src",
  "crates/ja-runtime/src",
  "apps/cli/src",
  "apps/desktop/src-tauri/src",
];
const textExtensions = new Set([".java", ".rs", ".ts", ".tsx", ".json", ".xml", ".sql"]);
const retiredTokens =
  /\b(?:StorageBaseline|DatabaseMigrationRecovery|LEGACY_UNAVAILABLE|legacy_unavailable|profileOrigin|LegacySessionWorkspaceMigration|LegacyAgentStoreBackfill)\b|LEGACY_SHARED|legacy_shared|legacySharedWorkspaceId|assistant_public_text|ja_agent_store_[a-z0-9_]+|JA-RPC\s+v1|contracts\/ja-rpc\/v1|contracts\/golden\/v1|ja-rpc-v1/iu;

/** 只扫描有效生产源码；注释与测试负例保留历史说明，不触发兼容实现拦截。 */
export function initialBaselineSourceViolation(source) {
  const implementation = source
    .replace(/\/\*[\s\S]*?\*\//gu, "")
    .replace(/<!--[\s\S]*?-->/gu, "")
    .replace(/^[\t ]*\/\/.*$/gmu, "")
    .replace(/^[\t ]*--.*$/gmu, "");
  return retiredTokens.exec(implementation)?.[0] ?? null;
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

/** 固定实际注册的 V1 kernel 基线和 wire major，避免历史升级链或备用基线重新进入新版本。 */
export async function checkInitialBaseline(root) {
  const violations = [];
  const migrationRoot = path.join(root, "ja-agent/ja-app-server/src/main/resources/db/migration");
  const migrations = await readdir(migrationRoot);
  if (migrations.length !== 1 || migrations[0] !== "V1__kernel.sql") {
    violations.push("0.2 数据库必须只从 V1__kernel.sql 建立新基线");
  }
  for (const relativeRoot of ["contracts/ja-rpc", "contracts/golden"]) {
    const entries = await readdir(path.join(root, relativeRoot), { withFileTypes: true });
    const versions = entries.filter((entry) => entry.isDirectory() && /^v\d+$/.test(entry.name));
    if (versions.length !== 1 || versions[0]?.name !== "v2") {
      violations.push(`${relativeRoot} 必须只包含 v2 合同语料`);
    }
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
