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

/// 全局发现使用独立 scope 与最小目录投影，不得退化为完整 Thread 列表或正文查询。
#[test]
fn thread_discovery_uses_minimal_cross_workspace_projection() {
    let input: ThreadDiscoverInput = serde_json::from_value(json!({
        "scope": "all",
        "query": "侧聊",
        "limit": 20
    }))
    .expect("valid discovery input");
    assert!(validate_thread_discover(&input).is_ok());
    assert!(
        validate_thread_discover(&ThreadDiscoverInput {
            scope: "workspace".to_owned(),
            query: None,
            cursor: None,
            limit: None,
            workspace_id: None,
        })
        .is_err()
    );

    let page = json!({
        "items": [{
            "threadId": "thr_side",
            "title": "临时旁支",
            "kind": "side_chat",
            "workspaceId": "ws_other",
            "status": "idle"
        }],
        "nextCursor": null
    });
    assert!(parse_thread_discovery(page.clone()).is_ok());
    assert!(
        parse_thread_discovery(json!({
            "items": [{
                "threadId": "thr_side",
                "title": "临时旁支",
                "kind": "side_chat",
                "workspaceId": "ws_other",
                "status": "idle",
                "revision": 1
            }],
            "nextCursor": null
        }))
        .is_err()
    );
}

/// Thread 创建必须显式携带协作模式与 nullable reasoning，Plan 不能由旧 native DTO 静默降级。
#[test]
fn thread_create_input_requires_collaboration_mode() {
    let valid = json!({
        "title": "Demo",
        "providerId": "provider_demo",
        "modelId": "model_demo",
        "reasoningLevel": null,
        "accessMode": "full_access",
        "collaborationMode": "plan"
    });
    let input: ThreadCreateInput =
        serde_json::from_value(valid.clone()).expect("valid thread create");
    assert!(validate_thread_create(&input).is_ok());

    let mut missing_mode = valid.clone();
    missing_mode
        .as_object_mut()
        .expect("thread create object")
        .remove("collaborationMode");
    assert!(serde_json::from_value::<ThreadCreateInput>(missing_mode).is_err());

    let mut missing_reasoning = valid;
    missing_reasoning
        .as_object_mut()
        .expect("thread create object")
        .remove("reasoningLevel");
    assert!(serde_json::from_value::<ThreadCreateInput>(missing_reasoning).is_err());
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
            "collaborationMode": "default",
            "titleSource": "manual"
        },
        "title": "Demo",
        "status": "active",
        "pinned": false,
        "latestTurnStatus": null,
        "latestTurnSeen": true,
        "activeGoalId": null,
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
            "reasoningLevel": null, "accessMode": "approval_required",
            "collaborationMode": "default", "titleSource": "auto"
        },
        "title": "Demo", "status": "active", "pinned": false,
        "latestTurnStatus": null, "latestTurnSeen": true, "activeGoalId": null, "revision": 1,
        "createdAt": "2026-08-25T00:00:00Z", "updatedAt": "2026-08-25T00:00:00Z"
    }]);
    assert!(parse_thread_page(json!({"items": items.clone(), "nextCursor": null})).is_ok());
    assert!(parse_thread_page(json!({"threads": items, "nextCursor": null})).is_err());
}

/// 已读投影必须由 Java 完整返回；缺失字段或无 Turn 却声称未读都属于损坏响应。
#[test]
fn thread_seen_projection_is_required_and_consistent() {
    let valid = json!({
        "threadId": "thr_demo", "workspaceId": "ws_demo", "preferences": null,
        "title": "Demo", "status": "active", "pinned": false,
        "latestTurnStatus": "completed", "latestTurnSeen": true,
        "activeGoalId": "goal_demo", "revision": 2,
        "createdAt": "2026-09-01T00:00:00Z", "updatedAt": "2026-09-01T00:01:00Z"
    });
    assert!(parse_thread(valid.clone()).is_ok());

    let mut missing = valid.clone();
    missing
        .as_object_mut()
        .expect("thread object")
        .remove("latestTurnSeen");
    assert!(parse_thread(missing).is_err());

    let mut missing_goal = valid.clone();
    missing_goal
        .as_object_mut()
        .expect("thread object")
        .remove("activeGoalId");
    assert!(parse_thread(missing_goal).is_err());

    let mut impossible = valid;
    impossible["latestTurnStatus"] = serde_json::Value::Null;
    impossible["latestTurnSeen"] = json!(false);
    assert!(parse_thread(impossible).is_err());
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
        "inputQueue": null,
        "contextUsage": null,
        "nextCursor": null
    });
    assert!(parse_thread_read(result).is_err());
}

