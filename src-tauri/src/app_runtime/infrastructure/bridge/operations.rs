// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// RPC operations 只发送固定 JA-RPC v1 方法并校验完整结果。

use super::*;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AttachmentWireResult {
    attachment_id: String,
    workspace_id: String,
    display_name: String,
    size_bytes: u64,
    media_kind: String,
    media_type: String,
    state: String,
    created_at: String,
    expires_at: String,
    bound_message_id: Option<String>,
}

/// 使用当前 actor session 发送一个固定附件方法；该 helper 不接受 WebView method 字符串。
fn attachment_request_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: &'static str,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    if !matches!(method, "attachment/import" | "attachment/discard") {
        return Err(RuntimeCommandError::invalid_params());
    }
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request(method, params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    value
        .get("result")
        .cloned()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::unavailable)
}

/// 将完整 Java metadata 校验后收窄为 native projection；时间、hash 与 workspace 不继续进入 WebView。
pub(crate) fn parse_attachment_result(
    value: Value,
    expected_attachment_id: Option<&str>,
    expected_workspace_id: Option<&str>,
    expected_name: Option<&str>,
    expected_size: Option<u64>,
    expected_state: &str,
) -> Result<AttachmentMetadata, RuntimeCommandError> {
    let wire: AttachmentWireResult =
        serde_json::from_value(value).map_err(|_| RuntimeCommandError::unavailable())?;
    let valid_media_type = {
        let Some((kind, subtype)) = wire.media_type.split_once('/') else {
            return Err(RuntimeCommandError::unavailable());
        };
        !kind.is_empty()
            && !subtype.is_empty()
            && wire.media_type.len() <= 128
            && wire.media_type.bytes().all(|byte| {
                byte.is_ascii_lowercase() || byte.is_ascii_digit() || b".+-/".contains(&byte)
            })
    };
    let valid_time = |value: &str| {
        !value.is_empty()
            && value.len() <= 64
            && value.ends_with('Z')
            && value.bytes().all(|byte| byte.is_ascii_graphic())
    };
    if !wire.attachment_id.starts_with("att_")
        || !valid_id(&wire.attachment_id, 128)
        || !wire.workspace_id.starts_with("ws_")
        || !valid_id(&wire.workspace_id, 128)
        || wire.display_name.is_empty()
        || wire.display_name.len() > 1_024
        || wire.display_name.chars().any(char::is_control)
        || wire.size_bytes > 100 * 1024 * 1024
        || !matches!(
            wire.media_kind.as_str(),
            "text" | "image" | "pdf" | "binary"
        )
        || !valid_media_type
        || !matches!(
            wire.state.as_str(),
            "draft" | "bound" | "discarded" | "expired"
        )
        || wire.state != expected_state
        || !valid_time(&wire.created_at)
        || !valid_time(&wire.expires_at)
        || wire
            .bound_message_id
            .as_deref()
            .is_some_and(|value| !value.starts_with("item_") || !valid_id(value, 101))
        || (wire.state == "bound") != wire.bound_message_id.is_some()
        || expected_attachment_id.is_some_and(|value| value != wire.attachment_id)
        || expected_workspace_id.is_some_and(|value| value != wire.workspace_id)
        || expected_name.is_some_and(|value| value != wire.display_name)
        || expected_size.is_some_and(|value| value != wire.size_bytes)
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(AttachmentMetadata {
        attachment_id: wire.attachment_id,
        display_name: wire.display_name,
        size_bytes: wire.size_bytes,
        media_kind: wire.media_kind,
        media_type: Some(wire.media_type),
        state: wire.state,
    })
}

/// 固定构造 `attachment/import` params 并要求 Java 返回同一 workspace/name/size 的 DRAFT。
pub(super) fn attachment_import_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    workspace_id: String,
    input: AttachmentImportInput,
    exit_control: &ExitControl,
) -> Result<AttachmentMetadata, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    if !workspace_id.starts_with("ws_") || !valid_id(&workspace_id, 128) {
        return Err(RuntimeCommandError::invalid_params());
    }
    let expected_workspace_id = workspace_id.clone();
    let expected_name = input.display_name.clone();
    let expected_size = input.size_bytes;
    let result = attachment_request_runtime(
        config,
        runtime,
        "attachment/import",
        json!({
            "ingressToken": input.ingress_token,
            "workspaceId": workspace_id,
            "displayName": input.display_name,
            "sizeBytes": input.size_bytes,
            "sha256": input.sha256,
        }),
        exit_control,
    )?;
    parse_attachment_result(
        result,
        None,
        Some(&expected_workspace_id),
        Some(&expected_name),
        Some(expected_size),
        "draft",
    )
}

/// 固定构造 `attachment/discard` 并验证 Java 返回的是同一 identity 的 DISCARDED 终态。
pub(super) fn attachment_discard_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: AttachmentDiscardInput,
    exit_control: &ExitControl,
) -> Result<(), RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    let expected_attachment_id = input.attachment_id.clone();
    let result = attachment_request_runtime(
        config,
        runtime,
        "attachment/discard",
        json!({"attachmentId": input.attachment_id}),
        exit_control,
    )?;
    parse_attachment_result(
        result,
        Some(&expected_attachment_id),
        None,
        None,
        None,
        "discarded",
    )?;
    Ok(())
}

/// 三个 preview 方法共享同一 Ready supervisor、退出取消门禁和结构化 RPC error 映射。
fn attachment_preview_request_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    operation: impl FnOnce(
        &mut SidecarSupervisor,
        Duration,
    )
        -> Result<RpcFrame, ja_runtime::app_server_process::AppServerProcessError>,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = operation(&mut current.supervisor, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    value
        .get("result")
        .cloned()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::unavailable)
}

/// open 结果必须通过 ja-runtime 的闭集 TryFrom，路径/hash/未知字段因此无法进入 Tauri。
pub(super) fn attachment_preview_open_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: AttachmentPreviewOpenParams,
    exit_control: &ExitControl,
) -> Result<AttachmentPreviewOpenResult, RuntimeCommandError> {
    let value = attachment_preview_request_runtime(
        config,
        runtime,
        |supervisor, timeout| supervisor.attachment_preview_open(input, timeout),
        exit_control,
    )?;
    AttachmentPreviewOpenResult::try_from(&value).map_err(|_| RuntimeCommandError::unavailable())
}

