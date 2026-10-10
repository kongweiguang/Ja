// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Strict DTOs for frozen code review Turns and reports.

use serde::{Deserialize, Serialize};
use std::collections::HashSet;

const MAX_REPORT_BYTES: usize = 3 * 1024 * 1024;
const MAX_REVIEW_FILES: u64 = 2_000;
const MAX_REVIEW_BYTES: u64 = 64 * 1024 * 1024;
const MAX_REVIEW_FINDINGS: usize = 2_000;

/// 区分必需可空字段的 null 与字段缺失，保持 JSON 闭集语义。
fn required_option<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(deserializer)
}

/// Review remains an ordinary user-visible Turn, admitted with an immutable snapshot attachment.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewStartParams {
    pub thread_id: String,
    pub expected_thread_revision: u64,
    pub operation_key: String,
    pub snapshot_attachment_id: String,
}

impl ReviewStartParams {
    /// 启动审查 Turn 前校验 CAS 版本与身份，避免无效请求进入副作用边界。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.thread_id, "thr_", 100)
            || self.expected_thread_revision > 9_007_199_254_740_991
            || !valid_operation_key(&self.operation_key)
            || !valid_prefixed(&self.snapshot_attachment_id, "att_", 128)
        {
            return Err("review start parameters are invalid");
        }
        Ok(())
    }
}

/// Accepted review Turn identity; `queued` reflects admission rather than report completion.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewStartResult {
    pub thread_id: String,
    pub turn_id: String,
    pub thread_revision: u64,
    pub queued: bool,
}

impl ReviewStartResult {
    /// 将受理回执绑定到请求 Thread 与 CAS 版本，防止返回其它会话的 Turn 身份。
    pub fn validate_for(&self, request: &ReviewStartParams) -> Result<(), &'static str> {
        request.validate()?;
        if self.thread_id != request.thread_id
            || !valid_prefixed(&self.turn_id, "turn_", 101)
            || self.thread_revision < request.expected_thread_revision
            || self.thread_revision > 9_007_199_254_740_991
        {
            return Err("review start result is not bound to its request");
        }
        Ok(())
    }
}

/// Review read always names a durable Thread/Turn pair, never a mutable working tree selector.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewReadParams {
    pub thread_id: String,
    pub turn_id: String,
}

impl ReviewReadParams {
    /// 读取冻结报告前校验持久 Thread/Turn 身份，不接受可变工作树选择器。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.thread_id, "thr_", 100)
            || !valid_prefixed(&self.turn_id, "turn_", 101)
        {
            return Err("review read parameters are invalid");
        }
        Ok(())
    }
}

/// Lowercase durable Turn states shared with ordinary conversation history.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewTurnStatus {
    Queued,
    Running,
    WaitingInput,
    WaitingApproval,
    Suspended,
    Completed,
    Failed,
    Cancelled,
}

/// Read projection with an explicit null report while the Turn is not complete.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewReadResult {
    pub thread_id: String,
    pub turn_id: String,
    pub status: ReviewTurnStatus,
    #[serde(deserialize_with = "required_option")]
    pub report: Option<ReviewReport>,
}

impl ReviewReadResult {
    /// 将报告绑定到指定 Turn，并要求 completed 终态一定有已持久化报告。
    pub fn validate_for(&self, request: &ReviewReadParams) -> Result<(), &'static str> {
        request.validate()?;
        if self.thread_id != request.thread_id || self.turn_id != request.turn_id {
            return Err("review result crossed its Thread or Turn identity");
        }
        match (&self.status, &self.report) {
            (ReviewTurnStatus::Completed, None) => Err("completed review lacks its report"),
            (_, Some(report)) => report.validate(),
            _ => Ok(()),
        }
    }
}

/// Frozen, hash-bound review output; identifiers cannot be supplied by the model.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewReport {
    pub report_id: String,
    pub snapshot_id: String,
    pub snapshot_hash: String,
    pub revision: String,
    pub summary: String,
    pub coverage: ReviewCoverage,
    pub findings: Vec<ReviewFinding>,
}

impl ReviewReport {
    /// 校验冻结快照身份、报告预算和发现项唯一性，确保每条发现都落在准确的路径与 Git 层覆盖中。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_digest_id(&self.report_id, "report_")
            || !valid_digest_id(&self.snapshot_id, "rev_")
            || !valid_digest(&self.snapshot_hash)
            || !valid_digest(&self.revision)
        {
            return Err("review report identity is invalid");
        }
        if code_points(&self.summary) > 4_096
            || self.summary.chars().any(|character| character == '\0')
            || self.findings.len() > MAX_REVIEW_FINDINGS
        {
            return Err("review report text or finding limit is invalid");
        }
        if serde_json::to_vec(self).map_or(true, |bytes| bytes.len() > MAX_REPORT_BYTES) {
            return Err("review report exceeds its UTF-8 byte budget");
        }
        self.coverage.validate()?;
        let covered_files: HashSet<(&str, ReviewLayer)> = self
            .coverage
            .files_reviewed
            .iter()
            .map(|file| (file.path.as_str(), file.layer))
            .collect();
        let mut finding_ids = HashSet::with_capacity(self.findings.len());
        for finding in &self.findings {
            finding.validate()?;
            if !covered_files.contains(&(finding.path.as_str(), finding.layer))
                || !finding_ids.insert(finding.finding_id.as_str())
            {
                return Err("review finding is duplicate or outside covered files");
            }
        }
        Ok(())
    }
}

