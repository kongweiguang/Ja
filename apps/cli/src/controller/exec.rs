// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 非交互运行只提交一次 Turn；断线和 ACK 不确定时均不自动重发有副作用请求。

use super::{
    CliError,
    args::ExecOptions,
    attachment::{ImportedAttachment, import_paths},
    canonical_workspace, create_exec_thread,
    headless::{
        ErrorNextAction, TerminalRecord, accepted, error as error_record, error_next_action,
        export_answer_command, notification, read_output_schema, read_prompt, requires_input,
        runtime_overrides, terminal, write_last_message, write_record,
    },
    required_str,
    resync_notification::{ResyncNotificationDeduper, parse_resync_required},
    rpc::Connection,
};
use ja_runtime::app_server_process::{EventPump, SessionEvent};
use serde_json::{Value, json};
use std::io::{self, IsTerminal, Write};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};

const SNAPSHOT_INTERVAL: Duration = Duration::from_secs(1);
const CANCEL_WAIT: Duration = Duration::from_secs(10);
const CONTENT_PAGE_CHARACTERS: u64 = 65_536;

#[derive(Debug)]
struct ExecIdentity {
    server_instance_id: Option<String>,
    thread_id: Option<String>,
    turn_id: Option<String>,
}

enum WaitOutcome {
    Completed,
    NeedsInput(CliError),
    TerminalError(CliError),
    ClientError(CliError),
    OutputWriteFailure {
        error: CliError,
        action: ErrorNextAction,
    },
}

/// 等待期间反复复用同一 Turn 身份；文件输出和终态模式随请求冻结。
struct TurnWaitContext<'a> {
    thread_id: &'a str,
    workspace_id: &'a str,
    turn_id: &'a str,
    json_output: bool,
    output_last_message: Option<&'a std::path::Path>,
}

/// 持久快照终态所需的字段合并传递，避免摘要页与最终回答使用不同身份。
struct SnapshotTerminal<'a> {
    thread_id: &'a str,
    snapshot: &'a Value,
    turn_id: &'a str,
    state: &'a str,
    json_output: bool,
    output_last_message: Option<&'a std::path::Path>,
}

/// 所有本地输入先有界解析，再连接唯一 Java owner；JSON 错误附带可读回核实的 v2 动作。
pub fn run(options: ExecOptions) -> Result<(), CliError> {
    let json_output = options.json;
    let mut identity = ExecIdentity {
        server_instance_id: None,
        thread_id: None,
        turn_id: None,
    };
    let mut output = io::stdout().lock();
    match run_inner(options, &mut output, &mut identity) {
        Ok(WaitOutcome::Completed) => Ok(()),
        Ok(WaitOutcome::NeedsInput(error) | WaitOutcome::TerminalError(error)) => Err(error),
        Ok(WaitOutcome::OutputWriteFailure { error, action }) => {
            if json_output {
                write_error_record(&mut output, &identity, &error, Some(action))?;
            }
            Err(error)
        }
        Ok(WaitOutcome::ClientError(error)) | Err(error) => {
            if json_output {
                write_error_record(&mut output, &identity, &error, None)?;
            }
            Err(error)
        }
    }
}

/// JSONL 失败记录沿用原身份；只有调用点明确提供的终答文件恢复动作才覆盖默认核实建议。
fn write_error_record(
    output: &mut impl Write,
    identity: &ExecIdentity,
    error: &CliError,
    action: Option<ErrorNextAction>,
) -> Result<(), CliError> {
    write_record(
        output,
        error_record(
            identity.server_instance_id.as_deref(),
            identity.thread_id.as_deref(),
            identity.turn_id.as_deref(),
            error.rpc_code,
            error.client_error_code,
            error.message(),
            error.exit_code(),
            error_next_action(action, identity.thread_id.as_deref()),
        ),
    )
}

