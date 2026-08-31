// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// mutable counter 与 immutable snapshot 隔离，使扫描在向 token 表发布证据前执行全部预算检查。
pub(super) struct TrashSnapshotBuilder {
    entries: Vec<TrashTreeEntry>,
    entry_count: usize,
    file_count: usize,
    directory_count: usize,
    total_bytes: u64,
    path_bytes: usize,
    deadline: Instant,
}

/// 把 UTF-8 目录名接入 slash-separated Workspace 契约，任何有损转换都失败关闭。
pub(super) fn join_trash_relative(
    parent: &str,
    name: &std::ffi::OsStr,
) -> Result<String, WorkspaceError> {
    let name = name.to_str().ok_or(WorkspaceError::InvalidRelativePath)?;
    if name.is_empty()
        || name
            .chars()
            .any(|character| matches!(character, '/' | '\\' | ':' | '\0'))
    {
        return Err(WorkspaceError::InvalidRelativePath);
    }
    Ok(if parent.is_empty() {
        name.to_owned()
    } else {
        format!("{parent}/{name}")
    })
}

/// 按确定顺序读取目录名；递归后再次读取，用于发现扫描期间的新增和删除。
pub(super) fn sorted_trash_children(
    path: &Path,
) -> Result<Vec<std::ffi::OsString>, WorkspaceError> {
    let mut names = Vec::new();
    for child in fs::read_dir(path).map_err(|error| WorkspaceError::io("trash_scan", error))? {
        names.push(
            child
                .map_err(|error| WorkspaceError::io("trash_scan", error))?
                .file_name(),
        );
    }
    names.sort();
    Ok(names)
}

/// 通过 Workspace guard 和完整 SHA-256 捕获子树节点；目录双重枚举防止不稳定扫描生成 Trash token。
pub(super) fn scan_trash_node(
    workspace: &WorkspaceHandle,
    workspace_relative_path: &str,
    target_relative_path: &str,
    depth: usize,
    builder: &mut TrashSnapshotBuilder,
) -> Result<(), WorkspaceError> {
    if Instant::now() >= builder.deadline {
        return Err(WorkspaceError::ScanDeadlineExceeded);
    }
    if depth > MAX_TRASH_DEPTH {
        return Err(WorkspaceError::DepthLimitExceeded);
    }
    builder.path_bytes = builder
        .path_bytes
        .checked_add(target_relative_path.len())
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    if builder.path_bytes > MAX_TRASH_PATH_BYTES {
        return Err(WorkspaceError::EntryBudgetExceeded);
    }
    builder.entry_count = builder.entry_count.saturating_add(1);
    if builder.entry_count > MAX_TRASH_ENTRIES {
        return Err(WorkspaceError::EntryBudgetExceeded);
    }

    let guard = workspace.resolve_guard(workspace_relative_path, None)?;
    let metadata = fs::symlink_metadata(&guard.path)
        .map_err(|error| WorkspaceError::io("trash_stat", error))?;
    if metadata.is_file() {
        builder.file_count = builder.file_count.saturating_add(1);
        builder.total_bytes = builder
            .total_bytes
            .checked_add(metadata.len())
            .ok_or(WorkspaceError::FileTooLarge)?;
        if builder.total_bytes > MAX_TRASH_BYTES {
            return Err(WorkspaceError::FileTooLarge);
        }
    } else if metadata.is_dir() {
        builder.directory_count = builder.directory_count.saturating_add(1);
    }
    let file_metadata = metadata_for_path_with_deadline(
        &guard.path,
        &metadata,
        MAX_TRASH_BYTES,
        Some(builder.deadline),
    )?;
    let identity = move_identity(&guard.path)?;
    workspace.verify_resolved(&guard, None)?;
    let initial_children = if metadata.is_dir() {
        Some(sorted_trash_children(&guard.path)?)
    } else {
        None
    };

    if let Some(children) = &initial_children {
        for name in children {
            let child_workspace_relative = join_trash_relative(workspace_relative_path, name)?;
            let child_target_relative = join_trash_relative(target_relative_path, name)?;
            scan_trash_node(
                workspace,
                &child_workspace_relative,
                &child_target_relative,
                depth.saturating_add(1),
                builder,
            )?;
        }
        if sorted_trash_children(&guard.path)? != *children {
            return Err(WorkspaceError::PathChanged);
        }
        workspace.verify_resolved(&guard, Some(true))?;
        let after = fs::symlink_metadata(&guard.path)
            .map_err(|error| WorkspaceError::io("trash_recheck", error))?;
        let after_metadata = metadata_for_path_with_deadline(
            &guard.path,
            &after,
            MAX_TRASH_BYTES,
            Some(builder.deadline),
        )?;
        if move_identity(&guard.path)? != identity
            || after_metadata.revision != file_metadata.revision
        {
            return Err(WorkspaceError::PathChanged);
        }
    }

    builder.entries.push(TrashTreeEntry {
        relative_path: target_relative_path.to_owned(),
        kind: file_metadata.kind,
        identity,
        revision: file_metadata.revision,
    });
    Ok(())
}

