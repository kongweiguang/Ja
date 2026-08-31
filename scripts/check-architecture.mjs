// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { access, readdir, readFile } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const desktopRoot = path.join(repositoryRoot, "apps", "desktop");
const desktopSourceRoot = path.join(repositoryRoot, "apps", "desktop", "src");
const desktopTestRoot = path.join(repositoryRoot, "apps", "desktop", "tests");
const architectureFixtureRoot = path.join(repositoryRoot, "scripts", "fixtures", "architecture");
const cratesRoot = path.join(repositoryRoot, "crates");
const runtimeCrateRoot = path.join(repositoryRoot, "crates", "ja-runtime");
const tauriSourceRoot = path.join(repositoryRoot, "src-tauri", "src");
const sourceExtensions = new Set([".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx", ".mts", ".cts"]);
const rustDddOwners = new Set(["workspace", "review", "app_runtime"]);
const rustDddLayers = new Set(["domain", "application", "infrastructure", "interface"]);
const runtimePhysicalLayers = new Set(["protocol", "process", "lifecycle", "client"]);
const runtimePrivateModule =
  /\bpub\s+mod\s+(?:client|codec|error|handshake|lifecycle|pending|process|process_tree|protocol|session|supervisor)\b/;
const criticalResponsibilityName =
  /(?:controller|service|adapter|transaction|cas|watch|cleanup|reconcile|recover|rollback|mutation|mutate|apply|save|write|move|trash|drop|switch|start|stop|cancel|approve|admit|spawn|launch|shutdown|exit|flush|commit|snapshot|transition|dispatch|handle|execute|invoke|request|response|event|lock|queue|fence|validate|contain)/i;
const retiredDesktopApis = [
  "ConnectionProvider",
  "useJaConnection",
  "useJaSession",
  "WorkbenchTool",
  "new_with_home",
  "new_with_runtime_control",
  "workspace_read::",
  "ja_git_status",
  "ja_git_diff",
  "ja_git_snapshot",
];
const retiredRustApis = [
  "new_with_home",
  "new_with_runtime_control",
  "workspace_read::",
  "ja_git_status",
  "ja_git_diff",
  "ja_git_snapshot",
];
const reactLayerNames = new Set(["domain", "application", "ui"]);
const reactTestPackageAreas = new Set(["app", "api", "features", "shared"]);
const reactSourceTestDirectoryNames = new Set([
  "tests",
  "__tests__",
  "__snapshots__",
  "__fixtures__",
]);
const appNativeAdapterCompositionRoots = new Set([
  "composition/defaultAdapters.ts",
  "composition/runtimeHostAdapter.ts",
]);
const reactDddFeatures = [
  { owner: "command", layers: ["domain", "application", "ui"] },
  { owner: "workspace", layers: ["domain", "application", "ui"] },
  { owner: "conversation", layers: ["domain", "application", "ui"] },
  { owner: "navigation", layers: ["domain", "application", "ui"] },
  { owner: "settings", layers: ["domain", "application", "ui"] },
  // Workbench 只编排受控 Tab 意图，没有独立 native 用例；application 由各能力子域持有。
  { owner: "workbench", layers: ["domain", "ui"] },
  { owner: "workbench/files", layers: ["domain", "application", "ui"] },
  // Editor 只有纯语言规则与无副作用视图，不为不存在的用例机械创建 application 层。
  { owner: "workbench/editor", layers: ["domain", "ui"] },
  { owner: "workbench/review", layers: ["domain", "application", "ui"] },
  { owner: "workbench/preview", layers: ["domain", "application", "ui"] },
  { owner: "workbench/terminal", layers: ["domain", "application", "ui"] },
];
/** 统一使用正斜杠比较责任路径，避免同一 fixture 在 Windows 与 CI 上产生不同结果。 */
function portablePath(filePath) {
  return filePath.split(path.sep).join("/");
}

/**
 * 依赖检查只读取代码 token，注释、普通字符串和 Rust raw string 都替换为空白；
 * 保留换行使后续违规行号仍可定位，同时避免规范说明本身触发架构规则。
 */
function stripCommentsAndStrings(source, recognizeRustLifetimes = true) {
  let result = "";
  let index = 0;
  const blank = (value) => (value === "\n" || value === "\r" ? value : " ");
  while (index < source.length) {
    if (source.startsWith("//", index)) {
      const end = source.indexOf("\n", index + 2);
      const boundary = end < 0 ? source.length : end;
      result += source.slice(index, boundary).split("").map(blank).join("");
      index = boundary;
      continue;
    }
    if (source.startsWith("/*", index)) {
      const end = source.indexOf("*/", index + 2);
      const boundary = end < 0 ? source.length : end + 2;
      result += source.slice(index, boundary).split("").map(blank).join("");
      index = boundary;
      continue;
    }
    const raw = /^r(#+)?"/.exec(source.slice(index));
    if (raw !== null) {
      const suffix = `"${raw[1] ?? ""}`;
      const end = source.indexOf(suffix, index + raw[0].length);
      const boundary = end < 0 ? source.length : end + suffix.length;
      result += source.slice(index, boundary).split("").map(blank).join("");
      index = boundary;
      continue;
    }
    const lifetimeMatch =
      source[index] === "'" ? /^'[A-Za-z_][A-Za-z0-9_]*/.exec(source.slice(index)) : null;
    const lifetime =
      recognizeRustLifetimes &&
      lifetimeMatch !== null &&
      source[index + lifetimeMatch[0].length] !== "'";
    if (source[index] === '"' || source[index] === "`" || (source[index] === "'" && !lifetime)) {
      const quote = source[index];
      let cursor = index + 1;
      while (cursor < source.length) {
        if (source[cursor] === "\\") cursor += 2;
        else if (source[cursor] === quote) {
          cursor += 1;
          break;
        } else cursor += 1;
      }
      result += source.slice(index, cursor).split("").map(blank).join("");
      index = cursor;
      continue;
    }
    result += source[index];
    index += 1;
  }
  return result;
}

/** TypeScript 单引号始终是字符串边界，不能被 Rust lifetime 规则截断后续测试或依赖 token。 */
function stripTypeScriptCommentsAndStrings(source) {
  return stripCommentsAndStrings(source, false);
}

/** cfg/test 扫描需要保留 attribute 字符串，只移除真实注释并保持 offset。 */
function stripComments(source) {
  let result = "";
  let index = 0;
  while (index < source.length) {
    if (source.startsWith("//", index)) {
      const end = source.indexOf("\n", index + 2);
      const boundary = end < 0 ? source.length : end;
      result += source.slice(index, boundary).replace(/[^\r\n]/g, " ");
      index = boundary;
      continue;
    }
    if (source.startsWith("/*", index)) {
      const end = source.indexOf("*/", index + 2);
      const boundary = end < 0 ? source.length : end + 2;
      result += source.slice(index, boundary).replace(/[^\r\n]/g, " ");
      index = boundary;
      continue;
    }
    const raw = /^r(#+)?"/.exec(source.slice(index));
    const quote = raw !== null ? `"${raw[1] ?? ""}` : source[index] === '"' ? '"' : null;
    if (quote !== null) {
      const prefixLength = raw?.[0].length ?? 1;
      let cursor = index + prefixLength;
      while (cursor < source.length) {
        if (raw === null && source[cursor] === "\\") cursor += 2;
        else if (source.startsWith(quote, cursor)) {
          cursor += quote.length;
          break;
        } else cursor += 1;
      }
      result += source.slice(index, cursor);
      index = cursor;
      continue;
    }
    result += source[index];
    index += 1;
  }
  return result;
}

/** Rust use tree 去掉空白与分组括号后形成稳定路径，覆盖 `std::{fs, process}` 等写法。 */
function normalizedRustCode(source) {
  return stripCommentsAndStrings(source)
    .replace(/\s*::\s*/g, "::")
    .replace(/[{}]/g, "")
    .replace(/[ \t]+/g, " ");
}

/**
 * 按配对花括号提取 enum body；Gate 只需要识别类型结构，不依赖可能被重命名的 enum/variant。
 */
function rustEnumBodies(source) {
  const code = stripCommentsAndStrings(source);
  const bodies = [];
  for (const declaration of code.matchAll(/\benum\s+([A-Za-z_][A-Za-z0-9_]*)[^;{]*\{/g)) {
    const open = (declaration.index ?? 0) + declaration[0].lastIndexOf("{");
    let depth = 1;
    let cursor = open + 1;
    while (cursor < code.length && depth > 0) {
      if (code[cursor] === "{") depth += 1;
      else if (code[cursor] === "}") depth -= 1;
      cursor += 1;
    }
    if (depth === 0) bodies.push({ name: declaration[1], body: code.slice(open + 1, cursor - 1) });
  }
  return bodies;
}

/**
 * Null/Bool/有符号数/无符号数/文本/递归 List/Object 的组合就是通用 JSON 树；
 * application 不得通过改名或自定义 enum 绕过动态值禁令。
 */
function containsGenericStructuredValueTree(source) {
  return rustEnumBodies(source).some(({ name, body }) => {
    const recursive = `(?:Self|${name})`;
    const unitVariant = /(?:^|,)\s*[A-Za-z_][A-Za-z0-9_]*\s*(?=,|$)/m.test(body);
    const boolean = /\(\s*bool\s*\)/.test(body);
    const signed = /\(\s*i(?:32|64|128)\s*\)/.test(body);
    const unsigned = /\(\s*u(?:32|64|128)\s*\)/.test(body);
    const textValues = [...body.matchAll(/\(\s*String\s*\)/g)].length >= 2;
    const list = new RegExp(`\\(\\s*Vec\\s*<\\s*${recursive}\\s*>\\s*\\)`).test(body);
    const object = new RegExp(
      `\\(\\s*(?:BTreeMap|HashMap|IndexMap)\\s*<\\s*String\\s*,\\s*${recursive}\\s*>\\s*\\)`,
    ).test(body);
    return unitVariant && boolean && signed && unsigned && textValues && list && object;
  });
}

/**
 * 识别只生成 operation-specific bytes newtype 的宏及其具名调用；宏模板与调用区间会从
 * raw bytes 扫描中排除，但具体生成类型仍参与共享/通用隧道检查。
 */
function rustOperationPayloadMacros(source) {
  const code = stripCommentsAndStrings(source);
  const macros = [];
  for (const declaration of code.matchAll(/\bmacro_rules!\s*([A-Za-z_][A-Za-z0-9_]*)/g)) {
    const macroName = declaration[1];
    const invocationPattern = new RegExp(`\\b${macroName}!\\s*\\(([^)]*)\\)\\s*;`, "g");
    invocationPattern.lastIndex = (declaration.index ?? 0) + declaration[0].length;
    const invocation = invocationPattern.exec(code);
    if (invocation === null) continue;
    const template = code.slice(declaration.index ?? 0, invocation.index);
    const generated =
      /\bstruct\s+\$\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*Vec\s*<\s*u8\s*>\s*\)\s*;/.exec(template);
    if (generated === null || !new RegExp(`\\bimpl\\s+\\$\\s*${generated[1]}\\b`).test(template))
      continue;
    const helperNames = rustFunctionSignatures(template).map(({ name }) => name);
    if (helperNames.some((name) => !["try_new", "into_bytes"].includes(name))) continue;
    const symbols = new Set(
      [...invocation[1].matchAll(/\b([A-Za-z_][A-Za-z0-9_]*)\b/g)].map((match) => match[1]),
    );
    macros.push({
      start: declaration.index ?? 0,
      end: invocation.index + invocation[0].length,
      symbols,
    });
  }
  return macros;
}

/** 每个 operation 可以拥有独立 bytes newtype；类型名本身不是边界，结构与使用方式才是。 */
function rustNominalByteNewtypes(source) {
  const code = normalizedRustCode(source);
  const symbols = new Set(
    [...code.matchAll(/\bstruct\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*Vec\s*<\s*u8\s*>\s*\)\s*;/g)].map(
      (match) => match[1],
    ),
  );
  for (const operationMacro of rustOperationPayloadMacros(source)) {
    for (const symbol of operationMacro.symbols) symbols.add(symbol);
  }
  return symbols;
}

/** 提取函数/trait method 的有界签名，足以判断 selector 与 payload 是否形成通用隧道。 */
function rustFunctionSignatures(source) {
  const code = normalizedRustCode(source);
  return [
    ...code.matchAll(
      /\bfn\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>{;]*>)?\s*\([\s\S]*?\)\s*(?:->\s*[^;{]+)?(?=;|\{)/g,
    ),
  ].map((match) => ({ name: match[1], signature: match[0] }));
}

/**
 * application 禁止 raw Vec<u8>、selector+bytes 通用请求和跨不同 operation 复用同一
 * bytes newtype；逐 operation nominal newtype 可以在同名 trait/host 方法中重复出现。
 */
function containsGenericByteTunnel(source, knownByteTypes = new Set()) {
  let sourceWithoutOperationMacros = source;
  const operationMacros = rustOperationPayloadMacros(source);
  for (const { start, end } of [...operationMacros].sort(
    (left, right) => right.start - left.start,
  )) {
    sourceWithoutOperationMacros = `${sourceWithoutOperationMacros.slice(0, start)}${" ".repeat(end - start)}${sourceWithoutOperationMacros.slice(end)}`;
  }
  const code = normalizedRustCode(sourceWithoutOperationMacros);
  const declarationPattern = /\bstruct\s+[A-Za-z_][A-Za-z0-9_]*\s*\(\s*Vec\s*<\s*u8\s*>\s*\)\s*;/g;
  const withoutNewtypeDeclarations = code.replace(declarationPattern, " ");
  if (/\bVec\s*<\s*u8\s*>/.test(withoutNewtypeDeclarations)) return true;
  const signatures = rustFunctionSignatures(source);
  for (const { signature } of signatures) {
    const selector = /\(\s*(?:[^)]*,\s*)?(?:method|operation|action)\s*:[^,)]*(?:,|\))/.test(
      signature,
    );
    const bytes = [...knownByteTypes].some((symbol) =>
      new RegExp(`\\b${symbol}\\b`).test(signature),
    );
    if (selector && bytes) return true;
  }
  for (const symbol of knownByteTypes) {
    const methodNames = new Set(
      signatures
        .filter(({ signature }) => new RegExp(`\\b${symbol}\\b`).test(signature))
        .map(({ name }) => name),
    );
    if (methodNames.size > 1) return true;
    if (
      rustEnumBodies(source).some(
        ({ body }) => [...body.matchAll(new RegExp(`\\b${symbol}\\b`, "g"))].length > 1,
      )
    )
      return true;
  }
  return false;
}

/** 收集 application 所有 nominal bytes contract，使跨文件 generic tunnel 也无法逃逸。 */
async function rustApplicationByteContractSymbols(applicationRoot) {
  const symbols = new Set();
  for (const file of (await collectRustAndTomlFiles(applicationRoot)).filter(
    (candidate) => path.extname(candidate) === ".rs",
  )) {
    for (const symbol of rustNominalByteNewtypes(await readFile(file, "utf8"))) symbols.add(symbol);
  }
  return symbols;
}

/** 外层公开类型名用于追踪 owner 根 re-export，防止内层借 façade 隐藏真实定义来源。 */
async function rustPublicTypeSymbols(layerRoot) {
  const symbols = new Set();
  for (const file of (await collectRustAndTomlFiles(layerRoot)).filter(
    (candidate) => path.extname(candidate) === ".rs",
  )) {
    const code = normalizedRustCode(await readFile(file, "utf8"));
    for (const declaration of code.matchAll(
      /\bpub(?:\([^)]*\))?\s+(?:struct|enum|type|trait)\s+([A-Za-z_][A-Za-z0-9_]*)/g,
    )) {
      symbols.add(declaration[1]);
    }
  }
  return symbols;
}

/** 从 cfg attribute 提取 feature 名，精确白名单不依赖名称中是否碰巧含 test。 */
function rustFeatureAttributes(source) {
  return [...stripComments(source).matchAll(/#\s*\[\s*cfg(?:_attr)?\s*\([^\]]*\)\s*\]/g)].flatMap(
    (attribute) =>
      [...attribute[0].matchAll(/feature\s*=\s*["']([^"']+)["']/g)].map((feature) => ({
        attribute,
        feature: feature[1],
      })),
  );
}

/** 测试体必须进入独立文件；文件名和 tests 目录是显式责任，不依赖内容猜测。 */
function isDedicatedRustTestFile(filePath) {
  const normalized = portablePath(filePath);
  const baseName = path.basename(filePath);
  return (
    normalized.split("/").includes("tests") ||
    /^(?:test|tests|smoke)\.rs$/.test(baseName) ||
    baseName === "tests.rs" ||
    /_tests?\.rs$/.test(baseName) ||
    /^(?:test|smoke)_support\.rs$/.test(baseName) ||
    /_(?:test|smoke)_support\.rs$/.test(baseName)
  );
}

/**
 * 识别承担共享 fixture/Harness 的文件名；`test_support_tests.rs` 是验证 Harness 的测试体，
 * 不属于支撑实现，避免因名称相近被错误迁移出 unit。
 */
function isRustSupportImplementationFile(filePath) {
  const baseName = path.basename(portablePath(filePath));
  return /^(?:test_)?support\.rs$|_test_support\.rs$/.test(baseName);
}

/**
 * 识别 unit 测试直接执行的外部 IO。unit 只验证纯规则、状态机和 fake port；真实文件、
 * 子进程、网络与平台 API 必须进入 integration，避免目录名与实际测试成本/副作用失真。
 */
function rustUnitExternalIoOperations(source) {
  const code = stripCommentsAndStrings(source);
  const patterns = [
    {
      label: "文件系统",
      expression:
        /\b(?:std\s*::\s*)?fs\s*::\s*(?:canonicalize|copy|create_dir|create_dir_all|hard_link|metadata|read|read_dir|read_link|read_to_string|remove_dir|remove_dir_all|remove_file|rename|set_permissions|symlink_metadata|write|File|OpenOptions)\b/g,
    },
    {
      label: "子进程或 PTY",
      expression:
        /\b(?:(?:std|tokio)\s*::\s*process\s*::\s*)?Command\s*::\s*new\b|\b(?:tokio\s*::\s*process|portable_pty)\b/g,
    },
    {
      label: "网络",
      expression: /\b(?:TcpListener|TcpStream|UdpSocket)\s*::\s*(?:bind|connect)\b/g,
    },
    {
      label: "平台文件或 Windows API",
      expression: /\b(?:std\s*::\s*os\s*::\s*(?:windows|unix)\s*::\s*fs|windows\s*::\s*Win32)\b/g,
    },
  ];
  return patterns.flatMap(({ label, expression }) =>
    [...code.matchAll(expression)].map((match) => ({ label, index: match.index ?? 0 })),
  );
}

/** cfg 表达式先移除字符串再查找 test token，避免把 `feature = "test-support"` 误当成 `cfg(test)`。 */
function isRustTestCfgAttribute(attribute) {
  const withoutStrings = attribute.replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, "");
  return /\btest\b/.test(withoutStrings);
}

