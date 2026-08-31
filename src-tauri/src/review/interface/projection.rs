// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review domain fact 到稳定 Tauri DTO 的只读投影。

use super::dto::*;
use crate::review::domain::{
    ReviewApplyResult, ReviewCatalog, ReviewFile, ReviewFileDiff, ReviewHunk, ReviewLineKind,
    ReviewSnapshot, ReviewSource,
};
use crate::workspace::WorkspaceHandle;
/// 将 catalog data 投影到既有 TypeScript wire contract。
pub(super) fn project_catalog(
    workspace_id: &str,
    workspace: &WorkspaceHandle,
    catalog: ReviewCatalog,
) -> ReviewCatalogDto {
    let repository_name = workspace
        .root_path()
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("Workspace")
        .to_owned();
    ReviewCatalogDto {
        workspace_id: workspace_id.to_owned(),
        repository_name,
        current_branch: catalog.branch,
        head_commit_id: catalog.head,
        base_refs: catalog
            .base_refs
            .into_iter()
            .map(|item| ReviewRefDto {
                label: item.ref_id.clone(),
                kind: if item.ref_id.starts_with("origin/") {
                    ReviewRefKind::Remote
                } else if matches!(item.ref_id.as_str(), "main" | "master") {
                    ReviewRefKind::Base
                } else {
                    ReviewRefKind::Local
                },
                ref_id: item.ref_id,
            })
            .collect(),
        commits: catalog
            .commits
            .into_iter()
            .map(|item| ReviewCommitDto {
                commit_id: item.commit_id,
                subject: item.subject,
                author: item.author,
                authored_at: item.authored_at,
            })
            .collect(),
    }
}

/// 投影 domain snapshot，并在 native code 中推导 source capability。
pub(crate) fn project_snapshot(workspace_id: &str, snapshot: ReviewSnapshot) -> ReviewSnapshotDto {
    let source = snapshot.source.clone();
    let capabilities = match source {
        ReviewSource::Unstaged => ReviewCapabilitiesDto {
            stage: true,
            unstage: false,
            revert: true,
        },
        ReviewSource::Staged => ReviewCapabilitiesDto {
            stage: false,
            unstage: true,
            revert: true,
        },
        ReviewSource::Branch { .. } | ReviewSource::Commit { .. } => ReviewCapabilitiesDto {
            stage: false,
            unstage: false,
            revert: false,
        },
    };
    ReviewSnapshotDto {
        workspace_id: workspace_id.to_owned(),
        source: source.into(),
        revision: snapshot.revision.into_string(),
        files: snapshot.files.iter().map(project_file).collect(),
        stats: ReviewStatsDto {
            files: snapshot.stats.files,
            additions: snapshot.stats.additions,
            deletions: snapshot.stats.deletions,
            binary_files: snapshot.stats.binary_files,
            truncated: snapshot.stats.truncated_files > 0,
        },
        capabilities,
    }
}

/// 投影 file tree item，并刻意省略 native patch bytes。
pub(super) fn project_file(file: &ReviewFile) -> ReviewFileDto {
    ReviewFileDto {
        file_id: file.file_id.as_str().to_owned(),
        path: file.path.clone(),
        old_path: file.old_path.clone(),
        status: file.status.into(),
        additions: file.additions,
        deletions: file.deletions,
        binary: file.binary,
        truncated: file.metadata_only && !file.binary,
        hunks: file.hunks.iter().map(project_hunk).collect(),
    }
}

/// 投影 hunk header 与坐标，不暴露 apply fragment。
pub(super) fn project_hunk(hunk: &ReviewHunk) -> ReviewHunkDto {
    ReviewHunkDto {
        hunk_id: hunk.hunk_id.as_str().to_owned(),
        header: hunk.header.clone(),
        old_start: hunk.old_start,
        old_lines: hunk.old_lines,
        new_start: hunk.new_start,
        new_lines: hunk.new_lines,
    }
}

/// 投影带有界 unified text 与 line 坐标的 lazy file diff。
pub(crate) fn project_file_diff(workspace_id: &str, diff: ReviewFileDiff) -> ReviewFileDiffDto {
    let unified = (!diff.file.patch.is_empty())
        .then(|| String::from_utf8_lossy(&diff.file.patch).into_owned());
    ReviewFileDiffDto {
        workspace_id: workspace_id.to_owned(),
        source: diff.source.into(),
        revision: diff.revision.into_string(),
        file_id: diff.file.file_id.as_str().to_owned(),
        path: diff.file.path.clone(),
        old_path: diff.file.old_path.clone(),
        status: diff.file.status.into(),
        binary: diff.file.binary,
        truncated: diff.file.metadata_only && !diff.file.binary,
        original: None,
        modified: None,
        unified,
        hunks: diff.file.hunks.iter().map(project_hunk).collect(),
        lines: project_lines(&diff.file.hunks),
    }
}

/// 从 bounded hunk line 推导 old/new 坐标，供 TS diff view 使用。
pub(super) fn project_lines(hunks: &[ReviewHunk]) -> Vec<ReviewDiffLineDto> {
    let mut lines = Vec::new();
    for hunk in hunks {
        let mut old_line = hunk.old_start;
        let mut new_line = hunk.new_start;
        for line in &hunk.lines {
            match line.kind {
                ReviewLineKind::Context => {
                    lines.push(ReviewDiffLineDto {
                        kind: ReviewDiffLineKind::Context,
                        old_line: Some(old_line),
                        new_line: Some(new_line),
                        text: line.text.clone(),
                    });
                    old_line = old_line.saturating_add(1);
                    new_line = new_line.saturating_add(1);
                }
                ReviewLineKind::Addition => {
                    lines.push(ReviewDiffLineDto {
                        kind: ReviewDiffLineKind::Addition,
                        old_line: None,
                        new_line: Some(new_line),
                        text: line.text.clone(),
                    });
                    new_line = new_line.saturating_add(1);
                }
                ReviewLineKind::Deletion => {
                    lines.push(ReviewDiffLineDto {
                        kind: ReviewDiffLineKind::Deletion,
                        old_line: Some(old_line),
                        new_line: None,
                        text: line.text.clone(),
                    });
                    old_line = old_line.saturating_add(1);
                }
                ReviewLineKind::FileHeader
                | ReviewLineKind::HunkHeader
                | ReviewLineKind::NoNewlineMarker => {}
            }
        }
    }
    lines
}

/// 投影成功 mutation 与其 fresh authoritative snapshot。
pub(super) fn project_apply_result(
    workspace_id: &str,
    operation_id: &str,
    result: ReviewApplyResult,
) -> ReviewApplyResultDto {
    ReviewApplyResultDto {
        workspace_id: workspace_id.to_owned(),
        operation_id: operation_id.to_owned(),
        applied: true,
        snapshot: project_snapshot(workspace_id, result.snapshot),
    }
}
