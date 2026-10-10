// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::*;

/// 以完整覆盖计数 fixture 固定当前聚合 summary 的 camelCase wire shape。
fn summary_fixture() -> Value {
    json!({
        "threadId": "thr_usage_fixture",
        "snapshotRevision": 7,
        "requestCount": 3,
        "measuredRequestCount": 2,
        "newInputRequestCount": 2,
        "newInputTokens": 200,
        "outputRequestCount": 2,
        "outputTokens": 60,
        "totalRequestCount": 2,
        "totalTokens": 260,
        "cacheReadRequestCount": 1,
        "cacheReadTokens": 80,
        "cacheWriteRequestCount": 1,
        "cacheWriteTokens": 20,
        "cacheCompleteRequestCount": 1,
        "cacheCompleteInputTokens": 100,
        "cacheCompleteReadTokens": 80
    })
}

/// 新增会话上下文 fixture 带齐不可由当前配置回填的 request profile 与必填 nullable 字段。
fn context_usage_fixture() -> Value {
    json!({
        "turnId": "turn_usage_fixture",
        "modelRequestId": "modelreq_usage_fixture",
        "modelRequestOrdinal": 2,
        "modelRound": 1,
        "purpose": "assistant",
        "certainty": "known",
        "profile": {
            "providerId": "provider_usage_fixture",
            "modelId": "model_usage_fixture",
            "api": "openai_responses",
            "upstreamModel": "fixture-model",
            "requestedReasoning": "medium",
            "effectiveReasoning": "medium",
            "accessMode": "approval_required",
            "collaborationMode": "default",
            "configGeneration": "cfg_usage_fixture",
            "promptRevision": "prompt_1",
            "toolCatalogRevision": "tools_1",
            "contextWindowTokens": 128000,
            "maxOutputTokens": 4096
        },
        "inputTokens": 100,
        "outputTokens": 20,
        "totalTokens": 120,
        "measuredAt": "2026-09-01T10:00:00Z",
        "contextEstimate": null
    })
}

/// 摘要共享 parser 接受 Java 当前 wire，并返回可由 Tauri/CLI 共用的强类型 DTO。
#[test]
fn summary_parser_accepts_current_wire_shape() {
    let summary = parse_thread_usage_summary(summary_fixture()).expect("valid usage summary");

    assert_eq!(summary.thread_id, "thr_usage_fixture");
    assert_eq!(summary.new_input_tokens, 200);
    assert_eq!(summary.cache_complete_read_tokens, 80);
}

/// 摘要校验必须拒绝额外/缺失字段、不可精确数字和覆盖量自相矛盾。
#[test]
fn summary_parser_rejects_malformed_or_inconsistent_values() {
    let mut missing = summary_fixture();
    missing
        .as_object_mut()
        .expect("summary object")
        .remove("newInputTokens");
    assert!(parse_thread_usage_summary(missing).is_err());

    let mut unknown = summary_fixture();
    unknown["privatePayload"] = json!("must not leak");
    assert!(parse_thread_usage_summary(unknown).is_err());

    let mut unsafe_number = summary_fixture();
    unsafe_number["totalTokens"] = json!(9_007_199_254_740_992_u64);
    assert!(parse_thread_usage_summary(unsafe_number).is_err());

    let mut bad_coverage = summary_fixture();
    bad_coverage["newInputRequestCount"] = json!(3);
    assert!(parse_thread_usage_summary(bad_coverage).is_err());

    let mut bad_identity = summary_fixture();
    bad_identity["threadId"] = json!("thr_../outside");
    assert!(parse_thread_usage_summary(bad_identity).is_err());
}

