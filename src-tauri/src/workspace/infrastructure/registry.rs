// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::workspace::WorkspaceError;
use crate::workspace::domain::{EntryKind, FileMetadata, FileRevision, RelativePath};
use crate::workspace::infrastructure::tree::TreeSnapshotCache;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::{self, Metadata};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};
use std::time::{Instant, UNIX_EPOCH};
use uuid::Uuid;

#[cfg(windows)]
use std::ffi::c_void;
#[cfg(windows)]
use std::os::windows::ffi::OsStrExt;

/// 不透明 Workspace 身份保证绝对根路径只存在于原生状态，不能成为 IPC 能力。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct WorkspaceId(Uuid);

impl WorkspaceId {
    /// 创建随机 opaque identity，使删除后重加的同路径 Workspace 不会复用旧 UI 能力。
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }
}

impl Default for WorkspaceId {
    /// 默认构造复用显式构造的随机身份路径，避免出现可预测或不同语义的第二套 id。
    fn default() -> Self {
        Self::new()
    }
}

/// 公开 Workspace 摘要刻意省略 canonical 根路径，只保留可安全展示的不透明身份。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceInfo {
    pub id: WorkspaceId,
}

/// 受信 canonical 根与不透明 id 组成所有工作台 reader 共用的原生能力句柄。
#[derive(Debug, Clone)]
pub struct WorkspaceHandle {
    id: WorkspaceId,
    root: Arc<PathBuf>,
    root_identity: FileIdentity,
    tree_snapshots: Arc<TreeSnapshotCache>,
    mutation_recovery_required: Arc<AtomicBool>,
}

/// 物理身份防止有界读取仍使用 canonical 名称时，路径替换静默改变 Workspace 目标。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct FileIdentity {
    volume: u64,
    file: u64,
}

/// 已解析路径保存每个核验过的 component，使调用方能在 IO 前后重验同一物理链。
#[derive(Debug, Clone)]
pub(crate) struct ResolvedPath {
    pub(crate) path: PathBuf,
    components: Vec<(PathBuf, FileIdentity)>,
    final_identity: FileIdentity,
}

impl ResolvedPath {
    /// 快照只保存不可逆的原生物理身份，不暴露路径组件，也不允许调用方自行重建身份比较。
    pub(crate) fn identity(&self) -> FileIdentity {
        self.final_identity
    }
}

impl WorkspaceHandle {
    /// 只返回 opaque identity，绝对 root 始终留在原生 registry。
    pub fn id(&self) -> WorkspaceId {
        self.id
    }

    /// 不跟随 symlink/reparse 解析常规文件并复核 canonical containment，别名路径失败关闭。
    pub fn resolve_file(&self, relative_path: &str) -> Result<PathBuf, WorkspaceError> {
        Ok(self.resolve_guard(relative_path, Some(false))?.path)
    }

    /// 只解析 canonical root 内现有目录，避免调用方拼接绝对路径绕过 guard。
    pub fn resolve_directory(&self, relative_path: &str) -> Result<PathBuf, WorkspaceError> {
        Ok(self.resolve_guard(relative_path, Some(true))?.path)
    }

    /// 返回 root-contained metadata 且不公开绝对 spelling，为 CAS 提供统一 revision。
    pub fn metadata(
        &self,
        relative_path: &str,
        hash_limit: u64,
    ) -> Result<FileMetadata, WorkspaceError> {
        let resolved = self.resolve_guard(relative_path, None)?;
        self.verify_resolved(&resolved, None)?;
        let path = &resolved.path;
        let metadata =
            fs::symlink_metadata(path).map_err(|error| WorkspaceError::io("stat", error))?;
        let result = metadata_for_path(path, &metadata, hash_limit)?;
        self.verify_resolved(&resolved, None)?;
        Ok(result)
    }

