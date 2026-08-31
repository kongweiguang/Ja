// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Settings 页面既有 Ja Kernel 方法的小型 typed bridge。
//
// 该模块刻意不做 generic RPC tunnel：WebView 只能请求 Java 契约已存在的 Skills/MCP 投影。

use crate::app_runtime::RuntimeCommandError;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

const MAX_METHOD: usize = 64;
const MAX_RESULT_BYTES: usize = 4 * 1024 * 1024;
pub(crate) const MAX_ROWS: usize = 200;
const MAX_TOOLS: usize = 200;
const MAX_ID: usize = 100;
const MAX_CURSOR: usize = 256;

/// 首个 Settings surface 唯一需要的 sidecar 方法闭集。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SettingsQueryMethod {
    SkillList,
    McpList,
    McpTest,
    ModelTest,
    McpToolsRead,
}

impl SettingsQueryMethod {
    /// 设计原因：该函数维护封闭查询 allowlist 与结果上限，不允许演化为任意 RPC 通道。
    /// 将产品闭集 allowlist 转换为 Java wire method name，禁止调用方注入任意方法。
    pub(crate) fn parse(value: &str) -> Result<Self, RuntimeCommandError> {
        match value {
            "skill/list" => Ok(Self::SkillList),
            "mcp/list" => Ok(Self::McpList),
            "mcp/test" => Ok(Self::McpTest),
            "model/test" => Ok(Self::ModelTest),
            "mcp/list-tools" => Ok(Self::McpToolsRead),
            _ => Err(RuntimeCommandError::invalid_params()),
        }
    }
}

/// Tauri input 将 method 与 params 放在一起，使 native command 只有一个验证边界，同时拒绝
/// 任意 method name。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SettingsQueryInput {
    pub method: String,
    pub params: Value,
}

impl SettingsQueryInput {
    /// 设计原因：该函数维护封闭查询 allowlist 与结果上限，不允许演化为任意 RPC 通道。
    /// 只验证有界 identifier；endpoint、command 与 secret data 不进入该 query surface。
    pub(crate) fn validate(self) -> Result<(SettingsQueryMethod, Value), RuntimeCommandError> {
        if self.method.is_empty() || self.method.len() > MAX_METHOD {
            return Err(RuntimeCommandError::invalid_params());
        }
        let method = SettingsQueryMethod::parse(&self.method)?;
        let object = self
            .params
            .as_object()
            .ok_or_else(RuntimeCommandError::invalid_params)?;
        match method {
            SettingsQueryMethod::SkillList | SettingsQueryMethod::McpList => {
                validate_page(object, false)?
            }
            SettingsQueryMethod::McpTest => {
                if object.len() != 1 {
                    return Err(RuntimeCommandError::invalid_params());
                }
                required_id(object, "mcpId", "mcp_")?;
            }
            SettingsQueryMethod::ModelTest => {
                if object.len() != 2 {
                    return Err(RuntimeCommandError::invalid_params());
                }
                required_id(object, "providerId", "provider_")?;
                required_id(object, "modelId", "model_")?;
            }
            SettingsQueryMethod::McpToolsRead => {
                if object
                    .keys()
                    .any(|key| !matches!(key.as_str(), "mcpId" | "cursor" | "limit"))
                {
                    return Err(RuntimeCommandError::invalid_params());
                }
                required_id(object, "mcpId", "mcp_")?;
                validate_page(object, true)?;
            }
        }
        Ok((method, Value::Object(object.clone())))
    }
}

