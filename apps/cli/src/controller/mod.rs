// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! CLI 命令协调只持有当前连接与视图，不复制 Java 的配置和会话状态机。

mod args;
mod attachment;
mod exec;
mod interactive;
mod projection;
mod rpc;
mod setup;

use args::Command;
use rpc::Connection;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub struct CliError {
    exit_code: u8,
    message: String,
    rpc_code: Option<i64>,
    uncertain: bool,
}

impl CliError {
    /// 参数错误在接入服务前收敛，不生成会话或后台副作用。
    fn usage(message: impl Into<String>) -> Self {
        Self {
            exit_code: 2,
            message: message.into(),
            rpc_code: None,
            uncertain: false,
        }
    }

    /// 文件与打包资源问题属于本地配置失败，给出可辨认的稳定分类。
    fn configuration(message: impl Into<String>) -> Self {
        Self {
            exit_code: 4,
            message: message.into(),
            rpc_code: None,
            uncertain: false,
        }
    }

    /// 连接异常可能发生在提交 ACK 丢失之后，调用方不得自动重发 Turn。
    fn transport(error: impl std::fmt::Display) -> Self {
        Self {
            exit_code: 4,
            message: format!("连接失败：{error}"),
            rpc_code: None,
            uncertain: false,
        }
    }

    /// 服务端错误码与消息只用于展示、冲突处理和退出码映射，权威状态需另行读取。
    fn rpc(code: i64, message: String) -> Self {
        Self {
            exit_code: 1,
            message: format!("请求失败 ({code})：{message}"),
            rpc_code: Some(code),
            uncertain: false,
        }
    }

    /// 缺失必填字段表示服务端合同不符，不能给 UI 推断出来的假状态。
    fn protocol(message: impl Into<String>) -> Self {
        Self {
            exit_code: 4,
            message: message.into(),
            rpc_code: None,
            uncertain: false,
        }
    }

    /// 非交互执行等待审批或澄清时保留 Thread，可由另一客户端恢复处理。
    fn needs_input(thread_id: &str) -> Self {
        Self {
            exit_code: 3,
            message: format!("会话 {thread_id} 需要用户输入；可用 ja resume {thread_id} 处理"),
            rpc_code: None,
            uncertain: false,
        }
    }

    /// 无默认模型时只允许交互式配置；脚本模式明确告知需要输入并退出。
    fn needs_setup() -> Self {
        Self {
            exit_code: 3,
            message: "Ja 尚未配置默认模型；请在交互终端运行 ja 完成首次设置".into(),
            rpc_code: None,
            uncertain: false,
        }
    }

    /// 新项目未受信任时，脚本模式不代替用户授予持久 Workspace 信任。
    fn needs_trust() -> Self {
        Self {
            exit_code: 3,
            message: "当前项目尚未受信任；请在交互终端运行 ja 进行授权".into(),
            rpc_code: None,
            uncertain: false,
        }
    }

    /// unknown 是持久提交结果待核实，不是“可安全重试”的失败。
    fn uncertain_operation(id: &str) -> Self {
        Self {
            exit_code: 4,
            message: format!("提交结果待核实（操作 {id}）；已停止自动重试，可从会话历史继续检查"),
            rpc_code: None,
            uncertain: true,
        }
    }

    /// 子命令只读取这两个安全字段，不输出协议 payload 或宿主环境。
    pub fn message(&self) -> &str {
        &self.message
    }
    pub fn exit_code(&self) -> u8 {
        self.exit_code
    }
    /// 未知提交属于可继续观察的状态，不应因为退出码 4 关闭整个 TUI。
    fn is_uncertain(&self) -> bool {
        self.uncertain
    }
}

pub struct ThreadContext {
    pub thread_id: String,
    pub title: String,
    pub workspace_id: String,
    pub workspace_root: String,
    pub model_id: String,
    pub provider_id: String,
    pub reasoning_level: Option<String>,
    pub access_mode: String,
    pub revision: u64,
}

