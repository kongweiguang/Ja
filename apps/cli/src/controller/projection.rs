// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 将 Java 权威快照投影为终端安全的展示值，不把协议字段解析放进渲染线程。

use super::{CliError, ThreadContext, required_str, required_u64};
use ja_cli::ui::{
    InteractionQuestion, InteractionQuestionKind, PendingPrompt, TimelineEntry, TimelineKind,
    TimelineStatus, TurnState, UiChoice, UiSnapshot,
};
use serde_json::Value;
use std::collections::HashMap;
use std::path::Path;

/// 每次读回全量快照都重建可见投影，以稳定 item ID 去重，避免重连后重复正文。
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
    let latest = turns.last();
    let latest_turn_id = latest
        .and_then(|turn| turn.get("turnId"))
        .and_then(Value::as_str);
    let latest_status = latest
        .and_then(|turn| turn.get("status"))
        .and_then(Value::as_str);
    let state = match latest_status {
        Some("queued" | "running" | "waiting_approval" | "suspended") => TurnState::Working,
        Some("failed") => TurnState::Failed,
        _ => TurnState::Idle,
    };
    let mut timeline = Vec::with_capacity(items.len() + 2);
    let turn_statuses: HashMap<&str, &str> = turns
        .iter()
        .filter_map(|turn| Some((turn.get("turnId")?.as_str()?, turn.get("status")?.as_str()?)))
        .collect();
    for item in items {
        let turn_status = item
            .get("turnId")
            .and_then(Value::as_str)
            .and_then(|id| turn_statuses.get(id).copied());
        if let Some(entry) = project_item(item, turn_status)? {
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
                .and_then(Value::as_str)
                .unwrap_or("UNKNOWN");
            let description = match code {
                "MODEL_UNAVAILABLE" => "模型服务暂时不可用",
                _ => "任务未完成",
            };
            timeline.push(TimelineEntry {
                id: format!("turn-error:{turn_id}"),
                kind: TimelineKind::Commentary,
                text: format!("{description} · {code}"),
                detail: None,
                status: Some(TimelineStatus::Failed),
            });
        }
    }
    if let Some(stream) = history.get("liveStream").filter(|value| !value.is_null()) {
        let turn_id = required_str(stream, "turnId")?;
        let segments = stream
            .get("segments")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("liveStream 缺少 segments"))?;
        let assistant = segments
            .iter()
            .filter(|part| part.get("kind").and_then(Value::as_str) == Some("assistant"))
            .filter_map(|part| part.get("text").and_then(Value::as_str))
            .collect::<String>();
        if !assistant.is_empty() {
            timeline.push(TimelineEntry {
                id: format!("live:{turn_id}"),
                kind: TimelineKind::Assistant,
                text: safe_display(&assistant),
                detail: None,
                status: Some(TimelineStatus::Running),
            });
        }
    }
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
        current_model_key: Some(format!("{}/{}", context.provider_id, context.model_id)),
        reasoning_label: context
            .reasoning_level
            .as_deref()
            .map(super::interactive::reasoning_label)
            .map(str::to_owned),
        provider_name,
        permission_label: Some(permission_label(&context.access_mode).to_owned()),
        turn_state: state,
        continuation_available: matches!(latest_status, Some("failed" | "cancelled"))
            && items.iter().any(|item| {
                item.get("turnId").and_then(Value::as_str) == latest_turn_id
                    && item.get("kind").and_then(Value::as_str) == Some("user_input")
            }),
        timeline,
        attachments: Vec::new(),
        has_older_history: false,
        pending_prompt,
        model_choices: model_choices(config),
        reasoning_choices: Vec::new(),
        reasoning_model_identifier: None,
        permission_choices: permission_choices(),
        thread_choices: Vec::new(),
        thread_next_cursor: None,
        input_history_choices: Vec::new(),
        input_history_next_cursor: None,
        file_choices: Vec::new(),
        skill_choices: Vec::new(),
        notice: None,
    })
}

/// 协议历史行只投影安全 presentation；原始 Tool 参数与结果从未进入终端层。
fn project_item(
    item: &Value,
    turn_status: Option<&str>,
) -> Result<Option<TimelineEntry>, CliError> {
    let id = required_str(item, "itemId")?.to_owned();
    let kind = required_str(item, "kind")?;
    let entry = match kind {
        "user_input" => {
            let content = item
                .get("content")
                .and_then(Value::as_array)
                .ok_or_else(|| CliError::protocol("用户历史缺少 content"))?;
            let text = content
                .iter()
                .filter_map(|block| match block.get("type").and_then(Value::as_str) {
                    Some("text") => block.get("text").and_then(Value::as_str).map(str::to_owned),
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
                .collect::<Vec<_>>()
                .join(" ");
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
            kind: TimelineKind::Commentary,
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
            let output = ["outputPreview", "stdout", "summary"]
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
                .or(input)
                .map(|text| safe_display(text.trim()));
            TimelineEntry {
                id,
                kind: TimelineKind::Tool {
                    action: action.into(),
                    target: safe_display(target),
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
        request_id: required_str(request, "requestId")?.to_owned(),
        expected_revision: required_u64(request, "revision")?,
        idempotency_key: format!("cli-{}", uuid::Uuid::new_v4().simple()),
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
