// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use serde_json::{Value, json};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum SuspendedTurnAction {
    Resume,
    Cancel,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct PendingSuspendedControl {
    pub(super) thread_id: String,
    pub(super) turn_id: String,
    pub(super) action: SuspendedTurnAction,
    pub(super) acknowledged: bool,
}

/// FnOnce 把一次恢复操作限制为单次 RPC；未知结果必须由快照核实，不能自动重发。
pub(super) fn request_once<E>(
    action: SuspendedTurnAction,
    turn_id: &str,
    expected_thread_revision: u64,
    request: impl FnOnce(&str, Value) -> Result<Value, E>,
) -> Result<Value, E> {
    let (method, params) = match action {
        SuspendedTurnAction::Resume => (
            "turn/resume",
            json!({
                "turnId": turn_id,
                "expectedThreadRevision": expected_thread_revision,
            }),
        ),
        SuspendedTurnAction::Cancel => ("turn/cancel", json!({"turnId":turn_id})),
    };
    request(method, params)
}

/// 回执必须指回原 Turn 并符合当前协议的成功形态，缺字段时按未知结果处理。
pub(super) fn confirms_acknowledgement(
    action: SuspendedTurnAction,
    turn_id: &str,
    previous_revision: u64,
    response: &Value,
) -> bool {
    if response.get("accepted").and_then(Value::as_bool) != Some(true)
        || response.get("turnId").and_then(Value::as_str) != Some(turn_id)
        || response
            .get("threadRevision")
            .and_then(Value::as_u64)
            .is_none_or(|revision| revision < previous_revision)
    {
        return false;
    }
    match action {
        SuspendedTurnAction::Resume => {
            response.get("queued").and_then(Value::as_bool) == Some(true)
        }
        SuspendedTurnAction::Cancel => response
            .get("status")
            .and_then(Value::as_str)
            .is_some_and(is_terminal_status),
    }
}

/// 仅同一 Thread 的读回可对账；挂起以外的活动态或终态证明控制状态已变化。
pub(super) fn observed_status<'a>(
    history: &'a Value,
    expected_thread_id: &str,
    turn_id: &str,
) -> Option<&'a str> {
    if history.get("threadId").and_then(Value::as_str) != Some(expected_thread_id) {
        return None;
    }
    history
        .get("turns")?
        .as_array()?
        .iter()
        .find(|turn| turn.get("turnId").and_then(Value::as_str) == Some(turn_id))?
        .get("status")?
        .as_str()
}

/// 队列与等待审批/输入可完成 Resume 对账；Cancel 仅以真实终态证明收口。
pub(super) fn control_state_resolved(status: &str) -> bool {
    matches!(
        status,
        "queued"
            | "running"
            | "waiting_input"
            | "waiting_approval"
            | "completed"
            | "failed"
            | "cancelled"
    )
}

/// 取消不得把排队或仍运行误报为完成；挂起态只有显式 F5 后可供用户重新选择动作。
pub(super) fn may_release_control_lock(
    action: SuspendedTurnAction,
    status: &str,
    explicit_read: bool,
) -> bool {
    if status == "suspended" {
        return explicit_read;
    }
    match action {
        SuspendedTurnAction::Resume => control_state_resolved(status),
        SuspendedTurnAction::Cancel => is_terminal_status(status),
    }
}

/// 活动 Turn、普通读重试或挂起控制回执任一存在时复用 Thread 历史轮询节奏。
pub(super) fn requires_history_poll(
    latest_turn_is_active: bool,
    refresh_pending: bool,
    suspended_control_pending: bool,
) -> bool {
    latest_turn_is_active || refresh_pending || suspended_control_pending
}

/// 终态是取消回执可返回的现有事实，不接受 suspended 或未知状态伪装为取消完成。
fn is_terminal_status(status: &str) -> bool {
    matches!(status, "completed" | "failed" | "cancelled")
}
