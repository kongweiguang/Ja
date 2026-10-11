// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 点击消息中的本地目标时，由原生层绑定 workspace identity、canonicalize 并做有界读取。

use super::error::{PreviewError, PreviewErrorCode};
use crate::app_runtime::RuntimeHost;
use crate::workspace::domain::ContentKind;
use crate::workspace::infrastructure::content::decode_content;
use crate::workspace::{ResolvedPath, WorkspaceHandle, WorkspaceId};
use serde::{Deserialize, Serialize};
use std::fs::{self, File, Metadata};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use url::Url;

const MAX_TARGET_BYTES: usize = 32 * 1024;
const MAX_TEXT_BYTES: u64 = 1024 * 1024;

/// Rust 决定目标进入 Files、媒体 Preview、Files 树定位或明确不支持状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PreviewFileKind {
    Text,
    Browser,
    Directory,
    Unsupported,
}

/// 浏览器预览是显式意图；默认路径解析保持文本文件进入受管 Files 编辑器。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PreviewMode {
    #[default]
    Editor,
    Browser,
}

/// Tauri 只接收点击路径、Java-owned workspace identity 和受限 Preview 意图，不接收 renderer root。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviewResolveFileInput {
    pub target: String,
    pub workspace_id: Option<String>,
    pub line: Option<usize>,
    pub column: Option<usize>,
    #[serde(default)]
    pub preview_mode: PreviewMode,
}

/// 原生确认路径身份后返回规范化位置；目录不签发 file URL，文本正文最多 1 MiB。
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
    pub file_url: Option<String>,
    pub content: Option<String>,
    pub truncated: bool,
    pub line: Option<usize>,
    pub column: Option<usize>,
    pub read_only: bool,
}

/// 任意本机绝对路径保留原目标；相对路径按当前 handle 解析并在返回前核验绑定未轮换。
pub(crate) fn resolve_file(
    runtime: &RuntimeHost,
    input: &PreviewResolveFileInput,
) -> Result<PreviewFileResolution, PreviewError> {
    if input.line.is_some_and(|line| line == 0) || input.column.is_some_and(|column| column == 0) {
        return Err(PreviewError::new(PreviewErrorCode::FileTargetInvalid));
    }

    let target = resolve_local_target(runtime, input)?;
    let workspace_relative_path = workspace_relative_path(
        runtime,
        input.workspace_id.as_deref(),
        &target.canonical_path,
        target.workspace_binding_id,
    );
    if target.supplied_as_relative && workspace_relative_path.is_none() {
        return Err(PreviewError::new(PreviewErrorCode::WorkspaceUnavailable));
    }
    let within_workspace = workspace_relative_path.is_some();
    let display_name = target
        .canonical_path
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| target.canonical_path.to_string_lossy().into_owned());
    if target.is_directory {
        verify_workspace_binding(target.workspace_guard.as_ref(), target.is_directory)?;
        return Ok(PreviewFileResolution {
            canonical_path: target.canonical_path.to_string_lossy().into_owned(),
            display_name,
            workspace_id: input.workspace_id.clone(),
            workspace_relative_path,
            within_workspace,
            kind: PreviewFileKind::Directory,
            mime_type: None,
            file_url: None,
            content: None,
            truncated: false,
            line: None,
            column: None,
            read_only: !within_workspace,
        });
    }

    let extension = target
        .canonical_path
        .extension()
        .and_then(|extension| extension.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase();
    let mime_type = mime_type(&extension).map(str::to_owned);
    let mut file = target
        .file
        .expect("regular file resolution retains its checked file handle");
    let is_browser_media =
        browser_document_extension(&extension) || browser_document_signature(&mut file)?;
    let is_explicit_html_preview = input.preview_mode == PreviewMode::Browser
        && (html_document_extension(&extension) || html_document_signature(&mut file)?);
    let (kind, content, truncated) = if is_browser_media || is_explicit_html_preview {
        (PreviewFileKind::Browser, None, false)
    } else {
        classify_text_file(file, target.metadata.len())?
    };
    verify_workspace_binding(target.workspace_guard.as_ref(), target.is_directory)?;
    let file_url = Url::from_file_path(&target.canonical_path)
        .map_err(|_| PreviewError::new(PreviewErrorCode::FileTargetInvalid))?
        .to_string();

    Ok(PreviewFileResolution {
        canonical_path: target.canonical_path.to_string_lossy().into_owned(),
        display_name,
        workspace_id: input.workspace_id.clone(),
        workspace_relative_path,
        within_workspace,
        kind,
        mime_type,
        file_url: Some(file_url),
        content,
        truncated,
        line: input.line,
        column: input.column,
        read_only: !within_workspace,
    })
}

