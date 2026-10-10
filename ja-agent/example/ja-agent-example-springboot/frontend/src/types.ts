// @author kongweiguang
export type TurnState =
  | "QUEUED"
  | "RUNNING"
  | "WAITING_APPROVAL"
  | "WAITING_INPUT"
  | "SUSPENDED"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED"
  | "HISTORY"
  | "UNKNOWN";

export interface ThreadView {
  threadId: string;
  agentId: string;
  title: string;
  archived: boolean;
  version: number;
  updatedAt?: string;
}

/** Agent 标识由服务端管理，浏览器仅为新会话选择已有标识。 */
export interface AgentView {
  id: string;
  name: string;
}

export interface HistoryItem {
  sequence: number;
  turnId: string;
  recordId?: string | null;
  kind: "user" | "assistant" | "tool" | "tool-result" | string;
  text?: string | null;
  reasoning?: string | null;
  callId?: string | null;
  name?: string | null;
  state?: string | null;
  content?: HistoryContent | null;
}

export interface AttachmentMetadata {
  id: string;
  threadId: string;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  createdAt: string;
  referenced: boolean;
}

/** 笔记接口只投影工作台需要的回执字段，不包含服务端身份或参数指纹。 */
export interface NoteView {
  callKey: string;
  turnId: string;
  title: string;
  body: string;
  sourceAttachmentIds: string[];
  createdAt: string;
}

export interface HistoryContent {
  text: string;
  skillIds: string[];
  attachments: AttachmentMetadata[];
}

export interface AttachmentDraft {
  operationKey: string;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  status: "uploading" | "uploaded" | "unknown" | "error";
  attachment?: AttachmentMetadata;
  error?: string;
  cancelRequested?: boolean;
  removeRequested?: boolean;
}

export interface MessageContent {
  text: string;
  skillIds: string[];
  attachmentIds: string[];
}

export interface AttachmentCapabilities {
  declaredFormats: string[];
  effectiveFormats: string[];
  maxSingleBytes: number;
  maxTotalBytes: number;
  maxAttachments: number;
}

export interface RecoveryView {
  recoveryId: string;
  callId: string;
  revision: number;
  allowedActions: Array<"RETRY" | "SKIP">;
  status?: string;
  disposition?: string;
  attempt?: number;
  toolResultRecordId?: string;
  updatedAt?: string;
}

export interface PendingRecoveryResolution {
  threadId: string;
  turnId: string;
  recoveryId: string;
  callId: string;
  expectedRecoveryRevision: number;
  decision: "RETRY" | "SKIP";
  operationKey: string;
}

export interface HistoryPage {
  items: HistoryItem[];
  nextSequence: number;
  throughSequence: number;
  hasMore: boolean;
  latestTurn?: {
    turnId: string;
    state: TurnState | string;
    updatedAt?: string;
  } | null;
}

export interface InteractionQuestion {
  questionId: string;
  prompt: string;
  type: "SINGLE" | "MULTIPLE" | "TEXT";
  required: boolean;
  allowFreeText: boolean;
  options: Array<{
    optionId: string;
    label: string;
    description: string;
    recommended: boolean;
  }>;
}

export interface WaitView {
  interactionId: string;
  interactionRevision: number;
  kind: "approval" | "input";
  status: "PENDING" | "ANSWERED" | "CANCELLED" | "SUPERSEDED" | "EXPIRED" | "CLOSED";
  questions: InteractionQuestion[];
}

export interface InteractionAnswer {
  questionId: string;
  optionIds: string[];
  freeText: string | null;
  skipped: boolean;
}

export interface TranscriptItem {
  key: string;
  kind: "assistant" | "reasoning" | "tool" | "tool-result" | "draft";
  text: string;
  callId?: string;
  name?: string;
  state?: string;
  recordId?: string;
}

export interface ChatTurn {
  turnId: string;
  userText: string;
  state: TurnState;
  resumeRequired: boolean;
  items: TranscriptItem[];
  cursor: number;
  answer?: string;
  error?: string;
  wait?: WaitView;
  waitRecordId?: string;
  waitAnswered?: boolean;
  answerOperationKey?: string;
  cancelRequested?: boolean;
  drafts: Record<string, { text: string; reasoning: string }>;
  usageRecordIds: string[];
  events: Array<{ sequence: number; eventType: string; payloadJson: string }>;
  operationKey?: string;
  skillIds: string[];
  attachments: AttachmentMetadata[];
  recoveries: RecoveryView[];
  recoveryError?: string;
}

/** 服务端状态保留 Core 原始阶段，并单独说明当前宿主是否需要显式 resume 对账。 */
export interface TurnStatusView {
  state: string;
  resumeRequired: boolean;
}

export interface PendingSubmission {
  threadId: string;
  turnId: string;
  operationKey: string;
  text: string;
  draftText?: string;
  skillIds: string[];
  attachmentIds: string[];
  attachments: AttachmentMetadata[];
}

export interface PendingQueueSubmission {
  threadId: string;
  turnId: string;
  operationKey: string;
  inputId: string;
  kind: string;
  text: string;
  skillIds: string[];
  attachmentIds: string[];
  attachments: AttachmentMetadata[];
}

export interface PendingControl {
  threadId: string;
  turnId: string;
  control: "pause" | "cancel";
  operationKey: string;
}

export interface ThreadSession {
  turns: ChatTurn[];
  draft: string;
  selectedSkills: string[];
  attachmentDrafts: AttachmentDraft[];
  scrollTop: number;
  loadingHistory: boolean;
  historyThroughSequence?: number;
  historyNextSequence?: number;
  historyHasMore: boolean;
  historyLoaded: boolean;
  observing?: boolean;
  connectError?: string;
}

export type WorkbenchTab = "skills" | "mcp" | "tools" | "queue" | "records";
export type ThemePreference = "system" | "light" | "dark";
