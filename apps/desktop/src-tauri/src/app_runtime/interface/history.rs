// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Workspace/Thread 历史命令的 Tauri DTO 适配。

use super::history_model::{
    AcceptedResult, HistoryMethod, MessageContentReadInput, MessageContentReadResult,
    OperationReadInput, OperationReadResult, ThreadCompactCancelInput, ThreadCompactInput,
    ThreadCompactResult, ThreadCreateInput, ThreadDiscoverInput, ThreadDiscoverResult, ThreadDto,
    ThreadListInput, ThreadListResult, ThreadMcpReadInput, ThreadMcpStatusResult,
    ThreadMutationInput, ThreadObservationInput, ThreadObservationResult, ThreadPinInput,
    ThreadPreferencesUpdateInput, ThreadReadInput, ThreadReadResult, ThreadRenameInput,
    ThreadSearchInput, ThreadUsageReadInput, ThreadUsageSummary, WorkspaceListInput,
    WorkspaceListResult, dispatch_compaction, dispatch_compaction_cancel, dispatch_mutation,
    dispatch_pin, dispatch_thread_lifecycle, parse_message_content_read, parse_operation_read,
    parse_thread, parse_thread_discovery, parse_thread_mcp_status, parse_thread_observation,
    parse_thread_page, parse_thread_read, parse_thread_usage_summary, parse_workspace_page,
    request_history, request_thread_discover, validate_message_content_read,
    validate_operation_read, validate_thread_create, validate_thread_discover,
    validate_thread_list, validate_thread_mcp_read, validate_thread_observation,
    validate_thread_preferences_update, validate_thread_read, validate_thread_rename,
    validate_thread_search, validate_thread_usage_read, validate_workspace_list,
};
use crate::app_runtime::{RuntimeCommandError, RuntimeHost};