/// Server 仅接收完整的有界 prompt、当前模型参数和已导入附件，不收到 CLI 文件系统路径。
fn run_inner(
    options: ExecOptions,
    output: &mut impl Write,
    identity: &mut ExecIdentity,
) -> Result<WaitOutcome, CliError> {
    let prompt = read_prompt(
        options.prompt.clone(),
        io::stdin().is_terminal(),
        io::stdin().lock(),
    )?;
    let output_schema = options
        .output_schema
        .as_deref()
        .map(read_output_schema)
        .transpose()?;
    let cwd = canonical_workspace(options.cwd.clone())?;
    let mut connection = Connection::connect_or_start()?;
    identity.server_instance_id = Some(connection.server_instance_id().to_owned());
    let thread = create_exec_thread(&mut connection, &cwd, options.model.as_deref(), "新对话")?;
    identity.thread_id = Some(thread.thread_id.clone());
    connection.observe(&thread.thread_id)?;
    let mut pump = connection.take_event_pump()?;
    let imported = if options.images.is_empty() {
        Vec::new()
    } else {
        import_paths(&mut connection, &thread.workspace_id, &options.images)?
    };
    let mut content = imported
        .iter()
        .map(|attachment| json!({"type":"attachment","attachmentId":attachment.id}))
        .collect::<Vec<_>>();
    content.push(json!({"type":"text","text":prompt}));
    let runtime_overrides = runtime_overrides(
        options.model.as_deref(),
        options.permissions.as_deref(),
        output_schema,
    )?;
    let mut params = json!({"threadId":thread.thread_id,"content":content});
    if let Some(overrides) = runtime_overrides {
        params["runtimeOverrides"] = Value::Object(overrides);
    }
    let cancellation = Arc::new(AtomicBool::new(false));
    let signal = Arc::clone(&cancellation);
    ctrlc::set_handler(move || {
        signal.store(true, Ordering::Release);
    })
    .map_err(|_| CliError::configuration("无法安装 Ctrl+C 处理器"))?;
    if cancellation.load(Ordering::Acquire) {
        cleanup_unbound_attachments(&mut connection, &imported);
        return Ok(WaitOutcome::ClientError(cancelled_error("任务已取消")));
    }
    let accepted_result = connection.request_operation("turn/start", params);
    let accepted = match accepted_result {
        Ok(accepted) => accepted,
        Err(error) => {
            if !error.is_uncertain() {
                cleanup_unbound_attachments(&mut connection, &imported);
            }
            return Err(error);
        }
    };
    if accepted.get("accepted").and_then(Value::as_bool) != Some(true) {
        cleanup_unbound_attachments(&mut connection, &imported);
        return Err(CliError::protocol("Turn 未被服务端接纳"));
    }
    let turn_id = required_str(&accepted, "turnId")?.to_owned();
    identity.turn_id = Some(turn_id.clone());
    if connection.take_replaced() {
        connection.observe(&thread.thread_id)?;
        pump = connection.take_event_pump()?;
    }
    if options.json {
        write_record(
            output,
            accepted_record(&connection, &thread.thread_id, &turn_id),
        )?;
    }
    let result = wait_for_turn(
        &mut connection,
        &mut pump,
        TurnWaitContext {
            thread_id: &thread.thread_id,
            workspace_id: &thread.workspace_id,
            turn_id: &turn_id,
            json_output: options.json,
            output_last_message: options.output_last_message.as_deref(),
        },
        output,
        &cancellation,
    );
    let _ = connection.unobserve(&thread.thread_id);
    let _ = connection.disconnect();
    result
}

/// 服务端对图片所有权和绑定负责；失败时只丢弃仍处于 draft 状态的附件。
fn cleanup_unbound_attachments(connection: &mut Connection, attachments: &[ImportedAttachment]) {
    for attachment in attachments {
        let _ = connection.request("attachment/discard", json!({"attachmentId":attachment.id}));
    }
}

