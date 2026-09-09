// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { GitCompareArrows, LoaderCircle } from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import { Button } from "@/shared/ui/primitives";
import {
  createTurnReviewReadClient,
  SupersededTurnReviewRead,
  type TurnReviewReadClient,
} from "../application/turnReviewReadClient";
import type { ParsedTurnReviewFile } from "../domain/turnReviewDiff";
import type { TurnReviewFile, TurnReviewPort, TurnReviewTarget } from "../domain/turnReview";
import "./TurnReviewPanel.css";
import {
  ReviewFileTree,
  type ReviewNavigationState,
  type ReviewTreeNavigationState,
} from "./ReviewFileTree";
import { ReviewShell } from "./ReviewShell";
import { ReviewUnifiedDiff } from "./ReviewUnifiedDiff";

export interface TurnReviewPanelViewProps {
  readonly target: TurnReviewTarget;
  readonly port: TurnReviewPort;
  readonly active: boolean;
  readonly onShowWorkspaceReview: () => void;
  readonly sourceNavigation?: ReactNode;
  readonly scopeLabel?: string;
  readonly onCopyText?: (text: string) => Promise<void>;
  readonly requestedPath?: string;
  readonly requestedPathRevision?: number;
  readonly navigationState?: ReviewNavigationState;
  readonly onNavigationStateChange?: (state: ReviewNavigationState) => void;
}

interface DiffLoadResult {
  readonly key: string;
  readonly files?: readonly ParsedTurnReviewFile[];
  readonly error?: string;
}

interface ReaderLifetime {
  readonly reader: TurnReviewReadClient;
  disposeToken?: object;
}

/** 冻结身份包含 artifact；后续 Turn 不能把正在阅读的历史证据替换成新事实。 */
function reviewTargetIdentity(target: TurnReviewTarget): string {
  return `${target.workspaceId}:${target.threadId}:${target.turnId}:${target.artifactId}`;
}

/** 路径仍存在时保留用户选择，否则回到当前冻结列表第一项。 */
function retainedPath(
  files: readonly TurnReviewFile[],
  current: string | undefined,
): string | undefined {
  if (current !== undefined && files.some((file) => file.path === current)) return current;
  return files[0]?.path;
}

