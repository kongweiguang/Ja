// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 固定工作流操作的冻结身份及不可解释 payload。

const MAX_WORKFLOW_PAYLOAD_BYTES: usize = 4 * 1024 * 1024;

/// 计划与交互请求 payload 只暴露稳定的无敏感拒绝原因，由 application 统一映射到 IPC 错误。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct WorkflowPayloadError;

/// JA-RPC 工作流方法闭集；variant 同时承担 request/result 配对身份，renderer 不能指定方法名。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WorkflowMethod {
    PlanRead,
    PlanCurrentRead,
    PlanRevisionsList,
    InteractionRead,
    InteractionObserve,
    InteractionUnobserve,
    InteractionDraftSave,
    InteractionRespond,
    InteractionCancel,
    PlanCreate,
    PlanDraftSave,
    PlanDraftDiscard,
    PlanPropose,
    PlanExecute,
    PlanObserve,
    PlanUnobserve,
    PlanEventsRead,
    PlanEvidenceList,
    PlanPause,
    PlanResume,
    PlanStop,
    PlanReject,
    ProcessList,
    ProcessRead,
    ProcessWrite,
    ProcessStop,
    ReviewStart,
    ReviewRead,
    ThreadFork,
}

impl WorkflowMethod {
    /// wire name 只从闭集映射，防止 renderer 或 application 拼接任意 JA-RPC 方法。
    pub(crate) const fn wire_name(self) -> &'static str {
        match self {
            Self::PlanRead => "plan/read",
            Self::PlanCurrentRead => "plan/current/read",
            Self::PlanRevisionsList => "plan/revisions/list",
            Self::InteractionRead => "interaction/read",
            Self::InteractionObserve => "interaction/observe",
            Self::InteractionUnobserve => "interaction/unobserve",
            Self::InteractionDraftSave => "interaction/draft/save",
            Self::InteractionRespond => "interaction/respond",
            Self::InteractionCancel => "interaction/cancel",
            Self::PlanCreate => "plan/create",
            Self::PlanDraftSave => "plan/draft/save",
            Self::PlanDraftDiscard => "plan/draft/discard",
            Self::PlanPropose => "plan/propose",
            Self::PlanExecute => "plan/execute",
            Self::PlanObserve => "plan/observe",
            Self::PlanUnobserve => "plan/unobserve",
            Self::PlanEventsRead => "plan/events/read",
            Self::PlanEvidenceList => "plan/evidence/list",
            Self::PlanPause => "plan/pause",
            Self::PlanResume => "plan/resume",
            Self::PlanStop => "plan/stop",
            Self::PlanReject => "plan/reject",
            Self::ProcessList => "process/list",
            Self::ProcessRead => "process/read",
            Self::ProcessWrite => "process/write",
            Self::ProcessStop => "process/stop",
            Self::ReviewStart => "review/start",
            Self::ReviewRead => "review/read",
            Self::ThreadFork => "thread/fork",
        }
    }
}

/// payload 在 native interface 完成 typed serde 后封装；中间层只能整体转交。
pub(crate) struct WorkflowPayload(Vec<u8>);

impl WorkflowPayload {
    /// payload 必须非空且服从协议帧上限，避免专用方法 lane 绕开既有内存预算。
    pub(crate) fn try_new(bytes: Vec<u8>) -> Result<Self, WorkflowPayloadError> {
        if bytes.is_empty() || bytes.len() > MAX_WORKFLOW_PAYLOAD_BYTES {
            return Err(WorkflowPayloadError);
        }
        Ok(Self(bytes))
    }

    /// 仅供固定 bridge/interface 消费完整所有权，不暴露可变视图或业务字段。
    pub(crate) fn into_bytes(self) -> Vec<u8> {
        self.0
    }
}

/// 请求把 method 与已验证 payload 固定为一个不可拆分 application value。
pub(crate) struct WorkflowRequest {
    pub(crate) method: WorkflowMethod,
    pub(crate) payload: WorkflowPayload,
}

/// 响应保留原 method，interface 必须验证与请求精确配对后才能反序列化。
pub(crate) struct WorkflowResponse {
    pub(crate) method: WorkflowMethod,
    pub(crate) payload: WorkflowPayload,
}
