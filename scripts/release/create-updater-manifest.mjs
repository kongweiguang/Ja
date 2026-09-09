// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createHash, createPublicKey, verify as verifySignature } from "node:crypto";
import { copyFile, mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { basename, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const TARGETS = [
  {
    artifact: "ja-native-app-server-windows-x86_64",
    key: "windows-x86_64",
    platform: "windows",
    arch: "x86_64",
    bundle: "nsis",
    signatureSuffix: ".exe.sig",
    installerSuffix: ".exe",
    updaterOutput: "windows_x86_64-setup.exe",
    installerOutput: null,
  },
  {
    artifact: "ja-native-app-server-macos-x86_64",
    key: "darwin-x86_64",
    platform: "macos",
    arch: "x86_64",
    bundle: "dmg",
    signatureSuffix: ".app.tar.gz.sig",
    installerSuffix: ".dmg",
    updaterOutput: "darwin_x86_64.app.tar.gz",
    installerOutput: "darwin_x86_64.dmg",
  },
  {
    artifact: "ja-native-app-server-macos-arm64",
    key: "darwin-aarch64",
    platform: "macos",
    arch: "arm64",
    bundle: "dmg",
    signatureSuffix: ".app.tar.gz.sig",
    installerSuffix: ".dmg",
    updaterOutput: "darwin_aarch64.app.tar.gz",
    installerOutput: "darwin_aarch64.dmg",
  },
];
const HEX_40 = /^[0-9a-f]{40}$/iu;
const HEX_64 = /^[0-9a-f]{64}$/iu;

/** 递归枚举普通文件，聚合器只从 Actions 上传的真实文件推导资产。 */
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

/** 确认文件存在且非空，防止缺失或零字节结果进入公开 Release。 */
async function requireFile(path, description) {
  let details;
  try {
    details = await stat(path);
  } catch (error) {
    throw new Error(`${description} is missing: ${path}`, { cause: error });
  }
  if (!details.isFile() || details.size <= 0)
    throw new Error(`${description} is empty or not a file: ${path}`);
  return path;
}

/** 从 Minisign 文本中提取首个 Base64 数据行，忽略 untrusted/trusted comment。 */
function firstBase64Line(text, description) {
  const line = text
    .split(/\r?\n/u)
    .map((value) => value.trim())
    .find((value) => value !== "" && /^[A-Za-z0-9+/]+={0,2}$/u.test(value));
  if (!line) throw new Error(`${description} has no Base64 payload`);
  return line;
}

/** 解析 Tauri 配置中的 Minisign 公钥并保留 key id，拒绝其它签名密钥。 */
function parseMinisignPublicKey(text) {
  let keyText = text.trim();
  try {
    const wrappedText = Buffer.from(keyText, "base64").toString("utf8");
    if (wrappedText.startsWith("untrusted comment:", 0)) keyText = wrappedText;
  } catch {
    // The direct textual form is handled below and remains the supported test form.
  }
  const decoded = Buffer.from(firstBase64Line(keyText, "updater public key"), "base64");
  if (decoded.length !== 42 || decoded.subarray(0, 2).toString("ascii") !== "Ed")
    throw new Error("updater public key is not a Minisign Ed25519 public key");
  const keyId = decoded.subarray(2, 10);
  const keyObject = createPublicKey({
    key: { crv: "Ed25519", kty: "OKP", x: decoded.subarray(10).toString("base64url") },
    format: "jwk",
  });
  return { keyId, keyObject };
}

/** 读取显式公钥或仓库 Tauri 配置，默认配置保持已安装客户端的信任根不变。 */
async function loadMinisignPublicKey({ publicKey, tauriConfig }) {
  if (typeof publicKey === "string" && publicKey.trim() !== "")
    return parseMinisignPublicKey(publicKey);
  const configPath = resolve(tauriConfig ?? "src-tauri/tauri.conf.json");
  let config;
  try {
    config = JSON.parse(await readFile(configPath, "utf8"));
  } catch (error) {
    throw new Error(`cannot read Tauri updater config: ${configPath}`, { cause: error });
  }
  const configuredKey = config?.plugins?.updater?.pubkey;
  if (typeof configuredKey !== "string" || configuredKey.trim() === "")
    throw new Error("Tauri updater public key is missing from configuration");
  return parseMinisignPublicKey(configuredKey);
}

/** 验证当前 Tauri CLI 的 Base64 封装、Blake2b 预哈希与可信注释，不混用系统证书。 */
function verifyMinisign(data, signatureText, publicKey) {
  const lines = Buffer.from(signatureText.trim(), "base64").toString("utf8").trim().split(/\r?\n/u);
  if (
    lines.length !== 4 ||
    !lines[0].startsWith("untrusted comment:") ||
    !lines[2].startsWith("trusted comment: ")
  )
    throw new Error("updater signature is not a Tauri Minisign envelope");
  const decoded = Buffer.from(lines[1], "base64");
  if (decoded.length !== 74 || decoded.subarray(0, 2).toString("ascii") !== "ED")
    throw new Error("updater signature is not a prehashed Minisign Ed25519 signature");
  if (!decoded.subarray(2, 10).equals(publicKey.keyId))
    throw new Error("updater signature key id does not match Tauri updater public key");
  const prehashed = createHash("blake2b512").update(data).digest();
  if (!verifySignature(null, prehashed, publicKey.keyObject, decoded.subarray(10)))
    throw new Error("updater signature does not verify against the artifact bytes");
  const comment = Buffer.from(lines[2].slice("trusted comment: ".length), "utf8");
  if (
    !verifySignature(
      null,
      Buffer.concat([decoded.subarray(10), comment]),
      publicKey.keyObject,
      Buffer.from(lines[3], "base64"),
    )
  )
    throw new Error("updater trusted comment signature is invalid");
}

/** 计算最终 Release 文件摘要，清单不从预期文件名或模板推导。 */
async function sha256(path) {
  const hash = createHash("sha256");
  hash.update(await readFile(path));
  return hash.digest("hex");
}

/** 读取一个矩阵目标的更新包、签名、安装器和 collector 证据，并拒绝半成品平台。 */
async function readTargetEvidence(root, target) {
  const artifactRoot = join(root, target.artifact);
  const allFiles = await filesBelow(artifactRoot);
  const signatures = allFiles.filter((path) => path.endsWith(target.signatureSuffix));
  if (signatures.length !== 1)
    throw new Error(
      `${target.key} expected one ${target.signatureSuffix}, found ${signatures.length}`,
    );
  const signaturePath = signatures[0];
  const updaterPath = signaturePath.slice(0, -4);
  await requireFile(updaterPath, `${target.key} updater artifact`);
  const signature = (await readFile(signaturePath, "utf8")).trim();
  if (signature === "") throw new Error(`${target.key} updater signature is empty`);
  const bundleMarker = `/bundle/${target.bundle}/`;
  const installers = allFiles.filter((path) => {
    const normalized = path.replaceAll("\\", "/").toLowerCase();
    return normalized.includes(bundleMarker) && normalized.endsWith(target.installerSuffix);
  });
  if (installers.length !== 1)
    throw new Error(
      `${target.key} expected one ${target.installerSuffix} installer, found ${installers.length}`,
    );
  const installerPath = installers[0];
  const evidencePaths = allFiles.filter((path) => basename(path) === "tauri-bundle-manifest.json");
  if (evidencePaths.length !== 1)
    throw new Error(
      `${target.key} expected one tauri-bundle-manifest.json, found ${evidencePaths.length}`,
    );
  let evidence;
  try {
    evidence = JSON.parse(await readFile(evidencePaths[0], "utf8"));
  } catch (error) {
    throw new Error(`${target.key} has invalid tauri-bundle-manifest.json`, { cause: error });
  }
  if (evidence.sourceCommit === undefined || !HEX_40.test(String(evidence.sourceCommit)))
    throw new Error(`${target.key} evidence sourceCommit is invalid`);
  if (
    evidence.target?.platform !== target.platform ||
    evidence.target?.arch !== target.arch ||
    evidence.target?.bundle !== target.bundle
  )
    throw new Error(`${target.key} evidence target does not match the release matrix`);
  if (evidence.build?.noSign !== false || evidence.build?.signingStatus !== "unsigned")
    throw new Error(
      `${target.key} must explicitly record an unsigned system bundle with release.noSign=false`,
    );
  if (evidence.build?.notarizationStatus !== "not-run")
    throw new Error(`${target.key} system signing evidence is not the unsigned release path`);
  const artifactFacts = Array.isArray(evidence.artifacts) ? evidence.artifacts : [];
  const installerFact = artifactFacts.find((entry) => entry?.fileName === basename(installerPath));
  const installerStats = await stat(installerPath);
  if (
    !installerFact ||
    installerFact.sizeBytes !== installerStats.size ||
    !HEX_64.test(installerFact.sha256) ||
    (await sha256(installerPath)) !== installerFact.sha256.toLowerCase()
  )
    throw new Error(`${target.key} installer evidence does not match the uploaded file`);
  const updaterEvidence = evidence.updater;
  if (updaterEvidence?.status !== "pending-aggregate-verification")
    throw new Error(`${target.key} updater evidence is missing`);
  for (const [label, path, fact] of [
    ["updater", updaterPath, updaterEvidence.artifact],
    ["updater signature", signaturePath, updaterEvidence.signature],
  ]) {
    const details = await stat(path);
    if (
      fact?.fileName !== basename(path) ||
      fact.sizeBytes !== details.size ||
      !HEX_64.test(fact.sha256) ||
      (await sha256(path)) !== fact.sha256.toLowerCase()
    )
      throw new Error(`${target.key} ${label} evidence does not match the uploaded file`);
  }
  return {
    target,
    sourceCommit: String(evidence.sourceCommit).toLowerCase(),
    updaterPath,
    signaturePath,
    signature,
    installerPath,
  };
}

/** 将一个已验证文件复制为 Release 稳定名称，并记录最终字节事实。 */
async function copyReleaseArtifact(sourcePath, outputPath, metadata) {
  if (resolve(sourcePath) !== resolve(outputPath)) await copyFile(sourcePath, outputPath);
  const details = await stat(outputPath);
  if (details.size <= 0) throw new Error(`copied artifact is empty: ${outputPath}`);
  return {
    fileName: basename(outputPath),
    role: metadata.role,
    target: metadata.target,
    arch: metadata.arch,
    bundle: metadata.bundle,
    sizeBytes: details.size,
    sha256: await sha256(outputPath),
    signatureFile: metadata.signatureFile ?? null,
    signingStatus: metadata.signingStatus,
  };
}

/** 从三平台真实产物生成 Tauri metadata、DMG、签名文件和可审计校验清单。 */
export async function createUpdaterManifest({
  input,
  output,
  repository,
  tag,
  publishedAt,
  sourceCommit,
  publicKey,
  tauriConfig,
}) {
  const version = tag.replace(/^v/u, "");
  if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/u.test(version))
    throw new Error(`tag is not a supported semantic version: ${tag}`);
  const publicationDate = new Date(publishedAt);
  if (Number.isNaN(publicationDate.valueOf()))
    throw new Error("publishedAt is not RFC 3339 compatible");
  await mkdir(output, { recursive: true });
  const verifier = await loadMinisignPublicKey({ publicKey, tauriConfig });
  const sources = await Promise.all(TARGETS.map((target) => readTargetEvidence(input, target)));
  const commits = new Set(sources.map((source) => source.sourceCommit));
  if (commits.size !== 1) throw new Error("native matrix source commits do not match");
  const actualSourceCommit = [...commits][0];
  if (sourceCommit !== undefined && sourceCommit.toLowerCase() !== actualSourceCommit)
    throw new Error("native matrix source commit differs from requested sourceCommit");
  const platforms = {};
  const artifacts = [];
  for (const source of sources) {
    const { target } = source;
    const updaterName = `Ja_${version}_${target.updaterOutput}`;
    const signatureName = `${updaterName}.sig`;
    const updaterPath = join(output, updaterName);
    const signaturePath = join(output, signatureName);
    verifyMinisign(await readFile(source.updaterPath), source.signature, verifier);
    await copyFile(source.updaterPath, updaterPath);
    await writeFile(signaturePath, `${source.signature}\n`, "utf8");
    artifacts.push(
      await copyReleaseArtifact(updaterPath, updaterPath, {
        role: "updater",
        target: target.key,
        arch: target.arch,
        bundle: target.platform === "windows" ? "nsis" : "app-tar-gz",
        signatureFile: signatureName,
        signingStatus: "verified",
      }),
    );
    artifacts.push(
      await copyReleaseArtifact(signaturePath, signaturePath, {
        role: "signature",
        target: target.key,
        arch: target.arch,
        bundle: target.platform === "windows" ? "nsis" : "app-tar-gz",
        signingStatus: "verified",
      }),
    );
    platforms[target.key] = {
      url: `https://github.com/${repository}/releases/download/${tag}/${encodeURIComponent(updaterName)}`,
      signature: source.signature,
    };
    if (target.installerOutput !== null) {
      const installerName = `Ja_${version}_${target.installerOutput}`;
      artifacts.push(
        await copyReleaseArtifact(source.installerPath, join(output, installerName), {
          role: "installer",
          target: target.key,
          arch: target.arch,
          bundle: target.bundle,
          signingStatus: "unsigned",
        }),
      );
    }
  }
  const manifest = {
    version,
    notes: `Ja ${tag}`,
    pub_date: publicationDate.toISOString(),
    platforms,
  };
  const releaseManifest = {
    schemaVersion: 1,
    product: "Ja",
    version,
    tag,
    sourceCommit: actualSourceCommit,
    release: { noSign: false, systemSigning: "unsigned", updaterSigning: "verified" },
    artifacts,
  };
  await writeFile(join(output, "latest.json"), `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  await writeFile(
    join(output, "artifact-manifest.json"),
    `${JSON.stringify(releaseManifest, null, 2)}\n`,
    "utf8",
  );
  await writeFile(
    join(output, "SHA256SUMS"),
    `${artifacts.map((artifact) => `${artifact.sha256}  ${artifact.fileName}`).join("\n")}\n`,
    "ascii",
  );
  return manifest;
}

/** 解析封闭 CLI 参数，保留可选 source commit、公钥和配置路径以便复核。 */
function parseArguments(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    if (name?.startsWith("--") !== true) throw new Error(`invalid argument: ${name ?? ""}`);
    values[name.slice(2)] = argv[index + 1];
  }
  for (const required of ["input", "output", "repository", "tag", "published-at"]) {
    if (typeof values[required] !== "string" || values[required].trim() === "")
      throw new Error(`missing --${required}`);
  }
  return {
    input: values.input,
    output: values.output,
    repository: values.repository,
    tag: values.tag,
    publishedAt: values["published-at"],
    sourceCommit: values["source-commit"],
    publicKey: values["public-key"],
    tauriConfig: values["tauri-config"],
  };
}

/** 仅直接执行脚本时运行 CLI，测试导入不会触碰工作流文件系统。 */
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
