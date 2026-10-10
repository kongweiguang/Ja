// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 将 Java 权威快照投影为终端安全的展示值，不把协议字段解析放进渲染线程。

use super::{CliError, ThreadContext, required_str, required_u64};
use ja_cli::ui::{
    InteractionQuestion, InteractionQuestionKind, PendingPrompt, TimelineEntry, TimelineKind,
    TimelineStatus, TurnState, UiChoice, UiSnapshot,
};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// @author kongweiguang
/// 同一 ModelStep 的正文和公开摘要各自合并，按首段序号排列；UI 加载态不从历史投影继承，避免旧选择器状态泄漏。
pub fn project(
    context: &ThreadContext,
    history: &Value,
    interaction: &Value,
    config: &Value,
    thread_title: Option<&str>,
) -> Result<UiSnapshot, CliError> {
    let revision = required_u64(history, "revision")?;
    let items = history
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("thread/read 缺少 items"))?;
    let turns = history
        .get("turns")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("thread/read 缺少 turns"))?;
    // Frozen inherited turns remain visible, but only a native turn can describe this Thread's live controls.
    let latest = turns
        .iter()
        .rev()
        .find(|turn| turn.get("origin").and_then(Value::as_str) != Some("inherited"));
    let latest_turn_id = latest
        .and_then(|turn| turn.get("turnId"))
        .and_then(Value::as_str);
    let latest_status = latest
        .and_then(|turn| turn.get("status"))
        .and_then(Value::as_str);
    let state = match latest_status {
        Some("queued" | "running" | "waiting_input" | "waiting_approval") => TurnState::Working,
        Some("suspended") => TurnState::Paused,
        Some("failed") => TurnState::Failed,
        _ => TurnState::Idle,
    };
    let turn_started_at = if state == TurnState::Working {
        latest
            .and_then(|turn| turn.get("requestedAt"))
            .and_then(Value::as_str)
            .and_then(parse_utc_system_time)
    } else {
        None
    };
    let mut timeline = Vec::with_capacity(items.len() + turns.len() + 4);
    let mut turn_id_by_entry_id = HashMap::new();
    let turn_statuses: HashMap<&str, &str> = turns
        .iter()
        .filter_map(|turn| Some((turn.get("turnId")?.as_str()?, turn.get("status")?.as_str()?)))
        .collect();
    for item in items {
        let item_turn_id = item.get("turnId").and_then(Value::as_str);
        let turn_status = item
            .get("turnId")
            .and_then(Value::as_str)
            .and_then(|id| turn_statuses.get(id).copied());
        if let Some(entry) = project_item(item, turn_status)? {
            if let Some(turn_id) = item_turn_id {
                turn_id_by_entry_id.insert(entry.id.clone(), turn_id.to_owned());
            }
            timeline.push(entry);
        }
    }
    if latest_status == Some("failed")
        && let Some(turn_id) = latest_turn_id
    {
        let has_answer = items.iter().any(|item| {
            item.get("turnId").and_then(Value::as_str) == Some(turn_id)
                && item.get("kind").and_then(Value::as_str) == Some("final_answer")
        });
        if !has_answer {
            let code = latest
                .and_then(|turn| turn.get("errorCode"))
                .and_then(Value::as_str);
            let description = match code {
                Some("MODEL_UNAVAILABLE") => "模型服务暂时不可用",
                Some("MODEL_PROTOCOL_ERROR") => "模型返回内容无法处理",
                _ => "任务未完成",
            };
            timeline.push(TimelineEntry {
                id: format!("turn-error:{turn_id}"),
                kind: TimelineKind::Commentary,
                text: code.map_or_else(
                    || description.to_owned(),
                    |code| format!("{description} · {code}"),
                ),
                detail: None,
                status: Some(TimelineStatus::Failed),
            });
            turn_id_by_entry_id.insert(format!("turn-error:{turn_id}"), turn_id.to_owned());
        }
    }
    if let Some(stream) = history.get("liveStream").filter(|value| !value.is_null()) {
        let turn_id = required_str(stream, "turnId")?;
        let segments = stream
            .get("segments")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("liveStream 缺少 segments"))?;
        let stream_is_open = matches!(
            turn_statuses.get(turn_id).copied(),
            Some("queued" | "running" | "waiting_input" | "waiting_approval" | "suspended")
        );
        let mut first_assistant_sequence = None;
        let mut assistant_text = String::new();
        let mut first_reasoning_sequence = None;
        let mut reasoning_text = String::new();
        for segment in segments {
            let kind = segment.get("kind").and_then(Value::as_str);
            if matches!(kind, Some("assistant" | "reasoningSummary")) {
                let start = segment
                    .get("segmentStartSeq")
                    .and_then(Value::as_u64)
                    .ok_or_else(|| CliError::protocol("liveStream 片段缺少 segmentStartSeq"))?;
                let _end = segment
                    .get("streamSeq")
                    .and_then(Value::as_u64)
                    .ok_or_else(|| CliError::protocol("liveStream 片段缺少 streamSeq"))?;
                let text = segment
                    .get("text")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CliError::protocol("liveStream 片段缺少 text"))?;
                if kind == Some("assistant") {
                    first_assistant_sequence.get_or_insert(start);
                    assistant_text.push_str(text);
                } else {
                    first_reasoning_sequence.get_or_insert(start);
                    reasoning_text.push_str(text);
                }
            }
        }
        let mut live_entries = Vec::with_capacity(2);
        for (sequence, text, kind, prefix) in [
            (
                first_assistant_sequence,
                assistant_text,
                TimelineKind::Assistant,
                "live",
            ),
            (
                first_reasoning_sequence,
                reasoning_text,
                TimelineKind::ReasoningSummary,
                "live-reasoning",
            ),
        ] {
            if let Some(start) = sequence.filter(|_| !text.is_empty()) {
                live_entries.push((
                    start,
                    TimelineEntry {
                        id: format!("{prefix}:{turn_id}:{start}"),
                        kind,
                        text: safe_display(&text),
                        detail: None,
                        status: Some(if stream_is_open {
                            TimelineStatus::Running
                        } else {
                            TimelineStatus::Complete
                        }),
                    },
                ));
            }
        }
        live_entries.sort_by_key(|(sequence, _)| *sequence);
        for (_, entry) in live_entries {
            let id = entry.id.clone();
            turn_id_by_entry_id.insert(id.clone(), turn_id.to_owned());
            timeline.push(entry);
        }
    }
    append_work_summaries(turns, &mut timeline, &mut turn_id_by_entry_id);
    let mut pending_prompt = approval_prompt(
        items,
        latest_turn_id,
        latest_status,
        &context.thread_id,
        revision,
    )?;
    if let Some(clarification) = clarification_prompt(interaction)? {
        pending_prompt = Some(clarification);
    }
    let (model_identifier, provider_name) =
        model_labels(config, &context.provider_id, &context.model_id);
    let project_label = Path::new(&context.workspace_root)
        .file_name()
        .and_then(|name| name.to_str())
        .map(str::to_owned);
    Ok(UiSnapshot {
        project_label,
        workspace_path: Some(context.workspace_root.clone()),
        thread_id: Some(context.thread_id.clone()),
        thread_title: thread_title.map(str::to_owned),
        model_identifier,
        usage: None,
        current_model_key: Some(format!("{}/{}", context.provider_id, context.model_id)),
        reasoning_label: context
            .reasoning_level
            .as_deref()
            .map(super::interactive::reasoning_label)
            .map(str::to_owned),
        provider_name,
        permission_label: Some(permission_label(&context.access_mode).to_owned()),
        turn_state: state,
        local_operation: None,
        turn_started_at,
        continuation_available: matches!(latest_status, Some("failed" | "cancelled"))
            && items.iter().any(|item| {
                item.get("turnId").and_then(Value::as_str) == latest_turn_id
                    && item.get("kind").and_then(Value::as_str) == Some("user_input")
            }),
        timeline,
        turn_id_by_entry_id,
        attachments: Vec::new(),
        has_older_history: false,
        pending_prompt,
        model_choices: model_choices(config),
        reasoning_choices: Vec::new(),
        reasoning_model_identifier: None,
        permission_choices: permission_choices(),
        thread_choices: Vec::new(),
        thread_next_cursor: None,
        fork_reply_choices: Vec::new(),
        fork_reply_next_cursor: None,
        fork_reply_loading: false,
        fork_reply_load_failed: false,
        input_history_choices: Vec::new(),
        input_history_next_cursor: None,
        file_choices: Vec::new(),
        skill_choices: Vec::new(),
        notice: None,
    })
}

