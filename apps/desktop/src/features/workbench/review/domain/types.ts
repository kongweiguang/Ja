// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** Review 来源只描述用户选择的权威快照，不携带 Git 命令或 native 句柄。 */
export type ReviewSource =
  | { kind: "uncommitted" }
  | { kind: "unstaged" }
  | { kind: "staged" }
  | { kind: "branch"; refId: string }
  | { kind: "commit"; commitId: string };

export type ReviewAction = "stage" | "unstage" | "revert";

/** Target 使用 Rust 返回的稳定身份，React 不生成 patch 或推断文件范围。 */
export type ReviewTarget =
  | { kind: "all" }
  | { kind: "file"; fileId: string }
  | { kind: "hunk"; fileId: string; hunkId: string };

interface ReviewRef {
  refId: string;
  label: string;
  kind: "base" | "local" | "remote";
}

interface ReviewCommit {
  commitId: string;
  subject: string;
  author: string;
  authoredAt: string;
}

export interface ReviewCatalog {
  workspaceId: string;
  repositoryName: string;
  currentBranch: string | null;
  headCommitId: string | null;
  baseRefs: ReviewRef[];
  commits: ReviewCommit[];
}

type ReviewFileStatus =
  | "added"
  | "modified"
  | "deleted"
  | "renamed"
  | "copied"
  | "conflicted"
  | "untracked";

interface ReviewHunk {
  hunkId: string;
  header: string;
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
}

export interface ReviewFile {
  fileId: string;
  layer: "staged" | "unstaged" | "untracked" | "comparison";
  path: string;
  oldPath: string | null;
  status: ReviewFileStatus;
  additions: number | null;
  deletions: number | null;
  binary: boolean;
  truncated: boolean;
  hunks: ReviewHunk[];
}

interface ReviewStats {
  files: number;
  additions: number;
  deletions: number;
  binaryFiles: number;
  truncated: boolean;
}

export interface ReviewCapabilities {
  stage: boolean;
  unstage: boolean;
  revert: boolean;
}

export interface ReviewSnapshot {
  workspaceId: string;
  source: ReviewSource;
  revision: string;
  files: ReviewFile[];
  stats: ReviewStats;
  capabilities: ReviewCapabilities;
}

interface ReviewDiffLine {
  kind: "context" | "addition" | "deletion";
  oldLine: number | null;
  newLine: number | null;
  text: string;
}

export interface ReviewFileDiff {
  workspaceId: string;
  source: ReviewSource;
  revision: string;
  fileId: string;
  layer: ReviewFile["layer"];
  path: string;
  oldPath: string | null;
  status: ReviewFileStatus;
  binary: boolean;
  truncated: boolean;
  original: string | null;
  modified: string | null;
  unified: string | null;
  hunks: ReviewHunk[];
  lines: ReviewDiffLine[];
}

export interface ReviewInvalidatedEvent {
  workspaceId: string;
  generation: number;
  reason: "mutation" | "external" | "turn_completed" | "repository_changed";
}

export interface ReviewApplyResult {
  workspaceId: string;
  operationId: string;
  applied: true;
  snapshot: ReviewSnapshot;
}

export interface ReviewCancelResult {
  workspaceId: string;
  operationId: string;
  cancelled: boolean;
}
