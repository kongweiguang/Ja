// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  CircleCheck,
  Columns3,
  Check,
  ChevronRight,
  History,
  FileDiff,
  LoaderCircle,
  MapPin,
  MoreHorizontal,
  RefreshCw,
  Rows3,
  ScanSearch,
  ShieldAlert,
  Wrench,
  X,
} from "lucide-react";
import * as AlertDialog from "@radix-ui/react-alert-dialog";
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type MouseEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import type { ReviewAction, ReviewFileDiff, ReviewSource, ReviewTarget } from "../domain/types";
import { CodeViewer, DiffViewer } from "@/features/workbench/editor";
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuLabel,
  MenuItemIndicator,
  MenuRadioGroup,
  MenuRadioItem,
  MenuSub,
  MenuSubContent,
  MenuSubTrigger,
  MenuSeparator,
  MenuTrigger,
  Select,
} from "@/shared/ui/primitives";
import type { ReviewActions, ReviewViewModel } from "../application/useReviewController";
import {
  aiReviewRepairPrompt,
  isAiReviewRunStale,
  type AiReviewController,
  type AiReviewReport,
  type AiReviewRun,
} from "../application/useAiReviewController";
import {
  actionLabel,
  canRenderUnifiedDiff,
  canRenderTextDiff,
  canReviewAction,
  fileStatusLabel,
  reviewLayerLabel,
  sourceKey,
  sourceLabel,
} from "../domain/model";
import "./ReviewPanel.css";
import {
  ReviewFileTree,
  type ReviewNavigationState,
  type ReviewTreeNavigationState,
} from "./ReviewFileTree";
import type { ReviewTreeFile } from "../domain/reviewTree";
import { ReviewShell } from "./ReviewShell";
import { ReviewUnifiedDiff, type ReviewUnifiedDiffHunk } from "./ReviewUnifiedDiff";

export interface ReviewPanelViewProps {
  viewModel: ReviewViewModel;
  actions: ReviewActions;
  onCopyText?: (text: string) => Promise<void>;
  sourceNavigation?: ReactNode;
  navigationState?: ReviewNavigationState;
  onNavigationStateChange?: (state: ReviewNavigationState) => void;
  aiReview?: AiReviewController;
  onRepairFinding?: (
    prompt: string,
    finding: AiReviewReport["findings"][number],
    report: AiReviewReport,
  ) => Promise<{ turnId: string; queued: boolean }>;
}

/** 标题和历史菜单只显示短状态，错误正文集中在报告内显示一次。 */
function aiReviewStatusLabel(run: AiReviewRun | undefined): string | undefined {
  if (run === undefined) return undefined;
  if (run.status === "capturing") return "正在冻结审查快照";
  if (run.status === "starting") return "正在启动审查";
  if (run.status === "interrupted") return "快照已冻结，等待继续";
  switch (run.status) {
    case "queued":
      return "排队中";
    case "running":
      return "正在工作";
    case "waiting_input":
      return "等待输入";
    case "waiting_approval":
      return "需要审批";
    case "suspended":
      return "已暂停";
    case "completed":
      return "已完成";
    case "failed":
      return "审查失败";
    case "cancelled":
      return "已取消";
    default:
      return undefined;
  }
}

/** 用协议枚举显示有界快照未覆盖原因，避免把部分审查标成完整结论。 */
function reviewCoverageReason(reason: string): string {
  const labels: Readonly<Record<string, string>> = {
    binary_file: "包含二进制文件",
    non_utf8: "包含非 UTF-8 文件",
    file_limit: "达到文件数量上限",
    byte_limit: "达到内容大小上限",
    content_unavailable: "部分文件正文不可用",
    patch_unavailable: "部分差异不可用",
  };
  return labels[reason] ?? "部分内容不可用";
}

interface AiReviewFindingIdentity {
  readonly reportId: string;
  readonly findingId: string;
}

type AiReviewRepairAcceptance = AiReviewFindingIdentity & { readonly queued: boolean };
type AiReviewRepairError = AiReviewFindingIdentity & { readonly message: string };
interface FrozenEvidenceSelection {
  readonly report: AiReviewReport;
  readonly finding: AiReviewReport["findings"][number];
}

/** findingId 只在一份报告内唯一，反馈比较必须同时绑定冻结报告身份。 */
function sameAiReviewFindingIdentity(
  candidate: AiReviewFindingIdentity | undefined,
  identity: AiReviewFindingIdentity,
): boolean {
  return candidate?.reportId === identity.reportId && candidate.findingId === identity.findingId;
}

/** 使用统一 Radix Select 渲染来源，避免原生下拉在 WebView2 中脱离主题和焦点规范。 */
function SourceSelector({
  source,
  sources,
  branchLabels,
  onChange,
}: {
  source: ReviewSource;
  sources: ReviewSource[];
  branchLabels: Map<string, string>;
  onChange: (source: ReviewSource) => void;
}): ReactElement {
  const options = sources.map((candidate) => ({
    value: sourceKey(candidate),
    label: sourceLabel(candidate, branchLabels.get(sourceKey(candidate))),
  }));
  return (
    <div className="ja-review-source-select">
      <Select
        ariaLabel="审查来源"
        className="ja-review-source-control"
        size="compact"
        value={sourceKey(source)}
        options={options}
        onValueChange={(value) => {
          const next = sources.find((candidate) => sourceKey(candidate) === value);
          if (next !== undefined) onChange(next);
        }}
      />
    </div>
  );
}

