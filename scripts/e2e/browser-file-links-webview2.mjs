// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* global document, window, location, HTMLImageElement, HTMLIFrameElement */

/**
 * 文件引用与右栏浏览器的隔离 WebView2 验收入口。
 * 复用 review-redesign-production 的独立 profile、JDK25、JAR、CDP 和进程清理所有权；
 * 本文件只负责本场景 loopback 回复、可读文件 fixture 和页面交互证据。
 */

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import process from "node:process";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { runProduction } from "./review-redesign-production.mjs";

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const REQUEST_MARKER = "JA_BROWSER_FILE_LINKS_REQUEST";
const PROMPT = `${REQUEST_MARKER}: 请回传 fixture 中给出的所有真实文件路径，保留行列定位。`;
const FIXTURE_TEXT_MARKER = "JA_BROWSER_FILE_TEXT_CONTENT";
const FIXTURE_HTML_MARKER = "JA_BROWSER_FILE_HTML_CONTENT";
const FIXTURE_NEXT_MARKER = "JA_BROWSER_FILE_HISTORY_NEXT";
const FIXTURE_THREAD_B_MARKER = "JA_BROWSER_FILE_THREAD_B";
const FIXTURE_DUPLICATE_A_MARKER = "JA_BROWSER_FILE_DUPLICATE_A";
const FIXTURE_DUPLICATE_B_MARKER = "JA_BROWSER_FILE_DUPLICATE_B";
const FIXTURE_SVG_MARKER = "JA_BROWSER_FILE_SVG_CONTENT";
const FIXTURE_PDF_MARKER = "JA_BROWSER_FILE_PDF_CONTENT";
const FIXTURE_HTTP_HISTORY_MARKER = "JA_BROWSER_FILE_HTTP_HISTORY_ORIGIN";
const PNG_FIXTURE = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
  "base64",
);
const execFileAsync = promisify(execFile);
const EXPLORER_TARGET_WINDOWS_SCRIPT = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$target = [IO.Path]::GetFullPath($env:JA_E2E_EXPLORER_TARGET)
$parent = [IO.Path]::GetDirectoryName($target)
$shellApp = New-Object -ComObject Shell.Application
$rows = @()
foreach ($window in @($shellApp.Windows())) {
  try {
    if ($window.FullName -notlike '*explorer.exe') { continue }
    $location = ([uri]$window.LocationURL).LocalPath
    if (-not [string]::Equals($location, $parent, [StringComparison]::OrdinalIgnoreCase)) { continue }
    $selected = @($window.Document.SelectedItems()) | ForEach-Object { $_.Path }
    $rows += [pscustomobject]@{ hwnd = [int64]$window.HWND; parent = $location; selected = @($selected) }
  } catch { }
}
ConvertTo-Json -InputObject $rows -Compress -Depth 3
`;
const EXPLORER_ALL_WINDOWS_SCRIPT = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$shellApp = New-Object -ComObject Shell.Application
$rows = @()
foreach ($window in @($shellApp.Windows())) {
  try {
    if ($window.FullName -notlike '*explorer.exe') { continue }
    $rows += [pscustomobject]@{
      hwnd = [int64]$window.HWND
      parent = ([uri]$window.LocationURL).LocalPath
      selected = @($window.Document.SelectedItems() | ForEach-Object { $_.Path })
    }
  } catch { }
}
ConvertTo-Json -InputObject $rows -Compress -Depth 3
`;
const CLOSE_EXPLORER_TARGET_WINDOW_SCRIPT = String.raw`
$ErrorActionPreference = 'Stop'
$target = [IO.Path]::GetFullPath($env:JA_E2E_EXPLORER_TARGET)
$parent = [IO.Path]::GetDirectoryName($target)
$expected = [int64]$env:JA_E2E_EXPLORER_HWND
$shellApp = New-Object -ComObject Shell.Application
foreach ($window in @($shellApp.Windows())) {
  try {
    if ([int64]$window.HWND -ne $expected) { continue }
    if (-not [string]::Equals(([uri]$window.LocationURL).LocalPath, $parent, [StringComparison]::OrdinalIgnoreCase)) { continue }
    $window.Quit()
    Write-Output 'closed'
    break
  } catch { }
}
`;
let activeBrowserEvidenceDirectory;
let activeBrowserRuntimeHome;
let activeBrowserFileOpenAcknowledgements;

/** 用 Shell COM 只读取隔离 fixture 的父目录和当前选中项，避免以进程已启动冒充正确落点。 */
async function explorerTargetWindows(targetPath) {
  const { stdout } = await execFileAsync(
    "pwsh",
    ["-NoProfile", "-Command", EXPLORER_TARGET_WINDOWS_SCRIPT],
    {
      env: { ...process.env, JA_E2E_EXPLORER_TARGET: targetPath },
      windowsHide: true,
      timeout: 8_000,
    },
  );
  const parsed = JSON.parse(stdout.trim());
  return Array.isArray(parsed) ? parsed : [parsed];
}

/** 失败时只对比本次点击后新增的 Explorer 窗口，区分真实落点与 COM 目标过滤失误。 */
async function explorerAllWindows() {
  const { stdout } = await execFileAsync(
    "pwsh",
    ["-NoProfile", "-Command", EXPLORER_ALL_WINDOWS_SCRIPT],
    { windowsHide: true, timeout: 8_000 },
  );
  const parsed = JSON.parse(stdout.trim());
  return Array.isArray(parsed) ? parsed : [parsed];
}

/** 只关闭本轮新开的目标目录窗口，不碰用户已有的资源管理器实例。 */
async function closeExplorerTargetWindow(targetPath, hwnd) {
  const { stdout } = await execFileAsync(
    "pwsh",
    ["-NoProfile", "-Command", CLOSE_EXPLORER_TARGET_WINDOW_SCRIPT],
    {
      env: {
        ...process.env,
        JA_E2E_EXPLORER_TARGET: targetPath,
        JA_E2E_EXPLORER_HWND: String(hwnd),
      },
      windowsHide: true,
      timeout: 8_000,
    },
  );
  assert.equal(stdout.trim(), "closed", "isolated Explorer verification window did not close");
}

/** 依据字节偏移生成最小单页 PDF，避免测试内容依赖 Office 或系统默认文件处理器。 */
export function createPdfFixture() {
  const pdfText = `BT /F1 14 Tf 20 90 Td (${FIXTURE_PDF_MARKER}) Tj ET`;
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 160] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${Buffer.byteLength(pdfText, "latin1")} >>\nstream\n${pdfText}\nendstream`,
  ];
  let body = "%PDF-1.4\n%JaBrowserFixture\n";
  const offsets = [0];
  for (const [index, object] of objects.entries()) {
    offsets.push(Buffer.byteLength(body, "latin1"));
    body += `${index + 1} 0 obj\n${object}\nendobj\n`;
  }
  const xrefOffset = Buffer.byteLength(body, "latin1");
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets.slice(1)) {
    body += `${String(offset).padStart(10, "0")} 00000 n \n`;
  }
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;
  return Buffer.from(body, "latin1");
}

/**
 * 建立仅位于本轮 workspace 及其隔离兄弟目录的文件集合；外部文本专门验证绝对路径读取
 * 与 workspace 编辑权限的分离，浏览器资源则都用相对链接证明 file URL 的真实加载。
 */
export async function writeBrowserFileFixture(workspaceRoot) {
  const workspace = resolve(workspaceRoot);
  const htmlPath = join(workspace, "web", "图形 示例", "首页 demo.html");
  const nextPath = join(workspace, "web", "图形 示例", "历史 后页.html");
  const threadBPath = join(workspace, "web", "图形 示例", "会话 B 页面.html");
  const svgPath = join(workspace, "web", "图形 示例", "资源", "标记 图.svg");
  const imagePath = join(workspace, "web", "图形 示例", "资源", "像素图.png");
  const pdfPath = join(workspace, "web", "图形 示例", "阅读 指南.pdf");
  const textPath = join(workspace, "说明", "本地 说明.txt");
  const sourcePath = join(workspace, "src", "导航", "启动 模块.ts");
  const duplicateAPath = join(workspace, "文档 A", "重复 名称.txt");
  const duplicateBPath = join(workspace, "文档 B", "重复 名称.txt");
  const externalPath = join(dirname(workspace), "工作区外 文件", "外部 只读说明.txt");
  const missingPath = join(workspace, "src", "缺失", "移动后 文件.ts");
  const contents = [
    [
      htmlPath,
      `<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8"><title>Ja 本地 HTML 验收</title></head>\n<body><h1>${FIXTURE_HTML_MARKER}</h1><img id="local-png" src="./资源/像素图.png" alt="本地 PNG"><img id="local-svg" src="./资源/标记 图.svg" alt="本地 SVG"><a id="next-page" href="./历史 后页.html">历史后页</a><iframe id="local-pdf" title="本地 PDF" src="./阅读 指南.pdf"></iframe></body></html>\n`,
    ],
    [
      nextPath,
      `<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8"><title>Ja 历史后页</title></head><body><h1>${FIXTURE_NEXT_MARKER}</h1><a href="./首页 demo.html">返回首页</a></body></html>\n`,
    ],
    [
      threadBPath,
      `<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8"><title>Ja 会话 B 页面</title></head><body><h1>${FIXTURE_THREAD_B_MARKER}</h1></body></html>\n`,
    ],
    [
      svgPath,
      `<svg xmlns="http://www.w3.org/2000/svg" width="320" height="160" viewBox="0 0 320 160"><title>${FIXTURE_SVG_MARKER}</title><rect width="320" height="160" fill="#eaf1ff"/><text x="16" y="84" font-size="18">${FIXTURE_SVG_MARKER}</text></svg>\n`,
    ],
    [textPath, `${FIXTURE_TEXT_MARKER}\nJa 本地文本文件应以只读内容页打开。\n`],
    [sourcePath, "export const first = true;\nexport const second = 2;\n"],
    [duplicateAPath, `${FIXTURE_DUPLICATE_A_MARKER}\n同名候选 A。\n`],
    [duplicateBPath, `${FIXTURE_DUPLICATE_B_MARKER}\n同名候选 B。\n`],
    [externalPath, `${FIXTURE_TEXT_MARKER}\n该文件位于隔离 workspace 之外，应保持只读。\n`],
  ];
  await Promise.all(
    contents.map(async ([path, content]) => {
      await mkdir(dirname(path), { recursive: true });
      await writeFile(path, content, "utf8");
    }),
  );
  await Promise.all([
    mkdir(dirname(imagePath), { recursive: true }),
    mkdir(dirname(pdfPath), { recursive: true }),
  ]);
  await Promise.all([writeFile(imagePath, PNG_FIXTURE), writeFile(pdfPath, createPdfFixture())]);
  const externalRelation = relative(workspace, externalPath);
  assert.ok(externalRelation.startsWith("..") || isAbsolute(externalRelation));
  return {
    workspace,
    htmlPath,
    nextPath,
    threadBPath,
    svgPath,
    imagePath,
    pdfPath,
    textPath,
    sourcePath,
    duplicateAPath,
    duplicateBPath,
    externalPath,
    missingPath,
    relativeHtmlPath: relative(workspace, htmlPath).replaceAll("\\", "/"),
    relativeTextPath: relative(workspace, textPath).replaceAll("\\", "/"),
    relativeSourcePath: relative(workspace, sourcePath).replaceAll("\\", "/"),
    relativeDuplicateAPath: relative(workspace, duplicateAPath).replaceAll("\\", "/"),
    relativeDuplicateBPath: relative(workspace, duplicateBPath).replaceAll("\\", "/"),
    relativeMissingPath: relative(workspace, missingPath).replaceAll("\\", "/"),
    htmlUrl: pathToFileURL(htmlPath).href,
    nextUrl: pathToFileURL(nextPath).href,
    threadBUrl: pathToFileURL(threadBPath).href,
    imageUrl: pathToFileURL(imagePath).href,
    svgUrl: pathToFileURL(svgPath).href,
    pdfUrl: pathToFileURL(pdfPath).href,
    textUrl: pathToFileURL(textPath).href,
    sourceUrl: pathToFileURL(sourcePath).href,
    externalUrl: pathToFileURL(externalPath).href,
  };
}

/** 返回真实文件引用语料，覆盖 Markdown URL、旧式行内代码相对/绝对路径及行列定位。 */
export function buildAssistantFileReply(files) {
  return [
    "文件引用验收 fixture 已创建以下目标：",
    `- 首页相对路径：\`${files.relativeHtmlPath}\``,
    `- 首页 Markdown 链接：[打开本地 HTML](${files.htmlUrl})`,
    `- 会话 B 页面：\`${files.threadBPath}\``,
    `- 本地图片：\`${files.imagePath}\``,
    `- SVG 页面：\`${files.svgPath}\``,
    `- PDF 页面：\`${files.pdfPath}\``,
    `- 文本内容：\`${files.relativeTextPath}\``,
    `- 带行列定位的代码：\`${files.relativeSourcePath}#L2C4\``,
    `- 旧回复行列定位：\`${files.relativeSourcePath}:2:4\``,
    `- 纯行号定位：\`${files.relativeSourcePath}#L1\``,
    `- 重名候选：\`重复 名称.txt\``,
    `- 已删除的旧路径：\`${files.relativeMissingPath}\``,
    `- workspace 外部的 Windows 绝对路径：\`${files.externalPath}\``,
    `- workspace 外部的 file URL：[打开只读说明](${files.externalUrl})`,
  ].join("\n\n");
}

/** 生成与 JA-RPC Provider adapter 相符的纯文本完成事件；fixture 不记录请求正文或凭据。 */
function successfulTextStream(text, ordinal) {
  const responseId = `resp_browser_files_${ordinal}`;
  const messageId = `msg_browser_files_${ordinal}`;
  const response = (status, output = [], usage = false) => ({
    id: responseId,
    created_at: 0,
    model: "ja-browser-files-e2e",
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status,
    ...(usage
      ? {
          usage: {
            input_tokens: 8,
            input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
            output_tokens: 24,
            output_tokens_details: { reasoning_tokens: 0 },
            total_tokens: 32,
          },
        }
      : {}),
  });
  const event = (type, sequence, data) =>
    `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequence, ...data })}\n\n`;
  const item = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, { response: response("in_progress") }),
    event("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: messageId,
      logprobs: [],
      output_index: 0,
    }),
    event("response.output_text.done", 2, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text,
    }),
    event("response.completed", 3, { response: response("completed", [item], true) }),
  ].join("");
}

