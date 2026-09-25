// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* eslint-disable @typescript-eslint/no-require-imports -- npm verification runs as CommonJS. */

"use strict";

const { createHash } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const expected = [
  "runtime/bin/ja.exe",
  "runtime/bin/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
];

/** 发布前逐个校验真实原生产物，防止空包、错架构文件或打包后替换进入 npm。 */
function verifyPackage() {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "runtime", "manifest.json"), "utf8"));
  const pkg = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
  if (pkg.private || pkg.version !== manifest.version || manifest.target !== "win32-x64") {
    throw new Error("package metadata does not match the staged Ja native artifacts");
  }
  if (Object.keys(manifest.artifacts).sort().join("\n") !== expected.sort().join("\n")) {
    throw new Error("native artifact set is incomplete or unexpected");
  }
  for (const relative of expected) {
    const artifact = manifest.artifacts[relative];
    const data = fs.readFileSync(path.join(root, ...relative.split("/")));
    const digest = createHash("sha256").update(data).digest("hex");
    if (data.length === 0 || data.length !== artifact.sizeBytes || digest !== artifact.sha256) {
      throw new Error(`native artifact verification failed: ${relative}`);
    }
  }
}

try {
  verifyPackage();
} catch (error) {
  console.error(`Ja npm package verification failed: ${error.message}`);
  process.exitCode = 1;
}