/// Coverage is split between the immutable source snapshot and files actually reviewed by the model.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewCoverage {
    pub complete: bool,
    pub files: u64,
    pub bytes: u64,
    pub max_files: u64,
    pub max_bytes: u64,
    pub partial_reasons: Vec<ReviewPartialReason>,
    pub files_reviewed: Vec<ReviewCoverageFile>,
    pub review_complete: bool,
}

impl ReviewCoverage {
    /// 分别复核快照完整度和模型覆盖度，避免模型输出改写来源快照事实。
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.files > MAX_REVIEW_FILES
            || self.bytes > MAX_REVIEW_BYTES
            || self.max_files != MAX_REVIEW_FILES
            || self.max_bytes != MAX_REVIEW_BYTES
            || self.files_reviewed.len() > MAX_REVIEW_FILES as usize
            || self.files_reviewed.len() as u64 > self.files
            || self.complete != self.partial_reasons.is_empty()
            || self.review_complete
                != (self.complete && self.files_reviewed.len() as u64 == self.files)
        {
            return Err("review coverage limits or completeness are inconsistent");
        }
        let mut partial_reasons = HashSet::with_capacity(self.partial_reasons.len());
        if self
            .partial_reasons
            .iter()
            .any(|reason| !partial_reasons.insert(*reason))
        {
            return Err("review coverage repeats a partial reason");
        }
        let mut files = HashSet::with_capacity(self.files_reviewed.len());
        for file in &self.files_reviewed {
            if !valid_relative_path(&file.path) || !files.insert((file.path.as_str(), file.layer)) {
                return Err("review coverage contains an invalid or duplicate file");
            }
        }
        Ok(())
    }
}

/// File and snapshot reasons use the same stable closed vocabulary as the native snapshot builder.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewPartialReason {
    BinaryFile,
    NonUtf8,
    FileLimit,
    ByteLimit,
    ContentUnavailable,
    PatchUnavailable,
}

/// A file identity includes its Git layer because one path may be both staged and modified again.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewCoverageFile {
    pub path: String,
    pub layer: ReviewLayer,
}

/// Review file layers mirror the immutable snapshot, not a UI grouping label.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewLayer {
    Staged,
    Unstaged,
    Untracked,
    Comparison,
}

/// One evidence-backed finding with a location tied to the frozen source snapshot.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewFinding {
    pub finding_id: String,
    pub severity: ReviewSeverity,
    pub title: String,
    pub body: String,
    pub path: String,
    pub layer: ReviewLayer,
    pub start_line: u64,
    pub end_line: u64,
    pub side: ReviewSide,
    pub evidence: String,
}

impl ReviewFinding {
    /// 限定发现文本与行号范围，并要求位置属于被冻结且已覆盖的文件。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_digest_id(&self.finding_id, "finding_")
            || !valid_relative_path(&self.path)
            || self.start_line == 0
            || self.end_line < self.start_line
            || code_points(&self.title) > 512
            || code_points(&self.body) > 16_384
            || code_points(&self.evidence) > 4_096
            || [&self.title, &self.body, &self.evidence]
                .iter()
                .any(|text| text.chars().any(|character| character == '\0'))
        {
            return Err("review finding identity, range or text is invalid");
        }
        Ok(())
    }
}

/// Severity is ordered in product display, with P0 reserved for critical findings.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ReviewSeverity {
    P0,
    P1,
    P2,
    P3,
}

/// Line side selects the base or current immutable file contents during Java validation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewSide {
    Base,
    Current,
}

/// 校验带固定前缀的摘要身份，不解码或信任调用方附带的内容。
fn valid_digest_id(value: &str, prefix: &str) -> bool {
    value.strip_prefix(prefix).is_some_and(valid_digest_suffix)
}

/// 将快照摘要限制为固定长度的小写十六进制，确保跨端比较稳定。
fn valid_digest(value: &str) -> bool {
    valid_lower_hex(value, 64)
}

/// 无分配地校验 24 位小写十六进制身份后缀。
fn valid_digest_suffix(value: &str) -> bool {
    valid_lower_hex(value, 24)
}

/// 按实际身份长度校验小写十六进制，摘要和短 ID 后缀不能混用长度规则。
fn valid_lower_hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

/// 按 Unicode 标量计数，与 Java 对用户可见文本的 code point 限值保持一致。
fn code_points(value: &str) -> usize {
    value.chars().count()
}

/// 将模型可见位置限制在仓库相对路径语法内，拒绝盘符、反斜杠和上级跳转。
fn valid_relative_path(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 32_768
        && !value.starts_with('/')
        && !value.starts_with('\\')
        && !value.contains('\\')
        && !value.contains(':')
        && !value.chars().any(char::is_control)
        && value
            .split('/')
            .all(|segment| !segment.is_empty() && segment != "." && segment != "..")
}

/// 在 RPC 边界校验不透明身份字符集，不赋予其路径或文件系统含义。
fn valid_prefixed(value: &str, prefix: &str, maximum: usize) -> bool {
    let Some(suffix) = value.strip_prefix(prefix) else {
        return false;
    };
    !suffix.is_empty()
        && value.len() <= maximum
        && suffix.as_bytes()[0].is_ascii_alphanumeric()
        && suffix
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// 与 JA-RPC 持久化变更请求共用同一小写随机操作键格式。
fn valid_operation_key(value: &str) -> bool {
    value.len() == 35
        && value.starts_with("op_")
        && value.as_bytes()[3..]
            .iter()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(byte))
}
