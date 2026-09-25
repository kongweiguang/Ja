// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review snapshot 与 diff 物化。

use super::catalog::EMPTY_TREE;
use super::git::model::GitStatusEntry;
use super::git::{CancellationToken, DiffOptions, GitStatusKind};
use super::git_query::{
    NameStatusRecord, NumStatRecord, SourceKind, diff_args, numstat_args, parse_name_status,
    parse_numstat, validate_ref,
};
use super::native::NativeReviewAdapter;
use super::parse::{ParsedFilePatch, ParsedHunk, parse_diff, synthetic_added_patch};
use crate::review::application::ReviewError;
use crate::review::domain::model::{MAX_REVIEW_SNAPSHOT_BYTES, MAX_REVIEW_SNAPSHOT_FILES};
use crate::review::domain::{
    MAX_REVIEW_DIFF_BYTES, MAX_REVIEW_DIFF_LINES, MAX_REVIEW_FILES, ReviewFile, ReviewFileId,
    ReviewFileLayer, ReviewFileStatus, ReviewHunk, ReviewHunkId, ReviewRevision, ReviewSnapshot,
    ReviewSource, ReviewStats, ReviewWorktreeEvidence,
};
use crate::workspace::{ResolvedPath, is_reparse_point};
use sha2::{Digest, Sha256};
use std::collections::{BTreeSet, HashMap};
use std::ffi::OsString;
use std::fs;
use std::io::Read;
use std::path::Path;
use std::sync::mpsc::{SyncSender, sync_channel};
use std::thread::JoinHandle;
use std::time::{Duration, Instant, UNIX_EPOCH};

