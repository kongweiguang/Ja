// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! CLI 与 TUI 共用同一份有界 Git 快照和 Java Review 生命周期。

use super::{
    CliError, ThreadContext, args::ReviewSource, attachment, canonical_workspace,
    create_exec_thread, open_workspace, required_str, required_u64, rpc::Connection,
};
#[path = "review_report.rs"]
mod report;
use ja_review::{
    ReviewSnapshot, ReviewSnapshotSource, SnapshotCancellation, SnapshotCollectionError,
    create_snapshot, resolve_commit_id,
};
use serde_json::{Value, json};
use std::io::{self, Write};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

const COLLECTION_DEADLINE: Duration = Duration::from_secs(120);
const REVIEW_POLL_INTERVAL: Duration = Duration::from_millis(750);

/// 持久审查身份必须经 review/read 回读，不能从 CLI 的进度文字推断。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct ReviewRun {
    pub(super) thread_id: String,
    pub(super) turn_id: String,
    pub(super) thread_revision: u64,
}

/// 完成前允许没有报告；完成状态必须携带冻结 artifact。
#[derive(Debug, Clone)]
pub(super) struct ReviewRead {
    pub(super) status: String,
    pub(super) report: Option<Value>,
}

/// 独立命令创建普通 Thread，并与 TUI 共用快照和 RPC helper。
pub(super) fn run(source: ReviewSource) -> Result<(), CliError> {
    let root = canonical_workspace(None)?;
    let mut connection = Connection::connect_or_start()?;
    let result = run_connected(&mut connection, &root, source);
    let disconnected = connection.disconnect();
    result.and(disconnected)
}

/// 统一释放本地连接，使成功和失败路径采用相同的收口方式。
fn run_connected(
    connection: &mut Connection,
    root: &Path,
    source: ReviewSource,
) -> Result<(), CliError> {
    let workspace = open_workspace(connection, root)?;
    if workspace.get("trust").and_then(Value::as_str) == Some("untrusted") {
        return Err(CliError::needs_trust());
    }
    let workspace_id = required_str(&workspace, "workspaceId")?.to_owned();
    let cancellation = Arc::new(AtomicBool::new(false));
    let signal = Arc::clone(&cancellation);
    ctrlc::set_handler(move || {
        signal.store(true, Ordering::Release);
    })
    .map_err(|_| CliError::configuration("无法安装 Ctrl+C 处理器"))?;

    let snapshot = collect_snapshot(
        root,
        &workspace_id,
        &source,
        cancellation.as_ref(),
        Instant::now() + COLLECTION_DEADLINE,
    )?;
    if cancellation.load(Ordering::Acquire) {
        return Err(cancelled_error("代码审查已取消"));
    }
    let thread_context = create_exec_thread(connection, root, None, "代码审查")?;
    if cancellation.load(Ordering::Acquire) {
        return Err(cancelled_error("代码审查已取消"));
    }
    if thread_context.workspace_id != workspace_id {
        return Err(CliError::protocol(
            "Review Thread 的 Workspace identity 与快照不一致",
        ));
    }
    let attachment_id = import_snapshot(connection, &thread_context, &snapshot)?;
    if cancellation.load(Ordering::Acquire) {
        discard_attachment(connection, &attachment_id);
        return Err(cancelled_error("代码审查已取消"));
    }
    let run = match start_review(
        connection,
        &thread_context.thread_id,
        thread_context.revision,
        &attachment_id,
    ) {
        Ok(run) => run,
        Err(error) if !error.is_uncertain() => {
            discard_attachment(connection, &attachment_id);
            return Err(error);
        }
        Err(error) => return Err(error),
    };
    println!("正在审查 · 会话 {}", run.thread_id);
    wait_for_review(connection, &run, &snapshot, cancellation.as_ref())
}

/// Git 发现与物化由 TUI worker 调用；helper 只执行有界同步 IO。
pub(super) fn collect_snapshot(
    root: &Path,
    workspace_id: &str,
    source: &ReviewSource,
    cancellation: &impl SnapshotCancellation,
    deadline: Instant,
) -> Result<ReviewSnapshot, CliError> {
    let source = match source {
        ReviewSource::Uncommitted => ReviewSnapshotSource::Uncommitted,
        ReviewSource::Base(selector) => ReviewSnapshotSource::Base {
            base_commit_id: resolve_commit_id(root, selector, cancellation, deadline)
                .map_err(collection_error)?,
        },
        ReviewSource::Commit(selector) => ReviewSnapshotSource::Commit {
            commit_id: resolve_commit_id(root, selector, cancellation, deadline)
                .map_err(collection_error)?,
        },
    };
    create_snapshot(root, workspace_id, source, cancellation, deadline).map_err(collection_error)
}

