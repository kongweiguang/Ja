// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::{
    DropToken, EntryKind, FileRevision, LineEnding, MutationId, RelativePath, TextEncoding,
    TrashToken, WorkspaceError,
};

/// 创建节点的闭集由领域定义，防止 interface 用自由字符串扩大原生写能力。
#[derive(Debug, Clone, Copy)]
pub(crate) enum CreateEntryKind {
    File,
    Directory,
}

/// 文本内容显式携带编码与换行约定，保证落盘前不会隐式改变用户文件格式。
#[derive(Debug, Clone)]
pub(crate) struct TextContent {
    pub text: String,
    pub encoding: TextEncoding,
    pub line_ending: LineEnding,
}

/// 创建用例只接受 workspace-relative 路径、CAS 证据和一次性 mutation id。
#[derive(Debug, Clone)]
pub(crate) struct CreateEntryCommand {
    pub relative_path: RelativePath,
    pub kind: CreateEntryKind,
    pub expected_revision: Option<FileRevision>,
    pub mutation_id: MutationId,
    pub content: Option<TextContent>,
}

/// 保存用例把 expected revision 作为必填不变量，拒绝无条件覆盖。
#[derive(Debug, Clone)]
pub(crate) struct SaveFileCommand {
    pub relative_path: RelativePath,
    pub expected_revision: FileRevision,
    pub mutation_id: MutationId,
    pub content: TextContent,
}

/// 移动用例同时携带源 revision 与两个相对路径，便于原生层一次锁定端点。
#[derive(Debug, Clone)]
pub(crate) struct MoveEntryCommand {
    pub from_relative_path: RelativePath,
    pub to_relative_path: RelativePath,
    pub expected_revision: FileRevision,
    pub mutation_id: MutationId,
}

/// Trash prepare 只生成短期计划，不在预览阶段触发不可逆平台副作用。
#[derive(Debug, Clone)]
pub(crate) struct TrashPrepareCommand {
    pub relative_path: RelativePath,
    pub expected_revision: FileRevision,
    pub mutation_id: MutationId,
}

/// Trash commit 必须同时匹配短期 token、原 revision 与新的 mutation id。
#[derive(Debug, Clone)]
pub(crate) struct TrashCommitCommand {
    pub relative_path: RelativePath,
    pub expected_revision: FileRevision,
    pub operation_token: TrashToken,
    pub mutation_id: MutationId,
}

/// Drop import 只消费 Rust 签发的 opaque token，不允许 WebView 传入原生路径。
#[derive(Debug, Clone)]
pub(crate) struct DropImportCommand {
    pub destination_relative_path: RelativePath,
    pub expected_revision: FileRevision,
    pub drop_token: DropToken,
    pub mutation_id: MutationId,
}

/// 文件保存成功只返回相对路径和权威 post-write revision。
#[derive(Debug, Clone)]
pub(crate) struct FileSaveResult {
    pub relative_path: String,
    pub revision: FileRevision,
}

/// 创建结果以真实落盘类型为准，避免 interface 根据请求猜测节点类型。
#[derive(Debug, Clone)]
pub(crate) struct CreateEntryResult {
    pub relative_path: String,
    pub kind: EntryKind,
    pub revision: FileRevision,
}

/// 移动结果保留前后相对路径，使 React 能原子重映射 tab 与 tree。
#[derive(Debug, Clone)]
pub(crate) struct MoveEntryResult {
    pub from_relative_path: String,
    pub to_relative_path: String,
    pub revision: FileRevision,
}

/// Trash prepare 只公开有界统计和到期时间，完整快照始终留在原生层。
#[derive(Debug, Clone)]
pub(crate) struct TrashPrepareResult {
    pub operation_token: String,
    pub file_count: usize,
    pub total_bytes: u64,
    pub expires_at_unix_millis: u64,
}

/// Trash commit 的成功事实不泄露回收站或绝对路径细节。
#[derive(Debug, Clone)]
pub(crate) struct TrashCommitResult {
    pub committed: bool,
    pub revision: Option<FileRevision>,
}

/// Drop import 返回实际发布的相对路径，失败时原生层负责回滚所有已发布节点。
#[derive(Debug, Clone)]
pub(crate) struct DropImportResult {
    pub imported_relative_paths: Vec<String>,
}

impl CreateEntryCommand {
    /// Create 同时准入条目路径与幂等键，避免 command adapter 构造半合法用例对象。
    pub(crate) fn new(
        relative_path: String,
        kind: CreateEntryKind,
        expected_revision: Option<FileRevision>,
        mutation_id: String,
        content: Option<TextContent>,
    ) -> Result<Self, WorkspaceError> {
        Ok(Self {
            relative_path: RelativePath::parse_entry(relative_path)?,
            kind,
            expected_revision,
            mutation_id: MutationId::parse(mutation_id)?,
            content,
        })
    }
}

impl SaveFileCommand {
    /// Save 必须携带非根路径、受控 revision 与一次性键，非法输入不能进入 CAS 事务。
    pub(crate) fn new(
        relative_path: String,
        expected_revision: FileRevision,
        mutation_id: String,
        content: TextContent,
    ) -> Result<Self, WorkspaceError> {
        Ok(Self {
            relative_path: RelativePath::parse_entry(relative_path)?,
            expected_revision,
            mutation_id: MutationId::parse(mutation_id)?,
            content,
        })
    }
}

impl MoveEntryCommand {
    /// Move 在领域边界同时验证两个端点，application 不会接收到只验证一侧的命令。
    pub(crate) fn new(
        from_relative_path: String,
        to_relative_path: String,
        expected_revision: FileRevision,
        mutation_id: String,
    ) -> Result<Self, WorkspaceError> {
        Ok(Self {
            from_relative_path: RelativePath::parse_entry(from_relative_path)?,
            to_relative_path: RelativePath::parse_entry(to_relative_path)?,
            expected_revision,
            mutation_id: MutationId::parse(mutation_id)?,
        })
    }
}

impl TrashPrepareCommand {
    /// Trash prepare 只允许普通条目路径，防止根删除意图进入平台回收站 adapter。
    pub(crate) fn new(
        relative_path: String,
        expected_revision: FileRevision,
        mutation_id: String,
    ) -> Result<Self, WorkspaceError> {
        Ok(Self {
            relative_path: RelativePath::parse_entry(relative_path)?,
            expected_revision,
            mutation_id: MutationId::parse(mutation_id)?,
        })
    }
}

impl TrashCommitCommand {
    /// Commit 将路径、CAS、计划 token 与新幂等键绑定成不可拆分的领域命令。
    pub(crate) fn new(
        relative_path: String,
        expected_revision: FileRevision,
        operation_token: String,
        mutation_id: String,
    ) -> Result<Self, WorkspaceError> {
        Ok(Self {
            relative_path: RelativePath::parse_entry(relative_path)?,
            expected_revision,
            operation_token: TrashToken::parse(operation_token)?,
            mutation_id: MutationId::parse(mutation_id)?,
        })
    }
}

impl DropImportCommand {
    /// Drop 允许目标为 Workspace 根，但源路径只由 opaque token 表示并在 Rust 内部消费。
    pub(crate) fn new(
        destination_relative_path: String,
        expected_revision: FileRevision,
        drop_token: String,
        mutation_id: String,
    ) -> Result<Self, WorkspaceError> {
        Ok(Self {
            destination_relative_path: RelativePath::parse(destination_relative_path)?,
            expected_revision,
            drop_token: DropToken::parse(drop_token)?,
            mutation_id: MutationId::parse(mutation_id)?,
        })
    }
}
