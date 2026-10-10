// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 设置操作只提交一次；两个持久化边界分别回读，断线后保留原意图供显式核实。

use ja_cli::ui::{
    ModelSettingsCredential, ModelSettingsOutcome, ModelSettingsRequest, ModelSettingsResult,
};
use serde_json::{Value, json};

/// 只保存稳定分类和脱敏文案，避免测试适配器或原生连接把请求正文带入 UI。
pub(super) struct SettingsRequestError {
    pub rpc_code: Option<i64>,
    pub message: String,
}

/// 未核实的保存保留原 ID、版本及密钥；configured 状态不能代替本次写入回执。
struct PendingSave {
    patch: Value,
    credential: Option<ModelSettingsCredential>,
    expected_credential_version: String,
    uncertain: bool,
}

/// 会话仅缓存已回读投影和本次未完成意图，不解释配置文件或重建项目覆盖。
#[derive(Default)]
pub(super) struct ModelSettingsSession {
    configuration: Option<Value>,
    pending: Option<PendingSave>,
}

impl ModelSettingsSession {
    /// transport 注入只隔离 I/O；生产和白盒测试执行同一保存与核实状态机。
    pub fn execute<F>(&mut self, request: ModelSettingsRequest, mut rpc: F) -> ModelSettingsResult
    where
        F: FnMut(&str, Value) -> Result<Value, SettingsRequestError>,
    {
        let (operation_id, outcome) = match request {
            ModelSettingsRequest::Read { operation_id } => {
                let outcome = if self.pending.as_ref().is_some_and(|value| value.uncertain) {
                    self.uncertain("保存结果待核实；按 F5 核实原操作，不能再次提交。")
                } else {
                    match self.read(&mut rpc) {
                        Ok(configuration) => ModelSettingsOutcome::Loaded { configuration },
                        Err(error) => failure(error),
                    }
                };
                (operation_id, outcome)
            }
            ModelSettingsRequest::Save {
                operation_id,
                expected_version,
                expected_credential_version,
                patch,
                credential,
            } => {
                let outcome = self.save(
                    expected_version,
                    expected_credential_version,
                    patch,
                    credential,
                    &mut rpc,
                );
                (operation_id, outcome)
            }
            ModelSettingsRequest::Verify { operation_id } => (operation_id, self.verify(&mut rpc)),
            ModelSettingsRequest::Test {
                operation_id,
                provider_id,
                model_id,
            } => {
                let outcome = match rpc(
                    "model/test",
                    json!({"providerId":provider_id,"modelId":model_id}),
                ) {
                    Ok(value) => match (
                        value.get("responseModel").and_then(Value::as_str),
                        value.get("latencyMs").and_then(Value::as_u64),
                    ) {
                        (Some(model), Some(latency)) if !model.trim().is_empty() => {
                            ModelSettingsOutcome::Tested {
                                response_model: model.to_owned(),
                                latency_ms: latency,
                            }
                        }
                        _ => ModelSettingsOutcome::Failed {
                            message: "连接验证返回无效结果；配置已保留，可重试或修改。".into(),
                        },
                    },
                    Err(error) => failure(error),
                };
                (operation_id, outcome)
            }
            ModelSettingsRequest::Repair {
                operation_id,
                expected_version,
                document,
            } => {
                let outcome = self.repair(expected_version, document, &mut rpc);
                (operation_id, outcome)
            }
        };
        ModelSettingsResult {
            operation_id,
            outcome,
        }
    }

    /// 普通刷新不改写文件；失败时仍持有最近确认投影供用户保留草稿。
    fn read<F>(&mut self, rpc: &mut F) -> Result<Value, SettingsRequestError>
    where
        F: FnMut(&str, Value) -> Result<Value, SettingsRequestError>,
    {
        let value = rpc("configuration/read", json!({}))?;
        if !value.get("effective").is_some_and(Value::is_object)
            || version(&value, "userVersion").is_none()
            || version(&value, "credentialVersion").is_none()
        {
            return Err(SettingsRequestError {
                rpc_code: None,
                message: "设置读取结果不完整；请重试，原草稿已保留。".into(),
            });
        }
        self.configuration = Some(value.clone());
        Ok(value)
    }

