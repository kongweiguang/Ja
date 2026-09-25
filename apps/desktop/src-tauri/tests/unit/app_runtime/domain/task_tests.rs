// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// task/create 只接受父 identity、显式 nullable Turn 和规范任务名，不携带首轮内容。
#[test]
fn task_create_validation_closes_control_fields_and_content_budget() {
    let valid = TaskCreateInput {
        parent_thread_id: "thr_parent".to_owned(),
        parent_turn_id: Some("turn_parent".to_owned()),
        expected_parent_revision: 4,
        task_name: "检查测试".to_owned(),
        preferences: None,
    };
    assert!(valid.validate().is_ok());
    assert!(
        TaskCreateInput {
            task_name: " 带空白 ".to_owned(),
            ..valid.clone()
        }
        .validate()
        .is_err()
    );
    assert!(
        TaskCreateInput {
            parent_turn_id: Some("thr_wrong".to_owned()),
            ..valid
        }
        .validate()
        .is_err()
    );
}

/// tree/delete 必须重复精确 task identity；普通 mutation 不能替代整树确认。
#[test]
fn task_tree_delete_requires_exact_repeated_identity() {
    let mutation = TaskMutationInput {
        task_thread_id: "thr_child".to_owned(),
        expected_task_revision: 8,
    };
    assert!(
        TaskTreeDeleteInput {
            mutation: mutation.clone(),
            confirm_task_thread_id: "thr_child".to_owned(),
        }
        .validate()
        .is_ok()
    );
    assert!(
        TaskTreeDeleteInput {
            mutation,
            confirm_task_thread_id: "thr_other".to_owned(),
        }
        .validate()
        .is_err()
    );
}

/// 侧聊关闭只携带有效 Thread identity，避免把 revision 或控制字段伪装成生命周期状态。
#[test]
fn task_close_validation_requires_thread_identity() {
    assert!(TaskCloseInput {
        task_thread_id: "thr_side".to_owned(),
    }
    .validate()
    .is_ok());
    assert!(TaskCloseInput {
        task_thread_id: "side".to_owned(),
    }
    .validate()
    .is_err());
}

/// QueueOnly 与 followup 均限制 Mailbox payload；只有 followup 额外携带 Task revision。
#[test]
fn task_message_and_followup_share_bounded_content_without_wake_flag() {
    let message = TaskMessageInput {
        sender_thread_id: "thr_parent".to_owned(),
        target_thread_id: "thr_child".to_owned(),
        content: vec![TurnContentPart::Text {
            text: "继续检查".to_owned(),
        }],
        idempotency_key: "message-1".to_owned(),
    };
    assert!(message.validate().is_ok());
    assert!(
        TaskFollowupInput {
            message: message.clone(),
            expected_task_revision: 9_007_199_254_740_991,
        }
        .validate()
        .is_ok()
    );
    assert!(
        TaskMessageInput {
            idempotency_key: "bad\nkey".to_owned(),
            ..message
        }
        .validate()
        .is_err()
    );
}
