// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createOperationKey } from "@/shared/operationKey";
import type {
  AiReviewAccepted,
  AiReviewCapture,
  AiReviewGateway,
  AiReviewHistoryPage,
  AiReviewReadInput,
  AiReviewReport,
  AiReviewResult,
  AiReviewTurnStatus,
} from "../domain/aiReview";
import { sourceKey } from "../domain/model";
import type { ReviewSource } from "../domain/types";
import { normalizeReviewFailure, type ReviewFailure } from "./ports";

export type { AiReviewReport, AiReviewTurnStatus } from "../domain/aiReview";

/** 普通实施 Turn 只携带经报告验证的发现身份与证据，模型仍须重读当前文件后决定是否修复。 */
export function aiReviewRepairPrompt(
  report: AiReviewReport,
  finding: AiReviewReport["findings"][number],
): string {
  return [
    "请检查并修复以下代码审查发现。先重新读取当前文件并核实问题仍然存在；若已不存在，请说明原因，不要重复修改。",
    `报告：${report.reportId}，冻结快照：${report.snapshotId} (${report.snapshotHash})。`,
    `发现：${finding.findingId} [${finding.severity}] ${finding.title}`,
    `位置：${finding.path}:${finding.startLine}-${finding.endLine} (${finding.layer}, ${finding.side})`,
    `说明：${finding.body}`,
    `证据：${finding.evidence}`,
    "确认仍存在后，按最小必要改动修复，并说明验证结果。",
  ].join("\n");
}

export interface AiReviewRun {
  readonly runId: string;
  readonly operationKey?: string;
  readonly source?: ReviewSource;
  readonly sourceRevision?: string;
  readonly status: AiReviewTurnStatus | "capturing" | "starting" | "interrupted";
  readonly capture?: AiReviewCapture;
  readonly turnId?: string;
  readonly report?: AiReviewReport;
  readonly historical?: boolean;
  readonly reportExpected?: boolean;
  readonly error?: ReviewFailure;
  readonly cancelPending?: boolean;
  readonly createdAt: string;
}

export interface AiReviewControllerState {
  readonly runs: readonly AiReviewRun[];
  readonly selectedRunId?: string;
  readonly loadingReportId?: string;
  readonly historyLoading?: boolean;
  readonly historyError?: ReviewFailure;
  readonly historyHasMore?: boolean;
  readonly historyNextCursor?: string | null;
  readonly historyRevision?: number;
}

export interface AiReviewActions {
  readonly start: () => Promise<void>;
  readonly retry: (runId: string) => Promise<void>;
  readonly refresh: (runId: string) => Promise<void>;
  readonly loadOlderHistory: () => Promise<void>;
  readonly retryHistory: () => Promise<void>;
  readonly cancel: (runId: string) => Promise<void>;
  readonly select: (runId: string) => void;
}

export interface AiReviewController {
  readonly state: AiReviewControllerState;
  readonly selectedRun: AiReviewRun | undefined;
  readonly ready: boolean;
  readonly busy: boolean;
  readonly actions: AiReviewActions;
}

export interface UseAiReviewControllerOptions {
  readonly active: boolean;
  readonly workspaceId: string | undefined;
  readonly threadId: string | undefined;
  readonly threadRevision: number | undefined;
  readonly runtimeGeneration?: number;
  readonly serverInstanceId?: string;
  readonly source: ReviewSource;
  readonly snapshotRevision: string | undefined;
  readonly gateway: AiReviewGateway;
  readonly cancelTurn?: (turnId: string) => Promise<void>;
  readonly onTurnAccepted?: (accepted: AiReviewAccepted) => void;
}

interface OwnedState extends AiReviewControllerState {
  readonly ownerKey: string;
}

interface CurrentContext extends UseAiReviewControllerOptions {
  readonly ownerKey: string;
}

