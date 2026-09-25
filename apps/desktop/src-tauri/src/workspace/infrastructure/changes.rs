// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::registry::{WorkspaceHandle, entry_kind, is_reparse_point};
use super::search::is_default_ignored_directory;
use super::tree::join_relative;
use crate::workspace::WorkspaceError;
use crate::workspace::domain::{EntryKind, FileRevision};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::fs;
use std::time::{Duration, Instant};

/// Polling 是明确的降级端口，使原生 Watcher 可替换扫描实现而无需改变前端事件合同。
pub trait ChangeDetector: Send {
    /// 返回有界变化批次或未到期标记，调用方不得把空批次解释为权威全量快照。
    fn poll(&mut self) -> Result<ChangeBatch, WorkspaceError>;

    /// 在溢出或 rescan 提示后强制重建权威基线，失败时保留旧完整基线用于恢复。
    fn rescan(&mut self) -> Result<ChangeBatch, WorkspaceError>;
}

/// 轮询策略同时限制频率和扫描规模，繁忙 Workspace 不能饿死 Agent 或用重复事件淹没 WebView。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PollingPolicy {
    pub min_interval_millis: u64,
    pub max_depth: usize,
    pub max_entries: usize,
    pub max_changes: usize,
    pub hash_limit_bytes: u64,
    pub max_total_bytes: u64,
    pub max_scan_millis: u64,
}

impl Default for PollingPolicy {
    /// 默认值让降级轮询保持稀疏，并对每次扫描设置显式预算。
    fn default() -> Self {
        Self {
            min_interval_millis: 250,
            max_depth: 64,
            max_entries: 100_000,
            max_changes: 2_000,
            hash_limit_bytes: 1024 * 1024,
            max_total_bytes: 256 * 1024 * 1024,
            max_scan_millis: 2_000,
        }
    }
}

/// 轮询状态说明结果为何不能被当作完整快照，调用方必须据此触发权威刷新。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PollState {
    NotDue,
    Updated,
    Overflow,
}

/// 变化种类刻意保持最小闭集；需要细节时调用方应读取完整 tree page，而不是信任单个事件。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChangeKind {
    Created,
    Modified,
    Deleted,
    Replaced,
}

/// 变化记录只携带相对路径与前后 revision，绝不包含操作系统绝对路径。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChangeRecord {
    pub relative_path: String,
    pub kind: ChangeKind,
    pub previous: Option<FileRevision>,
    pub current: Option<FileRevision>,
}

/// 轮询结果显式携带溢出与 rescan 状态，避免局部增量冒充完整事实。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChangeBatch {
    pub state: PollState,
    pub generation: u64,
    pub changes: Vec<ChangeRecord>,
    pub requires_rescan: bool,
}

/// 确定且有界的检测器保持 Workspace 身份与溢出语义稳定，后续可替换为原生 Watcher。
#[derive(Debug)]
pub struct PollingChangeDetector {
    workspace: WorkspaceHandle,
    policy: PollingPolicy,
    snapshot: Option<BTreeMap<String, FileRevision>>,
    last_poll: Option<Instant>,
    generation: u64,
}

impl PollingChangeDetector {
    /// 统一收窄扫描预算；即时启动与显式基线构建必须使用同一策略，避免两条路径
    /// 对大型 Workspace 给出不同的上限和恢复语义。
    fn normalized_policy(policy: PollingPolicy) -> PollingPolicy {
        PollingPolicy {
            min_interval_millis: policy.min_interval_millis.clamp(1, 60_000),
            max_depth: policy.max_depth.min(256),
            max_entries: policy.max_entries.clamp(1, 1_000_000),
            max_changes: policy.max_changes.clamp(1, 100_000),
            hash_limit_bytes: policy.hash_limit_bytes.min(16 * 1024 * 1024),
            max_total_bytes: policy.max_total_bytes.min(2 * 1024 * 1024 * 1024),
            max_scan_millis: policy.max_scan_millis.clamp(1, 60_000),
        }
    }

    /// 原生 notify 启动路径只登记一个尚无基线的 detector，使 Workspace 切换不等待
    /// 全量 metadata/hash。首次 focus 或 overflow 对账再在 blocking worker 中建立基线。
    pub(crate) fn new_uninitialized(workspace: WorkspaceHandle, policy: PollingPolicy) -> Self {
        Self {
            workspace,
            policy: Self::normalized_policy(policy),
            snapshot: None,
            last_poll: None,
            generation: 0,
        }
    }