/** 只允许本机 Responses endpoint；非 turn 标题请求使用固定标题文案，敏感输入不进入诊断。 */
export async function startBrowserFileProviderFixture() {
  let reply = "Ja 文件链接验收";
  let ordinal = 0;
  let acceptanceTurnCount = 0;
  let historyRequestCount = 0;
  const server = createServer(async (request, response) => {
    if (request.method === "GET" && request.url === "/browser-history-origin") {
      historyRequestCount += 1;
      const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>Ja HTTP history origin</title></head><body><h1>${FIXTURE_HTTP_HISTORY_MARKER}</h1><p id="history-request-count">${historyRequestCount}</p><p>Back returns to this HTTP document before Forward restores file://.</p></body></html>`;
      response.writeHead(200, {
        "cache-control": "no-store",
        "content-length": Buffer.byteLength(html, "utf8"),
        "content-type": "text/html; charset=utf-8",
      });
      response.end(html, "utf8");
      return;
    }
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    const chunks = [];
    let received = 0;
    for await (const chunk of request) {
      received += chunk.length;
      if (received > 2 * 1024 * 1024) {
        response.writeHead(413, { "content-type": "application/json" });
        response.end('{"error":"request_too_large"}');
        return;
      }
      chunks.push(chunk);
    }
    let payload;
    try {
      payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      response.writeHead(400, { "content-type": "application/json" });
      response.end('{"error":"invalid_json"}');
      return;
    }
    const inputText = JSON.stringify(payload?.input ?? []);
    const isAcceptanceTurn = inputText.includes(REQUEST_MARKER);
    if (isAcceptanceTurn) acceptanceTurnCount += 1;
    ordinal += 1;
    const text = isAcceptanceTurn ? reply : "Ja 文件链接验收";
    const stream = successfulTextStream(text, ordinal);
    response.writeHead(200, {
      "cache-control": "no-store",
      "content-length": Buffer.byteLength(stream, "utf8"),
      "content-type": "text/event-stream; charset=utf-8",
    });
    response.end(stream, "utf8");
  });
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  const origin = `http://127.0.0.1:${address.port}`;
  return {
    baseUrl: `${origin}/v1`,
    historyUrl: `${origin}/browser-history-origin`,
    /** 切换本轮受控回答；路径由 runner fixture 提供，不依赖用户项目位置。 */
    setReply(nextReply) {
      assert.equal(typeof nextReply, "string");
      reply = nextReply;
    },
    /** 只返回计数，不保留 Provider 请求的 prompt、路径或认证字段。 */
    snapshot() {
      return { requestCount: ordinal, acceptanceTurnCount, historyRequestCount };
    },
    /** 关闭本轮唯一 loopback listener，避免隔离真窗完成后遗留后台端口。 */
    async close() {
      await new Promise((resolvePromise, reject) =>
        server.close((error) => (error === undefined ? resolvePromise() : reject(error))),
      );
    },
  };
}

/**
 * 在 reload 前记录浏览器 open/layout/close 命令 ACK，不复制 URL、文件路径或其它命令参数。
 * 以 shared probe chain 包装可保留现有真窗验收观察者的执行顺序。
 */
export function installBrowserFileInvokeProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  const observedPrefixes = ["ja_preview_", "ja_browser_"];
  globalThis.__JA_BROWSER_FILE_NATIVE_CALL_SEQUENCE__ = 0;
  globalThis.__JA_BROWSER_FILE_NATIVE_TRACE__ = [];
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = request?.command;
    const callId = globalThis.__JA_BROWSER_FILE_NATIVE_CALL_SEQUENCE__++;
    const observed =
      typeof command === "string" && observedPrefixes.some((prefix) => command.startsWith(prefix));
    const append = (phase, error, result) => {
      if (!observed) return;
      const input = request?.args?.input;
      const target = input?.target;
      const rawTarget =
        typeof target === "string"
          ? target
          : (target?.path ?? target?.url ?? input?.url ?? undefined);
      let targetBasename;
      if (typeof rawTarget === "string") {
        const rawBasename = (rawTarget.split(/[\\/]/u).at(-1) ?? "").replace(/[?#].*$/u, "");
        try {
          targetBasename = decodeURIComponent(rawBasename).slice(0, 120);
        } catch {
          targetBasename = rawBasename.slice(0, 120);
        }
      }
      const entry = {
        command,
        callId,
        phase,
        atMs: Math.round(performance.now()),
        atUnixMillis: Date.now(),
        ...(typeof request?.args?.input?.requestId === "string"
          ? { requestId: request.args.input.requestId }
          : {}),
        ...(typeof input?.sessionId === "string" ? { sessionId: input.sessionId } : {}),
        ...(Number.isInteger(input?.generation) ? { generation: input.generation } : {}),
        ...(typeof target?.kind === "string" ? { targetKind: target.kind } : {}),
        ...(targetBasename === undefined ? {} : { targetBasename }),
        ...(command === "ja_preview_layout"
          ? { visible: request?.args?.input?.viewport?.visible === true }
          : {}),
      };
      const pageSnapshot = result?.snapshot ?? result?.session ?? result;
      if (
        phase === "resolved" &&
        typeof pageSnapshot?.id === "string" &&
        /^[A-Za-z0-9-]{8,80}$/u.test(pageSnapshot.id)
      ) {
        entry.resultPageId = pageSnapshot.id;
      }
      if (phase === "resolved" && Number.isInteger(pageSnapshot?.generation)) {
        entry.resultGeneration = pageSnapshot.generation;
      }
      if (error !== undefined) {
        const errorName = typeof error?.name === "string" ? error.name : "Error";
        const rawCode = error?.code ?? error?.kind ?? error?.reason;
        entry.errorName = errorName.slice(0, 80);
        if (typeof rawCode === "string" && /^[A-Za-z0-9_.:-]{1,80}$/u.test(rawCode))
          entry.errorCode = rawCode;
      }
      globalThis.__JA_BROWSER_FILE_NATIVE_TRACE__.push(entry);
    };
    append("start");
    try {
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      append("resolved", undefined, result);
      return result;
    } catch (error) {
      append("rejected", error);
      throw error;
    }
  };
}

/** 将本轮点击后成功返回的本地 Preview 原生命令与路径 basename 关联，排除先前 ACK。 */
export function findResolvedFileOpenAcknowledgement(trace, previousCallIds, targetPath) {
  const expectedBasename = basename(targetPath).toLocaleLowerCase("en-US");
  return (
    trace.find(
      ({ callId, command, phase, targetBasename }) =>
        !previousCallIds.has(callId) &&
        ["ja_preview_open_file", "ja_preview_navigate_file"].includes(command) &&
        phase === "resolved" &&
        targetBasename?.toLocaleLowerCase("en-US") === expectedBasename,
    ) ?? null
  );
}

/** 保留短时 Preview open 等待失败时的可见 Host、child target 与隔离运行时日志。 */
async function captureFileOpenDiagnostics(
  page,
  workbench,
  targetPath,
  trace,
  previousCallIds,
  clickAtMs,
) {
  const [tabs, panels, hostDom, childTargets, appServerErrorLog] = await Promise.all([
    browserTabFacts(workbench).catch(() => []),
    browserPageFacts(workbench).catch(() => []),
    workbench
      .evaluate((node) => ({
        threadHost: (() => {
          const host = node.closest(".ja-thread-workbench-session");
          return host === null
            ? null
            : {
                className: host.className,
                hidden: host.hidden,
                inert: host.inert,
                ariaHidden: host.getAttribute("aria-hidden"),
              };
        })(),
        tablist: node
          .querySelector('[role="tablist"][aria-label="浏览器页面"]')
          ?.outerHTML.slice(0, 6_000),
        panels: Array.from(node.querySelectorAll('[role="tabpanel"]')).map((panel) => ({
          id: panel.getAttribute("data-preview-page-id"),
          url: panel.getAttribute("data-preview-page-url"),
          title: panel.getAttribute("data-preview-page-title"),
          html: panel.outerHTML.slice(0, 2_000),
        })),
      }))
      .catch(() => undefined),
    Promise.all(
      page
        .context()
        .pages()
        .filter((candidate) => candidate !== page)
        .map(async (candidate) => ({
          url: candidate.url(),
          title: await candidate.title().catch(() => ""),
          closed: candidate.isClosed(),
        })),
    ).catch(() => []),
    activeBrowserRuntimeHome === undefined
      ? Promise.resolve("")
      : readFile(
          join(activeBrowserRuntimeHome, "profile", ".ja", "logs", "java", "app-server-error.log"),
          "utf8",
        ).catch(() => ""),
  ]);
  const basenameKey = basename(targetPath).toLocaleLowerCase("en-US");
  const targetTrace = trace.filter(
    ({ targetBasename }) => targetBasename?.toLocaleLowerCase("en-US") === basenameKey,
  );
  const openStart = targetTrace.find(
    ({ callId, command, phase }) =>
      ["ja_preview_open_file", "ja_preview_navigate_file"].includes(command) &&
      phase === "start" &&
      !previousCallIds.has(callId),
  );
  const facts = {
    targetBasename: basename(targetPath),
    waitedMs: Date.now() - clickAtMs,
    openStartedAtUnixMillis: openStart?.atUnixMillis,
    openStartLatencyMs:
      openStart?.atUnixMillis === undefined ? undefined : Date.now() - openStart.atUnixMillis,
    tabs,
    panels,
    hostDom,
    childTargets,
    targetCommands: targetTrace.slice(-20),
    appServerErrorLogTail: appServerErrorLog.slice(-4_000),
  };
  if (activeBrowserEvidenceDirectory !== undefined) {
    await writeFile(
      join(activeBrowserEvidenceDirectory, "file-open-pending-diagnostic.json"),
      `${JSON.stringify(facts, null, 2)}\n`,
      "utf8",
    );
    await page
      .screenshot({
        path: join(activeBrowserEvidenceDirectory, "file-open-pending-diagnostic.png"),
        animations: "disabled",
      })
      .catch(() => undefined);
  }
  return facts;
}

/** Thread→Host 的文件打开先等原生 open ACK，再用其 page id 校验可见 tab/panel 收敛。 */
async function waitForFileOpenAcknowledgement(page, workbench, targetPath, beforeTrace, deadline) {
  const previousCallIds = new Set(beforeTrace.map(({ callId }) => callId));
  const clickAtMs = Date.now();
  const expectedBasename = basename(targetPath).toLocaleLowerCase("en-US");
  let acknowledgement;
  try {
    await waitForCondition(
      "native file open acknowledgement",
      async () => {
        const trace = await browserCommandTrace(page);
        const rejected = trace.find(
          ({ callId, command, phase, targetBasename }) =>
            !previousCallIds.has(callId) &&
            ["ja_preview_open_file", "ja_preview_navigate_file"].includes(command) &&
            targetBasename?.toLocaleLowerCase("en-US") === expectedBasename &&
            phase === "rejected",
        );
        if (rejected !== undefined) {
          throw new Error(
            `native ${rejected.command} rejected (${rejected.errorCode ?? rejected.errorName ?? "unknown"})`,
          );
        }
        acknowledgement = findResolvedFileOpenAcknowledgement(trace, previousCallIds, targetPath);
        return acknowledgement !== null;
      },
      Math.min(deadline, Date.now() + 20_000),
    );
    await waitForCondition(
      "active Browser page after native file open acknowledgement",
      async () => {
        const active = (await browserTabFacts(workbench)).find(({ selected }) => selected);
        if (typeof active?.id !== "string" || active.id.length === 0) return false;
        if (
          acknowledgement?.resultPageId !== undefined &&
          acknowledgement.resultPageId !== active.id
        ) {
          return false;
        }
        const projection = (await browserPageFacts(workbench)).find(({ id }) => id === active.id);
        return (
          projection !== undefined &&
          normalizedFilePath(projection.url ?? "") === normalizedFilePath(targetPath)
        );
      },
      Math.min(deadline, Date.now() + 8_000),
    );
  } catch (error) {
    const trace = await browserCommandTrace(page).catch(() => []);
    const diagnostics = await captureFileOpenDiagnostics(
      page,
      workbench,
      targetPath,
      trace,
      previousCallIds,
      clickAtMs,
    );
    throw new Error(
      `native file open did not reach an active Browser page: ${String(error?.message ?? error)}; ` +
        `diagnostics=${JSON.stringify(diagnostics)}`,
    );
  }
  const active = (await browserTabFacts(workbench)).find(({ selected }) => selected);
  const trace = await browserCommandTrace(page);
  const started = trace.find(
    ({ callId, command, phase }) =>
      callId === acknowledgement.callId && command === acknowledgement.command && phase === "start",
  );
  const result = {
    command: acknowledgement.command,
    pageId: acknowledgement.resultPageId,
    tabPageId: active?.id,
    generation: acknowledgement.resultGeneration ?? acknowledgement.generation,
    ackLatencyMs:
      started?.atUnixMillis === undefined || acknowledgement.atUnixMillis === undefined
        ? undefined
        : acknowledgement.atUnixMillis - started.atUnixMillis,
  };
  activeBrowserFileOpenAcknowledgements?.push(result);
  return result;
}

/** 从注入 probe 读取脱敏 trace 副本，供资源关闭断言计算 ACK 数而不保留路径。 */
async function browserCommandTrace(page) {
  return page.evaluate(() =>
    (globalThis.__JA_BROWSER_FILE_NATIVE_TRACE__ ?? []).map((entry) => ({ ...entry })),
  );
}

/** 预览区进入产品错误态时立刻保留 page identity 与最近原生命令 ACK，避免仅看到模糊 toast。 */
async function assertNoPreviewFailure(workbench, page, stage, pageErrors = [], evidenceDirectory) {
  const panel = await activeBrowserPanel(workbench);
  const visible = await panel
    .getByText("浏览器预览异常", { exact: true })
    .isVisible()
    .catch(() => false);
  if (!visible) return;
  if (evidenceDirectory !== undefined) {
    const screenshotName = stage.replace(/[^a-z0-9-]+/giu, "-").toLowerCase();
    await page.screenshot({
      path: join(evidenceDirectory, `preview-error-${screenshotName}.png`),
      animations: "disabled",
    });
  }
  const facts = await panel.evaluate((node) => ({
    id: node.getAttribute("data-preview-page-id"),
    url: node.getAttribute("data-preview-page-url"),
    title: node.getAttribute("data-preview-page-title"),
    loading: node.getAttribute("data-preview-page-loading"),
    canGoBack: node.getAttribute("data-preview-can-go-back"),
    canGoForward: node.getAttribute("data-preview-can-go-forward"),
    text: node.innerText.slice(0, 300),
  }));
  const trace = await browserCommandTrace(page);
  throw new Error(
    `Browser preview failure at ${stage}: panel=${JSON.stringify(facts)}; nativeCommands=${JSON.stringify(trace.slice(-30))}; console=${JSON.stringify(pageErrors.slice(-12))}`,
  );
}

/** 统计某一命令前缀的 ACK；报告仅包含数量，不泄露命令载荷或本机路径。 */
function countCommandPrefix(trace, prefix, phase = "resolved") {
  return trace.filter((entry) => entry.command.startsWith(prefix) && entry.phase === phase).length;
}

/** 统计原生 layout 的显隐 ACK，区分会话隐藏与恢复显示，避免其它 resize 混入证据。 */
function countLayoutVisibility(trace, visible) {
  return trace.filter(
    (entry) =>
      entry.command === "ja_preview_layout" &&
      entry.phase === "resolved" &&
      entry.visible === visible,
  ).length;
}

/** WebView2/Rust 的 canonical path 可带 Windows verbatim 前缀；去掉此前缀后按同一文件身份比较。 */
function normalizedFilePath(value) {
  try {
    const rawPath = value.startsWith("file:") ? fileURLToPath(value) : value;
    const filePath =
      rawPath.startsWith("\\\\?\\") && /^[a-z]:[\\/]/iu.test(rawPath.slice(4))
        ? rawPath.slice(4)
        : rawPath;
    return resolve(filePath).replaceAll("/", sep).toLocaleLowerCase("en-US");
  } catch {
    return undefined;
  }
}

/** 将 Files 面板的 workspace-relative identity 还原到 fixture 根，避免误用 runner cwd。 */
export function canonicalFileDocumentPath(value, workspaceRoot) {
  const candidate =
    value.startsWith("file:") || isAbsolute(value) ? value : resolve(workspaceRoot, value);
  return normalizedFilePath(candidate);
}