    /// 将 Git pathspec 限制为普通 root-relative 名称，拒绝 magic、绝对路径和 traversal。
    pub(crate) fn validate_git_path(&self, relative_path: &str) -> Result<(), WorkspaceError> {
        if relative_path.is_empty() {
            return Err(WorkspaceError::InvalidRelativePath);
        }
        validate_relative_path(relative_path)?;
        if relative_path
            .bytes()
            .any(|byte| matches!(byte, b'*' | b'?' | b'[' | b']' | b':'))
        {
            return Err(WorkspaceError::InvalidRelativePath);
        }
        Ok(())
    }

    /// 仅向 crate 内原生 adapter 提供 registry 选定的 canonical cwd，不扩大到 IPC。
    pub(crate) fn root_path(&self) -> &Path {
        self.root.as_path()
    }

    /// 每个 Workspace identity 独占有界目录快照，分页复用不会跨根目录或 remove/re-add 泄漏。
    pub(crate) fn tree_snapshots(&self) -> &TreeSnapshotCache {
        self.tree_snapshots.as_ref()
    }

    /// 原生写入口在任何副作用前检查 Workspace 级恢复门禁；读取仍可继续用于人工核对现场。
    pub(crate) fn ensure_mutation_available(&self) -> Result<(), WorkspaceError> {
        if self.mutation_recovery_required.load(Ordering::Acquire) {
            return Err(WorkspaceError::RecoveryRequired);
        }
        Ok(())
    }

    /// 不确定事务一旦出现就以 release 顺序永久锁存到当前 handle；仅重新 admission 新 Workspace 身份可清除。
    pub(crate) fn mark_mutation_recovery_required(&self) {
        self.mutation_recovery_required
            .store(true, Ordering::Release);
    }