/// Java 对每项覆盖数和 Token 累计使用相同过滤条件；覆盖为零须拒绝非零累计，但覆盖样本可合法为零 Token。
/// @author kongweiguang
#[test]
fn summary_parser_rejects_tokens_without_coverage_but_accepts_reported_zero() {
    for (coverage_field, token_field) in [
        ("newInputRequestCount", "newInputTokens"),
        ("outputRequestCount", "outputTokens"),
        ("totalRequestCount", "totalTokens"),
        ("cacheReadRequestCount", "cacheReadTokens"),
        ("cacheWriteRequestCount", "cacheWriteTokens"),
    ] {
        let mut inconsistent = summary_fixture();
        inconsistent[coverage_field] = json!(0);
        inconsistent[token_field] = json!(1);
        assert!(
            parse_thread_usage_summary(inconsistent).is_err(),
            "{token_field} must require reported coverage"
        );
    }

    let mut uncovered_cache_input = summary_fixture();
    uncovered_cache_input["cacheCompleteRequestCount"] = json!(0);
    uncovered_cache_input["cacheCompleteInputTokens"] = json!(1);
    uncovered_cache_input["cacheCompleteReadTokens"] = json!(1);
    assert!(parse_thread_usage_summary(uncovered_cache_input).is_err());

    let mut uncovered_cache_read = summary_fixture();
    uncovered_cache_read["cacheCompleteRequestCount"] = json!(0);
    uncovered_cache_read["cacheCompleteReadTokens"] = json!(1);
    assert!(parse_thread_usage_summary(uncovered_cache_read).is_err());

    let mut reported_zero_tokens = summary_fixture();
    for token_field in [
        "newInputTokens",
        "outputTokens",
        "totalTokens",
        "cacheReadTokens",
        "cacheWriteTokens",
        "cacheCompleteInputTokens",
        "cacheCompleteReadTokens",
    ] {
        reported_zero_tokens[token_field] = json!(0);
    }
    assert!(parse_thread_usage_summary(reported_zero_tokens).is_ok());

    let mut empty_ledger = summary_fixture();
    for field in [
        "requestCount",
        "measuredRequestCount",
        "newInputRequestCount",
        "newInputTokens",
        "outputRequestCount",
        "outputTokens",
        "totalRequestCount",
        "totalTokens",
        "cacheReadRequestCount",
        "cacheReadTokens",
        "cacheWriteRequestCount",
        "cacheWriteTokens",
        "cacheCompleteRequestCount",
        "cacheCompleteInputTokens",
        "cacheCompleteReadTokens",
    ] {
        empty_ledger[field] = json!(0);
    }
    assert!(parse_thread_usage_summary(empty_ledger).is_ok());
}

/// 完整样本同时命中新输入与缓存读取；读取累计是所有缓存读取的子集，但输入含缓存时无需写缓存字段。
/// @author kongweiguang
#[test]
fn summary_parser_bounds_complete_cache_to_component_coverage_and_read_total() {
    let mut outside_new_input = summary_fixture();
    outside_new_input["newInputRequestCount"] = json!(1);
    outside_new_input["cacheCompleteRequestCount"] = json!(2);
    assert!(parse_thread_usage_summary(outside_new_input).is_err());

    let mut outside_cache_read = summary_fixture();
    outside_cache_read["cacheCompleteRequestCount"] = json!(2);
    assert!(parse_thread_usage_summary(outside_cache_read).is_err());

    let mut complete_read_exceeds_total_read = summary_fixture();
    complete_read_exceeds_total_read["cacheCompleteReadTokens"] = json!(81);
    assert!(parse_thread_usage_summary(complete_read_exceeds_total_read).is_err());

    let mut input_includes_cache_without_write_sample = summary_fixture();
    input_includes_cache_without_write_sample["cacheWriteRequestCount"] = json!(0);
    input_includes_cache_without_write_sample["cacheWriteTokens"] = json!(0);
    assert!(parse_thread_usage_summary(input_includes_cache_without_write_sample).is_ok());
}

/// Context decoder 接受 nullable snapshot、已知 Usage 与 UNKNOWN 本地估算三种真实状态。
#[test]
fn context_parser_accepts_known_unknown_and_null_states() {
    assert_eq!(parse_thread_context_usage(&Value::Null).unwrap(), None);

    let known = parse_thread_context_usage(&context_usage_fixture())
        .expect("valid context usage")
        .expect("present context usage");
    assert_eq!(known.certainty, "known");
    assert_eq!(known.profile.context_window_tokens, 128_000);
    assert_eq!(known.total_tokens, Some(120));

    let mut unknown = context_usage_fixture();
    unknown["certainty"] = json!("unknown");
    unknown["inputTokens"] = Value::Null;
    unknown["outputTokens"] = Value::Null;
    unknown["totalTokens"] = Value::Null;
    unknown["contextEstimate"] = json!({
        "certainty": "estimated",
        "source": "local",
        "usedTokens": 18500,
        "limitTokens": 128000,
        "measuredAt": "2026-09-01T10:00:01Z"
    });
    let unknown = parse_thread_context_usage(&unknown)
        .expect("valid local context estimate")
        .expect("present context usage");
    assert_eq!(unknown.certainty, "unknown");
    assert_eq!(unknown.input_tokens, None);
    assert_eq!(
        unknown.context_estimate.expect("estimate").used_tokens,
        18_500
    );
}

