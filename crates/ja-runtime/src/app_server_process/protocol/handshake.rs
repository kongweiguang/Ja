// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! initialize 握手与配置协议校验。
//!
//! 校验独立于进程监督，避免协议 schema 与 child 生命周期清理互相耦合。

use super::catalog::{V1_CLIENT_METHODS, V1_EVENT_METHODS};
use super::frame::RpcFrame;
use super::limits::{Limits, MIN_MAX_FRAME_BYTES};
use crate::app_server_process::error::AppServerProcessError;
use serde_json::Value;
use std::collections::HashSet;
#[cfg(unix)]
use std::io::Read;
use std::time::{Duration, Instant};

const READY_TOKEN_BYTES: usize = 16;
const READY_TOKEN_HEX_BYTES: usize = READY_TOKEN_BYTES * 2;

pub(crate) const MAX_READY_TIMEOUT: Duration = Duration::from_secs(600);
pub(crate) const MAX_SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(120);

/// 构造不携带业务配置的 v1 initialize。配置、凭据和 workspace 解析均由
/// Ja App Server 自己读取/持有；Rust 只协商 transport capability 与资源预算。
pub(crate) fn default_initialize_params(limits: &Limits) -> Value {
    serde_json::json!({
        "protocolMajor": 1,
        "protocolMinor": 0,
        "clientVersion": "ja-host",
        "capabilities": {
            "methods": V1_CLIENT_METHODS,
            "events": V1_EVENT_METHODS,
            "accessModes": ["approval_required", "full_access"],
            "collaborationModes": ["default", "plan"],
            "features": ["task_threads_v1", "plan_goal_v1"]
        },
        "limits": limits.to_value(),
    })
}

/// 只允许 sidecar 运行所需的稳定环境变量，避免隐式继承凭据或用户状态。
pub(crate) fn allowed_env_name(name: &str) -> bool {
    matches!(
        name,
        "JA_LOG_LEVEL"
            | "JA_DATA_DIR"
            | "RUST_LOG"
            | "LANG"
            | "LC_ALL"
            | "TMPDIR"
            | "SystemRoot"
            | "PATH"
            | "ComSpec"
            | "PSModuleAnalysisCachePath"
            | "SystemDrive"
            | "WINDIR"
            | "TEMP"
            | "TMP"
    )
}

/// 检查参数/环境名中的凭据标记，防止 secret 通过不可审计启动边界泄露。
pub(crate) fn contains_secret_marker(value: &str) -> bool {
    let value = value.to_ascii_lowercase();
    [
        "secret", "token", "password", "api_key", "apikey", "bearer", "cookie",
    ]
    .iter()
    .any(|marker| value.contains(marker))
}

/// 在启动前完整验证 host initialize，保证握手发送的声明就是 session 实际使用的预算。
pub(crate) fn validate_initialize_params(
    value: &Value,
    local: &Limits,
) -> Result<(), AppServerProcessError> {
    let object = value
        .as_object()
        .ok_or(AppServerProcessError::InvalidConfig)?;
    if object.get("protocolMajor").and_then(Value::as_i64) != Some(1) {
        return Err(AppServerProcessError::InvalidConfig);
    }
    if object.get("protocolMinor").and_then(Value::as_i64) != Some(0) {
        return Err(AppServerProcessError::InvalidConfig);
    }
    if !bounded_string(object.get("clientVersion"), 128) {
        return Err(AppServerProcessError::InvalidConfig);
    }
    validate_capabilities(object.get("capabilities"))?;
    validate_remote_limits(object.get("limits"), local)
        .map_err(|_| AppServerProcessError::InvalidConfig)?;
    Ok(())
}

/// 检查 server 错误是否明确宣告协议不兼容，避免普通 fault 被误标为配置问题。
/// 只把明确的协议版本错误映射为 Incompatible，普通错误仍进入 Faulted。
pub(crate) fn error_is_incompatible(code: i64, data: &Value) -> bool {
    code == -32_003
        && data.get("errorCode").and_then(Value::as_str) == Some("PROTOCOL_VERSION_UNSUPPORTED")
        && data.get("category").and_then(Value::as_str) == Some("protocol")
        && data.get("retryable").and_then(Value::as_bool) == Some(false)
}