/** 返回生产文件中的全部 test cfg，调用方据此逐处验证是否只是 tests/unit 外置 wiring。 */
function rustTestCfgAttributes(source) {
  return [...stripComments(source).matchAll(/#\s*\[\s*cfg(?:_attr)?\s*\([^\]]*\)\s*\]/g)].filter(
    (match) => isRustTestCfgAttribute(match[0]),
  );
}

/** 测试函数属性按属性名而非运行时库白名单识别，防止更换 async test 宏绕过分目录约束。 */
function rustTestFunctionAttributes(source) {
  const testAttribute =
    /#\s*\[\s*(?:[A-Za-z_][A-Za-z0-9_]*::)*(?:test|rstest|test_case|[A-Za-z_][A-Za-z0-9_]*_test)(?:\s*\([^\]]*\))?\s*\]/g;
  return [...stripCommentsAndStrings(source).matchAll(testAttribute)];
}

/** 解析显式 path module，供注册闭包校验使用；测试包内部也必须可追溯到真实入口。 */
function rustPathModuleWirings(source) {
  return [
    ...stripComments(source).matchAll(
      /#\s*\[\s*path\s*=\s*"([^"\r\n]+)"\s*\]\s*(?:pub(?:\([^)]*\))?\s+)?mod\s+([A-Za-z_][A-Za-z0-9_]*)\s*;/g,
    ),
  ].map((match) => ({
    index: match.index ?? 0,
    pathLiteral: match[1],
    moduleName: match[2],
  }));
}

/**
 * 解析独立 unit/integration target 的显式 include；只接受 Cargo manifest 根拼接和固定
 * tests 路径，防止动态 include 或任意源码路径被误认成已注册测试。
 */
function rustManifestTestIncludes(source, crateRoot) {
  const code = stripComments(source);
  const includes = [
    ...code.matchAll(
      /include!\s*\(\s*concat!\s*\(\s*env!\s*\(\s*"CARGO_MANIFEST_DIR"\s*\)\s*,\s*"(\/tests\/(?:unit|integration|support)\/[^"\r\n]+\.rs)"\s*\)\s*\)\s*;/g,
    ),
  ].map((match) => path.resolve(crateRoot, `.${match[1].replace(/[\\/]+/g, path.sep)}`));
  const scoped = [
    ...code.matchAll(
      /\b(?:unit|integration)_scope!\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*,\s*[^,]+,\s*"(\/tests\/(?:unit|integration)\/[^"\r\n]+\.rs)"\s*\)\s*;/g,
    ),
  ].map((match) => path.resolve(crateRoot, `.${match[1].replace(/[\\/]+/g, path.sep)}`));
  return [...includes, ...scoped];
}

/** Cargo 只在当前 [[test]] table 读取 path，避免把普通 path dependency 错认成测试入口。 */
function manifestTestPaths(source) {
  const paths = [];
  let insideTest = false;
  for (const line of source.split(/\r?\n/)) {
    const header = /^\s*\[\[?([^\]]+)\]\]?\s*$/.exec(line);
    if (header !== null) {
      insideTest = header[1].trim() === "test";
      continue;
    }
    if (!insideTest) continue;
    const pathMatch = /^\s*path\s*=\s*["']([^"']+)["']/.exec(line);
    if (pathMatch !== null) paths.push(pathMatch[1]);
  }
  return paths;
}

/**
 * 从 Cargo test target 开始递归追踪 path module 和固定 include；生产 src 不参与装配，
 * 未进入该闭包的 .rs 文件不会被 Cargo 编译，必须作为未注册测试拒绝。
 */
async function registeredRustTestFiles(crateRoot, directTargets, testFiles) {
  const testFileSet = new Set(testFiles.map((file) => path.resolve(file)));
  const registered = new Set();
  const queue = [];
  const register = (candidate) => {
    const resolved = path.resolve(candidate);
    if (!testFileSet.has(resolved) || registered.has(resolved)) return;
    registered.add(resolved);
    queue.push(resolved);
  };
  directTargets.forEach(register);
  while (queue.length > 0) {
    const sourceFile = queue.shift();
    const source = await readFile(sourceFile, "utf8");
    for (const wiring of rustPathModuleWirings(source)) {
      const target = path.resolve(
        path.dirname(sourceFile),
        wiring.pathLiteral.replace(/[\\/]+/g, path.sep),
      );
      if (rustTestLayer(target, crateRoot) !== null) register(target);
    }
    for (const target of rustManifestTestIncludes(source, crateRoot)) register(target);
  }
  return registered;
}

/** 识别所属 crate 的测试层；unit 可接入私有 module，integration 只经 crate 公共 façade 验证。 */
function rustTestLayer(filePath, crateRoot) {
  const relative = path.relative(path.resolve(crateRoot, "tests"), path.resolve(filePath));
  if (relative.startsWith(`..${path.sep}`) || relative === ".." || path.isAbsolute(relative))
    return null;
  const [layer] = relative.split(path.sep);
  return ["unit", "integration", "support"].includes(layer) ? layer : null;
}

/** 返回 test module 声明及行号，生产 src 不得以任何形态装配测试模块。 */
function rustTestModuleDeclarations(source) {
  return [
    ...stripCommentsAndStrings(source).matchAll(
      /\b(mod\s+(tests|[A-Za-z_][A-Za-z0-9_]*_tests?)\s*(?:;|\{))/g,
    ),
  ].map((match) => ({
    index: (match.index ?? 0) + match[0].indexOf(match[1]),
    moduleName: match[2],
  }));
}

/** 生产源码不能声明或转发 Harness；测试支撑只允许存在于 tests/support。 */
function rustProductionHarnessDeclarations(source) {
  const code = stripCommentsAndStrings(source);
  return [
    ...code.matchAll(
      /\b(?:struct|enum|trait|type)\s+[A-Za-z_][A-Za-z0-9_]*Harness\b|^\s*pub(?:\([^)]*\))?\s+use\s+[^;]*\b[A-Za-z_][A-Za-z0-9_]*Harness\b[^;]*;/gm,
    ),
  ];
}

/** 把行号作为稳定证据返回，避免门禁只给文件名后仍需人工搜索散落测试。 */
function sourceLineNumber(source, offset) {
  return source.slice(0, offset).split(/\r?\n/).length;
}

/** raw Tauri 能力按导入、宏和类型路径识别，普通注释中的产品名不会制造违规。 */
function hasTauriApi(source) {
  const code = stripCommentsAndStrings(source);
  return /#\s*\[\s*tauri::command\s*\]|\buse\s+tauri(?:::|\s*(?:[{;]|as\b))|\btauri::[A-Za-z_]/.test(
    code,
  );
}

/**
 * DDD owner 只允许精确的 interface 层获得 Tauri 能力；mod/composition 是唯一组合入口，
 * 不再为迁移前的平铺 history/settings 文件保留永久豁免。
 */
function isDddTauriBoundary(relativePath) {
  const normalized = portablePath(relativePath);
  const parts = normalized.split("/");
  return (
    parts[1] === "interface" ||
    (parts.length === 2 && ["mod.rs", "composition.rs"].includes(parts[1]))
  );
}

/** 非 DDD command 只允许 commands.rs、composition root 或独立原生快捷键 interface。 */
function isExistingTauriBoundary(relativePath) {
  const normalized = portablePath(relativePath);
  const parts = normalized.split("/");
  return (
    normalized === "lib.rs" ||
    normalized === "native_shortcuts.rs" ||
    parts[1] === "interface" ||
    normalized.endsWith("/commands.rs")
  );
}

/** 将路径和源码共同纳入判断，fixture 能验证“同一 API 在不同层结果不同”。 */
function violatesTauriPlacement(relativePath, source) {
  const normalized = portablePath(relativePath);
  const owner = normalized.split("/")[0];
  if (rustDddOwners.has(owner)) {
    return hasTauriApi(source) && !isDddTauriBoundary(normalized);
  }
  return hasTauriApi(source) && !isExistingTauriBoundary(normalized);
}

/** 生产 src 的 feature cfg 全部进入审计；测试差异必须由独立 Cargo test target 装配。 */
function runtimeTestFeatureAttributes(source) {
  const attributes = rustFeatureAttributes(source).map(({ attribute }) => attribute);
  return attributes.filter(
    (attribute, index) =>
      attributes.findIndex((candidate) => candidate.index === attribute.index) === index,
  );
}

/** 生产实现不得恢复测试专用函数或字段，确定性控制统一由 tests/support Harness 暴露。 */
function containsRustTestOnlyProductionSymbol(source) {
  const code = stripCommentsAndStrings(source);
  return (
    /\bfn\s+(?:new_for_[A-Za-z0-9_]+|[A-Za-z0-9_]+_for_test)\b/.test(code) ||
    /^\s*(?:pub(?:\([^)]*\))?\s+)?(?:[A-Za-z_][A-Za-z0-9_]*_for_test|(?:test|smoke)_[A-Za-z0-9_]+)\s*:/m.test(
      code,
    )
  );
}

/** 旧 Workbench Tab 只能被删除，v10 fixture 也不得继续演练迁移映射。 */
function containsRetiredWorkbenchPreferenceMapping(source) {
  return (
    /rightPanelTab\s*:\s*["'](?:search|diff|git)["']/.test(source) ||
    /rightPanelTabs\s*:\s*\[[^\]]*["'](?:search|diff|git)["']/s.test(source) ||
    /normalizeRightPanelTab\(\s*["'](?:search|diff|git)["']\s*\)/.test(source)
  );
}

/** Workbench 类型只能暴露五个当前能力，旧 search/diff/git union 分支不得恢复。 */
function containsRetiredWorkbenchTabAlias(source) {
  return /\btype\s+WorkbenchCapabilityTab\s*=\s*[^;]*["'](?:search|diff|git)["']/.test(source);
}

/** 旧 Command Palette shape 不得再转换到 canonical action，调用方必须直接使用唯一契约。 */
function containsLegacyCommandPaletteMapping(source) {
  return (
    /\bLegacyCommandPaletteAction\b/.test(source) || /\baction\.(?:run|disabled)\b/.test(source)
  );
}

/** 返回首个已退休符号，调用方可给出稳定且可定位的错误信息。 */
function retiredApiInSource(source, retiredApis) {
  const code = stripCommentsAndStrings(source);
  return retiredApis.find((retired) => code.includes(retired)) ?? null;
}

/** v10 使用独立 key/schema，生产代码不得再次读取或迁移 v8/v9。 */
function containsRetiredPreferenceStorage(source) {
  return (
    /["'][^"']*(?:preferences|ui)[^"']*v(?:8|9)[^"']*["']/i.test(source) ||
    /\b(?:migrate|upgrade|readLegacy)[A-Za-z0-9_]*(?:Preferences|UiState)\b/.test(
      stripTypeScriptCommentsAndStrings(source),
    )
  );
}

/** 同一文件默认只展示少量示例；显式诊断时可用 JA_ARCHITECTURE_VERBOSE=1 输出全部行号。 */
function lineSummary(lineNumbers) {
  const exampleLimit = process.env.JA_ARCHITECTURE_VERBOSE === "1" ? lineNumbers.length : 8;
  const examples = lineNumbers.slice(0, exampleLimit).join(",");
  const omitted =
    lineNumbers.length > exampleLimit ? `，另有 ${lineNumbers.length - exampleLimit} 处` : "";
  return `${lineNumbers.length} 处（示例行 ${examples}${omitted}）`;
}

/** 将生产树和镜像测试树统一映射到桌面模块相对路径，依赖规则因此不会因测试物理迁移而失效。 */
function desktopModuleRelativePath(filePath) {
  for (const root of [desktopSourceRoot, desktopTestRoot]) {
    const relative = path.relative(root, filePath);
    if (!relative.startsWith("..") && !path.isAbsolute(relative)) return portablePath(relative);
  }
  return null;
}

/** Workbench 的能力子目录拥有独立状态边界，不能借同一顶层目录互相深引。 */
function featureIdentity(filePath) {
  const relative = desktopModuleRelativePath(filePath);
  if (relative === null) return null;
  const parts = relative.split("/");
  if (parts[0] !== "features" || parts.length < 2) return null;
  const topLevel = parts[1];
  const workbenchChildren = new Set([
    "editor",
    "files",
    "git",
    "preview",
    "review",
    "search",
    "terminal",
  ]);
  if (topLevel === "workbench" && workbenchChildren.has(parts[2])) {
    return `workbench/${parts[2]}`;
  }
  return topLevel;
}

/**
 * app 与跨 feature 依赖必须命中目录级入口；app 额外允许只导出视图的 ui/index.ts，
 * 使重量级 renderer 能成为独立 lazy chunk，同时仍拒绝 application/domain 实现深链。
 */
function isPublicFeatureImport(fromArea, fromFeature, toFeature, specifier, target = null) {
  if (fromArea === "features" && fromFeature === toFeature) return true;
  if (target !== null) {
    const relativeTarget = portablePath(path.relative(desktopSourceRoot, target)).replace(
      /\/index$/,
      "",
    );
    if (
      relativeTarget === `features/${toFeature}` ||
      (fromArea === "app" && relativeTarget === `features/${toFeature}/ui`)
    )
      return true;
  }
  if (
    fromFeature === "workbench" &&
    toFeature.startsWith("workbench/") &&
    specifier === `./${toFeature.slice("workbench/".length)}`
  ) {
    return true;
  }
  return specifier === `@/features/${toFeature}`;
}

/** 只遍历人工维护的 TypeScript，避免生成物与依赖目录让门禁依赖本机状态。 */
async function collectSourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectSourceFiles(entryPath)));
    } else if (sourceExtensions.has(path.extname(entry.name))) {
      files.push(entryPath);
    }
  }
  return files;
}

/** 作者与语言注释门禁需要覆盖 CSS/TOML，仍只遍历明确的人工维护扩展。 */
async function collectMaintainableFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await collectMaintainableFiles(entryPath)));
    else if ([...sourceExtensions, ".css", ".rs", ".toml"].includes(path.extname(entry.name)))
      files.push(entryPath);
  }
  return files;
}

/** 提取静态与动态 import；门禁只需要依赖边，不额外引入 TypeScript AST 依赖。 */
function moduleSpecifiers(source) {
  const specifiers = [];
  const patterns = [
    /\b(?:import|export)\s+(?:type\s+)?(?:[^"']*?\s+from\s+)?["']([^"']+)["']/g,
    /\bimport\(\s*["']([^"']+)["']\s*\)/g,
  ];
  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) {
      specifiers.push(match[1]);
    }
  }
  return specifiers;
}

