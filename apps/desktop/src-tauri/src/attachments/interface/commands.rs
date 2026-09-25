// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 附件 picker、Native Drop、原生剪贴板与逐项 Channel workflow 的唯一桌面适配边界。

use super::super::{
    AdmittedAttachmentSource, AttachmentIngress, AttachmentIngressError,
    AttachmentIngressErrorCode, IngressAttachment, ItemCancellation, RETRY_ATTEMPT_TTL,
};
use crate::app_runtime::{
    AttachmentDiscardInput, AttachmentImportInput, AttachmentMetadata, RuntimeCommandError,
    RuntimeHost,
};
use arboard::{Clipboard, Error as ClipboardError};
use image::{DynamicImage, ImageFormat, RgbaImage};
use serde::Serialize;
use std::io::Cursor;
use std::path::PathBuf;
use std::sync::Arc;
use std::thread;
use std::time::Duration;
use tauri::ipc::Channel;
use tauri::{AppHandle, Runtime};
use tauri_plugin_dialog::DialogExt;

const MAX_PIXELS: u64 = 40_000_000;
const MAX_DECODED_IMAGE_BYTES: u64 = 256 * 1024 * 1024;
const PASTED_IMAGE_NAME: &str = "pasted-image.png";
const CLIPBOARD_RETRY_DELAYS_MS: [u64; 3] = [15, 35, 70];

/// 剪贴板命令只返回是否已接纳导入；空格式和短暂占用是用户提示，不伪装成附件失败。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum ClipboardImportOutcome {
    Accepted,
    NothingImportable,
    Busy,
}

/// 结果对象保持未来可扩展，但拒绝旧图片专用布尔合同。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub(crate) struct ClipboardImportResult {
    pub(crate) outcome: ClipboardImportOutcome,
}

pub(crate) enum ClipboardPayload {
    Paths(Vec<PathBuf>),
    Png(Vec<u8>),
    RejectedImage(AttachmentIngressError),
}

enum ClipboardReadResult {
    Payload(ClipboardPayload),
    NothingImportable,
    Busy,
}

/// 将平台图片收窄为独立 owned DTO，使优先级策略无需依赖真实系统剪贴板即可验证。
pub(crate) struct ClipboardImage {
    pub(crate) width: usize,
    pub(crate) height: usize,
    pub(crate) rgba: Vec<u8>,
}

/// 原生 adapter 只暴露本功能需要的 CF_HDROP 与 RGBA 两种读取，不读取文本、HTML 或 URL。
pub(crate) trait ClipboardReader {
    /// 文件列表保留系统返回顺序，后续 ingress 再执行路径与容量校验。
    fn read_file_list(&mut self) -> Result<Vec<PathBuf>, ClipboardError>;

    /// 图片读取立即转为 owned bytes，确保平台 clipboard handle 不跨 worker 生命周期。
    fn read_image(&mut self) -> Result<ClipboardImage, ClipboardError>;
}

impl ClipboardReader for Clipboard {
    /// `arboard` 在 Windows 通过 CF_HDROP 返回路径，renderer 永远不会接触这些值。
    fn read_file_list(&mut self) -> Result<Vec<PathBuf>, ClipboardError> {
        self.get().file_list()
    }

    /// `arboard` 负责 DIB/PNG 等平台格式解码，Ja 只接收统一 RGBA 并执行自己的预算。
    fn read_image(&mut self) -> Result<ClipboardImage, ClipboardError> {
        let image = self.get().image()?;
        Ok(ClipboardImage {
            width: image.width,
            height: image.height,
            rgba: image.bytes.into_owned(),
        })
    }
}

/// 重试结果将短暂占用与不可导入内容分型，调用方不会为二者制造失败附件卡。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum ClipboardRetryResult<T> {
    Payload(T),
    NothingImportable,
    Busy,
}

/// WebView 只观察 App Server 签发的身份和展示元数据；hash、token 与任何路径被刻意删去。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AttachmentDto {
    pub(crate) attachment_id: String,
    pub(crate) file_name: String,
    pub(crate) size_bytes: u64,
    pub(crate) media_kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) media_type: Option<String>,
    pub(crate) state: String,
}

impl From<AttachmentMetadata> for AttachmentDto {
    /// 在 IPC 前执行最后一次字段收窄，避免内部 metadata 演进时意外扩大 WebView 能力面。
    fn from(value: AttachmentMetadata) -> Self {
        Self {
            attachment_id: value.attachment_id,
            file_name: value.display_name,
            size_bytes: value.size_bytes,
            media_kind: value.media_kind,
            media_type: value.media_type,
            state: value.state,
        }
    }
}

/// 附件命令错误只保留稳定分类和恢复语义，不保存底层路径、token 或 RPC payload。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AttachmentCommandError {
    pub(crate) code: &'static str,
    pub(crate) message: &'static str,
    pub(crate) retryable: bool,
}

