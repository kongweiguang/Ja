// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::content::decode_content;
use super::registry::{WorkspaceHandle, entry_kind, is_reparse_point};
use super::tree::join_relative;
use crate::workspace::WorkspaceError;
use crate::workspace::domain::{ContentKind, SearchHit, TextEncoding};
use std::collections::VecDeque;
use std::fs;
use std::io::Read;
use std::time::{Duration, Instant};

/// 交互式搜索排除版本控制 metadata 与可再生 build/cache 目录；这些目录通常包含
/// 大量 binary 或生成文件，遍历会在到达项目源码前耗尽固定扫描预算。
pub(crate) fn is_default_ignored_directory(name: &str) -> bool {
    const EXACT: [&str; 10] = [
        ".git",
        ".hg",
        ".svn",
        ".cache",
        ".next",
        ".nuxt",
        ".skills-cache",
        "node_modules",
        "target",
        "coverage",
    ];
    EXACT
        .iter()
        .any(|candidate| name.eq_ignore_ascii_case(candidate))
        || name.eq_ignore_ascii_case("dist")
        || name.eq_ignore_ascii_case("build")
        || name.eq_ignore_ascii_case(".codex-target")
        || name
            .get(..7)
            .is_some_and(|prefix| prefix.eq_ignore_ascii_case("target-"))
        || name.eq_ignore_ascii_case(".tmp")
        || name
            .get(..5)
            .is_some_and(|prefix| prefix.eq_ignore_ascii_case(".tmp-"))
}

/// Watcher 与搜索共用相同的可再生目录边界，避免构建产物既耗尽扫描预算，
/// 又通过高频原生事件反向触发无意义的全量 reconciliation。
pub(crate) fn is_default_ignored_relative_path(relative_path: &str) -> bool {
    relative_path
        .split(['/', '\\'])
        .filter(|component| !component.is_empty())
        .any(is_default_ignored_directory)
}

/// 搜索策略通过条目、字节、结果与时间预算保证查询天然有界，即使仓库含海量生成文件或大 binary。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SearchPolicy {
    pub max_depth: usize,
    pub max_entries: usize,
    pub max_file_bytes: u64,
    pub max_total_bytes: u64,
    pub max_results: usize,
    pub max_query_bytes: usize,
    pub max_scan_millis: u64,
}

impl Default for SearchPolicy {
    /// 默认值限制交互式搜索的遍历与结果规模，调用方不能静默扩大预算。
    fn default() -> Self {
        Self {
            max_depth: 64,
            max_entries: 100_000,
            max_file_bytes: 4 * 1024 * 1024,
            max_total_bytes: 64 * 1024 * 1024,
            max_results: 500,
            max_query_bytes: 8 * 1024,
            max_scan_millis: 2_000,
        }
    }
}

/// 搜索结果显式报告截断状态，UI 不得把有界局部结果展示成穷尽结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TextSearchResult {
    pub hits: Vec<SearchHit>,
    pub truncated: bool,
    pub scanned_entries: usize,
    pub skipped_files: usize,
}

/// 文本搜索器在 Workspace 内执行字面量搜索，且永不跟随 link 或 reparse point。
#[derive(Debug, Clone)]
pub struct TextSearch {
    workspace: WorkspaceHandle,
    policy: SearchPolicy,
}

impl TextSearch {
    /// 把搜索绑定到 Tree 共用的 opaque Workspace root 与有界策略，避免形成第二个路径 owner。
    pub fn new(workspace: WorkspaceHandle, policy: SearchPolicy) -> Self {
        let policy = SearchPolicy {
            max_depth: policy.max_depth.min(256),
            max_entries: policy.max_entries.clamp(1, 1_000_000),
            max_file_bytes: policy.max_file_bytes.min(64 * 1024 * 1024),
            max_total_bytes: policy.max_total_bytes.min(512 * 1024 * 1024),
            max_results: policy.max_results.clamp(1, 100_000),
            max_query_bytes: policy.max_query_bytes.clamp(1, 64 * 1024),
            max_scan_millis: policy.max_scan_millis.clamp(1, 60_000),
        };
        Self { workspace, policy }
    }

