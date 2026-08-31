// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 冻结 JA-RPC v2 history 方法的 typed、有界 Tauri adapter。

use crate::app_runtime::{
    HistoryRequest, HistoryResponse, RuntimeCommandError, RuntimeHost, ThreadArchiveParams,
    ThreadCompactParams, ThreadCreateParams, ThreadDeleteParams, ThreadListParams,
    ThreadPreferencesUpdateParams, ThreadReadParams, ThreadRenameParams, ThreadSearchParams,
    WorkspaceListParams,
};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

const MAX_CURSOR: usize = 256;
const MAX_PAGE: u32 = 200;
const MAX_TITLE: usize = 512;
const MAX_ITEM_BYTES: usize = 4 * 1024 * 1024;
const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;

/// bridge actor 允许准入的 history 方法闭集。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum HistoryMethod {
    WorkspaceList,
    ThreadCreate,
    ThreadList,
    ThreadSearch,
    ThreadRead,
    ThreadRename,
    ThreadPreferencesUpdate,
    ThreadCompact,
    ThreadArchive,
    ThreadDelete,
}

/// 在 History interface 统一完成 JSON 与 application 结构树之间的反腐转换；这样 command、
/// mutation 与测试 Harness 都经过相同的深度、节点和文本预算，不会形成协议旁路。
pub(crate) fn request_history(
    state: &RuntimeHost,
    method: HistoryMethod,
    params: Value,
) -> Result<Value, RuntimeCommandError> {
    let bytes = serde_json::to_vec(&params).map_err(|_| RuntimeCommandError::invalid_params())?;
    // 每个方法构造不同 nominal params，method 与 payload 无法在 application 中自由组合。
    let request = match method {
        HistoryMethod::WorkspaceList => {
            HistoryRequest::WorkspaceList(WorkspaceListParams::try_new(bytes)?)
        }
        HistoryMethod::ThreadCreate => {
            HistoryRequest::ThreadCreate(ThreadCreateParams::try_new(bytes)?)
        }
        HistoryMethod::ThreadList => HistoryRequest::ThreadList(ThreadListParams::try_new(bytes)?),
        HistoryMethod::ThreadSearch => {
            HistoryRequest::ThreadSearch(ThreadSearchParams::try_new(bytes)?)
        }
        HistoryMethod::ThreadRead => HistoryRequest::ThreadRead(ThreadReadParams::try_new(bytes)?),
        HistoryMethod::ThreadRename => {
            HistoryRequest::ThreadRename(ThreadRenameParams::try_new(bytes)?)
        }
        HistoryMethod::ThreadPreferencesUpdate => {
            HistoryRequest::ThreadPreferencesUpdate(ThreadPreferencesUpdateParams::try_new(bytes)?)
        }
        HistoryMethod::ThreadCompact => {
            HistoryRequest::ThreadCompact(ThreadCompactParams::try_new(bytes)?)
        }
        HistoryMethod::ThreadArchive => {
            HistoryRequest::ThreadArchive(ThreadArchiveParams::try_new(bytes)?)
        }
        HistoryMethod::ThreadDelete => {
            HistoryRequest::ThreadDelete(ThreadDeleteParams::try_new(bytes)?)
        }
    };
    let response = state.history_request(request)?;
    // variant 必须与发出的 method 精确配对，任何错配都视为 infrastructure 故障而非尝试解析。
    let bytes = match (method, response) {
        (HistoryMethod::WorkspaceList, HistoryResponse::WorkspaceList(value)) => value.into_bytes(),
        (HistoryMethod::ThreadCreate, HistoryResponse::ThreadCreate(value)) => value.into_bytes(),
        (HistoryMethod::ThreadList, HistoryResponse::ThreadList(value)) => value.into_bytes(),
        (HistoryMethod::ThreadSearch, HistoryResponse::ThreadSearch(value)) => value.into_bytes(),
        (HistoryMethod::ThreadRead, HistoryResponse::ThreadRead(value)) => value.into_bytes(),
        (HistoryMethod::ThreadRename, HistoryResponse::ThreadRename(value)) => value.into_bytes(),
        (
            HistoryMethod::ThreadPreferencesUpdate,
            HistoryResponse::ThreadPreferencesUpdate(value),
        ) => value.into_bytes(),
        (HistoryMethod::ThreadCompact, HistoryResponse::ThreadCompact(value)) => value.into_bytes(),
        (HistoryMethod::ThreadArchive, HistoryResponse::ThreadArchive(value)) => value.into_bytes(),
        (HistoryMethod::ThreadDelete, HistoryResponse::ThreadDelete(value)) => value.into_bytes(),
        _ => return Err(history_response_rejected("history_variant")),
    };
    serde_json::from_slice(&bytes).map_err(|_| history_response_rejected("history_json"))
}

/// 共享 keyset page 输入；显式 null 与 unknown field 在 serde 边界关闭失败。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PageInput {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
}

/// Thread page 始终限定到一个 Java-issued Workspace identity；其它 Workspace 消耗有界首页后，
/// global page 无法再提供完整的逐项目 history。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadListInput {
    pub workspace_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
}

/// 搜索只限定当前 Workspace 的标题；空 query 由 Java 解释为最近会话，Rust 不扩展到正文或文件。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadSearchInput {
    pub workspace_id: String,
    pub query: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
}

/// 只提交 cwd/title 与 v4 模型偏好创建 Thread；连接、凭据与配置代际仍由 Java 持有。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadCreateInput {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    pub title: String,
    pub provider_id: String,
    pub model_id: String,
    pub reasoning_level: Option<String>,
    pub access_mode: String,
}

/// 读取一页权威 Thread snapshot，不重放 event journal。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadReadInput {
    pub thread_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
}

/// 人工标题更新独立于 lifecycle mutation，避免 UI 通过通用 patch 修改服务端拥有的其它元数据。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadRenameInput {
    pub thread_id: String,
    pub title: String,
    pub expected_thread_revision: u64,
}

