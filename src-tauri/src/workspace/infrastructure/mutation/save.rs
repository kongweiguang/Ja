// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// 文件系统 commit 边界捕获路径证据；link、hard link、非文件或超限文件等策略不安全节点
/// 可在冲突后恢复，但绝不能满足 editor save CAS。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct CapturedPathEvidence {
    pub(super) identity: MoveIdentity,
    pub(super) revision: FileRevision,
    pub(super) policy_safe_file: bool,
}

/// 不跟随末端 link 地分类节点；普通 Workspace metadata 因 hard-link 竞争拒绝时，
/// 该 fallback 仅保留 rollback 身份，绝不作为可编辑文件 revision 接受。
pub(super) fn basic_revision(metadata: &fs::Metadata) -> Result<FileRevision, WorkspaceError> {
    let kind = if is_reparse_point(metadata) {
        EntryKind::ReparsePoint
    } else if metadata.file_type().is_symlink() {
        EntryKind::Symlink
    } else if metadata.is_file() {
        EntryKind::File
    } else if metadata.is_dir() {
        EntryKind::Directory
    } else {
        EntryKind::Other
    };
    let modified_unix_millis = metadata.modified().ok().and_then(|modified| {
        modified
            .duration_since(UNIX_EPOCH)
            .ok()
            .map(|duration| duration.as_millis())
    });
    FileRevision::new(kind, metadata.len(), modified_unix_millis, None)
}

/// 在另一 mutation 消费 recovery 文件前读取身份与 revision；hard link 保留足够 rollback 证据，
/// 但显式标为策略不安全，不能通过 expected CAS。
pub(super) fn captured_path_evidence(path: &Path) -> Result<CapturedPathEvidence, WorkspaceError> {
    let identity_before = move_identity(path)?;
    let metadata =
        fs::symlink_metadata(path).map_err(|error| WorkspaceError::io("capture_stat", error))?;
    let (revision, policy_safe_file) =
        match metadata_for_path_with_deadline(path, &metadata, MAX_EDIT_BYTES, None) {
            Ok(metadata) => {
                let safe = metadata.kind == EntryKind::File
                    && metadata.revision.sha256().is_some()
                    && metadata.size <= MAX_EDIT_BYTES;
                (metadata.revision, safe)
            }
            Err(WorkspaceError::LinkNotAllowed) => (basic_revision(&metadata)?, false),
            Err(error) => return Err(error),
        };
    let identity_after = move_identity(path)?;
    let after =
        fs::symlink_metadata(path).map_err(|error| WorkspaceError::io("capture_recheck", error))?;
    let after_revision = basic_revision(&after)?;
    if identity_before != identity_after
        || revision.kind() != after_revision.kind()
        || revision.size() != after_revision.size()
        || revision.modified_unix_millis() != after_revision.modified_unix_millis()
    {
        return Err(WorkspaceError::PathChanged);
    }
    Ok(CapturedPathEvidence {
        identity: identity_after,
        revision,
        policy_safe_file,
    })
}

/// 把捕获文件与调用方批准的完整 revision 比较；Save admission 要求带 SHA-256 的常规文件，
/// 即使粗粒度 metadata 恰好一致，竞争产生的不安全节点也必须进入 rollback。
pub(super) fn evidence_matches_expected(
    evidence: &CapturedPathEvidence,
    expected: &FileRevision,
) -> bool {
    evidence.policy_safe_file && evidence.revision == internal_revision(expected)
}

/// 选择未创建的同级 recovery 名；让 ReplaceFile 自己处理竞争，避免占位文件在恢复中丢失。
#[cfg(windows)]
pub(super) fn absent_recovery_path(parent: &Path, prefix: &str) -> Result<PathBuf, WorkspaceError> {
    for _ in 0..8 {
        let path = parent.join(format!(".{prefix}-{}.tmp", Uuid::new_v4()));
        match fs::symlink_metadata(&path) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(path),
            Ok(_) => continue,
            Err(error) => return Err(WorkspaceError::io("recovery_stat", error)),
        }
    }
    Err(WorkspaceError::EntryBudgetExceeded)
}

