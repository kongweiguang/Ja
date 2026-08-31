// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// typed Move 全程保留物理身份；即使字节仍匹配，路径交换也不能冒充调用方批准的 source。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct MoveIdentity {
    volume: u64,
    file_low: u64,
    file_high: u64,
}

/// 不跟随最终 symlink/reparse 读取平台 file key；containment 仍由 Workspace guard 唯一负责。
pub(super) fn move_identity(path: &Path) -> Result<MoveIdentity, WorkspaceError> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let metadata = fs::symlink_metadata(path)
            .map_err(|error| WorkspaceError::io("move_identity", error))?;
        Ok(MoveIdentity {
            volume: metadata.dev(),
            file_low: metadata.ino(),
            file_high: 0,
        })
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;

        const FILE_READ_ATTRIBUTES: u32 = 0x0000_0080;
        const FILE_SHARE_READ: u32 = 0x0000_0001;
        const FILE_SHARE_WRITE: u32 = 0x0000_0002;
        const FILE_SHARE_DELETE: u32 = 0x0000_0004;
        const OPEN_EXISTING: u32 = 3;
        const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
        const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
        const FILE_ID_INFO_CLASS: i32 = 18;
        const INVALID_HANDLE_VALUE: *mut std::ffi::c_void = -1_isize as *mut std::ffi::c_void;

        #[repr(C)]
        struct FileIdInfo {
            volume_serial_number: u64,
            file_id: [u8; 16],
        }

        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn CloseHandle(handle: *mut std::ffi::c_void) -> i32;
            fn CreateFileW(
                name: *const u16,
                desired_access: u32,
                share_mode: u32,
                security_attributes: *const std::ffi::c_void,
                creation_disposition: u32,
                flags_and_attributes: u32,
                template_file: *mut std::ffi::c_void,
            ) -> *mut std::ffi::c_void;
            fn GetFileInformationByHandleEx(
                handle: *mut std::ffi::c_void,
                information_class: i32,
                information: *mut std::ffi::c_void,
                size: u32,
            ) -> i32;
        }

        let wide = path
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect::<Vec<_>>();
        // 安全性：NUL 结尾的路径 buffer 在同步调用期间存活，返回 handle 在之后每个分支都会关闭。
        let handle = unsafe {
            CreateFileW(
                wide.as_ptr(),
                FILE_READ_ATTRIBUTES,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                std::ptr::null(),
                OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                std::ptr::null_mut(),
            )
        };
        if handle.is_null() || handle == INVALID_HANDLE_VALUE {
            return Err(WorkspaceError::io(
                "move_identity",
                std::io::Error::last_os_error(),
            ));
        }
        let mut information = std::mem::MaybeUninit::<FileIdInfo>::uninit();
        // 安全性：`information` 使用文档规定的 FILE_ID_INFO layout，kernel 最多写入给定结构大小。
        let ok = unsafe {
            GetFileInformationByHandleEx(
                handle,
                FILE_ID_INFO_CLASS,
                information.as_mut_ptr().cast(),
                u32::try_from(std::mem::size_of::<FileIdInfo>()).unwrap_or(u32::MAX),
            )
        } != 0;
        let error = if ok {
            None
        } else {
            Some(std::io::Error::last_os_error())
        };
        // 安全性：`handle` 由 CreateFileW 返回，关闭后不再使用。
        unsafe {
            let _ = CloseHandle(handle);
        }
        if let Some(error) = error {
            return Err(WorkspaceError::io("move_identity", error));
        }
        // 安全性：成功的 GetFileInformationByHandleEx 已初始化上方完整定长 FILE_ID_INFO。
        let information = unsafe { information.assume_init() };
        let mut file_low = [0_u8; 8];
        file_low.copy_from_slice(&information.file_id[..8]);
        let mut file_high = [0_u8; 8];
        file_high.copy_from_slice(&information.file_id[8..]);
        Ok(MoveIdentity {
            volume: information.volume_serial_number,
            file_low: u64::from_le_bytes(file_low),
            file_high: u64::from_le_bytes(file_high),
        })
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = path;
        Err(WorkspaceError::Io {
            operation: "move_identity",
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
}

/// 从已打开文件句柄读取物理身份。拖入复制不能只比较路径，因为攻击者可在
/// `open` 前把普通文件替换成链接；句柄身份与签发/预检身份一致才允许读取。
pub(super) fn open_file_identity(file: &fs::File) -> Result<MoveIdentity, WorkspaceError> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let metadata = file
            .metadata()
            .map_err(|error| WorkspaceError::io("drop_identity", error))?;
        Ok(MoveIdentity {
            volume: metadata.dev(),
            file_low: metadata.ino(),
            file_high: 0,
        })
    }
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;

        const FILE_ID_INFO_CLASS: i32 = 18;
        #[repr(C)]
        struct FileIdInfo {
            volume_serial_number: u64,
            file_id: [u8; 16],
        }
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn GetFileInformationByHandleEx(
                handle: *mut std::ffi::c_void,
                class: i32,
                information: *mut std::ffi::c_void,
                size: u32,
            ) -> i32;
        }
        let mut information = std::mem::MaybeUninit::<FileIdInfo>::uninit();
        // 安全性：File 持有有效 handle，正确大小的输出 buffer 在同步身份查询期间存活。
        let ok = unsafe {
            GetFileInformationByHandleEx(
                file.as_raw_handle(),
                FILE_ID_INFO_CLASS,
                information.as_mut_ptr().cast(),
                std::mem::size_of::<FileIdInfo>() as u32,
            )
        } != 0;
        if !ok {
            return Err(WorkspaceError::io(
                "drop_identity",
                std::io::Error::last_os_error(),
            ));
        }
        let information = unsafe { information.assume_init() };
        Ok(MoveIdentity {
            volume: information.volume_serial_number,
            file_low: u64::from_le_bytes(information.file_id[..8].try_into().map_err(|_| {
                WorkspaceError::io("drop_identity", std::io::ErrorKind::InvalidData)
            })?),
            file_high: u64::from_le_bytes(information.file_id[8..].try_into().map_err(|_| {
                WorkspaceError::io("drop_identity", std::io::ErrorKind::InvalidData)
            })?),
        })
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = file;
        Err(WorkspaceError::Io {
            operation: "drop_identity",
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
}

/// 查询拖入文件的硬链接数；无法取得可靠计数时失败关闭，避免一个 workspace 外别名
/// 在导入期间改变同一物理文件，而路径与内容检查仍误判为独占 source。
pub(super) fn drop_hard_link_count(
    path: &Path,
    metadata: &fs::Metadata,
) -> Result<u64, WorkspaceError> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let _ = path;
        Ok(metadata.nlink())
    }
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        use windows::Win32::Foundation::HANDLE;
        use windows::Win32::Storage::FileSystem::{
            BY_HANDLE_FILE_INFORMATION, GetFileInformationByHandle,
        };

        let _ = metadata;
        let file =
            fs::File::open(path).map_err(|error| WorkspaceError::io("drop_link_count", error))?;
        let mut information = BY_HANDLE_FILE_INFORMATION::default();
        // 安全性：`file` 持有活动 handle，windows-rs 提供精确 BY_HANDLE_FILE_INFORMATION layout
        // 与输出 pointer 合同。
        unsafe { GetFileInformationByHandle(HANDLE(file.as_raw_handle()), &mut information) }
            .map_err(|_| WorkspaceError::io("drop_link_count", std::io::ErrorKind::Other))?;
        Ok(u64::from(information.nNumberOfLinks))
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (path, metadata);
        Err(WorkspaceError::Io {
            operation: "drop_link_count",
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
}

/// 将平台 no-replace 竞争映射为稳定 Workspace error，并把路径与 errno 留在 Rust 内部。
pub(super) fn map_move_no_replace_error(error: std::io::Error) -> WorkspaceError {
    if error.kind() == std::io::ErrorKind::AlreadyExists
        || matches!(error.raw_os_error(), Some(80 | 183))
    {
        WorkspaceError::AlreadyExists
    } else {
        WorkspaceError::io("move_no_replace", error)
    }
}

/// 仅当文件系统 commit point 不存在 destination 时 rename entry；刻意不以 portable exists-check
/// 加 `rename` 作为 fallback，因为 POSIX rename 会覆盖窗口内创建的目标。Unix 在 `verify` 前
/// 打开两个 parent 并相对这些 fd 提交，防止后续 parent-path 替换重定向操作。
pub(crate) fn rename_no_replace<F>(
    source: &Path,
    destination: &Path,
    verify: F,
) -> Result<(), WorkspaceError>
where
    F: FnOnce() -> Result<(), WorkspaceError>,
{
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        const MOVEFILE_WRITE_THROUGH: u32 = 0x8;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn MoveFileExW(existing: *const u16, replacement: *const u16, flags: u32) -> i32;
        }
        let source = source
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect::<Vec<_>>();
        let destination = destination
            .as_os_str()
            .encode_wide()
            .chain(Some(0))
            .collect::<Vec<_>>();
        verify()?;
        // 安全性：两个 NUL 结尾 buffer 均长于同步调用；省略 REPLACE_EXISTING 即 Windows no-overwrite 合同。
        let ok = unsafe {
            MoveFileExW(
                source.as_ptr(),
                destination.as_ptr(),
                MOVEFILE_WRITE_THROUGH,
            )
        } != 0;
        if !ok {
            return Err(map_move_no_replace_error(std::io::Error::last_os_error()));
        }
        Ok(())
    }
    #[cfg(target_os = "linux")]
    {
        use std::ffi::{CString, c_char};
        use std::os::fd::AsRawFd;
        use std::os::unix::ffi::OsStrExt;

        const RENAME_NOREPLACE: u32 = 1;
        unsafe extern "C" {
            fn renameat2(
                old_directory: i32,
                old_path: *const c_char,
                new_directory: i32,
                new_path: *const c_char,
                flags: u32,
            ) -> i32;
        }

        let source_parent = fs::File::open(source.parent().ok_or_else(|| {
            WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput)
        })?)
        .map_err(|error| WorkspaceError::io("move_parent_open", error))?;
        let destination_parent = fs::File::open(destination.parent().ok_or_else(|| {
            WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput)
        })?)
        .map_err(|error| WorkspaceError::io("move_parent_open", error))?;
        let source = CString::new(
            source
                .file_name()
                .ok_or_else(|| {
                    WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput)
                })?
                .as_bytes(),
        )
        .map_err(|_| WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput))?;
        let destination = CString::new(
            destination
                .file_name()
                .ok_or_else(|| {
                    WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput)
                })?
                .as_bytes(),
        )
        .map_err(|_| WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput))?;
        verify()?;
        // 安全性：两个 C string 与 parent file 在调用期间存活；directory fd 把 commit 绑定到上方重验的 parent。
        let status = unsafe {
            renameat2(
                source_parent.as_raw_fd(),
                source.as_ptr(),
                destination_parent.as_raw_fd(),
                destination.as_ptr(),
                RENAME_NOREPLACE,
            )
        };
        if status != 0 {
            return Err(map_move_no_replace_error(std::io::Error::last_os_error()));
        }
        Ok(())
    }
    #[cfg(target_vendor = "apple")]
    {
        use std::ffi::{CString, c_char};
        use std::os::fd::AsRawFd;
        use std::os::unix::ffi::OsStrExt;

        const RENAME_EXCL: u32 = 0x0000_0004;
        unsafe extern "C" {
            fn renameatx_np(
                old_directory: i32,
                old_path: *const c_char,
                new_directory: i32,
                new_path: *const c_char,
                flags: u32,
            ) -> i32;
        }

        let source_parent = fs::File::open(source.parent().ok_or_else(|| {
            WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput)
        })?)
        .map_err(|error| WorkspaceError::io("move_parent_open", error))?;
        let destination_parent = fs::File::open(destination.parent().ok_or_else(|| {
            WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput)
        })?)
        .map_err(|error| WorkspaceError::io("move_parent_open", error))?;
        let source = CString::new(
            source
                .file_name()
                .ok_or_else(|| {
                    WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput)
                })?
                .as_bytes(),
        )
        .map_err(|_| WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput))?;
        let destination = CString::new(
            destination
                .file_name()
                .ok_or_else(|| {
                    WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput)
                })?
                .as_bytes(),
        )
        .map_err(|_| WorkspaceError::io("move_no_replace", std::io::ErrorKind::InvalidInput))?;
        verify()?;
        // 安全性：parent fd 与 C string 在调用期间有效；RENAME_EXCL 在固定 parent 内提供原子 no-replace。
        let status = unsafe {
            renameatx_np(
                source_parent.as_raw_fd(),
                source.as_ptr(),
                destination_parent.as_raw_fd(),
                destination.as_ptr(),
                RENAME_EXCL,
            )
        };
        if status != 0 {
            return Err(map_move_no_replace_error(std::io::Error::last_os_error()));
        }
        Ok(())
    }
    #[cfg(all(unix, not(any(target_os = "linux", target_vendor = "apple"))))]
    {
        let _ = (source, destination, verify);
        Err(WorkspaceError::Io {
            operation: "move_no_replace",
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (source, destination, verify);
        Err(WorkspaceError::Io {
            operation: "move_no_replace",
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
}

/// 除明确 not-found 外，所有 metadata 结果都视为已占用或失败；权限错误绝不能误判为允许覆盖。
pub(super) fn require_destination_absent(destination: &Path) -> Result<(), WorkspaceError> {
    match fs::symlink_metadata(destination) {
        Ok(_) => Err(WorkspaceError::AlreadyExists),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(WorkspaceError::io("move_destination_stat", error)),
    }
}

/// Move stage 保存源身份、两个 parent 与独立 recovery marker；marker 让 application 在
/// commit 失败后能区分“尚未移动”和“已移动但待补偿”，而不是依赖空 rollback。
pub(crate) struct PreparedMove {
    from_relative_path: String,
    to_relative_path: String,
    expected_revision: FileRevision,
    source: PathBuf,
    destination: PathBuf,
    source_identity: MoveIdentity,
    recovery_marker: PathBuf,
    moved: bool,
    completed: bool,
}

/// 固定 Move 的物理身份并同步 recovery marker；不改变源或目标的可见位置。
pub(crate) fn prepare_move(
    workspace: &WorkspaceHandle,
    from_relative_path: &str,
    to_relative_path: &str,
    expected_revision: &FileRevision,
) -> Result<PreparedMove, WorkspaceError> {
    let source_guard = workspace.resolve_guard(from_relative_path, None)?;
    let source = current_metadata(workspace, from_relative_path)?;
    require_revision(expected_revision, &source.revision)?;
    let source_identity = move_identity(&source_guard.path)?;
    let (source_parent, source_from_parent) = workspace.resolve_parent(from_relative_path)?;
    if source_from_parent != source_guard.path {
        return Err(WorkspaceError::PathChanged);
    }
    let (destination_parent, destination) = workspace.resolve_parent(to_relative_path)?;
    require_destination_absent(&destination)?;
    workspace.verify_resolved(&source_parent, Some(true))?;
    workspace.verify_resolved(&source_guard, None)?;
    workspace.verify_resolved(&destination_parent, Some(true))?;

    // marker 使用 create_new 并立即同步；rollback 只会删除本事务生成的唯一 sibling 文件。
    let recovery_marker = absent_recovery_path(&destination_parent.path, "ja-move")?;
    let marker = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&recovery_marker)
        .map_err(|error| WorkspaceError::io("move_stage", error))?;
    marker
        .sync_all()
        .map_err(|error| WorkspaceError::io("move_stage_sync", error))?;
    Ok(PreparedMove {
        from_relative_path: from_relative_path.to_owned(),
        to_relative_path: to_relative_path.to_owned(),
        expected_revision: expected_revision.clone(),
        source: source_guard.path,
        destination,
        source_identity,
        recovery_marker,
        moved: false,
        completed: false,
    })
}

/// 在 prepared 身份上执行唯一 no-replace 发布；发布后状态立即记为 moved，确保后续校验失败能由 rollback 逆向恢复。
pub(crate) fn commit_prepared_move_with<F>(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedMove,
    precommit: F,
) -> Result<FileRevision, WorkspaceError>
where
    F: FnOnce(&Path, &Path) -> Result<(), WorkspaceError>,
{
    let source_guard = workspace.resolve_guard(&prepared.from_relative_path, None)?;
    let (source_parent, source) = workspace.resolve_parent(&prepared.from_relative_path)?;
    let (destination_parent, destination) = workspace.resolve_parent(&prepared.to_relative_path)?;
    if source != prepared.source || destination != prepared.destination {
        return Err(WorkspaceError::PathChanged);
    }
    let current = current_metadata(workspace, &prepared.from_relative_path)?;
    require_revision(&prepared.expected_revision, &current.revision)?;
    require_destination_absent(&prepared.destination)?;
    precommit(&prepared.source, &prepared.destination)?;
    rename_no_replace(&prepared.source, &prepared.destination, || {
        workspace.verify_resolved(&source_parent, Some(true))?;
        workspace.verify_resolved(&source_guard, None)?;
        workspace.verify_resolved(&destination_parent, Some(true))?;
        if move_identity(&prepared.source)? != prepared.source_identity {
            return Err(WorkspaceError::PathChanged);
        }
        Ok(())
    })?;
    prepared.moved = true;

    // 发布后必须证明目标仍是同一物理节点；marker 只在全部证据闭合后删除。
    workspace.verify_resolved(&source_parent, Some(true))?;
    workspace.verify_resolved(&destination_parent, Some(true))?;
    let destination_guard = workspace.resolve_guard(&prepared.to_relative_path, None)?;
    if move_identity(&destination_guard.path)? != prepared.source_identity {
        return Err(WorkspaceError::PathChanged);
    }
    let moved = current_metadata(workspace, &prepared.to_relative_path)?;
    require_revision(&prepared.expected_revision, &moved.revision)?;
    fs::remove_file(&prepared.recovery_marker).map_err(|_| WorkspaceError::RecoveryRequired)?;
    prepared.completed = true;
    Ok(moved.revision)
}

/// Production Move 不注入竞态 seam，仍复用相同 prepared state 与证据闭环。
pub(crate) fn commit_prepared_move(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedMove,
) -> Result<FileRevision, WorkspaceError> {
    commit_prepared_move_with(workspace, prepared, |_, _| Ok(()))
}

/// 未发布时只清理 marker；已发布时先验证目标身份，再原子 no-replace 恢复到原路径并清理 marker。
pub(crate) fn rollback_prepared_move(
    _workspace: &WorkspaceHandle,
    prepared: &mut PreparedMove,
) -> Result<(), WorkspaceError> {
    if prepared.completed {
        return Ok(());
    }
    if prepared.moved {
        require_destination_absent(&prepared.source)?;
        if move_identity(&prepared.destination)? != prepared.source_identity {
            return Err(WorkspaceError::RecoveryRequired);
        }
        rename_no_replace(&prepared.destination, &prepared.source, || {
            if move_identity(&prepared.destination)? != prepared.source_identity {
                return Err(WorkspaceError::PathChanged);
            }
            Ok(())
        })?;
        prepared.moved = false;
    }
    match fs::remove_file(&prepared.recovery_marker) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(_) => Err(WorkspaceError::RecoveryRequired),
    }
}

/// Recovery 保留 marker，并确认源或目标仍承载原物理身份；证据消失时不能宣称现场可恢复。
pub(crate) fn preserve_prepared_move(prepared: &PreparedMove) -> Result<(), WorkspaceError> {
    let source_matches =
        move_identity(&prepared.source).is_ok_and(|identity| identity == prepared.source_identity);
    let destination_matches = move_identity(&prepared.destination)
        .is_ok_and(|identity| identity == prepared.source_identity);
    if prepared.recovery_marker.exists() && (source_matches || destination_matches) {
        Ok(())
    } else {
        Err(WorkspaceError::RecoveryRequired)
    }
}