    /// 为需要前后复核的 reader 建立 path guard，集中保存 containment 与物理证据。
    pub(crate) fn resolve_guard(
        &self,
        relative_path: &str,
        directory: Option<bool>,
    ) -> Result<ResolvedPath, WorkspaceError> {
        validate_relative_path(relative_path)?;
        self.verify_root_identity()?;
        let mut current = self.root.as_ref().clone();
        let mut components = Vec::new();
        for component in Path::new(relative_path).components() {
            let Component::Normal(name) = component else {
                continue;
            };
            current.push(name);
            let metadata =
                fs::symlink_metadata(&current).map_err(|error| map_path_error(error, "stat"))?;
            if is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
                return Err(WorkspaceError::LinkNotAllowed);
            }
            components.push((current.clone(), physical_identity(&current)?));
        }
        let canonical =
            fs::canonicalize(&current).map_err(|error| map_path_error(error, "resolve"))?;
        if !path_is_within(self.root_path(), &canonical) {
            return Err(WorkspaceError::OutsideWorkspace);
        }
        let metadata =
            fs::symlink_metadata(&canonical).map_err(|error| map_path_error(error, "stat"))?;
        if is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
            return Err(WorkspaceError::LinkNotAllowed);
        }
        if let Some(directory) = directory {
            if directory && !metadata.is_dir() {
                return Err(WorkspaceError::NotDirectory);
            }
            if !directory && !metadata.is_file() {
                return Err(WorkspaceError::NotFile);
            }
        }
        if metadata.is_file() && hard_link_count(&canonical, &metadata)? > 1 {
            return Err(WorkspaceError::LinkNotAllowed);
        }
        let final_identity = physical_identity(&canonical)?;
        let resolved = ResolvedPath {
            path: canonical,
            components,
            final_identity,
        };
        self.verify_resolved(&resolved, directory)?;
        Ok(resolved)
    }

    /// 解析现有 parent 并返回尚未 admission 的 child；原子 IO 前必须再次复核 parent。
    pub(crate) fn resolve_parent(
        &self,
        relative_path: &str,
    ) -> Result<(ResolvedPath, PathBuf), WorkspaceError> {
        validate_relative_path(relative_path)?;
        let path = Path::new(relative_path);
        let Some(name) = path.file_name() else {
            return Err(WorkspaceError::InvalidRelativePath);
        };
        let parent = path.parent().unwrap_or_else(|| Path::new(""));
        let parent_relative = parent.to_str().ok_or(WorkspaceError::InvalidRelativePath)?;
        let guard = self.resolve_guard(parent_relative, Some(true))?;
        let child = guard.path.join(name);
        if child == self.root_path() {
            return Err(WorkspaceError::InvalidRelativePath);
        }
        Ok((guard, child))
    }

    /// 重验 root 与所有组件，使 junction、symlink 或 rename 竞争失败关闭而不越界。
    pub(crate) fn verify_resolved(
        &self,
        resolved: &ResolvedPath,
        directory: Option<bool>,
    ) -> Result<(), WorkspaceError> {
        self.verify_root_identity()?;
        for (path, expected) in &resolved.components {
            let metadata =
                fs::symlink_metadata(path).map_err(|error| map_path_error(error, "recheck"))?;
            if is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
                return Err(WorkspaceError::LinkNotAllowed);
            }
            if physical_identity(path)? != *expected {
                return Err(WorkspaceError::PathChanged);
            }
        }
        let canonical =
            fs::canonicalize(&resolved.path).map_err(|error| map_path_error(error, "recheck"))?;
        if canonical != resolved.path || !path_is_within(self.root_path(), &canonical) {
            return Err(WorkspaceError::PathChanged);
        }
        let metadata =
            fs::symlink_metadata(&canonical).map_err(|error| map_path_error(error, "recheck"))?;
        if is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
            return Err(WorkspaceError::LinkNotAllowed);
        }
        if physical_identity(&canonical)? != resolved.final_identity {
            return Err(WorkspaceError::PathChanged);
        }
        if let Some(directory) = directory
            && directory != metadata.is_dir()
        {
            return Err(if directory {
                WorkspaceError::NotDirectory
            } else {
                WorkspaceError::NotFile
            });
        }
        if metadata.is_file() && hard_link_count(&canonical, &metadata)? > 1 {
            return Err(WorkspaceError::LinkNotAllowed);
        }
        self.verify_root_identity()
    }

    /// 在已 admission parent 下核验 child；调用方包围整次枚举复核 parent，避免 Windows 二次复杂度。
    pub(crate) fn verify_enumerated_child_file(
        &self,
        parent: &ResolvedPath,
        child: &Path,
        observed: &Metadata,
    ) -> Result<(), WorkspaceError> {
        if child.parent() != Some(parent.path.as_path())
            || child.file_name().is_none()
            || !observed.is_file()
            || observed.file_type().is_symlink()
            || is_reparse_point(observed)
        {
            return Err(WorkspaceError::LinkNotAllowed);
        }
        let observed_identity = physical_identity(child)?;
        let canonical =
            fs::canonicalize(child).map_err(|error| map_path_error(error, "resolve"))?;
        if canonical != child || !path_is_within(self.root_path(), &canonical) {
            return Err(WorkspaceError::PathChanged);
        }
        let current =
            fs::symlink_metadata(&canonical).map_err(|error| map_path_error(error, "recheck"))?;
        if !current.is_file()
            || current.file_type().is_symlink()
            || is_reparse_point(&current)
            || physical_identity(&canonical)? != observed_identity
            || hard_link_count(&canonical, &current)? > 1
        {
            return Err(WorkspaceError::LinkNotAllowed);
        }
        Ok(())
    }

    /// 在子节点读取完成后仍复核 canonical 根对应最初准入的物理 Workspace，路径替换必须失败关闭。
    fn verify_root_identity(&self) -> Result<(), WorkspaceError> {
        let metadata = fs::symlink_metadata(self.root_path())
            .map_err(|error| map_path_error(error, "root"))?;
        if is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
            return Err(WorkspaceError::LinkNotAllowed);
        }
        if !metadata.is_dir() || physical_identity(self.root_path())? != self.root_identity {
            return Err(WorkspaceError::PathChanged);
        }
        Ok(())
    }
}