    /// 先保存配置再写密钥；通知或回执投影也可能在落盘后报 RPC 错误，只有写前拒绝才释放意图。
    fn save<F>(
        &mut self,
        expected_version: String,
        expected_credential_version: String,
        patch: Value,
        credential: Option<ModelSettingsCredential>,
        rpc: &mut F,
    ) -> ModelSettingsOutcome
    where
        F: FnMut(&str, Value) -> Result<Value, SettingsRequestError>,
    {
        if self.pending.as_ref().is_some_and(|value| value.uncertain) {
            return self.uncertain("上次保存结果待核实；请按 F5 核实后继续。");
        }
        if !patch.is_object() || expected_version.is_empty() {
            return ModelSettingsOutcome::Failed {
                message: "保存缺少配置版本或有效修改。".into(),
            };
        }
        self.pending = Some(PendingSave {
            patch: patch.clone(),
            credential,
            expected_credential_version,
            uncertain: false,
        });
        if !patch.as_object().is_some_and(|fields| fields.is_empty()) {
            match rpc(
                "configuration/patch",
                json!({"scope":"user","expectedVersion":expected_version,"patch":patch}),
            ) {
                Ok(value) if confirms_write(&value, "user") => {}
                Ok(_) => return self.mark_uncertain("配置保存回执无效；按 F5 核实。"),
                Err(error) if error.rpc_code == Some(-32011) => {
                    let configuration = self.read(rpc).ok();
                    self.pending = None;
                    return ModelSettingsOutcome::Conflict {
                        configuration,
                        message: "配置已被其它客户端修改；草稿已保留，请检查后重新保存。".into(),
                    };
                }
                Err(error) if definitely_rejected(&error) => {
                    self.pending = None;
                    return failure(error);
                }
                Err(_) => {
                    return self.mark_uncertain("配置保存结果待核实；按 F5 核实，不会自动重发。");
                }
            }
        }
        let configuration = match self.read(rpc) {
            Ok(value) => value,
            Err(_) => return self.mark_uncertain("配置提交后暂时无法回读；按 F5 核实。"),
        };
        if !patch_matches(&configuration, &patch) {
            self.pending = None;
            return ModelSettingsOutcome::Conflict {
                configuration: Some(configuration),
                message: "回读配置与本次修改不同；请检查当前值后重新保存。".into(),
            };
        }
        self.write_credential(rpc)
    }

    /// 密钥独立使用读取基线的 CAS；无法证明写前拒绝的错误保留内容核对材料，避免重复覆盖。
    fn write_credential<F>(&mut self, rpc: &mut F) -> ModelSettingsOutcome
    where
        F: FnMut(&str, Value) -> Result<Value, SettingsRequestError>,
    {
        let Some(pending) = &self.pending else {
            return ModelSettingsOutcome::Failed {
                message: "没有可继续的设置保存。".into(),
            };
        };
        let Some(credential) = &pending.credential else {
            self.pending = None;
            return self.saved();
        };
        let configuration = self
            .configuration
            .as_ref()
            .expect("configuration confirmed before credential");
        if !credential_reference_matches(configuration, credential) {
            self.pending = None;
            return ModelSettingsOutcome::Conflict {
                configuration: Some(configuration.clone()),
                message: "供应商的凭据引用已变化；请重新检查，未写入密钥。".into(),
            };
        }
        let credential_id = credential.credential_id.clone();
        let params = json!({
            "credentialId":credential_id,
            "secret":credential.secret,
            "expectedVersion":pending.expected_credential_version,
        });
        let committed_version = match rpc("credential/set", params) {
            Ok(value)
                if value.get("accepted").and_then(Value::as_bool) == Some(true)
                    && value.get("credentialId").and_then(Value::as_str)
                        == Some(&credential_id)
                    && value.get("configured").and_then(Value::as_bool) == Some(true)
                    && value
                        .get("version")
                        .and_then(Value::as_str)
                        .is_some_and(|version| !version.is_empty()) =>
            {
                value["version"]
                    .as_str()
                    .expect("validated version")
                    .to_owned()
            }
            Ok(_) => return self.mark_uncertain("密钥保存回执无效；按 F5 核实。"),
            Err(error) if error.rpc_code == Some(-32011) || definitely_rejected(&error) => {
                let message = if error.rpc_code == Some(-32011) {
                    "配置已保存，密钥被其它客户端修改；请核对后重新保存密钥。".to_owned()
                } else {
                    format!(
                        "配置已保存，密钥待补齐：{}",
                        safe_message(&error.message, self.pending.as_ref())
                    )
                };
                let _ = self.read(rpc);
                return self.needs_credential(message);
            }
            Err(_) => return self.mark_uncertain("配置已保存，密钥结果待核实；按 F5 核实。"),
        };
        let configuration = match self.read(rpc) {
            Ok(value) => value,
            Err(_) => return self.mark_uncertain("密钥提交后暂时无法回读；按 F5 核实。"),
        };
        let credential = self
            .pending
            .as_ref()
            .and_then(|value| value.credential.as_ref())
            .expect("credential pending");
        if !credential_reference_matches(&configuration, credential)
            || !credential_configured(&configuration, &credential_id)
            || version(&configuration, "credentialVersion") != Some(committed_version.as_str())
        {
            return self.mark_uncertain("密钥提交后的供应商状态已变化；按 F5 核实。");
        }
        if !self
            .pending
            .as_ref()
            .is_some_and(|pending| patch_matches(&configuration, &pending.patch))
        {
            self.pending = None;
            return ModelSettingsOutcome::Conflict {
                configuration: Some(configuration),
                message: "保存后配置已被修改；密钥步骤已完成，请检查最新配置。".into(),
            };
        }
        self.pending = None;
        self.saved()
    }

