// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Thread 计量与最近一次上下文用量的共享 JA-RPC 投影。
//!
//! 这些 DTO 仅承载 App Server 已持久化的脱敏事实；严格解析集中在 runtime，避免 CLI 与 Tauri
//! 对同一 wire 各自放宽字段、数字或估算语义。

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::fmt::{Display, Formatter};

const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;

/// SQLite 单快照聚合的最小计量摘要；每个 Token 累计值都有相应的覆盖请求数。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadUsageSummary {
    pub thread_id: String,
    pub snapshot_revision: u64,
    pub request_count: u64,
    pub measured_request_count: u64,
    pub new_input_request_count: u64,
    pub new_input_tokens: u64,
    pub output_request_count: u64,
    pub output_tokens: u64,
    pub total_request_count: u64,
    pub total_tokens: u64,
    pub cache_read_request_count: u64,
    pub cache_read_tokens: u64,
    pub cache_write_request_count: u64,
    pub cache_write_tokens: u64,
    pub cache_complete_request_count: u64,
    pub cache_complete_input_tokens: u64,
    pub cache_complete_read_tokens: u64,
}

/// 最近一次 Provider 请求及可选本地上下文估算；画像、窗口与计量必须来自同一持久事实。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadContextUsageDto {
    pub turn_id: String,
    pub model_request_id: String,
    pub model_request_ordinal: u64,
    pub model_round: u32,
    pub purpose: String,
    pub certainty: String,
    pub profile: ProviderRequestProfileDto,
    pub input_tokens: Option<u64>,
    pub output_tokens: Option<u64>,
    pub total_tokens: Option<u64>,
    pub measured_at: String,
    pub context_estimate: Option<ThreadContextEstimateDto>,
}

/// Java 本地派发或检查点估算与已结算的 Provider Token 事实保持区分。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadContextEstimateDto {
    pub certainty: String,
    pub source: String,
    pub used_tokens: u64,
    pub limit_tokens: u64,
    pub measured_at: String,
}

/// 每次请求的非敏感 Provider 画像；不包含 URL、凭据或请求正文。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProviderRequestProfileDto {
    pub provider_id: String,
    pub model_id: String,
    pub api: String,
    pub upstream_model: String,
    pub requested_reasoning: Option<String>,
    pub effective_reasoning: Option<String>,
    pub access_mode: String,
    pub collaboration_mode: String,
    pub config_generation: String,
    pub prompt_revision: String,
    pub tool_catalog_revision: String,
    pub context_window_tokens: u64,
    pub max_output_tokens: u64,
}

/// 不携带服务器回包、标识值或原始 JSON，避免 parser 错误进入日志时泄露会话数据。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UsageProtocolError {
    SummaryShape,
    SummaryIdentity,
    SummarySemantics,
    ContextUsageShape,
}

impl Display for UsageProtocolError {
    /// 为调用方提供固定诊断文案，不把拒绝字段值或 JSON 内容拼入错误。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::SummaryShape => "thread usage summary is invalid",
            Self::SummaryIdentity => "thread usage summary identity is invalid",
            Self::SummarySemantics => "thread usage summary semantics are invalid",
            Self::ContextUsageShape => "thread context usage is invalid",
        })
    }
}

impl std::error::Error for UsageProtocolError {}

/// 解析封闭字段集、Thread 身份与精确整数；零覆盖须为零累计，完整缓存样本是新输入与缓存读取覆盖的子集。
/// @author kongweiguang
pub fn parse_thread_usage_summary(value: Value) -> Result<ThreadUsageSummary, UsageProtocolError> {
    let result: ThreadUsageSummary =
        serde_json::from_value(value).map_err(|_| UsageProtocolError::SummaryShape)?;
    if !valid_prefixed_identity(&result.thread_id, "thr_", 100) {
        return Err(UsageProtocolError::SummaryIdentity);
    }
    let values = [
        result.snapshot_revision,
        result.request_count,
        result.measured_request_count,
        result.new_input_request_count,
        result.new_input_tokens,
        result.output_request_count,
        result.output_tokens,
        result.total_request_count,
        result.total_tokens,
        result.cache_read_request_count,
        result.cache_read_tokens,
        result.cache_write_request_count,
        result.cache_write_tokens,
        result.cache_complete_request_count,
        result.cache_complete_input_tokens,
        result.cache_complete_read_tokens,
    ];
    let has_tokens_without_coverage = [
        (result.new_input_request_count, result.new_input_tokens),
        (result.output_request_count, result.output_tokens),
        (result.total_request_count, result.total_tokens),
        (result.cache_read_request_count, result.cache_read_tokens),
        (result.cache_write_request_count, result.cache_write_tokens),
        (
            result.cache_complete_request_count,
            result.cache_complete_input_tokens,
        ),
        (
            result.cache_complete_request_count,
            result.cache_complete_read_tokens,
        ),
    ]
    .into_iter()
    .any(|(coverage, tokens)| coverage == 0 && tokens != 0);
    if values.into_iter().any(|value| value > MAX_SAFE_INTEGER)
        || result.measured_request_count > result.request_count
        || [
            result.new_input_request_count,
            result.output_request_count,
            result.total_request_count,
            result.cache_read_request_count,
            result.cache_write_request_count,
            result.cache_complete_request_count,
        ]
        .into_iter()
        .any(|coverage| coverage > result.measured_request_count)
        || has_tokens_without_coverage
        || result.cache_complete_request_count > result.new_input_request_count
        || result.cache_complete_request_count > result.cache_read_request_count
        || result.cache_complete_read_tokens > result.cache_complete_input_tokens
        || result.cache_complete_read_tokens > result.cache_read_tokens
    {
        return Err(UsageProtocolError::SummarySemantics);
    }
    Ok(result)
}