/// Registry 独占映射所有权；删除 Workspace 会让后续 lookup 失效，但已持有 handle 的有界读取可完成。
#[derive(Debug, Clone, Default)]
pub struct WorkspaceRegistry {
    workspaces: Arc<RwLock<HashMap<WorkspaceId, WorkspaceHandle>>>,
}

impl WorkspaceRegistry {
    /// admission 时只 canonicalize 一次，后续 reader 共享同一 root 且不再信任调用方 spelling。
    pub fn register(&self, root: impl AsRef<Path>) -> Result<WorkspaceInfo, WorkspaceError> {
        let raw_root = absolute_path(root.as_ref())?;
        reject_link_components(&raw_root)?;
        let raw_metadata =
            fs::symlink_metadata(&raw_root).map_err(|error| map_path_error(error, "root"))?;
        if is_reparse_point(&raw_metadata) || raw_metadata.file_type().is_symlink() {
            return Err(WorkspaceError::InvalidRoot);
        }
        let path = fs::canonicalize(&raw_root).map_err(|error| map_path_error(error, "root"))?;
        let metadata =
            fs::symlink_metadata(&path).map_err(|error| map_path_error(error, "root"))?;
        if !metadata.is_dir() || is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
            return Err(WorkspaceError::InvalidRoot);
        }
        let root_identity = physical_identity(&path)?;
        let id = WorkspaceId::new();
        let handle = WorkspaceHandle {
            id,
            root: Arc::new(path),
            root_identity,
            tree_snapshots: Arc::new(TreeSnapshotCache::default()),
            mutation_recovery_required: Arc::new(AtomicBool::new(false)),
        };
        self.workspaces
            .write()
            .map_err(|_| WorkspaceError::Io {
                operation: "registry",
                kind: std::io::ErrorKind::Other.into(),
            })?
            .insert(id, handle);
        Ok(WorkspaceInfo { id })
    }

    /// 返回操作期 canonical root 不可变的 handle，避免相同目录 remove/re-add 竞争。
    pub fn get(&self, id: WorkspaceId) -> Result<WorkspaceHandle, WorkspaceError> {
        self.workspaces
            .read()
            .map_err(|_| WorkspaceError::Io {
                operation: "registry",
                kind: std::io::ErrorKind::Other.into(),
            })?
            .get(&id)
            .cloned()
            .ok_or(WorkspaceError::WorkspaceNotFound)
    }

    /// 只删除 opaque mapping；已持有 handle 的有界操作可完成且不能改变 registry 新状态。
    pub fn remove(&self, id: WorkspaceId) -> Result<bool, WorkspaceError> {
        Ok(self
            .workspaces
            .write()
            .map_err(|_| WorkspaceError::Io {
                operation: "registry",
                kind: std::io::ErrorKind::Other.into(),
            })?
            .remove(&id)
            .is_some())
    }

    /// 只列出 opaque identity 与安全信息，项目侧栏不能得到绝对 root path。
    pub fn list(&self) -> Result<Vec<WorkspaceInfo>, WorkspaceError> {
        let mut result = self
            .workspaces
            .read()
            .map_err(|_| WorkspaceError::Io {
                operation: "registry",
                kind: std::io::ErrorKind::Other.into(),
            })?
            .keys()
            .copied()
            .map(|id| WorkspaceInfo { id })
            .collect::<Vec<_>>();
        result.sort_by_key(|info| info.id.0);
        Ok(result)
    }
}

/// 根目录准入先检查原生路径原貌，防止 canonicalize 抹去 symlink 或 junction 证据。
fn absolute_path(path: &Path) -> Result<PathBuf, WorkspaceError> {
    if path.is_absolute() {
        Ok(path.to_path_buf())
    } else {
        Ok(std::env::current_dir()
            .map_err(|error| map_path_error(error, "root"))?
            .join(path))
    }
}

