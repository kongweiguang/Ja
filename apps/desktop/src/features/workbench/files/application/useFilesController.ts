// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { FilesSaveCoordinator } from "./FilesSaveCoordinator";
import type { FilesOpenTarget, FilesWorkspaceOperations, WorkspaceChangedEvent } from "./ports";
import type {
  CloseDocumentRequest,
  FilesController,
  FilesControllerProps,
  FilesSearchResult,
  FilesSearchSummary,
  FilesWorkspaceCloseLease,
  FilesWorkspaceLifecycle,
  OpenDocument,
  SaveAsRequest,
  TrashRequest,
} from "./types";
import { createDocumentUseCases, type ExternalRefreshMode } from "./internal/documentUseCases";
import { DocumentSaveRuntimePort } from "./internal/controllerPorts";
import { createLifecycleUseCases } from "./internal/lifecycleUseCases";
import { createMutationUseCases, type TrashOperationSecret } from "./internal/mutationUseCases";
import {
  createReconciliationUseCases,
  type ReconciliationOptions,
} from "./internal/reconciliationUseCases";
import { createSaveAsUseCases } from "./internal/saveAsUseCases";
import { createSearchUseCases } from "./internal/searchUseCases";
import { createTreeUseCases } from "./internal/treeUseCases";
import { isWorkspaceRecoveryRequiredError } from "./internal/nativeErrorPolicy";
import type { FileRevision, WorkspaceFileNode } from "../domain/types";

type WorkspaceWatchSubscription = { stop: () => Promise<void> };

/**
 * React effect cleanup 无法等待 watcher ACK；原生下一次 start 与应用退出仍会执行有界权威回收，
 * 因此这里只消费 cleanup rejection，避免已被 Workspace 重绑取代的 stop 变成全局 pageerror。
 */
function stopWorkspaceWatch(subscription: WorkspaceWatchSubscription): void {
  void subscription.stop().catch(() => undefined);
}

/** 构造与 native 稳定码一致的本地拒绝，确保恢复门关闭后所有后续写入都 fail-closed。 */
function workspaceRecoveryBlockedError(): Error & { code: "WORKSPACE_RECOVERY_REQUIRED" } {
  const error = new Error("workspace mutation recovery is required") as Error & {
    code: "WORKSPACE_RECOVERY_REQUIRED";
  };
  error.code = "WORKSPACE_RECOVERY_REQUIRED";
  return error;
}

/**
 * 作为 Files 唯一 application controller，集中拥有编辑投影、Watcher 对账、mutation
 * 协调与 workspace generation；隐藏时继续持有草稿和切换 fence，但暂停重型原生 IO，
 * 重新可见后通过权威 Tree/Read 对账恢复，不让未显示的 Files 拖慢 workspace 切换。
 */
