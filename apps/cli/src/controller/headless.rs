// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Headless 调用共用有界输入、终答写入和稳定 JSONL v2 投影。

use super::CliError;
use serde_json::{Map, Value, json};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

pub(crate) const MAX_PROMPT_BYTES: usize = 4_000_000;
const MAX_SCHEMA_BYTES: u64 = 1024 * 1024;

/// 管道上下文与 prompt 合并为同一个 user message，保留原字节顺序且在连后台前完成准入。
pub(crate) fn read_prompt(
    prompt: Option<String>,
    stdin_is_terminal: bool,
    stdin: impl Read,
) -> Result<String, CliError> {
    let read_stdin = prompt.as_deref() == Some("-") || !stdin_is_terminal;
    let prompt = prompt.filter(|value| value != "-");
    let context = if read_stdin {
        let mut context = String::new();
        stdin
            .take((MAX_PROMPT_BYTES + 1) as u64)
            .read_to_string(&mut context)
            .map_err(CliError::transport)?;
        context
    } else {
        String::new()
    };
    let text = match (context.is_empty(), prompt) {
        (true, Some(prompt)) => prompt,
        (false, Some(prompt)) => format!("{context}\n\n{prompt}"),
        (false, None) => context,
        (true, None) => {
            return Err(CliError::usage(
                "exec 需要任务文本；从标准输入读取请使用 ja exec - 或管道输入",
            ));
        }
    };
    if text.trim().is_empty() || text.len() > MAX_PROMPT_BYTES || text.contains('\0') {
        return Err(CliError::usage("任务正文必须为 1–4,000,000 字节且不含 NUL"));
    }
    Ok(text)
}

/// 只接受有界 JSON object schema；最终合法性仍由 Java 与 Provider 能力共同裁决。
pub(crate) fn parse_output_schema(bytes: &[u8]) -> Result<Value, CliError> {
    if bytes.is_empty() || bytes.len() as u64 > MAX_SCHEMA_BYTES {
        return Err(CliError::usage(
            "终答 Schema 文件必须为 1 MiB 以内的非空 JSON object",
        ));
    }
    let value: Value =
        serde_json::from_slice(bytes).map_err(|_| CliError::usage("终答 Schema 不是有效 JSON"))?;
    if !value.is_object() {
        return Err(CliError::usage("终答 Schema 根节点必须是 JSON object"));
    }
    Ok(value)
}

/// 只把本次显式选择序列化为 runtime override，避免意外改写 Thread 或 Workspace 偏好。
pub(crate) fn runtime_overrides(
    model_id: Option<&str>,
    access_mode: Option<&str>,
    output_schema: Option<Value>,
) -> Result<Option<Map<String, Value>>, CliError> {
    let mut values = Map::new();
    if let Some(model_id) = model_id {
        if model_id.trim().is_empty() || model_id.len() > 256 || model_id.contains('\0') {
            return Err(CliError::usage("--model 需要有效的已配置模型ID"));
        }
        values.insert("modelId".into(), json!(model_id));
    }
    if let Some(access_mode) = access_mode {
        values.insert("accessMode".into(), json!(access_mode));
    }
    if let Some(schema) = output_schema {
        values.insert("outputSchema".into(), schema);
    }
    Ok((!values.is_empty()).then_some(values))
}

/// Schema 先检查文件元数据再有界读取，避免把巨大文件载入 headless 进程。
pub(crate) fn read_output_schema(path: &Path) -> Result<Value, CliError> {
    let metadata = fs::metadata(path).map_err(CliError::transport)?;
    if !metadata.is_file() || metadata.len() > MAX_SCHEMA_BYTES {
        return Err(CliError::usage("终答 Schema 必须是 1 MiB 以内的普通文件"));
    }
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    OpenOptions::new()
        .read(true)
        .open(path)
        .and_then(|file| file.take(MAX_SCHEMA_BYTES + 1).read_to_end(&mut bytes))
        .map_err(CliError::transport)?;
    parse_output_schema(&bytes)
}

/// 当前 Turn 的终答可能多次续写，按历史顺序保留该页最后一条公开 final_answer。
pub(crate) fn latest_final_answer_item<'a>(items: &'a [Value], turn_id: &str) -> Option<&'a Value> {
    items.iter().rev().find(|item| {
        item.get("turnId").and_then(Value::as_str) == Some(turn_id)
            && item.get("kind").and_then(Value::as_str) == Some("final_answer")
    })
}

