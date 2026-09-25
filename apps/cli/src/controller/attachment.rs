// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 附件经 Rust 私有 ingress 复制，再由 Java 以 token、长度和摘要校验导入。

use super::{
    CliError, required_str,
    rpc::{Connection, ensure_directory_tree, runtime_home},
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};

const MAX_FILE_BYTES: u64 = 100 * 1024 * 1024;
const MAX_BATCH_BYTES: u64 = 250 * 1024 * 1024;
const MAX_FILES: usize = 10;

pub struct ImportedAttachment {
    pub id: String,
    pub name: String,
    pub size: u64,
    pub media_kind: String,
}

struct Staged {
    token: String,
    path: PathBuf,
    display_name: String,
    size: u64,
    sha256: String,
}

impl Drop for Staged {
    /// Java 导入成功或任一步失败后都移除 run/ingress 中的临时明文副本。
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

#[derive(Clone, Copy, Eq, PartialEq)]
struct SourceSnapshot {
    volume: u64,
    file_low: u64,
    file_high: u64,
    size: u64,
    mtime_low: u64,
    mtime_high: i64,
    links: u64,
    regular: bool,
    reparse: bool,
}

/// 整批先执行数量和字节准入，随后逐个导入；失败文件不会留下可见附件或明文 staging。
pub fn import_paths(
    connection: &mut Connection,
    workspace_id: &str,
    paths: &[PathBuf],
) -> Result<Vec<ImportedAttachment>, CliError> {
    if paths.is_empty() || paths.len() > MAX_FILES {
        return Err(CliError::usage("一次最多添加 10 个附件"));
    }
    let mut admitted = Vec::with_capacity(paths.len());
    let mut total = 0_u64;
    for path in paths {
        validate_source_path(path)?;
        let source = open_source(path)?;
        let snapshot = snapshot_source(&source)?;
        if !snapshot.regular
            || snapshot.reparse
            || snapshot.links > 1
            || snapshot.size > MAX_FILE_BYTES
        {
            return Err(CliError::usage("附件必须是 100 MiB 以内的普通非链接文件"));
        }
        total = total
            .checked_add(snapshot.size)
            .ok_or_else(|| CliError::usage("附件总大小超限"))?;
        if total > MAX_BATCH_BYTES {
            return Err(CliError::usage("附件总大小超过 250 MiB"));
        }
        admitted.push((path.clone(), snapshot));
    }
    let mut imported: Vec<ImportedAttachment> = Vec::with_capacity(admitted.len());
    for (path, snapshot) in admitted {
        let result = (|| {
            let staged = stage_one(&path, snapshot)?;
            let result = connection.request("attachment/import", json!({
                "ingressToken":staged.token,"workspaceId":workspace_id,"displayName":staged.display_name,
                "sizeBytes":staged.size,"sha256":staged.sha256
            }))?;
            if required_str(&result, "workspaceId")? != workspace_id
                || required_str(&result, "displayName")? != staged.display_name
                || result.get("sizeBytes").and_then(Value::as_u64) != Some(staged.size)
            {
                return Err(CliError::protocol("附件导入回执与原生文件不符"));
            }
            Ok(ImportedAttachment {
                id: required_str(&result, "attachmentId")?.to_owned(),
                name: staged.display_name.clone(),
                size: staged.size,
                media_kind: required_str(&result, "mediaKind")?.to_owned(),
            })
        })();
        match result {
            Ok(item) => imported.push(item),
            Err(error) => {
                for item in &imported {
                    let _ =
                        connection.request("attachment/discard", json!({"attachmentId":item.id}));
                }
                return Err(error);
            }
        }
    }
    Ok(imported)
}

/// 复制期间按原句柄计算 SHA-256，发布前再比较路径句柄，禁止文件被替换后导入其他内容。
fn stage_one(path: &Path, admitted: SourceSnapshot) -> Result<Staged, CliError> {
    validate_source_path(path)?;
    let mut source = open_source(path)?;
    if snapshot_source(&source)? != admitted {
        return Err(CliError::usage("附件在选择后已变化"));
    }
    let root = ingress_root()?;
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty() && name.len() <= 512)
        .ok_or_else(|| CliError::usage("附件名称无效"))?
        .to_owned();
    for _ in 0..8 {
        let token = uuid::Uuid::new_v4().simple().to_string();
        let part = root.join(format!("{token}.part"));
        let final_path = root.join(&token);
        let mut staging = match OpenOptions::new().write(true).create_new(true).open(&part) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(CliError::transport(error)),
        };
        let staged = copy_and_publish(
            &mut source,
            &mut staging,
            &part,
            &final_path,
            path,
            admitted,
        );
        if staged.is_err() {
            let _ = fs::remove_file(&part);
        }
        let (size, sha256) = staged?;
        return Ok(Staged {
            token,
            path: final_path,
            display_name: name,
            size,
            sha256,
        });
    }
    Err(CliError::configuration("无法分配附件 staging token"))
}