/** application 只声明并消费端口，具体 Tauri 与持久化 adapter 必须由 app composition 注入。 */
function featureApplicationForbiddenDependency(source) {
  const forbiddenSpecifier = moduleSpecifiers(source).find(
    (specifier) =>
      specifier === "@/api/tauri" ||
      specifier.startsWith("@/api/tauri/") ||
      specifier.startsWith("@tauri-apps/") ||
      specifier === "@/shared/storage" ||
      specifier.startsWith("@/shared/storage/") ||
      specifier === "@/shared/preferences" ||
      specifier.startsWith("@/shared/preferences/"),
  );
  if (forbiddenSpecifier !== undefined) return forbiddenSpecifier;
  const storageAccess =
    /\b(?:(?:window|globalThis)\s*\.\s*)?(localStorage|sessionStorage)\s*\.\s*(?:getItem|setItem|removeItem|clear|key)\s*\(/.exec(
      source,
    );
  return storageAccess?.[1] ?? null;
}

/** ui（含独立测试包的 ui 层）只能消费 view model 与 action，不允许绕过 controller 直连 native adapter。 */
function featureUiForbiddenDependency(source) {
  return (
    moduleSpecifiers(source).find(
      (specifier) =>
        specifier === "@/api/tauri" ||
        specifier.startsWith("@/api/tauri/") ||
        specifier.startsWith("@tauri-apps/"),
    ) ?? null
  );
}

/** 本地 storage module 与浏览器 storage 一样是基础设施，不能绕过 composition 注入。 */
function isStorageModuleTarget(target) {
  if (target === null) return false;
  const relative = portablePath(path.relative(desktopSourceRoot, target));
  return (
    relative === "shared/storage" ||
    relative.startsWith("shared/storage/") ||
    relative === "shared/preferences" ||
    relative.startsWith("shared/preferences/")
  );
}

/** 层依赖按解析后的真实目标判断，alias 与相对路径因此遵守同一规则。 */
function reactLayerDependencyViolation(sourceFile, source, specifier, target) {
  if (sourceArea(sourceFile) !== "features") return null;
  const relative = desktopModuleRelativePath(sourceFile) ?? "";
  const fromLayer = reactFeatureLayer(relative);
  if (fromLayer === null) return null;
  const toArea = target === null ? null : sourceArea(target);
  const toLayer =
    target === null ? null : reactFeatureLayer(desktopModuleRelativePath(target) ?? "");
  const native = specifier.startsWith("@tauri-apps/") || toArea === "api";
  const storage =
    isStorageModuleTarget(target) ||
    (specifier === "" &&
      /\b(?:(?:window|globalThis)\s*\.\s*)?(?:localStorage|sessionStorage)\s*\./.test(
        stripTypeScriptCommentsAndStrings(source),
      ));
  if (fromLayer === "domain") {
    if (
      native ||
      storage ||
      specifier === "react" ||
      specifier.startsWith("react/") ||
      ["application", "ui"].includes(toLayer ?? "")
    )
      return "domain";
  } else if (fromLayer === "application") {
    if (native || storage || toLayer === "ui") return "application";
  } else if (fromLayer === "ui" && (native || storage)) {
    return "ui";
  }
  return null;
}

/** 从镜像相对路径读取显式层，使测试包的分层测试与生产层使用同一依赖约束。 */
function reactFeatureLayer(relativePath) {
  return (
    portablePath(relativePath)
      .split("/")
      .find((part) => reactLayerNames.has(part)) ?? null
  );
}

/**
 * 责任层必须至少包含一个人工 TypeScript 源文件且 feature 需要公开 index.ts；
 * 这同时拒绝空目录占位，也不依赖某个实现文件名猜测迁移是否完成。
 */
function missingReactDddResponsibilities(owner, layers, relativeFiles) {
  const prefix = `features/${owner}/`;
  const missing = [];
  if (!relativeFiles.has(`${prefix}index.ts`)) missing.push("index.ts");
  for (const layer of layers) {
    if (![...relativeFiles].some((file) => file.startsWith(`${prefix}${layer}/`))) {
      missing.push(layer);
    }
  }
  return missing;
}

/** 最深 feature owner 决定测试归属，避免 Workbench 顶层吞掉 Files/Review 等能力子域。 */
function reactDddOwner(relativePath) {
  const normalized = portablePath(relativePath);
  const owner =
    reactDddFeatures
      .map(({ owner }) => owner)
      .sort((left, right) => right.length - left.length)
      .find((candidate) => normalized.startsWith(`features/${candidate}/`)) ?? null;
  if (owner !== "workbench") return owner;
  const workbenchChild = normalized.slice("features/workbench/".length).split("/")[0];
  return ["domain", "application", "ui", "tests", "index.ts"].includes(workbenchChild)
    ? owner
    : null;
}

/** 有状态 feature 的测试必须进入独立测试包中的 domain/application/ui 镜像层。 */
function isReactDddTestPlacement(owner, relativePath) {
  const normalized = portablePath(relativePath);
  return ["domain", "application", "ui"].some((layer) =>
    normalized.startsWith(`features/${owner}/${layer}/`),
  );
}

/** App.tsx 只承担 Provider、路由、响应式 Shell 与 feature composition，不能重新拥有能力生命周期。 */
function containsAppOwnedFeatureResponsibility(source) {
  const nativeImport = moduleSpecifiers(source).some(
    (specifier) =>
      specifier === "@/api/tauri" ||
      specifier.startsWith("@/api/tauri/") ||
      specifier.startsWith("@tauri-apps/"),
  );
  const featureHostDeclaration =
    /\b(?:function|class)\s+(?:WorkbenchHost|FilesWorkspace|TerminalWorkspace|PreviewPanel)\b|\bconst\s+(?:WorkbenchHost|FilesWorkspace|TerminalWorkspace|PreviewPanel)\s*=/.test(
      source,
    );
  const lifecycleOwnership =
    /\buse(?:Files|Terminal|Preview|Workbench)[A-Za-z0-9_]*(?:Controller|Lifecycle)\s*\(/.test(
      source,
    );
  const adapterFactory =
    /\b(?:function\s+|const\s+)(?:create|build|make)[A-Za-z0-9_]*(?:Adapter|Operations|Port)\b/.test(
      source,
    );
  return nativeImport || featureHostDeclaration || lifecycleOwnership || adapterFactory;
}

/** Composer 必须由会话层受控，禁止 optional text、默认空值或复制第二份草稿状态。 */
function containsComposerTextCompatibility(source) {
  return (
    /\btext\s*\?\s*:\s*string\b/.test(source) ||
    /(?:^|[,\n]\s*)text\s*=\s*["']["']/.test(source) ||
    /\bconst\s*\[\s*(?:localText|localDraft|draft|composerText|inputText)\s*,[^\]]+\]\s*=\s*useState(?:<[^>]*>)?\s*\(/.test(
      source,
    ) ||
    /useState(?:<[^>]*>)?\s*\(\s*(?:\(\s*\)\s*=>\s*)?text\s*(?:\?\?\s*["']["'])?\s*\)/.test(source)
  );
}

/** Terminal 新建 Tab 只接受结构化 options，禁止恢复字符串 profile/shell 的重载与调用。 */
function containsTerminalAddTabStringOverload(source) {
  return (
    /\baddTab\s*:\s*\(\s*(?:profile|shell|command)\s*\?\s*:\s*(?:string|TerminalProfile)\b/.test(
      source,
    ) ||
    /\b(?:function\s+addTab\s*\(|const\s+addTab\s*=\s*(?:useCallback\s*\()?\s*\()\s*(?:profile|shell|command)\s*\?\s*:\s*(?:string|TerminalProfile)\b/.test(
      source,
    ) ||
    /(?:\.|\b)addTab\s*\(\s*["']/.test(source) ||
    /\baddTerminalTab\s*\([^,\r\n]+,\s*["']/.test(source)
  );
}

/** 持久布局只保存 dormant shape；旧 runtime-bearing schema 不得通过迁移类型或转换函数复活。 */
function containsLegacyRuntimeTerminalLayoutCompatibility(source) {
  const legacyTypeOrMigration =
    /\b(?:interface|type)\s+(?:LegacyTerminalLayout[A-Za-z0-9_]*|TerminalLayoutV0)\b|\b(?:migrate|upgrade|convertLegacy)[A-Za-z0-9_]*TerminalLayout\b/i.test(
      source,
    );
  const runtimeBearingLayoutShape =
    /\b(?:interface|type)\s+(?:TerminalPaneLayout|TerminalTabLayout|TerminalLayoutV[0-9]+)\b[^{=]*(?:\{|=\s*\{)[^}]*\b(?:sessionId|generation|pid|processId|output|scrollback|environment|runtime)\s*\??\s*:/m.test(
      source,
    );
  return legacyTypeOrMigration || runtimeBearingLayoutShape;
}

/** 只解析 Ja 本地 import；依赖所有权由规范化路径前缀决定，不需要探测扩展名。 */
function resolveLocalImport(sourceFile, specifier) {
  if (specifier === "@") {
    return desktopSourceRoot;
  }
  if (specifier.startsWith("@/")) {
    return path.resolve(desktopSourceRoot, specifier.slice(2));
  }
  if (specifier.startsWith(".")) {
    return path.resolve(path.dirname(sourceFile), specifier);
  }
  return null;
}

/** 将生产代码或测试映射到第一层 owner，使镜像测试继续接受同一依赖方向约束。 */
function sourceArea(filePath) {
  return desktopModuleRelativePath(filePath)?.split("/")[0] ?? "outside";
}

/** 先验证部署单元路径存在，避免缺目录时产生一串误导性的后续错误。 */
async function requirePath(relativePath) {
  const absolutePath = path.join(repositoryRoot, relativePath);
  try {
    await access(absolutePath, fsConstants.F_OK);
  } catch {
    throw new Error(`缺少生产模块路径: ${relativePath}`);
  }
}

/** 保持桌面依赖无环：shared 是基础层，API 不依赖 UI，feature 不反向依赖 composition。 */
async function checkDesktopDependencies() {
  const violations = [];
  const sourceFiles = [
    ...(await collectSourceFiles(desktopSourceRoot)),
    ...(await collectSourceFiles(desktopTestRoot)),
  ];
  for (const sourceFile of sourceFiles) {
    const source = await readFile(sourceFile, "utf8");
    const fromArea = sourceArea(sourceFile);
    if (
      moduleSpecifiers(source).some((specifier) => specifier.startsWith("@tauri-apps/")) &&
      fromArea !== "api"
    ) {
      violations.push(
        `${path.relative(repositoryRoot, sourceFile)}: raw Tauri import 只能位于 api`,
      );
    }
    const relative = desktopModuleRelativePath(sourceFile) ?? "";
    const layer = reactFeatureLayer(relative);
    if (layer !== null && reactLayerDependencyViolation(sourceFile, source, "", null) !== null) {
      violations.push(
        `${path.relative(repositoryRoot, sourceFile)}: React ${layer} 不得直接访问浏览器 storage`,
      );
    }
    for (const specifier of moduleSpecifiers(source)) {
      const target = resolveLocalImport(sourceFile, specifier);
      const layerViolation = reactLayerDependencyViolation(sourceFile, source, specifier, target);
      if (layerViolation !== null) {
        violations.push(
          `${path.relative(repositoryRoot, sourceFile)} -> ${specifier}: React ${layerViolation} 依赖越过 domain/application/ui 责任方向`,
        );
      }
      if (!target) {
        continue;
      }
      const toArea = sourceArea(target);
      if (fromArea === "shared" && ["api", "features", "app"].includes(toArea)) {
        violations.push(
          `${path.relative(repositoryRoot, sourceFile)} -> ${specifier}: shared 不能依赖 ${toArea}`,
        );
      }
      if (fromArea === "api" && ["features", "app"].includes(toArea)) {
        violations.push(
          `${path.relative(repositoryRoot, sourceFile)} -> ${specifier}: api 不能依赖 ${toArea}`,
        );
      }
      if (fromArea === "features" && toArea === "app") {
        violations.push(
          `${path.relative(repositoryRoot, sourceFile)} -> ${specifier}: feature 不能依赖 app`,
        );
      }
      if (["app", "features"].includes(fromArea) && toArea === "features") {
        const fromFeature = featureIdentity(sourceFile);
        const toFeature = featureIdentity(target);
        if (
          toFeature !== null &&
          !isPublicFeatureImport(fromArea, fromFeature, toFeature, specifier, target)
        ) {
          const boundary = fromArea === "app" ? "app 引用 feature" : "跨 feature";
          violations.push(
            `${path.relative(repositoryRoot, sourceFile)} -> ${specifier}: ${boundary} 必须经过公开 entrypoint`,
          );
        }
      }
    }
  }
  return violations;
}

/**
 * 有状态 feature 必须以 domain/application/ui 表达责任；Workbench 顶层是受控 composition shell，
 * 只豁免没有独立用例的 application，但其能力子域仍必须完整分层。
 */
async function checkReactDddResponsibilities() {
  const violations = [];
  const relativeFiles = new Set(
    (await collectSourceFiles(desktopSourceRoot)).map((file) =>
      portablePath(path.relative(desktopSourceRoot, file)),
    ),
  );
  for (const { owner, layers } of reactDddFeatures) {
    const missing = missingReactDddResponsibilities(owner, layers, relativeFiles);
    if (missing.length > 0) {
      violations.push(
        `apps/desktop/src/features/${owner}: 有状态 feature 缺少明确责任层 ${missing.join(", ")}`,
      );
    }
    const nestedOwners = reactDddFeatures
      .map((item) => item.owner)
      .filter((candidate) => candidate.startsWith(`${owner}/`));
    const prefix = `features/${owner}/`;
    for (const file of relativeFiles) {
      if (!file.startsWith(prefix)) continue;
      const ownerRelative = file.slice(prefix.length);
      if (
        nestedOwners.some((nested) =>
          ownerRelative.startsWith(`${nested.slice(owner.length + 1)}/`),
        )
      )
        continue;
      if (
        ownerRelative === "index.ts" ||
        layers.some((layer) => ownerRelative.startsWith(`${layer}/`))
      )
        continue;
      violations.push(
        `apps/desktop/src/${file}: 有状态 feature 生产实现不得散落在 domain/application/ui 外`,
      );
    }
  }
  return violations;
}

/** 递归收集目录用于检查空测试目录；目录本身也是物理责任，不能靠“当前没有文件”绕过门禁。 */
async function collectDirectories(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const directories = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const entryPath = path.join(directory, entry.name);
    directories.push(entryPath, ...(await collectDirectories(entryPath)));
  }
  return directories;
}

/** 测试文件只能位于 desktop/tests，生产 src 及桌面包其它位置都不得重新承载测试体。 */
function isReactTestPathAllowed(filePath) {
  const relative = path.relative(desktopTestRoot, filePath);
  return !relative.startsWith("..") && !path.isAbsolute(relative);
}

/** Spike UI 与正式桌面使用同一物理分离原则，但 Playwright e2e 保留独立 e2e 包。 */
/** 测试框架 import 是测试体的权威证据，防止改名后把 test 留在生产 src。 */
function importsTestFramework(source) {
  return moduleSpecifiers(source).some(
    (specifier) =>
      specifier === "vitest" ||
      specifier === "jest" ||
      specifier.startsWith("@jest/") ||
      specifier === "@playwright/test",
  );
}

/**
 * 同时出现 runner case 与 assertion/lifecycle 才判定为内联测试体，避免把业务代码中的
 * RegExp.test 或普通 expect 字段误认成测试。
 */
function containsReactTestBody(source) {
  const code = stripTypeScriptCommentsAndStrings(source);
  const caseDeclaration = /(?<!\.)\b(?:describe|it|test)\s*\(/.test(code);
  const assertionOrFixture =
    /(?<!\.)\bexpect\s*\(|\b(?:beforeEach|afterEach|beforeAll|afterAll)\s*\(|\bvi\s*\.\s*(?:mock|fn|spyOn|useFakeTimers)\s*\(/.test(
      code,
    );
  return caseDeclaration && assertionOrFixture;
}

/**
 * 生产 React 源码不得携带 E2E entry、探针全局或测试环境变量；依赖注入端口可以保留，
 * 但测试实现必须由 tests 包和测试构建配置组合，不能借 DEV 分支进入正常应用。
 */
function containsReactProductionTestSeam(relativePath, source) {
  const normalized = portablePath(relativePath);
  const hasTestOwnedPath = normalized
    .split("/")
    .some((segment) => /^(?:e2e|test(?:[-_]?support)?)(?:[._-]|[A-Z]|$)/u.test(segment));
  const code = stripTypeScriptCommentsAndStrings(source);
  return (
    hasTestOwnedPath ||
    /\bVITE_JA_E2E_[A-Z0-9_]*\b/u.test(code) ||
    /\b__JA_E2E_[A-Z0-9_]*__\b/u.test(code)
  );
}

/** Runner 配置导入测试框架但不承载测试体，保留在工具根目录更符合工具发现约定。 */
function isTestRunnerConfiguration(filePath) {
  return /^(?:playwright|vitest|jest)\.config\.[cm]?[jt]s$/.test(path.basename(filePath));
}

/** 测试包只镜像现有四个生产责任区，避免另起无法归属的 common 或 legacy 杂物目录。 */
function isReactTestPackageArea(relativePath) {
  return reactTestPackageAreas.has(portablePath(relativePath).split("/")[0]);
}

/** 生产树中的测试、快照和 fixture 目录一律视为责任泄漏，即使目录当前为空也拒绝。 */
function isReactSourceTestDirectory(directory) {
  return reactSourceTestDirectoryNames.has(path.basename(directory));
}

/**
 * React 测试统一进入独立 tests 包并镜像 app/api/features/shared；有状态 feature 继续按
 * domain/application/ui 分层，避免物理抽离后失去 DDD 责任边界。
 */
async function checkReactDddTestOrganization() {
  const violations = [];
  for (const file of await collectSourceFiles(desktopSourceRoot)) {
    const source = await readFile(file, "utf8");
    const relative = path.relative(desktopSourceRoot, file);
    if (containsReactProductionTestSeam(relative, source)) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: E2E entry、探针与测试环境实现必须位于 apps/desktop/tests，生产 src 只保留可注入端口`,
      );
    }
  }
  for (const file of await collectSourceFiles(desktopRoot)) {
    const source = await readFile(file, "utf8");
    if (
      (!isTypeScriptTestFile(file) &&
        !importsTestFramework(source) &&
        !containsReactTestBody(source)) ||
      isTestRunnerConfiguration(file)
    )
      continue;
    if (!isReactTestPathAllowed(file)) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: React 测试只能位于 apps/desktop/tests，生产 src 必须保持纯净`,
      );
    }
  }
  const forbiddenSourceDirectories = (await collectDirectories(desktopSourceRoot)).filter(
    (directory) => isReactSourceTestDirectory(directory),
  );
  for (const directory of forbiddenSourceDirectories) {
    violations.push(
      `${path.relative(repositoryRoot, directory)}: 生产 src 不得包含测试或测试资源目录`,
    );
  }
  for (const file of await collectSourceFiles(desktopTestRoot)) {
    if (!isTypeScriptTestFile(file)) continue;
    const relative = portablePath(path.relative(desktopTestRoot, file));
    if (!isReactTestPackageArea(relative)) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: React 测试包必须镜像 app/api/features/shared 责任目录`,
      );
      continue;
    }
    const owner = reactDddOwner(relative);
    if (owner !== null && !isReactDddTestPlacement(owner, relative)) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: 有状态 feature 测试必须位于独立测试包的 domain、application 或 ui 层`,
      );
    }
  }
  return violations;
}

/** 单独约束 App.tsx，避免已经抽离的 lifecycle controller 或 native adapter 工厂重新回到应用入口。 */
async function checkAppCompositionResponsibility() {
  const appEntry = path.join(desktopSourceRoot, "app", "App.tsx");
  const source = await readFile(appEntry, "utf8");
  return containsAppOwnedFeatureResponsibility(source)
    ? [
        "apps/desktop/src/app/App.tsx: 只能保留 Provider、路由、响应式 Shell 与 feature composition，不能拥有 Workbench/Files/Terminal/Preview lifecycle 或 native adapter factory",
      ]
    : [];
}

/**
 * 只有显式 adapter 组合入口可以创建具体 adapter；defaultAdapters 组合普通能力，
 * runtimeHostAdapter 负责把 Runtime typed adapter 投影成 application port。
 */
function isAppNativeAdapterCompositionRoot(relativePath) {
  return appNativeAdapterCompositionRoots.has(portablePath(relativePath));
}

/**
 * composition 可以注入 api/tauri 暴露的 typed adapter、port type 与 observer，但不能理解
 * wire Schema/DTO；协议形状必须在 api 边界完成校验和投影。
 */
function isAppCompositionWireDependency(source, specifier, target) {
  if (target === null || sourceArea(target) !== "api") return false;
  const targetRelative = portablePath(path.relative(desktopSourceRoot, target));
  if (targetRelative === "api/protocol" || targetRelative.startsWith("api/protocol/")) return true;
  const escapedSpecifier = specifier.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const importPattern = new RegExp(
    `\\bimport\\s+(?:type\\s+)?([\\s\\S]*?)\\s+from\\s*["']${escapedSpecifier}["']`,
    "g",
  );
  return [...source.matchAll(importPattern)].some((match) =>
    /\b[A-Za-z_$][A-Za-z0-9_$]*(?:Dto|Schema|Wire)\b/.test(match[1] ?? ""),
  );
}

/** application 只依赖 ports；composition 负责 typed adapter 注入，但仍不得读取 wire 合同或 raw Tauri。 */
async function checkAppCompositionDependencies() {
  const violations = [];
  const appRoot = path.join(desktopSourceRoot, "app");
  for (const file of await collectSourceFiles(appRoot)) {
    const relative = portablePath(path.relative(appRoot, file));
    const compositionTarget =
      relative.startsWith("composition/") || path.basename(file) === "useJaWorkbench.ts";
    const applicationTarget = relative.startsWith("application/");
    const adapterCompositionRoot = isAppNativeAdapterCompositionRoot(relative);
    if (!compositionTarget && !applicationTarget) continue;
    const source = await readFile(file, "utf8");
    for (const specifier of moduleSpecifiers(source)) {
      const target = resolveLocalImport(file, specifier);
      const rawTauri = specifier.startsWith("@tauri-apps/");
      const apiDependency = target !== null && sourceArea(target) === "api";
      if (applicationTarget && (rawTauri || apiDependency)) {
        violations.push(
          `${path.relative(repositoryRoot, file)} -> ${specifier}: app application 不得直接依赖 native API/DTO`,
        );
      } else if (compositionTarget && rawTauri) {
        violations.push(
          `${path.relative(repositoryRoot, file)} -> ${specifier}: app composition 必须注入 typed adapter，不能直接依赖 raw Tauri`,
        );
      } else if (
        compositionTarget &&
        !adapterCompositionRoot &&
        isAppCompositionWireDependency(source, specifier, target)
      ) {
        violations.push(
          `${path.relative(repositoryRoot, file)} -> ${specifier}: app composition 不得理解 wire Schema/DTO`,
        );
      }
    }
    if (
      compositionTarget &&
      !adapterCompositionRoot &&
      /\b(?:function\s+|const\s+)(?:create|build|make)[A-Za-z0-9_]*(?:Adapter|Operations|Port)\b/.test(
        stripTypeScriptCommentsAndStrings(source),
      )
    ) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: app composition 不得创建 native adapter`,
      );
    }
  }
  return violations;
}

/** ui 不能导出 concrete Port/Adapter；这些实现必须由 app composition 创建并注入。 */
function containsReactUiAdapterImplementation(source) {
  const code = stripTypeScriptCommentsAndStrings(source);
  return /\bexport\s+(?:const|function|class)\s+[A-Za-z_$][A-Za-z0-9_$]*(?:Port|Ports|Adapter|Adapters|Operations)\b/.test(
    code,
  );
}

/** ui 只渲染 view model/actions，不能在视图内部创建 controller、native 订阅或 concrete adapter。 */
async function checkReactUiOwnership() {
  const violations = [];
  for (const file of await collectSourceFiles(path.join(desktopSourceRoot, "features"))) {
    const relative = desktopModuleRelativePath(file) ?? "";
    if (reactFeatureLayer(relative) !== "ui") continue;
    const source = await readFile(file, "utf8");
    const code = stripTypeScriptCommentsAndStrings(source);
    if (
      /\b(?:use|create)[A-Za-z0-9_]*Controller\s*\(|\bnative[A-Za-z0-9_]*\.subscribe\s*\(/.test(
        code,
      )
    ) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: feature ui 只能消费 view model/actions，不得创建 controller 或订阅 native port`,
      );
    }
    if (containsReactUiAdapterImplementation(source)) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: feature ui 不得实现 concrete Port/Adapter，必须由 app composition 注入`,
      );
    }
  }
  return violations;
}

/** 主题查询统一复用 hook，feedback 语义组件只允许由 shared primitives 定义。 */
async function checkSharedUiReuse() {
  const violations = [];
  const themeProvider = await readFile(
    path.join(desktopSourceRoot, "app", "ThemeProvider.tsx"),
    "utf8",
  );
  if (!moduleSpecifiers(themeProvider).some((specifier) => specifier.endsWith("/useMediaQuery"))) {
    violations.push(
      "apps/desktop/src/app/ThemeProvider.tsx: ThemeProvider 必须复用 shared useMediaQuery",
    );
  }
  for (const file of await collectSourceFiles(desktopSourceRoot)) {
    const relative = portablePath(path.relative(desktopSourceRoot, file));
    if (relative === "shared/ui/primitives/Feedback.tsx") continue;
    const code = stripTypeScriptCommentsAndStrings(await readFile(file, "utf8"));
    if (/\b(?:function|class|const)\s+(?:LoadingState|EmptyState|ErrorState)\b/.test(code)) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: Loading/Empty/Error feedback 必须复用 shared/ui/primitives`,
      );
    }
  }
  return violations;
}

/** 返回单个 owner 的物理责任问题，生产树与真实 fixture 树复用同一检查器。 */
async function rustDddResponsibilityProblems(ownerRoot) {
  const problems = [];
  const entries = await readdir(ownerRoot, { withFileTypes: true });
  for (const layer of rustDddLayers) {
    const layerRoot = path.join(ownerRoot, layer);
    try {
      const layerFiles = await collectRustAndTomlFiles(layerRoot);
      if (!layerFiles.some((file) => path.extname(file) === ".rs")) problems.push(`${layer}:empty`);
    } catch {
      problems.push(`${layer}:missing`);
    }
  }
  for (const entry of entries) {
    if (entry.isDirectory() && !rustDddLayers.has(entry.name))
      problems.push(`${entry.name}:directory`);
    if (
      entry.isFile() &&
      path.extname(entry.name) === ".rs" &&
      !["mod.rs", "facade.rs", "composition.rs"].includes(entry.name)
    )
      problems.push(`${entry.name}:root-file`);
  }
  return problems;
}

/** owner 根只承担 façade/composition，四个非空目录才是真实责任层。 */
async function checkRustDddResponsibilities() {
  const violations = [];
  for (const owner of rustDddOwners) {
    const ownerRoot = path.join(tauriSourceRoot, owner);
    for (const problem of await rustDddResponsibilityProblems(ownerRoot))
      violations.push(`src-tauri/src/${owner}: Rust DDD 责任结构违规（${problem}）`);
  }
  return violations;
}

/** 从 owner façade 导入外层类型仍是反向依赖，不能因 re-export 改变定义来源事实。 */
function rootFacadeDependency(owner, code, outerLayerSymbols) {
  return [...outerLayerSymbols].some((symbol) =>
    new RegExp(`\\b(?:crate::${owner}|super(?::super)+)::[^;]*\\b${symbol}\\b`).test(code),
  );
}

/**
 * domain 不承担 wire 或 persistence 形状，因此 serde import、序列化 derive 与字段属性
 * 都属于外层责任；使用 token 组合检查，不能通过 grouped import 或 fully-qualified derive 绕过。
 */
function containsRustDomainSerdeCoupling(source) {
  const code = normalizedRustCode(source);
  return (
    /\buse\s+serde(?:::|\s*(?:;|as\b))|\bserde::[A-Za-z_]/.test(code) ||
    /#\s*\[\s*derive\s*\([^\]]*\b(?:Serialize|Deserialize)\b[^\]]*\)\s*\]/.test(code) ||
    /#\s*\[\s*serde(?:\s*\(|\s*\])/.test(code)
  );
}

/** Rust DDD 层按规范化 token 与真实类型来源检查，禁止源码拼接、动态 JSON 和依赖方向绕行。 */
function rustDddDependencyViolation(
  relativePath,
  source,
  interfaceSymbols = new Set(),
  byteContractSymbols = new Set(),
  applicationSymbols = new Set(),
) {
  const parts = portablePath(relativePath).split("/");
  const owner = parts[0];
  const layer = parts[1];
  if (!rustDddOwners.has(owner) || !rustDddLayers.has(layer)) return null;
  const code = normalizedRustCode(source);
  if (/\binclude(?:_str)?\s*!\s*\(/.test(code)) return "source-include";
  if (!["domain", "application"].includes(layer)) return null;
  const nativeCapability =
    /\b(?:std|tokio|async_std)::(?:[^;]*,)?(?:fs|process)\b|\btauri(?:::|\s+as\b)/.test(code);
  const dynamicJson = /\bserde_json::[^;]*\bValue\b/.test(code);
  if (nativeCapability || dynamicJson) return "native";
  if (layer === "domain" && containsRustDomainSerdeCoupling(source)) return "serde-coupling";
  if (
    layer === "domain" &&
    /\b(?:crate|self|super)::[^;]*(?:::|,)(?:application|infrastructure|interface)(?:::|,|;)/.test(
      code,
    )
  ) {
    return "direction";
  }
  if (layer === "domain" && rootFacadeDependency(owner, code, applicationSymbols))
    return "application-facade";
  if (layer === "application") {
    if (containsGenericStructuredValueTree(source)) return "generic-structured-value";
    if (owner === "app_runtime" && containsGenericByteTunnel(source, byteContractSymbols))
      return "generic-byte-tunnel";
    if (/\bserde_json(?:::|\s*!)/.test(code)) return "dynamic-json";
    const ownerValueAlias = new RegExp(
      `\\b(?:crate::${owner}|super(?::super)+)::[^;]*(?:ProtocolValue|Value)\\b`,
    );
    if (ownerValueAlias.test(code)) return "root-value-alias";
    if (rootFacadeDependency(owner, code, interfaceSymbols)) return "interface-dto";
    if (/\b(?:crate|self|super)::[^;]*(?:::|,)(?:infrastructure|interface)(?:::|,|;)/.test(code))
      return "direction";
    for (const otherOwner of rustDddOwners) {
      if (
        otherOwner !== owner &&
        new RegExp(`\\bcrate::${otherOwner}::application(?:\\b|::)`).test(code)
      )
        return "cross-owner";
    }
  }
  return null;
}

/** 旧的内存级 smoke fixture 复用真实路径规则；文件树 fixture 会在最终阶段覆盖路径遍历。 */
const rustDomainForbidden = {
  test: (source) => rustDddDependencyViolation("workspace/domain/model.rs", source) !== null,
};
const rustApplicationForbidden = {
  test: (source) => rustDddDependencyViolation("workspace/application/service.rs", source) !== null,
};

/** Rust domain/application 只保留领域值、纯规则和端口编排，原生能力只能由外层 adapter 持有。 */
async function checkRustDddBoundaries() {
  const violations = [];
  const interfaceSymbols = new Map();
  const byteContractSymbols = new Map();
  const applicationSymbols = new Map();
  for (const owner of rustDddOwners) {
    interfaceSymbols.set(
      owner,
      await rustPublicTypeSymbols(path.join(tauriSourceRoot, owner, "interface")),
    );
    byteContractSymbols.set(
      owner,
      await rustApplicationByteContractSymbols(path.join(tauriSourceRoot, owner, "application")),
    );
    applicationSymbols.set(
      owner,
      await rustPublicTypeSymbols(path.join(tauriSourceRoot, owner, "application")),
    );
  }
  const files = await collectRustAndTomlFiles(tauriSourceRoot);
  for (const file of files.filter((candidate) => path.extname(candidate) === ".rs")) {
    const relative = path.relative(tauriSourceRoot, file);
    const source = await readFile(file, "utf8");
    const owner = portablePath(relative).split("/")[0];
    const violation = rustDddDependencyViolation(
      relative,
      source,
      interfaceSymbols.get(owner),
      byteContractSymbols.get(owner),
      applicationSymbols.get(owner),
    );
    if (violation !== null)
      violations.push(
        `${path.relative(repositoryRoot, file)}: Rust ${portablePath(relative).split("/")[1]} 依赖越过 DDD 责任方向（${violation}）`,
      );
  }
  return violations;
}

/**
 * 对 DDD owner 只认精确 interface/composition，对其它模块只认 commands.rs、interface 或 lib root；
 * raw runtime handle 与 async runtime 同样属于 Tauri 能力，不能藏进 load/watch 等实现文件。
 */
async function checkTauriPlacement() {
  const violations = [];
  const files = await collectRustAndTomlFiles(tauriSourceRoot);
  for (const file of files.filter((candidate) => path.extname(candidate) === ".rs")) {
    const relative = path.relative(tauriSourceRoot, file);
    const source = await readFile(file, "utf8");
    if (violatesTauriPlacement(relative, source)) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: Tauri API 只能位于 interface 或 composition`,
      );
    }
  }
  return violations;
}

/** 收集每个 workspace crate 的 src，避免新增 crate 后自动掉出测试组织门禁。 */
async function collectCrateRustSourceRoots() {
  const entries = await readdir(cratesRoot, { withFileTypes: true });
  const roots = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const sourceRoot = path.join(cratesRoot, entry.name, "src");
    try {
      await access(sourceRoot, fsConstants.F_OK);
      roots.push({ crateRoot: path.join(cratesRoot, entry.name), sourceRoot });
    } catch {
      // 没有 src 的工具目录不是 Rust crate，不纳入人工源码门禁。
    }
  }
  return roots;
}

/**
 * Rust 测试只能位于所属 crate 的 tests/unit 或 tests/integration，支撑实现位于 tests/support；
 * unit 禁止真实 IO，生产 src 不保留测试装配、feature 或 Harness，所有测试都从 Cargo
 * [[test]] 根形成可追溯闭包。
 */
async function checkRustTestOrganization() {
  const violations = [];
  const roots = [
    { crateRoot: path.join(repositoryRoot, "src-tauri"), sourceRoot: tauriSourceRoot },
    ...(await collectCrateRustSourceRoots()),
  ];
  for (const { crateRoot, sourceRoot } of roots) {
    const files = await collectRustAndTomlFiles(sourceRoot);
    const sourceRustFiles = files.filter((candidate) => path.extname(candidate) === ".rs");
    for (const file of sourceRustFiles) {
      if (isDedicatedRustTestFile(file)) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: Rust 测试文件不得位于 src，必须迁移到所属 crate/tests/unit 或 tests/integration`,
        );
        continue;
      }
      const source = await readFile(file, "utf8");
      const testAttributeLines = rustTestFunctionAttributes(source).map((attribute) =>
        sourceLineNumber(source, attribute.index ?? 0),
      );
      if (testAttributeLines.length > 0) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: 生产 Rust 文件不得包含测试函数属性，发现 ${lineSummary(testAttributeLines)}`,
        );
      }

      const conditionalAttributes = [
        ...rustTestCfgAttributes(source),
        ...runtimeTestFeatureAttributes(source),
      ].filter(
        (attribute, index, attributes) =>
          attributes.findIndex((candidate) => candidate.index === attribute.index) === index,
      );
      if (conditionalAttributes.length > 0) {
        const lines = conditionalAttributes.map((attribute) =>
          sourceLineNumber(source, attribute.index ?? 0),
        );
        violations.push(
          `${path.relative(repositoryRoot, file)}: 生产 src 不得包含 test cfg 或 feature 分支，发现 ${lineSummary(lines)}`,
        );
      }

      const harnessLines = rustProductionHarnessDeclarations(source).map((declaration) =>
        sourceLineNumber(source, declaration.index ?? 0),
      );
      if (harnessLines.length > 0) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: 生产 src 不得声明或转发测试 Harness，发现 ${lineSummary(harnessLines)}`,
        );
      }

      const adjacentModuleLines = rustTestModuleDeclarations(source).map((module) =>
        sourceLineNumber(source, module.index ?? 0),
      );
      if (adjacentModuleLines.length > 0) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: 生产 src 不得声明测试 module，发现 ${lineSummary(adjacentModuleLines)}`,
        );
      }
      const unguardedPathLines = rustPathModuleWirings(source)
        .filter(
          (wiring) =>
            rustTestLayer(
              path.resolve(path.dirname(file), wiring.pathLiteral.replace(/[\\/]+/g, path.sep)),
              crateRoot,
            ) !== null,
        )
        .map((wiring) => sourceLineNumber(source, wiring.index));
      if (unguardedPathLines.length > 0) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: 生产 src 不得 path 装配 tests 目录，发现 ${lineSummary(unguardedPathLines)}`,
        );
      }
    }
    const testRoot = path.join(crateRoot, "tests");
    let testRustFiles = [];
    try {
      const testFiles = await collectRustAndTomlFiles(testRoot);
      testRustFiles = testFiles.filter((candidate) => path.extname(candidate) === ".rs");
      for (const file of testRustFiles) {
        const [layer] = path.relative(testRoot, file).split(path.sep);
        if (!["unit", "integration", "support"].includes(layer)) {
          violations.push(
            `${path.relative(repositoryRoot, file)}: Rust tests 必须进入所属 crate/tests/unit、integration 或 support 子目录`,
          );
        }
        if (layer !== "support" && isRustSupportImplementationFile(file)) {
          violations.push(
            `${path.relative(repositoryRoot, file)}: 共享测试支撑实现必须进入所属 crate/tests/support`,
          );
        }
        if (layer === "unit") {
          const unitSource = await readFile(file, "utf8");
          const operations = rustUnitExternalIoOperations(unitSource);
          if (operations.length > 0) {
            const details = operations
              .map(
                (operation) =>
                  `${operation.label}@${sourceLineNumber(unitSource, operation.index)}`,
              )
              .join(", ");
            violations.push(
              `${path.relative(repositoryRoot, file)}: tests/unit 只能验证纯规则、状态机和 fake port；真实 IO 必须迁移到 tests/integration，发现 ${details}`,
            );
          }
        }
        if (layer === "support") {
          const supportSource = await readFile(file, "utf8");
          if (rustTestFunctionAttributes(supportSource).length > 0) {
            violations.push(
              `${path.relative(repositoryRoot, file)}: tests/support 只承载 Harness/fixture，不得混入测试体`,
            );
          }
        }
      }
    } catch {
      // 没有测试的 crate 不强制创建空 tests 目录。
    }
    const manifest = await readFile(path.join(crateRoot, "Cargo.toml"), "utf8");
    const manifestPaths = manifestTestPaths(manifest);
    for (const testPath of manifestPaths) {
      const relativeTestPath = testPath.replace(/^tests[\\/]/, "");
      const [layer] = relativeTestPath.split(/[\\/]/);
      if (!["unit", "integration", "support"].includes(layer)) {
        violations.push(
          `${path.relative(repositoryRoot, path.join(crateRoot, "Cargo.toml"))}: [[test]] path 必须进入 tests/unit、integration 或 support`,
        );
      }
    }
    const directRegisteredTests = new Set(
      manifestPaths.map((testPath) =>
        path.resolve(crateRoot, testPath.replace(/[\\/]+/g, path.sep)),
      ),
    );
    const registeredTests = await registeredRustTestFiles(
      crateRoot,
      directRegisteredTests,
      testRustFiles,
    );
    for (const file of testRustFiles) {
      if (!registeredTests.has(path.resolve(file))) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: Rust 测试文件未进入 Cargo [[test]] target 的 path/include 注册闭包，当前不会被执行`,
        );
      }
    }
  }
  return violations;
}

/** 全 workspace crate 的测试构造差异只能由外置 Harness 提供，生产类型不得携带测试函数或字段。 */
async function checkRuntimeTestConstructors() {
  const violations = [];
  const roots = [
    { crateRoot: path.join(repositoryRoot, "src-tauri"), sourceRoot: tauriSourceRoot },
    ...(await collectCrateRustSourceRoots()),
  ];
  for (const { sourceRoot } of roots) {
    const files = await collectRustAndTomlFiles(sourceRoot);
    for (const file of files.filter((candidate) => path.extname(candidate) === ".rs")) {
      if (isDedicatedRustTestFile(file)) continue;
      const source = await readFile(file, "utf8");
      if (containsRustTestOnlyProductionSymbol(source)) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: 测试专用函数或字段必须移入所属 crate/tests/support Harness`,
        );
      }
    }
  }
  return violations;
}

/** 判断函数声明前是否存在解释设计意图的中文函数级注释。 */
function hasChineseFunctionComment(lines, declarationIndex, marker) {
  if (marker === "///") {
    let cursor = declarationIndex - 1;
    while (
      cursor >= 0 &&
      (lines[cursor].trim() === "" || /^\s*#\s*\[[^\]]+\]\s*$/.test(lines[cursor]))
    )
      cursor -= 1;
    if (cursor < 0 || !/^\s*\/\/\//.test(lines[cursor])) return false;
    const comments = [];
    while (cursor >= 0 && /^\s*\/\/\//.test(lines[cursor])) {
      comments.unshift(lines[cursor]);
      cursor -= 1;
    }
    return /[\u3400-\u9fff]/.test(comments.join("\n"));
  }
  const start = Math.max(0, declarationIndex - 8);
  const prefix = lines.slice(start, declarationIndex).join("\n");
  const markerIndex = prefix.lastIndexOf(marker);
  if (markerIndex < 0) return false;
  const comment = prefix.slice(markerIndex);
  const commentEnd = comment.lastIndexOf("*/") + 2;
  if (commentEnd < 2) return false;
  const trailing = comment.slice(commentEnd);
  if (trailing.replace(/^\s*#\s*\[[^\]]+\]\s*$/gm, "").trim() !== "") return false;
  return /[\u3400-\u9fff]/.test(comment);
}

/** 只识别有实现体的 TypeScript 函数，排除 if/for 等控制流和 interface 方法签名。 */
function isTypeScriptFunctionDeclaration(line) {
  const trimmed = line.trim();
  if (/^(?:if|for|while|switch|catch|with)\s*\(/.test(trimmed) || trimmed.endsWith(";"))
    return false;
  if (/^(?:(?:export|default)\s+)*(?:async\s+)?function\s+[A-Za-z_$][\w$]*/.test(trimmed))
    return true;
  if (
    /^(?:(?:public|private|protected|static|override|abstract|readonly)\s+)*(?:async\s+)?constructor\s*\(/.test(
      trimmed,
    )
  )
    return true;
  if (
    /^(?:(?:public|private|protected|static|override|readonly)\s+)*(?:async\s+)?[A-Za-z_$][\w$]*\s*(?:<[^>]+>)?\s*\([^;]*\)\s*(?::[^=]+)?\s*\{/.test(
      trimmed,
    )
  )
    return true;
  return (
    /^(?:export\s+)?const\s+[A-Za-z_$][\w$]*(?:\s*:[^=]+)?\s*=\s*(?:async\s+)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>/.test(
      trimmed,
    ) ||
    /^(?:export\s+)?const\s+[A-Za-z_$][\w$]*(?:\s*:[^=]+)?\s*=\s*(?:async\s+)?\(\s*$/.test(trimmed)
  );
}

/** 提取 TypeScript 实现名，让门禁围绕责任语义而不是文件行数工作。 */
function typeScriptFunctionName(line) {
  const trimmed = line.trim();
  const functionMatch = trimmed.match(
    /^(?:(?:export|default)\s+)*(?:async\s+)?function\s+([A-Za-z_$][\w$]*)/,
  );
  if (functionMatch) return functionMatch[1];
  if (/constructor\s*\(/.test(trimmed)) return "constructor";
  const methodMatch = trimmed.match(
    /^(?:(?:public|private|protected|static|override|readonly)\s+)*(?:async\s+)?([A-Za-z_$][\w$]*)\s*(?:<[^>]+>)?\s*\(/,
  );
  if (methodMatch) return methodMatch[1];
  return trimmed.match(/^(?:export\s+)?const\s+([A-Za-z_$][\w$]*)/)?.[1] ?? "";
}

/**
 * React 门禁覆盖 export、async、构造与关键 controller/service 责任；
 * 私有短小 formatter/getter 由命名和类型表达，不强迫写复述式注释。
 */
function typeScriptFunctionRequiresChineseComment(line) {
  if (!isTypeScriptFunctionDeclaration(line)) return false;
  const trimmed = line.trim();
  const name = typeScriptFunctionName(line);
  const pureMapping =
    /^(?:get|is|has|to|from|as|map)[A-Z_]/.test(name) &&
    !criticalResponsibilityName.test(name) &&
    !/\basync\b/.test(trimmed);
  if (pureMapping) return false;
  return (
    /^(?:export\b|public\b)/.test(trimmed) ||
    /\basync\b/.test(trimmed) ||
    name === "constructor" ||
    criticalResponsibilityName.test(name)
  );
}

/** Rust 声明必须拥有实现体；trait 的 `fn ...;` 由实现者而不是端口声明承担逻辑注释。 */
function isRustFunctionDeclaration(line) {
  const trimmed = line.trim();
  return (
    !trimmed.endsWith(";") &&
    /^(?:pub(?:\([^)]*\))?\s+)?(?:(?:async|const|unsafe)\s+)*(?:extern\s+"[^"]+"\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*/.test(
      trimmed,
    )
  );
}

/** 读取 Rust 声明名，用统一关键责任词覆盖 free function 与 impl method。 */
function rustFunctionName(line) {
  return line.match(/\bfn\s+([A-Za-z_][A-Za-z0-9_]*)/)?.[1] ?? "";
}

/** 多行 trait/extern 声明以分号结束且没有实现体，注释责任属于实现或端口类型本身。 */
function rustDeclarationHasImplementation(lines, declarationIndex) {
  const header = lines.slice(declarationIndex, declarationIndex + 20).join("\n");
  const bodyStart = header.indexOf("{");
  const declarationEnd = header.indexOf(";");
  return bodyStart >= 0 && (declarationEnd < 0 || bodyStart < declarationEnd);
}

/**
 * 只豁免可从一屏内确认的字段 getter 或按命名声明的纯映射；包含分支、await、错误传播
 * 或关键状态词的方法仍必须解释不变量，避免用 `get_` 名称绕开并发与事务说明。
 */
function isSimpleRustGetterOrMapping(lines, declarationIndex) {
  const declaration = lines[declarationIndex].trim();
  const name = rustFunctionName(declaration);
  if (/\basync\b/.test(declaration)) return false;
  const preview = lines.slice(declarationIndex, declarationIndex + 20).join("\n");
  const bodyStart = preview.indexOf("{");
  const bodyEnd = preview.indexOf("}", bodyStart + 1);
  if (bodyStart < 0 || bodyEnd < 0) return false;
  const body = preview.slice(bodyStart + 1, bodyEnd).trim();
  const flattened = body.replace(/\s+/g, " ");
  const hasControlFlow = /\b(?:if|match|for|while|loop)\b|\.await\b|\?/.test(body);
  if (body.length <= 600 && !hasControlFlow && /^Self\s*\{/.test(flattened)) return true;
  if (/^(?:from|to|as|into|map)_/.test(name) && body.length <= 600 && !hasControlFlow) {
    return (
      /^(?:Self|[A-Za-z_][A-Za-z0-9_:<>]*)\s*\{/.test(flattened) ||
      /^(?:&\s*)?self\.[A-Za-z_][A-Za-z0-9_]*(?:\.clone\(\))?$/.test(flattened)
    );
  }
  if (/^default_/.test(name) && body.length <= 160 && !hasControlFlow) {
    return /^(?:true|false|None|Some\([^;]+\)|\d+)$/.test(flattened);
  }
  if (
    criticalResponsibilityName.test(name) ||
    !/^(?:get_|is_|has_|default_|len$|[A-Za-z0-9_]+_id$|status$|generation$)/.test(name)
  )
    return false;
  return (
    body.length <= 160 &&
    !hasControlFlow &&
    /^(?:&\s*)?self\.[A-Za-z_][A-Za-z0-9_]*(?:\.clone\(\))?$|^(?:true|false|None|Some\([^;]+\)|\d+)$/.test(
      flattened,
    )
  );
}

/** Rust 只要求 public/pub(crate)、async、command 和关键状态责任函数写中文设计注释。 */
function rustFunctionRequiresChineseComment(lines, declarationIndex) {
  const line = lines[declarationIndex];
  if (
    !isRustFunctionDeclaration(line) ||
    !rustDeclarationHasImplementation(lines, declarationIndex) ||
    isSimpleRustGetterOrMapping(lines, declarationIndex)
  )
    return false;
  const name = rustFunctionName(line);
  if (/^[A-Z]/.test(name)) return false;
  const prefix = lines.slice(Math.max(0, declarationIndex - 4), declarationIndex).join("\n");
  return (
    /^\s*pub(?:\([^)]*\))?\s+/.test(line) ||
    /\basync\s+fn\b/.test(line) ||
    /#\s*\[\s*tauri::command\s*\]/.test(prefix) ||
    criticalResponsibilityName.test(name)
  );
}

/** 测试文件用行为名称表达目的，不把生产注释门禁机械复制到测试夹具。 */
function isTypeScriptTestFile(filePath) {
  return /\.(?:tests?|specs?)\.[cm]?[jt]sx?$/.test(filePath);
}

/** 本轮 React 全量重构覆盖 apps/desktop/src，测试体已物理外置且不参与生产注释门禁。 */
function isReactCommentTarget(filePath) {
  const relative = portablePath(path.relative(desktopSourceRoot, filePath));
  return relative !== ".." && !relative.startsWith("../") && !path.isAbsolute(relative);
}

/**
 * Rust 三个 DDD owner、ja-runtime 及本轮迁移测试/责任边界的存量 native 模块均纳入；
 * 独立测试文件不参与生产注释门禁，未重构的基础模块不被机械追加历史注释债务。
 */
function isRustCommentTarget(filePath) {
  if (isDedicatedRustTestFile(filePath)) return false;
  const tauriRelative = path.relative(tauriSourceRoot, filePath);
  if (!tauriRelative.startsWith("..") && !path.isAbsolute(tauriRelative)) {
    const parts = portablePath(tauriRelative).split("/");
    const owner = parts[0];
    return (
      [
        "app_runtime",
        "workspace",
        "review",
        "terminal",
        "preview",
        "settings",
      ].includes(owner) || ["lib.rs", "native_shortcuts.rs"].includes(parts[0])
    );
  }
  const runtimeRelative = path.relative(path.join(runtimeCrateRoot, "src"), filePath);
  if (runtimeRelative.startsWith("..") || path.isAbsolute(runtimeRelative)) return false;
  return true;
}

/** 对本轮 DDD 目录执行中文函数注释门禁，简单端口签名与测试体不制造复述式注释。 */
async function checkChineseFunctionComments() {
  const violations = [];
  const typescriptFiles = (await collectSourceFiles(desktopSourceRoot)).filter(
    (file) => !isTypeScriptTestFile(file) && isReactCommentTarget(file),
  );
  for (const file of typescriptFiles) {
    const lines = (await readFile(file, "utf8")).split(/\r?\n/);
    const missingComments = [];
    lines.forEach((line, index) => {
      const trivialConstructor = /\bconstructor\s*\([^)]*\)\s*\{\s*\}/.test(line);
      if (
        !trivialConstructor &&
        typeScriptFunctionRequiresChineseComment(line) &&
        !hasChineseFunctionComment(lines, index, "/**")
      ) {
        missingComments.push(index + 1);
      }
    });
    if (missingComments.length > 0) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: React 重构函数缺少中文设计注释，发现 ${lineSummary(missingComments)}`,
      );
    }
  }

  const rustRoots = [tauriSourceRoot, path.join(runtimeCrateRoot, "src")];
  for (const root of rustRoots) {
    const files = await collectRustAndTomlFiles(root);
    for (const file of files.filter(
      (candidate) => path.extname(candidate) === ".rs" && isRustCommentTarget(candidate),
    )) {
      const lines = (await readFile(file, "utf8")).split(/\r?\n/);
      const missingComments = [];
      lines.forEach((_line, index) => {
        if (
          rustFunctionRequiresChineseComment(lines, index) &&
          !hasChineseFunctionComment(lines, index, "///")
        ) {
          missingComments.push(index + 1);
        }
      });
      if (missingComments.length > 0) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: Rust 重构函数缺少中文设计注释，发现 ${lineSummary(missingComments)}`,
        );
      }
    }
  }
  return violations;
}

/** 人工维护源码统一标明责任作者；generated 声明是唯一机器生成例外。 */
async function checkAuthorMarkers() {
  const violations = [];
  const roots = [
    desktopSourceRoot,
    desktopTestRoot,
    tauriSourceRoot,
    path.join(repositoryRoot, "src-tauri", "tests"),
    path.join(runtimeCrateRoot, "src"),
    path.join(runtimeCrateRoot, "tests"),
    architectureFixtureRoot,
  ];
  for (const root of roots) {
    const files = await collectMaintainableFiles(root);
    for (const file of files) {
      const source = await readFile(file, "utf8");
      const header = source.split(/\r?\n/).slice(0, 16).join("\n");
      if (/@generated|generated file|machine generated/i.test(header)) continue;
      if (!/@author kongweiguang/.test(header))
        violations.push(
          `${path.relative(repositoryRoot, file)}: 人工维护源码缺少 @author kongweiguang`,
        );
    }
  }
  for (const file of [
    path.join(repositoryRoot, "Cargo.toml"),
    path.join(repositoryRoot, "src-tauri", "Cargo.toml"),
    path.join(runtimeCrateRoot, "Cargo.toml"),
  ]) {
    const header = (await readFile(file, "utf8")).split(/\r?\n/).slice(0, 16).join("\n");
    if (!/@author kongweiguang/.test(header))
      violations.push(
        `${path.relative(repositoryRoot, file)}: Cargo manifest 缺少 @author kongweiguang`,
      );
  }
  return violations;
}

/** 禁止已经完成删除的兼容入口重新进入源码或测试，测试也不能成为旧契约说明书。 */
async function checkRetiredApis() {
  const violations = [];
  const desktopFiles = [
    ...(await collectSourceFiles(desktopSourceRoot)),
    ...(await collectSourceFiles(desktopTestRoot)),
  ];
  for (const file of desktopFiles) {
    const source = await readFile(file, "utf8");
    const retired = retiredApiInSource(source, retiredDesktopApis);
    if (retired !== null)
      violations.push(`${path.relative(repositoryRoot, file)}: 已删除 API ${retired} 不得恢复`);
    if (containsRetiredWorkbenchTabAlias(source)) {
      violations.push(`${path.relative(repositoryRoot, file)}: Workbench 不得恢复旧 Tab 别名`);
    }
  }
  for (const root of [tauriSourceRoot, path.join(runtimeCrateRoot, "src")]) {
    const rustFiles = await collectRustAndTomlFiles(root);
    for (const file of rustFiles.filter((candidate) => path.extname(candidate) === ".rs")) {
      const source = await readFile(file, "utf8");
      const retired = retiredApiInSource(source, retiredRustApis);
      if (retired !== null) {
        violations.push(
          `${path.relative(repositoryRoot, file)}: 已删除 Rust API ${retired} 不得恢复`,
        );
      }
    }
  }
  for (const file of desktopFiles) {
    const source = await readFile(file, "utf8");
    const relative = desktopModuleRelativePath(file) ?? "";
    if (
      file.startsWith(desktopSourceRoot) &&
      relative.startsWith("shared/preferences/") &&
      containsRetiredPreferenceStorage(source)
    ) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: UI preferences 只能读取 v10 key/schema，禁止 v8/v9 或迁移入口`,
      );
    }
    if (
      relative.startsWith("shared/preferences/") &&
      containsRetiredWorkbenchPreferenceMapping(source)
    ) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: UI preferences v10 不得保留旧 Workbench Tab 修复映射`,
      );
    }
    if (relative.startsWith("features/command/") && containsLegacyCommandPaletteMapping(source)) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: Command Palette 不得保留 run/disabled 旧 action 转换`,
      );
    }
    if (
      relative.startsWith("features/conversation/composer/") &&
      containsComposerTextCompatibility(source)
    ) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: Composer text 必须受控且必填，禁止本地草稿兼容状态`,
      );
    }
    if (
      relative.startsWith("features/workbench/terminal/") &&
      containsTerminalAddTabStringOverload(source)
    ) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: Terminal addTab 只能接受结构化 options，禁止字符串重载`,
      );
    }
    if (
      relative === "features/workbench/terminal/domain/terminalLayout.ts" &&
      containsLegacyRuntimeTerminalLayoutCompatibility(source)
    ) {
      violations.push(
        `${path.relative(repositoryRoot, file)}: Terminal 持久布局不得恢复旧 runtime-bearing schema 或迁移兼容`,
      );
    }
  }
  return violations;
}

/** 用最小允许/拒绝样例验证规则本身，防止正则修改后静默失效。 */
function checkRuleFixtures() {
  const fixtureFailures = [];
  if (rustDomainForbidden.test("#[derive(Debug, Clone)] struct Revision(u64);"))
    fixtureFailures.push("Rust domain allow fixture 被误拒绝");
  if (!rustDomainForbidden.test("use std::fs;"))
    fixtureFailures.push("Rust domain reject fixture 未命中");
  if (containsRustDomainSerdeCoupling("#[derive(Debug, Clone)] struct Revision(u64);"))
    fixtureFailures.push("Rust domain plain derive allow fixture 被误拒绝");
  if (!containsRustDomainSerdeCoupling("use serde::{Serialize, Deserialize};"))
    fixtureFailures.push("Rust domain serde import reject fixture 未命中");
  if (!containsRustDomainSerdeCoupling("#[derive(serde::Serialize)] struct Revision(u64);"))
    fixtureFailures.push("Rust domain serde derive reject fixture 未命中");
  if (!containsRustDomainSerdeCoupling('#[serde(rename_all = "camelCase")] struct Status;'))
    fixtureFailures.push("Rust domain serde attribute reject fixture 未命中");
  if (
    rustDddDependencyViolation(
      "workspace/domain/model.rs",
      "use crate::workspace::WorkspaceServiceError;",
      new Set(),
      new Set(),
      new Set(["WorkspaceServiceError"]),
    ) !== "application-facade"
  )
    fixtureFailures.push("Rust domain application façade reject fixture 未命中");
  if (rustApplicationForbidden.test("use crate::workspace::domain::Revision;"))
    fixtureFailures.push("Rust application allow fixture 被误拒绝");
  if (!rustApplicationForbidden.test("use crate::workspace::infrastructure::NativeWriter;"))
    fixtureFailures.push("Rust application reject fixture 未命中");
  if (!rustApplicationForbidden.test('include!("../infrastructure/operations.rs");'))
    fixtureFailures.push("Rust application include! reject fixture 未命中");
  if (
    !rustApplicationForbidden.test("use serde_json::json; fn request() { let value = json!({}); }")
  )
    fixtureFailures.push("Rust application serde_json macro reject fixture 未命中");
  if (!rustApplicationForbidden.test("use crate::workspace::ProtocolValue as Value;"))
    fixtureFailures.push("Rust application 根 Value alias reject fixture 未命中");
  if (
    rustDddDependencyViolation(
      "workspace/application/ports.rs",
      "enum Operation { Start, Stop }",
    ) !== null
  )
    fixtureFailures.push("Rust application typed operation allow fixture 被误拒绝");
  if (
    rustDddDependencyViolation(
      "workspace/application/ports.rs",
      "use crate::workspace::WorkspaceDto;",
      new Set(["WorkspaceDto"]),
    ) !== "interface-dto"
  )
    fixtureFailures.push("Rust application façade interface DTO reject fixture 未命中");
  const nominalBytes =
    "struct ListPayload(Vec<u8>); trait Port { fn list(&self, payload: ListPayload); }";
  if (containsGenericByteTunnel(nominalBytes, new Set(["ListPayload"])))
    fixtureFailures.push("Rust application operation nominal bytes allow fixture 被误拒绝");
  if (
    !containsGenericByteTunnel(
      "struct Payload(Vec<u8>); trait Port { fn request(&self, method: String, payload: Payload); }",
      new Set(["Payload"]),
    )
  )
    fixtureFailures.push("Rust application selector bytes reject fixture 未命中");
  if (
    !containsGenericByteTunnel(
      "struct Payload(Vec<u8>); trait Port { fn one(&self, payload: Payload); fn two(&self, payload: Payload); }",
      new Set(["Payload"]),
    )
  )
    fixtureFailures.push("Rust application shared bytes reject fixture 未命中");
  if (
    rustDddDependencyViolation(
      "workspace/infrastructure/native.rs",
      'include_str!("policy.rs");',
    ) !== "source-include"
  )
    fixtureFailures.push("Rust infrastructure include_str! reject fixture 未命中");
  const reactDomainForbidden =
    /\bfrom\s+["']react["']|@\/api\/|@tauri-apps\/|\blocalStorage\b|\bsessionStorage\b/;
  if (reactDomainForbidden.test("import type { Revision } from './types';"))
    fixtureFailures.push("React domain allow fixture 被误拒绝");
  if (!reactDomainForbidden.test("import { useState } from 'react';"))
    fixtureFailures.push("React domain reject fixture 未命中");
  if (
    featureApplicationForbiddenDependency("import type { WorkspacePort } from './ports';") !== null
  )
    fixtureFailures.push("React application port allow fixture 被误拒绝");
  if (
    featureApplicationForbiddenDependency("import { invoke } from '@/api/tauri/nativeInvoke';") !==
    "@/api/tauri/nativeInvoke"
  )
    fixtureFailures.push("React application Tauri reject fixture 未命中");
  if (
    featureApplicationForbiddenDependency("import { invoke } from '@tauri-apps/api/core';") !==
    "@tauri-apps/api/core"
  )
    fixtureFailures.push("React application raw Tauri reject fixture 未命中");
  if (
    featureApplicationForbiddenDependency("localStorage.setItem('layout', 'x');") !== "localStorage"
  )
    fixtureFailures.push("React application storage reject fixture 未命中");
  if (featureUiForbiddenDependency("import type { ViewModel } from '../application';") !== null)
    fixtureFailures.push("React ui allow fixture 被误拒绝");
  if (
    featureUiForbiddenDependency("import { invoke } from '@/api/tauri/nativeInvoke';") !==
    "@/api/tauri/nativeInvoke"
  )
    fixtureFailures.push("React ui reject fixture 未命中");
  if (
    containsReactUiAdapterImplementation(
      "export function FilesWorkspace({ viewModel, actions }) { return null; }",
    )
  )
    fixtureFailures.push("React ui view allow fixture 被误拒绝");
  if (
    !containsReactUiAdapterImplementation("export const filesBrowserControllerPorts = { timer };")
  )
    fixtureFailures.push("React ui concrete Port reject fixture 未命中");
  if (reactFeatureLayer("features/terminal/ui/TerminalPanel.test.tsx") !== "ui")
    fixtureFailures.push("React tests/ui 分层 fixture 未命中");
  const completeFeatureFixture = new Set([
    "features/workspace/index.ts",
    "features/workspace/domain/model.ts",
    "features/workspace/application/useWorkspace.ts",
    "features/workspace/ui/WorkspaceView.tsx",
  ]);
  if (
    missingReactDddResponsibilities(
      "workspace",
      ["domain", "application", "ui"],
      completeFeatureFixture,
    ).length > 0
  )
    fixtureFailures.push("React DDD 完整责任 allow fixture 被误拒绝");
  const incompleteFeatureFixture = new Set(
    [...completeFeatureFixture].filter((file) => !file.includes("/application/")),
  );
  if (
    !missingReactDddResponsibilities(
      "workspace",
      ["domain", "application", "ui"],
      incompleteFeatureFixture,
    ).includes("application")
  )
    fixtureFailures.push("React DDD 缺 application reject fixture 未命中");
  const workbenchShellFixture = new Set([
    "features/workbench/index.ts",
    "features/workbench/domain/tabs.ts",
    "features/workbench/ui/Workbench.tsx",
  ]);
  if (
    missingReactDddResponsibilities("workbench", ["domain", "ui"], workbenchShellFixture).length > 0
  )
    fixtureFailures.push("Workbench 受控 composition shell allow fixture 被误拒绝");
  if (reactDddOwner("features/workbench/files/domain/filesModel.test.ts") !== "workbench/files")
    fixtureFailures.push("React 子域测试最深 owner fixture 未命中");
  if (reactDddOwner("features/workbench/editor/ui/Editor.test.tsx") !== "workbench/editor")
    fixtureFailures.push("React Editor 子域最深 owner fixture 未命中");
  if (reactDddOwner("features/navigation/ui/AppTitlebar.test.tsx") !== "navigation")
    fixtureFailures.push("React Navigation DDD owner fixture 未命中");
  if (reactDddOwner("features/command/ui/CommandPalette.test.tsx") !== "command")
    fixtureFailures.push("React Command DDD owner fixture 未命中");
  if (isReactDddTestPlacement("command", "features/command/CommandPalette.test.tsx"))
    fixtureFailures.push("React Command 平铺测试 reject fixture 未命中");
  if (reactDddOwner("features/workbench/search/SearchPanel.test.tsx") !== null)
    fixtureFailures.push("Workbench 非独立子域 allow fixture 被误拒绝");
  if (
    !isReactDddTestPlacement(
      "workbench/files",
      "features/workbench/files/application/FilesSaveCoordinator.test.ts",
    )
  )
    fixtureFailures.push("React 独立分层 tests allow fixture 被误拒绝");
  if (
    isReactDddTestPlacement(
      "workbench/files",
      "features/workbench/files/FilesSaveCoordinator.test.ts",
    )
  )
    fixtureFailures.push("React tests 缺显式责任层 reject fixture 未命中");
  if (
    !isReactTestPathAllowed(
      path.join(desktopTestRoot, "features", "workspace", "domain", "workspace.test.ts"),
    )
  )
    fixtureFailures.push("React tests 包 allow fixture 被误拒绝");
  if (
    isReactTestPathAllowed(
      path.join(desktopSourceRoot, "features", "workspace", "domain", "workspace.test.ts"),
    )
  )
    fixtureFailures.push("React src 测试 reject fixture 未命中");
  if (!isReactTestPackageArea("features/workspace/domain/workspace.test.ts"))
    fixtureFailures.push("React tests 镜像责任区 allow fixture 被误拒绝");
  if (isReactTestPackageArea("legacy/workspace.test.ts"))
    fixtureFailures.push("React tests 杂物责任区 reject fixture 未命中");
  if (isReactSourceTestDirectory(path.join(desktopSourceRoot, "features", "workspace", "ui")))
    fixtureFailures.push("React src 生产目录 allow fixture 被误拒绝");
  if (!isReactSourceTestDirectory(path.join(desktopSourceRoot, "features", "workspace", "tests")))
    fixtureFailures.push("React src tests 目录 reject fixture 未命中");
  if (containsReactTestBody("const matched = pattern.test(value);"))
    fixtureFailures.push("React 普通 RegExp.test allow fixture 被误拒绝");
  if (
    !containsReactTestBody(
      "describe('workspace', () => { it('rejects escape', () => expect(false).toBe(true)); });",
    )
  )
    fixtureFailures.push("React 内联测试体 reject fixture 未命中");
  if (containsReactProductionTestSeam("app/main.tsx", "render(<App />);"))
    fixtureFailures.push("React 生产 composition allow fixture 被误拒绝");
  if (
    !containsReactProductionTestSeam(
      "app/e2eProjectPicker.ts",
      "const path = import.meta.env.VITE_JA_E2E_PROJECT_PATH;",
    )
  )
    fixtureFailures.push("React 生产 E2E seam reject fixture 未命中");
  if (
    containsAppOwnedFeatureResponsibility(
      "import { AppProviders } from './AppProviders'; function App() { return <AppProviders />; }",
    )
  )
    fixtureFailures.push("App composition allow fixture 被误拒绝");
  if (!containsAppOwnedFeatureResponsibility("function WorkbenchHost() { return null; }"))
    fixtureFailures.push("App 重定义 WorkbenchHost reject fixture 未命中");
  if (
    !containsAppOwnedFeatureResponsibility(
      "function App() { useTerminalWorkspaceLifecycle(); return null; }",
    )
  )
    fixtureFailures.push("App lifecycle owner reject fixture 未命中");
  if (!containsAppOwnedFeatureResponsibility("const createTerminalAdapter = () => nativeInvoke;"))
    fixtureFailures.push("App native adapter factory reject fixture 未命中");
  if (!isAppNativeAdapterCompositionRoot("composition/defaultAdapters.ts"))
    fixtureFailures.push("App 唯一 adapter composition allow fixture 被误拒绝");
  if (!isAppNativeAdapterCompositionRoot("composition/runtimeHostAdapter.ts"))
    fixtureFailures.push("App Runtime port adapter composition allow fixture 被误拒绝");
  if (isAppNativeAdapterCompositionRoot("composition/JaApplication.tsx"))
    fixtureFailures.push("App 非 adapter composition native reject fixture 未命中");
  const typedCompositionImport =
    "import type { HistoryAdapter } from '@/api/tauri/history'; import { observeWindowCloseRequested } from '@/api/tauri/window';";
  if (
    isAppCompositionWireDependency(
      typedCompositionImport,
      "@/api/tauri/history",
      path.join(desktopSourceRoot, "api", "tauri", "history"),
    )
  )
    fixtureFailures.push("App composition typed adapter allow fixture 被误拒绝");
  const wireCompositionImport = "import type { RuntimeEventDto } from '@/api/tauri/runtime';";
  if (
    !isAppCompositionWireDependency(
      wireCompositionImport,
      "@/api/tauri/runtime",
      path.join(desktopSourceRoot, "api", "tauri", "runtime"),
    )
  )
    fixtureFailures.push("App composition wire DTO reject fixture 未命中");
  if (
    !isAppCompositionWireDependency(
      "import { RpcFrame } from '@/api/protocol/protocol';",
      "@/api/protocol/protocol",
      path.join(desktopSourceRoot, "api", "protocol", "protocol"),
    )
  )
    fixtureFailures.push("App composition protocol reject fixture 未命中");
  if (runtimePrivateModule.test("mod pending; pub use pending::PendingError;"))
    fixtureFailures.push("ja-runtime façade allow fixture 被误拒绝");
  if (!runtimePrivateModule.test("pub mod pending;"))
    fixtureFailures.push("ja-runtime façade reject fixture 未命中");
  if (!isPublicFeatureImport("app", null, "workspace", "@/features/workspace"))
    fixtureFailures.push("app 公开 feature import allow fixture 被误拒绝");
  if (
    isPublicFeatureImport(
      "app",
      null,
      "workspace",
      "@/features/workspace/application/useWorkspaceController",
    )
  )
    fixtureFailures.push("app 深引 feature reject fixture 未命中");
  if (
    !isPublicFeatureImport(
      "app",
      null,
      "workbench/terminal",
      "@/features/workbench/terminal/ui",
      path.join(desktopSourceRoot, "features", "workbench", "terminal", "ui"),
    )
  )
    fixtureFailures.push("app 延迟 UI entrypoint allow fixture 被误拒绝");
  if (
    isPublicFeatureImport(
      "app",
      null,
      "workbench/terminal",
      "@/features/workbench/terminal/application",
      path.join(desktopSourceRoot, "features", "workbench", "terminal", "application"),
    )
  )
    fixtureFailures.push("app application 次级入口 reject fixture 未命中");
  if (
    !isPublicFeatureImport(
      "features",
      "workbench/files",
      "workbench/review",
      "@/features/workbench/review",
    )
  )
    fixtureFailures.push("Workbench 子 feature 公开入口 allow fixture 被误拒绝");
  if (
    isPublicFeatureImport(
      "features",
      "workbench/files",
      "workbench/review",
      "@/features/workbench/review/ui/ReviewPanel",
    )
  )
    fixtureFailures.push("Workbench 子 feature 深引 reject fixture 未命中");
  if (!isPublicFeatureImport("features", "workbench", "workbench/editor", "./editor"))
    fixtureFailures.push("Workbench 聚合入口 allow fixture 被误拒绝");
  if (isPublicFeatureImport("features", "workbench", "workbench/editor", "./editor/CodeEditor"))
    fixtureFailures.push("Workbench 聚合入口深引 reject fixture 未命中");
  if (violatesTauriPlacement("workspace/interface/commands.rs", "#[tauri::command] fn read() {}"))
    fixtureFailures.push("Tauri interface allow fixture 被误拒绝");
  if (!violatesTauriPlacement("workspace/application/service.rs", "use tauri::State;"))
    fixtureFailures.push("Tauri application reject fixture 未命中");
  if (!violatesTauriPlacement("workspace/infrastructure/interface/leak.rs", "use tauri::State;"))
    fixtureFailures.push("Tauri 伪 interface 子目录逃逸 reject fixture 未命中");
  if (!violatesTauriPlacement("workspace/application/service.rs", "use tauri as desktop;"))
    fixtureFailures.push("Tauri alias reject fixture 未命中");
  if (!violatesTauriPlacement("app_runtime/history.rs", "#[tauri::command] fn list() {}"))
    fixtureFailures.push("Runtime 旧平铺 command reject fixture 未命中");
  if (!violatesTauriPlacement("app_runtime/bridge.rs", "use tauri::State;"))
    fixtureFailures.push("Runtime 实现层 Tauri reject fixture 未命中");
  if (violatesTauriPlacement("terminal/commands.rs", "#[tauri::command] fn open() {}"))
    fixtureFailures.push("既有 command interface allow fixture 被误拒绝");
  if (!violatesTauriPlacement("terminal/session.rs", "#[tauri::command] fn open() {}"))
    fixtureFailures.push("存量实现层 command reject fixture 未命中");
  if (!isDedicatedRustTestFile("crate/src/tests/query.rs"))
    fixtureFailures.push("Rust src/tests 识别 fixture 未命中");
  if (!isDedicatedRustTestFile("crate/src/tests.rs"))
    fixtureFailures.push("Rust src/tests.rs 识别 fixture 未命中");
  if (!isDedicatedRustTestFile("crate/src/query_test.rs"))
    fixtureFailures.push("Rust src/*_test.rs 识别 fixture 未命中");
  if (!isDedicatedRustTestFile("crate/src/query_tests.rs"))
    fixtureFailures.push("Rust src/*_tests.rs 识别 fixture 未命中");
  if (!isDedicatedRustTestFile("crate/src/test_support.rs"))
    fixtureFailures.push("Rust src/test_support.rs 识别 fixture 未命中");
  if (!isDedicatedRustTestFile("crate/src/test.rs"))
    fixtureFailures.push("Rust src/test.rs reject fixture 未命中");
  if (!isRustSupportImplementationFile("crate/tests/unit/test_support.rs"))
    fixtureFailures.push("Rust unit support 文件识别 fixture 未命中");
  if (isRustSupportImplementationFile("crate/tests/unit/test_support_tests.rs"))
    fixtureFailures.push("Rust Harness 行为测试被误判为 support 实现");
  if (rustUnitExternalIoOperations("let path = PathBuf::from(input);").length !== 0)
    fixtureFailures.push("Rust unit 纯路径值 allow fixture 被误拒绝");
  if (rustUnitExternalIoOperations('std::fs::write(root.join("a"), b"x");').length !== 1)
    fixtureFailures.push("Rust unit 文件 IO reject fixture 未命中");
  if (rustUnitExternalIoOperations('let child = std::process::Command::new("git");').length !== 1)
    fixtureFailures.push("Rust unit 进程 IO reject fixture 未命中");
  const rustFixtureCrateRoot = path.join(repositoryRoot, "__architecture_fixture__", "src-tauri");
  if (
    rustTestLayer(
      path.join(rustFixtureCrateRoot, "tests", "unit", "query.rs"),
      rustFixtureCrateRoot,
    ) !== "unit"
  )
    fixtureFailures.push("Rust tests/unit 测试体 allow fixture 被误拒绝");
  if (
    rustTestLayer(
      path.join(rustFixtureCrateRoot, "tests", "integration", "query.rs"),
      rustFixtureCrateRoot,
    ) !== "integration"
  )
    fixtureFailures.push("Rust tests/integration 测试体 allow fixture 被误拒绝");
  if (
    rustTestLayer(
      path.join(rustFixtureCrateRoot, "tests", "support", "harness.rs"),
      rustFixtureCrateRoot,
    ) !== "support"
  )
    fixtureFailures.push("Rust tests/support 支撑实现 allow fixture 被误拒绝");
  if (
    rustTestLayer(
      path.join(rustFixtureCrateRoot, "src", "query_tests.rs"),
      rustFixtureCrateRoot,
    ) !== null
  )
    fixtureFailures.push("Rust src 测试体 reject fixture 未命中");
  const rustAdjacentWiringSource = "#[cfg(test)]\nmod query_tests;";
  if (
    rustTestCfgAttributes(rustAdjacentWiringSource).length !== 1 ||
    rustTestModuleDeclarations(rustAdjacentWiringSource).length !== 1
  )
    fixtureFailures.push("Rust src 邻接 module reject fixture 未命中");
  const rustInlineTestModuleSource = "#[cfg(test)] mod tests { #[test] fn rejects_escape() {} }";
  if (
    rustTestCfgAttributes(rustInlineTestModuleSource).length !== 1 ||
    rustTestModuleDeclarations(rustInlineTestModuleSource).length !== 1
  )
    fixtureFailures.push("Rust 内联测试模块 reject fixture 未命中");
  if (rustTestFunctionAttributes("#[tokio::test] async fn races() {}").length === 0)
    fixtureFailures.push("Rust 测试函数属性 reject fixture 未命中");
  const rustScatteredCfgSource = "#[cfg(test)]\nconst CLOCK: u64 = 1;";
  if (rustTestCfgAttributes(rustScatteredCfgSource).length !== 1)
    fixtureFailures.push("Rust 散落 cfg(test) reject fixture 未命中");
  const rustSourcePathWiring = '#[path = "../../tests/unit/query.rs"]\nmod query_tests;';
  if (rustPathModuleWirings(rustSourcePathWiring).length !== 1)
    fixtureFailures.push("Rust src tests path reject fixture 未命中");
  const includeFixture =
    'include!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/unit/query_tests.rs"));\nunit_scope!(query_scope, crate::query, "/tests/unit/query.rs");';
  if (rustManifestTestIncludes(includeFixture, rustFixtureCrateRoot).length !== 2)
    fixtureFailures.push("Rust 独立 unit target include 注册 allow fixture 被误拒绝");
  const integrationIncludeFixture =
    'integration_scope!(git_scope, crate::git, "/tests/integration/git.rs");';
  if (rustManifestTestIncludes(integrationIncludeFixture, rustFixtureCrateRoot).length !== 1)
    fixtureFailures.push("Rust 独立 integration target include 注册 allow fixture 被误拒绝");
  if (rustManifestTestIncludes("include!(dynamic_test_path());", rustFixtureCrateRoot).length !== 0)
    fixtureFailures.push("Rust 动态 include reject fixture 未命中");
  const featureRejectSource = '#[cfg(feature = "test-support")]\nqueue_gate: Option<Gate>,';
  if (runtimeTestFeatureAttributes(featureRejectSource).length !== 1)
    fixtureFailures.push("Rust test-support feature reject fixture 未命中");
  const tauriSmokeFeatureSource = '#[cfg(feature = "tauri-smoke")]\nmod smoke_support;';
  if (runtimeTestFeatureAttributes(tauriSmokeFeatureSource).length !== 1)
    fixtureFailures.push("Rust tauri-smoke feature reject fixture 未命中");
  const unknownTestFeatureSource =
    '#[cfg(feature = "runtime-test-hooks")]\nqueue_gate: Option<Gate>,';
  if (runtimeTestFeatureAttributes(unknownTestFeatureSource).length !== 1)
    fixtureFailures.push("Rust 未登记测试 feature reject fixture 未命中");
  if (rustProductionHarnessDeclarations("pub use support::RuntimeHostHarness;").length !== 1)
    fixtureFailures.push("Rust Harness re-export reject fixture 未命中");
  if (rustProductionHarnessDeclarations("pub struct RuntimeHost;").length !== 0)
    fixtureFailures.push("Rust 生产类型 allow fixture 被误拒绝");
  if (containsRustTestOnlyProductionSymbol("fn controlled_exit_host() {}"))
    fixtureFailures.push("Runtime controlled harness port allow fixture 被误拒绝");
  if (!containsRustTestOnlyProductionSymbol("fn new_for_exit_test() {}"))
    fixtureFailures.push("Runtime 测试构造器 reject fixture 未命中");
  if (!containsRustTestOnlyProductionSymbol("test_exit_gate: Option<Gate>,"))
    fixtureFailures.push("Rust 测试专用字段 reject fixture 未命中");
  if (
    !hasChineseFunctionComment(
      ["/** 为什么由 application 串行化。 */", "public flush(): void {}"],
      1,
      "/**",
    )
  )
    fixtureFailures.push("中文函数注释 allow fixture 被误拒绝");
  if (hasChineseFunctionComment(["/** flush file. */", "public flush(): void {}"], 1, "/**"))
    fixtureFailures.push("中文函数注释 reject fixture 未命中");
  if (!hasChineseFunctionComment(["/// 为什么在 application 统一 CAS。", "fn save() {}"], 1, "///"))
    fixtureFailures.push("Rust 中文函数注释 allow fixture 被误拒绝");
  if (hasChineseFunctionComment(["/// saves file", "fn save() {}"], 1, "///"))
    fixtureFailures.push("Rust 中文函数注释 reject fixture 未命中");
  if (
    rustFunctionRequiresChineseComment(
      ["pub fn generation(&self) -> u64 {", "    self.generation", "}"],
      0,
    )
  )
    fixtureFailures.push("Rust 简单 getter allow fixture 被误拒绝");
  if (
    rustFunctionRequiresChineseComment(
      ["fn commit(&self, input: Input)", "    -> Result<(), Error>;"],
      0,
    )
  )
    fixtureFailures.push("Rust trait 端口签名 allow fixture 被误拒绝");
  if (
    rustFunctionRequiresChineseComment(
      ["pub const fn invalid_input() -> Self {", '    Self { code: "INVALID_INPUT" }', "}"],
      0,
    )
  )
    fixtureFailures.push("Rust 纯字段映射 allow fixture 被误拒绝");
  if (rustFunctionRequiresChineseComment(["fn default_limit() -> usize {", "    128", "}"], 0))
    fixtureFailures.push("Rust 默认字面量 getter allow fixture 被误拒绝");
  if (
    !rustFunctionRequiresChineseComment(
      ["pub async fn save_with_cas(&self) {", "    self.port.save().await;", "}"],
      0,
    )
  )
    fixtureFailures.push("Rust 关键责任注释 reject fixture 未命中");
  if (typeScriptFunctionRequiresChineseComment("function formatLabel(value: string): string {"))
    fixtureFailures.push("TypeScript 私有 formatter allow fixture 被误拒绝");
  if (
    !typeScriptFunctionRequiresChineseComment(
      "export async function reconcileWorkspace(): Promise<void> {",
    )
  )
    fixtureFailures.push("TypeScript controller 责任注释 reject fixture 未命中");
  if (
    !isReactCommentTarget(
      path.join(desktopSourceRoot, "features", "navigation", "ui", "AppTitlebar.tsx"),
    )
  )
    fixtureFailures.push("React 全生产树注释目标 fixture 未命中");
  if (!isRustCommentTarget(path.join(tauriSourceRoot, "terminal", "session.rs")))
    fixtureFailures.push("Rust 存量重构模块注释目标 fixture 未命中");
  if (isRustCommentTarget(path.join(tauriSourceRoot, "foundation.rs")))
    fixtureFailures.push("Rust 未重构基础模块注释 allow fixture 被误纳入");
  if (containsRetiredWorkbenchPreferenceMapping("rightPanelTab: 'files'"))
    fixtureFailures.push("v10 当前 Tab allow fixture 被误拒绝");
  if (!containsRetiredWorkbenchPreferenceMapping("rightPanelTabs: ['search', 'files']"))
    fixtureFailures.push("v10 旧 Tab reject fixture 未命中");
  if (containsRetiredWorkbenchTabAlias("type WorkbenchCapabilityTab = 'review' | 'files';"))
    fixtureFailures.push("当前 Workbench Tab allow fixture 被误拒绝");
  if (!containsRetiredWorkbenchTabAlias("type WorkbenchCapabilityTab = 'review' | 'search';"))
    fixtureFailures.push("旧 Workbench Tab alias reject fixture 未命中");
  if (containsLegacyCommandPaletteMapping("action.invoke()"))
    fixtureFailures.push("canonical command allow fixture 被误拒绝");
  if (!containsLegacyCommandPaletteMapping("action.disabled ? undefined : action.run()"))
    fixtureFailures.push("旧 Command action reject fixture 未命中");
  if (
    containsComposerTextCompatibility(
      "interface ComposerProps { text: string; } const [error, setError] = useState<string>();",
    )
  )
    fixtureFailures.push("Composer 受控 text allow fixture 被误拒绝");
  if (!containsComposerTextCompatibility("interface ComposerProps { text?: string; }"))
    fixtureFailures.push("Composer optional text reject fixture 未命中");
  if (!containsComposerTextCompatibility("const [localText, setLocalText] = useState(text ?? '');"))
    fixtureFailures.push("Composer 本地草稿 reject fixture 未命中");
  if (
    containsTerminalAddTabStringOverload("addTab: (options?: TerminalTabCreateOptions) => boolean;")
  )
    fixtureFailures.push("Terminal options addTab allow fixture 被误拒绝");
  if (!containsTerminalAddTabStringOverload("addTab: (profile?: string) => boolean;"))
    fixtureFailures.push("Terminal string addTab reject fixture 未命中");
  if (!containsTerminalAddTabStringOverload("controller.addTab('bash');"))
    fixtureFailures.push("Terminal string addTab call reject fixture 未命中");
  if (
    containsLegacyRuntimeTerminalLayoutCompatibility(
      "interface TerminalPaneLayout { paneId: string; profile: string; }",
    )
  )
    fixtureFailures.push("Terminal dormant layout allow fixture 被误拒绝");
  if (
    !containsLegacyRuntimeTerminalLayoutCompatibility(
      "interface TerminalLayoutV0 { sessionId: string; }",
    )
  )
    fixtureFailures.push("Terminal runtime-bearing layout reject fixture 未命中");
  if (
    !containsLegacyRuntimeTerminalLayoutCompatibility(
      "function migrateTerminalLayout(value: unknown) { return value; }",
    )
  )
    fixtureFailures.push("Terminal legacy layout migration reject fixture 未命中");
  if (retiredApiInSource("useRuntimeState()", retiredDesktopApis) !== null)
    fixtureFailures.push("当前 Runtime API allow fixture 被误拒绝");
  if (retiredApiInSource("useJaConnection()", retiredDesktopApis) !== "useJaConnection")
    fixtureFailures.push("已退休 API reject fixture 未命中");
  if (
    retiredApiInSource(
      "RuntimeHost::new_with_runtime_control(config, sink, port)",
      retiredRustApis,
    ) !== "new_with_runtime_control"
  )
    fixtureFailures.push("Runtime 多构造入口 reject fixture 未命中");
  if (retiredApiInSource("adapter.review_status_all(&token)", retiredRustApis) !== null)
    fixtureFailures.push("Review 私有 Git API allow fixture 被误拒绝");
  for (const retired of ["ja_git_status", "ja_git_diff", "ja_git_snapshot"]) {
    if (retiredApiInSource(`fn ${retired}() {}`, retiredRustApis) !== retired)
      fixtureFailures.push(`旧 Git API ${retired} reject fixture 未命中`);
  }
  if (containsRetiredPreferenceStorage("name: 'ja-ui-preferences-v10'"))
    fixtureFailures.push("preferences v10 allow fixture 被误拒绝");
  if (!containsRetiredPreferenceStorage("name: 'ja-ui-preferences-v9'"))
    fixtureFailures.push("preferences v9 key reject fixture 未命中");
  return fixtureFailures;
}

/**
 * 真实文件树 fixture 运行与生产相同的路径、import、manifest 和注释 checker；
 * 内存 smoke 只保护细粒度正则，这一层负责阻止目录遍历或路径解析改坏后继续假绿。
 */
async function checkFilesystemRuleFixtures() {
  const failures = [];
  const fixture = (kind, relative) =>
    path.join(architectureFixtureRoot, kind, ...relative.split("/"));
  const read = (kind, relative) => readFile(fixture(kind, relative), "utf8");

  const allowedDomain = await read("allow", "rust/domain.rs");
  const rejectedDomain = await read("reject", "rust/domain.rs");
  if (rustDddDependencyViolation("workspace/domain/model.rs", allowedDomain) !== null)
    failures.push("文件树 Rust domain allow fixture 被误拒绝");
  if (rustDddDependencyViolation("workspace/domain/model.rs", rejectedDomain) === null)
    failures.push("文件树 Rust grouped native/Value reject fixture 未命中");
  if (
    rustDddDependencyViolation(
      "workspace/domain/model.rs",
      await read("allow", "rust/domain_plain.rs"),
    ) !== null
  )
    failures.push("文件树 Rust domain plain model allow fixture 被误拒绝");
  for (const relative of [
    "rust/domain_serde_import.rs",
    "rust/domain_serde_derive.rs",
    "rust/domain_serde_attribute.rs",
  ]) {
    if (
      rustDddDependencyViolation("workspace/domain/model.rs", await read("reject", relative)) !==
      "serde-coupling"
    )
      failures.push(`文件树 Rust domain serde ${relative} reject fixture 未命中`);
  }
  if (
    rustDddDependencyViolation(
      "workspace/domain/model.rs",
      await read("reject", "rust/domain_application_facade.rs"),
      new Set(),
      new Set(),
      new Set(["WorkspaceServiceError"]),
    ) !== "application-facade"
  )
    failures.push("文件树 Rust domain application façade reject fixture 未命中");
  if (
    rustDddDependencyViolation(
      "workspace/application/service.rs",
      await read("allow", "rust/application.rs"),
    ) !== null
  )
    failures.push("文件树 Rust application port allow fixture 被误拒绝");
  for (const [relative, expected] of [
    ["rust/application_include.rs", "source-include"],
    ["rust/application_serde.rs", "dynamic-json"],
    ["rust/application_root_value.rs", "root-value-alias"],
    ["rust/application_structured_tree.rs", "generic-structured-value"],
  ]) {
    if (
      rustDddDependencyViolation(
        "workspace/application/service.rs",
        await read("reject", relative),
      ) !== expected
    ) {
      failures.push(`文件树 Rust application ${expected} reject fixture 未命中`);
    }
  }
  const applicationInterfaceSymbols = new Set(["WorkspaceDto"]);
  if (
    rustDddDependencyViolation(
      "workspace/application/service.rs",
      await read("reject", "rust/application_interface_dto.rs"),
      applicationInterfaceSymbols,
    ) !== "interface-dto"
  )
    failures.push("文件树 Rust application interface DTO reject fixture 未命中");
  const nominalByteSources = [
    ["allow", "rust/application_nominal_bytes.rs", false],
    ["allow", "rust/application_nominal_bytes_macro.rs", false],
    ["reject", "rust/application_raw_bytes.rs", true],
    ["reject", "rust/application_generic_bytes.rs", true],
    ["reject", "rust/application_generic_bytes_macro.rs", true],
    ["reject", "rust/application_shared_bytes.rs", true],
  ];
  for (const [kind, relative, expectedReject] of nominalByteSources) {
    const source = await read(kind, relative);
    const symbols = rustNominalByteNewtypes(source);
    if (containsGenericByteTunnel(source, symbols) !== expectedReject)
      failures.push(`文件树 Rust application bytes ${kind}/${relative} fixture 结果错误`);
  }

  if (
    violatesTauriPlacement(
      "workspace/interface/fixture.rs",
      await read("allow", "rust/interface.rs"),
    )
  )
    failures.push("文件树 Tauri interface allow fixture 被误拒绝");
  if (
    !violatesTauriPlacement(
      "workspace/application/fixture.rs",
      await read("reject", "rust/application.rs"),
    )
  )
    failures.push("文件树 Tauri alias reject fixture 未命中");
  if (manifestHasTauriDependency(await read("allow", "runtime/Cargo.toml")))
    failures.push("文件树 ja-runtime manifest allow fixture 被误拒绝");
  if (!manifestHasTauriDependency(await read("reject", "runtime/Cargo.toml")))
    failures.push("文件树 ja-runtime package alias reject fixture 未命中");

  const reactCases = [
    ["allow", "react/domain.ts", "domain", false],
    ["reject", "react/domain.ts", "domain", true],
    ["reject", "react/application.ts", "application", true],
    ["reject", "react/ui.ts", "ui", true],
  ];
  for (const [kind, relative, layer, expectedReject] of reactCases) {
    const source = await read(kind, relative);
    const sourceFile = path.join(desktopSourceRoot, "features", "workspace", layer, "fixture.ts");
    const specifiers = moduleSpecifiers(source);
    const rejectedByImport = specifiers.some(
      (specifier) =>
        reactLayerDependencyViolation(
          sourceFile,
          source,
          specifier,
          resolveLocalImport(sourceFile, specifier),
        ) !== null,
    );
    const rejectedByStorage = reactLayerDependencyViolation(sourceFile, source, "", null) !== null;
    if ((rejectedByImport || rejectedByStorage) !== expectedReject)
      failures.push(`文件树 React ${layer} ${kind} fixture 结果错误`);
  }

  const editorFixtureRoot = fixture("allow", "react_editor");
  const editorFixtureFiles = new Set(
    (await collectSourceFiles(editorFixtureRoot)).map((file) =>
      portablePath(path.relative(editorFixtureRoot, file)),
    ),
  );
  if (
    missingReactDddResponsibilities("workbench/editor", ["domain", "ui"], editorFixtureFiles)
      .length > 0
  )
    failures.push("文件树 React Editor DDD allow fixture 被误拒绝");

  const allowedComposition = await read("allow", "react/composition.ts");
  if (
    isAppCompositionWireDependency(
      allowedComposition,
      "@/api/tauri/history",
      path.join(desktopSourceRoot, "api", "tauri", "history"),
    )
  )
    failures.push("文件树 App composition typed adapter allow fixture 被误拒绝");
  const rejectedComposition = await read("reject", "react/composition.ts");
  if (
    !isAppCompositionWireDependency(
      rejectedComposition,
      "@/api/tauri/runtime",
      path.join(desktopSourceRoot, "api", "tauri", "runtime"),
    )
  )
    failures.push("文件树 App composition wire DTO reject fixture 未命中");

  if ((await rustDddResponsibilityProblems(fixture("allow", "ddd/workspace"))).length > 0)
    failures.push("文件树 Rust DDD 完整 owner allow fixture 被误拒绝");
  if ((await rustDddResponsibilityProblems(fixture("reject", "ddd/workspace"))).length === 0)
    failures.push("文件树 Rust DDD 缺层/根文件 reject fixture 未命中");
  if (rustTestLayer(fixture("allow", "tests/unit/case.rs"), fixture("allow", "")) !== "unit")
    failures.push("文件树 Rust tests/unit allow fixture 被误拒绝");
  if (rustTestLayer(fixture("reject", "tests/case.rs"), fixture("reject", "")) !== null)
    failures.push("文件树 Rust tests 根级 reject fixture 未命中");

  for (const [kind, expectedOrphans] of [
    ["allow", 0],
    ["reject", 2],
  ]) {
    const registrationRoot = fixture(kind, "registration");
    const directTargets = new Set();
    const manifest = await readFile(path.join(registrationRoot, "Cargo.toml"), "utf8");
    for (const testPath of manifestTestPaths(manifest))
      directTargets.add(path.resolve(registrationRoot, testPath));
    const testFiles = (await collectRustAndTomlFiles(path.join(registrationRoot, "tests"))).filter(
      (file) => path.extname(file) === ".rs",
    );
    const registered = await registeredRustTestFiles(registrationRoot, directTargets, testFiles);
    if (testFiles.length - registered.size !== expectedOrphans)
      failures.push(`文件树 Rust 测试注册 ${kind} fixture 结果错误`);
  }

  const featureSource = await read("reject", "rust/feature.rs");
  const featureAttribute = runtimeTestFeatureAttributes(featureSource)[0];
  if (featureAttribute === undefined)
    failures.push("文件树未知 test hook feature reject fixture 未命中");
  if (retiredApiInSource(await read("allow", "retired.ts"), retiredDesktopApis) !== null)
    failures.push("文件树当前 API allow fixture 被误拒绝");
  if (retiredApiInSource(await read("reject", "retired.ts"), retiredDesktopApis) !== "useJaSession")
    failures.push("文件树旧 API reject fixture 未命中");
  if (retiredApiInSource(await read("allow", "rust/retired_git_api.rs"), retiredRustApis) !== null)
    failures.push("文件树 Review 私有 Git API allow fixture 被误拒绝");
  if (
    retiredApiInSource(await read("reject", "rust/retired_git_api.rs"), retiredRustApis) !==
    "ja_git_status"
  )
    failures.push("文件树旧 Git API reject fixture 未命中");

  for (const [kind, expected] of [
    ["allow", true],
    ["reject", false],
  ]) {
    const lines = (await read(kind, "comments.ts")).split(/\r?\n/);
    const declarationIndex = lines.findIndex((line) => line.includes("function save"));
    if (hasChineseFunctionComment(lines, declarationIndex, "/**") !== expected)
      failures.push(`文件树中文注释 ${kind} fixture 结果错误`);
  }
  return failures;
}

/** Cargo dependency 同时覆盖普通 key、dotted key、table 与 package alias。 */
function manifestHasTauriDependency(source) {
  let section = "";
  const lines = source.split(/\r?\n/).map((line) => {
    let quote = null;
    for (let index = 0; index < line.length; index += 1) {
      if (quote !== null && line[index] === "\\") index += 1;
      else if (quote !== null && line[index] === quote) quote = null;
      else if (quote === null && ["'", '"'].includes(line[index])) quote = line[index];
      else if (quote === null && line[index] === "#") return line.slice(0, index);
    }
    return line;
  });
  for (const line of lines) {
    const sectionMatch = /^\s*\[([^\]]+)]\s*$/.exec(line);
    if (sectionMatch !== null) {
      section = sectionMatch[1];
      if (/(?:^|\.)(?:dev-|build-)?dependencies\.tauri(?:-[a-z0-9_-]+)?$/i.test(section))
        return true;
      continue;
    }
    if (!/(?:^|\.)(?:dev-|build-)?dependencies$/i.test(section)) continue;
    const dependency = /^\s*["']?([A-Za-z0-9_-]+)["']?(?:\.[A-Za-z0-9_-]+)?\s*=\s*(.*)$/.exec(line);
    if (dependency === null) continue;
    if (/^tauri(?:-[a-z0-9_-]+)?$/i.test(dependency[1])) return true;
    if (/\bpackage\s*=\s*["']tauri(?:-[a-z0-9_-]+)?["']/i.test(dependency[2])) return true;
  }
  return false;
}

/** 防止抽离后的 host crate 重新依赖 Tauri，并保持 app_server_process 为唯一公共 façade。 */
async function checkRuntimeCrateBoundary() {
  const files = await collectRustAndTomlFiles(runtimeCrateRoot);
  const violations = [];
  for (const file of files) {
    const source = await readFile(file, "utf8");
    const isManifest = path.extname(file) === ".toml";
    const hasTauriDependency = isManifest
      ? manifestHasTauriDependency(source)
      : /\btauri(?:::|\s+as\b)/i.test(normalizedRustCode(source));
    if (hasTauriDependency) {
      violations.push(`${path.relative(repositoryRoot, file)}: ja-runtime 不得依赖 Tauri`);
    }
  }
  const facade = await readFile(
    path.join(runtimeCrateRoot, "src", "app_server_process", "mod.rs"),
    "utf8",
  );
  if (
    /^\s*pub\s+mod\s+/m.test(stripCommentsAndStrings(facade)) ||
    runtimePrivateModule.test(facade)
  ) {
    violations.push(
      "crates/ja-runtime/src/app_server_process/mod.rs: 内部协议、registry、进程与生命周期模块必须保持私有",
    );
  }
  if (
    /pub\s+use\s+[^;]*(?:Pending|Handshake|ProcessTree|Registry)[A-Za-z0-9_]*/s.test(
      stripCommentsAndStrings(facade),
    )
  ) {
    violations.push(
      "crates/ja-runtime/src/app_server_process/mod.rs: pending、handshake 与 process tree 实现不得从 façade 导出",
    );
  }
  const crateRoot = await readFile(path.join(runtimeCrateRoot, "src", "lib.rs"), "utf8");
  const publicModules = [
    ...stripCommentsAndStrings(crateRoot).matchAll(
      /^\s*pub\s+mod\s+([A-Za-z_][A-Za-z0-9_]*)\s*;/gm,
    ),
  ].map((match) => match[1]);
  if (publicModules.length !== 1 || publicModules[0] !== "app_server_process") {
    violations.push(
      "crates/ja-runtime/src/lib.rs: app_server_process 必须是唯一公共 module/façade",
    );
  }
  for (const layer of runtimePhysicalLayers) {
    const layerRoot = path.join(runtimeCrateRoot, "src", "app_server_process", layer);
    try {
      if (
        !(await collectRustAndTomlFiles(layerRoot)).some((file) => path.extname(file) === ".rs")
      ) {
        violations.push(
          `crates/ja-runtime/src/app_server_process/${layer}: Runtime 物理责任层不能为空`,
        );
      }
    } catch {
      violations.push(`crates/ja-runtime/src/app_server_process/${layer}: 缺少 Runtime 物理责任层`);
    }
  }
  return violations;
}

/** 遍历 Rust/TOML 源码且不进入构建输出，因为源码和 Cargo 都可能重新引入耦合。 */
async function collectRustAndTomlFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectRustAndTomlFiles(entryPath)));
    } else if ([".rs", ".toml"].includes(path.extname(entry.name))) {
      files.push(entryPath);
    }
  }
  return files;
}

/** 汇总全部边界错误，便于一次修复而不是逐条削弱门禁。 */
async function main() {
  const fixtureViolations = [...checkRuleFixtures(), ...(await checkFilesystemRuleFixtures())];
  if (process.env.JA_ARCHITECTURE_FIXTURES_ONLY === "1") {
    if (fixtureViolations.length > 0)
      throw new Error(
        `架构 fixture 检查失败:\n${fixtureViolations.map((item) => `- ${item}`).join("\n")}`,
      );
    console.log("ARCHITECTURE_FIXTURES_OK mode=filesystem-and-predicate");
    return;
  }
  for (const requiredPath of [
    "apps/desktop/src",
    "apps/desktop/tests",
    "src-tauri/src",
    "crates/ja-runtime/src",
    "app-server/pom.xml",
  ]) {
    await requirePath(requiredPath);
  }
  const legacyPaths = [
    "src",
    "index.html",
    "vite.config.ts",
    "playwright.config.ts",
    "agent",
    "app-server/agent",
    "src-tauri/Cargo.lock",
    "src-tauri/src/agent_process",
    "src-tauri/src/workspace_read",
    "src-tauri/src/git_read",
    "src-tauri/src/settings",
    "src-tauri/src/app_runtime/application/bridge",
    "src-tauri/tests/unit/settings",
    "src-tauri/tests/unit/app_runtime/bridge",
    "apps/desktop/src/features/workbench/git",
    "apps/desktop/tests/features/workbench/git",
    "apps/desktop/src/features/workbench/SummaryPanel.tsx",
    "apps/desktop/src/common/components",
    "spikes/java-native-dependencies",
    "spikes/mcp-skills",
    "spikes/native-image",
    "spikes/protocol",
    "spikes/sandbox",
    "spikes/tauri-sidecar",
    "spikes/ui",
  ];
  const legacyViolations = [];
  for (const legacyPath of legacyPaths) {
    try {
      await access(path.join(repositoryRoot, legacyPath), fsConstants.F_OK);
      legacyViolations.push(`旧目录或已删除组件仍存在: ${legacyPath}`);
    } catch {
      // 目标不存在才是唯一允许状态。
    }
  }
  const violations = [
    ...legacyViolations,
    ...fixtureViolations,
    ...(await checkDesktopDependencies()),
    ...(await checkReactDddResponsibilities()),
    ...(await checkReactDddTestOrganization()),
    ...(await checkAppCompositionResponsibility()),
    ...(await checkAppCompositionDependencies()),
    ...(await checkReactUiOwnership()),
    ...(await checkSharedUiReuse()),
    ...(await checkRustDddResponsibilities()),
    ...(await checkRustDddBoundaries()),
    ...(await checkTauriPlacement()),
    ...(await checkRustTestOrganization()),
    ...(await checkRuntimeTestConstructors()),
    ...(await checkAuthorMarkers()),
    ...(await checkChineseFunctionComments()),
    ...(await checkRetiredApis()),
    ...(await checkRuntimeCrateBoundary()),
  ];
  if (violations.length > 0) {
    throw new Error(`架构边界检查失败:\n${violations.map((item) => `- ${item}`).join("\n")}`);
  }
  console.log(
    "ARCHITECTURE_CHECK_OK chain=apps/desktop->src-tauri->crates/ja-runtime->app-server desktop=app,api,features,shared tests=apps/desktop/tests",
  );
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
