// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::registry::{WorkspaceHandle, metadata_for_path};
use crate::workspace::WorkspaceError;
use crate::workspace::domain::{ContentKind, FileContent, LineEnding, TextEncoding};
use std::fs::{self, File};
use std::io::Read;

/// 内容策略在分配前限制每次读取，并阻止 binary 内容进入文本 IPC。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ContentPolicy {
    pub max_bytes: u64,
    pub hash_limit_bytes: u64,
}

impl Default for ContentPolicy {
    /// 默认预算足够小，以维持编辑器 IPC 响应并避免大文件占满内存。
    fn default() -> Self {
        Self {
            max_bytes: 4 * 1024 * 1024,
            hash_limit_bytes: 4 * 1024 * 1024,
        }
    }
}

/// 文件读取器只接受 Workspace handle 与有界内容策略，不接收任意绝对路径或动态预算。
#[derive(Debug, Clone)]
pub struct FileReader {
    workspace: WorkspaceHandle,
    policy: ContentPolicy,
}

impl FileReader {
    /// 显式绑定读取策略与 Workspace handle，调用方不能静默扩大预算或切换根目录。
    pub fn new(workspace: WorkspaceHandle, policy: ContentPolicy) -> Self {
        let max_bytes = policy.max_bytes.min(64 * 1024 * 1024);
        let policy = ContentPolicy {
            max_bytes,
            hash_limit_bytes: policy.hash_limit_bytes.min(max_bytes),
        };
        Self { workspace, policy }
    }

    /// 前后复核常规文件并分类编码；binary 或超限内容不向调用方返回无界字节。
    pub fn read(&self, relative_path: &str) -> Result<FileContent, WorkspaceError> {
        let resolved = self.workspace.resolve_guard(relative_path, Some(false))?;
        self.workspace.verify_resolved(&resolved, Some(false))?;
        let path = &resolved.path;
        let before =
            fs::symlink_metadata(path).map_err(|error| WorkspaceError::io("stat", error))?;
        let hash_limit = self.policy.hash_limit_bytes.min(self.policy.max_bytes);
        let before_metadata = metadata_for_path(path, &before, hash_limit)?;
        if before.len() > self.policy.max_bytes {
            self.workspace.verify_resolved(&resolved, Some(false))?;
            return Ok(FileContent {
                metadata: before_metadata,
                kind: ContentKind::TooLarge,
                encoding: None,
                line_ending: None,
                text: None,
                bytes_read: 0,
                truncated: true,
            });
        }
        let file = File::open(path).map_err(|error| WorkspaceError::io("open", error))?;
        let capacity = usize::try_from(self.policy.max_bytes)
            .map_err(|_| WorkspaceError::FileTooLarge)?
            .saturating_add(1);
        let mut bytes = Vec::with_capacity(capacity.min(64 * 1024));
        file.take(self.policy.max_bytes.saturating_add(1))
            .read_to_end(&mut bytes)
            .map_err(|error| WorkspaceError::io("read", error))?;
        let after =
            fs::symlink_metadata(path).map_err(|error| WorkspaceError::io("stat", error))?;
        let after_metadata = metadata_for_path(path, &after, hash_limit)?;
        if before_metadata.revision != after_metadata.revision {
            return Err(WorkspaceError::ChangedDuringRead);
        }
        self.workspace.verify_resolved(&resolved, Some(false))?;
        if bytes.len() as u64 > self.policy.max_bytes {
            return Ok(FileContent {
                metadata: after_metadata,
                kind: ContentKind::TooLarge,
                encoding: None,
                line_ending: None,
                text: None,
                bytes_read: usize::try_from(self.policy.max_bytes).unwrap_or(usize::MAX),
                truncated: true,
            });
        }
        let (kind, encoding, text) = decode_content(&bytes);
        let line_ending = text.as_deref().map(detect_line_ending);
        Ok(FileContent {
            metadata: after_metadata,
            kind,
            encoding,
            line_ending,
            text,
            bytes_read: bytes.len(),
            truncated: false,
        })
    }
}

/// 不归一化 editor buffer 地识别换行；mixed 状态显式保留，避免后续保存做有损猜测。
pub(crate) fn detect_line_ending(text: &str) -> LineEnding {
    let bytes = text.as_bytes();
    let mut saw_lf = false;
    let mut saw_crlf = false;
    let mut saw_cr = false;
    let mut index = 0usize;
    while index < bytes.len() {
        match bytes[index] {
            b'\r' if bytes.get(index + 1) == Some(&b'\n') => {
                saw_crlf = true;
                index += 2;
            }
            b'\r' => {
                saw_cr = true;
                index += 1;
            }
            b'\n' => {
                saw_lf = true;
                index += 1;
            }
            _ => index += 1,
        }
    }
    match (saw_lf, saw_crlf, saw_cr) {
        (true, false, false) => LineEnding::Lf,
        (false, true, false) => LineEnding::CrLf,
        (false, false, true) => LineEnding::Cr,
        (false, false, false) => LineEnding::Lf,
        _ => LineEnding::Mixed,
    }
}

/// 只解码带 BOM 的 UTF-16 与严格 UTF-8，避免猜测编码导致跨平台搜索结果不可复现。
pub(crate) fn decode_content(bytes: &[u8]) -> (ContentKind, Option<TextEncoding>, Option<String>) {
    if bytes.starts_with(&[0xef, 0xbb, 0xbf]) {
        return match std::str::from_utf8(&bytes[3..]) {
            Ok(text) => (
                ContentKind::Text,
                Some(TextEncoding::Utf8Bom),
                Some(text.to_owned()),
            ),
            Err(_) => (ContentKind::UnknownEncoding, None, None),
        };
    }
    if bytes.starts_with(&[0xff, 0xfe]) {
        return decode_utf16(&bytes[2..], true);
    }
    if bytes.starts_with(&[0xfe, 0xff]) {
        return decode_utf16(&bytes[2..], false);
    }
    match std::str::from_utf8(bytes) {
        Ok(text) if !text.contains('\0') => (
            ContentKind::Text,
            Some(TextEncoding::Utf8),
            Some(text.to_owned()),
        ),
        Ok(_) => (ContentKind::Binary, None, None),
        Err(_) if bytes.contains(&0) => (ContentKind::Binary, None, None),
        Err(_) => (ContentKind::UnknownEncoding, None, None),
    }
}

/// UTF-16 解码不引入第三方转码器；奇数字节与非法 surrogate sequence 直接拒绝，
/// 不用替换字符静默损坏用户数据。
fn decode_utf16(
    bytes: &[u8],
    little_endian: bool,
) -> (ContentKind, Option<TextEncoding>, Option<String>) {
    if !bytes.len().is_multiple_of(2) {
        return (ContentKind::UnknownEncoding, None, None);
    }
    let units = bytes
        .chunks_exact(2)
        .map(|pair| {
            if little_endian {
                u16::from_le_bytes([pair[0], pair[1]])
            } else {
                u16::from_be_bytes([pair[0], pair[1]])
            }
        })
        .collect::<Vec<_>>();
    let encoding = if little_endian {
        TextEncoding::Utf16Le
    } else {
        TextEncoding::Utf16Be
    };
    match String::from_utf16(&units) {
        Ok(text) => (ContentKind::Text, Some(encoding), Some(text)),
        Err(_) => (ContentKind::UnknownEncoding, None, None),
    }
}