/** 把本地 file URL、相对路径或带行列锚点的路径映射为 fixture 的 canonical identity。 */
function referenceFilePath(value, workspaceRoot) {
  const withoutLine = value.replace(
    /(?:#L[1-9][0-9]*(?:C[1-9][0-9]*)?|:[1-9][0-9]*(?::[1-9][0-9]*)?)$/u,
    "",
  );
  const candidate = withoutLine.startsWith("file:")
    ? withoutLine
    : isAbsolute(withoutLine)
      ? withoutLine
      : resolve(workspaceRoot, withoutLine);
  return normalizedFilePath(candidate);
}

/** 从链接目标或可见源码文本提取位置锚点，兼容 UI 将 path 与 line/column 分开保存。 */
function referenceLocation(value) {
  const match =
    /(?<suffix>(?:#L(?<hashLine>[1-9][0-9]*)(?:C[1-9][0-9]*)?|:(?<colonLine>[1-9][0-9]*)(?::[1-9][0-9]*)?))$/u.exec(
      value,
    );
  const line = match?.groups?.hashLine ?? match?.groups?.colonLine;
  return match === null || line === undefined
    ? undefined
    : { suffix: match.groups.suffix, line: Number(line) };
}

/** 按 canonical 文件身份匹配引用；Markdown 的 file URL 在渲染时已解码，独立标签可区分同路径的行内代码。 */
async function fileReference(
  page,
  message,
  expectedPath,
  workspaceRoot,
  expectedLine,
  expectedReference,
) {
  const selector = "button.ja-markdown__file-link[data-file-reference]";
  const references = message.locator(selector);
  const values = await references.evaluateAll((nodes) =>
    nodes.map((node) => ({
      reference: node.getAttribute("data-file-reference") ?? "",
      accessibleName: node.getAttribute("aria-label") ?? "",
      visibleName: node.textContent?.trim() ?? "",
    })),
  );
  const candidatePath = normalizedFilePath(expectedPath);
  const index = values.findIndex(({ reference, visibleName }) => {
    const referencePath = referenceFilePath(reference, workspaceRoot);
    const location = referenceLocation(reference) ?? referenceLocation(visibleName);
    const displayReference =
      referenceLocation(reference) === undefined &&
      referenceLocation(visibleName) !== undefined &&
      referenceFilePath(visibleName, workspaceRoot) === referencePath
        ? visibleName
        : reference;
    const matchesExpectedReference =
      expectedReference === undefined ||
      (expectedReference.startsWith("file:")
        ? referenceFilePath(expectedReference, workspaceRoot) === referencePath &&
          visibleName !== reference
        : isAbsolute(expectedReference)
          ? isAbsolute(reference)
          : [reference, displayReference, visibleName].includes(expectedReference));
    return (
      referencePath === candidatePath &&
      (expectedLine === undefined || location?.line === expectedLine) &&
      matchesExpectedReference
    );
  });
  assert.notEqual(
    index,
    -1,
    `assistant message is missing a clickable path reference: ${expectedPath}`,
  );
  assert.match(values[index].accessibleName, /在 Ja 中打开文件/u);
  const locator = references.nth(index);
  await locator.waitFor({ state: "visible" });
  const visibleLocation = referenceLocation(values[index].visibleName);
  const dataLocation = referenceLocation(values[index].reference);
  const displayReference =
    dataLocation === undefined &&
    visibleLocation !== undefined &&
    referenceFilePath(values[index].visibleName, workspaceRoot) === candidatePath
      ? values[index].visibleName
      : values[index].reference;
  return {
    locator,
    reference: {
      ...values[index],
      dataReference: values[index].reference,
      reference: displayReference,
    },
  };
}

/** 保留引用元数据和定位器，供后续断言核对真实路径；点击后才检查原生入口与 Thread Workbench。 */
async function openFileReference(
  page,
  message,
  files,
  expectedPath,
  expectedCapability,
  deadline,
  line,
  expectedReference,
) {
  const entry = await fileReference(
    page,
    message,
    expectedPath,
    files.workspace,
    line,
    expectedReference,
  );
  const previewTraceBeforeClick =
    expectedCapability === "preview" ? await browserCommandTrace(page) : [];
  await entry.locator.click({ timeout: Math.max(1, deadline - Date.now()) });
  const workbench = currentWorkbench(page);
  await workbench.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await waitForCondition(
    "current Thread capability after opening file reference",
    async () => (await workbench.getAttribute("data-active-tab")) === expectedCapability,
    deadline,
  );
  if (expectedCapability === "preview") {
    await waitForFileOpenAcknowledgement(
      page,
      workbench,
      expectedPath,
      previewTraceBeforeClick,
      deadline,
    );
    await assertNoPreviewFailure(workbench, page, "assistant file-reference open");
  }
  return entry;
}

/** 用只有 basename 的旧式引用触发候选选择，并验证用户选中的 workspace 相对目标。 */
async function openAmbiguousFileReference(page, message, files, deadline) {
  const button = message
    .locator("button.ja-markdown__file-link[data-file-reference]")
    .filter({ hasText: "重复 名称.txt" });
  await button.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await button.click({ timeout: Math.max(1, deadline - Date.now()) });
  const dialog = page.getByRole("dialog", { name: "选择要打开的文件", exact: true });
  await dialog.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const candidate = dialog.locator(`button[data-file-candidate="${files.relativeDuplicateAPath}"]`);
  await candidate.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await candidate.click({ timeout: Math.max(1, deadline - Date.now()) });
  await dialog.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
  return inspectFileDocument(
    page,
    files.duplicateAPath,
    FIXTURE_DUPLICATE_A_MARKER,
    false,
    deadline,
    files.workspace,
  );
}

/** 等待入口真实 App Server 和项目目录就绪，未自动选择项目时通过用户界面加入隔离 workspace。 */
async function prepareApplication(page, deadline) {
  await waitForApplication(page, deadline);
  const selectedProject = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selectedProject.count()) === 0) {
    await page.getByRole("button", { name: "添加项目", exact: true }).click({
      timeout: Math.max(1, deadline - Date.now()),
    });
  }
  await selectedProject.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
}

/** 通过生产 History adapter 创建隔离 durable Thread；此处不绕过类型化 Tauri 接口。 */
async function createThread(page, workspaceRoot, title) {
  return page.evaluate(
    async ({ cwd, threadTitle }) => {
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      return createHistoryAdapter().threadCreate({
        cwd,
        title: threadTitle,
        providerId: "provider_e2e",
        modelId: "model_e2e",
        reasoningLevel: null,
        accessMode: "approval_required",
        collaborationMode: "default",
      });
    },
    { cwd: workspaceRoot, threadTitle: title },
  );
}

/** 选择已持久化 Thread 并等待侧栏 current identity 与真实会话一致。 */
async function selectThread(page, threadId, deadline) {
  assert.match(threadId, /^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u);
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await row.click({ timeout: Math.max(1, deadline - Date.now()) });
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
}

/** 定位当前 Thread 专属 inspector，避免隐藏会话的已保留浏览器面板混入交互与报告。 */
function currentInspector(page) {
  return page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
}

/** 返回当前 Thread 唯一 Workbench 根，调用者只在其处于 visible 时执行浏览器操作。 */
function currentWorkbench(page) {
  return currentInspector(page).locator(".ja-workbench");
}

/** 打开会话自己的右栏，且通过能力入口激活真实 Browser capability。 */
async function ensureBrowserCapability(page, deadline) {
  const inspector = currentInspector(page);
  if ((await inspector.getAttribute("data-visible")) !== "true") {
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click({
      timeout: Math.max(1, deadline - Date.now()),
    });
  }
  await inspector.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const workbench = inspector.locator(".ja-workbench");
  const browserTab = workbench.locator('[data-workbench-tab="preview"]');
  if ((await browserTab.count()) === 0) {
    await workbench.getByRole("button", { name: "新建标签页", exact: true }).click({
      timeout: Math.max(1, deadline - Date.now()),
    });
    await page
      .getByRole("menuitem")
      .filter({ hasText: "浏览器" })
      .first()
      .click({
        timeout: Math.max(1, deadline - Date.now()),
      });
  } else {
    await browserTab.click({ timeout: Math.max(1, deadline - Date.now()) });
  }
  await workbench.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await waitForCondition(
    "current Thread Browser capability",
    async () => (await workbench.getAttribute("data-active-tab")) === "preview",
    deadline,
  );
  return workbench;
}

/** 从 DOM 读取 pageId；右栏隐藏时跳过可见性等待，但仍检查保留会话的标签身份。 */
async function browserTabFacts(workbench, visible = true) {
  const tablist = workbench.locator('[role="tablist"][aria-label="浏览器页面"]');
  if (visible) await tablist.waitFor({ state: "visible" });
  return tablist.locator('[role="tab"]').evaluateAll((tabs) =>
    tabs.map((tab) => ({
      id: tab.getAttribute("data-preview-page-id"),
      selected: tab.getAttribute("aria-selected") === "true",
      title: tab.getAttribute("aria-label") ?? tab.textContent?.trim() ?? "",
    })),
  );
}

/** 读取 BrowserHost 中的可见原生页面投影，HTML 与 child WebView 由独立断言交叉核对。 */
async function browserPageFacts(workbench) {
  return workbench
    .locator('[role="tabpanel"][data-preview-page-url][data-preview-page-id]')
    .evaluateAll((pages) =>
      pages.map((node) => ({
        id: node.getAttribute("data-preview-page-id"),
        url: node.getAttribute("data-preview-page-url"),
        title: node.getAttribute("data-preview-page-title"),
        loading: node.getAttribute("data-preview-page-loading"),
        canGoBack: node.getAttribute("data-preview-can-go-back"),
        canGoForward: node.getAttribute("data-preview-can-go-forward"),
      })),
    );
}

/** 定位当前 Thread 唯一活动的 tabpanel，避免固定 DOM id 和隐藏会话面板污染真窗验收。 */
async function activeBrowserPanel(workbench, expectedPageId) {
  const pageId =
    expectedPageId ?? (await browserTabFacts(workbench)).find(({ selected }) => selected)?.id;
  if (!pageId) {
    const page = workbench.page();
    const facts = {
      tabs: await browserTabFacts(workbench),
      panels: await browserPageFacts(workbench),
      dom: await workbench.evaluate((node) => ({
        threadHost: (() => {
          const host = node.closest(".ja-thread-workbench-session");
          return host === null
            ? null
            : {
                className: host.className,
                hidden: host.hidden,
                inert: host.inert,
                ariaHidden: host.getAttribute("aria-hidden"),
              };
        })(),
        tablist: node
          .querySelector('[role="tablist"][aria-label="浏览器页面"]')
          ?.outerHTML.slice(0, 6_000),
        panels: Array.from(node.querySelectorAll('[role="tabpanel"]')).map((panel) => ({
          id: panel.getAttribute("data-preview-page-id"),
          url: panel.getAttribute("data-preview-page-url"),
          title: panel.getAttribute("data-preview-page-title"),
          html: panel.outerHTML.slice(0, 2_000),
        })),
      })),
      nativeCommands: (await browserCommandTrace(page))
        .filter(({ command }) =>
          [
            "ja_preview_open",
            "ja_preview_open_file",
            "ja_preview_open_blank",
            "ja_preview_resolve_file",
            "ja_preview_navigate",
            "ja_preview_navigate_file",
          ].includes(command),
        )
        .slice(-12),
    };
    if (activeBrowserEvidenceDirectory !== undefined) {
      await writeFile(
        join(activeBrowserEvidenceDirectory, "browser-page-identity-diagnostic.json"),
        `${JSON.stringify(facts, null, 2)}\n`,
        "utf8",
      );
      await page
        .screenshot({
          path: join(activeBrowserEvidenceDirectory, "browser-page-identity-diagnostic.png"),
          animations: "disabled",
        })
        .catch(() => undefined);
    }
    throw new Error(`active browser tab must expose page identity: ${JSON.stringify(facts)}`);
  }
  return workbench.locator(`[role="tabpanel"][data-preview-page-id="${pageId}"]`);
}

/** 等待 active browser page 使用预期地址，避免用地址栏草稿值误判原生页面导航已完成。 */
async function waitForActiveBrowserUrl(workbench, targetUrl, deadline) {
  const targetPath = normalizedFilePath(targetUrl);
  await waitForCondition(
    "active browser URL",
    async () => {
      const tabs = await browserTabFacts(workbench);
      const active = tabs.find(({ selected }) => selected);
      if (active === undefined) return false;
      const pageFacts = await browserPageFacts(workbench);
      const activePage = pageFacts.find(({ id }) => id === active.id);
      return activePage !== undefined && normalizedFilePath(activePage.url ?? "") === targetPath;
    },
    deadline,
  );
  const tabs = await browserTabFacts(workbench);
  const active = tabs.find(({ selected }) => selected);
  if (!active?.id) {
    const page = workbench.page();
    const panelFacts = await browserPageFacts(workbench);
    const dom = await workbench.evaluate((node) => ({
      tablist: node
        .querySelector('[role="tablist"][aria-label="浏览器页面"]')
        ?.outerHTML.slice(0, 12_000),
      tabs: Array.from(
        node.querySelectorAll('[role="tablist"][aria-label="浏览器页面"] [role="tab"]'),
      ).map((tab) => tab.outerHTML.slice(0, 2_000)),
      panels: Array.from(node.querySelectorAll('[role="tabpanel"]')).map((panel) =>
        panel.outerHTML.slice(0, 4_000),
      ),
    }));
    const trace = await browserCommandTrace(page);
    throw new Error(
      `active browser tab must expose page identity: selected=${JSON.stringify(active ?? null)}; ` +
        `pages=${JSON.stringify(panelFacts)}; dom=${JSON.stringify(dom)}; ` +
        `nativeCommands=${JSON.stringify(trace.slice(-30))}`,
    );
  }
  return active;
}

/** 在 Playwright CDP context 中找到真正加载本地 file URL 的原生 child WebView 页面。 */
async function waitForChildWebView(page, targetUrl, deadline) {
  const targetPath = normalizedFilePath(targetUrl);
  let child;
  await waitForCondition(
    "native child WebView target",
    async () => {
      child = page
        .context()
        .pages()
        .find(
          (candidate) => candidate !== page && normalizedFilePath(candidate.url()) === targetPath,
        );
      return child !== undefined && !child.isClosed();
    },
    deadline,
  );
  return child;
}

/** 找到指定 URL 当前承载的原生子 WebView；HTTP 与 file scheme 使用各自稳定的身份比较。 */
async function waitForNativeChildUrl(page, targetUrl, deadline) {
  const isFile = targetUrl.startsWith("file:");
  const targetPath = isFile ? normalizedFilePath(targetUrl) : undefined;
  let child;
  await waitForCondition(
    "native child WebView URL",
    async () => {
      child = page
        .context()
        .pages()
        .find((candidate) => {
          if (candidate === page || candidate.isClosed()) return false;
          return isFile
            ? normalizedFilePath(candidate.url()) === targetPath
            : candidate.url() === targetUrl;
        });
      return child !== undefined;
    },
    deadline,
  );
  return child;
}

/** 从真实主 WebView 直达闭集 Preview invoke，用于 runtime handshake 失败时验收独立原生层。 */
async function invokeNativePreview(page, command, input) {
  const result = await page.evaluate(
    async ({ commandName, payload }) => {
      const invoke = window.__TAURI_INTERNALS__?.invoke;
      if (typeof invoke !== "function")
        return { ok: false, error: "Tauri invoke bridge unavailable" };
      try {
        return {
          ok: true,
          value: await invoke(commandName, payload === undefined ? {} : { input: payload }),
        };
      } catch (error) {
        return { ok: false, error: String(error?.message ?? error).slice(0, 500) };
      }
    },
    { commandName: command, payload: input },
  );
  assert.equal(result.ok, true, `${command} invoke rejected: ${result.error ?? "unknown error"}`);
  return result.value;
}

