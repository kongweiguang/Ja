// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime 恢复 marker 的原子事务存储。

use super::path_policy::is_reparse_point;
use crate::app_runtime::{
    ManualRecoveryConfirmation, ManualRecoveryReason, RuntimeCommandError, RuntimeRecoveryState,
};
use serde::{Deserialize, Serialize};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use uuid::Uuid;

const RECOVERY_FILE_NAME: &str = "ja-runtime-recovery.json";
pub(crate) const RECOVERY_ACK_FILE_NAME: &str = "ja-runtime-recovery-ack.json";
pub(crate) const RECOVERY_TEMP_PREFIX: &str = "ja-runtime-recovery.json.tmp-";
const RECOVERY_ACK_TEMP_PREFIX: &str = "ja-runtime-recovery-ack.json.tmp-";
const RECOVERY_SCHEMA_VERSION: u64 = 2;
pub(crate) const MAX_RECOVERY_BYTES: u64 = 4096;
static NEXT_RECOVERY_TEMP_ID: AtomicU64 = AtomicU64::new(1);

/// 强制退出前无法确认清理完成时写入的严格磁盘 marker；它只用于诊断和显式恢复门禁，不承担 owner 或自动回收进程协议。
#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub(crate) struct RecoveryMarker {
    #[serde(rename = "schemaVersion")]
    schema_version: u64,
    status: RecoveryMarkerStatus,
    #[serde(rename = "recoveryId")]
    pub(crate) recovery_id: String,
    pub(crate) revision: u64,
    generation: u64,
}

#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum RecoveryMarkerStatus {
    ManualRecoveryRequired,
}

/// 独立的确认凭据用于在原子删除 pending marker 前记录显式用户操作，使失败重试仍可验证同一次确认。
#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct RecoveryAcknowledgement {
    #[serde(rename = "schemaVersion")]
    schema_version: u64,
    status: RecoveryAcknowledgementStatus,
    #[serde(rename = "recoveryId")]
    recovery_id: String,
    revision: u64,
    reason: RecoveryReasonRecord,
}

#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum RecoveryAcknowledgementStatus {
    ManualRecoveryAckPending,
}

/// 持久化层拥有自己的 wire 枚举，domain 不携带 serde 或磁盘 schema 属性。
#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "PascalCase")]
enum RecoveryReasonRecord {
    SystemRestarted,
    ExternallyCleaned,
}

/// 返回受信任应用私有 runtime 目录下的固定 marker 路径，避免调用方自行拼接恢复位置。
pub(crate) fn recovery_marker_path(run_dir: &Path) -> PathBuf {
    run_dir.join(RECOVERY_FILE_NAME)
}

/// 读取原生恢复门禁但不向 WebView 暴露路径、进程 ID 或 marker 内容；格式错误或中断的事务一律 fail-closed，并保持不可确认状态。
pub(crate) fn recovery_state(run_dir: &Path) -> RuntimeRecoveryState {
    if ensure_private_run_dir(run_dir).is_err() {
        return RuntimeRecoveryState {
            required: true,
            acknowledgeable: false,
            recovery_id: None,
            revision: None,
        };
    }
    let entries = match fs::read_dir(run_dir) {
        Ok(entries) => entries,
        Err(_) => {
            return RuntimeRecoveryState {
                required: true,
                acknowledgeable: false,
                recovery_id: None,
                revision: None,
            };
        }
    };
    for entry in entries {
        let entry = match entry {
            Ok(entry) => entry,
            Err(_) => {
                return RuntimeRecoveryState {
                    required: true,
                    acknowledgeable: false,
                    recovery_id: None,
                    revision: None,
                };
            }
        };
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.starts_with(RECOVERY_TEMP_PREFIX) || name.starts_with(RECOVERY_ACK_TEMP_PREFIX) {
            return RuntimeRecoveryState {
                required: true,
                acknowledgeable: false,
                recovery_id: None,
                revision: None,
            };
        }
    }
    let marker = read_recovery_marker(&recovery_marker_path(run_dir));
    let acknowledgement = read_recovery_ack(&run_dir.join(RECOVERY_ACK_FILE_NAME));
    match (marker, acknowledgement) {
        (Ok(Some(marker)), Ok(None)) => recovery_projection(Some(&marker), true),
        (Ok(None), Ok(Some(ack))) => recovery_projection(Some(&ack), true),
        (Ok(Some(marker)), Ok(Some(ack)))
            if marker.recovery_id == ack.recovery_id && marker.revision == ack.revision =>
        {
            recovery_projection(Some(&marker), true)
        }
        (Ok(None), Ok(None)) => RuntimeRecoveryState {
            required: false,
            acknowledgeable: false,
            recovery_id: None,
            revision: None,
        },
        _ => RuntimeRecoveryState {
            required: true,
            acknowledgeable: false,
            recovery_id: None,
            revision: None,
        },
    }
}

