// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Tauri 设置命令是 Ja App Server 的类型化代理。
//
// Rust 明确不再持有 `config.toml` 或 `auth.json`；这里只校验最小原生边界、转发单个 allowlist 操作，
// 并且仅向 WebView 投影脱敏 JSON。

use crate::app_runtime::{
    ConfigurationPatchParams, ConfigurationReadParams, ConfigurationReplaceParams,
    ConfigurationRequest, ConfigurationResetParams, ConfigurationResponse, CredentialDeleteParams,
    CredentialSetParams, RuntimeCommandError, RuntimeHost,
};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use std::fmt;

pub(crate) const MAX_VERSION_BYTES: usize = 256;
const MAX_VALUE_BYTES: usize = 1_048_576;
const CONFIG_VERSION_PREFIX: &str = "cfg_";

/// 每个调用点同时固定 request 与 response variant；宏只消除编码样板，不接受 method 字符串，
/// 因而无法把 Settings command 扩大为 generic runtime tunnel。
macro_rules! request_configuration {
    ($host:expr, $request:expr, $response:path) => {{
        let response = $host
            .config_request($request)
            .map_err(SettingsCommandError::from)?;
        let $response(result) = response else {
            return Err(SettingsCommandError::unavailable());
        };
        serde_json::from_slice(&result.into_bytes())
            .map_err(|_| SettingsCommandError::unavailable())
    }};
}

/// 配置作用域由 Java 解析，不从客户端 snapshot 推断，防止项目写入静默变成用户全局写入。
#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ConfigScope {
    User,
    Project,
}

/// 配置读取只接受 Java 签发的可选工作区身份，不允许 WebView 传入路径。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConfigReadInput {
    #[serde(default)]
    pub workspace_id: Option<String>,
}

/// RFC 7396 Merge Patch 输入；项目作用域必须携带 workspaceId。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConfigPatchInput {
    pub scope: ConfigScope,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_id: Option<String>,
    pub patch: Map<String, Value>,
    pub expected_version: String,
}

/// 完整配置替换输入；文档由 Java 再执行领域级严格校验。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConfigReplaceInput {
    pub scope: ConfigScope,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_id: Option<String>,
    pub document: Map<String, Value>,
    pub expected_version: String,
}

/// 独立配置重置输入，不接受模式选择或可选 document 分支。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConfigResetInput {
    pub scope: ConfigScope,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_id: Option<String>,
    pub expected_version: String,
}

/// 唯一持有 Secret 字节的 Tauri DTO；刻意不实现 `Clone`、`Debug` 或 `Serialize`，command 返回后立即清空缓冲区。
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CredentialSetInput {
    pub credential_id: String,
    pub secret: String,
    pub expected_version: String,
}

impl Drop for CredentialSetInput {
    /// 即使 RPC 失败也缩短 command 所持 Secret 的生命周期。
    fn drop(&mut self) {
        let mut bytes = std::mem::take(&mut self.secret).into_bytes();
        bytes.fill(0);
    }
}

/// 幂等凭据删除不携带任何 Secret 字段。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CredentialDeleteInput {
    pub credential_id: String,
    pub expected_version: String,
}

/// 稳定且脱敏的 Tauri 错误；Java 细节与 Secret 值不得越过该边界。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsCommandError {
    pub code: &'static str,
    pub message: &'static str,
    pub retryable: bool,
}

impl fmt::Display for SettingsCommandError {
    /// 使用固定消息，避免解析器、路径或凭据细节泄漏。
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message)
    }
}

impl std::error::Error for SettingsCommandError {}

impl From<RuntimeCommandError> for SettingsCommandError {
    /// 只保留原生 runtime 边界稳定的重试语义。
    fn from(error: RuntimeCommandError) -> Self {
        Self {
            code: error.code,
            message: error.message,
            retryable: error.retryable,
        }
    }
}

impl SettingsCommandError {
    /// 将所有 renderer 输入失败收敛为同一稳定类别，避免校验分支泄漏字段或路径细节。
    fn invalid() -> Self {
        Self {
            code: "INVALID_PARAMS",
            message: "settings request parameters are invalid",
            retryable: false,
        }
    }

    /// 将跨进程解码、响应形状或 worker 失败统一标记为可重试，不把内部原因带入 WebView。
    fn unavailable() -> Self {
        Self {
            code: "SETTINGS_UNAVAILABLE",
            message: "settings are unavailable",
            retryable: true,
        }
    }
}

