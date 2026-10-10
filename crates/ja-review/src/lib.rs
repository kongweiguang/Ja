// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Shared, bounded evidence envelope for AI code review in desktop and CLI clients.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::io::{self, Write};
use thiserror::Error;

mod collection;

pub use collection::{
    SnapshotCancellation, SnapshotCollectionError, create_snapshot, resolve_commit_id,
};

pub const SNAPSHOT_VERSION: u32 = 1;
pub const MAX_SNAPSHOT_FILES: usize = 2_000;
pub const MAX_SNAPSHOT_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_SNAPSHOT_JSON_BYTES: usize = 100 * 1024 * 1024;
const MAX_SNAPSHOT_METADATA_BYTES: usize = 16 * 1024;

/// Git comparison identity is resolved to an object ID before it crosses the client boundary.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    rename_all = "snake_case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum ReviewSnapshotSource {
    Uncommitted,
    Staged,
    Unstaged,
    Base {
        #[serde(rename = "commitId")]
        base_commit_id: String,
    },
    Commit {
        commit_id: String,
    },
}

impl ReviewSnapshotSource {
    /// Reject abbreviated IDs and ref syntax so every client reviews the same immutable objects.
    fn validate(&self) -> Result<(), SnapshotError> {
        match self {
            Self::Uncommitted | Self::Staged | Self::Unstaged => Ok(()),
            Self::Base { base_commit_id } => validate_commit_id(base_commit_id),
            Self::Commit { commit_id } => validate_commit_id(commit_id),
        }
    }
}

/// File status is closed so a newer client cannot silently reinterpret an unknown Git state.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewFileStatus {
    Added,
    Modified,
    Deleted,
    Renamed,
    Copied,
    Conflicted,
    Untracked,
}

/// Layer preserves the existing Review distinction between index, worktree, and comparison data.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewFileLayer {
    Staged,
    Unstaged,
    Untracked,
    Comparison,
}

/// Partial reasons make excluded evidence visible to the model and prevent a partial scan claiming completeness.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SnapshotPartialReason {
    BinaryFile,
    NonUtf8,
    FileLimit,
    ByteLimit,
    ContentUnavailable,
    PatchUnavailable,
}

/// Coverage totals count source evidence bytes, while the attachment digest covers the encoded JSON envelope.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SnapshotCoverage {
    pub complete: bool,
    pub files: usize,
    pub bytes: usize,
    pub max_files: usize,
    pub max_bytes: usize,
    pub partial_reasons: Vec<SnapshotPartialReason>,
}

/// One immutable repository file entry; finding lines use `old` with `base_content` and `new` with `content`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewSnapshotFile {
    pub path: String,
    pub old_path: Option<String>,
    pub status: ReviewFileStatus,
    pub layer: ReviewFileLayer,
    pub binary: bool,
    pub base_content: Option<String>,
    pub content: Option<String>,
    pub patch: Option<String>,
    pub base_sha256: Option<String>,
    pub content_sha256: Option<String>,
    pub patch_sha256: Option<String>,
}

/// Canonical snapshot shared by clients; all fields and file order are stable for reproducible hashing.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewSnapshot {
    pub version: u32,
    pub snapshot_id: String,
    pub workspace_id: String,
    pub source: ReviewSnapshotSource,
    pub revision: String,
    pub snapshot_hash: String,
    pub coverage: SnapshotCoverage,
    pub files: Vec<ReviewSnapshotFile>,
}

/// Raw bytes are retained only until strict UTF-8 classification and per-file hashes are established.
#[derive(Debug, Clone)]
pub struct SnapshotFileInput {
    pub path: String,
    pub old_path: Option<String>,
    pub status: ReviewFileStatus,
    pub layer: ReviewFileLayer,
    pub binary: bool,
    pub base_content: Option<Vec<u8>>,
    pub content: Option<Vec<u8>>,
    pub patch: Option<Vec<u8>>,
}

/// Builder enforces one shared file/byte budget before any caller can serialize an oversized snapshot.
#[derive(Debug)]
pub struct ReviewSnapshotBuilder {
    workspace_id: String,
    source: ReviewSnapshotSource,
    source_revision: String,
    files: Vec<ReviewSnapshotFile>,
    identities: BTreeSet<(String, ReviewFileLayer)>,
    bytes: usize,
    encoded_files_bytes: usize,
    partial_reasons: BTreeSet<SnapshotPartialReason>,
}

