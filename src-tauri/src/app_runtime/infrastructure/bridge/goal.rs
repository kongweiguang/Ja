// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Goal/Plan 固定 JA-RPC lane。

use super::*;

/// Goal 请求复用当前 supervised session 与取消预算；只接受 `GoalMethod` 闭集。
pub(super) fn goal_request_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: GoalMethod,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)
        .map_err(|error| goal_request_failed(method, "runtime", error))?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)
        .map_err(|error| goal_request_failed(method, "deadline", error))?;
    let response = current
        .supervisor
        .request(method.wire_name(), params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))
        .map_err(|error| goal_request_failed(method, "request", error))?;
    let value = frame_to_value(&response)
        .map_err(|_| RuntimeCommandError::unavailable())
        .map_err(|error| goal_request_failed(method, "frame", error))?;
    if let Some(error) = value.get("error") {
        return Err(goal_request_failed(
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
        .map_err(|error| goal_request_failed(method, "result_shape", error))
}

/// 日志只保留固定 method、阶段和稳定错误码，避免 Goal objective/plan/evidence 泄漏。
fn goal_request_failed(
    method: GoalMethod,
    stage: &'static str,
    error: RuntimeCommandError,
) -> RuntimeCommandError {
    tracing::warn!(
        goal_method = method.wire_name(),
        goal_stage = stage,
        error_code = error.code,
        "goal bridge request failed"
    );
    error
}