/// 终态摘要紧跟各自最后一条可见记录，隐藏时间损坏或逆序的 Turn 时长。
fn append_work_summaries(
    turns: &[Value],
    timeline: &mut Vec<TimelineEntry>,
    turn_id_by_entry_id: &mut HashMap<String, String>,
) {
    let mut summarized_turns = HashSet::new();
    for turn in turns {
        let Some(turn_id) = turn.get("turnId").and_then(Value::as_str) else {
            continue;
        };
        if !summarized_turns.insert(turn_id) {
            continue;
        }
        let Some(elapsed_seconds) = completed_elapsed_seconds(turn) else {
            continue;
        };
        let Some(last_index) = timeline.iter().rposition(|entry| {
            turn_id_by_entry_id
                .get(&entry.id)
                .is_some_and(|entry_turn_id| entry_turn_id == turn_id)
        }) else {
            continue;
        };
        let id = format!("turn-summary:{turn_id}");
        timeline.insert(
            last_index + 1,
            TimelineEntry {
                id: id.clone(),
                kind: TimelineKind::WorkSummary { elapsed_seconds },
                text: String::new(),
                detail: None,
                status: None,
            },
        );
        turn_id_by_entry_id.insert(id, turn_id.to_owned());
    }
}

/// 只从已确认的终态起止时间计算整秒时长，不用客户端当前时间补齐缺失事实。
fn completed_elapsed_seconds(turn: &Value) -> Option<u64> {
    if !matches!(
        turn.get("status").and_then(Value::as_str),
        Some("completed" | "failed" | "cancelled")
    ) {
        return None;
    }
    let requested_at = parse_utc_epoch_nanoseconds(turn.get("requestedAt")?.as_str()?)?;
    let completed_at = parse_utc_epoch_nanoseconds(turn.get("completedAt")?.as_str()?)?;
    let elapsed = completed_at.checked_sub(requested_at)?;
    if elapsed < 0 {
        return None;
    }
    u64::try_from(elapsed / 1_000_000_000).ok()
}