impl AttachmentCommandError {
    /// ingress category 仅用于选定稳定恢复语义；详细 IO 事实不进入 renderer。
    fn ingress(error: AttachmentIngressError) -> Self {
        tracing::warn!(category = ?error.code, "attachment ingress rejected an item");
        match error.code {
            AttachmentIngressErrorCode::Cancelled => Self::cancelled(),
            AttachmentIngressErrorCode::AttemptNotFound => Self {
                code: "ATTACHMENT_ATTEMPT_EXPIRED",
                message: "attachment retry is no longer available",
                retryable: false,
            },
            AttachmentIngressErrorCode::ImageTooLarge => Self {
                code: "ATTACHMENT_IMAGE_TOO_LARGE",
                message: "clipboard image exceeds the preview budget",
                retryable: false,
            },
            AttachmentIngressErrorCode::TooManyFiles => Self {
                code: "ATTACHMENT_TOO_MANY_FILES",
                message: "too many attachments were selected",
                retryable: false,
            },
            AttachmentIngressErrorCode::FileTooLarge
            | AttachmentIngressErrorCode::BatchTooLarge => Self {
                code: "ATTACHMENT_SIZE_LIMIT",
                message: "attachment exceeds the size limit",
                retryable: false,
            },
            AttachmentIngressErrorCode::UnsupportedPath => Self {
                code: "ATTACHMENT_UNSUPPORTED_PATH",
                message: "attachment path is unsupported",
                retryable: false,
            },
            AttachmentIngressErrorCode::NotRegularFile => Self {
                code: "ATTACHMENT_NOT_REGULAR_FILE",
                message: "attachment is not a regular file",
                retryable: false,
            },
            AttachmentIngressErrorCode::LinkNotAllowed => Self {
                code: "ATTACHMENT_LINK_NOT_ALLOWED",
                message: "attachment links are not allowed",
                retryable: false,
            },
            AttachmentIngressErrorCode::SourceChanged => Self {
                code: "ATTACHMENT_SOURCE_CHANGED",
                message: "attachment source changed during import",
                retryable: false,
            },
            AttachmentIngressErrorCode::SourceReadFailed => Self {
                code: "ATTACHMENT_SOURCE_READ_FAILED",
                message: "attachment source could not be read",
                retryable: true,
            },
            AttachmentIngressErrorCode::StagingFailed => Self {
                code: "ATTACHMENT_STAGING_FAILED",
                message: "attachment staging failed",
                retryable: true,
            },
            _ => Self {
                code: "ATTACHMENT_INGRESS_FAILED",
                message: "attachment could not be imported",
                retryable: false,
            },
        }
    }

    /// Runtime 错误只继承是否可重试，不转发 App Server code/message 到附件组件。
    fn runtime(error: RuntimeCommandError) -> Self {
        Self {
            code: "ATTACHMENT_RUNTIME_FAILED",
            message: "attachment service is unavailable",
            retryable: error.retryable,
        }
    }

    /// callback 生命周期异常表示原生边界不可用，不能当作用户取消。
    fn callback_closed() -> Self {
        Self {
            code: "ATTACHMENT_DIALOG_FAILED",
            message: "attachment picker is unavailable",
            retryable: true,
        }
    }

    /// renderer identity 非法时不进入任何进程级 registry。
    fn invalid_identity() -> Self {
        Self {
            code: "ATTACHMENT_INVALID_IDENTITY",
            message: "attachment operation is invalid",
            retryable: false,
        }
    }

    /// 协作取消是独立终态，不作为可重试错误渲染。
    fn cancelled() -> Self {
        Self {
            code: "ATTACHMENT_CANCELLED",
            message: "attachment import was cancelled",
            retryable: false,
        }
    }
}

/// Channel payload 使用显式 tag，两个并发 operation 可按 identity 严格归约且不会串线。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub(crate) enum AttachmentIngressEvent {
    Started {
        operation_id: String,
        attempt_id: String,
        item_id: String,
        file_name: String,
        size_bytes: u64,
    },
    Progress {
        operation_id: String,
        attempt_id: String,
        item_id: String,
        phase: AttachmentIngressPhase,
        bytes_copied: u64,
        total_bytes: u64,
    },
    Completed {
        operation_id: String,
        attempt_id: String,
        item_id: String,
        attachment: AttachmentDto,
    },
    Failed {
        operation_id: String,
        attempt_id: String,
        item_id: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        file_name: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        size_bytes: Option<u64>,
        code: &'static str,
        message: &'static str,
        retryable: bool,
    },
    Cancelled {
        operation_id: String,
        attempt_id: String,
        item_id: String,
    },
}

/// copying 是确定进度，importing 只表达 App Server 阶段且不伪造百分比。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum AttachmentIngressPhase {
    Copying,
    Importing,
}