/// 启动新 sidecar 前拒绝任何 marker、确认 tombstone 或临时残留；只有显式强类型确认才能解除门禁。
pub(crate) fn ensure_recovery_clear(run_dir: &Path) -> Result<(), RuntimeCommandError> {
    if recovery_state(run_dir).required {
        Err(RuntimeCommandError::recovery_required())
    } else {
        Ok(())
    }
}

/// 通过 serde 的重复字段、未知字段和类型检查解析版本化 marker，并限制类 UUID identity，防止歧义磁盘数据解除启动门禁。
pub(crate) fn parse_recovery_marker(bytes: &[u8]) -> Result<RecoveryMarker, RuntimeCommandError> {
    if bytes.len() as u64 > MAX_RECOVERY_BYTES {
        return Err(RuntimeCommandError::recovery_required());
    }
    let marker: RecoveryMarker =
        serde_json::from_slice(bytes).map_err(|_| RuntimeCommandError::recovery_required())?;
    if marker.schema_version != RECOVERY_SCHEMA_VERSION
        || marker.revision == 0
        || Uuid::parse_str(&marker.recovery_id).is_err()
    {
        return Err(RuntimeCommandError::recovery_required());
    }
    Ok(marker)
}

/// 使用与 pending marker 相同的严格 schema 和 identity 约束解析短生命周期确认 tombstone。
fn parse_recovery_ack(bytes: &[u8]) -> Result<RecoveryAcknowledgement, RuntimeCommandError> {
    if bytes.len() as u64 > MAX_RECOVERY_BYTES {
        return Err(RuntimeCommandError::recovery_required());
    }
    let acknowledgement: RecoveryAcknowledgement =
        serde_json::from_slice(bytes).map_err(|_| RuntimeCommandError::recovery_required())?;
    if acknowledgement.schema_version != RECOVERY_SCHEMA_VERSION
        || Uuid::parse_str(&acknowledgement.recovery_id).is_err()
        || acknowledgement.revision == 0
    {
        return Err(RuntimeCommandError::recovery_required());
    }
    Ok(acknowledgement)
}

/// 读取大小受限的普通 marker 文件；symlink、reparse 或权限错误统一收敛为 fail-closed 解析错误。
fn read_recovery_marker(path: &Path) -> Result<Option<RecoveryMarker>, RuntimeCommandError> {
    read_recovery_bytes(path)
        .and_then(|bytes| bytes.map_or(Ok(None), |bytes| parse_recovery_marker(&bytes).map(Some)))
}

/// 使用相同路径策略读取大小受限的普通事务 tombstone，确保两类恢复证据遵守同一安全边界。
fn read_recovery_ack(path: &Path) -> Result<Option<RecoveryAcknowledgement>, RuntimeCommandError> {
    read_recovery_bytes(path)
        .and_then(|bytes| bytes.map_or(Ok(None), |bytes| parse_recovery_ack(&bytes).map(Some)))
}

