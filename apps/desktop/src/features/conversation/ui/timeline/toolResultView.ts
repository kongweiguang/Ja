// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ToolPresentation } from "../../domain/timelineTypes";

export type ToolResultRow = {
  path: string;
  kind: "file" | "directory" | "match" | "context";
  line?: number;
  detail?: string;
};

export type ToolResultView =
  | { kind: "read"; content: string }
  | { kind: "raw"; content: string }
  | { kind: "matches"; rows: ToolResultRow[] }
  | { kind: "files"; rows: ToolResultRow[] };

const DISCOVERY_FOOTER = /\n{2}\[workspace discovery\][\s\S]*$/u;
const READ_CONTINUATION = /\n{2}\[Showing lines [\s\S]*$/u;

/**
 * 将已由 Java 脱敏的固定 Tool 输出行投影为紧凑结果视图；未知或不完整格式绝不丢内容，退回等宽正文。
 */
export function toolResultView(
  toolName: string | undefined,
  presentation: ToolPresentation,
  output: string | undefined,
): ToolResultView | undefined {
  const content = toolResultContent(toolName, presentation, output);
  if (content === undefined || content.trim() === "") return undefined;
  if (content.trim() === "") return undefined;
  switch (toolName) {
    case "grep": {
      const rows = content.split(/\r?\n/u).map(grepRow);
      return rows.every((row): row is ToolResultRow => row !== undefined)
        ? { kind: "matches", rows }
        : { kind: "raw", content };
    }
    case "find": {
      const rows = content.split(/\r?\n/u).map(discoveredPathRow);
      return rows.every((row): row is ToolResultRow => row !== undefined)
        ? { kind: "files", rows }
        : { kind: "raw", content };
    }
    case "ls": {
      const rows = content.split(/\r?\n/u).map(directoryEntryRow);
      return rows.every((row): row is ToolResultRow => row !== undefined)
        ? { kind: "files", rows }
        : { kind: "raw", content };
    }
    case "read":
      return { kind: "read", content };
    default:
      return { kind: "raw", content };
  }
}

/**
 * 返回适合阅读的安全正文，并只在服务器已下发等价摘要时去除机器续读提示，供展开与完整输出共用同一行数。
 */
export function toolResultContent(
  toolName: string | undefined,
  presentation: ToolPresentation,
  output: string | undefined,
): string | undefined {
  return output === undefined ? undefined : resultBody(toolName, presentation.summary, output);
}

/**
 * 新版摘要已承接受控的发现和续读说明时，隐藏重复的协议提示；历史记录没有摘要时保留原文以免丢失事实。
 */
function resultBody(
  toolName: string | undefined,
  summary: string | undefined,
  output: string,
): string {
  if (summary === undefined) return output;
  if (toolName === "grep" || toolName === "find" || toolName === "ls") {
    return output.replace(DISCOVERY_FOOTER, "");
  }
  return toolName === "read" ? output.replace(READ_CONTINUATION, "") : output;
}

/**
 * grep 的 Java 投影只有命中 `path:line:` 与上下文 `path-line-` 两种行；非该格式不猜测路径或行号。
 */
function grepRow(value: string): ToolResultRow | undefined {
  const match = /^(.+?):(\d+):\s?(.*)$/u.exec(value);
  if (match !== null) return resultMatch(match, "match");
  const context = /^(.+?)-(\d+)-\s?(.*)$/u.exec(value);
  return context === null ? undefined : resultMatch(context, "context");
}

/**
 * 命中与上下文共用数值行号校验，防止预览文本中任意冒号被展示为可定位的文件记录。
 */
function resultMatch(match: RegExpExecArray, kind: "match" | "context"): ToolResultRow | undefined {
  const line = Number(match[2]);
  const path = match[1]?.trim();
  if (!Number.isSafeInteger(line) || line < 1 || path === undefined || path === "")
    return undefined;
  return { kind, path, line, detail: match[3] ?? "" };
}

/**
 * fd 结果使用末尾斜杠传达目录身份；空行、绝对路径样式或附加字段都交给原始预览而不是伪造条目。
 */
function discoveredPathRow(value: string): ToolResultRow | undefined {
  if (value === "" || value.includes("\t")) return undefined;
  const directory = value.endsWith("/");
  const path = directory ? value.slice(0, -1) : value;
  return path === "" ? undefined : { kind: directory ? "directory" : "file", path };
}

/**
 * ls 的单层结果固定为 `relativePath<TAB>file|directory`，让图标和文本由真实 Tool 类型驱动。
 */
function directoryEntryRow(value: string): ToolResultRow | undefined {
  const [path, kind, extra] = value.split("\t");
  if (path === undefined || path === "" || extra !== undefined) return undefined;
  if (kind !== "file" && kind !== "directory") return undefined;
  return { kind, path };
}
