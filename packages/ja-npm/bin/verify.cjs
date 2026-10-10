// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* eslint-disable @typescript-eslint/no-require-imports -- npm verification runs as CommonJS. */

"use strict";

const { createHash } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const baseArtifacts = [
  "runtime/bin/ja.exe",
  "runtime/bin/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
];
const searchToolsDirectory = "runtime/bin/sidecars/tools";
const searchToolsManifest = searchToolsDirectory + "/tools-manifest.json";
const searchToolContract = [
  {
    name: "fd",
    version: "10.5.0",
    repository: "sharkdp/fd",
    releaseTag: "v10.5.0",
    asset: {
      fileName: "fd-v10.5.0-x86_64-pc-windows-msvc.zip",
      url: "https://github.com/sharkdp/fd/releases/download/v10.5.0/fd-v10.5.0-x86_64-pc-windows-msvc.zip",
      sha256: "a227701b8551c35a9931d9f6da75503cf86d88e182d71fb849a70864c5d57cd7",
      sizeBytes: 1535063,
    },
    licenseNames: ["LICENSE-APACHE", "LICENSE-MIT"],
  },
  {
    name: "rg",
    version: "15.2.0",
    repository: "BurntSushi/ripgrep",
    releaseTag: "15.2.0",
    asset: {
      fileName: "ripgrep-15.2.0-x86_64-pc-windows-msvc.zip",
      url: "https://github.com/BurntSushi/ripgrep/releases/download/15.2.0/ripgrep-15.2.0-x86_64-pc-windows-msvc.zip",
      sha256: "71b2fef860abe467217a538ff31de02f5258807c0129f771846f87bd029aafc5",
      sizeBytes: 1789611,
    },
    licenseNames: ["COPYING", "LICENSE-MIT", "UNLICENSE"],
  },
];

/** 文件清单来自锁定的 Native 构建，只有按当前工具版本、来源与相对路径核对后才信任。 */
function inspectSearchTools(toolsDirectory, sourceCommit) {
  const toolsRoot = path.resolve(toolsDirectory);
  const manifestPath = path.join(toolsRoot, "tools-manifest.json");
  const manifestArtifact = readArtifact(manifestPath, searchToolsManifest);
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  if (manifest.schemaVersion !== 1 || manifest.product !== "Ja"
      || manifest.stagingMode !== "copy" || manifest.sourceCommit !== sourceCommit
      || manifest.target?.platform !== "windows" || manifest.target?.arch !== "x86_64"
      || manifest.target?.targetTriple !== "x86_64-pc-windows-msvc") {
    throw new Error("search-tool manifest identity does not match the Windows x64 source build");
  }
  if (!Array.isArray(manifest.tools) || manifest.tools.length !== searchToolContract.length) {
    throw new Error("search-tool manifest must contain exactly the pinned fd and rg tools");
  }
  const toolsByName = new Map(manifest.tools.map((tool) => [tool?.name, tool]));
  if (toolsByName.size !== searchToolContract.length
      || searchToolContract.some((tool) => !toolsByName.has(tool.name))) {
    throw new Error("search-tool manifest must contain exactly the pinned fd and rg tools");
  }

  const files = [manifestArtifact];
  for (const expected of searchToolContract) {
    const tool = toolsByName.get(expected.name);
    if (tool.version !== expected.version || tool.repository !== expected.repository
        || tool.releaseTag !== expected.releaseTag
        || tool.asset?.fileName !== expected.asset.fileName
        || tool.asset?.url !== expected.asset.url
        || tool.asset?.sha256 !== expected.asset.sha256
        || tool.asset?.sizeBytes !== expected.asset.sizeBytes) {
      throw new Error("search-tool manifest does not identify the pinned " + expected.name + " release");
    }
    const binaryName = expected.name + ".exe";
    if (tool.binary?.relativePath !== "tools/" + binaryName
        || tool.binary?.fileName !== binaryName
        || typeof tool.binary?.versionOutput !== "string"
        || !tool.binary.versionOutput.includes(expected.version)) {
      throw new Error("search-tool manifest binary entry is invalid: " + expected.name);
    }
    files.push(readManifestArtifact(
      toolsRoot,
      path.join(toolsRoot, binaryName),
      searchToolsDirectory + "/" + binaryName,
      tool.binary,
    ));

    if (!Array.isArray(tool.licenses) || tool.licenses.length !== expected.licenseNames.length) {
      throw new Error("search-tool license set is incomplete: " + expected.name);
    }
    const licensesByName = new Map(tool.licenses.map((license) => [license?.sourceName, license]));
    if (licensesByName.size !== expected.licenseNames.length
        || expected.licenseNames.some((name) => !licensesByName.has(name))) {
      throw new Error("search-tool license set is unexpected: " + expected.name);
    }
    for (const sourceName of expected.licenseNames) {
      const license = licensesByName.get(sourceName);
      const fileName = expected.name + "-" + sourceName;
      if (license.relativePath !== "tools/" + fileName || license.fileName !== fileName) {
        throw new Error("search-tool license path is invalid: " + expected.name);
      }
      files.push(readManifestArtifact(
        toolsRoot,
        path.join(toolsRoot, fileName),
        searchToolsDirectory + "/" + fileName,
        license,
      ));
    }
  }
  return { manifest, files };
}