/// 读取 Java 投影；Host 在内部复用健康 generation，首次调用才进入完整启动链。
#[tauri::command]
pub async fn ja_configuration_read(
    input: ConfigReadInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<Value, SettingsCommandError> {
    validate_optional_workspace(input.workspace_id.as_deref())?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let params = omit_null(json!({"workspaceId": input.workspace_id}));
        let request = ConfigurationRequest::Read(
            ConfigurationReadParams::try_new(
                serde_json::to_vec(&params).map_err(|_| SettingsCommandError::invalid())?,
            )
            .map_err(SettingsCommandError::from)?,
        );
        let value = request_configuration!(&host, request, ConfigurationResponse::Read)?;
        validate_redacted_projection(value)
    })
    .await
    .map_err(|_| SettingsCommandError::unavailable())?
}

/// 转发 RFC 7396 Merge Patch，Rust 不解释配置字段含义。
#[tauri::command]
pub async fn ja_configuration_patch(
    input: ConfigPatchInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<Value, SettingsCommandError> {
    validate_patch(&input)?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let params = serde_json::to_vec(&input).map_err(|_| SettingsCommandError::invalid())?;
        let request = ConfigurationRequest::Patch(
            ConfigurationPatchParams::try_new(params).map_err(SettingsCommandError::from)?,
        );
        let value = request_configuration!(&host, request, ConfigurationResponse::Patch)?;
        validate_write_result(value)
    })
    .await
    .map_err(|_| SettingsCommandError::unavailable())?
}

/// 转发完整严格文档替换，不在 Rust 生成兼容写入列表。
#[tauri::command]
pub async fn ja_configuration_replace(
    input: ConfigReplaceInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<Value, SettingsCommandError> {
    validate_replace(&input)?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let params = serde_json::to_vec(&input).map_err(|_| SettingsCommandError::invalid())?;
        let request = ConfigurationRequest::Replace(
            ConfigurationReplaceParams::try_new(params).map_err(SettingsCommandError::from)?,
        );
        let value = request_configuration!(&host, request, ConfigurationResponse::Replace)?;
        validate_write_result(value)
    })
    .await
    .map_err(|_| SettingsCommandError::unavailable())?
}

/// 通过独立命令重置配置层，不接受 mode 或 document。
#[tauri::command]
pub async fn ja_configuration_reset(
    input: ConfigResetInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<Value, SettingsCommandError> {
    validate_reset(&input)?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let params = serde_json::to_vec(&input).map_err(|_| SettingsCommandError::invalid())?;
        let request = ConfigurationRequest::Reset(
            ConfigurationResetParams::try_new(params).map_err(SettingsCommandError::from)?,
        );
        let value = request_configuration!(&host, request, ConfigurationResponse::Reset)?;
        validate_write_result(value)
    })
    .await
    .map_err(|_| SettingsCommandError::unavailable())?
}

/// 转发唯一携带 Secret 的操作；响应进入 WebView 序列化前必须拒绝任何意外 Secret 回显。
#[tauri::command]
pub async fn ja_credential_set(
    input: CredentialSetInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<Value, SettingsCommandError> {
    validate_credential(&input)?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let params = serde_json::to_vec(&json!({"credentialId": input.credential_id, "secret": input.secret, "expectedVersion": input.expected_version}))
            .map_err(|_| SettingsCommandError::invalid())?;
        let request = ConfigurationRequest::CredentialSet(
            CredentialSetParams::try_new(params).map_err(SettingsCommandError::from)?,
        );
        let value = request_configuration!(&host, request, ConfigurationResponse::CredentialSet)?;
        validate_credential_result(value)
    }).await.map_err(|_| SettingsCommandError::unavailable())?
}

/// 幂等清除一个 Java 持有的凭据，且只返回 `configured=false`。
#[tauri::command]
pub async fn ja_credential_delete(
    input: CredentialDeleteInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<Value, SettingsCommandError> {
    validate_credential_id(&input.credential_id)?;
    validate_version(&input.expected_version)?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let params = serde_json::to_vec(&input).map_err(|_| SettingsCommandError::invalid())?;
        let request = ConfigurationRequest::CredentialDelete(
            CredentialDeleteParams::try_new(params).map_err(SettingsCommandError::from)?,
        );
        let value =
            request_configuration!(&host, request, ConfigurationResponse::CredentialDelete)?;
        validate_credential_result(value)
    })
    .await
    .map_err(|_| SettingsCommandError::unavailable())?
}