/// 只接受 initialize 完成后、同一 server instance 发出的结构化 ready notification。
pub(crate) fn is_ready_notification(frame: &RpcFrame, expected_instance: Option<&str>) -> bool {
    frame.method() == Some("runtime/status-changed")
        && frame
            .params()
            .and_then(|params| params.get("status"))
            .and_then(Value::as_str)
            == Some("ready")
        && frame
            .params()
            .and_then(|params| params.get("serverInstanceId"))
            .and_then(Value::as_str)
            == expected_instance
        && frame
            .params()
            .and_then(|params| params.get("eventId"))
            .and_then(Value::as_str)
            .is_some_and(|id| valid_schema_id(id, "evt_", 100))
        && frame
            .params()
            .and_then(|params| params.get("occurredAt"))
            .and_then(Value::as_str)
            .is_some_and(valid_timestamp)
}

/// 判断 frame 是否是 runtime ready 控制事实；其余字段由严格握手校验继续验证，
/// 这样缺失 token 或错误 instance 不会被当成普通事件拖到 deadline 才暴露。
pub(crate) fn is_runtime_ready_notification(frame: &RpcFrame) -> bool {
    frame.method() == Some("runtime/status-changed")
        && frame
            .params()
            .and_then(|params| params.get("status"))
            .and_then(Value::as_str)
            == Some("ready")
}

/// 从操作系统 CSPRNG 生成每个 generation 一次的新 challenge；失败必须让握手失败。
pub(crate) fn generate_ready_token() -> Result<String, AppServerProcessError> {
    let mut bytes = [0_u8; READY_TOKEN_BYTES];
    fill_csprng(&mut bytes)?;
    let mut token = String::with_capacity(READY_TOKEN_HEX_BYTES);
    for byte in bytes {
        const HEX: &[u8; 16] = b"0123456789abcdef";
        token.push(HEX[(byte >> 4) as usize] as char);
        token.push(HEX[(byte & 0x0f) as usize] as char);
    }
    Ok(token)
}

/// 选择平台原生随机源，拒绝时间、线程序号等可预测替代物。
fn fill_csprng(bytes: &mut [u8; READY_TOKEN_BYTES]) -> Result<(), AppServerProcessError> {
    #[cfg(windows)]
    {
        #[link(name = "bcrypt")]
        unsafe extern "system" {
            fn BCryptGenRandom(
                algorithm: *mut core::ffi::c_void,
                buffer: *mut u8,
                length: u32,
                flags: u32,
            ) -> i32;
        }
        const BCRYPT_USE_SYSTEM_PREFERRED_RNG: u32 = 0x0000_0002;
        // SAFETY：BCryptGenRandom 只写入固定长度栈 buffer，不保留任何 pointer；
        // null algorithm 明确选择系统 RNG，因此调用结束后不存在借用生命周期。
        let status = unsafe {
            BCryptGenRandom(
                core::ptr::null_mut(),
                bytes.as_mut_ptr(),
                bytes.len() as u32,
                BCRYPT_USE_SYSTEM_PREFERRED_RNG,
            )
        };
        if status == 0 {
            Ok(())
        } else {
            Err(AppServerProcessError::HandshakeFailed)
        }
    }
    #[cfg(unix)]
    {
        let mut source = std::fs::File::open("/dev/urandom")
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        source
            .read_exact(bytes)
            .map_err(|_| AppServerProcessError::HandshakeFailed)
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = bytes;
        Err(AppServerProcessError::HandshakeFailed)
    }
}

/// 校验 initialize 两端能力 object；任务线程能力必须显式协商，不能仅凭方法存在推断。
pub(crate) fn validate_capabilities(value: Option<&Value>) -> Result<(), AppServerProcessError> {
    let Some(object) = value.and_then(Value::as_object) else {
        return Err(AppServerProcessError::ProtocolFault);
    };
    if object.keys().any(|key| {
        !matches!(
            key.as_str(),
            "methods" | "events" | "accessModes" | "collaborationModes" | "features"
        )
    }) {
        return Err(AppServerProcessError::ProtocolFault);
    }
    validate_string_array(object.get("methods"), 256, 128, None)?;
    validate_string_array(object.get("events"), 256, 128, None)?;
    validate_string_array(
        object.get("accessModes"),
        2,
        32,
        Some(&["approval_required", "full_access"]),
    )?;
    validate_string_array(
        object.get("collaborationModes"),
        2,
        32,
        Some(&["default", "plan"]),
    )?;
    if object
        .get("collaborationModes")
        .and_then(Value::as_array)
        .is_none_or(|items| items.len() != 2)
    {
        return Err(AppServerProcessError::ProtocolFault);
    }
    validate_string_array(
        object.get("features"),
        2,
        32,
        Some(&["task_threads_v1", "plan_goal_v1"]),
    )?;
    if object
        .get("features")
        .and_then(Value::as_array)
        .is_none_or(|items| items.len() != 2)
    {
        return Err(AppServerProcessError::ProtocolFault);
    }
    Ok(())
}