/// 在 canonicalization 隐藏别名之前拒绝 link/reparse 组件，守住 Workspace 信任边界。
pub(crate) fn reject_link_components(path: &Path) -> Result<(), WorkspaceError> {
    let mut current = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Prefix(_) | Component::RootDir => {
                current.push(component.as_os_str());
            }
            Component::CurDir => {}
            Component::ParentDir => {
                current.pop();
            }
            Component::Normal(name) => {
                current.push(name);
                let metadata = fs::symlink_metadata(&current)
                    .map_err(|error| map_path_error(error, "root"))?;
                if is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
                    return Err(WorkspaceError::InvalidRoot);
                }
            }
        }
    }
    Ok(())
}

/// 返回稳定物理文件 key；平台缺少强身份 primitive 时失败关闭，不以 size/time 冒充身份。
fn physical_identity(path: &Path) -> Result<FileIdentity, WorkspaceError> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let metadata = fs::metadata(path).map_err(|error| WorkspaceError::io("identity", error))?;
        return Ok(FileIdentity {
            volume: metadata.dev(),
            file: metadata.ino(),
        });
    }
    #[cfg(windows)]
    {
        query_windows_file_information(path)
            .map(|(identity, _)| identity)
            .map_err(|error| WorkspaceError::io("identity", error))
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = path;
        return Err(WorkspaceError::Io {
            operation: "identity",
            kind: std::io::ErrorKind::Unsupported.into(),
        });
    }
}

/// hard link 必须拒绝，因为 Workspace 内路径即使没有 symlink/reparse point，也可能别名到外部物理文件。
fn hard_link_count(path: &Path, metadata: &Metadata) -> Result<u64, WorkspaceError> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let _ = path;
        return Ok(metadata.nlink());
    }
    #[cfg(windows)]
    {
        let _ = metadata;
        query_windows_file_information(path)
            .map(|(_, links)| u64::from(links))
            .map_err(|error| WorkspaceError::io("link_count", error))
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (path, metadata);
        Err(WorkspaceError::Io {
            operation: "link_count",
            kind: std::io::ErrorKind::Unsupported.into(),
        })
    }
}

/// 显式编码 digest，避免依赖 Rust 1.88 下 sha2 hybrid-array 不支持的 LowerHex。
pub(crate) fn hex_lower(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut result = String::with_capacity(bytes.len().saturating_mul(2));
    for byte in bytes {
        result.push(HEX[usize::from(byte >> 4)] as char);
        result.push(HEX[usize::from(byte & 0x0f)] as char);
    }
    result
}

#[cfg(windows)]
const FILE_READ_ATTRIBUTES: u32 = 0x0000_0080;
#[cfg(windows)]
const FILE_SHARE_READ: u32 = 0x0000_0001;
#[cfg(windows)]
const FILE_SHARE_WRITE: u32 = 0x0000_0002;
#[cfg(windows)]
const FILE_SHARE_DELETE: u32 = 0x0000_0004;
#[cfg(windows)]
const OPEN_EXISTING: u32 = 3;
#[cfg(windows)]
const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
#[cfg(windows)]
const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
#[cfg(windows)]
const INVALID_HANDLE_VALUE: *mut c_void = -1_isize as *mut c_void;

#[cfg(windows)]
#[repr(C)]
struct ByHandleFileInformation {
    file_attributes: u32,
    creation_time_low: u32,
    creation_time_high: u32,
    last_access_time_low: u32,
    last_access_time_high: u32,
    last_write_time_low: u32,
    last_write_time_high: u32,
    volume_serial_number: u32,
    file_size_high: u32,
    file_size_low: u32,
    number_of_links: u32,
    file_index_high: u32,
    file_index_low: u32,
}

#[cfg(windows)]
#[link(name = "kernel32")]
unsafe extern "system" {
    fn CloseHandle(handle: *mut c_void) -> i32;
    fn CreateFileW(
        name: *const u16,
        desired_access: u32,
        share_mode: u32,
        security_attributes: *const c_void,
        creation_disposition: u32,
        flags_and_attributes: u32,
        template_file: *mut c_void,
    ) -> *mut c_void;
    fn GetFileInformationByHandle(
        handle: *mut c_void,
        information: *mut ByHandleFileInformation,
    ) -> i32;
}