/// Workflow 只依赖两个固定 App Server 用例，使失败补偿可在独立 integration target 验证。
pub(crate) trait AttachmentRuntimePort: Send + Sync {
    /// 导入必须返回 App Server 已持久化的 draft identity，caller 随后才能删除 staging。
    fn import_attachment(
        &self,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError>;
    /// 补偿与用户移除共用真实 discard，不允许 fake 本地状态替代 App Server。
    fn discard_attachment(&self, attachment_id: String) -> Result<(), RuntimeCommandError>;
}

impl AttachmentRuntimePort for RuntimeHost {
    /// production port 绑定 Host 当前 Workspace，interface 不接受 renderer 提供的 workspace identity。
    fn import_attachment(
        &self,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        self.attachment_import(input)
    }

    /// production discard 只把 App Server identity 交给固定 typed lane。
    fn discard_attachment(&self, attachment_id: String) -> Result<(), RuntimeCommandError> {
        self.attachment_discard(AttachmentDiscardInput { attachment_id })
    }
}

/// Channel sender 失效表示 renderer 已离开；worker 继续完成安全 cleanup，但不记录 payload。
fn send_event(channel: &Channel<AttachmentIngressEvent>, event: AttachmentIngressEvent) {
    if channel.send(event).is_err() {
        tracing::debug!("attachment ingress channel is no longer available");
    }
}

/// 对单个已 admission 文件执行 copy→App Server import；retryable failure 保留短期 staging capability。
fn import_admitted_item(
    host: &dyn AttachmentRuntimePort,
    ingress: &Arc<AttachmentIngress>,
    operation_id: &str,
    item_id: String,
    attempt_id: String,
    source: AdmittedAttachmentSource,
    channel: &Channel<AttachmentIngressEvent>,
) {
    let file_name = source.display_name.clone();
    let size_bytes = source.size_bytes;
    let cancellation = match ingress.register_operation_item(operation_id, &item_id) {
        Ok(value) => value,
        Err(error) => {
            send_failed(
                channel,
                operation_id,
                attempt_id,
                item_id,
                None,
                None,
                error.into(),
            );
            return;
        }
    };
    send_event(
        channel,
        AttachmentIngressEvent::Started {
            operation_id: operation_id.to_owned(),
            attempt_id: attempt_id.clone(),
            item_id: item_id.clone(),
            file_name: file_name.clone(),
            size_bytes,
        },
    );
    let staged = ingress.stage_admitted_with_control(source, &cancellation, |copied, total| {
        send_event(
            channel,
            AttachmentIngressEvent::Progress {
                operation_id: operation_id.to_owned(),
                attempt_id: attempt_id.clone(),
                item_id: item_id.clone(),
                phase: AttachmentIngressPhase::Copying,
                bytes_copied: copied,
                total_bytes: total,
            },
        );
    });
    let staged = match staged {
        Ok(value) => value,
        Err(error) if error.code == AttachmentIngressErrorCode::Cancelled => {
            send_cancelled(channel, operation_id, attempt_id, item_id);
            return;
        }
        Err(error) => {
            send_failed(
                channel,
                operation_id,
                attempt_id,
                item_id,
                Some(file_name),
                Some(size_bytes),
                error.into(),
            );
            return;
        }
    };
    import_staged_item(
        host,
        ingress,
        operation_id,
        item_id,
        attempt_id,
        staged,
        cancellation,
        channel,
    );
}

/// App Server 阶段不伪造进度；失败是否保留 staging 只由 Runtime retryable 契约决定。
#[allow(clippy::too_many_arguments)]
fn import_staged_item(
    host: &dyn AttachmentRuntimePort,
    ingress: &Arc<AttachmentIngress>,
    operation_id: &str,
    item_id: String,
    attempt_id: String,
    staged: IngressAttachment,
    cancellation: ItemCancellation,
    channel: &Channel<AttachmentIngressEvent>,
) {
    if cancellation.is_cancelled() {
        let _ = ingress.discard(&staged.ingress_token);
        send_cancelled(channel, operation_id, attempt_id, item_id);
        return;
    }
    send_event(
        channel,
        AttachmentIngressEvent::Progress {
            operation_id: operation_id.to_owned(),
            attempt_id: attempt_id.clone(),
            item_id: item_id.clone(),
            phase: AttachmentIngressPhase::Importing,
            bytes_copied: staged.size_bytes,
            total_bytes: staged.size_bytes,
        },
    );
    let result = host.import_attachment(AttachmentImportInput {
        ingress_token: staged.ingress_token.as_str().to_owned(),
        display_name: staged.display_name.clone(),
        size_bytes: staged.size_bytes,
        sha256: staged.sha256.clone(),
    });
    if cancellation.is_cancelled() {
        if let Ok(metadata) = result {
            let _ = host.discard_attachment(metadata.attachment_id);
        }
        match ingress.discard(&staged.ingress_token) {
            Ok(()) => send_cancelled(channel, operation_id, attempt_id, item_id),
            Err(error) => send_failed(
                channel,
                operation_id,
                attempt_id,
                item_id,
                Some(staged.display_name),
                Some(staged.size_bytes),
                error.into(),
            ),
        }
        return;
    }
    match result {
        Ok(metadata) => complete_import(
            host,
            ingress,
            operation_id,
            item_id,
            attempt_id,
            staged,
            metadata,
            channel,
        ),
        Err(error) => {
            // 仅记录稳定内部码即可区分 Host、bridge 与 App Server 边界；附件身份和路径不得进入日志。
            tracing::warn!(
                error_code = error.code,
                retryable = error.retryable,
                "attachment runtime import failed"
            );
            retain_or_discard_failed_import(
                ingress,
                operation_id,
                item_id,
                attempt_id,
                staged,
                AttachmentCommandError::runtime(error),
                channel,
            )
        }
    }
}

/// App Server 成功后必须先删除 Rust staging；清理失败会补偿 Java draft，避免双 owner。
#[allow(clippy::too_many_arguments)]
fn complete_import(
    host: &dyn AttachmentRuntimePort,
    ingress: &Arc<AttachmentIngress>,
    operation_id: &str,
    item_id: String,
    attempt_id: String,
    staged: IngressAttachment,
    metadata: AttachmentMetadata,
    channel: &Channel<AttachmentIngressEvent>,
) {
    let projected = AttachmentDto::from(metadata);
    if let Err(error) = ingress.complete(&staged.ingress_token) {
        let _ = host.discard_attachment(projected.attachment_id.clone());
        send_failed(
            channel,
            operation_id,
            attempt_id,
            item_id,
            Some(staged.display_name),
            Some(staged.size_bytes),
            error.into(),
        );
        return;
    }
    send_event(
        channel,
        AttachmentIngressEvent::Completed {
            operation_id: operation_id.to_owned(),
            attempt_id,
            item_id,
            attachment: projected,
        },
    );
}

/// retryable failure 只有成功登记 capability 才保留 staging；容量或不可重试失败都立即删除。
fn retain_or_discard_failed_import(
    ingress: &Arc<AttachmentIngress>,
    operation_id: &str,
    item_id: String,
    attempt_id: String,
    staged: IngressAttachment,
    failure: AttachmentCommandError,
    channel: &Channel<AttachmentIngressEvent>,
) {
    if failure.retryable
        && ingress
            .retain_retry_attempt(attempt_id.clone(), item_id.clone(), staged.clone())
            .is_ok()
    {
        schedule_attempt_expiry(Arc::clone(ingress), attempt_id.clone());
    } else {
        let _ = ingress.discard(&staged.ingress_token);
    }
    send_failed(
        channel,
        operation_id,
        attempt_id,
        item_id,
        Some(staged.display_name),
        Some(staged.size_bytes),
        failure,
    );
}

/// TTL 清理使用 runtime timer 而不是 sleeping OS thread，且只消费对应 opaque attempt。
fn schedule_attempt_expiry(ingress: Arc<AttachmentIngress>, attempt_id: String) {
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(RETRY_ATTEMPT_TTL).await;
        ingress.expire_retry_attempt(&attempt_id);
    });
}

