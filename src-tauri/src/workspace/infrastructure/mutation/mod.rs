// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang


// 有界 Workspace mutation 与 Native Drop 准入。
//
// renderer 只提供不透明 Workspace id、slash-separated 相对路径、expected revision
// 与 idempotency key；canonical 根和 Native Drop 路径只保存在本模块 Rust 状态中。

use super::registry::{
    WorkspaceHandle, WorkspaceId, hex_lower, is_reparse_point, metadata_for_path_with_deadline,
    reject_link_components, validate_relative_path,
};
use crate::workspace::WorkspaceError;
use crate::workspace::domain::{
    CreateEntryCommand, CreateEntryKind, CreateEntryResult, DropImportCommand, DropImportResult,
    EntryKind, FileMetadata, FileRevision, FileSaveResult, LineEnding, MoveEntryCommand,
    MoveEntryResult, SaveFileCommand, TextContent, TextEncoding, TrashCommitCommand,
    TrashCommitResult, TrashPrepareCommand, TrashPrepareResult,
};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use uuid::Uuid;

mod foundation;
pub(crate) use foundation::{
    MutationInfrastructureError, PathMutationQueue, replace_atomically, resolve_relative,
};

pub(crate) const MAX_EDIT_BYTES: u64 = 4 * 1024 * 1024;
const MAX_DROP_ITEMS: usize = 32;
const MAX_DROP_TOKEN_BYTES: usize = 128;
const MAX_DROP_ENTRIES: usize = 100_000;
const MAX_DROP_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const MAX_DROP_PATH_BYTES: usize = 16 * 1024 * 1024;
const MAX_DROP_DEPTH: usize = 128;
const DROP_SCAN_DEADLINE: Duration = Duration::from_secs(30);
const MAX_RETAINED_DROP_PLANS: usize = 64;
const MAX_RETAINED_DROP_ENTRIES: usize = MAX_DROP_ENTRIES;
const MAX_RETAINED_DROP_SOURCE_PATH_BYTES: usize = MAX_DROP_PATH_BYTES;
const MAX_RETAINED_DROP_TREE_PATH_BYTES: usize = MAX_DROP_PATH_BYTES;
const MAX_RETAINED_MUTATIONS: usize = 65_536;
const MUTATION_RETENTION: Duration = Duration::from_secs(60 * 60);
const DROP_TTL: Duration = Duration::from_secs(30);
const TRASH_TTL: Duration = Duration::from_secs(30);
const MAX_TRASH_ENTRIES: usize = 100_000;
const MAX_TRASH_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const MAX_TRASH_PATH_BYTES: usize = 16 * 1024 * 1024;
const MAX_TRASH_DEPTH: usize = 128;
const TRASH_SCAN_DEADLINE: Duration = Duration::from_secs(30);
const MAX_RETAINED_TRASH_PLANS: usize = 64;
const MAX_RETAINED_TRASH_SNAPSHOT_ENTRIES: usize = MAX_TRASH_ENTRIES;
const MAX_RETAINED_TRASH_SNAPSHOT_PATH_BYTES: usize = MAX_TRASH_PATH_BYTES;

/// 将共享写入基础设施错误投影为现有 workspace 语义，不把平台或路径细节扩散到 IPC。
impl From<MutationInfrastructureError> for WorkspaceError {
    fn from(error: MutationInfrastructureError) -> Self {
        match error {
            MutationInfrastructureError::OutsideWorkspace => WorkspaceError::OutsideWorkspace,
            MutationInfrastructureError::AtomicUnsupported => WorkspaceError::Io {
                operation: "mutation_atomic",
                kind: std::io::ErrorKind::Unsupported.into(),
            },
            MutationInfrastructureError::Busy => WorkspaceError::Io {
                operation: "mutation_queue",
                kind: std::io::ErrorKind::WouldBlock.into(),
            },
            MutationInfrastructureError::Io => WorkspaceError::Io {
                operation: "mutation_queue",
                kind: std::io::ErrorKind::Other.into(),
            },
        }
    }
}

/// 在所有桌面写入口复用 Workspace/Review 的进程级 canonical-path 队列；
/// 队列错误经过本模块映射，调用方仍能使用原有 path-redacted workspace 错误。
fn with_workspace_mutation_paths<T>(
    paths: &[PathBuf],
    operation: impl FnOnce() -> Result<T, WorkspaceError>,
) -> Result<T, WorkspaceError> {
    PathMutationQueue::global().with_paths(paths, operation)
}

/// prepare 与 commit 之间保留确定的子树 entry；目录 mtime 无法识别等长或保持时间戳的编辑，
/// 因此必须同时保存物理身份与完整有界 revision。
#[derive(Debug, Clone, PartialEq, Eq)]
struct TrashTreeEntry {
    relative_path: String,
    kind: EntryKind,
    identity: MoveIdentity,
    revision: FileRevision,
}