/// 解析 Java Instant 的固定 UTC 形式；拒绝时区偏移、非闰日、闰秒和超精度小数。
fn parse_utc_epoch_nanoseconds(timestamp: &str) -> Option<i128> {
    let bytes = timestamp.as_bytes();
    if !(20..=30).contains(&bytes.len())
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
        || ![(0, 4), (5, 7), (8, 10), (11, 13), (14, 16), (17, 19)]
            .iter()
            .all(|(start, end)| bytes[*start..*end].iter().all(u8::is_ascii_digit))
    {
        return None;
    }
    let year = decimal_component(&bytes[0..4])?;
    let month = decimal_component(&bytes[5..7])?;
    let day = decimal_component(&bytes[8..10])?;
    let hour = decimal_component(&bytes[11..13])?;
    let minute = decimal_component(&bytes[14..16])?;
    let second = decimal_component(&bytes[17..19])?;
    if !(1..=12).contains(&month)
        || day == 0
        || day > days_in_month(year, month)?
        || hour > 23
        || minute > 59
        || second > 59
    {
        return None;
    }
    let fraction_nanos = match bytes.get(19) {
        Some(b'Z') if bytes.len() == 20 => 0,
        Some(b'.') if bytes.last() == Some(&b'Z') => {
            let fraction = &bytes[20..bytes.len() - 1];
            if fraction.is_empty() || fraction.len() > 9 || !fraction.iter().all(u8::is_ascii_digit)
            {
                return None;
            }
            decimal_component(fraction)? * 10_i128.pow((9 - fraction.len()) as u32)
        }
        _ => return None,
    };
    let year = i64::try_from(year).ok()?;
    let month = i64::try_from(month).ok()?;
    let day = i64::try_from(day).ok()?;
    let adjusted_year = year - i64::from(month <= 2);
    let era = if adjusted_year >= 0 {
        adjusted_year
    } else {
        adjusted_year - 399
    } / 400;
    let year_of_era = adjusted_year - era * 400;
    let shifted_month = month + if month > 2 { -3 } else { 9 };
    let day_of_year = (153 * shifted_month + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    let days_since_epoch = era * 146_097 + day_of_era - 719_468;
    let seconds_since_epoch =
        i128::from(days_since_epoch) * 86_400 + hour * 3_600 + minute * 60 + second;
    seconds_since_epoch
        .checked_mul(1_000_000_000)?
        .checked_add(fraction_nanos)
}

/// 只选权威时间最早的挂起 Turn，时间缺失或损坏时拒绝猜测，平局按 Turn ID 稳定决胜。
pub(super) fn earliest_suspended_turn_id(history: &Value) -> Result<Option<String>, CliError> {
    let turns = history
        .get("turns")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("thread/read 缺少 turns"))?;
    turns
        .iter()
        .filter(|turn| {
            turn.get("origin").and_then(Value::as_str) != Some("inherited")
                && turn.get("status").and_then(Value::as_str) == Some("suspended")
        })
        .map(|turn| {
            let turn_id = required_str(turn, "turnId")?.to_owned();
            let requested_at = required_str(turn, "requestedAt")?;
            let requested_at = parse_utc_epoch_nanoseconds(requested_at)
                .ok_or_else(|| CliError::protocol("挂起 Turn 的 requestedAt 无效"))?;
            Ok((requested_at, turn_id))
        })
        .collect::<Result<Vec<_>, CliError>>()
        .map(|turns| turns.into_iter().min().map(|(_, turn_id)| turn_id))
}