/// 同目录暂存并原子替换目标，确保磁盘或权限错误不会截断已有终答文件。
pub(crate) fn write_last_message(path: &Path, text: &str) -> Result<(), CliError> {
    if path.as_os_str().is_empty() {
        return Err(CliError::usage("终答文件路径不能为空"));
    }
    let parent = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    if !parent.is_dir() {
        return Err(CliError::local_output("目标目录不存在"));
    }
    if path.file_name().is_none() {
        return Err(CliError::local_output("目标路径需要文件名"));
    }
    let mut last_collision = None;
    for _ in 0..3 {
        let temporary = parent.join(format!(
            ".ja-last-message-{}.tmp",
            uuid::Uuid::new_v4().simple()
        ));
        let cleanup = TemporaryOutputPath(temporary.clone());
        let mut file = match OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
        {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                last_collision = Some(error);
                continue;
            }
            Err(error) => return Err(CliError::local_output(error)),
        };
        file.write_all(text.as_bytes())
            .and_then(|_| file.flush())
            .and_then(|_| file.sync_all())
            .map_err(CliError::local_output)?;
        drop(file);
        atomic_replace(&temporary, path).map_err(CliError::local_output)?;
        drop(cleanup);
        return Ok(());
    }
    Err(CliError::local_output(last_collision.unwrap_or_else(
        || std::io::Error::other("无法生成唯一的终答暂存文件"),
    )))
}

/// 失败时仅清理由当前调用创建的同目录暂存文件，目标文件始终由原子替换控制。
struct TemporaryOutputPath(PathBuf);

impl Drop for TemporaryOutputPath {
    /// 临时文件清理错误不覆盖写入或原子替换的首要诊断。
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// Windows 通过 MoveFileExW 的 replace-existing 与 write-through 标志原子发布同卷暂存文件。
#[cfg(windows)]
fn atomic_replace(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;

    const MOVEFILE_REPLACE_EXISTING: u32 = 0x1;
    const MOVEFILE_WRITE_THROUGH: u32 = 0x8;

    let source = source
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();
    let destination = destination
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn MoveFileExW(existing: *const u16, replacement: *const u16, flags: u32) -> i32;
    }