/// 生成确定且有界的子树 fingerprint；token 只证明 commit 看见与 prepare 相同的完整证据。
pub(super) fn snapshot_trash_target(
    workspace: &WorkspaceHandle,
    relative_path: &str,
) -> Result<TrashSnapshot, WorkspaceError> {
    let mut builder = TrashSnapshotBuilder {
        entries: Vec::new(),
        entry_count: 0,
        file_count: 0,
        directory_count: 0,
        total_bytes: 0,
        path_bytes: 0,
        deadline: Instant::now() + TRASH_SCAN_DEADLINE,
    };
    scan_trash_node(workspace, relative_path, "", 0, &mut builder)?;
    if Instant::now() >= builder.deadline {
        return Err(WorkspaceError::ScanDeadlineExceeded);
    }
    builder
        .entries
        .sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
    Ok(TrashSnapshot {
        entries: builder.entries,
        file_count: builder.file_count,
        directory_count: builder.directory_count,
        total_bytes: builder.total_bytes,
        path_bytes: builder.path_bytes,
    })
}

/// Trash prepare stage 的完整子树证据；commit 只负责把该不可变计划发布到 token 表，
/// 不再重新扫描并制造两份可能不同的事实。
pub(crate) struct PreparedTrashPlan {
    plan: TrashPlan,
    file_count: usize,
    total_bytes: u64,
    expires_at_unix_millis: u64,
}

/// 扫描并固定 Trash 计划但不发布 capability；stage 失败不会产生 token 或改变既有计划。
pub(crate) fn prepare_trash_plan(
    workspace: &WorkspaceHandle,
    relative_path: String,
    expected_revision: &FileRevision,
) -> Result<PreparedTrashPlan, WorkspaceError> {
    if relative_path.is_empty() {
        return Err(WorkspaceError::InvalidRelativePath);
    }
    let resolved = workspace.resolve_guard(&relative_path, None)?;
    let root_identity = move_identity(&resolved.path)?;
    let metadata = current_metadata(workspace, &relative_path)?;
    require_revision(expected_revision, &metadata.revision)?;
    workspace.verify_resolved(&resolved, None)?;
    let snapshot = snapshot_trash_target(workspace, &relative_path)?;
    workspace.verify_resolved(&resolved, None)?;
    let after = current_metadata(workspace, &relative_path)?;
    if after.revision != metadata.revision {
        return Err(WorkspaceError::RevisionConflict);
    }
    let snapshot_root = snapshot
        .entries
        .iter()
        .find(|entry| entry.relative_path.is_empty())
        .ok_or(WorkspaceError::RevisionConflict)?;
    if snapshot_root.identity != root_identity
        || snapshot_root.revision.kind() != metadata.revision.kind()
        || snapshot_root.revision.size() != metadata.revision.size()
        || snapshot_root.revision.modified_unix_millis() != metadata.revision.modified_unix_millis()
        || metadata
            .revision
            .sha256()
            .is_some_and(|hash| snapshot_root.revision.sha256() != Some(hash))
    {
        return Err(WorkspaceError::RevisionConflict);
    }
    let now = Instant::now();
    let expires = now + TRASH_TTL;
    let expires_at_unix_millis = unix_millis().saturating_add(TRASH_TTL.as_millis() as u64);
    let file_count = snapshot.file_count;
    let total_bytes = snapshot.total_bytes;
    let plan = TrashPlan {
        workspace_id: workspace.id(),
        relative_path,
        expected_revision: metadata.revision,
        snapshot,
        expires,
    };
    Ok(PreparedTrashPlan {
        plan,
        file_count,
        total_bytes,
        expires_at_unix_millis,
    })
}

