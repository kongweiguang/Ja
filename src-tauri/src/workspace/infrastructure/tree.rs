// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::registry::{
    FileIdentity, ResolvedPath, WorkspaceHandle, entry_kind, hex_lower, is_reparse_point,
};
use crate::workspace::WorkspaceError;
use crate::workspace::application::TreePageRequest;
use crate::workspace::domain::{EntryKind, TreeEntry, TreePage};
use sha2::{Digest, Sha256};
use std::cmp::Ordering;
use std::collections::HashMap;
use std::fs::{self, Metadata};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, UNIX_EPOCH};

const TREE_SNAPSHOT_TTL: Duration = Duration::from_secs(120);
const MAX_CACHED_TREE_SNAPSHOTS: usize = 64;
const MAX_CACHED_TREE_ENTRIES: usize = 250_000;

/// 缓存策略签名阻止测试或未来调用方用不同安全预算续读旧 snapshot。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct TreePolicySignature {
    max_depth: usize,
    max_entries_per_page_scan: usize,
    hash_limit_bytes: u64,
    max_total_bytes: u64,
}

/// 快照条目保留首次枚举的 metadata；正文摘要只在该条目真正进入当前 page 时计算。
#[derive(Debug)]
struct CachedTreeEntry {
    name: String,
    path: PathBuf,
    metadata: Metadata,
}

/// 单目录快照保存确定排序与目录 revision，使后续 cursor 不再重复枚举、排序和全量 hash。
#[derive(Debug)]
struct CachedTreeSnapshot {
    created_at: Instant,
    policy: TreePolicySignature,
    entries: Vec<CachedTreeEntry>,
    directory_identity: FileIdentity,
    directory_revision: crate::workspace::FileRevision,
    snapshot_token: String,
    depth: usize,
}

/// 每个 WorkspaceHandle 独占的有界快照表；TTL 与总 entry 上限共同约束内存生命周期。
#[derive(Debug, Default)]
pub(crate) struct TreeSnapshotCache {
    snapshots: Mutex<HashMap<String, Arc<CachedTreeSnapshot>>>,
}

impl TreeSnapshotCache {
    /// 只续读同一路径、同一 token 与同一策略的活跃 snapshot；任何缺失都要求调用方刷新。
    fn get(
        &self,
        relative_path: &str,
        token: &str,
        policy: TreePolicySignature,
    ) -> Result<Arc<CachedTreeSnapshot>, WorkspaceError> {
        let mut snapshots = self.snapshots.lock().map_err(|_| WorkspaceError::Io {
            operation: "tree_cache",
            kind: std::io::ErrorKind::Other.into(),
        })?;
        snapshots.retain(|_, snapshot| snapshot.created_at.elapsed() <= TREE_SNAPSHOT_TTL);
        let snapshot = snapshots
            .get(relative_path)
            .filter(|snapshot| snapshot.snapshot_token == token && snapshot.policy == policy)
            .cloned()
            .ok_or(WorkspaceError::StaleCursor)?;
        Ok(snapshot)
    }

    /// 新的首 page 原子替换同目录旧快照，并按最旧优先淘汰直到满足全局有界预算。
    fn insert(
        &self,
        relative_path: String,
        snapshot: Arc<CachedTreeSnapshot>,
    ) -> Result<(), WorkspaceError> {
        let mut snapshots = self.snapshots.lock().map_err(|_| WorkspaceError::Io {
            operation: "tree_cache",
            kind: std::io::ErrorKind::Other.into(),
        })?;
        snapshots.retain(|_, cached| cached.created_at.elapsed() <= TREE_SNAPSHOT_TTL);
        snapshots.insert(relative_path, snapshot);
        loop {
            let total_entries = snapshots.values().try_fold(0usize, |total, cached| {
                total
                    .checked_add(cached.entries.len())
                    .ok_or(WorkspaceError::EntryBudgetExceeded)
            })?;
            if snapshots.len() <= MAX_CACHED_TREE_SNAPSHOTS
                && total_entries <= MAX_CACHED_TREE_ENTRIES
            {
                break;
            }
            let Some(oldest_path) = snapshots
                .iter()
                .min_by_key(|(_, cached)| cached.created_at)
                .map(|(path, _)| path.clone())
            else {
                break;
            };
            snapshots.remove(&oldest_path);
        }
        Ok(())
    }
}

