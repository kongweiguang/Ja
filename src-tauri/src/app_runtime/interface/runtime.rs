// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime、Turn 与 Approval 的 Tauri command 适配。

use super::dto::*;
use super::history_model::WorkspaceWireDto;
use crate::app_runtime::{RuntimeCommandError, RuntimeHost};
use std::time::Instant;

/// 把会等待 actor、文件或跨进程 I/O 的 Runtime 用例移出 Tauri command 执行线程。
/// 统一适配 Join 失败能避免 WebView 事件投影与同步 invoke 相互等待，同时不在每个 command
/// 复制线程策略或改变领域错误。
async fn run_blocking<T: Send + 'static>(
    operation: impl FnOnce() -> Result<T, RuntimeCommandError> + Send + 'static,
) -> Result<T, RuntimeCommandError> {
    tauri::async_runtime::spawn_blocking(operation)
        .await
        .map_err(|_| RuntimeCommandError::unavailable())?
}

/// 在唯一 composition 入口注册核心 command；具体命令只做边界适配，不持有业务状态。
pub fn register_commands<R: tauri::Runtime>(builder: tauri::Builder<R>) -> tauri::Builder<R> {
    builder.invoke_handler(ja_command_handler!([]))
}

/// 启动受信 sidecar，并在握手消费 ready token 后只返回脱敏状态投影。
#[tauri::command]
pub async fn ja_runtime_start(
    state: tauri::State<'_, RuntimeHost>,
) -> Result<RuntimeStatusDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.start().map(Into::into)).await
}

/// 请求有界停止；进程树清理由 Host/bridge owner 完成，command 不复制生命周期判断。
#[tauri::command]
pub async fn ja_runtime_stop(
    state: tauri::State<'_, RuntimeHost>,
) -> Result<RuntimeStatusDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.stop().map(Into::into)).await
}

/// 为 reload 或晚订阅返回权威快照，不从事件流推导当前状态。
#[tauri::command]
pub async fn ja_runtime_state(
    state: tauri::State<'_, RuntimeHost>,
) -> Result<RuntimeStatusDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.state().map(Into::into)).await
}

/// 返回稳定存储投影；该读操作不会隐式启动或重新配置 sidecar。
#[tauri::command]
pub fn ja_runtime_storage_info(state: tauri::State<'_, RuntimeHost>) -> RuntimeStorageInfoDto {
    state.storage_info().into()
}

/// 返回 Java-owned 通用 Workspace；无路径输入，避免 WebView 绕过 native capability。
#[tauri::command]
pub async fn ja_runtime_general_workspace(
    state: tauri::State<'_, RuntimeHost>,
) -> Result<GeneralWorkspaceDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.general_workspace().map(Into::into)).await
}

/// 启动类型化 Turn；executable、cwd 与握手值始终由受信 Host 决定。
#[tauri::command]
pub async fn ja_turn_start(
    input: TurnStartInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TurnAcceptedDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.turn_start(input.into()).map(Into::into)).await
}

/// 请求取消活动 Turn，但不停止 sidecar；最终完成事实仍来自 Java 事件。
#[tauri::command]
pub async fn ja_turn_cancel(
    input: TurnCancelInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TurnCancelResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.turn_cancel(input.into()).map(Into::into)).await
}

/// 将 steering 输入追加到 Java durable FIFO；Tool 边界由 Java 事务决定。
#[tauri::command]
pub async fn ja_turn_steer(
    input: TurnQueuedInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TurnQueuedInputResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.turn_steer(input.into()).map(Into::into)).await
}

/// 将 follow-up 输入排到活动 Turn 完成边界，不开放重排或删除队列能力。
#[tauri::command]
pub async fn ja_turn_follow_up(
    input: TurnQueuedInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TurnQueuedInputResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.turn_follow_up(input.into()).map(Into::into)).await
}

/// 读取 Java 持久化且已脱敏的 Tool artifact；workspaceId 只用于 native active binding 授权。
#[tauri::command]
pub async fn ja_tool_artifact_read(
    input: ToolArtifactReadInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ToolArtifactReadResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    let (workspace_id, input) = input.into_domain();
    run_blocking(move || {
        host.tool_artifact_read(&workspace_id, input)
            .map(Into::into)
    })
    .await
}

/// 返回不含 token、路径和进程细节的 native recovery gate。
#[tauri::command]
pub fn ja_runtime_recovery_state(state: tauri::State<'_, RuntimeHost>) -> RuntimeRecoveryStateDto {
    state.recovery_state().into()
}

/// 只确认当前 recovery identity/revision；调用方不能指定 marker 路径或进程标识。
#[tauri::command]
pub async fn ja_runtime_acknowledge_recovery(
    confirmation: ManualRecoveryConfirmationDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<RuntimeRecoveryStateDto, RuntimeCommandError> {
    let host = state.inner().clone();
    let confirmation = confirmation.into();
    run_blocking(move || host.acknowledge_recovery(&confirmation).map(Into::into)).await
}

/// 通过单个 application 用例确保 Java owner Ready 并注册受信 native cwd，command 不复制生命周期编排。
#[tauri::command]
pub async fn ja_runtime_workspace_open(
    input: WorkspaceOpenInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceWireDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.start_and_open_workspace(input.into()).map(Into::into)).await
}

/// 将类型化审批决定交给当前 pending Java request，不开放 generic server response。
#[tauri::command]
pub async fn ja_approval_respond(
    input: ApprovalResponseInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<(), RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.approval_respond(input.into())).await
}

/// 复用生产 shutdown 路径供 composition/MockRuntime 调用，避免测试出现第二套清理语义。
pub fn cleanup_on_exit(state: &RuntimeHost) -> Result<(), RuntimeCommandError> {
    state.shutdown()
}

/// 传递 composition 已创建的绝对 deadline，避免 bridge 重启一段独立退出预算。
pub fn cleanup_on_exit_until(
    state: &RuntimeHost,
    deadline: Instant,
) -> Result<(), RuntimeCommandError> {
    state.shutdown_until(deadline)
}