/** 读取 Rust 签发的当前 generation，避免历史命令和异步导航竞态使用旧身份。 */
async function readNativePreviewState(page, sessionId) {
  return invokeNativePreview(page, "ja_preview_state", { sessionId });
}

/** 读取并验证 HTML 及其相对图片资源；该检查执行于实际 native child WebView document。 */
async function inspectHtmlChild(child, deadline) {
  await child.waitForFunction(
    (marker) => document.body?.innerText.includes(marker),
    FIXTURE_HTML_MARKER,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  await child.waitForFunction(
    () => {
      const png = document.querySelector("#local-png");
      const svg = document.querySelector("#local-svg");
      return png?.complete && png.naturalWidth > 0 && svg?.complete && svg.naturalWidth > 0;
    },
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  return child.evaluate(() => {
    const png = document.querySelector("#local-png");
    const svg = document.querySelector("#local-svg");
    const pdf = document.querySelector("#local-pdf");
    return {
      title: document.title,
      markerVisible: document.body?.innerText.includes("JA_BROWSER_FILE_HTML_CONTENT") === true,
      pngDecoded: png instanceof HTMLImageElement && png.complete && png.naturalWidth === 1,
      svgDecoded: svg instanceof HTMLImageElement && svg.complete && svg.naturalWidth === 320,
      relativePdfSource:
        pdf instanceof HTMLIFrameElement && pdf.getAttribute("src") === "./阅读 指南.pdf",
    };
  });
}

/** 在 true Files editor wrapper 内读取只读标志和文字；不从文件路径本身推断权限结果。 */
async function inspectFileDocument(
  page,
  expectedPath,
  marker,
  expectedReadOnly,
  deadline,
  workspaceRoot,
  expectedReveal,
) {
  const targetPath = canonicalFileDocumentPath(expectedPath, workspaceRoot);
  const wrapperDeadline = Math.min(deadline, Date.now() + 8_000);
  let wrapper;
  let lastCandidates = [];
  try {
    await waitForCondition(
      "file document wrapper",
      async () => {
        const wrappers = page.locator("[data-document-path][data-document-read-only]");
        lastCandidates = await wrappers.evaluateAll((nodes) =>
          nodes.map((node) => ({
            path: node.getAttribute("data-document-path") ?? "",
            readOnly: node.getAttribute("data-document-read-only"),
            truncated: node.getAttribute("data-document-truncated"),
            text: node.textContent ?? "",
          })),
        );
        const index = lastCandidates.findIndex(
          ({ path, readOnly, text }) =>
            canonicalFileDocumentPath(path, workspaceRoot) === targetPath &&
            readOnly === String(expectedReadOnly) &&
            text.includes(marker),
        );
        if (index < 0) return false;
        wrapper = wrappers.nth(index);
        return true;
      },
      wrapperDeadline,
    );
  } catch (error) {
    const observed = lastCandidates.map(({ path, readOnly }) => ({ path, readOnly }));
    throw new Error(
      `${String(error?.message ?? error)}; expected=${relative(workspaceRoot, expectedPath) || expectedPath}; observed=${JSON.stringify(observed)}`,
    );
  }
  const facts = await wrapper.evaluate((node) => {
    const active = document.activeElement;
    const content = node.querySelector(".cm-content");
    const activeLine = content?.querySelector(".cm-line.cm-activeLine");
    const gutterLine = node.querySelector(".cm-gutterElement.cm-activeLineGutter");
    const renderedLines = content === null ? [] : Array.from(content.querySelectorAll(".cm-line"));
    const selection = document.getSelection();
    let cursorColumn;
    if (
      activeLine !== null &&
      selection?.anchorNode !== null &&
      selection?.anchorNode !== undefined
    ) {
      if (activeLine.contains(selection.anchorNode)) {
        const range = document.createRange();
        range.selectNodeContents(activeLine);
        range.setEnd(selection.anchorNode, selection.anchorOffset);
        cursorColumn = range.toString().length + 1;
      }
    }
    const gutterNumber = Number.parseInt(gutterLine?.textContent?.trim() ?? "", 10);
    return {
      path: node.getAttribute("data-document-path"),
      readOnly: node.getAttribute("data-document-read-only") === "true",
      truncated: node.getAttribute("data-document-truncated"),
      contentVisible: node.textContent?.trim().length > 0,
      editorFocused: active !== null && node.contains(active),
      editorPosition: {
        activeLine:
          Number.isInteger(gutterNumber) && gutterNumber > 0
            ? gutterNumber
            : activeLine === null
              ? undefined
              : renderedLines.indexOf(activeLine) + 1,
        activeLineText: activeLine?.textContent?.trim(),
        cursorColumn,
      },
    };
  });
  if (expectedReveal !== undefined) {
    const expectedColumn = expectedReveal.column ?? 1;
    assert.equal(
      facts.editorPosition.activeLine,
      expectedReveal.line,
      `CodeMirror active line did not follow the file reference: ${JSON.stringify(facts.editorPosition)}`,
    );
    assert.equal(
      facts.editorPosition.cursorColumn,
      expectedColumn,
      `CodeMirror cursor column did not follow the file reference: ${JSON.stringify(facts.editorPosition)}`,
    );
  }
  const fileTabPaths = await page
    .locator("[data-file-tab-path]")
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute("data-file-tab-path") ?? ""));
  const tabFound = fileTabPaths.some(
    (path) => canonicalFileDocumentPath(path, workspaceRoot) === targetPath,
  );
  assert.equal(tabFound, true, "file path must become a Files tab");
  if (expectedReadOnly) {
    assert.equal(facts.truncated, "false", "small fixture must not be truncated");
    const externalTabPaths = await page
      .locator("[data-file-tab-path][data-external-file='true']")
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute("data-file-tab-path") ?? ""));
    assert.ok(
      externalTabPaths.some(
        (path) => canonicalFileDocumentPath(path, workspaceRoot) === targetPath,
      ),
      "outside-workspace file tab must be marked external",
    );
  }
  return { ...facts, fileTabCount: await page.locator("[data-file-tab-path]").count() };
}

/** 切换到指定原生 page identity，随后从唯一 active panel 读取该页地址和 history 投影。 */
async function selectBrowserPageById(
  workbench,
  pageId,
  deadline,
  pageErrors = [],
  evidenceDirectory,
) {
  assert.ok(pageId, "browser workbench must retain target page identity");
  const tab = workbench
    .getByRole("tablist", { name: "浏览器页面", exact: true })
    .locator(`[role="tab"][data-preview-page-id="${pageId}"]`);
  await tab.click({ timeout: Math.max(1, deadline - Date.now()) });
  await waitForCondition(
    "selected browser tab",
    async () => (await tab.getAttribute("aria-selected")) === "true",
    deadline,
  );
  await waitForCondition(
    "active Browser page identity",
    async () => (await browserPageFacts(workbench)).some(({ id }) => id === pageId),
    deadline,
  );
  await assertNoPreviewFailure(
    workbench,
    workbench.page(),
    "browser tab selection",
    pageErrors,
    evidenceDirectory,
  );
  return pageId;
}

