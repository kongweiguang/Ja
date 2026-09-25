// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 三个附件预览 command 的固定 workflow；具体 RuntimeHost adapter 由 composition root 注入。

use super::bridge::{project_preview_descriptor, project_text_preview_chunk};
use super::error::{AttachmentPreviewError, AttachmentPreviewErrorCode};
use super::host::AttachmentPreviewHost;
use super::image_pipeline::derive_attachment_image;
use super::model::{
    ATTACHMENT_PREVIEW_READ_BYTES, ATTACHMENT_PREVIEW_TEXT_BYTES, AttachmentPreviewCloseResult,
    AttachmentPreviewKind, AttachmentPreviewOpenResult, AttachmentPreviewSessionId,
    AttachmentTextReadResult,
};
use base64::Engine;
use ja_runtime::app_server_process::{
    ATTACHMENT_PREVIEW_READ_MAX_BYTES, ATTACHMENT_PREVIEW_READ_MIN_BYTES,
    AttachmentPreviewCloseParams, AttachmentPreviewOpenResult as RuntimeOpenResult,
    AttachmentPreviewReadParams, AttachmentPreviewReadResult as RuntimeReadResult,
};
use serde::Deserialize;
use std::sync::Arc;
use tauri::Runtime;

/// command 名保持为常量，composition 注册与前端 wrapper 可做静态漂移检查。
pub const ATTACHMENT_PREVIEW_OPEN_COMMAND: &str = "ja_attachment_preview_open";
pub const ATTACHMENT_PREVIEW_READ_COMMAND: &str = "ja_attachment_preview_read";
pub const ATTACHMENT_PREVIEW_CLOSE_COMMAND: &str = "ja_attachment_preview_close";

/// Runtime adapter 必须走三个 fixed supervisor lane；该 trait 不提供 generic method/value passthrough。
pub trait AttachmentPreviewRuntimePort: Send + Sync {
    /// open 由原生 owner 为 DRAFT 注入当前 Workspace；renderer 只能选择 DRAFT/BOUND，不能签发 Workspace 授权。
    fn open(
        &self,
        attachment_id: String,
        authorization: AttachmentPreviewAuthorizationInput,
    ) -> Result<RuntimeOpenedAttachmentPreview, AttachmentPreviewError>;
    /// read 只接受 ja-runtime 已验证的 session/offset/limit DTO。
    fn read(
        &self,
        params: AttachmentPreviewReadParams,
    ) -> Result<RuntimeReadResult, AttachmentPreviewError>;
    /// close 只有在 App Server 确认幂等关闭后成功。
    fn close(&self, params: AttachmentPreviewCloseParams) -> Result<(), AttachmentPreviewError>;
}

impl AttachmentPreviewRuntimePort for crate::app_runtime::RuntimeHost {
    /// RuntimeHost 在同一锁序下冻结 Workspace identity，并把它同时用于 JA-RPC DRAFT 授权与本地 cache 归属；
    /// open 失败只记录稳定 runtime code，便于区分授权、合同与生命周期故障且不泄露资源 identity。
    fn open(
        &self,
        attachment_id: String,
        authorization: AttachmentPreviewAuthorizationInput,
    ) -> Result<RuntimeOpenedAttachmentPreview, AttachmentPreviewError> {
        self.attachment_preview_open_with_authorization(attachment_id, authorization)
            .map(
                |(result, workspace_identity)| RuntimeOpenedAttachmentPreview {
                    result,
                    workspace_identity,
                },
            )
            .map_err(|error| {
                tracing::warn!(
                    runtime_error_code = error.code,
                    "attachment preview open runtime request failed"
                );
                AttachmentPreviewError::runtime(error.retryable)
            })
    }

    /// read 复用 RuntimeHost 的 Ready generation，不启动或替换 sidecar。
    fn read(
        &self,
        params: AttachmentPreviewReadParams,
    ) -> Result<RuntimeReadResult, AttachmentPreviewError> {
        self.attachment_preview_read(params)
            .map_err(|error| AttachmentPreviewError::runtime(error.retryable))
    }

    /// close 保持 App Server 为 session 终态 owner；Rust host 仅在其成功后撤销本地 token。
    fn close(&self, params: AttachmentPreviewCloseParams) -> Result<(), AttachmentPreviewError> {
        self.attachment_preview_close(params)
            .map_err(|error| AttachmentPreviewError::runtime(error.retryable))
    }
}

/// native host 清理按当前 Workspace identity 分组；identity 不投影给 renderer。
pub struct RuntimeOpenedAttachmentPreview {
    pub result: RuntimeOpenResult,
    pub workspace_identity: String,
}

/// Tauri managed state 使用 sized wrapper 承载窄 trait object，避免 command 取得完整 RuntimeHost。
#[derive(Clone)]
pub struct AttachmentPreviewRuntimeState(Arc<dyn AttachmentPreviewRuntimePort>);

impl AttachmentPreviewRuntimeState {
    /// setup 显式注入 production adapter；测试可注入内存 fake 而无需启动 sidecar。
    pub fn new(runtime: Arc<dyn AttachmentPreviewRuntimePort>) -> Self {
        Self(runtime)
    }

