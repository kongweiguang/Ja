// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 非交互运行只提交一次 Turn；断线和 ACK 不确定时均不自动重发有副作用请求。

use super::{CliError, canonical_workspace, create_thread, required_str, rpc::Connection};
use ja_runtime::app_server_process::{EventPump, SessionEvent};
use serde_json::{Value, json};
use std::io::{self, Read, Write};
use std::path::PathBuf;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};

const SNAPSHOT_INTERVAL: Duration = Duration::from_secs(1);
const CANCEL_WAIT: Duration = Duration::from_secs(10);

/// stdin 的 `-` 保持原始多行任务，但拒绝空正文及协议超过上限的输入。
pub fn run(cwd: Option<PathBuf>, prompt: String, json_output: bool) -> Result<(), CliError> {
    let prompt = if prompt == "-" {
        let mut text = String::new();
        io::stdin()
            .take(4_000_001)
            .read_to_string(&mut text)
            .map_err(CliError::transport)?;
        text
    } else {
        prompt
    };
    if prompt.trim().is_empty() || prompt.len() > 4_000_000 || prompt.contains('\0') {
        return Err(CliError::usage("任务正文必须为 1–4,000,000 字符且不含 NUL"));
    }
    let cwd = canonical_workspace(cwd)?;
    let mut connection = Connection::connect_or_start()?;
    let thread = create_thread(&mut connection, &cwd, false)?;
    connection.observe(&thread.thread_id)?;
    let mut pump = connection.take_event_pump()?;
    let cancellation = Arc::new(AtomicBool::new(false));
    let signal = Arc::clone(&cancellation);
    ctrlc::set_handler(move || {
        signal.store(true, Ordering::Release);
    })
    .map_err(|_| CliError::configuration("无法安装 Ctrl+C 处理器"))?;
    if cancellation.load(Ordering::Acquire) {
        return Err(CliError {
            exit_code: 130,
            message: "任务已取消".into(),
            rpc_code: None,
            uncertain: false,
        });
    }
    let accepted = connection
        .request_operation(
            "turn/start",
            json!({
                "threadId":thread.thread_id,
                "content":[{"type":"text","text":prompt}]
            }),
        )
        .map_err(|error| CliError {
            exit_code: error.exit_code(),
            message: format!(
                "会话 {} {}。{}",
                thread.thread_id,
                if error.is_uncertain() || (error.exit_code() == 4 && error.rpc_code.is_none()) {
                    "的提交结果待核实；未自动重发"
                } else {
                    "提交失败"
                },
                error.message()
            ),
            rpc_code: error.rpc_code,
            uncertain: error.uncertain,
        })?;
    if accepted.get("accepted").and_then(Value::as_bool) != Some(true) {
        return Err(CliError::protocol("Turn 未被服务端接纳"));
    }
    let turn_id = required_str(&accepted, "turnId")?.to_owned();
    if connection.take_replaced() {
        connection.observe(&thread.thread_id)?;
        pump = connection.take_event_pump()?;
    }
    let mut output = io::stdout().lock();
    if json_output
        && !write_json(
            &mut output,
            json!({
                "version":1,"type":"accepted","serverInstanceId":connection.server_instance_id(),
                "threadId":thread.thread_id,"turnId":turn_id
            }),
        )?
    {
        connection.disconnect()?;
        return Ok(());
    }
    let result = wait_for_turn(
        &mut connection,
        &mut pump,
        &thread.thread_id,
        &turn_id,
        json_output,
        &mut output,
        &cancellation,
    );
    let _ = connection.unobserve(&thread.thread_id);
    let _ = connection.disconnect();
    result
}

