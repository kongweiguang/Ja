// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// cancel request 消耗 bridge queue slot 前拒绝 wrong-domain ID、control text 与 unknown field。
#[test]
fn cancel_input_is_strict_and_bounded() {
    let valid = TurnCancelInput {
        turn_id: "turn_fixture".to_owned(),
        expected_thread_revision: 7,
    };
    assert!(valid.validate().is_ok());
    assert!(
        TurnCancelInput {
            turn_id: "turn_._fixture".to_owned(),
            expected_thread_revision: 7,
        }
        .validate()
        .is_err()
    );
    assert!(
        TurnCancelInput {
            turn_id: "turn_fixture".to_owned(),
            expected_thread_revision: 9_007_199_254_740_992,
        }
        .validate()
        .is_err()
    );
}

/// queued input 只接受绝对 Turn identity 与一个有界 text value；legacy queue control 与 NUL
/// payload 不得进入 bridge actor。
#[test]
fn queued_input_is_strict_and_bounded() {
    let valid = TurnQueuedInput {
        turn_id: "turn_fixture".to_owned(),
        text: "guide the next model call".to_owned(),
    };
    assert!(valid.validate().is_ok());
    assert!(
        TurnQueuedInput {
            turn_id: "turn_fixture".to_owned(),
            text: "".to_owned()
        }
        .validate()
        .is_err()
    );
    assert!(
        TurnQueuedInput {
            turn_id: "turn_fixture".to_owned(),
            text: "bad\0text".to_owned()
        }
        .validate()
        .is_err()
    );
}

/// Approval validation 只暴露当前两个 decision；任意 sidecar request 入队前拒绝已删除的
/// session/one-shot 兼容别名。
#[test]
fn approval_decisions_are_exact() {
    for decision in ["approve", "deny"] {
        assert!(
            ApprovalResponseInput {
                approval_id: "appr_demo".to_owned(),
                turn_id: "turn_demo".to_owned(),
                decision: decision.to_owned(),
                expected_thread_revision: 1,
            }
            .validate()
            .is_ok()
        );
    }
    for decision in ["allow_once", "allow_session"] {
        assert!(
            ApprovalResponseInput {
                approval_id: "appr_demo".to_owned(),
                turn_id: "turn_demo".to_owned(),
                decision: decision.to_owned(),
                expected_thread_revision: 1,
            }
            .validate()
            .is_err()
        );
    }
}

/// Turn content 接受 text/attachment union，同时拒绝重复 identity、超过十个附件与旧的自由 kind 形状。
#[test]
fn turn_content_is_strict_bounded_and_path_free() {
    let valid = TurnStartInput {
        thread_id: "thr_fixture".to_owned(),
        content: vec![
            TurnContentPart::Text {
                text: "hello".to_owned(),
            },
            TurnContentPart::Attachment {
                attachment_id: "att_fixture".to_owned(),
            },
        ],
        deadline_ms: Some(86_400_000),
    };
    assert!(valid.validate().is_ok());
    assert!(
        TurnStartInput {
            thread_id: "thr_fixture".to_owned(),
            content: vec![
                TurnContentPart::Attachment {
                    attachment_id: "att_duplicate".to_owned()
                },
                TurnContentPart::Attachment {
                    attachment_id: "att_duplicate".to_owned()
                },
            ],
            deadline_ms: None,
        }
        .validate()
        .is_err()
    );
    assert!(
        TurnStartInput {
            thread_id: "thr_fixture".to_owned(),
            content: (0..11)
                .map(|index| TurnContentPart::Attachment {
                    attachment_id: format!("att_{index}"),
                })
                .collect(),
            deadline_ms: None,
        }
        .validate()
        .is_err()
    );
}

/// Import/discard 输入只承认 Rust token/hash 和 Java identity，不接受路径或超预算元数据。
#[test]
fn attachment_commands_enforce_opaque_identities_and_product_limits() {
    assert!(
        AttachmentImportInput {
            ingress_token: "a".repeat(32),
            display_name: "设计说明.txt".to_owned(),
            size_bytes: 3,
            sha256: "b".repeat(64),
        }
        .validate()
        .is_ok()
    );
    assert!(
        AttachmentImportInput {
            ingress_token: "C:\\private\\file".to_owned(),
            display_name: "file".to_owned(),
            size_bytes: 3,
            sha256: "b".repeat(64),
        }
        .validate()
        .is_err()
    );
    assert!(
        AttachmentDiscardInput {
            attachment_id: "att_fixture".to_owned()
        }
        .validate()
        .is_ok()
    );
    assert!(
        AttachmentDiscardInput {
            attachment_id: "C:\\private".to_owned()
        }
        .validate()
        .is_err()
    );
}
