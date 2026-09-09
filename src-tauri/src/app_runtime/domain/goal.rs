// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Goal/Plan 的冻结操作身份与不可解释 payload。

const MAX_GOAL_PAYLOAD_BYTES: usize = 4 * 1024 * 1024;

/// Goal payload 只暴露无敏感信息的领域拒绝原因，由 application 统一映射到 IPC 错误。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct GoalPayloadError;

/// Goal/Plan JA-RPC 方法闭集；variant 同时承担 request/result 配对身份。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum GoalMethod {
    GoalRead,
    GoalEventsRead,
    GoalObserve,
    GoalUnobserve,
    PlanRead,
    PlanRevisionsList,
    GoalEvidenceList,
    GoalCreate,
    GoalPlanAttach,
    GoalPlanDetach,
    GoalPause,
    GoalResume,
    GoalStop,
    GoalInputRespond,
    PlanCreate,
    PlanDraftSave,
    PlanDraftDiscard,
    PlanPropose,
    PlanApprove,
    PlanExecute,
    PlanReject,
}

impl GoalMethod {
    /// wire name 只从闭集映射，防止 renderer 或 application 拼接任意 JA-RPC 方法。
    pub(crate) const fn wire_name(self) -> &'static str {
        match self {
            Self::GoalRead => "goal/read",
            Self::GoalEventsRead => "goal/events/read",
            Self::GoalObserve => "goal/observe",
            Self::GoalUnobserve => "goal/unobserve",
            Self::PlanRead => "plan/read",
            Self::PlanRevisionsList => "plan/revisions/list",
            Self::GoalEvidenceList => "goal/evidence/list",
            Self::GoalCreate => "goal/create",
            Self::GoalPlanAttach => "goal/plan/attach",
            Self::GoalPlanDetach => "goal/plan/detach",
            Self::GoalPause => "goal/pause",
            Self::GoalResume => "goal/resume",
            Self::GoalStop => "goal/stop",
            Self::GoalInputRespond => "goal/input/respond",
            Self::PlanCreate => "plan/create",
            Self::PlanDraftSave => "plan/draft/save",
            Self::PlanDraftDiscard => "plan/draft/discard",
            Self::PlanPropose => "plan/propose",
            Self::PlanApprove => "plan/approve",
            Self::PlanExecute => "plan/execute",
            Self::PlanReject => "plan/reject",
        }
    }
}

/// payload 在 native interface 完成 typed serde 后封装；中间层只能整体转交。
pub(crate) struct GoalPayload(Vec<u8>);

impl GoalPayload {
    /// payload 必须非空且服从协议帧上限，避免 Goal lane 绕开既有内存预算。
    pub(crate) fn try_new(bytes: Vec<u8>) -> Result<Self, GoalPayloadError> {
        if bytes.is_empty() || bytes.len() > MAX_GOAL_PAYLOAD_BYTES {
            return Err(GoalPayloadError);
        }
        Ok(Self(bytes))
    }

    /// 仅供固定 bridge/interface 消费完整所有权，不暴露可变视图或业务字段。
    pub(crate) fn into_bytes(self) -> Vec<u8> {
        self.0
    }
}

/// 请求把 method 与已验证 payload 固定为一个不可拆分 application value。
pub(crate) struct GoalRequest {
    pub(crate) method: GoalMethod,
    pub(crate) payload: GoalPayload,
}

/// 响应保留原 method，interface 必须验证与请求精确配对后才能反序列化。
pub(crate) struct GoalResponse {
    pub(crate) method: GoalMethod,
    pub(crate) payload: GoalPayload,
}
