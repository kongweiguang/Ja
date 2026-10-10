// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 显式设置与会话复用同一面板，配置和凭据仍由 Java App Server 拥有。

use super::{CliError, rpc::Connection};
use serde_json::{Value, json};
use std::io::{self, IsTerminal, Write};

/// 设置可随时管理全局模型；开始对话前先恢复终端，再进入常规会话信任流程。
pub fn run() -> Result<(), CliError> {
    if super::model_settings::run_standalone()? {
        super::interactive::run(None, None, None)?;
    }
    Ok(())
}

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

/// 信任提示仅在 TUI 启动前读取整行，EOF 或取消保持未信任，不与原始终端输入竞争。
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