/// 固定宽度 ASCII 十进制字段避免宽松日期解析器归一化无效日期。
fn decimal_component(bytes: &[u8]) -> Option<i128> {
    bytes.iter().try_fold(0_i128, |value, byte| {
        if !byte.is_ascii_digit() {
            return None;
        }
        value.checked_mul(10)?.checked_add(i128::from(byte - b'0'))
    })
}

/// 按公历闰年规则限制日数，确保 2 月 29 日不会被滚动到下个月。
fn days_in_month(year: i128, month: i128) -> Option<i128> {
    let leap_year = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => Some(31),
        4 | 6 | 9 | 11 => Some(30),
        2 => Some(if leap_year { 29 } else { 28 }),
        _ => None,
    }
}

/// 将通过严格校验的起始时间映射到本地时钟；超出平台 SystemTime 范围时保持未知。
fn parse_utc_system_time(timestamp: &str) -> Option<SystemTime> {
    let epoch_nanoseconds = parse_utc_epoch_nanoseconds(timestamp)?;
    let magnitude = epoch_nanoseconds.checked_abs()?;
    let seconds = u64::try_from(magnitude / 1_000_000_000).ok()?;
    let nanos = u32::try_from(magnitude % 1_000_000_000).ok()?;
    let duration = Duration::new(seconds, nanos);
    if epoch_nanoseconds >= 0 {
        UNIX_EPOCH.checked_add(duration)
    } else {
        UNIX_EPOCH.checked_sub(duration)
    }
}

/// 终态快照已包含完整业务结果，之后出队的旧增量不能再次成为活动流；未知 Turn 仍待接续。
pub(super) fn turn_is_terminal(history: &Value, turn_id: &str) -> bool {
    history
        .get("turns")
        .and_then(Value::as_array)
        .and_then(|turns| {
            turns
                .iter()
                .find(|turn| turn.get("turnId").and_then(Value::as_str) == Some(turn_id))
        })
        .and_then(|turn| turn.get("status"))
        .and_then(Value::as_str)
        .is_some_and(|status| matches!(status, "completed" | "failed" | "cancelled"))
}