/** 在新建 Browser tab 中提交地址，并等待页面 projection 和 child WebView 均已转到目标 URL。 */
async function navigateAddress(
  workbench,
  page,
  targetUrl,
  deadline,
  pageErrors = [],
  evidenceDirectory,
) {
  const before = (await browserTabFacts(workbench)).length;
  await workbench.getByRole("button", { name: "新建浏览器标签", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForCondition(
    "new browser tab creation",
    async () => (await browserTabFacts(workbench)).length === before + 1,
    deadline,
  );
  const address = workbench.getByRole("textbox", { name: "浏览器地址", exact: true });
  await address.fill(targetUrl);
  const addressFocused = await address.evaluate((node) => document.activeElement === node);
  assert.equal(
    addressFocused,
    true,
    "address entry should retain keyboard focus before navigation",
  );
  await workbench.getByRole("button", { name: "访问地址", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  const tab = await waitForActiveBrowserUrl(workbench, targetUrl, deadline);
  const child = await waitForChildWebView(page, targetUrl, deadline);
  await assertNoPreviewFailure(
    workbench,
    page,
    "address navigation",
    pageErrors,
    evidenceDirectory,
  );
  return { tab, child, addressFocused };
}

/**
 * 在两个 Thread 内真实创建回复与文件，检验路径引用、文件内容路由、多页导航/history、
 * session 隔离和 close ACK；所有浏览器页面事实均与实际 child WebView 的 document 交叉核对。
 */
export async function runBrowserFileLinksWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  isolatedRuntimeHome,
  fixture,
  verifyExplorerFolder = false,
}) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "workspaceRoot is required");
  assert.ok(fixture, "fixture is required");
  activeBrowserEvidenceDirectory = evidenceDirectory;
  activeBrowserRuntimeHome = isolatedRuntimeHome;
  activeBrowserFileOpenAcknowledgements = [];
  await mkdir(evidenceDirectory, { recursive: true });
  const files = await writeBrowserFileFixture(workspaceRoot);
  fixture.setReply(buildAssistantFileReply(files));
  const deadline = Date.now() + 10 * 60_000;
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  page.on("console", (message) => {
    if (message.type() === "error") pageErrors.push(message.text().slice(0, 500));
  });
  await page.context().addInitScript(installBrowserFileInvokeProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: Math.max(1, deadline - Date.now()) });
  await prepareApplication(page, deadline);
  const threadA = await createThread(page, files.workspace, "文件浏览器验收 A");
  const threadB = await createThread(page, files.workspace, "文件浏览器验收 B");
  assert.notEqual(threadA.threadId, threadB.threadId);
  await page.reload({ waitUntil: "domcontentloaded", timeout: Math.max(1, deadline - Date.now()) });
  await prepareApplication(page, deadline);
  await page
    .locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadA.threadId}"]`)
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await page
    .locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadB.threadId}"]`)
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });

  await selectThread(page, threadA.threadId, deadline);
  await page.setViewportSize({ width: 1480, height: 960 });
  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(PROMPT);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  const userMessage = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: PROMPT })
    .last();
  await userMessage.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  // Timeline 会在 ACK、标题更新和终态提交时重排虚拟行；以本轮唯一 fixture 正文定位最终答复，
  // 不把仍在变化的 USER 节点祖先当作长期稳定的 Playwright 查询根。
  const assistant = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: files.relativeHtmlPath })
    .last();
  try {
    await assistant.waitFor({
      state: "visible",
      timeout: Math.min(60_000, Math.max(1, deadline - Date.now())),
    });
  } catch (error) {
    await page
      .screenshot({
        path: join(evidenceDirectory, "00-assistant-timeout.png"),
        animations: "disabled",
      })
      .catch(() => undefined);
    const responseStates = await page
      .locator(".ja-chat-message-final[data-response-state]")
      .evaluateAll((nodes) =>
        nodes.map((node) => ({
          state: node.getAttribute("data-response-state"),
          text: (node.textContent ?? "").slice(0, 160),
        })),
      );
    throw new Error(`fixture final reply was not visible: ${JSON.stringify(responseStates)}`, {
      cause: error,
    });
  }
  await waitForCondition(
    "fixture assistant turn",
    () => fixture.snapshot().acceptanceTurnCount > 0,
    deadline,
  );
  const visibleReply = (await assistant.innerText()).trim();
  assert.ok(visibleReply.includes(files.relativeHtmlPath));
  const preClickFileResolveCount = countCommandPrefix(
    await browserCommandTrace(page),
    "ja_preview_resolve_file",
    "start",
  );
  assert.equal(
    preClickFileResolveCount,
    0,
    "rendering a message must not resolve referenced files",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "01-assistant-path-references.png"),
    animations: "disabled",
  });

  const markdownHtmlReference = await openFileReference(
    page,
    assistant,
    files,
    files.htmlPath,
    "preview",
    deadline,
  );
  let workbench = await ensureBrowserCapability(page, deadline);
  await waitForActiveBrowserUrl(workbench, files.htmlUrl, deadline);
  let htmlTab = (await browserTabFacts(workbench)).find(({ selected }) => selected);
  assert.ok(htmlTab?.id);
  let htmlChild = await waitForChildWebView(page, files.htmlUrl, deadline);
  const htmlFacts = await inspectHtmlChild(htmlChild, deadline);
  assert.equal(htmlFacts.markerVisible, true);
  assert.equal(htmlFacts.pngDecoded, true);
  assert.equal(htmlFacts.svgDecoded, true);
  assert.equal(htmlFacts.relativePdfSource, true);
  await page.screenshot({
    path: join(evidenceDirectory, "02-html-local-child-webview.png"),
    animations: "disabled",
  });

  const relativeHtmlReference = await openFileReference(
    page,
    assistant,
    files,
    files.htmlPath,
    "preview",
    deadline,
    undefined,
    files.relativeHtmlPath,
  );
  assert.match(relativeHtmlReference.reference.accessibleName, /在 Ja 中打开文件/u);
  workbench = await ensureBrowserCapability(page, deadline);
  await waitForActiveBrowserUrl(workbench, files.htmlUrl, deadline);
  htmlTab = (await browserTabFacts(workbench)).find(({ selected }) => selected);
  assert.ok(htmlTab?.id);
  htmlChild = await waitForChildWebView(page, files.htmlUrl, deadline);
  assert.equal((await inspectHtmlChild(htmlChild, deadline)).markerVisible, true);
  const missingFileEntry = await fileReference(
    page,
    assistant,
    files.missingPath,
    files.workspace,
    undefined,
    files.relativeMissingPath,
  );
  const browserTabsBeforeMissing = await browserTabFacts(workbench);
  const browserPageProjectionBeforeMissing = await browserPageFacts(workbench);
  const browserPagesBeforeMissing = browserTabsBeforeMissing.map(({ id }) => id);
  const nativeTargetsBeforeMissing = await Promise.all(
    page
      .context()
      .pages()
      .filter((candidate) => candidate !== page)
      .map(async (candidate) => ({
        url: candidate.url(),
        title: await candidate.title().catch(() => ""),
      })),
  );
  const traceBeforeMissing = await browserCommandTrace(page);
  const resolveStartsBeforeMissing = countCommandPrefix(
    traceBeforeMissing,
    "ja_preview_resolve_file",
    "start",
  );
  const missingReferenceClickAt = Date.now();
  await missingFileEntry.locator.click({ timeout: Math.max(1, deadline - Date.now()) });
  await page.getByText("文件不存在或已被移动。", { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  const missingFileMessageRetained = await assistant.isVisible();
  const missingFileFocusRetained = await missingFileEntry.locator.evaluate(
    (node) => document.activeElement === node,
  );
  const browserTabsAfterMissing = await browserTabFacts(workbench);
  const browserPageProjectionAfterMissing = await browserPageFacts(workbench);
  const browserPagesAfterMissing = browserTabsAfterMissing.map(({ id }) => id);
  const nativeTargetsAfterMissing = await Promise.all(
    page
      .context()
      .pages()
      .filter((candidate) => candidate !== page)
      .map(async (candidate) => ({
        url: candidate.url(),
        title: await candidate.title().catch(() => ""),
      })),
  );
  const traceAfterMissing = await browserCommandTrace(page);
  const missingClickDelta = traceAfterMissing.slice(traceBeforeMissing.length);
  const missingClickCallIds = new Set(
    missingClickDelta.filter(({ phase }) => phase === "start").map(({ callId }) => callId),
  );
  const missingClickNativeCommands = missingClickDelta.filter(({ callId }) =>
    missingClickCallIds.has(callId),
  );
  const missingClickPageCommands = missingClickNativeCommands.filter(({ command }) =>
    [
      "ja_preview_open",
      "ja_preview_open_blank",
      "ja_preview_open_file",
      "ja_preview_navigate",
      "ja_preview_navigate_file",
    ].includes(command),
  );
  const newNativeTargets = nativeTargetsAfterMissing.filter(
    ({ url, title }) =>
      !nativeTargetsBeforeMissing.some(
        (candidate) => candidate.url === url && candidate.title === title,
      ),
  );
  const missingFileResolveRejected = missingClickNativeCommands.some(
    ({ command, phase, targetBasename, errorCode }) =>
      command === "ja_preview_resolve_file" &&
      phase === "rejected" &&
      targetBasename === "移动后 文件.ts" &&
      errorCode === "FileNotFound",
  );
  const missingFileChildTargetCreated = newNativeTargets.some(
    ({ url }) => normalizedFilePath(url) === normalizedFilePath(files.missingPath),
  );
  const missingPathNoNativePageOpened =
    missingClickPageCommands.length === 0 && !missingFileChildTargetCreated;
  const browserTabsProjectionChanged =
    JSON.stringify(browserPagesAfterMissing) !== JSON.stringify(browserPagesBeforeMissing);
  if (
    browserTabsProjectionChanged ||
    newNativeTargets.length > 0 ||
    missingClickPageCommands.length > 0
  ) {
    await writeFile(
      join(evidenceDirectory, "missing-file-page-diff.json"),
      `${JSON.stringify(
        {
          missingReferenceClickAt,
          browserTabsBeforeMissing,
          browserPageProjectionBeforeMissing,
          browserTabsAfterMissing,
          browserPageProjectionAfterMissing,
          nativeTargetsBeforeMissing,
          nativeTargetsAfterMissing,
          missingClickNativeCommands,
          missingClickPageCommands,
          missingPathNoNativePageOpened,
          missingFileResolveRejected,
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
  }
  const missingFileResolveAttempted =
    countCommandPrefix(traceAfterMissing, "ja_preview_resolve_file", "start") >
    resolveStartsBeforeMissing;
  assert.equal(missingFileMessageRetained, true);
  assert.equal(missingFileFocusRetained, true);
  assert.equal(
    missingFileResolveRejected,
    true,
    "missing path must fail in the resolver before opening",
  );
  assert.equal(
    missingPathNoNativePageOpened,
    true,
    `missing-file native page create diff: ${JSON.stringify({
      clickAt: missingReferenceClickAt,
      beforeIds: browserPagesBeforeMissing,
      afterIds: browserPagesAfterMissing,
      beforePage: browserPageProjectionBeforeMissing,
      afterPage: browserPageProjectionAfterMissing,
      newTargetTitles: newNativeTargets.map(({ title }) => title),
      commands: missingClickNativeCommands.map(
        ({
          command,
          callId,
          phase,
          sessionId,
          generation,
          targetKind,
          targetBasename,
          errorCode,
        }) => ({
          command,
          callId,
          phase,
          sessionId,
          generation,
          targetKind,
          targetBasename,
          errorCode,
        }),
      ),
    })}`,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "03-missing-file-feedback.png"),
    animations: "disabled",
  });
  assert.equal(missingFileResolveAttempted, true);
  // 先收起右栏，证明 Ctrl+点击仍可触达真实 Tauri command 且不弹系统窗口干扰桌面。
  const revealTraceBefore = await browserCommandTrace(page);
  const revealTabsBefore = await browserTabFacts(workbench);
  await page.getByRole("button", { name: "收起右侧栏" }).click();
  await page.getByRole("button", { name: "显示工作区面板" }).waitFor({ state: "visible" });
  await missingFileEntry.locator.click({
    modifiers: ["Control"],
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForCondition(
    "native Explorer reveal rejection for missing file",
    async () =>
      (await browserCommandTrace(page))
        .slice(revealTraceBefore.length)
        .some(
          ({ command, phase, errorCode }) =>
            command === "ja_preview_reveal_file" &&
            phase === "rejected" &&
            errorCode === "FileNotFound",
        ),
    deadline,
  );
  assert.equal(await assistant.isVisible(), true);
  assert.equal(
    await missingFileEntry.locator.evaluate((node) => document.activeElement === node),
    true,
    "failed Ctrl+click must restore focus to the file reference",
  );
  assert.deepEqual(await browserTabFacts(workbench, false), revealTabsBefore);
  const explorerKeptPanelHidden = await page
    .getByRole("button", { name: "显示工作区面板" })
    .isVisible();
  if (!explorerKeptPanelHidden) {
    await page.screenshot({
      path: join(evidenceDirectory, "03b-ctrl-click-panel-state.png"),
      animations: "disabled",
    });
    const shell = await page.locator(".ja-layout").evaluate((node) => ({
      className: node.className,
      panelHidden: document.querySelector("#workbench")?.hasAttribute("hidden"),
      focused: document.activeElement?.outerHTML.slice(0, 300),
    }));
    throw new Error(
      `Ctrl+click reopened right panel: ${JSON.stringify({
        shell,
        commands: (await browserCommandTrace(page)).slice(revealTraceBefore.length),
      })}`,
    );
  }
  // 与鼠标修饰键走同一原生边界；失败后维持引用焦点与收起状态。
  assert.equal(await missingFileEntry.locator.getAttribute("aria-keyshortcuts"), "Control+Enter");
  const keyboardRevealTraceBefore = (await browserCommandTrace(page)).length;
  await missingFileEntry.locator.press("Control+Enter");
  await waitForCondition(
    "keyboard Explorer reveal rejection for missing file",
    async () =>
      (await browserCommandTrace(page)).slice(keyboardRevealTraceBefore).some(
        ({ command, phase, errorCode }) =>
          command === "ja_preview_reveal_file" &&
          phase === "rejected" &&
          errorCode === "FileNotFound",
      ),
    deadline,
  );
  assert.equal(
    await missingFileEntry.locator.evaluate((node) => document.activeElement === node),
    true,
  );
  assert.equal(await page.getByRole("button", { name: "显示工作区面板" }).isVisible(), true);
  await page.screenshot({
    path: join(evidenceDirectory, "03b-explorer-keyboard-feedback.png"),
    animations: "disabled",
  });
  // 重名 Explorer 意图必须说清楚将显示哪个文件；取消不得打开系统窗口或右栏。
  const duplicateExplorerEntry = assistant
    .locator('button.ja-markdown__file-link[data-file-reference="重复 名称.txt"]')
    .first();
  await duplicateExplorerEntry.click({
    modifiers: ["Control"],
    timeout: Math.max(1, deadline - Date.now()),
  });
  const explorerChoice = page.getByRole("dialog", {
    name: "选择要在文件夹中定位的文件",
    exact: true,
  });
  await explorerChoice.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await page.getByText("文件不存在或已被移动。", { exact: true }).waitFor({
    state: "hidden",
    timeout: Math.max(1, deadline - Date.now()),
  });
  await page.screenshot({
    path: join(evidenceDirectory, "03c-explorer-duplicate-choice.png"),
    animations: "disabled",
  });
  await explorerChoice.getByRole("button", { name: "取消" }).click();
  await explorerChoice.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
  assert.equal(await page.getByRole("button", { name: "显示工作区面板" }).isVisible(), true);
  let explorerFolderVerified = false;
  if (verifyExplorerFolder) {
    const sourceEntry = await fileReference(
      page,
      assistant,
      files.sourcePath,
      files.workspace,
      2,
    );
    const existingWindows = await explorerTargetWindows(files.sourcePath);
    assert.deepEqual(existingWindows, [], "isolated fixture folder was already open in Explorer");
    const explorerBaseline = await explorerAllWindows();
    const revealTraceStart = (await browserCommandTrace(page)).length;
    await sourceEntry.locator.click({
      modifiers: ["Control"],
      timeout: Math.max(1, deadline - Date.now()),
    });
    await waitForCondition(
      "native Explorer folder reveal acknowledgement",
      async () =>
        (await browserCommandTrace(page)).slice(revealTraceStart).some(
          ({ command, phase }) => command === "ja_preview_reveal_file" && phase === "resolved",
        ),
      deadline,
    );
    let selectedWindow;
    try {
      await waitForCondition(
        "Explorer parent folder with selected source file",
        async () => {
          const windows = await explorerTargetWindows(files.sourcePath);
          selectedWindow = windows.find(({ selected }) =>
            (Array.isArray(selected) ? selected : []).some(
              (item) => normalizedFilePath(item) === normalizedFilePath(files.sourcePath),
            ),
          );
          return selectedWindow !== undefined;
        },
        Math.min(deadline, Date.now() + 12_000),
      );
      explorerFolderVerified = true;
    } catch (error) {
      const laterWindows = await explorerAllWindows();
      const openedWindows = laterWindows.filter(
        ({ hwnd }) => !explorerBaseline.some((baseline) => baseline.hwnd === hwnd),
      );
      const revealTrace = (await browserCommandTrace(page)).slice(revealTraceStart);
      throw new Error(
        `${String(error?.message ?? error)}; explorer=${JSON.stringify(openedWindows)}; native=${JSON.stringify(revealTrace)}`,
      );
    } finally {
      for (const window of await explorerTargetWindows(files.sourcePath)) {
        if (!existingWindows.some(({ hwnd }) => hwnd === window.hwnd)) {
          await closeExplorerTargetWindow(files.sourcePath, window.hwnd);
        }
      }
    }
    assert.equal(explorerFolderVerified, true);
    assert.equal(await page.getByRole("button", { name: "显示工作区面板" }).isVisible(), true);
  }
  const sourceReference = await openFileReference(
    page,
    assistant,
    files,
    files.sourcePath,
    "files",
    deadline,
    2,
  );
  const sourceFacts = await inspectFileDocument(
    page,
    files.sourcePath,
    "export const second = 2;",
    false,
    deadline,
    files.workspace,
    { line: 2, column: 4 },
  );
  assert.match(sourceReference.reference.reference, /#L2C4$/u);
  assert.equal(
    sourceFacts.editorFocused,
    true,
    "source file opening should place focus in the file viewer",
  );
  const legacyLineColumnReference = await openFileReference(
    page,
    assistant,
    files,
    files.sourcePath,
    "files",
    deadline,
    2,
    `${files.relativeSourcePath}:2:4`,
  );
  assert.match(legacyLineColumnReference.reference.reference, /:2:4$/u);
  const legacySourceFacts = await inspectFileDocument(
    page,
    files.sourcePath,
    "export const second = 2;",
    false,
    deadline,
    files.workspace,
    { line: 2, column: 4 },
  );
  const lineOnlyReference = await openFileReference(
    page,
    assistant,
    files,
    files.sourcePath,
    "files",
    deadline,
    1,
    `${files.relativeSourcePath}#L1`,
  );
  assert.match(lineOnlyReference.reference.reference, /#L1$/u);
  const lineOnlySourceFacts = await inspectFileDocument(
    page,
    files.sourcePath,
    "export const second = 2;",
    false,
    deadline,
    files.workspace,
    { line: 1 },
  );
  await page.screenshot({
    path: join(evidenceDirectory, "04-code-path-line-column.png"),
    animations: "disabled",
  });

  const workspaceTextReference = await openFileReference(
    page,
    assistant,
    files,
    files.textPath,
    "files",
    deadline,
  );
  const workspaceTextFacts = await inspectFileDocument(
    page,
    files.textPath,
    FIXTURE_TEXT_MARKER,
    false,
    deadline,
    files.workspace,
  );
  assert.ok(workspaceTextReference.reference.reference.length > 0);
  await page.screenshot({
    path: join(evidenceDirectory, "05-workspace-text-file.png"),
    animations: "disabled",
  });

  const duplicateFacts = await openAmbiguousFileReference(page, assistant, files, deadline);
  assert.equal(
    canonicalFileDocumentPath(duplicateFacts.path ?? "", files.workspace),
    canonicalFileDocumentPath(files.duplicateAPath, files.workspace),
  );
  await page.screenshot({
    path: join(evidenceDirectory, "06-duplicate-file-choice.png"),
    animations: "disabled",
  });

  const externalTextReference = await openFileReference(
    page,
    assistant,
    files,
    files.externalPath,
    "files",
    deadline,
    undefined,
    files.externalPath,
  );
  const windowsAbsoluteFileFacts = await inspectFileDocument(
    page,
    files.externalPath,
    FIXTURE_TEXT_MARKER,
    true,
    deadline,
    files.workspace,
  );
  assert.ok(externalTextReference.reference.reference.length > 0);
  await page.screenshot({
    path: join(evidenceDirectory, "07-external-text-read-only.png"),
    animations: "disabled",
  });
  const externalFileUrlReference = await openFileReference(
    page,
    assistant,
    files,
    files.externalPath,
    "files",
    deadline,
    undefined,
    files.externalUrl,
  );
  const externalTextFacts = await inspectFileDocument(
    page,
    files.externalPath,
    FIXTURE_TEXT_MARKER,
    true,
    deadline,
    files.workspace,
  );
  assert.equal(externalFileUrlReference.reference.visibleName, "打开只读说明");

  await openFileReference(page, assistant, files, files.imagePath, "preview", deadline);
  workbench = await ensureBrowserCapability(page, deadline);
  await waitForActiveBrowserUrl(workbench, files.imageUrl, deadline);
  const imageChild = await waitForChildWebView(page, files.imageUrl, deadline);
  await imageChild.waitForFunction(
    () => Array.from(document.images).some((image) => image.complete && image.naturalWidth === 1),
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  const imageFacts = await imageChild.evaluate(() => ({
    contentType: document.contentType,
    imageDecoded: Array.from(document.images).some(
      (image) => image.complete && image.naturalWidth === 1,
    ),
  }));
  assert.equal(imageFacts.imageDecoded, true);
  await page.screenshot({
    path: join(evidenceDirectory, "08-image-browser-page.png"),
    animations: "disabled",
  });
  const visibleLayoutBeforeResize = countLayoutVisibility(await browserCommandTrace(page), true);
  const originalImageChildWidth = await imageChild.evaluate(() => window.innerWidth);
  await page.setViewportSize({ width: 1120, height: 760 });
  await waitForCondition(
    "native Browser layout after window resize",
    async () =>
      countLayoutVisibility(await browserCommandTrace(page), true) > visibleLayoutBeforeResize,
    deadline,
  );
  const selectedImageTab = (await browserTabFacts(workbench)).find(({ selected }) => selected);
  assert.ok(selectedImageTab?.id, "selected image browser tab must retain its page identity");
  const resizedBrowserBounds = await activeBrowserPanel(workbench, selectedImageTab?.id).then(
    (panel) => panel.boundingBox(),
  );
  assert.ok(resizedBrowserBounds?.width > 0 && resizedBrowserBounds?.height > 0);
  await waitForCondition(
    "native child WebView resized with its parent window",
    async () => (await imageChild.evaluate(() => window.innerWidth)) < originalImageChildWidth,
    deadline,
  );
  const resizedChildWidth = await imageChild.evaluate(() => window.innerWidth);
  await page.screenshot({
    path: join(evidenceDirectory, "08-image-browser-narrow.png"),
    animations: "disabled",
  });
  const visibleLayoutBeforeRestore = countLayoutVisibility(await browserCommandTrace(page), true);
  await page.setViewportSize({ width: 1480, height: 960 });
  await waitForCondition(
    "native Browser layout after restoring window size",
    async () =>
      countLayoutVisibility(await browserCommandTrace(page), true) > visibleLayoutBeforeRestore,
    deadline,
  );
  await waitForCondition(
    "native child WebView restored with its parent window",
    async () => (await imageChild.evaluate(() => window.innerWidth)) >= originalImageChildWidth,
    deadline,
  );
  const restoredChildWidth = await imageChild.evaluate(() => window.innerWidth);

  await openFileReference(
    page,
    assistant,
    files,
    files.svgPath,
    "preview",
    deadline,
  );
  workbench = await ensureBrowserCapability(page, deadline);
  await waitForActiveBrowserUrl(workbench, files.svgUrl, deadline);
  const svgChild = await waitForChildWebView(page, files.svgUrl, deadline);
  await svgChild.waitForFunction(
    (marker) =>
      document.querySelector("svg title")?.textContent?.includes(marker) === true ||
      document.body?.innerText.includes(marker) === true,
    FIXTURE_SVG_MARKER,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  const svgFacts = await svgChild.evaluate(() => ({
    title: document.title,
    markerVisible:
      document.querySelector("svg title")?.textContent?.includes("JA_BROWSER_FILE_SVG_CONTENT") ===
        true || document.body?.innerText.includes("JA_BROWSER_FILE_SVG_CONTENT") === true,
    svgDocument: document.querySelector("svg") !== null,
  }));
  assert.equal(svgFacts.markerVisible, true);
  assert.equal(svgFacts.svgDocument, true);
  const svgTabFacts = await browserTabFacts(workbench);
  assert.ok(
    svgTabFacts.length >= 2,
    "opening multiple HTML/SVG targets must keep multiple browser pages",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "09-svg-browser-page.png"),
    animations: "disabled",
  });

  await openFileReference(
    page,
    assistant,
    files,
    files.pdfPath,
    "preview",
    deadline,
  );
  workbench = await ensureBrowserCapability(page, deadline);
  await waitForActiveBrowserUrl(workbench, files.pdfUrl, deadline);
  const pdfChild = await waitForChildWebView(page, files.pdfUrl, deadline);
  await waitForCondition(
    "native PDF viewer document",
    async () =>
      pdfChild.evaluate(
        () =>
          document.contentType === "application/pdf" ||
          document.querySelector(
            "pdf-viewer, embed[type='application/pdf'], object[type='application/pdf']",
          ) !== null ||
          document.body?.innerText.includes("JA_BROWSER_FILE_PDF_CONTENT") === true,
      ),
    deadline,
  );
  const pdfFacts = await pdfChild.evaluate(() => ({
    title: document.title,
    contentType: document.contentType,
    viewerDetected:
      document.contentType === "application/pdf" ||
      document.querySelector(
        "pdf-viewer, embed[type='application/pdf'], object[type='application/pdf']",
      ) !== null ||
      document.body?.innerText.includes("JA_BROWSER_FILE_PDF_CONTENT") === true,
  }));
  assert.equal(pdfFacts.viewerDetected, true);
  await page.screenshot({
    path: join(evidenceDirectory, "10-pdf-browser-tab.png"),
    animations: "disabled",
  });

  const htmlPageId = await selectBrowserPageById(
    workbench,
    htmlTab.id,
    deadline,
    pageErrors,
    evidenceDirectory,
  );
  await waitForActiveBrowserUrl(workbench, files.htmlUrl, deadline);
  const browserUrlBeforeHistory = await browserPageFacts(workbench);
  assert.equal(browserUrlBeforeHistory.find(({ id }) => id === htmlPageId)?.canGoBack, "false");
  const address = workbench.getByRole("textbox", { name: "浏览器地址", exact: true });
  await address.fill(files.nextUrl);
  await workbench.getByRole("button", { name: "访问地址", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForActiveBrowserUrl(workbench, files.nextUrl, deadline);
  let historyChild = await waitForChildWebView(page, files.nextUrl, deadline);
  await historyChild.getByText(FIXTURE_NEXT_MARKER, { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForCondition(
    "native back history state",
    async () =>
      (await browserPageFacts(workbench)).find(({ id }) => id === htmlPageId)?.canGoBack === "true",
    deadline,
  );
  await workbench.getByRole("button", { name: "后退", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForActiveBrowserUrl(workbench, files.htmlUrl, deadline);
  htmlChild = await waitForChildWebView(page, files.htmlUrl, deadline);
  assert.equal((await inspectHtmlChild(htmlChild, deadline)).markerVisible, true);
  await waitForCondition(
    "native forward history state",
    async () =>
      (await browserPageFacts(workbench)).find(({ id }) => id === htmlPageId)?.canGoForward ===
      "true",
    deadline,
  );
  await workbench.getByRole("button", { name: "前进", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForActiveBrowserUrl(workbench, files.nextUrl, deadline);
  historyChild = await waitForChildWebView(page, files.nextUrl, deadline);
  await historyChild.getByText(FIXTURE_NEXT_MARKER, { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  const reloadBefore = countCommandPrefix(
    await browserCommandTrace(page),
    "ja_preview_reload",
    "resolved",
  );
  await workbench.getByRole("button", { name: "刷新页面", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForCondition(
    "native reload ACK",
    async () =>
      countCommandPrefix(await browserCommandTrace(page), "ja_preview_reload", "resolved") >
      reloadBefore,
    deadline,
  );
  await historyChild.waitForLoadState("load", { timeout: Math.max(1, deadline - Date.now()) });
  await historyChild.getByText(FIXTURE_NEXT_MARKER, { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  const historyFacts = {
    pageId: htmlPageId,
    backResolved:
      countCommandPrefix(await browserCommandTrace(page), "ja_preview_go_back", "resolved") > 0,
    forwardResolved:
      countCommandPrefix(await browserCommandTrace(page), "ja_preview_go_forward", "resolved") > 0,
    reloadResolved:
      countCommandPrefix(await browserCommandTrace(page), "ja_preview_reload", "resolved") >
      reloadBefore,
    childContentsObserved: true,
  };
  assert.equal(
    historyFacts.backResolved && historyFacts.forwardResolved && historyFacts.reloadResolved,
    true,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "11-browser-history-after-forward.png"),
    animations: "disabled",
  });

  const crossSchemeAddress = workbench.getByRole("textbox", { name: "浏览器地址", exact: true });
  await crossSchemeAddress.fill(fixture.historyUrl);
  await workbench.getByRole("button", { name: "访问地址", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForActiveBrowserUrl(workbench, fixture.historyUrl, deadline);
  const httpHistoryChild = await waitForChildWebView(page, fixture.historyUrl, deadline);
  await httpHistoryChild.getByText(FIXTURE_HTTP_HISTORY_MARKER, { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  const crossSchemeBackBefore = countCommandPrefix(
    await browserCommandTrace(page),
    "ja_preview_go_back",
    "resolved",
  );
  await crossSchemeAddress.fill(files.nextUrl);
  await workbench.getByRole("button", { name: "访问地址", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForActiveBrowserUrl(workbench, files.nextUrl, deadline);
  historyChild = await waitForChildWebView(page, files.nextUrl, deadline);
  await historyChild.getByText(FIXTURE_NEXT_MARKER, { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForCondition(
    "cross-scheme file history back availability",
    async () =>
      (await browserPageFacts(workbench)).find(({ id }) => id === htmlPageId)?.canGoBack === "true",
    deadline,
  );
  await workbench.getByRole("button", { name: "后退", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForActiveBrowserUrl(workbench, fixture.historyUrl, deadline);
  const crossSchemeHttpChildAfterBack = await waitForChildWebView(
    page,
    fixture.historyUrl,
    deadline,
  );
  await crossSchemeHttpChildAfterBack
    .getByText(FIXTURE_HTTP_HISTORY_MARKER, { exact: true })
    .waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
  const crossSchemeForwardBefore = countCommandPrefix(
    await browserCommandTrace(page),
    "ja_preview_go_forward",
    "resolved",
  );
  await waitForCondition(
    "cross-scheme HTTP history forward availability",
    async () =>
      (await browserPageFacts(workbench)).find(({ id }) => id === htmlPageId)?.canGoForward ===
      "true",
    deadline,
  );
  await workbench.getByRole("button", { name: "前进", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForActiveBrowserUrl(workbench, files.nextUrl, deadline);
  historyChild = await waitForChildWebView(page, files.nextUrl, deadline);
  await historyChild.getByText(FIXTURE_NEXT_MARKER, { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  const crossSchemeHistoryFacts = {
    samePageId: (await browserPageFacts(workbench)).some(({ id }) => id === htmlPageId),
    httpDocumentObserved: true,
    fileDocumentObserved: true,
    backResolved:
      countCommandPrefix(await browserCommandTrace(page), "ja_preview_go_back", "resolved") >
      crossSchemeBackBefore,
    forwardResolved:
      countCommandPrefix(await browserCommandTrace(page), "ja_preview_go_forward", "resolved") >
      crossSchemeForwardBefore,
    forwardRestoredLocalDocument: (await browserPageFacts(workbench)).some(
      ({ id, url }) => id === htmlPageId && url === files.nextUrl,
    ),
  };
  assert.equal(
    crossSchemeHistoryFacts.samePageId &&
      crossSchemeHistoryFacts.backResolved &&
      crossSchemeHistoryFacts.forwardResolved &&
      crossSchemeHistoryFacts.forwardRestoredLocalDocument,
    true,
    "HTTP → file → Back(HTTP) → Forward(file) must use one native page history",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "12-cross-scheme-forward-file.png"),
    animations: "disabled",
  });

  const aTabs = await browserTabFacts(workbench);
  const aPageIds = aTabs.map(({ id }) => id);
  const inactiveLayoutBefore = countLayoutVisibility(await browserCommandTrace(page), false);
  await selectThread(page, threadB.threadId, deadline);
  const hiddenHosts = page.locator(
    '.ja-thread-workbench-session[hidden][inert][aria-hidden="true"]',
  );
  await hiddenHosts
    .first()
    .waitFor({ state: "attached", timeout: Math.max(1, deadline - Date.now()) });
  await waitForCondition(
    "A child WebViews are hidden on B switch",
    async () =>
      countLayoutVisibility(await browserCommandTrace(page), false) > inactiveLayoutBefore,
    deadline,
  );
  workbench = await ensureBrowserCapability(page, deadline);
  const bOpened = await navigateAddress(
    workbench,
    page,
    files.threadBUrl,
    deadline,
    pageErrors,
    evidenceDirectory,
  );
  const bTabId = bOpened.tab.id;
  assert.ok(
    bTabId && !aPageIds.includes(bTabId),
    "Thread B must own a different Browser page identity",
  );
  await bOpened.child.getByText(FIXTURE_THREAD_B_MARKER, { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  await page.screenshot({
    path: join(evidenceDirectory, "13-thread-b-browser-isolated.png"),
    animations: "disabled",
  });
  const closeBefore = countCommandPrefix(
    await browserCommandTrace(page),
    "ja_preview_close",
    "resolved",
  );
  const bTab = workbench
    .getByRole("tablist", { name: "浏览器页面", exact: true })
    .locator(`[role="tab"][data-preview-page-id="${bTabId}"]`);
  const bPanel = await activeBrowserPanel(workbench, bTabId);
  const bTitle = await bPanel.getAttribute("data-preview-page-title");
  assert.ok(bTitle, "Browser page must expose its native title for an accessible close action");
  await workbench
    .getByRole("button", { name: `关闭浏览器标签 ${bTitle}`, exact: true })
    .click({ timeout: Math.max(1, deadline - Date.now()) });
  await waitForCondition(
    "B native page close ACK and projection cleanup",
    async () =>
      countCommandPrefix(await browserCommandTrace(page), "ja_preview_close", "resolved") >
        closeBefore && (await browserTabFacts(workbench)).every(({ id }) => id !== bTabId),
    deadline,
  );
  assert.equal(await bTab.count(), 0, "closed B tab must be removed from the tab strip");
  await waitForCondition(
    "closed B child WebView release",
    async () => !page.context().pages().includes(bOpened.child) || bOpened.child.isClosed(),
    deadline,
  );

  const aRestoreVisibleBefore = countLayoutVisibility(await browserCommandTrace(page), true);
  await selectThread(page, threadA.threadId, deadline);
  workbench = await ensureBrowserCapability(page, deadline);
  const aRestoredTabs = await browserTabFacts(workbench);
  assert.deepEqual(
    aRestoredTabs.map(({ id }) => id),
    aPageIds,
  );
  await selectBrowserPageById(workbench, htmlPageId, deadline, pageErrors, evidenceDirectory);
  await waitForActiveBrowserUrl(workbench, files.nextUrl, deadline);
  const aNextChild = await waitForChildWebView(page, files.nextUrl, deadline);
  await aNextChild.getByText(FIXTURE_NEXT_MARKER, { exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForCondition(
    "Thread A native Browser page restored visible",
    async () =>
      countLayoutVisibility(await browserCommandTrace(page), true) > aRestoreVisibleBefore,
    deadline,
  );
  const finalTrace = await browserCommandTrace(page);
  const closeAckCount = countCommandPrefix(finalTrace, "ja_preview_close", "resolved");
  assert.ok(closeAckCount >= closeBefore + 1);
  assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);

  const screenshots = [
    "01-assistant-path-references.png",
    "02-html-local-child-webview.png",
    "03-missing-file-feedback.png",
    "03b-explorer-keyboard-feedback.png",
    "03c-explorer-duplicate-choice.png",
    "04-code-path-line-column.png",
    "05-workspace-text-file.png",
    "06-duplicate-file-choice.png",
    "07-external-text-read-only.png",
    "08-image-browser-page.png",
    "08-image-browser-narrow.png",
    "09-svg-browser-page.png",
    "10-pdf-browser-tab.png",
    "11-browser-history-after-forward.png",
    "12-cross-scheme-forward-file.png",
    "13-thread-b-browser-isolated.png",
  ];
  const report = {
    contractVersion: 1,
    status: "passed",
    runtime: { platform: process.platform, surface: "tauri_webview2", boundary: "debug_jar" },
    fixture: {
      provider: "deterministic_loopback",
      externalCalls: 0,
      explorerFolderVerificationRequested: verifyExplorerFolder,
      assistantTurnCount: fixture.snapshot().acceptanceTurnCount,
      relativeHtmlPath: files.relativeHtmlPath,
      unicodeAndSpaces: true,
      externalTextOutsideWorkspace: true,
    },
    assistantTurn: {
      completed: true,
      referencesNotResolvedBeforeClick: preClickFileResolveCount === 0,
      inlineCodePathClicked: relativeHtmlReference.reference.reference.length > 0,
      markdownLinkClicked: markdownHtmlReference.reference.reference.length > 0,
      lineColumnPathClicked: sourceReference.reference.reference.endsWith("#L2C4"),
      legacyLineColumnPathClicked: legacyLineColumnReference.reference.reference.endsWith(":2:4"),
      lineOnlyPathClicked: lineOnlyReference.reference.reference.endsWith("#L1"),
      missingPathFeedback: true,
      missingPathMessageRetained: missingFileMessageRetained,
      missingPathFocusRetained: missingFileFocusRetained,
      missingPathNoNativePageOpened,
      missingPathBrowserTabsProjectionChanged: browserTabsProjectionChanged,
      missingPathResolutionRejected: missingFileResolveRejected,
      missingPathResolveAttempted: missingFileResolveAttempted,
      missingPathCtrlClickReachedNativeExplorerCommand: true,
      missingPathCtrlClickKeptPanelHidden: explorerKeptPanelHidden,
      missingPathCtrlEnterReachedNativeExplorerCommand: true,
      duplicateExplorerChoiceDisplayed: true,
      explorerFolderOpenedAndFileSelected: explorerFolderVerified,
      absoluteWindowsPathClicked:
        isAbsolute(externalTextReference.reference.reference) &&
        referenceFilePath(externalTextReference.reference.reference, files.workspace) ===
          normalizedFilePath(files.externalPath),
      absoluteFileUrlClicked:
        files.externalUrl.startsWith("file:") &&
        externalFileUrlReference.reference.visibleName === "打开只读说明" &&
        referenceFilePath(externalFileUrlReference.reference.reference, files.workspace) ===
          normalizedFilePath(files.externalPath),
      relativeFilePath: relativeHtmlReference.reference.reference,
      markdownTarget: markdownHtmlReference.reference.reference,
      sourceLineColumn: sourceReference.reference.reference,
      externalTarget: externalTextReference.reference.reference,
      externalFileUrlTarget: files.externalUrl,
      externalFileUrlResolvedPath: externalFileUrlReference.reference.reference,
    },
    browser: {
      htmlPageId: htmlTab.id,
      htmlUrl: files.htmlUrl,
      fileOpenAcknowledgements: [...(activeBrowserFileOpenAcknowledgements ?? [])],
      htmlChildContentVerified: htmlFacts.markerVisible,
      relativePngDecoded: htmlFacts.pngDecoded,
      relativeSvgDecoded: htmlFacts.svgDecoded,
      relativePdfSourceVerified: htmlFacts.relativePdfSource,
      imagePageOpened: imageFacts.imageDecoded,
      imageChildContentType: imageFacts.contentType,
      svgPageOpened: svgFacts.svgDocument,
      svgDocumentOpened: svgFacts.markerVisible,
      svgPageCount: svgTabFacts.length,
      pdfDocumentOpened: pdfFacts.viewerDetected,
      pdfChildContentType: pdfFacts.contentType,
      multiplePagesOpen: aPageIds.length >= 4,
      pageIdsDistinct: new Set(aPageIds).size === aPageIds.length,
      addressFocused: bOpened.addressFocused,
      viewportResizeApplied: resizedBrowserBounds?.width > 0 && resizedBrowserBounds?.height > 0,
      nativeChildResized: resizedChildWidth < originalImageChildWidth,
      nativeChildRestoredSize: restoredChildWidth >= originalImageChildWidth,
      childViewportWidths: {
        before: originalImageChildWidth,
        narrow: resizedChildWidth,
        restored: restoredChildWidth,
      },
      backForwardReloadVerified: true,
      history: historyFacts,
      crossSchemeHistory: crossSchemeHistoryFacts,
      crossSchemeBackForwardVerified: true,
    },
    textViewer: {
      workspaceFileOpened: workspaceTextFacts.contentVisible,
      workspaceFilePath: workspaceTextFacts.path,
      workspaceFileReadOnly: workspaceTextFacts.readOnly,
      duplicateBasenameDisambiguated:
        canonicalFileDocumentPath(duplicateFacts.path ?? "", files.workspace) ===
        canonicalFileDocumentPath(files.duplicateAPath, files.workspace),
      externalFileReadOnly: externalTextFacts.readOnly,
      externalFileOutsideWorkspace: relative(files.workspace, files.externalPath).startsWith(".."),
      externalFileEditorFocused: externalTextFacts.editorFocused,
      absoluteWindowsPathOpened: windowsAbsoluteFileFacts.contentVisible,
      sourceEditorFocused: sourceFacts.editorFocused,
      sourceReveal: {
        line: sourceFacts.editorPosition.activeLine,
        column: sourceFacts.editorPosition.cursorColumn,
      },
      legacyLineColumnReveal: {
        line: legacySourceFacts.editorPosition.activeLine,
        column: legacySourceFacts.editorPosition.cursorColumn,
      },
      lineOnlyReveal: {
        line: lineOnlySourceFacts.editorPosition.activeLine,
        column: lineOnlySourceFacts.editorPosition.cursorColumn,
      },
      fileTabCount: externalTextFacts.fileTabCount,
    },
    threadScope: {
      isolated: threadA.threadId !== threadB.threadId && !aPageIds.includes(bTabId),
      aPageIds,
      bPageId: bTabId,
      aPagesRestored: aRestoredTabs.map(({ id }) => id).join(",") === aPageIds.join(","),
      bClosed: true,
      closedNativeResourcesAcknowledged: closeAckCount > closeBefore,
      nativeCloseAckCount: closeAckCount,
      hiddenLayoutAckCount: countLayoutVisibility(finalTrace, false),
      visibleLayoutAckCount: countLayoutVisibility(finalTrace, true),
    },
    screenshots,
    pageErrors,
  };
  // 失败时也保留完整真窗事实，避免单条报告断言把截图与 native ACK 的定位依据抹掉。
  await writeFile(
    join(evidenceDirectory, "browser-file-links-webview2-observed.json"),
    `${JSON.stringify(report, null, 2)}\n`,
    "utf8",
  );
  validateBrowserFileLinksReport(report);
  return report;
}

/**
 * 在 runtime handshake 不可用时直接从隔离 main WebView 验收 Preview 原生命令。
 * 此模式证明的是子 WebView、文件资源和 native history/close 生命周期，不冒充消息链接或 Thread UI 流程。
 */
export async function runNativePreviewFallback({
  page,
  workspaceRoot,
  evidenceDirectory,
  fixture,
}) {
  assert.ok(page, "page is required");
  const deadline = Date.now() + 150_000;
  const files = await writeBrowserFileFixture(workspaceRoot);
  const viewport = { x: 36, y: 84, width: 900, height: 640, visible: true };
  const opened = [];
  const screenshots = [];
  const pagesByTarget = new Map();

  /** 仅保留已由 Rust 签发的 session identity，finally 可以回收部分失败场景的原生资源。 */
  const remember = (result, label) => {
    assert.equal(result?.snapshot?.status, "open", `${label} native session did not open`);
    assert.match(result.snapshot.id, /^[0-9a-f]{8}-[0-9a-f-]{27}$/iu);
    assert.equal(result.window?.label, `preview_${result.snapshot.id.replaceAll("-", "")}`);
    opened.push({ id: result.snapshot.id, generation: result.snapshot.generation, label });
    return result.snapshot;
  };

  /** 以真实 Tauri command 打开绝对本机文件，并确认 Chromium context 新增了对应 child target。 */
  const openFile = async (target, label, url) => {
    const result = await invokeNativePreview(page, "ja_preview_open_file", {
      target,
      viewport,
    });
    const snapshot = remember(result, label);
    const child = await waitForNativeChildUrl(page, url, deadline);
    pagesByTarget.set(label, child);
    return { snapshot, child };
  };

  /** child 截图同时作为 native document 内容已到达的可审阅证据。 */
  const saveChildScreenshot = async (child, name) => {
    const path = join(evidenceDirectory, name);
    await child.screenshot({ path, animations: "disabled" });
    const bytes = (await stat(path)).size;
    assert.ok(bytes > 512, `${name} native child screenshot was unexpectedly empty`);
    screenshots.push({ file: name, bytes });
    return bytes;
  };

  let cleanup = { closeAckCount: 0, allChildTargetsClosed: false };
  try {
    await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });

    let runtimeStatus = "unavailable";
    try {
      const runtime = await invokeNativePreview(page, "ja_runtime_state");
      runtimeStatus = typeof runtime?.status === "string" ? runtime.status : "unknown";
    } catch {
      // Preview command 的 direct invoke 成功与否才是本 fallback 的事实边界。
    }

    const html = await openFile(files.htmlPath, "html", files.htmlUrl);
    const htmlFacts = await inspectHtmlChild(html.child, deadline);
    assert.equal(htmlFacts.markerVisible, true);
    assert.equal(htmlFacts.pngDecoded, true);
    assert.equal(htmlFacts.svgDecoded, true);
    assert.equal(htmlFacts.relativePdfSource, true);
    const htmlBytes = await saveChildScreenshot(
      html.child,
      "native-01-local-html-relative-assets.png",
    );

    const svg = await openFile(files.svgPath, "svg", files.svgUrl);
    await svg.child.waitForFunction(
      (marker) =>
        document.querySelector("svg title")?.textContent?.includes(marker) === true ||
        document.body?.innerText.includes(marker) === true,
      FIXTURE_SVG_MARKER,
      { timeout: Math.max(1, deadline - Date.now()) },
    );
    const svgFacts = await svg.child.evaluate(() => ({
      svgDocument: document.querySelector("svg") !== null,
      markerVisible:
        document
          .querySelector("svg title")
          ?.textContent?.includes("JA_BROWSER_FILE_SVG_CONTENT") === true ||
        document.body?.innerText.includes("JA_BROWSER_FILE_SVG_CONTENT") === true,
    }));
    assert.deepEqual(svgFacts, { svgDocument: true, markerVisible: true });
    await saveChildScreenshot(svg.child, "native-02-svg-document.png");

    const image = await openFile(files.imagePath, "image", files.imageUrl);
    await image.child.waitForFunction(
      () =>
        Array.from(document.images).some((element) => element.complete && element.naturalWidth > 0),
      undefined,
      { timeout: Math.max(1, deadline - Date.now()) },
    );
    const imageFacts = await image.child.evaluate(() => ({
      contentType: document.contentType,
      imageDecoded: Array.from(document.images).some(
        (element) => element.complete && element.naturalWidth === 1,
      ),
    }));
    assert.equal(imageFacts.imageDecoded, true);
    await saveChildScreenshot(image.child, "native-03-image-document.png");

    const pdf = await openFile(files.pdfPath, "pdf", files.pdfUrl);
    await waitForCondition(
      "native PDF viewer document",
      async () =>
        pdf.child.evaluate(
          () =>
            document.contentType === "application/pdf" ||
            document.querySelector(
              "pdf-viewer, embed[type='application/pdf'], object[type='application/pdf']",
            ) !== null ||
            document.body?.innerText.includes("JA_BROWSER_FILE_PDF_CONTENT") === true,
        ),
      deadline,
    );
    const pdfFacts = await pdf.child.evaluate(() => ({
      contentType: document.contentType,
      viewerDetected:
        document.contentType === "application/pdf" ||
        document.querySelector(
          "pdf-viewer, embed[type='application/pdf'], object[type='application/pdf']",
        ) !== null ||
        document.body?.innerText.includes("JA_BROWSER_FILE_PDF_CONTENT") === true,
    }));
    assert.equal(pdfFacts.viewerDetected, true);
    const pdfBytes = await saveChildScreenshot(pdf.child, "native-04-pdf-document.png");

    const resized = await invokeNativePreview(page, "ja_preview_layout", {
      sessionId: html.snapshot.id,
      viewport: { ...viewport, width: 660, height: 460 },
    });
    assert.equal(resized.status, "open");
    await waitForCondition(
      "child viewport resize",
      async () => (await html.child.evaluate(() => window.innerWidth)) <= 660,
      deadline,
    );
    const resizedWidth = await html.child.evaluate(() => window.innerWidth);
    const hidden = await invokeNativePreview(page, "ja_preview_layout", {
      sessionId: html.snapshot.id,
      viewport: { ...viewport, width: 660, height: 460, visible: false },
    });
    const shown = await invokeNativePreview(page, "ja_preview_layout", {
      sessionId: html.snapshot.id,
      viewport: { ...viewport, width: 900, height: 640, visible: true },
    });
    assert.equal(hidden.status, "open");
    assert.equal(shown.status, "open");
    await waitForCondition(
      "child viewport restored",
      async () => (await html.child.evaluate(() => window.innerWidth)) >= 900,
      deadline,
    );

    const blankResult = await invokeNativePreview(page, "ja_preview_open_blank", { viewport });
    const blank = remember(blankResult, "history");
    assert.equal(blank.url, "about:blank");
    const navigatedHttp = await invokeNativePreview(page, "ja_preview_navigate", {
      sessionId: blank.id,
      generation: blank.generation,
      source: "user",
      url: fixture.historyUrl,
    });
    assert.equal(navigatedHttp.status, "open");
    const httpChild = await waitForNativeChildUrl(page, fixture.historyUrl, deadline);
    pagesByTarget.set("history", httpChild);
    await httpChild.getByText(FIXTURE_HTTP_HISTORY_MARKER, { exact: true }).waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
    const httpFacts = await httpChild.evaluate(() => ({
      markerVisible:
        document.body?.innerText.includes("JA_BROWSER_FILE_HTTP_HISTORY_ORIGIN") === true,
      requestOrdinal: Number(document.querySelector("#history-request-count")?.textContent),
    }));
    assert.equal(httpFacts.markerVisible, true);
    await saveChildScreenshot(httpChild, "native-05-http-history-origin.png");

    const beforeFile = await readNativePreviewState(page, blank.id);
    const navigatedFile = await invokeNativePreview(page, "ja_preview_navigate_file", {
      sessionId: blank.id,
      generation: beforeFile.generation,
      target: files.nextPath,
    });
    assert.equal(navigatedFile.status, "open");
    const fileChild = await waitForNativeChildUrl(page, files.nextUrl, deadline);
    assert.equal(
      fileChild,
      httpChild,
      "history navigation must stay inside the same native child page",
    );
    await fileChild.getByText(FIXTURE_NEXT_MARKER, { exact: true }).waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });

    const beforeBack = await readNativePreviewState(page, blank.id);
    const back = await invokeNativePreview(page, "ja_preview_go_back", {
      sessionId: blank.id,
      generation: beforeBack.generation,
    });
    assert.equal(back.status, "open");
    const returnedHttp = await waitForNativeChildUrl(page, fixture.historyUrl, deadline);
    await returnedHttp.getByText(FIXTURE_HTTP_HISTORY_MARKER, { exact: true }).waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
    const reloadCountBefore = fixture.snapshot().historyRequestCount;
    const beforeReload = await readNativePreviewState(page, blank.id);
    const reloaded = await invokeNativePreview(page, "ja_preview_reload", {
      sessionId: blank.id,
      generation: beforeReload.generation,
    });
    assert.equal(reloaded.status, "open");
    await waitForCondition(
      "HTTP native child reload request",
      async () => fixture.snapshot().historyRequestCount > reloadCountBefore,
      deadline,
    );
    const beforeForward = await readNativePreviewState(page, blank.id);
    const forward = await invokeNativePreview(page, "ja_preview_go_forward", {
      sessionId: blank.id,
      generation: beforeForward.generation,
    });
    assert.equal(forward.status, "open");
    const returnedFile = await waitForNativeChildUrl(page, files.nextUrl, deadline);
    assert.equal(returnedFile, httpChild);
    await returnedFile.getByText(FIXTURE_NEXT_MARKER, { exact: true }).waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
    const historyForwardFacts = await returnedFile.evaluate(() => ({
      markerVisible: document.body?.innerText.includes("JA_BROWSER_FILE_HISTORY_NEXT") === true,
      url: location.href,
    }));
    assert.equal(historyForwardFacts.markerVisible, true);
    await saveChildScreenshot(returnedFile, "native-06-cross-scheme-forward-file.png");

    const identities = opened.map(({ id }) => id);
    assert.equal(
      new Set(identities).size,
      identities.length,
      "each browser page must have its own Rust identity",
    );
    assert.equal(pagesByTarget.size, 5);
    for (const session of opened) {
      const closed = await invokeNativePreview(page, "ja_preview_close", { sessionId: session.id });
      assert.equal(closed.status, "closed", `native close ACK missing for ${session.label}`);
      cleanup.closeAckCount += 1;
    }
    await waitForCondition(
      "all native child WebView targets closed after close ACK",
      async () =>
        Array.from(pagesByTarget.values()).every(
          (child) => child.isClosed() || !page.context().pages().includes(child),
        ),
      deadline,
    );
    cleanup.allChildTargetsClosed = true;

    return {
      contractVersion: 1,
      status: "passed",
      acceptanceScope: "native_preview_layer_only",
      conversationFlowVerified: false,
      runtime: { platform: process.platform, surface: "tauri_webview2", boundary: "debug_jar" },
      runtimeBootstrap: {
        observedStatus: runtimeStatus,
        directPreviewInvokeSucceeded: true,
        runtimeReadyRequired: false,
      },
      browser: {
        directFileOpenSucceeded: true,
        htmlChildContentVerified: htmlFacts.markerVisible,
        relativePngDecoded: htmlFacts.pngDecoded,
        relativeSvgDecoded: htmlFacts.svgDecoded,
        relativePdfSourceVerified: htmlFacts.relativePdfSource,
        svgDocumentOpened: svgFacts.svgDocument && svgFacts.markerVisible,
        imageDocumentOpened: imageFacts.imageDecoded,
        pdfDocumentOpened: pdfFacts.viewerDetected,
        multiplePagesOpen: identities.length === 5 && pagesByTarget.size === 5,
        pageIdsDistinct: new Set(identities).size === identities.length,
        viewportResizeApplied: resizedWidth <= 660,
        viewportRestored: true,
        crossSchemeHistory: {
          sameChildTarget: returnedHttp === httpChild && returnedFile === httpChild,
          httpDocumentObserved: httpFacts.markerVisible,
          localDocumentObserved: historyForwardFacts.markerVisible,
          backResolved: back.status === "open",
          reloadResolved: reloaded.status === "open",
          reloadRequestObserved: fixture.snapshot().historyRequestCount > reloadCountBefore,
          forwardResolved: forward.status === "open",
          forwardRestoredLocalDocument: historyForwardFacts.markerVisible,
        },
        childScreenshotBytes: Math.max(htmlBytes, pdfBytes),
      },
      threadScope: {
        verified: false,
        nativeCloseAckCount: cleanup.closeAckCount,
        closedNativeResourcesAcknowledged: cleanup.closeAckCount === opened.length,
        allChildTargetsClosed: cleanup.allChildTargetsClosed,
      },
      browserTabFocusVerified: false,
      fileLinkUiVerified: false,
      textViewerRoutingVerified: false,
      screenshots,
    };
  } finally {
    for (const session of opened) {
      try {
        const state = await invokeNativePreview(page, "ja_preview_state", {
          sessionId: session.id,
        });
        if (state.status === "open") {
          const closed = await invokeNativePreview(page, "ja_preview_close", {
            sessionId: session.id,
          });
          if (closed.status === "closed") cleanup.closeAckCount += 1;
        }
      } catch {
        // Preserve the primary assertion failure; runProduction still owns parent-process cleanup.
      }
    }
  }
}

/** 有界等待异步 UI/原生事件收敛，超时文本只报告场景名称而不包含测试路径。 */
async function waitForCondition(label, predicate, deadline) {
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} 超时`);
}

/** 等待 App Server 就绪；运行时握手失败时读取无敏感数据的原生状态，避免耗尽场景总时限。 */
async function waitForApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  const ready = page.getByRole("status", { name: "本地运行时：已连接", exact: true });
  const failed = page.getByRole("status", { name: "本地运行时：连接失败", exact: true });
  await waitForCondition(
    "App Server startup status",
    async () => (await ready.isVisible()) || (await failed.isVisible()),
    Math.min(deadline, Date.now() + 60_000),
  );
  if (await failed.isVisible()) {
    const nativeState = await page.evaluate(async () => {
      const invoke = window.__TAURI_INTERNALS__?.invoke;
      if (typeof invoke !== "function") return { error: "native invoke bridge unavailable" };
      const readState = async () => invoke("ja_runtime_state", {});
      let stateBefore;
      try {
        stateBefore = await readState();
      } catch (error) {
        return { stateError: String(error?.message ?? error).slice(0, 300) };
      }
      let retry;
      let timer;
      try {
        retry = await Promise.race([
          invoke("ja_runtime_start", {}),
          new Promise((resolvePromise) => {
            timer = window.setTimeout(() => resolvePromise({ timeout: true }), 20_000);
          }),
        ]);
      } catch (error) {
        retry = { error: String(error?.message ?? error).slice(0, 300) };
      } finally {
        if (timer !== undefined) window.clearTimeout(timer);
      }
      let stateAfter;
      try {
        stateAfter = await readState();
      } catch (error) {
        stateAfter = { error: String(error?.message ?? error).slice(0, 300) };
      }
      return { stateBefore, retry, stateAfter };
    });
    throw new Error(`App Server runtime failed to connect: ${JSON.stringify(nativeState)}`);
  }
}

/** 对已实现的报告使用失败关闭校验；骨架状态不能被误报为完整 WebView2 验收通过。 */
export function validateBrowserFileLinksReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.status, "passed");
  assert.equal(report?.runtime?.platform, "win32");
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.boundary, "debug_jar");
  assert.equal(report?.fixture?.provider, "deterministic_loopback");
  assert.equal(report?.fixture?.externalCalls, 0);
  assert.equal(report?.fixture?.externalTextOutsideWorkspace, true);
  assert.equal(report?.assistantTurn?.completed, true);
  assert.equal(report?.assistantTurn?.referencesNotResolvedBeforeClick, true);
  assert.equal(report?.assistantTurn?.inlineCodePathClicked, true);
  assert.equal(report?.assistantTurn?.markdownLinkClicked, true);
  assert.equal(report?.assistantTurn?.lineColumnPathClicked, true);
  assert.equal(report?.assistantTurn?.legacyLineColumnPathClicked, true);
  assert.equal(report?.assistantTurn?.lineOnlyPathClicked, true);
  assert.equal(report?.assistantTurn?.missingPathFeedback, true);
  assert.equal(report?.assistantTurn?.missingPathMessageRetained, true);
  assert.equal(report?.assistantTurn?.missingPathFocusRetained, true);
  assert.equal(report?.assistantTurn?.missingPathNoNativePageOpened, true);
  assert.equal(report?.assistantTurn?.missingPathResolutionRejected, true);
  assert.equal(report?.assistantTurn?.missingPathResolveAttempted, true);
  assert.equal(report?.assistantTurn?.missingPathCtrlClickReachedNativeExplorerCommand, true);
  assert.equal(report?.assistantTurn?.missingPathCtrlClickKeptPanelHidden, true);
  assert.equal(report?.assistantTurn?.missingPathCtrlEnterReachedNativeExplorerCommand, true);
  assert.equal(report?.assistantTurn?.duplicateExplorerChoiceDisplayed, true);
  if (report?.fixture?.explorerFolderVerificationRequested) {
    assert.equal(report?.assistantTurn?.explorerFolderOpenedAndFileSelected, true);
  }
  assert.equal(report?.assistantTurn?.absoluteWindowsPathClicked, true);
  assert.equal(report?.assistantTurn?.absoluteFileUrlClicked, true);
  assert.equal(report?.browser?.htmlChildContentVerified, true);
  const htmlOpenAck = report?.browser?.fileOpenAcknowledgements?.[0];
  assert.equal(htmlOpenAck?.command, "ja_preview_open_file");
  assert.match(htmlOpenAck?.pageId ?? "", /^[0-9a-f-]{36}$/iu);
  assert.equal(htmlOpenAck?.tabPageId, htmlOpenAck?.pageId);
  assert.ok(Number.isInteger(htmlOpenAck?.generation));
  assert.ok(Number.isInteger(htmlOpenAck?.ackLatencyMs) && htmlOpenAck.ackLatencyMs >= 0);
  assert.equal(report?.browser?.relativePngDecoded, true);
  assert.equal(report?.browser?.relativeSvgDecoded, true);
  assert.equal(report?.browser?.relativePdfSourceVerified, true);
  assert.equal(report?.browser?.imagePageOpened, true);
  assert.equal(report?.browser?.svgPageOpened, true);
  assert.equal(report?.browser?.svgDocumentOpened, true);
  assert.equal(report?.browser?.pdfDocumentOpened, true);
  assert.equal(report?.browser?.multiplePagesOpen, true);
  assert.equal(report?.browser?.addressFocused, true);
  assert.equal(report?.browser?.viewportResizeApplied, true);
  assert.equal(report?.browser?.nativeChildResized, true);
  assert.equal(report?.browser?.nativeChildRestoredSize, true);
  assert.equal(report?.browser?.pageIdsDistinct, true);
  assert.equal(report?.browser?.backForwardReloadVerified, true);
  assert.equal(report?.browser?.crossSchemeBackForwardVerified, true);
  assert.equal(report?.browser?.crossSchemeHistory?.samePageId, true);
  assert.equal(report?.browser?.crossSchemeHistory?.backResolved, true);
  assert.equal(report?.browser?.crossSchemeHistory?.forwardResolved, true);
  assert.equal(report?.browser?.crossSchemeHistory?.forwardRestoredLocalDocument, true);
  assert.equal(report?.textViewer?.workspaceFileOpened, true);
  assert.equal(report?.textViewer?.duplicateBasenameDisambiguated, true);
  assert.equal(report?.textViewer?.workspaceFileReadOnly, false);
  assert.equal(report?.textViewer?.sourceEditorFocused, true);
  assert.equal(report?.textViewer?.absoluteWindowsPathOpened, true);
  assert.equal(report?.textViewer?.externalFileReadOnly, true);
  assert.equal(report?.textViewer?.externalFileOutsideWorkspace, true);
  assert.deepEqual(report?.textViewer?.sourceReveal, { line: 2, column: 4 });
  assert.deepEqual(report?.textViewer?.legacyLineColumnReveal, { line: 2, column: 4 });
  assert.deepEqual(report?.textViewer?.lineOnlyReveal, { line: 1, column: 1 });
  assert.equal(report?.threadScope?.isolated, true);
  assert.equal(report?.threadScope?.aPagesRestored, true);
  assert.equal(report?.threadScope?.bClosed, true);
  assert.equal(report?.threadScope?.closedNativeResourcesAcknowledged, true);
  assert.ok(report?.threadScope?.hiddenLayoutAckCount > 0);
  assert.ok(report?.threadScope?.visibleLayoutAckCount > 0);
  assert.ok(report?.threadScope?.nativeCloseAckCount > 0);
  assert.deepEqual(report?.pageErrors, []);
  assert.ok(Array.isArray(report?.screenshots) && report.screenshots.length >= 10);
  return report;
}

/** 失败关闭验证 direct native fallback 的子 WebView、真实文件页面和资源回收证据。 */
export function validateNativePreviewFallbackReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.status, "passed");
  assert.equal(report?.acceptanceScope, "native_preview_layer_only");
  assert.equal(report?.conversationFlowVerified, false);
  assert.equal(report?.runtime?.platform, "win32");
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.boundary, "debug_jar");
  assert.equal(report?.runtimeBootstrap?.directPreviewInvokeSucceeded, true);
  assert.equal(report?.runtimeBootstrap?.runtimeReadyRequired, false);
  assert.equal(report?.browser?.directFileOpenSucceeded, true);
  assert.equal(report?.browser?.htmlChildContentVerified, true);
  assert.equal(report?.browser?.relativePngDecoded, true);
  assert.equal(report?.browser?.relativeSvgDecoded, true);
  assert.equal(report?.browser?.relativePdfSourceVerified, true);
  assert.equal(report?.browser?.svgDocumentOpened, true);
  assert.equal(report?.browser?.imageDocumentOpened, true);
  assert.equal(report?.browser?.pdfDocumentOpened, true);
  assert.equal(report?.browser?.multiplePagesOpen, true);
  assert.equal(report?.browser?.pageIdsDistinct, true);
  assert.equal(report?.browser?.viewportResizeApplied, true);
  assert.equal(report?.browser?.viewportRestored, true);
  assert.equal(report?.browser?.crossSchemeHistory?.sameChildTarget, true);
  assert.equal(report?.browser?.crossSchemeHistory?.httpDocumentObserved, true);
  assert.equal(report?.browser?.crossSchemeHistory?.localDocumentObserved, true);
  assert.equal(report?.browser?.crossSchemeHistory?.backResolved, true);
  assert.equal(report?.browser?.crossSchemeHistory?.reloadResolved, true);
  assert.equal(report?.browser?.crossSchemeHistory?.reloadRequestObserved, true);
  assert.equal(report?.browser?.crossSchemeHistory?.forwardResolved, true);
  assert.equal(report?.browser?.crossSchemeHistory?.forwardRestoredLocalDocument, true);
  assert.ok(report?.browser?.childScreenshotBytes > 512);
  assert.equal(report?.threadScope?.closedNativeResourcesAcknowledged, true);
  assert.equal(report?.threadScope?.allChildTargetsClosed, true);
  assert.ok(report?.threadScope?.nativeCloseAckCount >= 5);
  assert.equal(report?.browserTabFocusVerified, false);
  assert.equal(report?.fileLinkUiVerified, false);
  assert.equal(report?.textViewerRoutingVerified, false);
  assert.ok(Array.isArray(report?.screenshots) && report.screenshots.length >= 6);
  return report;
}

/** 严格解析本脚本参数；JDK25 与 Cargo target 使用仓库已验证默认值并允许显式覆盖。 */
function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(REPO_ROOT, "target", "codex-browser-file-links"),
    edgeDriver: undefined,
    mode: "conversation",
    verifyExplorerFolder: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--verify-explorer-folder") {
      options.verifyExplorerFolder = true;
      continue;
    }
    if (argument === "--mode") {
      const value = argv[index + 1];
      if (value === undefined || value.startsWith("--"))
        throw new Error(`missing value for ${argument}`);
      if (!["conversation", "native-preview"].includes(value)) {
        throw new Error("--mode must be conversation or native-preview");
      }
      options.mode = value;
      index += 1;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else if (argument === "--edge-driver") options.edgeDriver = resolve(value);
    else throw new Error(`unknown argument: ${argument}`);
    index += 1;
  }
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  return options;
}

/** 执行 isolated JVM JAR/WebView2 runner，并无条件回收本轮 loopback Provider listener。 */
async function main() {
  if (process.platform !== "win32")
    throw new Error("browser file-link acceptance requires Windows 11");
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startBrowserFileProviderFixture();
  try {
    const report = await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      maxModelRounds: 4,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      hiddenWindow: options.mode === "native-preview",
      prewarmWebview: true,
      preserveFailedProfile: true,
      driver:
        options.mode === "native-preview"
          ? (driverOptions) => runNativePreviewFallback({ ...driverOptions, fixture })
          : (driverOptions) =>
              runBrowserFileLinksWebView2({
                ...driverOptions,
                fixture,
                verifyExplorerFolder: options.verifyExplorerFolder,
              }),
      validateReport:
        options.mode === "native-preview"
          ? validateNativePreviewFallbackReport
          : validateBrowserFileLinksReport,
      reportFileName:
        options.mode === "native-preview"
          ? "browser-file-links-webview2-native-preview-report.json"
          : "browser-file-links-webview2-report.json",
    });
    console.log(
      `JA_BROWSER_FILE_LINKS_WEBVIEW2_PASS ${JSON.stringify({ status: report.status, mode: options.mode })}`,
    );
  } finally {
    await fixture.close();
  }
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(
      `JA_BROWSER_FILE_LINKS_WEBVIEW2_FAIL ${String(error?.stack ?? error?.message ?? error).slice(0, 4000)}`,
    );
    process.exitCode = 1;
  });
}
