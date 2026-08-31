// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 用于 id、统计与 hunk mutation 的有界 unified-diff parser。

use crate::review::application::ReviewError;
use crate::review::domain::{
    MAX_REVIEW_DIFF_BYTES, MAX_REVIEW_DIFF_LINES, ReviewLine, ReviewLineKind,
};

/// 与 Git name-status 关联前的原生 patch；精确路径与 rename 仍以 NUL 分隔记录为准。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedFilePatch {
    pub old_path: Option<String>,
    pub new_path: Option<String>,
    pub binary: bool,
    pub additions: Option<u64>,
    pub deletions: Option<u64>,
    pub raw: Vec<u8>,
    pub hunks: Vec<ParsedHunk>,
}

/// 保存 `git apply` 所需完整 fragment 的 parsed hunk，避免 interface 重建可写 patch。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedHunk {
    pub header: String,
    pub old_start: u64,
    pub old_lines: u64,
    pub new_start: u64,
    pub new_lines: u64,
    pub lines: Vec<ReviewLine>,
    pub raw_patch: Vec<u8>,
}

/// 解析有界 Git diff 的全部 file block，并保留仅供原生 mutation 使用的精确 hunk bytes。
pub(crate) fn parse_diff(bytes: &[u8]) -> Result<Vec<ParsedFilePatch>, ReviewError> {
    if bytes.len() > MAX_REVIEW_DIFF_BYTES {
        return Ok(Vec::new());
    }
    let mut blocks = Vec::<Vec<u8>>::new();
    let mut current = Vec::new();
    for line in bytes.split_inclusive(|byte| *byte == b'\n') {
        if line.starts_with(b"diff --git ") && !current.is_empty() {
            blocks.push(std::mem::take(&mut current));
        }
        current.extend_from_slice(line);
    }
    if !current.is_empty() {
        blocks.push(current);
    }
    blocks
        .into_iter()
        .map(parse_file_block)
        .collect::<Result<Vec<_>, _>>()
}

/// 解析单个 `diff --git` block，并限制可渲染 hunk 行数以约束内存与 IPC 投影。
fn parse_file_block(block: Vec<u8>) -> Result<ParsedFilePatch, ReviewError> {
    let mut old_path = None;
    let mut new_path = None;
    let mut binary = false;
    let mut additions = 0_u64;
    let mut deletions = 0_u64;
    let mut has_text_hunk = false;
    let mut prelude = Vec::new();
    let mut hunks = Vec::new();
    let mut active: Option<(ParsedHunk, Vec<u8>)> = None;
    let mut rendered_lines = 0_usize;

    for line in block.split_inclusive(|byte| *byte == b'\n') {
        if line.starts_with(b"diff --git ") {
            let (old, new) = parse_diff_git_paths(line);
            old_path = old;
            new_path = new;
            prelude.extend_from_slice(line);
            continue;
        }
        if line.starts_with(b"GIT binary patch") || line.starts_with(b"Binary files ") {
            binary = true;
        }
        if line.starts_with(b"@@ ") || line.starts_with(b"@@-") {
            if let Some((mut hunk, raw)) = active.take() {
                hunk.raw_patch = [prelude.as_slice(), raw.as_slice()].concat();
                hunks.push(hunk);
            }
            let (old_start, old_lines, new_start, new_lines, header) = parse_hunk_header(line)?;
            let hunk = ParsedHunk {
                header,
                old_start,
                old_lines,
                new_start,
                new_lines,
                lines: Vec::new(),
                raw_patch: Vec::new(),
            };
            let mut raw = Vec::new();
            raw.extend_from_slice(line);
            active = Some((hunk, raw));
            has_text_hunk = true;
            continue;
        }
        if let Some((hunk, raw)) = active.as_mut() {
            raw.extend_from_slice(line);
            if rendered_lines < MAX_REVIEW_DIFF_LINES
                && let Some((kind, text)) = parse_hunk_line(line)
            {
                match kind {
                    ReviewLineKind::Addition => additions = additions.saturating_add(1),
                    ReviewLineKind::Deletion => deletions = deletions.saturating_add(1),
                    _ => {}
                }
                hunk.lines.push(ReviewLine { kind, text });
                rendered_lines += 1;
            }
        } else {
            prelude.extend_from_slice(line);
        }
    }
    if let Some((mut hunk, raw)) = active.take() {
        hunk.raw_patch = [prelude.as_slice(), raw.as_slice()].concat();
        hunks.push(hunk);
    }
    if !has_text_hunk {
        additions = 0;
        deletions = 0;
    }
    Ok(ParsedFilePatch {
        old_path,
        new_path,
        binary,
        additions: has_text_hunk.then_some(additions),
        deletions: has_text_hunk.then_some(deletions),
        raw: block,
        hunks,
    })
}