/// read 结果由 ja-runtime 校验 offset 单调性、64 KiB 和 Base64 解码长度后才返回 host。
pub(super) fn attachment_preview_read_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: AttachmentPreviewReadParams,
    exit_control: &ExitControl,
) -> Result<AttachmentPreviewReadResult, RuntimeCommandError> {
    let value = attachment_preview_request_runtime(
        config,
        runtime,
        |supervisor, timeout| supervisor.attachment_preview_read(input, timeout),
        exit_control,
    )?;
    AttachmentPreviewReadResult::try_from(&value).map_err(|_| RuntimeCommandError::unavailable())
}

/// close 只有在返回同一有效 session 且 closed=true 时完成，保持本地释放的提交点清晰。
pub(super) fn attachment_preview_close_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: AttachmentPreviewCloseParams,
    exit_control: &ExitControl,
) -> Result<(), RuntimeCommandError> {
    let value = attachment_preview_request_runtime(
        config,
        runtime,
        |supervisor, timeout| supervisor.attachment_preview_close(input, timeout),
        exit_control,
    )?;
    ja_runtime::app_server_process::AttachmentPreviewCloseResult::try_from(&value)
        .map(|_| ())
        .map_err(|_| RuntimeCommandError::unavailable())
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 发送一个固定 history query/mutation，并复用 bridge 既有 session deadline 与 RPC error
/// 投影；Rust 不为 history 建立第二套 transport、database 或 request registry。
pub(super) fn history_request_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: HistoryMethod,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)
        .map_err(|error| history_request_failed(method, "runtime", error))?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let requested_timeout = if method == HistoryMethod::ThreadCompact {
        COMPACTION_REQUEST_DEADLINE
    } else {
        config.request_timeout
    };
    let timeout = operation_timeout(requested_timeout, exit_control)
        .map_err(|error| history_request_failed(method, "deadline", error))?;
    let response = current
        .supervisor
        .request(method.wire_name(), params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))
        .map_err(|error| history_request_failed(method, "request", error))?;
    let value = frame_to_value(&response)
        .map_err(|_| RuntimeCommandError::unavailable())
        .map_err(|error| history_request_failed(method, "frame", error))?;
    if let Some(error) = value.get("error") {
        return Err(history_request_failed(
            method,
            "rpc_error",
            command_error_from_rpc(error),
        ));
    }
    value
        .get("result")
        .cloned()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::unavailable)
        .map_err(|error| history_request_failed(method, "result_shape", error))
}

/// History transport 日志只记录固定 method、阶段和稳定错误码；不记录 params、response、
/// process error 或正文，避免冷启动诊断反向泄漏用户会话和路径。
fn history_request_failed(
    method: HistoryMethod,
    stage: &'static str,
    error: RuntimeCommandError,
) -> RuntimeCommandError {
    tracing::warn!(
        history_method = method.wire_name(),
        history_stage = stage,
        error_code = error.code,
        "history bridge request failed"
    );
    error
}

/// 通过当前 supervised generation 执行固定路径搜索；该 lane 不读取文件正文，也不接受动态 method。
pub(super) fn workspace_path_search_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: WorkspacePathSearchInput,
    exit_control: &ExitControl,
) -> Result<WorkspacePathSearchResult, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let mut params = json!({
        "threadId": input.thread_id,
        "workspaceId": input.workspace_id,
        "query": input.query,
    });
    if let Some(limit) = input.limit {
        params["limit"] = json!(limit);
    }
    let response = current
        .supervisor
        .request("workspace/path/search", params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    parse_workspace_path_search_result(&input, current.generation, &value)
}

