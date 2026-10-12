// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { verifyPublished } from "../scripts/verify-published.mjs";

/** 固定同版本双包与不同 CLI 构建字节，验证重跑不会把版本号相同误当成发布成功。 */
async function fixture(t) {
  const root = await mkdtemp(path.join(tmpdir(), "ja-npm-published-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const directories = [path.join(root, "published"), path.join(root, "candidate")];
  for (const [index, directory] of directories.entries()) {
    const artifacts = {};
    for (const [relative, value] of Object.entries({
      "runtime/bin/ja.exe": `cli-build-${index}`,
      "runtime/bin/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe": "verified-server",
      "runtime/bin/sidecars/tools/fd.exe": "pinned-fd",
      "runtime/bin/sidecars/tools/rg.exe": "pinned-rg",
    })) {
      const content = Buffer.from(value);
      const target = path.join(directory, ...relative.split("/"));
      await mkdir(path.dirname(target), { recursive: true });
      await writeFile(target, content);
      artifacts[relative] = {
        sizeBytes: content.length,
        sha256: createHash("sha256").update(content).digest("hex"),
      };
    }
    await mkdir(path.join(directory, "bin"));
    await writeFile(path.join(directory, "bin", "ja.cjs"), "same-launcher");
    await writeFile(path.join(directory, "bin", "verify.cjs"), "same-verifier");
    await writeFile(
      path.join(directory, "package.json"),
      JSON.stringify({
        name: "@kongweiguang/ja",
        version: "0.2.0",
        bin: { ja: "bin/ja.cjs" },
        os: ["win32"],
        cpu: ["x64"],
        engines: { node: ">=24 <25" },
        license: "GPL-3.0-or-later",
      }),
    );
    await writeFile(
      path.join(directory, "runtime", "manifest.json"),
      JSON.stringify({
        schemaVersion: 1,
        version: "0.2.0",
        target: "win32-x64",
        sourceCommit: "a".repeat(40),
        artifacts,
      }),
    );
  }
  return directories;
}

/** 修改公开包事实以模拟 registry 已占用版本；候选包保持原始验收身份。 */
async function changeManifest(root, mutate) {
  const file = path.join(root, "runtime", "manifest.json");
  const manifest = JSON.parse(await readFile(file, "utf8"));
  mutate(manifest);
  await writeFile(file, JSON.stringify(manifest));
}

test("same source with a rebuilt CLI remains reusable", async (t) => {
  const roots = await fixture(t);
  await verifyPublished(...roots);
});

// npm bin-links 只移除真实入口 shebang 行的 CR；其余正文仍须字节相同。
test("npm installed bin hashbang normalization remains reusable", async (t) => {
  const roots = await fixture(t);
  await writeFile(
    path.join(roots[0], "bin", "ja.cjs"),
    "#!/usr/bin/env node\nconst value = 1;\r\n",
  );
  await writeFile(
    path.join(roots[1], "bin", "ja.cjs"),
    "#!/usr/bin/env node\r\nconst value = 1;\r\n",
  );
  await verifyPublished(...roots);
});

test("bin body line ending changes still block release", async (t) => {
  const roots = await fixture(t);
  await writeFile(path.join(roots[0], "bin", "ja.cjs"), "#!/usr/bin/env node\nconst value = 1;\n");
  await writeFile(
    path.join(roots[1], "bin", "ja.cjs"),
    "#!/usr/bin/env node\r\nconst value = 1;\r\n",
  );
  await assert.rejects(verifyPublished(...roots), /published launcher differs/u);
});

test("a non-bin verifier hashbang cannot be normalized", async (t) => {
  const roots = await fixture(t);
  await writeFile(path.join(roots[0], "bin", "verify.cjs"), "#!/usr/bin/env node\nverify();\r\n");
  await writeFile(path.join(roots[1], "bin", "verify.cjs"), "#!/usr/bin/env node\r\nverify();\r\n");
  await assert.rejects(verifyPublished(...roots), /published launcher differs/u);
});

test("an occupied npm version from another commit blocks release", async (t) => {
  const roots = await fixture(t);
  await changeManifest(roots[0], (manifest) => {
    manifest.sourceCommit = "b".repeat(40);
  });
  await assert.rejects(verifyPublished(...roots), /another source commit/u);
});

test("a package without the pinned tools cannot satisfy a rerun", async (t) => {
  const roots = await fixture(t);
  await changeManifest(roots[0], (manifest) => {
    delete manifest.artifacts["runtime/bin/sidecars/tools/fd.exe"];
  });
  await assert.rejects(verifyPublished(...roots), /native dependencies differ/u);
});

test("tampered published native bytes block release", async (t) => {
  const roots = await fixture(t);
  await writeFile(path.join(roots[0], "runtime", "bin", "ja.exe"), "cli-changed");
  await assert.rejects(verifyPublished(...roots), /checksum mismatch/u);
});

test("a self-consistent but different server blocks release", async (t) => {
  const roots = await fixture(t);
  const relative = "runtime/bin/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe";
  const content = Buffer.from("other-server");
  await writeFile(path.join(roots[0], ...relative.split("/")), content);
  await changeManifest(roots[0], (manifest) => {
    manifest.artifacts[relative] = {
      sizeBytes: content.length,
      sha256: createHash("sha256").update(content).digest("hex"),
    };
  });
  await assert.rejects(verifyPublished(...roots), /runtime dependency differs/u);
});
