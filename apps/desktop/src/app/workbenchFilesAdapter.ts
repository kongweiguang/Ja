// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { WorkspaceChangedEvent, WorkspaceMutationHostAdapter } from "@/api/tauri/workspace";
import { observeWindowFocus } from "@/api/tauri/window";
import type {
  FileReadDto,
  FilesWorkspaceOperations,
  NewlineStyle,
  WorkspaceTreeEntryDto,
} from "@/features/workbench/files";

/** App composition 只依赖 Files 所需的原生宿主端口，不向 feature 暴露 Tauri 类型。 */
export type WorkspaceFilesHostPort = WorkspaceMutationHostAdapter;

const TREE_PAGE_SIZE = 200;
const MAX_PENDING_WATCH_EVENTS = 64;
const WATCH_GENERATION_MILLIS_STRIDE = 1_024;
let nextWatcherGeneration = Math.max(1, Date.now() * WATCH_GENERATION_MILLIS_STRIDE);

interface WatchSessionState {
  generation: number;
  status: "starting" | "active" | "closed";
  pendingEvents: WorkspaceChangedEvent[];
}

/** Start ACK 前只保存一个有界事件前缀；超过预算时折叠为根级对账提示。 */
function queuePendingWatchEvent(session: WatchSessionState, event: WorkspaceChangedEvent): void {
  if (session.pendingEvents.some((pending) => pending.requiresRescan)) return;
  if (event.requiresRescan || session.pendingEvents.length >= MAX_PENDING_WATCH_EVENTS) {
    session.pendingEvents = [
      {
        relativePath: "",
        generation: session.generation,
        revision: null,
        requiresRescan: true,
      },
    ];
    return;
  }
  session.pendingEvents.push(event);
}

/**
 * 为整个 renderer 分配单调 generation，而不是按 workspace/factory 从 1
 * 重启；事件契约没有 workspaceId，因此唯有跨工作区唯一身份才能拒绝旧
 * watcher 在切换窗口中的晚到广播。
 */
function allocateWatcherGeneration(): number {
  if (nextWatcherGeneration > Number.MAX_SAFE_INTEGER)
    throw new Error("workspace watcher generation exhausted");
  const generation = nextWatcherGeneration;
  nextWatcherGeneration += 1;
  return generation;
}

/** 只拼接两个已是相对路径的片段；Rust 仍是最终路径权威。 */
function joinRelativePath(parent: string, leaf: string): string {
  return parent.length === 0 ? leaf : `${parent}/${leaf}`;
}

/** 跨越 native save schema 前收窄可编辑换行类型。 */
function editableLineEnding(value: NewlineStyle): "lf" | "crlf" | "cr" {
  if (value === "lf" || value === "crlf" || value === "cr") return value;
  throw new Error("non-editable line ending");
}

/** 投影单个 native entry，同时保留特殊节点类型，防止 UI 对 link/reparse 执行普通文件动作。 */
function mapTreeEntry(
  entry: Awaited<ReturnType<WorkspaceMutationHostAdapter["tree"]>>["entries"][number],
): WorkspaceTreeEntryDto {
  return {
    name: entry.name,
    relativePath: entry.relativePath,
    kind: entry.metadata.kind,
    hasChildren: entry.canExpand,
    revision: entry.metadata.revision,
    size: entry.metadata.size,
  };
}

/**
 * 把 native workspace 契约收口为 Files controller 的固定能力集合。订阅先于
 * watcher 启动，生命周期按 generation 隔离，CAS revision 始终原样往返。
 */
