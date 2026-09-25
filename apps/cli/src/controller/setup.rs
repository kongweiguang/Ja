// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 首次配置只写 Java 拥有的 v2 用户文档和凭据 RPC；CLI 不接触配置文件。

use super::{CliError, required_str, rpc::Connection};
use crossterm::event::{
    self, DisableBracketedPaste, EnableBracketedPaste, Event, KeyCode, KeyEventKind, KeyModifiers,
};
use crossterm::{execute, terminal};
use serde_json::{Value, json};
use std::io::{self, IsTerminal, Write};

/// 向导仅暂存用户已输入的 Provider、模型和凭据材料，提交与回滚仍由 Java RPC 决定。
type ProviderWizardDraft = (String, String, Option<Value>, Option<(String, String)>);

/// 项目覆盖层与 Tool 权限必须由用户明确授予；拒绝时不改变 Java 的 Workspace 信任事实。
pub fn confirm_workspace_trust(
    connection: &mut Connection,
    cwd: &str,
    workspace_id: &str,
) -> Result<(), CliError> {
    if !io::stdin().is_terminal() || !io::stderr().is_terminal() {
        return Err(CliError::needs_trust());
    }
    eprintln!("当前项目尚未受信任：{cwd}");
    let answer = prompt_line("信任此项目并允许读取项目配置与执行工具？ y/N", Some("N"))?;
    if !matches!(answer.as_str(), "y" | "Y" | "yes" | "YES") {
        return Err(CliError::needs_trust());
    }
    let result = connection.request(
        "workspace/set-trust",
        json!({"workspaceId":workspace_id,"trust":"trusted"}),
    )?;
    if result.get("accepted").and_then(Value::as_bool) != Some(true) {
        return Err(CliError::protocol("项目信任设置未获 Java 确认"));
    }
    Ok(())
}

/// 无默认模型时在独占终端进行一次最小设置；脚本环境需要返回明确的“需要输入”。
pub fn configure_default_model(
    connection: &mut Connection,
    config: &Value,
) -> Result<(), CliError> {
    if !io::stdin().is_terminal() || !io::stderr().is_terminal() {
        return Err(CliError::needs_setup());
    }
    eprintln!("Ja 首次设置 · 选择模型后即可开始对话 · 输入 /cancel 可取消\n");
    let user = config
        .get("user")
        .and_then(|value| value.get("document"))
        .filter(|value| !value.is_null());
    let providers = user
        .and_then(|value| value.get("providers"))
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let (provider_id, model_id, new_provider, credential) = if providers.is_empty() {
        create_provider_wizard()?
    } else {
        let (provider_id, model_id) = choose_existing_model(&providers)?;
        (provider_id, model_id, None, None)
    };
    let user_version = config
        .get("cas")
        .and_then(|cas| cas.get("userVersion"))
        .and_then(Value::as_str)
        .ok_or_else(|| CliError::protocol("配置缺少 userVersion"))?
        .to_owned();
    let mut credential_rollback = None;
    if let Some((credential_id, secret)) = credential {
        let version = config
            .get("cas")
            .and_then(|cas| cas.get("credentialVersion"))
            .and_then(Value::as_str)
            .ok_or_else(|| CliError::protocol("配置缺少 credentialVersion"))?;
        let stored = connection.request(
            "credential/set",
            json!({
                "credentialId":credential_id,"secret":secret,"expectedVersion":version
            }),
        )?;
        credential_rollback = Some((credential_id, required_str(&stored, "version")?.to_owned()));
    }
    let mut patch = json!({
        "default_provider_id":provider_id,"default_model_id":model_id
    });
    if let Some(provider) = new_provider {
        let mut updated = providers;
        updated.push(provider);
        patch["providers"] = Value::Array(updated);
    }
    let write = connection.request(
        "configuration/patch",
        json!({
            "scope":"user","expectedVersion":user_version,"patch":patch
        }),
    );
    if write.is_err()
        && let Some((credential_id, version)) = credential_rollback
    {
        let _ = connection.request(
            "credential/delete",
            json!({
                "credentialId":credential_id,"expectedVersion":version
            }),
        );
    }
    let write = write?;
    if write.get("accepted").and_then(Value::as_bool) != Some(true) {
        return Err(CliError::protocol("配置保存未获 Java 确认"));
    }
    eprintln!("\n设置已保存。\n");
    Ok(())
}

