// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useVirtualizer } from "@tanstack/react-virtual";
import { ChevronDown, ChevronUp } from "lucide-react";
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactElement,
  type ReactNode,
} from "react";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import { IconButton } from "@/shared/ui/primitives";
import {
  createReviewSyntaxHighlighter,
  SupersededReviewSyntaxRequest,
  type ReviewSyntaxHighlighter,
  type ReviewSyntaxRequest,
  type ReviewSyntaxResult,
} from "../application/reviewSyntaxHighlightClient";
import "./ReviewUnifiedDiff.css";

export interface ReviewUnifiedDiffLine {
  readonly kind: "context" | "addition" | "deletion";
  readonly oldLine: number | null;
  readonly newLine: number | null;
  readonly text: string;
}

export interface ReviewUnifiedDiffHunk {
  readonly hunkId?: string;
  readonly header: string;
  readonly oldStart: number;
  readonly oldLines: number;
  readonly newStart: number;
  readonly newLines: number;
}

export interface ReviewUnifiedDiffFile {
  readonly path: string;
  readonly lines: readonly ReviewUnifiedDiffLine[];
  readonly hunks?: readonly ReviewUnifiedDiffHunk[];
  readonly unified?: string | null;
}

export interface ReviewUnifiedDiffProps {
  readonly file: ReviewUnifiedDiffFile;
  readonly revision: string;
  readonly viewMode?: "unified" | "split";
  readonly onCopyText?: (text: string) => Promise<void>;
  readonly renderHunkActions?: (hunk: ReviewUnifiedDiffHunk, index: number) => ReactNode;
}

interface DiffHunkProjection {
  readonly key: string;
  readonly header: string;
  readonly lines: readonly ReviewUnifiedDiffLine[];
  readonly source?: ReviewUnifiedDiffHunk;
}

interface HiddenContextRange {
  readonly key: string;
  readonly lines: readonly ReviewUnifiedDiffLine[];
}

type ProjectedDiffRow =
  | {
      readonly kind: "hunk";
      readonly key: string;
      readonly hunkIndex: number;
      readonly hunk: DiffHunkProjection;
    }
  | { readonly kind: "gap"; readonly key: string }
  | { readonly kind: "line"; readonly key: string; readonly line: ReviewUnifiedDiffLine }
  | {
      readonly kind: "context-toggle";
      readonly key: string;
      readonly range: HiddenContextRange;
      readonly expanded: boolean;
    };

type DiffRow =
  | ProjectedDiffRow
  | {
      readonly kind: "split-line";
      readonly key: string;
      readonly oldLine: ReviewUnifiedDiffLine | null;
      readonly newLine: ReviewUnifiedDiffLine | null;
    };

const CONTEXT_RADIUS = 3;
const LINE_ROW_HEIGHT = 22;
const META_ROW_HEIGHT = 28;
const EMPTY_SET = new Set<string>();

/**
 * 原生 hunk 的 old/new 行数是权威边界；只有全部行恰好被消费时才采用该投影，
 * 防止部分或过期元数据把两个真实区块错误串接。
 */
function projectNativeHunks(
  path: string,
  lines: readonly ReviewUnifiedDiffLine[],
  hunks: readonly ReviewUnifiedDiffHunk[],
): readonly DiffHunkProjection[] | undefined {
  const projected: DiffHunkProjection[] = [];
  let cursor = 0;
  for (const [index, hunk] of hunks.entries()) {
    const start = cursor;
    let oldLines = 0;
    let newLines = 0;
    while (cursor < lines.length && (oldLines < hunk.oldLines || newLines < hunk.newLines)) {
      const line = lines[cursor]!;
      if (line.oldLine !== null) oldLines += 1;
      if (line.newLine !== null) newLines += 1;
      if (oldLines > hunk.oldLines || newLines > hunk.newLines) return undefined;
      cursor += 1;
    }
    if (cursor === start || oldLines !== hunk.oldLines || newLines !== hunk.newLines)
      return undefined;
    projected.push({
      key: `${path}:native:${hunk.hunkId ?? index}`,
      header: hunk.header,
      lines: lines.slice(start, cursor),
      source: hunk,
    });
  }
  return cursor === lines.length ? projected : undefined;
}