export function createFilesWorkspaceOperations(
  adapter: WorkspaceMutationHostAdapter,
  focusObserver: typeof observeWindowFocus = observeWindowFocus,
): FilesWorkspaceOperations {
  const watchSessions = new Map<string, WatchSessionState>();

  return {
    tree: async (input) => {
      const page = await adapter.tree({ ...input, pageSize: TREE_PAGE_SIZE });
      return {
        entries: page.entries.map(mapTreeEntry),
        directoryRevision: page.directoryRevision,
        nextCursor: page.nextCursor,
        snapshotToken: page.snapshotToken,
      };
    },
    readFile: async (input): Promise<FileReadDto> => {
      const result = await adapter.readFile(input);
      return {
        path: input.relativePath,
        kind: result.kind,
        content: result.text,
        revision: result.metadata.revision,
        size: result.metadata.size,
        encoding: result.encoding,
        newline: result.lineEnding ?? "unknown",
      };
    },
    saveFile: async (input) => {
      const result = await adapter.saveFile({
        workspaceId: input.workspaceId,
        relativePath: input.relativePath,
        expectedRevision: input.expectedRevision,
        mutationId: input.mutationId,
        text: input.content,
        encoding: input.encoding,
        lineEnding: editableLineEnding(input.newline),
      });
      return { revision: result.revision, mutationId: input.mutationId };
    },
    createEntry: async (input) => {
      const result = await adapter.createEntry({
        workspaceId: input.workspaceId,
        relativePath: input.relativePath,
        expectedRevision: input.expectedRevision,
        mutationId: input.mutationId,
        kind: input.kind,
        ...(input.initialContent === undefined
          ? {}
          : {
              content: {
                text: input.initialContent.content,
                encoding: input.initialContent.encoding,
                lineEnding: editableLineEnding(input.initialContent.newline),
              },
            }),
      });
      return { revision: result.revision };
    },
    moveEntry: async (input) => {
      const leaf = input.newName ?? input.relativePath.split("/").at(-1) ?? input.relativePath;
      const result = await adapter.moveEntry({
        workspaceId: input.workspaceId,
        fromRelativePath: input.relativePath,
        toRelativePath: joinRelativePath(input.targetDirectory, leaf),
        expectedRevision: input.expectedRevision,
        mutationId: input.mutationId,
      });
      return { revision: result.revision };
    },
    trashPrepare: (input) => adapter.trashPrepare(input),
    trashCommit: async (input) => {
      const result = await adapter.trashCommit(input);
      return { revision: result.revision };
    },
    importDrop: async (input) => {
      await adapter.importDrop({
        workspaceId: input.workspaceId,
        destinationRelativePath: input.targetDirectory,
        expectedRevision: input.expectedRevision,
        dropToken: input.dropToken,
        mutationId: input.mutationId,
      });
    },
    search: async (input) => {
      const result = await adapter.search(input);
      return {
        hits: result.hits.map((hit) => ({
          id: `${hit.relativePath}:${hit.line}:${hit.column}`,
          path: hit.relativePath,
          line: hit.line,
          column: hit.column,
          preview: hit.snippet,
        })),
        truncated: result.truncated,
        scannedEntries: result.scannedEntries,
        skippedFiles: result.skippedFiles,
      };
    },
    /** 仅公开本机 discovery 已确认可用的目标，不渲染无效或未安装入口。 */
    openTargets: async (input) => {
      const result = await adapter.openTargets(input);
      return result.targets
        .filter((target) => target.available)
        .map((target) => ({ target: target.target, displayName: target.displayName }));
    },
    /** 打开动作仍只传固定 target 与工作区相对路径，进程和绝对路径全部由 Rust 持有。 */
    openTarget: async (input) => {
      await adapter.open(input);
    },
    /** 先安装广播 listener，再以全局唯一 generation 启动并精确过滤事件。 */
    watchStart: async (input, listener) => {
      const requestedGeneration = allocateWatcherGeneration();
      const session: WatchSessionState = {
        generation: requestedGeneration,
        status: "starting",
        pendingEvents: [],
      };
      watchSessions.set(input.workspaceId, session);
      let unlisten: (() => void | Promise<void>) | undefined;
      let listenerActive = false;
      /** 在晚到 start、失败和 effect cleanup 中只释放这一个原生 callback 一次。 */
      const releaseListener = async (): Promise<void> => {
        if (!listenerActive) return;
        listenerActive = false;
        await unlisten?.();
      };
      let ownsNativeSession = false;
      try {
        unlisten = await adapter.subscribeChanged((event) => {
          if (watchSessions.get(input.workspaceId) !== session) return;
          if (event.generation !== requestedGeneration) return;
          if (session.status === "starting") {
            queuePendingWatchEvent(session, event);
            return;
          }
          if (session.status !== "active") return;
          listener(event);
        });
        listenerActive = true;
        const started = await adapter.watchStart({
          workspaceId: input.workspaceId,
          generation: requestedGeneration,
        });
        ownsNativeSession = started.started && started.generation === requestedGeneration;
        if (ownsNativeSession && watchSessions.get(input.workspaceId) === session) {
          session.status = "active";
          const pendingEvents = session.pendingEvents;
          session.pendingEvents = [];
          for (const event of pendingEvents) listener(event);
        } else {
          session.status = "closed";
          session.pendingEvents = [];
          await releaseListener();
          if (watchSessions.get(input.workspaceId) === session) {
            watchSessions.delete(input.workspaceId);
          }
        }
      } catch (error) {
        session.status = "closed";
        session.pendingEvents = [];
        await releaseListener();
        if (watchSessions.get(input.workspaceId) === session) {
          watchSessions.delete(input.workspaceId);
        }
        throw error;
      }
      let stopped = false;
      return {
        stop: async () => {
          if (stopped) return;
          stopped = true;
          session.status = "closed";
          session.pendingEvents = [];
          try {
            if (ownsNativeSession) {
              await adapter.watchStop({
                workspaceId: input.workspaceId,
                generation: requestedGeneration,
              });
            }
          } finally {
            await releaseListener();
            if (watchSessions.get(input.workspaceId) === session) {
              watchSessions.delete(input.workspaceId);
            }
          }
        },
      };
    },
    /** Start ACK 前的 focus 对账由初始 Tree 覆盖；静默跳过可避免伪造“扫描失败”。 */
    watchRescan: async (input) => {
      const session = watchSessions.get(input.workspaceId);
      if (session?.status !== "active") return;
      await adapter.watchRescan({
        workspaceId: input.workspaceId,
        generation: session.generation,
      });
    },
    /** stop 只终止调用时仍由该 workspace 持有的 generation。 */
    watchStop: async (input) => {
      const session = watchSessions.get(input.workspaceId);
      if (session === undefined) return;
      session.status = "closed";
      session.pendingEvents = [];
      await adapter.watchStop({ workspaceId: input.workspaceId, generation: session.generation });
      if (watchSessions.get(input.workspaceId) === session) {
        watchSessions.delete(input.workspaceId);
      }
    },
    /** 不把 native window owner 泄露给 feature，只暴露一个窄 focus 信号。 */
    subscribeWindowFocus: async (listener) => {
      const observer = focusObserver(listener);
      return () => observer.dispose();
    },
    subscribeNativeDrop: (listener) => adapter.subscribeNativeDrop(listener),
  };
}