/** 文本不可用时说明原因，不渲染具有误导性的空编辑器。 */
function UnavailableDiff({ diff }: { diff: ReviewFileDiff | undefined }): ReactElement {
  const label =
    diff?.binary === true
      ? "二进制文件不提供文本 Diff。"
      : diff?.truncated === true
        ? "文件超过内容上限，无法显示文本 Diff。"
        : "当前文件没有可用的安全 Diff。";
  return (
    <div className="ja-review-empty is-compact">
      <ShieldAlert aria-hidden="true" />
      <p>{label}</p>
    </div>
  );
}

/** 零 hunk 的重命名只改 Git 路径；要求冻结前后正文一致，避免隐藏真实内容变化。 */
function isContentlessRenameDiff(diff: ReviewFileDiff | undefined): boolean {
  return (
    diff?.status === "renamed" &&
    diff.oldPath !== null &&
    diff.hunks.length === 0 &&
    diff.lines.length === 0 &&
    diff.original === diff.modified
  );
}

/** 文件身份与状态沿用同一标题，纯重命名说明也保留冻结快照的旧路径和中文状态。 */
function ReviewDiffHeader({ diff }: { diff: ReviewFileDiff }): ReactElement {
  return (
    <div className="ja-review-diff-header">
      <div>
        <strong title={diff.path}>{diff.path}</strong>
        {diff.oldPath === null ? null : <small>从 {diff.oldPath} 重命名</small>}
      </div>
      <span>{fileStatusLabel(diff.status)}</span>
    </div>
  );
}

/** 为 empty、loading 与 native read 失败提供聚焦状态行。 */
function ReviewStatus({
  kind,
  message,
  onRetry,
}: {
  kind: "loading" | "empty" | "error";
  message: string;
  onRetry?: () => void;
}): ReactElement {
  return (
    <div className={`ja-review-empty is-${kind}`} role={kind === "loading" ? "status" : undefined}>
      {kind === "loading" ? (
        <LoaderCircle className="ja-review-spin" aria-hidden="true" />
      ) : kind === "error" ? (
        <ShieldAlert aria-hidden="true" />
      ) : (
        <FileDiff aria-hidden="true" />
      )}
      <p>{message}</p>
      {kind === "error" && onRetry === undefined ? null : kind === "error" ? (
        <button type="button" onClick={onRetry}>
          重新读取
        </button>
      ) : null}
    </div>
  );
}

/** 把 native action 收进单个上下文菜单，避免高频阅读区出现重复按钮阵列。 */
function ActionMenu({
  label,
  actions,
  target,
  pending,
  onAction,
}: {
  label: string;
  actions: readonly ReviewAction[];
  target: ReviewTarget;
  pending: boolean;
  onAction: (action: ReviewAction, target: ReviewTarget) => void;
}): ReactElement | null {
  if (actions.length === 0) return null;
  return (
    <Menu>
      <MenuTrigger asChild>
        <IconButton
          label={label}
          tooltip={label}
          disabled={pending}
          aria-busy={pending || undefined}
        >
          {pending ? (
            <LoaderCircle className="ja-review-spin" aria-hidden="true" />
          ) : (
            <MoreHorizontal aria-hidden="true" />
          )}
        </IconButton>
      </MenuTrigger>
      <MenuContent align="end">
        {actions.map((action) => (
          <MenuItem key={action} onSelect={() => onAction(action, target)}>
            {actionLabel(action, target)}
          </MenuItem>
        ))}
      </MenuContent>
    </Menu>
  );
}

/** 文件动作作为更多菜单的一组；进行中只禁用写操作，阅读与布局选择保持可用。 */
function FileActionItems({
  selectedActions,
  selectedTarget,
  allActions,
  pending,
  onAction,
}: {
  selectedActions: readonly ReviewAction[];
  selectedTarget: ReviewTarget | undefined;
  allActions: readonly ReviewAction[];
  pending: boolean;
  onAction: (action: ReviewAction, target: ReviewTarget) => void;
}): ReactElement | null {
  if ((selectedTarget === undefined || selectedActions.length === 0) && allActions.length === 0)
    return null;
  return (
    <>
      <MenuSeparator />
      {selectedTarget === undefined || selectedActions.length === 0 ? null : (
        <>
          <MenuLabel>当前文件</MenuLabel>
          {selectedActions.map((action) => (
            <MenuItem
              disabled={pending}
              key={`file:${action}`}
              onSelect={() => onAction(action, selectedTarget)}
            >
              {actionLabel(action, selectedTarget)}
            </MenuItem>
          ))}
        </>
      )}
      {allActions.length === 0 ? null : (
        <>
          {selectedTarget === undefined || selectedActions.length === 0 ? null : <MenuSeparator />}
          <MenuLabel>全部文件</MenuLabel>
          {allActions.map((action) => (
            <MenuItem
              disabled={pending}
              key={`all:${action}`}
              onSelect={() => onAction(action, { kind: "all" })}
            >
              {actionLabel(action, { kind: "all" })}
            </MenuItem>
          ))}
        </>
      )}
    </>
  );
}

type ReviewCapabilitiesLike = { stage: boolean; unstage: boolean; revert: boolean };

/** 在保留 native capability 校验的前提下派生 source 可见 action。 */
function availableActions(
  capabilities: ReviewCapabilitiesLike | undefined,
  source: ReviewSource,
  layer?: "staged" | "unstaged" | "untracked" | "comparison",
): ReviewAction[] {
  if (capabilities === undefined) return [];
  const candidates: ReviewAction[] =
    source.kind === "uncommitted"
      ? layer === "staged"
        ? ["unstage", "revert"]
        : layer === "unstaged" || layer === "untracked"
          ? ["stage", "revert"]
          : []
      : source.kind === "unstaged"
        ? ["stage", "revert"]
        : source.kind === "staged"
          ? ["unstage", "revert"]
          : [];
  return candidates.filter((action) => canReviewAction(capabilities, action));
}

