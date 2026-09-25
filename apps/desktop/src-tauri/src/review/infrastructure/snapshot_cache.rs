// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review metadata snapshot 的进程内有界缓存。

use crate::review::domain::{
    ReviewFile, ReviewFileId, ReviewRevision, ReviewSnapshot, ReviewSource,
};
use crate::workspace::WorkspaceId;
use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

const CACHE_TTL: Duration = Duration::from_secs(120);
const MAX_CACHE_ENTRIES: usize = 4;
const MAX_CACHE_FILES: usize = 8_000;
const MAX_CACHE_BYTES: usize = 8 * 1024 * 1024;

/// 单条缓存同时绑定随机 Workspace identity 与 source，删除后重加同一路径不会复用旧能力。
struct CacheEntry {
    workspace_id: WorkspaceId,
    source: ReviewSource,
    snapshot: ReviewSnapshot,
    inserted_at: Instant,
    bytes: usize,
}

#[derive(Default)]
struct SnapshotCache {
    entries: VecDeque<CacheEntry>,
    files: usize,
    bytes: usize,
}

/// 获取唯一进程缓存；Mutex 只保护 bounded clone/eviction，任何 Git 或文件 IO 都在锁外。
fn cache() -> &'static Mutex<SnapshotCache> {
    static CACHE: OnceLock<Mutex<SnapshotCache>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(SnapshotCache::default()))
}

/// 估算 snapshot 所有 owned buffer，宁可高估也不能让 patch 或长路径绕过 byte cap。
fn snapshot_bytes(snapshot: &ReviewSnapshot) -> usize {
    std::mem::size_of::<ReviewSnapshot>().saturating_add(snapshot.files.iter().fold(
        0_usize,
        |total, file| {
            let file_bytes = std::mem::size_of::<ReviewFile>()
                .saturating_add(file.path.capacity())
                .saturating_add(file.old_path.as_ref().map_or(0, String::capacity))
                .saturating_add(file.file_id.as_str().len())
                .saturating_add(file.patch.capacity())
                .saturating_add(file.revision_evidence.capacity())
                .saturating_add(file.state_evidence.capacity())
                .saturating_add(
                    file.hunks
                        .iter()
                        .map(|hunk| {
                            std::mem::size_of_val(hunk)
                                .saturating_add(hunk.header.capacity())
                                .saturating_add(hunk.raw_patch.capacity())
                                .saturating_add(
                                    hunk.lines
                                        .iter()
                                        .map(|line| {
                                            std::mem::size_of_val(line)
                                                .saturating_add(line.text.capacity())
                                        })
                                        .sum::<usize>(),
                                )
                        })
                        .sum::<usize>(),
                );
            total.saturating_add(file_bytes)
        },
    ))
}

/// 移除过期或超额的最旧项，所有计数与队列变更保持在一个临界区内。
fn enforce_limits(cache: &mut SnapshotCache, now: Instant) {
    cache
        .entries
        .retain(|entry| now.duration_since(entry.inserted_at) < CACHE_TTL);
    cache.files = cache
        .entries
        .iter()
        .map(|entry| entry.snapshot.files.len())
        .sum();
    cache.bytes = cache.entries.iter().map(|entry| entry.bytes).sum();
    while cache.entries.len() > MAX_CACHE_ENTRIES
        || cache.files > MAX_CACHE_FILES
        || cache.bytes > MAX_CACHE_BYTES
    {
        remove_front(cache);
    }
}

/// 返回 exact cache snapshot 供 light manifest probe 复用 strong digest，不暴露到 application。
pub(super) fn snapshot(
    workspace_id: WorkspaceId,
    source: &ReviewSource,
    revision: &ReviewRevision,
) -> Option<ReviewSnapshot> {
    let mut cache = cache().lock().ok()?;
    let now = Instant::now();
    enforce_limits(&mut cache, now);
    let index = cache.entries.iter().position(|entry| {
        entry.workspace_id == workspace_id
            && &entry.source == source
            && &entry.snapshot.revision == revision
    })?;
    let entry = cache.entries.remove(index)?;
    let snapshot = entry.snapshot.clone();
    cache.entries.push_back(entry);
    Some(snapshot)
}

/// 统一维护 aggregate counters，避免 eviction 后的 byte/file 预算漂移。
fn remove_front(cache: &mut SnapshotCache) {
    if let Some(entry) = cache.entries.pop_front() {
        cache.files = cache.files.saturating_sub(entry.snapshot.files.len());
        cache.bytes = cache.bytes.saturating_sub(entry.bytes);
    }
}

/// 保存 mutable Git source 的最新 metadata snapshot；comparison source 不进入跨命令缓存。
pub(super) fn remember(workspace_id: WorkspaceId, snapshot: &ReviewSnapshot) {
    if snapshot.source.is_read_only() {
        return;
    }
    let bytes = snapshot_bytes(snapshot);
    if snapshot.files.len() > MAX_CACHE_FILES || bytes > MAX_CACHE_BYTES {
        return;
    }
    let Ok(mut cache) = cache().lock() else {
        return;
    };
    let now = Instant::now();
    enforce_limits(&mut cache, now);
    if let Some(index) = cache
        .entries
        .iter()
        .position(|entry| entry.workspace_id == workspace_id && entry.source == snapshot.source)
        && let Some(entry) = cache.entries.remove(index)
    {
        cache.files = cache.files.saturating_sub(entry.snapshot.files.len());
        cache.bytes = cache.bytes.saturating_sub(entry.bytes);
    }
    cache.files = cache.files.saturating_add(snapshot.files.len());
    cache.bytes = cache.bytes.saturating_add(bytes);
    cache.entries.push_back(CacheEntry {
        workspace_id,
        source: snapshot.source.clone(),
        snapshot: snapshot.clone(),
        inserted_at: now,
        bytes,
    });
    enforce_limits(&mut cache, now);
}

/// 仅以 exact workspace/source/revision/file identity 解析缓存，过期与 poison 都按 miss 处理。
pub(super) fn file(
    workspace_id: WorkspaceId,
    source: &ReviewSource,
    revision: &ReviewRevision,
    file_id: &ReviewFileId,
) -> Option<ReviewFile> {
    let mut cache = cache().lock().ok()?;
    let now = Instant::now();
    enforce_limits(&mut cache, now);
    let index = cache.entries.iter().position(|entry| {
        entry.workspace_id == workspace_id
            && &entry.source == source
            && &entry.snapshot.revision == revision
    })?;
    let entry = cache.entries.remove(index)?;
    let selected = entry
        .snapshot
        .files
        .iter()
        .find(|file| &file.file_id == file_id)
        .cloned();
    cache.entries.push_back(entry);
    selected
}

/// mutation 或 freshness 失败后移除同 Workspace/source，避免过期 selector 继续命中。
pub(super) fn invalidate(workspace_id: WorkspaceId, source: &ReviewSource) {
    let Ok(mut cache) = cache().lock() else {
        return;
    };
    if let Some(index) = cache
        .entries
        .iter()
        .position(|entry| entry.workspace_id == workspace_id && &entry.source == source)
        && let Some(entry) = cache.entries.remove(index)
    {
        cache.files = cache.files.saturating_sub(entry.snapshot.files.len());
        cache.bytes = cache.bytes.saturating_sub(entry.bytes);
    }
}
