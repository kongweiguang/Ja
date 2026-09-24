// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 点击消息中的本地目标时，由原生层绑定 workspace identity、canonicalize 并做有界读取。

use super::error::{PreviewError, PreviewErrorCode};
use crate::app_runtime::RuntimeHost;
use crate::workspace::domain::ContentKind;
use crate::workspace::infrastructure::content::decode_content;
use serde::{Deserialize, Serialize};
use std::fs::{self, File, Metadata};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use url::Url;

const MAX_TARGET_BYTES: usize = 32 * 1024;
const MAX_TEXT_BYTES: u64 = 1024 * 1024;

/// 按 Rust 类型边界区分进入 Files 的文本与交给隔离 WebView 尝试的普通文件。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PreviewFileKind {
    Text,
    Browser,
    Unsupported,
}

/// Tauri 仅接收消息点击产生的路径和 Java-owned workspace identity，不接收 renderer root。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviewResolveFileInput {
    pub target: String,
    pub workspace_id: Option<String>,
    pub line: Option<usize>,
    pub column: Option<usize>,
}

/// 原生确认文件身份后返回规范化位置；browser 类型不带正文，text 正文最多 1 MiB。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewFileResolution {
    pub canonical_path: String,
    pub display_name: String,
    pub workspace_id: Option<String>,
    pub workspace_relative_path: Option<String>,
    pub within_workspace: bool,
    pub kind: PreviewFileKind,
    pub mime_type: Option<String>,
    pub file_url: String,
    pub content: Option<String>,
    pub truncated: bool,
    pub line: Option<usize>,
    pub column: Option<usize>,
    pub read_only: bool,
}