/// 以 create_new + 同目录 hard_link 建立 no-replace 发布；短暂双链接只存在于私有 staging 内。
fn copy_and_publish(
    source: &mut File,
    staging: &mut File,
    part: &Path,
    final_path: &Path,
    source_path: &Path,
    admitted: SourceSnapshot,
) -> Result<(u64, String), CliError> {
    let mut digest = Sha256::new();
    let mut copied = 0_u64;
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let count = source.read(&mut buffer).map_err(CliError::transport)?;
        if count == 0 {
            break;
        }
        copied = copied
            .checked_add(count as u64)
            .ok_or_else(|| CliError::usage("附件大小超限"))?;
        if copied > admitted.size || copied > MAX_FILE_BYTES {
            return Err(CliError::usage("附件在复制期间变大"));
        }
        staging
            .write_all(&buffer[..count])
            .map_err(CliError::transport)?;
        digest.update(&buffer[..count]);
    }
    if copied != admitted.size
        || snapshot_source(source)? != admitted
        || snapshot_path(source_path)? != admitted
    {
        return Err(CliError::usage("附件在复制期间已变化"));
    }
    staging
        .flush()
        .and_then(|_| staging.sync_all())
        .map_err(CliError::transport)?;
    fs::hard_link(part, final_path).map_err(CliError::transport)?;
    if let Err(error) = fs::remove_file(part) {
        let _ = fs::remove_file(final_path);
        return Err(CliError::transport(error));
    }
    let sha256 = digest
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    Ok((copied, sha256))
}

/// ingress 根只允许位于当前用户 Ja run 目录，并拒绝任何上级 junction 或 symlink。
fn ingress_root() -> Result<PathBuf, CliError> {
    let run = runtime_home()?.join("run");
    ensure_directory_tree(&run)?;
    let root = fs::canonicalize(&run)
        .map_err(CliError::transport)?
        .join("attachment-ingress");
    ensure_directory_tree(&root)?;
    let canonical = fs::canonicalize(&root).map_err(CliError::transport)?;
    if canonical != root {
        return Err(CliError::configuration("附件 ingress 根目录已重定向"));
    }
    Ok(canonical)
}

/// 文件路径每一级都不能是链接；Win32 设备名和 ADS 也不能进入普通附件通道。
fn validate_source_path(path: &Path) -> Result<(), CliError> {
    if !path.is_absolute() || path.file_name().is_none() {
        return Err(CliError::usage("附件需要绝对文件路径"));
    }
    let mut current = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Prefix(_) | Component::RootDir => current.push(component.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => return Err(CliError::usage("附件路径不能包含 ..")),
            Component::Normal(name) => {
                current.push(name);
                let metadata = fs::symlink_metadata(&current).map_err(source_io_error)?;
                if metadata.file_type().is_symlink() || reparse(&metadata) {
                    return Err(CliError::usage("附件路径不能包含链接"));
                }
                #[cfg(windows)]
                {
                    let name = name
                        .to_str()
                        .ok_or_else(|| CliError::usage("附件路径必须是 Unicode"))?;
                    if name.contains(':') || reserved_windows_name(name) {
                        return Err(CliError::usage("附件路径包含设备名或附加数据流"));
                    }
                }
            }
        }
    }
    Ok(())
}

/// 普通文件在打开前先检查，避免 FIFO、设备和目录的特殊读取语义。
fn open_source(path: &Path) -> Result<File, CliError> {
    let metadata = fs::symlink_metadata(path).map_err(source_io_error)?;
    if !metadata.is_file() || metadata.file_type().is_symlink() || reparse(&metadata) {
        return Err(CliError::usage("附件必须是普通文件"));
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options
            .share_mode(0x0000_0001)
            .custom_flags(0x0020_0000 | 0x0800_0000);
    }
    options.open(path).map_err(source_io_error)
}

