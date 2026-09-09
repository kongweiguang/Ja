// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Child Thread 任务的 Tauri command 边界。

use super::dto::*;
use super::runtime::run_blocking;
use crate::app_runtime::{RuntimeCommandError, RuntimeHost};

/// 首次发送侧边任务时原子创建 Child Thread；空白草稿不会经过该 command。
#[tauri::command]
pub async fn ja_runtime_task_create(
    input: TaskCreateInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskCreateResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_create(input.into()).map(Into::into)).await
}

/// 读取一个根任务的有界后代投影，不物化 Child transcript。
#[tauri::command]
pub async fn ja_runtime_task_list(
    input: TaskListInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskListResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_list(input.into()).map(Into::into)).await
}

/// 分页读取任务 seed、低频 activity 与 mailbox；详细对话继续走 thread/read。
#[tauri::command]
pub async fn ja_runtime_task_read(
    input: TaskReadInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskReadResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_read(input.into()).map(Into::into)).await
}

/// 为当前详情实例建立 connection-scoped 高频观察，返回的 handle 必须显式释放。
#[tauri::command]
pub async fn ja_runtime_task_observe(
    input: TaskObserveInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskObserveResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_observe(input.into()).map(Into::into)).await
}

/// 幂等释放当前 connection 的观察句柄；sidecar generation 结束仍由 Java 自动兜底清理。
#[tauri::command]
pub async fn ja_runtime_task_unobserve(
    input: TaskUnobserveInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskAcceptedResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || {
        host.task_unobserve(input.into())
            .map(|()| TaskAcceptedResultDto::ok())
    })
    .await
}

/// 以 task revision CAS 推进持久已读边界，不依赖本地未读计数推断。
#[tauri::command]
pub async fn ja_runtime_task_seen(
    input: TaskSeenInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskMutationResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_seen(input.into()).map(Into::into)).await
}

/// QueueOnly 投递一条 Mailbox 消息；该 command 不启动空闲 Child Turn。
#[tauri::command]
pub async fn ja_runtime_task_message_send(
    input: TaskMessageInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskMessageResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_message_send(input.into()).map(Into::into)).await
}

/// 持久化 FOLLOW_UP 并让 Java 启动或排队新 Child Turn，Rust 不复制调度状态。
#[tauri::command]
pub async fn ja_runtime_task_followup(
    input: TaskFollowupInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskFollowupResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_followup(input.into()).map(Into::into)).await
}

/// 取消目标任务并由 Java 按 ATTACHED 规则递归传播；Rust 不遍历任务树。
#[tauri::command]
pub async fn ja_runtime_task_cancel(
    input: TaskMutationInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskMutationResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_cancel(input.into()).map(Into::into)).await
}

/// 仅通过重复确认 identity 的专用入口删除完整任务子树。
#[tauri::command]
pub async fn ja_runtime_task_tree_delete(
    input: TaskTreeDeleteInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TaskTreeDeleteResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    run_blocking(move || host.task_tree_delete(input.into()).map(Into::into)).await
}