/// 任意本机绝对路径跨 Workspace 可读；相对路径必须由 Rust 的当前绑定 handle 解析。
pub(crate) fn resolve_file(
    runtime: &RuntimeHost,
    input: &PreviewResolveFileInput,
) -> Result<PreviewFileResolution, PreviewError> {
    if input.line.is_some_and(|line| line == 0) || input.column.is_some_and(|column| column == 0) {
        return Err(PreviewError::new(PreviewErrorCode::FileTargetInvalid));
    }

    let (canonical, metadata, mut file, workspace_relative_path) =
        resolve_regular_file(runtime, input)?;
    let within_workspace = workspace_relative_path.is_some();
    let extension = canonical
        .extension()
        .and_then(|extension| extension.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase();
    let mime_type = mime_type(&extension).map(str::to_owned);
    let is_browser_document =
        browser_document_extension(&extension) || browser_document_signature(&mut file)?;
    let (kind, content, truncated) = if is_browser_document {
        (PreviewFileKind::Browser, None, false)
    } else {
        classify_text_file(file, metadata.len())?
    };
    let file_url = Url::from_file_path(&canonical)
        .map_err(|_| PreviewError::new(PreviewErrorCode::FileTargetInvalid))?
        .to_string();

    Ok(PreviewFileResolution {
        canonical_path: canonical.to_string_lossy().into_owned(),
        display_name: canonical
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_else(|| canonical.to_string_lossy().into_owned()),
        workspace_id: input.workspace_id.clone(),
        workspace_relative_path,
        within_workspace,
        kind,
        mime_type,
        file_url,
        content,
        truncated,
        line: input.line,
        column: input.column,
        read_only: !within_workspace,
    })
}

/// Explorer 只需要已核验文件身份，不应为了定位文件而解码最多 1 MiB 正文。
pub(crate) fn resolve_reveal_file_path(
    runtime: &RuntimeHost,
    input: &PreviewResolveFileInput,
) -> Result<PathBuf, PreviewError> {
    resolve_regular_file(runtime, input).map(|(canonical, _, _, _)| canonical)
}

/// 预览与资源管理器共用存在性、可读性和 Workspace identity 边界，避免修饰键绕开路径策略。
fn resolve_regular_file(
    runtime: &RuntimeHost,
    input: &PreviewResolveFileInput,
) -> Result<(PathBuf, Metadata, File, Option<String>), PreviewError> {
    validate_target(&input.target)?;
    let (path, supplied_as_relative) = resolve_target_path(runtime, input)?;
    let canonical = fs::canonicalize(&path).map_err(map_io_error)?;
    let metadata = fs::metadata(&canonical).map_err(map_io_error)?;
    if metadata.is_dir() {
        return Err(PreviewError::new(PreviewErrorCode::FileIsDirectory));
    }
    if !metadata.is_file() {
        return Err(PreviewError::new(PreviewErrorCode::LocalFileUnsupported));
    }

    // Probe readability for every file before returning browser kind. This keeps errors local
    // to the click and never materializes arbitrary file contents into the UI response.
    let file = File::open(&canonical).map_err(map_io_error)?;
    let workspace_relative_path = input
        .workspace_id
        .as_deref()
        .and_then(|workspace_id| workspace_relative_path(runtime, workspace_id, &canonical));
    if supplied_as_relative && workspace_relative_path.is_none() {
        return Err(PreviewError::new(PreviewErrorCode::WorkspaceUnavailable));
    }
    Ok((canonical, metadata, file, workspace_relative_path))
}

/// 在最终打开边界重新解析，防止过期 UI DTO 授权一个已被替换的路径。
pub(crate) fn resolve_browser_target(
    runtime: &RuntimeHost,
    target: &str,
    workspace_id: Option<&str>,
) -> Result<PreviewFileResolution, PreviewError> {
    let resolution = resolve_file(
        runtime,
        &PreviewResolveFileInput {
            target: target.to_owned(),
            workspace_id: workspace_id.map(str::to_owned),
            line: None,
            column: None,
        },
    )?;
    if resolution.kind != PreviewFileKind::Browser {
        return Err(PreviewError::new(PreviewErrorCode::LocalFileUnsupported));
    }
    Ok(resolution)
}

/// 相对路径绑定 App Server identity；绝对路径与 file URL 独立 canonicalize。
fn resolve_target_path(
    runtime: &RuntimeHost,
    input: &PreviewResolveFileInput,
) -> Result<(PathBuf, bool), PreviewError> {
    if let Some(path) = parse_absolute_or_file_target(&input.target)? {
        return Ok((path, false));
    }
    let workspace_id = input
        .workspace_id
        .as_deref()
        .ok_or(PreviewError::new(PreviewErrorCode::WorkspaceRequired))?;
    let resolved = runtime
        .with_configured_workspace(workspace_id, |workspace| {
            workspace
                .resolve_file(&input.target)
                .map_err(map_workspace_error)
        })
        .map_err(|_| PreviewError::new(PreviewErrorCode::WorkspaceUnavailable))??;
    Ok((resolved, true))
}

/// 接受本地 file URL 和原生绝对路径，相对路径留给 Workspace identity 绑定。
pub(crate) fn parse_absolute_or_file_target(target: &str) -> Result<Option<PathBuf>, PreviewError> {
    if target
        .get(..5)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("file:"))
    {
        let url = Url::parse(target)
            .map_err(|_| PreviewError::new(PreviewErrorCode::FileTargetInvalid))?;
        if url.scheme() != "file" {
            return Err(PreviewError::new(PreviewErrorCode::FileTargetInvalid));
        }
        return url
            .to_file_path()
            .map(Some)
            .map_err(|_| PreviewError::new(PreviewErrorCode::FileTargetInvalid));
    }
    let path = Path::new(target);
    if path.is_absolute() {
        return Ok(Some(path.to_path_buf()));
    }
    if target.as_bytes().get(1) == Some(&b':') {
        return Err(PreviewError::new(PreviewErrorCode::FileTargetInvalid));
    }
    Ok(None)
}

/// 只有 App Server 持有的 root 能证明 canonical containment 时才返回工作区相对位置。
fn workspace_relative_path(
    runtime: &RuntimeHost,
    workspace_id: &str,
    canonical: &Path,
) -> Option<String> {
    runtime
        .with_configured_workspace(workspace_id, |workspace| {
            canonical
                .strip_prefix(workspace.root_path())
                .ok()
                .map(path_to_workspace_string)
        })
        .ok()
        .flatten()
}