    /// 按条目、字节、结果和 deadline 预算扫描；非法或 binary 文件跳过并计数，不中断全局搜索。
    pub fn search(
        &self,
        relative_path: &str,
        query: &str,
    ) -> Result<TextSearchResult, WorkspaceError> {
        if query.is_empty() || query.len() > self.policy.max_query_bytes {
            return Err(WorkspaceError::InvalidRelativePath);
        }
        let root = self.workspace.resolve_guard(relative_path, Some(true))?;
        let mut queue = VecDeque::from([(relative_path.to_owned(), root, 0usize)]);
        let deadline = Instant::now()
            .checked_add(Duration::from_millis(self.policy.max_scan_millis))
            .unwrap_or_else(Instant::now);
        let mut hits = Vec::new();
        let mut scanned_entries = 0usize;
        let mut skipped_files = 0usize;
        let mut total_bytes = 0u64;
        let mut truncated = false;
        while let Some((parent_relative, directory, depth)) = queue.pop_front() {
            if Instant::now() >= deadline {
                self.workspace.verify_resolved(&directory, Some(true))?;
                return Ok(TextSearchResult {
                    hits,
                    truncated: true,
                    scanned_entries,
                    skipped_files,
                });
            }
            if depth > self.policy.max_depth {
                truncated = true;
                continue;
            }
            self.workspace.verify_resolved(&directory, Some(true))?;
            let entries = fs::read_dir(&directory.path)
                .map_err(|error| WorkspaceError::io("read_dir", error))?;
            for entry in entries {
                if Instant::now() >= deadline {
                    self.workspace.verify_resolved(&directory, Some(true))?;
                    return Ok(TextSearchResult {
                        hits,
                        truncated: true,
                        scanned_entries,
                        skipped_files,
                    });
                }
                scanned_entries = scanned_entries.saturating_add(1);
                if scanned_entries > self.policy.max_entries {
                    truncated = true;
                    break;
                }
                let entry = entry.map_err(|error| WorkspaceError::io("read_dir", error))?;
                let name = entry
                    .file_name()
                    .into_string()
                    .map_err(|_| WorkspaceError::InvalidRelativePath)?;
                let path = entry.path();
                let metadata = fs::symlink_metadata(&path)
                    .map_err(|error| WorkspaceError::io("stat", error))?;
                let kind = entry_kind(&metadata);
                let relative = join_relative(&parent_relative, &name);
                if kind == crate::workspace::domain::EntryKind::Directory
                    && !is_reparse_point(&metadata)
                {
                    if is_default_ignored_directory(&name) {
                        skipped_files = skipped_files.saturating_add(1);
                        continue;
                    }
                    if depth < self.policy.max_depth {
                        let child = self.workspace.resolve_guard(&relative, Some(true))?;
                        queue.push_back((relative, child, depth.saturating_add(1)));
                    } else {
                        truncated = true;
                    }
                    continue;
                }
                if kind != crate::workspace::domain::EntryKind::File {
                    continue;
                }
                self.workspace
                    .verify_enumerated_child_file(&directory, &path, &metadata)?;
                if metadata.len() > self.policy.max_file_bytes
                    || total_bytes.saturating_add(metadata.len()) > self.policy.max_total_bytes
                {
                    skipped_files = skipped_files.saturating_add(1);
                    truncated = true;
                    continue;
                }
                let file =
                    fs::File::open(&path).map_err(|error| WorkspaceError::io("open", error))?;
                let Some(bytes) = read_bounded_until(file, self.policy.max_file_bytes, deadline)?
                else {
                    skipped_files = skipped_files.saturating_add(1);
                    self.workspace.verify_resolved(&directory, Some(true))?;
                    return Ok(TextSearchResult {
                        hits,
                        truncated: true,
                        scanned_entries,
                        skipped_files,
                    });
                };
                if bytes.len() as u64 > self.policy.max_file_bytes {
                    skipped_files = skipped_files.saturating_add(1);
                    truncated = true;
                    continue;
                }
                let current = fs::symlink_metadata(&path)
                    .map_err(|error| WorkspaceError::io("recheck", error))?;
                self.workspace
                    .verify_enumerated_child_file(&directory, &path, &current)?;
                total_bytes = total_bytes.saturating_add(bytes.len() as u64);
                let (content_kind, encoding, text) = decode_content(&bytes);
                if content_kind != ContentKind::Text {
                    continue;
                }
                let Some((text, encoding)) = text.zip(encoding) else {
                    continue;
                };
                if let Some(limit_reached) = append_hits(
                    &mut hits,
                    &relative,
                    query,
                    &text,
                    encoding,
                    self.policy.max_results,
                ) {
                    truncated |= limit_reached;
                    if hits.len() >= self.policy.max_results {
                        self.workspace.verify_resolved(&directory, Some(true))?;
                        return Ok(TextSearchResult {
                            hits,
                            truncated: true,
                            scanned_entries,
                            skipped_files,
                        });
                    }
                }
            }
            self.workspace.verify_resolved(&directory, Some(true))?;
            if truncated && scanned_entries >= self.policy.max_entries {
                break;
            }
        }
        Ok(TextSearchResult {
            hits,
            truncated,
            scanned_entries,
            skipped_files,
        })
    }
}