/**
 * Turn Diff 暂未公开 hunk 元数据，只能以结构化行号的跳跃作为边界；该推导宁可多分区，
 * 也不会把中间未加载的文件正文伪装成连续上下文。
 */
function inferHunks(
  path: string,
  lines: readonly ReviewUnifiedDiffLine[],
): readonly DiffHunkProjection[] {
  if (lines.length === 0) return [];
  const groups: ReviewUnifiedDiffLine[][] = [];
  let current: ReviewUnifiedDiffLine[] = [];
  let lastOld: number | undefined;
  let lastNew: number | undefined;
  for (const line of lines) {
    const oldBreak = line.oldLine !== null && lastOld !== undefined && line.oldLine !== lastOld + 1;
    const newBreak = line.newLine !== null && lastNew !== undefined && line.newLine !== lastNew + 1;
    if ((oldBreak || newBreak) && current.length > 0) {
      groups.push(current);
      current = [];
      lastOld = undefined;
      lastNew = undefined;
    }
    current.push(line);
    if (line.oldLine !== null) lastOld = line.oldLine;
    if (line.newLine !== null) lastNew = line.newLine;
  }
  if (current.length > 0) groups.push(current);
  return groups.map((group, index) => ({
    key: `${path}:inferred:${index}:${group[0]?.oldLine ?? "-"}:${group[0]?.newLine ?? "-"}`,
    header: inferredHunkHeader(group),
    lines: group,
  }));
}

/** 缺少原生 header 时只展示行号可证明的范围，不伪造标准 unified header 或未知起点。 */
function inferredHunkHeader(lines: readonly ReviewUnifiedDiffLine[]): string {
  const old = lines.flatMap((line) => (line.oldLine === null ? [] : [line.oldLine]));
  const next = lines.flatMap((line) => (line.newLine === null ? [] : [line.newLine]));
  return `旧行 ${lineRange(old)} · 新行 ${lineRange(next)}`;
}

/** 已知行号只压缩首尾范围；单侧没有行时明确标为无，避免用 0 冒充真实位置。 */
function lineRange(lines: readonly number[]): string {
  if (lines.length === 0) return "无";
  return lines.length === 1 ? String(lines[0]) : `${lines[0]}–${lines.at(-1)}`;
}

/** 优先保留 native 分区；元数据不完整时退回行号分区，绝不跨不连续行合并。 */
function projectHunks(file: ReviewUnifiedDiffFile): readonly DiffHunkProjection[] {
  if (file.hunks !== undefined && file.hunks.length > 0) {
    const native = projectNativeHunks(file.path, file.lines, file.hunks);
    if (native !== undefined) return native;
  }
  return inferHunks(file.path, file.lines);
}

/**
 * 每段连续上下文独立折叠：变更前保留末三行、变更后保留前三行、两处变更间各留三行。
 * toggle 只代表已经加载到前端的行，因此不会为 hunk 间缺口提供误导性展开能力。
 */
function projectContextRun(
  hunk: DiffHunkProjection,
  start: number,
  end: number,
  expanded: ReadonlySet<string>,
): readonly ProjectedDiffRow[] {
  const run = hunk.lines.slice(start, end);
  const keepBefore = start === 0 ? 0 : Math.min(CONTEXT_RADIUS, run.length);
  const keepAfter =
    end === hunk.lines.length ? 0 : Math.min(CONTEXT_RADIUS, run.length - keepBefore);
  const hiddenStart = keepBefore;
  const hiddenEnd = run.length - keepAfter;
  if (hiddenEnd <= hiddenStart) {
    return run.map((line, index) => lineRow(hunk.key, start + index, line));
  }
  const key = `${hunk.key}:context:${start + hiddenStart}:${start + hiddenEnd}`;
  const range = { key, lines: run.slice(hiddenStart, hiddenEnd) };
  const isExpanded = expanded.has(key);
  return [
    ...run.slice(0, hiddenStart).map((line, index) => lineRow(hunk.key, start + index, line)),
    { kind: "context-toggle", key, range, expanded: isExpanded },
    ...(isExpanded
      ? range.lines.map((line, index) => lineRow(hunk.key, start + hiddenStart + index, line))
      : []),
    ...run
      .slice(hiddenEnd)
      .map((line, index) => lineRow(hunk.key, start + hiddenEnd + index, line)),
  ];
}

