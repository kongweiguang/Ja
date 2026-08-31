// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 只读 Git 查询计划与 NUL 输出解析。
//
// 该模块只接受固定 source plan 和 canonical workspace；它不执行 mutation，也不保存事务
// 状态。ref grammar、diff argv 与 name-status 解析集中在这里，避免 façade 重复安全判断。

use crate::review::application::ReviewError;
use crate::review::domain::{MAX_REVIEW_FILES, ReviewFileStatus};
use crate::workspace::WorkspaceHandle;
use std::ffi::OsString;

const MAX_REF_BYTES: usize = 256;

/// 内部 source command plan；ref 进入 enum 前已校验，command 构造保持 typed/bounded。
#[derive(Debug, Clone)]
pub(super) enum SourceKind {
    Unstaged,
    Staged,
    Range { left: String, right: String },
}

/// 一条 NUL-delimited Git name-status record。
#[derive(Debug, Clone)]
pub(super) struct NameStatusRecord {
    pub(super) path: String,
    pub(super) old_path: Option<String>,
    pub(super) status: ReviewFileStatus,
}

/// 对 candidate ref 排序，使 conventional base 优先，并保持 fallback lexical order 稳定。
pub(super) fn base_ref_priority(ref_id: &str) -> u8 {
    match ref_id {
        "origin/HEAD" => 0,
        "main" => 1,
        "master" => 2,
        _ => 3,
    }
}

/// 仅移除 NUL ref field 周围的 CR/LF；不接受其它 whitespace，以维持 ref validation 边界。
pub(super) fn trim_ref_record_terminators(mut value: &[u8]) -> &[u8] {
    while value
        .first()
        .is_some_and(|byte| matches!(byte, b'\r' | b'\n'))
    {
        value = &value[1..];
    }
    while value
        .last()
        .is_some_and(|byte| matches!(byte, b'\r' | b'\n'))
    {
        value = &value[..value.len() - 1];
    }
    value
}

/// Git ref/object selector 到达 Git 前执行严格 grammar 校验。
pub(super) fn validate_ref(value: &str) -> Result<(), ReviewError> {
    if value.is_empty()
        || value.len() > MAX_REF_BYTES
        || value.starts_with('-')
        || value.contains("..")
        || value.contains("@{")
        || value
            .bytes()
            .any(|byte| byte.is_ascii_control() || byte.is_ascii_whitespace())
        || value
            .bytes()
            .any(|byte| matches!(byte, b'~' | b'^' | b':' | b'?' | b'*' | b'[' | b'\\'))
    {
        return Err(ReviewError::InvalidInput);
    }
    Ok(())
}

/// 为 worktree、index 或 two-ref source 构造固定 Git diff argv。
pub(super) fn diff_args(kind: &SourceKind, names_only: bool) -> Vec<OsString> {
    let mut args = vec![
        OsString::from("diff"),
        OsString::from("--no-ext-diff"),
        OsString::from("--no-color"),
        OsString::from("--no-textconv"),
        // name-status 与 patch 必须使用同一 rename 决策；否则冻结文件记录会与 diff block
        // 分裂为 delete/add，无法证明 rename 的行统计和 oldPath 归属。
        OsString::from("--find-renames"),
    ];
    if names_only {
        args.extend([OsString::from("--name-status"), OsString::from("-z")]);
    } else {
        args.push(OsString::from("--binary"));
    }
    match kind {
        SourceKind::Unstaged => {}
        SourceKind::Staged => args.push(OsString::from("--cached")),
        SourceKind::Range { left, right } => {
            args.push(OsString::from(left));
            args.push(OsString::from(right));
        }
    }
    args
}

/// 解析 NUL name-status，并在返回前按 canonical workspace policy 校验每条路径。
pub(super) fn parse_name_status(
    workspace: &WorkspaceHandle,
    bytes: &[u8],
) -> Result<Vec<NameStatusRecord>, ReviewError> {
    let fields = bytes.split(|byte| *byte == 0).collect::<Vec<_>>();
    let mut records = Vec::new();
    let mut index = 0_usize;
    while index < fields.len() {
        let status = fields[index];
        index += 1;
        if status.is_empty() {
            continue;
        }
        let status_text = String::from_utf8(status.to_vec()).map_err(|_| ReviewError::Parse)?;
        let status_kind = status_text
            .as_bytes()
            .first()
            .copied()
            .ok_or(ReviewError::Parse)?;
        let first = fields.get(index).ok_or(ReviewError::Parse)?;
        index += 1;
        let (old_path, path) = if matches!(status_kind, b'R' | b'C') {
            let new_path = fields.get(index).ok_or(ReviewError::Parse)?;
            index += 1;
            (
                Some(String::from_utf8(first.to_vec()).map_err(|_| ReviewError::Parse)?),
                String::from_utf8(new_path.to_vec()).map_err(|_| ReviewError::Parse)?,
            )
        } else {
            (
                None,
                String::from_utf8(first.to_vec()).map_err(|_| ReviewError::Parse)?,
            )
        };
        workspace
            .validate_git_path(&path)
            .map_err(ReviewError::from)?;
        if let Some(old_path) = old_path.as_deref() {
            workspace
                .validate_git_path(old_path)
                .map_err(ReviewError::from)?;
        }
        records.push(NameStatusRecord {
            path,
            old_path,
            status: status_from_code(status_kind),
        });
        if records.len() > MAX_REVIEW_FILES {
            return Err(ReviewError::Parse);
        }
    }
    Ok(records)
}

/// 将 Git name-status byte 映射到封闭 Review status enum。
fn status_from_code(code: u8) -> ReviewFileStatus {
    match code {
        b'A' => ReviewFileStatus::Added,
        b'D' => ReviewFileStatus::Deleted,
        b'R' => ReviewFileStatus::Renamed,
        b'C' => ReviewFileStatus::Copied,
        b'U' => ReviewFileStatus::Conflict,
        _ => ReviewFileStatus::Modified,
    }
}