    /// workflow 只借用固定 port，不能替换当前 runtime owner。
    fn runtime(&self) -> &dyn AttachmentPreviewRuntimePort {
        self.0.as_ref()
    }
}

/// open authorization 是严格 tagged union，不接受同时出现 workspace/thread 的模糊 scope。
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind", deny_unknown_fields)]
pub enum AttachmentPreviewAuthorizationInput {
    Draft,
    Thread {
        #[serde(rename = "threadId")]
        thread_id: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentPreviewOpenInput {
    pub attachment_id: String,
    pub authorization: AttachmentPreviewAuthorizationInput,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentPreviewReadInput {
    pub preview_session_id: String,
    pub offset_bytes: u64,
    pub max_bytes: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentPreviewCloseInput {
    pub preview_session_id: String,
}

/// open 在 blocking worker 中完成同步 JA-RPC、Base64 与图片处理；UI executor 不承载 CPU/stdio 工作。
#[tauri::command]
pub async fn ja_attachment_preview_open<R: Runtime>(
    webview: tauri::Webview<R>,
    input: AttachmentPreviewOpenInput,
    runtime: tauri::State<'_, AttachmentPreviewRuntimeState>,
    host: tauri::State<'_, Arc<AttachmentPreviewHost>>,
) -> Result<AttachmentPreviewOpenResult, AttachmentPreviewError> {
    let window_label = webview.label().to_owned();
    let runtime = runtime.inner().clone();
    let host = Arc::clone(host.inner());
    tauri::async_runtime::spawn_blocking(move || {
        open_preview(&window_label, &runtime, &host, input)
    })
    .await
    .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::StateUnavailable))?
}

/// text read 每次重新解析本地 session 授权并调用 server；图片内容只走 custom protocol。
#[tauri::command]
pub async fn ja_attachment_preview_read<R: Runtime>(
    webview: tauri::Webview<R>,
    input: AttachmentPreviewReadInput,
    runtime: tauri::State<'_, AttachmentPreviewRuntimeState>,
    host: tauri::State<'_, Arc<AttachmentPreviewHost>>,
) -> Result<AttachmentTextReadResult, AttachmentPreviewError> {
    let window_label = webview.label().to_owned();
    let runtime = runtime.inner().clone();
    let host = Arc::clone(host.inner());
    tauri::async_runtime::spawn_blocking(move || read_text(&window_label, &runtime, &host, input))
        .await
        .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::StateUnavailable))?
}

/// close 先等待 App Server 幂等确认，再撤销本地 URL；RPC 失败时保留 session 供用户重试。
#[tauri::command]
pub async fn ja_attachment_preview_close<R: Runtime>(
    webview: tauri::Webview<R>,
    input: AttachmentPreviewCloseInput,
    runtime: tauri::State<'_, AttachmentPreviewRuntimeState>,
    host: tauri::State<'_, Arc<AttachmentPreviewHost>>,
) -> Result<AttachmentPreviewCloseResult, AttachmentPreviewError> {
    let window_label = webview.label().to_owned();
    let runtime = runtime.inner().clone();
    let host = Arc::clone(host.inner());
    tauri::async_runtime::spawn_blocking(move || {
        close_preview(&window_label, &runtime, &host, input)
    })
    .await
    .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::StateUnavailable))?
}

/// workflow 对 open 后的任何本地失败执行 server close 补偿，避免等待 5 分钟 TTL 才释放 slot。
pub(crate) fn open_preview(
    window_label: &str,
    runtime: &AttachmentPreviewRuntimeState,
    host: &AttachmentPreviewHost,
    input: AttachmentPreviewOpenInput,
) -> Result<AttachmentPreviewOpenResult, AttachmentPreviewError> {
    let opened = runtime
        .runtime()
        .open(input.attachment_id.clone(), input.authorization.clone())?;
    let descriptor = project_preview_descriptor(opened.result)?;
    let server_session = descriptor.preview_session_id.clone();
    let derivatives = if descriptor.preview_kind == AttachmentPreviewKind::Image {
        let bytes = match read_complete_image(runtime.runtime(), &descriptor) {
            Ok(bytes) => bytes,
            Err(error) => {
                compensate_close(runtime.runtime(), &server_session);
                return Err(error);
            }
        };
        match derive_attachment_image(&bytes) {
            Ok(derivatives) => Some(derivatives),
            Err(error) => {
                compensate_close(runtime.runtime(), &server_session);
                return Err(error);
            }
        }
    } else {
        None
    };
    match host.open(
        window_label,
        opened.workspace_identity,
        descriptor,
        derivatives,
    ) {
        Ok(result) => Ok(result),
        Err(error) => {
            compensate_close(runtime.runtime(), &server_session);
            Err(error)
        }
    }
}

