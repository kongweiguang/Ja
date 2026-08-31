// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Settings 查询的 Tauri command 适配；方法 allowlist 与结果边界留在 query 模块。

use super::settings_model::{SettingsQueryInput, validate_result};
use crate::app_runtime::{
    McpListParams, McpTestParams, McpToolsReadParams, ModelTestParams, RuntimeCommandError,
    RuntimeHost, SettingsRequest, SettingsResponse, SkillListParams,
};
use serde_json::Value;

/// 通过 Ready generation 执行 allowlist 查询，并在进入 Tauri serializer 前限制结果。
#[tauri::command]
pub fn ja_runtime_query(
    input: SettingsQueryInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<Value, RuntimeCommandError> {
    let (method, params) = input.validate()?;
    let bytes = serde_json::to_vec(&params).map_err(|_| RuntimeCommandError::invalid_params())?;
    // interface 按查询闭集构造不同 nominal params，application 不接触 method 字符串或共享 blob。
    let request = match method {
        super::settings_model::SettingsQueryMethod::SkillList => {
            SettingsRequest::SkillList(SkillListParams::try_new(bytes)?)
        }
        super::settings_model::SettingsQueryMethod::McpList => {
            SettingsRequest::McpList(McpListParams::try_new(bytes)?)
        }
        super::settings_model::SettingsQueryMethod::McpTest => {
            SettingsRequest::McpTest(McpTestParams::try_new(bytes)?)
        }
        super::settings_model::SettingsQueryMethod::ModelTest => {
            SettingsRequest::ModelTest(ModelTestParams::try_new(bytes)?)
        }
        super::settings_model::SettingsQueryMethod::McpToolsRead => {
            SettingsRequest::McpToolsRead(McpToolsReadParams::try_new(bytes)?)
        }
    };
    let response = state.settings_query(request)?;
    // 返回 variant 必须与查询类型配对，错配不尝试兼容解析。
    let bytes = match (method, response) {
        (
            super::settings_model::SettingsQueryMethod::SkillList,
            SettingsResponse::SkillList(value),
        ) => value.into_bytes(),
        (super::settings_model::SettingsQueryMethod::McpList, SettingsResponse::McpList(value)) => {
            value.into_bytes()
        }
        (super::settings_model::SettingsQueryMethod::McpTest, SettingsResponse::McpTest(value)) => {
            value.into_bytes()
        }
        (
            super::settings_model::SettingsQueryMethod::ModelTest,
            SettingsResponse::ModelTest(value),
        ) => value.into_bytes(),
        (
            super::settings_model::SettingsQueryMethod::McpToolsRead,
            SettingsResponse::McpToolsRead(value),
        ) => value.into_bytes(),
        _ => return Err(RuntimeCommandError::unavailable()),
    };
    let value = serde_json::from_slice(&bytes).map_err(|_| RuntimeCommandError::unavailable())?;
    validate_result(method, value)
}