    /// 尝试建立有界初始基线；超出条目、字节或时间预算时保留一个可恢复的
    /// 降级 detector，而不是让 native notify 因大型工作区而无法启动。
    pub fn new(workspace: WorkspaceHandle, policy: PollingPolicy) -> Result<Self, WorkspaceError> {
        let mut detector = Self::new_uninitialized(workspace, policy);
        let deadline = Instant::now()
            .checked_add(Duration::from_millis(detector.policy.max_scan_millis))
            .unwrap_or_else(Instant::now);
        detector.snapshot = match scan_workspace(&detector.workspace, &detector.policy, deadline) {
            Ok(scan) if !scan.overflow => Some(scan.snapshot),
            Ok(_) | Err(WorkspaceError::ScanDeadlineExceeded) => None,
            Err(error) => return Err(error),
        };
        Ok(detector)
    }

    /// 告知 watcher 初始基线是否不完整；调用方据此立即发出根级 rescan
    /// marker，tree/read 仍是大型工作区中的权威事实。
    pub fn requires_initial_rescan(&self) -> bool {
        self.snapshot.is_none()
    }

    /// 应用一次完整扫描：缺失旧基线时只建立新基线并要求调用方做一次权威
    /// 刷新，已有基线时才生成有界的逐路径差异。
    fn apply_complete_scan(&mut self, snapshot: BTreeMap<String, FileRevision>) -> ChangeBatch {
        self.generation = self.generation.saturating_add(1);
        let Some(previous) = self.snapshot.as_ref() else {
            self.snapshot = Some(snapshot);
            return ChangeBatch {
                state: PollState::Updated,
                generation: self.generation,
                changes: Vec::new(),
                requires_rescan: true,
            };
        };
        let changes = diff_snapshots(previous, &snapshot, self.policy.max_changes);
        let overflow = changes.len() >= self.policy.max_changes;
        self.snapshot = Some(snapshot);
        ChangeBatch {
            state: if overflow {
                PollState::Overflow
            } else {
                PollState::Updated
            },
            generation: self.generation,
            changes,
            requires_rescan: overflow,
        }
    }

    /// 在扫描预算不足时保留最后一个完整基线；没有完整基线的 detector
    /// 继续显式要求 rescan，绝不把部分快照误认为权威状态。
    fn overflow_batch(&mut self) -> ChangeBatch {
        self.generation = self.generation.saturating_add(1);
        ChangeBatch {
            state: PollState::Overflow,
            generation: self.generation,
            changes: Vec::new(),
            requires_rescan: true,
        }
    }
}

impl ChangeDetector for PollingChangeDetector {
    /// 保持最小轮询间隔；降级状态即使尚未到期也继续携带 rescan 提示，避免
    /// 调用方把“没有完整基线”误判成“工作区没有变化”。
    fn poll(&mut self) -> Result<ChangeBatch, WorkspaceError> {
        let now = Instant::now();
        if self.last_poll.is_some_and(|last| {
            now.duration_since(last) < Duration::from_millis(self.policy.min_interval_millis)
        }) {
            return Ok(ChangeBatch {
                state: PollState::NotDue,
                generation: self.generation,
                changes: Vec::new(),
                requires_rescan: self.snapshot.is_none(),
            });
        }
        self.last_poll = Some(now);
        let deadline = Instant::now()
            .checked_add(Duration::from_millis(self.policy.max_scan_millis))
            .unwrap_or_else(Instant::now);
        let scan = match scan_workspace(&self.workspace, &self.policy, deadline) {
            Ok(scan) => scan,
            Err(WorkspaceError::ScanDeadlineExceeded) => {
                return Ok(self.overflow_batch());
            }
            Err(error) => return Err(error),
        };
        if scan.overflow {
            return Ok(self.overflow_batch());
        }
        Ok(self.apply_complete_scan(scan.snapshot))
    }

    /// 强制扫描在完整时更新基线，在超限时保留旧基线；缺失旧基线时返回根级
    /// rescan 语义，让 UI 在基线恢复的同一轮仍执行 tree/read 权威刷新。
    fn rescan(&mut self) -> Result<ChangeBatch, WorkspaceError> {
        let deadline = Instant::now()
            .checked_add(Duration::from_millis(self.policy.max_scan_millis))
            .unwrap_or_else(Instant::now);
        let scan = match scan_workspace(&self.workspace, &self.policy, deadline) {
            Ok(scan) => scan,
            Err(WorkspaceError::ScanDeadlineExceeded) => {
                return Ok(self.overflow_batch());
            }
            Err(error) => return Err(error),
        };
        if scan.overflow {
            return Ok(self.overflow_batch());
        }
        self.last_poll = Some(Instant::now());
        Ok(self.apply_complete_scan(scan.snapshot))
    }
}