/// 从 diff header 解析两个展示路径；遇到 quotePath 转义时仍由 name-status 覆盖。
fn parse_diff_git_paths(line: &[u8]) -> (Option<String>, Option<String>) {
    let text = String::from_utf8_lossy(line)
        .trim_end_matches('\n')
        .to_owned();
    let Some(rest) = text.strip_prefix("diff --git ") else {
        return (None, None);
    };
    let Some(split) = rest.find(" b/") else {
        return (None, None);
    };
    let left = rest.get(..split).unwrap_or_default().trim();
    let right = rest.get(split + 3..).unwrap_or_default().trim();
    (
        left.strip_prefix("a/").map(unquote_path),
        right.strip_prefix("b/").map(unquote_path),
    )
}

/// 仅移除 Git quote wrapper，不做有损八进制转换；精确路径仍由 NUL name-status 决定。
fn unquote_path(path: &str) -> String {
    path.strip_prefix('"')
        .and_then(|value| value.strip_suffix('"'))
        .unwrap_or(path)
        .to_owned()
}

/// 将 hunk header 解析为 UI 与 hunk identity hash 共用的四个坐标。
fn parse_hunk_header(line: &[u8]) -> Result<(u64, u64, u64, u64, String), ReviewError> {
    let header = String::from_utf8_lossy(line)
        .trim_end_matches('\n')
        .to_owned();
    let mut tokens = header.split_whitespace();
    if tokens.next() != Some("@@") {
        return Err(ReviewError::Parse);
    }
    let old = tokens.next().ok_or(ReviewError::Parse)?;
    let new = tokens.next().ok_or(ReviewError::Parse)?;
    let (old_start, old_lines) = parse_range(old, b'-')?;
    let (new_start, new_lines) = parse_range(new, b'+')?;
    Ok((old_start, old_lines, new_start, new_lines, header))
}

/// 严格解析 `-start,count` 或 `+start,count`，拒绝越界与错误 marker。
fn parse_range(value: &str, marker: u8) -> Result<(u64, u64), ReviewError> {
    let bytes = value.as_bytes();
    if bytes.first().copied() != Some(marker) {
        return Err(ReviewError::Parse);
    }
    let value = &value[1..];
    let mut parts = value.splitn(2, ',');
    let start = parts
        .next()
        .ok_or(ReviewError::Parse)?
        .parse::<u64>()
        .map_err(|_| ReviewError::Parse)?;
    let lines = parts
        .next()
        .map(|part| part.parse::<u64>().map_err(|_| ReviewError::Parse))
        .transpose()?
        .unwrap_or(1);
    Ok((start, lines))
}

/// 分类 hunk line 并移除 leading sign，保留 renderer 自主展示符号的能力。
fn parse_hunk_line(line: &[u8]) -> Option<(ReviewLineKind, String)> {
    let trimmed = line.strip_suffix(b"\n").unwrap_or(line);
    if trimmed == b"\\ No newline at end of file" {
        return Some((ReviewLineKind::NoNewlineMarker, String::new()));
    }
    let (kind, text) = match trimmed.first().copied()? {
        b'+' => (ReviewLineKind::Addition, &trimmed[1..]),
        b'-' => (ReviewLineKind::Deletion, &trimmed[1..]),
        b' ' => (ReviewLineKind::Context, &trimmed[1..]),
        _ => return None,
    };
    Some((kind, String::from_utf8_lossy(text).into_owned()))
}

/// 为已完成路径校验和字节限额的 untracked regular file 构造 synthetic added patch。
pub(crate) fn synthetic_added_patch(path: &str, bytes: &[u8]) -> ParsedFilePatch {
    let mut raw = format!(
        "diff --git a/{path} b/{path}\nnew file mode 100644\n--- /dev/null\n+++ b/{path}\n"
    )
    .into_bytes();
    let text = String::from_utf8_lossy(bytes);
    let lines = text.lines().collect::<Vec<_>>();
    raw.extend_from_slice(format!("@@ -0,0 +1,{} @@\n", lines.len()).as_bytes());
    for line in &lines {
        raw.push(b'+');
        raw.extend_from_slice(line.as_bytes());
        raw.push(b'\n');
    }
    let (old_start, old_lines, new_start, new_lines, header) = parse_hunk_header(
        format!("@@ -0,0 +1,{} @@\n", lines.len()).as_bytes(),
    )
    .unwrap_or((0, 0, 1, lines.len() as u64, String::new()));
    let hunk_lines = lines
        .iter()
        .take(MAX_REVIEW_DIFF_LINES)
        .map(|line| ReviewLine {
            kind: ReviewLineKind::Addition,
            text: (*line).to_owned(),
        })
        .collect::<Vec<_>>();
    let hunk = ParsedHunk {
        header,
        old_start,
        old_lines,
        new_start,
        new_lines,
        lines: hunk_lines,
        // synthetic add 必须同时携带 file prelude 与 hunk header；bare hunk 无法让
        // `git apply --cached` 创建 index entry。
        raw_patch: raw.clone(),
    };
    ParsedFilePatch {
        old_path: None,
        new_path: Some(path.to_owned()),
        binary: bytes.contains(&0),
        additions: Some(lines.len() as u64),
        deletions: Some(0),
        raw,
        hunks: vec![hunk],
    }
}
