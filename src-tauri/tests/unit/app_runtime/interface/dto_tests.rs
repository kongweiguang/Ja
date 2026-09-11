// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// IPC 序列化必须保留活动根身份，否则前端严格校验会使真实侧聊 Composer 永久不可用。
#[test]
fn task_activity_dto_preserves_root_identity() {
    let dto = TaskActivityDto::from(crate::app_runtime::domain::TaskActivity {
        activity_sequence: 1,
        activity_id: "activity_created".into(),
        root_thread_id: "thr_source".into(),
        task_thread_id: "thr_side".into(),
        actor_thread_id: "thr_source".into(),
        causal_turn_id: None,
        kind: "created".into(),
        summary: "已创建侧聊".into(),
        created_at: "2026-09-10T00:00:00Z".into(),
    });
    let value = serde_json::to_value(dto).expect("activity DTO must serialize");
    assert_eq!(value["rootThreadId"], "thr_source");
    assert_eq!(value["taskThreadId"], "thr_side");
}

/// Interface DTO 必须拒绝已删除的 cancel 字段，不能把兼容输入带入纯领域模型。
#[test]
fn cancel_dto_rejects_unknown_and_legacy_fields() {
    assert!(
        serde_json::from_value::<TurnCancelInputDto>(
            serde_json::json!({"turnId":"turn_fixture","unknown":true})
        )
        .is_err()
    );
    assert!(serde_json::from_value::<TurnCancelInputDto>(serde_json::json!({"turnId":"turn_fixture","expectedThreadRevision":7,"reason":"legacy"})).is_err());
}

/// Resume DTO 只接受 JA-RPC 规定的两个 CAS 字段，运行时指纹与 execution state 不能由 WebView 注入。
#[test]
fn resume_dto_is_exact_and_rejects_execution_state() {
    assert!(
        serde_json::from_value::<TurnResumeInputDto>(serde_json::json!({
            "turnId": "turn_fixture",
            "expectedThreadRevision": 7
        }))
        .is_ok()
    );
    assert!(
        serde_json::from_value::<TurnResumeInputDto>(serde_json::json!({
            "turnId": "turn_fixture",
            "expectedThreadRevision": 7,
            "executionCursor": 3
        }))
        .is_err()
    );
}

/// Frozen diff DTO 必须显式携带单文件 identity；缺失字段或旧分页字段都不能恢复整轮读取。
#[test]
fn turn_change_set_read_dto_requires_file_path() {
    let valid = serde_json::json!({
        "workspaceId": "ws_fixture",
        "threadId": "thr_fixture",
        "turnId": "turn_fixture",
        "artifactId": "artifact_fixture",
        "filePath": "src/main.rs"
    });
    let dto = serde_json::from_value::<TurnChangeSetReadInputDto>(valid.clone())
        .expect("file-scoped request");
    let (workspace_id, input) = dto.into_domain();
    assert_eq!(workspace_id, "ws_fixture");
    assert_eq!(input.file_path, "src/main.rs");

    let mut missing = valid.clone();
    missing
        .as_object_mut()
        .expect("request object")
        .remove("filePath");
    assert!(serde_json::from_value::<TurnChangeSetReadInputDto>(missing).is_err());

    let mut paged = valid;
    paged["offsetBytes"] = serde_json::json!(0);
    paged["limitBytes"] = serde_json::json!(65_536);
    assert!(serde_json::from_value::<TurnChangeSetReadInputDto>(paged).is_err());
}

