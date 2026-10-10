// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 编码工作流的固定原生命令；Java 拥有进程、审查与持久会话身份。

use crate::app_runtime::{RuntimeCommandError, RuntimeHost, WorkflowMethod};
use ja_runtime::app_server_process::{
    ProcessSessionListParams, ProcessSessionListResult, ProcessSessionReadParams,
    ProcessSessionSnapshot, ProcessSessionStopParams, ProcessSessionWriteParams, ReviewReadParams,
    ReviewReadResult, ReviewStartParams, ReviewStartResult, ThreadForkParams, ThreadForkResult,
};
use serde::{Serialize, de::DeserializeOwned};

/// 固定命令在返回 WebView 前同时核对响应形状与来源身份，迟到或错配结果不能污染当前会话。
async fn dispatch_coding<I, O>(
    host: RuntimeHost,
    method: WorkflowMethod,
    input: I,
    validate: fn(&O, &I) -> Result<(), &'static str>,
) -> Result<O, RuntimeCommandError>
where
    I: Serialize + Send + 'static,
    O: DeserializeOwned + Send + 'static,
{
    super::runtime::run_blocking(move || {
        let result = super::plans::request_workflow(&host, method, &input)?;
        validate(&result, &input).map_err(|_| RuntimeCommandError::unavailable())?;
        Ok(result)
    })
    .await
}

/// 首次查询不接受 generation；当前 owner 代际由 Java 返回，避免 Rust 连接代际冒充进程代际。
#[tauri::command]
pub async fn ja_process_list(
    input: ProcessSessionListParams,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ProcessSessionListResult, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    dispatch_coding(
        state.inner().clone(),
        WorkflowMethod::ProcessList,
        input,
        ProcessSessionListResult::validate_for,
    )
    .await
}

/// 读取只转交有界游标，所有权与代际校验保留在唯一进程注册表。
#[tauri::command]
pub async fn ja_process_read(
    input: ProcessSessionReadParams,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ProcessSessionSnapshot, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    dispatch_coding(
        state.inner().clone(),
        WorkflowMethod::ProcessRead,
        input,
        ProcessSessionSnapshot::validate_for_read,
    )
    .await
}

/// 原生层不重试 stdin 写入；Java 使用 operationKey 核对实际投递，歧义结果由用户恢复。
#[tauri::command]
pub async fn ja_process_write(
    input: ProcessSessionWriteParams,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ProcessSessionSnapshot, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    dispatch_coding(
        state.inner().clone(),
        WorkflowMethod::ProcessWrite,
        input,
        ProcessSessionSnapshot::validate_for_write,
    )
    .await
}

/// 停止 ACK 允许 stopping；界面随后读取权威退出状态，不能把受理当成资源已清理。
#[tauri::command]
pub async fn ja_process_stop(
    input: ProcessSessionStopParams,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ProcessSessionSnapshot, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    dispatch_coding(
        state.inner().clone(),
        WorkflowMethod::ProcessStop,
        input,
        ProcessSessionSnapshot::validate_for_stop,
    )
    .await
}

/// 启动仅引用已导入快照；renderer 不传递 Git 路径、报告事实或模型工具权限。
#[tauri::command]
pub async fn ja_ai_review_start(
    input: ReviewStartParams,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ReviewStartResult, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    dispatch_coding(
        state.inner().clone(),
        WorkflowMethod::ReviewStart,
        input,
        ReviewStartResult::validate_for,
    )
    .await
}

/// 报告始终按冻结 Turn 身份读取，不以当前 Git 内容替代历史审查证据。
#[tauri::command]
pub async fn ja_ai_review_read(
    input: ReviewReadParams,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ReviewReadResult, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    dispatch_coding(
        state.inner().clone(),
        WorkflowMethod::ReviewRead,
        input,
        ReviewReadResult::validate_for,
    )
    .await
}

/// 持久分叉由 Java 在 revision CAS 下冻结上下文，活动执行与审批留在来源会话。
#[tauri::command]
pub async fn ja_thread_fork(
    input: ThreadForkParams,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadForkResult, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    dispatch_coding(
        state.inner().clone(),
        WorkflowMethod::ThreadFork,
        input,
        ThreadForkResult::validate_for,
    )
    .await
}