/// 用户选取的文件失败属于可修正输入错误，不能伪装成后台连接故障并关闭 TUI。
fn source_io_error(error: std::io::Error) -> CliError {
    match error.kind() {
        std::io::ErrorKind::NotFound => CliError::usage("附件文件不存在，请检查路径"),
        std::io::ErrorKind::PermissionDenied => CliError::usage("附件文件无读取权限"),
        _ => CliError::usage("附件文件无法访问，请检查路径或占用状态"),
    }
}

/// 路径快照由独立只读句柄获取，和持续持有的复制句柄比较物理文件身份。
fn snapshot_path(path: &Path) -> Result<SourceSnapshot, CliError> {
    snapshot_source(&open_source(path)?)
}

#[cfg(windows)]
/// Windows 使用文件句柄的 volume/index/link count，路径 metadata 无法提供同样的竞态保护。
fn snapshot_source(file: &File) -> Result<SourceSnapshot, CliError> {
    use std::os::windows::io::AsRawHandle;
    use windows::Win32::Foundation::HANDLE;
    use windows::Win32::Storage::FileSystem::{
        BY_HANDLE_FILE_INFORMATION, GetFileInformationByHandle,
    };
    let mut info = BY_HANDLE_FILE_INFORMATION::default();
    // SAFETY: file 在同步调用期间持有有效 Windows handle，输出结构由 windows-rs 保证 ABI。
    unsafe { GetFileInformationByHandle(HANDLE(file.as_raw_handle()), &mut info) }
        .map_err(CliError::transport)?;
    let attrs = info.dwFileAttributes;
    Ok(SourceSnapshot {
        volume: u64::from(info.dwVolumeSerialNumber),
        file_low: u64::from(info.nFileIndexLow),
        file_high: u64::from(info.nFileIndexHigh),
        size: (u64::from(info.nFileSizeHigh) << 32) | u64::from(info.nFileSizeLow),
        mtime_low: u64::from(info.ftLastWriteTime.dwLowDateTime),
        mtime_high: i64::from(info.ftLastWriteTime.dwHighDateTime),
        links: u64::from(info.nNumberOfLinks),
        regular: attrs & (0x10 | 0x40) == 0,
        reparse: attrs & 0x400 != 0,
    })
}

#[cfg(unix)]
/// Unix 使用 fstat 的 inode/device/nlink，避免路径二次 stat 穿过换名窗口。
fn snapshot_source(file: &File) -> Result<SourceSnapshot, CliError> {
    use std::os::unix::fs::MetadataExt;
    let meta = file.metadata().map_err(CliError::transport)?;
    Ok(SourceSnapshot {
        volume: meta.dev(),
        file_low: meta.ino(),
        file_high: 0,
        size: meta.len(),
        mtime_low: meta.mtime_nsec() as u64,
        mtime_high: meta.mtime(),
        links: meta.nlink(),
        regular: meta.is_file(),
        reparse: false,
    })
}

#[cfg(not(any(windows, unix)))]
/// 无法取得物理文件身份的平台显式不开放附件导入。
fn snapshot_source(_file: &File) -> Result<SourceSnapshot, CliError> {
    Err(CliError::configuration("当前平台不支持安全附件导入"))
}

/// 与 CLI 运行目录相同的 reparse 判定；仅信任普通文件系统节点。
fn reparse(metadata: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        metadata.file_attributes() & 0x400 != 0
    }
    #[cfg(not(windows))]
    {
        let _ = metadata;
        false
    }
}

#[cfg(windows)]
/// Win32 在尾点/空格归一化前解析设备名，须按同一规则拒绝。
fn reserved_windows_name(name: &str) -> bool {
    let upper = name
        .trim_end_matches([' ', '.'])
        .split('.')
        .next()
        .unwrap_or("")
        .to_ascii_uppercase();
    matches!(
        upper.as_str(),
        "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$"
    ) || upper
        .strip_prefix("COM")
        .or_else(|| upper.strip_prefix("LPT"))
        .is_some_and(|suffix| matches!(suffix, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9"))
}