/// 读取已知恢复文件且不跟随 reparse 间接路径，避免受信任目录被重定向。
fn read_recovery_bytes(path: &Path) -> Result<Option<Vec<u8>>, RuntimeCommandError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(RuntimeCommandError::recovery_required()),
    };
    if metadata.file_type().is_symlink() || is_reparse_point(&metadata) || !metadata.is_file() {
        return Err(RuntimeCommandError::recovery_required());
    }
    if metadata.len() > MAX_RECOVERY_BYTES {
        return Err(RuntimeCommandError::recovery_required());
    }
    fs::read(path)
        .map(Some)
        .map_err(|_| RuntimeCommandError::recovery_required())
}

/// 投影有效 marker 或与之匹配的事务 tombstone，只保留显式陈旧性校验确认所需的 identity。
fn recovery_projection(
    marker: Option<&dyn RecoveryMarkerLike>,
    acknowledgeable: bool,
) -> RuntimeRecoveryState {
    let (recovery_id, revision) = marker
        .map(|marker| {
            (
                Some(marker.recovery_id().to_owned()),
                Some(marker.revision()),
            )
        })
        .unwrap_or((None, None));
    RuntimeRecoveryState {
        required: true,
        acknowledgeable,
        recovery_id,
        revision,
    }
}

trait RecoveryMarkerLike {
    /// 只暴露恢复记录的 CAS identity，marker 与 tombstone 才能复用同一比较逻辑而不泄漏文件形态。
    fn recovery_id(&self) -> &str;

    /// revision 与 identity 共同构成确认条件，禁止仅凭文件存在性清除较新的恢复记录。
    fn revision(&self) -> u64;
}

impl RecoveryMarkerLike for RecoveryMarker {
    /// marker 直接返回持久化 identity；借用避免在恢复判断中制造可能脱离 revision 的副本。
    fn recovery_id(&self) -> &str {
        &self.recovery_id
    }

    /// marker revision 是当前待确认代际，必须与 acknowledgement 做精确 CAS 比较。
    fn revision(&self) -> u64 {
        self.revision
    }
}

impl RecoveryMarkerLike for RecoveryAcknowledgement {
    /// tombstone 暴露同一 identity 接口，使删除中断后的重试仍走统一 CAS 校验而非分叉清理逻辑。
    fn recovery_id(&self) -> &str {
        &self.recovery_id
    }

    /// tombstone 保留确认时 revision，重试只能清理由同一代际产生的 marker。
    fn revision(&self) -> u64 {
        self.revision
    }
}

/// 先在同目录 tombstone 中记录强类型确认，再删除 tombstone 与 marker；删除失败时保留 tombstone，使相同确认可无猜测地安全重试。
pub(crate) fn acknowledge_manual_recovery(
    run_dir: impl AsRef<Path>,
    confirmation: &ManualRecoveryConfirmation,
) -> Result<(), RuntimeCommandError> {
    let run_dir = run_dir.as_ref();
    ensure_private_run_dir(run_dir).map_err(|_| RuntimeCommandError::recovery_required())?;
    let marker = read_recovery_marker(&recovery_marker_path(run_dir))?;
    let ack_path = run_dir.join(RECOVERY_ACK_FILE_NAME);
    let pending_ack = read_recovery_ack(&ack_path)?;
    let (current_id, current_revision) = marker
        .as_ref()
        .map(|value| (value.recovery_id.as_str(), value.revision))
        .or_else(|| {
            pending_ack
                .as_ref()
                .map(|value| (value.recovery_id.as_str(), value.revision))
        })
        .ok_or_else(RuntimeCommandError::recovery_required)?;
    if current_id != confirmation.recovery_id || current_revision != confirmation.revision {
        return Err(RuntimeCommandError::recovery_stale());
    }
    if let Some(pending_ack) = pending_ack.as_ref()
        && (pending_ack.recovery_id != confirmation.recovery_id
            || pending_ack.revision != confirmation.revision)
    {
        return Err(RuntimeCommandError::recovery_stale());
    }
    let acknowledgement = RecoveryAcknowledgement {
        schema_version: RECOVERY_SCHEMA_VERSION,
        status: RecoveryAcknowledgementStatus::ManualRecoveryAckPending,
        recovery_id: confirmation.recovery_id.clone(),
        revision: confirmation.revision,
        reason: match confirmation.reason {
            ManualRecoveryReason::SystemRestarted => RecoveryReasonRecord::SystemRestarted,
            ManualRecoveryReason::ExternallyCleaned => RecoveryReasonRecord::ExternallyCleaned,
        },
    };
    let bytes = serde_json::to_vec(&acknowledgement)
        .map_err(|_| RuntimeCommandError::recovery_required())?;
    atomic_write_file(&ack_path, &bytes).map_err(|_| RuntimeCommandError::recovery_required())?;
    remove_file_synced(&recovery_marker_path(run_dir))
        .map_err(|_| RuntimeCommandError::recovery_required())?;
    remove_file_synced(&ack_path).map_err(|_| RuntimeCommandError::recovery_required())?;
    Ok(())
}