/// 设计原因：该函数维护封闭查询 allowlist 与结果上限，不允许演化为任意 RPC 通道。
/// 验证共享 cursor page shape，不静默接受旧 empty-only Settings query 或超限 server response
/// 请求。
fn validate_page(
    object: &Map<String, Value>,
    allow_mcp_id: bool,
) -> Result<(), RuntimeCommandError> {
    if object
        .keys()
        .any(|key| !(matches!(key.as_str(), "cursor" | "limit") || allow_mcp_id && key == "mcpId"))
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    if let Some(cursor) = object.get("cursor") {
        let cursor = cursor
            .as_str()
            .filter(|cursor| !cursor.is_empty() && cursor.len() <= MAX_CURSOR)
            .ok_or_else(RuntimeCommandError::invalid_params)?;
        if cursor.chars().any(char::is_control) {
            return Err(RuntimeCommandError::invalid_params());
        }
    }
    if object.get("limit").is_some_and(|limit| {
        !limit
            .as_u64()
            .is_some_and(|limit| (1..=200).contains(&limit))
    }) {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// 严格校验统一列表信封与大小，不再接受领域专用列表键或额外身份回显。
pub(crate) fn validate_result(
    method: SettingsQueryMethod,
    value: Value,
) -> Result<Value, RuntimeCommandError> {
    if contains_private_field(&value) {
        return Err(RuntimeCommandError::unavailable());
    }
    let object = value
        .as_object()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let max_rows = match method {
        SettingsQueryMethod::SkillList | SettingsQueryMethod::McpList => MAX_ROWS,
        SettingsQueryMethod::McpTest | SettingsQueryMethod::ModelTest => 0,
        SettingsQueryMethod::McpToolsRead => MAX_TOOLS,
    };
    let array_name = match method {
        SettingsQueryMethod::SkillList
        | SettingsQueryMethod::McpList
        | SettingsQueryMethod::McpToolsRead => Some("items"),
        SettingsQueryMethod::McpTest | SettingsQueryMethod::ModelTest => None,
    };
    if let Some(name) = array_name {
        if object.len() != 2 || !object.contains_key("items") || !object.contains_key("nextCursor")
        {
            return Err(RuntimeCommandError::unavailable());
        }
        let rows = object
            .get(name)
            .and_then(Value::as_array)
            .ok_or_else(RuntimeCommandError::unavailable)?;
        if rows.len() > max_rows {
            return Err(RuntimeCommandError::unavailable());
        }
        if !object.contains_key("nextCursor") {
            return Err(RuntimeCommandError::unavailable());
        }
    }
    if method == SettingsQueryMethod::ModelTest
        && (object.len() != 2
            || !object
                .get("responseModel")
                .and_then(Value::as_str)
                .is_some_and(|value| {
                    !value.is_empty() && value.len() <= 512 && !value.chars().any(char::is_control)
                })
            || object
                .get("latencyMs")
                .and_then(Value::as_u64)
                .is_none_or(|value| value > 3_600_000))
    {
        return Err(RuntimeCommandError::unavailable());
    }
    let encoded = serde_json::to_vec(&value).map_err(|_| RuntimeCommandError::unavailable())?;
    if encoded.len() > MAX_RESULT_BYTES {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(value)
}

/// 设计原因：该函数维护封闭查询 allowlist 与结果上限，不允许演化为任意 RPC 通道。
/// 任意 Settings 结果到达 Tauri serialization 前递归拒绝 credential-bearing field name；
/// 即使受损 sidecar 添加其它格式合法的 extension object 也不能绕过。
fn contains_private_field(value: &Value) -> bool {
    match value {
        Value::Object(object) => object.iter().any(|(key, child)| {
            matches!(
                key.to_ascii_lowercase().as_str(),
                "apikey" | "secretvalue" | "credential" | "credentials" | "authorization"
            ) || contains_private_field(child)
        }),
        Value::Array(values) => values.iter().any(contains_private_field),
        _ => false,
    }
}

/// 设计原因：该函数维护封闭查询 allowlist 与结果上限，不允许演化为任意 RPC 通道。
/// revision 必须使用所有 native ID 共用的有界字符集。
fn required_id(
    object: &Map<String, Value>,
    field: &str,
    prefix: &str,
) -> Result<(), RuntimeCommandError> {
    let value = object
        .get(field)
        .and_then(Value::as_str)
        .ok_or_else(RuntimeCommandError::invalid_params)?;
    validate_id(value, prefix, MAX_ID)
}

/// 设计原因：该函数维护封闭查询 allowlist 与结果上限，不允许演化为任意 RPC 通道。
/// query DTO 明确拒绝路径、URL 与任意 request handle。
fn validate_id(value: &str, prefix: &str, max: usize) -> Result<(), RuntimeCommandError> {
    if !value.starts_with(prefix)
        || value.len() > max
        || value.len() == prefix.len()
        || !value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.' | ':')
        })
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}