/// 硬预算防止虚拟化 UI 的一次请求退化为全量 tree 遍历或无界目录分配。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TreePolicy {
    pub max_depth: usize,
    pub max_entries_per_page_scan: usize,
    pub max_page_size: usize,
    pub hash_limit_bytes: u64,
    pub max_total_bytes: u64,
    pub max_scan_millis: u64,
}

impl Default for TreePolicy {
    /// 保守默认值确保单次 UI 请求始终有界，调用方不能借分页扩大扫描深度。
    fn default() -> Self {
        Self {
            max_depth: 64,
            max_entries_per_page_scan: 100_000,
            max_page_size: 500,
            hash_limit_bytes: 4 * 1024 * 1024,
            max_total_bytes: 256 * 1024 * 1024,
            max_scan_millis: 2_000,
        }
    }
}

/// TreeReader 每次只读取一级目录；只有 UI 显式展开后才检查子节点，以维持大型仓库可用性。
#[derive(Debug, Clone)]
pub struct TreeReader {
    workspace: WorkspaceHandle,
    policy: TreePolicy,
}

impl TreeReader {
    /// 把 reader 绑定到不可变 canonical root 与显式预算，分页期间不能漂移 Workspace。
    pub fn new(workspace: WorkspaceHandle, policy: TreePolicy) -> Self {
        let policy = TreePolicy {
            max_depth: policy.max_depth.min(256),
            max_entries_per_page_scan: policy.max_entries_per_page_scan.clamp(1, 1_000_000),
            max_page_size: policy.max_page_size.clamp(1, 10_000),
            hash_limit_bytes: policy.hash_limit_bytes.min(64 * 1024 * 1024),
            max_total_bytes: policy.max_total_bytes.min(2 * 1024 * 1024 * 1024),
            max_scan_millis: policy.max_scan_millis.clamp(1, 60_000),
        };
        Self { workspace, policy }
    }

    /// 构造只影响快照兼容性的策略签名；page size 不改变排序或节点 revision，因此不参与。
    fn policy_signature(&self) -> TreePolicySignature {
        TreePolicySignature {
            max_depth: self.policy.max_depth,
            max_entries_per_page_scan: self.policy.max_entries_per_page_scan,
            hash_limit_bytes: self.policy.hash_limit_bytes,
            max_total_bytes: self.policy.max_total_bytes,
        }
    }