/// 失败 event 使用稳定白名单 message；调用点不能传入 path、token 或底层错误文本。
#[allow(clippy::too_many_arguments)]
fn send_failed(
    channel: &Channel<AttachmentIngressEvent>,
    operation_id: &str,
    attempt_id: String,
    item_id: String,
    file_name: Option<String>,
    size_bytes: Option<u64>,
    error: AttachmentCommandError,
) {
    send_event(
        channel,
        AttachmentIngressEvent::Failed {
            operation_id: operation_id.to_owned(),
            attempt_id,
            item_id,
            file_name,
            size_bytes,
            code: error.code,
            message: error.message,
            retryable: error.retryable,
        },
    );
}

/// cancelled event 与 failed 分型，使 UI 不把用户移除解释为错误恢复入口。
fn send_cancelled(
    channel: &Channel<AttachmentIngressEvent>,
    operation_id: &str,
    attempt_id: String,
    item_id: String,
) {
    send_event(
        channel,
        AttachmentIngressEvent::Cancelled {
            operation_id: operation_id.to_owned(),
            attempt_id,
            item_id,
        },
    );
}

/// 整批 admission 后逐项串行导入；每项状态独立，单项失败不回滚已经 ready 的兄弟附件。
pub(crate) fn import_paths(
    host: &dyn AttachmentRuntimePort,
    ingress: Arc<AttachmentIngress>,
    operation_id: String,
    paths: Vec<PathBuf>,
    channel: Channel<AttachmentIngressEvent>,
) {
    let admitted = match ingress.admit_paths(paths) {
        Ok(value) => value,
        Err(error) => {
            send_failed(
                &channel,
                &operation_id,
                opaque_id(),
                opaque_id(),
                None,
                None,
                error.into(),
            );
            ingress.finish_operation(&operation_id);
            return;
        }
    };
    for source in admitted {
        import_admitted_item(
            host,
            &ingress,
            &operation_id,
            opaque_id(),
            opaque_id(),
            source,
            &channel,
        );
    }
    ingress.finish_operation(&operation_id);
}

