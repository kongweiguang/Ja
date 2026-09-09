// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 类型化附件预览契约测试；固定 wire shape、授权判别和分页边界。

use super::*;

/// DRAFT 与 THREAD 使用同一 open 方法但保持互斥授权字段，不能同时携带 Workspace/Thread。
#[test]
fn open_params_serialize_tagged_authorization_without_resource_leakage() {
    let draft = AttachmentPreviewOpenParams::draft("att_alpha", "ws_current").unwrap();
    let thread = AttachmentPreviewOpenParams::thread("att_beta", "thr_history").unwrap();

    assert_eq!(
        serde_json::to_value(draft).unwrap(),
        json!({
            "attachmentId": "att_alpha",
            "authorization": {"kind": "draft", "workspaceId": "ws_current"}
        })
    );
    assert_eq!(
        serde_json::to_value(thread).unwrap(),
        json!({
            "attachmentId": "att_beta",
            "authorization": {"kind": "thread", "threadId": "thr_history"}
        })
    );
    assert!(AttachmentPreviewOpenParams::draft("att_alpha", "thr_wrong").is_err());
    assert!(AttachmentPreviewOpenParams::thread("att_beta", "ws_wrong").is_err());
}

/// 分段读取只接受 4..=64 KiB 和受限 offset，避免 caller 绕过 App Server 资源预算。
#[test]
fn read_params_enforce_segment_budget() {
    assert!(AttachmentPreviewReadParams::new("apv_session", 0, 4).is_ok());
    assert!(AttachmentPreviewReadParams::new("apv_session", 0, 65_536).is_ok());
    assert!(AttachmentPreviewReadParams::new("apv_session", 0, 3).is_err());
    assert!(AttachmentPreviewReadParams::new("apv_session", 0, 65_537).is_err());
    assert!(
        AttachmentPreviewReadParams::new("apv_session", 104_857_601, 4).is_err()
    );
}

/// open 结果拒绝内部资源字段与媒体/previewKind 漂移，防止 UI 获得路径或错误渲染能力。
#[test]
fn open_result_accepts_only_safe_exact_projection() {
    let value = json!({
        "previewSessionId": "apv_0123456789abcdef",
        "attachmentId": "att_image",
        "displayName": "capture.png",
        "sizeBytes": 512,
        "mediaKind": "image",
        "mediaType": "image/png",
        "previewKind": "image"
    });
    let parsed = AttachmentPreviewOpenResult::try_from(&value).unwrap();
    assert_eq!(parsed.preview_kind, "image");

    let mut leaked = value.clone();
    leaked["path"] = Value::String("C:/secret.png".to_owned());
    assert!(AttachmentPreviewOpenResult::try_from(&leaked).is_err());

    let mut mismatched = value;
    mismatched["previewKind"] = Value::String("text".to_owned());
    assert!(AttachmentPreviewOpenResult::try_from(&mismatched).is_err());
}

/// read 结果要求 Base64 解码长度等于 offset 跨度，并拒绝非 EOF 的空分页。
#[test]
fn read_result_enforces_offset_eof_and_truncation_contract() {
    let page = json!({
        "previewSessionId": "apv_0123456789abcdef",
        "offsetBytes": 0,
        "nextOffsetBytes": 4,
        "contentBase64": "dGVzdA==",
        "eof": false,
        "truncated": false
    });
    assert!(AttachmentPreviewReadResult::try_from(&page).is_ok());

    let mut wrong_span = page.clone();
    wrong_span["nextOffsetBytes"] = json!(3);
    assert!(AttachmentPreviewReadResult::try_from(&wrong_span).is_err());

    let mut stuck = page.clone();
    stuck["nextOffsetBytes"] = json!(0);
    stuck["contentBase64"] = Value::String(String::new());
    assert!(AttachmentPreviewReadResult::try_from(&stuck).is_err());

    let terminal = json!({
        "previewSessionId": "apv_0123456789abcdef",
        "offsetBytes": 1_048_572,
        "nextOffsetBytes": 1_048_576,
        "contentBase64": "dGVzdA==",
        "eof": true,
        "truncated": true
    });
    assert!(AttachmentPreviewReadResult::try_from(&terminal).is_ok());
}

/// close 只有 App Server 明确确认 closed:true 才能释放 host 侧资源映射。
#[test]
fn close_result_requires_positive_idempotent_confirmation() {
    let closed = json!({"previewSessionId": "apv_session", "closed": true});
    assert!(AttachmentPreviewCloseResult::try_from(&closed).is_ok());

    let open = json!({"previewSessionId": "apv_session", "closed": false});
    assert!(AttachmentPreviewCloseResult::try_from(&open).is_err());
}