/// 工具只消费安全 presentation；缺失结果不能用输入补齐，多行命令标题保留可见分隔。
fn project_item(
    item: &Value,
    turn_status: Option<&str>,
) -> Result<Option<TimelineEntry>, CliError> {
    let id = required_str(item, "itemId")?.to_owned();
    let kind = required_str(item, "kind")?;
    let entry = match kind {
        "user_input" => {
            let text = user_input_display(item, None)?;
            TimelineEntry {
                id,
                kind: TimelineKind::User,
                text: safe_display(&text),
                detail: None,
                status: None,
            }
        }
        "assistant_progress" | "final_answer" => TimelineEntry {
            id,
            kind: if kind == "final_answer" {
                TimelineKind::FinalAnswer
            } else {
                TimelineKind::Assistant
            },
            text: safe_display(text_field(item, "text")?),
            detail: None,
            status: Some(if kind == "final_answer" && turn_status == Some("failed") {
                TimelineStatus::Failed
            } else {
                TimelineStatus::Complete
            }),
        },
        "reasoning_summary" => TimelineEntry {
            id,
            kind: TimelineKind::ReasoningSummary,
            text: safe_display(text_field(item, "text")?),
            detail: None,
            status: None,
        },
        "tool_call" => {
            let presentation = item
                .get("presentation")
                .ok_or_else(|| CliError::protocol("Tool 缺少 presentation"))?;
            let action = match presentation.get("kind").and_then(Value::as_str) {
                Some("read") => "读取",
                Some("edit") => "修改",
                Some("write") => "写入",
                Some("shell") => "运行",
                Some("mcp") => "调用",
                _ => "工具",
            };
            let target = presentation
                .get("command")
                .and_then(Value::as_str)
                .or_else(|| {
                    presentation
                        .get("relativePaths")
                        .and_then(Value::as_array)
                        .and_then(|paths| paths.first())
                        .and_then(Value::as_str)
                })
                .or_else(|| presentation.get("title").and_then(Value::as_str))
                .unwrap_or("工具");
            let status = match presentation.get("status").and_then(Value::as_str) {
                Some("success") => Some(TimelineStatus::Complete),
                Some("error" | "cancelled") => Some(TimelineStatus::Failed),
                _ => Some(TimelineStatus::Running),
            };
            let output_fields = if presentation.get("kind").and_then(Value::as_str) == Some("shell")
            {
                ["stdout", "outputPreview", "summary"]
            } else {
                ["outputPreview", "stdout", "summary"]
            };
            let output = output_fields
                .into_iter()
                .filter_map(|key| presentation.get(key).and_then(Value::as_str))
                .find(|text| !text.trim().is_empty());
            let error = (status == Some(TimelineStatus::Failed))
                .then(|| presentation.get("stderr").and_then(Value::as_str))
                .flatten()
                .filter(|text| !text.trim().is_empty());
            let input = presentation
                .get("inputPreview")
                .and_then(Value::as_str)
                .filter(|text| !text.trim().is_empty() && text.trim() != target.trim());
            let detail = error
                .or(output)
                .map(|text| safe_display(text.trim()))
                .or_else(|| {
                    (status == Some(TimelineStatus::Running))
                        .then(|| input.map(|text| format!("输入：{}", safe_display(text.trim()))))
                        .flatten()
                });
            TimelineEntry {
                id,
                kind: TimelineKind::Tool {
                    action: action.into(),
                    target: safe_display(target).replace('\n', " ↵ "),
                    preview_truncated: presentation.get("truncated").and_then(Value::as_bool)
                        == Some(true),
                },
                text: String::new(),
                detail,
                status,
            }
        }
        "thread_message" => TimelineEntry {
            id,
            kind: TimelineKind::Commentary,
            text: safe_display(text_field(item, "content")?),
            detail: None,
            status: None,
        },
        "approval" => return Ok(None),
        _ => return Err(CliError::protocol("未知历史条目类型")),
    };
    Ok(Some(entry))
}

/// 保留结构化引用，只在详情读取成功后用全文替换用户输入的有界 text 预览。
pub(super) fn user_input_display(
    item: &Value,
    full_text: Option<&str>,
) -> Result<String, CliError> {
    let content = item
        .get("content")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("用户历史缺少 content"))?;
    let mut chunks = content
        .iter()
        .filter_map(|block| match block.get("type").and_then(Value::as_str) {
            Some("text") if full_text.is_none() => {
                block.get("text").and_then(Value::as_str).map(str::to_owned)
            }
            Some("workspace_reference") => block
                .get("relativePath")
                .and_then(Value::as_str)
                .map(|path| format!("@{path}")),
            Some("skill_reference") => block
                .get("skillId")
                .and_then(Value::as_str)
                .map(|id| format!("${}", id.split_once(':').map_or(id, |(_, name)| name))),
            _ => None,
        })
        .collect::<Vec<_>>();
    if let Some(full_text) = full_text {
        chunks.push(full_text.to_owned());
    }
    Ok(chunks.join(" "))
}

