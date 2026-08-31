// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use serde_json::json;

/// Workspace page 保持共享 cursor/limit shape，不引入 Thread-specific scope。
#[test]
fn page_input_contains_only_cursor_and_limit() {
    let value = serde_json::to_value(PageInput {
        cursor: Some("cursor_1".to_owned()),
        limit: Some(200),
    })
    .expect("page input");
    assert_eq!(value, json!({"cursor": "cursor_1", "limit": 200}));
}

/// Thread page 进入 bridge 前必须携带 Java-issued Workspace identity。
#[test]
fn thread_list_input_is_workspace_scoped() {
    let input = ThreadListInput {
        workspace_id: "ws_demo".to_owned(),
        cursor: None,
        limit: Some(200),
    };
    assert!(validate_thread_list(&input).is_ok());
    assert_eq!(
        serde_json::to_value(input).expect("thread list input"),
        json!({"workspaceId": "ws_demo", "limit": 200})
    );
}

/// v3 create 结果为直接投影，并只接受下一轮 Provider/Model 偏好而非旧 Profile binding。
#[test]
fn thread_create_result_is_direct() {
    let direct = json!({
        "threadId": "thr_demo",
        "workspaceId": "ws_demo",
        "preferences": {
            "providerId": "provider_demo",
            "modelId": "model_demo",
            "reasoningLevel": "medium",
            "accessMode": "full_access",
            "titleSource": "manual"
        },
        "title": "Demo",
        "status": "active",
        "revision": 1,
        "createdAt": "2026-08-25T00:00:00Z",
        "updatedAt": "2026-08-25T00:00:00Z"
    });
    assert!(parse_thread(direct.clone()).is_ok());
    assert!(parse_thread(json!({"thread": direct})).is_err());
    assert!(
        parse_thread(json!({
            "threadId": "thr_demo", "workspaceId": "ws_demo", "profileId": null,
            "title": "Demo", "status": "active", "revision": 1,
            "createdAt": "2026-08-25T00:00:00Z", "updatedAt": "2026-08-25T00:00:00Z"
        }))
        .is_err()
    );
}

/// 锁定 Java 所有的 Workspace 投影和统一 items 键，避免 Rust 旧字段误拒绝成功列表。
#[test]
fn workspace_page_uses_root_projection() {
    let page = json!({
        "items": [{
            "workspaceId": "ws_demo",
            "root": "C:\\demo",
            "displayName": "Demo",
            "trust": "trusted",
            "revision": 1
        }],
        "nextCursor": null
    });
    assert!(parse_workspace_page(page.clone()).is_ok());
    assert!(
        parse_workspace_page(json!({"workspaces": page["items"], "nextCursor": null})).is_err()
    );
}

/// Thread list 也只接受统一 items 键，防止 Tauri 继续传播旧领域专用字段。
#[test]
fn thread_page_rejects_old_list_key() {
    let items = json!([{
        "threadId": "thr_demo", "workspaceId": "ws_demo",
        "preferences": {
            "providerId": "provider_demo", "modelId": "model_demo",
            "reasoningLevel": null, "accessMode": "approval_required", "titleSource": "auto"
        },
        "title": "Demo", "status": "active", "revision": 1,
        "createdAt": "2026-08-25T00:00:00Z", "updatedAt": "2026-08-25T00:00:00Z"
    }]);
    assert!(parse_thread_page(json!({"items": items.clone(), "nextCursor": null})).is_ok());
    assert!(parse_thread_page(json!({"threads": items, "nextCursor": null})).is_err());
}

/// Snapshot pagination 拒绝已删除的 afterSeq/event replay shape。
#[test]
fn thread_read_requires_snapshot_cursor_shape() {
    let valid: ThreadReadInput = serde_json::from_value(json!({
        "threadId": "thr_demo",
        "cursor": "10",
        "limit": 200
    }))
    .expect("valid read input");
    assert!(validate_thread_read(&valid).is_ok());
    assert!(
        serde_json::from_value::<ThreadReadInput>(json!({
            "threadId": "thr_demo",
            "afterSeq": 10
        }))
        .is_err()
    );
}

/// 可扩展 item object 不能夹带 credential field 到 WebView。
#[test]
fn thread_read_rejects_private_item_fields() {
    let result = json!({
        "threadId": "thr_demo",
        "revision": 1,
        "turns": [],
        "items": [{"itemId": "item_demo", "metadata": {"secretValue": "hidden"}}],
        "contextUsage": null,
        "nextCursor": null
    });
    assert!(parse_thread_read(result).is_err());
}