/** 为 file/all 撤销生成明确且不暴露路径的确认说明；区块撤销保持直接操作。 */
function revertConfirmationCopy(target: ReviewTarget): { title: string; description: string } {
  return target.kind === "all"
    ? { title: "撤销全部变更？", description: "工作区中的全部未提交变更将被移除。" }
    : target.kind === "hunk"
      ? { title: "撤销区块变更？", description: "这个变更区块将从工作区移除。" }
      : { title: "撤销文件变更？", description: "这个文件的未提交变更将被移除。" };
}

/**
 * Review Shell 聚合 Git Diff 与 AI 报告；无报告终态明确说明结果未知，避免伪装为零发现。
 * 冻结证据沿用 Radix 的关闭与焦点边界，显式关闭和 Escape 进入同一回程。
 * 关闭图标保留原生 title，避免焦点触发 Tooltip 抢先消费第一次 Escape。
 * 错误提示明确显示重试动作，避免用关闭图标表达重新读取。
 */
export function ReviewPanelView({
  viewModel,
  actions,
  onCopyText,
  sourceNavigation,
  navigationState,
  onNavigationStateChange,
  aiReview,
  onRepairFinding,
}: ReviewPanelViewProps): ReactElement {
  const { state } = viewModel;
  const refreshButtonRef = useRef<HTMLButtonElement>(null);
  const retryFocusRef = useRef<
    | { scope: string; trigger: HTMLButtonElement; failure: ReviewViewModel["state"]["error"] }
    | undefined
  >(undefined);
  const retryFocusScope = JSON.stringify([
    state.catalog?.workspaceId ?? state.snapshot?.workspaceId,
    sourceKey(state.source),
  ]);

  /** 原失败投影改变才算重试推进，避免旧 passive effect 提前消费新意图；范围和用户新焦点优先。 */
  useEffect(() => {
    const pending = retryFocusRef.current;
    if (pending === undefined) return;
    if (pending.scope !== retryFocusScope) {
      retryFocusRef.current = undefined;
      return;
    }
    if (state.loading || state.catalogLoading || state.error === pending.failure) return;
    retryFocusRef.current = undefined;
    const button = refreshButtonRef.current;
    if (
      button === null ||
      !button.isConnected ||
      button.disabled ||
      button.closest('[hidden], [inert], [aria-hidden="true"]') !== null
    )
      return;
    const activeElement = button.ownerDocument.activeElement;
    if (activeElement === button.ownerDocument.body || activeElement === pending.trigger)
      button.focus({ preventScroll: true });
  }, [retryFocusScope, state.loading, state.catalogLoading, state.error]);

  /** 显式重试绑定点击时的失败对象，读取完成再归焦；被动刷新仍复用原控制器动作。 */
  const retryNotice = (event: MouseEvent<HTMLButtonElement>): void => {
    retryFocusRef.current = {
      scope: retryFocusScope,
      trigger: event.currentTarget,
      failure: state.error,
    };
    actions.refresh();
  };
  const selectedButtonRef = useRef<HTMLButtonElement | null>(null);
  const revertCancelRef = useRef<HTMLButtonElement>(null);
  const [findingLocation, setFindingLocation] = useState<{
    readonly path: string;
    readonly layer: ReviewFileDiff["layer"];
    readonly side: "base" | "current";
    readonly line: number;
    readonly token: string;
  }>();
  const [repairingFindings, setRepairingFindings] = useState<readonly AiReviewFindingIdentity[]>(
    [],
  );
  const [repairAccepted, setRepairAccepted] = useState<AiReviewRepairAcceptance>();
  const [repairError, setRepairError] = useState<AiReviewRepairError>();
  const [detailOpen, setDetailOpen] = useState(navigationState?.detailOpen ?? false);
  const [treeNavigation, setTreeNavigation] = useState<ReviewTreeNavigationState | undefined>(
    navigationState?.tree,
  );
  const [pendingRevertTarget, setPendingRevertTarget] = useState<ReviewTarget>();
  const [frozenEvidence, setFrozenEvidence] = useState<FrozenEvidenceSelection>();
  const frozenEvidenceTriggerRef = useRef<HTMLButtonElement | null>(null);
  const branchLabels = useMemo(
    () =>
      new Map(
        (state.catalog?.baseRefs ?? []).map((ref) => [
          sourceKey({ kind: "branch", refId: ref.refId }),
          ref.label,
        ]),
      ),
    [state.catalog?.baseRefs],
  );
  const capabilities = state.snapshot?.capabilities;
  const availableReviewActions = availableActions(capabilities, state.source);
  const selectedReviewActions = availableActions(
    capabilities,
    state.source,
    viewModel.selectedFile?.layer,
  );
  const selectedDiff = state.diff;
  const splitDiffAvailable = canRenderTextDiff(selectedDiff);
  const unifiedDiffAvailable = canRenderUnifiedDiff(selectedDiff);
  const aiRun = aiReview?.selectedRun;
  const aiReport = aiRun?.report;
  const aiReportStale =
    aiRun === undefined || aiReview === undefined
      ? false
      : isAiReviewRunStale(aiRun, state.source, state.snapshot?.revision);
  const aiStatus = aiReviewStatusLabel(aiRun);
  const activeReviewTurn =
    aiRun?.turnId !== undefined &&
    aiRun.status !== "completed" &&
    aiRun.status !== "failed" &&
    aiRun.status !== "cancelled";
  const historicalReportMissing =
    aiRun?.historical === true && aiRun.reportExpected === false && !activeReviewTurn;
  const terminalReviewWithoutReport =
    aiRun?.turnId !== undefined &&
    aiReport === undefined &&
    (aiRun.status === "failed" || aiRun.status === "cancelled");
  const focusedDiffLocation =
    findingLocation !== undefined &&
    selectedDiff?.path === findingLocation.path &&
    selectedDiff.layer === findingLocation.layer
      ? findingLocation
      : undefined;

  /** 当前同 revision 报告走真实 Diff；历史或过期报告只展示其冻结证据行。 */
  const locateFinding = (
    finding: AiReviewReport["findings"][number],
    trigger: HTMLButtonElement,
  ): void => {
    if (aiRun?.historical === true || aiReportStale) {
      frozenEvidenceTriggerRef.current = trigger;
      setFrozenEvidence({ report: aiReport!, finding });
      return;
    }
    const target = state.snapshot?.files.find(
      (candidate) => candidate.path === finding.path && candidate.layer === finding.layer,
    );
    if (target === undefined) return;
    actions.setFilter("all");
    actions.setLayerFilter("all");
    actions.setQuery("");
    actions.selectFile(target.fileId);
    setFindingLocation({
      path: finding.path,
      layer: finding.layer,
      side: finding.side,
      line: finding.startLine,
      token: finding.findingId,
    });
    setDetailOpen(true);
    onNavigationStateChange?.({ detailOpen: true, query: "", tree: treeNavigation });
  };

  /** 修复只提交普通用户 Turn；异步结果按冻结报告和发现隔离，ACK 不在 Review 中暴露 Turn ID。 */
  const repairFinding = async (
    finding: AiReviewReport["findings"][number],
    report: AiReviewReport,
  ): Promise<void> => {
    if (onRepairFinding === undefined || aiReportStale || aiRun?.historical === true) return;
    const identity: AiReviewFindingIdentity = {
      reportId: report.reportId,
      findingId: finding.findingId,
    };
    if (repairingFindings.some((candidate) => sameAiReviewFindingIdentity(candidate, identity)))
      return;
    setRepairingFindings((current) => [...current, identity]);
    setRepairError((current) =>
      sameAiReviewFindingIdentity(current, identity) ? undefined : current,
    );
    setRepairAccepted((current) =>
      sameAiReviewFindingIdentity(current, identity) ? undefined : current,
    );
    try {
      const accepted = await onRepairFinding(
        aiReviewRepairPrompt(report, finding),
        finding,
        report,
      );
      setRepairAccepted({ ...identity, queued: accepted.queued });
    } catch {
      setRepairError({ ...identity, message: "修复提交失败，请重试。" });
    } finally {
      setRepairingFindings((current) =>
        current.filter((candidate) => !sameAiReviewFindingIdentity(candidate, identity)),
      );
    }
  };

  /** file/all 撤销进入应用内确认态，避免 Tauri WebView2 拦截 window.confirm 后静默失效。 */
  const onAction = (action: ReviewAction, target: ReviewTarget): void => {
    if (action === "revert") {
      setPendingRevertTarget(target);
      return;
    }
    void actions.applyAction(action, target);
  };

  /** 从最新 capability 和文件层派生菜单项；操作进行中时不开放并发 mutation。 */
  const fileContextActions = (file: ReviewTreeFile): readonly ReviewAction[] =>
    state.pendingOperationIds.size > 0
      ? []
      : availableActions(capabilities, state.source, file.layer);

  /** 在执行前重新确认 clicked file id/layer 仍属于当前快照和当前来源。 */
  const applyFileContextAction = (file: ReviewTreeFile, action: ReviewAction): void => {
    if (state.pendingOperationIds.size > 0) return;
    const currentFile = viewModel.visibleFiles.find(
      (candidate) => candidate.fileId === file.id && candidate.layer === file.layer,
    );
    if (
      currentFile === undefined ||
      !availableActions(capabilities, state.source, currentFile.layer).includes(action)
    )
      return;
    onAction(action, { kind: "file", fileId: currentFile.fileId });
  };

  /** 只消费当前冻结的 target 一次；先关闭 Dialog，再把真实 mutation 交回 controller。 */
  const confirmPendingRevert = (): void => {
    if (pendingRevertTarget === undefined) return;
    const target = pendingRevertTarget;
    setPendingRevertTarget(undefined);
    void actions.applyAction("revert", target);
  };

  const revertCopy =
    pendingRevertTarget === undefined ? undefined : revertConfirmationCopy(pendingRevertTarget);

  /** 把紧凑状态通知解析为安全的可见文案。 */
  const notice = state.error?.message ?? state.notice;
  const currentFileIndex = viewModel.visibleFiles.findIndex(
    (file) => file.fileId === state.selectedFileId,
  );
  const selectedFileTarget =
    viewModel.selectedFile === undefined
      ? undefined
      : ({ kind: "file", fileId: viewModel.selectedFile.fileId } as const);

  /** 文件导航复用 controller 的严格 fileId 选择，越界时保持当前 Diff。 */
  const selectRelativeFile = (offset: -1 | 1): void => {
    const next = viewModel.visibleFiles[currentFileIndex + offset];
    if (next !== undefined) {
      actions.selectFile(next.fileId);
      setDetailOpen(true);
      onNavigationStateChange?.({ detailOpen: true, query: state.query, tree: treeNavigation });
    }
  };

  const fallbackNavigation = (
    <SourceSelector
      source={state.source}
      sources={viewModel.sourceOptions}
      branchLabels={branchLabels}
      onChange={actions.setSource}
    />
  );
  // 图标使用真实阶段和可访问名称；取消与重试复用原控制器，不引入第二套运行状态。
  const aiRetryable =
    aiRun !== undefined && (aiRun.error !== undefined || aiRun.status === "interrupted");
  const aiActionLabel = activeReviewTurn
    ? "取消 AI 审查"
    : aiRetryable
      ? "重试 AI 审查"
      : aiReview?.state.historyLoading
        ? "读取记录中"
        : aiReview?.busy
          ? "审查中"
          : (aiReview?.state.runs.length ?? 0) > 0
            ? "重新审查"
            : "AI 审查";
  /** 单个主入口跟随当前审查阶段，点击时交回同一控制器，避免额外的按钮状态机。 */
  const runAiAction = (): void => {
    if (aiReview === undefined) return;
    if (activeReviewTurn && aiRun !== undefined) void aiReview.actions.cancel(aiRun.runId);
    else if (aiRetryable && aiRun !== undefined) void aiReview.actions.retry(aiRun.runId);
    else void aiReview.actions.start();
  };
  const primaryAction =
    aiReview === undefined ? undefined : (
      <IconButton
        label={aiActionLabel}
        tooltip={aiActionLabel}
        aria-busy={(aiReview.busy && !aiRetryable) || undefined}
        disabled={
          activeReviewTurn
            ? aiRun?.cancelPending
            : !aiReview.ready ||
              state.snapshot === undefined ||
              (aiRetryable
                ? aiReview.state.historyLoading || aiReview.state.loadingReportId === aiRun?.runId
                : aiReview.busy)
        }
        onClick={runAiAction}
      >
        {activeReviewTurn ? (
          <X aria-hidden="true" />
        ) : aiReview.busy && !aiRetryable ? (
          <LoaderCircle className="ja-review-spin" aria-hidden="true" />
        ) : (
          <ScanSearch aria-hidden="true" />
        )}
      </IconButton>
    );
  const menuItems = (
    <>
      <MenuRadioGroup
        value={state.viewMode}
        onValueChange={(value) => actions.setViewMode(value as "unified" | "split")}
        aria-label="Diff 布局"
      >
        <MenuRadioItem value="unified">
          <Rows3 aria-hidden="true" />
          统一 Diff
          <MenuItemIndicator className="ja-review-menu-check">
            <Check aria-hidden="true" />
          </MenuItemIndicator>
        </MenuRadioItem>
        <MenuRadioItem value="split">
          <Columns3 aria-hidden="true" />
          双栏 Diff
          <MenuItemIndicator className="ja-review-menu-check">
            <Check aria-hidden="true" />
          </MenuItemIndicator>
        </MenuRadioItem>
      </MenuRadioGroup>
      {aiReview === undefined ||
      (aiReview.state.runs.length <= 1 && !aiReview.state.historyHasMore) ? null : (
        <MenuSub>
          <MenuSubTrigger>
            <History aria-hidden="true" />
            审查记录
            <ChevronRight className="ja-review-menu-chevron" aria-hidden="true" />
          </MenuSubTrigger>
          <MenuSubContent className="ja-review-history-menu" aria-label="审查记录">
            <MenuRadioGroup value={aiRun?.runId ?? ""} onValueChange={aiReview.actions.select}>
              {aiReview.state.runs.map((run, index) => (
                <MenuRadioItem key={run.runId} value={run.runId}>
                  <span>{`${run.historical ? "历史审查" : "审查"} ${aiReview.state.runs.length - index} · ${aiReviewStatusLabel(run) ?? "快照"}`}</span>
                  <MenuItemIndicator className="ja-review-menu-check">
                    <Check aria-hidden="true" />
                  </MenuItemIndicator>
                </MenuRadioItem>
              ))}
            </MenuRadioGroup>
            {aiReview.state.historyHasMore ? (
              <>
                <MenuSeparator />
                <MenuItem
                  disabled={aiReview.state.historyLoading}
                  onSelect={() => void aiReview.actions.loadOlderHistory()}
                >
                  读取更早审查
                </MenuItem>
              </>
            ) : null}
          </MenuSubContent>
        </MenuSub>
      )}
      <FileActionItems
        selectedActions={selectedReviewActions}
        selectedTarget={selectedFileTarget}
        allActions={availableReviewActions}
        pending={state.pendingOperationIds.size > 0}
        onAction={onAction}
      />
    </>
  );
  const aiFeedback = (
    <>
      {aiReview?.state.historyLoading && aiRun === undefined ? (
        <p className="ja-review-ai-summary" role="status">
          正在读取审查记录…
        </p>
      ) : null}
      {aiReview?.state.historyError === undefined ? null : (
        <div className="ja-review-ai-panel-controls">
          <span className="ja-review-ai-panel-status is-error" role="alert">
            {aiReview.state.historyError.message}
          </span>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={aiReview.state.historyLoading}
            onClick={() => void aiReview.actions.retryHistory()}
          >
            重试读取
          </Button>
        </div>
      )}
      {aiRun?.error === undefined ? null : (
        <div className="ja-review-ai-panel-controls">
          <span className="ja-review-ai-panel-status is-error" role="alert">
            {aiRun.error.message}
          </span>
          {activeReviewTurn && aiReview !== undefined ? (
            <Button
              type="button"
              variant="secondary"
              size="sm"
              disabled={aiReview.state.loadingReportId === aiRun.runId}
              onClick={() => void aiReview.actions.retry(aiRun.runId)}
            >
              重试读取
            </Button>
          ) : null}
        </div>
      )}
    </>
  );
  const reviewPanel =
    aiRun === undefined ? (
      aiReview?.state.historyError !== undefined || aiReview?.state.historyLoading ? (
        <section className="ja-review-ai-panel">{aiFeedback}</section>
      ) : undefined
    ) : (
      <section className="ja-review-ai-panel" aria-label="AI 审查结果">
        <header className="ja-review-ai-panel-header">
          <div className="ja-review-ai-panel-title">
            {aiReport !== undefined && !aiReportStale ? (
              <CircleCheck aria-hidden="true" />
            ) : (
              <ScanSearch aria-hidden="true" />
            )}
            <span role={aiReport === undefined ? "status" : undefined}>
              {aiReport === undefined ? (aiStatus ?? "审查结果") : "审查结果"}
            </span>
            {aiReport === undefined ? null : <span>{aiReport.findings.length} 项发现</span>}
            {aiReport === undefined || !activeReviewTurn ? null : (
              <span role="status">{aiStatus}</span>
            )}
          </div>
        </header>
        {aiFeedback}
        {aiReport === undefined ? (
          aiReview?.state.loadingReportId === aiRun.runId ? (
            <p className="ja-review-ai-summary" role="status">
              正在读取历史报告…
            </p>
          ) : historicalReportMissing ? (
            <>
              <p className="ja-review-ai-summary" role="status">
                此审查已结束，但未提交有效报告；不能据此判断为零发现。
              </p>
              <div className="ja-review-ai-panel-controls">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  disabled={!aiReview?.ready || aiReview.busy || state.snapshot === undefined}
                  onClick={() => void aiReview?.actions.start()}
                >
                  <ScanSearch aria-hidden="true" />
                  重新审查当前内容
                </Button>
              </div>
            </>
          ) : terminalReviewWithoutReport ? (
            <>
              <p
                className="ja-review-ai-summary"
                role={aiRun?.status === "failed" ? "alert" : "status"}
              >
                {aiRun?.status === "failed"
                  ? "本次审查失败，未生成报告。错误详情见对话，不能据此判断为零发现。"
                  : "本次审查已取消，未生成报告；不能据此判断为零发现。"}
              </p>
              <div className="ja-review-ai-panel-controls">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  disabled={
                    aiReview === undefined ||
                    !aiReview.ready ||
                    aiReview.busy ||
                    state.snapshot === undefined
                  }
                  onClick={() => void aiReview?.actions.start()}
                >
                  <ScanSearch aria-hidden="true" />
                  重新审查当前内容
                </Button>
              </div>
            </>
          ) : null
        ) : (
          <>
            {aiReportStale ? (
              <p className="ja-review-ai-stale" role="status">
                报告基于不同快照。定位仅展示冻结发现证据；需要对当前内容处理时请重新审查。
              </p>
            ) : null}
            {aiRun?.historical ? (
              <p className="ja-review-ai-coverage" role="status">
                历史审查只读。定位展示冻结发现证据行，不代表完整 Diff 或当前文件。
              </p>
            ) : null}
            <p className="ja-review-ai-summary">
              {aiReport.summary ||
                (aiReport.findings.length === 0
                  ? aiReport.coverage.reviewComplete
                    ? "未发现需要处理的问题。"
                    : "在已审内容中未发现需要处理的问题。"
                  : "审查完成。")}
            </p>
            <p className="ja-review-ai-coverage">
              {aiReport.coverage.reviewComplete ? "覆盖完整" : "覆盖不完整"}：
              {aiReport.coverage.filesReviewed.length}/{aiReport.coverage.files} 个文件
              {aiReport.coverage.partialReasons.length === 0
                ? ""
                : ` · ${aiReport.coverage.partialReasons.map(reviewCoverageReason).join("、")}`}
            </p>
            {aiReport.findings.length === 0 ? null : (
              <ol className="ja-review-ai-findings">
                {aiReport.findings.map((finding) => {
                  const target = state.snapshot?.files.find(
                    (candidate) =>
                      candidate.path === finding.path && candidate.layer === finding.layer,
                  );
                  const identity: AiReviewFindingIdentity = {
                    reportId: aiReport.reportId,
                    findingId: finding.findingId,
                  };
                  const isRepairing = repairingFindings.some((candidate) =>
                    sameAiReviewFindingIdentity(candidate, identity),
                  );
                  const reportRepairing = repairingFindings.some(
                    (candidate) => candidate.reportId === identity.reportId,
                  );
                  const accepted = sameAiReviewFindingIdentity(repairAccepted, identity)
                    ? repairAccepted
                    : undefined;
                  const repairFailure = sameAiReviewFindingIdentity(repairError, identity)
                    ? repairError
                    : undefined;
                  return (
                    <li key={finding.findingId} className="ja-review-ai-finding">
                      <div className="ja-review-ai-finding-title">
                        <span className="ja-review-ai-severity" data-severity={finding.severity}>
                          {finding.severity}
                        </span>
                        <span>{finding.title}</span>
                      </div>
                      <span className="ja-review-ai-finding-location">
                        {finding.path}:{finding.startLine}-{finding.endLine} ·{" "}
                        {reviewLayerLabel(finding.layer)}
                      </span>
                      <div className="ja-review-ai-finding-actions">
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          disabled={
                            target === undefined && !aiReportStale && aiRun?.historical !== true
                          }
                          onClick={(event) => locateFinding(finding, event.currentTarget)}
                        >
                          <MapPin aria-hidden="true" />
                          定位
                        </Button>
                        {onRepairFinding === undefined ||
                        aiReportStale ||
                        aiRun?.historical ? null : (
                          <Button
                            type="button"
                            variant="secondary"
                            size="sm"
                            disabled={reportRepairing || target === undefined || aiReportStale}
                            aria-busy={isRepairing || undefined}
                            onClick={() => void repairFinding(finding, aiReport)}
                          >
                            {isRepairing ? (
                              <LoaderCircle className="ja-review-spin" aria-hidden="true" />
                            ) : (
                              <Wrench aria-hidden="true" />
                            )}
                            修复
                          </Button>
                        )}
                      </div>
                      <p className="ja-review-ai-finding-body">{finding.body}</p>
                      <p className="ja-review-ai-finding-evidence">证据：{finding.evidence}</p>
                      {accepted === undefined ? null : (
                        <span className="ja-review-ai-repair-feedback" role="status">
                          {accepted.queued ? "修复已排队" : "修复已提交"}
                        </span>
                      )}
                      {repairFailure === undefined ? null : (
                        <span className="ja-review-ai-panel-status is-error" role="alert">
                          {repairFailure.message}
                        </span>
                      )}
                    </li>
                  );
                })}
              </ol>
            )}
          </>
        )}
      </section>
    );
  const treeFiles = useMemo(
    () =>
      viewModel.visibleFiles.map((file) => ({
        id: file.fileId,
        path: file.path,
        status: file.status,
        layer: file.layer,
        additions: file.additions,
        deletions: file.deletions,
        binary: file.binary,
      })),
    [viewModel.visibleFiles],
  );
  const tree = (
    <ReviewFileTree
      files={treeFiles}
      selectedId={state.selectedFileId}
      selectedButtonRef={selectedButtonRef}
      query={state.query}
      onQueryChange={actions.setQuery}
      onSelect={(file) => {
        actions.selectFile(file.id);
        setDetailOpen(true);
        onNavigationStateChange?.({ detailOpen: true, query: state.query, tree: treeNavigation });
      }}
      fileContextActions={fileContextActions}
      onFileContextAction={applyFileContextAction}
      navigationState={treeNavigation}
      onNavigationStateChange={(next) => {
        setTreeNavigation(next);
        onNavigationStateChange?.({ detailOpen, query: state.query, tree: next });
      }}
      loading={state.loading}
      queryResultsAvailable={state.snapshot !== undefined}
      emptyMessage={state.snapshot === undefined ? "文件列表尚未读取。" : "当前范围没有变更。"}
    />
  );
  const diff = (
    <div
      className="ja-review-diff"
      aria-label="变更 Diff"
      data-review-selected-file-id={viewModel.selectedFile?.fileId}
      data-review-selected-layer={viewModel.selectedFile?.layer}
    >
      {state.loading ? (
        <ReviewStatus kind="loading" message="正在读取审阅…" />
      ) : state.diffError !== undefined ? (
        <ReviewStatus
          kind="error"
          message={
            state.diffError.code === "PARSE" && viewModel.selectedFile?.status === "conflicted"
              ? "此文件有合并冲突，暂不支持显示组合 Diff。请解决冲突后重新读取。"
              : state.diffError.message
          }
          onRetry={actions.refresh}
        />
      ) : state.snapshot === undefined ? (
        <ReviewStatus kind="empty" message="没有可用的审阅快照。" />
      ) : viewModel.selectedFile !== undefined && state.diffLoading ? (
        <ReviewStatus kind="loading" message={`正在读取 ${viewModel.selectedFile.path}…`} />
      ) : viewModel.selectedFile?.binary ? (
        <ReviewStatus kind="empty" message="二进制文件不提供文本 Diff。" />
      ) : viewModel.selectedFile?.truncated ? (
        <ReviewStatus kind="empty" message="文件超过内容上限，无法显示文本 Diff。" />
      ) : selectedDiff !== undefined && isContentlessRenameDiff(selectedDiff) ? (
        <>
          <ReviewDiffHeader diff={selectedDiff} />
          <ReviewStatus kind="empty" message="仅重命名，无正文差异。" />
        </>
      ) : selectedDiff === undefined ? (
        <ReviewStatus kind="empty" message="选择文件查看 Diff。" />
      ) : (
        <>
          <ReviewDiffHeader diff={selectedDiff} />
          {splitDiffAvailable && state.viewMode === "split" ? (
            <DiffViewer
              filePath={selectedDiff.path}
              original={selectedDiff.original ?? ""}
              modified={selectedDiff.modified ?? ""}
              revision={selectedDiff.revision}
              scrollLocation={
                focusedDiffLocation === undefined
                  ? undefined
                  : {
                      side: focusedDiffLocation.side === "base" ? "original" : "modified",
                      line: focusedDiffLocation.line,
                      token: focusedDiffLocation.token,
                    }
              }
              onCopyText={onCopyText}
            />
          ) : unifiedDiffAvailable ? (
            <ReviewUnifiedDiff
              file={selectedDiff}
              viewMode={state.viewMode}
              revision={`${selectedDiff.revision}:${selectedDiff.fileId}`}
              focusLocation={focusedDiffLocation}
              onCopyText={onCopyText}
              renderHunkActions={(hunk: ReviewUnifiedDiffHunk) =>
                hunk.hunkId === undefined ? null : (
                  <ActionMenu
                    label="区块操作"
                    actions={selectedReviewActions}
                    target={{ kind: "hunk", fileId: selectedDiff.fileId, hunkId: hunk.hunkId }}
                    pending={state.pendingOperationIds.size > 0}
                    onAction={onAction}
                  />
                )
              }
            />
          ) : splitDiffAvailable ? (
            <DiffViewer
              filePath={selectedDiff.path}
              original={selectedDiff.original ?? ""}
              modified={selectedDiff.modified ?? ""}
              revision={selectedDiff.revision}
              scrollLocation={
                focusedDiffLocation === undefined
                  ? undefined
                  : {
                      side: focusedDiffLocation.side === "base" ? "original" : "modified",
                      line: focusedDiffLocation.line,
                      token: focusedDiffLocation.token,
                    }
              }
              onCopyText={onCopyText}
            />
          ) : (
            <UnavailableDiff diff={selectedDiff} />
          )}
        </>
      )}
    </div>
  );
  return (
    <>
      <ReviewShell
        ariaLabel="审阅"
        scopeLabel={sourceLabel(state.source, branchLabels.get(sourceKey(state.source)))}
        sourceNavigation={sourceNavigation ?? fallbackNavigation}
        stats={
          state.snapshot === undefined
            ? undefined
            : {
                files: state.snapshot.stats.files,
                additions: state.snapshot.stats.additions,
                deletions: state.snapshot.stats.deletions,
              }
        }
        refreshing={state.loading || state.catalogLoading}
        onRefresh={actions.refresh}
        refreshLabel="重新读取审阅"
        refreshButtonRef={refreshButtonRef}
        notice={
          notice === undefined ? undefined : (
            <button
              type="button"
              className={`ja-review-notice${state.error === undefined ? "" : " is-error"}`}
              onClick={state.error === undefined ? actions.clearNotice : retryNotice}
            >
              <span>{notice}</span>
              {state.error === undefined ? (
                <X aria-hidden="true" />
              ) : (
                <span className="ja-review-notice-action">
                  <RefreshCw aria-hidden="true" />
                  重试
                </span>
              )}
            </button>
          )
        }
        primaryAction={primaryAction}
        menuItems={menuItems}
        reviewPanel={reviewPanel}
        tree={tree}
        diff={diff}
        detailOpen={detailOpen}
        onBack={() => {
          setDetailOpen(false);
          onNavigationStateChange?.({
            detailOpen: false,
            query: state.query,
            tree: treeNavigation,
          });
          requestAnimationFrame(() => selectedButtonRef.current?.focus());
        }}
        onPreviousFile={() => selectRelativeFile(-1)}
        onNextFile={() => selectRelativeFile(1)}
        previousDisabled={currentFileIndex <= 0}
        nextDisabled={currentFileIndex < 0 || currentFileIndex >= viewModel.visibleFiles.length - 1}
        dataAttributes={{ "data-source": state.source.kind }}
      />
      <AlertDialog.Root
        open={pendingRevertTarget !== undefined}
        onOpenChange={(open) => {
          if (!open) setPendingRevertTarget(undefined);
        }}
      >
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="ja-review-confirm-overlay" />
          <AlertDialog.Content
            className="ja-review-confirm-dialog"
            onOpenAutoFocus={(event) => {
              event.preventDefault();
              revertCancelRef.current?.focus();
            }}
          >
            <AlertDialog.Title>{revertCopy?.title}</AlertDialog.Title>
            <AlertDialog.Description>{revertCopy?.description}</AlertDialog.Description>
            <div className="ja-review-confirm-actions">
              <AlertDialog.Cancel asChild>
                <button
                  ref={revertCancelRef}
                  type="button"
                  className="ja-button ja-button-secondary ja-button-sm"
                >
                  取消
                </button>
              </AlertDialog.Cancel>
              <AlertDialog.Action asChild>
                <Button type="button" variant="danger" size="sm" onClick={confirmPendingRevert}>
                  确认撤销
                </Button>
              </AlertDialog.Action>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
      <Dialog
        open={frozenEvidence !== undefined}
        onOpenChange={(open) => {
          if (!open) setFrozenEvidence(undefined);
        }}
      >
        {frozenEvidence === undefined ? null : (
          <DialogContent
            className="ja-review-frozen-evidence-dialog"
            onCloseAutoFocus={(event) => {
              event.preventDefault();
              window.requestAnimationFrame(() => {
                if (frozenEvidenceTriggerRef.current?.isConnected)
                  frozenEvidenceTriggerRef.current.focus();
              });
            }}
          >
            <div className="ja-review-frozen-evidence-heading">
              <DialogTitle>冻结发现证据</DialogTitle>
              <DialogClose asChild>
                <Button
                  className="ja-review-frozen-evidence-close"
                  variant="ghost"
                  size="sm"
                  aria-label="关闭冻结证据"
                  title="关闭冻结证据"
                >
                  <X aria-hidden="true" />
                </Button>
              </DialogClose>
            </div>
            <DialogDescription>
              仅包含审查报告引用的证据行，不是完整 Diff 或当前文件。
            </DialogDescription>
            <div className="ja-review-frozen-evidence-meta">
              <strong title={frozenEvidence.finding.path}>{frozenEvidence.finding.path}</strong>
              <span>
                {reviewLayerLabel(frozenEvidence.finding.layer)} ·
                {frozenEvidence.finding.side === "base" ? " 基准侧" : " 当前侧"} · 第{" "}
                {frozenEvidence.finding.startLine}-{frozenEvidence.finding.endLine} 行 · 快照{" "}
                {frozenEvidence.report.snapshotId}
              </span>
            </div>
            <CodeViewer
              filePath={frozenEvidence.finding.path}
              content={frozenEvidence.finding.evidence}
              lineNumberStart={frozenEvidence.finding.startLine}
              revision={`${frozenEvidence.report.snapshotHash}:${frozenEvidence.finding.findingId}`}
              onCopyText={onCopyText}
            />
          </DialogContent>
        )}
      </Dialog>
    </>
  );
}
