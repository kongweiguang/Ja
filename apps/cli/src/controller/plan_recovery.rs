// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use serde_json::Value;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(in crate::controller) enum PlanRunAction {
    Execute,
    Pause,
    Resume,
    Stop,
}

/// Wire ownership is nested so this check follows the Java Plan aggregate contract before reusing a draft.
pub(in crate::controller) fn can_resume_plan_creation(
    current: &Value,
    owner_thread_id: &str,
    objective: &str,
) -> Result<bool, &'static str> {
    let plan = current
        .get("plan")
        .filter(|plan| !plan.is_null())
        .ok_or("Plan 快照缺少 plan")?;
    let owner = plan
        .get("owner")
        .and_then(|owner| owner.get("threadId"))
        .and_then(Value::as_str)
        .ok_or("Plan 快照缺少 owner.threadId")?;
    if owner != owner_thread_id {
        return Err("Plan 所属会话与当前 Thread 不一致");
    }
    let status = plan
        .get("status")
        .and_then(Value::as_str)
        .ok_or("Plan 快照缺少 status")?
        .to_ascii_lowercase();
    if matches!(status.as_str(), "completed" | "stopped") {
        return Ok(false);
    }
    let current_objective = plan
        .get("objective")
        .and_then(Value::as_str)
        .ok_or("Plan 快照缺少 objective")?;
    if current_objective != objective {
        return Err("当前会话已有其他未完成 Plan；请先运行 /plan status 查看");
    }
    if status == "draft" {
        return Ok(true);
    }
    Err("当前目标已有非草稿 Plan；请运行 /plan status 查看")
}

/// 只有同一 Plan 的 revision 前进且出现目标 Run 状态，才把未知写回执视为已应用。
pub(in crate::controller) fn confirms_plan_transition(
    action: PlanRunAction,
    plan_id: &str,
    previous_revision: u64,
    previous_run_id: Option<&str>,
    current: &Value,
) -> bool {
    let Some(current_plan_id) = current.get("planId").and_then(Value::as_str) else {
        return false;
    };
    let Some(current_revision) = current.get("revision").and_then(Value::as_u64) else {
        return false;
    };
    let Some(status) = current.get("status").and_then(Value::as_str) else {
        return false;
    };
    if current_plan_id != plan_id || current_revision <= previous_revision {
        return false;
    }
    let current_run_id = current.get("activeRunId").and_then(Value::as_str);
    let same_run = current_run_id.is_some() && current_run_id == previous_run_id;
    match action {
        PlanRunAction::Execute => {
            current_run_id.is_some_and(|run_id| Some(run_id) != previous_run_id)
                && matches!(status, "executing" | "verifying" | "paused" | "completed")
        }
        PlanRunAction::Pause => same_run && status == "paused",
        PlanRunAction::Resume => {
            same_run && matches!(status, "executing" | "verifying" | "completed")
        }
        PlanRunAction::Stop => same_run && status == "stopped",
    }
}