/// Java 当前运行时要求 Provider 凭据非空；已有目录但未配置密钥时也要在首屏补齐。
pub fn ensure_default_credential(
    connection: &mut Connection,
    config: &Value,
    allow_setup: bool,
) -> Result<bool, CliError> {
    let effective = config
        .get("effective")
        .ok_or_else(|| CliError::protocol("配置缺少 effective"))?;
    let Some(provider_id) = effective.get("default_provider_id").and_then(Value::as_str) else {
        return Ok(false);
    };
    let provider = effective
        .get("providers")
        .and_then(Value::as_array)
        .and_then(|providers| {
            providers.iter().find(|provider| {
                provider.get("provider_id").and_then(Value::as_str) == Some(provider_id)
            })
        })
        .ok_or_else(|| CliError::protocol("默认 Provider 不在有效目录中"))?;
    let credential_id = required_str(provider, "credential_id")?;
    if config
        .get("credentials")
        .and_then(|items| items.get(credential_id))
        .and_then(|state| state.get("configured"))
        .and_then(Value::as_bool)
        == Some(true)
    {
        return Ok(false);
    }
    if !allow_setup || !io::stdin().is_terminal() || !io::stderr().is_terminal() {
        return Err(CliError::needs_setup());
    }
    eprintln!(
        "当前默认 Provider 尚未配置 API Key：{}",
        required_str(provider, "name")?
    );
    let secret = prompt_nonempty_secret()?;
    let version = config
        .get("cas")
        .and_then(|cas| cas.get("credentialVersion"))
        .and_then(Value::as_str)
        .ok_or_else(|| CliError::protocol("配置缺少 credentialVersion"))?;
    let stored = connection.request(
        "credential/set",
        json!({
            "credentialId":credential_id,"secret":secret,"expectedVersion":version
        }),
    )?;
    if stored.get("accepted").and_then(Value::as_bool) != Some(true)
        || stored.get("credentialId").and_then(Value::as_str) != Some(credential_id)
    {
        return Err(CliError::protocol("Provider 凭据未获 Java 确认"));
    }
    eprintln!("凭据已保存。\n");
    Ok(true)
}

/// 已配置 Provider 只需让用户明确选择上游模型，再经 CAS 保存默认引用。
fn choose_existing_model(providers: &[Value]) -> Result<(String, String), CliError> {
    let mut models = Vec::new();
    for provider in providers {
        let provider_id = required_str(provider, "provider_id")?;
        let provider_name = required_str(provider, "name")?;
        if let Some(entries) = provider.get("models").and_then(Value::as_array) {
            for model in entries {
                let model_id = required_str(model, "model_id")?;
                let identifier = required_str(model, "model")?;
                models.push((
                    provider_id.to_owned(),
                    model_id.to_owned(),
                    provider_name.to_owned(),
                    identifier.to_owned(),
                ));
            }
        }
    }
    if models.is_empty() {
        return Err(CliError::configuration("现有 Provider 没有可用模型"));
    }
    for (index, (_, _, provider, model)) in models.iter().enumerate() {
        eprintln!("  {:>2}. {model} · {provider}", index + 1);
    }
    let index = loop {
        let selection = prompt_line("选择模型编号", Some("1"))?;
        if let Some(index) = selection
            .parse::<usize>()
            .ok()
            .filter(|value| *value >= 1 && *value <= models.len())
        {
            break index - 1;
        }
        eprintln!("模型编号无效，请选择 1..={}", models.len());
    };
    Ok((models[index].0.clone(), models[index].1.clone()))
}

/// 新 Provider 沿用桌面已有通用起始预算，但不猜模型的推理档位或上游标识。
fn create_provider_wizard() -> Result<ProviderWizardDraft, CliError> {
    let name = loop {
        let value = prompt_line("Provider 名称", None)?;
        if value.len() <= 512 {
            break value;
        }
        eprintln!("Provider 名称最多 512 字符，请重新输入");
    };
    let api = loop {
        let value = prompt_line(
            "API 协议 1=OpenAI Responses, 2=Anthropic Messages, 3=OpenAI Chat Completions",
            Some("1"),
        )?;
        match value.as_str() {
            "1" => break "openai_responses",
            "2" => break "anthropic_messages",
            "3" => break "openai_chat_completions",
            _ => eprintln!("API 协议编号无效，请选择 1、2 或 3"),
        }
    };
    let base_url = loop {
        let value = prompt_line("Base URL（完整 http/https 地址）", None)?;
        if value.starts_with("https://") || value.starts_with("http://") {
            break value;
        }
        eprintln!("Base URL 必须以 http:// 或 https:// 开始，请重新输入");
    };
    let model_identifier = loop {
        let value = prompt_line("上游模型标识", None)?;
        if value.len() <= 512 {
            break value;
        }
        eprintln!("模型标识最多 512 字符，请重新输入");
    };
    let context_window = prompt_u64("上下文上限 tokens", 128_000, 4_096, 4_000_000)?;
    let max_output = prompt_u64("最大输出 tokens", 8_192, 1, 1_000_000)?;
    if max_output >= context_window {
        return Err(CliError::usage("最大输出必须小于上下文上限"));
    }
    let secret = prompt_nonempty_secret()?;
    let provider_id = format!("provider_{}", uuid::Uuid::new_v4().simple());
    let model_id = format!("model_{}", uuid::Uuid::new_v4().simple());
    let credential_id = format!("cred_{}", uuid::Uuid::new_v4().simple());
    let provider = json!({
        "provider_id":provider_id,"name":name,"api":api,"base_url":base_url,
        "credential_id":credential_id,
        "network_timeouts":{"connect_timeout_ms":10000,"request_timeout_ms":120000},
        "agent_defaults":{"context":{"auto_compact":true}},
        "models":[{"model_id":model_id,"name":model_identifier,"model":model_identifier,
            "capabilities":{"context_window_tokens":context_window,"max_output_tokens":max_output},
            "reasoning_level_map":{},"default_reasoning_level":null}]
    });
    let credential = Some((credential_id, secret));
    Ok((provider_id, model_id, Some(provider), credential))
}

