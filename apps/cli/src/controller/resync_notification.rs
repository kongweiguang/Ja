// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 投影失效通知没有可重放游标；CLI 只校验身份闭集并用 Thread 快照恢复。

use serde_json::Value;
use std::collections::{HashSet, VecDeque};

const EVENT_ID_CACHE_CAPACITY: usize = 128;
const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;

#[derive(Debug, Eq, PartialEq)]
pub(crate) struct ResyncRequiredNotification {
    pub(crate) event_id: String,
    pub(crate) workspace_id: String,
    pub(crate) thread_id: String,
}

/// 校验恢复通知封套；连接序号只做协议完整性检查，不参与快照读取或去重。
pub(crate) fn parse_resync_required(
    params: &Value,
) -> Result<ResyncRequiredNotification, &'static str> {
    let Some(object) = params.as_object() else {
        return Err("params must be an object");
    };
    const FIELDS: [&str; 9] = [
        "serverInstanceId",
        "eventId",
        "sequence",
        "occurredAt",
        "generation",
        "workspaceId",
        "threadId",
        "turnId",
        "reasonCode",
    ];
    if object.len() != FIELDS.len() || FIELDS.iter().any(|field| !object.contains_key(*field)) {
        return Err("params fields do not match the resync contract");
    }
    validate_prefixed(object.get("serverInstanceId"), "srv_", 100)?;
    let event_id = validate_prefixed(object.get("eventId"), "evt_", 100)?;
    let sequence = object
        .get("sequence")
        .and_then(Value::as_u64)
        .ok_or("sequence must be a positive integer")?;
    if sequence == 0 || sequence > MAX_SAFE_INTEGER {
        return Err("sequence must be a positive safe integer");
    }
    let occurred_at = object
        .get("occurredAt")
        .and_then(Value::as_str)
        .ok_or("occurredAt must be a timestamp")?;
    if !valid_timestamp(occurred_at) {
        return Err("occurredAt must be a timestamp");
    }
    let generation = object
        .get("generation")
        .and_then(Value::as_u64)
        .ok_or("generation must be a positive integer")?;
    if generation == 0 || generation > MAX_SAFE_INTEGER {
        return Err("generation must be a positive safe integer");
    }
    let workspace_id = validate_prefixed(object.get("workspaceId"), "ws_", 99)?;
    let thread_id = validate_prefixed(object.get("threadId"), "thr_", 100)?;
    validate_prefixed(object.get("turnId"), "turn_", 101)?;
    if object.get("reasonCode").and_then(Value::as_str) != Some("projection_failed") {
        return Err("reasonCode is unsupported");
    }
    Ok(ResyncRequiredNotification {
        event_id: event_id.to_owned(),
        workspace_id: workspace_id.to_owned(),
        thread_id: thread_id.to_owned(),
    })
}

/// 在有界窗口内压掉同一恢复通知的重投，不让断线/重连放大 Thread 读取量。
#[derive(Default)]
pub(crate) struct ResyncNotificationDeduper {
    event_ids: HashSet<String>,
    insertion_order: VecDeque<String>,
}

impl ResyncNotificationDeduper {
    /// 只记住最近的固定数量 Event ID；淘汰最旧项保持常量级内存占用。
    pub(crate) fn remember_once(&mut self, event_id: &str) -> bool {
        if self.event_ids.contains(event_id) {
            return false;
        }
        let owned = event_id.to_owned();
        self.event_ids.insert(owned.clone());
        self.insertion_order.push_back(owned);
        if self.insertion_order.len() > EVENT_ID_CACHE_CAPACITY
            && let Some(expired) = self.insertion_order.pop_front()
        {
            self.event_ids.remove(&expired);
        }
        true
    }
}

/// 验证带类型前缀的 opaque identity，避免将工作区路径等主机数据当成事件 ID。
fn validate_prefixed<'a>(
    value: Option<&'a Value>,
    prefix: &str,
    maximum_length: usize,
) -> Result<&'a str, &'static str> {
    let identifier = value
        .and_then(Value::as_str)
        .ok_or("identity must be a string")?;
    let suffix = identifier
        .strip_prefix(prefix)
        .ok_or("identity has an unsupported prefix")?;
    let mut chars = suffix.chars();
    let first = chars.next().ok_or("identity suffix must not be empty")?;
    if identifier.len() > maximum_length
        || !first.is_ascii_alphanumeric()
        || !chars.all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
        })
    {
        return Err("identity format is invalid");
    }
    Ok(identifier)
}

/// 仅校验 RFC 3339 的定形结构；时间含义由服务端签发，CLI 不自行归一化或排序。
fn valid_timestamp(timestamp: &str) -> bool {
    let bytes = timestamp.as_bytes();
    if !(20..=64).contains(&bytes.len())
        || ![4, 7].iter().all(|index| bytes[*index] == b'-')
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
        || ![(0, 4), (5, 7), (8, 10), (11, 13), (14, 16), (17, 19)]
            .iter()
            .all(|(start, end)| bytes[*start..*end].iter().all(u8::is_ascii_digit))
    {
        return false;
    }
    let mut offset = 19;
    if bytes.get(offset) == Some(&b'.') {
        offset += 1;
        let fraction_start = offset;
        while bytes.get(offset).is_some_and(u8::is_ascii_digit) {
            offset += 1;
        }
        if offset == fraction_start {
            return false;
        }
    }
    match bytes.get(offset..) {
        Some([b'Z']) => true,
        Some(
            [
                b'+' | b'-',
                hour_tens,
                hour_units,
                b':',
                minute_tens,
                minute_units,
            ],
        ) => [hour_tens, hour_units, minute_tens, minute_units]
            .iter()
            .all(|byte| byte.is_ascii_digit()),
        _ => false,
    }
}