/// 只有最新待审批 Turn 的未裁决记录才能成为可点击审批；revision 来自同次权威读回。
fn approval_prompt(
    items: &[Value],
    latest_turn_id: Option<&str>,
    status: Option<&str>,
    thread_id: &str,
    revision: u64,
) -> Result<Option<PendingPrompt>, CliError> {
    if status != Some("waiting_approval") {
        return Ok(None);
    }
    let approval = items.iter().rev().find(|item| {
        item.get("kind").and_then(Value::as_str) == Some("approval")
            && item.get("turnId").and_then(Value::as_str) == latest_turn_id
            && item.get("decision").is_some_and(Value::is_null)
    });
    let Some(approval) = approval else {
        return Ok(None);
    };
    let reason = required_str(approval, "reason")?;
    let prompt = if reason == "Tool requires approval" {
        let call_id = required_str(approval, "callId")?;
        let tool = items.iter().rev().find(|item| {
            item.get("kind").and_then(Value::as_str) == Some("tool_call")
                && item.get("callId").and_then(Value::as_str) == Some(call_id)
        });
        let target = tool
            .and_then(|item| item.get("presentation"))
            .and_then(|presentation| {
                presentation
                    .get("command")
                    .and_then(Value::as_str)
                    .or_else(|| {
                        presentation
                            .get("relativePaths")
                            .and_then(Value::as_array)
                            .and_then(|paths| paths.first())
                            .and_then(Value::as_str)
                    })
                    .or_else(|| presentation.get("title").and_then(Value::as_str))
            })
            .unwrap_or(required_str(approval, "toolName")?);
        format!("允许执行 {}？", safe_display(target))
    } else {
        safe_display(reason)
    };
    Ok(Some(PendingPrompt::ToolApproval {
        approval_id: required_str(approval, "approvalId")?.to_owned(),
        thread_id: thread_id.into(),
        turn_id: required_str(approval, "turnId")?.to_owned(),
        expected_thread_revision: revision,
        prompt,
        choices: vec![
            UiChoice {
                id: "approve".into(),
                label: "允许".into(),
                detail: None,
            },
            UiChoice {
                id: "deny".into(),
                label: "拒绝".into(),
                detail: None,
            },
        ],
    }))
}

/// 澄清仅使用 interaction/read 中的当前 pending 请求，保留所有题目和原始选项 identity。
fn clarification_prompt(interaction: &Value) -> Result<Option<PendingPrompt>, CliError> {
    let Some(request) = interaction.get("request").filter(|value| !value.is_null()) else {
        return Ok(None);
    };
    if request.get("status").and_then(Value::as_str) != Some("pending") {
        return Ok(None);
    }
    let raw_questions = request
        .get("questions")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("澄清请求缺少 questions"))?;
    let mut questions = Vec::with_capacity(raw_questions.len());
    for raw in raw_questions {
        let kind = match required_str(raw, "type")? {
            "single" => InteractionQuestionKind::Single,
            "multiple" => InteractionQuestionKind::Multiple,
            "text" => InteractionQuestionKind::Text,
            _ => return Err(CliError::protocol("未知澄清题目类型")),
        };
        let options = raw
            .get("options")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("澄清题目缺少 options"))?
            .iter()
            .map(|option| {
                Ok(UiChoice {
                    id: required_str(option, "optionId")?.to_owned(),
                    label: safe_display(required_str(option, "label")?),
                    detail: option
                        .get("description")
                        .and_then(Value::as_str)
                        .map(safe_display),
                })
            })
            .collect::<Result<Vec<_>, CliError>>()?;
        questions.push(InteractionQuestion {
            question_id: required_str(raw, "questionId")?.to_owned(),
            prompt: safe_display(required_str(raw, "prompt")?),
            kind,
            options,
            allow_skip: raw.get("required").and_then(Value::as_bool) == Some(false),
            allow_free_text: raw.get("allowFreeText").and_then(Value::as_bool) == Some(true),
        });
    }
    Ok(Some(PendingPrompt::Clarification {
        thread_id: required_str(request, "threadId")?.to_owned(),
        interaction_id: required_str(request, "interactionId")?.to_owned(),
        expected_interaction_revision: required_u64(request, "interactionRevision")?,
        operation_key: format!("cli-{}", uuid::Uuid::new_v4().simple()),
        questions,
    }))
}

