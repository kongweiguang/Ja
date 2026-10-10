// @author kongweiguang
import type {
  AgentView,
  AttachmentCapabilities,
  AttachmentDraft,
  ChatTurn,
  HistoryItem,
  PendingControl,
  PendingQueueSubmission,
  PendingRecoveryResolution,
  PendingSubmission,
  ThreadSession,
  ThreadView,
  ThemePreference,
  WorkbenchTab,
} from "./types";

const preferenceKey = "ja-spring-workbench-v1";
const terminalStates = new Set(["COMPLETED", "FAILED", "CANCELLED"]);

export interface StoredPreferences {
  activeThreadId?: string;
  theme?: ThemePreference;
  drafts?: Record<string, string>;
  selectedSkills?: Record<string, string[]>;
  attachmentDrafts?: Record<string, AttachmentDraft[]>;
  scrollTops?: Record<string, number>;
  pending?: Record<string, PendingSubmission>;
  pendingQueue?: Record<string, PendingQueueSubmission>;
  pendingControls?: Record<string, PendingControl>;
  pendingRecoveries?: Record<string, PendingRecoveryResolution>;
  pendingCreateKey?: string;
  pendingCreateAgentId?: string;
}

export interface WorkbenchData {
  skills?: unknown[];
  mcp?: unknown[];
  tools?: unknown[];
  queue?: unknown;
  records?: unknown;
}

export interface AppState {
  agents: AgentView[];
  newAgentId: string;
  threads: ThreadView[];
  threadHasMore: boolean;
  threadListLoadingMore: boolean;
  selectedThreadId?: string;
  archivedView: boolean;
  search: string;
  sessions: Record<string, ThreadSession>;
  pending: Record<string, PendingSubmission>;
  pendingQueue: Record<string, PendingQueueSubmission>;
  pendingControls: Record<string, PendingControl>;
  pendingRecoveries: Record<string, PendingRecoveryResolution>;
  pendingCreateKey?: string;
  pendingCreateAgentId?: string;
  model: string;
  experienceEnabled: boolean;
  attachmentCapabilities?: AttachmentCapabilities;
  theme: ThemePreference;
  loading: boolean;
  loadingError?: string;
  notice?: { text: string; tone: "info" | "error" | "success" };
  workbenchOpen: boolean;
  workbenchTab: WorkbenchTab;
  workbenchData: WorkbenchData;
  workbenchLoading: boolean;
  sidebarOpen: boolean;
}

export type AppAction =
  | { type: "patch"; value: Partial<AppState> }
  | { type: "session-patch"; threadId: string; value: Partial<ThreadSession> }
  | { type: "session-turn"; threadId: string; turn: ChatTurn }
  | { type: "remove-thread"; threadId: string }
  | {
      type: "history";
      threadId: string;
      items: HistoryItem[];
      latestTurn?: { turnId: string; state: string } | null;
      throughSequence: number;
      nextSequence: number;
      hasMore: boolean;
    }
  | {
      type: "history-append";
      threadId: string;
      items: HistoryItem[];
      latestTurn?: { turnId: string; state: string } | null;
      nextSequence: number;
      hasMore: boolean;
    };

/** 为每个会话建立轻量界面状态，对话正文始终从服务端获取。 */
export function emptySession(): ThreadSession {
  return {
    turns: [],
    draft: "",
    selectedSkills: [],
    attachmentDrafts: [],
    scrollTop: 0,
    loadingHistory: false,
    historyHasMore: false,
    historyLoaded: false,
  };
}

/** 偏好数据不包含对话历史，避免本地存储形成另一份对话事实来源。 */
export function readPreferences(): StoredPreferences {
  try {
    const value = JSON.parse(
      localStorage.getItem(preferenceKey) ?? "null",
    ) as StoredPreferences | null;
    return value && typeof value === "object" ? value : {};
  } catch {
    return {};
  }
}