/// 下一轮偏好必须整体替换并使用 Thread revision CAS，防止 provider/model/reasoning 产生混合代际。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadPreferencesUpdateInput {
    pub thread_id: String,
    pub provider_id: String,
    pub model_id: String,
    pub reasoning_level: Option<String>,
    pub access_mode: String,
    pub expected_thread_revision: u64,
}

/// 执行一次 Thread lifecycle CAS，不开放 generic mutation tunnel。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadMutationInput {
    pub thread_id: String,
    pub expected_thread_revision: u64,
}

/// 手动压缩使用独立输入类型，避免与 archive/delete 共享可扩展的 lifecycle mutation tunnel。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadCompactInput {
    pub thread_id: String,
    pub expected_thread_revision: u64,
}

/// Workspace keyset page；continuation cursor 字段必需但允许 null。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceListResult {
    pub items: Vec<WorkspaceWireDto>,
    pub next_cursor: Option<String>,
}

/// create 与 list 共用的冻结 Thread metadata。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadDto {
    pub thread_id: String,
    pub workspace_id: String,
    pub preferences: Option<ThreadPreferencesDto>,
    pub title: String,
    pub status: String,
    pub revision: u64,
    pub created_at: String,
    pub updated_at: String,
}

/// Thread 下一轮偏好只包含稳定选择器和公开执行模式，不包含 Provider 连接或 Secret。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadPreferencesDto {
    pub provider_id: String,
    pub model_id: String,
    pub reasoning_level: Option<String>,
    pub access_mode: String,
    pub title_source: String,
}

/// Thread keyset page，不携带 active-profile 或 active-workspace replay data。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadListResult {
    pub items: Vec<ThreadDto>,
    pub next_cursor: Option<String>,
}

/// 权威 snapshot page；item union 仍由协议持有，但在 WebView serialization 前受有界校验。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadReadResult {
    pub thread_id: String,
    pub revision: u64,
    pub turns: Vec<ThreadSnapshotTurnDto>,
    pub items: Vec<Value>,
    pub context_usage: Option<ThreadContextUsageDto>,
    pub next_cursor: Option<String>,
}

/// 最近一次已提交模型轮次的精确 Provider Usage；`turn_id` 绑定冻结 runtime，避免模型切换后误用。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadContextUsageDto {
    pub turn_id: String,
    pub model_round: u32,
    pub input_tokens: u64,
    pub output_tokens: u64,
    pub total_tokens: u64,
    pub measured_at: String,
}

/// 历史 Turn 只公开冻结的非敏感路由事实与稳定失败码。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadSnapshotTurnDto {
    pub turn_id: String,
    pub status: String,
    pub runtime: Option<ThreadRuntimeSnapshotDto>,
    pub requested_at: String,
    pub updated_at: String,
    pub completed_at: Option<String>,
    pub change_set: Option<TurnChangeSetDto>,
    pub error_code: Option<String>,
}

/// Turn admission 快照排除 URL 与 Secret，且 Provider API 维持当前两类闭集。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadRuntimeSnapshotDto {
    pub provider_id: String,
    pub model_id: String,
    pub provider: String,
    pub api: String,
    pub upstream_model: String,
    pub reasoning_level: Option<String>,
    pub access_mode: String,
    pub config_generation: String,
}

/// V4 历史只保存冻结 change-set 摘要；精确 diff 内容必须通过受身份约束的 artifact reader 读取。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TurnChangeSetDto {
    pub state: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    pub files: Vec<TurnChangeFileDto>,
    pub stats: TurnChangeStatsDto,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artifact_id: Option<String>,
}

/// 单文件变化保持相对路径和可证明的行统计；binary 文件不接受伪造的文本行数。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TurnChangeFileDto {
    pub path: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub old_path: Option<String>,
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub additions: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub deletions: Option<u64>,
    pub binary: bool,
    pub truncated: bool,
}

/// 聚合统计与冻结文件列表绑定；unavailable 快照必须保持全零且非 truncated。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TurnChangeStatsDto {
    pub files: u64,
    pub additions: u64,
    pub deletions: u64,
    pub binary_files: u64,
    pub truncated: bool,
}

/// archive/delete CAS operation 的稳定结果。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AcceptedResult {
    pub accepted: bool,
}

/// Java-owned 上下文压缩结果；nullable identity 字段始终序列化，消除 unchanged 的缺失/null 歧义。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadCompactResult {
    pub outcome: String,
    pub compaction_id: Option<String>,
    pub checkpoint_id: Option<String>,
    pub thread_revision: u64,
    pub input_tokens_before: u64,
    pub input_tokens_after: u64,
}

