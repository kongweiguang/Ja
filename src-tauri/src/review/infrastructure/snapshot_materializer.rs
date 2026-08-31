// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review snapshot 与 diff 物化。

use super::catalog::EMPTY_TREE;
use super::git::{CancellationToken, DiffOptions, GitStatusKind};
use super::git_query::{NameStatusRecord, SourceKind, diff_args, parse_name_status, validate_ref};
use super::native::NativeReviewAdapter;
use super::parse::{ParsedFilePatch, ParsedHunk, parse_diff, synthetic_added_patch};
use crate::review::application::ReviewError;
use crate::review::domain::{
    MAX_REVIEW_DIFF_BYTES, MAX_REVIEW_DIFF_LINES, MAX_REVIEW_FILES, ReviewFile, ReviewFileId,
    ReviewFileStatus, ReviewHunk, ReviewHunkId, ReviewRevision, ReviewSnapshot, ReviewSource,
    ReviewStats,
};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::ffi::OsString;
use std::fs;
use std::io::Read;

impl NativeReviewAdapter {
    /// 物化指定 source 的权威文件树与 opaque revision。
    pub(super) fn materialize_snapshot(
        &self,
        source: &ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError> {
        let materialized = self.materialize(source, cancellation)?;
        Ok(ReviewSnapshot {
            revision: ReviewRevision::parse(materialized.revision)
                .map_err(|_| ReviewError::Parse)?,
            source: source.clone(),
            files: materialized.files,
            stats: materialized.stats,
        })
    }

    /// 从有界 name-status 与 patch 物化 Git source，仅 Unstaged source 合并 untracked file。
    pub(super) fn materialize(
        &self,
        source: &ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<Materialized, ReviewError> {
        let kind = self.source_kind(source, cancellation)?;
        let records = self.name_status(&kind, cancellation)?;
        let patch_bytes = self.patch_bytes(&kind, cancellation)?;
        let parsed = if patch_bytes.len() <= MAX_REVIEW_DIFF_BYTES {
            parse_diff(&patch_bytes)?
        } else {
            Vec::new()
        };
        let mut used = BTreeSet::new();
        let mut files = Vec::with_capacity(records.len());
        for record in records {
            let parsed_file = parsed.iter().enumerate().find_map(|(index, value)| {
                if used.contains(&index) {
                    return None;
                }
                if value.new_path.as_deref() == Some(record.path.as_str())
                    || value.old_path.as_deref() == Some(record.path.as_str())
                {
                    Some((index, value))
                } else {
                    None
                }
            });
            let (parsed_index, parsed_file) = parsed_file.unwrap_or_else(|| {
                parsed
                    .iter()
                    .enumerate()
                    .find(|(index, _)| !used.contains(index))
                    .unwrap_or((usize::MAX, &EMPTY_PARSED_FILE))
            });
            if parsed_index != usize::MAX {
                used.insert(parsed_index);
            }
            files.push(self.build_review_file(
                &record.path,
                record.old_path.as_deref(),
                record.status,
                (!parsed_file.raw.is_empty()).then_some(parsed_file),
                parsed_file.raw.clone(),
            )?);
        }
        if matches!(kind, SourceKind::Unstaged) {
            for entry in self.git.review_status_all(cancellation)? {
                if !matches!(entry.kind, GitStatusKind::Untracked) || entry.path.ends_with('/') {
                    continue;
                }
                if files.iter().any(|file| file.path == entry.path) {
                    continue;
                }
                files.push(self.untracked_file(&entry.path)?);
            }
        }
        if files.len() > MAX_REVIEW_FILES {
            return Err(ReviewError::Parse);
        }
        let revision = hash_revision(source, &files)?;
        Ok(Materialized {
            stats: stats_for_files(&files),
            revision,
            files,
        })
    }

    /// 为 lazy limit 标记的 metadata-only file 物化有界 path-specific diff。
    pub(super) fn materialize_one_file(
        &self,
        source: &ReviewSource,
        file: &ReviewFile,
        cancellation: &CancellationToken,
    ) -> Result<ReviewFile, ReviewError> {
        if file.status == ReviewFileStatus::Untracked {
            return self.untracked_file(&file.path);
        }
        let kind = self.source_kind(source, cancellation)?;
        let path = self.validated_path_arg(&file.path)?;
        let mut args = diff_args(&kind, false);
        args.push(OsString::from("--"));
        args.push(OsString::from(path));
        let bytes = self.git.run_review_command(&args, cancellation)?;
        let parsed = parse_diff(&bytes)?.into_iter().next();
        self.build_review_file(
            &file.path,
            file.old_path.as_deref(),
            file.status,
            parsed.as_ref(),
            bytes,
        )
    }

    /// 在 Git process 启动前计算 source command kind 并校验 ref selector。
    pub(super) fn source_kind(
        &self,
        source: &ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<SourceKind, ReviewError> {
        match source {
            ReviewSource::Unstaged => Ok(SourceKind::Unstaged),
            ReviewSource::Staged => Ok(SourceKind::Staged),
            ReviewSource::Branch { ref_id } => {
                validate_ref(ref_id.as_str())?;
                let head = self.head(cancellation)?.ok_or(ReviewError::InvalidInput)?;
                Ok(SourceKind::Range {
                    left: ref_id.as_str().to_owned(),
                    right: head,
                })
            }
            ReviewSource::Commit { commit_id } => {
                validate_ref(commit_id.as_str())?;
                let parent = self
                    .commit_parent(commit_id.as_str(), cancellation)?
                    .unwrap_or_else(|| EMPTY_TREE.to_owned());
                Ok(SourceKind::Range {
                    left: parent,
                    right: commit_id.as_str().to_owned(),
                })
            }
        }
    }

    /// 读取指定 source kind 的有界 NUL-delimited name-status record。
    pub(super) fn name_status(
        &self,
        kind: &SourceKind,
        cancellation: &CancellationToken,
    ) -> Result<Vec<NameStatusRecord>, ReviewError> {
        let mut args = diff_args(kind, true);
        args.push(OsString::from("--"));
        let bytes = self.git.run_review_command(&args, cancellation)?;
        parse_name_status(&self.workspace, &bytes)
    }

    /// 使用同一 hardened Git runner 读取 binary-safe raw patch bytes。
    pub(super) fn patch_bytes(
        &self,
        kind: &SourceKind,
        cancellation: &CancellationToken,
    ) -> Result<Vec<u8>, ReviewError> {
        match kind {
            SourceKind::Unstaged => Ok(self
                .git
                .diff(
                    &DiffOptions {
                        staged: false,
                        relative_path: None,
                    },
                    cancellation,
                )?
                .bytes),
            SourceKind::Staged => Ok(self
                .git
                .diff(
                    &DiffOptions {
                        staged: true,
                        relative_path: None,
                    },
                    cancellation,
                )?
                .bytes),
            SourceKind::Range { .. } => {
                let mut args = diff_args(kind, false);
                args.push(OsString::from("--"));
                Ok(self.git.run_review_command(&args, cancellation)?)
            }
        }
    }

    /// 将 parsed block 与 hunk fragment 转为 opaque-id file。
    pub(super) fn build_review_file(
        &self,
        path: &str,
        old_path: Option<&str>,
        status: ReviewFileStatus,
        parsed: Option<&ParsedFilePatch>,
        patch: Vec<u8>,
    ) -> Result<ReviewFile, ReviewError> {
        self.validate_relative_path(path)?;
        if let Some(old_path) = old_path {
            self.validate_relative_path(old_path)?;
        }
        let binary = parsed.is_some_and(|value| value.binary) || patch.contains(&0);
        let too_large = patch.len() > MAX_REVIEW_DIFF_BYTES;
        let revision_evidence = if too_large || binary {
            Sha256::digest(&patch).to_vec()
        } else {
            patch.clone()
        };
        let stored_patch = if too_large || binary {
            Vec::new()
        } else {
            patch
        };
        let too_many_lines = parsed.is_some_and(|value| {
            value
                .hunks
                .iter()
                .any(|hunk| hunk.lines.len() >= MAX_REVIEW_DIFF_LINES)
        });
        let metadata_only = binary || too_large || too_many_lines || parsed.is_none();
        let mut hunks = Vec::new();
        if let Some(parsed) = parsed {
            for (index, hunk) in parsed.hunks.iter().enumerate() {
                let hunk_id = ReviewHunkId::parse(hash_hunk_id(path, old_path, index, hunk))
                    .map_err(|_| ReviewError::Parse)?;
                hunks.push(ReviewHunk {
                    hunk_id,
                    header: hunk.header.clone(),
                    old_start: hunk.old_start,
                    old_lines: hunk.old_lines,
                    new_start: hunk.new_start,
                    new_lines: hunk.new_lines,
                    lines: hunk.lines.clone(),
                    raw_patch: hunk.raw_patch.clone(),
                });
            }
        }
        let file_id = ReviewFileId::parse(hash_file_id(path, old_path, status, &revision_evidence))
            .map_err(|_| ReviewError::Parse)?;
        Ok(ReviewFile {
            file_id,
            path: path.to_owned(),
            old_path: old_path.map(str::to_owned),
            status,
            additions: parsed.and_then(|value| value.additions),
            deletions: parsed.and_then(|value| value.deletions),
            binary,
            metadata_only,
            hunks,
            patch: stored_patch,
            revision_evidence,
        })
    }

    /// 通过 workspace guard 读取并合成 untracked file。
    pub(super) fn untracked_file(&self, path: &str) -> Result<ReviewFile, ReviewError> {
        let before = self
            .workspace
            .metadata(path, MAX_REVIEW_DIFF_BYTES as u64)
            .map_err(ReviewError::from)?;
        let resolved = self.workspace.resolve_file(path)?;
        let bytes = read_bounded_file(&resolved)?;
        let after = self
            .workspace
            .metadata(path, MAX_REVIEW_DIFF_BYTES as u64)
            .map_err(ReviewError::from)?;
        if before.revision != after.revision {
            return Err(ReviewError::ReviewStale);
        }
        let parsed = if bytes.len() <= MAX_REVIEW_DIFF_BYTES && !bytes.contains(&0) {
            Some(synthetic_added_patch(path, &bytes))
        } else {
            None
        };
        let mut file = self.build_review_file(
            path,
            None,
            ReviewFileStatus::Untracked,
            parsed.as_ref(),
            if bytes.len() <= MAX_REVIEW_DIFF_BYTES {
                parsed
                    .as_ref()
                    .map(|value| value.raw.clone())
                    .unwrap_or_else(|| bytes.clone())
            } else {
                bytes
            },
        )?;
        let mut evidence = file.revision_evidence.clone();
        evidence.extend_from_slice(&before.revision.size().to_le_bytes());
        evidence.extend_from_slice(
            &before
                .revision
                .modified_unix_millis()
                .unwrap_or_default()
                .to_le_bytes(),
        );
        file.file_id = ReviewFileId::parse(hash_file_id(
            path,
            None,
            ReviewFileStatus::Untracked,
            &evidence,
        ))
        .map_err(|_| ReviewError::Parse)?;
        file.revision_evidence = evidence;
        Ok(file)
    }
}

/// 物化 source 及其 revision 所依据的 native evidence。
#[derive(Debug, Clone)]
pub(super) struct Materialized {
    pub(super) revision: String,
    pub(super) files: Vec<ReviewFile>,
    pub(super) stats: ReviewStats,
}

/// metadata-only file 使用的确定性 empty parsed block。
static EMPTY_PARSED_FILE: ParsedFilePatch = ParsedFilePatch {
    old_path: None,
    new_path: None,
    binary: false,
    additions: None,
    deletions: None,
    raw: Vec::new(),
    hunks: Vec::new(),
};

/// 聚合统计，同时保证 binary 与 metadata-only line total 不被虚构。
fn stats_for_files(files: &[ReviewFile]) -> ReviewStats {
    let mut stats = ReviewStats {
        files: files.len() as u64,
        ..ReviewStats::default()
    };
    for file in files {
        if file.binary {
            stats.binary_files = stats.binary_files.saturating_add(1);
        }
        if file.metadata_only && !file.binary {
            stats.truncated_files = stats.truncated_files.saturating_add(1);
        }
        stats.additions = stats
            .additions
            .saturating_add(file.additions.unwrap_or_default());
        stats.deletions = stats
            .deletions
            .saturating_add(file.deletions.unwrap_or_default());
    }
    stats
}

/// 将 source 与 native patch evidence 哈希为 opaque revision token。
fn hash_revision(source: &ReviewSource, files: &[ReviewFile]) -> Result<String, ReviewError> {
    let mut hasher = Sha256::new();
    // revision 使用内部固定 tag 与受验证 selector，而不是 serde wire；wire 重命名不会意外
    // 改变 CAS identity，领域层也不需要依赖序列化实现。
    match source {
        ReviewSource::Unstaged => hasher.update(b"unstaged"),
        ReviewSource::Staged => hasher.update(b"staged"),
        ReviewSource::Branch { ref_id } => {
            hasher.update(b"branch\0");
            hasher.update(ref_id.as_str().as_bytes());
        }
        ReviewSource::Commit { commit_id } => {
            hasher.update(b"commit\0");
            hasher.update(commit_id.as_str().as_bytes());
        }
    }
    for file in files {
        hasher.update(file.path.as_bytes());
        hasher.update([0]);
        hasher.update(file.old_path.as_deref().unwrap_or_default().as_bytes());
        hasher.update([0]);
        hasher.update(file.revision_evidence.as_slice());
    }
    Ok(hex_digest(hasher.finalize()))
}

/// 将 path/status/patch evidence 哈希为 React 可见 file selector。
fn hash_file_id(
    path: &str,
    old_path: Option<&str>,
    status: ReviewFileStatus,
    patch: &[u8],
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(path.as_bytes());
    hasher.update([0]);
    hasher.update(old_path.unwrap_or_default().as_bytes());
    hasher.update([0]);
    hasher.update(format!("{status:?}").as_bytes());
    hasher.update([0]);
    hasher.update(patch);
    hex_digest(hasher.finalize())
}

/// 将 hunk 坐标与 raw patch evidence 哈希为稳定 hunk selector。
fn hash_hunk_id(path: &str, old_path: Option<&str>, index: usize, hunk: &ParsedHunk) -> String {
    let mut hasher = Sha256::new();
    hasher.update(path.as_bytes());
    hasher.update([0]);
    hasher.update(old_path.unwrap_or_default().as_bytes());
    hasher.update([0]);
    hasher.update(index.to_le_bytes());
    hasher.update([0]);
    hasher.update(hunk.raw_patch.as_slice());
    hex_digest(hasher.finalize())
}

/// 不引入新 serialization dependency 地格式化 SHA-256 digest。
fn hex_digest(digest: impl AsRef<[u8]>) -> String {
    digest
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

/// 从固定 Git metadata output 裁剪一条 ASCII line。
pub(super) fn trim_ascii_line(bytes: &[u8]) -> Result<String, ReviewError> {
    String::from_utf8(bytes.to_vec())
        .map_err(|_| ReviewError::Parse)
        .map(|value| value.trim().to_owned())
}

/// 最多超额读取一个 byte，以 metadata-only 表示大文件且不分配完整内容。
fn read_bounded_file(path: &std::path::Path) -> Result<Vec<u8>, ReviewError> {
    read_bounded_file_limit(path, MAX_REVIEW_DIFF_BYTES)
}

/// 有界读取 file image 供 postimage 校验；额外一个 byte 用于识别 over-limit。
fn read_bounded_file_limit(path: &std::path::Path, limit: usize) -> Result<Vec<u8>, ReviewError> {
    let file = fs::File::open(path).map_err(|_| ReviewError::Io)?;
    let mut bytes = Vec::new();
    file.take((limit as u64).saturating_add(1))
        .read_to_end(&mut bytes)
        .map_err(|_| ReviewError::Io)?;
    Ok(bytes)
}