/// Snapshot construction errors are stable categories and never retain command output or repository paths.
#[derive(Debug, Error, Clone, Copy, PartialEq, Eq)]
pub enum SnapshotError {
    #[error("invalid review snapshot identity")]
    InvalidIdentity,
    #[error("invalid review file path")]
    InvalidPath,
    #[error("duplicate review file layer")]
    DuplicateFile,
    #[error("snapshot serialization failed")]
    Serialization,
    #[error("snapshot envelope exceeds attachment capacity")]
    EnvelopeTooLarge,
}

impl ReviewSnapshotBuilder {
    /// Bind the envelope to a workspace and the reader's before/after revision fence.
    pub fn new(
        workspace_id: String,
        source: ReviewSnapshotSource,
        source_revision: String,
    ) -> Result<Self, SnapshotError> {
        if !valid_workspace_id(&workspace_id)
            || source_revision.is_empty()
            || source_revision.len() > 256
            || source_revision.chars().any(char::is_control)
        {
            return Err(SnapshotError::InvalidIdentity);
        }
        source.validate()?;
        Ok(Self {
            workspace_id,
            source,
            source_revision,
            files: Vec::new(),
            identities: BTreeSet::new(),
            bytes: 0,
            encoded_files_bytes: 0,
            partial_reasons: BTreeSet::new(),
        })
    }

    /// Record an omitted surface explicitly so file/byte exhaustion cannot masquerade as a complete review.
    pub fn mark_partial(&mut self, reason: SnapshotPartialReason) {
        self.partial_reasons.insert(reason);
    }

    /// Let collectors stop before reading evidence that cannot fit the envelope-wide byte budget.
    pub fn remaining_bytes(&self) -> usize {
        MAX_SNAPSHOT_BYTES.saturating_sub(self.bytes)
    }

    /// Let collectors avoid starting more Git and filesystem work after the file budget is full.
    pub fn remaining_files(&self) -> usize {
        MAX_SNAPSHOT_FILES.saturating_sub(self.files.len())
    }

    /// 按原始证据和 JSON 转义后附件体积共同准入，避免可导出的来源数据生成不可导入的快照。
    pub fn push_file(&mut self, input: SnapshotFileInput) -> Result<bool, SnapshotError> {
        validate_relative_path(&input.path)?;
        if let Some(old_path) = &input.old_path {
            validate_relative_path(old_path)?;
        }
        let identity = (input.path.clone(), input.layer);
        if self.identities.contains(&identity) {
            return Err(SnapshotError::DuplicateFile);
        }
        if self.files.len() >= MAX_SNAPSHOT_FILES {
            self.mark_partial(SnapshotPartialReason::FileLimit);
            return Ok(false);
        }

        let evidence_bytes = input
            .base_content
            .as_ref()
            .map_or(0, Vec::len)
            .saturating_add(input.content.as_ref().map_or(0, Vec::len))
            .saturating_add(input.patch.as_ref().map_or(0, Vec::len));
        if self.bytes.saturating_add(evidence_bytes) > MAX_SNAPSHOT_BYTES {
            self.mark_partial(SnapshotPartialReason::ByteLimit);
            return Ok(false);
        }

        let (mut base_content, base_sha256, base_binary, base_non_utf8) =
            classify_text(input.base_content.as_deref());
        let (mut content, content_sha256, content_binary, content_non_utf8) =
            classify_text(input.content.as_deref());
        let (mut patch, patch_sha256, patch_binary, patch_non_utf8) =
            classify_text(input.patch.as_deref());
        let binary = input.binary || base_binary || content_binary || patch_binary;
        if binary {
            self.mark_partial(SnapshotPartialReason::BinaryFile);
            base_content = None;
            content = None;
            patch = None;
        }
        if input.base_content.is_some() && base_content.is_none() && !base_binary {
            self.mark_partial(SnapshotPartialReason::ContentUnavailable);
        }
        if input.content.is_some() && content.is_none() && !content_binary {
            self.mark_partial(SnapshotPartialReason::ContentUnavailable);
        }
        if input.patch.is_some() && patch.is_none() && !patch_binary {
            self.mark_partial(SnapshotPartialReason::PatchUnavailable);
        }
        if base_non_utf8 || content_non_utf8 || patch_non_utf8 {
            self.mark_partial(SnapshotPartialReason::NonUtf8);
        }

        let file = ReviewSnapshotFile {
            path: input.path,
            old_path: input.old_path,
            status: input.status,
            layer: input.layer,
            binary,
            base_content,
            content,
            patch,
            base_sha256,
            content_sha256,
            patch_sha256,
        };
        let serialized_file_bytes = json_byte_len(&file)?;
        let separator_bytes = usize::from(!self.files.is_empty());
        let next_encoded_files_bytes = self
            .encoded_files_bytes
            .saturating_add(separator_bytes)
            .saturating_add(serialized_file_bytes);
        if next_encoded_files_bytes.saturating_add(MAX_SNAPSHOT_METADATA_BYTES)
            > MAX_SNAPSHOT_JSON_BYTES
        {
            self.mark_partial(SnapshotPartialReason::ByteLimit);
            return Ok(false);
        }

        self.bytes = self.bytes.saturating_add(evidence_bytes);
        self.encoded_files_bytes = next_encoded_files_bytes;
        self.identities.insert(identity);
        self.files.push(file);
        Ok(true)
    }

