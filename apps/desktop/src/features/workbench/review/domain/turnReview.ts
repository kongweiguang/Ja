// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type TurnReviewIncompleteReason =
  | "unknown_mutator"
  | "mutation_chain_broken"
  | "outside_workspace"
  | "limit_exceeded"
  | "capture_failed"
  | "commit_unconfirmed"
  | "recovery_boundary";

export interface TurnReviewStats {
  readonly files: number;
  readonly additions: number;
  readonly deletions: number;
  readonly binaryFiles: number;
  readonly truncated: boolean;
}

export interface TurnReviewFile {
  readonly path: string;
  readonly status: "added" | "modified" | "deleted";
  readonly additions: number;
  readonly deletions: number;
  readonly binary: boolean;
  readonly truncated: boolean;
}

/** 终态目标只引用 Ja 已持久化的冻结证据，绝不回读当前工作树。 */
export interface FrozenTurnReviewTarget {
  readonly kind: "frozen_turn";
  readonly workspaceId: string;
  readonly threadId: string;
  readonly turnId: string;
  readonly threadRevision: number;
  readonly turnNumber?: number;
  readonly completedAt?: string;
  readonly state: "complete" | "partial";
  readonly incompleteReasons: readonly TurnReviewIncompleteReason[];
  readonly stats: TurnReviewStats;
  readonly files: readonly TurnReviewFile[];
  readonly artifactId: string;
}

export type TurnReviewTarget = FrozenTurnReviewTarget;

/** 单文件结果保留服务端证据身份，组件接纳正文前必须逐项核对。 */
export interface TurnReviewFileContent {
  readonly artifactId: string;
  readonly filePath: string;
  readonly byteLength: number;
  readonly sha256: string;
  readonly content: string;
}

/** Review 只读取一个冻结文件；Git 操作继续由独立 Review controller 负责。 */
export interface TurnReviewPort {
  readFrozen(
    target: FrozenTurnReviewTarget,
    file: TurnReviewFile,
    signal?: AbortSignal,
  ): Promise<TurnReviewFileContent>;
}