/// @author kongweiguang
/// 事件流提供实时 JSONL，快照校验权威状态；来源字段是区分继承展示与本会话控制的协议边界。
fn wait_for_turn(
    connection: &mut Connection,
    pump: &mut EventPump,
    context: TurnWaitContext<'_>,
    output: &mut impl Write,
    cancellation: &AtomicBool,
) -> Result<WaitOutcome, CliError> {
    let TurnWaitContext {
        thread_id,
        workspace_id,
        turn_id,
        json_output,
        output_last_message,
    } = context;
    let mut next_snapshot = Instant::now();
    let mut cancel_deadline = None;
    let mut seen_resync_notifications = ResyncNotificationDeduper::default();
    loop {
        if cancellation.swap(false, Ordering::AcqRel) && cancel_deadline.is_none() {
            connection.request("turn/cancel", json!({"turnId":turn_id}))?;
            cancel_deadline = Some(Instant::now() + CANCEL_WAIT);
        }
        if Instant::now() >= next_snapshot {
            let snapshot = connection.request(
                "thread/read",
                json!({"threadId":thread_id,"tail":true,"limit":200}),
            )?;
            super::interactive::validate_history_origins(&snapshot)?;
            if let Some(state) = turn_state(&snapshot, turn_id)? {
                if cancel_deadline.is_none()
                    && matches!(state, "waiting_approval" | "waiting_input" | "suspended")
                {
                    if json_output {
                        write_record(
                            output,
                            requires_input(
                                connection.server_instance_id(),
                                thread_id,
                                turn_id,
                                state,
                            ),
                        )?;
                    }
                    return Ok(WaitOutcome::NeedsInput(CliError::needs_input(thread_id)));
                }
                if matches!(state, "completed" | "failed" | "cancelled") {
                    return emit_snapshot_terminal(
                        connection,
                        output,
                        SnapshotTerminal {
                            thread_id,
                            snapshot: &snapshot,
                            turn_id,
                            state,
                            json_output,
                            output_last_message,
                        },
                    );
                }
            }
            let interaction =
                connection.request("interaction/read", json!({"threadId":thread_id}))?;
            if cancel_deadline.is_none()
                && interaction
                    .get("request")
                    .and_then(|request| request.get("status"))
                    .and_then(Value::as_str)
                    == Some("pending")
            {
                if json_output {
                    write_record(
                        output,
                        requires_input(
                            connection.server_instance_id(),
                            thread_id,
                            turn_id,
                            "clarification",
                        ),
                    )?;
                }
                return Ok(WaitOutcome::NeedsInput(CliError::needs_input(thread_id)));
            }
            next_snapshot = Instant::now() + SNAPSHOT_INTERVAL;
        }
        if cancel_deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            return Ok(WaitOutcome::ClientError(cancelled_error(&format!(
                "取消已请求；终态待确认，稍后可运行 ja resume {thread_id}"
            ))));
        }
        match pump.next_event(Duration::from_millis(100)) {
            Some(SessionEvent::Notification(frame)) => {
                let Some(method) = frame.method() else {
                    continue;
                };
                let Some(params) = frame.params() else {
                    continue;
                };
                if method == "turn/resync-required" {
                    let required = parse_resync_required(params).map_err(|reason| {
                        CliError::protocol(format!("无效的恢复通知：{reason}"))
                    })?;
                    if required.thread_id != thread_id || required.workspace_id != workspace_id {
                        continue;
                    }
                    if seen_resync_notifications.remember_once(&required.event_id) {
                        next_snapshot = Instant::now();
                    }
                } else if params.get("threadId").and_then(Value::as_str) != Some(thread_id) {
                    continue;
                }
                if json_output {
                    write_record(
                        output,
                        notification(connection.server_instance_id(), thread_id, method, params),
                    )?;
                }
                if method == "turn/terminal"
                    && params.get("turnId").and_then(Value::as_str) == Some(turn_id)
                {
                    return emit_event_terminal(
                        connection,
                        output,
                        thread_id,
                        turn_id,
                        params,
                        json_output,
                        output_last_message,
                    );
                }
            }
            Some(
                SessionEvent::Eof
                | SessionEvent::HandshakeFailed
                | SessionEvent::ProtocolFault(_)
                | SessionEvent::ProcessExited { .. }
                | SessionEvent::QueueFatalOverflow(_),
            ) => {
                return Err(CliError::protocol(format!(
                    "连接中断；会话 {thread_id} 的执行状态需重新读取，未自动重发"
                )));
            }
            _ => {}
        }
    }
}

/// 实时 terminal 使用服务端最终内容；JSONL 与纯文本成功输出都检查写入结果。
fn emit_event_terminal(
    connection: &Connection,
    output: &mut impl Write,
    thread_id: &str,
    turn_id: &str,
    params: &Value,
    json_output: bool,
    output_last_message: Option<&std::path::Path>,
) -> Result<WaitOutcome, CliError> {
    let state = required_str(params, "state")?;
    let final_text = params
        .get("finalMessage")
        .and_then(|message| message.get("text"))
        .and_then(Value::as_str);
    emit_terminal(
        connection,
        output,
        thread_id,
        turn_id,
        state,
        params.get("errorCode").and_then(Value::as_str),
        final_text,
        params.get("eventSeq"),
        params.get("streamSeq"),
        json_output,
        output_last_message,
    )
}