const MAX_REVIEW_SNAPSHOT_TIME: Duration = Duration::from_secs(30);

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
        let mut budget = SnapshotBudget::new(cancellation);
        if !source.is_read_only() {
            return self.materialize_mutable(source, &mut budget, true);
        }
        self.materialize_single(source, &mut budget)
    }

    /// 仅重算 mutable source 的强内容证据，供 cached file diff freshness 与 mutation CAS 使用。
    pub(super) fn probe_revision(
        &self,
        source: &ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<String, ReviewError> {
        let mut budget = SnapshotBudget::new(cancellation);
        if source.is_read_only() {
            return self
                .materialize(source, cancellation)
                .map(|value| value.revision);
        }
        self.materialize_mutable(source, &mut budget, false)
            .map(|value| value.revision)
    }

    /// 复用 cache strong digest 校验轻量 manifest，只对当前选中文件重新计算内容摘要。
    pub(super) fn probe_cached_revision(
        &self,
        source: &ReviewSource,
        cached: &ReviewSnapshot,
        selected_file_id: &ReviewFileId,
        cancellation: &CancellationToken,
    ) -> Result<String, ReviewError> {
        if source.is_read_only() || cached.source != *source {
            return Err(ReviewError::ReviewStale);
        }
        let mut budget = SnapshotBudget::new(cancellation);
        let statuses = self.git.review_status_all(budget.cancellation())?;
        let records = mutable_records(source, statuses)?;
        if records.len() != cached.files.len() {
            return Err(ReviewError::ReviewStale);
        }
        budget.reserve(records.len(), 0)?;
        let cached_by_path = cached
            .files
            .iter()
            .map(|file| ((file.layer, file.path.as_str()), file))
            .collect::<HashMap<_, _>>();
        let mut parent_guards = HashMap::new();
        let mut files = Vec::with_capacity(records.len());
        for record in records {
            budget.check()?;
            let cached_file = cached_by_path
                .get(&(record.layer, record.path.as_str()))
                .copied()
                .ok_or(ReviewError::ReviewStale)?;
            if cached_file.old_path != record.old_path
                || cached_file.status != record.status
                || cached_file.state_evidence != record.state_evidence
            {
                return Err(ReviewError::ReviewStale);
            }
            let mut revision_evidence = record.state_evidence;
            if record.read_worktree {
                let cached_worktree = cached_file
                    .worktree_evidence
                    .as_ref()
                    .ok_or(ReviewError::ReviewStale)?;
                if !self.light_metadata_matches(
                    &record.path,
                    cached_worktree,
                    &mut parent_guards,
                )? {
                    return Err(ReviewError::ReviewStale);
                }
                if &cached_file.file_id == selected_file_id {
                    let selected = self.scan_file_evidence(&record.path, &mut budget)?;
                    if selected.digest != cached_worktree.digest {
                        return Err(ReviewError::ReviewStale);
                    }
                }
                revision_evidence.extend_from_slice(&cached_worktree.digest);
                revision_evidence.extend_from_slice(&cached_worktree.size.to_le_bytes());
                revision_evidence
                    .extend_from_slice(&cached_worktree.modified_unix_millis.to_le_bytes());
            }
            let mut file = cached_file.clone();
            file.revision_evidence = revision_evidence;
            files.push(file);
        }
        self.verify_parent_guards(&parent_guards, &budget)?;
        let confirmed = self.git.review_status_all(budget.cancellation())?;
        self.confirm_mutable_manifest_from_statuses(source, &files, &mut budget, confirmed)?;
        hash_revision(source, &files)
    }

    /// 从单次 porcelain-v2 状态与可选 numstat 构造 metadata-first mutable tree。
    fn materialize_mutable(
        &self,
        source: &ReviewSource,
        budget: &mut SnapshotBudget,
        include_stats: bool,
    ) -> Result<Materialized, ReviewError> {
        budget.check()?;
        let statuses = self.git.review_status_all(budget.cancellation())?;
        let records = mutable_records(source, statuses)?;
        budget.reserve(records.len(), 0)?;
        let stats = if include_stats {
            self.mutable_numstats(source, budget.cancellation())?
        } else {
            HashMap::new()
        };
        let mut files = Vec::with_capacity(records.len());
        for record in records {
            budget.check()?;
            files.push(self.build_lazy_mutable_file(record, &stats, budget)?);
        }
        self.confirm_mutable_manifest(source, &files, budget)?;
        let revision = hash_revision(source, &files)?;
        Ok(Materialized {
            stats: stats_for_files(&files),
            revision,
            files,
        })
    }

    /// 扫描结束后用第二次轻量 status + metadata 复验 manifest，拒绝收集窗口中的外部漂移。
    fn confirm_mutable_manifest(
        &self,
        source: &ReviewSource,
        files: &[ReviewFile],
        budget: &mut SnapshotBudget,
    ) -> Result<(), ReviewError> {
        budget.check()?;
        let statuses = self.git.review_status_all(budget.cancellation())?;
        self.confirm_mutable_manifest_from_statuses(source, files, budget, statuses)
    }

    /// 使用已读取的第二轮 status 复核 manifest，使普通与 session 路径共享 evidence 规则。
    fn confirm_mutable_manifest_from_statuses(
        &self,
        source: &ReviewSource,
        files: &[ReviewFile],
        budget: &mut SnapshotBudget,
        statuses: Vec<GitStatusEntry>,
    ) -> Result<(), ReviewError> {
        budget.check()?;
        let records = mutable_records(source, statuses)?;
        if records.len() != files.len() {
            return Err(ReviewError::ReviewStale);
        }
        let files_by_path = files
            .iter()
            .map(|file| ((file.layer, file.path.as_str()), file))
            .collect::<HashMap<_, _>>();
        let mut parent_guards = HashMap::new();
        for record in records {
            budget.check()?;
            let file = files_by_path
                .get(&(record.layer, record.path.as_str()))
                .copied()
                .ok_or(ReviewError::ReviewStale)?;
            if file.old_path != record.old_path
                || file.status != record.status
                || file.state_evidence != record.state_evidence
            {
                return Err(ReviewError::ReviewStale);
            }
            if record.read_worktree {
                let expected = file
                    .worktree_evidence
                    .as_ref()
                    .ok_or(ReviewError::ReviewStale)?;
                if !self.light_metadata_matches(&record.path, expected, &mut parent_guards)? {
                    return Err(ReviewError::ReviewStale);
                }
            }
        }
        self.verify_parent_guards(&parent_guards, budget)?;
        Ok(())
    }

    /// cached manifest 复用父目录物理 guard，避免每个文件重复 canonicalize；末端仍拒绝链接。
    fn light_metadata_matches(
        &self,
        path: &str,
        expected: &ReviewWorktreeEvidence,
        parent_guards: &mut HashMap<String, ResolvedPath>,
    ) -> Result<bool, ReviewError> {
        self.validate_relative_path(path)?;
        let relative = Path::new(path);
        let name = relative.file_name().ok_or(ReviewError::ReviewStale)?;
        let parent = relative.parent().unwrap_or_else(|| Path::new(""));
        let parent_relative = parent.to_str().ok_or(ReviewError::ReviewStale)?;
        if !parent_guards.contains_key(parent_relative) {
            let guard = self
                .workspace
                .resolve_guard(parent_relative, Some(true))
                .map_err(ReviewError::from)?;
            parent_guards.insert(parent_relative.to_owned(), guard);
        }
        let guard = parent_guards
            .get(parent_relative)
            .ok_or(ReviewError::ReviewStale)?;
        let candidate = guard.path.join(name);
        let metadata = fs::symlink_metadata(candidate).map_err(|_| ReviewError::ReviewStale)?;
        if !metadata.is_file() || metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
            return Ok(false);
        }
        let modified = metadata
            .modified()
            .ok()
            .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
            .map(|value| value.as_millis())
            .unwrap_or_default();
        Ok(metadata.len() == expected.size && modified == expected.modified_unix_millis)
    }

    /// 一轮 manifest 结束后重验所有共享父目录，封闭目录替换或 junction 竞态窗口。
    fn verify_parent_guards(
        &self,
        parent_guards: &HashMap<String, ResolvedPath>,
        budget: &SnapshotBudget,
    ) -> Result<(), ReviewError> {
        for guard in parent_guards.values() {
            budget.check()?;
            self.workspace
                .verify_resolved(guard, Some(true))
                .map_err(ReviewError::from)?;
        }
        Ok(())
    }

    /// 只在首开树时查询 staged/unstaged numstat；freshness probe 不为展示统计重复做 diff。
    fn mutable_numstats(
        &self,
        source: &ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<HashMap<(ReviewFileLayer, String), NumStatRecord>, ReviewError> {
        let mut result = HashMap::new();
        let mut read_layer =
            |kind: SourceKind, layer: ReviewFileLayer| -> Result<(), ReviewError> {
                let bytes = self
                    .git
                    .run_review_command(&numstat_args(&kind), cancellation)?;
                for record in parse_numstat(&self.workspace, &bytes)? {
                    result.insert((layer, record.path.clone()), record);
                }
                Ok(())
            };
        if matches!(source, ReviewSource::Uncommitted | ReviewSource::Staged) {
            read_layer(SourceKind::Staged, ReviewFileLayer::Staged)?;
        }
        if matches!(source, ReviewSource::Uncommitted | ReviewSource::Unstaged) {
            read_layer(SourceKind::Unstaged, ReviewFileLayer::Unstaged)?;
        }
        Ok(result)
    }

    /// 为单条 mutable status 生成选择身份；工作区正文只流式读取一次用于强摘要。
    fn build_lazy_mutable_file(
        &self,
        record: MutableRecord,
        stats: &HashMap<(ReviewFileLayer, String), NumStatRecord>,
        budget: &mut SnapshotBudget,
    ) -> Result<ReviewFile, ReviewError> {
        self.validate_relative_path(&record.path)?;
        if let Some(old_path) = record.old_path.as_deref() {
            self.validate_relative_path(old_path)?;
        }
        let scan = if record.read_worktree {
            Some(self.scan_file_evidence(&record.path, budget)?)
        } else {
            None
        };
        let stat = stats.get(&(record.layer, record.path.clone()));
        let binary = stat
            .is_some_and(|value| value.additions.is_none() || value.deletions.is_none())
            || scan.as_ref().is_some_and(|value| value.binary);
        let (additions, deletions) = if record.layer == ReviewFileLayer::Untracked {
            if binary {
                (None, None)
            } else {
                (scan.as_ref().map(|value| value.lines), Some(0))
            }
        } else {
            (
                stat.and_then(|value| value.additions),
                stat.and_then(|value| value.deletions),
            )
        };
        let state_evidence = record.state_evidence;
        let mut evidence = state_evidence.clone();
        if let Some(scan) = scan.as_ref() {
            evidence.extend_from_slice(&scan.digest);
            evidence.extend_from_slice(&scan.size.to_le_bytes());
            evidence.extend_from_slice(&scan.modified_unix_millis.to_le_bytes());
        }
        let file_id = ReviewFileId::parse(hash_file_id(
            &record.path,
            record.old_path.as_deref(),
            record.status,
            record.layer,
            &evidence,
        ))
        .map_err(|_| ReviewError::Parse)?;
        Ok(ReviewFile {
            file_id,
            layer: record.layer,
            path: record.path,
            old_path: record.old_path,
            status: record.status,
            additions,
            deletions,
            binary,
            metadata_only: false,
            hunks: Vec::new(),
            diff_loaded: false,
            patch: Vec::new(),
            revision_evidence: evidence,
            state_evidence,
            worktree_evidence: scan.map(|value| ReviewWorktreeEvidence {
                digest: value.digest,
                size: value.size,
                modified_unix_millis: value.modified_unix_millis,
            }),
        })
    }

    /// 读取一个 regular file 的强 SHA、行数与 binary probe，并在读取前后复核 metadata。
    fn scan_file_evidence(
        &self,
        path: &str,
        budget: &mut SnapshotBudget,
    ) -> Result<FileScanEvidence, ReviewError> {
        budget.check()?;
        let resolved = self.workspace.resolve_guard(path, Some(false))?;
        let before = fs::symlink_metadata(&resolved.path).map_err(|_| ReviewError::Io)?;
        budget.reserve(0, before.len())?;
        let mut file = fs::File::open(&resolved.path).map_err(|_| ReviewError::Io)?;
        let mut digest = Sha256::new();
        let mut buffer = [0_u8; 64 * 1024];
        let expected_bytes = before.len();
        let mut total_bytes = 0_u64;
        let mut lines = 0_u64;
        let mut binary = false;
        let mut last = None;
        loop {
            budget.check()?;
            let remaining = expected_bytes
                .checked_add(1)
                .and_then(|limit| limit.checked_sub(total_bytes))
                .ok_or(ReviewError::OutputLimitExceeded)?;
            let read_limit = usize::try_from(remaining)
                .unwrap_or(buffer.len())
                .min(buffer.len());
            if read_limit == 0 {
                return Err(ReviewError::ReviewStale);
            }
            let read = file
                .read(&mut buffer[..read_limit])
                .map_err(|_| ReviewError::Io)?;
            if read == 0 {
                break;
            }
            total_bytes = total_bytes
                .checked_add(u64::try_from(read).map_err(|_| ReviewError::OutputLimitExceeded)?)
                .ok_or(ReviewError::OutputLimitExceeded)?;
            if total_bytes > expected_bytes {
                return Err(ReviewError::ReviewStale);
            }
            let chunk = &buffer[..read];
            digest.update(chunk);
            binary |= chunk.contains(&0);
            lines =
                lines.saturating_add(chunk.iter().filter(|byte| **byte == b'\n').count() as u64);
            last = chunk.last().copied();
        }
        if total_bytes != expected_bytes {
            return Err(ReviewError::ReviewStale);
        }
        if expected_bytes > 0 && last != Some(b'\n') {
            lines = lines.saturating_add(1);
        }
        let after = fs::symlink_metadata(&resolved.path).map_err(|_| ReviewError::Io)?;
        self.workspace.verify_resolved(&resolved, Some(false))?;
        let before_modified = before
            .modified()
            .ok()
            .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
            .map(|value| value.as_millis())
            .unwrap_or_default();
        let after_modified = after
            .modified()
            .ok()
            .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
            .map(|value| value.as_millis())
            .unwrap_or_default();
        if after.len() != expected_bytes || after_modified != before_modified {
            return Err(ReviewError::ReviewStale);
        }
        Ok(FileScanEvidence {
            digest: digest.finalize().into(),
            size: expected_bytes,
            modified_unix_millis: before_modified,
            lines,
            binary,
        })
    }

    /// 物化只读 Git comparison；mutable source 已统一走 metadata-first，不能回退 eager patch。
    fn materialize_single(
        &self,
        source: &ReviewSource,
        budget: &mut SnapshotBudget,
    ) -> Result<Materialized, ReviewError> {
        budget.check()?;
        let operation_cancellation = budget.cancellation().clone();
        let kind = self.source_kind(source, &operation_cancellation)?;
        let records = self.name_status(&kind, &operation_cancellation)?;
        budget.check()?;
        let patch_bytes = self.patch_bytes(&kind, &operation_cancellation)?;
        budget.check()?;
        budget.reserve(records.len(), patch_bytes.len() as u64)?;
        let parsed = if patch_bytes.len() <= MAX_REVIEW_DIFF_BYTES {
            parse_diff(&patch_bytes)?
        } else {
            Vec::new()
        };
        let mut used = BTreeSet::new();
        let mut files = Vec::with_capacity(records.len());
        let unmatched_evidence =
            format!("snapshot:{}", hex_digest(Sha256::digest(&patch_bytes))).into_bytes();
        for record in records {
            budget.check()?;
            let parsed_file = parsed.iter().enumerate().find_map(|(index, value)| {
                if used.contains(&index) {
                    return None;
                }
                if parsed_file_matches(&record.path, record.old_path.as_deref(), value) {
                    Some((index, value))
                } else {
                    None
                }
            });
            // 路径没有匹配的 patch 时保持 metadata-only；回退任意未使用 block 会把另一
            // 文件的正文、hunk 和 mutation patch 绑定到当前 file identity。
            let (parsed_index, parsed_file) =
                parsed_file.unwrap_or((usize::MAX, &EMPTY_PARSED_FILE));
            if parsed_index != usize::MAX {
                used.insert(parsed_index);
            }
            files.push(self.build_review_file(
                &record.path,
                record.old_path.as_deref(),
                record.status,
                layer_for_source(source, record.status),
                (!parsed_file.raw.is_empty()).then_some(parsed_file),
                if parsed_file.raw.is_empty() {
                    unmatched_evidence.clone()
                } else {
                    parsed_file.raw.clone()
                },
            )?);
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

    /// 只物化所选文件，并让每条 Git 命令沿用完整安全检查与同一读取期限。
    pub(super) fn materialize_one_file(
        &self,
        source: &ReviewSource,
        file: &ReviewFile,
        cancellation: &CancellationToken,
    ) -> Result<ReviewFile, ReviewError> {
        let budget = SnapshotBudget::new(cancellation);
        if file.layer == ReviewFileLayer::Untracked {
            let mut materialized = self.untracked_file(&file.path, None, &budget)?;
            preserve_snapshot_identity(&mut materialized, file);
            return Ok(materialized);
        }
        let effective_source = effective_source_for_file(source, file)?;
        let kind = self.source_kind(&effective_source, budget.cancellation())?;
        let path = self.validated_path_arg(&file.path)?;
        let mut args = diff_args(&kind, false);
        args.push(OsString::from("--"));
        args.push(OsString::from(path));
        let bytes = self.git.run_review_command(&args, budget.cancellation())?;
        budget.check()?;
        let parsed = if bytes.len() > MAX_REVIEW_DIFF_BYTES {
            None
        } else {
            parse_diff(&bytes)?.into_iter().find(|candidate| {
                parsed_file_matches(&file.path, file.old_path.as_deref(), candidate)
            })
        };
        if parsed.is_none() && bytes.len() <= MAX_REVIEW_DIFF_BYTES {
            return Err(ReviewError::ReviewStale);
        }
        let mut materialized = self.build_review_file(
            &file.path,
            file.old_path.as_deref(),
            file.status,
            file.layer,
            parsed.as_ref(),
            bytes,
        )?;
        // snapshot 的 opaque selector 表示已确认的聚合身份；lazy 单文件 patch 只补正文，
        // 不能以不同证据重算 selector，否则严格调用方会把正确响应视为另一文件。
        preserve_snapshot_identity(&mut materialized, file);
        Ok(materialized)
    }

    /// 在 Git process 启动前计算 source command kind 并校验 ref selector。
    pub(super) fn source_kind(
        &self,
        source: &ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<SourceKind, ReviewError> {
        match source {
            ReviewSource::Uncommitted => Err(ReviewError::InvalidInput),
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
        layer: ReviewFileLayer,
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
        let stored_patch = if too_large || binary || parsed.is_none() {
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
        let file_id = ReviewFileId::parse(hash_file_id(
            path,
            old_path,
            status,
            layer,
            &revision_evidence,
        ))
        .map_err(|_| ReviewError::Parse)?;
        Ok(ReviewFile {
            file_id,
            layer,
            path: path.to_owned(),
            old_path: old_path.map(str::to_owned),
            status,
            additions: parsed.and_then(|value| value.additions),
            deletions: parsed.and_then(|value| value.deletions),
            binary,
            metadata_only,
            hunks,
            diff_loaded: true,
            patch: stored_patch,
            state_evidence: revision_evidence.clone(),
            worktree_evidence: None,
            revision_evidence,
        })
    }

    /// 通过 workspace guard 读取并合成 untracked file；预检尺寸用于阻止读取前的增长绕过全局预算。
    fn untracked_file(
        &self,
        path: &str,
        preflight_size: Option<u64>,
        budget: &SnapshotBudget,
    ) -> Result<ReviewFile, ReviewError> {
        budget.check()?;
        let before = self
            .workspace
            .metadata(path, 0)
            .map_err(ReviewError::from)?;
        budget.check()?;
        if preflight_size.is_some_and(|size| size != before.size) {
            return Err(ReviewError::ReviewStale);
        }
        let resolved = self.workspace.resolve_file(path)?;
        let bytes = read_bounded_file(&resolved, budget)?;
        budget.check()?;
        let after = self
            .workspace
            .metadata(path, 0)
            .map_err(ReviewError::from)?;
        budget.check()?;
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
            ReviewFileLayer::Untracked,
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
            ReviewFileLayer::Untracked,
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

/// porcelain-v2 一条状态按 layer 展开后的 metadata-first 文件记录。
struct MutableRecord {
    path: String,
    old_path: Option<String>,
    layer: ReviewFileLayer,
    status: ReviewFileStatus,
    state_evidence: Vec<u8>,
    read_worktree: bool,
}

/// 单次有界流式读取产生的强内容证据；不保存正文或 synthetic patch。
struct FileScanEvidence {
    digest: [u8; 32],
    size: u64,
    modified_unix_millis: u128,
    lines: u64,
    binary: bool,
}

/// 把 status 的 XY 状态投影到请求 source；同路径 staged/unstaged 保持两个稳定 layer identity。
fn mutable_records(
    source: &ReviewSource,
    statuses: Vec<GitStatusEntry>,
) -> Result<Vec<MutableRecord>, ReviewError> {
    let mut records = Vec::new();
    for entry in statuses {
        match entry.kind {
            GitStatusKind::Head | GitStatusKind::Ignored => continue,
            GitStatusKind::Untracked => {
                if matches!(source, ReviewSource::Uncommitted | ReviewSource::Unstaged) {
                    records.push(MutableRecord {
                        path: entry.path,
                        old_path: None,
                        layer: ReviewFileLayer::Untracked,
                        status: ReviewFileStatus::Untracked,
                        state_evidence: b"untracked".to_vec(),
                        read_worktree: true,
                    });
                }
            }
            GitStatusKind::Unmerged => {
                if matches!(source, ReviewSource::Uncommitted | ReviewSource::Unstaged) {
                    records.push(MutableRecord {
                        read_worktree: entry.worktree_status != Some('D'),
                        path: entry.path,
                        old_path: None,
                        layer: ReviewFileLayer::Unstaged,
                        status: ReviewFileStatus::Conflict,
                        state_evidence: entry.state_evidence,
                    });
                }
            }
            GitStatusKind::Changed | GitStatusKind::Renamed => {
                if matches!(source, ReviewSource::Uncommitted | ReviewSource::Staged)
                    && entry.index_status.is_some_and(status_is_changed)
                {
                    let status = status_from_porcelain(entry.index_status.unwrap_or('M'));
                    records.push(MutableRecord {
                        path: entry.path.clone(),
                        old_path: matches!(
                            status,
                            ReviewFileStatus::Renamed | ReviewFileStatus::Copied
                        )
                        .then(|| entry.original_path.clone())
                        .flatten(),
                        layer: ReviewFileLayer::Staged,
                        status,
                        state_evidence: entry.state_evidence.clone(),
                        read_worktree: false,
                    });
                }
                if matches!(source, ReviewSource::Uncommitted | ReviewSource::Unstaged)
                    && entry.worktree_status.is_some_and(status_is_changed)
                {
                    let status = status_from_porcelain(entry.worktree_status.unwrap_or('M'));
                    records.push(MutableRecord {
                        path: entry.path,
                        old_path: matches!(
                            status,
                            ReviewFileStatus::Renamed | ReviewFileStatus::Copied
                        )
                        .then_some(entry.original_path)
                        .flatten(),
                        layer: ReviewFileLayer::Unstaged,
                        status,
                        state_evidence: entry.state_evidence,
                        read_worktree: status != ReviewFileStatus::Deleted,
                    });
                }
            }
        }
    }
    if records.len() > MAX_REVIEW_SNAPSHOT_FILES {
        return Err(ReviewError::OutputLimitExceeded);
    }
    Ok(records)
}

/// porcelain `.` 表示该 layer 未变化，其它封闭状态字符都进入明确 Review 状态。
fn status_is_changed(status: char) -> bool {
    !matches!(status, '.' | ' ')
}

/// 将 porcelain XY 字符映射为 Review 封闭状态；未知 Git 扩展保守归为 Modified。
fn status_from_porcelain(status: char) -> ReviewFileStatus {
    match status {
        'A' => ReviewFileStatus::Added,
        'D' => ReviewFileStatus::Deleted,
        'R' => ReviewFileStatus::Renamed,
        'C' => ReviewFileStatus::Copied,
        'U' => ReviewFileStatus::Conflict,
        _ => ReviewFileStatus::Modified,
    }
}

/// path-specific 正文只补充展示材料，snapshot 的 opaque identity 与 cache manifest 必须保持不变。
fn preserve_snapshot_identity(materialized: &mut ReviewFile, snapshot_file: &ReviewFile) {
    materialized.file_id = snapshot_file.file_id.clone();
    materialized.revision_evidence = snapshot_file.revision_evidence.clone();
    materialized.state_evidence = snapshot_file.state_evidence.clone();
    materialized.worktree_evidence = snapshot_file.worktree_evidence.clone();
}

/// 单次 snapshot 的共享文件、字节与 wall-clock 预算；聚合层不能各自重置上限。
struct SnapshotBudget {
    files: usize,
    bytes: u64,
    deadline: Instant,
    cancellation: CancellationToken,
    timer_stop: Option<SyncSender<()>>,
    timer: Option<JoinHandle<()>>,
}

impl SnapshotBudget {
    /// 从一个统一 deadline 开始预算，使多层 Git 查询仍属于一次有界交互。
    fn new(caller: &CancellationToken) -> Self {
        let now = Instant::now();
        Self::with_deadline(
            caller,
            now.checked_add(MAX_REVIEW_SNAPSHOT_TIME).unwrap_or(now),
        )
    }

    /// 一致性复验重置输出计数但沿用原 deadline，避免通过重验延长整个操作寿命。
    fn with_deadline(caller: &CancellationToken, deadline: Instant) -> Self {
        let cancellation = caller.child_token();
        let timer_cancellation = cancellation.clone();
        let wait = deadline.saturating_duration_since(Instant::now());
        let (timer_stop, stopped) = sync_channel(1);
        let timer = std::thread::spawn(move || {
            if stopped.recv_timeout(wait).is_err() {
                timer_cancellation.cancel();
            }
        });
        Self {
            files: 0,
            bytes: 0,
            deadline,
            cancellation,
            timer_stop: Some(timer_stop),
            timer: Some(timer),
        }
    }

    /// 每个 Git/文件 IO 阶段前后检查取消与 deadline；底层 Git runner 仍保留单进程超时。
    fn check(&self) -> Result<(), ReviewError> {
        if Instant::now() >= self.deadline {
            return Err(ReviewError::OutputLimitExceeded);
        }
        ensure_not_cancelled(&self.cancellation)?;
        Ok(())
    }

    /// Git runner 与文件读取共享 deadline token，使正在运行的子进程也能在总预算到期时退出。
    fn cancellation(&self) -> &CancellationToken {
        &self.cancellation
    }

    /// 在正文读取前一次性预留本层资源，并使用 checked arithmetic 防止上限回绕。
    fn reserve(&mut self, files: usize, bytes: u64) -> Result<(), ReviewError> {
        self.files = self
            .files
            .checked_add(files)
            .ok_or(ReviewError::OutputLimitExceeded)?;
        self.bytes = self
            .bytes
            .checked_add(bytes)
            .ok_or(ReviewError::OutputLimitExceeded)?;
        enforce_snapshot_budget(self.files, self.bytes, std::iter::empty())
    }
}

impl Drop for SnapshotBudget {
    /// 正常完成时唤醒 timer 并 join，避免每次快照留下一个等待 30 秒的后台线程。
    fn drop(&mut self) {
        if let Some(stop) = self.timer_stop.take() {
            let _ = stop.send(());
        }
        if let Some(timer) = self.timer.take() {
            let _ = timer.join();
        }
    }
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
    let distinct_paths = files
        .iter()
        .map(|file| file.path.as_str())
        .collect::<BTreeSet<_>>();
    let mut stats = ReviewStats {
        files: distinct_paths.len() as u64,
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

/// patch block 必须精确对应 name-status 的当前路径与 rename 旧路径；不允许借用相邻 block。
fn parsed_file_matches(path: &str, old_path: Option<&str>, candidate: &ParsedFilePatch) -> bool {
    match old_path {
        Some(old_path) => {
            candidate.new_path.as_deref() == Some(path)
                && candidate.old_path.as_deref() == Some(old_path)
        }
        None => {
            candidate.new_path.as_deref() == Some(path)
                || candidate.old_path.as_deref() == Some(path)
        }
    }
}

/// 将 source 与 native patch evidence 哈希为 opaque revision token。
fn hash_revision(source: &ReviewSource, files: &[ReviewFile]) -> Result<String, ReviewError> {
    let mut hasher = Sha256::new();
    // revision 使用内部固定 tag 与受验证 selector，而不是 serde wire；wire 重命名不会意外
    // 改变 CAS identity，领域层也不需要依赖序列化实现。
    match source {
        ReviewSource::Uncommitted => hasher.update(b"uncommitted"),
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
        hasher.update(format!("{:?}", file.layer).as_bytes());
        hasher.update([0]);
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
    layer: ReviewFileLayer,
    patch: &[u8],
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(format!("{layer:?}").as_bytes());
    hasher.update([0]);
    hasher.update(path.as_bytes());
    hasher.update([0]);
    hasher.update(old_path.unwrap_or_default().as_bytes());
    hasher.update([0]);
    hasher.update(format!("{status:?}").as_bytes());
    hasher.update([0]);
    hasher.update(patch);
    hex_digest(hasher.finalize())
}

/// 基础来源决定文件层；untracked 状态必须独立于 unstaged tracked diff 展示与路由。
fn layer_for_source(source: &ReviewSource, status: ReviewFileStatus) -> ReviewFileLayer {
    match source {
        ReviewSource::Staged => ReviewFileLayer::Staged,
        ReviewSource::Unstaged if status == ReviewFileStatus::Untracked => {
            ReviewFileLayer::Untracked
        }
        ReviewSource::Unstaged => ReviewFileLayer::Unstaged,
        ReviewSource::Branch { .. } | ReviewSource::Commit { .. } => ReviewFileLayer::Comparison,
        ReviewSource::Uncommitted => ReviewFileLayer::Comparison,
    }
}

/// 聚合文件补读必须回到其真实 Git 比较层，禁止把 staged patch 当 worktree patch 读取。
fn effective_source_for_file(
    source: &ReviewSource,
    file: &ReviewFile,
) -> Result<ReviewSource, ReviewError> {
    if !matches!(source, ReviewSource::Uncommitted) {
        return Ok(source.clone());
    }
    match file.layer {
        ReviewFileLayer::Staged => Ok(ReviewSource::Staged),
        ReviewFileLayer::Unstaged | ReviewFileLayer::Untracked => Ok(ReviewSource::Unstaged),
        ReviewFileLayer::Comparison => Err(ReviewError::InvalidInput),
    }
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

/// 检查 cooperative cancellation；独立 helper 让预检、逐文件和读取循环使用同一错误语义。
fn ensure_not_cancelled(cancellation: &CancellationToken) -> Result<(), ReviewError> {
    if cancellation.is_cancelled() {
        return Err(ReviewError::Cancelled);
    }
    Ok(())
}

/// 聚合 snapshot 文件数与逻辑正文总量；checked arithmetic 防止超大尺寸回绕预算。
pub(crate) fn enforce_snapshot_budget(
    file_count: usize,
    tracked_patch_bytes: u64,
    untracked_sizes: impl IntoIterator<Item = u64>,
) -> Result<(), ReviewError> {
    if file_count > MAX_REVIEW_SNAPSHOT_FILES || tracked_patch_bytes > MAX_REVIEW_SNAPSHOT_BYTES {
        return Err(ReviewError::OutputLimitExceeded);
    }
    let total = untracked_sizes
        .into_iter()
        .try_fold(tracked_patch_bytes, |total, size| total.checked_add(size))
        .ok_or(ReviewError::OutputLimitExceeded)?;
    if total > MAX_REVIEW_SNAPSHOT_BYTES {
        return Err(ReviewError::OutputLimitExceeded);
    }
    Ok(())
}

/// 最多超额读取一个 byte，以 metadata-only 表示大文件，并在每个 IO chunk 之间响应取消。
fn read_bounded_file(
    path: &std::path::Path,
    budget: &SnapshotBudget,
) -> Result<Vec<u8>, ReviewError> {
    budget.check()?;
    let file = fs::File::open(path).map_err(|_| ReviewError::Io)?;
    let bytes = read_bounded_with_cancellation(file, MAX_REVIEW_DIFF_BYTES, budget.cancellation())?;
    budget.check()?;
    Ok(bytes)
}

/// 按固定块读取并在块间检查 token；同步 `Read` 本身不可中断，但单次阻塞不会扩大到整文件。
pub(crate) fn read_bounded_with_cancellation(
    mut reader: impl Read,
    limit: usize,
    cancellation: &CancellationToken,
) -> Result<Vec<u8>, ReviewError> {
    let read_limit = limit
        .checked_add(1)
        .ok_or(ReviewError::OutputLimitExceeded)?;
    let mut bytes = Vec::with_capacity(read_limit.min(64 * 1024));
    let mut buffer = [0_u8; 16 * 1024];
    while bytes.len() < read_limit {
        ensure_not_cancelled(cancellation)?;
        let remaining = read_limit - bytes.len();
        let read_len = remaining.min(buffer.len());
        let count = reader
            .read(&mut buffer[..read_len])
            .map_err(|_| ReviewError::Io)?;
        if count == 0 {
            break;
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
    ensure_not_cancelled(cancellation)?;
    Ok(bytes)
}