/// 将 staged 子树证据一次性发布为短期 capability；插入失败不改变 prepared 计划，便于 application rollback。
pub(crate) fn commit_prepared_trash_plan(
    prepared: &PreparedTrashPlan,
) -> Result<TrashPrepareResult, WorkspaceError> {
    let mut plans = trash_plans().lock().map_err(|_| WorkspaceError::Io {
        operation: "trash_plan",
        kind: std::io::ErrorKind::Other.into(),
    })?;
    let token = insert_trash_plan_bounded(
        &mut plans,
        prepared.plan.clone(),
        Instant::now(),
        RETAINED_TRASH_PLAN_BUDGET,
    )?;
    Ok(TrashPrepareResult {
        operation_token: token,
        file_count: prepared.file_count,
        total_bytes: prepared.total_bytes,
        expires_at_unix_millis: prepared.expires_at_unix_millis,
    })
}

/// Rollback 释放尚未发布的子树 entry 预算；清空后同一 prepared state 不能再次生成 capability。
pub(super) fn discard_prepared_trash_plan(
    prepared: &mut PreparedTrashPlan,
) -> Result<(), WorkspaceError> {
    prepared.plan.snapshot.entries.clear();
    prepared.file_count = 0;
    prepared.total_bytes = 0;
    Ok(())
}

/// Recovery 只接受仍持有根 entry 的完整 snapshot；空计划不能作为可恢复证据。
pub(crate) fn preserve_prepared_trash_plan(
    prepared: &PreparedTrashPlan,
) -> Result<(), WorkspaceError> {
    if prepared
        .plan
        .snapshot
        .entries
        .iter()
        .any(|entry| entry.relative_path.is_empty())
    {
        Ok(())
    } else {
        Err(WorkspaceError::RecoveryRequired)
    }
}

