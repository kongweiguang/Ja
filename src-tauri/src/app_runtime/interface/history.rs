// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Workspace/Thread 历史命令的 Tauri DTO 适配。

use super::history_model::{
    AcceptedResult, HistoryMethod, PageInput, ThreadCompactInput, ThreadCompactResult,
    ThreadCreateInput, ThreadDiscoverInput, ThreadDiscoverResult, ThreadDto, ThreadListInput,
    ThreadListResult, ThreadMutationInput, ThreadPinInput, ThreadPreferencesUpdateInput,
    ThreadReadInput, ThreadReadResult, ThreadRenameInput, ThreadSearchInput, WorkspaceListResult,
    dispatch_compaction, dispatch_mutation, dispatch_pin, dispatch_thread_lifecycle, parse_thread,
    parse_thread_discovery, parse_thread_page, parse_thread_read, parse_workspace_page,
    request_history, request_thread_discover, validate_page, validate_thread_create,
    validate_thread_discover, validate_thread_list, validate_thread_preferences_update,
    validate_thread_read, validate_thread_rename, validate_thread_search,
};
use crate::app_runtime::{RuntimeCommandError, RuntimeHost};

/// 列出 Java-owned Workspace；分页输入先在 native 边界完成约束校验。
#[tauri::command]
pub fn ja_workspace_list(
    input: PageInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<WorkspaceListResult, RuntimeCommandError> {
    validate_page(&input)?;
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

/// 在阻塞 actor/stdio 路径之外等待最长五分钟的 Java 手动压缩；command 不解释 Profile、
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
