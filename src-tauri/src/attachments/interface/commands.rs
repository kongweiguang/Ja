// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 原生 dialog、Tauri command 与附件 workflow 的唯一桌面适配边界。

use super::super::{AttachmentIngress, AttachmentIngressError};
use crate::app_runtime::{
    AttachmentDiscardInput, AttachmentImportInput, AttachmentMetadata, RuntimeCommandError,
    RuntimeHost,
};
use serde::Serialize;
use std::path::PathBuf;
use std::sync::Arc;
use tauri::{AppHandle, Runtime};
use tauri_plugin_dialog::DialogExt;

/// WebView 只观察 Java 签发的身份和展示元数据；workspace、hash、token 与任何路径都被刻意删去。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AttachmentDto {
    pub(crate) attachment_id: String,
    pub(crate) file_name: String,
    pub(crate) size_bytes: u64,
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
    /// ingress 失败统一收敛为安全文件错误；详细类别仍只留在 Rust 内部测试与诊断中。
    fn ingress(error: AttachmentIngressError) -> Self {
        tracing::warn!(
            category = ?error.code,
            "attachment ingress rejected a selected file"
        );
        Self {
            code: "ATTACHMENT_INGRESS_FAILED",
            message: "attachment could not be imported",
            retryable: false,
        }
    }

    /// Runtime 错误只继承是否可重试，不转发 Java/Rust 的 code 或 message 到附件组件。
    fn runtime(error: RuntimeCommandError) -> Self {
        Self {
            code: "ATTACHMENT_RUNTIME_FAILED",
            message: "attachment service is unavailable",
            retryable: error.retryable,
        }
    }

    /// callback sender/receiver 生命周期异常表示原生边界不可用，不能当作用户取消。
    fn callback_closed() -> Self {
        Self {
            code: "ATTACHMENT_DIALOG_FAILED",
            message: "attachment picker is unavailable",
            retryable: true,
        }
    }
}

/// Workflow 只依赖两个固定 Java 用例，使真实文件清理与失败补偿可在独立 integration target 验证。
pub(crate) trait AttachmentRuntimePort {
    /// 导入必须返回 Java 已持久化的 draft identity，caller 随后才能删除 staging。
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

    /// production discard 只把 Java identity 交给固定 typed lane。
    fn discard_attachment(&self, attachment_id: String) -> Result<(), RuntimeCommandError> {
        self.attachment_discard(AttachmentDiscardInput { attachment_id })
    }
}

/// 已成功导入 Java 的附件在批次后续失败时逐个补偿，避免 UI 收不到 identity 的孤儿草稿等待 24h GC。
fn compensate_imports(host: &dyn AttachmentRuntimePort, imported: &[AttachmentDto]) {
    for attachment in imported.iter().rev() {
        let _ = host.discard_attachment(attachment.attachment_id.clone());
    }
}

/// 批次工作流在线程内串行完成 stage→private RPC→cleanup；任一步失败都回收 Rust staging 并补偿已提交草稿。
pub(crate) fn import_selected(
    host: &dyn AttachmentRuntimePort,
    ingress: Arc<AttachmentIngress>,
    paths: Vec<PathBuf>,
) -> Result<Vec<AttachmentDto>, AttachmentCommandError> {
    let staged = ingress
        .stage_paths(paths)
        .map_err(AttachmentCommandError::ingress)?;
    let mut imported = Vec::with_capacity(staged.len());
    for attachment in &staged {
        let result = host.import_attachment(AttachmentImportInput {
            ingress_token: attachment.ingress_token.as_str().to_owned(),
            display_name: attachment.display_name.clone(),
            size_bytes: attachment.size_bytes,
            sha256: attachment.sha256.clone(),
        });
        let metadata = match result {
            Ok(metadata) => metadata,
            Err(error) => {
                compensate_imports(host, &imported);
                for pending in &staged {
                    let _ = ingress.discard(&pending.ingress_token);
                }
                return Err(AttachmentCommandError::runtime(error));
            }
        };
        let projected = AttachmentDto::from(metadata);
        if let Err(error) = ingress.complete(&attachment.ingress_token) {
            imported.push(projected);
            compensate_imports(host, &imported);
            for pending in &staged {
                let _ = ingress.discard(&pending.ingress_token);
            }
            return Err(AttachmentCommandError::ingress(error));
        }
        imported.push(projected);
    }
    Ok(imported)
}

/// 在同一次用户触发的 command 中打开原生多选 dialog；取消通过空路径列表进入正常成功分支。
fn pick_paths<R: Runtime>(
    app: &AppHandle<R>,
    callback: impl FnOnce(Result<Vec<PathBuf>, AttachmentCommandError>) + Send + 'static,
) {
    app.dialog().file().pick_files(move |selection| {
        let Some(selection) = selection else {
            callback(Ok(Vec::new()));
            return;
        };
        let paths = selection
            .into_iter()
            .map(|selected| {
                selected
                    .into_path()
                    .map_err(|_| AttachmentCommandError::callback_closed())
            })
            .collect();
        callback(paths);
    });
}

/// 统一等待 dialog callback 的 one-shot 终态；sender 丢失必须 reject，不能永久悬挂 invoke。
pub(crate) async fn await_callback<T>(
    receiver: tokio::sync::oneshot::Receiver<Result<T, AttachmentCommandError>>,
) -> Result<T, AttachmentCommandError> {
    receiver
        .await
        .map_err(|_| AttachmentCommandError::callback_closed())?
}

/// 单个异步 command 保持 dialog 的用户手势，并用 one-shot 确保每次调用只 resolve/reject 一次。
#[tauri::command]
pub(crate) async fn ja_attachment_import<R: Runtime>(
    app: AppHandle<R>,
    ingress: tauri::State<'_, Arc<AttachmentIngress>>,
    runtime: tauri::State<'_, RuntimeHost>,
) -> Result<Vec<AttachmentDto>, AttachmentCommandError> {
    let ingress = Arc::clone(ingress.inner());
    let host = runtime.inner().clone();
    let (sender, receiver) = tokio::sync::oneshot::channel();
    pick_paths(&app, move |selection| match selection {
        Ok(paths) if paths.is_empty() => {
            let _ = sender.send(Ok(Vec::new()));
        }
        Ok(paths) => {
            std::thread::spawn(move || {
                let _ = sender.send(import_selected(&host, ingress, paths));
            });
        }
        Err(error) => {
            let _ = sender.send(Err(error));
        }
    });
    await_callback(receiver).await
}

/// 用户移除草稿时调用真实 Java `attachment/discard`，不在 React 或 Rust 伪造本地成功状态。
#[tauri::command]
pub(crate) async fn ja_attachment_discard(
    attachment_id: String,
    runtime: tauri::State<'_, RuntimeHost>,
) -> Result<(), AttachmentCommandError> {
    let host = runtime.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        host.attachment_discard(AttachmentDiscardInput { attachment_id })
            .map_err(AttachmentCommandError::runtime)
    })
    .await
    .map_err(|_| AttachmentCommandError::callback_closed())?
}
