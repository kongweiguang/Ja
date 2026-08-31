// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::error::{AttachmentIngressError, AttachmentIngressErrorCode, map_io_error};
use std::fs::{self, File, Metadata, OpenOptions};
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct FileIdentity {
    volume: u64,
    low: u64,
    high: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct FileSnapshot {
    pub(super) identity: FileIdentity,
    pub(super) size: u64,
    pub(super) modified_low: u64,
    pub(super) modified_high: i64,
    pub(super) links: u64,
    pub(super) regular: bool,
    pub(super) reparse: bool,
}

/// dialog 结果仍是不可信路径：必须是绝对普通路径，且每个已存在组件都不能是 link/reparse。
pub(super) fn validate_source_path(path: &Path) -> Result<(), AttachmentIngressError> {
    if !path.is_absolute() || path.file_name().is_none() {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::UnsupportedPath,
        ));
    }
    validate_platform_spelling(path)?;
    let mut current = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Prefix(_) | Component::RootDir => current.push(component.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => {
                return Err(AttachmentIngressError::new(
                    AttachmentIngressErrorCode::UnsupportedPath,
                ));
            }
            Component::Normal(name) => {
                current.push(name);
                let metadata = fs::symlink_metadata(&current).map_err(|error| {
                    map_io_error(
                        AttachmentIngressErrorCode::SourceReadFailed,
                        "source_component_metadata",
                        error,
                    )
                })?;
                if metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
                    return Err(AttachmentIngressError::new(
                        AttachmentIngressErrorCode::LinkNotAllowed,
                    ));
                }
            }
        }
    }
    Ok(())
}

/// 读取前先用非跟随 metadata 排除目录、socket/FIFO/device 等特殊节点，避免打开时阻塞或触发设备语义。
pub(super) fn require_regular_metadata(path: &Path) -> Result<Metadata, AttachmentIngressError> {
    let metadata = fs::symlink_metadata(path).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::SourceReadFailed,
            "source_metadata",
            error,
        )
    })?;
    if metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::LinkNotAllowed,
        ));
    }
    if !metadata.is_file() {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::NotRegularFile,
        ));
    }
    Ok(metadata)
}

/// Windows 以 `OPEN_REPARSE_POINT` 且不共享 write/delete 打开读取句柄；这会让已存在 writer 或替换竞争失败关闭。
pub(super) fn open_source(path: &Path) -> Result<File, AttachmentIngressError> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        const FILE_SHARE_READ: u32 = 0x0000_0001;
        const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
        const FILE_FLAG_SEQUENTIAL_SCAN: u32 = 0x0800_0000;
        options
            .share_mode(FILE_SHARE_READ)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_SEQUENTIAL_SCAN);
    }
    options.open(path).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::SourceReadFailed,
            "source_open",
            error,
        )
    })
}

/// 从实际读取句柄查询物理 identity、size、mtime 与 hard-link count，不能用路径二次 stat 代替。
pub(super) fn snapshot_file(file: &File) -> Result<FileSnapshot, AttachmentIngressError> {
    snapshot_file_impl(file).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::SourceReadFailed,
            "source_handle_snapshot",
            error,
        )
    })
}

/// 路径快照通过短生命周期读取句柄取得，和正式句柄比较可发现 preflight/open 之间的交换。
pub(super) fn snapshot_path(path: &Path) -> Result<FileSnapshot, AttachmentIngressError> {
    let file = open_source(path)?;
    snapshot_file(&file)
}

/// 创建 staging 文件必须使用 `create_new`；UUID 即使发生碰撞也只能重试，绝不覆盖既有 run 数据。
pub(super) fn create_staging_file(path: &Path) -> std::io::Result<File> {
    OpenOptions::new().write(true).create_new(true).open(path)
}

/// 发布 staging 文件使用平台 no-replace 原语；目标名随机仍不能成为允许覆盖竞争者文件的理由。
pub(super) fn rename_no_replace(
    source: &Path,
    destination: &Path,
) -> Result<(), AttachmentIngressError> {
    rename_no_replace_impl(source, destination).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::StagingFailed,
            "staging_publish",
            error,
        )
    })
}

/// ingress root 由 Rust 创建，但每次批处理仍复核它未被换成 symlink/junction。
pub(super) fn prepare_ingress_root(run_root: &Path) -> Result<PathBuf, AttachmentIngressError> {
    if !run_root.is_absolute() {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::UnsupportedPath,
        ));
    }
    fs::create_dir_all(run_root).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::StagingFailed,
            "run_root_create",
            error,
        )
    })?;
    require_plain_directory(run_root)?;
    let run_root = fs::canonicalize(run_root).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::StagingFailed,
            "run_root_canonicalize",
            error,
        )
    })?;
    let ingress_root = run_root.join("attachment-ingress");
    match fs::create_dir(&ingress_root) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(error) => {
            return Err(map_io_error(
                AttachmentIngressErrorCode::StagingFailed,
                "ingress_root_create",
                error,
            ));
        }
    }
    require_plain_directory(&ingress_root)?;
    let canonical = fs::canonicalize(&ingress_root).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::StagingFailed,
            "ingress_root_canonicalize",
            error,
        )
    })?;
    if canonical != ingress_root {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::LinkNotAllowed,
        ));
    }
    Ok(canonical)
}

