// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// CLI 命令协调只持有当前连接与视图，不复制 Java 的配置和会话状态机。

mod answer_export;
mod args;
mod attachment;
mod clipboard;
mod exec;
mod external_editor;
mod headless;
mod interactive;
mod model_settings;
mod model_settings_recovery;
mod preference_recovery;
mod projection;
mod queue_recovery;
mod resync_notification;
mod review;
mod rpc;
mod server_stop;
mod setup;
mod suspended_recovery;
mod usage;

use args::Command;
use queue_recovery::retry_command as queue_retry_command;
use rpc::Connection;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub struct CliError {
    exit_code: u8,
    message: String,
    rpc_code: Option<i64>,
    client_error_code: Option<&'static str>,
    uncertain: bool,
    read_retryable: bool,
}

impl CliError {
    /// 参数错误在接入服务前收敛，不生成会话或后台副作用。
    fn usage(message: impl Into<String>) -> Self {
        Self {
            exit_code: 2,
            message: message.into(),
            rpc_code: None,
            client_error_code: None,
            uncertain: false,
            read_retryable: false,
        }
    }

    /// 文件与打包资源问题属于本地配置失败，给出可辨认的稳定分类。
    fn configuration(message: impl Into<String>) -> Self {
        Self {
            exit_code: 4,
            message: message.into(),
            rpc_code: None,
            client_error_code: None,
            uncertain: false,
            read_retryable: false,
        }
    }

    /// 连接异常可能发生在提交 ACK 丢失之后，调用方不得自动重发 Turn。
    fn transport(error: impl std::fmt::Display) -> Self {
        Self {
            exit_code: 4,
            message: format!("连接失败：{error}"),
            rpc_code: None,
            client_error_code: None,
            uncertain: false,
            read_retryable: true,
        }
    }

    /// 本地终答文件失败使用稳定客户端错误码，避免与 Provider 执行错误混淆。
    fn local_output(error: impl std::fmt::Display) -> Self {
        Self {
            exit_code: 4,
            message: format!("终答文件写入失败：{error}"),
            rpc_code: None,
            client_error_code: Some("CLIENT_OUTPUT_FAILURE"),
            uncertain: false,
            read_retryable: false,
        }
    }

    /// 保留原始本地写入错误分类，并给已完成的 Turn 附上只读恢复命令。
    fn with_recovery_command(mut self, command: &str) -> Self {
        self.message
            .push_str(&format!("。可运行以下命令重新导出：{command}"));
        self
    }

    /// 服务端错误码与消息只用于展示、冲突处理和退出码映射，权威状态需另行读取。
    fn rpc(code: i64, message: String) -> Self {
        Self {
            exit_code: 1,
            message: format!("请求失败 ({code})：{message}"),
            rpc_code: Some(code),
            client_error_code: None,
            uncertain: false,
            // 只读快照会遇到队列、存储与时限故障；权限、身份和协议错误不自动重试。
            read_retryable: matches!(code, -32008 | -32012 | -32014 | -32020),
        }
    }

    /// 缺失必填字段表示服务端合同不符，不能给 UI 推断出来的假状态。
    fn protocol(message: impl Into<String>) -> Self {
        Self {
            exit_code: 4,
            message: message.into(),
            rpc_code: None,
            client_error_code: None,
            uncertain: false,
            read_retryable: false,
        }
    }

    /// Review collection and model failures are task failures, distinct from RPC incompatibility.
    fn review_failed(message: impl Into<String>) -> Self {
        Self {
            exit_code: 1,
            message: message.into(),
            rpc_code: None,
            client_error_code: None,
            uncertain: false,
            read_retryable: false,
        }
    }

    /// 非交互执行等待审批或澄清时保留 Thread，可由另一客户端恢复处理。
    fn needs_input(thread_id: &str) -> Self {
        Self {
            exit_code: 3,
            message: format!("会话 {thread_id} 需要用户输入；可用 ja resume {thread_id} 处理"),
            rpc_code: None,
            client_error_code: None,
            uncertain: false,
            read_retryable: false,
        }
    }

    /// 无可用模型或凭据时给出统一管理入口；脚本不隐式启动交互面板或配置写入。
    fn needs_setup() -> Self {
        Self {
            exit_code: 3,
            message: "Ja 尚未配置可用模型或凭据；运行 `ja setup` 完成配置后可在当前终端继续".into(),
            rpc_code: None,
            client_error_code: None,
            uncertain: false,
            read_retryable: false,
        }
    }

