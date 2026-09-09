// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import { createUpdaterManifest } from "./create-updater-manifest.mjs";

/** 生成只用于测试的 Minisign Ed25519 密钥材料，不依赖生产 updater 私钥。 */
async function testSigner(root) {
  const cli = resolve("node_modules/@tauri-apps/cli/tauri.js");
  const keyPath = join(root, "test.key");
  execFileSync(
    process.execPath,
    [cli, "signer", "generate", "--ci", "--password", "", "--write-keys", keyPath],
    { stdio: "pipe" },
  );
  return {
    publicKeyText: (await readFile(`${keyPath}.pub`, "utf8")).trim(),
    /** 直接使用锁定 CLI 签名临时产物，避免测试自行拼装格式后只证明自身假设。 */
    async signArtifact(bytes) {
      const artifact = join(root, "sign-input.bin");
      await writeFile(artifact, bytes);
      execFileSync(
        process.execPath,
        [cli, "signer", "sign", "--private-key-path", keyPath, "--password", "", artifact],
        { stdio: "pipe" },
      );
      return readFile(`${artifact}.sig`, "utf8");
    },
  };
}

/** 记录 fixture 文件的真实摘要，与 collector artifact facts 保持一致。 */
function fact(fileName, bytes) {
  return {
    fileName,
    sizeBytes: bytes.length,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    signatureFile: null,
    signingStatus: "unsigned",
  };
}

/** 创建三个真实矩阵目标的 updater、DMG、签名和 collector evidence。 */
async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "ja-updater-manifest-"));
  const input = join(root, "input");
  const output = join(root, "output");
  const signer = await testSigner(root);
  const sourceCommit = "a".repeat(40);
  const tauriConfig = join(root, "tauri.conf.json");
  await writeFile(
    tauriConfig,
    JSON.stringify({
      plugins: { updater: { pubkey: signer.publicKeyText } },
    }),
  );
  const targets = [
    {
      directory: "ja-native-app-server-windows-x86_64",
      platform: "windows",
      arch: "x86_64",
      bundle: "nsis",
      updaterDirectory: "nsis",
      updaterName: "Ja_0.1.0_x64-setup.exe",
      installerName: "Ja_0.1.0_x64-setup.exe",
    },
    {
      directory: "ja-native-app-server-macos-x86_64",
      platform: "macos",
      arch: "x86_64",
      bundle: "dmg",
      updaterDirectory: "macos",
      updaterName: "Ja_0.1.0_x86_64.app.tar.gz",
      installerName: "Ja_0.1.0_x86_64.dmg",
    },
    {
      directory: "ja-native-app-server-macos-arm64",
      platform: "macos",
      arch: "arm64",
      bundle: "dmg",
      updaterDirectory: "macos",
      updaterName: "Ja_0.1.0_aarch64.app.tar.gz",
      installerName: "Ja_0.1.0_aarch64.dmg",
    },
  ];
  for (const target of targets) {
    const artifactRoot = join(input, target.directory);
    const bundle = join(artifactRoot, "src-tauri", "target", "release", "bundle");
    const updaterRoot = join(bundle, target.updaterDirectory);
    const installerRoot = join(bundle, target.bundle);
    const updaterBytes = Buffer.from(`updater:${target.directory}`);
    const installerBytes =
      target.platform === "windows" ? updaterBytes : Buffer.from(`installer:${target.directory}`);
    const signatureBytes = Buffer.from(await signer.signArtifact(updaterBytes));
    await mkdir(updaterRoot, { recursive: true });
    await mkdir(installerRoot, { recursive: true });
    await writeFile(join(updaterRoot, target.updaterName), updaterBytes);
    await writeFile(join(updaterRoot, `${target.updaterName}.sig`), signatureBytes);
    await writeFile(join(installerRoot, target.installerName), installerBytes);
    const evidenceRoot = join(artifactRoot, "ja-tauri-bundle-evidence");
    await mkdir(evidenceRoot, { recursive: true });
    const evidence = {
      schemaVersion: 1,
      product: "Ja",
      sourceCommit,
      target: { platform: target.platform, arch: target.arch, bundle: target.bundle },
      build: { noSign: false, signingStatus: "unsigned", notarizationStatus: "not-run" },
      updater: {
        status: "pending-aggregate-verification",
        artifact: fact(target.updaterName, updaterBytes),
        signature: fact(`${target.updaterName}.sig`, signatureBytes),
      },
      artifacts: [fact(target.installerName, installerBytes)],
    };
    await writeFile(
      join(evidenceRoot, "tauri-bundle-manifest.json"),
      `${JSON.stringify(evidence)}\n`,
    );
  }
  return { input, output, tauriConfig, sourceCommit, root };
}

