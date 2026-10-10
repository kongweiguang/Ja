// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[path = "../support/headless_test_support.rs"]
mod support;

use serde_json::{Value, json};
use std::io::Cursor;
use std::path::Path;

/// 管道上下文应位于任务正文之前，让脚本能组合仓库数据与执行意图。
#[test]
fn prompt_and_stdin_context_are_joined_in_order() {
    let text = support::headless::read_prompt(
        Some("fix the failing test".into()),
        false,
        Cursor::new("compiler output"),
    )
    .expect("combined prompt");

    assert_eq!(text, "compiler output\n\nfix the failing test");
}

/// 显式使用短横线时始终读取 stdin，保持 Shell 调用行为可预测。
#[test]
fn dash_reads_stdin_without_a_separate_prompt() {
    let text =
        support::headless::read_prompt(Some("-".into()), true, Cursor::new("inspect this diff"))
            .expect("stdin prompt");

    assert_eq!(text, "inspect this diff");
}

/// 空内容、超限内容和含 NUL 的输入必须在启动运行时前失败。
#[test]
fn prompt_rejects_empty_oversized_and_nul_input() {
    assert!(support::headless::read_prompt(None, true, Cursor::new("")).is_err());
    assert!(support::headless::read_prompt(Some("\0".into()), true, Cursor::new("")).is_err());
    let oversized = "x".repeat(support::headless::MAX_PROMPT_BYTES + 1);
    assert!(support::headless::read_prompt(Some(oversized), true, Cursor::new("")).is_err());
}

