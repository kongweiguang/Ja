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

/** 首版仅有一份建库资源和一套协议目录，历史转换不能作为隐含备用链路打包。 */
export async function checkInitialBaseline(root) {
  const violations = [];
  const migrationRoot = path.join(root, "app-server/src/main/resources/db/migration");
  const migrations = await readdir(migrationRoot);
  if (migrations.length !== 1 || migrations[0] !== "V1__kernel.sql") {
    violations.push("数据库首版只能包含 V1__kernel.sql，禁止历史迁移和专用配置");
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