/// 调用 ReplaceFileW 原子捕获 displaced target 而不是删除它；backup 与 replacement pointer
/// 始终由调用方持有。
#[cfg(windows)]
pub(super) fn replace_file_with_backup(
    target: &Path,
    replacement: &Path,
    backup: &Path,
    operation: &'static str,
) -> Result<(), WorkspaceError> {
    use std::os::windows::ffi::OsStrExt;

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn ReplaceFileW(
            replaced_file_name: *const u16,
            replacement_file_name: *const u16,
            backup_file_name: *const u16,
            replace_flags: u32,
            exclude: *mut std::ffi::c_void,
            reserved: *mut std::ffi::c_void,
        ) -> i32;
    }

    let target = target
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    let replacement = replacement
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    let backup = backup
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    // 安全性：全部 UTF-16 buffer 均以 NUL 结尾并在同步调用期间存活；ReplaceFileW 不保留传入 pointer。
    let ok = unsafe {
        ReplaceFileW(
            target.as_ptr(),
            replacement.as_ptr(),
            backup.as_ptr(),
            0,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
        )
    } != 0;
    if ok {
        Ok(())
    } else {
        Err(WorkspaceError::io(
            operation,
            std::io::Error::last_os_error(),
        ))
    }
}

/// 通过平台 exchange syscall 原子交换两个 sibling 名称；parent directory fd 把操作绑定到
/// 最终 syscall 前已打开的目录，避免不安全的 portable check-then-rename fallback。
#[cfg(unix)]
pub(super) fn atomic_exchange(
    first: &Path,
    second: &Path,
    operation: &'static str,
) -> Result<(), WorkspaceError> {
    use std::ffi::{CString, c_char};
    use std::os::fd::AsRawFd;
    use std::os::unix::ffi::OsStrExt;

    let first_parent_path = first
        .parent()
        .ok_or_else(|| WorkspaceError::io(operation, std::io::ErrorKind::InvalidInput))?;
    let second_parent_path = second
        .parent()
        .ok_or_else(|| WorkspaceError::io(operation, std::io::ErrorKind::InvalidInput))?;
    let first_parent =
        fs::File::open(first_parent_path).map_err(|error| WorkspaceError::io(operation, error))?;
    let second_parent =
        fs::File::open(second_parent_path).map_err(|error| WorkspaceError::io(operation, error))?;
    let first_name = CString::new(
        first
            .file_name()
            .ok_or_else(|| WorkspaceError::io(operation, std::io::ErrorKind::InvalidInput))?
            .as_bytes(),
    )
    .map_err(|_| WorkspaceError::io(operation, std::io::ErrorKind::InvalidInput))?;
    let second_name = CString::new(
        second
            .file_name()
            .ok_or_else(|| WorkspaceError::io(operation, std::io::ErrorKind::InvalidInput))?
            .as_bytes(),
    )
    .map_err(|_| WorkspaceError::io(operation, std::io::ErrorKind::InvalidInput))?;

    #[cfg(target_os = "linux")]
    {
        const RENAME_EXCHANGE: u32 = 2;
        unsafe extern "C" {
            fn renameat2(
                old_directory: i32,
                old_path: *const c_char,
                new_directory: i32,
                new_path: *const c_char,
                flags: u32,
            ) -> i32;
        }
        // 安全性：两个 parent file 与 C string 在调用期间存活；RENAME_EXCHANGE 原子交换现有目录项。
        let status = unsafe {
            renameat2(
                first_parent.as_raw_fd(),
                first_name.as_ptr(),
                second_parent.as_raw_fd(),
                second_name.as_ptr(),
                RENAME_EXCHANGE,
            )
        };
        if status == 0 {
            Ok(())
        } else {
            Err(WorkspaceError::io(
                operation,
                std::io::Error::last_os_error(),
            ))
        }
    }

    #[cfg(target_vendor = "apple")]
    {
        const RENAME_SWAP: u32 = 0x0000_0002;
        unsafe extern "C" {
            fn renameatx_np(
                old_directory: i32,
                old_path: *const c_char,
                new_directory: i32,
                new_path: *const c_char,
                flags: u32,
            ) -> i32;
        }
        // 安全性：parent fd 与 C string 在同步调用期间存活；RENAME_SWAP 是 Apple 原子交换 primitive。
        let status = unsafe {
            renameatx_np(
                first_parent.as_raw_fd(),
                first_name.as_ptr(),
                second_parent.as_raw_fd(),
                second_name.as_ptr(),
                RENAME_SWAP,
            )
        };
        if status == 0 {
            Ok(())
        } else {
            Err(WorkspaceError::io(
                operation,
                std::io::Error::last_os_error(),
            ))
        }
    }

    #[cfg(all(unix, not(any(target_os = "linux", target_vendor = "apple"))))]
    {
        let _ = (first_parent, second_parent, first_name, second_name);
        Err(WorkspaceError::Io {
            operation,
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
}

/// 原子发布 candidate，并把 commit point 的精确 displaced 对象保存在私有 sibling path，
/// 供校验或 rollback 使用。
pub(super) fn capture_replacement(
    candidate: &Path,
    target: &Path,
    displaced: &Path,
) -> Result<(), WorkspaceError> {
    #[cfg(windows)]
    {
        replace_file_with_backup(target, candidate, displaced, "save_capture")
    }
    #[cfg(unix)]
    {
        if displaced != candidate {
            return Err(WorkspaceError::io(
                "save_capture",
                std::io::ErrorKind::InvalidInput,
            ));
        }
        atomic_exchange(candidate, target, "save_capture")
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (candidate, target, displaced);
        Err(WorkspaceError::Io {
            operation: "save_capture",
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
}

/// 原子恢复 displaced 对象并把 Ja candidate 保存到可独立核验的 recovery path；证据不确定时不删除。
pub(super) fn rollback_replacement(
    displaced: &Path,
    target: &Path,
    candidate_recovery: &Path,
) -> Result<(), WorkspaceError> {
    #[cfg(windows)]
    {
        replace_file_with_backup(target, displaced, candidate_recovery, "save_rollback")
    }
    #[cfg(unix)]
    {
        if candidate_recovery != displaced {
            return Err(WorkspaceError::io(
                "save_rollback",
                std::io::ErrorKind::InvalidInput,
            ));
        }
        atomic_exchange(displaced, target, "save_rollback")
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (displaced, target, candidate_recovery);
        Err(WorkspaceError::Io {
            operation: "save_rollback",
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
}

/// 发布新建文件时不得替换并发创建的 destination；Unix 使用原子 no-replace hard link，
/// Windows 省略 replace flag。
pub(super) fn atomic_create(temp: &Path, target: &Path) -> Result<(), WorkspaceError> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        const MOVEFILE_WRITE_THROUGH: u32 = 0x8;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn MoveFileExW(existing: *const u16, replacement: *const u16, flags: u32) -> i32;
        }
        let source = temp
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect::<Vec<_>>();
        let destination = target
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect::<Vec<_>>();
        // 安全性：buffer 均以 NUL 结尾并在同步调用期间存活；操作系统随后不保留任何 pointer。
        let ok = unsafe {
            MoveFileExW(
                source.as_ptr(),
                destination.as_ptr(),
                MOVEFILE_WRITE_THROUGH,
            )
        } != 0;
        if !ok {
            return Err(WorkspaceError::io(
                "atomic_create",
                std::io::Error::last_os_error(),
            ));
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        fs::hard_link(temp, target).map_err(|error| WorkspaceError::io("atomic_create", error))?;
        fs::remove_file(temp).map_err(|error| WorkspaceError::io("atomic_create", error))
    }
}

/// Save stage 的真实候选与恢复证据；candidate 在 application 调用 commit 前已经完整落盘，
/// displaced 只会在原子发布后出现，因此 rollback 可以准确判断要清理候选还是恢复旧目标。
pub(crate) struct PreparedSave {
    relative_path: String,
    expected: FileRevision,
    target: PathBuf,
    parent_path: PathBuf,
    candidate: PathBuf,
    candidate_evidence: CapturedPathEvidence,
    displaced: Option<PathBuf>,
    displaced_evidence: Option<CapturedPathEvidence>,
    published: bool,
    completed: bool,
}

/// 在同目录创建并同步候选文件；该阶段不改变目标，但留下 rollback 可独立核验和清理的真实资源。
pub(crate) fn prepare_save(
    workspace: &WorkspaceHandle,
    relative_path: &str,
    expected: &FileRevision,
    bytes: &[u8],
) -> Result<PreparedSave, WorkspaceError> {
    // 先固定 parent、目标身份和旧 revision，候选只能在全部 CAS 与 containment 成功后落盘。
    let (parent_guard, target) = workspace.resolve_parent(relative_path)?;
    let current_guard = workspace.resolve_guard(relative_path, Some(false))?;
    let current = current_metadata(workspace, relative_path)?;
    require_revision(expected, &current.revision)?;
    if current.revision.size() > MAX_EDIT_BYTES {
        return Err(WorkspaceError::WriteTooLarge);
    }
    if expected.kind() != EntryKind::File || expected.sha256().is_none() {
        return Err(WorkspaceError::RevisionConflict);
    }
    workspace.verify_resolved(&parent_guard, Some(true))?;
    workspace.verify_resolved(&current_guard, Some(false))?;
    let candidate = write_temp(&parent_guard.path, bytes)?;
    let candidate_evidence = match captured_path_evidence(&candidate) {
        Ok(evidence) if evidence.policy_safe_file => evidence,
        Ok(_) | Err(_) => {
            let _ = fs::remove_file(&candidate);
            return Err(WorkspaceError::RecoveryRequired);
        }
    };
    Ok(PreparedSave {
        relative_path: relative_path.to_owned(),
        expected: expected.clone(),
        target,
        parent_path: parent_guard.path,
        candidate,
        candidate_evidence,
        displaced: None,
        displaced_evidence: None,
        published: false,
        completed: false,
    })
}

/// 原子发布已落盘候选；发生冲突时保留 displaced 与 candidate 证据，由 application 决定调用 rollback。
pub(crate) fn commit_prepared_save_with<F>(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedSave,
    precommit: F,
) -> Result<FileRevision, WorkspaceError>
where
    F: FnOnce(&Path) -> Result<(), WorkspaceError>,
{
    // 提交栅栏重新解析目标并复核入口 CAS，避免 stage 到 commit 之间的替换绕过 expected revision。
    let (parent_guard, target) = workspace.resolve_parent(&prepared.relative_path)?;
    if parent_guard.path != prepared.parent_path || target != prepared.target {
        return Err(WorkspaceError::PathChanged);
    }
    let latest_guard = workspace.resolve_guard(&prepared.relative_path, Some(false))?;
    let latest = current_metadata(workspace, &prepared.relative_path)?;
    require_revision(&prepared.expected, &latest.revision)?;
    workspace.verify_resolved(&parent_guard, Some(true))?;
    workspace.verify_resolved(&latest_guard, Some(false))?;
    precommit(&prepared.target)?;

    #[cfg(windows)]
    let displaced = absent_recovery_path(&prepared.parent_path, "ja-displaced")?;
    #[cfg(not(windows))]
    let displaced = prepared.candidate.clone();
    prepared.displaced = Some(displaced.clone());
    if let Err(error) = capture_replacement(&prepared.candidate, &prepared.target, &displaced) {
        let candidate_untouched = captured_path_evidence(&prepared.candidate)
            .is_ok_and(|evidence| evidence == prepared.candidate_evidence);
        #[cfg(windows)]
        let displaced_absent = matches!(
            fs::symlink_metadata(&displaced),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound
        );
        #[cfg(not(windows))]
        let displaced_absent = true;
        if candidate_untouched && displaced_absent {
            return Err(
                if captured_path_evidence(&prepared.target)
                    .is_ok_and(|evidence| evidence_matches_expected(&evidence, &prepared.expected))
                {
                    error
                } else {
                    WorkspaceError::RevisionConflict
                },
            );
        }
        return Err(WorkspaceError::RecoveryRequired);
    }
    prepared.published = true;

    // 发布后同时核验新目标与 displaced；只有旧内容仍匹配 CAS 才能清理恢复证据并完成提交。
    workspace.verify_resolved(&parent_guard, Some(true))?;
    if captured_path_evidence(&prepared.target)
        .map_or(true, |evidence| evidence != prepared.candidate_evidence)
    {
        return Err(WorkspaceError::RecoveryRequired);
    }
    let displaced_evidence =
        captured_path_evidence(&displaced).map_err(|_| WorkspaceError::RecoveryRequired)?;
    prepared.displaced_evidence = Some(displaced_evidence.clone());
    if !evidence_matches_expected(&displaced_evidence, &prepared.expected) {
        return Err(WorkspaceError::RevisionConflict);
    }
    if fs::remove_file(&displaced).is_err() {
        return Err(WorkspaceError::RecoveryRequired);
    }
    prepared.completed = true;
    Ok(prepared.candidate_evidence.revision.clone())
}

/// Production commit 不注入测试 seam；全部发布与证据规则仍复用同一实现。
pub(crate) fn commit_prepared_save(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedSave,
) -> Result<FileRevision, WorkspaceError> {
    commit_prepared_save_with(workspace, prepared, |_| Ok(()))
}

/// 清理未发布候选，或把已发布候选原子换回 recovery path 后恢复旧目标；任何证据不确定都拒绝删除。
pub(crate) fn rollback_prepared_save(
    _workspace: &WorkspaceHandle,
    prepared: &mut PreparedSave,
) -> Result<(), WorkspaceError> {
    if prepared.completed {
        return Ok(());
    }
    if !prepared.published {
        return match fs::remove_file(&prepared.candidate) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err(WorkspaceError::RecoveryRequired),
        };
    }
    let displaced = prepared
        .displaced
        .as_ref()
        .ok_or(WorkspaceError::RecoveryRequired)?;
    let displaced_evidence = prepared
        .displaced_evidence
        .as_ref()
        .ok_or(WorkspaceError::RecoveryRequired)?;
    if captured_path_evidence(&prepared.target)
        .map_or(true, |evidence| evidence != prepared.candidate_evidence)
        || captured_path_evidence(displaced)
            .map_or(true, |evidence| evidence != *displaced_evidence)
    {
        return Err(WorkspaceError::RecoveryRequired);
    }
    #[cfg(windows)]
    let candidate_recovery = absent_recovery_path(&prepared.parent_path, "ja-candidate")?;
    #[cfg(not(windows))]
    let candidate_recovery = displaced.clone();
    rollback_replacement(displaced, &prepared.target, &candidate_recovery)?;
    if captured_path_evidence(&prepared.target)
        .map_or(true, |evidence| evidence != *displaced_evidence)
        || captured_path_evidence(&candidate_recovery)
            .map_or(true, |evidence| evidence != prepared.candidate_evidence)
    {
        return Err(WorkspaceError::RecoveryRequired);
    }
    fs::remove_file(candidate_recovery).map_err(|_| WorkspaceError::RecoveryRequired)?;
    prepared.published = false;
    Ok(())
}

/// Recovery 不删除任何无法归属的节点；至少一个物理证据仍存在才确认现场可供人工恢复。
pub(crate) fn preserve_prepared_save(prepared: &PreparedSave) -> Result<(), WorkspaceError> {
    if prepared.target.exists()
        || prepared.candidate.exists()
        || prepared
            .displaced
            .as_ref()
            .is_some_and(|path| path.exists())
    {
        Ok(())
    } else {
        Err(WorkspaceError::RecoveryRequired)
    }
}