/// 将一个已验证目标只送入系统回收站。Windows 使用
/// `FOFX_RECYCLEONDELETE`，因此系统不能回收时返回错误，绝不退化为永久删除；
/// `verify` 在 Shell item 排队后、真正执行前再次核对完整 identity/snapshot。Windows
/// Shell 只接受 path-bound item 而非调用方持有的文件句柄，因此外部进程仍可能在
/// 最终校验与实际移动之间替换路径；PostDelete、aborted 与源状态证据只能缩小并显式
/// 报告这个窗口，无法从用户态彻底消除。
pub(super) fn move_to_os_trash<F>(path: &Path, verify: F) -> Result<(), WorkspaceError>
where
    F: FnOnce() -> Result<(), WorkspaceError> + Send,
{
    #[cfg(windows)]
    {
        use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance};
        use windows::Win32::UI::Shell::{
            FileOperation, IFileOperation, IShellItem, SHCreateItemFromParsingName,
        };
        use windows::core::PCWSTR;

        std::thread::scope(|scope| {
            scope
                .spawn(move || {
                    let _com = TrashComGuard::initialize()?;
                    let parsing_path = windows_shell_parsing_path(path)?;
                    ensure_windows_recycle_policy(&parsing_path)?;
                    // 安全性：该专用 scoped thread 独占 STA；所有 interface 与 parsing name
                    // 均长于 PerformOperations 调用。
                    let operation: IFileOperation =
                        unsafe { CoCreateInstance(&FileOperation, None, CLSCTX_INPROC_SERVER) }
                            .map_err(|_| {
                                WorkspaceError::io("trash_create", std::io::ErrorKind::Other)
                            })?;
                    unsafe { operation.SetOperationFlags(windows_trash_operation_flags()) }
                        .map_err(|_| {
                            WorkspaceError::io("trash_flags", std::io::ErrorKind::Other)
                        })?;
                    let item: IShellItem =
                        unsafe { SHCreateItemFromParsingName(PCWSTR(parsing_path.as_ptr()), None) }
                            .map_err(|_| {
                                WorkspaceError::io("trash_item", std::io::ErrorKind::Other)
                            })?;
                    let (progress_sink, post_delete) = TrashProgressSink::create();
                    unsafe { operation.DeleteItem(&item, &progress_sink) }.map_err(|_| {
                        WorkspaceError::io("trash_queue", std::io::ErrorKind::Other)
                    })?;
                    verify()?;
                    unsafe { operation.PerformOperations() }
                        .map_err(|_| WorkspaceError::io("trash", std::io::ErrorKind::Other))?;
                    let aborted = unsafe { operation.GetAnyOperationsAborted() }.map_err(|_| {
                        WorkspaceError::io("trash_status", std::io::ErrorKind::Other)
                    })?;
                    if aborted.as_bool() {
                        return Err(WorkspaceError::io("trash", std::io::ErrorKind::Interrupted));
                    }
                    if !post_delete.proves_recycled() {
                        return Err(WorkspaceError::io(
                            "trash_post_delete",
                            std::io::ErrorKind::Other,
                        ));
                    }
                    verify_windows_trash_source_absent(path)?;
                    Ok(())
                })
                .join()
                .map_err(|_| WorkspaceError::io("trash_thread", std::io::ErrorKind::Other))?
        })
    }
    #[cfg(not(windows))]
    {
        verify()?;
        trash::delete(path).map_err(|_| WorkspaceError::io("trash", std::io::ErrorKind::Other))
    }
}

/// 将已变化或新出现不安全节点映射为稳定冲突；资源预算失败保持独立以允许缩小目标后重试。
pub(super) fn map_trash_rescan_error(error: WorkspaceError) -> WorkspaceError {
    match error {
        WorkspaceError::PathNotFound
        | WorkspaceError::PathChanged
        | WorkspaceError::LinkNotAllowed
        | WorkspaceError::NotDirectory
        | WorkspaceError::NotFile => WorkspaceError::RevisionConflict,
        error => error,
    }
}

/// 重新生成完整子树快照并核对根物理身份；返回的路径只可用于紧随其后的
/// recycle 调用，调用方仍需在平台副作用边界内再执行一次本校验。
pub(super) fn verify_trash_plan_snapshot(
    workspace: &WorkspaceHandle,
    plan: &TrashPlan,
) -> Result<PathBuf, WorkspaceError> {
    let current =
        snapshot_trash_target(workspace, &plan.relative_path).map_err(map_trash_rescan_error)?;
    if current != plan.snapshot {
        return Err(WorkspaceError::RevisionConflict);
    }
    let resolved = workspace
        .resolve_guard(&plan.relative_path, None)
        .map_err(map_trash_rescan_error)?;
    workspace
        .verify_resolved(&resolved, None)
        .map_err(map_trash_rescan_error)?;
    let root_evidence = plan
        .snapshot
        .entries
        .iter()
        .find(|entry| entry.relative_path.is_empty())
        .ok_or(WorkspaceError::RevisionConflict)?;
    let root_metadata =
        fs::symlink_metadata(&resolved.path).map_err(|_| WorkspaceError::RevisionConflict)?;
    let root_revision = metadata_for_path_with_deadline(
        &resolved.path,
        &root_metadata,
        MAX_TRASH_BYTES,
        Some(Instant::now() + TRASH_SCAN_DEADLINE),
    )
    .map_err(map_trash_rescan_error)?
    .revision;
    if move_identity(&resolved.path).map_err(|_| WorkspaceError::RevisionConflict)?
        != root_evidence.identity
        || root_revision != root_evidence.revision
    {
        return Err(WorkspaceError::RevisionConflict);
    }
    Ok(resolved.path)
}