    /// 核实是用户明确触发的只读动作；secret 只在内存比较，不把 configured 当内容证明。
    fn verify<F>(&mut self, rpc: &mut F) -> ModelSettingsOutcome
    where
        F: FnMut(&str, Value) -> Result<Value, SettingsRequestError>,
    {
        let configuration = match self.read(rpc) {
            Ok(value) => value,
            Err(_) => return self.uncertain("暂时无法核实保存结果；保持草稿，稍后按 F5 重试。"),
        };
        let Some(pending) = &self.pending else {
            return ModelSettingsOutcome::Loaded { configuration };
        };
        if !patch_matches(&configuration, &pending.patch) {
            self.pending = None;
            return ModelSettingsOutcome::Conflict {
                configuration: Some(configuration),
                message: "本次修改未在当前配置中确认；请检查草稿后重新保存。".into(),
            };
        }
        let Some(credential) = &pending.credential else {
            self.pending = None;
            return self.saved();
        };
        if !credential_reference_matches(&configuration, credential) {
            self.pending = None;
            return ModelSettingsOutcome::Conflict {
                configuration: Some(configuration),
                message: "凭据引用已被修改；请重新检查，未自动重写密钥。".into(),
            };
        }
        let provider_id = credential.provider_id.clone();
        if !credential_configured(&configuration, &credential.credential_id) {
            if let Some(pending) = &mut self.pending {
                pending.uncertain = false;
            }
            return self.needs_credential("配置已确认，密钥尚未配置；请重新保存密钥。".into());
        }
        match rpc(
            "credential/reveal-provider",
            json!({"providerId":provider_id}),
        ) {
            Ok(mut value) => {
                let matches = self
                    .pending
                    .as_ref()
                    .and_then(|pending| pending.credential.as_ref())
                    .is_some_and(|credential| {
                        value.get("secret").and_then(Value::as_str)
                            == Some(credential.secret.as_str())
                    });
                if let Some(Value::String(secret)) = value.get_mut("secret") {
                    let mut bytes = std::mem::take(secret).into_bytes();
                    bytes.fill(0);
                }
                // reveal 只返回 Secret；再次读取引用和凭据 CAS，防止两次调用之间换绑导致误判。
                let verified_configuration = match self.read(rpc) {
                    Ok(current) => current,
                    Err(_) => return self.uncertain("密钥比较后暂时无法核实引用；按 F5 重试。"),
                };
                let reference_stable = self
                    .pending
                    .as_ref()
                    .and_then(|pending| pending.credential.as_ref())
                    .is_some_and(|credential| {
                        credential_reference_matches(&verified_configuration, credential)
                    });
                if !reference_stable {
                    self.pending = None;
                    return ModelSettingsOutcome::Conflict {
                        configuration: Some(verified_configuration),
                        message: "核实期间凭据引用已变化；请重新检查草稿。".into(),
                    };
                }
                if !self
                    .pending
                    .as_ref()
                    .is_some_and(|pending| patch_matches(&verified_configuration, &pending.patch))
                {
                    self.pending = None;
                    return ModelSettingsOutcome::Conflict {
                        configuration: Some(verified_configuration),
                        message: "核实期间模型配置已变化；请检查草稿后重新保存。".into(),
                    };
                }
                if version(&configuration, "credentialVersion")
                    != version(&verified_configuration, "credentialVersion")
                {
                    return self.uncertain("核实期间凭据已被修改；按 F5 重新核实。");
                }
                if matches {
                    self.pending = None;
                    self.saved()
                } else {
                    if let Some(pending) = &mut self.pending {
                        pending.uncertain = false;
                    }
                    self.needs_credential(
                        "配置已确认，当前密钥与本次输入不同；请检查后重新保存密钥。".into(),
                    )
                }
            }
            Err(_) => self.uncertain("暂时无法核实密钥内容；保持草稿，稍后按 F5 重试。"),
        }
    }