/// clipboard RGBA 必须满足像素、解码字节和精确 buffer 长度预算，再用固定 PNG encoder 产生 staging bytes。
pub(crate) fn encode_clipboard_png(
    width: u32,
    height: u32,
    rgba: Vec<u8>,
) -> Result<Vec<u8>, AttachmentIngressError> {
    let pixels = u64::from(width)
        .checked_mul(u64::from(height))
        .ok_or_else(|| AttachmentIngressError::new(AttachmentIngressErrorCode::ImageTooLarge))?;
    let decoded_bytes = pixels
        .checked_mul(4)
        .ok_or_else(|| AttachmentIngressError::new(AttachmentIngressErrorCode::ImageTooLarge))?;
    if width == 0
        || height == 0
        || pixels > MAX_PIXELS
        || decoded_bytes > MAX_DECODED_IMAGE_BYTES
        || decoded_bytes != u64::try_from(rgba.len()).unwrap_or(u64::MAX)
    {
        return Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::ImageTooLarge,
        ));
    }
    let image = RgbaImage::from_raw(width, height, rgba)
        .ok_or_else(|| AttachmentIngressError::new(AttachmentIngressErrorCode::ImageInvalid))?;
    let mut output = Cursor::new(Vec::new());
    DynamicImage::ImageRgba8(image)
        .write_to(&mut output, ImageFormat::Png)
        .map_err(|_| AttachmentIngressError::new(AttachmentIngressErrorCode::ImageEncodeFailed))?;
    Ok(output.into_inner())
}

/// renderer identity 必须为有界 ASCII opaque value，避免任意长 key 扩大进程 registry。
fn validate_identity(value: &str) -> Result<(), AttachmentCommandError> {
    if value.is_empty()
        || value.len() > 128
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
    {
        Err(AttachmentCommandError::invalid_identity())
    } else {
        Ok(())
    }
}

/// Rust 生成所有 item/attempt identity，避免 renderer 选择内部 registry key。
fn opaque_id() -> String {
    uuid::Uuid::new_v4().simple().to_string()
}

/// 在同一次用户手势中打开原生多选 dialog；取消通过空路径列表进入正常分支。
fn pick_paths<R: Runtime>(
    app: &AppHandle<R>,
    callback: impl FnOnce(Result<Vec<PathBuf>, AttachmentCommandError>) + Send + 'static,
) {
    app.dialog().file().pick_files(move |selection| {
        let Some(selection) = selection else {
            callback(Ok(Vec::new()));
            return;
        };
        callback(
            selection
                .into_iter()
                .map(|selected| {
                    selected
                        .into_path()
                        .map_err(|_| AttachmentCommandError::callback_closed())
                })
                .collect(),
        );
    });
}

/// picker command 只等待 native callback；安全复制和 RPC 留在 blocking worker，由 Channel 投递终态。
#[tauri::command]
pub(crate) async fn ja_attachment_picker_import<R: Runtime>(
    operation_id: String,
    on_event: Channel<AttachmentIngressEvent>,
    app: AppHandle<R>,
    ingress: tauri::State<'_, Arc<AttachmentIngress>>,
    runtime: tauri::State<'_, RuntimeHost>,
) -> Result<(), AttachmentCommandError> {
    validate_identity(&operation_id)?;
    ingress
        .begin_operation(&operation_id)
        .map_err(AttachmentCommandError::ingress)?;
    let (sender, receiver) = tokio::sync::oneshot::channel();
    pick_paths(&app, move |selection| {
        let _ = sender.send(selection);
    });
    let paths = match receiver.await {
        Ok(Ok(paths)) => paths,
        Ok(Err(error)) => {
            ingress.finish_operation(&operation_id);
            return Err(error);
        }
        Err(_) => {
            ingress.finish_operation(&operation_id);
            return Err(AttachmentCommandError::callback_closed());
        }
    };
    if paths.is_empty() {
        ingress.finish_operation(&operation_id);
        return Ok(());
    }
    spawn_path_import(
        runtime.inner().clone(),
        Arc::clone(ingress.inner()),
        operation_id,
        paths,
        on_event,
    );
    Ok(())
}

