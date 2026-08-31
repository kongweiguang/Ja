// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! sidecar host 对外错误。
//!
//! 这里故意不携带 command、绝对路径、环境变量或底层错误字符串；日志可以在
//! 上层用诊断 ID 关联，但 UI/协议错误不能把 secret 或用户目录泄露出去。

use crate::app_server_process::protocol::CodecError;
use std::fmt::{Display, Formatter};
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QueueKind {
    Control,
    Data,
    Stderr,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AppServerProcessError {
    InvalidConfig,
    InvalidTimeout,
    Codec(CodecError),
    QueueFull(QueueKind),
    QueueClosed(QueueKind),
    PendingLimit,
    RequestLedgerExhausted,
    DuplicateRequest,
    DeadlineExceeded,
    Cancelled,
    SessionClosed,
    NotReady,
    Incompatible,
    InvalidState,
    Spawn,
    ProcessTree,
    ProcessExited,
    ShuttingDown,
    HandshakeFailed,
    ProtocolFault,
    InvalidErrorCatalog,
    ShutdownTimeout,
    Backoff { retry_after: Duration },
    Faulted,
}

impl Display for AppServerProcessError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidConfig => formatter.write_str("invalid sidecar configuration"),
            Self::InvalidTimeout => formatter.write_str("sidecar timeout exceeds hard limit"),
            Self::Codec(error) => write!(formatter, "sidecar protocol framing failed: {error}"),
            Self::QueueFull(kind) => write!(formatter, "sidecar {kind:?} queue is full"),
            Self::QueueClosed(kind) => write!(formatter, "sidecar {kind:?} queue is closed"),
            Self::PendingLimit => formatter.write_str("sidecar pending request limit reached"),
            Self::RequestLedgerExhausted => {
                formatter.write_str("sidecar request id ledger exhausted")
            }
            Self::DuplicateRequest => formatter.write_str("duplicate sidecar request"),
            Self::DeadlineExceeded => formatter.write_str("sidecar request deadline exceeded"),
            Self::Cancelled => formatter.write_str("sidecar request cancelled"),
            Self::SessionClosed => formatter.write_str("sidecar session closed"),
            Self::NotReady => formatter.write_str("sidecar is not ready"),
            Self::Incompatible => formatter.write_str("sidecar protocol is incompatible"),
            Self::InvalidState => formatter.write_str("invalid sidecar lifecycle state"),
            Self::Spawn => formatter.write_str("sidecar process could not be started"),
            Self::ProcessTree => formatter.write_str("sidecar process tree cleanup failed"),
            Self::ProcessExited => formatter.write_str("sidecar process exited"),
            Self::ShuttingDown => formatter.write_str("sidecar is shutting down"),
            Self::HandshakeFailed => formatter.write_str("sidecar handshake failed"),
            Self::ProtocolFault => formatter.write_str("sidecar protocol fault"),
            Self::InvalidErrorCatalog => {
                formatter.write_str("unsupported sidecar error catalog entry")
            }
            Self::ShutdownTimeout => formatter.write_str("sidecar shutdown deadline exceeded"),
            Self::Backoff { retry_after } => {
                write!(formatter, "sidecar restart is in backoff ({retry_after:?})")
            }
            Self::Faulted => formatter.write_str("sidecar host is faulted"),
        }
    }
}

impl std::error::Error for AppServerProcessError {}

impl From<CodecError> for AppServerProcessError {
    /// 保留 codec 的稳定分类，让 session 不把原始 frame 内容泄露到错误消息。
    fn from(error: CodecError) -> Self {
        if matches!(error, CodecError::HandshakeFailed) {
            Self::HandshakeFailed
        } else {
            Self::Codec(error)
        }
    }
}