/// 事件流给用户实时 JSONL，权威快照周期校验终态和缺失事件；两者只按 turnId 收敛。
fn wait_for_turn(
    connection: &mut Connection,
    pump: &mut EventPump,
    thread_id: &str,
    turn_id: &str,
    json_output: bool,
    output: &mut impl Write,
    cancellation: &AtomicBool,
) -> Result<(), CliError> {
    let mut next_snapshot = Instant::now();
    let mut cancel_deadline = None;
    loop {
        if cancellation.swap(false, Ordering::AcqRel) && cancel_deadline.is_none() {
            // Cancel ACK 只表示接纳，真正退出仍等待 terminal 或有限时长的权威读回。
            connection.request("turn/cancel", json!({"turnId":turn_id}))?;
            cancel_deadline = Some(Instant::now() + CANCEL_WAIT);
        }
        if Instant::now() >= next_snapshot {
            let snapshot = connection.request(
                "thread/read",
                json!({"threadId":thread_id,"tail":true,"limit":200}),
            )?;
            if let Some(state) = turn_state(&snapshot, turn_id)? {
                if cancel_deadline.is_none()
                    && (state == "waiting_approval" || state == "suspended")
                {
                    if json_output {
                        let _ = write_json(
                            output,
                            json!({"version":1,"type":"requires_input",
                            "threadId":thread_id,"turnId":turn_id,"state":state}),
                        )?;
                    }
                    return Err(CliError::needs_input(thread_id));
                }
                if matches!(state, "completed" | "failed" | "cancelled") {
                    return emit_snapshot_terminal(output, &snapshot, turn_id, state, json_output);
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
                    let _ = write_json(
                        output,
                        json!({"version":1,"type":"requires_input",
                        "threadId":thread_id,"turnId":turn_id,"state":"clarification"}),
                    )?;
                }
                return Err(CliError::needs_input(thread_id));
            }
            next_snapshot = Instant::now() + SNAPSHOT_INTERVAL;
        }
        if let Some(deadline) = cancel_deadline
            && Instant::now() >= deadline
        {
            return Err(CliError {
                exit_code: 130,
                message: format!("取消已请求；终态待确认，稍后可运行 ja resume {thread_id}"),
                rpc_code: None,
                uncertain: false,
            });
        }
        match pump.next_event(Duration::from_millis(100)) {
            Some(SessionEvent::Notification(frame)) => {
                let Some(method) = frame.method() else {
                    continue;
                };
                let Some(params) = frame.params() else {
                    continue;
                };
                if params.get("threadId").and_then(Value::as_str) != Some(thread_id) {
                    continue;
                }
                if json_output
                    && !write_json(
                        output,
                        json!({
                            "version":1,"type":"notification","method":method,"params":params
                        }),
                    )?
                {
                    // 管道关闭只释放客户端，后台 Turn 继续由 Java owner 管理。
                    return Ok(());
                }
                if method == "turn/terminal"
                    && params.get("turnId").and_then(Value::as_str) == Some(turn_id)
                {
                    return emit_event_terminal(output, params, json_output);
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

/// 已收到完整终态事件时直接使用服务端最终消息，避免仅靠 stream delta 误截断答案。
fn emit_event_terminal(
    output: &mut impl Write,
    params: &Value,
    json_output: bool,
) -> Result<(), CliError> {
    let state = required_str(params, "state")?;
    if !json_output
        && let Some(text) = params
            .get("finalMessage")
            .and_then(|value| value.get("text"))
            .and_then(Value::as_str)
        && !write_text(output, text)?
    {
        return Ok(());
    }
    terminal_exit(state, params.get("errorCode").and_then(Value::as_str))
}

/// 重连或通知遗漏时由 Thread 历史取回同一 Turn 的最终答案与状态。
fn emit_snapshot_terminal(
    output: &mut impl Write,
    snapshot: &Value,
    turn_id: &str,
    state: &str,
    json_output: bool,
) -> Result<(), CliError> {
    let text = snapshot
        .get("items")
        .and_then(Value::as_array)
        .and_then(|items| {
            items.iter().rev().find(|item| {
                item.get("turnId").and_then(Value::as_str) == Some(turn_id)
                    && item.get("kind").and_then(Value::as_str) == Some("final_answer")
            })
        })
        .and_then(|item| item.get("text"))
        .and_then(Value::as_str);
    if json_output {
        if !write_json(
            output,
            json!({"version":1,"type":"recovered_terminal","turnId":turn_id,"state":state,"finalText":text}),
        )? {
            return Ok(());
        }
    } else if let Some(text) = text
        && !write_text(output, text)?
    {
        return Ok(());
    }
    let error_code = snapshot
        .get("turns")
        .and_then(Value::as_array)
        .and_then(|turns| {
            turns
                .iter()
                .find(|turn| turn.get("turnId").and_then(Value::as_str) == Some(turn_id))
        })
        .and_then(|turn| turn.get("errorCode"))
        .and_then(Value::as_str);
    terminal_exit(state, error_code)
}

/// Turn 状态只从 Java 的 durable snapshot 读取；缺失说明分页或状态未提交，应继续等待。
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

/// Exit code 与用户可见终态一一对应，失败不会被非零之外的日志吞掉。
fn terminal_exit(state: &str, error_code: Option<&str>) -> Result<(), CliError> {
    match state {
        "completed" => Ok(()),
        "cancelled" => Err(CliError {
            exit_code: 130,
            message: "任务已取消".into(),
            rpc_code: None,
            uncertain: false,
        }),
        "failed" => Err(CliError {
            exit_code: 1,
            message: format!("任务失败：{}", error_code.unwrap_or("UNKNOWN")),
            rpc_code: None,
            uncertain: false,
        }),
        _ => Err(CliError::protocol("未知 Turn 终态")),
    }
}

/// 写 stdout 时识别管道关闭，避免终端下游退出时误取消后台任务。
fn write_text(output: &mut impl Write, text: &str) -> Result<bool, CliError> {
    match output
        .write_all(text.as_bytes())
        .and_then(|_| output.write_all(b"\n"))
        .and_then(|_| output.flush())
    {
        Ok(()) => Ok(true),
        Err(error) if error.kind() == io::ErrorKind::BrokenPipe => Ok(false),
        Err(error) => Err(CliError::transport(error)),
    }
}

/// JSONL 逐帧落盘且有稳定 version，调用方可按 threadId/turnId 恢复后续处理。
fn write_json(output: &mut impl Write, value: Value) -> Result<bool, CliError> {
    let mut bytes = serde_json::to_vec(&value).map_err(CliError::transport)?;
    bytes.push(b'\n');
    match output.write_all(&bytes).and_then(|_| output.flush()) {
        Ok(()) => Ok(true),
        Err(error) if error.kind() == io::ErrorKind::BrokenPipe => Ok(false),
        Err(error) => Err(CliError::transport(error)),
    }
}