/// Native Drop command 先消费一次性 capability，路径只存在于 Rust worker 参数且永不进入事件。
#[tauri::command]
pub(crate) async fn ja_attachment_drop_import(
    operation_id: String,
    drop_token: String,
    on_event: Channel<AttachmentIngressEvent>,
    ingress: tauri::State<'_, Arc<AttachmentIngress>>,
    runtime: tauri::State<'_, RuntimeHost>,
) -> Result<(), AttachmentCommandError> {
    validate_identity(&operation_id)?;
    validate_identity(&drop_token)?;
    ingress
        .begin_operation(&operation_id)
        .map_err(AttachmentCommandError::ingress)?;
    let paths = crate::workspace::consume_native_drop(&drop_token).map_err(|_| {
        ingress.finish_operation(&operation_id);
        AttachmentCommandError {
            code: "ATTACHMENT_DROP_EXPIRED",
            message: "native drop is invalid or expired",
            retryable: false,
        }
    })?;
    spawn_path_import(
        runtime.inner().clone(),
        Arc::clone(ingress.inner()),
        operation_id,
        paths,
        on_event,
    );
    Ok(())
}

/// 剪贴板读取和 PNG 编码全部在 blocking worker；返回前只确定接纳结果，逐项进度仍由 Channel 推送。
#[tauri::command]
pub(crate) async fn ja_attachment_clipboard_import(
    operation_id: String,
    on_event: Channel<AttachmentIngressEvent>,
    ingress: tauri::State<'_, Arc<AttachmentIngress>>,
    runtime: tauri::State<'_, RuntimeHost>,
) -> Result<ClipboardImportResult, AttachmentCommandError> {
    validate_identity(&operation_id)?;
    ingress
        .begin_operation(&operation_id)
        .map_err(AttachmentCommandError::ingress)?;
    let ingress = Arc::clone(ingress.inner());
    let host = runtime.inner().clone();
    let clipboard = match tauri::async_runtime::spawn_blocking(read_clipboard_payload).await {
        Ok(result) => result,
        Err(_) => {
            ingress.finish_operation(&operation_id);
            return Err(AttachmentCommandError::callback_closed());
        }
    };
    match clipboard {
        ClipboardReadResult::Payload(ClipboardPayload::Paths(paths)) => {
            spawn_path_import(host, ingress, operation_id, paths, on_event);
            Ok(ClipboardImportResult {
                outcome: ClipboardImportOutcome::Accepted,
            })
        }
        ClipboardReadResult::Payload(ClipboardPayload::Png(png)) => {
            tauri::async_runtime::spawn_blocking(move || {
                import_clipboard_png(&host, ingress, operation_id, on_event, png);
            });
            Ok(ClipboardImportResult {
                outcome: ClipboardImportOutcome::Accepted,
            })
        }
        ClipboardReadResult::Payload(ClipboardPayload::RejectedImage(error)) => {
            tauri::async_runtime::spawn_blocking(move || {
                import_clipboard_rejection(ingress, operation_id, on_event, error);
            });
            Ok(ClipboardImportResult {
                outcome: ClipboardImportOutcome::Accepted,
            })
        }
        ClipboardReadResult::NothingImportable => {
            ingress.finish_operation(&operation_id);
            Ok(ClipboardImportResult {
                outcome: ClipboardImportOutcome::NothingImportable,
            })
        }
        ClipboardReadResult::Busy => {
            ingress.finish_operation(&operation_id);
            Ok(ClipboardImportResult {
                outcome: ClipboardImportOutcome::Busy,
            })
        }
    }
}

/// 每次重试都重新执行“文件优先、图片次之”的完整判定，避免占用解除后错误改变格式优先级。
fn read_clipboard_payload() -> ClipboardReadResult {
    match retry_clipboard_read(read_clipboard_payload_once, thread::sleep) {
        ClipboardRetryResult::Payload(payload) => ClipboardReadResult::Payload(payload),
        ClipboardRetryResult::NothingImportable => ClipboardReadResult::NothingImportable,
        ClipboardRetryResult::Busy => ClipboardReadResult::Busy,
    }
}

/// 占用重试只有立即、15ms、35ms、70ms 四次尝试，总等待严格不超过 120ms。
pub(crate) fn retry_clipboard_read<T>(
    mut read_once: impl FnMut() -> Result<Option<T>, ClipboardError>,
    mut sleep: impl FnMut(Duration),
) -> ClipboardRetryResult<T> {
    for retry_delay in CLIPBOARD_RETRY_DELAYS_MS
        .iter()
        .copied()
        .map(Some)
        .chain(std::iter::once(None))
    {
        match read_once() {
            Ok(Some(payload)) => return ClipboardRetryResult::Payload(payload),
            Ok(None) => return ClipboardRetryResult::NothingImportable,
            Err(ClipboardError::ClipboardOccupied) => match retry_delay {
                Some(delay_ms) => sleep(Duration::from_millis(delay_ms)),
                None => return ClipboardRetryResult::Busy,
            },
            Err(_) => return ClipboardRetryResult::NothingImportable,
        }
    }
    ClipboardRetryResult::Busy
}