/// 列出 Java-owned Workspace；分页输入先在 native 边界完成约束校验。
#[tauri::command]
pub fn ja_workspace_list(
    input: WorkspaceListInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceListResult, RuntimeCommandError> {
    validate_workspace_list(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::WorkspaceList,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_workspace_page(result)
}

/// 创建 Thread；Workspace/config 身份仍由 Java 用例解析，command 不派生 owner。
#[tauri::command]
pub fn ja_thread_create(
    input: ThreadCreateInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadDto, RuntimeCommandError> {
    validate_thread_create(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::ThreadCreate,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread(result)
}

/// 在一个 Workspace 内按 keyset cursor 列出 Thread，避免跨项目混页。
#[tauri::command]
pub fn ja_thread_list(
    input: ThreadListInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadListResult, RuntimeCommandError> {
    validate_thread_list(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::ThreadList,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_page(result)
}

/// 在当前 Ja 实例内发现可通信 Thread；只返回最小身份目录，不物化任何会话正文。
#[tauri::command]
pub fn ja_thread_discover(
    input: ThreadDiscoverInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadDiscoverResult, RuntimeCommandError> {
    validate_thread_discover(&input)?;
    let result = request_thread_discover(
        &state,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_discovery(result)
}

/// 仅在指定 Workspace 内搜索标题；空查询返回最近 Thread，排序与分页由 Java/SQLite 拥有。
#[tauri::command]
pub fn ja_thread_search(
    input: ThreadSearchInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadListResult, RuntimeCommandError> {
    validate_thread_search(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::ThreadSearch,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_page(result)
}

/// 读取有界权威快照；不请求或重放 event journal。
#[tauri::command]
pub fn ja_thread_read(
    input: ThreadReadInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadReadResult, RuntimeCommandError> {
    validate_thread_read(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::ThreadRead,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_read(result)
}

/// 已提交 Assistant 正文按消息身份分页；Rust 复核每一页的字符进度后才交给 WebView。
#[tauri::command]
pub fn ja_thread_message_content_read(
    input: MessageContentReadInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<MessageContentReadResult, RuntimeCommandError> {
    validate_message_content_read(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::MessageContentRead,
        serde_json::to_value(&input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_message_content_read(result, &input)
}

/// 先获得连接级观察 ACK，再由调用方读取权威快照；切换期间的事件由服务端排队。
#[tauri::command]
pub fn ja_thread_observe(
    input: ThreadObservationInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadObservationResult, RuntimeCommandError> {
    validate_thread_observation(&input)?;
    let expected_thread_id = input.thread_id.clone();
    let result = request_history(
        &state,
        HistoryMethod::ThreadObserve,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_observation(result, &expected_thread_id)
}

/// 待核实状态只回读服务端同事务回执；unknown 明确禁止前端自动重发原副作用。
#[tauri::command]
pub fn ja_operation_read(
    input: OperationReadInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<OperationReadResult, RuntimeCommandError> {
    validate_operation_read(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::OperationRead,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_operation_read(result)
}

/// 释放隐藏或关闭 Thread 的连接级订阅，后台 Turn 本身继续运行。
#[tauri::command]
pub fn ja_thread_unobserve(
    input: ThreadObservationInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadObservationResult, RuntimeCommandError> {
    validate_thread_observation(&input)?;
    let expected_thread_id = input.thread_id.clone();
    let result = request_history(
        &state,
        HistoryMethod::ThreadUnobserve,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_observation(result, &expected_thread_id)
}

/// 读取只含覆盖范围和累计值的持久计量，不加载 Timeline 正文，也不参与用户界面渲染。
#[tauri::command]
pub fn ja_thread_usage_read(
    input: ThreadUsageReadInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadUsageSummary, RuntimeCommandError> {
    validate_thread_usage_read(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::ThreadUsageRead,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_usage_summary(result)
}

/// 读取指定 Thread 的脱敏 MCP 状态，不连接或探测服务器，避免旁路读取改变会话运行状态。
#[tauri::command]
pub fn ja_thread_mcp_read(
    input: ThreadMcpReadInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadMcpStatusResult, RuntimeCommandError> {
    validate_thread_mcp_read(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::ThreadMcpRead,
        serde_json::to_value(&input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_mcp_status(result, &input.thread_id)
}

/// 通过 revision CAS 设置人工标题；Java 同一事务内维护 titleSource，Rust 不推导竞争结果。
#[tauri::command]
pub fn ja_thread_rename(
    input: ThreadRenameInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadDto, RuntimeCommandError> {
    validate_thread_rename(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::ThreadRename,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread(result)
}

/// 整体替换 Thread 偏好；在途 Provider 请求不变，同一 Turn 的下一请求读取新值。
#[tauri::command]
pub fn ja_thread_preferences_update(
    input: ThreadPreferencesUpdateInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadDto, RuntimeCommandError> {
    validate_thread_preferences_update(&input)?;
    let result = request_history(
        &state,
        HistoryMethod::ThreadPreferencesUpdate,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread(result)
}

/// 在阻塞 actor/stdio 路径之外等待 Java 手动压缩的结果或连接关闭；command 不解释 Profile、
/// Summary 或 Checkpoint，且 WebView 不能传入 timeout 或 Provider 参数。
#[tauri::command]
pub async fn ja_thread_compact(
    input: ThreadCompactInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadCompactResult, RuntimeCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || dispatch_compaction(input, &host))
        .await
        .map_err(|_| RuntimeCommandError::unavailable())?
}

/// 停止本连接同一 Thread 正在运行的压缩，不等待慢摘要线程退出才向界面确认意图。
#[tauri::command]
pub async fn ja_thread_compact_cancel(
    input: ThreadCompactCancelInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<AcceptedResult, RuntimeCommandError> {
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || dispatch_compaction_cancel(input, &host))
        .await
        .map_err(|_| RuntimeCommandError::unavailable())?
}

/// 通过 expected revision CAS 归档 idle Thread，失败语义由 Java 事务决定。
#[tauri::command]
pub fn ja_thread_archive(
    input: ThreadMutationInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadDto, RuntimeCommandError> {
    dispatch_thread_lifecycle(input, &state, HistoryMethod::ThreadArchive)
}

/// 通过显式目标值和 revision CAS 更新置顶，返回权威 Thread 供 UI 重排。
#[tauri::command]
pub fn ja_thread_pin(
    input: ThreadPinInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadDto, RuntimeCommandError> {
    dispatch_pin(input, &state)
}

/// 将当前最新 Turn 标记为已查看；Java 以 revision CAS 推进持久边界，Rust 不在本地伪造已读状态。
#[tauri::command]
pub fn ja_thread_seen(
    input: ThreadMutationInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadDto, RuntimeCommandError> {
    dispatch_thread_lifecycle(input, &state, HistoryMethod::ThreadSeen)
}

/// 恢复归档 Thread；服务端强制清除置顶并返回完整 active 投影。
#[tauri::command]
pub fn ja_thread_restore(
    input: ThreadMutationInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ThreadDto, RuntimeCommandError> {
    dispatch_thread_lifecycle(input, &state, HistoryMethod::ThreadRestore)
}

/// 通过 expected revision CAS 逻辑删除 Thread，不在 command 复制生命周期规则。
#[tauri::command]
pub fn ja_thread_delete(
    input: ThreadMutationInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<AcceptedResult, RuntimeCommandError> {
    dispatch_mutation(input, &state, HistoryMethod::ThreadDelete)
}
