// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[path = "../support/headless_test_support.rs"]
mod support;

use std::fs;
use std::io::{self, Write};
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

/// 路径同时包含进程和时间标识，避免并行集成测试共享输出文件。
fn unique_path(label: &str) -> PathBuf {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_nanos();
    std::env::temp_dir().join(format!("ja-cli-{label}-{}-{stamp}", std::process::id()))
}

/// 除字节解析外，验证从文件加载 Schema 的实际路径。
#[test]
fn schema_file_is_read_as_a_bounded_json_object() {
    let path = unique_path("schema").with_extension("json");
    fs::write(&path, br#"{"type":"object","required":["ok"]}"#).expect("write schema");

    let schema = support::headless::read_output_schema(&path).expect("read schema");

    assert_eq!(schema["type"], "object");
    assert_eq!(schema["required"][0], "ok");
    fs::remove_file(path).expect("remove schema");
}

/// 终答导出必须将完整文本 flush 到指定本地文件。
#[test]
fn last_message_file_contains_the_complete_reply() {
    let path = unique_path("last-message").with_extension("md");

    support::headless::write_last_message(&path, "第一行\nsecond line\n")
        .expect("write final answer");

    assert_eq!(
        fs::read_to_string(&path).expect("read final answer"),
        "第一行\nsecond line\n"
    );
    fs::remove_file(path).expect("remove final answer");
}

/// 重复导出通过原子替换更新完整文件，不在已有回答上暴露截断的中间状态。
#[test]
fn last_message_file_atomically_replaces_an_existing_reply() {
    let path = unique_path("last-message-replace").with_extension("md");
    fs::write(&path, "previous complete reply").expect("write previous reply");

    support::headless::write_last_message(&path, "new complete reply\n")
        .expect("replace previous answer");

    assert_eq!(
        fs::read_to_string(&path).expect("read replaced reply"),
        "new complete reply\n"
    );
    fs::remove_file(path).expect("remove replaced reply");
}

/// Windows 句柄拒绝 delete sharing 时原子替换必须失败，且之前交付的终答字节保持完整。
#[cfg(windows)]
#[test]
fn failed_replacement_preserves_an_existing_reply() {
    use std::fs::OpenOptions;
    use std::os::windows::fs::OpenOptionsExt;

    const FILE_SHARE_READ: u32 = 0x1;

    let path = unique_path("last-message-locked").with_extension("md");
    fs::write(&path, "previous complete reply").expect("write previous reply");
    let lock = OpenOptions::new()
        .read(true)
        .share_mode(FILE_SHARE_READ)
        .open(&path)
        .expect("open reply without delete sharing");

    let result = support::headless::write_last_message(&path, "new reply");

    assert!(
        result.is_err(),
        "replacement must fail while delete sharing is denied"
    );
    assert_eq!(
        fs::read_to_string(&path).expect("read original reply"),
        "previous complete reply"
    );
    drop(lock);
    fs::remove_file(path).expect("remove original reply");
}

/// 目标无法作为文件打开时必须报告失败，不能误报导出成功。
#[test]
fn last_message_file_reports_open_failure() {
    let directory = unique_path("directory");
    fs::create_dir(&directory).expect("create directory fixture");

    let result = support::headless::write_last_message(&directory, "reply");

    assert!(result.is_err());
    let error = result.expect_err("directory cannot receive a final answer");
    assert_eq!(error.category, "local_output");
    assert_eq!(error.client_error_code, Some("CLIENT_OUTPUT_FAILURE"));
    fs::remove_dir(directory).expect("remove directory fixture");
}

struct ClosedPipe;

impl Write for ClosedPipe {
    /// 模拟下游在 JSONL 记录 flush 前关闭 stdout。
    fn write(&mut self, _buffer: &[u8]) -> io::Result<usize> {
        Err(io::Error::new(io::ErrorKind::BrokenPipe, "closed"))
    }

    /// 即使单独调用 flush，也保留断管道错误。
    fn flush(&mut self) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::BrokenPipe, "closed"))
    }
}

/// stdout 断管道必须成为命令错误，避免自动化把丢失输出误认为成功。
#[test]
fn jsonl_record_write_reports_a_closed_pipe() {
    let result =
        support::headless::write_record(&mut ClosedPipe, serde_json::json!({"type":"terminal"}));

    assert!(result.is_err());
}