/// 四个队列 DTO 使用独立闭集，enqueue 不能夹带 kind，mutation 不能省略 item CAS。
#[test]
fn turn_input_dtos_reject_cross_method_control_fields() {
    assert!(
        serde_json::from_value::<TurnInputEnqueueDto>(
            serde_json::json!({"turnId":"turn_fixture","content":[{"type":"text","text":"x"}],"kind":"steering"})
        )
        .is_err()
    );
    assert!(
        serde_json::from_value::<TurnInputPrioritizeDto>(serde_json::json!({
            "turnId":"turn_fixture",
            "inputId":"input_fixture"
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TurnInputUpdateDto>(serde_json::json!({
            "turnId":"turn_fixture",
            "inputId":"input_fixture",
            "expectedInputRevision":1,
            "content":[{"type":"text","text":"updated"}]
        }))
        .is_ok()
    );
}

/// Turn DTO 只接受 strict content union；旧 input、text/attachment 混字段和路径字段均失败关闭。
#[test]
fn turn_start_dto_accepts_content_union_and_rejects_legacy_or_mixed_parts() {
    let valid = serde_json::from_value::<TurnStartInputDto>(serde_json::json!({
        "threadId":"thr_fixture",
        "content":[
            {"type":"workspace_reference","workspaceId":"ws_fixture","relativePath":"src/main.rs","kind":"file"},
            {"type":"skill_reference","skillId":"skill_fixture"},
            {"type":"attachment","attachmentId":"att_fixture"},
            {"type":"text","text":"hello"}
        ]
    }));
    assert!(valid.is_ok());
    assert!(
        serde_json::from_value::<TurnStartInputDto>(serde_json::json!({
            "threadId":"thr_fixture",
            "input":[{"type":"text","text":"legacy"}]
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TurnStartInputDto>(serde_json::json!({
            "threadId":"thr_fixture",
            "content":[{"type":"attachment","attachmentId":"att_fixture","text":"mixed"}]
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TurnStartInputDto>(serde_json::json!({
            "threadId":"thr_fixture",
            "content":[{"type":"attachment","attachmentId":"att_fixture","path":"C:\\private"}]
        }))
        .is_err()
    );
}

/// Task DTO 要求 create 的 parentTurnId 显式 nullable，并拒绝 kind/lifecycle 等服务端字段注入。
#[test]
fn task_create_dto_is_exact_and_requires_nullable_parent_turn() {
    let valid = json!({
        "parentThreadId": "thr_parent",
        "parentTurnId": null,
        "expectedParentRevision": 2,
        "taskName": "检查测试"
    });
    assert!(serde_json::from_value::<TaskCreateInputDto>(valid.clone()).is_ok());
    let mut missing = valid.clone();
    missing
        .as_object_mut()
        .expect("object")
        .remove("parentTurnId");
    assert!(serde_json::from_value::<TaskCreateInputDto>(missing).is_err());
    let mut injected = valid;
    injected["taskKind"] = json!("subagent");
    assert!(serde_json::from_value::<TaskCreateInputDto>(injected).is_err());
}

/// Message/followup DTO 不能携带 wake、state 或 boundTurnId，避免 renderer 控制调度与消费状态。
#[test]
fn task_message_dtos_reject_server_owned_fields() {
    let message = json!({
        "senderThreadId": "thr_parent", "targetThreadId": "thr_child",
        "content": [{"type":"text","text":"状态如何"}], "idempotencyKey": "msg-1"
    });
    assert!(serde_json::from_value::<TaskMessageInputDto>(message.clone()).is_ok());
    let mut wake = message.clone();
    wake["wake"] = json!(true);
    assert!(serde_json::from_value::<TaskMessageInputDto>(wake).is_err());
    let mut followup = message;
    followup["expectedTaskRevision"] = json!(3);
    assert!(serde_json::from_value::<TaskFollowupInputDto>(followup).is_ok());
}

/// 其余 Task DTO 均保持方法专属字段闭集，不能注入 transcript、connection 或传播开关。
#[test]
fn task_control_dtos_reject_cross_method_fields() {
    assert!(
        serde_json::from_value::<TaskListInputDto>(json!({
            "rootThreadId": "thr_root"
        }))
        .is_ok()
    );
    assert!(
        serde_json::from_value::<TaskListInputDto>(json!({
            "rootThreadId": "thr_root", "includeTranscript": true
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TaskReadInputDto>(json!({
            "taskThreadId": "thr_child"
        }))
        .is_ok()
    );
    assert!(
        serde_json::from_value::<TaskReadInputDto>(json!({
            "taskThreadId": "thr_child", "cursor": null
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TaskReadInputDto>(json!({
            "taskThreadId": "thr_child", "observe": true
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TaskObserveInputDto>(json!({
            "taskThreadId": "thr_child", "expectedTaskRevision": 2
        }))
        .is_ok()
    );
    assert!(
        serde_json::from_value::<TaskObserveInputDto>(json!({
            "taskThreadId": "thr_child", "expectedTaskRevision": 2, "connectionId": "conn_1"
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TaskUnobserveInputDto>(json!({
            "observationId": "observe_child", "taskThreadId": "thr_child"
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TaskSeenInputDto>(json!({
            "taskThreadId": "thr_child", "expectedTaskRevision": 2
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TaskMutationInputDto>(json!({
            "taskThreadId": "thr_child", "expectedTaskRevision": 2, "recursive": false
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<TaskTreeDeleteInputDto>(json!({
            "taskThreadId": "thr_child", "expectedTaskRevision": 2
        }))
        .is_err()
    );
}

/// Path search DTO 只接受 Thread/Workspace/query/limit，禁止 caller 注入 generation 或扫描预算。
#[test]
fn workspace_path_search_dto_is_exact() {
    assert!(
        serde_json::from_value::<WorkspacePathSearchInputDto>(serde_json::json!({
            "threadId":"thr_fixture", "workspaceId":"ws_fixture", "query":"src", "limit":20
        }))
        .is_ok()
    );
    assert!(
        serde_json::from_value::<WorkspacePathSearchInputDto>(serde_json::json!({
            "threadId":"thr_fixture", "workspaceId":"ws_fixture", "query":"src", "generation":1
        }))
        .is_err()
    );
}
