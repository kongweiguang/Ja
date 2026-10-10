// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use serde_json::json;

/// 大回复只按 Unicode 页前进；拒绝错位、空进度和混入未知字段，避免界面重复或漏字。
#[test]
fn item_content_pages_keep_exact_identity_and_progress() {
    let input = ItemContentReadInput {
        thread_id: "thr_demo".to_owned(),
        item_id: "item_answer".to_owned(),
        offset_characters: 0,
        limit_characters: 2,
    };
    assert!(validate_item_content_read(&input).is_ok());
    let first = parse_item_content_read(
        json!({
            "itemId":"item_answer", "offsetCharacters":0, "nextOffsetCharacters":2,
            "totalCharacters":3, "truncated":true, "content":"甲😀"
        }),
        &input,
    )
    .expect("first Unicode page");
    assert_eq!(first.next_offset_characters, Some(2));
    assert!(
        parse_item_content_read(
            json!({
                "itemId":"item_answer", "offsetCharacters":0, "nextOffsetCharacters":0,
                "totalCharacters":3, "truncated":true, "content":""
            }),
            &input
        )
        .is_err()
    );
    assert!(
        parse_item_content_read(
            json!({
                "itemId":"item_other", "offsetCharacters":0, "nextOffsetCharacters":null,
                "totalCharacters":1, "truncated":false, "content":"甲"
            }),
            &input
        )
        .is_err()
    );
    assert!(
        parse_item_content_read(
            json!({
                "itemId":"item_answer", "offsetCharacters":0, "nextOffsetCharacters":null,
                "totalCharacters":1, "truncated":false, "content":"甲", "extra":true
            }),
            &input
        )
        .is_err()
    );
}

/// 订阅 ACK 和持久操作回执都必须精确回显身份；unknown 不会伪造可重试成功。
#[test]
fn observation_and_operation_read_results_are_strict() {
    let observation = ThreadObservationInput {
        thread_id: "thr_demo".to_owned(),
    };
    assert!(validate_thread_observation(&observation).is_ok());
    assert!(
        parse_thread_observation(json!({"accepted":true,"threadId":"thr_demo"}), "thr_demo")
            .is_ok()
    );
    assert!(
        parse_thread_observation(json!({"accepted":true,"threadId":"thr_other"}), "thr_demo")
            .is_err()
    );

    let query = OperationReadInput {
        operation_key: "op_0123456789abcdef0123456789abcdef".to_owned(),
        source_thread_id: None,
    };
    assert!(validate_operation_read(&query).is_ok());
    assert!(matches!(
        parse_operation_read(json!({"status":"unknown"}), None),
        Ok(OperationReadResult::Unknown)
    ));
    assert!(
        parse_operation_read(
            json!({
                "status":"committed", "method":"turn/start", "threadId":"thr_demo",
                "result":{"accepted":true,"queued":false,"turnId":"turn_demo","threadRevision":1}
            }),
            None
        )
        .is_ok()
    );
    assert!(parse_operation_read(json!({
        "status":"committed", "method":"turn/start", "threadId":"thr_demo",
        "result":{"accepted":true,"queued":false,"turnId":"turn_demo","threadRevision":1,"secret":"bad"}
    }), None).is_err());

    let fork_receipt = json!({
        "status":"committed", "method":"thread/fork", "threadId":"thr_source",
        "result":{
            "thread":{
                "threadId":"thr_forked", "workspaceId":"ws_demo", "workspaceKind":"project",
                "preferences":null, "title":"来源标题（分叉）", "status":"active", "pinned":false,
                "latestTurnStatus":null, "latestTurnSeen":true, "revision":0,
                "createdAt":"2026-10-06T08:00:00Z", "updatedAt":"2026-10-06T08:00:00Z"
            },
            "source":{"threadId":"thr_source", "itemId":"item_answer", "threadRevision":7}
        }
    });
    assert!(parse_operation_read(fork_receipt.clone(), Some("thr_source")).is_ok());
    assert!(parse_operation_read(fork_receipt.clone(), Some("thr_other")).is_err());
    assert!(parse_operation_read(fork_receipt, None).is_err());
}

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