/// Search 结果必须回显请求栅栏与 actor generation，防止旧 Workspace 结果进入新 Composer。
pub(crate) fn parse_workspace_path_search_result(
    input: &WorkspacePathSearchInput,
    expected_generation: u64,
    value: &Value,
) -> Result<WorkspacePathSearchResult, RuntimeCommandError> {
    let result = value
        .get("result")
        .and_then(Value::as_object)
        .filter(|result| {
            result.len() == 6
                && result.contains_key("threadId")
                && result.contains_key("workspaceId")
                && result.contains_key("generation")
                && result.contains_key("query")
                && result.contains_key("items")
                && result.contains_key("truncated")
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let thread_id = result
        .get("threadId")
        .and_then(Value::as_str)
        .filter(|value| *value == input.thread_id)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let workspace_id = result
        .get("workspaceId")
        .and_then(Value::as_str)
        .filter(|value| *value == input.workspace_id)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let generation = result
        .get("generation")
        .and_then(Value::as_u64)
        .filter(|value| *value == expected_generation && *value > 0)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let query = result
        .get("query")
        .and_then(Value::as_str)
        .filter(|value| *value == input.query)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let raw_items = result
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= input.limit.unwrap_or(50) as usize)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let mut paths = std::collections::HashSet::with_capacity(raw_items.len());
    let mut items = Vec::with_capacity(raw_items.len());
    for item in raw_items {
        let item = item
            .as_object()
            .filter(|item| {
                item.len() == 2 && item.contains_key("relativePath") && item.contains_key("kind")
            })
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let relative_path = item
            .get("relativePath")
            .and_then(Value::as_str)
            .filter(|path| {
                let bytes = path.as_bytes();
                let has_drive =
                    bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':';
                !path.is_empty()
                    && path.chars().count() <= 4_096
                    && !path.starts_with('/')
                    && !has_drive
                    && !path.contains('\\')
                    && !path.chars().any(char::is_control)
                    && !path.split('/').any(|segment| segment == "..")
            })
            .ok_or_else(RuntimeCommandError::unavailable)?;
        if !paths.insert(relative_path) {
            return Err(RuntimeCommandError::unavailable());
        }
        let kind = item
            .get("kind")
            .and_then(Value::as_str)
            .filter(|kind| matches!(*kind, "file" | "directory"))
            .ok_or_else(RuntimeCommandError::unavailable)?;
        items.push(WorkspacePathSearchItem {
            relative_path: relative_path.to_owned(),
            kind: kind.to_owned(),
        });
    }
    let truncated = result
        .get("truncated")
        .and_then(Value::as_bool)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    Ok(WorkspacePathSearchResult {
        thread_id: thread_id.to_owned(),
        workspace_id: workspace_id.to_owned(),
        generation,
        query: query.to_owned(),
        items,
        truncated,
    })
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 通过当前 Java session 发送无参数 general-workspace read，只返回对象结果供 Host 严格投影。
pub(super) fn general_workspace_read_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request("workspace/open-general", json!({}), timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    value
        .get("result")
        .cloned()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::unavailable)
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 通过普通 client request lane 发送 allowlist 内的 Skills/MCP 请求；已解析私有 snapshot
/// 始终由 Java 持有。
pub(super) fn settings_query_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: SettingsQueryMethod,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request(method.wire_name(), params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    value
        .get("result")
        .cloned()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::unavailable)
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 打开已 canonicalize 的 native cwd，并协调显式 native trust 目标；Java 持有 durable identity，
/// 可能返回 trust 已比注册默认值更新的既有 Workspace，因此重复打开必须比较而不能假定 untrusted。
pub(super) fn workspace_open_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    root: PathBuf,
    display_name: String,
    trust: String,
    exit_control: &ExitControl,
) -> Result<WorkspaceDto, RuntimeCommandError> {
    if !matches!(trust.as_str(), "trusted" | "untrusted")
        || display_name.is_empty()
        || display_name.len() > 512
        || display_name.chars().any(char::is_control)
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    let root = root
        .to_str()
        .filter(|value| !value.is_empty() && value.len() <= 4096)
        .ok_or_else(RuntimeCommandError::configuration)?;
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request(
            "workspace/open",
            json!({"cwd": root, "displayName": display_name}),
            timeout,
        )
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    let result = value
        .get("result")
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let mut projection = validate_workspace_open_result(result, std::path::Path::new(root))?;
    if projection.trust != trust {
        let timeout = operation_timeout(config.request_timeout, exit_control)?;
        let response = current
            .supervisor
            .request(
                "workspace/set-trust",
                json!({"workspaceId": projection.workspace_id.clone(), "trust": trust}),
                timeout,
            )
            .map_err(|error| RuntimeCommandError::from_process(&error))?;
        let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
        if let Some(error) = value.get("error") {
            return Err(command_error_from_rpc(error));
        }
        let accepted = value
            .get("result")
            .and_then(Value::as_object)
            .filter(|result| result.len() == 1)
            .and_then(|result| result.get("accepted"))
            .and_then(Value::as_bool);
        if accepted != Some(true) {
            return Err(RuntimeCommandError::unavailable());
        }
        projection.trust = trust;
        projection.revision = projection
            .revision
            .checked_add(1)
            .filter(|revision| *revision <= 9_007_199_254_740_991)
            .ok_or_else(RuntimeCommandError::unavailable)?;
    }
    Ok(projection)
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 验证 Java 权威 Workspace 投影，但不要求重复打开回显 caller 的 display-name 建议；Java 会保留
/// 首次注册名称，而 root identity 是必须匹配的 native capability 边界。
pub(crate) fn validate_workspace_open_result(
    result: &Value,
    expected_root: &std::path::Path,
) -> Result<WorkspaceDto, RuntimeCommandError> {
    let result = result
        .as_object()
        .filter(|result| {
            result.len() == 5
                && ["workspaceId", "root", "displayName", "trust", "revision"]
                    .iter()
                    .all(|key| result.contains_key(*key))
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let display_name = result
        .get("displayName")
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty() && value.len() <= 1_024 && !value.contains('\0'))
        .ok_or_else(RuntimeCommandError::unavailable)?
        .to_owned();
    let returned_trust = result
        .get("trust")
        .and_then(Value::as_str)
        .filter(|value| matches!(*value, "trusted" | "untrusted"))
        .ok_or_else(RuntimeCommandError::unavailable)?
        .to_owned();
    let revision = result
        .get("revision")
        .and_then(Value::as_u64)
        .filter(|revision| *revision <= 9_007_199_254_740_991)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let workspace_id = result
        .get("workspaceId")
        .and_then(Value::as_str)
        .filter(|value| value.starts_with("ws_") && valid_id(value, 99))
        .ok_or_else(RuntimeCommandError::unavailable)?
        .to_owned();
    let returned_root_value = result
        .get("root")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty() && value.len() <= 4_096)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let returned_root = std::fs::canonicalize(PathBuf::from(returned_root_value))
        .ok()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if returned_root != expected_root {
        return Err(RuntimeCommandError::configuration());
    }
    Ok(WorkspaceDto {
        workspace_id,
        root: returned_root_value.to_owned(),
        display_name,
        trust: returned_trust,
        revision,
    })
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// Host 返回 startup/configuration 成功前，要求完整有界 health 结果与 Ready runtime；
/// component 名称和状态仅限 native，诊断 payload 不进入 WebView state。
pub(super) fn health_read_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    exit_control: &ExitControl,
) -> Result<(), RuntimeCommandError> {
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request("runtime/health", json!({}), timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    let result = value
        .get("result")
        .ok_or_else(RuntimeCommandError::unavailable)?;
    validate_health_result(result)
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 将完整 health 结果验证与 transport 分离，使 malformed 或含 secret fixture 无需启动 Java
/// 进程也能覆盖。
pub(crate) fn validate_health_result(result: &Value) -> Result<(), RuntimeCommandError> {
    let result = result
        .as_object()
        .filter(|result| {
            result.len() == 2 && result.contains_key("status") && result.contains_key("components")
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let components = result
        .get("components")
        .and_then(Value::as_array)
        .filter(|components| components.len() == 4)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if result.get("status").and_then(Value::as_str) != Some("ready")
        || components.iter().any(|component| {
            let Some(component) = component.as_object() else {
                return true;
            };
            let Some(name) = component.get("name").and_then(Value::as_str) else {
                return true;
            };
            let Some(status) = component.get("status").and_then(Value::as_str) else {
                return true;
            };
            let known_name = matches!(name, "sidecar" | "sqlite" | "kernel" | "configuration");
            let valid_status = matches!(status, "healthy" | "degraded" | "unavailable" | "stopped");
            let valid_shape = if name == "configuration" {
                matches!(component.len(), 2 | 3)
                    && component
                        .keys()
                        .all(|key| matches!(key.as_str(), "name" | "status" | "diagnostics"))
                    && component
                        .get("diagnostics")
                        .is_none_or(valid_health_diagnostics)
            } else {
                component.len() == 2
                    && component
                        .keys()
                        .all(|key| matches!(key.as_str(), "name" | "status"))
            };
            !(known_name && valid_id(name, 256) && valid_status && valid_shape)
        })
        || {
            let mut names = std::collections::HashSet::new();
            components.iter().any(|component| {
                component
                    .get("name")
                    .and_then(Value::as_str)
                    .is_none_or(|name| !names.insert(name))
            })
        }
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(())
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 验证 Java 唯一可选 health payload：configuration component 上有界的大写 diagnostic code
/// 列表；独立 grammar 可阻止任意异常文本或含 secret 诊断跨越 native 边界。
pub(super) fn valid_health_diagnostics(value: &Value) -> bool {
    let Some(values) = value.as_array() else {
        return false;
    };
    values.len() <= 16
        && values.iter().all(|value| {
            let Some(code) = value.as_str() else {
                return false;
            };
            let bytes = code.as_bytes();
            !bytes.is_empty()
                && bytes.len() <= 64
                && bytes[0].is_ascii_uppercase()
                && bytes[1..]
                    .iter()
                    .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || *byte == b'_')
        })
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 发送一个 allowlist 内 Java-owned configuration/credential 操作；含 secret 参数只向 Java
/// 转发一次，绝不保留在 Host config、status、event 或诊断投影中。
pub(super) fn config_request_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: String,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    if !matches!(
        method.as_str(),
        "configuration/read"
            | "configuration/patch"
            | "configuration/replace"
            | "configuration/reset"
            | "credential/set"
            | "credential/delete"
    ) {
        return Err(RuntimeCommandError::invalid_params());
    }
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request(&method, params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    value
        .get("result")
        .cloned()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::unavailable)
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// Java 返回 accepted 后即越过不可回滚的提交点，因此这里只允许执行不会失败的 admission
/// 记录并直接返回；生命周期与 timeline 投影由各自 owner 异步发布，不能把后置投影失败伪装成
/// 可重试的 `turn/start` 失败，否则 caller 重试会创建重复 Turn。
pub(super) fn turn_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    params: Value,
    exit_control: &ExitControl,
) -> Result<TurnAccepted, RuntimeCommandError> {
    let Some(current) = runtime.as_mut() else {
        return Err(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready",
            retryable: true,
        });
    };
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _ = params
        .get("threadId")
        .and_then(Value::as_str)
        .filter(|value| valid_id(value, 100))
        .ok_or_else(RuntimeCommandError::invalid_params)?;
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request("turn/start", params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    parse_turn_accepted_result(&value, None, false)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TurnAcceptedWire {
    accepted: bool,
    turn_id: String,
    queued: bool,
    thread_revision: u64,
}

/// 严格解析 Start/Resume 共用的 Accepted 四字段；Resume 额外绑定既有 Turn identity，防止错配回执。
fn parse_turn_accepted_result(
    value: &Value,
    expected_turn_id: Option<&str>,
    require_queued: bool,
) -> Result<TurnAccepted, RuntimeCommandError> {
    let result = value
        .get("result")
        .cloned()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let result: TurnAcceptedWire =
        serde_json::from_value(result).map_err(|_| RuntimeCommandError::unavailable())?;
    if !result.accepted
        || (require_queued && !result.queued)
        || !valid_frozen_turn_id(&result.turn_id)
        || result.thread_revision > 9_007_199_254_740_991
        || expected_turn_id.is_some_and(|expected| expected != result.turn_id)
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TurnAccepted {
        accepted: result.accepted,
        turn_id: result.turn_id,
        queued: result.queued,
        thread_revision: result.thread_revision,
    })
}

/// 通过当前 generation 的 supervised session 发送 Resume；Rust 不把 Turn suspended 映射为进程恢复状态。
pub(super) fn turn_resume_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    params: Value,
    exit_control: &ExitControl,
) -> Result<TurnAccepted, RuntimeCommandError> {
    let Some(current) = runtime.as_mut() else {
        return Err(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready",
            retryable: true,
        });
    };
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let requested_turn_id = params
        .get("turnId")
        .and_then(Value::as_str)
        .filter(|value| valid_frozen_turn_id(value))
        .ok_or_else(RuntimeCommandError::invalid_params)?
        .to_owned();
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request("turn/resume", params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    parse_turn_resume_result(&requested_turn_id, &value)
}

/// Resume 回执必须回显精确 Turn identity 并保持 Accepted 常量，禁止 sidecar 错配唤醒其它 Turn。
pub(crate) fn parse_turn_resume_result(
    requested_turn_id: &str,
    value: &Value,
) -> Result<TurnAccepted, RuntimeCommandError> {
    parse_turn_accepted_result(value, Some(requested_turn_id), true)
}

/// Actor 只签发当前 Ready generation 的窄读取 lease，正文等待由调用线程承担。
pub(super) fn turn_change_set_read_lease_runtime(
    runtime: &mut Option<RunningRuntime>,
) -> Result<TurnChangeSetReadLease, RuntimeCommandError> {
    runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?
        .supervisor
        .turn_change_set_read_lease()
        .map_err(|error| RuntimeCommandError::from_process(&error))
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ToolArtifactReadWire {
    artifact_id: String,
    offset_characters: u64,
    next_offset_characters: Option<u64>,
    total_characters: u64,
    truncated: bool,
    content: String,
}

/// 按 Thread/Turn/Call/Artifact 四元身份读取 Tool 输出；字符总量以 Unicode code point 复核。
pub(super) fn tool_artifact_read_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: ToolArtifactReadInput,
    exit_control: &ExitControl,
) -> Result<ToolArtifactReadResult, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    let expected_artifact_id = input.artifact_id.clone();
    let expected_offset = input.offset_characters;
    let requested_limit = input.limit_characters;
    let result = fixed_artifact_request(
        config,
        runtime,
        "tool/artifact/read",
        json!({
            "threadId": input.thread_id,
            "turnId": input.turn_id,
            "callId": input.call_id,
            "artifactId": input.artifact_id,
            "offsetCharacters": input.offset_characters,
            "limitCharacters": input.limit_characters,
        }),
        exit_control,
    )?;
    validate_tool_artifact_page(
        result,
        &expected_artifact_id,
        expected_offset,
        requested_limit,
    )
}

/// 对 Tool artifact 页复核四元归属之外的返回 identity、Unicode 进度和请求预算；空的
/// truncated 页会导致 UI 循环，因此必须关闭失败而不是尝试猜测下一偏移。
pub(crate) fn validate_tool_artifact_page(
    result: Value,
    expected_artifact_id: &str,
    expected_offset: u64,
    requested_limit: u64,
) -> Result<ToolArtifactReadResult, RuntimeCommandError> {
    let wire: ToolArtifactReadWire =
        serde_json::from_value(result).map_err(|_| RuntimeCommandError::unavailable())?;
    let observed_end = wire.next_offset_characters.unwrap_or(wire.total_characters);
    let content_characters = wire.content.chars().count() as u64;
    if wire.artifact_id != expected_artifact_id
        || wire.offset_characters != expected_offset
        || wire.total_characters > 9_007_199_254_740_991
        || observed_end < wire.offset_characters
        || observed_end > wire.total_characters
        || observed_end.saturating_sub(wire.offset_characters) != content_characters
        || content_characters > requested_limit
        || wire
            .next_offset_characters
            .is_some_and(|next| next <= wire.offset_characters)
        || wire.truncated != wire.next_offset_characters.is_some()
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(ToolArtifactReadResult {
        artifact_id: wire.artifact_id,
        offset_characters: wire.offset_characters,
        next_offset_characters: wire.next_offset_characters,
        total_characters: wire.total_characters,
        truncated: wire.truncated,
        content: wire.content,
    })
}

/// Tool artifact 分页独占既有 Ready supervisor、超时与 error envelope。
fn fixed_artifact_request(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: &'static str,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    if method != "tool/artifact/read" {
        return Err(RuntimeCommandError::invalid_params());
    }
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let response = current
        .supervisor
        .request(
            method,
            params,
            operation_timeout(config.request_timeout, exit_control)?,
        )
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    value
        .get("result")
        .cloned()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::unavailable)
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 发送 typed cancel 请求，但不修改 lifecycle generation 或停止 sidecar；最终 terminal event
/// 仍是权威事实。
pub(super) fn turn_cancel_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    params: Value,
    exit_control: &ExitControl,
) -> Result<TurnCancelResult, RuntimeCommandError> {
    let Some(current) = runtime.as_mut() else {
        return Err(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready",
            retryable: true,
        });
    };
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let requested_turn_id = params
        .get("turnId")
        .and_then(Value::as_str)
        .filter(|value| valid_frozen_turn_id(value))
        .ok_or_else(RuntimeCommandError::invalid_params)?
        .to_owned();
    let response = current
        .supervisor
        .request("turn/cancel", params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    parse_turn_cancel_result(&requested_turn_id, &value)
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 将 cancellation acknowledgement 解析为完整 value object；字段缺失、identity drift 或未接受
/// 取消都不能向 UI 投影为成功 command。
pub(super) fn parse_turn_cancel_result(
    requested_turn_id: &str,
    value: &Value,
) -> Result<TurnCancelResult, RuntimeCommandError> {
    let result = value
        .get("result")
        .and_then(Value::as_object)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let accepted = result
        .get("accepted")
        .and_then(Value::as_bool)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let turn_id = result
        .get("turnId")
        .and_then(Value::as_str)
        .filter(|value| valid_frozen_turn_id(value))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if turn_id != requested_turn_id || !accepted {
        return Err(RuntimeCommandError::unavailable());
    }
    let status = result
        .get("status")
        .and_then(Value::as_str)
        .filter(|value| {
            matches!(
                *value,
                "queued"
                    | "running"
                    | "waiting_approval"
                    | "suspended"
                    | "completed"
                    | "failed"
                    | "cancelled"
            )
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let thread_revision = result
        .get("threadRevision")
        .and_then(Value::as_u64)
        .filter(|revision| *revision <= 9_007_199_254_740_991)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    Ok(TurnCancelResult {
        accepted,
        turn_id: turn_id.to_owned(),
        status: status.to_owned(),
        thread_revision,
    })
}

/// 通过当前 supervised session 发送固定队列 mutation，并验证完整权威队列后才返回 WebView。
pub(super) fn turn_input_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: &'static str,
    params: Value,
    exit_control: &ExitControl,
) -> Result<TurnInputResult, RuntimeCommandError> {
    let Some(current) = runtime.as_mut() else {
        return Err(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready",
            retryable: true,
        });
    };
    let requested_turn_id = params
        .get("turnId")
        .and_then(Value::as_str)
        .ok_or_else(RuntimeCommandError::invalid_params)?
        .to_owned();
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request(method, params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    parse_turn_input_result(&requested_turn_id, &value)
}

/// 强制成功 tuple 为 `{accepted:true,inputId,inputQueue}` 并校验队列预算、identity 和真实顺序。
pub(crate) fn parse_turn_input_result(
    requested_turn_id: &str,
    value: &Value,
) -> Result<TurnInputResult, RuntimeCommandError> {
    let result = value
        .get("result")
        .and_then(Value::as_object)
        .filter(|result| {
            result.len() == 3
                && result.contains_key("accepted")
                && result.contains_key("inputId")
                && result.contains_key("inputQueue")
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let accepted = result
        .get("accepted")
        .and_then(Value::as_bool)
        .filter(|value| *value)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let input_id = result
        .get("inputId")
        .and_then(Value::as_str)
        .filter(|value| value.starts_with("input_") && valid_id(value, 128))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let input_queue = parse_input_queue(result.get("inputQueue"), requested_turn_id)?;
    Ok(TurnInputResult {
        accepted,
        input_id: input_id.to_owned(),
        input_queue,
    })
}

/// 解析完整队列时同时限制条目数和 UTF-8 总量；Rust 不重排数组，也不把 kind 当排序提示。
pub(crate) fn parse_input_queue(
    value: Option<&Value>,
    requested_turn_id: &str,
) -> Result<InputQueue, RuntimeCommandError> {
    let queue = value
        .and_then(Value::as_object)
        .filter(|queue| {
            queue.len() == 4
                && queue.contains_key("turnId")
                && queue.contains_key("revision")
                && queue.contains_key("accepting")
                && queue.contains_key("items")
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let turn_id = queue
        .get("turnId")
        .and_then(Value::as_str)
        .filter(|turn_id| *turn_id == requested_turn_id)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let revision = queue
        .get("revision")
        .and_then(Value::as_u64)
        .filter(|revision| *revision <= 9_007_199_254_740_991)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let accepting = queue
        .get("accepting")
        .and_then(Value::as_bool)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let raw_items = queue
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 8)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let mut content_bytes = 0_usize;
    let mut identities = std::collections::HashSet::with_capacity(raw_items.len());
    let mut items = Vec::with_capacity(raw_items.len());
    for item in raw_items {
        let item = parse_queued_input(item, turn_id)?;
        content_bytes = content_bytes
            .checked_add(
                serde_json::to_vec(&turn_content_value(&item.content))
                    .map_err(|_| RuntimeCommandError::unavailable())?
                    .len(),
            )
            .filter(|bytes| *bytes <= 524_288)
            .ok_or_else(RuntimeCommandError::unavailable)?;
        if !identities.insert(item.input_id.clone()) {
            return Err(RuntimeCommandError::unavailable());
        }
        items.push(item);
    }
    Ok(InputQueue {
        turn_id: turn_id.to_owned(),
        revision,
        accepting,
        items,
    })
}

/// 校验单条队列记录的精确 wire 形状，旧 text 字段与自由调度字段均失败关闭。
pub(crate) fn parse_queued_input(
    value: &Value,
    requested_turn_id: &str,
) -> Result<QueuedInput, RuntimeCommandError> {
    let item = value
        .as_object()
        .filter(|item| {
            item.len() == 9
                && item.contains_key("inputId")
                && item.contains_key("turnId")
                && item.contains_key("content")
                && item.contains_key("attachments")
                && item.contains_key("kind")
                && item.contains_key("status")
                && item.contains_key("issue")
                && item.contains_key("inputRevision")
                && item.contains_key("createdAt")
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let input_id = item
        .get("inputId")
        .and_then(Value::as_str)
        .filter(|value| value.starts_with("input_") && valid_id(value, 128))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let turn_id = item
        .get("turnId")
        .and_then(Value::as_str)
        .filter(|value| *value == requested_turn_id)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let content = parse_turn_content(item.get("content"), turn_id)?;
    let attachments = parse_attachment_summaries(item.get("attachments"), &content)?;
    let kind = item
        .get("kind")
        .and_then(Value::as_str)
        .filter(|kind| matches!(*kind, "follow_up" | "steering"))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let status = item
        .get("status")
        .and_then(Value::as_str)
        .filter(|status| matches!(*status, "pending" | "needs_attention"))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let issue = parse_queued_input_issue(item.get("issue"), status)?;
    let input_revision = item
        .get("inputRevision")
        .and_then(Value::as_u64)
        .filter(|revision| (1..=9_007_199_254_740_991).contains(revision))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let created_at = item
        .get("createdAt")
        .and_then(Value::as_str)
        .filter(|value| valid_protocol_timestamp(value))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    Ok(QueuedInput {
        input_id: input_id.to_owned(),
        turn_id: turn_id.to_owned(),
        content,
        attachments,
        kind: kind.to_owned(),
        status: status.to_owned(),
        issue,
        input_revision,
        created_at: created_at.to_owned(),
    })
}

/// 摘要字段与 attachment block 必须一一同序，防止合法摘要被拼接到另一附件 identity。
pub(crate) fn parse_attachment_summaries(
    value: Option<&Value>,
    content: &[crate::app_runtime::TurnContentPart],
) -> Result<Vec<crate::app_runtime::AttachmentSummary>, RuntimeCommandError> {
    let values = value
        .and_then(Value::as_array)
        .filter(|values| values.len() <= 10)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let attachment_ids = content.iter().filter_map(|part| match part {
        crate::app_runtime::TurnContentPart::Attachment { attachment_id } => Some(attachment_id),
        _ => None,
    });
    let mut summaries = Vec::with_capacity(values.len());
    for (value, expected_id) in values.iter().zip(attachment_ids) {
        let object = value
            .as_object()
            .filter(|object| {
                object.len() == 5
                    && [
                        "attachmentId",
                        "displayName",
                        "sizeBytes",
                        "mediaKind",
                        "mediaType",
                    ]
                    .iter()
                    .all(|key| object.contains_key(*key))
            })
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let attachment_id = object
            .get("attachmentId")
            .and_then(Value::as_str)
            .filter(|id| *id == expected_id && id.starts_with("att_") && valid_id(id, 128))
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let display_name = object
            .get("displayName")
            .and_then(Value::as_str)
            .filter(|name| {
                !name.is_empty()
                    && name.chars().count() <= 512
                    && !name.chars().any(char::is_control)
            })
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let size_bytes = object
            .get("sizeBytes")
            .and_then(Value::as_u64)
            .filter(|size| *size <= 104_857_600)
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let media_kind = object
            .get("mediaKind")
            .and_then(Value::as_str)
            .filter(|kind| matches!(*kind, "text" | "image" | "pdf" | "binary"))
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let media_type = object
            .get("mediaType")
            .and_then(Value::as_str)
            .filter(|media_type| {
                (3..=128).contains(&media_type.len()) && !media_type.chars().any(char::is_control)
            })
            .ok_or_else(RuntimeCommandError::unavailable)?;
        summaries.push(crate::app_runtime::AttachmentSummary {
            attachment_id: attachment_id.to_owned(),
            display_name: display_name.to_owned(),
            size_bytes,
            media_kind: media_kind.to_owned(),
            media_type: media_type.to_owned(),
        });
    }
    if summaries.len()
        != content
            .iter()
            .filter(|part| matches!(part, crate::app_runtime::TurnContentPart::Attachment { .. }))
            .count()
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(summaries)
}

/// 将不可信 sidecar 内容解析为封闭枚举，再复用 domain 校验锁定块顺序、去重和预算。
pub(crate) fn parse_turn_content(
    value: Option<&Value>,
    turn_id: &str,
) -> Result<Vec<crate::app_runtime::TurnContentPart>, RuntimeCommandError> {
    let values = value
        .and_then(Value::as_array)
        .filter(|values| (1..=64).contains(&values.len()))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let mut content = Vec::with_capacity(values.len());
    for value in values {
        let object = value
            .as_object()
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let part = match object.get("type").and_then(Value::as_str) {
            Some("text") if object.len() == 2 => crate::app_runtime::TurnContentPart::Text {
                text: object
                    .get("text")
                    .and_then(Value::as_str)
                    .ok_or_else(RuntimeCommandError::unavailable)?
                    .to_owned(),
            },
            Some("attachment") if object.len() == 2 => {
                crate::app_runtime::TurnContentPart::Attachment {
                    attachment_id: object
                        .get("attachmentId")
                        .and_then(Value::as_str)
                        .ok_or_else(RuntimeCommandError::unavailable)?
                        .to_owned(),
                }
            }
            Some("workspace_reference") if object.len() == 4 => {
                crate::app_runtime::TurnContentPart::WorkspaceReference {
                    workspace_id: object
                        .get("workspaceId")
                        .and_then(Value::as_str)
                        .ok_or_else(RuntimeCommandError::unavailable)?
                        .to_owned(),
                    relative_path: object
                        .get("relativePath")
                        .and_then(Value::as_str)
                        .ok_or_else(RuntimeCommandError::unavailable)?
                        .to_owned(),
                    kind: object
                        .get("kind")
                        .and_then(Value::as_str)
                        .ok_or_else(RuntimeCommandError::unavailable)?
                        .to_owned(),
                }
            }
            Some("skill_reference") if object.len() == 2 => {
                crate::app_runtime::TurnContentPart::SkillReference {
                    skill_id: object
                        .get("skillId")
                        .and_then(Value::as_str)
                        .ok_or_else(RuntimeCommandError::unavailable)?
                        .to_owned(),
                }
            }
            _ => return Err(RuntimeCommandError::unavailable()),
        };
        content.push(part);
    }
    TurnInputEnqueue {
        turn_id: turn_id.to_owned(),
        content: content.clone(),
    }
    .validate()
    .map_err(|_| RuntimeCommandError::unavailable())?;
    Ok(content)
}

/// `pending` 必须没有 issue，`needs_attention` 必须携带可展示且稳定的恢复问题。
fn parse_queued_input_issue(
    value: Option<&Value>,
    status: &str,
) -> Result<Option<QueuedInputIssue>, RuntimeCommandError> {
    if status == "pending" {
        return value
            .filter(|value| value.is_null())
            .map(|_| None)
            .ok_or_else(RuntimeCommandError::unavailable);
    }
    let issue = value
        .and_then(Value::as_object)
        .filter(|issue| {
            issue.len() == 3
                && issue.contains_key("errorCode")
                && issue.contains_key("message")
                && issue.contains_key("retryable")
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let error_code = issue
        .get("errorCode")
        .and_then(Value::as_str)
        .filter(|code| {
            matches!(
                *code,
                "WORKSPACE_REFERENCE_INVALID"
                    | "SKILL_UNAVAILABLE"
                    | "SKILL_LOAD_FAILED"
                    | "CONTENT_TOO_LARGE"
                    | "ATTACHMENT_UNAVAILABLE"
            )
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let message = issue
        .get("message")
        .and_then(Value::as_str)
        .filter(|message| {
            !message.is_empty()
                && message.chars().count() <= 512
                && !message
                    .chars()
                    .any(|character| matches!(character, '\0' | '\r' | '\n'))
        })
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let retryable = issue
        .get("retryable")
        .and_then(Value::as_bool)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    Ok(Some(QueuedInputIssue {
        error_code: error_code.to_owned(),
        message: message.to_owned(),
        retryable,
    }))
}

/// 将已验证 RPC errorCode 映射为稳定命令错误，展示 message 不参与分类。
pub(crate) fn command_error_from_rpc(value: &Value) -> RuntimeCommandError {
    match value
        .get("data")
        .and_then(|data| data.get("errorCode"))
        .and_then(Value::as_str)
    {
        Some("WORKSPACE_NOT_FOUND") => RuntimeCommandError {
            code: "WORKSPACE_NOT_FOUND",
            message: "workspace was not found",
            retryable: false,
        },
        Some("THREAD_NOT_FOUND") => RuntimeCommandError {
            code: "THREAD_NOT_FOUND",
            message: "thread was not found",
            retryable: false,
        },
        Some("THREAD_READ_ONLY") => RuntimeCommandError {
            code: "THREAD_READ_ONLY",
            message: "thread is read-only",
            retryable: false,
        },
        Some("TASK_NOT_FOUND") => RuntimeCommandError {
            code: "TASK_NOT_FOUND",
            message: "task was not found",
            retryable: false,
        },
        Some("TASK_RELATION_INVALID") => RuntimeCommandError {
            code: "TASK_RELATION_INVALID",
            message: "task relationship is invalid",
            retryable: false,
        },
        Some("TASK_CONTEXT_REVISION_CONFLICT") => RuntimeCommandError {
            code: "TASK_CONTEXT_REVISION_CONFLICT",
            message: "task context revision changed",
            retryable: true,
        },
        Some("TASK_PERMISSION_DENIED") => RuntimeCommandError {
            code: "TASK_PERMISSION_DENIED",
            message: "task operation is not permitted",
            retryable: false,
        },
        Some("TASK_DEPTH_LIMIT") => RuntimeCommandError {
            code: "TASK_DEPTH_LIMIT",
            message: "task depth limit was reached",
            retryable: false,
        },
        Some("TASK_TREE_LIMIT") => RuntimeCommandError {
            code: "TASK_TREE_LIMIT",
            message: "task tree limit was reached",
            retryable: false,
        },
        Some("TASK_MAILBOX_FULL") => RuntimeCommandError {
            code: "TASK_MAILBOX_FULL",
            message: "task mailbox is full",
            retryable: true,
        },
        Some("TASK_TREE_DELETE_REQUIRED") => RuntimeCommandError {
            code: "TASK_TREE_DELETE_REQUIRED",
            message: "task tree delete confirmation is required",
            retryable: false,
        },
        Some("TASK_OBSERVATION_INVALID") => RuntimeCommandError {
            code: "TASK_OBSERVATION_INVALID",
            message: "task observation is invalid",
            retryable: false,
        },
        Some("WORKSPACE_WRITE_LEASE_TIMEOUT") => RuntimeCommandError {
            code: "WORKSPACE_WRITE_LEASE_TIMEOUT",
            message: "workspace write lease timed out",
            retryable: true,
        },
        Some("GOAL_NOT_FOUND") => RuntimeCommandError {
            code: "GOAL_NOT_FOUND",
            message: "goal was not found",
            retryable: false,
        },
        Some("GOAL_REVISION_CONFLICT") => RuntimeCommandError {
            code: "GOAL_REVISION_CONFLICT",
            message: "goal revision changed",
            retryable: true,
        },
        Some("GOAL_INVALID_STATE") => RuntimeCommandError {
            code: "GOAL_INVALID_STATE",
            message: "goal state does not allow this operation",
            retryable: false,
        },
        Some("PLAN_INVALID") => RuntimeCommandError {
            code: "PLAN_INVALID",
            message: "plan is invalid",
            retryable: false,
        },
        Some("PLAN_APPROVAL_STALE") => RuntimeCommandError {
            code: "PLAN_APPROVAL_STALE",
            message: "plan approval is stale",
            retryable: false,
        },
        Some("GOAL_EVIDENCE_INCOMPLETE") => RuntimeCommandError {
            code: "GOAL_EVIDENCE_INCOMPLETE",
            message: "goal acceptance evidence is incomplete",
            retryable: false,
        },
        Some("GOAL_RECOVERY_REQUIRED") => RuntimeCommandError {
            code: "GOAL_RECOVERY_REQUIRED",
            message: "goal recovery requires user action",
            retryable: false,
        },
        Some("INTERACTION_NOT_FOUND") => RuntimeCommandError {
            code: "INTERACTION_NOT_FOUND",
            message: "interaction was not found",
            retryable: false,
        },
        Some("INTERACTION_REVISION_CONFLICT") => RuntimeCommandError {
            code: "INTERACTION_REVISION_CONFLICT",
            message: "interaction revision changed",
            retryable: true,
        },
        Some("INTERACTION_INVALID_STATE") => RuntimeCommandError {
            code: "INTERACTION_INVALID_STATE",
            message: "interaction state does not allow this operation",
            retryable: false,
        },
        Some("INTERACTION_INVALID") => RuntimeCommandError {
            code: "INTERACTION_INVALID",
            message: "interaction is invalid",
            retryable: false,
        },
        Some("PLAN_REVISION_CONFLICT") => RuntimeCommandError {
            code: "PLAN_REVISION_CONFLICT",
            message: "plan revision changed",
            retryable: true,
        },
        Some("PLAN_INVALID_STATE") => RuntimeCommandError {
            code: "PLAN_INVALID_STATE",
            message: "plan state does not allow this operation",
            retryable: false,
        },
        Some("CONFLICT") => RuntimeCommandError {
            code: "CONFLICT",
            message: "runtime state conflict",
            retryable: true,
        },
        Some("THREAD_BUSY") => RuntimeCommandError {
            code: "THREAD_BUSY",
            message: "thread is busy",
            retryable: true,
        },
        Some("SUMMARY_FAILURE") => RuntimeCommandError {
            code: "SUMMARY_FAILURE",
            message: "context summary failed",
            retryable: true,
        },
        Some("CONTEXT_LIMIT") => RuntimeCommandError {
            code: "CONTEXT_LIMIT",
            message: "context limit was exceeded",
            retryable: false,
        },
        Some("CANCELLED") => RuntimeCommandError {
            code: "CANCELLED",
            message: "request was cancelled",
            retryable: false,
        },
        Some("INVALID_STATE") => RuntimeCommandError {
            code: "INVALID_STATE",
            message: "runtime state is invalid",
            retryable: false,
        },
        Some("RECOVERY_REQUIRED") => RuntimeCommandError {
            code: "RECOVERY_REQUIRED",
            message: "runtime recovery is required",
            retryable: false,
        },
        Some("TURN_NOT_RESUMABLE") => RuntimeCommandError {
            code: "TURN_NOT_RESUMABLE",
            message: "turn cannot be resumed",
            retryable: false,
        },
        Some("TURN_RESUME_ORDER_CONFLICT") => RuntimeCommandError {
            code: "TURN_RESUME_ORDER_CONFLICT",
            message: "an earlier turn must be resolved first",
            retryable: true,
        },
        Some("TURN_INPUT_QUEUE_FULL") => RuntimeCommandError {
            code: "TURN_INPUT_QUEUE_FULL",
            message: "turn input queue is full",
            retryable: true,
        },
        Some("QUEUED_INPUT_NOT_FOUND") => RuntimeCommandError {
            code: "QUEUED_INPUT_NOT_FOUND",
            message: "queued input was not found",
            retryable: false,
        },
        Some("WORKSPACE_REFERENCE_INVALID") => RuntimeCommandError {
            code: "WORKSPACE_REFERENCE_INVALID",
            message: "workspace reference is no longer valid",
            retryable: false,
        },
        Some("SKILL_UNAVAILABLE") => RuntimeCommandError {
            code: "SKILL_UNAVAILABLE",
            message: "skill is unavailable",
            retryable: true,
        },
        Some("SKILL_LOAD_FAILED") => RuntimeCommandError {
            code: "SKILL_LOAD_FAILED",
            message: "skill could not be loaded",
            retryable: true,
        },
        Some("CONTENT_TOO_LARGE") => RuntimeCommandError {
            code: "CONTENT_TOO_LARGE",
            message: "message content is too large",
            retryable: false,
        },
        Some("CONFIG_INVALID") => RuntimeCommandError {
            code: "CONFIG_INVALID",
            message: "configuration input is invalid",
            retryable: false,
        },
        Some("CONFIG_CONFLICT") => RuntimeCommandError {
            code: "CONFIG_CONFLICT",
            message: "configuration version conflict",
            retryable: true,
        },
        Some("STORAGE_UNAVAILABLE") => RuntimeCommandError {
            code: "STORAGE_UNAVAILABLE",
            message: "configuration storage is unavailable",
            retryable: true,
        },
        Some("ATTACHMENT_NOT_FOUND") => RuntimeCommandError {
            code: "ATTACHMENT_NOT_FOUND",
            message: "attachment is unavailable in this scope",
            retryable: false,
        },
        Some("ATTACHMENT_LIMIT_EXCEEDED") => RuntimeCommandError {
            code: "ATTACHMENT_LIMIT_EXCEEDED",
            message: "attachment exceeds the supported limit",
            retryable: false,
        },
        Some("ATTACHMENT_CONFLICT") => RuntimeCommandError {
            code: "ATTACHMENT_CONFLICT",
            message: "attachment state conflicts with this request",
            retryable: false,
        },
        Some("ATTACHMENT_UNAVAILABLE") => RuntimeCommandError {
            code: "ATTACHMENT_UNAVAILABLE",
            message: "attachment content is unavailable",
            retryable: true,
        },
        Some("CONFIG_CORRUPTED") => RuntimeCommandError {
            code: "CONFIG_CORRUPTED",
            message: "configuration storage is corrupted",
            retryable: false,
        },
        Some("REQUEST_DEADLINE_EXCEEDED") => RuntimeCommandError::deadline(),
        Some("INVALID_PARAMS") => RuntimeCommandError::invalid_params(),
        _ => RuntimeCommandError::unavailable(),
    }
}

/// 设计原因：该函数只发送固定 JA-RPC v1 方法并校验完整结果，不开放 generic passthrough。
/// 将返回 identifier 限制为与输入 ID 相同的有界字符集，防止不可信 sidecar 字符串成为 UI
/// 控制数据。
pub(super) fn valid_id(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.' | ':')
        })
}