/** 行 key 同时包含 hunk 与原始局部位置，展开上下文不会改变其它行身份。 */
function lineRow(hunkKey: string, index: number, line: ReviewUnifiedDiffLine): ProjectedDiffRow {
  return { kind: "line", key: `${hunkKey}:line:${index}`, line };
}

/** 将单一 hunk 投影成 header、变更行与可独立展开的上下文行。 */
function projectHunkRows(
  hunk: DiffHunkProjection,
  hunkIndex: number,
  expanded: ReadonlySet<string>,
): readonly ProjectedDiffRow[] {
  const rows: ProjectedDiffRow[] = [{ kind: "hunk", key: `${hunk.key}:header`, hunkIndex, hunk }];
  let cursor = 0;
  while (cursor < hunk.lines.length) {
    const line = hunk.lines[cursor]!;
    if (line.kind !== "context") {
      rows.push(lineRow(hunk.key, cursor, line));
      cursor += 1;
      continue;
    }
    let end = cursor + 1;
    while (end < hunk.lines.length && hunk.lines[end]!.kind === "context") end += 1;
    rows.push(...projectContextRun(hunk, cursor, end, expanded));
    cursor = end;
  }
  return rows;
}

/** 在 hunk 之间插入不可展开的缺口标记，让虚拟列表仍准确表达正文并不连续。 */
function projectRows(
  hunks: readonly DiffHunkProjection[],
  expanded: ReadonlySet<string>,
): readonly ProjectedDiffRow[] {
  return hunks.flatMap((hunk, index) => [
    ...(index === 0 ? [] : [{ kind: "gap" as const, key: `${hunk.key}:gap-before` }]),
    ...projectHunkRows(hunk, index, expanded),
  ]);
}

/**
 * 双栏只配对同一已加载变更块内的结构化增删行；数量不等时保留空侧，避免通过文本相似度
 * 重算 Diff 或把 hunk 之外的未知正文伪装成对齐结果。
 */
function projectSplitChangeRows(rows: readonly ProjectedDiffRow[]): readonly DiffRow[] {
  const changed = rows.filter(
    (row): row is Extract<ProjectedDiffRow, { kind: "line" }> => row.kind === "line",
  );
  const deletions = changed.filter((row) => row.line.kind === "deletion");
  const additions = changed.filter((row) => row.line.kind === "addition");
  return Array.from({ length: Math.max(deletions.length, additions.length) }, (_, index) => ({
    kind: "split-line" as const,
    key: `${changed[0]?.key ?? "change"}:split:${index}`,
    oldLine: deletions[index]?.line ?? null,
    newLine: additions[index]?.line ?? null,
  }));
}

/**
 * 展示模式只变换最终行投影：上下文在两侧复用，连续增删块按顺序配对，所有 meta 行、
 * 折叠状态、hunk 操作和虚拟滚动身份保持原有结构。
 */
function projectViewRows(
  rows: readonly ProjectedDiffRow[],
  viewMode: "unified" | "split",
): readonly DiffRow[] {
  if (viewMode === "unified") return rows;
  const projected: DiffRow[] = [];
  let cursor = 0;
  while (cursor < rows.length) {
    const row = rows[cursor]!;
    if (row.kind !== "line") {
      projected.push(row);
      cursor += 1;
      continue;
    }
    if (row.line.kind === "context") {
      projected.push({
        kind: "split-line",
        key: `${row.key}:split-context`,
        oldLine: row.line,
        newLine: row.line,
      });
      cursor += 1;
      continue;
    }
    let end = cursor + 1;
    while (end < rows.length) {
      const next = rows[end];
      if (next?.kind !== "line" || next.line.kind === "context") break;
      end += 1;
    }
    projected.push(...projectSplitChangeRows(rows.slice(cursor, end)));
    cursor = end;
  }
  return projected;
}

