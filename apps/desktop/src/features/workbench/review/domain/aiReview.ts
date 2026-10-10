// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReviewSource } from "./types";

export type AiReviewTurnStatus =
  | "queued"
  | "running"
  | "waiting_input"
  | "waiting_approval"
  | "suspended"
  | "completed"
  | "failed"
  | "cancelled";

export interface AiReviewCoverage {
  readonly complete: boolean;
  readonly files: number;
  readonly bytes: number;
  readonly maxFiles: number;
  readonly maxBytes: number;
  readonly partialReasons: ReadonlyArray<
    | "binary_file"
    | "non_utf8"
    | "file_limit"
    | "byte_limit"
    | "content_unavailable"
    | "patch_unavailable"
  >;
  readonly filesReviewed: readonly {
    readonly path: string;
    readonly layer: "staged" | "unstaged" | "untracked" | "comparison";
  }[];
  readonly reviewComplete: boolean;
}

export interface AiReviewFinding {
  readonly findingId: string;
  readonly severity: "P0" | "P1" | "P2" | "P3";
  readonly title: string;
  readonly body: string;
  readonly path: string;
  readonly layer: "staged" | "unstaged" | "untracked" | "comparison";
  readonly startLine: number;
  readonly endLine: number;
  readonly side: "base" | "current";
  readonly evidence: string;
}

export interface AiReviewReport {
  readonly reportId: string;
  readonly snapshotId: string;
  readonly snapshotHash: string;
  readonly revision: string;
  readonly summary: string;
  readonly coverage: AiReviewCoverage;
  readonly findings: readonly AiReviewFinding[];
}

/** Feature-owned capture identity omits native Git source details the UI does not need. */
export interface AiReviewCapture {
  readonly workspaceId: string;
  readonly snapshotId: string;
  readonly snapshotHash: string;
  readonly revision: string;
  readonly attachmentId: string;
  readonly coverage: Omit<AiReviewCoverage, "filesReviewed" | "reviewComplete">;
}

export interface AiReviewStartInput {
  readonly threadId: string;
  readonly expectedThreadRevision: number;
  readonly operationKey: string;
  readonly snapshotAttachmentId: string;
}

export interface AiReviewAccepted {
  readonly threadId: string;
  readonly turnId: string;
  readonly threadRevision: number;
  readonly queued: boolean;
}

export interface AiReviewReadInput {
  readonly threadId: string;
  readonly turnId: string;
}

export interface AiReviewResult {
  readonly threadId: string;
  readonly turnId: string;
  readonly status: AiReviewTurnStatus;
  readonly report: AiReviewReport | null;
}

export interface AiReviewHistoryPage {
  readonly threadId: string;
  readonly revision: number;
  readonly turns: readonly {
    readonly turnId: string;
    readonly status: AiReviewTurnStatus;
    readonly requestedAt: string;
  }[];
  readonly items: readonly {
    readonly itemId: string;
    readonly turnId: string;
    readonly createdAt: string;
    readonly toolName: string;
    readonly status: "pending" | "running" | "waiting_approval" | "success" | "error" | "cancelled";
    readonly artifactId?: string;
  }[];
  readonly nextCursor: string | null;
}

/** Domain gateway keeps the controller independent from JA-RPC and native command shapes. */
export interface AiReviewGateway {
  captureSnapshot(input: {
    readonly workspaceId: string;
    readonly source: ReviewSource;
    readonly expectedSnapshotRevision: string;
    readonly operationId: string;
  }): Promise<AiReviewCapture>;
  startAiReview(input: AiReviewStartInput): Promise<AiReviewAccepted>;
  readAiReview(input: AiReviewReadInput): Promise<AiReviewResult>;
  readHistoryPage(input: {
    readonly threadId: string;
    readonly cursor?: string;
    readonly tail?: true;
  }): Promise<AiReviewHistoryPage>;
}
