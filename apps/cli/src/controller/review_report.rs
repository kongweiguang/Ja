// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! CLI 与 TUI controller 共用的冻结 Review 报告校验。

use ja_review::{ReviewFileLayer, ReviewSnapshot};
use serde_json::Value;
use std::collections::HashSet;

type FileIdentity = (String, String);

/// 仅接受快照、已审查文件身份和发现都与冻结证据一致的报告。
pub(crate) fn render_report(
    report: &Value,
    snapshot: &ReviewSnapshot,
) -> Result<String, &'static str> {
    required_str(report, "reportId")?;
    if required_str(report, "snapshotId")? != snapshot.snapshot_id
        || required_str(report, "snapshotHash")? != snapshot.snapshot_hash
        || required_str(report, "revision")? != snapshot.revision
    {
        return Err("Review 报告不属于本次冻结快照");
    }
    let summary = required_str(report, "summary")?;
    let coverage = report
        .get("coverage")
        .and_then(Value::as_object)
        .ok_or("Review 报告缺少 coverage")?;
    let expected_coverage =
        serde_json::to_value(&snapshot.coverage).map_err(|_| "本地快照 coverage 无法序列化")?;
    for field in [
        "complete",
        "files",
        "bytes",
        "maxFiles",
        "maxBytes",
        "partialReasons",
    ] {
        if coverage.get(field) != expected_coverage.get(field) {
            return Err("Review coverage 与本地快照不一致");
        }
    }
    let reviewed_files = reviewed_files(coverage, snapshot)?;
    let review_complete = coverage
        .get("reviewComplete")
        .and_then(Value::as_bool)
        .ok_or("Review coverage 缺少 reviewComplete")?;
    let expected_complete =
        snapshot.coverage.complete && reviewed_files.len() == snapshot.files.len();
    if review_complete != expected_complete {
        return Err("Review coverage 完整性与文件身份不一致");
    }
    let findings = report
        .get("findings")
        .and_then(Value::as_array)
        .ok_or("Review 报告缺少 findings")?;

    let mut lines = vec![sanitize_single_line(summary)];
    lines.push(format!(
        "覆盖：{}/{} 个文件 · {}",
        reviewed_files.len(),
        snapshot.coverage.files,
        if review_complete { "完整" } else { "部分" }
    ));
    if !snapshot.coverage.complete {
        let reasons = coverage
            .get("partialReasons")
            .and_then(Value::as_array)
            .ok_or("部分快照缺少 partialReasons")?;
        let labels = reasons
            .iter()
            .map(|reason| {
                let reason = reason.as_str().ok_or("Review partial reason 类型无效")?;
                partial_reason_label(reason).ok_or("Review partial reason 未知")
            })
            .collect::<Result<Vec<_>, _>>()?;
        lines.push(format!("快照受限：{}", labels.join("、")));
    }
    if !review_complete {
        lines.push("模型未覆盖快照中的全部内容".into());
    }
    if findings.is_empty() {
        lines.push(
            if review_complete {
                "未发现问题"
            } else {
                "当前覆盖范围内未发现问题"
            }
            .into(),
        );
    }
    let mut finding_ids = HashSet::with_capacity(findings.len());
    for finding in findings {
        render_finding(
            &mut lines,
            finding,
            snapshot,
            &reviewed_files,
            &mut finding_ids,
        )?;
    }
    Ok(lines.join("\n"))
}

/// 解析协议中的 path/layer 数组，并拒绝不可变快照以外的文件身份。
fn reviewed_files(
    coverage: &serde_json::Map<String, Value>,
    snapshot: &ReviewSnapshot,
) -> Result<HashSet<FileIdentity>, &'static str> {
    let files = coverage
        .get("filesReviewed")
        .and_then(Value::as_array)
        .ok_or("Review coverage 缺少 filesReviewed 文件身份")?;
    if files.len() > snapshot.files.len() {
        return Err("Review coverage 文件数量越界");
    }
    let expected = snapshot
        .files
        .iter()
        .map(|file| (file.path.clone(), layer_name(file.layer).to_owned()))
        .collect::<HashSet<_>>();
    let mut identities = HashSet::with_capacity(files.len());
    for file in files {
        let path = required_str(file, "path")?;
        let layer = required_str(file, "layer")?;
        let identity = (path.to_owned(), layer.to_owned());
        if !expected.contains(&identity) {
            return Err("Review coverage 文件身份不属于本次快照");
        }
        if !identities.insert(identity) {
            return Err("Review coverage 文件身份重复");
        }
    }
    Ok(identities)
}