/// 解码 Thread snapshot 的显式 nullable 用量；同时冻结 profile、certainty 与估算的一致关系。
pub fn parse_thread_context_usage(
    value: &Value,
) -> Result<Option<ThreadContextUsageDto>, UsageProtocolError> {
    if value.is_null() {
        return Ok(None);
    }
    if !valid_context_usage_wire(value) {
        return Err(UsageProtocolError::ContextUsageShape);
    }
    serde_json::from_value(value.clone())
        .map(Some)
        .map_err(|_| UsageProtocolError::ContextUsageShape)
}

/// 校验 usage/profile/estimate 的 exact field sets 与交叉字段约束后才允许 DTO 解码。
fn valid_context_usage_wire(value: &Value) -> bool {
    let Some(usage) = value.as_object() else {
        return false;
    };
    if !has_exact_fields(
        usage,
        &[
            "turnId",
            "modelRequestId",
            "modelRequestOrdinal",
            "modelRound",
            "purpose",
            "certainty",
            "profile",
            "inputTokens",
            "outputTokens",
            "totalTokens",
            "measuredAt",
            "contextEstimate",
        ],
    ) || !valid_prefixed_value(usage.get("turnId"), "turn_", 128)
        || !valid_prefixed_value(usage.get("modelRequestId"), "modelreq_", 104)
        || !integer_in_range(usage.get("modelRequestOrdinal"), 1, MAX_SAFE_INTEGER)
        || !integer_in_range(usage.get("modelRound"), 1, 128)
        || !matches!(
            usage.get("purpose").and_then(Value::as_str),
            Some("assistant" | "summary")
        )
        || !usage
            .get("measuredAt")
            .and_then(Value::as_str)
            .is_some_and(super::valid_protocol_timestamp)
        || !usage
            .get("profile")
            .is_some_and(valid_provider_request_profile_wire)
    {
        return false;
    }
    let context_estimate_valid = match usage.get("contextEstimate") {
        Some(Value::Null) => true,
        Some(estimate) => {
            let Some(estimate_object) = estimate.as_object() else {
                return false;
            };
            has_exact_fields(
                estimate_object,
                &[
                    "certainty",
                    "source",
                    "usedTokens",
                    "limitTokens",
                    "measuredAt",
                ],
            ) && usage.get("certainty").and_then(Value::as_str) == Some("unknown")
                && estimate_object.get("certainty").and_then(Value::as_str) == Some("estimated")
                && estimate_object.get("source").and_then(Value::as_str) == Some("local")
                && integer_in_range(estimate_object.get("usedTokens"), 0, MAX_SAFE_INTEGER)
                && integer_in_range(estimate_object.get("limitTokens"), 1, MAX_SAFE_INTEGER)
                && usage
                    .get("profile")
                    .and_then(|profile| profile.get("contextWindowTokens"))
                    == estimate_object.get("limitTokens")
                && estimate_object
                    .get("measuredAt")
                    .and_then(Value::as_str)
                    .is_some_and(super::valid_protocol_timestamp)
        }
        None => false,
    };
    match usage.get("certainty").and_then(Value::as_str) {
        Some("known") => {
            context_estimate_valid
                && integer_in_range(usage.get("inputTokens"), 0, MAX_SAFE_INTEGER)
                && integer_in_range(usage.get("outputTokens"), 0, MAX_SAFE_INTEGER)
                && integer_in_range(usage.get("totalTokens"), 0, MAX_SAFE_INTEGER)
                && usage
                    .get("inputTokens")
                    .and_then(Value::as_u64)
                    .zip(usage.get("outputTokens").and_then(Value::as_u64))
                    .and_then(|(input, output)| input.checked_add(output))
                    .zip(usage.get("totalTokens").and_then(Value::as_u64))
                    .is_some_and(|(minimum, total)| total >= minimum)
        }
        Some("unknown") => {
            ["inputTokens", "outputTokens", "totalTokens"]
                .iter()
                .all(|key| usage.get(*key).is_some_and(Value::is_null))
                && context_estimate_valid
        }
        _ => false,
    }
}