/** 本地存储失败不回滚服务端已受理的操作，调用方可统一展示一条界面提示。 */
export function writePreferences(value: StoredPreferences): boolean {
  try {
    localStorage.setItem(preferenceKey, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

/** 以任一持久草稿来源恢复 Thread 显示状态，确保附件独立草稿刷新后仍可继续。 */
export function createInitialState(): AppState {
  const saved = readPreferences();
  const persistedThreadIds = new Set([
    ...Object.keys(saved.drafts ?? {}),
    ...Object.keys(saved.selectedSkills ?? {}),
    ...Object.keys(saved.attachmentDrafts ?? {}),
    ...Object.keys(saved.scrollTops ?? {}),
  ]);
  return {
    agents: [{ id: "general", name: "通用助手" }],
    newAgentId: "general",
    threads: [],
    threadHasMore: false,
    threadListLoadingMore: false,
    selectedThreadId: saved.activeThreadId,
    archivedView: false,
    search: "",
    sessions: Object.fromEntries(
      [...persistedThreadIds].map((threadId) => [
        threadId,
        {
          ...emptySession(),
          draft: saved.drafts?.[threadId] ?? "",
          selectedSkills: saved.selectedSkills?.[threadId] ?? [],
          attachmentDrafts: (saved.attachmentDrafts?.[threadId] ?? []).map((attachment) =>
            attachment.status === "uploading" ? { ...attachment, status: "unknown" } : attachment,
          ),
          scrollTop: saved.scrollTops?.[threadId] ?? 0,
        },
      ]),
    ),
    pending: saved.pending ?? {},
    pendingQueue: saved.pendingQueue ?? {},
    pendingControls: saved.pendingControls ?? {},
    pendingRecoveries: saved.pendingRecoveries ?? {},
    pendingCreateKey: saved.pendingCreateKey,
    pendingCreateAgentId: saved.pendingCreateAgentId,
    model: "",
    experienceEnabled: false,
    attachmentCapabilities: undefined,
    theme: saved.theme ?? "system",
    loading: true,
    workbenchOpen: false,
    workbenchTab: "skills",
    workbenchData: {},
    workbenchLoading: false,
    sidebarOpen: false,
  };
}

/** 事件与异步响应使用不可变更新，避免切换会话时共享可变的对话记录。 */
export function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case "patch":
      return { ...state, ...action.value };
    case "session-patch":
      return {
        ...state,
        sessions: {
          ...state.sessions,
          [action.threadId]: { ...getSession(state, action.threadId), ...action.value },
        },
      };
    case "session-turn": {
      const session = getSession(state, action.threadId);
      const index = session.turns.findIndex((turn) => turn.turnId === action.turn.turnId);
      const turns =
        index < 0
          ? [...session.turns, action.turn]
          : session.turns.map((turn, turnIndex) => (turnIndex === index ? action.turn : turn));
      return {
        ...state,
        sessions: { ...state.sessions, [action.threadId]: { ...session, turns } },
      };
    }
    case "remove-thread": {
      const threads = state.threads.filter((thread) => thread.threadId !== action.threadId);
      const sessions = { ...state.sessions };
      delete sessions[action.threadId];
      const pending = { ...state.pending };
      delete pending[action.threadId];
      const pendingQueue = Object.fromEntries(
        Object.entries(state.pendingQueue).filter(
          ([, value]) => value.threadId !== action.threadId,
        ),
      );
      const pendingControls = Object.fromEntries(
        Object.entries(state.pendingControls).filter(
          ([, value]) => value.threadId !== action.threadId,
        ),
      );
      const pendingRecoveries = Object.fromEntries(
        Object.entries(state.pendingRecoveries).filter(
          ([, value]) => value.threadId !== action.threadId,
        ),
      );
      return {
        ...state,
        threads,
        sessions,
        pending,
        pendingQueue,
        pendingControls,
        pendingRecoveries,
      };
    }
    case "history": {
      const session = getSession(state, action.threadId);
      const turns = turnsFromHistory(action.items, action.latestTurn);
      return {
        ...state,
        sessions: {
          ...state.sessions,
          [action.threadId]: {
            ...session,
            turns,
            loadingHistory: false,
            historyLoaded: true,
            historyThroughSequence: action.throughSequence,
            historyNextSequence: action.nextSequence,
            historyHasMore: action.hasMore,
          },
        },
      };
    }
    case "history-append": {
      const session = getSession(state, action.threadId);
      const incoming = turnsFromHistory(action.items, action.latestTurn);
      const turns = mergeHistoricalTurns(session.turns, incoming, action.latestTurn);
      return {
        ...state,
        sessions: {
          ...state.sessions,
          [action.threadId]: {
            ...session,
            turns,
            loadingHistory: false,
            historyLoaded: true,
            historyNextSequence: action.nextSequence,
            historyHasMore: action.hasMore,
          },
        },
      };
    }
  }
}

/** 按记录标识合并后续历史页，同时保留当前轮次已收到的实时增量。 */
function mergeHistoricalTurns(
  current: ChatTurn[],
  incoming: ChatTurn[],
  latestTurn?: { turnId: string; state: string } | null,
): ChatTurn[] {
  const byId = new Map(current.map((turn) => [turn.turnId, turn]));
  const order = current.map((turn) => turn.turnId);
  for (const value of incoming) {
    const previous = byId.get(value.turnId);
    if (!previous) {
      byId.set(value.turnId, value);
      order.push(value.turnId);
      continue;
    }
    const items = [...previous.items];
    for (const item of value.items) {
      const duplicate = items.some(
        (existing) =>
          existing.key === item.key ||
          (existing.kind === item.kind &&
            existing.recordId &&
            item.recordId &&
            existing.recordId === item.recordId),
      );
      if (!duplicate) items.push(item);
    }
    byId.set(value.turnId, {
      ...previous,
      userText: previous.userText || value.userText,
      items,
      waitRecordId: previous.waitRecordId ?? value.waitRecordId,
      usageRecordIds: [...new Set([...previous.usageRecordIds, ...value.usageRecordIds])],
    });
  }
  if (latestTurn) {
    const currentLatest = byId.get(latestTurn.turnId);
    const latest = currentLatest ?? emptyTurn(latestTurn.turnId, "", []);
    if (!currentLatest) order.push(latestTurn.turnId);
    byId.set(latestTurn.turnId, { ...latest, state: asTurnState(latestTurn.state) });
  }
  return order.map((turnId) => byId.get(turnId)!).filter(Boolean);
}

/** 构建公开历史投影并保留原始输入附件，避免刷新后只剩下不可用的文件名提示。 */
export function turnsFromHistory(
  items: HistoryItem[],
  latestTurn?: { turnId: string; state: string } | null,
): ChatTurn[] {
  const sorted = [...items].sort((left, right) => left.sequence - right.sequence);
  const byId = new Map<string, ChatTurn>();
  const order: string[] = [];
  for (const item of sorted) {
    let turn = byId.get(item.turnId);
    if (!turn) {
      turn = emptyTurn(item.turnId, "", []);
      turn.state = "HISTORY";
      byId.set(item.turnId, turn);
      order.push(item.turnId);
    }
    if (item.kind === "user") {
      const content = item.content;
      turn.userText ||= content?.text ?? item.text ?? "";
      if (content) {
        turn.skillIds = [...content.skillIds];
        turn.attachments = [...content.attachments];
      }
      continue;
    }
    if (item.kind === "assistant") {
      if (item.reasoning)
        turn.items.push({
          key: `history:${item.recordId ?? item.sequence}:reasoning`,
          kind: "reasoning",
          text: item.reasoning,
          recordId: item.recordId ?? undefined,
        });
      if (item.text)
        turn.items.push({
          key: `history:${item.recordId ?? item.sequence}:assistant`,
          kind: "assistant",
          text: item.text,
          recordId: item.recordId ?? undefined,
        });
      continue;
    }
    if (item.kind === "tool") {
      turn.items.push({
        key: `tool:${item.callId ?? item.recordId ?? item.sequence}`,
        kind: "tool",
        text: "",
        callId: item.callId ?? undefined,
        name: item.name ?? undefined,
        state: item.state ?? "PENDING",
        recordId: item.recordId ?? undefined,
      });
      continue;
    }
    if (item.kind === "tool-result") {
      turn.items.push({
        key: `history:${item.recordId ?? item.sequence}:tool-result`,
        kind: "tool-result",
        text: item.text ?? "",
        callId: item.callId ?? undefined,
        state: item.state ?? undefined,
        recordId: item.recordId ?? undefined,
      });
      continue;
    }
    if (item.kind === "usage" && item.recordId)
      turn.usageRecordIds = [...new Set([...turn.usageRecordIds, item.recordId])];
    if (item.kind === "wait" && item.recordId) turn.waitRecordId = item.recordId;
  }
  if (latestTurn) {
    let latest = byId.get(latestTurn.turnId);
    if (!latest) {
      latest = emptyTurn(latestTurn.turnId, "", []);
      byId.set(latestTurn.turnId, latest);
      order.push(latestTurn.turnId);
    }
    latest.state = asTurnState(latestTurn.state);
  }
  return order.map((turnId) => byId.get(turnId)!).filter(Boolean);
}

/** 为乐观提交和持久事件重放提供结构一致的空轮次。 */
export function emptyTurn(
  turnId: string,
  userText: string,
  skillIds: string[],
  operationKey?: string,
): ChatTurn {
  return {
    turnId,
    userText,
    state: "QUEUED",
    resumeRequired: false,
    items: [],
    cursor: 0,
    drafts: {},
    usageRecordIds: [],
    events: [],
    operationKey,
    skillIds,
    attachments: [],
    recoveries: [],
  };
}

/** 未知的服务端枚举值按重连状态展示，避免误判为执行完成。 */
export function asTurnState(value: string): ChatTurn["state"] {
  switch (value) {
    case "QUEUED":
    case "RUNNING":
    case "WAITING_APPROVAL":
    case "WAITING_INPUT":
    case "SUSPENDED":
    case "COMPLETED":
    case "FAILED":
    case "CANCELLED":
      return value;
    default:
      return "UNKNOWN";
  }
}

/** 控制动作与自动重连共用状态判断，确保终态定义一致。 */
export function isTerminal(state: string | undefined): boolean {
  return state !== undefined && terminalStates.has(state);
}

/** 仅读取其他会话的渲染路径不创建会话状态，避免读取产生副作用。 */
export function getSession(state: AppState, threadId: string): ThreadSession {
  return state.sessions[threadId] ?? emptySession();
}