/// 后续 operation 用 canonical equality 验证 root，防止生命周期中目录被 junction 替换。
pub(super) fn verify_ingress_root(root: &Path) -> Result<(), AttachmentIngressError> {
    require_plain_directory(root)?;
    let canonical = fs::canonicalize(root).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::StagingFailed,
            "ingress_root_verify",
            error,
        )
    })?;
    if canonical != root {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::LinkNotAllowed,
        ));
    }
    Ok(())
}

/// 目录验证拒绝所有非目录、symlink 与 Windows reparse point。
fn require_plain_directory(path: &Path) -> Result<(), AttachmentIngressError> {
    let metadata = fs::symlink_metadata(path).map_err(|error| {
        map_io_error(
            AttachmentIngressErrorCode::StagingFailed,
            "owned_directory_metadata",
            error,
        )
    })?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::LinkNotAllowed,
        ));
    }
    Ok(())
}

/// Windows reparse attribute 必须先于普通 file/dir 分类，否则 junction 会伪装成目录。
fn is_reparse_point(metadata: &Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0000_0400;
        metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
    }
    #[cfg(not(windows))]
    {
        let _ = metadata;
        false
    }
}

#[cfg(windows)]
/// 拒绝 Win32 device、NT namespace、ADS 与保留设备名；这些 spelling 即使可打开也不是普通用户文件语义。
fn validate_platform_spelling(path: &Path) -> Result<(), AttachmentIngressError> {
    use std::path::{Prefix, PrefixComponent};

    let prefix = path.components().find_map(|component| match component {
        Component::Prefix(prefix) => Some(prefix),
        _ => None,
    });
    let allowed_prefix = prefix.is_some_and(|prefix: PrefixComponent<'_>| {
        matches!(prefix.kind(), Prefix::Disk(_) | Prefix::UNC(_, _))
    });
    if !allowed_prefix {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::UnsupportedPath,
        ));
    }
    for component in path.components() {
        let Component::Normal(name) = component else {
            continue;
        };
        let name = name.to_str().ok_or_else(|| {
            AttachmentIngressError::new(AttachmentIngressErrorCode::UnsupportedPath)
        })?;
        if name.contains(':') || is_reserved_windows_name(name) {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::UnsupportedPath,
            ));
        }
    }
    Ok(())
}