/** 复制只使用原生 unified 或当前结构化行，永远不把折叠状态写进剪贴板内容。 */
function copyText(file: ReviewUnifiedDiffFile, hunks: readonly DiffHunkProjection[]): string {
  if (file.unified !== undefined && file.unified !== null) return file.unified;
  return hunks
    .flatMap((hunk) => [
      hunk.header,
      ...hunk.lines.map(
        (line) =>
          `${line.kind === "addition" ? "+" : line.kind === "deletion" ? "-" : " "}${line.text}`,
      ),
    ])
    .join("\n");
}

/** 根据行类型返回语义 marker，避免仅靠背景色传达增删含义。 */
function lineMarker(kind: ReviewUnifiedDiffLine["kind"]): string {
  return kind === "addition" ? "+" : kind === "deletion" ? "-" : " ";
}

/** 单栏行号以该行实际所在一侧为准，删除取旧行，其余取新行，避免同时展示两个 gutter。 */
function unifiedLineNumber(line: ReviewUnifiedDiffLine): {
  readonly number: number | null;
  readonly label: string;
} {
  const side = line.kind === "deletion" ? "旧文件" : "新文件";
  const number = line.kind === "deletion" ? line.oldLine : line.newLine;
  return { number, label: number === null ? `${side}行号未知` : `${side}第 ${number} 行` };
}

/** 双栏每侧只读取自己的结构化行号；空侧明确无对应行，不借用另一侧编号。 */
function splitLineNumber(
  line: ReviewUnifiedDiffLine | null,
  side: "old" | "new",
): { readonly number: number | null; readonly label: string } {
  const sideLabel = side === "old" ? "旧文件" : "新文件";
  const number = line === null ? null : side === "old" ? line.oldLine : line.newLine;
  return {
    number,
    label:
      line === null
        ? `${sideLabel}无对应行`
        : number === null
          ? `${sideLabel}行号未知`
          : `${sideLabel}第 ${number} 行`,
  };
}

/**
 * 仅投影 Worker 所需的文本与原始行索引；每个 hunk 的旧、新侧分片独立，避免不连续正文或
 * 删除侧 parser 状态污染新增侧。上下文由新侧结果覆盖，语义与修改后文件保持一致。
 */
function syntaxRequest(
  identity: string,
  file: ReviewUnifiedDiffFile,
  hunks: readonly DiffHunkProjection[],
): ReviewSyntaxRequest {
  const lineIndexes = new Map(file.lines.map((line, index) => [line, index]));
  const fragments: ReviewSyntaxRequest["fragments"][number][] = [];
  for (const hunk of hunks) {
    for (const excluded of ["addition", "deletion"] as const) {
      const lines = hunk.lines.filter((line) => line.kind !== excluded);
      fragments.push({
        lineIndexes: lines.map((line) => lineIndexes.get(line) ?? -1),
        lines: lines.map((line) => line.text),
      });
    }
  }
  return { identity, filePath: file.path, lineCount: file.lines.length, fragments };
}

/** hidden Workbench tab 与浏览器后台都不拥有 Worker；MutationObserver 负责处理无 React render 的 tab 切换。 */
function reviewElementVisible(element: HTMLElement): boolean {
  if (document.visibilityState === "hidden" || element.closest("[hidden]") !== null) return false;
  let current: HTMLElement | null = element;
  while (current !== null) {
    const style = getComputedStyle(current);
    if (style.display === "none" || style.visibility === "hidden") return false;
    current = current.parentElement;
  }
  return true;
}

interface HighlighterLifetime {
  readonly highlighter: ReviewSyntaxHighlighter;
  disposeToken?: object;
}

/**
 * 统一承载 Git 与 Turn 的单文件 Unified Diff；只消费既有结构化事实，纵向虚拟化限制
 * 1 MiB/10,000 行场景的 DOM 数量，同时保留代码长行的原生横向滚动。
 */