/// 跨会话消息作为独立历史事实保留来源标题快照；缺失来源或混入其它字段必须关闭失败。
#[test]
fn thread_read_accepts_strict_thread_message_items() {
    let mut result = json!({
        "threadId": "thr_target",
        "revision": 3,
        "turns": [],
        "items": [{
            "itemId": "item_message_1",
            "createdAt": "2026-09-05T00:00:00Z",
            "turnId": "turn_target",
            "kind": "thread_message",
            "sourceThreadId": "thr_source",
            "sourceTitle": "临时侧聊",
            "content": "请检查这个旁支结果"
        }],
        "taskActivities": [],
        "goalActivities": [],
        "contextUsage": null,
        "inputQueue": null,
        "nextCursor": null
    });
    let parsed = parse_thread_read(result.clone()).expect("thread message item");
    assert_eq!(parsed.items[0]["kind"], "thread_message");
    assert_eq!(parsed.items[0]["sourceThreadId"], "thr_source");
    assert_eq!(parsed.items[0]["sourceTitle"], "临时侧聊");

    result["items"][0]["sourceThreadId"] = json!("thread_source");
    assert!(parse_thread_read(result.clone()).is_err());

    result["items"][0]["sourceThreadId"] = json!("thr_source");
    result["items"][0]["extra"] = json!("must be rejected");
    assert!(parse_thread_read(result).is_err());
}