/// 完整且有界的预览证据只允许 `file_count` 与 `total_bytes` 跨 IPC，其余计数仅约束原生资源。
#[derive(Debug, Clone, PartialEq, Eq)]
struct TrashSnapshot {
    entries: Vec<TrashTreeEntry>,
    file_count: usize,
    directory_count: usize,
    total_bytes: u64,
    path_bytes: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct TrashPlan {
    workspace_id: WorkspaceId,
    relative_path: String,
    expected_revision: FileRevision,
    snapshot: TrashSnapshot,
    expires: Instant,
}

#[derive(Debug, Clone, Copy)]
struct TrashPlanBudget {
    max_plans: usize,
    max_snapshot_entries: usize,
    max_snapshot_path_bytes: usize,
}

const RETAINED_TRASH_PLAN_BUDGET: TrashPlanBudget = TrashPlanBudget {
    max_plans: MAX_RETAINED_TRASH_PLANS,
    max_snapshot_entries: MAX_RETAINED_TRASH_SNAPSHOT_ENTRIES,
    max_snapshot_path_bytes: MAX_RETAINED_TRASH_SNAPSHOT_PATH_BYTES,
};

#[derive(Debug, Clone)]
pub(crate) struct NativeDropPlan {
    sources: Vec<NativeDropSource>,
    entry_count: usize,
    source_path_bytes: usize,
    tree_path_bytes: usize,
    expires: Instant,
}

/// 原生事件签发时只保留路径与物理身份；导入时必须重新匹配，避免 30 秒 capability
/// 被同名替换后读取未经用户拖入的对象。
#[derive(Debug, Clone)]
struct NativeDropSource {
    path: PathBuf,
    identity: MoveIdentity,
}

/// 完整预检后的单个源节点；hash 在 staging 流式复制时再次核对，确保可见目标
/// 发布前拿到的是同一份有界快照。
#[derive(Debug, Clone)]
struct DropManifestEntry {
    source: PathBuf,
    relative_path: String,
    kind: EntryKind,
    identity: MoveIdentity,
    size: u64,
    modified_unix_millis: Option<u128>,
    sha256: Option<String>,
    children: Option<Vec<std::ffi::OsString>>,
}

/// 一次导入的完整 source manifest 与对外返回路径；全部校验和 staging 成功后
/// 才会逐个发布顶层 entry。
#[derive(Debug)]
struct DropManifest {
    entries: Vec<DropManifestEntry>,
    top_level_relative_paths: Vec<String>,
    imported_relative_paths: Vec<String>,
}

#[derive(Debug, Clone, Copy)]
struct DropPlanBudget {
    max_plans: usize,
    max_entries: usize,
    max_source_path_bytes: usize,
    max_tree_path_bytes: usize,
}

const RETAINED_DROP_PLAN_BUDGET: DropPlanBudget = DropPlanBudget {
    max_plans: MAX_RETAINED_DROP_PLANS,
    max_entries: MAX_RETAINED_DROP_ENTRIES,
    max_source_path_bytes: MAX_RETAINED_DROP_SOURCE_PATH_BYTES,
    max_tree_path_bytes: MAX_RETAINED_DROP_TREE_PATH_BYTES,
};

static USED_MUTATIONS: OnceLock<Mutex<HashMap<(WorkspaceId, String), Instant>>> = OnceLock::new();
static TRASH_PLANS: OnceLock<Mutex<HashMap<String, TrashPlan>>> = OnceLock::new();
static TRASH_RECOVERY_PLANS: OnceLock<Mutex<HashMap<String, TrashPlan>>> = OnceLock::new();
static DROP_PLANS: OnceLock<Mutex<HashMap<String, NativeDropPlan>>> = OnceLock::new();

/// 返回所有 mutation 共用的进程级幂等 ledger，避免每个 command 形成独立权威状态。
fn used_mutations() -> &'static Mutex<HashMap<(WorkspaceId, String), Instant>> {
    USED_MUTATIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 返回仅驻留 Rust 的短期 Trash plan 表，完整快照和原生路径绝不进入 renderer。
fn trash_plans() -> &'static Mutex<HashMap<String, TrashPlan>> {
    TRASH_PLANS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 返回与可消费 token 表隔离的 Trash 恢复证据；不确定的平台结果只能进入此表，
/// 防止调用方换一个 mutation id 后把同一删除计划再次提交。
fn trash_recovery_plans() -> &'static Mutex<HashMap<String, TrashPlan>> {
    TRASH_RECOVERY_PLANS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 在原生 token 表中准入一个删除计划：先清理过期项，再同时约束计划数、
/// 快照条目数和路径字节数；预算检查通过后才生成 opaque token，避免拒绝结果
/// 携带路径或尚未生效的 capability。遍历成本由计划数上限约束，无需维护易漂移计数器。
fn insert_trash_plan_bounded(
    plans: &mut HashMap<String, TrashPlan>,
    plan: TrashPlan,
    now: Instant,
    budget: TrashPlanBudget,
) -> Result<String, WorkspaceError> {
    plans.retain(|_, retained| retained.expires > now);

    let retained_entries = plans.values().try_fold(0usize, |total, retained| {
        total
            .checked_add(retained.snapshot.entries.len())
            .ok_or(WorkspaceError::EntryBudgetExceeded)
    })?;
    let retained_path_bytes = plans.values().try_fold(0usize, |total, retained| {
        total
            .checked_add(retained.snapshot.path_bytes)
            .ok_or(WorkspaceError::EntryBudgetExceeded)
    })?;
    let next_plan_count = plans
        .len()
        .checked_add(1)
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    let next_entry_count = retained_entries
        .checked_add(plan.snapshot.entries.len())
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    let next_path_bytes = retained_path_bytes
        .checked_add(plan.snapshot.path_bytes)
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    if next_plan_count > budget.max_plans
        || next_entry_count > budget.max_snapshot_entries
        || next_path_bytes > budget.max_snapshot_path_bytes
    {
        return Err(WorkspaceError::EntryBudgetExceeded);
    }

    let token = loop {
        let candidate = Uuid::new_v4().to_string();
        if !plans.contains_key(&candidate) {
            break candidate;
        }
    };
    plans.insert(token.clone(), plan);
    Ok(token)
}

/// 返回带到期边界的原生 Drop token 表，使拖入路径只能被消费一次。
fn drop_plans() -> &'static Mutex<HashMap<String, NativeDropPlan>> {
    DROP_PLANS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 将原生拖入 capability 限制在固定计划数、完整树条目与两类路径字节预算内；
/// 过期计划先回收，拒绝时不替换任何仍有效 token，避免拖动洪泛占满原生资源。
fn insert_drop_plan_bounded(
    plans: &mut HashMap<String, NativeDropPlan>,
    plan: NativeDropPlan,
    now: Instant,
    budget: DropPlanBudget,
) -> Result<String, WorkspaceError> {
    plans.retain(|_, retained| retained.expires > now);
    let retained_entries = plans.values().try_fold(0usize, |total, retained| {
        total
            .checked_add(retained.entry_count)
            .ok_or(WorkspaceError::EntryBudgetExceeded)
    })?;
    let retained_source_path_bytes = plans.values().try_fold(0usize, |total, retained| {
        total
            .checked_add(retained.source_path_bytes)
            .ok_or(WorkspaceError::EntryBudgetExceeded)
    })?;
    let retained_tree_path_bytes = plans.values().try_fold(0usize, |total, retained| {
        total
            .checked_add(retained.tree_path_bytes)
            .ok_or(WorkspaceError::EntryBudgetExceeded)
    })?;
    let next_plan_count = plans
        .len()
        .checked_add(1)
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    let next_entry_count = retained_entries
        .checked_add(plan.entry_count)
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    let next_source_path_bytes = retained_source_path_bytes
        .checked_add(plan.source_path_bytes)
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    let next_tree_path_bytes = retained_tree_path_bytes
        .checked_add(plan.tree_path_bytes)
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    if next_plan_count > budget.max_plans
        || next_entry_count > budget.max_entries
        || next_source_path_bytes > budget.max_source_path_bytes
        || next_tree_path_bytes > budget.max_tree_path_bytes
    {
        return Err(WorkspaceError::EntryBudgetExceeded);
    }
    let token = loop {
        let candidate = Uuid::new_v4().to_string();
        if !plans.contains_key(&candidate) {
            break candidate;
        }
    };
    plans.insert(token.clone(), plan);
    Ok(token)
}

/// 在副作用前预留 mutation id，成功或结果不确定的操作都不能被重试重复执行。
fn reserve_mutation(workspace_id: WorkspaceId, mutation_id: &str) -> Result<(), WorkspaceError> {
    let mut used = used_mutations().lock().map_err(|_| WorkspaceError::Io {
        operation: "mutation_ledger",
        kind: std::io::ErrorKind::Other.into(),
    })?;
    let now = Instant::now();
    used.retain(|_, seen| now.duration_since(*seen) < MUTATION_RETENTION);
    if used.contains_key(&(workspace_id, mutation_id.to_owned())) {
        return Err(WorkspaceError::MutationAlreadyUsed);
    }
    if used.len() >= MAX_RETAINED_MUTATIONS {
        return Err(WorkspaceError::EntryBudgetExceeded);
    }
    used.insert((workspace_id, mutation_id.to_owned()), now);
    Ok(())
}

/// 将 wire revision 转为内部比较值，字段不做推断或默认填充，确保 CAS 证据原样进入唯一实现。
fn internal_revision(input: &FileRevision) -> FileRevision {
    input.clone()
}

/// 执行严格 CAS 等值比较；调用方提供 hash 时，存在性和值都必须一致。
fn require_revision(expected: &FileRevision, actual: &FileRevision) -> Result<(), WorkspaceError> {
    if internal_revision(expected) == *actual {
        Ok(())
    } else {
        Err(WorkspaceError::RevisionConflict)
    }
}

/// path guard 重验后读取当前有界 revision，使 CAS 比较与实际 IO 使用同一物理目标。
fn current_metadata(
    workspace: &WorkspaceHandle,
    relative_path: &str,
) -> Result<FileMetadata, WorkspaceError> {
    workspace.metadata(relative_path, MAX_EDIT_BYTES)
}

/// 把 editor text 无损转换为字节；unknown、binary 或 mixed 状态无法由有界 writer 确定表达，
/// 因而必须拒绝。
fn encode_text(content: &TextContent) -> Result<Vec<u8>, WorkspaceError> {
    if content.line_ending == LineEnding::Mixed || content.text.contains('\0') {
        return Err(if content.line_ending == LineEnding::Mixed {
            WorkspaceError::MixedLineEndings
        } else {
            WorkspaceError::UnsupportedContent
        });
    }
    let mut normalized = String::with_capacity(content.text.len());
    let bytes = content.text.as_bytes();
    let mut index = 0usize;
    while index < bytes.len() {
        match bytes[index] {
            b'\r' if bytes.get(index + 1) == Some(&b'\n') => {
                normalized.push('\n');
                index += 2;
            }
            b'\r' => {
                normalized.push('\n');
                index += 1;
            }
            b'\n' => {
                normalized.push('\n');
                index += 1;
            }
            _ => {
                let character = content.text[index..]
                    .chars()
                    .next()
                    .ok_or(WorkspaceError::UnsupportedContent)?;
                normalized.push(character);
                index += character.len_utf8();
            }
        }
    }
    let newline = match content.line_ending {
        LineEnding::Lf => "\n",
        LineEnding::CrLf => "\r\n",
        LineEnding::Cr => "\r",
        LineEnding::Mixed => return Err(WorkspaceError::MixedLineEndings),
    };
    let text = if newline == "\n" {
        normalized
    } else {
        normalized.replace('\n', newline)
    };
    let mut output = match content.encoding {
        TextEncoding::Utf8 | TextEncoding::Utf8Bom => text.into_bytes(),
        TextEncoding::Utf16Le => text.encode_utf16().flat_map(u16::to_le_bytes).collect(),
        TextEncoding::Utf16Be => text.encode_utf16().flat_map(u16::to_be_bytes).collect(),
    };
    if matches!(content.encoding, TextEncoding::Utf8Bom) {
        output.splice(0..0, [0xef, 0xbb, 0xbf]);
    } else if matches!(content.encoding, TextEncoding::Utf16Le) {
        output.splice(0..0, [0xff, 0xfe]);
    } else if matches!(content.encoding, TextEncoding::Utf16Be) {
        output.splice(0..0, [0xfe, 0xff]);
    }
    if u64::try_from(output.len()).unwrap_or(u64::MAX) > MAX_EDIT_BYTES {
        return Err(WorkspaceError::WriteTooLarge);
    }
    Ok(output)
}

/// 在目标同目录写入并 flush 临时文件，为后续原子替换保留同卷语义与崩溃边界。
fn write_temp(parent: &Path, bytes: &[u8]) -> Result<PathBuf, WorkspaceError> {
    let temp = parent.join(format!(".ja-write-{}.tmp", Uuid::new_v4()));
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temp)
            .map_err(|error| WorkspaceError::io("create_temp", error))?;
        file.write_all(bytes)
            .map_err(|error| WorkspaceError::io("write_temp", error))?;
        file.flush()
            .map_err(|error| WorkspaceError::io("flush_temp", error))?;
        file.sync_all()
            .map_err(|error| WorkspaceError::io("sync_temp", error))?;
        Ok(temp.clone())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}

pub(crate) mod save;
use save::*;
pub(crate) mod move_entry;
use move_entry::*;
pub(crate) mod trash;
use trash::*;
#[cfg(windows)]
pub(crate) mod trash_windows;
#[cfg(windows)]
use trash_windows::*;
pub(crate) mod drop;
use drop::*;
pub(crate) use drop::{consume_native_drop, issue_native_drop};
pub(crate) mod port;
pub(crate) use port::NativeWorkspaceMutationPort;

/// 将系统时钟转换为有界 token 到期数值；时钟异常或整数溢出时饱和到最大值，避免 panic。
fn unix_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| u64::try_from(duration.as_millis()).ok())
        .unwrap_or(u64::MAX)
}