    /// 首 page 只枚举、stat 和排序一次；文件内容摘要延迟到对应 page，降低大型目录首屏延迟。
    fn build_snapshot(
        &self,
        relative_path: &str,
        resolved: &ResolvedPath,
        depth: usize,
        deadline: Instant,
    ) -> Result<Arc<CachedTreeSnapshot>, WorkspaceError> {
        let mut entries = Vec::new();
        let mut allocated_bytes = 0_u64;
        for entry in
            fs::read_dir(&resolved.path).map_err(|error| WorkspaceError::io("read_dir", error))?
        {
            if Instant::now() >= deadline {
                return Err(WorkspaceError::ScanDeadlineExceeded);
            }
            let entry = entry.map_err(|error| WorkspaceError::io("read_dir", error))?;
            let name = entry
                .file_name()
                .into_string()
                .map_err(|_| WorkspaceError::InvalidRelativePath)?;
            let path = entry.path();
            let metadata =
                fs::symlink_metadata(&path).map_err(|error| WorkspaceError::io("stat", error))?;
            if entries.len() >= self.policy.max_entries_per_page_scan {
                return Err(WorkspaceError::EntryBudgetExceeded);
            }
            let entry_bytes = name
                .len()
                .saturating_add(relative_path.len())
                .saturating_add(std::mem::size_of::<CachedTreeEntry>());
            allocated_bytes = allocated_bytes
                .checked_add(
                    u64::try_from(entry_bytes).map_err(|_| WorkspaceError::EntryBudgetExceeded)?,
                )
                .ok_or(WorkspaceError::EntryBudgetExceeded)?;
            if allocated_bytes > self.policy.max_total_bytes {
                return Err(WorkspaceError::EntryBudgetExceeded);
            }
            entries.push(CachedTreeEntry {
                name,
                path,
                metadata,
            });
        }
        entries.sort_by(|left, right| {
            match (entry_kind(&left.metadata), entry_kind(&right.metadata)) {
                (EntryKind::Directory, EntryKind::Directory) => left.name.cmp(&right.name),
                (EntryKind::Directory, _) => Ordering::Less,
                (_, EntryKind::Directory) => Ordering::Greater,
                _ => left.name.cmp(&right.name),
            }
        });
        let mut token_hasher = Sha256::new();
        for entry in &entries {
            update_snapshot_token(&mut token_hasher, entry);
        }
        self.workspace.verify_resolved(resolved, Some(true))?;
        let snapshot_token = hex_lower(&token_hasher.finalize());
        let directory_metadata = fs::symlink_metadata(&resolved.path)
            .map_err(|error| WorkspaceError::io("stat", error))?;
        let directory_revision = super::registry::metadata_for_path_with_deadline(
            &resolved.path,
            &directory_metadata,
            self.policy.hash_limit_bytes,
            Some(deadline),
        )?
        .revision;
        self.workspace.verify_resolved(resolved, Some(true))?;
        let snapshot = Arc::new(CachedTreeSnapshot {
            created_at: Instant::now(),
            policy: self.policy_signature(),
            entries,
            directory_identity: resolved.identity(),
            directory_revision,
            snapshot_token,
            depth,
        });
        self.workspace
            .tree_snapshots()
            .insert(relative_path.to_owned(), snapshot.clone())?;
        Ok(snapshot)
    }

    /// 返回目录优先、同类名称排序的不递归 page；cursor 续读共享同一有界内存快照，
    /// 因而总成本为一次 O(N) 枚举/排序加各 page 自身的 O(page) revision 计算。
    pub fn read_page(&self, request: &TreePageRequest) -> Result<TreePage, WorkspaceError> {
        let relative_path = request.relative_path.as_str();
        let resolved = self.workspace.resolve_guard(relative_path, Some(true))?;
        self.workspace.verify_resolved(&resolved, Some(true))?;
        let depth = relative_depth(relative_path);
        if depth > self.policy.max_depth {
            return Err(WorkspaceError::DepthLimitExceeded);
        }
        let start = request
            .cursor
            .as_deref()
            .unwrap_or("0")
            .parse::<usize>()
            .map_err(|_| WorkspaceError::InvalidRelativePath)?;
        let page_size = request
            .page_size
            .unwrap_or(self.policy.max_page_size)
            .clamp(1, self.policy.max_page_size);
        let deadline = Instant::now()
            .checked_add(Duration::from_millis(self.policy.max_scan_millis))
            .unwrap_or_else(Instant::now);
        let cached_page = request.cursor.is_some();
        let snapshot = match (&request.cursor, &request.snapshot_token) {
            (None, None) => self.build_snapshot(relative_path, &resolved, depth, deadline)?,
            (Some(_), Some(token)) => self.workspace.tree_snapshots().get(
                relative_path,
                token,
                self.policy_signature(),
            )?,
            _ => return Err(WorkspaceError::StaleCursor),
        };
        if cached_page {
            self.verify_snapshot_directory(&snapshot, &resolved, deadline)?;
        }
        let total_entries = snapshot.entries.len();
        if start > total_entries || (cached_page && start == total_entries) {
            return Err(WorkspaceError::StaleCursor);
        }
        let end = start.saturating_add(page_size).min(total_entries);
        let mut page_entries = Vec::with_capacity(end.saturating_sub(start));
        for entry in snapshot.entries.iter().skip(start).take(page_size) {
            let metadata = super::registry::metadata_for_path_with_deadline(
                &entry.path,
                &entry.metadata,
                self.policy.hash_limit_bytes,
                Some(deadline),
            )
            .map_err(|error| map_snapshot_materialization_error(error, cached_page))?;
            let kind = metadata.kind;
            page_entries.push(TreeEntry {
                name: entry.name.clone(),
                relative_path: join_relative(relative_path, &entry.name),
                metadata,
                can_expand: kind == EntryKind::Directory
                    && !is_reparse_point(&entry.metadata)
                    && depth.saturating_add(1) < self.policy.max_depth,
            });
        }
        self.workspace.verify_resolved(&resolved, Some(true))?;
        Ok(TreePage {
            entries: page_entries,
            directory_revision: snapshot.directory_revision.clone(),
            next_cursor: (end < total_entries).then(|| end.to_string()),
            snapshot_token: snapshot.snapshot_token.clone(),
            total_entries,
            depth: snapshot.depth,
        })
    }