/// 将严格手动压缩 DTO 路由到 Java；Rust 只校验 wire 不变量，不推导压缩策略或 Checkpoint。
pub(super) fn dispatch_compaction(
    input: ThreadCompactInput,
    state: &RuntimeHost,
) -> Result<ThreadCompactResult, RuntimeCommandError> {
    validate_prefixed(&input.thread_id, "thr_", 100)?;
    if input.expected_thread_revision > MAX_SAFE_INTEGER {
        return Err(RuntimeCommandError::invalid_params());
    }
    let result = request_history(
        state,
        HistoryMethod::ThreadCompact,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    parse_thread_compact(result)
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 两个 lifecycle CAS command 共享严格验证与结果解析，不引入 renderer 可选的方法字符串。
pub(super) fn dispatch_mutation(
    input: ThreadMutationInput,
    state: &RuntimeHost,
    method: HistoryMethod,
) -> Result<AcceptedResult, RuntimeCommandError> {
    validate_prefixed(&input.thread_id, "thr_", 100)?;
    if input.expected_thread_revision > MAX_SAFE_INTEGER {
        return Err(RuntimeCommandError::invalid_params());
    }
    let result = request_history(
        state,
        method,
        serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    let accepted: AcceptedResult = parse_exact(result, &["accepted"])?;
    if !accepted.accepted {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(accepted)
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 请求到达 actor 前应用公共 v2 keyset 上限。
pub(super) fn validate_page(input: &PageInput) -> Result<(), RuntimeCommandError> {
    validate_pagination(&input.cursor, input.limit)
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// Thread metadata 到达 actor 前校验 Workspace identity 与共享分页上限。
pub(crate) fn validate_thread_list(input: &ThreadListInput) -> Result<(), RuntimeCommandError> {
    validate_prefixed(&input.workspace_id, "ws_", 100)?;
    validate_pagination(&input.cursor, input.limit)
}

/// 搜索沿用 Thread page 的 Workspace/cursor 约束，并把 query 限制为有界单行标题片段。
pub(crate) fn validate_thread_search(input: &ThreadSearchInput) -> Result<(), RuntimeCommandError> {
    validate_prefixed(&input.workspace_id, "ws_", 100)?;
    validate_pagination(&input.cursor, input.limit)?;
    if input.query.len() > 256 || input.query.chars().any(char::is_control) {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 集中 cursor 与 limit 验证，防止 Workspace page 与 Thread page 规则漂移。
fn validate_pagination(
    cursor: &Option<String>,
    limit: Option<u32>,
) -> Result<(), RuntimeCommandError> {
    if cursor.as_ref().is_some_and(|cursor| {
        cursor.is_empty() || cursor.len() > MAX_CURSOR || cursor.chars().any(char::is_control)
    }) || limit.is_some_and(|limit| !(1..=MAX_PAGE).contains(&limit))
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 按冻结公共 grammar 验证每个必需 create field。
pub(crate) fn validate_thread_create(input: &ThreadCreateInput) -> Result<(), RuntimeCommandError> {
    if let Some(cwd) = input.cwd.as_deref()
        && (cwd.is_empty() || cwd.len() > 4096 || cwd.chars().any(char::is_control))
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    validate_runtime_preferences(
        &input.provider_id,
        &input.model_id,
        input.reasoning_level.as_deref(),
        &input.access_mode,
    )?;
    validate_title(&input.title)?;
    Ok(())
}

/// 人工重命名只允许单行有界标题和 JavaScript 安全 revision，标题来源由 Java 原子更新为 manual。
pub(crate) fn validate_thread_rename(input: &ThreadRenameInput) -> Result<(), RuntimeCommandError> {
    validate_prefixed(&input.thread_id, "thr_", 100)?;
    validate_title(&input.title)?;
    validate_revision(input.expected_thread_revision)
}

/// 偏好更新与 Thread 创建共享同一 v4 选择器语法，但额外要求精确 Thread revision CAS。
pub(crate) fn validate_thread_preferences_update(
    input: &ThreadPreferencesUpdateInput,
) -> Result<(), RuntimeCommandError> {
    validate_prefixed(&input.thread_id, "thr_", 100)?;
    validate_runtime_preferences(
        &input.provider_id,
        &input.model_id,
        input.reasoning_level.as_deref(),
        &input.access_mode,
    )?;
    validate_revision(input.expected_thread_revision)
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 只接受 cursor pagination，从而拒绝旧 view/afterSeq replay field。
pub(crate) fn validate_thread_read(input: &ThreadReadInput) -> Result<(), RuntimeCommandError> {
    validate_prefixed(&input.thread_id, "thr_", 100)?;
    validate_page(&PageInput {
        cursor: input.cursor.clone(),
        limit: input.limit,
    })
}

/// 解析并限制严格的 Workspace page，旧列表键会在反序列化前被拒绝。
pub(crate) fn parse_workspace_page(
    value: Value,
) -> Result<WorkspaceListResult, RuntimeCommandError> {
    let page: WorkspaceListResult = parse_exact(value, &["items", "nextCursor"])?;
    if page.items.len() > MAX_PAGE as usize
        || page.items.iter().any(|workspace| {
            validate_prefixed(&workspace.workspace_id, "ws_", 99).is_err()
                || workspace.root.is_empty()
                || workspace.root.len() > 4096
                || workspace.display_name.is_empty()
                || workspace.display_name.len() > MAX_TITLE
                || !matches!(workspace.trust.as_str(), "trusted" | "untrusted")
                || workspace.revision > MAX_SAFE_INTEGER
        })
    {
        return Err(RuntimeCommandError::unavailable());
    }
    validate_cursor(&page.next_cursor)?;
    Ok(page)
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 解析一个精确 Thread 投影，并复用 page 的同一套验证。
pub(crate) fn parse_thread(value: Value) -> Result<ThreadDto, RuntimeCommandError> {
    let thread: ThreadDto = parse_exact(
        value,
        &[
            "threadId",
            "workspaceId",
            "preferences",
            "title",
            "status",
            "revision",
            "createdAt",
            "updatedAt",
        ],
    )?;
    validate_thread(&thread)?;
    Ok(thread)
}

/// 解析有界 Thread page，不接受旧列表键或遗留 active-turn 字段。
pub(crate) fn parse_thread_page(value: Value) -> Result<ThreadListResult, RuntimeCommandError> {
    let page: ThreadListResult = parse_exact(value, &["items", "nextCursor"])?;
    if page.items.len() > MAX_PAGE as usize {
        return Err(RuntimeCommandError::unavailable());
    }
    page.items.iter().try_for_each(validate_thread)?;
    validate_cursor(&page.next_cursor)?;
    Ok(page)
}

/// 解析权威 V4 snapshot；先检查嵌套判别联合与必需 nullable 字段，再反序列化 typed DTO。
/// 这样无计量时显式 `contextUsage:null` 可恢复，而缺失 `runtime/changeSet/completedAt/errorCode` 不会被
/// `Option` 静默当成同一种语义；失败日志只记录稳定阶段，不包含正文或 raw payload。
pub(crate) fn parse_thread_read(value: Value) -> Result<ThreadReadResult, RuntimeCommandError> {
    let object = value
        .as_object()
        .filter(|object| {
            exact_keys(
                object,
                &[
                    "threadId",
                    "revision",
                    "turns",
                    "items",
                    "contextUsage",
                    "nextCursor",
                ],
            )
        })
        .ok_or_else(|| history_response_rejected("thread_read_root"))?;
    let turns = object
        .get("turns")
        .and_then(Value::as_array)
        .ok_or_else(|| history_response_rejected("thread_read_turns_shape"))?;
    if turns.len() > MAX_PAGE as usize || !turns.iter().all(valid_snapshot_turn_wire) {
        return Err(history_response_rejected("thread_read_turns_shape"));
    }
    let items = object
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(|| history_response_rejected("thread_read_items_shape"))?;
    if items.len() > MAX_PAGE as usize || !items.iter().all(valid_snapshot_item_wire) {
        return Err(history_response_rejected("thread_read_items_shape"));
    }
    if !object
        .get("contextUsage")
        .is_some_and(|usage| usage.is_null() || valid_context_usage_wire(usage))
    {
        return Err(history_response_rejected("thread_read_context_usage_shape"));
    }
    let result: ThreadReadResult = serde_json::from_value(value)
        .map_err(|_| history_response_rejected("thread_read_decode"))?;
    validate_prefixed(&result.thread_id, "thr_", 100)
        .map_err(|_| history_response_rejected("thread_read_identity"))?;
    if result.revision > MAX_SAFE_INTEGER
        || result.turns.len() > MAX_PAGE as usize
        || result.items.len() > MAX_PAGE as usize
    {
        return Err(history_response_rejected("thread_read_limits"));
    }
    result
        .turns
        .iter()
        .try_for_each(validate_snapshot_turn)
        .map_err(|_| history_response_rejected("thread_read_turn_semantics"))?;
    if let Some(usage) = &result.context_usage {
        validate_context_usage(usage, &result.turns)
            .map_err(|_| history_response_rejected("thread_read_context_usage_semantics"))?;
    }
    let encoded = serde_json::to_vec(&result.items)
        .map_err(|_| history_response_rejected("thread_read_encode"))?;
    if encoded.len() > MAX_ITEM_BYTES
        || result
            .items
            .iter()
            .any(|item| !item.is_object() || contains_private_field(item))
    {
        return Err(history_response_rejected("thread_read_item_budget"));
    }
    validate_cursor(&result.next_cursor)
        .map_err(|_| history_response_rejected("thread_read_cursor"))?;
    Ok(result)
}

/// V4 Turn 的四个 nullable 字段在 wire 上必须存在；`null` 表示历史事实未知/不存在，缺失则
/// 表示协议漂移。change-set 对象继续进入其专用闭集校验。
fn valid_snapshot_turn_wire(value: &Value) -> bool {
    value.as_object().is_some_and(|turn| {
        exact_keys(
            turn,
            &[
                "turnId",
                "status",
                "runtime",
                "requestedAt",
                "updatedAt",
                "completedAt",
                "changeSet",
                "errorCode",
            ],
        ) && turn
            .get("runtime")
            .is_some_and(|runtime| runtime.is_null() || valid_runtime_snapshot_wire(runtime))
            && turn
                .get("completedAt")
                .is_some_and(|completed| completed.is_null() || valid_timestamp_value(completed))
            && turn.get("errorCode").is_some_and(|error| {
                error.is_null() || error.as_str().is_some_and(valid_error_code)
            })
            && turn
                .get("changeSet")
                .is_some_and(|change_set| change_set.is_null() || valid_change_set_wire(change_set))
    })
}

/// Runtime snapshot 的 nullable reasoningLevel 必须显式存在，防止缺列与模型默认值混为一谈。
fn valid_runtime_snapshot_wire(value: &Value) -> bool {
    value.as_object().is_some_and(|runtime| {
        exact_keys(
            runtime,
            &[
                "providerId",
                "modelId",
                "provider",
                "api",
                "upstreamModel",
                "reasoningLevel",
                "accessMode",
                "configGeneration",
            ],
        ) && runtime
            .get("reasoningLevel")
            .is_some_and(|level| level.is_null() || level.as_str().is_some_and(is_reasoning_level))
    })
}

/// Usage wire 是封闭对象且数值必须能被 JavaScript 精确表达；加法使用 checked_add 防止溢出绕过。
fn valid_context_usage_wire(value: &Value) -> bool {
    let Some(usage) = value.as_object() else {
        return false;
    };
    if !exact_keys(
        usage,
        &[
            "turnId",
            "modelRound",
            "inputTokens",
            "outputTokens",
            "totalTokens",
            "measuredAt",
        ],
    ) || !validate_prefixed_value(usage.get("turnId"), "turn_", 128)
        || !integer_in_range(usage.get("modelRound"), 1, 128)
        || !integer_in_range(usage.get("inputTokens"), 0, MAX_SAFE_INTEGER)
        || !integer_in_range(usage.get("outputTokens"), 0, MAX_SAFE_INTEGER)
        || !integer_in_range(usage.get("totalTokens"), 0, MAX_SAFE_INTEGER)
        || !usage.get("measuredAt").is_some_and(valid_timestamp_value)
    {
        return false;
    }
    usage
        .get("inputTokens")
        .and_then(Value::as_u64)
        .zip(usage.get("outputTokens").and_then(Value::as_u64))
        .and_then(|(input, output)| input.checked_add(output))
        .zip(usage.get("totalTokens").and_then(Value::as_u64))
        .is_some_and(|(minimum, total)| total >= minimum)
}

/// V4 item 是封闭判别联合；按 kind 检查精确 key，避免 serde `Value` 接受残缺 Tool、审批或
///附件记录，同时只验证安全展示 DTO，不允许 raw arguments/result 回到 WebView。
fn valid_snapshot_item_wire(value: &Value) -> bool {
    let Some(item) = value.as_object() else {
        return false;
    };
    if !validate_prefixed_value(item.get("itemId"), "item_", 101)
        || !validate_prefixed_value(item.get("turnId"), "turn_", 128)
        || !item.get("createdAt").is_some_and(valid_timestamp_value)
    {
        return false;
    }
    match item.get("kind").and_then(Value::as_str) {
        Some("user_input" | "final_answer") => {
            exact_keys(item, &["itemId", "createdAt", "turnId", "kind", "text"])
                && item
                    .get("text")
                    .is_some_and(|text| valid_text(text, 1_048_576))
        }
        Some("assistant_progress" | "reasoning_summary") => {
            exact_keys(
                item,
                &[
                    "itemId",
                    "createdAt",
                    "turnId",
                    "kind",
                    "text",
                    "modelRound",
                ],
            ) && item
                .get("text")
                .is_some_and(|text| valid_text(text, 1_048_576))
                && integer_in_range(item.get("modelRound"), 1, 128)
        }
        Some("tool_call") => {
            exact_keys(
                item,
                &[
                    "itemId",
                    "createdAt",
                    "turnId",
                    "kind",
                    "callId",
                    "toolName",
                    "ordinal",
                    "presentation",
                ],
            ) && validate_prefixed_value(item.get("callId"), "call_", 101)
                && item.get("toolName").is_some_and(valid_identifier_value)
                && integer_in_range(item.get("ordinal"), 0, 1_023)
                && item
                    .get("presentation")
                    .is_some_and(valid_tool_presentation_wire)
        }
        Some("approval") => {
            exact_keys(
                item,
                &[
                    "itemId",
                    "createdAt",
                    "turnId",
                    "kind",
                    "approvalId",
                    "callId",
                    "toolName",
                    "reason",
                    "expiresAt",
                    "decision",
                ],
            ) && validate_prefixed_value(item.get("approvalId"), "appr_", 101)
                && validate_prefixed_value(item.get("callId"), "call_", 101)
                && item.get("toolName").is_some_and(valid_identifier_value)
                && item
                    .get("reason")
                    .is_some_and(|value| valid_text(value, 2_048))
                && item.get("expiresAt").is_some_and(valid_timestamp_value)
                && item.get("decision").is_some_and(|decision| {
                    decision.is_null()
                        || decision
                            .as_str()
                            .is_some_and(|value| matches!(value, "approve" | "deny"))
                })
        }
        Some("attachment") => {
            exact_keys(
                item,
                &[
                    "itemId",
                    "createdAt",
                    "turnId",
                    "kind",
                    "attachmentId",
                    "displayName",
                    "sizeBytes",
                    "mediaKind",
                    "mediaType",
                    "state",
                ],
            ) && validate_prefixed_value(item.get("attachmentId"), "att_", 101)
                && item.get("displayName").is_some_and(valid_safe_name)
                && integer_in_range(item.get("sizeBytes"), 0, 104_857_600)
                && item
                    .get("mediaKind")
                    .and_then(Value::as_str)
                    .is_some_and(|kind| matches!(kind, "text" | "image" | "pdf" | "binary"))
                && item.get("mediaType").is_some_and(|media_type| {
                    media_type.as_str().is_some_and(|value| {
                        (3..=128).contains(&value.chars().count())
                            && !value.chars().any(char::is_control)
                    })
                })
                && item
                    .get("state")
                    .and_then(Value::as_str)
                    .is_some_and(|state| {
                        matches!(state, "draft" | "bound" | "discarded" | "expired")
                    })
        }
        _ => false,
    }
}

/// ToolPresentation 只接受 Java 已脱敏的闭集字段、相对路径和有界预览；optional 字段缺失
/// 合法，但显式 null 不作为第二种缺失语义。
fn valid_tool_presentation_wire(value: &Value) -> bool {
    let Some(presentation) = value.as_object() else {
        return false;
    };
    if !exact_keys_allowing(
        presentation,
        &["kind", "title", "status", "relativePaths", "truncated"],
        &[
            "inputPreview",
            "outputPreview",
            "command",
            "relativeCwd",
            "stdout",
            "stderr",
            "exitCode",
            "durationMs",
            "artifactId",
        ],
    ) || !presentation
        .get("kind")
        .and_then(Value::as_str)
        .is_some_and(|kind| matches!(kind, "read" | "edit" | "write" | "shell" | "mcp"))
        || !presentation.get("title").is_some_and(valid_safe_name)
        || !presentation
            .get("status")
            .and_then(Value::as_str)
            .is_some_and(|status| {
                matches!(
                    status,
                    "pending" | "running" | "waiting_approval" | "success" | "error" | "cancelled"
                )
            })
        || !optional_value(presentation.get("inputPreview"), |value| {
            valid_text(value, 32_768)
        })
        || !optional_value(presentation.get("outputPreview"), |value| {
            valid_text(value, 32_768)
        })
        || !optional_value(presentation.get("command"), |value| {
            valid_text(value, 32_768)
        })
        || !optional_value(presentation.get("stdout"), |value| {
            valid_text(value, 32_768)
        })
        || !optional_value(presentation.get("stderr"), |value| {
            valid_text(value, 32_768)
        })
        || !optional_value(presentation.get("relativeCwd"), valid_relative_path)
        || !optional_value(presentation.get("exitCode"), |value| {
            value
                .as_i64()
                .is_some_and(|number| i32::try_from(number).is_ok())
        })
        || !optional_value(presentation.get("durationMs"), |value| {
            integer_in_range(Some(value), 0, MAX_SAFE_INTEGER)
        })
        || !presentation.get("truncated").is_some_and(Value::is_boolean)
        || !optional_value(presentation.get("artifactId"), |value| {
            validate_prefixed_value(Some(value), "artifact_", 128)
        })
    {
        return false;
    }
    presentation
        .get("relativePaths")
        .and_then(Value::as_array)
        .is_some_and(|paths| {
            paths.len() <= 64
                && paths.iter().all(valid_relative_path)
                && paths
                    .iter()
                    .enumerate()
                    .all(|(index, path)| !paths[..index].iter().any(|previous| previous == path))
        })
}

/// Frozen change-set 的 available/unavailable 变体使用不同 key 集合；旧 Turn 使用顶层 null，
/// 不接受缺失字段或兼容别名。
fn valid_change_set_wire(value: &Value) -> bool {
    let Some(change_set) = value.as_object() else {
        return false;
    };
    let state = change_set.get("state").and_then(Value::as_str);
    let keys_valid = match state {
        Some("available") => {
            exact_keys_allowing(change_set, &["state", "files", "stats"], &["artifactId"])
        }
        Some("unavailable") => exact_keys(change_set, &["state", "reason", "files", "stats"]),
        _ => false,
    };
    if !keys_valid {
        return false;
    }
    let Some(files) = change_set.get("files").and_then(Value::as_array) else {
        return false;
    };
    if files.len() > 10_000 || !files.iter().all(valid_change_file_wire) {
        return false;
    }
    let Some(stats) = change_set.get("stats").and_then(Value::as_object) else {
        return false;
    };
    if !exact_keys(
        stats,
        &[
            "files",
            "additions",
            "deletions",
            "binaryFiles",
            "truncated",
        ],
    ) || !integer_in_range(stats.get("files"), 0, 10_000)
        || !integer_in_range(stats.get("additions"), 0, MAX_SAFE_INTEGER)
        || !integer_in_range(stats.get("deletions"), 0, MAX_SAFE_INTEGER)
        || !integer_in_range(stats.get("binaryFiles"), 0, 10_000)
        || !stats.get("truncated").is_some_and(Value::is_boolean)
        || stats.get("files").and_then(Value::as_u64) != Some(files.len() as u64)
    {
        return false;
    }
    match state {
        Some("available") => optional_value(change_set.get("artifactId"), |artifact| {
            validate_prefixed_value(Some(artifact), "artifact_", 128)
        }),
        Some("unavailable") => {
            files.is_empty()
                && change_set
                    .get("reason")
                    .and_then(Value::as_str)
                    .is_some_and(|reason| {
                        matches!(
                            reason,
                            "concurrent_turn" | "not_git" | "capture_failed" | "diff_too_large"
                        )
                    })
                && stats.get("files").and_then(Value::as_u64) == Some(0)
                && stats.get("additions").and_then(Value::as_u64) == Some(0)
                && stats.get("deletions").and_then(Value::as_u64) == Some(0)
                && stats.get("binaryFiles").and_then(Value::as_u64) == Some(0)
                && stats.get("truncated").and_then(Value::as_bool) == Some(false)
        }
        _ => false,
    }
}

/// 单文件 shape 允许按文本/binary 事实省略行统计，并强制 oldPath 只属于 rename。
fn valid_change_file_wire(value: &Value) -> bool {
    let Some(file) = value.as_object() else {
        return false;
    };
    if !exact_keys_allowing(
        file,
        &["path", "status", "binary", "truncated"],
        &["oldPath", "additions", "deletions"],
    ) || !file.get("path").is_some_and(valid_relative_path)
        || !file.get("binary").is_some_and(Value::is_boolean)
        || !file.get("truncated").is_some_and(Value::is_boolean)
        || !optional_value(file.get("additions"), |value| {
            integer_in_range(Some(value), 0, MAX_SAFE_INTEGER)
        })
        || !optional_value(file.get("deletions"), |value| {
            integer_in_range(Some(value), 0, MAX_SAFE_INTEGER)
        })
    {
        return false;
    }
    let renamed = file.get("status").and_then(Value::as_str) == Some("renamed");
    if !file
        .get("status")
        .and_then(Value::as_str)
        .is_some_and(|status| matches!(status, "added" | "modified" | "deleted" | "renamed"))
        || renamed != file.contains_key("oldPath")
        || !optional_value(file.get("oldPath"), valid_relative_path)
    {
        return false;
    }
    !file.get("binary").and_then(Value::as_bool).unwrap_or(false)
        || (!file.contains_key("additions") && !file.contains_key("deletions"))
}

/// 严格校验历史 Turn 与两类 Provider API；Rust 不推导缺失运行快照或错误语义。
fn validate_snapshot_turn(turn: &ThreadSnapshotTurnDto) -> Result<(), RuntimeCommandError> {
    validate_prefixed(&turn.turn_id, "turn_", 128)
        .map_err(|_| RuntimeCommandError::unavailable())?;
    if !matches!(
        turn.status.as_str(),
        "queued" | "running" | "waiting_approval" | "completed" | "failed" | "cancelled"
    ) || !valid_timestamp(&turn.requested_at)
        || !valid_timestamp(&turn.updated_at)
        || turn
            .completed_at
            .as_ref()
            .is_some_and(|value| !valid_timestamp(value))
        || turn
            .error_code
            .as_ref()
            .is_some_and(|value| !valid_error_code(value))
    {
        return Err(RuntimeCommandError::unavailable());
    }
    if let Some(runtime) = &turn.runtime {
        validate_prefixed(&runtime.provider_id, "provider_", 128)
            .map_err(|_| RuntimeCommandError::unavailable())?;
        validate_prefixed(&runtime.model_id, "model_", 128)
            .map_err(|_| RuntimeCommandError::unavailable())?;
        if !matches!(runtime.provider.as_str(), "openai" | "anthropic")
            || !matches!(
                runtime.api.as_str(),
                "openai_responses" | "anthropic_messages"
            )
            || runtime.upstream_model.is_empty()
            || runtime.upstream_model.len() > 512
            || runtime.upstream_model.chars().any(char::is_control)
            || runtime
                .reasoning_level
                .as_ref()
                .is_some_and(|value| !is_reasoning_level(value))
            || !matches!(
                runtime.access_mode.as_str(),
                "approval_required" | "full_access"
            )
            || !runtime.config_generation.starts_with("cfg_")
            || runtime.config_generation.len() > 128
        {
            return Err(RuntimeCommandError::unavailable());
        }
    }
    Ok(())
}

/// 恢复 Usage 时同时绑定快照中的真实 Turn；Tool-only 轮次不要求存在可见文本，但身份不能悬空。
fn validate_context_usage(
    usage: &ThreadContextUsageDto,
    turns: &[ThreadSnapshotTurnDto],
) -> Result<(), RuntimeCommandError> {
    validate_prefixed(&usage.turn_id, "turn_", 128)
        .map_err(|_| RuntimeCommandError::unavailable())?;
    let tokens_valid = usage.model_round > 0
        && usage.model_round <= 128
        && usage.input_tokens <= MAX_SAFE_INTEGER
        && usage.output_tokens <= MAX_SAFE_INTEGER
        && usage.total_tokens <= MAX_SAFE_INTEGER
        && usage
            .input_tokens
            .checked_add(usage.output_tokens)
            .is_some_and(|minimum| usage.total_tokens >= minimum);
    if !tokens_valid
        || !valid_timestamp(&usage.measured_at)
        || !turns.iter().any(|turn| turn.turn_id == usage.turn_id)
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(())
}

/// History parser 的拒绝日志只写稳定阶段与内部错误码；不得记录 Thread ID、绝对路径、正文、
/// Tool 输出或 raw JSON，UI 仍只接收既有脱敏 `RUNTIME_UNAVAILABLE` envelope。
fn history_response_rejected(stage: &'static str) -> RuntimeCommandError {
    tracing::warn!(
        history_stage = stage,
        error_code = "HISTORY_RESPONSE_INVALID",
        "history response rejected"
    );
    RuntimeCommandError::unavailable()
}

/// 错误码沿用 JA-RPC 机器标识闭集，拒绝自由文本借历史投影进入 WebView。
fn valid_error_code(value: &str) -> bool {
    if value.len() < 2 || value.len() > 64 {
        return false;
    }
    let mut chars = value.chars();
    chars.next().is_some_and(|first| first.is_ascii_uppercase())
        && chars.all(|ch| ch == '_' || ch.is_ascii_digit() || ch.is_ascii_uppercase())
}

/// 严格解析手动压缩结果并绑定 outcome 与 nullable identity/token 关系，拒绝半成功投影。
pub(crate) fn parse_thread_compact(
    value: Value,
) -> Result<ThreadCompactResult, RuntimeCommandError> {
    let result: ThreadCompactResult = parse_exact(
        value,
        &[
            "outcome",
            "compactionId",
            "checkpointId",
            "threadRevision",
            "inputTokensBefore",
            "inputTokensAfter",
        ],
    )?;
    if result.thread_revision > MAX_SAFE_INTEGER
        || result.input_tokens_before > MAX_SAFE_INTEGER
        || result.input_tokens_after > MAX_SAFE_INTEGER
    {
        return Err(RuntimeCommandError::unavailable());
    }
    let valid_outcome = match result.outcome.as_str() {
        "compacted" => {
            result
                .compaction_id
                .as_deref()
                .is_some_and(|id| validate_prefixed(id, "cmp_", 100).is_ok())
                && result
                    .checkpoint_id
                    .as_deref()
                    .is_some_and(|id| validate_prefixed(id, "checkpoint_", 107).is_ok())
                && result.input_tokens_after < result.input_tokens_before
        }
        "unchanged" => {
            result.compaction_id.is_none()
                && result.checkpoint_id.is_none()
                && result.input_tokens_after == result.input_tokens_before
        }
        _ => false,
    };
    if !valid_outcome {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(result)
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 从可扩展 item payload 递归拒绝 provider/auth material；普通 opaque ID 与已提交用户文本不受影响。
fn contains_private_field(value: &Value) -> bool {
    match value {
        Value::Object(object) => object.iter().any(|(key, child)| {
            matches!(
                key.to_ascii_lowercase().as_str(),
                "apikey" | "secretvalue" | "credential" | "credentials" | "authorization"
            ) || contains_private_field(child)
        }),
        Value::Array(values) => values.iter().any(contains_private_field),
        _ => false,
    }
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 验证 Thread 结果，但不暴露 timestamp parser 诊断。
fn validate_thread(thread: &ThreadDto) -> Result<(), RuntimeCommandError> {
    validate_prefixed(&thread.thread_id, "thr_", 100)
        .map_err(|_| RuntimeCommandError::unavailable())?;
    validate_prefixed(&thread.workspace_id, "ws_", 99)
        .map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(preferences) = &thread.preferences {
        validate_runtime_preferences(
            &preferences.provider_id,
            &preferences.model_id,
            preferences.reasoning_level.as_deref(),
            &preferences.access_mode,
        )
        .map_err(|_| RuntimeCommandError::unavailable())?;
        if !matches!(
            preferences.title_source.as_str(),
            "placeholder" | "auto" | "manual"
        ) {
            return Err(RuntimeCommandError::unavailable());
        }
    }
    if thread.title.is_empty()
        || thread.title.len() > MAX_TITLE
        || !matches!(thread.status.as_str(), "active" | "archived" | "deleted")
        || thread.revision > MAX_SAFE_INTEGER
        || !valid_timestamp(&thread.created_at)
        || !valid_timestamp(&thread.updated_at)
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(())
}

/// 共享 Provider/Model/reasoning/access 语法，确保创建与更新不会因两份校验长期漂移。
fn validate_runtime_preferences(
    provider_id: &str,
    model_id: &str,
    reasoning_level: Option<&str>,
    access_mode: &str,
) -> Result<(), RuntimeCommandError> {
    validate_prefixed(provider_id, "provider_", 128)?;
    validate_prefixed(model_id, "model_", 128)?;
    if reasoning_level.is_some_and(|value| !is_reasoning_level(value))
        || !matches!(access_mode, "approval_required" | "full_access")
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// 仅接受 schema v4 的逻辑思考档位；`None` 由调用方保留为模型默认，不能与 `off` 混同。
fn is_reasoning_level(value: &str) -> bool {
    matches!(
        value,
        "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max"
    )
}

/// 标题在所有写路径上保持非空、单行和固定上限，避免搜索结果与侧栏布局被控制字符破坏。
fn validate_title(title: &str) -> Result<(), RuntimeCommandError> {
    if title.is_empty()
        || title.len() > MAX_TITLE
        || title
            .chars()
            .any(|character| matches!(character, '\0' | '\n' | '\r'))
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// 所有 renderer 提交的 revision 都限制在 JavaScript 安全整数内，避免 CAS 精度截断。
fn validate_revision(revision: u64) -> Result<(), RuntimeCommandError> {
    if revision > MAX_SAFE_INTEGER {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// serde 前要求精确 result key，避免缺失 nullable field 与协议显式 null 无法区分。
fn parse_exact<T: for<'de> Deserialize<'de>>(
    value: Value,
    keys: &[&str],
) -> Result<T, RuntimeCommandError> {
    let object = value
        .as_object()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if !exact_keys(object, keys) {
        return Err(RuntimeCommandError::unavailable());
    }
    serde_json::from_value(value).map_err(|_| RuntimeCommandError::unavailable())
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 以集合检查 required key，因为 wire object 顺序没有语义。
fn exact_keys(object: &Map<String, Value>, keys: &[&str]) -> bool {
    object.len() == keys.len() && keys.iter().all(|key| object.contains_key(*key))
}

/// 带 optional 字段的对象仍维持封闭 key 集合；optional 只允许缺失，具体值不合法时由调用方
/// 单独拒绝，避免 `null` 被当成缺失兼容路径。
fn exact_keys_allowing(object: &Map<String, Value>, required: &[&str], optional: &[&str]) -> bool {
    required.iter().all(|key| object.contains_key(*key))
        && object
            .keys()
            .all(|key| required.contains(&key.as_str()) || optional.contains(&key.as_str()))
}

/// 将可选 wire 值的“字段缺失”与“字段存在但值非法/null”分开处理。
fn optional_value(value: Option<&Value>, validator: impl FnOnce(&Value) -> bool) -> bool {
    value.is_none_or(validator)
}

/// 历史 JSON 数字必须是 JavaScript 可精确表达的非负整数，并满足 method-specific 上限。
fn integer_in_range(value: Option<&Value>, minimum: u64, maximum: u64) -> bool {
    value
        .and_then(Value::as_u64)
        .is_some_and(|number| (minimum..=maximum).contains(&number))
}

/// 将 Value 中的 opaque identity 复用现有前缀语法；错误不会携带原始 identity。
fn validate_prefixed_value(value: Option<&Value>, prefix: &str, maximum: usize) -> bool {
    value
        .and_then(Value::as_str)
        .is_some_and(|value| validate_prefixed(value, prefix, maximum).is_ok())
}

/// Tool 名称使用有界 ASCII identifier，禁止路径分隔符与控制字符进入展示路由。
fn valid_identifier_value(value: &Value) -> bool {
    value.as_str().is_some_and(|identifier| {
        !identifier.is_empty()
            && identifier.len() <= 256
            && identifier.chars().all(|character| {
                character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.' | ':')
            })
    })
}

/// 将 timestamp Value 交给统一冻结 shape 校验，不输出原始时间文本。
fn valid_timestamp_value(value: &Value) -> bool {
    value.as_str().is_some_and(valid_timestamp)
}

/// 历史正文与 Tool preview 只限制字符预算和 NUL；富文本清理已由 Java 安全投影完成。
fn valid_text(value: &Value, maximum: usize) -> bool {
    value
        .as_str()
        .is_some_and(|text| text.chars().count() <= maximum && !text.contains('\0'))
}

/// 用户可见名称必须非空、单行且有界，防止历史项改变卡片或日志结构。
fn valid_safe_name(value: &Value) -> bool {
    value.as_str().is_some_and(|name| {
        (1..=512).contains(&name.chars().count())
            && !name
                .chars()
                .any(|character| matches!(character, '\0' | '\r' | '\n'))
    })
}

/// V4 历史只允许 workspace-relative `/` 路径；拒绝盘符、绝对路径、反斜杠、ISO 控制字符和
/// `..` 父级段，避免 Review 入口恢复后越界。
fn valid_relative_path(value: &Value) -> bool {
    value.as_str().is_some_and(|path| {
        let bytes = path.as_bytes();
        let has_drive_prefix =
            bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':';
        !path.is_empty()
            && path.chars().count() <= 4_096
            && !path.starts_with('/')
            && !has_drive_prefix
            && !path.contains('\\')
            && !path.chars().any(char::is_control)
            && !path.split('/').any(|segment| segment == "..")
    })
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 应用共享 opaque identity 字符集，明确拒绝路径。
fn validate_prefixed(value: &str, prefix: &str, maximum: usize) -> Result<(), RuntimeCommandError> {
    if !value.starts_with(prefix)
        || value.len() <= prefix.len()
        || value.len() > maximum
        || !value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.')
        })
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// WebView serialization 前验证 Java 返回的 optional cursor。
fn validate_cursor(cursor: &Option<String>) -> Result<(), RuntimeCommandError> {
    if cursor.as_ref().is_some_and(|cursor| {
        cursor.is_empty() || cursor.len() > MAX_CURSOR || cursor.chars().any(char::is_control)
    }) {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(())
}

/// 设计原因：该函数集中维护 History DTO 的边界与完整性，避免 command 重复协议判断。
/// 使用冻结 RFC3339 UTC shape，详细时间解析仍由 Java 负责。
fn valid_timestamp(value: &str) -> bool {
    value.len() >= 20
        && value.len() <= 64
        && value.ends_with('Z')
        && value.contains('T')
        && !value.chars().any(char::is_control)
}

/// Interface 专用 Workspace DTO；application 使用独立 domain projection。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceWireDto {
    pub workspace_id: String,
    pub root: String,
    pub display_name: String,
    pub trust: String,
    pub revision: u64,
}

impl From<crate::app_runtime::domain::WorkspaceDto> for WorkspaceWireDto {
    /// application Workspace projection 显式映射为 interface DTO，不把 serde 依赖带回 domain。
    fn from(value: crate::app_runtime::domain::WorkspaceDto) -> Self {
        Self {
            workspace_id: value.workspace_id,
            root: value.root,
            display_name: value.display_name,
            trust: value.trust,
            revision: value.revision,
        }
    }
}