/// 主模型标签使用上游 model 字段，配置别名仅保留在次要 UI 选择详情。
pub fn model_labels(
    config: &Value,
    provider_id: &str,
    model_id: &str,
) -> (Option<String>, Option<String>) {
    let providers = config
        .get("effective")
        .and_then(|value| value.get("providers"))
        .and_then(Value::as_array);
    let provider = providers.and_then(|list| {
        list.iter()
            .find(|item| item.get("provider_id").and_then(Value::as_str) == Some(provider_id))
    });
    let model = provider
        .and_then(|item| item.get("models"))
        .and_then(Value::as_array)
        .and_then(|list| {
            list.iter()
                .find(|item| item.get("model_id").and_then(Value::as_str) == Some(model_id))
        });
    (
        model
            .and_then(|item| item.get("model"))
            .and_then(Value::as_str)
            .map(safe_display),
        provider
            .and_then(|item| item.get("name"))
            .and_then(Value::as_str)
            .map(safe_display),
    )
}

/// 模型选项的 id 仅是本地可逆 key，提交前 controller 仍从当前配置核验 provider/model 配对。
pub(super) fn model_choices(config: &Value) -> Vec<UiChoice> {
    config
        .get("effective")
        .and_then(|value| value.get("providers"))
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .flat_map(|provider| {
            let provider_id = provider
                .get("provider_id")
                .and_then(Value::as_str)
                .unwrap_or("");
            let provider_name = provider.get("name").and_then(Value::as_str).unwrap_or("");
            provider
                .get("models")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(move |model| {
                    let identifier = model.get("model")?.as_str()?;
                    Some(UiChoice {
                        id: format!("{provider_id}/{}", model.get("model_id")?.as_str()?),
                        label: safe_display(identifier),
                        detail: Some(model_description(provider_name, identifier, model)),
                    })
                })
        })
        .collect()
}

/// 候选说明只投影当前配置中的 Provider、别名和能力事实，并用真实上游标识排除冗余别名。
fn model_description(provider: &str, upstream_model: &str, model: &Value) -> String {
    let alias = model
        .get("name")
        .and_then(Value::as_str)
        .filter(|name| *name != upstream_model)
        .map(safe_display);
    let context = model
        .get("capabilities")
        .and_then(|value| value.get("context_window_tokens"))
        .and_then(Value::as_u64);
    let output = model
        .get("capabilities")
        .and_then(|value| value.get("max_output_tokens"))
        .and_then(Value::as_u64);
    let mut details = vec![safe_display(provider)];
    if let Some(alias) = alias {
        details.push(format!("配置名 {alias}"));
    }
    if let Some(tokens) = context {
        details.push(format!("上下文 {} token", token_count_label(tokens)));
    }
    if let Some(tokens) = output {
        details.push(format!("最大输出 {} token", token_count_label(tokens)));
    }
    if context.is_none() && output.is_none() {
        details.push("未配置能力参数".into());
    }
    details.join(" · ")
}

/// 目录缺少自然语言描述时以配置中的 token 上限构造简短事实标签，不推断模型质量。
fn token_count_label(tokens: u64) -> String {
    if tokens >= 1_000_000 && tokens.is_multiple_of(1_000_000) {
        format!("{}M", tokens / 1_000_000)
    } else if tokens >= 1_000 && tokens.is_multiple_of(1_000) {
        format!("{}K", tokens / 1_000)
    } else {
        tokens.to_string()
    }
}

/// 权限选项只提供服务端 schema 的两个合法值，选择仍走 thread CAS。
pub(super) fn permission_choices() -> Vec<UiChoice> {
    vec![
        UiChoice {
            id: "approval_required".into(),
            label: "需要审批".into(),
            detail: None,
        },
        UiChoice {
            id: "full_access".into(),
            label: "完全访问".into(),
            detail: None,
        },
    ]
}

/// 权限主标签短且明确，窄终端中可为正文留出宽度。
pub fn permission_label(value: &str) -> &str {
    match value {
        "approval_required" => "需要审批",
        "full_access" => "完全访问",
        _ => "权限未知",
    }
}

/// 从外部文本移除控制码，保留换行和制表；历史 Tool 输出不能注入终端控制序列。
fn safe_display(value: &str) -> String {
    value
        .chars()
        .filter(|ch| *ch == '\n' || *ch == '\t' || !ch.is_control())
        .collect()
}

/// 协议允许部分助手和摘要正文为空；字段必须存在但不强加 UI 自己的非空语义。
fn text_field<'a>(value: &'a Value, field: &str) -> Result<&'a str, CliError> {
    value
        .get(field)
        .and_then(Value::as_str)
        .ok_or_else(|| CliError::protocol(format!("协议字段 {field} 缺失")))
}