/// 工作区类型筛选采用封闭的可选集合；省略筛选时保留完整的发现行为。
#[test]
fn workspace_list_input_accepts_only_known_kind_filters() {
    let input: WorkspaceListInput =
        serde_json::from_value(json!({"kind":"session","limit":50})).expect("kind filter");
    assert!(validate_workspace_list(&input).is_ok());
    assert_eq!(
        serde_json::to_value(input).expect("serialize filter"),
        json!({"kind":"session","limit":50})
    );
    let all: WorkspaceListInput = serde_json::from_value(json!({"cursor":null,"limit":50}))
        .expect("unfiltered workspace list");
    assert!(validate_workspace_list(&all).is_ok());
    assert!(serde_json::from_value::<WorkspaceListInput>(json!({"kind":"future"})).is_err());
}

/// Thread page 进入 bridge 前必须携带 Java-issued Workspace identity。
#[test]
fn thread_list_input_is_workspace_scoped() {
    let input = ThreadListInput {
        workspace_id: Some("ws_demo".to_owned()),
        workspace_kind: None,
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
    assert_eq!(
        serde_json::to_value(&input).expect("serialize session creation"),
        valid
    );

    let mut explicit_null_cwd = valid.clone();
    explicit_null_cwd["cwd"] = serde_json::Value::Null;
    assert!(serde_json::from_value::<ThreadCreateInput>(explicit_null_cwd).is_err());

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
        "workspaceKind": "project",
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

/// 锁定 Java 所有的 Workspace 投影与 `project/session` 闭集，拒绝旧关联字段和列表键。
#[test]
fn workspace_page_uses_root_projection() {
    let page = json!({
        "items": [{
            "workspaceId": "ws_demo",
            "root": "C:\\demo",
            "displayName": "Demo",
            "trust": "trusted",
            "revision": 1,
            "kind": "project"
        }],
        "nextCursor": null
    });
    assert!(parse_workspace_page(page.clone()).is_ok());

    let mut project_with_legacy = page.clone();
    project_with_legacy["items"][0]["legacySharedWorkspaceId"] = json!("ws_legacy");
    assert!(parse_workspace_page(project_with_legacy).is_err());

    let mut legacy_with_legacy = page.clone();
    legacy_with_legacy["items"][0]["kind"] = json!("legacy_shared");
    legacy_with_legacy["items"][0]["legacySharedWorkspaceId"] = json!("ws_legacy");
    assert!(parse_workspace_page(legacy_with_legacy).is_err());

    let mut session_with_legacy = page.clone();
    session_with_legacy["items"][0]["kind"] = json!("session");
    session_with_legacy["items"][0]["legacySharedWorkspaceId"] = json!("ws_legacy");
    assert!(parse_workspace_page(session_with_legacy).is_err());

    assert!(
        parse_workspace_page(json!({"workspaces": page["items"], "nextCursor": null})).is_err()
    );
}

/// Thread list 也只接受统一 items 键，防止 Tauri 继续传播旧领域专用字段。
#[test]
fn thread_page_rejects_old_list_key() {
    let items = json!([{
        "threadId": "thr_demo", "workspaceId": "ws_demo",
        "workspaceKind": "project",
        "preferences": {
            "providerId": "provider_demo", "modelId": "model_demo",
            "reasoningLevel": null, "accessMode": "approval_required",
            "collaborationMode": "default", "titleSource": "auto"
        },
        "title": "Demo", "status": "active", "pinned": false,
        "latestTurnStatus": null, "latestTurnSeen": true, "revision": 1,
        "createdAt": "2026-08-25T00:00:00Z", "updatedAt": "2026-08-25T00:00:00Z"
    }]);
    assert!(parse_thread_page(json!({"items": items.clone(), "nextCursor": null})).is_ok());
    assert!(parse_thread_page(json!({"threads": items, "nextCursor": null})).is_err());
}

/// Java 持久化的澄清等待态在重启后仍可见，不能使整个会话列表或发现页失败。
#[test]
fn waiting_input_thread_remains_visible_after_restart() {
    let mut thread = json!({
        "threadId": "thr_waiting", "workspaceId": "ws_demo",
        "workspaceKind": "project",
        "preferences": null, "title": "Clarification", "status": "active",
        "pinned": false, "latestTurnStatus": "waiting_input",
        "latestTurnSeen": false, "revision": 2,
        "createdAt": "2026-09-03T00:00:00Z", "updatedAt": "2026-09-03T00:00:01Z"
    });
    assert!(parse_thread_page(json!({"items": [thread.clone()], "nextCursor": null})).is_ok());
    thread["latestTurnStatus"] = json!("unknown_wait");
    assert!(parse_thread_page(json!({"items": [thread], "nextCursor": null})).is_err());

    assert!(
        parse_thread_discovery(json!({
            "items": [{
                "threadId": "thr_waiting", "workspaceId": "ws_demo",
                "title": "Clarification", "kind": "main", "status": "waiting_input"
            }],
            "nextCursor": null
        }))
        .is_ok()
    );
}

/// 项目页面按 workspace ID 筛选；无项目的侧栏使用 Java 的 session 类型筛选。
#[test]
fn thread_list_filters_are_project_or_session_only() {
    let project: ThreadListInput =
        serde_json::from_value(json!({"workspaceId":"ws_project","limit":50}))
            .expect("project list filter");
    assert!(validate_thread_list(&project).is_ok());
    let session: ThreadListInput =
        serde_json::from_value(json!({"workspaceKind":"session","cursor":null,"limit":50}))
            .expect("session list filter");
    assert!(validate_thread_list(&session).is_ok());
    for invalid in [
        json!({"workspaceId":"ws_project","workspaceKind":"session"}),
        json!({"workspaceKind":"legacy_shared"}),
        json!({}),
    ] {
        let parsed: ThreadListInput = serde_json::from_value(invalid).expect("shape fields");
        assert!(validate_thread_list(&parsed).is_err());
    }
}

/// 搜索的 session-kind 分支与项目 Workspace ID 互斥，避免 renderer 传入路径身份。
#[test]
fn thread_search_filters_are_project_or_session_only() {
    for value in [
        json!({"workspaceId":"ws_project","query":"hello"}),
        json!({"workspaceKind":"session","query":"hello"}),
    ] {
        let input: ThreadSearchInput = serde_json::from_value(value).expect("search filter");
        assert!(validate_thread_search(&input).is_ok());
    }
    for value in [
        json!({"workspaceId":"ws_project","workspaceKind":"session","query":"x"}),
        json!({"workspaceKind":"legacy_shared","query":"x"}),
        json!({"query":"x"}),
    ] {
        let input: ThreadSearchInput = serde_json::from_value(value).expect("search shape");
        assert!(validate_thread_search(&input).is_err());
    }
}

/// 已读投影必须由 Java 完整返回；缺失字段或无 Turn 却声称未读都属于损坏响应。
#[test]
fn thread_seen_projection_is_required_and_consistent() {
    let valid = json!({
        "threadId": "thr_demo", "workspaceId": "ws_demo", "workspaceKind": "project",
        "preferences": null,
        "title": "Demo", "status": "active", "pinned": false,
        "latestTurnStatus": "completed", "latestTurnSeen": true,
        "revision": 2,
        "createdAt": "2026-09-01T00:00:00Z", "updatedAt": "2026-09-01T00:01:00Z"
    });
    assert!(parse_thread(valid.clone()).is_ok());

    let mut missing = valid.clone();
    missing
        .as_object_mut()
        .expect("thread object")
        .remove("latestTurnSeen");
    assert!(parse_thread(missing).is_err());

    let mut project_with_legacy_association = valid.clone();
    project_with_legacy_association["legacySharedWorkspaceId"] = json!("ws_legacy");
    assert!(parse_thread(project_with_legacy_association).is_err());

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
        "liveStream": null,
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
        "origin": "native",
        "kind": "thread_message",
            "sourceThreadId": "thr_source",
            "sourceTitle": "临时侧聊",
            "content": "请检查这个旁支结果",
            "totalCharacters": 9,
            "truncated": false
        }],
        "taskActivities": [],
        "contextUsage": null,
        "inputQueue": null,
        "liveStream": null,
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

/// 该 fixture 镜像当前 JA-RPC snapshot：必需 nullable 字段和正文计量完整，Tool 只携带安全 presentation。
fn thread_read_snapshot_fixture() -> serde_json::Value {
    json!({
        "threadId": "thr_demo",
        "revision": 11,
        "turns": [{
            "turnId": "turn_demo",
            "origin": "native",
            "sourceMessageId": null,
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
                "turnId": "turn_demo", "origin": "native", "kind": "user_input",
                "content": [
                    {"type":"workspace_reference","workspaceId":"ws_demo","relativePath":"src/main.rs","kind":"file"},
                    {"type":"skill_reference","skillId":"user:demo"},
                    {"type":"text","text":"检查合同"}
                ],
                "attachments": [], "totalCharacters": 4, "truncated": false
            },
            {
                "itemId": "item_progress_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "origin": "native", "kind": "assistant_progress",
                "text": "正在检查合同。", "modelRound": 1,
                "totalCharacters": 7, "truncated": false
            },
            {
                "itemId": "item_reasoning_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "origin": "native", "kind": "reasoning_summary",
                "text": "需要先验证当前协议。", "modelRound": 1,
                "totalCharacters": 10, "truncated": false
            },
            {
                "itemId": "item_call_demo", "createdAt": "2026-08-30T10:00:00Z",
                "turnId": "turn_demo", "origin": "native", "kind": "tool_call", "callId": "toolu_01HABC123",
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
                "turnId": "turn_demo", "origin": "native", "kind": "approval", "approvalId": "appr_demo",
                "callId": "toolu_01HABC123", "toolName": "shell", "reason": "Tool requires approval",
                "expiresAt": "2026-08-30T10:05:00Z", "decision": null
            },
            {
                "itemId": "item_final_demo", "createdAt": "2026-08-30T10:00:02Z",
                "turnId": "turn_demo", "origin": "native", "kind": "final_answer", "text": "合同检查完成。",
                "totalCharacters": 7, "truncated": false
            }
        ],
        "inputQueue": null,
        "taskActivities": [],
        "contextUsage": {
            "turnId": "turn_demo",
            "modelRequestId": "modelreq_demo",
            "modelRequestOrdinal": 1,
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
            "measuredAt": "2026-08-30T10:00:00Z",
            "contextEstimate": null
        },
        "liveStream": null,
        "nextCursor": null
    })
}

/// 冷启动恢复必须接受包含 `changeSet` 与精确 Usage 的当前快照；缺失必需 nullable 字段与
/// 绝对文件路径仍应关闭失败，旧 Turn runtime 快照不再作为恢复事实。
#[test]
fn thread_read_accepts_snapshot_and_preserves_required_nulls() {
    let fixture = thread_read_snapshot_fixture();
    let parsed = parse_thread_read(fixture.clone()).expect("current history fixture");
    assert_eq!(
        parsed.turns[0]
            .change_set
            .as_ref()
            .and_then(|change_set| change_set.artifact_id.as_deref()),
        Some("artifact_change_demo")
    );
    assert_eq!(parsed.turns[0].source_message_id, None);
    assert_eq!(
        serde_json::to_value(&parsed.turns[0]).expect("turn projection")["sourceMessageId"],
        Value::Null
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

    let mut missing_source_message_id = fixture.clone();
    missing_source_message_id["turns"][0]
        .as_object_mut()
        .expect("turn object")
        .remove("sourceMessageId");
    assert!(parse_thread_read(missing_source_message_id).is_err());

    let mut invalid_source_message_id = fixture.clone();
    invalid_source_message_id["turns"][0]["sourceMessageId"] = json!("turn_wrong_domain");
    assert!(parse_thread_read(invalid_source_message_id).is_err());

    let mut continuation = fixture.clone();
    continuation["turns"][0]["sourceMessageId"] = json!("item_user_demo");
    let projected = parse_thread_read(continuation).expect("hidden continuation source");
    assert_eq!(
        projected.turns[0].source_message_id.as_deref(),
        Some("item_user_demo")
    );

    let mut absolute_path = fixture;
    absolute_path["turns"][0]["changeSet"]["files"][0]["path"] = json!("C:/private/main.rs");
    assert!(parse_thread_read(absolute_path).is_err());
}

/// Snapshot 与 Timeline 使用全局 item 身份和 Thread/Turn/call 复合关联，保留 Provider 原生 callId。
#[test]
fn snapshot_tool_call_identity_is_scoped_to_turn() {
    let mut fixture = thread_read_snapshot_fixture();
    assert_eq!(fixture["items"][3]["callId"], "toolu_01HABC123");

    let mut next_turn = fixture["turns"][0].clone();
    next_turn["turnId"] = json!("turn_next");
    fixture["turns"]
        .as_array_mut()
        .expect("turns")
        .push(next_turn);
    let mut next_turn_call = fixture["items"][3].clone();
    next_turn_call["itemId"] = json!("item_call_next_turn");
    next_turn_call["turnId"] = json!("turn_next");
    fixture["items"]
        .as_array_mut()
        .expect("items")
        .push(next_turn_call.clone());
    assert!(parse_thread_read(fixture.clone()).is_ok());

    let mut duplicate_call = fixture.clone();
    let mut same_turn_call = next_turn_call.clone();
    same_turn_call["itemId"] = json!("item_call_duplicate");
    same_turn_call["turnId"] = json!("turn_demo");
    duplicate_call["items"]
        .as_array_mut()
        .expect("items")
        .push(same_turn_call);
    assert!(parse_thread_read(duplicate_call).is_err());

    let mut duplicate_item = fixture;
    next_turn_call["itemId"] = json!("item_call_demo");
    duplicate_item["items"]
        .as_array_mut()
        .expect("items")
        .push(next_turn_call);
    assert!(parse_thread_read(duplicate_item).is_err());
}

/// 活动流必须绑定活动 Turn，非空片段连续覆盖到 baseline；空片段仅用于保留已提交后的游标，
/// 同时用 Unicode 字节预算固定 native parser 与 Java/TypeScript 的恢复边界。
#[test]
fn thread_read_validates_live_stream_baseline_and_utf8_budget() {
    let mut fixture = thread_read_snapshot_fixture();
    fixture["turns"][0]["status"] = json!("running");
    fixture["turns"][0]["completedAt"] = Value::Null;
    fixture["turns"][0]["changeSet"] = Value::Null;
    fixture["liveStream"] = json!({
        "turnId": "turn_demo",
        "streamSeq": 2,
        "segments": [
            {
                "kind": "assistant",
                "segmentStartSeq": 1,
                "streamSeq": 1,
                "text": "draft",
                "occurredAt": "2026-08-30T10:00:00Z"
            },
            {
                "kind": "reasoningSummary",
                "segmentStartSeq": 2,
                "streamSeq": 2,
                "text": "thinking",
                "occurredAt": "2026-08-30T10:00:01Z"
            }
        ]
    });
    let parsed = parse_thread_read(fixture.clone()).expect("live stream baseline");
    assert_eq!(
        parsed.live_stream.as_ref().map(|stream| stream.stream_seq),
        Some(2)
    );

    let mut tail_gap = fixture.clone();
    tail_gap["liveStream"]["segments"][1]["streamSeq"] = json!(1);
    assert!(parse_thread_read(tail_gap).is_err());

    let mut empty_text = fixture.clone();
    empty_text["liveStream"]["segments"][0]["text"] = json!("");
    assert!(parse_thread_read(empty_text).is_err());

    let mut wrong_owner = fixture.clone();
    wrong_owner["liveStream"]["turnId"] = json!("turn_other");
    assert!(parse_thread_read(wrong_owner).is_err());

    let mut unicode_budget = fixture;
    unicode_budget["liveStream"]["segments"][0]["text"] = json!("界".repeat(21_846));
    assert!(parse_thread_read(unicode_budget).is_err());

    let mut invalid_timestamp = thread_read_snapshot_fixture();
    invalid_timestamp["turns"][0]["status"] = json!("running");
    invalid_timestamp["turns"][0]["completedAt"] = Value::Null;
    invalid_timestamp["turns"][0]["changeSet"] = Value::Null;
    invalid_timestamp["liveStream"] = json!({
        "turnId": "turn_demo",
        "streamSeq": 1,
        "segments": [{
            "kind": "assistant",
            "segmentStartSeq": 1,
            "streamSeq": 1,
            "text": "draft",
            "occurredAt": "2026-02-30T10:00:00Z"
        }]
    });
    assert!(parse_thread_read(invalid_timestamp).is_err());

    let mut missing_live_stream = thread_read_snapshot_fixture();
    missing_live_stream
        .as_object_mut()
        .expect("thread read object")
        .remove("liveStream");
    assert!(parse_thread_read(missing_live_stream).is_err());
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
        "contextUsage": null,
        "inputQueue": null,
        "liveStream": null,
        "nextCursor": null
    }))
    .expect("new empty thread snapshot");

    assert_eq!(parsed.thread_id, "thr_empty");
    assert!(parsed.turns.is_empty());
    assert!(parsed.items.is_empty());
}