#[cfg(windows)]
/// 以 reparse-point 语义打开文件并读取 Windows volume/file index 与 link count，
/// 防止路径替换被静默跟随。
fn query_windows_file_information(path: &Path) -> std::io::Result<(FileIdentity, u32)> {
    let wide = path
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    // 安全性：`wide` 在调用期间保持存活且以 NUL 结尾；其余指针为空只表示不提供可选参数。
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
        return Err(std::io::Error::last_os_error());
    }
    let mut information = std::mem::MaybeUninit::<ByHandleFileInformation>::uninit();
    // 安全性：有效 handle 由上一步创建，Windows 只在返回成功时初始化完整输出结构。
    let ok = unsafe { GetFileInformationByHandle(handle, information.as_mut_ptr()) } != 0;
    let error = (!ok).then(std::io::Error::last_os_error);
    // 安全性：handle 只在本函数持有，读取完成后无论成功或失败都只关闭一次。
    unsafe {
        let _ = CloseHandle(handle);
    }
    if let Some(error) = error {
        return Err(error);
    }
    // 安全性：只有 `GetFileInformationByHandle` 明确返回成功才会到达这里，结构已完全初始化。
    let information = unsafe { information.assume_init() };
    Ok((
        FileIdentity {
            volume: u64::from(information.volume_serial_number),
            file: (u64::from(information.file_index_high) << 32)
                | u64::from(information.file_index_low),
        },
        information.number_of_links,
    ))
}

/// 在 IO 前拒绝 absolute、drive prefix 与歧义路径，仅空字符串可表示 Workspace root。
pub(crate) fn validate_relative_path(relative_path: &str) -> Result<(), WorkspaceError> {
    RelativePath::parse(relative_path.to_owned()).map(|_| ())
}

/// 把 metadata 转成安全 revision，只 hash 有界常规文件，防止大 binary 进入内存与 IPC。
pub(crate) fn metadata_for_path(
    path: &Path,
    metadata: &Metadata,
    hash_limit: u64,
) -> Result<FileMetadata, WorkspaceError> {
    metadata_for_path_with_deadline(path, metadata, hash_limit, None)
}

/// 在可选绝对 deadline 内生成 revision，使 polling scan 不突破停止预算。
pub(crate) fn metadata_for_path_with_deadline(
    path: &Path,
    metadata: &Metadata,
    hash_limit: u64,
    deadline: Option<Instant>,
) -> Result<FileMetadata, WorkspaceError> {
    let kind = entry_kind(metadata);
    if kind == EntryKind::File && hard_link_count(path, metadata)? > 1 {
        return Err(WorkspaceError::LinkNotAllowed);
    }
    let identity_before = if matches!(kind, EntryKind::Symlink | EntryKind::ReparsePoint) {
        None
    } else {
        Some(physical_identity(path)?)
    };
    let sha256 = if kind == EntryKind::File && metadata.len() <= hash_limit {
        Some(hash_file_until(path, hash_limit, deadline)?)
    } else {
        None
    };
    let modified_unix_millis = metadata.modified().ok().and_then(|modified| {
        modified
            .duration_since(UNIX_EPOCH)
            .ok()
            .map(|duration| duration.as_millis())
    });
    let after = fs::symlink_metadata(path).map_err(|error| WorkspaceError::io("recheck", error))?;
    let identity_changed = identity_before.is_some_and(|identity| {
        physical_identity(path)
            .map(|current| current != identity)
            .unwrap_or(true)
    });
    let modified_after_unix_millis = after.modified().ok().and_then(|modified| {
        modified
            .duration_since(UNIX_EPOCH)
            .ok()
            .map(|duration| duration.as_millis())
    });
    if entry_kind(&after) != kind
        || after.len() != metadata.len()
        || modified_after_unix_millis != modified_unix_millis
        || identity_changed
    {
        return Err(WorkspaceError::PathChanged);
    }
    Ok(FileMetadata {
        kind,
        size: metadata.len(),
        modified_unix_millis,
        revision: FileRevision::new(kind, metadata.len(), modified_unix_millis, sha256)?,
    })
}

