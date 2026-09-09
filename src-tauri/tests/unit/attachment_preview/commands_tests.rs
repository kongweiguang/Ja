// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::attachment_preview::{
    AttachmentPreviewError, AttachmentPreviewErrorCode, AttachmentPreviewHost,
};
use ja_runtime::app_server_process::{
    AttachmentPreviewCloseParams, AttachmentPreviewOpenResult as RuntimeOpenResult,
    AttachmentPreviewReadParams,
    AttachmentPreviewReadResult as RuntimeReadResult,
};
use std::collections::VecDeque;
use std::sync::Mutex;

struct FakeRuntime {
    open_result: Mutex<Option<RuntimeOpenedAttachmentPreview>>,
    reads: Mutex<VecDeque<RuntimeReadResult>>,
    closes: AtomicUsize,
}

impl FakeRuntime {
    /// 每个 fake 只服务一个 open workflow，避免测试形成比 production 更宽的 session registry。
    fn new(open_result: RuntimeOpenedAttachmentPreview, reads: Vec<RuntimeReadResult>) -> Self {
        Self {
            open_result: Mutex::new(Some(open_result)),
            reads: Mutex::new(reads.into()),
            closes: AtomicUsize::new(0),
        }
    }
}

impl AttachmentPreviewRuntimePort for FakeRuntime {
    /// open 只移交预置强类型结果，重复调用按不可用失败。
    fn open(
        &self,
        _attachment_id: String,
        _authorization: AttachmentPreviewAuthorizationInput,
    ) -> Result<RuntimeOpenedAttachmentPreview, AttachmentPreviewError> {
        self.open_result
            .lock()
            .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::StateUnavailable))?
            .take()
            .ok_or_else(|| {
                AttachmentPreviewError::new(AttachmentPreviewErrorCode::SourceUnavailable)
            })
    }

    /// read 按固定队列返回，测试因此可验证 offset/EOF 规则而不启动 sidecar。
    fn read(
        &self,
        _params: AttachmentPreviewReadParams,
    ) -> Result<RuntimeReadResult, AttachmentPreviewError> {
        self.reads
            .lock()
            .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::StateUnavailable))?
            .pop_front()
            .ok_or_else(|| {
                AttachmentPreviewError::new(AttachmentPreviewErrorCode::SourceUnavailable)
            })
    }

    /// close 只记录补偿/显式关闭次数，不保存 opaque params。
    fn close(&self, _params: AttachmentPreviewCloseParams) -> Result<(), AttachmentPreviewError> {
        self.closes.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
}

/// 构造 runtime 已验证形状；测试不依赖 serde 宽松反序列化。
fn runtime_open(kind: &str, size_bytes: u64) -> RuntimeOpenedAttachmentPreview {
    RuntimeOpenedAttachmentPreview {
        result: RuntimeOpenResult {
            preview_session_id: "apv_12345678".to_owned(),
            attachment_id: "att_12345678".to_owned(),
            display_name: if kind == "text" {
                "notes.txt"
            } else {
                "image.png"
            }
            .to_owned(),
            size_bytes,
            media_kind: kind.to_owned(),
            media_type: if kind == "text" {
                "text/plain"
            } else {
                "image/png"
            }
            .to_owned(),
            preview_kind: kind.to_owned(),
        },
        workspace_identity: "ws_12345678".to_owned(),
    }
}

/// 草稿 Preview 夹具固定 workspace 授权，避免测试误用已删除的 bound 双轨 scope。
fn draft_input() -> AttachmentPreviewOpenInput {
    AttachmentPreviewOpenInput {
        attachment_id: "att_12345678".to_owned(),
        authorization: AttachmentPreviewAuthorizationInput::Draft,
    }
}

/// Thread Preview 的 WebView DTO 必须接受 camelCase tagged union，同时拒绝旧 bound scope 和额外字段。
#[test]
fn thread_authorization_uses_strict_camel_case_wire_shape() {
    let parsed = serde_json::from_value::<AttachmentPreviewOpenInput>(serde_json::json!({
        "attachmentId": "att_12345678",
        "authorization": {"kind": "thread", "threadId": "thr_12345678"}
    }))
    .expect("thread preview input");
    assert!(matches!(
        parsed.authorization,
        AttachmentPreviewAuthorizationInput::Thread { ref thread_id }
            if thread_id == "thr_12345678"
    ));
    assert!(serde_json::from_value::<AttachmentPreviewOpenInput>(serde_json::json!({
        "attachmentId": "att_12345678",
        "authorization": {"kind": "bound", "threadId": "thr_12345678"}
    }))
    .is_err());
    assert!(serde_json::from_value::<AttachmentPreviewOpenInput>(serde_json::json!({
        "attachmentId": "att_12345678",
        "authorization": {"kind": "thread", "threadId": "thr_12345678", "workspaceId": "ws_old"}
    }))
    .is_err());
}