/// 快照恢复使用全文分页 API 核实预览并补齐回复，不把 preview 截断误报为完整终答。
fn emit_snapshot_terminal(
    connection: &mut Connection,
    output: &mut impl Write,
    terminal: SnapshotTerminal<'_>,
) -> Result<WaitOutcome, CliError> {
    let SnapshotTerminal {
        thread_id,
        snapshot,
        turn_id,
        state,
        json_output,
        output_last_message,
    } = terminal;
    let final_item = snapshot
        .get("items")
        .and_then(Value::as_array)
        .and_then(|items| {
            items.iter().rev().find(|item| {
                item.get("turnId").and_then(Value::as_str) == Some(turn_id)
                    && item.get("kind").and_then(Value::as_str) == Some("final_answer")
            })
        });
    let final_text = final_item
        .map(|item| read_final_item(connection, thread_id, item))
        .transpose()?;
    let turn = snapshot
        .get("turns")
        .and_then(Value::as_array)
        .and_then(|turns| {
            turns
                .iter()
                .find(|turn| turn.get("turnId").and_then(Value::as_str) == Some(turn_id))
        });
    let error_code = turn
        .and_then(|turn| turn.get("errorCode"))
        .and_then(Value::as_str);
    let event_seq = turn.and_then(|turn| turn.get("eventSeq"));
    let stream_seq = turn.and_then(|turn| turn.get("streamSeq"));
    emit_terminal(
        connection,
        output,
        thread_id,
        turn_id,
        state,
        error_code,
        final_text.as_deref(),
        event_seq,
        stream_seq,
        json_output,
        output_last_message,
    )
}

/// Snapshot 中只接受完整标识匹配的 final_answer；较短预览通过 Unicode 字符偏移分页读取。
fn read_final_item(
    connection: &mut Connection,
    thread_id: &str,
    item: &Value,
) -> Result<String, CliError> {
    let item_id = required_str(item, "itemId")?;
    let preview = item
        .get("text")
        .and_then(Value::as_str)
        .ok_or_else(|| CliError::protocol("终答历史缺少文本预览"))?;
    let total = item
        .get("totalCharacters")
        .and_then(Value::as_u64)
        .ok_or_else(|| CliError::protocol("终答历史缺少总字符数"))?;
    let truncated = item
        .get("truncated")
        .and_then(Value::as_bool)
        .ok_or_else(|| CliError::protocol("终答历史缺少截断状态"))?;
    if !truncated {
        if u64::try_from(preview.chars().count()).map_err(CliError::transport)? != total {
            return Err(CliError::protocol("终答历史字符数不一致"));
        }
        return Ok(preview.to_owned());
    }
    let mut offset = 0_u64;
    let mut text = String::new();
    loop {
        let page = connection.request(
            "thread/item-content/read",
            json!({
                "threadId":thread_id,
                "itemId":item_id,
                "offsetCharacters":offset,
                "limitCharacters":CONTENT_PAGE_CHARACTERS
            }),
        )?;
        if required_str(&page, "itemId")? != item_id
            || required_u64(&page, "offsetCharacters")? != offset
            || required_u64(&page, "totalCharacters")? != total
        {
            return Err(CliError::protocol("终答全文分页身份不一致"));
        }
        let part = page
            .get("content")
            .and_then(Value::as_str)
            .ok_or_else(|| CliError::protocol("终答全文分页缺少 content"))?;
        let next = offset
            .checked_add(u64::try_from(part.chars().count()).map_err(CliError::transport)?)
            .ok_or_else(|| CliError::protocol("终答全文偏移溢出"))?;
        let page_truncated = page
            .get("truncated")
            .and_then(Value::as_bool)
            .ok_or_else(|| CliError::protocol("终答全文分页缺少 truncated"))?;
        match (page_truncated, page.get("nextOffsetCharacters")) {
            (true, Some(value))
                if value.as_u64() == Some(next) && next > offset && next < total => {}
            (false, Some(Value::Null)) if next == total => {
                text.push_str(part);
                return Ok(text);
            }
            _ => return Err(CliError::protocol("终答全文分页状态不一致")),
        }
        text.push_str(part);
        offset = next;
    }
}

