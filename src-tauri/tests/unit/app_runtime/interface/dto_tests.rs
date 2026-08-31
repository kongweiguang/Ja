// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

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

/// Queued DTO 只接受 Turn identity 与 text，method/priority 等调度字段不能由 WebView 注入。
#[test]
fn queued_dto_rejects_method_control_fields() {
    assert!(
        serde_json::from_value::<TurnQueuedInputDto>(
            serde_json::json!({"turnId":"turn_fixture","text":"x","priority":1})
        )
        .is_err()
    );
}

/// Turn DTO 只接受 strict content union；旧 input、text/attachment 混字段和路径字段均失败关闭。
#[test]
fn turn_start_dto_accepts_content_union_and_rejects_legacy_or_mixed_parts() {
    let valid = serde_json::from_value::<TurnStartInputDto>(serde_json::json!({
        "threadId":"thr_fixture",
        "content":[
            {"type":"text","text":"hello"},
            {"type":"attachment","attachmentId":"att_fixture"}
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
