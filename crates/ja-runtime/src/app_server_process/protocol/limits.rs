// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! JA-RPC v2 协商资源上限；这里只维护数值不变量与 initialize 投影。

use super::error_policy::CodecError;
use serde_json::Value;

pub const DEFAULT_MAX_FRAME_BYTES: usize = 4 * 1024 * 1024;
pub(super) const MIN_MAX_FRAME_BYTES: usize = 1024;
pub(super) const MAX_MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;
pub(super) const MAX_REQUEST_ID_BYTES: usize = 98;
pub(super) const MAX_METHOD_BYTES: usize = 128;
/// 协商后的有界资源，使用协议默认值保持 Java 与 Rust 首次握手一致。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Limits {
    pub max_frame_bytes: usize,
    pub inbound_queue_frames: usize,
    pub outbound_queue_frames: usize,
    pub max_in_flight_requests: usize,
    pub max_pending_requests: usize,
    pub max_tombstones: usize,
    pub max_stderr_line_bytes: usize,
    pub max_log_bytes: usize,
    pub request_deadline_ms: u64,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_frame_bytes: DEFAULT_MAX_FRAME_BYTES,
            inbound_queue_frames: 256,
            outbound_queue_frames: 1_024,
            max_in_flight_requests: 64,
            max_pending_requests: 64,
            max_tombstones: 128,
            max_stderr_line_bytes: 64 * 1024,
            max_log_bytes: 1_048_576,
            // 手动上下文压缩的 Java 绝对预算为五分钟；Session 上限略大于业务预算，
            // 仅让专用 thread/compact 调用有机会接收终态响应，其他方法仍由各自短 timeout 约束。
            request_deadline_ms: 305_000,
        }
    }
}

impl Limits {
    /// 拒绝超过协议硬上限的本地配置，避免一次错误配置让 parser 无界分配。
    pub fn validate(&self) -> Result<(), CodecError> {
        if !(MIN_MAX_FRAME_BYTES..=MAX_MAX_FRAME_BYTES).contains(&self.max_frame_bytes) {
            return Err(CodecError::InvalidLimit);
        }
        if !(1..=256).contains(&self.inbound_queue_frames)
            || !(1..=1_024).contains(&self.outbound_queue_frames)
            || !(1..=64).contains(&self.max_in_flight_requests)
            || !(1..=64).contains(&self.max_pending_requests)
            || !(1..=8_192).contains(&self.max_tombstones)
            || !(1..=1_048_576).contains(&self.max_stderr_line_bytes)
            || !(4_096..=67_108_864).contains(&self.max_log_bytes)
            || !(1_000..=3_600_000).contains(&self.request_deadline_ms)
        {
            return Err(CodecError::InvalidLimit);
        }
        Ok(())
    }

    /// 把 host 的限制转换成 initialize 需要的 object，保持字段名与冻结 Schema 一致。
    pub fn to_value(&self) -> Value {
        serde_json::json!({
            "maxFrameBytes": self.max_frame_bytes,
            "maxInboundQueueFrames": self.inbound_queue_frames,
            "maxControlOutboundQueueFrames": 64,
            "maxDataOutboundQueueFrames": self.outbound_queue_frames,
            "maxInFlightRequests": self.max_in_flight_requests,
            "maxConcurrentTurns": 8,
            "maxAdmittedTurns": self.max_pending_requests,
            "maxThreadQueuedTurns": 8,
            "maxSnapshotPageItems": 200,
            "maxToolBatchConcurrency": 8,
        })
    }
}