/// 命令仅在需要时连接；status/stop 不会隐式创建第二个后台 owner。
pub fn run<I>(args: I) -> Result<(), CliError>
where
    I: IntoIterator<Item = String>,
{
    let command = args::parse(args).map_err(CliError::usage)?;
    match command {
        Command::Help => {
            print!("{}", args::help());
            Ok(())
        }
        Command::Version => {
            println!("ja {}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        Command::ServerStatus => {
            let mut connection = Connection::connect_existing()?;
            let health = connection.request("runtime/health", json!({}))?;
            let status = required_str(&health, "status")?;
            println!("{status} ({})", connection.server_instance_id());
            connection.disconnect()?;
            Ok(())
        }
        Command::ServerStop { force } => {
            let mut connection = Connection::connect_existing()?;
            connection.stop_server(force)?;
            let _ = connection.disconnect();
            println!("Ja App Server 正在关闭");
            Ok(())
        }
        Command::Exec { cwd, prompt, json } => exec::run(cwd, prompt, json),
        Command::Interactive { cwd, prompt } => interactive::run(cwd, prompt, None, false),
        Command::Resume { thread_id } => interactive::run(None, None, thread_id, true),
    }
}

/// `-C` 和启动目录必须真实存在，避免 Java 创建一个与 shell 当前项目无关的 session。
fn canonical_workspace(cwd: Option<PathBuf>) -> Result<PathBuf, CliError> {
    let cwd = match cwd {
        Some(path) => path,
        None => std::env::current_dir().map_err(CliError::transport)?,
    };
    let cwd = std::fs::canonicalize(cwd).map_err(|_| CliError::usage("项目目录不存在"))?;
    if !cwd.is_dir() {
        return Err(CliError::usage("项目路径不是目录"));
    }
    Ok(cwd)
}

/// 规范目录统一解析为 Java Workspace identity，避免 Thread 创建与恢复列表的项目边界分叉。
fn create_thread(
    connection: &mut Connection,
    cwd: &Path,
    allow_setup: bool,
) -> Result<ThreadContext, CliError> {
    let cwd = cwd
        .to_str()
        .ok_or_else(|| CliError::usage("项目目录路径不是有效 Unicode"))?;
    let workspace = open_workspace(connection, Path::new(cwd))?;
    let workspace_id = required_str(&workspace, "workspaceId")?.to_owned();
    if workspace.get("trust").and_then(Value::as_str) == Some("untrusted") {
        if !allow_setup {
            return Err(CliError::needs_trust());
        }
        setup::confirm_workspace_trust(connection, cwd, &workspace_id)?;
    }
    let mut config =
        connection.request("configuration/read", json!({"workspaceId":workspace_id}))?;
    if config
        .get("effective")
        .and_then(|value| value.get("default_model_id"))
        .is_some_and(Value::is_null)
    {
        if !allow_setup {
            return Err(CliError::needs_setup());
        }
        setup::configure_default_model(connection, &config)?;
        config = connection.request("configuration/read", json!({"workspaceId":workspace_id}))?;
    }
    if setup::ensure_default_credential(connection, &config, allow_setup)? {
        config = connection.request("configuration/read", json!({"workspaceId":workspace_id}))?;
    }
    let effective = config
        .get("effective")
        .ok_or_else(|| CliError::protocol("缺少配置快照"))?;
    let provider = effective
        .get("default_provider_id")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            CliError::configuration("尚未配置默认 Provider，请先在 Ja 设置中选择模型")
        })?;
    let model = effective
        .get("default_model_id")
        .and_then(Value::as_str)
        .ok_or_else(|| CliError::configuration("尚未配置默认模型，请先在 Ja 设置中选择模型"))?;
    let access_mode = effective
        .get("default_access_mode")
        .and_then(Value::as_str)
        .ok_or_else(|| CliError::protocol("配置缺少默认权限模式"))?;
    let thread = connection.request("thread/create", json!({
        "cwd":cwd,
        "title":"新对话",
        "providerId":provider,
        "modelId":model,
        "reasoningLevel":effective.get("default_reasoning_level").cloned().unwrap_or(Value::Null),
        "accessMode":access_mode,
        "collaborationMode":"default"
    }))?;
    if required_str(&thread, "workspaceId")? != workspace_id {
        return Err(CliError::protocol(
            "创建会话返回的 Workspace identity 不一致",
        ));
    }
    Ok(ThreadContext {
        thread_id: required_str(&thread, "threadId")?.to_owned(),
        title: required_str(&thread, "title")?.to_owned(),
        workspace_id,
        workspace_root: cwd.to_owned(),
        model_id: model.to_owned(),
        provider_id: provider.to_owned(),
        reasoning_level: effective
            .get("default_reasoning_level")
            .and_then(Value::as_str)
            .map(str::to_owned),
        access_mode: access_mode.to_owned(),
        revision: required_u64(&thread, "revision")?,
    })
}

/// 用与新会话相同的规范路径解析 Workspace，确保恢复列表和 Thread 创建绑定同一项目身份。
fn open_workspace(connection: &mut Connection, cwd: &Path) -> Result<Value, CliError> {
    let display_name = cwd
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| {
            !name.trim().is_empty() && name.len() <= 512 && !name.chars().any(char::is_control)
        })
        .unwrap_or("当前项目");
    let cwd = cwd
        .to_str()
        .ok_or_else(|| CliError::usage("项目目录路径不是有效 Unicode"))?;
    connection.request(
        "workspace/open",
        json!({"cwd":cwd,"displayName":display_name}),
    )
}

/// 强制字符串字段存在，避免带空 identity 的请求穿过客户端边界。
fn required_str<'a>(value: &'a Value, field: &str) -> Result<&'a str, CliError> {
    value
        .get(field)
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| CliError::protocol(format!("协议字段 {field} 缺失")))
}

/// revision 是 Java 签发的 CAS，不从客户端本地计数构造。
fn required_u64(value: &Value, field: &str) -> Result<u64, CliError> {
    value
        .get(field)
        .and_then(Value::as_u64)
        .ok_or_else(|| CliError::protocol(format!("协议字段 {field} 缺失")))
}