/// 校验 capability 数组的大小、字符串形状和 uniqueItems，防止握手伪造无界能力表。
fn validate_string_array(
    value: Option<&Value>,
    max_items: usize,
    max_len: usize,
    allowed: Option<&[&str]>,
) -> Result<(), AppServerProcessError> {
    let Some(values) = value.and_then(Value::as_array) else {
        return Err(AppServerProcessError::ProtocolFault);
    };
    if values.is_empty() {
        return Ok(());
    }
    if values.len() > max_items {
        return Err(AppServerProcessError::ProtocolFault);
    }
    let mut unique = HashSet::with_capacity(values.len());
    for value in values {
        let Some(item) = value.as_str() else {
            return Err(AppServerProcessError::ProtocolFault);
        };
        if item.is_empty()
            || item.len() > max_len
            || !unique.insert(item)
            || allowed.is_some_and(|choices| !choices.contains(&item))
        {
            return Err(AppServerProcessError::ProtocolFault);
        }
    }
    Ok(())
}

/// 复用冻结 ID 的前缀/ASCII 规则，避免 starts_with 检查接受伪造 instance/event。
pub(crate) fn valid_schema_id(value: &str, prefix: &str, max_len: usize) -> bool {
    let Some(suffix) = value.strip_prefix(prefix) else {
        return false;
    };
    if value.len() > max_len || suffix.is_empty() || suffix.len() > 96 {
        return false;
    }
    let bytes = suffix.as_bytes();
    bytes[0].is_ascii_alphanumeric()
        && bytes[1..].iter().all(|byte| {
            byte.is_ascii_alphanumeric() || *byte == b'.' || *byte == b'_' || *byte == b'-'
        })
}

/// 只接受完整 RFC3339 date-time，避免格式伪造绕过 ready 事件的协议校验。
pub(crate) fn valid_timestamp(value: &str) -> bool {
    let bytes = value.as_bytes();
    if bytes.len() > 64
        || bytes.len() < 20
        || bytes.iter().any(|byte| byte.is_ascii_whitespace())
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
        || !ascii_digits(bytes, 0, 4)
        || !ascii_digits(bytes, 5, 2)
        || !ascii_digits(bytes, 8, 2)
        || !ascii_digits(bytes, 11, 2)
        || !ascii_digits(bytes, 14, 2)
        || !ascii_digits(bytes, 17, 2)
    {
        return false;
    }

    let year = decimal_component(bytes, 0, 4);
    let month = decimal_component(bytes, 5, 2);
    let day = decimal_component(bytes, 8, 2);
    let hour = decimal_component(bytes, 11, 2);
    let minute = decimal_component(bytes, 14, 2);
    let second = decimal_component(bytes, 17, 2);
    if !(1..=12).contains(&month)
        || !(1..=days_in_month(year, month)).contains(&day)
        || hour > 23
        || minute > 59
        || second > 60
    {
        return false;
    }

    let mut index = 19;
    if bytes.get(index) == Some(&b'.') {
        index += 1;
        let fraction_start = index;
        while bytes.get(index).is_some_and(u8::is_ascii_digit) {
            index += 1;
        }
        if index == fraction_start {
            return false;
        }
    }

    match bytes.get(index) {
        Some(b'Z') => index + 1 == bytes.len(),
        Some(b'+') | Some(b'-') => {
            index + 6 == bytes.len()
                && bytes[index + 3] == b':'
                && ascii_digits(bytes, index + 1, 2)
                && ascii_digits(bytes, index + 4, 2)
                && decimal_component(bytes, index + 1, 2) <= 23
                && decimal_component(bytes, index + 4, 2) <= 59
        }
        _ => false,
    }
}

/// 保持 RFC3339 解析不依赖宽松 Unicode/整数转换，防止非 ASCII 数字被接受。
fn ascii_digits(bytes: &[u8], start: usize, len: usize) -> bool {
    bytes
        .get(start..start.saturating_add(len))
        .is_some_and(|part| part.len() == len && part.iter().all(u8::is_ascii_digit))
}

