// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::{EntryKind, WorkspaceError};

const MAX_RELATIVE_PATH_BYTES: usize = 4 * 1024;
const MAX_OPAQUE_ID_BYTES: usize = 128;

/// Workspace 相对路径在进入 application 前完成纯词法准入，确保所有 IO adapter 共享同一套输入不变量。
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) struct RelativePath(String);

impl RelativePath {
    /// 允许空字符串表示 Workspace 根；是否允许对根执行具体用例由命令构造器进一步收紧。
    pub(crate) fn parse(value: String) -> Result<Self, WorkspaceError> {
        validate_relative_path(&value)?;
        Ok(Self(value))
    }

    /// mutation 不能把 Workspace 根当作普通条目，因而在领域边界排除该非法状态。
    pub(crate) fn parse_entry(value: String) -> Result<Self, WorkspaceError> {
        let path = Self::parse(value)?;
        if path.is_root() {
            return Err(WorkspaceError::InvalidRelativePath);
        }
        Ok(path)
    }

    /// 借用 slash-separated 表示，避免基础设施为了调用系统 API复制路径所有权。
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }

    /// 根路径只由空字符串表达，禁止再引入 `.` 等第二套别名。
    pub(crate) fn is_root(&self) -> bool {
        self.0.is_empty()
    }
}

/// Mutation id 是一次性幂等键；领域层限制长度与控制字符，避免 ledger 接受不可审计状态。
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) struct MutationId(String);

impl MutationId {
    /// 构造时完成唯一准入，interface 与 infrastructure 不再各自维护一套长度规则。
    pub(crate) fn parse(value: String) -> Result<Self, WorkspaceError> {
        validate_opaque_id(&value).map_err(|_| WorkspaceError::InvalidMutationId)?;
        Ok(Self(value))
    }

    /// ledger 只借用键值，避免准入与查重之间额外分配。
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

/// Trash token 只能由 Rust 计划表签发并被消费一次，类型隔离可防止与其它 token 误用。
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) struct TrashToken(String);

impl TrashToken {
    /// IPC 返回的 opaque token 仍是不可信输入，必须在进入用例前验证有界性。
    pub(crate) fn parse(value: String) -> Result<Self, WorkspaceError> {
        validate_opaque_id(&value).map_err(|_| WorkspaceError::TrashTokenInvalid)?;
        Ok(Self(value))
    }

    /// 计划表按原始 token 查找，不暴露内部字符串所有权。
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

/// Native Drop token 与 Trash token 分型，防止两个一次性能力在 application 层串用。
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) struct DropToken(String);

impl DropToken {
    /// Drop capability 使用与其它 opaque id 相同的资源上限，但映射为独立领域错误。
    pub(crate) fn parse(value: String) -> Result<Self, WorkspaceError> {
        validate_opaque_id(&value).map_err(|_| WorkspaceError::DropTokenInvalid)?;
        Ok(Self(value))
    }

    /// 计划消费只需要借用 key，避免重复分配短生命周期 token。
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

/// 文件修订是 CAS 证据；受控构造保证 `sha256` 只能是完整的 256-bit 十六进制摘要。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileRevision {
    kind: EntryKind,
    size: u64,
    modified_unix_millis: Option<u128>,
    sha256: Option<String>,
}

impl FileRevision {
    /// 外部输入必须经过完整校验，避免畸形摘要进入 CAS 比较并形成永远无法匹配的状态。
    pub fn try_new(
        kind: EntryKind,
        size: u64,
        modified_unix_millis: Option<u128>,
        sha256: Option<String>,
    ) -> Result<Self, WorkspaceError> {
        if sha256.as_ref().is_some_and(|hash| {
            hash.len() != 64 || !hash.bytes().all(|byte| byte.is_ascii_hexdigit())
        }) {
            return Err(WorkspaceError::InvalidRevision);
        }
        Ok(Self {
            kind,
            size,
            modified_unix_millis,
            sha256,
        })
    }

    /// 基础设施同样通过受控入口构造权威 revision；摘要生成错误必须显式传播而非降级。
    pub(crate) fn new(
        kind: EntryKind,
        size: u64,
        modified_unix_millis: Option<u128>,
        sha256: Option<String>,
    ) -> Result<Self, WorkspaceError> {
        Self::try_new(kind, size, modified_unix_millis, sha256)
    }

    /// 条目类型参与 CAS，防止文件被目录或特殊节点替换后仍被视为同一 revision。
    pub fn kind(&self) -> EntryKind {
        self.kind
    }

    /// 字节数用于有界读取与 CAS 快速比较，不代表内容 hash。
    pub fn size(&self) -> u64 {
        self.size
    }

    /// 时间戳只是组合证据的一部分，缺失时仍由类型、大小和可选 hash 保持语义。
    pub fn modified_unix_millis(&self) -> Option<u128> {
        self.modified_unix_millis
    }

    /// 摘要以借用形式公开，避免 DTO 投影之外复制可能频繁读取的修订值。
    pub fn sha256(&self) -> Option<&str> {
        self.sha256.as_deref()
    }

    /// DTO 投影消费 revision 时移交摘要所有权，保持边界映射直接且无额外 clone。
    pub(crate) fn into_parts(self) -> (EntryKind, u64, Option<u128>, Option<String>) {
        (self.kind, self.size, self.modified_unix_millis, self.sha256)
    }
}

/// 纯词法校验不访问文件系统；物理 containment、link 与 identity 仍由 infrastructure 独占。
fn validate_relative_path(value: &str) -> Result<(), WorkspaceError> {
    if value.contains('\0')
        || value.len() > MAX_RELATIVE_PATH_BYTES
        || value.contains(':')
        || value.contains('\\')
        || value.starts_with('/')
        || value.ends_with('/')
    {
        return Err(WorkspaceError::InvalidRelativePath);
    }
    for component in value.split('/') {
        if component.is_empty() {
            if value.is_empty() {
                continue;
            }
            return Err(WorkspaceError::InvalidRelativePath);
        }
        if component == "." || component == ".." || is_windows_ambiguous_component(component) {
            return Err(WorkspaceError::InvalidRelativePath);
        }
    }
    Ok(())
}

/// Windows 路径别名在所有平台都按同一协议规则拒绝，避免跨平台请求产生不同含义。
fn is_windows_ambiguous_component(value: &str) -> bool {
    if value.ends_with(['.', ' ']) {
        return true;
    }
    let stem = value
        .split('.')
        .next()
        .unwrap_or_default()
        .trim_end_matches(['.', ' '])
        .to_ascii_uppercase();
    matches!(
        stem.as_str(),
        "CON"
            | "PRN"
            | "AUX"
            | "NUL"
            | "COM1"
            | "COM2"
            | "COM3"
            | "COM4"
            | "COM5"
            | "COM6"
            | "COM7"
            | "COM8"
            | "COM9"
            | "LPT1"
            | "LPT2"
            | "LPT3"
            | "LPT4"
            | "LPT5"
            | "LPT6"
            | "LPT7"
            | "LPT8"
            | "LPT9"
    )
}

/// Opaque id 只允许有界且可审计的非控制字符；具体来源与一次性语义由各值对象区分。
fn validate_opaque_id(value: &str) -> Result<(), ()> {
    if value.is_empty() || value.len() > MAX_OPAQUE_ID_BYTES || value.chars().any(char::is_control)
    {
        return Err(());
    }
    Ok(())
}
