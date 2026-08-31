// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// RPC operations 只发送固定 JA-RPC v2 方法并校验完整结果。

use super::*;
use sha2::{Digest, Sha256};

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
    bound_turn_id: Option<String>,
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
fn parse_attachment_result(
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
            .bound_turn_id
            .as_deref()
            .is_some_and(|value| !valid_frozen_turn_id(value))
        || (wire.state == "bound") != wire.bound_turn_id.is_some()
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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
    let result = value
        .get("result")
        .and_then(Value::as_object)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let accepted = result
        .get("accepted")
        .and_then(Value::as_bool)
        .filter(|accepted| *accepted)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let turn_id = result
        .get("turnId")
        .and_then(Value::as_str)
        .filter(|value| valid_id(value, 128))
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let queued = result
        .get("queued")
        .and_then(Value::as_bool)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let thread_revision = result
        .get("threadRevision")
        .and_then(Value::as_u64)
        .filter(|revision| *revision <= 9_007_199_254_740_991)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let accepted = TurnAccepted {
        accepted,
        turn_id: turn_id.to_owned(),
        queued,
        thread_revision,
    };
    Ok(accepted)
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CommittedTurnChangeFileWire {
    path: String,
    #[serde(default)]
    old_path: Option<String>,
    status: String,
    #[serde(default)]
    additions: Option<u64>,
    #[serde(default)]
    deletions: Option<u64>,
    binary: bool,
    truncated: bool,
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CommittedTurnChangeStatsWire {
    files: u64,
    additions: u64,
    deletions: u64,
    binary_files: u64,
    truncated: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CommittedTurnChangeSetWire {
    state: String,
    #[serde(default)]
    reason: Option<String>,
    files: Vec<CommittedTurnChangeFileWire>,
    stats: CommittedTurnChangeStatsWire,
    #[serde(default)]
    artifact_id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TurnChangeCommitResultWire {
    accepted: bool,
    change_set: CommittedTurnChangeSetWire,
}

/// terminal 边界使用普通 client request 提交 frozen change-set；不引入 Java→Rust 反向 RPC。
pub(super) fn commit_turn_change_set_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    thread_id: &str,
    turn_id: &str,
    workspace_id: &str,
    change_set: &TurnChangeSet,
    exit_control: &ExitControl,
) -> Result<(), RuntimeCommandError> {
    if !thread_id.starts_with("thr_")
        || !valid_id(thread_id, 100)
        || !turn_id.starts_with("turn_")
        || !valid_id(turn_id, 101)
        || !workspace_id.starts_with("ws_")
        || !valid_id(workspace_id, 99)
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    let (mut params, expected, expects_artifact) = turn_change_set_params(change_set)?;
    params["threadId"] = json!(thread_id);
    params["turnId"] = json!(turn_id);
    params["workspaceId"] = json!(workspace_id);
    let result = fixed_artifact_request(
        config,
        runtime,
        "turn/change-set/commit",
        params,
        exit_control,
    )?;
    let result: TurnChangeCommitResultWire =
        serde_json::from_value(result).map_err(|_| RuntimeCommandError::unavailable())?;
    if !result.accepted
        || result.change_set.state != expected.state
        || result.change_set.reason != expected.reason
        || result.change_set.files != expected.files
        || result.change_set.stats != expected.stats
        || expects_artifact != result.change_set.artifact_id.is_some()
        || result
            .change_set
            .artifact_id
            .as_deref()
            .is_some_and(|value| !value.starts_with("artifact_") || !valid_id(value, 128))
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(())
}

/// 将 native domain 事实映射为唯一 JA-RPC params，并同时构造用于回读校验的无内容摘要。
fn turn_change_set_params(
    change_set: &TurnChangeSet,
) -> Result<(Value, CommittedTurnChangeSetWire, bool), RuntimeCommandError> {
    let (state, reason, files, stats, artifact) = match change_set {
        TurnChangeSet::Available {
            files,
            stats,
            artifact,
        } => {
            let files = files
                .iter()
                .map(|file| CommittedTurnChangeFileWire {
                    path: file.path.clone(),
                    old_path: file.old_path.clone(),
                    status: turn_file_status(file.status).to_owned(),
                    additions: file.additions,
                    deletions: file.deletions,
                    binary: file.binary,
                    truncated: file.truncated,
                })
                .collect::<Vec<_>>();
            let stats = CommittedTurnChangeStatsWire {
                files: stats.files,
                additions: stats.additions,
                deletions: stats.deletions,
                binary_files: stats.binary_files,
                truncated: stats.truncated,
            };
            ("available", None, files, stats, artifact.as_ref())
        }
        TurnChangeSet::Unavailable { reason } => (
            "unavailable",
            Some(reason.as_str().to_owned()),
            Vec::new(),
            CommittedTurnChangeStatsWire {
                files: 0,
                additions: 0,
                deletions: 0,
                binary_files: 0,
                truncated: false,
            },
            None,
        ),
    };
    let file_values = files
        .iter()
        .map(|file| {
            let mut value = json!({
                "path": file.path,
                "status": file.status,
                "binary": file.binary,
                "truncated": file.truncated,
            });
            if let Some(old_path) = file.old_path.as_ref() {
                value["oldPath"] = json!(old_path);
            }
            if let Some(additions) = file.additions {
                value["additions"] = json!(additions);
            }
            if let Some(deletions) = file.deletions {
                value["deletions"] = json!(deletions);
            }
            value
        })
        .collect::<Vec<_>>();
    let mut params = json!({
        "state": state,
        "files": file_values,
        "stats": {
            "files": stats.files,
            "additions": stats.additions,
            "deletions": stats.deletions,
            "binaryFiles": stats.binary_files,
            "truncated": stats.truncated,
        },
    });
    if let Some(reason) = reason.as_ref() {
        params["reason"] = json!(reason);
    }
    if let Some(artifact) = artifact {
        if artifact.unified_diff.len() as u64 != artifact.byte_length
            || artifact.byte_length > crate::review::domain::MAX_REVIEW_DIFF_BYTES as u64
            || artifact.sha256 != sha256_hex(artifact.unified_diff.as_bytes())
        {
            return Err(RuntimeCommandError::invalid_params());
        }
        params["artifact"] = json!({
            "sha256": artifact.sha256,
            "byteLength": artifact.byte_length,
            "unifiedDiff": artifact.unified_diff,
        });
    }
    Ok((
        params,
        CommittedTurnChangeSetWire {
            state: state.to_owned(),
            reason,
            files,
            stats,
            artifact_id: None,
        },
        artifact.is_some(),
    ))
}

/// 在 artifact 进入 JA-RPC 前重算固定小写十六进制摘要，防止 byteLength、正文和 identity
/// 因内部组装错误产生不可恢复的冻结记录。
fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

/// Turn change-set 只暴露四种可观察状态，内部 Review 扩展不得直接改变 wire。
fn turn_file_status(status: crate::review::ReviewFileStatus) -> &'static str {
    match status {
        crate::review::ReviewFileStatus::Added | crate::review::ReviewFileStatus::Untracked => {
            "added"
        }
        crate::review::ReviewFileStatus::Deleted => "deleted",
        crate::review::ReviewFileStatus::Renamed | crate::review::ReviewFileStatus::Copied => {
            "renamed"
        }
        crate::review::ReviewFileStatus::Modified | crate::review::ReviewFileStatus::Conflict => {
            "modified"
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TurnChangeSetReadWire {
    artifact_id: String,
    offset_bytes: u64,
    next_offset_bytes: Option<u64>,
    byte_length: u64,
    truncated: bool,
    content: String,
}

/// 按 Thread/Turn/Artifact 三元身份分页读取 frozen diff，并严格复核 byte offset。
pub(super) fn turn_change_set_read_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: TurnChangeSetReadInput,
    exit_control: &ExitControl,
) -> Result<TurnChangeSetReadResult, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    let expected_artifact_id = input.artifact_id.clone();
    let expected_offset = input.offset_bytes;
    let requested_limit = input.limit_bytes;
    let result = fixed_artifact_request(
        config,
        runtime,
        "turn/change-set/read",
        json!({
            "threadId": input.thread_id,
            "turnId": input.turn_id,
            "artifactId": input.artifact_id,
            "offsetBytes": input.offset_bytes,
            "limitBytes": input.limit_bytes,
        }),
        exit_control,
    )?;
    validate_turn_change_set_page(
        result,
        &expected_artifact_id,
        expected_offset,
        requested_limit,
    )
}

/// 对 Java 返回的冻结 diff 页执行第二层 identity、进度和调用方预算校验；严格递增的
/// `nextOffsetBytes` 防止 renderer 在损坏 artifact 上无限重读同一页。
pub(crate) fn validate_turn_change_set_page(
    result: Value,
    expected_artifact_id: &str,
    expected_offset: u64,
    requested_limit: u64,
) -> Result<TurnChangeSetReadResult, RuntimeCommandError> {
    let wire: TurnChangeSetReadWire =
        serde_json::from_value(result).map_err(|_| RuntimeCommandError::unavailable())?;
    let observed_end = wire.next_offset_bytes.unwrap_or(wire.byte_length);
    if wire.artifact_id != expected_artifact_id
        || wire.offset_bytes != expected_offset
        || wire.byte_length > crate::review::domain::MAX_REVIEW_DIFF_BYTES as u64
        || observed_end < wire.offset_bytes
        || observed_end > wire.byte_length
        || observed_end.saturating_sub(wire.offset_bytes) != wire.content.len() as u64
        || wire.content.len() as u64 > requested_limit
        || wire
            .next_offset_bytes
            .is_some_and(|next| next <= wire.offset_bytes)
        || wire.truncated != wire.next_offset_bytes.is_some()
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TurnChangeSetReadResult {
        artifact_id: wire.artifact_id,
        offset_bytes: wire.offset_bytes,
        next_offset_bytes: wire.next_offset_bytes,
        byte_length: wire.byte_length,
        truncated: wire.truncated,
        content: wire.content,
    })
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

/// 三个 artifact 方法共享同一 Ready supervisor、超时与 error envelope，但 method 仍是闭集。
fn fixed_artifact_request(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: &'static str,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    if !matches!(
        method,
        "turn/change-set/commit" | "turn/change-set/read" | "tool/artifact/read"
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
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
                "queued" | "running" | "waiting_approval" | "completed" | "failed" | "cancelled"
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

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
/// 通过当前 supervised session 发送一个 queued input 请求，并验证完整结果，阻止 malformed
/// Java 输出到达 UI。
pub(super) fn turn_queued_input_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: &'static str,
    params: Value,
    exit_control: &ExitControl,
) -> Result<TurnQueuedInputResult, RuntimeCommandError> {
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
    parse_turn_queued_input_result(&requested_turn_id, method, &value)
}

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
/// 强制匹配 Turn identity 与 method-specific kind；queue ordering 和 terminal race 只由 Java
/// transaction 裁决。
pub(super) fn parse_turn_queued_input_result(
    requested_turn_id: &str,
    method: &str,
    value: &Value,
) -> Result<TurnQueuedInputResult, RuntimeCommandError> {
    let result = value
        .get("result")
        .and_then(Value::as_object)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let expected_kind = if method == "turn/steer" {
        "steering"
    } else {
        "follow_up"
    };
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
    let turn_id = result
        .get("turnId")
        .and_then(Value::as_str)
        .filter(|value| *value == requested_turn_id)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let kind = result
        .get("kind")
        .and_then(Value::as_str)
        .filter(|value| *value == expected_kind)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let status = result
        .get("status")
        .and_then(Value::as_str)
        .filter(|value| *value == "queued")
        .ok_or_else(RuntimeCommandError::unavailable)?;
    Ok(TurnQueuedInputResult {
        accepted,
        input_id: input_id.to_owned(),
        turn_id: turn_id.to_owned(),
        kind: kind.to_owned(),
        status: status.to_owned(),
    })
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
        Some("TOKEN_COUNT_UNAVAILABLE") => RuntimeCommandError {
            code: "TOKEN_COUNT_UNAVAILABLE",
            message: "token count is unavailable",
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
        Some("REQUEST_DEADLINE_EXCEEDED") => RuntimeCommandError::deadline(),
        Some("INVALID_PARAMS") => RuntimeCommandError::invalid_params(),
        _ => RuntimeCommandError::unavailable(),
    }
}

/// 设计原因：该函数只发送固定 JA-RPC v2 方法并校验完整结果，不开放 generic passthrough。
/// 将返回 identifier 限制为与输入 ID 相同的有界字符集，防止不可信 sidecar 字符串成为 UI
/// 控制数据。
pub(super) fn valid_id(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.' | ':')
        })
}