/// Context parser 拒绝缺失 nullable 字段、Token 不一致和冻结窗口不匹配的估算。
#[test]
fn context_parser_rejects_partial_usage_and_invalid_estimates() {
    let mut missing_nullable = context_usage_fixture();
    missing_nullable
        .as_object_mut()
        .expect("context object")
        .remove("contextEstimate");
    assert!(parse_thread_context_usage(&missing_nullable).is_err());

    let mut inconsistent_total = context_usage_fixture();
    inconsistent_total["totalTokens"] = json!(119);
    assert!(parse_thread_context_usage(&inconsistent_total).is_err());

    let mut bad_estimate = context_usage_fixture();
    bad_estimate["certainty"] = json!("unknown");
    bad_estimate["inputTokens"] = Value::Null;
    bad_estimate["outputTokens"] = Value::Null;
    bad_estimate["totalTokens"] = Value::Null;
    bad_estimate["contextEstimate"] = json!({
        "certainty": "estimated",
        "source": "local",
        "usedTokens": 18500,
        "limitTokens": 64000,
        "measuredAt": "2026-09-01T10:00:01Z"
    });
    assert!(parse_thread_context_usage(&bad_estimate).is_err());

    let mut partial_profile = context_usage_fixture();
    partial_profile["profile"]["unexpected"] = json!(true);
    assert!(parse_thread_context_usage(&partial_profile).is_err());
}

/// 两个 parser 都必须拒绝负数和小数，不能把非整数计量静默转换成 Token 数。
#[test]
fn usage_parsers_reject_negative_and_fractional_numbers() {
    let mut negative_summary = summary_fixture();
    negative_summary["snapshotRevision"] = json!(-1);
    assert!(parse_thread_usage_summary(negative_summary).is_err());

    let mut fractional_summary = summary_fixture();
    fractional_summary["totalTokens"] = json!(260.5);
    assert!(parse_thread_usage_summary(fractional_summary).is_err());

    for (field, invalid_value) in [
        ("modelRequestOrdinal", json!(-1)),
        ("modelRequestOrdinal", json!(1.5)),
        ("inputTokens", json!(-1)),
        ("outputTokens", json!(20.5)),
    ] {
        let mut invalid = context_usage_fixture();
        invalid[field] = invalid_value;
        assert!(
            parse_thread_context_usage(&invalid).is_err(),
            "{field} must reject negative or fractional values"
        );
    }
}

/// 已知计量只能携带实际 token，未知计量可无估算但不能混入 token 样本。
#[test]
fn context_parser_keeps_certainty_and_nullable_fields_consistent() {
    let mut known_with_estimate = context_usage_fixture();
    known_with_estimate["contextEstimate"] = json!({
        "certainty": "estimated",
        "source": "local",
        "usedTokens": 18500,
        "limitTokens": 128000,
        "measuredAt": "2026-09-01T10:00:01Z"
    });
    assert!(parse_thread_context_usage(&known_with_estimate).is_err());

    let mut unknown_with_tokens = context_usage_fixture();
    unknown_with_tokens["certainty"] = json!("unknown");
    unknown_with_tokens["inputTokens"] = json!(0);
    unknown_with_tokens["outputTokens"] = Value::Null;
    unknown_with_tokens["totalTokens"] = Value::Null;
    assert!(parse_thread_context_usage(&unknown_with_tokens).is_err());

    let mut unknown_without_estimate = context_usage_fixture();
    unknown_without_estimate["certainty"] = json!("unknown");
    unknown_without_estimate["inputTokens"] = Value::Null;
    unknown_without_estimate["outputTokens"] = Value::Null;
    unknown_without_estimate["totalTokens"] = Value::Null;
    unknown_without_estimate["contextEstimate"] = Value::Null;
    let parsed = parse_thread_context_usage(&unknown_without_estimate)
        .expect("unknown usage without estimate remains valid")
        .expect("explicit unknown usage");
    assert_eq!(parsed.certainty, "unknown");
    assert_eq!(parsed.context_estimate, None);
}