/// 该 fixture 镜像当前 Java snapshot：Turn 的 changeSet/completedAt/errorCode 均显式存在，
/// Tool 只携带安全 presentation，Provider 请求画像归属 Usage 而不再伪装成 Turn 单一 runtime。
fn v1_thread_read_fixture() -> serde_json::Value {
    json!({
        "threadId": "thr_demo",
        "revision": 11,
        "turns": [{
            "turnId": "turn_demo",
            "status": "completed",
            "requestedAt": "2026-08-30T10:00:00Z",
            "updatedAt": "2026-08-30T10:00:02Z",
            "completedAt": "2026-08-30T10:00:02Z",
            "changeSet": {
                "state": "complete",
                "incompleteReasons": [],
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
                "turnId": "turn_demo", "kind": "user_input",
                "content": [
                    {"type":"workspace_reference","workspaceId":"ws_demo","relativePath":"src/main.rs","kind":"file"},
                    {"type":"skill_reference","skillId":"skill_demo"},
                    {"type":"text","text":"检查合同"}
                ],
                "attachments": []
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
                "itemId": "item_final_demo", "createdAt": "2026-08-30T10:00:02Z",
                "turnId": "turn_demo", "kind": "final_answer", "text": "合同检查完成。"
            }
        ],
        "inputQueue": null,
        "taskActivities": [],
        "goalActivities": [],
        "contextUsage": {
            "turnId": "turn_demo",
            "requestId": "request_demo",
            "requestOrdinal": 1,
            "modelRound": 1,
            "purpose": "assistant",
            "certainty": "known",
            "profile": {
                "providerId": "provider_demo",
                "modelId": "model_demo",
                "api": "openai_responses",
                "upstreamModel": "gpt-5",
                "requestedReasoning": null,
                "effectiveReasoning": null,
                "accessMode": "approval_required",
                "collaborationMode": "default",
                "configGeneration": "cfg_demo",
                "promptRevision": "prompt_demo",
                "toolCatalogRevision": "tools_demo",
                "contextWindowTokens": 128000,
                "maxOutputTokens": 4096
            },
            "inputTokens": 100,
            "outputTokens": 20,
            "totalTokens": 120,
            "measuredAt": "2026-08-30T10:00:00Z"
        },
        "nextCursor": null
    })
}

/// 冷启动恢复必须接受包含 `changeSet` 与精确 Usage 的真实快照；缺失必需 nullable 字段与
/// 绝对文件路径仍应关闭失败，旧 Turn runtime 快照不再作为恢复事实。
#[test]
fn thread_read_accepts_v1_snapshot_and_preserves_required_nulls() {
    let fixture = v1_thread_read_fixture();
    let parsed = parse_thread_read(fixture.clone()).expect("v1 history fixture");
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
        Some((Some(100), Some(20), Some(120)))
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

/// 新建 Thread 在首条消息前会返回完全空的 Timeline；该合法产品态不能因只测过有内容 fixture
/// 而被 Rust 严格解析器误判为 runtime unavailable。
#[test]
fn thread_read_accepts_a_new_empty_thread_snapshot() {
    let parsed = parse_thread_read(serde_json::json!({
        "threadId": "thr_empty",
        "revision": 1,
        "turns": [],
        "items": [],
        "taskActivities": [],
        "goalActivities": [],
        "contextUsage": null,
        "inputQueue": null,
        "nextCursor": null
    }))
    .expect("new empty thread snapshot");

    assert_eq!(parsed.thread_id, "thr_empty");
    assert!(parsed.turns.is_empty());
    assert!(parsed.items.is_empty());
}

/// Goal 终态必须是有界、唯一且按事件序号升序的不可逆事实，运行中状态不得混入历史时间线。
#[test]
fn thread_read_validates_terminal_goal_activities() {
    let mut fixture = v1_thread_read_fixture();
    fixture["goalActivities"] = json!([{
        "goalId": "goal_done",
        "objective": "完成生产验收",
        "status": "achieved",
        "goalRevision": 8,
        "eventSequence": 21,
        "occurredAt": "2026-09-05T00:00:00Z"
    }]);
    assert!(parse_thread_read(fixture.clone()).is_ok());

    fixture["goalActivities"][0]["status"] = json!("active");
    assert!(parse_thread_read(fixture).is_err());
}

/// 冷启动快照接受 Java 持久化的 suspended，且非终态必须保留显式 null completedAt/errorCode。
#[test]
fn thread_read_accepts_suspended_turn_snapshot() {
    let mut fixture = v1_thread_read_fixture();
    fixture["turns"][0]["status"] = json!("suspended");
    fixture["turns"][0]["completedAt"] = Value::Null;
    fixture["turns"][0]["changeSet"] = Value::Null;
    fixture["turns"][0]["errorCode"] = Value::Null;
    fixture["items"] = json!([]);
    fixture["inputQueue"] = json!({
        "turnId": "turn_demo",
        "revision": 0,
        "accepting": true,
        "items": []
    });
    fixture["contextUsage"] = Value::Null;

    let parsed = parse_thread_read(fixture).expect("suspended history fixture");
    assert_eq!(parsed.turns[0].status, "suspended");
    assert!(parsed.turns[0].completed_at.is_none());
}

/// Context Usage 必须同时归属快照内的 Turn 和请求 identity，且不能伪造较低的 total 占用。
#[test]
fn thread_read_rejects_orphaned_or_inconsistent_context_usage() {
    let mut orphaned = v1_thread_read_fixture();
    orphaned["contextUsage"]["turnId"] = json!("turn_other");
    assert!(parse_thread_read(orphaned).is_err());

    let mut missing_turn = v1_thread_read_fixture();
    missing_turn["contextUsage"]
        .as_object_mut()
        .expect("context usage object")
        .remove("turnId");
    assert!(parse_thread_read(missing_turn).is_err());

    let mut invalid_request = v1_thread_read_fixture();
    invalid_request["contextUsage"]["requestId"] = json!("turn_other");
    assert!(parse_thread_read(invalid_request).is_err());

    let mut inconsistent = v1_thread_read_fixture();
    inconsistent["contextUsage"]["totalTokens"] = json!(119);
    assert!(parse_thread_read(inconsistent).is_err());
}

/// UNKNOWN Usage 必须完整保留为空计量；混入任意 Token 数会破坏崩溃窗口的事实语义并被拒绝。
#[test]
fn thread_read_accepts_strict_unknown_context_usage() {
    let mut fixture = v1_thread_read_fixture();
    fixture["contextUsage"]["certainty"] = json!("unknown");
    fixture["contextUsage"]["inputTokens"] = Value::Null;
    fixture["contextUsage"]["outputTokens"] = Value::Null;
    fixture["contextUsage"]["totalTokens"] = Value::Null;

    let parsed = parse_thread_read(fixture.clone()).expect("unknown context usage");
    let usage = parsed.context_usage.expect("usage");
    assert_eq!(usage.certainty, "unknown");
    assert_eq!(
        (usage.input_tokens, usage.output_tokens, usage.total_tokens),
        (None, None, None)
    );

    fixture["contextUsage"]["inputTokens"] = json!(0);
    assert!(parse_thread_read(fixture).is_err());
}

/// Java 对可选 artifact 使用“缺失”而非 `null`；Rust 再投影给 WebView 时必须保留该语义，
/// 同时拒绝 JA-RPC 2.0 的 unavailable/reason 和可选文本统计兼容形状。
#[test]
fn thread_read_serialization_omits_absent_change_set_fields() {
    let mut fixture = v1_thread_read_fixture();
    fixture["turns"][0]["changeSet"] = json!({
        "state": "partial",
        "incompleteReasons": ["recovery_boundary"],
        "files": [],
        "stats": {
            "files": 0,
            "additions": 0,
            "deletions": 0,
            "binaryFiles": 0,
            "truncated": false
        }
    });

    let parsed = parse_thread_read(fixture).expect("partial change set fixture");
    let projected = serde_json::to_value(parsed).expect("WebView history projection");
    let change_set = projected["turns"][0]["changeSet"]
        .as_object()
        .expect("change set object");

    assert_eq!(
        change_set.get("incompleteReasons"),
        Some(&json!(["recovery_boundary"]))
    );
    assert!(!change_set.contains_key("artifactId"));

    let mut complete = v1_thread_read_fixture();
    let complete_change_set = complete["turns"][0]["changeSet"]
        .as_object_mut()
        .expect("complete change set");
    complete_change_set.remove("artifactId");
    let projected = serde_json::to_value(
        parse_thread_read(complete).expect("complete change set without artifact"),
    )
    .expect("WebView complete history projection");
    let change_set = projected["turns"][0]["changeSet"]
        .as_object()
        .expect("available change set object");
    assert!(!change_set.contains_key("artifactId"));

    let mut legacy = v1_thread_read_fixture();
    legacy["turns"][0]["changeSet"]["state"] = json!("available");
    assert!(parse_thread_read(legacy).is_err());

    let mut missing_reasons = v1_thread_read_fixture();
    missing_reasons["turns"][0]["changeSet"]
        .as_object_mut()
        .expect("change set")
        .remove("incompleteReasons");
    assert!(parse_thread_read(missing_reasons).is_err());

    let mut renamed = v1_thread_read_fixture();
    renamed["turns"][0]["changeSet"]["files"][0]["status"] = json!("renamed");
    assert!(parse_thread_read(renamed).is_err());
}

/// Tool 状态必须来自当前闭集；历史中的旧 `unknown` 不能被投影成一个看似真实的步骤，
/// 应关闭失败并由调用方重新读取权威快照。
#[test]
fn thread_read_rejects_unknown_tool_presentation_status() {
    let mut fixture = v1_thread_read_fixture();
    fixture["items"][3]["presentation"]["status"] = json!("unknown");
    assert!(parse_thread_read(fixture).is_err());
}

/// Snapshot item 页精确接受 200 条并拒绝第 201 条；字节预算继续受 4 MiB frame 边界约束，
/// 不因首版 Tool/change-set 扩展而形成无界恢复路径。
#[test]
fn thread_read_enforces_v1_item_page_limit() {
    let mut fixture = v1_thread_read_fixture();
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