/// 使用新 identity 写入已脱敏的 pending marker；失败时有意保留临时残留，使后续启动继续被门禁阻断。
pub(crate) fn persist_recovery_record(
    path: &Path,
    attempt_id: u64,
    generation: u64,
) -> io::Result<()> {
    let marker = RecoveryMarker {
        schema_version: RECOVERY_SCHEMA_VERSION,
        status: RecoveryMarkerStatus::ManualRecoveryRequired,
        recovery_id: Uuid::new_v4().to_string(),
        revision: attempt_id.max(generation).max(1),
        generation,
    };
    let bytes = serde_json::to_vec(&marker).map_err(io::Error::other)?;
    atomic_write_file(path, &bytes)
}

/// 仅在确认 owner 已退出后删除 marker，并在报告干净退出前同步目录元数据，保证恢复事实可持久观察。
pub(crate) fn clear_recovery_record(path: &Path) -> io::Result<()> {
    remove_file_synced(path)
}

/// 确认目录是受信任 app-data 边界内的真实目录；生产调用方必须传入规范化的 Tauri app-data runtime 目录。
fn ensure_private_run_dir(run_dir: &Path) -> io::Result<()> {
    let metadata = fs::symlink_metadata(run_dir)?;
    if metadata.file_type().is_symlink() || is_reparse_point(&metadata) || !metadata.is_dir() {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "runtime directory is not a private directory",
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o777 != 0o700 {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "runtime directory is not user-only",
            ));
        }
    }
    Ok(())
}

/// 创建、刷盘并原子替换同目录文件；唯一且 create-new 的临时文件名让部分写入保持可观测，避免静默覆盖有效恢复记录。
fn atomic_write_file(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let parent = path.parent().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "recovery path has no parent")
    })?;
    ensure_private_run_dir(parent)?;
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid recovery filename"))?;
    let temp = create_recovery_temp(parent, file_name)?;
    let result = (|| {
        let mut file = open_private_file(&temp)?;
        file.write_all(bytes)?;
        file.flush()?;
        file.sync_all()?;
        atomic_replace(&temp, path)?;
        sync_directory(parent)
    })();
    if result.is_err() {
        tracing::error!("durable recovery marker write failed; startup remains fail-closed");
    }
    result
}

/// 分配重试次数受限的 create-new 临时路径，既不把进程数据写入持久 marker，也不依赖无界重试循环。
fn create_recovery_temp(parent: &Path, file_name: &str) -> io::Result<PathBuf> {
    for _ in 0..32 {
        let id = NEXT_RECOVERY_TEMP_ID.fetch_add(1, Ordering::Relaxed);
        let candidate = parent.join(format!("{file_name}.tmp-{id}"));
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        match options.open(&candidate) {
            Ok(file) => {
                drop(file);
                return Ok(candidate);
            }
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error),
        }
    }
    Err(io::Error::new(
        io::ErrorKind::AlreadyExists,
        "recovery temp name space exhausted",
    ))
}

/// Unix 上设置私有文件 mode，Windows 上保留继承的应用私有 ACL；调用前必须已经校验父目录边界。
fn open_private_file(path: &Path) -> io::Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

