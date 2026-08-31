// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

pub(crate) mod error;
pub(crate) mod mutation;
pub(crate) mod open_with;
pub(crate) mod value_objects;
pub(crate) mod watch;

pub use error::{IoFailureKind, WorkspaceError};
pub(crate) use mutation::{
    CreateEntryCommand, CreateEntryKind, CreateEntryResult, DropImportCommand, DropImportResult,
    FileSaveResult, MoveEntryCommand, MoveEntryResult, SaveFileCommand, TextContent,
    TrashCommitCommand, TrashCommitResult, TrashPrepareCommand, TrashPrepareResult,
};
pub(crate) use open_with::{OpenError, OpenResult, OpenTargetAvailability};
pub use open_with::{OpenTargetUnavailableReason, OpenWithTarget};
pub use value_objects::FileRevision;
pub(crate) use value_objects::{DropToken, MutationId, RelativePath, TrashToken};
pub(crate) use watch::{
    WatchCommand, WatchRescanResult, WatchStartResult, WatchStopResult, WorkspaceChange,
};

/// 区分可遍历条目与不透明文件系统节点，避免基础设施层自行解释节点语义。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EntryKind {
    File,
    Directory,
    Symlink,
    ReparsePoint,
    Other,
}

/// 表示受限读取器对普通文件的分类，作为读取策略与界面投影之间的稳定领域事实。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContentKind {
    Text,
    Binary,
    UnknownEncoding,
    TooLarge,
}

/// 文本编码有意限制为 Rust 标准库可确定且无损解码的集合，避免平台编码导致不可逆写回。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TextEncoding {
    Utf8,
    Utf8Bom,
    Utf16Le,
    Utf16Be,
}

/// 记录读取时观察到的换行约定，使保存流程保持用户文件风格而不会静默改写。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineEnding {
    Lf,
    CrLf,
    Cr,
    Mixed,
}

/// 元数据不包含绝对路径，因为调用方只应持有不透明 Workspace id 与请求时的相对路径。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileMetadata {
    pub kind: EntryKind,
    pub size: u64,
    pub modified_unix_millis: Option<u128>,
    pub revision: FileRevision,
}

/// 分页目录条目只表达树形投影所需事实，不泄漏文件系统实现细节。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TreeEntry {
    pub name: String,
    pub relative_path: String,
    pub metadata: FileMetadata,
    pub can_expand: bool,
}

/// 单次非递归目录分页结果同时携带修订与快照令牌，保证后续分页可检测陈旧状态。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TreePage {
    pub entries: Vec<TreeEntry>,
    pub directory_revision: FileRevision,
    pub next_cursor: Option<String>,
    pub snapshot_token: String,
    pub total_entries: usize,
    pub depth: usize,
}

/// 受限普通文件读取结果显式表达截断与编码状态，避免界面把不完整内容当作完整事实。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileContent {
    pub metadata: FileMetadata,
    pub kind: ContentKind,
    pub encoding: Option<TextEncoding>,
    pub line_ending: Option<LineEnding>,
    pub text: Option<String>,
    pub bytes_read: usize,
    pub truncated: bool,
}

/// 搜索坐标基于解码后的文本而非原始字节偏移，确保不同编码下的界面定位一致。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchHit {
    pub relative_path: String,
    pub line: usize,
    pub column: usize,
    pub snippet: String,
    pub encoding: TextEncoding,
}