/** 历史页只对发起请求时的可见 Thread、revision 与 Runtime 实例有效。 */
function ownsAiReviewHistoryRequest(current: CurrentContext, initial: CurrentContext): boolean {
  return (
    current.ownerKey === initial.ownerKey &&
    current.threadId === initial.threadId &&
    current.workspaceId === initial.workspaceId &&
    current.threadRevision === initial.threadRevision &&
    current.runtimeGeneration === initial.runtimeGeneration &&
    current.serverInstanceId === initial.serverInstanceId
  );
}

/** 新审查只用 source CAS 判断过期；历史或 CAS 身份未知的运行不推断新鲜度。 */
export function isAiReviewRunStale(
  run: AiReviewRun,
  source: ReviewSource,
  snapshotRevision: string | undefined,
): boolean {
  if (run.historical || run.sourceRevision === undefined) return false;
  if (run.sourceRevision !== snapshotRevision) return true;
  return run.source === undefined || sourceKey(run.source) !== sourceKey(source);
}

/** UI 阶段状态不属于 RPC Turn 状态；只有服务端终态才结束该运行。 */
function isRunTerminal(status: AiReviewRun["status"]): boolean {
  return status === "completed" || status === "failed" || status === "cancelled";
}

/** 单飞同一报告读取，避免显式重试与可见轮询争抢同一个 Turn。 */
function singleflightRead<T>(
  requests: Map<string, Promise<T>>,
  key: string,
  run: () => Promise<T>,
): Promise<T> {
  const pending = requests.get(key);
  if (pending !== undefined) return pending;
  const request = run();
  requests.set(key, request);
  const release = (): void => {
    if (requests.get(key) === request) requests.delete(key);
  };
  void request.then(release, release);
  return request;
}