/// 该 fixture 镜像当前 Java V4 snapshot：Turn 的 runtime/changeSet/completedAt/errorCode 均
/// 显式存在，Tool 只携带安全 presentation，便于锁定冷启动恢复的真实 wire 形状。
fn v4_thread_read_fixture() -> serde_json::Value {
    json!({
        "threadId": "thr_demo",
        "revision": 11,
        "turns": [{
            "turnId": "turn_demo",
            "status": "completed",
            "runtime": null,
            "requestedAt": "2026-08-30T10:00:00Z",
            "updatedAt": "2026-08-30T10:00:02Z",
            "completedAt": "2026-08-30T10:00:02Z",
            "changeSet": {
                "state": "available",
                "files": [{
                    "path": "src/main.rs", "status": "modified",
                    "additions": 1, "deletions": 0,
                    "binary": false, "truncated": false
                }],
                "stats": {
                    "files": 1, "additions": 1, "deletions": 0,
                    "binaryFiles": 0, "truncated": false
                },
                "artifactId": "artifact_change_demo"
            },
            "errorCode": null
        }],
        "items": [
            {
                "itemId": "item_user_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "kind": "user_input", "text": "检查合同"
            },
            {
                "itemId": "item_progress_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "kind": "assistant_progress",
                "text": "正在检查合同。", "modelRound": 1
            },
            {
                "itemId": "item_reasoning_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "kind": "reasoning_summary",
                "text": "需要先验证当前协议。", "modelRound": 1
            },
            {
                "itemId": "item_call_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "kind": "tool_call", "callId": "call_shell_demo",
                "toolName": "shell", "ordinal": 0,
                "presentation": {
                    "kind": "shell", "title": "运行命令", "status": "success",
                    "outputPreview": "M src/main.rs", "relativePaths": ["src/main.rs"],
                    "command": "git status --short", "relativeCwd": "workspace",
                    "stdout": "M src/main.rs", "exitCode": 0, "durationMs": 18,
                    "truncated": true, "artifactId": "artifact_tool_demo"
                }
            },
            {
                "itemId": "item_approval_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "kind": "approval", "approvalId": "appr_demo",
                "callId": "call_shell_demo", "toolName": "shell", "reason": "Tool requires approval",
                "expiresAt": "2026-08-30T10:05:00Z", "decision": null
            },
            {
                "itemId": "item_attachment_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "kind": "attachment", "attachmentId": "att_demo",
                "displayName": "设计说明.pdf", "sizeBytes": 2048, "mediaKind": "pdf",
                "mediaType": "application/pdf", "state": "bound"
            },
            {
                "itemId": "item_final_demo", "createdAt": "2026-08-30T10:00:02Z",
                "turnId": "turn_demo", "kind": "final_answer", "text": "合同检查完成。"
            }
        ],
        "contextUsage": {
            "turnId": "turn_demo",
            "modelRound": 1,
            "inputTokens": 100,
            "outputTokens": 20,
            "totalTokens": 120,
            "measuredAt": "2026-08-30T10:00:00Z"
        },
        "nextCursor": null
    })
}

/// 冷启动恢复必须接受包含 `changeSet`、精确 Usage 且 `runtime:null` 的真实 V4 快照；缺失必需
/// nullable 字段与绝对文件路径仍应关闭失败。
#[test]
fn thread_read_accepts_v4_snapshot_and_preserves_required_nulls() {
    let fixture = v4_thread_read_fixture();
    let parsed = parse_thread_read(fixture.clone()).expect("V4 history fixture");
    assert!(parsed.turns[0].runtime.is_none());
    assert_eq!(
        parsed.turns[0]
            .change_set
            .as_ref()
            .and_then(|change_set| change_set.artifact_id.as_deref()),
        Some("artifact_change_demo")
    );
    assert_eq!(
        parsed.context_usage.as_ref().map(|usage| (
            usage.input_tokens,
            usage.output_tokens,
            usage.total_tokens
        )),
        Some((100, 20, 120))
    );

    let mut missing_context_usage = fixture.clone();
    missing_context_usage
        .as_object_mut()
        .expect("thread read object")
        .remove("contextUsage");
    assert!(parse_thread_read(missing_context_usage).is_err());

    let mut missing_change_set = fixture.clone();
    missing_change_set["turns"][0]
        .as_object_mut()
        .expect("turn object")
        .remove("changeSet");
    assert!(parse_thread_read(missing_change_set).is_err());

    let mut absolute_path = fixture;
    absolute_path["turns"][0]["changeSet"]["files"][0]["path"] = json!("C:/private/main.rs");
    assert!(parse_thread_read(absolute_path).is_err());
}

/// Context Usage 不能悬挂到其它 Turn，也不能用小于输入输出之和的 total 伪造较低占用。
#[test]
fn thread_read_rejects_orphaned_or_inconsistent_context_usage() {
    let mut orphaned = v4_thread_read_fixture();
    orphaned["contextUsage"]["turnId"] = json!("turn_other");
    assert!(parse_thread_read(orphaned).is_err());

    let mut inconsistent = v4_thread_read_fixture();
    inconsistent["contextUsage"]["totalTokens"] = json!(119);
    assert!(parse_thread_read(inconsistent).is_err());
}

