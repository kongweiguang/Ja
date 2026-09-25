// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Ja App Server 进程错误到 application 错误的稳定映射。

use crate::app_runtime::RuntimeCommandError;
use ja_runtime::app_server_process::AppServerProcessError;

impl RuntimeCommandError {
    /// 将 foundation 错误映射为稳定桌面类别与重试策略，不泄漏内部错误文本。
    pub(crate) fn from_process(error: &AppServerProcessError) -> Self {
        match error {
            AppServerProcessError::InvalidConfig => Self::configuration(),
            AppServerProcessError::Incompatible => Self {
                code: "PROTOCOL_INCOMPATIBLE",
                message: "sidecar protocol is incompatible",
                retryable: false,
            },
            AppServerProcessError::Faulted => Self {
                code: "RUNTIME_FAULTED",
                message: "runtime is faulted",
                retryable: false,
            },
            AppServerProcessError::Backoff { .. } => Self {
                code: "RUNTIME_BACKOFF",
                message: "runtime restart is cooling down",
                retryable: true,
            },
            AppServerProcessError::ShuttingDown => Self {
                code: "SHUTTING_DOWN",
                message: "runtime is shutting down",
                retryable: true,
            },
            AppServerProcessError::NotReady | AppServerProcessError::InvalidState => Self {
                code: "RUNTIME_NOT_READY",
                message: "runtime is not ready",
                retryable: true,
            },
            AppServerProcessError::DeadlineExceeded | AppServerProcessError::ShutdownTimeout => {
                Self {
                    code: "RUNTIME_TIMEOUT",
                    message: "runtime operation timed out",
                    retryable: true,
                }
            }
            AppServerProcessError::ProcessExited
            | AppServerProcessError::ProcessTree
            | AppServerProcessError::Spawn
            | AppServerProcessError::SessionClosed => Self {
                code: "SIDECAR_CRASHED",
                message: "sidecar process is unavailable",
                retryable: true,
            },
            AppServerProcessError::Codec(_)
            | AppServerProcessError::HandshakeFailed
            | AppServerProcessError::ProtocolFault
            | AppServerProcessError::QueueFull(_)
            | AppServerProcessError::QueueClosed(_)
            | AppServerProcessError::PendingLimit
            | AppServerProcessError::RequestLedgerExhausted
            | AppServerProcessError::DuplicateRequest
            | AppServerProcessError::Cancelled
            | AppServerProcessError::InvalidTimeout
            | AppServerProcessError::InvalidErrorCatalog => Self {
                code: "RUNTIME_PROTOCOL_ERROR",
                message: "runtime protocol operation failed",
                retryable: false,
            },
        }
    }
}