    /// 修复由 Java 备份原文并替换；落盘后投影错误与回执丢失均须核实，不能重复生成备份。
    fn repair<F>(
        &mut self,
        expected_version: String,
        document: Value,
        rpc: &mut F,
    ) -> ModelSettingsOutcome
    where
        F: FnMut(&str, Value) -> Result<Value, SettingsRequestError>,
    {
        if self.pending.as_ref().is_some_and(|value| value.uncertain) {
            return self.uncertain("上次保存结果待核实；请先按 F5 核实。");
        }
        self.pending = Some(PendingSave {
            patch: document.clone(),
            credential: None,
            expected_credential_version: String::new(),
            uncertain: false,
        });
        match rpc(
            "configuration/replace",
            json!({"scope":"user","expectedVersion":expected_version,"document":document}),
        ) {
            Ok(value) if confirms_write(&value, "user") => {}
            Ok(_) => return self.mark_uncertain("修复回执无效；按 F5 核实，不会重复替换。"),
            Err(error) if error.rpc_code == Some(-32011) => {
                let configuration = self.read(rpc).ok();
                self.pending = None;
                return ModelSettingsOutcome::Conflict {
                    configuration,
                    message: "配置已变化，修复未覆盖当前文件；请重新检查。".into(),
                };
            }
            Err(error) if definitely_rejected(&error) => {
                self.pending = None;
                return failure(error);
            }
            Err(_) => return self.mark_uncertain("修复结果待核实；按 F5 核实，不会重复替换。"),
        }
        match self.read(rpc) {
            Ok(configuration) if repair_matches(&configuration, &document) => {
                self.pending = None;
                self.saved()
            }
            _ => self.mark_uncertain("修复后的配置尚未核实；按 F5 重新读取。"),
        }
    }

    /// UI 只接收脱敏回读结果，密钥和写入意图继续留在 controller 的短生命周期内存。
    fn saved(&self) -> ModelSettingsOutcome {
        match &self.configuration {
            Some(configuration) => ModelSettingsOutcome::Saved {
                configuration: configuration.clone(),
            },
            None => ModelSettingsOutcome::Failed {
                message: "设置结果尚未回读。".into(),
            },
        }
    }

    /// 两阶段部分成功须可见；不回滚已保存 Provider，也不假装跨文件事务。
    fn needs_credential(&self, message: String) -> ModelSettingsOutcome {
        match &self.configuration {
            Some(configuration) => ModelSettingsOutcome::NeedsCredential {
                configuration: configuration.clone(),
                message,
            },
            None => self.uncertain(&message),
        }
    }

    /// 无法判断的写入锁住 pending，后续只允许显式核实或离开并放弃内存草稿。
    fn mark_uncertain(&mut self, message: &str) -> ModelSettingsOutcome {
        if let Some(pending) = &mut self.pending {
            pending.uncertain = true;
        }
        self.uncertain(message)
    }

    /// 最近确认投影用于保留可读界面，文案明确它不是本次操作成功的证明。
    fn uncertain(&self, message: &str) -> ModelSettingsOutcome {
        ModelSettingsOutcome::Uncertain {
            configuration: self.configuration.clone(),
            message: message.into(),
        }
    }
}

/// opaque CAS 只从 Java 投影读取；客户端不能推导或自增版本。
fn version<'a>(configuration: &'a Value, field: &str) -> Option<&'a str> {
    configuration
        .get("cas")?
        .get(field)?
        .as_str()
        .filter(|value| !value.is_empty())
}

/// 回执必须同时确认作用域与新版本，HTTP/RPC 返回并不代表已保存。
fn confirms_write(value: &Value, scope: &str) -> bool {
    value.get("accepted").and_then(Value::as_bool) == Some(true)
        && value.get("scope").and_then(Value::as_str) == Some(scope)
        && value
            .get("version")
            .and_then(Value::as_str)
            .is_some_and(|version| !version.is_empty())
}

/// 全局写入只以可用用户层文档核对；有效配置会回退到默认值，不能证明损坏文件已修复。
pub(super) fn patch_matches(configuration: &Value, patch: &Value) -> bool {
    if configuration
        .pointer("/user/status")
        .and_then(Value::as_str)
        .is_some_and(|status| matches!(status, "partial" | "corrupt" | "io_error"))
    {
        return false;
    }
    let Some(document) = configuration
        .pointer("/user/document")
        .filter(|value| value.is_object())
    else {
        return false;
    };
    fields_match(document, patch, false)
}