/** 每次选择只读取 Ja 已持久化的单文件 Diff，不提供 Git 写操作或运行中预览。 */
export function TurnReviewPanelView({
  target,
  port,
  active,
  onShowWorkspaceReview,
  sourceNavigation,
  scopeLabel = "最后一轮",
  onCopyText,
  requestedPath,
  requestedPathRevision = 0,
  navigationState,
  onNavigationStateChange,
}: TurnReviewPanelViewProps): ReactElement {
  const readerLifetimeRef = useRef<ReaderLifetime | undefined>(undefined);
  const selectedButtonRef = useRef<HTMLButtonElement | null>(null);
  const targetIdentity = reviewTargetIdentity(target);
  const selectionGenerationRef = useRef(0);
  const [reloadToken, setReloadToken] = useState(0);
  const [selectedPath, setSelectedPath] = useState<string | undefined>(
    navigationState?.selectedPath,
  );
  const [consumedPathRequestRevision, setConsumedPathRequestRevision] = useState<
    number | undefined
  >(navigationState?.consumedPathRequestRevision);
  const [query, setQuery] = useState(navigationState?.query ?? "");
  const [detailOpen, setDetailOpen] = useState(navigationState?.detailOpen ?? false);
  const [treeNavigation, setTreeNavigation] = useState<ReviewTreeNavigationState | undefined>(
    navigationState?.tree,
  );
  const [diffResult, setDiffResult] = useState<DiffLoadResult>();
  const [showLoading, setShowLoading] = useState(false);

  /** Worker 仅在面板可见时存在；隐藏立即清正文、撤销待发解析且不触发新 IO。 */
  useEffect(() => {
    if (!active) {
      const lifetime = readerLifetimeRef.current;
      readerLifetimeRef.current = undefined;
      lifetime?.reader.dispose();
      selectionGenerationRef.current += 1;
      // 隐藏是正文资源释放边界，不允许用旧 state 在重新显示时复活正文。
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setDiffResult(undefined);
      setShowLoading(false);
      return undefined;
    }
    const lifetime = readerLifetimeRef.current ?? { reader: createTurnReviewReadClient() };
    lifetime.disposeToken = undefined;
    readerLifetimeRef.current = lifetime;
    return () => {
      const disposeToken = {};
      lifetime.disposeToken = disposeToken;
      queueMicrotask(() => {
        if (readerLifetimeRef.current === lifetime && lifetime.disposeToken === disposeToken) {
          readerLifetimeRef.current = undefined;
          lifetime.reader.dispose();
        }
      });
    };
  }, [active, reloadToken]);

  const files = target.files;
  const requestedAvailable =
    requestedPath !== undefined && files.some((file) => file.path === requestedPath);
  const requestIdentity = requestedAvailable
    ? `${targetIdentity}:${requestedPath}:${requestedPathRevision}`
    : undefined;
  const pendingRequestedPath =
    requestIdentity !== undefined && consumedPathRequestRevision !== requestedPathRevision
      ? requestedPath
      : undefined;

  /** 外部文件请求只消费一次；历史卡路径不能在普通 rerender 时持续覆盖手动选择。 */
  useEffect(() => {
    if (requestIdentity === undefined || consumedPathRequestRevision === requestedPathRevision)
      return;
    // 消费外部一次性导航请求后转成本地选择，避免历史卡请求持续覆盖手动导航。
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelectedPath(requestedPath);
    setDetailOpen(true);
    setConsumedPathRequestRevision(requestedPathRevision);
    onNavigationStateChange?.({
      detailOpen: true,
      query,
      selectedPath: requestedPath,
      consumedPathRequestRevision: requestedPathRevision,
      tree: treeNavigation,
    });
  }, [
    consumedPathRequestRevision,
    onNavigationStateChange,
    query,
    requestIdentity,
    requestedPath,
    requestedPathRevision,
    treeNavigation,
  ]);

  const effectiveSelectedPath = retainedPath(files, pendingRequestedPath ?? selectedPath);
  const effectiveDetailOpen = pendingRequestedPath !== undefined || detailOpen;
  const selectedFile = files.find((file) => file.path === effectiveSelectedPath);
  const readKey = `${targetIdentity}:${effectiveSelectedPath ?? "none"}:${requestedPathRevision}:${reloadToken}`;

  /**
   * 每个新选择递增 generation；迟到结果即使路径再次相同也不能覆盖新一轮 A-B-A 选择。
   * 120ms 前保持安静，只在真实慢读取时展示轻量 loading。
   */
  useEffect(() => {
    const generation = ++selectionGenerationRef.current;
    // 请求开始即释放上一文件正文；身份栅栏确保晚到结果不能重新占用它。
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDiffResult(undefined);
    setShowLoading(false);
    if (!active || selectedFile === undefined || selectedFile.binary || selectedFile.truncated) {
      readerLifetimeRef.current?.reader.cancel();
      return undefined;
    }
    const reader = readerLifetimeRef.current?.reader;
    if (reader === undefined) return undefined;
    const timer = window.setTimeout(() => {
      if (selectionGenerationRef.current === generation) setShowLoading(true);
    }, 120);
    const parsedRead = reader.read({
      key: readKey,
      load: async (signal) => {
        const result = await port.readFrozen(target, selectedFile, signal);
        const bytes = new TextEncoder().encode(result.content).byteLength;
        if (
          result.artifactId !== target.artifactId ||
          result.filePath !== selectedFile.path ||
          result.byteLength !== bytes
        )
          throw new Error("turn review identity mismatch");
        return result.content;
      },
    });
    void parsedRead
      .then((parsedFiles) => {
        if (selectionGenerationRef.current === generation)
          setDiffResult({ key: readKey, files: parsedFiles });
      })
      .catch((error: unknown) => {
        if (
          selectionGenerationRef.current === generation &&
          !(error instanceof SupersededTurnReviewRead) &&
          !(error instanceof Error && error.name === "AbortError")
        )
          setDiffResult({ key: readKey, error: "无法读取本轮修改，请重试。" });
      })
      .finally(() => window.clearTimeout(timer));
    return () => window.clearTimeout(timer);
  }, [active, port, readKey, selectedFile, target]);

  const currentResult = diffResult?.key === readKey ? diffResult : undefined;
  const parsedFile = useMemo(
    () => currentResult?.files?.find((file) => file.path === effectiveSelectedPath),
    [currentResult?.files, effectiveSelectedPath],
  );
  const loading =
    selectedFile !== undefined &&
    !selectedFile.binary &&
    !selectedFile.truncated &&
    currentResult === undefined;

  /** 重试建立新的读取 generation，并重建可能故障的解析 Worker。 */
  const refresh = (): void => {
    const lifetime = readerLifetimeRef.current;
    readerLifetimeRef.current = undefined;
    lifetime?.reader.dispose();
    setReloadToken((current) => current + 1);
  };

  const visibleFiles = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return normalized.length === 0
      ? files
      : files.filter((file) => file.path.toLocaleLowerCase().includes(normalized));
  }, [files, query]);
  const currentIndex = visibleFiles.findIndex((file) => file.path === effectiveSelectedPath);

  /** 导航提示只保存路径、查询和树状态，不保留正文或解析结果。 */
  const publishNavigation = (next: Partial<ReviewNavigationState>): void => {
    onNavigationStateChange?.({
      detailOpen: next.detailOpen ?? detailOpen,
      query: next.query ?? query,
      selectedPath: next.selectedPath ?? effectiveSelectedPath,
      consumedPathRequestRevision,
      tree: next.tree ?? treeNavigation,
    });
  };
  const fileIdentity = useCallback(
    (file: TurnReviewFile): string => `${targetIdentity}:${file.path}`,
    [targetIdentity],
  );
  const treeFiles = useMemo(
    () =>
      visibleFiles.map((file) => ({
        id: fileIdentity(file),
        path: file.path,
        status: file.status,
        layer: "comparison" as const,
        additions: file.additions,
        deletions: file.deletions,
        binary: file.binary,
      })),
    [fileIdentity, visibleFiles],
  );

  /** 上下文件导航立即清除旧正文，避免新标题下短暂展示上一文件。 */
  const selectRelative = (offset: -1 | 1): void => {
    const file = visibleFiles[currentIndex + offset];
    if (file === undefined) return;
    setDiffResult(undefined);
    setSelectedPath(file.path);
    setDetailOpen(true);
    publishNavigation({ detailOpen: true, selectedPath: file.path });
  };

  const tree = (
    <ReviewFileTree
      files={treeFiles}
      selectedId={selectedFile === undefined ? undefined : fileIdentity(selectedFile)}
      selectedButtonRef={selectedButtonRef}
      query={query}
      onQueryChange={(next) => {
        setQuery(next);
        publishNavigation({ query: next });
      }}
      onSelect={(file) => {
        setDetailOpen(true);
        publishNavigation({ detailOpen: true, selectedPath: file.path });
        // 同一路径再次点击也重新读取，不能只清正文却不推进 effect 身份。
        if (file.path === effectiveSelectedPath) {
          refresh();
          return;
        }
        setDiffResult(undefined);
        setSelectedPath(file.path);
      }}
      navigationState={treeNavigation}
      onNavigationStateChange={(next) => {
        setTreeNavigation(next);
        publishNavigation({ tree: next });
      }}
      fileAriaLabel={(file) => `查看 ${file.path} 的本轮修改`}
      initialGrouping="directory"
      allowedGroupings={["directory", "flat"]}
      loading={false}
      emptyMessage="本轮没有变更。"
    />
  );

  const diff = (
    <main
      className="ja-turn-review-diff"
      aria-label="本轮 Unified diff"
      data-review-selected-file-id={
        selectedFile === undefined ? undefined : fileIdentity(selectedFile)
      }
      data-review-selected-layer="comparison"
    >
      {selectedFile === undefined ? null : (
        <header className="ja-turn-review-diff-header">
          <strong title={selectedFile.path}>{selectedFile.path}</strong>
          <span>{{ added: "新增", modified: "修改", deleted: "删除" }[selectedFile.status]}</span>
        </header>
      )}
      <div className="ja-turn-review-diff-content">
        {loading && showLoading ? (
          <p className="ja-turn-review-state" role="status">
            <LoaderCircle className="ja-turn-review-spin" aria-hidden="true" /> 正在读取
            {selectedFile?.path ?? "本轮修改"}…
          </p>
        ) : currentResult?.error !== undefined ? (
          <div className="ja-turn-review-state is-error" role="alert">
            <p>{currentResult.error}</p>
            <Button type="button" variant="secondary" size="sm" onClick={refresh}>
              重试
            </Button>
          </div>
        ) : selectedFile === undefined ? (
          <p className="ja-turn-review-state">当前记录没有可选择的文件。</p>
        ) : selectedFile.binary ? (
          <p className="ja-turn-review-state">二进制文件不提供文本 Diff。</p>
        ) : selectedFile.truncated ? (
          <p className="ja-turn-review-state">文件超过内容上限，无法显示文本 Diff。</p>
        ) : parsedFile === undefined ? (
          loading ? null : (
            <p className="ja-turn-review-state">所选文件没有可显示的文本差异。</p>
          )
        ) : (
          <ReviewUnifiedDiff file={parsedFile} revision={readKey} onCopyText={onCopyText} />
        )}
      </div>
    </main>
  );

  return (
    <ReviewShell
      ariaLabel="本轮修改查看"
      scopeLabel={scopeLabel}
      sourceNavigation={
        sourceNavigation ?? (
          <Button type="button" variant="ghost" size="sm" onClick={onShowWorkspaceReview}>
            <GitCompareArrows aria-hidden="true" />
            未提交
          </Button>
        )
      }
      stats={{
        files: target.stats.files,
        additions: target.stats.additions,
        deletions: target.stats.deletions,
      }}
      refreshing={loading && showLoading}
      onRefresh={refresh}
      refreshLabel="重新读取本轮修改"
      notice={
        target.state === "partial"
          ? "本轮修改可能不完整。"
          : target.stats.truncated
            ? "文件列表或统计已按安全上限截断。"
            : undefined
      }
      tree={tree}
      diff={diff}
      detailOpen={effectiveDetailOpen}
      onBack={() => {
        setDetailOpen(false);
        publishNavigation({ detailOpen: false });
        requestAnimationFrame(() => selectedButtonRef.current?.focus());
      }}
      onPreviousFile={() => selectRelative(-1)}
      onNextFile={() => selectRelative(1)}
      previousDisabled={currentIndex <= 0}
      nextDisabled={currentIndex < 0 || currentIndex >= visibleFiles.length - 1}
      dataAttributes={{ "data-turn-review-kind": "frozen_turn" }}
    />
  );
}