/// Java 对可选 change-set 与单文件字段使用“缺失”而非 `null`；Rust 反序列化后再次投影给
/// WebView 时必须保留该语义，否则 TypeScript 严格判别联合会把合法历史误判为运行时不可用。
#[test]
fn thread_read_serialization_omits_absent_change_set_fields() {
    let mut fixture = v4_thread_read_fixture();
    fixture["turns"][0]["changeSet"] = json!({
        "state": "unavailable",
        "reason": "not_git",
        "files": [],
        "stats": {
            "files": 0,
            "additions": 0,
            "deletions": 0,
            "binaryFiles": 0,
            "truncated": false
        }
    });

    let parsed = parse_thread_read(fixture).expect("unavailable change set fixture");
    let projected = serde_json::to_value(parsed).expect("WebView history projection");
    let change_set = projected["turns"][0]["changeSet"]
        .as_object()
        .expect("change set object");

    assert_eq!(change_set.get("reason"), Some(&json!("not_git")));
    assert!(!change_set.contains_key("artifactId"));

    let mut available = v4_thread_read_fixture();
    let available_change_set = available["turns"][0]["changeSet"]
        .as_object_mut()
        .expect("available change set");
    available_change_set.remove("artifactId");
    let available_file = available_change_set["files"][0]
        .as_object_mut()
        .expect("available change file");
    available_file.remove("oldPath");
    available_file.remove("additions");
    available_file.remove("deletions");
    let projected = serde_json::to_value(
        parse_thread_read(available).expect("available change set without artifact"),
    )
    .expect("WebView available history projection");
    let change_set = projected["turns"][0]["changeSet"]
        .as_object()
        .expect("available change set object");
    assert!(!change_set.contains_key("reason"));
    assert!(!change_set.contains_key("artifactId"));
    let change_file = change_set["files"][0]
        .as_object()
        .expect("projected change file");
    assert!(!change_file.contains_key("oldPath"));
    assert!(!change_file.contains_key("additions"));
    assert!(!change_file.contains_key("deletions"));
}

/// Tool 状态必须来自当前闭集；历史中的旧 `unknown` 不能被投影成一个看似真实的步骤，
/// 应关闭失败并由调用方重新读取权威快照。
#[test]
fn thread_read_rejects_unknown_tool_presentation_status() {
    let mut fixture = v4_thread_read_fixture();
    fixture["items"][3]["presentation"]["status"] = json!("unknown");
    assert!(parse_thread_read(fixture).is_err());
}

/// Snapshot item 页精确接受 200 条并拒绝第 201 条；字节预算继续受 4 MiB frame 边界约束，
/// 不因 V4 Tool/change-set 扩展而形成无界恢复路径。
#[test]
fn thread_read_enforces_v4_item_page_limit() {
    let mut fixture = v4_thread_read_fixture();
    let items = (0..200)
        .map(|index| {
            json!({
                "itemId": format!("item_final_{index}"),
                "createdAt": "2026-08-30T10:00:02Z",
                "turnId": "turn_demo",
                "kind": "final_answer",
                "text": "ok"
            })
        })
        .collect::<Vec<_>>();
    fixture["items"] = json!(items);
    assert!(parse_thread_read(fixture.clone()).is_ok());
    fixture["items"]
        .as_array_mut()
        .expect("items array")
        .push(json!({
            "itemId": "item_final_overflow",
            "createdAt": "2026-08-30T10:00:02Z",
            "turnId": "turn_demo",
            "kind": "final_answer",
            "text": "overflow"
        }));
    assert!(parse_thread_read(fixture).is_err());
}

/// thread/compact 输入只允许 Thread identity 与 CAS revision，拒绝旧策略或 Provider 参数。
#[test]
fn thread_compact_input_is_exact_and_safe() {
    let input: ThreadCompactInput = serde_json::from_value(json!({
        "threadId": "thr_demo",
        "expectedThreadRevision": 7
    }))
    .expect("valid compact input");
    assert_eq!(
        serde_json::to_value(input).expect("compact input"),
        json!({"threadId": "thr_demo", "expectedThreadRevision": 7})
    );
    assert!(
        serde_json::from_value::<ThreadCompactInput>(json!({
            "threadId": "thr_demo",
            "expectedThreadRevision": 7,
            "strategy": "local"
        }))
        .is_err()
    );
}

/// 压缩结果要求两个 nullable identity 字段始终存在，并与 compacted/unchanged 状态绑定。
#[test]
fn thread_compact_result_enforces_outcome_invariants() {
    let compacted = json!({
        "outcome": "compacted",
        "compactionId": "cmp_demo",
        "checkpointId": "checkpoint_demo",
        "threadRevision": 8,
        "inputTokensBefore": 100,
        "inputTokensAfter": 60
    });
    assert!(parse_thread_compact(compacted.clone()).is_ok());

    let unchanged = json!({
        "outcome": "unchanged",
        "compactionId": null,
        "checkpointId": null,
        "threadRevision": 8,
        "inputTokensBefore": 60,
        "inputTokensAfter": 60
    });
    assert!(parse_thread_compact(unchanged).is_ok());

    let mut missing_nullable_identity = compacted.clone();
    missing_nullable_identity
        .as_object_mut()
        .expect("compact result")
        .remove("compactionId");
    assert!(parse_thread_compact(missing_nullable_identity).is_err());

    let mut non_reducing = compacted;
    non_reducing["inputTokensAfter"] = json!(100);
    assert!(parse_thread_compact(non_reducing).is_err());
}
