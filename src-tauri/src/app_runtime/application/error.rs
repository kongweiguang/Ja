// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime application 的稳定脱敏错误。

use serde::Serialize;

use crate::app_runtime::GoalPayloadError;

/// 稳定且脱敏的命令错误；内部进程错误仅保留在日志中，WebView 不得接收路径、token、stack 或 child command。
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeCommandError {
    pub code: &'static str,
    pub message: &'static str,
    pub retryable: bool,
}

impl std::fmt::Display for RuntimeCommandError {
    /// 保持 setup 与 invoke 错误转换稳定，同时不暴露内部诊断信息。
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.message)
    }
}

impl std::error::Error for RuntimeCommandError {}

impl From<GoalPayloadError> for RuntimeCommandError {
    /// 领域层只表达 payload 不可接纳，application 在唯一边界收敛为稳定参数错误。
    fn from(_: GoalPayloadError) -> Self {
        Self::invalid_params()
    }
}

impl RuntimeCommandError {
    /// 为受信任启动设置生成唯一配置错误形态，避免调用方依赖内部原因。
    pub(crate) const fn configuration() -> Self {
        Self {
            code: "RUNTIME_CONFIG_INVALID",
            message: "runtime configuration is invalid",
            retryable: false,
        }
    }

    /// 在 IPC 边界拒绝畸形命令字段，防止其进入 Java 协议层。
    pub(crate) const fn invalid_params() -> Self {
        Self {
            code: "INVALID_PARAMS",
            message: "runtime request parameters are invalid",
            retryable: false,
        }
    }

    /// 表示 queue/actor 生命周期失败，但不携带可能泄密的诊断内容。
    pub(crate) const fn unavailable() -> Self {
        Self {
            code: "RUNTIME_UNAVAILABLE",
            message: "runtime bridge is unavailable",
            retryable: true,
        }
    }

    /// 向调用方返回稳定 backpressure 结果，避免 Tauri command 一直阻塞到 actor 最终排空有界队列。
    pub(crate) const fn queue_full() -> Self {
        Self {
            code: "RUNTIME_QUEUE_FULL",
            message: "runtime bridge queue is full",
            retryable: true,
        }
    }

    /// 将有界命令 deadline 与通用 sidecar 失败区分开，使调用方可重试且不暴露 child 细节。
    pub(crate) const fn deadline() -> Self {
        Self {
            code: "RUNTIME_COMMAND_DEADLINE",
            message: "runtime request deadline exceeded",
            retryable: true,
        }
    }

    /// 让命令调用方与 lifecycle actor 均可观察原生事件投递失败，但不转发原生错误文本。
    pub(crate) const fn event_delivery() -> Self {
        Self {
            code: "RUNTIME_EVENT_DELIVERY_FAILED",
            message: "runtime event delivery failed",
            retryable: true,
        }
    }

    /// 暴露 cleanup deadline，使 worker 仍持有活动进程或 event-pump handle 时 Tauri 可阻止退出。
    pub(crate) const fn shutdown_timeout() -> Self {
        Self {
            code: "RUNTIME_SHUTDOWN_TIMEOUT",
            message: "runtime shutdown timed out",
            retryable: true,
        }
    }

    /// 先前强制退出仍需用户显式恢复并确认 marker 时，阻止新 sidecar 启动。
    pub(crate) const fn recovery_required() -> Self {
        Self {
            code: "RECOVERY_REQUIRED",
            message: "manual runtime recovery is required",
            retryable: false,
        }
    }

    /// 拒绝来自旧原生投影的确认；只有当前 recovery identity/revision 才能清除门禁。
    pub(crate) const fn recovery_stale() -> Self {
        Self {
            code: "RECOVERY_STALE",
            message: "runtime recovery confirmation is stale",
            retryable: false,
        }
    }
}