/// Trash commit stage 消费 token 并保留完整计划；commit 失败时 application 可依据
/// `side_effect_started` 与快照状态决定返还 capability 或进入 recovery。
pub(crate) struct PreparedTrashCommit {
    token: String,
    plan: TrashPlan,
    side_effect_started: bool,
    completed: bool,
}

/// 在持锁 stage 内消费并验证 capability；从此 token 只存在于事务状态，避免 commit 再次查表重建。
pub(crate) fn prepare_trash_commit(
    workspace: &WorkspaceHandle,
    input: &TrashCommitCommand,
) -> Result<PreparedTrashCommit, WorkspaceError> {
    let token = input.operation_token.as_str().to_owned();
    let plan = {
        let mut plans = trash_plans().lock().map_err(|_| WorkspaceError::Io {
            operation: "trash_plan",
            kind: std::io::ErrorKind::Other.into(),
        })?;
        let Some(plan) = plans.remove(input.operation_token.as_str()) else {
            return Err(WorkspaceError::TrashTokenInvalid);
        };
        if plan.expires <= Instant::now() {
            return Err(WorkspaceError::TrashTokenExpired);
        }
        if plan.workspace_id != workspace.id() {
            return Err(WorkspaceError::TrashTokenInvalid);
        }
        if plan.relative_path != input.relative_path.as_str()
            || internal_revision(&input.expected_revision) != plan.expected_revision
        {
            return Err(WorkspaceError::RevisionConflict);
        }
        plan
    };
    Ok(PreparedTrashCommit {
        token,
        plan,
        side_effect_started: false,
        completed: false,
    })
}

/// 仅在 token 表仍空缺时恢复未提交计划；过期或同 token 被占用意味着状态不再可安全自动重试。
fn restore_trash_plan(prepared: &PreparedTrashCommit) -> Result<(), WorkspaceError> {
    if prepared.plan.expires <= Instant::now() {
        return Err(WorkspaceError::RecoveryRequired);
    }
    // Recovery quarantine 中的 token 已经代表结果不确定，绝不能重新进入正常消费表。
    let quarantined = trash_recovery_plans()
        .lock()
        .map_err(|_| WorkspaceError::RecoveryRequired)?
        .contains_key(&prepared.token);
    if quarantined {
        return Err(WorkspaceError::RecoveryRequired);
    }
    let mut plans = trash_plans()
        .lock()
        .map_err(|_| WorkspaceError::RecoveryRequired)?;
    if plans.contains_key(&prepared.token) {
        return Err(WorkspaceError::RecoveryRequired);
    }
    plans.insert(prepared.token.clone(), prepared.plan.clone());
    Ok(())
}