struct ScanResult {
    snapshot: BTreeMap<String, FileRevision>,
    overflow: bool,
}

/// 扫描只读取 metadata，不跟随 link 或 reparse point；首次超预算立即停止，
/// 让调用方明确请求 rescan，而不是返回伪完整结果。
fn scan_workspace(
    workspace: &WorkspaceHandle,
    policy: &PollingPolicy,
    deadline: Instant,
) -> Result<ScanResult, WorkspaceError> {
    let root = workspace.resolve_guard("", Some(true))?;
    let mut queue = VecDeque::from([(String::new(), root, 0usize)]);
    let mut snapshot = BTreeMap::new();
    let mut visited = 0usize;
    let mut total_bytes = 0_u64;
    while let Some((parent, directory, depth)) = queue.pop_front() {
        if Instant::now() >= deadline {
            return Ok(ScanResult {
                snapshot,
                overflow: true,
            });
        }
        if depth > policy.max_depth {
            return Ok(ScanResult {
                snapshot,
                overflow: true,
            });
        }
        workspace.verify_resolved(&directory, Some(true))?;
        for entry in
            fs::read_dir(&directory.path).map_err(|error| WorkspaceError::io("read_dir", error))?
        {
            if Instant::now() >= deadline {
                return Ok(ScanResult {
                    snapshot,
                    overflow: true,
                });
            }
            visited = visited.saturating_add(1);
            if visited > policy.max_entries {
                return Ok(ScanResult {
                    snapshot,
                    overflow: true,
                });
            }
            let entry = entry.map_err(|error| WorkspaceError::io("read_dir", error))?;
            let name = entry
                .file_name()
                .into_string()
                .map_err(|_| WorkspaceError::InvalidRelativePath)?;
            let path = entry.path();
            let metadata =
                fs::symlink_metadata(&path).map_err(|error| WorkspaceError::io("stat", error))?;
            total_bytes = total_bytes
                .saturating_add(metadata.len())
                .saturating_add(name.len() as u64);
            if total_bytes > policy.max_total_bytes {
                return Ok(ScanResult {
                    snapshot,
                    overflow: true,
                });
            }
            let kind = entry_kind(&metadata);
            if kind == EntryKind::Directory && is_default_ignored_directory(&name) {
                continue;
            }
            let relative = join_relative(&parent, &name);
            let revision = super::registry::metadata_for_path_with_deadline(
                &path,
                &metadata,
                policy.hash_limit_bytes,
                Some(deadline),
            )?
            .revision;
            snapshot.insert(relative, revision);
            if kind == EntryKind::Directory && !is_reparse_point(&metadata) {
                if depth >= policy.max_depth {
                    return Ok(ScanResult {
                        snapshot,
                        overflow: true,
                    });
                }
                let child_relative = join_relative(&parent, &name);
                let child = workspace.resolve_guard(&child_relative, Some(true))?;
                queue.push_back((child_relative, child, depth.saturating_add(1)));
            }
        }
        workspace.verify_resolved(&directory, Some(true))?;
    }
    Ok(ScanResult {
        snapshot,
        overflow: false,
    })
}

/// 生成确定顺序的 create/modify/delete 记录并限制事件量，溢出时要求权威 rescan。
fn diff_snapshots(
    previous: &BTreeMap<String, FileRevision>,
    current: &BTreeMap<String, FileRevision>,
    max_changes: usize,
) -> Vec<ChangeRecord> {
    let mut changes = Vec::new();
    let paths = previous
        .keys()
        .chain(current.keys())
        .cloned()
        .collect::<BTreeSet<_>>();
    for path in paths {
        if changes.len() >= max_changes {
            break;
        }
        let old = previous.get(&path);
        let new = current.get(&path);
        let kind = match (old, new) {
            (None, Some(_)) => Some(ChangeKind::Created),
            (Some(_), None) => Some(ChangeKind::Deleted),
            (Some(old), Some(new)) if old != new => {
                if old.size() == new.size()
                    && old.modified_unix_millis() == new.modified_unix_millis()
                {
                    Some(ChangeKind::Replaced)
                } else {
                    Some(ChangeKind::Modified)
                }
            }
            _ => None,
        };
        if let Some(kind) = kind {
            changes.push(ChangeRecord {
                relative_path: path.clone(),
                kind,
                previous: old.cloned(),
                current: new.cloned(),
            });
        }
    }
    changes
}
