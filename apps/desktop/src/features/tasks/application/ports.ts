// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  ConversationAccessMode,
  ConversationCollaborationMode,
  ConversationModelSelection,
  ConversationThreadPreferences,
  ReasoningLevel,
  TimelineSnapshot,
} from "@/features/conversation";
import type { TaskContentBlock, TaskReadModel, TaskSummary } from "../domain/taskModel";

export interface TaskPort {
  create(input: {
    parentThreadId: string;
    parentTurnId: string | null;
    expectedParentRevision: number;
    taskName: string;
    /** 首轮创建时冻结 child 的模型、推理、权限和协作模式，避免先创建后补写的竞态。 */
    preferences?: ConversationModelSelection & {
      accessMode: ConversationAccessMode;
      collaborationMode: ConversationCollaborationMode;
    };
  }): Promise<{ accepted: true; task: TaskSummary }>;
  list(input: { rootThreadId: string }): Promise<{ items: TaskSummary[] }>;
  read(input: { taskThreadId: string; cursor?: string; limit?: number }): Promise<TaskReadModel>;
  observe(input: {
    taskThreadId: string;
    expectedTaskRevision: number;
  }): Promise<{ observationId: string; taskThreadId: string; revision: number }>;
  unobserve(input: { observationId: string }): Promise<void>;
  seen(input: {
    taskThreadId: string;
    expectedTaskRevision: number;
    throughActivitySequence: number;
  }): Promise<{ accepted: true; task: TaskSummary }>;
  /** 侧聊关闭由服务端统一取消执行、释放资源并清除临时数据，ACK 前不得移除 Tab。 */
  close(input: { taskThreadId: string }): Promise<{ closed: true }>;
  messageSend(input: {
    senderThreadId: string;
    targetThreadId: string;
    content: TaskContentBlock[];
    idempotencyKey: string;
  }): Promise<{ accepted: true; messageId: string; mailboxSequence: number }>;
  followup(input: {
    senderThreadId: string;
    targetThreadId: string;
    content: TaskContentBlock[];
    idempotencyKey: string;
    expectedTaskRevision: number;
  }): Promise<{ accepted: true; messageId: string; turnId: string; task: TaskSummary }>;
  cancel(input: {
    taskThreadId: string;
    expectedTaskRevision: number;
  }): Promise<{ accepted: true; task: TaskSummary }>;
  treeDelete(input: {
    taskThreadId: string;
    expectedTaskRevision: number;
    confirmTaskThreadId: string;
  }): Promise<{ accepted: true; deletedTaskCount: number }>;
}

/** Child Thread 的偏好更新必须带自身 revision，不能借用父 Thread 的 CAS。 */
export interface TaskPreferencesPort {
  update(input: {
    threadId: string;
    expectedThreadRevision: number;
    providerId: string;
    modelId: string;
    reasoningLevel: ReasoningLevel | null;
    accessMode: ConversationAccessMode;
    collaborationMode: ConversationCollaborationMode;
  }): Promise<ConversationThreadPreferences>;
}

/** Child Transcript 直接承接 thread/read 的完整 Timeline 投影，避免丢失队列、Usage 与活动。 */
export type TaskTranscriptSnapshot = TimelineSnapshot;

/** Child transcript 复用 thread/read，但应用层只依赖窄读取端口，不知道 History adapter。 */
export interface TaskTranscriptPort {
  read(input: {
    threadId: string;
    cursor?: string;
    limit?: number;
  }): Promise<TaskTranscriptSnapshot>;
}

/** Task 标题沿用 Thread rename CAS；窄端口避免 Task feature 依赖完整 History adapter。 */
export interface TaskThreadRenamePort {
  rename(input: {
    threadId: string;
    title: string;
    expectedThreadRevision: number;
  }): Promise<{ threadId: string; title: string; revision: number }>;
}

/** Approval 仍属于原 Turn；Task UI 只转发服务端 identity 与 revision CAS。 */
export interface TaskApprovalPort {
  respond(input: {
    approvalId: string;
    turnId: string;
    decision: "approve" | "deny";
    expectedThreadRevision: number;
  }): Promise<void>;
}

/** Suspended Child 复用既有 Turn identity 恢复，Task feature 不新增第二条恢复协议。 */
export interface TaskResumePort {
  resume(input: { turnId: string; expectedThreadRevision: number }): Promise<void>;
}