/** 按 Workspace 与 Thread 隔离报告导航状态；服务端仍是历史报告的权威持有者。 */
export function useAiReviewController({
  active,
  workspaceId,
  threadId,
  threadRevision,
  runtimeGeneration,
  serverInstanceId,
  source,
  snapshotRevision,
  gateway,
  cancelTurn,
  onTurnAccepted,
}: UseAiReviewControllerOptions): AiReviewController {
  const ownerKey = JSON.stringify([
    workspaceId ?? null,
    threadId ?? null,
    runtimeGeneration ?? null,
    serverInstanceId ?? null,
  ]);
  const contextRef = useRef<CurrentContext>({
    active,
    workspaceId,
    threadId,
    threadRevision,
    runtimeGeneration,
    serverInstanceId,
    source,
    snapshotRevision,
    gateway,
    cancelTurn,
    onTurnAccepted,
    ownerKey,
  });
  contextRef.current = {
    active,
    workspaceId,
    threadId,
    threadRevision,
    runtimeGeneration,
    serverInstanceId,
    source,
    snapshotRevision,
    gateway,
    cancelTurn,
    onTurnAccepted,
    ownerKey,
  };
  const [ownedState, setOwnedState] = useState<OwnedState>({ ownerKey, runs: [] });
  const runs = useMemo(
    () => (ownedState.ownerKey === ownerKey ? ownedState.runs : []),
    [ownerKey, ownedState.ownerKey, ownedState.runs],
  );
  const selectedRunId = ownedState.ownerKey === ownerKey ? ownedState.selectedRunId : undefined;
  const loadingReportId = ownedState.ownerKey === ownerKey ? ownedState.loadingReportId : undefined;
  const readRequestsRef = useRef(new Map<string, Promise<AiReviewResult>>());
  const historyRequestRef = useRef<{ key: string; request: Promise<boolean> } | undefined>(
    undefined,
  );
  const historyLoadedKeyRef = useRef<string | undefined>(undefined);
  const historyRetryCursorRef = useRef<string | undefined>(undefined);
  const historyCursorStateRef = useRef<
    | {
        readonly ownerKey: string;
        readonly revision: number;
        readonly cursors: ReadonlySet<string>;
        readonly itemIds: ReadonlySet<string>;
      }
    | undefined
  >(undefined);
  const autoReadRunRef = useRef<string | undefined>(undefined);

  /** 同步更新所选 Thread 的报告索引；异步旧 scope 只能被丢弃。 */
  const updateRun = useCallback(
    (runId: string, update: (run: AiReviewRun) => AiReviewRun): void => {
      setOwnedState((previous) => {
        if (previous.ownerKey !== ownerKey) return previous;
        return {
          ...previous,
          runs: previous.runs.map((run) => (run.runId === runId ? update(run) : run)),
        };
      });
    },
    [ownerKey],
  );

  /** 新 tail 独立去重并按 Turn 合并；未受理的本地请求须保留，避免并发刷新丢失错误与幂等重试身份。 */
  const readHistoryPage = useCallback(
    async (cursor?: string): Promise<boolean> => {
      const initial = contextRef.current;
      if (!initial.active || initial.workspaceId === undefined || initial.threadId === undefined)
        return false;
      const threadId = initial.threadId;
      const key = JSON.stringify([ownerKey, cursor ?? "tail", initial.threadRevision ?? null]);
      if (historyRequestRef.current?.key === key) return historyRequestRef.current.request;

      const request = (async (): Promise<boolean> => {
        setOwnedState((previous) => ({
          ...(previous.ownerKey === ownerKey ? previous : { ownerKey, runs: [] }),
          ownerKey,
          historyLoading: true,
          historyError: undefined,
        }));
        historyRetryCursorRef.current = cursor;
        try {
          const page = await initial.gateway.readHistoryPage({
            threadId,
            ...(cursor === undefined ? { tail: true as const } : { cursor }),
          });
          const current = contextRef.current;
          if (!ownsAiReviewHistoryRequest(current, initial)) return false;
          if (!current.active) {
            setOwnedState((previous) =>
              previous.ownerKey !== ownerKey ? previous : { ...previous, historyLoading: false },
            );
            return false;
          }
          if (page.threadId !== threadId) throw { code: "RUNTIME_PROTOCOL_ERROR" };
          const previousCursorState = historyCursorStateRef.current;
          if (
            cursor !== undefined &&
            (previousCursorState?.ownerKey !== ownerKey ||
              page.revision !== previousCursorState.revision ||
              !previousCursorState.cursors.has(cursor))
          )
            throw { code: "RUNTIME_PROTOCOL_ERROR" };

          const turns = new Map(page.turns.map((turn) => [turn.turnId, turn] as const));
          const calls = new Map<
            string,
            { readonly item: AiReviewHistoryPage["items"][number]; readonly hasReport: boolean }
          >();
          const itemIds = new Set(
            cursor !== undefined && previousCursorState?.ownerKey === ownerKey
              ? previousCursorState.itemIds
              : [],
          );
          const cursors = new Set(
            cursor === undefined || previousCursorState?.ownerKey !== ownerKey
              ? []
              : previousCursorState.cursors,
          );
          for (const item of page.items) {
            if (itemIds.has(item.itemId)) throw { code: "RUNTIME_PROTOCOL_ERROR" };
            itemIds.add(item.itemId);
            if (
              item.toolName !== "review_report_submit" &&
              item.toolName !== "review_snapshot_list" &&
              item.toolName !== "review_snapshot_read"
            )
              continue;
            if (item.toolName === "review_report_submit") {
              const previous = calls.get(item.turnId);
              const hasReport = item.status === "success" && item.artifactId !== undefined;
              calls.set(item.turnId, {
                item: previous?.item ?? item,
                hasReport: previous?.hasReport === true || hasReport,
              });
            } else if (!calls.has(item.turnId)) {
              calls.set(item.turnId, { item, hasReport: false });
            }
          }
          const nextCursor = page.nextCursor;
          if (nextCursor !== null) {
            if (nextCursor === cursor || cursors.has(nextCursor))
              throw { code: "RUNTIME_PROTOCOL_ERROR" };
            cursors.add(nextCursor);
          }
          historyCursorStateRef.current = {
            ownerKey,
            revision: page.revision,
            cursors,
            itemIds,
          };

          const historyRuns: AiReviewRun[] = [];
          for (const [turnId, candidate] of calls) {
            const turn = turns.get(turnId);
            if (turn === undefined) throw { code: "RUNTIME_PROTOCOL_ERROR" };
            historyRuns.push({
              runId: `history:${turnId}`,
              turnId,
              sourceRevision: undefined,
              status: turn.status,
              createdAt: turn.requestedAt || candidate.item.createdAt,
              historical: true,
              reportExpected: candidate.hasReport,
            });
          }

          setOwnedState((previous) => {
            const existingRuns = previous.ownerKey === ownerKey ? previous.runs : [];
            const existingByTurn = new Map(
              existingRuns.flatMap((run) =>
                run.turnId === undefined ? [] : [[run.turnId, run] as const],
              ),
            );
            const mergedHistory = historyRuns.map((run) => {
              const existing = existingByTurn.get(run.turnId!);
              if (existing === undefined) return run;
              existingByTurn.delete(run.turnId!);
              return {
                ...existing,
                status: run.status,
                reportExpected: existing.reportExpected === true || run.reportExpected === true,
              };
            });
            const merged = [
              ...existingRuns.filter((run) => run.turnId === undefined),
              ...mergedHistory,
              ...existingByTurn.values(),
            ].sort((left, right) => right.createdAt.localeCompare(left.createdAt));
            const selectedRunId =
              previous.ownerKey === ownerKey &&
              previous.selectedRunId !== undefined &&
              merged.some((run) => run.runId === previous.selectedRunId)
                ? previous.selectedRunId
                : merged[0]?.runId;
            return {
              ownerKey,
              runs: merged,
              selectedRunId,
              historyLoading: false,
              historyHasMore: nextCursor !== null,
              historyNextCursor: nextCursor,
              historyRevision: page.revision,
              historyError: undefined,
            };
          });
          historyRetryCursorRef.current = undefined;
          return true;
        } catch (error) {
          const current = contextRef.current;
          if (!ownsAiReviewHistoryRequest(current, initial)) return false;
          if (!current.active) {
            setOwnedState((previous) =>
              previous.ownerKey !== ownerKey ? previous : { ...previous, historyLoading: false },
            );
            return false;
          }
          setOwnedState((previous) =>
            previous.ownerKey !== ownerKey
              ? previous
              : {
                  ...previous,
                  historyLoading: false,
                  historyError: normalizeReviewFailure(error),
                },
          );
          return false;
        }
      })();
      historyRequestRef.current = { key, request };
      try {
        return await request;
      } finally {
        if (historyRequestRef.current?.request === request) historyRequestRef.current = undefined;
      }
    },
    [ownerKey],
  );

  /** Review 面板可见时仅读最近一页；更早记录由用户逐页请求。 */
  useEffect(() => {
    if (!active) return;
    const key = JSON.stringify([ownerKey, threadRevision ?? null]);
    if (historyLoadedKeyRef.current === key) return;
    void readHistoryPage().then((loaded) => {
      if (loaded) historyLoadedKeyRef.current = key;
    });
  }, [active, ownerKey, readHistoryPage, threadRevision]);

  /** 只继续一个旧页，避免打开 Review 时扫描整段会话历史。 */
  const loadOlderHistory = useCallback(async (): Promise<void> => {
    const cursor = ownedState.ownerKey === ownerKey ? ownedState.historyNextCursor : undefined;
    if (
      ownedState.ownerKey === ownerKey &&
      ownedState.historyHasMore === true &&
      cursor !== undefined &&
      cursor !== null
    )
      await readHistoryPage(cursor);
  }, [
    ownerKey,
    ownedState.historyHasMore,
    ownedState.historyNextCursor,
    ownedState.ownerKey,
    readHistoryPage,
  ]);

  /** 历史发现失败可显式重试，保留同一 Workspace/Thread/runtime 围栏。 */
  const retryHistory = useCallback(async (): Promise<void> => {
    const cursor = historyRetryCursorRef.current;
    const loaded = await readHistoryPage(cursor);
    if (loaded && cursor === undefined) {
      historyLoadedKeyRef.current = JSON.stringify([ownerKey, threadRevision ?? null]);
    }
  }, [ownerKey, readHistoryPage, threadRevision]);

  /** 对当前冻结附件启动普通 Review Turn；同一运行重试沿用幂等 operation key。 */
  const startRun = useCallback(
    async (run: AiReviewRun): Promise<void> => {
      const initial = contextRef.current;
      if (
        !initial.active ||
        initial.workspaceId === undefined ||
        initial.threadId === undefined ||
        initial.snapshotRevision === undefined ||
        initial.threadRevision === undefined ||
        initial.ownerKey !== ownerKey ||
        run.source === undefined ||
        sourceKey(initial.source) !== sourceKey(run.source) ||
        initial.snapshotRevision !== run.sourceRevision
      )
        return;
      let capture = run.capture;
      try {
        if (capture === undefined) {
          updateRun(run.runId, (current) => ({
            ...current,
            status: "capturing",
            error: undefined,
          }));
          capture = await initial.gateway.captureSnapshot({
            workspaceId: initial.workspaceId,
            source: initial.source,
            expectedSnapshotRevision: initial.snapshotRevision,
            operationId: createOperationKey(),
          });
          updateRun(run.runId, (current) => ({
            ...current,
            capture,
            status: "starting",
            error: undefined,
          }));
        } else {
          updateRun(run.runId, (current) => ({ ...current, status: "starting", error: undefined }));
        }

        const current = contextRef.current;
        if (
          !current.active ||
          current.ownerKey !== ownerKey ||
          current.workspaceId !== initial.workspaceId ||
          current.threadId !== initial.threadId ||
          current.snapshotRevision !== run.sourceRevision ||
          run.source === undefined ||
          sourceKey(current.source) !== sourceKey(run.source)
        ) {
          updateRun(run.runId, (value) => ({ ...value, capture, status: "interrupted" }));
          return;
        }
        if (current.threadRevision === undefined) throw new Error("thread revision unavailable");
        const accepted = await current.gateway.startAiReview({
          threadId: current.threadId,
          expectedThreadRevision: current.threadRevision,
          operationKey: run.operationKey!,
          snapshotAttachmentId: capture.attachmentId,
        });
        if (accepted.threadId !== current.threadId) throw { code: "RUNTIME_PROTOCOL_ERROR" };
        updateRun(run.runId, (value) => ({
          ...value,
          capture,
          turnId: accepted.turnId,
          status: accepted.queued ? "queued" : "running",
          error: undefined,
        }));
        contextRef.current.onTurnAccepted?.(accepted);
      } catch (error) {
        if (contextRef.current.ownerKey !== ownerKey) return;
        updateRun(run.runId, (current) => ({
          ...current,
          ...(capture === undefined ? {} : { capture }),
          status: "failed",
          error: normalizeReviewFailure(error),
        }));
      }
    },
    [ownerKey, updateRun],
  );

  /** 依据普通 Turn 身份读取冻结报告；历史报告 revision 不能替代当前 source CAS。 */
  const refresh = useCallback(
    async (runId: string): Promise<void> => {
      const run =
        contextRef.current.ownerKey === ownerKey
          ? runs.find((candidate) => candidate.runId === runId)
          : undefined;
      const current = contextRef.current;
      if (
        !current.active ||
        current.threadId === undefined ||
        run?.turnId === undefined ||
        (run.historical === true && run.reportExpected !== true)
      )
        return;
      setOwnedState((previous) =>
        previous.ownerKey !== ownerKey ? previous : { ...previous, loadingReportId: runId },
      );
      try {
        const input: AiReviewReadInput = { threadId: current.threadId, turnId: run.turnId };
        const result = await singleflightRead(
          readRequestsRef.current,
          `${current.threadId}:${run.turnId}`,
          () => current.gateway.readAiReview(input),
        );
        if (
          contextRef.current.ownerKey !== ownerKey ||
          result.threadId !== current.threadId ||
          result.turnId !== run.turnId
        )
          return;
        if (
          result.report !== null &&
          !run.historical &&
          (run.capture === undefined ||
            result.report.snapshotId !== run.capture.snapshotId ||
            result.report.snapshotHash !== run.capture.snapshotHash)
        )
          throw { code: "RUNTIME_PROTOCOL_ERROR" };
        updateRun(runId, (latest) => ({
          ...latest,
          status: result.status,
          report: result.report ?? undefined,
          error: result.status === "failed" ? latest.error : undefined,
        }));
        setOwnedState((previous) =>
          previous.ownerKey !== ownerKey
            ? previous
            : {
                ...previous,
                loadingReportId:
                  previous.loadingReportId === runId ? undefined : previous.loadingReportId,
              },
        );
      } catch (error) {
        if (contextRef.current.ownerKey !== ownerKey) return;
        updateRun(runId, (latest) => ({ ...latest, error: normalizeReviewFailure(error) }));
        setOwnedState((previous) =>
          previous.ownerKey !== ownerKey
            ? previous
            : {
                ...previous,
                loadingReportId:
                  previous.loadingReportId === runId ? undefined : previous.loadingReportId,
              },
        );
      }
    },
    [ownerKey, runs, updateRun],
  );

  /** 新建一份只读审查记录，不取代当前 source 或改写普通 Turn 输入。 */
  const start = useCallback(async (): Promise<void> => {
    const current = contextRef.current;
    if (
      !current.active ||
      current.workspaceId === undefined ||
      current.threadId === undefined ||
      current.snapshotRevision === undefined ||
      current.threadRevision === undefined ||
      (ownedState.ownerKey === ownerKey && ownedState.historyLoading === true) ||
      runs.some((run) => run.turnId !== undefined && !isRunTerminal(run.status))
    )
      return;
    const run: AiReviewRun = {
      runId: createOperationKey(),
      operationKey: createOperationKey(),
      source: current.source,
      sourceRevision: current.snapshotRevision,
      status: "capturing",
      createdAt: new Date().toISOString(),
    };
    setOwnedState((previous) => {
      const current =
        previous.ownerKey === ownerKey ? previous : { ownerKey, runs: [] as AiReviewRun[] };
      return { ...current, ownerKey, runs: [run, ...current.runs], selectedRunId: run.runId };
    });
    await startRun(run);
  }, [ownerKey, ownedState.historyLoading, ownedState.ownerKey, runs, startRun]);

  /** 用同一个幂等键继续失败或被隐藏打断的 capture/start；Turn 已签发时只重读。 */
  const retry = useCallback(
    async (runId: string): Promise<void> => {
      const run = runs.find((candidate) => candidate.runId === runId);
      if (run === undefined) return;
      if (run.turnId !== undefined) await refresh(runId);
      else await startRun(run);
    },
    [refresh, runs, startRun],
  );

  /** 取消委托普通 Turn controller，取消 ACK 本身不伪造终态。 */
  const cancel = useCallback(
    async (runId: string): Promise<void> => {
      const run = runs.find((candidate) => candidate.runId === runId);
      const current = contextRef.current;
      if (
        run?.turnId === undefined ||
        current.cancelTurn === undefined ||
        isRunTerminal(run.status)
      )
        return;
      updateRun(runId, (value) => ({ ...value, cancelPending: true, error: undefined }));
      try {
        await current.cancelTurn(run.turnId);
        if (contextRef.current.ownerKey === ownerKey)
          updateRun(runId, (value) => ({ ...value, cancelPending: false }));
      } catch (error) {
        if (contextRef.current.ownerKey === ownerKey)
          updateRun(runId, (value) => ({
            ...value,
            cancelPending: false,
            error: normalizeReviewFailure(error),
          }));
      }
    },
    [ownerKey, runs, updateRun],
  );

  /** 历史报告只在 artifact 已确认时按原 Thread/Turn 回读；不可读记录不得留下 loading。 */
  const select = useCallback(
    (runId: string): void => {
      const run = runs.find((candidate) => candidate.runId === runId);
      if (run === undefined) return;
      const current = contextRef.current;
      const shouldReadReport =
        current.active &&
        current.threadId !== undefined &&
        run.report === undefined &&
        run.turnId !== undefined &&
        (run.historical !== true || run.reportExpected === true);
      setOwnedState((previous) =>
        previous.ownerKey !== ownerKey
          ? previous
          : {
              ...previous,
              selectedRunId: runId,
              loadingReportId: shouldReadReport ? runId : undefined,
              runs: previous.runs.map((candidate) =>
                candidate.runId === runId ? candidate : { ...candidate, report: undefined },
              ),
            },
      );
      if (shouldReadReport) void refresh(runId);
    },
    [ownerKey, refresh, runs],
  );

  const pollRun = runs.find((run) => run.turnId !== undefined && !isRunTerminal(run.status));
  const pollRunId = pollRun?.runId;
  const pollRunStatus = pollRun?.status;
  useEffect(() => {
    if (!active || pollRunId === undefined) return undefined;
    const timer = window.setTimeout(() => void refresh(pollRunId), 900);
    return () => window.clearTimeout(timer);
  }, [active, pollRunId, pollRunStatus, refresh]);

  const selectedRun = runs.find((run) => run.runId === selectedRunId) ?? runs[0];
  /** Historical artifact 只按提交证据惰性回读；live run 被 history 推进到终态时也要补读正文。 */
  useEffect(() => {
    if (
      !active ||
      selectedRun?.turnId === undefined ||
      selectedRun.reportExpected !== true ||
      selectedRun.report !== undefined ||
      selectedRun.error !== undefined
    )
      return;
    const shouldReadHistoricalReport = selectedRun.historical === true;
    const shouldReadTerminalLiveReport =
      selectedRun.historical !== true && isRunTerminal(selectedRun.status);
    if (!shouldReadHistoricalReport && !shouldReadTerminalLiveReport) return;
    const key = `${ownerKey}:${selectedRun.turnId}`;
    if (autoReadRunRef.current === key) return;
    autoReadRunRef.current = key;
    void refresh(selectedRun.runId);
  }, [
    active,
    ownerKey,
    refresh,
    selectedRun?.historical,
    selectedRun?.report,
    selectedRun?.runId,
    selectedRun?.turnId,
    selectedRun?.reportExpected,
    selectedRun?.error,
    selectedRun?.status,
  ]);

  const busy =
    (ownedState.ownerKey === ownerKey && ownedState.historyLoading === true) ||
    runs.some(
      (run) =>
        run.status === "capturing" ||
        run.status === "starting" ||
        run.status === "interrupted" ||
        (run.turnId !== undefined && !isRunTerminal(run.status)),
    );
  const state = useMemo<AiReviewControllerState>(
    () => ({
      runs,
      selectedRunId,
      loadingReportId,
      historyLoading: ownedState.ownerKey === ownerKey && ownedState.historyLoading === true,
      historyError: ownedState.ownerKey === ownerKey ? ownedState.historyError : undefined,
      historyHasMore: ownedState.ownerKey === ownerKey && ownedState.historyHasMore === true,
      historyNextCursor:
        ownedState.ownerKey === ownerKey ? ownedState.historyNextCursor : undefined,
      historyRevision: ownedState.ownerKey === ownerKey ? ownedState.historyRevision : undefined,
    }),
    [loadingReportId, ownerKey, ownedState, runs, selectedRunId],
  );
  const actions = useMemo<AiReviewActions>(
    () => ({ start, retry, refresh, loadOlderHistory, retryHistory, cancel, select }),
    [cancel, loadOlderHistory, refresh, retry, retryHistory, select, start],
  );
  const ready =
    active &&
    workspaceId !== undefined &&
    threadId !== undefined &&
    threadRevision !== undefined &&
    snapshotRevision !== undefined;
  return { state, selectedRun, ready, busy, actions };
}