/// 最多 hash `hash_limit + 1` 字节；即使调用方未给 deadline，首次 stat 后增长的文件
/// 也不能把 metadata 请求变成无界读取。
pub(crate) fn hash_file_until(
    path: &Path,
    hash_limit: u64,
    deadline: Option<Instant>,
) -> Result<String, WorkspaceError> {
    // 多读一个字节是文件超出调用方预算的有界证据；checked arithmetic 防止最大 u64 上限回绕。
    let mut remaining = hash_limit
        .checked_add(1)
        .ok_or(WorkspaceError::FileTooLarge)?;
    let mut file = fs::File::open(path).map_err(|error| WorkspaceError::io("hash", error))?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 16 * 1024];
    loop {
        if deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            return Err(WorkspaceError::ScanDeadlineExceeded);
        }
        let read_len = usize::try_from(remaining)
            .unwrap_or(buffer.len())
            .min(buffer.len());
        if read_len == 0 {
            return Err(WorkspaceError::FileTooLarge);
        }
        let count = std::io::Read::read(&mut file, &mut buffer[..read_len])
            .map_err(|error| WorkspaceError::io("hash", error))?;
        if count == 0 {
            break;
        }
        digest.update(&buffer[..count]);
        remaining = remaining
            .checked_sub(u64::try_from(count).map_err(|_| WorkspaceError::FileTooLarge)?)
            .ok_or(WorkspaceError::FileTooLarge)?;
        if remaining == 0 {
            return Err(WorkspaceError::FileTooLarge);
        }
    }
    Ok(hex_lower(&digest.finalize()))
}

/// 先于 symlink 分类 reparse point，避免 Windows junction 被误判为普通目录。
pub(crate) fn entry_kind(metadata: &Metadata) -> EntryKind {
    if is_reparse_point(metadata) {
        return EntryKind::ReparsePoint;
    }
    let file_type = metadata.file_type();
    if file_type.is_symlink() {
        EntryKind::Symlink
    } else if metadata.is_file() {
        EntryKind::File
    } else if metadata.is_dir() {
        EntryKind::Directory
    } else {
        EntryKind::Other
    }
}

/// Windows 读取 reparse metadata，其它平台以 symlink bit 作为等价边界。
pub(crate) fn is_reparse_point(_metadata: &Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0400;
        _metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
    }
    #[cfg(not(windows))]
    {
        let _ = _metadata;
        false
    }
}

/// canonical containment 比较 component 而非字符串前缀，Windows 才执行大小写折叠。
pub(crate) fn path_is_within(root: &Path, candidate: &Path) -> bool {
    let root_components = root.components().collect::<Vec<_>>();
    let candidate_components = candidate.components().collect::<Vec<_>>();
    if candidate_components.len() < root_components.len() {
        return false;
    }
    root_components
        .iter()
        .zip(candidate_components.iter())
        .all(|(root, candidate)| {
            #[cfg(windows)]
            {
                root.as_os_str()
                    .to_string_lossy()
                    .eq_ignore_ascii_case(&candidate.as_os_str().to_string_lossy())
            }
            #[cfg(not(windows))]
            {
                root == candidate
            }
        })
}

/// 把 OS 路径失败映射为稳定类别，不在 error payload 嵌入绝对路径或用户文件名。
pub(crate) fn map_path_error(error: std::io::Error, operation: &'static str) -> WorkspaceError {
    match error.kind() {
        std::io::ErrorKind::NotFound => WorkspaceError::PathNotFound,
        std::io::ErrorKind::PermissionDenied => WorkspaceError::Io {
            operation,
            kind: std::io::ErrorKind::PermissionDenied.into(),
        },
        _ => WorkspaceError::io(operation, error),
    }
}
