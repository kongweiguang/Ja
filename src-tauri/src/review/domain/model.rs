// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 领域值对象与事实模型。
//
// 领域层不承担 serde 或 Tauri wire 兼容；interface 必须先把不可信字符串转换为这里的
// 值对象。这样 revision、operation、file/hunk target 与 source selector 只有一套校验，
// application 和 infrastructure 可以假定非法状态已经被类型排除。

use std::fmt::{Display, Formatter};

/// 单个 Review file 可进入 WebView projection 的最大 patch bytes。
pub const MAX_REVIEW_DIFF_BYTES: usize = 2 * 1024 * 1024;
/// 单个 Review file 保留的最大 rendered diff 行数。
pub const MAX_REVIEW_DIFF_LINES: usize = 20_000;
/// 单个 Review snapshot 允许保留的最大文件数。
pub const MAX_REVIEW_FILES: usize = 100_000;

const MAX_REVISION_BYTES: usize = 256;
const MAX_TARGET_ID_BYTES: usize = 256;
const MAX_OPERATION_ID_BYTES: usize = 128;
const MAX_CATALOG_COMMITS: usize = 100;
const DEFAULT_CATALOG_COMMITS: usize = 50;

/// 值对象校验失败只表达“领域值非法”，不携带原始输入，避免边界错误泄漏内容。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReviewValueError;

/// 校验 opaque identity 的共同安全约束；具体最大长度仍由各值对象自行决定。
fn validate_opaque(value: &str, max_bytes: usize) -> Result<(), ReviewValueError> {
    if value.is_empty()
        || value.len() > max_bytes
        || value.bytes().any(|byte| byte < 0x20 || byte == 0x7f)
    {
        return Err(ReviewValueError);
    }
    Ok(())
}

macro_rules! opaque_value {
    ($name:ident, $limit:expr, $reason:literal) => {
        #[doc = $reason]
        #[derive(Debug, Clone, PartialEq, Eq, Hash)]
        pub struct $name(String);

        impl $name {
            /// 在 interface 边界一次性验证长度与控制字符，后续层不再重复处理裸字符串。
            pub fn parse(value: impl Into<String>) -> Result<Self, ReviewValueError> {
                let value = value.into();
                validate_opaque(&value, $limit)?;
                Ok(Self(value))
            }

            /// 只借用已验证值，避免为了 Git 参数或 DTO 投影产生多余 clone。
            pub fn as_str(&self) -> &str {
                &self.0
            }

            /// 仅在 wire 投影或需要取得所有权的 adapter 边界消费值对象。
            pub fn into_string(self) -> String {
                self.0
            }
        }

        impl AsRef<str> for $name {
            /// 让 infrastructure 接收受验证的字符串视图，而不重新开放构造入口。
            fn as_ref(&self) -> &str {
                self.as_str()
            }
        }

        impl Display for $name {
            /// Display 仅用于内部稳定材料，不应直接进入用户可见错误或日志。
            fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
                formatter.write_str(self.as_str())
            }
        }
    };
}

opaque_value!(
    ReviewRevision,
    MAX_REVISION_BYTES,
    "Review snapshot 的 CAS revision；只有 native 生成或边界校验后的值才能进入用例。"
);
opaque_value!(
    ReviewOperationId,
    MAX_OPERATION_ID_BYTES,
    "一次 mutation 的取消身份；有界且不含控制字符，防止注册表被不可信 identity 污染。"
);
opaque_value!(
    ReviewFileId,
    MAX_TARGET_ID_BYTES,
    "当前 revision 内的 opaque file identity；它从不被解释为路径。"
);
opaque_value!(
    ReviewHunkId,
    MAX_TARGET_ID_BYTES,
    "当前 revision 与 file 内的 opaque hunk identity；它从不携带调用方 patch。"
);
opaque_value!(
    ReviewRefId,
    MAX_TARGET_ID_BYTES,
    "Branch source 的受验证 ref selector；Git adapter 仍需执行 ref allowlist 校验。"
);
opaque_value!(
    ReviewCommitId,
    MAX_TARGET_ID_BYTES,
    "Commit source 的受验证 object selector；Git adapter 仍需执行 object allowlist 校验。"
);

/// Catalog 的 commit 窗口是产品资源上限，不能由 interface 与 adapter 各自复制范围判断。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReviewCatalogLimit(usize);