/// 发现必须指向已审查的精确 path/layer，且行范围存在于声明的一侧内容中。
fn render_finding(
    lines: &mut Vec<String>,
    finding: &Value,
    snapshot: &ReviewSnapshot,
    reviewed_files: &HashSet<FileIdentity>,
    finding_ids: &mut HashSet<String>,
) -> Result<(), &'static str> {
    let finding_id = required_str(finding, "findingId")?;
    if !finding_ids.insert(finding_id.to_owned()) {
        return Err("Review finding ID 重复");
    }
    let severity = required_str(finding, "severity")?;
    if !matches!(severity, "P0" | "P1" | "P2" | "P3") {
        return Err("Review finding severity 无效");
    }
    let path = required_str(finding, "path")?;
    let layer = required_str(finding, "layer")?;
    let side = required_str(finding, "side")?;
    let start_line = required_u64(finding, "startLine")?;
    let end_line = required_u64(finding, "endLine")?;
    if start_line == 0 || end_line < start_line {
        return Err("Review finding 行号范围无效");
    }
    let valid_location = snapshot.files.iter().any(|file| {
        if file.path != path
            || layer_name(file.layer) != layer
            || !reviewed_files.contains(&(file.path.clone(), layer.to_owned()))
        {
            return false;
        }
        let content = match side {
            "base" => file.base_content.as_deref(),
            "current" => file.content.as_deref(),
            _ => None,
        };
        content.is_some_and(|text| end_line <= text.lines().count() as u64)
    });
    if !valid_location {
        return Err("Review finding 位置不属于本次审查覆盖");
    }
    let title = required_str(finding, "title")?;
    let body = required_str(finding, "body")?;
    let evidence = required_str(finding, "evidence")?;
    lines.push(format!(
        "\n{severity} · {path}:{start_line}-{end_line} · {}",
        sanitize_single_line(title)
    ));
    append_indented(lines, body);
    if !evidence.is_empty() {
        lines.push("  证据：".into());
        append_indented(lines, evidence);
    }
    Ok(())
}

/// 严格读取 Schema 字段，避免畸形 JSON 被降级为空值。
fn required_str<'a>(value: &'a Value, field: &str) -> Result<&'a str, &'static str> {
    value
        .get(field)
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .ok_or("Review 报告字段缺失或类型无效")
}

/// 行号只接受非负 JSON 整数。
fn required_u64(value: &Value, field: &str) -> Result<u64, &'static str> {
    value
        .get(field)
        .and_then(Value::as_u64)
        .ok_or("Review finding 行号缺失或类型无效")
}

/// 协议 layer 名称遵循共享 serde 枚举的 snake_case 表示。
fn layer_name(layer: ReviewFileLayer) -> &'static str {
    match layer {
        ReviewFileLayer::Staged => "staged",
        ReviewFileLayer::Unstaged => "unstaged",
        ReviewFileLayer::Untracked => "untracked",
        ReviewFileLayer::Comparison => "comparison",
    }
}

/// 将封闭的部分覆盖原因集合转换为简明说明。
fn partial_reason_label(reason: &str) -> Option<&'static str> {
    match reason {
        "binary_file" => Some("包含二进制文件"),
        "non_utf8" => Some("包含非 UTF-8 内容"),
        "file_limit" => Some("达到文件数量上限"),
        "byte_limit" => Some("达到内容大小上限"),
        "content_unavailable" => Some("部分文件内容不可读取"),
        "patch_unavailable" => Some("部分差异内容不可读取"),
        _ => None,
    }
}

/// 报告标题保持单行，并移除 ANSI 控制字符。
fn sanitize_single_line(value: &str) -> String {
    value
        .chars()
        .map(|character| {
            if character.is_control() {
                ' '
            } else {
                character
            }
        })
        .collect()
}

/// 保留报告正文换行，同时阻止模型文本输出终端控制字符。
fn append_indented(lines: &mut Vec<String>, value: &str) {
    for line in value.lines() {
        let safe = line
            .chars()
            .map(|character| {
                if character.is_control() {
                    ' '
                } else {
                    character
                }
            })
            .collect::<String>();
        lines.push(format!("  {safe}"));
    }
}