/// 冻结 Provider profile 的 wire vocabulary，防止缺项被当前设置推测填补。
fn valid_provider_request_profile_wire(value: &Value) -> bool {
    let Some(profile) = value.as_object() else {
        return false;
    };
    has_exact_fields(
        profile,
        &[
            "providerId",
            "modelId",
            "api",
            "upstreamModel",
            "requestedReasoning",
            "effectiveReasoning",
            "accessMode",
            "collaborationMode",
            "configGeneration",
            "promptRevision",
            "toolCatalogRevision",
            "contextWindowTokens",
            "maxOutputTokens",
        ],
    ) && valid_prefixed_value(profile.get("providerId"), "provider_", 128)
        && valid_prefixed_value(profile.get("modelId"), "model_", 128)
        && matches!(
            profile.get("api").and_then(Value::as_str),
            Some("anthropic_messages" | "openai_responses" | "openai_chat_completions")
        )
        && profile
            .get("upstreamModel")
            .and_then(Value::as_str)
            .is_some_and(|model| {
                !model.is_empty() && model.len() <= 512 && !model.chars().any(char::is_control)
            })
        && ["requestedReasoning", "effectiveReasoning"]
            .iter()
            .all(|field| {
                profile.get(*field).is_some_and(|reasoning| {
                    reasoning.is_null() || reasoning.as_str().is_some_and(valid_reasoning_level)
                })
            })
        && matches!(
            profile.get("accessMode").and_then(Value::as_str),
            Some("approval_required" | "full_access")
        )
        && matches!(
            profile.get("collaborationMode").and_then(Value::as_str),
            Some("default" | "plan")
        )
        && profile
            .get("configGeneration")
            .and_then(Value::as_str)
            .is_some_and(|value| value.starts_with("cfg_") && value.len() <= 128)
        && ["promptRevision", "toolCatalogRevision"]
            .iter()
            .all(|field| {
                profile
                    .get(*field)
                    .and_then(Value::as_str)
                    .is_some_and(|value| {
                        !value.is_empty()
                            && value.len() <= 256
                            && value.chars().all(|character| {
                                character.is_ascii_alphanumeric()
                                    || matches!(character, '_' | '-' | '.' | ':')
                            })
                    })
            })
        && integer_in_range(profile.get("contextWindowTokens"), 1, MAX_SAFE_INTEGER)
        && integer_in_range(profile.get("maxOutputTokens"), 1, MAX_SAFE_INTEGER)
}

/// 用封闭字段数加逐字段匹配拒绝缺字段与未知字段，特别保护 nullable wire 必填语义。
fn has_exact_fields(object: &Map<String, Value>, expected: &[&str]) -> bool {
    object.len() == expected.len() && expected.iter().all(|field| object.contains_key(*field))
}

/// JSON usage 数值仅允许可由 JavaScript 精确表示的非负整数。
fn integer_in_range(value: Option<&Value>, minimum: u64, maximum: u64) -> bool {
    value
        .and_then(Value::as_u64)
        .is_some_and(|number| (minimum..=maximum).contains(&number))
}

/// Value 中 opaque identity 必须遵守既有前缀、长度和字符集合，不返回输入值。
fn valid_prefixed_value(value: Option<&Value>, prefix: &str, maximum: usize) -> bool {
    value
        .and_then(Value::as_str)
        .is_some_and(|value| valid_prefixed_identity(value, prefix, maximum))
}

/// 以 ASCII identity 子集拒绝空后缀、路径和控制字符，保持 CLI/Tauri 一致的边界。
fn valid_prefixed_identity(value: &str, prefix: &str, maximum: usize) -> bool {
    value.starts_with(prefix)
        && value.len() > prefix.len()
        && value.len() <= maximum
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.')
        })
}

/// Profile 的 reasoning 仅允许目前共享的公开档位词汇。
fn valid_reasoning_level(value: &str) -> bool {
    matches!(
        value,
        "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max"
    )
}