/// 文本 open/read 将 server session 替换为 Rust public identity，并只向 UI 返回 UTF-8。
#[test]
fn text_workflow_remaps_session_and_decodes_utf8() {
    let content = "你好";
    let fake = Arc::new(FakeRuntime::new(
        runtime_open("text", content.len() as u64),
        vec![RuntimeReadResult {
            preview_session_id: "apv_12345678".to_owned(),
            offset_bytes: 0,
            next_offset_bytes: content.len() as u64,
            content_base64: base64::engine::general_purpose::STANDARD.encode(content.as_bytes()),
            eof: true,
            truncated: false,
        }],
    ));
    let runtime = AttachmentPreviewRuntimeState::new(fake.clone());
    let host = AttachmentPreviewHost::new().expect("host");
    let opened = open_preview("main", &runtime, &host, draft_input()).expect("open text");
    assert!(opened.preview_session_id.starts_with("prv_"));
    assert_ne!(opened.preview_session_id, "apv_12345678");
    assert!(opened.resource_url.is_none());
    let chunk = read_text(
        "main",
        &runtime,
        &host,
        AttachmentPreviewReadInput {
            preview_session_id: opened.preview_session_id.clone(),
            offset_bytes: 0,
            max_bytes: 64 * 1024,
        },
    )
    .expect("read text");
    assert_eq!(chunk.preview_session_id, opened.preview_session_id);
    assert_eq!(chunk.content, content);
    assert!(chunk.end_of_file);
}

/// 图片损坏只令 preview open 失败，并立即补偿关闭 App Server session，不影响附件本体。
#[test]
fn invalid_image_open_compensates_server_session() {
    let fake = Arc::new(FakeRuntime::new(
        runtime_open("image", 4),
        vec![RuntimeReadResult {
            preview_session_id: "apv_12345678".to_owned(),
            offset_bytes: 0,
            next_offset_bytes: 4,
            content_base64: base64::engine::general_purpose::STANDARD.encode(b"bad!"),
            eof: true,
            truncated: false,
        }],
    ));
    let runtime = AttachmentPreviewRuntimeState::new(fake.clone());
    let host = AttachmentPreviewHost::new().expect("host");
    let error = open_preview("main", &runtime, &host, draft_input()).expect_err("invalid image");
    assert_eq!(error.code, AttachmentPreviewErrorCode::InvalidImage);
    assert_eq!(fake.closes.load(Ordering::SeqCst), 1);
    assert_eq!(host.cache_bytes().expect("cache bytes"), 0);
}

/// 显式 close 首次调用 server，重复 close 在本地幂等成功且不会重复占用 RPC lane。
#[test]
fn close_is_locally_idempotent_after_server_confirmation() {
    let fake = Arc::new(FakeRuntime::new(runtime_open("text", 1), Vec::new()));
    let runtime = AttachmentPreviewRuntimeState::new(fake.clone());
    let host = AttachmentPreviewHost::new().expect("host");
    let opened = open_preview("main", &runtime, &host, draft_input()).expect("open text");
    let input = AttachmentPreviewCloseInput {
        preview_session_id: opened.preview_session_id,
    };
    assert!(
        close_preview("main", &runtime, &host, input.clone())
            .expect("first close")
            .closed
    );
    assert!(
        close_preview("main", &runtime, &host, input)
            .expect("second close")
            .closed
    );
    assert_eq!(fake.closes.load(Ordering::SeqCst), 1);
}

/// Workspace 切换先关闭全部 server session，再统一清除本地 bearer URL 和缓存。
#[test]
fn workspace_switch_closes_server_sessions_before_cache_clear() {
    let fake = Arc::new(FakeRuntime::new(runtime_open("text", 1), Vec::new()));
    let runtime = AttachmentPreviewRuntimeState::new(fake.clone());
    let host = AttachmentPreviewHost::new().expect("host");
    open_preview("main", &runtime, &host, draft_input()).expect("open text");
    assert_eq!(
        close_for_workspace_switch(&runtime, &host).expect("switch cleanup"),
        1
    );
    assert_eq!(fake.closes.load(Ordering::SeqCst), 1);
    assert!(host.server_sessions().expect("server sessions").is_empty());
    assert_eq!(host.cache_bytes().expect("cache bytes"), 0);
}