/// 将不确定的 Trash 计划放入独立恢复隔离区；同一证据重复保存是幂等的，
/// 但隔离区受与活动 token 相同的预算约束，避免失败风暴形成无界原生内存。
fn quarantine_trash_plan(prepared: &PreparedTrashCommit) -> Result<(), WorkspaceError> {
    let now = Instant::now();
    let mut recovery = trash_recovery_plans()
        .lock()
        .map_err(|_| WorkspaceError::RecoveryRequired)?;
    recovery.retain(|_, plan| plan.expires > now);
    if let Some(existing) = recovery.get(&prepared.token) {
        return if existing == &prepared.plan {
            Ok(())
        } else {
            Err(WorkspaceError::RecoveryRequired)
        };
    }

    let retained_entries = recovery.values().try_fold(0usize, |total, plan| {
        total
            .checked_add(plan.snapshot.entries.len())
            .ok_or(WorkspaceError::RecoveryRequired)
    })?;
    let retained_path_bytes = recovery.values().try_fold(0usize, |total, plan| {
        total
            .checked_add(plan.snapshot.path_bytes)
            .ok_or(WorkspaceError::RecoveryRequired)
    })?;
    let next_plan_count = recovery
        .len()
        .checked_add(1)
        .ok_or(WorkspaceError::RecoveryRequired)?;
    let next_entry_count = retained_entries
        .checked_add(prepared.plan.snapshot.entries.len())
        .ok_or(WorkspaceError::RecoveryRequired)?;
    let next_path_bytes = retained_path_bytes
        .checked_add(prepared.plan.snapshot.path_bytes)
        .ok_or(WorkspaceError::RecoveryRequired)?;
    if next_plan_count > RETAINED_TRASH_PLAN_BUDGET.max_plans
        || next_entry_count > RETAINED_TRASH_PLAN_BUDGET.max_snapshot_entries
        || next_path_bytes > RETAINED_TRASH_PLAN_BUDGET.max_snapshot_path_bytes
    {
        return Err(WorkspaceError::RecoveryRequired);
    }
    recovery.insert(prepared.token.clone(), prepared.plan.clone());
    Ok(())
}

/// 对 staged 计划执行平台回收站副作用；开始前再次核对完整快照，开始后任何不确定失败都交给 application 补偿判断。
pub(crate) fn commit_prepared_trash_with<F, G>(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedTrashCommit,
    before_rescan: F,
    trash_fn: G,
) -> Result<TrashCommitResult, WorkspaceError>
where
    F: FnOnce(&Path) -> Result<(), WorkspaceError>,
    G: FnOnce(
        &Path,
        &(dyn Fn() -> Result<(), WorkspaceError> + Sync),
    ) -> Result<(), WorkspaceError>,
{
    let admitted = workspace
        .resolve_guard(&prepared.plan.relative_path, None)
        .map_err(map_trash_rescan_error)?;
    before_rescan(&admitted.path)?;
    let resolved_path = verify_trash_plan_snapshot(workspace, &prepared.plan)?;
    let verify_at_platform_boundary = || {
        let current_path = verify_trash_plan_snapshot(workspace, &prepared.plan)?;
        if current_path != resolved_path {
            return Err(WorkspaceError::RevisionConflict);
        }
        Ok(())
    };
    prepared.side_effect_started = true;
    trash_fn(&resolved_path, &verify_at_platform_boundary)?;
    prepared.completed = true;
    Ok(TrashCommitResult {
        committed: true,
        revision: None,
    })
}

/// Production Trash commit 使用同一平台适配器；token consumption 已在 stage 完成。
pub(crate) fn commit_prepared_trash(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedTrashCommit,
) -> Result<TrashCommitResult, WorkspaceError> {
    commit_prepared_trash_with(
        workspace,
        prepared,
        |_| Ok(()),
        |path, verify| move_to_os_trash(path, verify),
    )
}

/// 平台副作用未开始时直接返还 token；已开始时只有完整快照仍存在才可确认无副作用并返还。
pub(crate) fn rollback_prepared_trash(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedTrashCommit,
) -> Result<(), WorkspaceError> {
    if prepared.completed {
        return Ok(());
    }
    if prepared.side_effect_started {
        verify_trash_plan_snapshot(workspace, &prepared.plan)
            .map_err(|_| WorkspaceError::RecoveryRequired)?;
    }
    restore_trash_plan(prepared)
}

/// Recovery 把已消费计划放入不可执行的隔离表；证据可供诊断，但同 token 不能再次触发平台删除。
pub(crate) fn preserve_prepared_trash(
    prepared: &PreparedTrashCommit,
) -> Result<(), WorkspaceError> {
    quarantine_trash_plan(prepared)
}