    /// 冻结确定性文件顺序并复核完整 envelope 上限，避免元数据预留与实际编码发生偏差。
    pub fn finish(mut self) -> Result<ReviewSnapshot, SnapshotError> {
        self.files.sort_by(|left, right| {
            left.path
                .cmp(&right.path)
                .then_with(|| left.layer.cmp(&right.layer))
        });
        let coverage = SnapshotCoverage {
            complete: self.partial_reasons.is_empty(),
            files: self.files.len(),
            bytes: self.bytes,
            max_files: MAX_SNAPSHOT_FILES,
            max_bytes: MAX_SNAPSHOT_BYTES,
            partial_reasons: self.partial_reasons.into_iter().collect(),
        };
        let revision = digest_json(&RevisionMaterial {
            workspace_id: &self.workspace_id,
            source: &self.source,
            source_revision: &self.source_revision,
            files: &self.files,
        })?;
        let snapshot_id = format!("rev_{}", &revision[..24]);
        let snapshot_hash = digest_json(&SnapshotHashMaterial {
            version: SNAPSHOT_VERSION,
            workspace_id: &self.workspace_id,
            source: &self.source,
            revision: &revision,
            coverage: &coverage,
            files: &self.files,
        })?;
        let snapshot = ReviewSnapshot {
            version: SNAPSHOT_VERSION,
            snapshot_id,
            workspace_id: self.workspace_id,
            source: self.source,
            revision,
            snapshot_hash,
            coverage,
            files: self.files,
        };
        if json_byte_len(&snapshot)? > MAX_SNAPSHOT_JSON_BYTES {
            return Err(SnapshotError::EnvelopeTooLarge);
        }
        Ok(snapshot)
    }
}

impl ReviewSnapshot {
    /// 仅为已通过附件预算的快照分配完整字节，返回值是 ingress 校验的精确 SHA-256 输入。
    pub fn canonical_json(&self) -> Result<Vec<u8>, SnapshotError> {
        if json_byte_len(self)? > MAX_SNAPSHOT_JSON_BYTES {
            return Err(SnapshotError::EnvelopeTooLarge);
        }
        serde_json::to_vec(self).map_err(|_| SnapshotError::Serialization)
    }

    /// 重算 envelope 摘要且核验附件容量，不信任反序列化对象携带的 hash 与体积声明。
    pub fn verify_hash(&self) -> Result<bool, SnapshotError> {
        if self.version != SNAPSHOT_VERSION
            || !valid_workspace_id(&self.workspace_id)
            || self.source.validate().is_err()
            || !valid_digest(&self.revision)
            || !valid_digest(&self.snapshot_hash)
            || self.snapshot_id != format!("rev_{}", &self.revision[..24])
            || self.coverage.files != self.files.len()
            || self.coverage.files > MAX_SNAPSHOT_FILES
            || self.coverage.bytes > MAX_SNAPSHOT_BYTES
            || self.coverage.max_files != MAX_SNAPSHOT_FILES
            || self.coverage.max_bytes != MAX_SNAPSHOT_BYTES
            || self.coverage.complete != self.coverage.partial_reasons.is_empty()
        {
            return Ok(false);
        }
        if json_byte_len(self)? > MAX_SNAPSHOT_JSON_BYTES {
            return Ok(false);
        }
        let mut identities = BTreeSet::new();
        for file in &self.files {
            if validate_relative_path(&file.path).is_err()
                || file
                    .old_path
                    .as_deref()
                    .is_some_and(|path| validate_relative_path(path).is_err())
                || !identities.insert((file.path.clone(), file.layer))
                || !matches_file_hash(&file.base_content, &file.base_sha256)
                || !matches_file_hash(&file.content, &file.content_sha256)
                || !matches_file_hash(&file.patch, &file.patch_sha256)
                || (file.binary
                    && (file.base_content.is_some()
                        || file.content.is_some()
                        || file.patch.is_some()))
            {
                return Ok(false);
            }
        }
        let computed = digest_json(&SnapshotHashMaterial {
            version: self.version,
            workspace_id: &self.workspace_id,
            source: &self.source,
            revision: &self.revision,
            coverage: &self.coverage,
            files: &self.files,
        })?;
        Ok(computed == self.snapshot_hash)
    }
}

