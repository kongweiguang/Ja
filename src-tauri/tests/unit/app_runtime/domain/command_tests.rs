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

/// Resume 与 Cancel 共用冻结 identity/CAS 边界，但仍是独立领域意图，不能携带本地恢复状态。
#[test]
fn resume_input_is_strict_and_bounded() {
    assert!(
        TurnResumeInput {
            turn_id: "turn_fixture".to_owned(),
            expected_thread_revision: 7,
        }
        .validate()
        .is_ok()
    );
    for input in [
        TurnResumeInput {
            turn_id: "turn_._fixture".to_owned(),
            expected_thread_revision: 7,
        },
        TurnResumeInput {
            turn_id: "turn_fixture".to_owned(),
            expected_thread_revision: 9_007_199_254_740_992,
        },
    ] {
        assert!(input.validate().is_err());
    }
}

/// enqueue 只接受冻结 Turn identity 与单条队列预算；kind、排序和 NUL 不得进入 bridge actor。
#[test]
fn turn_input_enqueue_is_strict_and_bounded() {
    let valid = TurnInputEnqueue {
        turn_id: "turn_fixture".to_owned(),
        content: vec![TurnContentPart::Text {
            text: "guide the next model call".to_owned(),
        }],
    };
    assert!(valid.validate().is_ok());
    assert!(
        TurnInputEnqueue {
            turn_id: "turn_fixture".to_owned(),
            content: vec![TurnContentPart::Text { text: "".to_owned() }]
        }
        .validate()
        .is_err()
    );
    assert!(
        TurnInputEnqueue {
            turn_id: "turn_fixture".to_owned(),
            content: vec![TurnContentPart::Text { text: "bad\0text".to_owned() }]
        }
        .validate()
        .is_err()
    );
}

/// prioritize/update/delete 共用严格 item CAS，update 还必须重新执行单条文本预算。
#[test]
fn turn_input_mutations_are_identity_and_revision_bounded() {
    assert!(
        TurnInputPrioritize {
            turn_id: "turn_fixture".to_owned(),
            input_id: "input_fixture".to_owned(),
            expected_input_revision: 1,
        }
        .validate()
        .is_ok()
    );
    assert!(
        TurnInputDelete {
            turn_id: "turn_fixture".to_owned(),
            input_id: "wrong_fixture".to_owned(),
            expected_input_revision: 1,
        }
        .validate()
        .is_err()
    );
    assert!(
        TurnInputUpdate {
            turn_id: "turn_fixture".to_owned(),
            input_id: "input_fixture".to_owned(),
            expected_input_revision: 9_007_199_254_740_992,
            content: vec![TurnContentPart::Text { text: "updated".to_owned() }],
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
            TurnContentPart::WorkspaceReference {
                workspace_id: "ws_fixture".to_owned(),
                relative_path: "src/main.rs".to_owned(),
                kind: "file".to_owned(),
            },
            TurnContentPart::SkillReference {
                skill_id: "skill_fixture".to_owned(),
            },
            TurnContentPart::Attachment {
                attachment_id: "att_fixture".to_owned(),
            },
            TurnContentPart::Text {
                text: "hello".to_owned(),
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

/// Frozen review 只允许 artifact 内的协议相对路径，防止单文件读取成为宿主路径探针。
#[test]
fn turn_change_set_read_is_bound_to_a_safe_file_path() {
    let valid = TurnChangeSetReadInput {
        thread_id: "thr_fixture".to_owned(),
        turn_id: "turn_fixture".to_owned(),
        artifact_id: "artifact_fixture".to_owned(),
        file_path: "src/main.rs".to_owned(),
    };
    assert!(valid.validate().is_ok());

    for invalid_path in ["", "/src/main.rs", "C:/src/main.rs", "../main.rs", "src\\main.rs"] {
        assert!(
            TurnChangeSetReadInput {
                file_path: invalid_path.to_owned(),
                ..valid.clone()
            }
            .validate()
            .is_err()
        );
    }
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