    /// 续页只做 O(1) 目录身份与 metadata 对账；目录增删/替换立即使旧 cursor 失效，
    /// 同时避免为了校验每一页再次枚举、排序或 hash 整个目录。
    fn verify_snapshot_directory(
        &self,
        snapshot: &CachedTreeSnapshot,
        resolved: &ResolvedPath,
        deadline: Instant,
    ) -> Result<(), WorkspaceError> {
        if resolved.identity() != snapshot.directory_identity {
            return Err(WorkspaceError::StaleCursor);
        }
        let metadata = fs::symlink_metadata(&resolved.path).map_err(|error| {
            map_snapshot_materialization_error(WorkspaceError::io("stat", error), true)
        })?;
        let current = super::registry::metadata_for_path_with_deadline(
            &resolved.path,
            &metadata,
            self.policy.hash_limit_bytes,
            Some(deadline),
        )
        .map_err(|error| map_snapshot_materialization_error(error, true))?;
        if current.revision != snapshot.directory_revision {
            return Err(WorkspaceError::StaleCursor);
        }
        Ok(())
    }
}

/// 快照 token 只依赖排序、类型与 cheap metadata；内容 hash 在页面可见时按原预算计算。
fn update_snapshot_token(hasher: &mut Sha256, entry: &CachedTreeEntry) {
    let modified_unix_millis = entry
        .metadata
        .modified()
        .ok()
        .and_then(|modified| modified.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis());
    hasher.update(entry.name.as_bytes());
    hasher.update([0]);
    hasher.update(
        format!(
            "{:?}:{}:{modified_unix_millis:?}\n",
            entry_kind(&entry.metadata),
            entry.metadata.len()
        )
        .as_bytes(),
    );
}

/// 续页材料化发现节点被替换、删除或变成 link 时统一要求刷新；其它预算/权限错误保持原语义。
fn map_snapshot_materialization_error(error: WorkspaceError, cached_page: bool) -> WorkspaceError {
    if !cached_page {
        return error;
    }
    match error {
        WorkspaceError::PathChanged
        | WorkspaceError::PathNotFound
        | WorkspaceError::LinkNotAllowed
        | WorkspaceError::Io {
            kind: crate::workspace::IoFailureKind::NotFound,
            ..
        } => WorkspaceError::StaleCursor,
        other => other,
    }
}

/// 深度只计算普通路径 component，重复 separator 不能绕过层级预算。
fn relative_depth(relative_path: &str) -> usize {
    Path::new(relative_path)
        .components()
        .filter(|component| matches!(component, std::path::Component::Normal(_)))
        .count()
}

/// 将 UI 相对路径统一为 slash separator，避免 host OS spelling 渗入协议。
pub(crate) fn join_relative(parent: &str, child: &str) -> String {
    if parent.is_empty() {
        child.to_owned()
    } else {
        format!("{parent}/{child}")
    }
}