/// 在 interface 边界只接受缺省值或 Java 签发的工作区身份，禁止把路径或自由文本带入配置查询。
fn validate_optional_workspace(value: Option<&str>) -> Result<(), SettingsCommandError> {
    let Some(value) = value else {
        return Ok(());
    };
    if !valid_workspace_id(value) {
        return Err(SettingsCommandError::invalid());
    }
    Ok(())
}

/// 将 scope 与 workspace identity 的组合约束集中在 command 边界，避免 User 写入被误投影为 Project 写入。
fn validate_scope(
    scope: ConfigScope,
    workspace_id: Option<&str>,
) -> Result<(), SettingsCommandError> {
    match (scope, workspace_id) {
        (ConfigScope::User, None) => Ok(()),
        (ConfigScope::Project, Some(value)) if valid_workspace_id(value) => Ok(()),
        _ => Err(SettingsCommandError::invalid()),
    }
}

/// 校验不透明工作区身份，不解析或恢复宿主路径。
fn valid_workspace_id(value: &str) -> bool {
    value.starts_with("ws_")
        && (4..=100).contains(&value.len())
        && value[3..]
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// 只接受服务端签发的 missing 标记或有界、不透明的 Base64URL CAS token；前缀用于区分配置代际与 renderer revision，
/// Rust 只校验传输形状，不解释 token 内容或摘要长度。
pub(crate) fn validate_version(value: &str) -> Result<(), SettingsCommandError> {
    let valid = value == "cfg_missing"
        || value
            .strip_prefix(CONFIG_VERSION_PREFIX)
            .is_some_and(|suffix| {
                !suffix.is_empty()
                    && value.len() <= MAX_VERSION_BYTES
                    && suffix
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
            });
    if !valid {
        return Err(SettingsCommandError::invalid());
    }
    Ok(())
}

/// 在跨进程前同时限制序列化体积并拒绝 secret 字段，防止 WebView 借通用配置文档绕过凭据专用通道。
fn validate_value(value: &Value) -> Result<(), SettingsCommandError> {
    if serde_json::to_vec(value)
        .map_err(|_| SettingsCommandError::invalid())?
        .len()
        > MAX_VALUE_BYTES
        || contains_secret_field(value)
    {
        return Err(SettingsCommandError::invalid());
    }
    Ok(())
}

/// Patch 准入同时维护 scope、CAS version、字段数与内容上限，所有条件满足后才允许调用 Ja App Server。
fn validate_patch(input: &ConfigPatchInput) -> Result<(), SettingsCommandError> {
    validate_scope(input.scope, input.workspace_id.as_deref())?;
    validate_version(&input.expected_version)?;
    if input.patch.len() > 64 {
        return Err(SettingsCommandError::invalid());
    }
    validate_value(&Value::Object(input.patch.clone()))
}

/// Replace 仍复用同一 scope、CAS 与 secret 约束，避免全量写入形成比 Patch 更宽的输入通道。
fn validate_replace(input: &ConfigReplaceInput) -> Result<(), SettingsCommandError> {
    validate_scope(input.scope, input.workspace_id.as_deref())?;
    validate_version(&input.expected_version)?;
    validate_value(&Value::Object(input.document.clone()))
}

/// Reset 虽无文档 payload，仍必须绑定合法 scope 与 CAS version，防止陈旧 UI 重置错误 owner。
fn validate_reset(input: &ConfigResetInput) -> Result<(), SettingsCommandError> {
    validate_scope(input.scope, input.workspace_id.as_deref())?;
    validate_version(&input.expected_version)
}

/// 凭据只在专用 command 中校验有界、无控制字符的 secret；不解析 provider 语义，也不把 secret 复制到配置文档。
fn validate_credential(input: &CredentialSetInput) -> Result<(), SettingsCommandError> {
    validate_credential_id(&input.credential_id)?;
    validate_version(&input.expected_version)?;
    if input.secret.is_empty()
        || input.secret.len() > 8192
        || input.secret.chars().any(char::is_control)
    {
        return Err(SettingsCommandError::invalid());
    }
    Ok(())
}

/// 凭据身份保持有界不透明 token，禁止路径分隔符和自由格式文本进入 Java credential owner。
fn validate_credential_id(value: &str) -> Result<(), SettingsCommandError> {
    if !value.starts_with("cred_")
        || value.len() > 100
        || value.len() == 5
        || !value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.'))
    {
        return Err(SettingsCommandError::invalid());
    }
    Ok(())
}

/// 对配置读取结果执行失败关闭的脱敏合同检查，并只接受顶层 `cas` 三版本事实。
fn validate_redacted_projection(value: Value) -> Result<Value, SettingsCommandError> {
    if contains_secret_field(&value) {
        return Err(SettingsCommandError::unavailable());
    }
    let object = value
        .as_object()
        .ok_or_else(SettingsCommandError::unavailable)?;
    let expected = [
        "effective",
        "user",
        "project",
        "credentials",
        "cas",
        "diagnostics",
        "trusted",
        "workspaceId",
    ];
    if object.len() != expected.len() || expected.iter().any(|key| !object.contains_key(*key)) {
        return Err(SettingsCommandError::unavailable());
    }
    match object.get("workspaceId") {
        Some(Value::Null) => {}
        Some(Value::String(workspace_id)) if valid_workspace_id(workspace_id) => {}
        _ => return Err(SettingsCommandError::unavailable()),
    }
    let cas = object
        .get("cas")
        .and_then(Value::as_object)
        .ok_or_else(SettingsCommandError::unavailable)?;
    let cas_keys = ["userVersion", "projectVersion", "credentialVersion"];
    if cas.len() != cas_keys.len()
        || cas_keys.iter().any(|key| {
            cas.get(*key)
                .and_then(Value::as_str)
                .is_none_or(|version| validate_version(version).is_err())
        })
    {
        return Err(SettingsCommandError::unavailable());
    }
    for layer_name in ["user", "project"] {
        let layer = object
            .get(layer_name)
            .and_then(Value::as_object)
            .ok_or_else(SettingsCommandError::unavailable)?;
        if layer.contains_key("version") {
            return Err(SettingsCommandError::unavailable());
        }
    }
    if object.contains_key("credentialVersion") {
        return Err(SettingsCommandError::unavailable());
    }
    Ok(value)
}

/// 写入结果只接受稳定的 accepted/scope/version 形状，避免把服务端内部响应透传成新的隐式前端契约。
fn validate_write_result(value: Value) -> Result<Value, SettingsCommandError> {
    let object = value
        .as_object()
        .ok_or_else(SettingsCommandError::unavailable)?;
    if object.get("accepted").and_then(Value::as_bool) != Some(true)
        || object.get("scope").and_then(Value::as_str).is_none()
        || object.get("version").and_then(Value::as_str).is_none()
    {
        return Err(SettingsCommandError::unavailable());
    }
    Ok(value)
}

/// 凭据结果必须只暴露配置状态与 version，并再次递归拒绝 secret，覆盖服务端异常返回路径。
fn validate_credential_result(value: Value) -> Result<Value, SettingsCommandError> {
    let object = value
        .as_object()
        .ok_or_else(SettingsCommandError::unavailable)?;
    if object.get("accepted").and_then(Value::as_bool) != Some(true)
        || object.get("credentialId").and_then(Value::as_str).is_none()
        || object.get("configured").and_then(Value::as_bool).is_none()
        || object.get("version").and_then(Value::as_str).is_none()
        || contains_secret_field(&value)
    {
        return Err(SettingsCommandError::unavailable());
    }
    Ok(value)
}

/// 递归检查所有对象与数组层级的敏感字段名，防止嵌套结构绕过顶层脱敏边界。
fn contains_secret_field(value: &Value) -> bool {
    match value {
        Value::Object(map) => map.iter().any(|(key, child)| {
            matches!(
                key.to_ascii_lowercase().as_str(),
                "secret" | "secretvalue" | "apikey" | "authorization" | "password" | "token"
            ) || contains_secret_field(child)
        }),
        Value::Array(items) => items.iter().any(contains_secret_field),
        _ => false,
    }
}

/// 只在读取请求的 interface 边界移除可选 null，保持 Java 严格合同中“缺失”与“显式 null”的区别。
fn omit_null(mut value: Value) -> Value {
    if let Some(object) = value.as_object_mut() {
        object.retain(|_, value| !value.is_null());
    }
    value
}