/// 先校验快照摘要，再通过现有私有 ingress 导入规范化字节。
pub(super) fn import_snapshot(
    connection: &mut Connection,
    thread: &ThreadContext,
    snapshot: &ReviewSnapshot,
) -> Result<String, CliError> {
    if snapshot.workspace_id != thread.workspace_id || !snapshot.verify_hash().unwrap_or(false) {
        return Err(CliError::protocol("Review 快照身份或摘要校验失败"));
    }
    let bytes = snapshot
        .canonical_json()
        .map_err(|_| CliError::protocol("Review 快照无法序列化"))?;
    let attachment = attachment::import_bytes(
        connection,
        &thread.workspace_id,
        "review-snapshot.json",
        &bytes,
    )?;
    Ok(attachment.id)
}

/// 操作账本核实 ACK 丢失，避免重复触发 Review 副作用。
pub(super) fn start_review(
    connection: &mut Connection,
    thread_id: &str,
    expected_thread_revision: u64,
    snapshot_attachment_id: &str,
) -> Result<ReviewRun, CliError> {
    let result = connection.request_operation(
        "review/start",
        json!({
            "threadId":thread_id,
            "expectedThreadRevision":expected_thread_revision,
            "snapshotAttachmentId":snapshot_attachment_id
        }),
    )?;
    if required_str(&result, "threadId")? != thread_id
        || result.get("queued").and_then(Value::as_bool).is_none()
    {
        return Err(CliError::protocol("Review/start 回执与请求不符"));
    }
    let run = ReviewRun {
        thread_id: thread_id.to_owned(),
        turn_id: required_str(&result, "turnId")?.to_owned(),
        thread_revision: required_u64(&result, "threadRevision")?,
    };
    if run.thread_revision <= expected_thread_revision {
        return Err(CliError::protocol("Review/start 未推进 Thread revision"));
    }
    Ok(run)
}

/// 回读持久 Review 时核对 Thread 和 Turn，防止报告串到其他会话。
pub(super) fn read_review(
    connection: &mut Connection,
    thread_id: &str,
    turn_id: &str,
) -> Result<ReviewRead, CliError> {
    let result = connection.request(
        "review/read",
        json!({"threadId":thread_id,"turnId":turn_id}),
    )?;
    if required_str(&result, "threadId")? != thread_id
        || required_str(&result, "turnId")? != turn_id
    {
        return Err(CliError::protocol("Review/read 身份与请求不符"));
    }
    let status = required_str(&result, "status")?.to_owned();
    if !matches!(
        status.as_str(),
        "queued"
            | "running"
            | "waiting_approval"
            | "waiting_input"
            | "suspended"
            | "completed"
            | "failed"
            | "cancelled"
    ) {
        return Err(CliError::protocol("Review/read 返回未知状态"));
    }
    let report = result
        .get("report")
        .ok_or_else(|| CliError::protocol("Review/read 缺少 report"))?;
    let report = if report.is_null() {
        None
    } else if report.is_object() {
        Some(report.clone())
    } else {
        return Err(CliError::protocol("Review/read 的 report 类型无效"));
    };
    if status == "completed" && report.is_none() {
        return Err(CliError::protocol("已完成的 Review 缺少冻结报告"));
    }
    Ok(ReviewRead { status, report })
}