export function useFilesController({
  workspaceId,
  operations,
  activityEnabled = true,
  initialNodes = [],
  onNotice,
  onRegisterLifecycle,
  timer,
  resolveNativeDropTarget,
  subscribeBrowserReconciliation,
}: FilesControllerProps): FilesController {
  const [nodes, setNodes] = useState<WorkspaceFileNode[]>(() => [...initialNodes]);
  const [treeLoading, setTreeLoading] = useState(false);
  const [treeError, setTreeError] = useState<string>();
  const [selectedPath, setSelectedPath] = useState<string>();
  const [documents, setDocuments] = useState<Record<string, OpenDocument>>({});
  const [documentOrder, setDocumentOrder] = useState<string[]>([]);
  const [activePath, setActivePath] = useState<string>();
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<FilesSearchResult[]>([]);
  const [searchSummary, setSearchSummary] = useState<FilesSearchSummary>();
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState<string>();
  const [comparePath, setComparePath] = useState<string>();
  const [conflictAction, setConflictAction] = useState<{
    path: string;
    kind: "compare" | "reload";
  }>();
  const [saveAsRequest, setSaveAsRequest] = useState<SaveAsRequest>();
  const [trashRequest, setTrashRequest] = useState<TrashRequest>();
  const [closeDocumentRequest, setCloseDocumentRequest] = useState<CloseDocumentRequest>();
  const [lifecycleClosing, setLifecycleClosing] = useState(false);
  const [mutationRecoveryRequired, setMutationRecoveryRequired] = useState(false);
  const [openTargetsState, setOpenTargetsState] = useState<{
    workspaceId: string;
    targets: FilesOpenTarget[];
  }>(() => ({ workspaceId, targets: [] }));
  const openTargetOperation = operations.openTarget;
  const openTargetsOperation = operations.openTargets;
  const treeRequestRef = useRef(new Map<string, number>());
  const workspaceGenerationRef = useRef(0);
  const treeRequestSequenceRef = useRef(0);
  const rootTreeReadyRef = useRef(initialNodes.length > 0);
  const initialNodesRef = useRef(initialNodes);
  const nodesRef = useRef(nodes);
  const documentsRef = useRef(documents);
  const documentOrderRef = useRef(documentOrder);
  const activePathRef = useRef(activePath);
  const conflictActionRef = useRef(conflictAction);
  const saveAsRequestRef = useRef(saveAsRequest);
  const inFlightRef = useRef(new Map<string, number>());
  const pendingMutationRef = useRef(new Map<string, string>());
  const deferredWatchRef = useRef(new Map<string, WorkspaceChangedEvent>());
  const externalReadRef = useRef(new Map<string, number>());
  const externalConflictCheckRef = useRef(new Set<string>());
  const searchTimerRef = useRef<unknown | undefined>(undefined);
  const searchRequestRef = useRef(0);
  const revisionRef = useRef(new Map<string, FileRevision>());
  const reconciliationTaskRef = useRef<Promise<void> | undefined>(undefined);
  const watcherReadyRef = useRef(false);
  const trashRequestSequenceRef = useRef(0);
  const trashOperationRef = useRef<TrashOperationSecret | undefined>(undefined);
  const trashRequestRef = useRef<TrashRequest | undefined>(trashRequest);
  const mountedRef = useRef(false);
  const activityEnabledRef = useRef(activityEnabled);
  const previousActivityEnabledRef = useRef(activityEnabled);
  const activityWorkspaceRef = useRef(workspaceId);
  const lifecycleFenceRef = useRef(false);
  const lifecycleLeaseRef = useRef<Promise<void> | undefined>(undefined);
  const lifecycleLeaseHoldersRef = useRef(0);
  const mutationRecoveryRequiredRef = useRef(false);
  const flushRef = useRef<(path: string) => Promise<void>>(async () => undefined);
  const flushWorkspaceRef = useRef<() => Promise<FilesWorkspaceCloseLease>>(async () => ({
    release: () => undefined,
  }));
  const scheduleSaveRef = useRef<(path: string) => void>(() => undefined);
  const externalRefreshRef = useRef<
    (path: string, mode: ExternalRefreshMode, expectedRevision?: FileRevision) => Promise<void>
  >(async () => undefined);
  /** runtime port 只保存最新函数指针，用于打破 coordinator 与 document use cases 的装配环。 */
  const [documentSaveRuntime] = useState(() => new DocumentSaveRuntimePort());
  /** lazy state 只构造一个 coordinator；它不保存文档事实，只串行调用最新 runtime port。 */
  const [saveCoordinator] = useState(
    () =>
      new FilesSaveCoordinator({
        timer,
        saveOnce: (path) => documentSaveRuntime.saveOnce(path),
        shouldContinue: (path) => documentSaveRuntime.shouldContinue(path),
      }),
  );

  /** 首个真实 recovery code 立即作废排队保存与 Trash capability，并在 workspace 切换前冻结写端口。 */
  const latchMutationRecovery = useCallback(
    (error: unknown): void => {
      if (!isWorkspaceRecoveryRequiredError(error) || mutationRecoveryRequiredRef.current) return;
      mutationRecoveryRequiredRef.current = true;
      setMutationRecoveryRequired(true);
      saveCoordinator.reset();
      trashRequestSequenceRef.current += 1;
      trashOperationRef.current = undefined;
      setTrashRequest(undefined);
      setSaveAsRequest(undefined);
      onNotice?.("工作区写入已停止。请核对相关文件后重新打开工作区或重启 Ja。");
    },
    [onNotice, saveCoordinator],
  );

  /** 所有文件 mutation 共用同一恢复门；查询和外部打开不受影响，便于用户核对现场。 */
  const runWorkspaceMutation = useCallback(
    async <T>(operation: () => Promise<T>): Promise<T> => {
      if (mutationRecoveryRequiredRef.current) throw workspaceRecoveryBlockedError();
      try {
        return await operation();
      } catch (error) {
        latchMutationRecovery(error);
        throw error;
      }
    },
    [latchMutationRecovery],
  );

  /** 将 injected operations 的全部写入口包在同一硬门后，避免某个协作者漏掉恢复保护。 */
  const mutationOperations = useMemo<FilesWorkspaceOperations>(
    () => ({
      ...operations,
      saveFile: (input) => runWorkspaceMutation(() => operations.saveFile(input)),
      ...(operations.createEntry === undefined
        ? {}
        : {
            createEntry: (input: Parameters<NonNullable<typeof operations.createEntry>>[0]) =>
              runWorkspaceMutation(() => operations.createEntry!(input)),
          }),
      ...(operations.moveEntry === undefined
        ? {}
        : {
            moveEntry: (input: Parameters<NonNullable<typeof operations.moveEntry>>[0]) =>
              runWorkspaceMutation(() => operations.moveEntry!(input)),
          }),
      ...(operations.trashPrepare === undefined
        ? {}
        : {
            trashPrepare: (input: Parameters<NonNullable<typeof operations.trashPrepare>>[0]) =>
              runWorkspaceMutation(() => operations.trashPrepare!(input)),
          }),
      ...(operations.trashCommit === undefined
        ? {}
        : {
            trashCommit: (input: Parameters<NonNullable<typeof operations.trashCommit>>[0]) =>
              runWorkspaceMutation(() => operations.trashCommit!(input)),
          }),
      ...(operations.importDrop === undefined
        ? {}
        : {
            importDrop: (input: Parameters<NonNullable<typeof operations.importDrop>>[0]) =>
              runWorkspaceMutation(() => operations.importDrop!(input)),
          }),
    }),
    [operations, runWorkspaceMutation],
  );

  /** commit 后同步只读镜像，协作者事件不会在 render 阶段读取或修改 React ref。 */
  useEffect(() => {
    documentsRef.current = documents;
    documentOrderRef.current = documentOrder;
    activePathRef.current = activePath;
    conflictActionRef.current = conflictAction;
    saveAsRequestRef.current = saveAsRequest;
    initialNodesRef.current = initialNodes;
    nodesRef.current = nodes;
    trashRequestRef.current = trashRequest;
  });

  /**
   * 在 React 批量渲染之间同步权威 document ref，避免 native 事件观察到过期的
   * saving/dirty 状态。
   */
  const commitDocuments = useCallback(
    (update: (current: Record<string, OpenDocument>) => Record<string, OpenDocument>): void => {
      const current = documentsRef.current;
      const next = update(current);
      if (next === current) return;
      documentsRef.current = next;
      setDocuments(next);
    },
    [],
  );

  /** 每次调用才装配 Tree 窄 context，render 阶段不读取 refs，也不创建第二状态 owner。 */
  const loadDirectory = useCallback(
    (relativePath: string) =>
      createTreeUseCases({
        workspaceId,
        tree: operations.tree,
        treeRequestSequence: treeRequestSequenceRef,
        treeRequests: treeRequestRef,
        workspaceGeneration: workspaceGenerationRef,
        rootTreeReady: rootTreeReadyRef,
        revisions: revisionRef,
        setNodes,
        setTreeLoading,
        setTreeError,
      }).loadDirectory(relativePath),
    [operations.tree, workspaceId],
  );

  /** 延迟到事件执行阶段装配 Document 窄 context，render 不读取 refs。 */
  const getDocumentUseCases = useCallback(
    () =>
      createDocumentUseCases({
        workspaceId,
        operations: { readFile: operations.readFile, saveFile: mutationOperations.saveFile },
        saveCoordinator,
        documents: documentsRef,
        documentOrder: documentOrderRef,
        activePath: activePathRef,
        workspaceGeneration: workspaceGenerationRef,
        revisions: revisionRef,
        inFlight: inFlightRef,
        pendingMutations: pendingMutationRef,
        deferredWatch: deferredWatchRef,
        externalReads: externalReadRef,
        externalConflictChecks: externalConflictCheckRef,
        lifecycleFence: lifecycleFenceRef,
        conflictAction: conflictActionRef,
        externalRefresh: externalRefreshRef,
        commitDocuments,
        setDocumentOrder,
        setActivePath,
        setSelectedPath,
        setComparePath,
        setConflictAction,
        setSaveAsRequest,
        setCloseDocumentRequest,
        onNotice,
      }),
    [
      commitDocuments,
      onNotice,
      operations.readFile,
      mutationOperations.saveFile,
      saveCoordinator,
      workspaceId,
    ],
  );

  /** 打开用例委托给无状态协作者，主 hook 只保留稳定 React callback。 */
  const openDocument = useCallback(
    (path: string, reveal?: { line: number; column?: number }) =>
      getDocumentUseCases().openDocument(path, reveal),
    [getDocumentUseCases],
  );
  /** 单次 CAS 保存通过稳定 callback 注入唯一 single-flight coordinator。 */
  const saveDocumentOnce = useCallback(
    (path: string) => getDocumentUseCases().saveDocumentOnce(path),
    [getDocumentUseCases],
  );
  /** 显式保存复用唯一 coordinator，不在主 hook 复制队列规则。 */
  const flushDocument = useCallback(
    (path: string) => getDocumentUseCases().flushDocument(path),
    [getDocumentUseCases],
  );
  /** 自动保存只委托 debounce 用例，不在 render 期间读取草稿。 */
  const scheduleSave = useCallback(
    (path: string) => getDocumentUseCases().scheduleSave(path),
    [getDocumentUseCases],
  );
  /** 编辑 intent 由协作者统一检查 fence、只读与冲突不变量。 */
  const handleEditorChange = useCallback(
    (path: string, content: string) => getDocumentUseCases().editDocument(path, content),
    [getDocumentUseCases],
  );
  /** 外部读取统一经过 generation/request 栅栏，Watcher 与用户动作共享同一路径。 */
  const reloadExternal = useCallback(
    (path: string, mode: ExternalRefreshMode, expectedRevision?: FileRevision) =>
      getDocumentUseCases().reloadExternal(path, mode, expectedRevision),
    [getDocumentUseCases],
  );
  /** Compare intent 只调用权威冲突读取协作者。 */
  const compareConflict = useCallback(
    (path: string) => getDocumentUseCases().compareConflict(path),
    [getDocumentUseCases],
  );
  /** Reload intent 保持显式丢弃草稿语义。 */
  const reloadConflict = useCallback(
    (path: string) => getDocumentUseCases().reloadConflict(path),
    [getDocumentUseCases],
  );
  /** Discard intent 只清理主 hook 投影与保存队列。 */
  const discardDocument = useCallback(
    (path: string) => getDocumentUseCases().discardDocument(path),
    [getDocumentUseCases],
  );
  /** Close intent 根据同一文档状态选择直接关闭或确认对话框。 */
  const closeDocument = useCallback(
    (path: string) => getDocumentUseCases().closeDocument(path),
    [getDocumentUseCases],
  );

  /** commit 后更新 coordinator 与跨用例回调端口，避免 render 阶段写 ref。 */
  useEffect(() => {
    documentSaveRuntime.update(
      saveDocumentOnce,
      (path) =>
        documentsRef.current[path]?.status !== "clean" &&
        documentsRef.current[path]?.status !== "conflict",
    );
    flushRef.current = flushDocument;
    scheduleSaveRef.current = scheduleSave;
    externalRefreshRef.current = reloadExternal;
  }, [documentSaveRuntime, flushDocument, reloadExternal, saveDocumentOnce, scheduleSave]);

  /** Lifecycle context 只在切换 intent 执行时创建，render 不读取 fence refs。 */
  const getLifecycleUseCases = useCallback(
    () =>
      createLifecycleUseCases({
        saveCoordinator,
        mounted: mountedRef,
        lifecycleFence: lifecycleFenceRef,
        lifecycleLease: lifecycleLeaseRef,
        lifecycleLeaseHolders: lifecycleLeaseHoldersRef,
        documents: documentsRef,
        scheduledSave: scheduleSaveRef,
        flush: flushRef,
        inFlight: inFlightRef,
        externalConflictChecks: externalConflictCheckRef,
        setLifecycleClosing,
        onNotice,
      }),
    [onNotice, saveCoordinator],
  );

  /** Workspace change 只通过 lifecycle 协作者取得共享 lease。 */
  const flushForWorkspaceChange = useCallback(
    () => getLifecycleUseCases().flushForWorkspaceChange(),
    [getLifecycleUseCases],
  );

  /** 注册对象始终通过 ref 调用最新切换用例，commit 后再更新端口。 */
  useEffect(() => {
    flushWorkspaceRef.current = flushForWorkspaceChange;
  }, [flushForWorkspaceChange]);

  useEffect(() => {
    mountedRef.current = true;
    const lifecycle: FilesWorkspaceLifecycle = {
      workspaceId,
      /** 通过 ref 调用最新 flush 实现，注册对象无需随每次编辑重建。 */
      flushForWorkspaceChange: () => flushWorkspaceRef.current(),
    };
    onRegisterLifecycle?.(lifecycle);
    return () => {
      mountedRef.current = false;
      onRegisterLifecycle?.(undefined);
    };
  }, [onRegisterLifecycle, workspaceId]);

  /** Save As context 只在交互阶段装配，主 hook 仍持有请求和文档事实。 */
  const getSaveAsUseCases = useCallback(
    () =>
      createSaveAsUseCases({
        workspaceId,
        operations: { createEntry: mutationOperations.createEntry },
        request: saveAsRequestRef,
        documents: documentsRef,
        workspaceGeneration: workspaceGenerationRef,
        revisions: revisionRef,
        loadDirectory,
        commitDocuments,
        setDocumentOrder,
        setSelectedPath,
        setActivePath,
        setComparePath,
        setSaveAsRequest,
      }),
    [commitDocuments, loadDirectory, mutationOperations.createEntry, workspaceId],
  );

  /** Begin intent 只建立应用内请求，不触发 native 写入。 */
  const beginSaveAs = useCallback(
    (path: string) => getSaveAsUseCases().beginSaveAs(path),
    [getSaveAsUseCases],
  );
  /** Submit intent 把正文交给一次原子 Create，不再发布中间空文件。 */
  const submitSaveAs = useCallback(() => getSaveAsUseCases().submitSaveAs(), [getSaveAsUseCases]);

  /** Reconciliation context 在事件阶段读取 refs，render 期间不观察 Watcher 状态。 */
  const getReconciliationUseCases = useCallback(
    () =>
      createReconciliationUseCases({
        workspaceId,
        watchRescan: operations.watchRescan,
        watcherReady: watcherReadyRef,
        documents: documentsRef,
        workspaceGeneration: workspaceGenerationRef,
        reconciliationTask: reconciliationTaskRef,
        inFlight: inFlightRef,
        pendingMutations: pendingMutationRef,
        deferredWatch: deferredWatchRef,
        loadDirectory,
        reloadExternal,
        onNotice,
      }),
    [loadDirectory, onNotice, operations.watchRescan, reloadExternal, workspaceId],
  );

  /** 全量对账合并为主 hook 持有的一条共享任务。 */
  const reconcileAuthoritativeWorkspace = useCallback(
    (options?: ReconciliationOptions) =>
      getReconciliationUseCases().reconcileAuthoritativeWorkspace(options),
    [getReconciliationUseCases],
  );
  /** Watcher 有界 hint 统一交给权威 Tree/Read 协作者。 */
  const handleWorkspaceChanged = useCallback(
    (event: WorkspaceChangedEvent) => getReconciliationUseCases().handleWorkspaceChanged(event),
    [getReconciliationUseCases],
  );

  /** Mutation context 只在用户 intent 执行时创建，不在 render 读取 CAS 或 token refs。 */
  const getMutationUseCases = useCallback(
    () =>
      createMutationUseCases({
        workspaceId,
        operations: mutationOperations,
        saveCoordinator,
        loadDirectory,
        openDocument,
        commitDocuments,
        documents: documentsRef,
        inFlight: inFlightRef,
        pendingMutations: pendingMutationRef,
        deferredWatch: deferredWatchRef,
        externalReads: externalReadRef,
        externalConflictChecks: externalConflictCheckRef,
        revisions: revisionRef,
        scheduledSave: scheduleSaveRef,
        workspaceGeneration: workspaceGenerationRef,
        trashRequestSequence: trashRequestSequenceRef,
        trashOperation: trashOperationRef,
        trashRequest: trashRequestRef,
        setNodes,
        setDocumentOrder,
        setActivePath,
        setSelectedPath,
        setTrashRequest,
        onNotice,
      }),
    [
      commitDocuments,
      loadDirectory,
      onNotice,
      openDocument,
      mutationOperations,
      saveCoordinator,
      workspaceId,
    ],
  );

  /** Create intent 只委托具名 mutation 用例。 */
  const handleCreate = useCallback(
    (parent: string, name: string, kind: "file" | "directory") =>
      getMutationUseCases().createEntry(parent, name, kind),
    [getMutationUseCases],
  );
  /** Move intent 统一执行 CAS 与投影重映射。 */
  const handleMove = useCallback(
    (node: WorkspaceFileNode, targetDirectory: string) =>
      getMutationUseCases().moveEntry(node, targetDirectory),
    [getMutationUseCases],
  );
  /** Rename intent 复用同一 Move collaborator。 */
  const handleRename = useCallback(
    (node: WorkspaceFileNode, name: string) => getMutationUseCases().renameEntry(node, name),
    [getMutationUseCases],
  );
  /** Trash prepare intent 只产生短寿命确认摘要。 */
  const handleTrash = useCallback(
    (node: WorkspaceFileNode) => getMutationUseCases().prepareTrash(node),
    [getMutationUseCases],
  );
  /** Cancel intent 同步作废 token 与请求序号。 */
  const cancelTrash = useCallback(() => getMutationUseCases().cancelTrash(), [getMutationUseCases]);
  /** Confirm intent 只提交当前有效的 prepared operation。 */
  const confirmTrash = useCallback(
    () => getMutationUseCases().confirmTrash(),
    [getMutationUseCases],
  );
  /** Native Drop intent 只消费 opaque token。 */
  const handleNativeDrop = useCallback(
    (dropToken: string, targetDirectory: string) =>
      getMutationUseCases().importNativeDrop(dropToken, targetDirectory),
    [getMutationUseCases],
  );

  /** 外部打开只消费 Rust discovery 的闭集 target；失败保持在 notice，不影响文件树状态。 */
  const handleOpenTarget = useCallback(
    (target: FilesOpenTarget["target"], relativePath: string): void => {
      if (openTargetOperation === undefined) return;
      void openTargetOperation({ workspaceId, target, relativePath }).catch(() =>
        onNotice?.("无法使用所选应用打开此位置。"),
      );
    },
    [onNotice, openTargetOperation, workspaceId],
  );

  /** 搜索 intent 执行时才装配 context，render 阶段不读取 debounce/request refs。 */
  const runSearch = useCallback(
    (query: string) =>
      createSearchUseCases({
        workspaceId,
        search: operations.search,
        timer,
        searchTimer: searchTimerRef,
        searchRequest: searchRequestRef,
        workspaceGeneration: workspaceGenerationRef,
        setSearchQuery,
        setSearchResults,
        setSearchSummary,
        setSearchLoading,
        setSearchError,
      }).runSearch(query),
    [operations.search, timer, workspaceId],
  );

  const activeDocument = activePath === undefined ? undefined : documents[activePath];
  const openPaths = useMemo(
    () => documentOrder.filter((path) => documents[path] !== undefined),
    [documents, documentOrder],
  );
  const openTargets =
    openTargetsOperation !== undefined && openTargetsState.workspaceId === workspaceId
      ? openTargetsState.targets
      : [];
  const activeTrashRequest = trashRequest?.workspaceId === workspaceId ? trashRequest : undefined;
  const closeRequestedDocument =
    closeDocumentRequest === undefined ? undefined : documents[closeDocumentRequest.path];

  /** 让 workspace reset effect 读取本次 commit 的活动状态，而不因显隐切换重复清空草稿。 */
  useEffect(() => {
    activityEnabledRef.current = activityEnabled;
  }, [activityEnabled]);

  useEffect(() => {
    workspaceGenerationRef.current += 1;
    treeRequestSequenceRef.current = 0;
    treeRequestRef.current.clear();
    rootTreeReadyRef.current = initialNodesRef.current.length > 0;
    saveCoordinator.reset();
    inFlightRef.current.clear();
    pendingMutationRef.current.clear();
    deferredWatchRef.current.clear();
    externalReadRef.current.clear();
    externalConflictCheckRef.current.clear();
    reconciliationTaskRef.current = undefined;
    watcherReadyRef.current = false;
    setNodes([...initialNodesRef.current]);
    setTreeError(undefined);
    setSelectedPath(undefined);
    commitDocuments(() => ({}));
    setDocumentOrder([]);
    setActivePath(undefined);
    searchRequestRef.current += 1;
    setSearchQuery("");
    setSearchResults([]);
    setSearchLoading(false);
    setSearchError(undefined);
    setComparePath(undefined);
    setConflictAction(undefined);
    setSaveAsRequest(undefined);
    setCloseDocumentRequest(undefined);
    trashRequestSequenceRef.current += 1;
    trashOperationRef.current = undefined;
    setTrashRequest(undefined);
    revisionRef.current.clear();
    lifecycleFenceRef.current = false;
    lifecycleLeaseRef.current = undefined;
    lifecycleLeaseHoldersRef.current = 0;
    setLifecycleClosing(false);
    mutationRecoveryRequiredRef.current = false;
    setMutationRecoveryRequired(false);
    if (activityEnabledRef.current) void loadDirectory("");
  }, [commitDocuments, loadDirectory, saveCoordinator, workspaceId]);

  /**
   * 从同一 workspace 的隐藏状态恢复时直接读取权威 Tree/Read；Watcher 在后续 effect 才启动，
   * 此处跳过 rescan，避免正常激活时序产生一次必然失败的原生调用和错误提示。
   */
  useEffect(() => {
    const workspaceChanged = activityWorkspaceRef.current !== workspaceId;
    const wasEnabled = previousActivityEnabledRef.current;
    activityWorkspaceRef.current = workspaceId;
    previousActivityEnabledRef.current = activityEnabled;
    if (!workspaceChanged && !wasEnabled && activityEnabled)
      reconcileAuthoritativeWorkspace({ rescanWatcher: false });
  }, [activityEnabled, reconcileAuthoritativeWorkspace, workspaceId]);

  useEffect(() => {
    let active = true;
    if (!activityEnabled || openTargetsOperation === undefined) return () => undefined;
    void openTargetsOperation({ workspaceId })
      .then((targets) => {
        if (active) setOpenTargetsState({ workspaceId, targets: [...targets] });
      })
      .catch(() => {
        if (active) setOpenTargetsState({ workspaceId, targets: [] });
      });
    return () => {
      active = false;
    };
  }, [activityEnabled, openTargetsOperation, workspaceId]);

  useEffect(() => {
    watcherReadyRef.current = false;
    if (!activityEnabled || operations.watchStart === undefined) return undefined;
    let active = true;
    const watchWorkspaceGeneration = workspaceGenerationRef.current;
    let subscription: WorkspaceWatchSubscription | undefined;
    void operations
      .watchStart({ workspaceId }, (event) => {
        if (watchWorkspaceGeneration === workspaceGenerationRef.current)
          handleWorkspaceChanged(event);
      })
      .then((next) => {
        if (!active) stopWorkspaceWatch(next);
        else {
          subscription = next;
          watcherReadyRef.current = true;
        }
      })
      .catch(() => undefined);
    return () => {
      active = false;
      watcherReadyRef.current = false;
      // pending start 由最终返回的 subscription 停止；提前调用 raw stop 会抢在 native
      // start 前执行，从而遗留一个晚到的 Watcher。
      if (subscription !== undefined) stopWorkspaceWatch(subscription);
    };
  }, [activityEnabled, handleWorkspaceChanged, operations, workspaceId]);

  useEffect(() => {
    if (!activityEnabled) return undefined;
    return subscribeBrowserReconciliation(reconcileAuthoritativeWorkspace);
  }, [activityEnabled, reconcileAuthoritativeWorkspace, subscribeBrowserReconciliation]);

  /** 窗口恢复后 DOM focus 不可靠，因此使用 Tauri HWND focus 信号触发对账。 */
  useEffect(() => {
    if (!activityEnabled || operations.subscribeWindowFocus === undefined) return undefined;
    let active = true;
    let unlisten: (() => void | Promise<void>) | undefined;
    void operations
      .subscribeWindowFocus((focused) => {
        if (active && focused) reconcileAuthoritativeWorkspace();
      })
      .then((next) => {
        if (!active) void next();
        else unlisten = next;
      })
      .catch(() => undefined);
    return () => {
      active = false;
      void unlisten?.();
    };
  }, [activityEnabled, operations, reconcileAuthoritativeWorkspace]);

  useEffect(() => {
    if (
      !activityEnabled ||
      operations.subscribeNativeDrop === undefined ||
      operations.importDrop === undefined
    )
      return undefined;
    let active = true;
    let unlisten: (() => void | Promise<void>) | undefined;
    void operations
      .subscribeNativeDrop((event) => {
        if (!active) return;
        const targetDirectory = resolveNativeDropTarget(event.x, event.y, nodesRef.current);
        if (targetDirectory === undefined) return;
        void handleNativeDrop(event.dropToken, targetDirectory);
      })
      .then((next) => {
        if (!active) void next();
        else unlisten = next;
      })
      .catch(() => undefined);
    return () => {
      active = false;
      void unlisten?.();
    };
  }, [activityEnabled, handleNativeDrop, operations, resolveNativeDropTarget]);

  useEffect(
    () => () => {
      workspaceGenerationRef.current += 1;
      trashRequestSequenceRef.current += 1;
      trashOperationRef.current = undefined;
      reconciliationTaskRef.current = undefined;
      saveCoordinator.reset();
      if (searchTimerRef.current !== undefined) timer.clear(searchTimerRef.current);
    },
    [saveCoordinator, timer],
  );

  return {
    viewModel: {
      nodes,
      selectedPath,
      treeLoading,
      treeError,
      searchQuery,
      searchResults,
      searchSummary,
      searchLoading,
      searchError,
      documents,
      openPaths,
      activePath,
      activeDocument,
      comparePath,
      conflictAction,
      saveAsRequest,
      trashRequest: activeTrashRequest,
      closeDocumentRequest,
      closeRequestedDocument,
      lifecycleClosing,
      mutationRecoveryRequired,
      openTargets,
    },
    actions: {
      selectNode: (node) => {
        setSelectedPath(node.path);
        if (node.kind === "file") void openDocument(node.path);
      },
      toggleDirectory: (node) => {
        if (node.kind === "directory" && node.children === undefined) void loadDirectory(node.path);
      },
      retryTree: () => void loadDirectory(""),
      createFile:
        mutationRecoveryRequired || operations.createEntry === undefined
          ? undefined
          : (parent, name) => void handleCreate(parent, name, "file"),
      createDirectory:
        mutationRecoveryRequired || operations.createEntry === undefined
          ? undefined
          : (parent, name) => void handleCreate(parent, name, "directory"),
      rename:
        mutationRecoveryRequired || operations.moveEntry === undefined
          ? undefined
          : (node, name) => void handleRename(node, name),
      move:
        mutationRecoveryRequired || operations.moveEntry === undefined
          ? undefined
          : (node, targetDirectory) => void handleMove(node, targetDirectory),
      trash:
        mutationRecoveryRequired ||
        operations.trashPrepare === undefined ||
        operations.trashCommit === undefined
          ? undefined
          : (node) => void handleTrash(node),
      refreshTree: (path) => void loadDirectory(path ?? ""),
      importDrop:
        mutationRecoveryRequired || operations.importDrop === undefined
          ? undefined
          : (dropToken, targetDirectory) => void handleNativeDrop(dropToken, targetDirectory),
      openTarget: operations.openTarget === undefined ? undefined : handleOpenTarget,
      changeSearchQuery: runSearch,
      openSearchResult: (result) => {
        void openDocument(result.path, { line: result.line, column: result.column });
      },
      selectDocument: (path) => {
        setActivePath(path);
        setSelectedPath(path);
      },
      closeDocument,
      compareConflict: (path) => void compareConflict(path),
      reloadConflict: (path) => void reloadConflict(path),
      beginSaveAs:
        mutationRecoveryRequired || operations.createEntry === undefined ? undefined : beginSaveAs,
      retrySave: (path) => void flushDocument(path),
      editDocument: handleEditorChange,
      saveDocument: flushDocument,
      hideComparison: () => setComparePath(undefined),
      changeSaveAsTarget: (targetPath) =>
        setSaveAsRequest((current) =>
          current === undefined ? current : { ...current, targetPath, error: undefined },
        ),
      cancelSaveAs: () => setSaveAsRequest(undefined),
      submitSaveAs,
      dismissCloseDocument: () => setCloseDocumentRequest(undefined),
      discardCloseDocument: () => {
        if (closeDocumentRequest !== undefined) discardDocument(closeDocumentRequest.path);
      },
      cancelTrash,
      confirmTrash,
    },
  };
}
