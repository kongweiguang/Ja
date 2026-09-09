// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { copyFile, mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const TARGETS = [
  {
    artifact: "ja-native-app-server-windows-x86_64",
    key: "windows-x86_64",
    signatureSuffix: ".exe.sig",
    outputSuffix: "windows_x86_64-setup.exe",
  },
  {
    artifact: "ja-native-app-server-macos-x86_64",
    key: "darwin-x86_64",
    signatureSuffix: ".app.tar.gz.sig",
    outputSuffix: "darwin_x86_64.app.tar.gz",
  },
  {
    artifact: "ja-native-app-server-macos-arm64",
    key: "darwin-aarch64",
    signatureSuffix: ".app.tar.gz.sig",
    outputSuffix: "darwin_aarch64.app.tar.gz",
  },
];

/** 递归枚举普通文件，发布聚合不依赖 runner 解压后的中间目录深度。 */
async function filesBelow(root) {
  const entries = await readdir(root, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map(async (entry) => {
      const path = join(root, entry.name);
      return entry.isDirectory() ? filesBelow(path) : [path];
    }),
  );
  return nested.flat();
}

/** 每个平台必须恰好提供一个签名及其同名产物，缺失或重复都拒绝生成半成品 metadata。 */
async function signedArtifact(root, target) {
  const artifactRoot = join(root, target.artifact);
  const signatures = (await filesBelow(artifactRoot)).filter((path) =>
    path.endsWith(target.signatureSuffix),
  );
  if (signatures.length !== 1) {
    throw new Error(
      `${target.key} expected one ${target.signatureSuffix}, found ${signatures.length}`,
    );
  }
  const signaturePath = signatures[0];
  const artifactPath = signaturePath.slice(0, -4);
  return {
    artifactPath,
    signature: (await readFile(signaturePath, "utf8")).trim(),
  };
}

/**
 * 从矩阵真实产物生成 Tauri v2 静态 manifest，并重命名同名 macOS archive，
 * 使一个 GitHub Release 能同时承载两种架构且签名内容仍对应原始字节。
 */
export async function createUpdaterManifest({ input, output, repository, tag, publishedAt }) {
  const version = tag.replace(/^v/u, "");
  if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/u.test(version)) {
    throw new Error(`tag is not a supported semantic version: ${tag}`);
  }
  const publicationDate = new Date(publishedAt);
  if (Number.isNaN(publicationDate.valueOf()))
    throw new Error("publishedAt is not RFC 3339 compatible");
  await mkdir(output, { recursive: true });
  const platforms = {};
  for (const target of TARGETS) {
    const source = await signedArtifact(input, target);
    const fileName = `Ja_${version}_${target.outputSuffix}`;
    await copyFile(source.artifactPath, join(output, fileName));
    platforms[target.key] = {
      signature: source.signature,
      url: `https://github.com/${repository}/releases/download/${tag}/${encodeURIComponent(fileName)}`,
    };
  }
  const manifest = {
    version,
    notes: `Ja ${tag}`,
    pub_date: publicationDate.toISOString(),
    platforms,
  };
  await writeFile(join(output, "latest.json"), `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  return manifest;
}

/** 解析封闭 CLI 参数，避免工作流用位置参数时把仓库、tag 或路径错位。 */
function parseArguments(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    if (name?.startsWith("--") !== true) throw new Error(`invalid argument: ${name ?? ""}`);
    values[name.slice(2)] = argv[index + 1];
  }
  for (const required of ["input", "output", "repository", "tag", "published-at"]) {
    if (typeof values[required] !== "string" || values[required].trim() === "") {
      throw new Error(`missing --${required}`);
    }
  }
  return {
    input: values.input,
    output: values.output,
    repository: values.repository,
    tag: values.tag,
    publishedAt: values["published-at"],
  };
}

/** 仅直接执行脚本时运行 CLI，测试导入不会触碰文件系统。 */
async function main() {
  await createUpdaterManifest(parseArguments(process.argv.slice(2)));
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    const message = error instanceof Error ? error.message : "updater manifest generation failed";
    process.stderr.write(`${message}\n`);
    process.exitCode = 1;
  });
}