test("creates verified Tauri metadata and all real three-platform release assets", async () => {
  const paths = await fixture();
  try {
    const manifest = await createUpdaterManifest({
      ...paths,
      repository: "kongweiguang/Ja",
      tag: "v0.1.0",
      publishedAt: "2026-09-01T00:00:00Z",
    });
    assert.deepEqual(Object.keys(manifest.platforms), [
      "windows-x86_64",
      "darwin-x86_64",
      "darwin-aarch64",
    ]);
    assert.equal(manifest.version, "0.1.0");
    assert.equal(
      Buffer.from(manifest.platforms["darwin-aarch64"].signature, "base64")
        .toString("utf8")
        .startsWith("untrusted comment:"),
      true,
    );
    assert.deepEqual((await readdir(paths.output)).sort(), [
      "Ja_0.1.0_darwin_aarch64.app.tar.gz",
      "Ja_0.1.0_darwin_aarch64.app.tar.gz.sig",
      "Ja_0.1.0_darwin_aarch64.dmg",
      "Ja_0.1.0_darwin_x86_64.app.tar.gz",
      "Ja_0.1.0_darwin_x86_64.app.tar.gz.sig",
      "Ja_0.1.0_darwin_x86_64.dmg",
      "Ja_0.1.0_windows_x86_64-setup.exe",
      "Ja_0.1.0_windows_x86_64-setup.exe.sig",
      "SHA256SUMS",
      "artifact-manifest.json",
      "latest.json",
    ]);
    const release = JSON.parse(
      await readFile(join(paths.output, "artifact-manifest.json"), "utf8"),
    );
    assert.equal(release.sourceCommit, paths.sourceCommit);
    assert.deepEqual(release.release, {
      noSign: false,
      systemSigning: "unsigned",
      updaterSigning: "verified",
    });
    assert.equal(release.artifacts.length, 8);
    assert.equal(
      release.artifacts.every((entry) => /^[0-9a-f]{64}$/u.test(entry.sha256)),
      true,
    );
    assert.equal(
      (await readFile(join(paths.output, "SHA256SUMS"), "utf8")).trim().split("\n").length,
      8,
    );
    assert.deepEqual(
      JSON.parse(await readFile(join(paths.output, "latest.json"), "utf8")),
      manifest,
    );
  } finally {
    await rm(paths.root, { recursive: true, force: true });
  }
});

test("fails closed when one platform signature is missing", async () => {
  const paths = await fixture();
  try {
    await rm(
      join(
        paths.input,
        "ja-native-app-server-macos-arm64",
        "src-tauri",
        "target",
        "release",
        "bundle",
        "macos",
        "Ja_0.1.0_aarch64.app.tar.gz.sig",
      ),
    );
    await assert.rejects(
      createUpdaterManifest({
        ...paths,
        repository: "kongweiguang/Ja",
        tag: "v0.1.0",
        publishedAt: "2026-09-01T00:00:00Z",
      }),
      /expected one .*\.app\.tar\.gz\.sig/u,
    );
  } finally {
    await rm(paths.root, { recursive: true, force: true });
  }
});

test("rejects an artifact whose updater signature does not verify", async () => {
  const paths = await fixture();
  try {
    const artifact = join(
      paths.input,
      "ja-native-app-server-windows-x86_64",
      "src-tauri",
      "target",
      "release",
      "bundle",
      "nsis",
      "Ja_0.1.0_x64-setup.exe",
    );
    await writeFile(artifact, "tampered");
    await assert.rejects(
      createUpdaterManifest({
        ...paths,
        repository: "kongweiguang/Ja",
        tag: "v0.1.0",
        publishedAt: "2026-09-01T00:00:00Z",
      }),
      /evidence does not match/u,
    );
  } finally {
    await rm(paths.root, { recursive: true, force: true });
  }
});
