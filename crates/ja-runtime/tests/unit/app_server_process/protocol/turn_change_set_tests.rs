// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 冻结 Turn ChangeSet 单文件读取的严格合同测试。

use super::*;

/// 请求只包含四元身份；分页字段与不安全路径不得重新进入 JA-RPC。
#[test]
fn read_params_are_exact_and_reject_paging_or_unsafe_paths() {
    let params = TurnChangeSetReadParams::new(
        "thr_fixture",
        "turn_fixture",
        "artifact_fixture",
        "src/main.rs",
    )
    .expect("valid identity");
    assert_eq!(
        params.into_value().expect("serialized request"),
        json!({
            "threadId": "thr_fixture",
            "turnId": "turn_fixture",
            "artifactId": "artifact_fixture",
            "filePath": "src/main.rs"
        })
    );
    assert!(
        TurnChangeSetReadParams::new(
            "thr_fixture",
            "turn_fixture",
            "artifact_fixture",
            "../secret"
        )
        .is_err()
    );
    assert!(!validate_turn_change_set_request(
        "turn/change-set/read",
        &json!({
            "threadId": "thr_fixture",
            "turnId": "turn_fixture",
            "artifactId": "artifact_fixture",
            "filePath": "src/main.rs",
            "offsetBytes": 0
        })
    ));
}

/// 2 MiB 正文对应 2,796,204 个 Base64 字符；上下各一字节必须按 decoded bytes 收口。
#[test]
fn result_surface_enforces_two_mebibyte_base64_boundary() {
    let base = |byte_length: u64, content_base64: String| {
        json!({
            "artifactId": "artifact_fixture",
            "filePath": "src/main.rs",
            "byteLength": byte_length,
            "sha256": "0".repeat(64),
            "contentBase64": content_base64
        })
    };
    let exact = "A".repeat(2_796_204);
    assert_eq!(exact.len(), ((TURN_CHANGE_SET_MAX_BYTES as usize + 2) / 3) * 4);
    assert!(TurnChangeSetReadResult::try_from(&base(TURN_CHANGE_SET_MAX_BYTES, exact)).is_ok());
    assert!(
        TurnChangeSetReadResult::try_from(&base(
            TURN_CHANGE_SET_MAX_BYTES - 1,
            "A".repeat(2_796_204)
        ))
        .is_ok(),
        "protocol surface leaves decoded length correlation to Tauri"
    );
    assert!(
        TurnChangeSetReadResult::try_from(&base(
            TURN_CHANGE_SET_MAX_BYTES + 1,
            "A".repeat(2_796_204)
        ))
        .is_err()
    );
}

/// 协议表面先拒绝 URL-safe、空白、错误 alphabet 与非小写摘要；padding/tail bits 由 Tauri 解码复核。
#[test]
fn result_surface_rejects_non_standard_base64_and_digest_spelling() {
    let valid = json!({
        "artifactId": "artifact_fixture",
        "filePath": "src/main.rs",
        "byteLength": 1,
        "sha256": "0".repeat(64),
        "contentBase64": "YQ=="
    });
    assert!(TurnChangeSetReadResult::try_from(&valid).is_ok());
    for content in ["YQ__", "YQ==\n", "Y Q=", "YQ==="] {
        let mut invalid = valid.clone();
        invalid["contentBase64"] = json!(content);
        assert!(TurnChangeSetReadResult::try_from(&invalid).is_err());
    }
    let mut uppercase_digest = valid.clone();
    uppercase_digest["sha256"] = json!("A".repeat(64));
    assert!(TurnChangeSetReadResult::try_from(&uppercase_digest).is_err());
}