impl ReviewCatalogLimit {
    /// 校验 1..=100 的封闭窗口，避免无界 Git 历史输出进入内存或 IPC。
    pub fn parse(value: usize) -> Result<Self, ReviewValueError> {
        if !(1..=MAX_CATALOG_COMMITS).contains(&value) {
            return Err(ReviewValueError);
        }
        Ok(Self(value))
    }

    /// 为默认 catalog 用例提供唯一产品默认值，interface 不再复制魔法数字。
    pub const fn default_window() -> Self {
        Self(DEFAULT_CATALOG_COMMITS)
    }

    /// 仅在原生 Git 查询边界解包资源上限。
    pub const fn get(self) -> usize {
        self.0
    }
}

/// 选择 Review snapshot 代表的当前 Git 状态或显式 Git 对象范围。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReviewSource {
    Unstaged,
    Staged,
    Branch { ref_id: ReviewRefId },
    Commit { commit_id: ReviewCommitId },
}

impl ReviewSource {
    /// 只读 source 永远不能进入 mutation port，此能力由领域事实而不是 interface 猜测。
    pub const fn is_read_only(&self) -> bool {
        matches!(self, Self::Branch { .. } | Self::Commit { .. })
    }
}

/// 选择一次 Review mutation 的范围；id 必须来自当前 revision。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReviewTarget {
    All,
    File {
        file_id: ReviewFileId,
    },
    Hunk {
        file_id: ReviewFileId,
        hunk_id: ReviewHunkId,
    },
}

/// 选择受支持的封闭写操作，不允许 caller 扩展为任意 Git command。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReviewAction {
    Stage,
    Unstage,
    Revert,
}

/// 文件的封闭状态分类，不暴露 Git human status text。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReviewFileStatus {
    Added,
    Modified,
    Deleted,
    Renamed,
    Copied,
    Untracked,
    Conflict,
}

/// rendered unified-diff line 的封闭符号分类。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReviewLineKind {
    Context,
    Addition,
    Deletion,
    FileHeader,
    HunkHeader,
    NoNewlineMarker,
}

/// Review hunk 中的一条有界 line。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewLine {
    pub kind: ReviewLineKind,
    pub text: String,
}

/// 稳定 hunk projection；raw patch 仅供 native mutation，绝不跨 IPC。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewHunk {
    pub hunk_id: ReviewHunkId,
    pub header: String,
    pub old_start: u64,
    pub old_lines: u64,
    pub new_start: u64,
    pub new_lines: u64,
    pub lines: Vec<ReviewLine>,
    pub(crate) raw_patch: Vec<u8>,
}

/// Review snapshot 中的文件；native 材料不进入 interface DTO。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewFile {
    pub file_id: ReviewFileId,
    pub path: String,
    pub old_path: Option<String>,
    pub status: ReviewFileStatus,
    pub additions: Option<u64>,
    pub deletions: Option<u64>,
    pub binary: bool,
    pub metadata_only: bool,
    pub hunks: Vec<ReviewHunk>,
    pub(crate) patch: Vec<u8>,
    pub(crate) revision_evidence: Vec<u8>,
}

/// Review header 使用的有界聚合统计。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ReviewStats {
    pub files: u64,
    pub additions: u64,
    pub deletions: u64,
    pub binary_files: u64,
    pub truncated_files: u64,
}

/// 每次 query 或 mutation 后返回的权威 snapshot。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewSnapshot {
    pub revision: ReviewRevision,
    pub source: ReviewSource,
    pub files: Vec<ReviewFile>,
    pub stats: ReviewStats,
}

/// lazy single-file diff 响应。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewFileDiff {
    pub revision: ReviewRevision,
    pub source: ReviewSource,
    pub file: ReviewFile,
}

/// catalog discovery 暴露的安全 ref candidate。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewRef {
    pub ref_id: String,
    pub object_id: String,
}

/// catalog discovery 暴露的 commit summary。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewCommit {
    pub commit_id: String,
    pub parents: Vec<String>,
    pub author: String,
    pub subject: String,
    pub authored_at: String,
}

/// 安全 base refs 与 recent commits 的 native catalog。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewCatalog {
    pub head: Option<String>,
    pub branch: Option<String>,
    pub base_refs: Vec<ReviewRef>,
    pub commits: Vec<ReviewCommit>,
}

/// 成功 mutation 的结果；caller 必须丢弃旧 file/hunk id。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewApplyResult {
    pub snapshot: ReviewSnapshot,
}