/// 取消只提交一次，后续回读决定服务端是否已经到达终态。
fn wait_for_review(
    connection: &mut Connection,
    run: &ReviewRun,
    snapshot: &ReviewSnapshot,
    cancellation: &AtomicBool,
) -> Result<(), CliError> {
    let mut cancel_requested = false;
    loop {
        if cancellation.swap(false, Ordering::AcqRel) && !cancel_requested {
            let cancelled = connection.request("turn/cancel", json!({"turnId":run.turn_id}));
            let cancelled = match cancelled {
                Ok(result) => {
                    let status = result.get("status").and_then(Value::as_str);
                    let revision = result.get("threadRevision").and_then(Value::as_u64);
                    if result.get("accepted").and_then(Value::as_bool) == Some(true)
                        && result.get("turnId").and_then(Value::as_str)
                            == Some(run.turn_id.as_str())
                        && status.is_some_and(|status| {
                            matches!(
                                status,
                                "queued"
                                    | "running"
                                    | "waiting_approval"
                                    | "waiting_input"
                                    | "suspended"
                                    | "completed"
                                    | "failed"
                                    | "cancelled"
                            )
                        })
                        && revision.is_some_and(|revision| revision > 0)
                    {
                        Ok(())
                    } else {
                        Err(CliError::protocol("turn/cancel 回执与请求不符"))
                    }
                }
                Err(error) => Err(error),
            };
            if let Err(error) = cancelled {
                return Err(CliError::protocol(format!(
                    "取消回执待核实；可用 ja resume {} 检查审查状态：{}",
                    run.thread_id,
                    error.message()
                )));
            }
            cancel_requested = true;
        }
        let current = match read_review(connection, &run.thread_id, &run.turn_id) {
            Ok(current) => current,
            Err(error) if error.read_retryable => {
                thread::sleep(REVIEW_POLL_INTERVAL);
                continue;
            }
            Err(error) => return Err(error),
        };
        match current.status.as_str() {
            "completed" => {
                let report = current
                    .report
                    .as_ref()
                    .ok_or_else(|| CliError::protocol("已完成的 Review 缺少报告"))?;
                return print_report(report, snapshot);
            }
            "failed" => return Err(CliError::review_failed("代码审查未完成")),
            "cancelled" => return Err(cancelled_error("代码审查已取消")),
            "waiting_approval" | "waiting_input" | "suspended" => {
                return Err(CliError::needs_input(&run.thread_id));
            }
            "queued" | "running" => thread::sleep(REVIEW_POLL_INTERVAL),
            _ => return Err(CliError::protocol("Review/read 返回未知状态")),
        }
    }
}

/// 只有快照身份及发现位置与本次输入一致时才展示报告。
fn print_report(report: &Value, snapshot: &ReviewSnapshot) -> Result<(), CliError> {
    let rendered = render_report(report, snapshot)?;
    let stdout = io::stdout();
    let mut output = stdout.lock();
    output
        .write_all(rendered.as_bytes())
        .and_then(|_| output.write_all(b"\n"))
        .and_then(|_| output.flush())
        .map_err(CliError::transport)
}

/// 统一校验并格式化冻结报告，让 TUI 与 headless 共用同一语义。
pub(super) fn render_report(report: &Value, snapshot: &ReviewSnapshot) -> Result<String, CliError> {
    report::render_report(report, snapshot)
        .map_err(|message| CliError::protocol(message.to_owned()))
}

/// 只有确认 review/start 未提交时才丢弃仍处于 draft 状态的附件。
fn discard_attachment(connection: &mut Connection, attachment_id: &str) {
    let _ = connection.request("attachment/discard", json!({"attachmentId":attachment_id}));
}

/// collector 的稳定错误分类映射为 CLI 诊断，不转发 Git stderr 或仓库敏感信息。
fn collection_error(error: SnapshotCollectionError) -> CliError {
    match error {
        SnapshotCollectionError::Cancelled => cancelled_error("代码审查已取消"),
        SnapshotCollectionError::InvalidRepository => {
            CliError::usage("当前目录不是可审查的 Git 工作区")
        }
        SnapshotCollectionError::InvalidSelector => CliError::usage("Git 审查来源无效或无法解析"),
        SnapshotCollectionError::GitUnavailable => {
            CliError::configuration("找不到 Git；请确认 Git 已安装并位于 PATH 中")
        }
        SnapshotCollectionError::DeadlineExceeded => {
            CliError::review_failed("读取 Git 审查快照超时")
        }
        SnapshotCollectionError::StaleSnapshot => {
            CliError::review_failed("审查期间工作区发生变化，请重新运行 ja review")
        }
        SnapshotCollectionError::GitCommandFailed
        | SnapshotCollectionError::OutputLimitExceeded
        | SnapshotCollectionError::InvalidSnapshot => {
            CliError::review_failed("无法生成完整有效的 Git 审查快照")
        }
    }
}

/// 本地取消使用与 exec Turn 相同的进程退出码。
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