/// 逐段解码避免同时保留 133% Base64 与完整图片的第二份集合，并严格要求物理 EOF 与 metadata 大小一致。
fn read_complete_image(
    runtime: &dyn AttachmentPreviewRuntimePort,
    descriptor: &super::model::AttachmentPreviewDescriptor,
) -> Result<Vec<u8>, AttachmentPreviewError> {
    let mut offset = 0_u64;
    let capacity = usize::try_from(descriptor.size_bytes).map_err(|_| {
        AttachmentPreviewError::new(AttachmentPreviewErrorCode::ImageBudgetExceeded)
    })?;
    let mut bytes = Vec::with_capacity(capacity);
    loop {
        let params = AttachmentPreviewReadParams::new(
            descriptor.preview_session_id.as_str(),
            offset,
            u64::from(ATTACHMENT_PREVIEW_READ_BYTES),
        )
        .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::SourceUnavailable))?;
        let chunk = runtime.read(params)?;
        if chunk.preview_session_id != descriptor.preview_session_id.as_str()
            || chunk.offset_bytes != offset
            || chunk.truncated
        {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::SourceUnavailable,
            ));
        }
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(&chunk.content_base64)
            .map_err(|_| {
                AttachmentPreviewError::new(AttachmentPreviewErrorCode::SourceUnavailable)
            })?;
        let next_total = bytes
            .len()
            .checked_add(decoded.len())
            .filter(|total| *total <= capacity)
            .ok_or_else(|| {
                AttachmentPreviewError::new(AttachmentPreviewErrorCode::SourceUnavailable)
            })?;
        if chunk.next_offset_bytes
            != offset.saturating_add(u64::try_from(decoded.len()).unwrap_or(u64::MAX))
        {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::SourceUnavailable,
            ));
        }
        bytes.extend_from_slice(&decoded);
        offset = chunk.next_offset_bytes;
        if chunk.eof {
            if next_total != capacity {
                return Err(AttachmentPreviewError::new(
                    AttachmentPreviewErrorCode::SourceUnavailable,
                ));
            }
            return Ok(bytes);
        }
        if decoded.is_empty() {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::SourceUnavailable,
            ));
        }
    }
}

/// UI offset/limit 与 server hard limit 双重校验，且 public id 只用于返回关联，不进入 JA-RPC。
pub(crate) fn read_text(
    window_label: &str,
    runtime: &AttachmentPreviewRuntimeState,
    host: &AttachmentPreviewHost,
    input: AttachmentPreviewReadInput,
) -> Result<AttachmentTextReadResult, AttachmentPreviewError> {
    if input.offset_bytes > ATTACHMENT_PREVIEW_TEXT_BYTES
        || !(ATTACHMENT_PREVIEW_READ_MIN_BYTES..=ATTACHMENT_PREVIEW_READ_MAX_BYTES)
            .contains(&input.max_bytes)
    {
        return Err(AttachmentPreviewError::new(
            AttachmentPreviewErrorCode::InvalidInput,
        ));
    }
    let server_session = host.authorize_text_read(window_label, &input.preview_session_id)?;
    let params = AttachmentPreviewReadParams::new(
        server_session.as_str(),
        input.offset_bytes,
        input.max_bytes,
    )
    .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::InvalidInput))?;
    let result = runtime.runtime().read(params)?;
    project_text_preview_chunk(input.preview_session_id, &server_session, result)
}

/// 本地缺失已经满足幂等 close；存在时只有 server 确认后才撤销本地缓存和 token。
pub(crate) fn close_preview(
    window_label: &str,
    runtime: &AttachmentPreviewRuntimeState,
    host: &AttachmentPreviewHost,
    input: AttachmentPreviewCloseInput,
) -> Result<AttachmentPreviewCloseResult, AttachmentPreviewError> {
    let server_session = match host.authorize_close(window_label, &input.preview_session_id) {
        Ok(session) => session,
        Err(error) if error.code == AttachmentPreviewErrorCode::TokenNotFound => {
            return Ok(AttachmentPreviewCloseResult { closed: true });
        }
        Err(error) => return Err(error),
    };
    let params = AttachmentPreviewCloseParams::new(server_session.as_str())
        .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::SourceUnavailable))?;
    runtime.runtime().close(params)?;
    host.close(window_label, &input.preview_session_id)?;
    Ok(AttachmentPreviewCloseResult { closed: true })
}

/// Workspace 切换在旧 Workspace 仍为 App Server current owner 时逐个 close；
/// 任一失败保留全部本地 token 供重试，全部确认后再原子清 cache。
pub(crate) fn close_for_workspace_switch(
    runtime: &AttachmentPreviewRuntimeState,
    host: &AttachmentPreviewHost,
) -> Result<usize, AttachmentPreviewError> {
    let sessions = host.server_sessions()?;
    for session in &sessions {
        let params = AttachmentPreviewCloseParams::new(session.as_str()).map_err(|_| {
            AttachmentPreviewError::new(AttachmentPreviewErrorCode::SourceUnavailable)
        })?;
        runtime.runtime().close(params)?;
    }
    host.clear()?;
    Ok(sessions.len())
}

/// 补偿失败不覆盖原始 preview 错误；远端 session 的 TTL 仍提供最终兜底。
fn compensate_close(
    runtime: &dyn AttachmentPreviewRuntimePort,
    session: &AttachmentPreviewSessionId,
) {
    if let Ok(params) = AttachmentPreviewCloseParams::new(session.as_str()) {
        let _ = runtime.close(params);
    }
}
