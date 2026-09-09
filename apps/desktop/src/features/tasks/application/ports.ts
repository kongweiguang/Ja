// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TaskContentBlock, TaskReadModel, TaskSummary } from "../domain/taskModel";

export interface TaskPort {
  create(input: {
    parentThreadId: string;
    parentTurnId: string | null;
    expectedParentRevision: number;
    taskName: string;
    content: TaskContentBlock[];
  }): Promise<{ accepted: true; task: TaskSummary; turnId: string }>;
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

export interface TaskTranscriptSnapshot {
  threadId: string;
  revision: number;
  turns: Array<{
    turnId: string;
    status: string;
    requestedAt: string;
    updatedAt: string;
    completedAt: string | null;
    errorCode: string | null;
  }>;
  items: Array<Record<string, unknown>>;
  nextCursor: string | null;
}

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
