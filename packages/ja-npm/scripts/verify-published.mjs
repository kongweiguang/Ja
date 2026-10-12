// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

/** 只模拟 npm 对 bin 首行的安装改写；保留正文原始字节，避免换行归一化隐藏真实差异。 */
function installedBinCandidate(content) {
  const newline = content.indexOf(10);
  if (content[0] === 35 && content[1] === 33 && newline > 3 && content[newline - 1] === 13) {
    return Buffer.concat([content.subarray(0, newline - 1), content.subarray(newline)]);
  }
  return content;
}

/** npm 同版本不可覆盖；身份与原生依赖严格一致，只允许实际 bin 入口的 npm 首行改写。 */
export async function verifyPublished(publishedRoot, candidateRoot) {
  const roots = [publishedRoot, candidateRoot].map((root) => path.resolve(root));
  const packages = await Promise.all(
    roots.map(async (root) => ({
      metadata: JSON.parse(await readFile(path.join(root, "package.json"), "utf8")),
      manifest: JSON.parse(await readFile(path.join(root, "runtime", "manifest.json"), "utf8")),
    })),
  );
  const [published, candidate] = packages;
  for (const { metadata, manifest } of packages) {
    assert.equal(metadata.name, "@kongweiguang/ja", "unexpected npm package identity");
    assert.equal(metadata.private, undefined, "published package must be public");
    assert.equal(
      metadata.version,
      manifest.version,
      "package version differs from native manifest",
    );
    assert.equal(manifest.schemaVersion, 1, "unexpected runtime manifest schema");
    assert.equal(manifest.target, "win32-x64", "unexpected npm target");
    assert.match(manifest.sourceCommit, /^[0-9a-f]{40}$/u, "invalid native source commit");
  }
  assert.equal(
    published.metadata.version,
    candidate.metadata.version,
    "published version differs from release",
  );
  assert.equal(
    published.manifest.sourceCommit,
    candidate.manifest.sourceCommit,
    "npm version belongs to another source commit",
  );
  const artifacts = Object.keys(candidate.manifest.artifacts).sort();
  assert.deepEqual(
    Object.keys(published.manifest.artifacts).sort(),
    artifacts,
    "published native dependencies differ from release",
  );
  for (const relative of artifacts) {
    assert.ok(
      relative.startsWith("runtime/bin/") && !relative.split("/").includes(".."),
      "invalid native artifact path",
    );
    const fact = published.manifest.artifacts[relative];
    const content = await readFile(path.join(roots[0], ...relative.split("/")));
    assert.equal(content.length, fact.sizeBytes, `published artifact size mismatch: ${relative}`);
    assert.equal(
      createHash("sha256").update(content).digest("hex"),
      fact.sha256,
      `published artifact checksum mismatch: ${relative}`,
    );
    // 同一 Rust 源码在重跑时可以产生新的构建字节；已验证的 App Server 与 pinned 工具必须完全一致。
    if (relative !== "runtime/bin/ja.exe") {
      assert.deepEqual(
        fact,
        candidate.manifest.artifacts[relative],
        `published runtime dependency differs from release: ${relative}`,
      );
    }
  }
  for (const key of ["bin", "os", "cpu", "engines", "license"]) {
    assert.deepEqual(
      published.metadata[key],
      candidate.metadata[key],
      `published package ${key} differs from release`,
    );
  }
  for (const relative of ["bin/ja.cjs", "bin/verify.cjs"]) {
    const contents = await Promise.all(
      roots.map((root) => readFile(path.join(root, ...relative.split("/")))),
    );
    const expected =
      relative === "bin/ja.cjs" && candidate.metadata.bin?.ja === relative
        ? installedBinCandidate(contents[1])
        : contents[1];
    assert.ok(contents[0].equals(expected), `published launcher differs from release: ${relative}`);
  }
}

/** 直接执行只做已安装目录的回读校验，不触发上传，也不执行包内未知脚本。 */
async function main() {
  const [publishedRoot, candidateRoot, extra] = process.argv.slice(2);
  if (!publishedRoot || !candidateRoot || extra) {
    throw new Error(
      "usage: verify-published.mjs <published-package-directory> <verified-candidate-directory>",
    );
  }
  await verifyPublished(publishedRoot, candidateRoot);
  process.stdout.write("JA_NPM_PUBLISHED_PACKAGE_OK\n");
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    console.error(`Ja npm publication verification failed: ${error.message}`);
    process.exitCode = 1;
  });
}