/// 每次重试创建短命 clipboard handle，避免 Windows 全局 clipboard 被 Ja 自身长期占用。
fn read_clipboard_payload_once() -> Result<Option<ClipboardPayload>, ClipboardError> {
    let mut clipboard = Clipboard::new()?;
    select_clipboard_payload(&mut clipboard)
}

/// 单次读取不触碰 HTML、URL 或远程资源；CF_HDROP 有内容时绝不再尝试位图分支。
pub(crate) fn select_clipboard_payload(
    clipboard: &mut impl ClipboardReader,
) -> Result<Option<ClipboardPayload>, ClipboardError> {
    match clipboard.read_file_list() {
        Ok(paths) if !paths.is_empty() => return Ok(Some(ClipboardPayload::Paths(paths))),
        Ok(_) | Err(ClipboardError::ContentNotAvailable) => {}
        Err(ClipboardError::ClipboardOccupied) => return Err(ClipboardError::ClipboardOccupied),
        // 某些应用会同时发布无法转换的文件描述与有效位图；文件分支失败时仍按固定优先级尝试图片。
        Err(_) => {}
    }
    let image = match clipboard.read_image() {
        Ok(image) => image,
        Err(ClipboardError::ContentNotAvailable) => return Ok(None),
        Err(error) => return Err(error),
    };
    let width = u32::try_from(image.width).map_err(|_| ClipboardError::ConversionFailure)?;
    let height = u32::try_from(image.height).map_err(|_| ClipboardError::ConversionFailure)?;
    Ok(Some(
        match encode_clipboard_png(width, height, image.rgba) {
            Ok(png) => ClipboardPayload::Png(png),
            Err(error) => ClipboardPayload::RejectedImage(error),
        },
    ))
}

/// 超预算或非法位图仍形成一个可见且可移除的失败条目，避免“已接纳”手势静默消失。
fn import_clipboard_rejection(
    ingress: Arc<AttachmentIngress>,
    operation_id: String,
    channel: Channel<AttachmentIngressEvent>,
    error: AttachmentIngressError,
) {
    let item_id = opaque_id();
    let attempt_id = opaque_id();
    if let Err(register_error) = ingress.register_operation_item(&operation_id, &item_id) {
        send_failed(
            &channel,
            &operation_id,
            attempt_id,
            item_id,
            Some(PASTED_IMAGE_NAME.to_owned()),
            None,
            register_error.into(),
        );
    } else {
        send_failed(
            &channel,
            &operation_id,
            attempt_id,
            item_id,
            Some(PASTED_IMAGE_NAME.to_owned()),
            None,
            error.into(),
        );
    }
    ingress.finish_operation(&operation_id);
}

/// 已完成预算校验的 PNG 复用 byte staging 与 App Server import，不建立图片专用第二条状态机。
fn import_clipboard_png(
    host: &dyn AttachmentRuntimePort,
    ingress: Arc<AttachmentIngress>,
    operation_id: String,
    channel: Channel<AttachmentIngressEvent>,
    png: Vec<u8>,
) {
    let item_id = opaque_id();
    let attempt_id = opaque_id();
    let cancellation = match ingress.register_operation_item(&operation_id, &item_id) {
        Ok(value) => value,
        Err(error) => {
            send_failed(
                &channel,
                &operation_id,
                attempt_id,
                item_id,
                None,
                None,
                error.into(),
            );
            ingress.finish_operation(&operation_id);
            return;
        }
    };
    send_event(
        &channel,
        AttachmentIngressEvent::Started {
            operation_id: operation_id.clone(),
            attempt_id: attempt_id.clone(),
            item_id: item_id.clone(),
            file_name: PASTED_IMAGE_NAME.to_owned(),
            size_bytes: u64::try_from(png.len()).unwrap_or(u64::MAX),
        },
    );
    let staged = ingress.stage_bytes_with_control(
        PASTED_IMAGE_NAME.to_owned(),
        &png,
        &cancellation,
        |copied, total| {
            send_event(
                &channel,
                AttachmentIngressEvent::Progress {
                    operation_id: operation_id.clone(),
                    attempt_id: attempt_id.clone(),
                    item_id: item_id.clone(),
                    phase: AttachmentIngressPhase::Copying,
                    bytes_copied: copied,
                    total_bytes: total,
                },
            );
        },
    );
    match staged {
        Ok(staged) => import_staged_item(
            host,
            &ingress,
            &operation_id,
            item_id,
            attempt_id,
            staged,
            cancellation,
            &channel,
        ),
        Err(error) if error.code == AttachmentIngressErrorCode::Cancelled => {
            send_cancelled(&channel, &operation_id, attempt_id, item_id)
        }
        Err(error) => send_failed(
            &channel,
            &operation_id,
            attempt_id,
            item_id,
            Some(PASTED_IMAGE_NAME.to_owned()),
            None,
            error.into(),
        ),
    }
    ingress.finish_operation(&operation_id);
}

