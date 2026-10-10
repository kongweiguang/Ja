// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Plan 与 Interaction 使用固定 JA-RPC method lane。

use super::*;

/// Plan 与 Interaction 请求复用当前 supervised session 与取消预算，只接受固定方法闭集。
pub(super) fn workflow_request_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: WorkflowMethod,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)
        .map_err(|error| workflow_request_failed(method, "runtime", error))?;
    if let Some(session) = current.client.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)
        .map_err(|error| workflow_request_failed(method, "deadline", error))?;
    let response = current
        .client
        .request(method.wire_name(), params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))
        .map_err(|error| workflow_request_failed(method, "request", error))?;
    let value = frame_to_value(&response)
        .map_err(|_| RuntimeCommandError::unavailable())
        .map_err(|error| workflow_request_failed(method, "frame", error))?;
    if let Some(error) = value.get("error") {
        return Err(workflow_request_failed(
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
        .map_err(|error| workflow_request_failed(method, "result_shape", error))
}

/// 日志只保留固定 method、阶段和稳定错误码，避免请求正文或计划证据泄漏。
fn workflow_request_failed(
    method: WorkflowMethod,
    stage: &'static str,
    error: RuntimeCommandError,
) -> RuntimeCommandError {
    tracing::warn!(
        request_method = method.wire_name(),
        request_stage = stage,
        error_code = error.code,
        "workflow bridge request failed"
    );
    error
}