/// 按 ID 操作与顺序控制字段对应 Java 数组投影；普通嵌套对象仍按字段核对。
fn fields_match(actual: &Value, intended: &Value, provider: bool) -> bool {
    let Some(fields) = intended.as_object() else {
        return actual == intended;
    };
    if fields.get("__ja_null") == Some(&Value::Bool(true)) && fields.len() == 1 {
        return actual.is_null();
    }
    fields.iter().all(|(name, value)| match name.as_str() {
        "providers" if value.is_object() => {
            keyed_array_matches(actual.get(name), value, "provider_id", true)
        }
        "models" if provider && value.is_object() => {
            keyed_array_matches(actual.get(name), value, "model_id", false)
        }
        "provider_order" => order_matches(actual.get("providers"), value, "provider_id"),
        "model_order" if provider => order_matches(actual.get("models"), value, "model_id"),
        "config_revision" => true,
        _ if value.is_null() => actual.get(name).is_none_or(Value::is_null),
        _ => actual
            .get(name)
            .is_some_and(|field| fields_match(field, value, false)),
    })
}

/// null 删除核对实体缺失，新增/编辑只比较有意修改的字段，保留未知原始配置。
fn keyed_array_matches(
    actual: Option<&Value>,
    operations: &Value,
    id_field: &str,
    provider: bool,
) -> bool {
    let Some(operations) = operations.as_object() else {
        return false;
    };
    let items = actual
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or_default();
    operations.iter().all(|(id, value)| {
        let matched = items
            .iter()
            .find(|item| item.get(id_field).and_then(Value::as_str) == Some(id));
        if value.is_null() {
            matched.is_none()
        } else {
            matched.is_some_and(|item| fields_match(item, value, provider))
        }
    })
}

/// 顺序只比较操作列出的已知 ID，投影中未显示的坏条目不参与客户端排序。
fn order_matches(actual: Option<&Value>, order: &Value, id_field: &str) -> bool {
    let Some(order) = order.as_array() else {
        return false;
    };
    let expected: Option<Vec<&str>> = order.iter().map(Value::as_str).collect();
    let Some(expected) = expected else {
        return false;
    };
    let values: Vec<&str> = actual
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|item| item.get(id_field).and_then(Value::as_str))
        .filter(|id| expected.contains(id))
        .collect();
    values == expected
}

/// 修复同样要求可用用户文档；发布序号由 Java 拥有，不把默认投影视为修复成功。
fn repair_matches(configuration: &Value, document: &Value) -> bool {
    patch_matches(configuration, document)
}

/// 读取用户层身份，防止并发换连接后把旧草稿密钥写给新的 Provider 引用。
fn credential_reference_matches(
    configuration: &Value,
    credential: &ModelSettingsCredential,
) -> bool {
    configuration
        .pointer("/user/document/providers")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .any(|provider| {
            provider.get("provider_id").and_then(Value::as_str)
                == Some(credential.provider_id.as_str())
                && provider.get("credential_id").and_then(Value::as_str)
                    == Some(credential.credential_id.as_str())
        })
}

/// configured 只用于回执后校验可用状态，不能据此证明密钥与本次输入一致。
fn credential_configured(configuration: &Value, id: &str) -> bool {
    configuration
        .get("credentials")
        .and_then(|values| values.get(id))
        .and_then(|value| value.get("configured"))
        .and_then(Value::as_bool)
        == Some(true)
}

/// 部分成功文案剔除当前密钥，即使下游误带正文也不能把它展示到终端。
fn safe_message(message: &str, pending: Option<&PendingSave>) -> String {
    match pending
        .and_then(|value| value.credential.as_ref())
        .filter(|value| !value.secret.is_empty())
    {
        Some(credential) => message.replace(&credential.secret, "[已隐藏]"),
        None => message.to_owned(),
    }
}

/// 只有入站队列拒绝或写前读取损坏能证明没有提交；校验、存储和内部错误也可能来自写后投影。
fn definitely_rejected(error: &SettingsRequestError) -> bool {
    matches!(error.rpc_code, Some(-32008 | -32013))
}

/// 已确定失败可重试，未知写入另走保留状态的分支，避免所有异常混成同一种出口。
fn failure(error: SettingsRequestError) -> ModelSettingsOutcome {
    ModelSettingsOutcome::Failed {
        message: error.message,
    }
}