/// 在所有受支持平台执行原子替换；Windows 使用原生 replace-and-write-through，因为该平台的 `rename` 无法原子替换既有文件。
fn atomic_replace(temp: &Path, target: &Path) -> io::Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let source: Vec<u16> = temp.as_os_str().encode_wide().chain([0]).collect();
        let destination: Vec<u16> = target.as_os_str().encode_wide().chain([0]).collect();
        const MOVEFILE_REPLACE_EXISTING: u32 = 0x1;
        const MOVEFILE_WRITE_THROUGH: u32 = 0x8;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn MoveFileExW(
                existing_file_name: *const u16,
                new_file_name: *const u16,
                flags: u32,
            ) -> i32;
        }
        // SAFETY：两个缓冲区均以 NUL 结尾并在调用期间存活；操作系统执行替换时不会保留任何指针。
        let replaced = unsafe {
            MoveFileExW(
                source.as_ptr(),
                destination.as_ptr(),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        };
        if replaced == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        fs::rename(temp, target)
    }
}

/// 在 marker 替换或删除后持久化目录项，避免进程退出后只留下部分恢复状态。
fn sync_directory(path: &Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        File::open(path)?.sync_all()
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let encoded: Vec<u16> = path.as_os_str().encode_wide().chain([0]).collect();
        const GENERIC_READ: u32 = 0x8000_0000;
        const FILE_SHARE_READ: u32 = 0x1;
        const FILE_SHARE_WRITE: u32 = 0x2;
        const FILE_SHARE_DELETE: u32 = 0x4;
        const OPEN_EXISTING: u32 = 3;
        const FILE_FLAG_BACKUP_SEMANTICS: u32 = 0x0200_0000;
        const FILE_FLAG_WRITE_THROUGH: u32 = 0x8000_0000;
        const INVALID_HANDLE_VALUE: *mut std::ffi::c_void = -1_isize as *mut std::ffi::c_void;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            #[link_name = "CreateFileW"]
            fn recovery_create_file(
                file_name: *const u16,
                desired_access: u32,
                share_mode: u32,
                security_attributes: *const std::ffi::c_void,
                creation_disposition: u32,
                flags_and_attributes: u32,
                template_file: *mut std::ffi::c_void,
            ) -> *mut std::ffi::c_void;
            #[link_name = "FlushFileBuffers"]
            fn recovery_flush_file_buffers(handle: *mut std::ffi::c_void) -> i32;
            #[link_name = "CloseHandle"]
            fn recovery_close_handle(handle: *mut std::ffi::c_void) -> i32;
        }
        // SAFETY：路径缓冲区在每次 OS 调用期间均以 NUL 结尾；所有返回分支都会先关闭句柄。
        let handle = unsafe {
            recovery_create_file(
                encoded.as_ptr(),
                GENERIC_READ,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                std::ptr::null(),
                OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_WRITE_THROUGH,
                std::ptr::null_mut(),
            )
        };
        if handle == INVALID_HANDLE_VALUE {
            return Err(io::Error::last_os_error());
        }
        let flushed = unsafe { recovery_flush_file_buffers(handle) };
        let flush_error = (flushed == 0).then(io::Error::last_os_error);
        let close_result = unsafe { recovery_close_handle(handle) };
        if let Some(error) = flush_error {
            // 部分受支持的 Windows 版本不允许对 NTFS 目录句柄调用 `FlushFileBuffers`。
            // 上述带 WRITE_THROUGH 的 `MoveFileExW` 已是平台提供的持久替换屏障；
            // 其他错误仍需保留，确保真实 ACL 问题不会被吞掉。
            if !matches!(error.raw_os_error(), Some(1 | 5 | 6 | 87)) {
                return Err(error);
            }
        }
        if close_result == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
}

/// 删除 marker 并同步其所在目录；容忍已清理状态，避免幂等 shutdown 被误报为故障。
fn remove_file_synced(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink()
                || is_reparse_point(&metadata)
                || !metadata.is_file()
            {
                return Err(io::Error::new(
                    io::ErrorKind::PermissionDenied,
                    "recovery marker is not a regular file",
                ));
            }
            fs::remove_file(path)?;
            sync_directory(path.parent().ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidInput, "recovery path has no parent")
            })?)
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error),
    }
}
