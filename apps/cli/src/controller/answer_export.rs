// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 按明确 Thread/Turn 只读完整终答，不创建会话或提交新 Turn。

use super::rpc::Connection;
use super::{CliError, headless, interactive, required_str, required_u64};
use serde_json::{Value, json};
use std::collections::HashSet;
use std::path::Path;
use std::time::{Duration, Instant};

const THREAD_PAGE_SIZE: u64 = 200;
const MAX_THREAD_PAGES: usize = 500;
const MAX_ANSWER_BYTES: usize = 64 * 1024 * 1024;
const READ_DEADLINE: Duration = Duration::from_secs(60);

/// 只连接现有 App Server，按精确身份读取回答并原子发布到本地文件。
pub(super) fn run(thread_id: &str, turn_id: &str, output: &Path) -> Result<(), CliError> {
    let mut connection = Connection::connect_existing()?;
    let answer = read_final_answer(&mut connection, thread_id, turn_id)?;
    let recovery = headless::export_answer_command(thread_id, turn_id, output);
    headless::write_last_message(output, &answer)
        .map_err(|error| error.with_recovery_command(&recovery))?;
    let _ = connection.disconnect();
    println!(
        "已导出会话 {thread_id} 的 Turn {turn_id} 到 {}。",
        output.display()
    );
    Ok(())
}

/// @author kongweiguang
/// 一个 deadline 覆盖 Thread 与正文分页；每页固定 revision 且要求明示来源，拒绝混合或猜测历史。
fn read_final_answer(
    connection: &mut Connection,
    thread_id: &str,
    turn_id: &str,
) -> Result<String, CliError> {
    let deadline = Instant::now() + READ_DEADLINE;
    let mut cursor: Option<String> = None;
    let mut seen_cursors = HashSet::new();
    let mut seen_items = HashSet::new();
    let mut revision = None;
    let mut answer = None;
    let mut finished = false;

    for _ in 0..MAX_THREAD_PAGES {
        let mut params = json!({
            "threadId":thread_id,
            "tail":true,
            "limit":THREAD_PAGE_SIZE
        });
        if let Some(value) = &cursor {
            if !seen_cursors.insert(value.clone()) {
                return Err(CliError::protocol("终答读取游标重复"));
            }
            params["cursor"] = json!(value);
        }
        let page = connection.request_with_timeout("thread/read", params, remaining(deadline)?)?;
        super::interactive::validate_history_origins(&page)?;
        if required_str(&page, "threadId")? != thread_id {
            return Err(CliError::protocol("终答读取 Thread 身份不一致"));
        }
        let page_revision = required_u64(&page, "revision")?;
        if revision.is_some_and(|expected| expected != page_revision) {
            return Err(CliError::usage(
                "读取期间会话发生变化；未写入文件，请重新导出",
            ));
        }
        revision = Some(page_revision);
        let items = page
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("Thread 页面缺少 items"))?;
        for item in items {
            let item_id = required_str(item, "itemId")?;
            if !seen_items.insert(item_id.to_owned()) {
                return Err(CliError::protocol("终答读取包含重复历史项"));
            }
        }
        answer = headless::latest_final_answer_item(items, turn_id).cloned();
        cursor = match page.get("nextCursor") {
            Some(Value::Null) | None => None,
            Some(Value::String(value)) if !value.is_empty() => Some(value.clone()),
            _ => return Err(CliError::protocol("Thread 页面游标格式无效")),
        };
        if answer.is_some() || cursor.is_none() {
            finished = true;
            break;
        }
    }
    if !finished {
        return Err(CliError::usage(format!(
            "会话历史超过 {} 页读取上限（每页最多 {} 项）；未写入文件，请在桌面端打开目标 Turn 查看终答",
            MAX_THREAD_PAGES, THREAD_PAGE_SIZE
        )));
    }
    let item = answer.ok_or_else(|| CliError::usage("指定 Turn 没有可导出的终答"))?;
    let item_id = required_str(&item, "itemId")?;
    match item.get("truncated").and_then(Value::as_bool) {
        Some(true) => {
            let total_characters = required_u64(&item, "totalCharacters")?;
            interactive::read_item_content_pages_until(
                thread_id,
                item_id,
                total_characters,
                Some(MAX_ANSWER_BYTES),
                deadline,
                |params, timeout| {
                    connection.request_with_timeout("thread/item-content/read", params, timeout)
                },
            )
            .map_err(|error| {
                if error.message().contains("终端详情容量") {
                    CliError::usage(
                        "终答超过 CLI 的 64 MiB 单文件导出上限；未写入文件，请在桌面端打开目标 Turn 查看终答",
                    )
                } else {
                    error
                }
            })
        }
        Some(false) => {
            let text = item
                .get("text")
                .and_then(Value::as_str)
                .ok_or_else(|| CliError::protocol("终答历史项缺少正文"))?;
            if text.len() > MAX_ANSWER_BYTES {
                return Err(CliError::usage(
                    "终答超过 CLI 的 64 MiB 单文件导出上限；未写入文件，请在桌面端打开目标 Turn 查看终答",
                ));
            }
            Ok(text.to_owned())
        }
        None => Err(CliError::protocol("终答历史项缺少截断状态")),
    }
}

/// 分页网络请求共用一个整体截止时间，不能因不断返回游标而无限等待。
fn remaining(deadline: Instant) -> Result<Duration, CliError> {
    let remaining = deadline.saturating_duration_since(Instant::now());
    if remaining.is_zero() {
        return Err(CliError::transport("终答分页读取超过当前操作时限"));
    }
    Ok(remaining)
}