/// retry 一次性消费旧 attempt 并复用 itemId；再次失败生成新的 opaque attempt capability。
#[tauri::command]
pub(crate) async fn ja_attachment_retry(
    operation_id: String,
    attempt_id: String,
    on_event: Channel<AttachmentIngressEvent>,
    ingress: tauri::State<'_, Arc<AttachmentIngress>>,
    runtime: tauri::State<'_, RuntimeHost>,
) -> Result<(), AttachmentCommandError> {
    validate_identity(&operation_id)?;
    validate_identity(&attempt_id)?;
    ingress
        .begin_operation(&operation_id)
        .map_err(AttachmentCommandError::ingress)?;
    let attempt = match ingress.take_retry_attempt(&attempt_id) {
        Ok(value) => value,
        Err(error) => {
            ingress.finish_operation(&operation_id);
            return Err(AttachmentCommandError::ingress(error));
        }
    };
    let item_id = attempt.item_id;
    let staged = attempt.attachment;
    let new_attempt_id = opaque_id();
    let cancellation = match ingress.register_operation_item(&operation_id, &item_id) {
        Ok(value) => value,
        Err(error) => {
            let _ = ingress.discard(&staged.ingress_token);
            ingress.finish_operation(&operation_id);
            return Err(AttachmentCommandError::ingress(error));
        }
    };
    let ingress = Arc::clone(ingress.inner());
    let host = runtime.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        send_event(
            &on_event,
            AttachmentIngressEvent::Started {
                operation_id: operation_id.clone(),
                attempt_id: new_attempt_id.clone(),
                item_id: item_id.clone(),
                file_name: staged.display_name.clone(),
                size_bytes: staged.size_bytes,
            },
        );
        import_staged_item(
            &host,
            &ingress,
            &operation_id,
            item_id,
            new_attempt_id,
            staged,
            cancellation,
            &on_event,
        );
        ingress.finish_operation(&operation_id);
    });
    Ok(())
}

/// importing X 可精确取消 item；itemId 缺省供关闭整个 picker/drop operation。
#[tauri::command]
pub(crate) fn ja_attachment_cancel(
    operation_id: String,
    item_id: Option<String>,
    ingress: tauri::State<'_, Arc<AttachmentIngress>>,
) -> Result<bool, AttachmentCommandError> {
    validate_identity(&operation_id)?;
    if let Some(item_id) = item_id.as_deref() {
        validate_identity(item_id)?;
    }
    ingress
        .cancel_operation(&operation_id, item_id.as_deref())
        .map_err(AttachmentCommandError::ingress)
}

/// failed X 消费 retry capability 并删除 Rust staging，不等待五分钟 TTL。
#[tauri::command]
pub(crate) async fn ja_attachment_attempt_discard(
    attempt_id: String,
    ingress: tauri::State<'_, Arc<AttachmentIngress>>,
) -> Result<(), AttachmentCommandError> {
    validate_identity(&attempt_id)?;
    let ingress = Arc::clone(ingress.inner());
    tauri::async_runtime::spawn_blocking(move || ingress.discard_retry_attempt(&attempt_id))
        .await
        .map_err(|_| AttachmentCommandError::callback_closed())?
        .map_err(AttachmentCommandError::ingress)
}

/// ready X 调用真实 App Server discard，不在 React 或 Rust 伪造本地成功状态。
#[tauri::command]
pub(crate) async fn ja_attachment_discard(
    attachment_id: String,
    runtime: tauri::State<'_, RuntimeHost>,
) -> Result<(), AttachmentCommandError> {
    validate_identity(&attachment_id)?;
    let host = runtime.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        host.attachment_discard(AttachmentDiscardInput { attachment_id })
            .map_err(AttachmentCommandError::runtime)
    })
    .await
    .map_err(|_| AttachmentCommandError::callback_closed())?
}

/// path workflow 始终离开 async executor；不同 operation 各持独立 Channel 与 registry identity。
fn spawn_path_import(
    host: RuntimeHost,
    ingress: Arc<AttachmentIngress>,
    operation_id: String,
    paths: Vec<PathBuf>,
    channel: Channel<AttachmentIngressEvent>,
) {
    tauri::async_runtime::spawn_blocking(move || {
        import_paths(&host, ingress, operation_id, paths, channel);
    });
}

impl From<AttachmentIngressError> for AttachmentCommandError {
    /// 内部 workflow 的统一映射仍只产生固定白名单 envelope。
    fn from(value: AttachmentIngressError) -> Self {
        Self::ingress(value)
    }
}
