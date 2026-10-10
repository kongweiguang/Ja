// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 命令行语法保持小而明确，非法组合在连接 App Server 前拒绝。

use std::path::PathBuf;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    Interactive {
        cwd: Option<PathBuf>,
        prompt: Option<String>,
    },
    Resume(ResumeRequest),
    Exec(ExecOptions),
    Queue {
        thread_id: String,
        turn_id: Option<String>,
        text: String,
        kind: String,
        operation_key: Option<String>,
    },
    ExportAnswer {
        thread_id: String,
        turn_id: String,
        output: PathBuf,
    },
    Review(ReviewSource),
    ServerStatus,
    ServerStop {
        force: bool,
    },
    Setup,
    Help(HelpTopic),
    Version,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HelpTopic {
    General,
    Resume,
    Exec,
    Queue,
    ExportAnswer,
    Review,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ResumeRequest {
    pub target: ResumeTarget,
    pub prompt: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum ResumeTarget {
    Last,
    All,
    ThreadId(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecOptions {
    pub cwd: Option<PathBuf>,
    pub prompt: Option<String>,
    pub json: bool,
    pub images: Vec<PathBuf>,
    pub output_schema: Option<PathBuf>,
    pub output_last_message: Option<PathBuf>,
    pub model: Option<String>,
    pub permissions: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReviewSource {
    Uncommitted,
    Base(String),
    Commit(String),
}

const HELP: &str = "Ja 终端客户端\n\n用法:\n  ja [-C 目录] [任务]\n  ja resume [--last|--all|thread-id] [--prompt 续接内容]\n  ja exec [-C 目录] [--json] [--image 图片] [--output-schema 文件] [--output-last-message 文件] [--model 模型ID] [--permissions approval-required|full-access] [任务|-]\n  ja queue <thread-id> [--kind steering|follow_up] [--turn-id ID --operation-key KEY] <内容>\n  ja export-answer <thread-id> <turn-id> <文件>\n  ja review [--uncommitted|--base <ref>|--commit <hash>]\n  ja setup                  管理全局供应商、模型与 API Key\n  ja server status\n  ja server stop [--force]\n\n通用参数:\n  -C 目录                    在指定目录创建会话\n  -h, --help                 显示帮助\n  -V, --version              显示版本\n\nexec 参数:\n  --json                     输出统一 JSONL v2 事件\n  --image 文件               将图片作为模型输入，可重复指定，最多 10 张\n  --output-schema 文件       使用 JSON Schema 校验本次终答\n  --output-last-message 文件 将最终回复写入文件\n  --model 模型ID             本次执行覆盖，必须是已配置的模型标识\n  --permissions 模式         approval-required 或 full-access，仅影响本次执行\n  任务或 -                   可与 stdin 管道内容组合；单独使用 - 时从 stdin 读取任务\n";

const RESUME_HELP: &str = "用法:\n  ja resume [--last|--all|thread-id] [--prompt 续接内容]\n\n恢复最近会话、列出全部可恢复会话，或按 thread-id 恢复指定会话。\n--prompt 会在选定会话中提交为新的用户 Turn。\n-h, --help 显示本帮助。\n";

const EXEC_HELP: &str = "用法:\n  ja exec [-C 目录] [--json] [--image 图片] [--output-schema 文件] [--output-last-message 文件] [--model 模型ID] [--permissions approval-required|full-access] [任务|-]\n\n非交互执行一个新 Turn。任务可与 stdin 管道内容组合；任务为 - 时直接从 stdin 读取。\n--json 输出 accepted、notification、requires_input、terminal 或 error 记录，版本为 JSONL v2。\n--image 可重复指定，最多 10 张；--output-schema 按本次终答校验。\n--model 和 --permissions 仅覆盖本次执行，不修改会话偏好。\n-h, --help 显示本帮助。\n";

const QUEUE_HELP: &str = "用法:\n  ja queue <thread-id> [--kind steering|follow_up] [--turn-id ID --operation-key KEY] <内容>\n\n将内容追加到已有 Thread 的输入队列，不创建 Thread 或 Turn。\n--kind 缺省为 follow_up；内容作为一个参数传入，含空格时请用引号。\n回执待核实时，使用错误提示中的完整命令以相同参数和操作键重试。\n-h, --help 显示本帮助。\n";

const EXPORT_ANSWER_HELP: &str = "用法:\n  ja export-answer <thread-id> <turn-id> <文件>\n\n只读取指定 Thread/Turn 的完整终答并原子写入文件，不创建 Thread 或 Turn。\n-h, --help 显示本帮助。\n";

const REVIEW_HELP: &str = "用法:\n  ja review [--uncommitted|--base <ref>|--commit <hash>]\n\n审查工作区未提交改动，或选择基准分支/提交作为差异来源。\n省略来源时使用 --uncommitted。\n-h, --help 显示本帮助。\n";

/// 固定子命令与位置参数的语义，避免多余参数被当成任务正文发送。
pub fn parse<I>(args: I) -> Result<Command, String>
where
    I: IntoIterator<Item = String>,
{
    let mut args = args.into_iter().peekable();
    let Some(first) = args.peek().cloned() else {
        return Ok(Command::Interactive {
            cwd: None,
            prompt: None,
        });
    };
    if first == "-h" || first == "--help" {
        args.next();
        return if args.next().is_none() {
            Ok(Command::Help(HelpTopic::General))
        } else {
            Err("帮助命令不能携带其他参数".into())
        };
    }
    if first == "-V" || first == "--version" {
        args.next();
        return if args.next().is_none() {
            Ok(Command::Version)
        } else {
            Err("版本命令不能携带其他参数".into())
        };
    }
    match first.as_str() {
        "resume" => parse_resume(args),
        "exec" => parse_exec(args),
        "queue" => parse_queue(args),
        "export-answer" => parse_export_answer(args),
        "review" => parse_review(args),
        "server" => {
            args.next();
            match args.next().as_deref() {
                Some("status") if args.next().is_none() => Ok(Command::ServerStatus),
                Some("stop") => match args.next().as_deref() {
                    None => Ok(Command::ServerStop { force: false }),
                    Some("--force") if args.next().is_none() => {
                        Ok(Command::ServerStop { force: true })
                    }
                    _ => Err("server stop 仅接受 --force".into()),
                },
                _ => Err("server 仅支持 status 或 stop".into()),
            }
        }
        "setup" => {
            args.next();
            if args.next().is_none() {
                Ok(Command::Setup)
            } else {
                Err("setup 不接受参数".into())
            }
        }
        _ => {
            let (cwd, prompt) = parse_interactive_options(args)?;
            Ok(Command::Interactive { cwd, prompt })
        }
    }
}

/// 续接目标是互斥选择；缺省使用当前目录最近的会话，并把续接正文与恢复动作分开。
fn parse_resume<I>(mut args: std::iter::Peekable<I>) -> Result<Command, String>
where
    I: Iterator<Item = String>,
{
    args.next();
    let mut target = None;
    let mut prompt = None;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--last" | "--all" => {
                if target.is_some() {
                    return Err("resume 只能指定一个目标".into());
                }
                target = Some(if arg == "--last" {
                    ResumeTarget::Last
                } else {
                    ResumeTarget::All
                });
            }
            "--prompt" => {
                if prompt.is_some() {
                    return Err("--prompt 只能指定一次".into());
                }
                let value = args.next().ok_or("--prompt 需要续接内容")?;
                if value.trim().is_empty() {
                    return Err("续接内容不能为空".into());
                }
                prompt = Some(value);
            }
            "-h" | "--help" => return Ok(Command::Help(HelpTopic::Resume)),
            value if value.starts_with('-') => return Err(format!("未知 resume 选项：{value}")),
            value => {
                if target.is_some() {
                    return Err("resume 只能指定一个 thread-id 或目标选项".into());
                }
                target = Some(ResumeTarget::ThreadId(non_empty(
                    Some(value.to_owned()),
                    "resume thread-id 不能为空",
                )?));
            }
        }
    }
    Ok(Command::Resume(ResumeRequest {
        target: target.unwrap_or(ResumeTarget::Last),
        prompt,
    }))
}

/// 解析 headless 执行的显式输入和逐次覆盖；值在调用 Java 前仍由权威配置校验。
fn parse_exec<I>(mut args: std::iter::Peekable<I>) -> Result<Command, String>
where
    I: Iterator<Item = String>,
{
    args.next();
    let mut options = ExecOptions {
        cwd: None,
        prompt: None,
        json: false,
        images: Vec::new(),
        output_schema: None,
        output_last_message: None,
        model: None,
        permissions: None,
    };
    let mut positional = false;
    while let Some(arg) = args.next() {
        if !positional && matches!(arg.as_str(), "-h" | "--help") {
            return Ok(Command::Help(HelpTopic::Exec));
        } else if !positional && arg == "--" {
            positional = true;
        } else if !positional && arg == "-C" {
            set_once(
                &mut options.cwd,
                args.next()
                    .filter(|value| !value.is_empty())
                    .map(PathBuf::from),
                "-C 需要目录路径",
                "-C 只能指定一次",
            )?;
        } else if !positional && arg == "--json" {
            if options.json {
                return Err("--json 只能指定一次".into());
            }
            options.json = true;
        } else if !positional && arg == "--image" {
            let value = args.next().ok_or("--image 需要文件路径")?;
            if value.is_empty() {
                return Err("--image 需要非空文件路径".into());
            }
            if options.images.len() == 10 {
                return Err("一次最多添加 10 张图片".into());
            }
            options.images.push(PathBuf::from(value));
        } else if !positional && arg == "--output-schema" {
            set_once(
                &mut options.output_schema,
                args.next()
                    .filter(|value| !value.is_empty())
                    .map(PathBuf::from),
                "--output-schema 需要文件路径",
                "--output-schema 只能指定一次",
            )?;
        } else if !positional && arg == "--output-last-message" {
            set_once(
                &mut options.output_last_message,
                args.next()
                    .filter(|value| !value.is_empty())
                    .map(PathBuf::from),
                "--output-last-message 需要文件路径",
                "--output-last-message 只能指定一次",
            )?;
        } else if !positional && arg == "--model" {
            set_once(
                &mut options.model,
                args.next().filter(|value| !value.trim().is_empty()),
                "--model 需要模型ID",
                "--model 只能指定一次",
            )?;
        } else if !positional && arg == "--permissions" {
            let value = args.next().ok_or("--permissions 需要权限模式")?;
            let mode = match value.as_str() {
                "approval-required" => "approval_required",
                "full-access" => "full_access",
                _ => return Err("--permissions 仅支持 approval-required 或 full-access".into()),
            };
            if options.permissions.replace(mode.to_owned()).is_some() {
                return Err("--permissions 只能指定一次".into());
            }
        } else if !positional && arg.starts_with('-') && arg != "-" {
            return Err(format!("未知 exec 选项：{arg}"));
        } else if options.prompt.replace(arg).is_some() {
            return Err("exec 任务文本只能是一个参数；含空格时请用引号".into());
        }
    }
    Ok(Command::Exec(options))
}

/// 将队列入口固定到一个已存在 Thread，避免命令悄悄创建新会话或不明 turn。
fn parse_queue<I>(mut args: std::iter::Peekable<I>) -> Result<Command, String>
where
    I: Iterator<Item = String>,
{
    args.next();
    if args
        .peek()
        .is_some_and(|arg| matches!(arg.as_str(), "-h" | "--help"))
    {
        return Ok(Command::Help(HelpTopic::Queue));
    }
    let thread_id = non_empty(args.next(), "queue 需要 thread-id")?;
    let mut kind = "follow_up".to_owned();
    let mut kind_set = false;
    let mut turn_id = None;
    let mut operation_key = None;
    let mut text = None;
    while let Some(arg) = args.next() {
        if matches!(arg.as_str(), "-h" | "--help") {
            return Ok(Command::Help(HelpTopic::Queue));
        } else if arg == "--kind" {
            if kind_set {
                return Err("--kind 只能指定一次".into());
            }
            kind_set = true;
            kind = match args.next().as_deref() {
                Some("steering") => "steering".into(),
                Some("follow_up") => "follow_up".into(),
                _ => return Err("--kind 仅支持 steering 或 follow_up".into()),
            };
        } else if arg == "--turn-id" {
            set_once(
                &mut turn_id,
                args.next().filter(|value| !value.trim().is_empty()),
                "--turn-id 需要 Turn ID",
                "--turn-id 只能指定一次",
            )?;
        } else if arg == "--operation-key" {
            set_once(
                &mut operation_key,
                args.next().filter(|value| !value.trim().is_empty()),
                "--operation-key 需要操作键",
                "--operation-key 只能指定一次",
            )?;
        } else if text.replace(arg).is_some() {
            return Err("queue 内容必须作为一个参数传入；含空格时请用引号".into());
        }
    }
    let text = text.ok_or("queue 需要内容")?;
    if text.trim().is_empty() {
        return Err("queue 内容不能为空".into());
    }
    if turn_id.is_some() != operation_key.is_some() {
        return Err("--turn-id 与 --operation-key 必须同时指定，用于原请求重试".into());
    }
    if operation_key
        .as_deref()
        .is_some_and(|key| !valid_operation_key(key))
    {
        return Err("--operation-key 格式必须为 op_ 加 32 位小写十六进制".into());
    }
    Ok(Command::Queue {
        thread_id,
        turn_id,
        text,
        kind,
        operation_key,
    })
}

/// 只读终答导出必须绑定明确 Thread、Turn 和目标文件，避免隐式读取或生成当前会话。
fn parse_export_answer<I>(mut args: std::iter::Peekable<I>) -> Result<Command, String>
where
    I: Iterator<Item = String>,
{
    args.next();
    if args
        .peek()
        .is_some_and(|arg| matches!(arg.as_str(), "-h" | "--help"))
    {
        return Ok(Command::Help(HelpTopic::ExportAnswer));
    }
    let thread_id = non_empty(args.next(), "export-answer 需要 thread-id")?;
    let turn_id = non_empty(args.next(), "export-answer 需要 turn-id")?;
    let output = non_empty(args.next(), "export-answer 需要输出文件").map(PathBuf::from)?;
    if args.next().is_some() {
        return Err("export-answer 只接受 thread-id、turn-id 和输出文件".into());
    }
    Ok(Command::ExportAnswer {
        thread_id,
        turn_id,
        output,
    })
}

/// CLI 持久副作用共享 operation ledger 的固定键格式，供显式恢复入口提前拒绝误输。
pub(super) fn valid_operation_key(value: &str) -> bool {
    value.strip_prefix("op_").is_some_and(|suffix| {
        suffix.len() == 32
            && suffix
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    })
}

/// Review 来源互斥，默认审查工作区未提交差异，避免隐式扫描仓库历史。
fn parse_review<I>(mut args: std::iter::Peekable<I>) -> Result<Command, String>
where
    I: Iterator<Item = String>,
{
    args.next();
    let mut source = None;
    while let Some(arg) = args.next() {
        let selected = match arg.as_str() {
            "-h" | "--help" => return Ok(Command::Help(HelpTopic::Review)),
            "--uncommitted" => ReviewSource::Uncommitted,
            "--base" => ReviewSource::Base(non_empty(args.next(), "--base 需要 ref")?),
            "--commit" => ReviewSource::Commit(non_empty(args.next(), "--commit 需要 hash")?),
            _ => return Err(format!("未知 review 选项：{arg}")),
        };
        if source.replace(selected).is_some() {
            return Err("review 只能指定一种来源".into());
        }
    }
    Ok(Command::Review(source.unwrap_or(ReviewSource::Uncommitted)))
}

/// 普通交互入口只接受工作目录和一个可选首条任务，斜杠命令仍由 TUI 解析。
fn parse_interactive_options<I>(args: I) -> Result<(Option<PathBuf>, Option<String>), String>
where
    I: IntoIterator<Item = String>,
{
    let mut cwd = None;
    let mut prompt = None;
    let mut positional = false;
    let mut args = args.into_iter();
    while let Some(arg) = args.next() {
        if !positional && arg == "--" {
            positional = true;
        } else if !positional && arg == "-C" {
            set_once(
                &mut cwd,
                args.next().map(PathBuf::from),
                "-C 需要目录路径",
                "-C 只能指定一次",
            )?;
        } else if !positional && arg.starts_with('-') {
            return Err(format!("未知选项：{arg}"));
        } else if prompt.replace(arg).is_some() {
            return Err("任务文本必须作为一个参数传入；含空格时请用引号".into());
        }
    }
    if cwd.as_ref().is_some_and(|path| path.as_os_str().is_empty()) {
        return Err("-C 需要非空目录路径".into());
    }
    Ok((cwd, prompt))
}

/// 同一参数遇到重复与缺值时使用不同诊断，减少脚本依赖模糊错误文字。
fn set_once<T>(
    slot: &mut Option<T>,
    value: Option<T>,
    missing: &str,
    duplicate: &str,
) -> Result<(), String> {
    let value = value.ok_or_else(|| missing.to_owned())?;
    if slot.replace(value).is_some() {
        return Err(duplicate.into());
    }
    Ok(())
}

/// 结构化参数不可为空白，否则下游身份或 Git 解析错误会掩盖命令输入错误。
fn non_empty(value: Option<String>, message: &str) -> Result<String, String> {
    value
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| message.to_owned())
}

/// 所有帮助主题集中映射，确保参数说明与实际解析入口共用唯一正文。
pub fn help(topic: HelpTopic) -> &'static str {
    match topic {
        HelpTopic::General => HELP,
        HelpTopic::Resume => RESUME_HELP,
        HelpTopic::Exec => EXEC_HELP,
        HelpTopic::Queue => QUEUE_HELP,
        HelpTopic::ExportAnswer => EXPORT_ANSWER_HELP,
        HelpTopic::Review => REVIEW_HELP,
    }
}