export function ReviewUnifiedDiff({
  file,
  revision,
  viewMode = "unified",
  onCopyText,
  renderHunkActions,
}: ReviewUnifiedDiffProps): ReactElement {
  const containerRef = useRef<HTMLElement>(null);
  const viewportRef = useRef<HTMLDivElement>(null);
  const highlighterLifetimeRef = useRef<HighlighterLifetime | undefined>(undefined);
  const fileIdentity = `${file.path}:${revision}`;
  const [expandedState, setExpandedState] = useState<{
    readonly fileIdentity: string;
    readonly keys: ReadonlySet<string>;
  }>({ fileIdentity, keys: EMPTY_SET });
  const [navigation, setNavigation] = useState({ fileIdentity, index: 0 });
  const [visible, setVisible] = useState(false);
  const [syntaxState, setSyntaxState] = useState<{
    readonly identity: string;
    readonly status: "loading" | "ready";
    readonly lines?: ReviewSyntaxResult;
  }>();
  const expanded = expandedState.fileIdentity === fileIdentity ? expandedState.keys : EMPTY_SET;
  const hunks = useMemo(() => projectHunks(file), [file]);
  const syntaxInput = useMemo(
    () => syntaxRequest(fileIdentity, file, hunks),
    [file, fileIdentity, hunks],
  );
  const lineIndexes = useMemo(
    () => new Map(file.lines.map((line, index) => [line, index])),
    [file.lines],
  );
  const rows = useMemo(
    () => projectViewRows(projectRows(hunks, expanded), viewMode),
    [expanded, hunks, viewMode],
  );
  const currentHunk = navigation.fileIdentity === fileIdentity ? navigation.index : 0;
  const maximumLineLength = useMemo(
    () => file.lines.reduce((maximum, line) => Math.max(maximum, line.text.length), 0),
    [file.lines],
  );
  // TanStack Virtual 的命令式测量/滚动 API 不可安全 memoize，限制在只读 Diff 视图内部。
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => viewportRef.current,
    estimateSize: (index) =>
      rows[index]?.kind === "line" || rows[index]?.kind === "split-line"
        ? LINE_ROW_HEIGHT
        : META_ROW_HEIGHT,
    getItemKey: (index) => rows[index]?.key ?? index,
    overscan: 18,
    initialRect: { width: 800, height: 500 },
  });
  const measuredRows = virtualizer.getVirtualItems();
  let fallbackStart = 0;
  const fallbackRows = rows.slice(0, 50).map((row, index) => {
    const size =
      row.kind === "line" || row.kind === "split-line" ? LINE_ROW_HEIGHT : META_ROW_HEIGHT;
    const virtualRow = {
      index,
      start: fallbackStart,
      size,
      end: fallbackStart + size,
      key: row.key,
      lane: 0,
    };
    fallbackStart += size;
    return virtualRow;
  });
  const renderedRows = measuredRows.length > 0 || rows.length === 0 ? measuredRows : fallbackRows;
  const contentStyle = {
    height: virtualizer.getTotalSize(),
    minWidth: `max(100%, ${
      viewMode === "split" ? maximumLineLength * 2 + 16 : maximumLineLength + 7
    }ch)`,
  } satisfies CSSProperties;

  /** 首次 commit 后才判断真实父 tab 可见性；hidden 属性变化和窗口后台化都会立即释放资源。 */
  useEffect(() => {
    const element = containerRef.current;
    if (element === null) return undefined;
    const panel = element.closest<HTMLElement>("[data-tab-panel]");
    const update = (): void => setVisible(reviewElementVisible(element));
    update();
    const observer = panel === null ? undefined : new MutationObserver(update);
    if (panel !== null) observer?.observe(panel, { attributes: true, attributeFilter: ["hidden"] });
    const resizeObserver =
      typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(update);
    resizeObserver?.observe(element);
    document.addEventListener("visibilitychange", update);
    return () => {
      observer?.disconnect();
      resizeObserver?.disconnect();
      document.removeEventListener("visibilitychange", update);
    };
  }, []);

  /** 一个可见 Diff 复用一个有界 highlighter；隐藏、卸载和 StrictMode cleanup 均终止 Worker。 */
  useEffect(() => {
    if (!visible) {
      const lifetime = highlighterLifetimeRef.current;
      highlighterLifetimeRef.current = undefined;
      lifetime?.highlighter.dispose();
      return undefined;
    }
    const lifetime = highlighterLifetimeRef.current ?? {
      highlighter: createReviewSyntaxHighlighter(),
    };
    lifetime.disposeToken = undefined;
    highlighterLifetimeRef.current = lifetime;
    return () => {
      const disposeToken = {};
      lifetime.disposeToken = disposeToken;
      queueMicrotask(() => {
        if (highlighterLifetimeRef.current === lifetime && lifetime.disposeToken === disposeToken) {
          highlighterLifetimeRef.current = undefined;
          lifetime.highlighter.dispose();
        }
      });
    };
  }, [visible]);

  /**
   * 文件切换首帧按 identity 自动回落完整纯文本；Worker 结果仅在当前 identity 仍可见时接纳，
   * 旧请求迟到与 latest 队列淘汰都保持无 UI 错误、无旧 token 闪烁。
   */
  useEffect(() => {
    if (!visible) return undefined;
    const highlighter = highlighterLifetimeRef.current?.highlighter;
    if (highlighter === undefined) return undefined;
    let accepting = true;
    // 这是异步资源状态的 commit 后同步；render 本身始终不执行 parser。
    setSyntaxState({ identity: fileIdentity, status: "loading" });
    void highlighter
      .highlight(syntaxInput)
      .then((lines) => {
        if (accepting) setSyntaxState({ identity: fileIdentity, status: "ready", lines });
      })
      .catch((error: unknown) => {
        if (accepting && !(error instanceof SupersededReviewSyntaxRequest))
          setSyntaxState((current) => (current?.identity === fileIdentity ? undefined : current));
      });
    return () => {
      accepting = false;
    };
  }, [fileIdentity, syntaxInput, visible]);

  const currentSyntax = syntaxState?.identity === fileIdentity ? syntaxState : undefined;

  /** 导航只滚到结构化 hunk header，不依赖正文文本搜索或动画结束。 */
  const navigateToHunk = (index: number): void => {
    const bounded = Math.max(0, Math.min(hunks.length - 1, index));
    const rowIndex = rows.findIndex((row) => row.kind === "hunk" && row.hunkIndex === bounded);
    if (rowIndex < 0) return;
    setNavigation({ fileIdentity, index: bounded });
    virtualizer.scrollToIndex(rowIndex, { align: "start" });
  };

  /** 展开集合按文件身份隔离，文件切换不会把旧位置的 disclosure 状态带入新文件。 */
  const toggleContext = (key: string): void => {
    const next = new Set(expanded);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    setExpandedState({ fileIdentity, keys: next });
  };

  return (
    <section
      ref={containerRef}
      className="ja-review-unified-diff"
      aria-label={`${viewMode === "split" ? "双栏" : "统一"} Diff ${file.path}`}
      data-review-unified-diff
      data-review-diff-mode={viewMode}
      data-review-diff-path={file.path}
      data-review-row-count={rows.length}
      data-review-syntax={currentSyntax?.status ?? "plain"}
    >
      <header className="ja-review-unified-diff-toolbar">
        <div className="ja-review-unified-diff-navigation" aria-label="变更区块导航">
          <IconButton
            className="ja-review-unified-diff-action"
            label="上一个变更区块"
            disabled={hunks.length === 0 || currentHunk <= 0}
            onClick={() => navigateToHunk(currentHunk - 1)}
          >
            <ChevronUp aria-hidden="true" />
          </IconButton>
          <span aria-live="polite">
            {hunks.length === 0 ? "0 / 0" : `${currentHunk + 1} / ${hunks.length}`}
          </span>
          <IconButton
            className="ja-review-unified-diff-action"
            label="下一个变更区块"
            disabled={hunks.length === 0 || currentHunk >= hunks.length - 1}
            onClick={() => navigateToHunk(currentHunk + 1)}
          >
            <ChevronDown aria-hidden="true" />
          </IconButton>
        </div>
        {onCopyText === undefined ? null : (
          <CopyTextButton
            className="ja-review-unified-diff-copy"
            text={() => copyText(file, hunks)}
            label="复制 Diff"
            onCopyText={onCopyText}
          />
        )}
      </header>
      <div ref={viewportRef} className="ja-review-unified-diff-viewport" tabIndex={0}>
        <div className="ja-review-unified-diff-spacer" style={contentStyle}>
          {renderedRows.map((virtualRow) => {
            const row = rows[virtualRow.index];
            if (row === undefined) return null;
            return (
              <div
                ref={virtualizer.measureElement}
                key={row.key}
                className={`ja-review-unified-diff-row is-${row.kind}`}
                data-index={virtualRow.index}
                data-review-diff-row-kind={row.kind === "line" ? row.line.kind : row.kind}
                data-review-hunk-index={row.kind === "hunk" ? row.hunkIndex : undefined}
                style={{ transform: `translateY(${virtualRow.start}px)` }}
              >
                {row.kind === "hunk" ? (
                  <>
                    <code>{row.hunk.header}</code>
                    {row.hunk.source === undefined || renderHunkActions === undefined ? null : (
                      <span className="ja-review-unified-diff-hunk-actions">
                        {renderHunkActions(row.hunk.source, row.hunkIndex)}
                      </span>
                    )}
                  </>
                ) : row.kind === "gap" ? (
                  <span className="ja-review-unified-diff-gap" aria-label="未加载的文件内容">
                    •••
                  </span>
                ) : row.kind === "context-toggle" ? (
                  <button
                    type="button"
                    className="ja-review-unified-diff-context"
                    aria-expanded={row.expanded}
                    aria-label={`${row.expanded ? "折叠" : "展开"} ${row.range.lines.length} 行上下文`}
                    data-review-context-toggle={row.key}
                    onClick={() => toggleContext(row.key)}
                  >
                    <span aria-hidden="true">•••</span>
                    <span>{row.range.lines.length}</span>
                  </button>
                ) : row.kind === "line" ? (
                  <>
                    <span
                      className="ja-review-unified-diff-number"
                      aria-label={unifiedLineNumber(row.line).label}
                      title={unifiedLineNumber(row.line).label}
                    >
                      {unifiedLineNumber(row.line).number ?? ""}
                    </span>
                    <span className="ja-review-unified-diff-marker" aria-hidden="true">
                      {lineMarker(row.line.kind)}
                    </span>
                    <code>
                      {currentSyntax?.lines?.[lineIndexes.get(row.line) ?? -1]?.map(
                        (token, index) => (
                          <span
                            key={index}
                            data-syntax-role={token.role}
                            style={
                              token.role === undefined
                                ? undefined
                                : { color: `var(--ja-syntax-${token.role})` }
                            }
                          >
                            {token.text}
                          </span>
                        ),
                      ) ?? row.line.text}
                    </code>
                  </>
                ) : (
                  (["old", "new"] as const).map((side) => {
                    const line = side === "old" ? row.oldLine : row.newLine;
                    const lineNumber = splitLineNumber(line, side);
                    return (
                      <div
                        key={side}
                        className={`ja-review-unified-diff-cell${line === null ? " is-empty" : ""}`}
                        data-review-diff-side={side}
                        data-review-diff-cell-kind={line?.kind ?? "empty"}
                        aria-label={line === null ? lineNumber.label : undefined}
                      >
                        <span
                          className="ja-review-unified-diff-number"
                          aria-label={line === null ? undefined : lineNumber.label}
                          title={lineNumber.label}
                        >
                          {lineNumber.number ?? ""}
                        </span>
                        <span className="ja-review-unified-diff-marker" aria-hidden="true">
                          {line === null ? " " : lineMarker(line.kind)}
                        </span>
                        <code>
                          {line === null
                            ? ""
                            : (currentSyntax?.lines?.[lineIndexes.get(line) ?? -1]?.map(
                                (token, index) => (
                                  <span
                                    key={index}
                                    data-syntax-role={token.role}
                                    style={
                                      token.role === undefined
                                        ? undefined
                                        : { color: `var(--ja-syntax-${token.role})` }
                                    }
                                  >
                                    {token.text}
                                  </span>
                                ),
                              ) ?? line.text)}
                        </code>
                      </div>
                    );
                  })
                )}
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
