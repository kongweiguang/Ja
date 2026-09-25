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
    Resume {
        thread_id: Option<String>,
    },
    Exec {
        cwd: Option<PathBuf>,
        prompt: String,
        json: bool,
    },
    ServerStatus,
    ServerStop {
        force: bool,
    },
    Help,
    Version,
}

const HELP: &str = "Ja 终端客户端\n\n用法:\n  ja [-C 目录] [任务]\n  ja resume [thread-id]\n  ja exec [-C 目录] [--json] <任务|->\n  ja server status\n  ja server stop [--force]\n\n参数:\n  -C 目录       在指定目录创建会话\n  --json        exec 输出 JSONL 事件\n  -h, --help    显示帮助\n  -V, --version 显示版本\n";

/// 固定子命令与位置参数的语义，避免一个多余参数被当成任务正文发送。
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
            Ok(Command::Help)
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
        "resume" => {
            args.next();
            let thread_id = args.next();
            if args.next().is_some() {
                return Err("resume 最多接收一个 thread-id".into());
            }
            Ok(Command::Resume { thread_id })
        }
        "exec" => {
            args.next();
            let (cwd, json, prompt) = parse_task_options(args, true)?;
            let prompt = prompt.ok_or("exec 需要任务文本；从标准输入读取请使用 -")?;
            Ok(Command::Exec { cwd, prompt, json })
        }
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
        _ => {
            let (cwd, _, prompt) = parse_task_options(args, false)?;
            Ok(Command::Interactive { cwd, prompt })
        }
    }
}

/// 解析最小选项集；`--` 明确结束选项，以便任务正文可以从连字符开始。
fn parse_task_options<I>(
    args: I,
    exec: bool,
) -> Result<(Option<PathBuf>, bool, Option<String>), String>
where
    I: IntoIterator<Item = String>,
{
    let mut args = args.into_iter().peekable();
    let mut cwd = None;
    let mut json = false;
    let mut prompt = None;
    let mut positional = false;
    while let Some(arg) = args.next() {
        if !positional && arg == "--" {
            positional = true;
        } else if !positional && arg == "-C" {
            if cwd.is_some() {
                return Err("-C 只能指定一次".into());
            }
            let value = args.next().ok_or("-C 需要目录路径")?;
            if value.is_empty() {
                return Err("-C 需要非空目录路径".into());
            }
            cwd = Some(PathBuf::from(value));
        } else if !positional && arg == "--json" && exec {
            if json {
                return Err("--json 只能指定一次".into());
            }
            json = true;
        } else if !positional && arg.starts_with('-') && !(exec && arg == "-") {
            return Err(format!("未知选项：{arg}"));
        } else if prompt.replace(arg).is_some() {
            return Err("任务文本必须作为一个参数传入；含空格时请用引号".into());
        }
    }
    Ok((cwd, json, prompt))
}

/// 帮助正文只从此处生成，确保参数错误和显式帮助显示相同的可执行语法。
pub fn help() -> &'static str {
    HELP
}