/// 普通输入错误就地重试，EOF 或 /cancel 才退出，避免填错一项后重走整份向导。
fn prompt_line(label: &str, default: Option<&str>) -> Result<String, CliError> {
    loop {
        match default {
            Some(value) => eprint!("{label} [{value}]: "),
            None => eprint!("{label}: "),
        }
        io::stderr().flush().map_err(CliError::transport)?;
        let mut line = String::new();
        let count = io::stdin()
            .read_line(&mut line)
            .map_err(CliError::transport)?;
        if count == 0 {
            return Err(CliError::usage("设置已取消"));
        }
        let line = line.trim();
        if matches!(line, "/cancel" | "\u{1b}") {
            return Err(CliError::usage("设置已取消"));
        }
        let value = if line.is_empty() {
            default.unwrap_or("")
        } else {
            line
        };
        if !value.is_empty() && value.len() <= 2_048 && !value.chars().any(char::is_control) {
            return Ok(value.to_owned());
        }
        eprintln!("设置输入不能为空、超过 2048 字节或包含控制字符，请重新输入");
    }
}

/// 数值预算按配置 schema 校验并就地重试，避免用户改正一个数字时从头填写。
fn prompt_u64(label: &str, default: u64, min: u64, max: u64) -> Result<u64, CliError> {
    loop {
        let value = prompt_line(label, Some(&default.to_string()))?;
        if let Some(number) = value
            .parse::<u64>()
            .ok()
            .filter(|number| *number >= min && *number <= max)
        {
            return Ok(number);
        }
        eprintln!("{label} 必须位于 {min}..={max}，请重新输入");
    }
}

/// 空密钥保留在当前设置步骤重试，SecretGuard 每次都负责恢复回显与粘贴模式。
fn prompt_nonempty_secret() -> Result<String, CliError> {
    loop {
        let secret = prompt_secret("API Key（必填，输入不回显）")?;
        if !secret.is_empty() {
            return Ok(secret);
        }
        eprintln!("API Key 不能为空，请重新输入");
    }
}

struct SecretGuard;

impl Drop for SecretGuard {
    /// 无论输入成功、取消还是异常返回，都恢复终端回显与粘贴模式。
    fn drop(&mut self) {
        let _ = execute!(io::stderr(), DisableBracketedPaste);
        let _ = terminal::disable_raw_mode();
    }
}

/// Secret 仅通过无回显原生输入收集并提交 credential/set，不进入 shell history 或普通日志。
fn prompt_secret(label: &str) -> Result<String, CliError> {
    eprint!("{label}: ");
    io::stderr().flush().map_err(CliError::transport)?;
    terminal::enable_raw_mode().map_err(CliError::transport)?;
    let _guard = SecretGuard;
    execute!(io::stderr(), EnableBracketedPaste).map_err(CliError::transport)?;
    let mut secret = String::new();
    loop {
        match event::read().map_err(CliError::transport)? {
            Event::Key(key) if key.kind == KeyEventKind::Press => match key.code {
                KeyCode::Enter => {
                    eprintln!();
                    if secret == "/cancel" {
                        return Err(CliError::usage("设置已取消"));
                    }
                    return Ok(secret);
                }
                KeyCode::Esc => return Err(CliError::usage("设置已取消")),
                KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                    return Err(CliError::usage("设置已取消"));
                }
                KeyCode::Backspace => {
                    if secret.pop().is_some() {
                        eprint!("\u{8} \u{8}");
                    }
                }
                KeyCode::Char(ch) if !ch.is_control() && secret.len() + ch.len_utf8() <= 8_192 => {
                    secret.push(ch);
                    eprint!("•");
                }
                _ => {}
            },
            Event::Paste(text)
                if text.len() + secret.len() <= 8_192 && !text.chars().any(char::is_control) =>
            {
                secret.push_str(&text);
                eprint!("•");
            }
            _ => {}
        }
        io::stderr().flush().map_err(CliError::transport)?;
    }
}