    /// 新项目未受信任时，脚本模式不代替用户授予持久 Workspace 信任。
    fn needs_trust() -> Self {
        Self {
            exit_code: 3,
            message: "当前项目尚未受信任；请在交互终端运行 ja 进行授权".into(),
            rpc_code: None,
            client_error_code: None,
            uncertain: false,
            read_retryable: false,
        }
    }

    /// unknown 是持久提交结果待核实，不是“可安全重试”的失败。
    fn uncertain_operation(id: &str) -> Self {
        Self {
            exit_code: 4,
            message: format!("提交结果待核实（操作 {id}）；已停止自动重试，可从会话历史继续检查"),
            rpc_code: None,
            client_error_code: None,
            uncertain: true,
            read_retryable: false,
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

/// 命令仅在需要时连接；resume 正文由恢复流程选定 Thread 后消费一次，不能同时充当启动正文。
/// status/stop 不会隐式创建第二个后台 owner。
pub fn run<I>(args: I) -> Result<(), CliError>
where
    I: IntoIterator<Item = String>,
{
    let command = args::parse(args).map_err(CliError::usage)?;
    match command {
        Command::Help(topic) => {
            print!("{}", args::help(topic));
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
        Command::Exec(options) => exec::run(options),
        Command::Interactive { cwd, prompt } => interactive::run(cwd, prompt, None),
        Command::Resume(request) => interactive::run(None, None, Some(request)),
        Command::Queue {
            thread_id,
            turn_id,
            text,
            kind,
            operation_key,
        } => queue_input(
            &thread_id,
            turn_id.as_deref(),
            &text,
            &kind,
            operation_key.as_deref(),
        ),
        Command::ExportAnswer {
            thread_id,
            turn_id,
            output,
        } => answer_export::run(&thread_id, &turn_id, &output),
        Command::Review(source) => review::run(source),
        Command::Setup => setup::run(),
    }
}

/// @author kongweiguang
/// 外部 queue 固定目标 Turn 与 operation key；快照的来源字段不能省略或默认成本会话执行。
fn queue_input(
    thread_id: &str,
    requested_turn_id: Option<&str>,
    text: &str,
    kind: &str,
    requested_operation_key: Option<&str>,
) -> Result<(), CliError> {
    if !matches!(kind, "steering" | "follow_up")
        || text.trim().is_empty()
        || text.len() > 500_000
        || text.contains('\0')
    {
        return Err(CliError::usage("queue 内容或类型超出服务端队列限制"));
    }
    let mut connection = Connection::connect_existing()?;
    let snapshot = connection.request(
        "thread/read",
        json!({"threadId":thread_id,"tail":true,"limit":200}),
    )?;
    interactive::validate_history_origins(&snapshot)?;
    if required_str(&snapshot, "threadId")? != thread_id {
        return Err(CliError::protocol("Thread 快照身份与 queue 目标不一致"));
    }
    let turn_id = if let Some(turn_id) = requested_turn_id {
        turn_id
    } else {
        let turn = snapshot
            .get("turns")
            .and_then(Value::as_array)
            .and_then(|turns| turns.last())
            .ok_or_else(|| CliError::usage("目标会话没有可排队的 Turn"))?;
        let state = required_str(turn, "status")?;
        if !matches!(
            state,
            "queued" | "running" | "waiting_input" | "waiting_approval" | "suspended"
        ) {
            return Err(CliError::usage("目标会话当前没有可接收输入的 Turn"));
        }
        required_str(turn, "turnId")?
    };
    let operation_key = requested_operation_key
        .map(str::to_owned)
        .unwrap_or_else(|| format!("op_{}", uuid::Uuid::new_v4().simple()));
    let result = match connection.request_operation_with_key(
        "turn/input/enqueue",
        json!({
            "turnId":turn_id,
            "content":[{"type":"text","text":text}],
            "kind":kind
        }),
        &operation_key,
    ) {
        Ok(result) => result,
        Err(mut error) if error.is_uncertain() => {
            let command = queue_retry_command(thread_id, turn_id, kind, text, &operation_key);
            error.message.push_str(&format!(
                "。在 PowerShell 中用相同参数核实或重试：{command}"
            ));
            return Err(error);
        }
        Err(error) => return Err(error),
    };
    if result.get("accepted").and_then(Value::as_bool) != Some(true) {
        return Err(CliError::protocol("服务端未确认输入已进入队列"));
    }
    let input_id = required_str(&result, "inputId")?;
    let _ = connection.disconnect();
    println!("已排入会话 {thread_id}，Turn {turn_id}，输入 {input_id}");
    Ok(())
}

/// CLI 调用方提供可辨认的 Thread 标题；模型从配置唯一解析，不猜测 Provider 归属。
fn create_exec_thread(
    connection: &mut Connection,
    cwd: &Path,
    model_override: Option<&str>,
    title: &str,
) -> Result<ThreadContext, CliError> {
    let cwd_text = cwd
        .to_str()
        .ok_or_else(|| CliError::usage("项目目录路径不是有效 Unicode"))?;
    let workspace = open_workspace(connection, cwd)?;
    let workspace_id = required_str(&workspace, "workspaceId")?.to_owned();
    if workspace.get("trust").and_then(Value::as_str) == Some("untrusted") {
        return Err(CliError::needs_trust());
    }
    let configuration =
        connection.request("configuration/read", json!({"workspaceId":workspace_id}))?;
    let effective = configuration
        .get("effective")
        .ok_or_else(|| CliError::protocol("缺少配置快照"))?;
    let default_provider_id = effective.get("default_provider_id").and_then(Value::as_str);
    let default_model_id = effective.get("default_model_id").and_then(Value::as_str);
    let selection = match model_override {
        Some(model_id) => configured_model(effective, model_id)?,
        None => match (default_provider_id, default_model_id) {
            (Some(provider_id), Some(model_id)) => (provider_id, model_id),
            _ => return Err(CliError::needs_setup()),
        },
    };
    let access_mode = effective
        .get("default_access_mode")
        .and_then(Value::as_str)
        .ok_or_else(|| CliError::protocol("配置缺少默认权限模式"))?;
    let reasoning_level =
        if Some(selection.0) == default_provider_id && Some(selection.1) == default_model_id {
            effective
                .get("default_reasoning_level")
                .cloned()
                .unwrap_or(Value::Null)
        } else {
            Value::Null
        };
    let thread = connection.request(
        "thread/create",
        json!({
            "cwd":cwd_text,
            "title":title,
            "providerId":selection.0,
            "modelId":selection.1,
            "reasoningLevel":reasoning_level,
            "accessMode":access_mode,
            "collaborationMode":"default"
        }),
    )?;
    if required_str(&thread, "workspaceId")? != workspace_id {
        return Err(CliError::protocol(
            "创建会话返回的 Workspace identity 不一致",
        ));
    }
    Ok(ThreadContext {
        thread_id: required_str(&thread, "threadId")?.to_owned(),
        title: required_str(&thread, "title")?.to_owned(),
        workspace_id,
        workspace_root: cwd_text.to_owned(),
        model_id: selection.1.to_owned(),
        provider_id: selection.0.to_owned(),
        reasoning_level: reasoning_level.as_str().map(str::to_owned),
        access_mode: access_mode.to_owned(),
        revision: required_u64(&thread, "revision")?,
    })
}

/// 配置模型表以 provider_id/models.model_id 形成唯一映射，未知或重复 ID 都显式拒绝。
fn configured_model<'a>(
    effective: &'a Value,
    model_id: &str,
) -> Result<(&'a str, &'a str), CliError> {
    let providers = effective
        .get("providers")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("配置缺少 Provider 模型目录"))?;
    let mut found = None;
    for provider in providers {
        let provider_id = required_str(provider, "provider_id")?;
        let models = provider
            .get("models")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("Provider 缺少模型目录"))?;
        for model in models {
            if model.get("model_id").and_then(Value::as_str) == Some(model_id) {
                if found.is_some() {
                    return Err(CliError::protocol("模型 ID 在配置目录中重复"));
                }
                found = Some((provider_id, required_str(model, "model_id")?));
            }
        }
    }
    found.ok_or_else(|| CliError::usage(format!("未找到已配置模型：{model_id}")))
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
    let config = connection.request("configuration/read", json!({"workspaceId":workspace_id}))?;
    let effective = config
        .get("effective")
        .ok_or_else(|| CliError::protocol("缺少配置快照"))?;
    if effective.get("default_model_id").is_none_or(Value::is_null) {
        return Err(CliError::needs_setup());
    }
    let provider = effective
        .get("default_provider_id")
        .and_then(Value::as_str)
        .ok_or_else(CliError::needs_setup)?;
    let model = effective
        .get("default_model_id")
        .and_then(Value::as_str)
        .ok_or_else(CliError::needs_setup)?;
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
