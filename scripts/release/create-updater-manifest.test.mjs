// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createUpdaterManifest } from "./create-updater-manifest.mjs";

/** 创建与 Actions 下载目录一致的最小签名矩阵，不伪造额外平台或 bundle 类型。 */
async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "ja-updater-manifest-"));
  const input = join(root, "input");
  const output = join(root, "output");
  const targets = [
    ["ja-native-app-server-windows-x86_64", "Ja-setup.exe"],
    ["ja-native-app-server-macos-x86_64", "Ja.app.tar.gz"],
    ["ja-native-app-server-macos-arm64", "Ja.app.tar.gz"],
  ];
  for (const [directory, name] of targets) {
    const bundle = join(input, directory, "bundle");
    await mkdir(bundle, { recursive: true });
    await writeFile(join(bundle, name), directory, "utf8");
    await writeFile(join(bundle, `${name}.sig`), `signature-${directory}\n`, "utf8");
  }
  return { input, output };
}

test("creates one complete Tauri v2 manifest from the signed matrix", async () => {
  const paths = await fixture();
  const manifest = await createUpdaterManifest({
    ...paths,
    repository: "kongweiguang/Ja",
    tag: "v0.2.0",
    publishedAt: "2026-09-01T00:00:00Z",
  });

  assert.deepEqual(Object.keys(manifest.platforms), [
    "windows-x86_64",
    "darwin-x86_64",
    "darwin-aarch64",
  ]);
  assert.equal(manifest.version, "0.2.0");
  assert.match(manifest.platforms["darwin-aarch64"].url, /Ja_0\.2\.0_darwin_aarch64/u);
  assert.deepEqual(JSON.parse(await readFile(join(paths.output, "latest.json"), "utf8")), manifest);
});

test("fails closed when one platform signature is missing", async () => {
  const paths = await fixture();
  const missingInput = join(paths.input, "ja-native-app-server-macos-arm64", "bundle");
  await writeFile(join(missingInput, "unrelated.txt"), "ignored", "utf8");
  const signature = join(missingInput, "Ja.app.tar.gz.sig");
  await rm(signature);

  await assert.rejects(
    createUpdaterManifest({
      ...paths,
      repository: "kongweiguang/Ja",
      tag: "v0.2.0",
      publishedAt: "2026-09-01T00:00:00Z",
    }),
    /expected one/u,
  );
});