/// 冷启动快照接受 Java 持久化的 suspended，且非终态必须保留显式 null completedAt/errorCode。
#[test]
fn thread_read_accepts_suspended_turn_snapshot() {
    let mut fixture = thread_read_snapshot_fixture();
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

/// 等待澄清仍是可管理的活动 Turn；队列和已提交流归属必须在冷启动快照中保持有效。
#[test]
fn thread_read_accepts_waiting_input_snapshot() {
    let mut fixture = thread_read_snapshot_fixture();
    fixture["turns"][0]["status"] = json!("waiting_input");
    fixture["turns"][0]["completedAt"] = Value::Null;
    fixture["turns"][0]["changeSet"] = Value::Null;
    fixture["items"] = json!([]);
    fixture["contextUsage"] = Value::Null;
    fixture["inputQueue"] = json!({
        "turnId": "turn_demo", "revision": 0, "accepting": true, "items": []
    });
    fixture["liveStream"] = json!({
        "turnId": "turn_demo", "streamSeq": 1,
        "segments": [{
            "kind": "assistant", "segmentStartSeq": 1, "streamSeq": 1,
            "text": "Clarifying", "occurredAt": "2026-08-30T10:00:01Z"
        }]
    });

    let parsed = parse_thread_read(fixture).expect("waiting input history fixture");
    assert_eq!(parsed.turns[0].status, "waiting_input");
    assert_eq!(
        parsed
            .input_queue
            .as_ref()
            .map(|queue| queue.turn_id.as_str()),
        Some("turn_demo")
    );
    assert_eq!(
        parsed
            .live_stream
            .as_ref()
            .map(|stream| stream.turn_id.as_str()),
        Some("turn_demo")
    );
}

/// Context Usage 必须同时归属快照内的 Turn 和请求 identity，且不能伪造较低的 total 占用。
#[test]
fn thread_read_rejects_orphaned_or_inconsistent_context_usage() {
    let mut orphaned = thread_read_snapshot_fixture();
    orphaned["contextUsage"]["turnId"] = json!("turn_other");
    assert!(parse_thread_read(orphaned).is_err());

    let mut missing_turn = thread_read_snapshot_fixture();
    missing_turn["contextUsage"]
        .as_object_mut()
        .expect("context usage object")
        .remove("turnId");
    assert!(parse_thread_read(missing_turn).is_err());

    let mut invalid_request = thread_read_snapshot_fixture();
    invalid_request["contextUsage"]["modelRequestId"] = json!("turn_other");
    assert!(parse_thread_read(invalid_request).is_err());

    let mut inconsistent = thread_read_snapshot_fixture();
    inconsistent["contextUsage"]["totalTokens"] = json!(119);
    assert!(parse_thread_read(inconsistent).is_err());
}

/// UNKNOWN Usage 必须完整保留为空计量；混入任意 Token 数会破坏崩溃窗口的事实语义并被拒绝。
#[test]
fn thread_read_accepts_strict_unknown_context_usage() {
    let mut fixture = thread_read_snapshot_fixture();
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

/// 本地派发估算仅在使用量标记为 UNKNOWN 且窗口身份保持冻结时，才能通过 Thread 历史解码保留下来。
#[test]
fn thread_read_restores_local_context_estimate_without_fabricating_usage() {
    let mut fixture = thread_read_snapshot_fixture();
    fixture["contextUsage"]["certainty"] = json!("unknown");
    fixture["contextUsage"]["inputTokens"] = Value::Null;
    fixture["contextUsage"]["outputTokens"] = Value::Null;
    fixture["contextUsage"]["totalTokens"] = Value::Null;
    fixture["contextUsage"]["contextEstimate"] = json!({
        "certainty": "estimated",
        "source": "local",
        "usedTokens": 18_500,
        "limitTokens": 128_000,
        "measuredAt": "2026-08-30T10:00:01Z"
    });

    let parsed = parse_thread_read(fixture.clone()).expect("local context estimate restores");
    let usage = parsed.context_usage.expect("context usage");
    assert_eq!(usage.certainty, "unknown");
    assert_eq!(
        (usage.input_tokens, usage.output_tokens, usage.total_tokens),
        (None, None, None)
    );
    assert_eq!(
        usage
            .context_estimate
            .as_ref()
            .map(|estimate| estimate.used_tokens),
        Some(18_500)
    );

    fixture["contextUsage"]["certainty"] = json!("known");
    fixture["contextUsage"]["inputTokens"] = json!(100);
    fixture["contextUsage"]["outputTokens"] = json!(20);
    fixture["contextUsage"]["totalTokens"] = json!(120);
    assert!(parse_thread_read(fixture.clone()).is_err());

    fixture["contextUsage"]["certainty"] = json!("unknown");
    fixture["contextUsage"]["inputTokens"] = Value::Null;
    fixture["contextUsage"]["outputTokens"] = Value::Null;
    fixture["contextUsage"]["totalTokens"] = Value::Null;
    fixture["contextUsage"]["contextEstimate"]["limitTokens"] = json!(1);
    assert!(parse_thread_read(fixture).is_err());
}

/// Tauri usage command 复用 ja-runtime 摘要边界，并将错误压到原有脱敏 runtime error。
#[test]
fn thread_usage_summary_uses_shared_strict_parser() {
    let summary = json!({
        "threadId": "thr_usage_demo",
        "snapshotRevision": 11,
        "requestCount": 2,
        "measuredRequestCount": 2,
        "newInputRequestCount": 2,
        "newInputTokens": 180,
        "outputRequestCount": 2,
        "outputTokens": 40,
        "totalRequestCount": 2,
        "totalTokens": 220,
        "cacheReadRequestCount": 1,
        "cacheReadTokens": 60,
        "cacheWriteRequestCount": 1,
        "cacheWriteTokens": 10,
        "cacheCompleteRequestCount": 1,
        "cacheCompleteInputTokens": 70,
        "cacheCompleteReadTokens": 60
    });
    let parsed = parse_thread_usage_summary(summary.clone()).expect("shared summary parser");
    assert_eq!(parsed.new_input_tokens, 180);

    let mut malformed = summary;
    malformed["cacheCompleteReadTokens"] = json!(71);
    assert!(parse_thread_usage_summary(malformed).is_err());
}

/// Java 对可选 artifact 使用“缺失”而非 `null`；Rust 再投影给 WebView 时必须保留该语义，
/// 同时拒绝 JA-RPC 2.0 的 unavailable/reason 和可选文本统计兼容形状。
#[test]
fn thread_read_serialization_omits_absent_change_set_fields() {
    let mut fixture = thread_read_snapshot_fixture();
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

    let mut complete = thread_read_snapshot_fixture();
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

    let mut legacy = thread_read_snapshot_fixture();
    legacy["turns"][0]["changeSet"]["state"] = json!("available");
    assert!(parse_thread_read(legacy).is_err());

    let mut missing_reasons = thread_read_snapshot_fixture();
    missing_reasons["turns"][0]["changeSet"]
        .as_object_mut()
        .expect("change set")
        .remove("incompleteReasons");
    assert!(parse_thread_read(missing_reasons).is_err());

    let mut renamed = thread_read_snapshot_fixture();
    renamed["turns"][0]["changeSet"]["files"][0]["status"] = json!("renamed");
    assert!(parse_thread_read(renamed).is_err());
}

/// Tool 状态必须来自当前闭集；历史中的旧 `unknown` 不能被投影成一个看似真实的步骤，
/// 应关闭失败并由调用方重新读取权威快照。
#[test]
fn thread_read_rejects_unknown_tool_presentation_status() {
    let mut fixture = thread_read_snapshot_fixture();
    fixture["items"][3]["presentation"]["status"] = json!("unknown");
    assert!(parse_thread_read(fixture).is_err());
}

/// Java 已脱敏的问答展示与待裁决恢复版本分别按原样通过历史回读。
#[test]
fn thread_read_accepts_structured_interaction_answers_and_recovery() {
    let mut fixture = thread_read_snapshot_fixture();
    fixture["items"][3]["toolName"] = json!("request_user_input");
    fixture["items"][3]["presentation"]["kind"] = json!("mcp");
    fixture["items"][3]["presentation"]["title"] = json!("询问偏好");
    fixture["items"][3]["presentation"]["summary"] = json!("已回答 2 个澄清问题");
    fixture["items"][3]["presentation"]["relativePaths"] = json!([]);
    let presentation = fixture["items"][3]["presentation"]
        .as_object_mut()
        .expect("tool presentation");
    for field in ["command", "stdout", "exitCode", "artifactId"] {
        presentation.remove(field);
    }
    fixture["items"][3]["presentation"]["interactionAnswers"] = json!([
        {"question": "如何同步？", "answers": ["保留本地改动", "合并远程提交"], "skipped": false},
        {"question": "是否推送？", "answers": [], "skipped": true}
    ]);
    let parsed = parse_thread_read(fixture.clone()).expect("safe interaction history");
    assert_eq!(
        parsed.items[3]["presentation"]["interactionAnswers"][0]["answers"][1],
        "合并远程提交"
    );

    fixture["items"][3]["presentation"]
        .as_object_mut()
        .expect("tool presentation")
        .remove("interactionAnswers");
    fixture["items"][3]["presentation"]["status"] = json!("error");
    fixture["items"][3]["presentation"]["recovery"] = json!({"revision": 1});
    let recovery = parse_thread_read(fixture).expect("safe recovery history");
    assert_eq!(recovery.items[3]["presentation"]["recovery"]["revision"], 1);
}

/// 历史快照不能把原始答案、越界文案或恢复命令夹带在安全 Tool 展示字段中。
#[test]
fn thread_read_rejects_invalid_interaction_answers_and_recovery() {
    let mut fixture = thread_read_snapshot_fixture();
    fixture["items"][3]["presentation"]["interactionAnswers"] = json!([
        {"question": "是否推送？", "answers": ["推送"], "skipped": true}
    ]);
    assert!(parse_thread_read(fixture.clone()).is_err());

    fixture["items"][3]["presentation"]["interactionAnswers"][0]["skipped"] = json!(false);
    fixture["items"][3]["presentation"]["interactionAnswers"][0]["rawAnswer"] = json!("private");
    assert!(parse_thread_read(fixture.clone()).is_err());

    fixture["items"][3]["presentation"]["interactionAnswers"][0]
        .as_object_mut()
        .expect("interaction answer")
        .remove("rawAnswer");
    fixture["items"][3]["presentation"]["interactionAnswers"][0]["answers"][0] = json!(" ");
    assert!(parse_thread_read(fixture.clone()).is_err());

    fixture["items"][3]["presentation"]["interactionAnswers"][0]["answers"][0] =
        json!("x".repeat(4_097));
    assert!(parse_thread_read(fixture.clone()).is_err());

    fixture["items"][3]["presentation"]["interactionAnswers"] = json!(null);
    assert!(parse_thread_read(fixture.clone()).is_err());

    fixture["items"][3]["presentation"]
        .as_object_mut()
        .expect("tool presentation")
        .remove("interactionAnswers");
    fixture["items"][3]["presentation"]["recovery"] = json!({"revision": 1, "command": "hidden"});
    assert!(parse_thread_read(fixture.clone()).is_err());

    fixture["items"][3]["presentation"]["recovery"] = json!({"revision": 0});
    assert!(parse_thread_read(fixture).is_err());
}

/// Snapshot item 页精确接受 200 条并拒绝第 201 条；字节预算继续受 4 MiB frame 边界约束，
/// 不因当前 Tool/change-set 投影而形成无界恢复路径。
#[test]
fn thread_read_enforces_item_page_limit() {
    let mut fixture = thread_read_snapshot_fixture();
    let items = (0..200)
        .map(|index| {
            json!({
                "itemId": format!("item_final_{index}"),
                "createdAt": "2026-08-30T10:00:02Z",
                "turnId": "turn_demo",
                "origin": "native",
                "kind": "final_answer",
                "text": "ok",
                "totalCharacters": 2,
                "truncated": false
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
            "origin": "native",
            "kind": "final_answer",
            "text": "overflow",
            "totalCharacters": 8,
            "truncated": false
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

/// MCP 概览读取只接受脱敏名称、来源和状态；未知工具数保持缺省。
#[test]
fn thread_mcp_status_keeps_scopes_and_unknown_counts() {
    let result = parse_thread_mcp_status(json!({
        "threadId": "thr_demo", "source": "last_observed", "notices": [],
        "catalogRevision": "catalog_42", "observedAt": "2026-09-23T12:30:00Z",
        "servers": [
            {"serverId": "mcp_kerminal", "name": "Kerminal", "scope": "global", "state": "available", "toolCount": 68},
            {"serverId": "mcp_project", "name": "Kerminal", "scope": "project", "state": "not_discovered"}
        ]
    }), "thr_demo").expect("redacted thread MCP status");
    let wire = serde_json::to_value(result).expect("MCP status wire projection");
    assert_eq!(wire["servers"][0]["toolCount"], json!(68));
    assert!(wire["servers"][1].get("toolCount").is_none());
    assert!(wire["servers"][0].get("endpoint").is_none());
}

/// 结果必须绑定请求 Thread，且不能夹带凭据、传输地址或旧 probe 来源。
#[test]
fn thread_mcp_status_rejects_private_and_stale_shapes() {
    let valid = json!({"threadId":"thr_demo", "source":"active", "notices":["configuration_changed"],
        "servers":[{"serverId":"mcp_local", "name":"Local", "scope":"project", "state":"available"}]});
    assert!(parse_thread_mcp_status(valid.clone(), "thr_demo").is_ok());
    assert!(parse_thread_mcp_status(valid.clone(), "thr_other").is_err());
    let mut private = valid.clone();
    private["servers"][0]["endpoint"] = json!("http://127.0.0.1");
    assert!(parse_thread_mcp_status(private, "thr_demo").is_err());
    let mut probe = valid;
    probe["source"] = json!("probe");
    assert!(parse_thread_mcp_status(probe, "thr_demo").is_err());
}