/** 同时校验生成器 manifest 的文件事实与磁盘字节，避免路径猜测及链接替换。 */
function readManifestArtifact(toolsRoot, sourcePath, packagePath, declared) {
  const artifact = readArtifact(sourcePath, packagePath);
  const expectedPath = "tools/" + path.basename(sourcePath);
  if (path.relative(toolsRoot, sourcePath) !== path.basename(sourcePath)
      || declared.relativePath !== expectedPath
      || declared.sizeBytes !== artifact.sizeBytes
      || declared.sha256 !== artifact.sha256) {
    throw new Error("search-tool artifact does not match its manifest: " + packagePath);
  }
  return artifact;
}

/** 哈希仅接受普通非空文件；相对路径始终由本文件的固定闭集构造。 */
function readArtifact(sourcePath, packagePath) {
  const metadata = fs.lstatSync(sourcePath);
  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error("required runtime artifact is missing or not a regular file: " + packagePath);
  }
  const data = fs.readFileSync(sourcePath);
  return {
    sourcePath,
    packagePath,
    sizeBytes: data.length,
    sha256: createHash("sha256").update(data).digest("hex"),
  };
}

/** 校验发布包的完整运行时闭集，使发布脚本和 npm prepublish 共用同一证明。 */
function verifyPackage(packageRoot = root) {
  const resolvedRoot = path.resolve(packageRoot);
  const manifest = JSON.parse(fs.readFileSync(path.join(resolvedRoot, "runtime", "manifest.json"), "utf8"));
  const pkg = JSON.parse(fs.readFileSync(path.join(resolvedRoot, "package.json"), "utf8"));
  if (pkg.name !== "@kongweiguang/ja" || pkg.private
      || pkg.version !== manifest.version || manifest.schemaVersion !== 1
      || manifest.target !== "win32-x64" || !/^[0-9a-f]{40}$/.test(manifest.sourceCommit ?? "")) {
    throw new Error("package metadata does not match the staged Ja native artifacts");
  }
  if (manifest.artifacts === null || typeof manifest.artifacts !== "object"
      || Array.isArray(manifest.artifacts)) {
    throw new Error("runtime artifact manifest is missing");
  }
  const searchTools = inspectSearchTools(
    path.join(resolvedRoot, searchToolsDirectory),
    manifest.sourceCommit,
  );
  const expected = [...baseArtifacts, ...searchTools.files.map((file) => file.packagePath)].sort();
  if (Object.keys(manifest.artifacts).sort().join("\n") !== expected.join("\n")) {
    throw new Error("runtime artifact set is incomplete or unexpected");
  }
  const files = [
    ...baseArtifacts.map((relative) => readArtifact(
      path.join(resolvedRoot, ...relative.split("/")),
      relative,
    )),
    ...searchTools.files,
  ];
  for (const file of files) {
    const artifact = manifest.artifacts[file.packagePath];
    if (artifact.sizeBytes !== file.sizeBytes || artifact.sha256 !== file.sha256) {
      throw new Error("native artifact verification failed: " + file.packagePath);
    }
  }
  return { packageRoot: resolvedRoot, package: pkg, manifest };
}

module.exports = { inspectSearchTools, searchToolContract, verifyPackage };

if (require.main === module) {
  try {
    verifyPackage();
  } catch (error) {
    console.error("Ja npm package verification failed: " + error.message);
    process.exitCode = 1;
  }
}