/// 将已经通过 ASCII 数字校验的日期片段转换为无失败的十进制值。
fn decimal_component(bytes: &[u8], start: usize, len: usize) -> u32 {
    bytes[start..start + len]
        .iter()
        .fold(0_u32, |value, digit| value * 10 + u32::from(digit - b'0'))
}

/// 按 Gregorian 闰年规则计算日期上限，阻止二月三十日等伪造时间戳。
fn days_in_month(year: u32, month: u32) -> u32 {
    match month {
        2 if year.is_multiple_of(400) || (year.is_multiple_of(4) && !year.is_multiple_of(100)) => {
            29
        }
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// 检查 runtime 版本字段的存在性与长度，避免将任意对象误当握手能力。
pub(crate) fn bounded_string(value: Option<&Value>, max_len: usize) -> bool {
    value
        .and_then(Value::as_str)
        .is_some_and(|text| !text.is_empty() && text.len() <= max_len)
}

/// 统一限制 supervisor deadline，并拒绝 Instant 加法溢出导致的无限等待。
pub(crate) fn checked_deadline(
    timeout: Duration,
    maximum: Duration,
) -> Result<Instant, AppServerProcessError> {
    if timeout > maximum {
        return Err(AppServerProcessError::InvalidTimeout);
    }
    Instant::now()
        .checked_add(timeout)
        .ok_or(AppServerProcessError::InvalidTimeout)
}

/// 校验 server 宣告的每项 limit 均在冻结 Schema 且不超过 host 预算。
pub(crate) fn validate_remote_limits(
    value: Option<&Value>,
    local: &Limits,
) -> Result<(), AppServerProcessError> {
    let Some(object) = value.and_then(Value::as_object) else {
        return Err(AppServerProcessError::ProtocolFault);
    };
    const LIMIT_FIELDS: &[&str] = &[
        "maxFrameBytes",
        "maxInFlightRequests",
        "maxInboundQueueFrames",
        "maxControlOutboundQueueFrames",
        "maxDataOutboundQueueFrames",
        "maxConcurrentTurns",
        "maxAdmittedTurns",
        "maxThreadQueuedTurns",
        "maxSnapshotPageItems",
        "maxToolBatchConcurrency",
        "maxTurnQueuedInputs",
        "maxTurnQueuedInputBytes",
    ];
    if object
        .keys()
        .any(|key| !LIMIT_FIELDS.contains(&key.as_str()))
    {
        return Err(AppServerProcessError::ProtocolFault);
    }
    let number = |key: &str| object.get(key).and_then(Value::as_u64);
    let number_usize = |key: &str| {
        number(key)
            .and_then(|value| usize::try_from(value).ok())
            .ok_or(AppServerProcessError::ProtocolFault)
    };
    let max_frame = number_usize("maxFrameBytes")?;
    let inbound = number_usize("maxInboundQueueFrames")?;
    let control_outbound = number_usize("maxControlOutboundQueueFrames")?;
    let data_outbound = number_usize("maxDataOutboundQueueFrames")?;
    let in_flight = number_usize("maxInFlightRequests")?;
    let concurrent_turns = number_usize("maxConcurrentTurns")?;
    let admitted_turns = number_usize("maxAdmittedTurns")?;
    let thread_queued_turns = number_usize("maxThreadQueuedTurns")?;
    let snapshot_page_items = number_usize("maxSnapshotPageItems")?;
    let tool_batch_concurrency = number_usize("maxToolBatchConcurrency")?;
    let turn_queued_inputs = number_usize("maxTurnQueuedInputs")?;
    let turn_queued_input_bytes = number_usize("maxTurnQueuedInputBytes")?;
    if !(MIN_MAX_FRAME_BYTES..=4_194_304).contains(&max_frame)
        || !(1..=256).contains(&inbound)
        || control_outbound != 64
        || !(1..=1_024).contains(&data_outbound)
        || !(1..=64).contains(&in_flight)
        || !(1..=8).contains(&concurrent_turns)
        || !(1..=64).contains(&admitted_turns)
        || !(1..=8).contains(&thread_queued_turns)
        || snapshot_page_items != 200
        || !(1..=8).contains(&tool_batch_concurrency)
        || turn_queued_inputs != 8
        || turn_queued_input_bytes != 524_288
        || max_frame != local.max_frame_bytes
        || inbound != local.inbound_queue_frames
        || data_outbound != local.outbound_queue_frames
        || in_flight != local.max_in_flight_requests
        || admitted_turns != local.max_pending_requests
    {
        return Err(AppServerProcessError::ProtocolFault);
    }
    Ok(())
}