/// 单文件按块读取，使扫描 deadline 同时约束慢文件系统读取，而不只约束文件间遍历。
pub(crate) fn read_bounded_until(
    mut reader: impl Read,
    max_bytes: u64,
    deadline: Instant,
) -> Result<Option<Vec<u8>>, WorkspaceError> {
    let max_bytes = usize::try_from(max_bytes).map_err(|_| WorkspaceError::FileTooLarge)?;
    let mut bytes = Vec::with_capacity(max_bytes.min(64 * 1024));
    let mut buffer = [0_u8; 16 * 1024];
    loop {
        if Instant::now() >= deadline {
            return Ok(None);
        }
        let count = reader
            .read(&mut buffer)
            .map_err(|error| WorkspaceError::io("read", error))?;
        if count == 0 {
            return Ok(Some(bytes));
        }
        let remaining = max_bytes.saturating_add(1).saturating_sub(bytes.len());
        bytes.extend_from_slice(&buffer[..count.min(remaining)]);
        if bytes.len() > max_bytes {
            return Ok(Some(bytes));
        }
    }
}

/// 追加有界行列命中并返回结果上限状态，使调用方能向 UI 诚实说明局部结果。
fn append_hits(
    hits: &mut Vec<SearchHit>,
    relative_path: &str,
    query: &str,
    text: &str,
    encoding: TextEncoding,
    max_results: usize,
) -> Option<bool> {
    let mut found = false;
    for (offset, _) in text.match_indices(query) {
        found = true;
        if hits.len() >= max_results {
            return Some(true);
        }
        let line = text[..offset].bytes().filter(|byte| *byte == b'\n').count() + 1;
        let column = text[..offset]
            .rsplit('\n')
            .next()
            .map(|prefix| prefix.chars().count() + 1)
            .unwrap_or(1);
        hits.push(SearchHit {
            relative_path: relative_path.to_owned(),
            line,
            column,
            snippet: snippet(text, offset, query.len()),
            encoding,
        });
    }
    found.then_some(false)
}

/// 摘要窗口保持足够小，避免单条 timeline/search payload 膨胀。
fn snippet(text: &str, offset: usize, query_len: usize) -> String {
    let start = text[..offset]
        .char_indices()
        .rev()
        .nth(80)
        .map(|(index, _)| index)
        .unwrap_or(0);
    let query_end = offset.saturating_add(query_len).min(text.len());
    let end = text[query_end..]
        .char_indices()
        .nth(80)
        .map(|(index, _)| query_end + index)
        .unwrap_or(text.len());
    text.get(start..end).unwrap_or_default().replace('\n', " ")
}
