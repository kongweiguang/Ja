// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use serde_json::Value;

/// 保存偏好后用同一连接只读回核实；FnOnce 限制写入只执行一次，未知回执不会触发重放。
pub(super) struct PreferenceUpdateAttempt<E, S> {
    pub(super) update: Result<(), E>,
    pub(super) readback: Result<S, E>,
}

/// UI 按权威读回区分已生效、未生效、被并发改写和仍待核实。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum PreferenceResolution {
    Applied,
    NotApplied,
    Superseded,
    Unknown,
}

/// 不论写入是否收到回执，都只提交一次并尝试一次读回；调用方据读回决定展示或重试。
pub(super) fn update_then_readback_once<C, E, S>(
    connection: &mut C,
    update: impl FnOnce(&mut C) -> Result<(), E>,
    readback: impl FnOnce(&mut C) -> Result<S, E>,
) -> PreferenceUpdateAttempt<E, S> {
    let update = update(connection);
    let readback = readback(connection);
    PreferenceUpdateAttempt { update, readback }
}

/// 写请求结果不能代替最新偏好；只有读回明确匹配目标时才视为当前设置已应用。
pub(super) fn resolve<E>(
    update: &Result<(), E>,
    readback_matches: Option<bool>,
) -> PreferenceResolution {
    match readback_matches {
        Some(true) => PreferenceResolution::Applied,
        Some(false) if update.is_ok() => PreferenceResolution::Superseded,
        Some(false) => PreferenceResolution::NotApplied,
        None => PreferenceResolution::Unknown,
    }
}

/// 只比较写请求覆盖的偏好字段；缺字段或非对象保持未知，不把不完整快照算成未生效。
pub(super) fn matches_preferences(metadata: &Value, requested: &Value) -> Option<bool> {
    let actual = metadata.get("preferences")?.as_object()?;
    let requested = requested.as_object()?;
    let mut matches = true;
    for field in ["providerId", "modelId", "accessMode", "collaborationMode"] {
        let actual_value = actual.get(field)?.as_str()?;
        let requested_value = requested.get(field)?.as_str()?;
        matches &= actual_value == requested_value;
    }
    let actual_reasoning = optional_text(actual.get("reasoningLevel")?)?;
    let requested_reasoning = optional_text(requested.get("reasoningLevel")?)?;
    Some(matches && actual_reasoning == requested_reasoning)
}

/// 推理等级在协议中可显式为空；其它 JSON 类型无法作为权威读回证据。
fn optional_text(value: &Value) -> Option<Option<&str>> {
    match value {
        Value::Null => Some(None),
        Value::String(text) => Some(Some(text)),
        _ => None,
    }
}