/// 请求到达服务端前，终答 Schema 必须是 JSON object。
#[test]
fn output_schema_parser_accepts_objects_and_rejects_other_roots() {
    let schema =
        support::headless::parse_output_schema(br#"{"type":"object"}"#).expect("object schema");
    assert_eq!(schema, json!({"type":"object"}));

    for invalid in [&b"[]"[..], &b"null"[..], &b"not-json"[..], &b""[..]] {
        assert!(support::headless::parse_output_schema(invalid).is_err());
    }
}

/// 终答定位必须按精确 Turn 取最新 final_answer，不误选相邻会话的内容。
#[test]
fn final_answer_selection_is_scoped_to_turn_and_uses_latest_item() {
    let items = vec![
        json!({"itemId":"item_old","turnId":"turn_a","kind":"final_answer","text":"old"}),
        json!({"itemId":"item_other","turnId":"turn_b","kind":"final_answer","text":"other"}),
        json!({"itemId":"item_new","turnId":"turn_a","kind":"final_answer","text":"new"}),
    ];

    assert_eq!(
        support::headless::latest_final_answer_item(&items, "turn_a")
            .and_then(|item| item.get("itemId"))
            .and_then(Value::as_str),
        Some("item_new")
    );
    assert!(support::headless::latest_final_answer_item(&items, "turn_missing").is_none());
}

/// 人可复制的 PowerShell 命令必须引用空格路径，并用双单引号保留参数中的单引号。
#[test]
fn export_answer_command_quotes_thread_turn_and_path_literals() {
    let command = support::headless::export_answer_command(
        "thr with 'space'",
        "turn with 'space'",
        Path::new(r"C:\work dir\O'Brien.md"),
    );

    assert_eq!(
        command,
        "ja export-answer 'thr with ''space''' 'turn with ''space''' 'C:\\work dir\\O''Brien.md'"
    );
}

/// JSON 恢复 argv 保留身份和路径原值，普通错误继续输出原有的 verify_execution 动作。
#[test]
fn json_error_next_action_preserves_export_argv_and_generic_verification() {
    let output = Path::new(r"C:\work dir\O'Brien.md").to_path_buf();
    let export = support::headless::ErrorNextAction::ExportAnswer {
        thread_id: "thr with 'space'".into(),
        turn_id: "turn with 'space'".into(),
        output,
    };
    assert_eq!(
        support::headless::error_next_action(Some(export), Some("thr_fallback")),
        Some(json!({
            "kind":"export_answer",
            "argv":["ja","export-answer","thr with 'space'","turn with 'space'",r"C:\work dir\O'Brien.md"],
            "automaticRetry":false
        }))
    );
    assert_eq!(
        support::headless::error_next_action(None, Some("thr_original")),
        Some(json!({
            "kind":"verify_execution",
            "argv":["ja","resume","thr_original"],
            "automaticRetry":false
        }))
    );
    assert_eq!(support::headless::error_next_action(None, None), None);
}

/// 本次显式覆盖应映射到协议键，未选择的字段不得进入请求负载。
#[test]
fn runtime_overrides_contain_only_explicit_run_values() {
    let schema = json!({"type":"object"});
    let overrides = support::headless::runtime_overrides(
        Some("provider/model"),
        Some("full-access"),
        Some(schema.clone()),
    )
    .expect("valid overrides")
    .expect("selected overrides");
    assert_eq!(overrides.len(), 3);
    assert_eq!(overrides.get("modelId"), Some(&json!("provider/model")));
    assert_eq!(overrides.get("accessMode"), Some(&json!("full-access")));
    assert_eq!(overrides.get("outputSchema"), Some(&schema));

    assert!(
        support::headless::runtime_overrides(None, None, None)
            .expect("no overrides")
            .is_none()
    );
    for invalid_model in ["", "  ", "x\0y", &"x".repeat(257)] {
        assert!(support::headless::runtime_overrides(Some(invalid_model), None, None).is_err());
    }
}

/// accepted、通知和可恢复输入记录都保留固定的 v2 外层字段。
#[test]
fn jsonl_v2_progress_records_keep_identity_and_sequence_fields() {
    assert_eq!(
        support::headless::accepted("srv_1", "thr_2", "turn_3"),
        json!({
            "version": 2,
            "type": "accepted",
            "serverInstanceId": "srv_1",
            "threadId": "thr_2",
            "turnId": "turn_3"
        })
    );

    let params = json!({"turnId":"turn_3","eventSeq":7,"streamSeq":11});
    let notification = support::headless::notification("srv_1", "thr_2", "turn/updated", &params);
    assert_eq!(notification["eventSeq"], 7);
    assert_eq!(notification["streamSeq"], 11);
    assert_eq!(notification["params"], params);

    assert_eq!(
        support::headless::requires_input("srv_1", "thr_2", "turn_3", "waiting_approval"),
        json!({
            "version": 2,
            "type": "requires_input",
            "serverInstanceId": "srv_1",
            "threadId": "thr_2",
            "turnId": "turn_3",
            "state": "waiting_approval",
            "nextAction": {
                "kind": "resolve_input",
                "argv": ["ja", "resume", "thr_2"],
                "automaticRetry": false
            }
        })
    );
}

/// 终态投影分别保留持久事件序号和流序号，确保恢复与实时观察一致。
#[test]
fn jsonl_v2_terminal_and_error_records_have_stable_shapes() {
    let event_seq = json!(13);
    let stream_seq = json!(21);
    let terminal = support::headless::terminal(support::headless::TerminalRecord {
        server_instance_id: "srv_1",
        thread_id: "thr_2",
        turn_id: "turn_3",
        state: "completed",
        error_code: None,
        final_text: Some("done"),
        event_seq: Some(&event_seq),
        stream_seq: Some(&stream_seq),
    });
    assert_eq!(
        terminal,
        json!({
            "version": 2,
            "type": "terminal",
            "serverInstanceId": "srv_1",
            "threadId": "thr_2",
            "turnId": "turn_3",
            "state": "completed",
            "errorCode": null,
            "finalText": "done",
            "eventSeq": 13,
            "streamSeq": 21
        })
    );

    let error = support::headless::error(None, None, None, Some(4), None, "failed", 2, None);
    let expected: Value = json!({
        "version": 2,
        "type": "error",
        "serverInstanceId": null,
        "threadId": null,
        "turnId": null,
        "code": 4,
        "errorCode": null,
        "message": "failed",
        "exitCode": 2,
        "nextAction": null
    });
    assert_eq!(error, expected);

    let output_failure = support::headless::error(
        Some("srv_1"),
        Some("thr_2"),
        Some("turn_3"),
        None,
        Some("CLIENT_OUTPUT_FAILURE"),
        "终答文件写入失败",
        4,
        Some(json!({
            "kind":"export_answer",
            "argv":["ja","export-answer","thr_2","turn_3","answer.md"],
            "automaticRetry":false
        })),
    );
    assert_eq!(output_failure["errorCode"], "CLIENT_OUTPUT_FAILURE");
    assert_eq!(output_failure["code"], Value::Null);
    assert_eq!(output_failure["exitCode"], 4);
    assert_eq!(
        output_failure["nextAction"],
        json!({
            "kind":"export_answer",
            "argv":["ja","export-answer","thr_2","turn_3","answer.md"],
            "automaticRetry":false
        })
    );

    let untyped_output_failure = support::headless::error(
        Some("srv_1"),
        Some("thr_2"),
        Some("turn_3"),
        None,
        Some("CLIENT_OUTPUT_FAILURE"),
        "unclassified output failure",
        4,
        support::headless::error_next_action(None, Some("thr_2")),
    );
    assert_eq!(
        untyped_output_failure["nextAction"],
        json!({
            "kind":"verify_execution",
            "argv":["ja","resume","thr_2"],
            "automaticRetry":false
        })
    );

    let recovery = support::headless::error(
        Some("srv_1"),
        Some("thr_2"),
        Some("turn_3"),
        None,
        None,
        "connection interrupted",
        4,
        Some(json!({
            "kind":"verify_execution",
            "argv":["ja","resume","thr_2"],
            "automaticRetry":false
        })),
    );
    assert_eq!(
        recovery["nextAction"]["argv"],
        json!(["ja", "resume", "thr_2"])
    );
    assert_eq!(recovery["nextAction"]["automaticRetry"], false);
}