/// Explorer 只需核验目标类型与路径，不读取目录内容，也不解码文件正文。
pub(crate) fn resolve_reveal_target(
    runtime: &RuntimeHost,
    input: &PreviewResolveFileInput,
) -> Result<PreviewRevealTarget, PreviewError> {
    let target = resolve_local_target(runtime, input)?;
    if target.supplied_as_relative
        && workspace_relative_path(
            runtime,
            input.workspace_id.as_deref(),
            &target.canonical_path,
            target.workspace_binding_id,
        )
        .is_none()
    {
        return Err(PreviewError::new(PreviewErrorCode::WorkspaceUnavailable));
    }
    verify_workspace_binding(target.workspace_guard.as_ref(), target.is_directory)?;
    Ok(PreviewRevealTarget {
        path: target.canonical_path,
        kind: if target.is_directory {
            crate::workspace::domain::EntryKind::Directory
        } else {
            crate::workspace::domain::EntryKind::File
        },
    })
}

/// 通过同一受信 resolver 区分文件和目录；相对路径绑定的 handle 若已轮换则拒绝迟到结果。
fn resolve_local_target(
    runtime: &RuntimeHost,
    input: &PreviewResolveFileInput,
) -> Result<ResolvedLocalTarget, PreviewError> {
    validate_target(&input.target)?;
    let (path, supplied_as_relative, workspace_binding_id, workspace_guard) =
        resolve_target_path(runtime, input)?;
    let canonical = fs::canonicalize(&path).map_err(map_io_error)?;
    let metadata = fs::metadata(&canonical).map_err(map_io_error)?;
    let is_directory = metadata.is_dir();
    if !is_directory && !metadata.is_file() {
        return Err(PreviewError::new(PreviewErrorCode::LocalFileUnsupported));
    }

    // 只在文件目标上打开句柄；目录定位不读内容，并保留解析时的内部 handle identity 供返回前复核。
    let file = if is_directory {
        None
    } else {
        Some(File::open(&canonical).map_err(map_io_error)?)
    };
    Ok(ResolvedLocalTarget {
        canonical_path: canonical,
        metadata,
        file,
        is_directory,
        supplied_as_relative,
        workspace_binding_id,
        workspace_guard,
    })
}

/// 相对文件读取期间保留 Workspace IO lease，并在返回内容前复核路径身份与根目录身份。
fn verify_workspace_binding(
    guard: Option<&WorkspaceBindingGuard>,
    is_directory: bool,
) -> Result<(), PreviewError> {
    if let Some(guard) = guard {
        guard
            .workspace
            .verify_resolved(&guard.resolved, Some(is_directory))
            .map_err(map_workspace_error)?;
    }
    Ok(())
}

/// 不透明路径类型把浏览器参数构造和文件/目录 `/select` 差异留在 native 层。
#[derive(Debug)]
pub(crate) struct PreviewRevealTarget {
    pub path: PathBuf,
    pub kind: crate::workspace::domain::EntryKind,
}

/// 保存有界解析所需的 native 证据，避免 renderer root 或可变 Workspace 快照参与判型。
struct ResolvedLocalTarget {
    canonical_path: PathBuf,
    metadata: Metadata,
    file: Option<File>,
    is_directory: bool,
    supplied_as_relative: bool,
    workspace_binding_id: Option<WorkspaceId>,
    workspace_guard: Option<WorkspaceBindingGuard>,
}

/// 解析路径的 lease 必须覆盖后续 bounded read，避免 root rebind 越过仍在执行的本地文件 IO。
struct WorkspaceBindingGuard {
    workspace: WorkspaceHandle,
    resolved: ResolvedPath,
}

/// 在最终打开边界重新解析，防止过期 UI DTO 授权一个已被替换的路径；目录与其它非 Browser 类型
/// 使用各自稳定的拒绝类别，让地址栏错误准确说明目标类型而不改变路径或权限失败语义。
pub(crate) fn resolve_browser_target(
    runtime: &RuntimeHost,
    target: &str,
    workspace_id: Option<&str>,
    preview_mode: PreviewMode,
) -> Result<PreviewFileResolution, PreviewError> {
    let resolution = resolve_file(
        runtime,
        &PreviewResolveFileInput {
            target: target.to_owned(),
            workspace_id: workspace_id.map(str::to_owned),
            line: None,
            column: None,
            preview_mode,
        },
    )?;
    if let Some(code) = browser_target_error_code(resolution.kind) {
        return Err(PreviewError::new(code));
    }
    Ok(resolution)
}

/// 目录保留专用拒绝码，让用户能区分文件夹与普通不支持文件；已解析的其他类型沿用原分类。
/// @author kongweiguang
pub(crate) fn browser_target_error_code(kind: PreviewFileKind) -> Option<PreviewErrorCode> {
    match kind {
        PreviewFileKind::Browser => None,
        PreviewFileKind::Directory => Some(PreviewErrorCode::FileIsDirectory),
        PreviewFileKind::Text | PreviewFileKind::Unsupported => {
            Some(PreviewErrorCode::LocalFileUnsupported)
        }
    }
}