/// 将可信 Workspace handle 解析出的相对路径转为显示格式，供后续重新解析。
fn path_to_workspace_string(path: &Path) -> String {
    path.components()
        .filter_map(|component| match component {
            std::path::Component::Normal(value) => Some(value.to_string_lossy().into_owned()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("/")
}

/// 在 path/URL parser 宽松解释输入前校验点击目标。
pub(crate) fn validate_target(target: &str) -> Result<(), PreviewError> {
    if target.is_empty() || target.len() > MAX_TARGET_BYTES || target.chars().any(char::is_control)
    {
        return Err(PreviewError::new(PreviewErrorCode::FileTargetInvalid));
    }
    Ok(())
}

/// 只有有界前缀能严格解码为文本时才交给 Files；未知二进制仍交浏览器尝试。
pub(crate) fn classify_text_file(
    file: File,
    file_size: u64,
) -> Result<(PreviewFileKind, Option<String>, bool), PreviewError> {
    let mut truncated = file_size > MAX_TEXT_BYTES;
    let mut bytes = Vec::with_capacity(file_size.min(MAX_TEXT_BYTES) as usize);
    file.take(MAX_TEXT_BYTES.saturating_add(1))
        .read_to_end(&mut bytes)
        .map_err(map_io_error)?;
    if bytes.len() as u64 > MAX_TEXT_BYTES {
        truncated = true;
        bytes.truncate(MAX_TEXT_BYTES as usize);
    }
    trim_incomplete_text_tail(&mut bytes, truncated);
    let (kind, _encoding, content) = decode_content(&bytes);
    match kind {
        ContentKind::Text => Ok((PreviewFileKind::Text, content, truncated)),
        ContentKind::TooLarge => Ok((PreviewFileKind::Text, None, true)),
        ContentKind::Binary | ContentKind::UnknownEncoding => {
            Ok((PreviewFileKind::Browser, None, false))
        }
    }
}

/// 只移除有界读取末尾不完整的字符单元，截断时不输出替代字符。
pub(crate) fn trim_incomplete_text_tail(bytes: &mut Vec<u8>, truncated: bool) {
    if !truncated {
        return;
    }
    if bytes.starts_with(&[0xef, 0xbb, 0xbf]) {
        if let Err(error) = std::str::from_utf8(&bytes[3..])
            && error.error_len().is_none()
        {
            bytes.truncate(3 + error.valid_up_to());
        }
        return;
    }
    if bytes.starts_with(&[0xff, 0xfe]) || bytes.starts_with(&[0xfe, 0xff]) {
        if !bytes.len().is_multiple_of(2) {
            bytes.pop();
        }
        let little_endian = bytes.starts_with(&[0xff, 0xfe]);
        if bytes.len() >= 4 {
            let last = &bytes[bytes.len() - 2..];
            let unit = if little_endian {
                u16::from_le_bytes([last[0], last[1]])
            } else {
                u16::from_be_bytes([last[0], last[1]])
            };
            if (0xd800..=0xdbff).contains(&unit) {
                bytes.truncate(bytes.len() - 2);
            }
        }
        return;
    }
    if let Err(error) = std::str::from_utf8(bytes)
        && error.error_len().is_none()
    {
        bytes.truncate(error.valid_up_to());
    }
}

/// 已知由浏览器渲染的格式跳过文本解码，让 SVG/HTML/PDF 作为页面打开。
pub(crate) fn browser_document_extension(extension: &str) -> bool {
    matches!(
        extension,
        "html"
            | "htm"
            | "xhtml"
            | "svg"
            | "pdf"
            | "png"
            | "jpg"
            | "jpeg"
            | "gif"
            | "webp"
            | "bmp"
            | "ico"
            | "avif"
            | "mp3"
            | "wav"
            | "ogg"
            | "m4a"
            | "mp4"
            | "webm"
            | "mov"
            | "avi"
    )
}

/// 识别常见浏览器文件签名，防止无扩展名 PDF 或媒体文件误进 Files。
pub(crate) fn browser_document_signature(file: &mut File) -> Result<bool, PreviewError> {
    let mut prefix = [0u8; 1024];
    let length = file.read(&mut prefix).map_err(map_io_error)?;
    file.seek(SeekFrom::Start(0)).map_err(map_io_error)?;
    let bytes = &prefix[..length];
    let signature = bytes.starts_with(b"%PDF-")
        || bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a])
        || bytes.starts_with(&[0xff, 0xd8, 0xff])
        || bytes.starts_with(b"GIF87a")
        || bytes.starts_with(b"GIF89a")
        || (bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WEBP"))
        || (bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WAVE"))
        || bytes.starts_with(b"BM")
        || bytes.starts_with(b"II*\0")
        || bytes.starts_with(b"MM\0*")
        || bytes.starts_with(&[0, 0, 1, 0])
        || bytes.get(4..8).is_some_and(|kind| kind == b"ftyp")
        || bytes.starts_with(b"OggS")
        || bytes.starts_with(b"ID3")
        || bytes.starts_with(b"fLaC")
        || bytes.starts_with(&[0x1a, 0x45, 0xdf, 0xa3]);
    if signature {
        return Ok(true);
    }
    let text = std::str::from_utf8(bytes).unwrap_or_default();
    let normalized = text
        .trim_start_matches('\u{feff}')
        .trim_start()
        .to_ascii_lowercase();
    Ok(normalized.starts_with("<!doctype html")
        || normalized.starts_with("<html")
        || normalized.starts_with("<svg")
        || (normalized.starts_with("<?xml") && normalized.contains("<svg")))
}

/// 小型静态 MIME 映射补足少见格式在 Windows 文件 URL 上的内容识别。
fn mime_type(extension: &str) -> Option<&'static str> {
    Some(match extension {
        "html" | "htm" => "text/html",
        "xhtml" => "application/xhtml+xml",
        "svg" => "image/svg+xml",
        "pdf" => "application/pdf",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "bmp" => "image/bmp",
        "ico" => "image/x-icon",
        "avif" => "image/avif",
        "mp3" => "audio/mpeg",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "m4a" => "audio/mp4",
        "mp4" => "video/mp4",
        "webm" => "video/webm",
        "mov" => "video/quicktime",
        "avi" => "video/x-msvideo",
        "txt" | "log" => "text/plain",
        "md" | "markdown" => "text/markdown",
        "json" | "jsonc" | "jsonl" => "application/json",
        "xml" => "application/xml",
        "css" => "text/css",
        "js" | "mjs" | "cjs" => "text/javascript",
        _ => return None,
    })
}

/// 将文件系统错误映射为静态 Preview 类别，不回显 OS 消息或路径。
fn map_io_error(error: std::io::Error) -> PreviewError {
    match error.kind() {
        std::io::ErrorKind::NotFound => PreviewError::new(PreviewErrorCode::FileNotFound),
        std::io::ErrorKind::PermissionDenied => PreviewError::new(PreviewErrorCode::FileUnreadable),
        std::io::ErrorKind::InvalidInput => PreviewError::new(PreviewErrorCode::FileTargetInvalid),
        _ => PreviewError::new(PreviewErrorCode::FileUnreadable),
    }
}

/// Workspace resolver 错误映射为稳定类别，不将原始错误字符串传入 Preview UI。
fn map_workspace_error(error: crate::workspace::WorkspaceError) -> PreviewError {
    use crate::workspace::WorkspaceError;
    match error {
        WorkspaceError::PathNotFound | WorkspaceError::WorkspaceNotFound => {
            PreviewError::new(PreviewErrorCode::FileNotFound)
        }
        WorkspaceError::NotDirectory | WorkspaceError::NotFile => {
            PreviewError::new(PreviewErrorCode::FileIsDirectory)
        }
        WorkspaceError::Io {
            kind: crate::workspace::IoFailureKind::PermissionDenied,
            ..
        } => PreviewError::new(PreviewErrorCode::FileUnreadable),
        WorkspaceError::InvalidRelativePath
        | WorkspaceError::OutsideWorkspace
        | WorkspaceError::LinkNotAllowed => PreviewError::new(PreviewErrorCode::FileTargetInvalid),
        _ => PreviewError::new(PreviewErrorCode::FileUnreadable),
    }
}