/// 两种终态来源共享固定 v2 对象及同一错误码映射，防止重连改变脚本结果形状。
#[allow(clippy::too_many_arguments)]
fn emit_terminal(
    connection: &Connection,
    output: &mut impl Write,
    thread_id: &str,
    turn_id: &str,
    state: &str,
    error_code: Option<&str>,
    final_text: Option<&str>,
    event_seq: Option<&Value>,
    stream_seq: Option<&Value>,
    json_output: bool,
    output_last_message: Option<&std::path::Path>,
) -> Result<WaitOutcome, CliError> {
    let outcome = match state {
        "completed" => WaitOutcome::Completed,
        "cancelled" => WaitOutcome::TerminalError(cancelled_error("任务已取消")),
        "failed" => WaitOutcome::TerminalError(failed_error(error_code)),
        _ => {
            return Err(CliError::protocol(
                "未知 Turn 终态；请读取同一会话核实状态，未重发任务",
            ));
        }
    };
    if state == "completed" && output_last_message.is_some() && final_text.is_none() {
        return Err(CliError::protocol(
            "Turn 已完成，但服务端没有返回可写入终答文件的正文",
        ));
    }
    if let (Some(path), Some(text)) = (output_last_message, final_text)
        && let Err(error) = write_last_message(path, text)
    {
        let command = export_answer_command(thread_id, turn_id, path);
        return Ok(WaitOutcome::OutputWriteFailure {
            error: error.with_recovery_command(&command),
            action: ErrorNextAction::ExportAnswer {
                thread_id: thread_id.to_owned(),
                turn_id: turn_id.to_owned(),
                output: path.to_path_buf(),
            },
        });
    }
    if json_output {
        write_record(
            output,
            terminal(TerminalRecord {
                server_instance_id: connection.server_instance_id(),
                thread_id,
                turn_id,
                state,
                error_code,
                final_text,
                event_seq,
                stream_seq,
            }),
        )?;
    } else if let Some(text) = final_text {
        write_text(output, text)?;
    }
    Ok(outcome)
}

/// Turn 状态只从 Java durable snapshot 读取；缺失表示状态尚未确认，继续保留观察。
fn turn_state<'a>(snapshot: &'a Value, turn_id: &str) -> Result<Option<&'a str>, CliError> {
    let turns = snapshot
        .get("turns")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("thread/read 缺少 turns"))?;
    Ok(turns
        .iter()
        .find(|turn| turn.get("turnId").and_then(Value::as_str) == Some(turn_id))
        .and_then(|turn| turn.get("status"))
        .and_then(Value::as_str))
}

/// 正常回复按服务端状态文本写入 stdout，断管道明确返回失败而不重发或取消后台任务。
fn write_text(output: &mut impl Write, text: &str) -> Result<(), CliError> {
    output
        .write_all(text.as_bytes())
        .and_then(|_| output.write_all(b"\n"))
        .and_then(|_| output.flush())
        .map_err(|error| CliError::transport(format!("终答 stdout 写入失败：{error}")))
}

/// 创建输入取消后的稳定退出结果；远端尚未确认时保留可继续检查的 Thread 身份。
fn cancelled_error(message: &str) -> CliError {
    CliError {
        exit_code: 130,
        message: message.to_owned(),
        rpc_code: None,
        client_error_code: None,
        uncertain: false,
        read_retryable: false,
    }
}

/// 失败信息只显示已持久化的错误码，不合成 UNKNOWN 或 Provider 私有异常正文。
fn failed_error(error_code: Option<&str>) -> CliError {
    CliError {
        exit_code: 1,
        message: error_code.map_or_else(|| "任务失败".into(), |code| format!("任务失败：{code}")),
        rpc_code: None,
        client_error_code: None,
        uncertain: false,
        read_retryable: false,
    }
}

/// 以固定字段构造首帧，避免 accepted 格式在实时输出和测试桩间漂移。
fn accepted_record(connection: &Connection, thread_id: &str, turn_id: &str) -> Value {
    accepted(connection.server_instance_id(), thread_id, turn_id)
}

/// 只接受服务端返回的整数序号，防止浮点数或负值进入终态投影。
fn required_u64(value: &Value, field: &str) -> Result<u64, CliError> {
    value
        .get(field)
        .and_then(Value::as_u64)
        .ok_or_else(|| CliError::protocol(format!("协议字段 {field} 缺失或无效")))
}