/// 相对路径在一个 handle 锁内解析并记录内部 identity；Markdown 的 Windows 分隔符归一为协议 slash，
/// 尾分隔符只作目录提示移除，之后仍由 Workspace 执行严格词法/物理校验；绝对路径与 file URI 原样保留。
fn resolve_target_path(
    runtime: &RuntimeHost,
    input: &PreviewResolveFileInput,
) -> Result<
    (
        PathBuf,
        bool,
        Option<WorkspaceId>,
        Option<WorkspaceBindingGuard>,
    ),
    PreviewError,
> {
    if let Some(path) = parse_absolute_or_file_target(&input.target)? {
        return Ok((path, false, None, None));
    }
    let workspace_id = input
        .workspace_id
        .as_deref()
        .ok_or(PreviewError::new(PreviewErrorCode::WorkspaceRequired))?;
    // 绝对路径/file URI 已在上方原样分流；此处只规范相对路径，再由 Workspace 执行安全准入。
    let workspace_target = normalize_workspace_relative_target(&input.target)?;
    let (resolved, workspace) = runtime
        .with_configured_workspace(workspace_id, |workspace| {
            let resolved = workspace
                .resolve_guard(&workspace_target, None)
                .map_err(map_workspace_error)?;
            Ok::<_, PreviewError>((resolved, workspace.clone()))
        })
        .map_err(|_| PreviewError::new(PreviewErrorCode::WorkspaceUnavailable))??;
    let path = resolved.path.clone();
    let workspace_binding_id = workspace.id();
    Ok((
        path,
        true,
        Some(workspace_binding_id),
        Some(WorkspaceBindingGuard {
            workspace,
            resolved,
        }),
    ))
}

/// 相对 Markdown 路径统一成 Workspace 的 slash 词法；根别名在去尾分隔符后为空时必须拒绝。
pub(crate) fn normalize_workspace_relative_target(target: &str) -> Result<String, PreviewError> {
    let normalized = target.replace('\\', "/");
    let without_trailing_separator = normalized.trim_end_matches('/');
    if without_trailing_separator.is_empty() {
        return Err(PreviewError::new(PreviewErrorCode::FileTargetInvalid));
    }
    Ok(without_trailing_separator.to_owned())
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

/// 只有当前 App Server root 且 handle identity 未轮换时才返回相对位置，避免迟到结果跨根路由。
fn workspace_relative_path(
    runtime: &RuntimeHost,
    workspace_id: Option<&str>,
    canonical: &Path,
    expected_binding_id: Option<WorkspaceId>,
) -> Option<String> {
    let workspace_id = workspace_id?;
    runtime
        .with_configured_workspace(workspace_id, |workspace| {
            if expected_binding_id.is_some_and(|expected| expected != workspace.id()) {
                return None;
            }
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

/// 只有有界前缀能严格解码为文本时才交给 Files；未知二进制失败关闭为 Unsupported。
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
            Ok((PreviewFileKind::Unsupported, None, false))
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

/// PDF、SVG 与媒体格式直接复用隔离媒体预览；HTML 需要单独的显式 Browser 意图。
pub(crate) fn browser_document_extension(extension: &str) -> bool {
    matches!(
        extension,
        "svg"
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

/// 只有明确选择浏览器预览时，HTML 扩展名才覆盖默认源码编辑行为。
pub(crate) fn html_document_extension(extension: &str) -> bool {
    matches!(extension, "html" | "htm" | "xhtml")
}

/// 识别常见媒体文件签名，避免无扩展名 PDF 或音视频误进文本编辑器。
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
    Ok(normalized.starts_with("<svg")
        || (normalized.starts_with("<?xml") && normalized.contains("<svg")))
}

/// 仅显式 Browser 请求才识别 HTML 正文，普通链接默认保留 Files 源码体验。
pub(crate) fn html_document_signature(file: &mut File) -> Result<bool, PreviewError> {
    let mut prefix = [0u8; 1024];
    let length = file.read(&mut prefix).map_err(map_io_error)?;
    file.seek(SeekFrom::Start(0)).map_err(map_io_error)?;
    let text = std::str::from_utf8(&prefix[..length]).unwrap_or_default();
    let normalized = text
        .trim_start_matches('\u{feff}')
        .trim_start()
        .to_ascii_lowercase();
    Ok(normalized.starts_with("<!doctype html")
        || normalized.starts_with("<html")
        || (normalized.starts_with("<?xml") && normalized.contains("<html")))
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

/// 仅把经过规范 URL 解析的本地音视频文件视为媒体探测候选，避免对任意 file 页面豁免导航失败。
/// @author kongweiguang
pub(crate) fn is_local_media_preview_url(raw_url: &str) -> bool {
    let Ok(url) = Url::parse(raw_url) else {
        return false;
    };
    if url.scheme() != "file" || url.query().is_some() || url.fragment().is_some() {
        return false;
    }
    let Ok(path) = url.to_file_path() else {
        return false;
    };
    let Some(extension) = path.extension().and_then(|value| value.to_str()) else {
        return false;
    };
    mime_type(&extension.to_ascii_lowercase())
        .is_some_and(|mime| mime.starts_with("audio/") || mime.starts_with("video/"))
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