/// 通过计数型 writer 获取 serde 的真实 JSON 字节数，避免为预算检查再复制大型文件正文。
fn json_byte_len(value: &impl Serialize) -> Result<usize, SnapshotError> {
    let mut counter = JsonByteCounter(0);
    serde_json::to_writer(&mut counter, value).map_err(|_| SnapshotError::Serialization)?;
    Ok(counter.0)
}

/// serde 每次写入只累计长度；它不保存正文，因此大快照的准入检查保持有界内存。
struct JsonByteCounter(usize);

impl Write for JsonByteCounter {
    /// 只累计已编码字节数，饱和运算让异常超大输入仍走预算拒绝而不发生整数回绕。
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        self.0 = self.0.saturating_add(buffer.len());
        Ok(buffer.len())
    }

    /// 计数 writer 没有缓冲区需要刷新。
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RevisionMaterial<'a> {
    workspace_id: &'a str,
    source: &'a ReviewSnapshotSource,
    source_revision: &'a str,
    files: &'a [ReviewSnapshotFile],
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotHashMaterial<'a> {
    version: u32,
    workspace_id: &'a str,
    source: &'a ReviewSnapshotSource,
    revision: &'a str,
    coverage: &'a SnapshotCoverage,
    files: &'a [ReviewSnapshotFile],
}

/// Compute compact serde JSON then SHA-256 so every client shares one byte-level digest rule.
fn digest_json(value: &impl Serialize) -> Result<String, SnapshotError> {
    let bytes = serde_json::to_vec(value).map_err(|_| SnapshotError::Serialization)?;
    Ok(hex_digest(Sha256::digest(bytes)))
}

/// Convert strict UTF-8 evidence while preserving its raw-byte hash and line endings.
fn classify_text(bytes: Option<&[u8]>) -> (Option<String>, Option<String>, bool, bool) {
    let Some(bytes) = bytes else {
        return (None, None, false, false);
    };
    let hash = hex_digest(Sha256::digest(bytes));
    if bytes.contains(&0) {
        return (None, Some(hash), true, std::str::from_utf8(bytes).is_err());
    }
    match std::str::from_utf8(bytes) {
        Ok(text) => (Some(text.to_owned()), Some(hash), false, false),
        Err(_) => (None, Some(hash), true, true),
    }
}

/// Path admission mirrors the Workspace Git path contract before any model-visible location is created.
pub fn validate_relative_path(path: &str) -> Result<(), SnapshotError> {
    if path.is_empty()
        || path.len() > 32_768
        || path.starts_with('/')
        || path.starts_with('\\')
        || path.contains('\\')
        || path.contains(':')
        || path.chars().any(char::is_control)
        || path
            .split('/')
            .any(|segment| segment.is_empty() || segment == "." || segment == "..")
    {
        return Err(SnapshotError::InvalidPath);
    }
    Ok(())
}

/// Workspace identity is opaque and must not smuggle a filesystem path into the snapshot contract.
fn valid_workspace_id(value: &str) -> bool {
    value.starts_with("ws_")
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
}

/// Commit selectors require the full SHA-1 or SHA-256 object identity returned by Git.
fn validate_commit_id(value: &str) -> Result<(), SnapshotError> {
    if matches!(value.len(), 40 | 64)
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        Ok(())
    } else {
        Err(SnapshotError::InvalidIdentity)
    }
}

/// Validate a lowercase 256-bit digest before using it in an identifier or a slice boundary.
fn valid_digest(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

/// Match optional content to its raw-byte digest and reject hashes on absent evidence.
fn matches_file_hash(content: &Option<String>, digest: &Option<String>) -> bool {
    match (content, digest) {
        (Some(content), Some(digest)) => hex_digest(Sha256::digest(content.as_bytes())) == *digest,
        (None, None) => true,
        (None, Some(digest)) => valid_digest(digest),
        (Some(_), None) => false,
    }
}

/// Lowercase hexadecimal keeps digest representation identical across JVM, Rust, and TypeScript.
fn hex_digest(digest: impl AsRef<[u8]>) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let bytes = digest.as_ref();
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}