#[cfg(windows)]
/// Win32 会在扩展名和尾随点/空格归一化之前解析 DOS 设备名，因此按同一规则拒绝。
fn is_reserved_windows_name(name: &str) -> bool {
    let normalized = name.trim_end_matches([' ', '.']);
    let stem = normalized.split('.').next().unwrap_or_default();
    let upper = stem.to_ascii_uppercase();
    matches!(
        upper.as_str(),
        "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$"
    ) || upper
        .strip_prefix("COM")
        .or_else(|| upper.strip_prefix("LPT"))
        .is_some_and(|suffix| matches!(suffix, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9"))
}

#[cfg(not(windows))]
/// Unix 没有 Win32 namespace spelling；绝对路径与 link component 已由共享路径验证覆盖。
fn validate_platform_spelling(_path: &Path) -> Result<(), AttachmentIngressError> {
    Ok(())
}

#[cfg(windows)]
/// Windows 直接读取活动 handle 的 file index/link count/mtime，避免 std 路径 metadata 再次跟随链接。
fn snapshot_file_impl(file: &File) -> std::io::Result<FileSnapshot> {
    use std::os::windows::io::AsRawHandle;
    use windows::Win32::Foundation::HANDLE;
    use windows::Win32::Storage::FileSystem::{
        BY_HANDLE_FILE_INFORMATION, FILE_ATTRIBUTE_DEVICE, FILE_ATTRIBUTE_DIRECTORY,
        FILE_ATTRIBUTE_REPARSE_POINT, GetFileInformationByHandle,
    };

    let mut information = BY_HANDLE_FILE_INFORMATION::default();
    // 安全性：`file` 在同步调用期间持有有效 handle，windows-rs 保证输出结构的 ABI layout。
    unsafe { GetFileInformationByHandle(HANDLE(file.as_raw_handle()), &mut information) }
        .map_err(|error| std::io::Error::from_raw_os_error(error.code().0))?;
    let attributes = information.dwFileAttributes;
    Ok(FileSnapshot {
        identity: FileIdentity {
            volume: u64::from(information.dwVolumeSerialNumber),
            low: u64::from(information.nFileIndexLow),
            high: u64::from(information.nFileIndexHigh),
        },
        size: (u64::from(information.nFileSizeHigh) << 32) | u64::from(information.nFileSizeLow),
        modified_low: u64::from(information.ftLastWriteTime.dwLowDateTime),
        modified_high: i64::from(information.ftLastWriteTime.dwHighDateTime),
        links: u64::from(information.nNumberOfLinks),
        regular: attributes & (FILE_ATTRIBUTE_DIRECTORY.0 | FILE_ATTRIBUTE_DEVICE.0) == 0,
        reparse: attributes & FILE_ATTRIBUTE_REPARSE_POINT.0 != 0,
    })
}

#[cfg(unix)]
/// Unix 使用 fstat-backed MetadataExt；device/inode 与 nlink 均来自已打开 fd。
fn snapshot_file_impl(file: &File) -> std::io::Result<FileSnapshot> {
    use std::os::unix::fs::MetadataExt;

    let metadata = file.metadata()?;
    Ok(FileSnapshot {
        identity: FileIdentity {
            volume: metadata.dev(),
            low: metadata.ino(),
            high: 0,
        },
        size: metadata.len(),
        modified_low: u64::try_from(metadata.mtime_nsec()).unwrap_or(u64::MAX),
        modified_high: metadata.mtime(),
        links: metadata.nlink(),
        regular: metadata.is_file(),
        reparse: false,
    })
}

#[cfg(not(any(unix, windows)))]
/// 缺少可靠物理 identity 的平台失败关闭，不能用 size/mtime 冒充身份。
fn snapshot_file_impl(_file: &File) -> std::io::Result<FileSnapshot> {
    Err(std::io::Error::from(std::io::ErrorKind::Unsupported))
}

#[cfg(windows)]
/// Windows `MoveFileExW` 未设置 REPLACE_EXISTING，并启用 write-through，提供同卷原子 no-replace 发布。
fn rename_no_replace_impl(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;

    const MOVEFILE_WRITE_THROUGH: u32 = 0x0000_0008;
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
    // 安全性：两个 NUL 结尾 buffer 在同步调用期间存活；不设置 REPLACE_EXISTING 保证不覆盖。
    let status = unsafe {
        MoveFileExW(
            source.as_ptr(),
            destination.as_ptr(),
            MOVEFILE_WRITE_THROUGH,
        )
    };
    if status == 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(target_os = "linux")]
/// Linux 使用 `renameat2(RENAME_NOREPLACE)`，避免 exists-check 与 rename 之间的覆盖窗口。
fn rename_no_replace_impl(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::ffi::{CString, c_char};
    use std::os::unix::ffi::OsStrExt;

    const AT_FDCWD: i32 = -100;
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
    let source = CString::new(source.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    let destination = CString::new(destination.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    // 安全性：C strings 在同步调用期间存活；RENAME_NOREPLACE 禁止替换目标。
    let status = unsafe {
        renameat2(
            AT_FDCWD,
            source.as_ptr(),
            AT_FDCWD,
            destination.as_ptr(),
            RENAME_NOREPLACE,
        )
    };
    if status == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

#[cfg(target_vendor = "apple")]
/// Apple 平台使用 `renamex_np(RENAME_EXCL)` 保持与 Windows/Linux 相同的 no-replace 语义。
fn rename_no_replace_impl(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::ffi::{CString, c_char};
    use std::os::unix::ffi::OsStrExt;

    const RENAME_EXCL: u32 = 0x0000_0004;
    unsafe extern "C" {
        fn renamex_np(from: *const c_char, to: *const c_char, flags: u32) -> i32;
    }
    let source = CString::new(source.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    let destination = CString::new(destination.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    // 安全性：C strings 在同步调用期间存活；RENAME_EXCL 禁止替换目标。
    let status = unsafe { renamex_np(source.as_ptr(), destination.as_ptr(), RENAME_EXCL) };
    if status == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

#[cfg(all(unix, not(any(target_os = "linux", target_vendor = "apple"))))]
/// 未提供原子 no-replace 原语的平台失败关闭，不回退到可覆盖的 portable rename。
fn rename_no_replace_impl(_source: &Path, _destination: &Path) -> std::io::Result<()> {
    Err(std::io::Error::from(std::io::ErrorKind::Unsupported))
}

#[cfg(not(any(unix, windows)))]
/// 未知平台无法证明原子 no-replace，必须失败关闭。
fn rename_no_replace_impl(_source: &Path, _destination: &Path) -> std::io::Result<()> {
    Err(std::io::Error::from(std::io::ErrorKind::Unsupported))
}