    // SAFETY: both paths are NUL-terminated UTF-16 buffers that live through the synchronous Win32 call.
    if unsafe {
        MoveFileExW(
            source.as_ptr(),
            destination.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    } == 0
    {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

/// POSIX rename replaces an existing file atomically when source and destination share a directory.
#[cfg(not(windows))]
fn atomic_replace(source: &Path, destination: &Path) -> std::io::Result<()> {
    fs::rename(source, destination)
}

/// 给 Windows 用户生成可直接重试的指定 Thread/Turn 终答导出命令。
pub(crate) fn export_answer_command(thread_id: &str, turn_id: &str, path: &Path) -> String {
    format!(
        "ja export-answer {} {} {}",
        quote_powershell_literal(thread_id),
        quote_powershell_literal(turn_id),
        quote_powershell_literal(&path.to_string_lossy())
    )
}

/// PowerShell 单引号字符串通过双写单引号保留原参数，包含空格或引号时仍可复制执行。
fn quote_powershell_literal(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum ErrorNextAction {
    VerifyExecution {
        thread_id: String,
    },
    ExportAnswer {
        thread_id: String,
        turn_id: String,
        output: PathBuf,
    },
}

impl ErrorNextAction {
    /// JSONL 使用独立 argv 元素，保留空格和引号参数的原始边界。
    pub(crate) fn to_json(&self) -> Value {
        match self {
            Self::VerifyExecution { thread_id } => thread_action("verify_execution", thread_id),
            Self::ExportAnswer {
                thread_id,
                turn_id,
                output,
            } => json!({
                "kind":"export_answer",
                "argv":["ja","export-answer",thread_id,turn_id,output.to_string_lossy()],
                "automaticRetry":false
            }),
        }
    }
}

/// 显式本地恢复动作优先；没有动作元数据时保持按 Thread 核实执行状态的默认行为。
pub(crate) fn error_next_action(
    action: Option<ErrorNextAction>,
    thread_id: Option<&str>,
) -> Option<Value> {
    action.map(|value| value.to_json()).or_else(|| {
        thread_id.map(|thread_id| {
            ErrorNextAction::VerifyExecution {
                thread_id: thread_id.to_owned(),
            }
            .to_json()
        })
    })
}

/// 所有 JSONL 记录逐帧写入并 flush；下游断管道算作输出失败而不伪报成功。
pub(crate) fn write_record(output: &mut impl Write, record: Value) -> Result<(), CliError> {
    let mut bytes = serde_json::to_vec(&record).map_err(CliError::transport)?;
    bytes.push(b'\n');
    output
        .write_all(&bytes)
        .and_then(|_| output.flush())
        .map_err(|error| CliError::transport(format!("JSONL stdout 写入失败：{error}")))
}

/// accepted 身份在执行前冻结；所有脚本都能从首帧获得后台代际和可恢复身份。
pub(crate) fn accepted(server_instance_id: &str, thread_id: &str, turn_id: &str) -> Value {
    json!({
        "version":2,
        "type":"accepted",
        "serverInstanceId":server_instance_id,
        "threadId":thread_id,
        "turnId":turn_id
    })
}

/// 通知包保留服务端原始序号语义，不从 durable eventSeq 推算流式 streamSeq。
pub(crate) fn notification(
    server_instance_id: &str,
    thread_id: &str,
    method: &str,
    params: &Value,
) -> Value {
    json!({
        "version":2,
        "type":"notification",
        "serverInstanceId":server_instance_id,
        "threadId":thread_id,
        "turnId":params.get("turnId").cloned().unwrap_or(Value::Null),
        "eventSeq":params.get("eventSeq").cloned().unwrap_or(Value::Null),
        "streamSeq":params.get("streamSeq").cloned().unwrap_or(Value::Null),
        "method":method,
        "params":params
    })
}

/// requires_input 携带同一 Thread 的 argv 续接动作，脚本无需解析仅供人的 stderr 文案。
pub(crate) fn requires_input(
    server_instance_id: &str,
    thread_id: &str,
    turn_id: &str,
    state: &str,
) -> Value {
    json!({
        "version":2,
        "type":"requires_input",
        "serverInstanceId":server_instance_id,
        "threadId":thread_id,
        "turnId":turn_id,
        "state":state,
        "nextAction":thread_action("resolve_input", thread_id)
    })
}

/// 以 argv 数组表达恢复命令，避免把不透明 Thread identity 拼成可注入的 shell 字符串。
pub(crate) fn thread_action(kind: &str, thread_id: &str) -> Value {
    json!({
        "kind":kind,
        "argv":["ja","resume",thread_id],
        "automaticRetry":false
    })
}

/// 三类终态身份与序号收在一条记录中，避免实时和恢复投影漏带或串用字段。
pub(crate) struct TerminalRecord<'a> {
    pub server_instance_id: &'a str,
    pub thread_id: &'a str,
    pub turn_id: &'a str,
    pub state: &'a str,
    pub error_code: Option<&'a str>,
    pub final_text: Option<&'a str>,
    pub event_seq: Option<&'a Value>,
    pub stream_seq: Option<&'a Value>,
}

/// 实时事件和历史快照只通过此终态构造器投影，避免两个完成路径形成不同脚本契约。
pub(crate) fn terminal(record: TerminalRecord<'_>) -> Value {
    json!({
        "version":2,
        "type":"terminal",
        "serverInstanceId":record.server_instance_id,
        "threadId":record.thread_id,
        "turnId":record.turn_id,
        "state":record.state,
        "errorCode":record.error_code,
        "finalText":record.final_text,
        "eventSeq":record.event_seq.cloned().unwrap_or(Value::Null),
        "streamSeq":record.stream_seq.cloned().unwrap_or(Value::Null)
    })
}

/// 未能取得服务端执行终态的错误保持固定字段；已知 Thread 时只建议读回核实，不建议重发。
#[allow(clippy::too_many_arguments)]
pub(crate) fn error(
    server_instance_id: Option<&str>,
    thread_id: Option<&str>,
    turn_id: Option<&str>,
    code: Option<i64>,
    client_error_code: Option<&str>,
    message: &str,
    exit_code: u8,
    thread_action: Option<Value>,
) -> Value {
    let mut record = Map::new();
    record.insert("version".into(), json!(2));
    record.insert("type".into(), json!("error"));
    record.insert("serverInstanceId".into(), json!(server_instance_id));
    record.insert("threadId".into(), json!(thread_id));
    record.insert("turnId".into(), json!(turn_id));
    record.insert("code".into(), json!(code));
    record.insert("errorCode".into(), json!(client_error_code));
    record.insert("message".into(), json!(message));
    record.insert("exitCode".into(), json!(exit_code));
    record.insert("nextAction".into(), thread_action.unwrap_or(Value::Null));
    Value::Object(record)
}
