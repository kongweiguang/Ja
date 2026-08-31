// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// Stderr 脱敏、预算与持续 drain 测试。

use super::*;
use crate::app_server_process::protocol::Limits;
use crate::unit_support_tests::{close_session, pipe_pair, session_from_io};
use std::io::Write;
use std::time::Duration;

#[test]
fn stderr_budget_emits_one_truncation_and_keeps_draining() {
    let (server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let (stderr_reader, mut stderr_writer) = pipe_pair();
    let (host_to_server_reader, host_to_server_writer) = pipe_pair();
    let limits = Limits {
        max_log_bytes: 4_096,
        max_stderr_line_bytes: 1_024,
        ..Limits::default()
    };
    let session = session_from_io(
        server_to_host_reader,
        host_to_server_writer,
        stderr_reader,
        12,
        limits,
    )
    .unwrap();
    let mut event_pump = session.take_event_pump().unwrap();
    stderr_writer
        .write_all(b"api_key=sk-test-secret path=C:\\Users\\private\\project prompt=private source=secret.rs\n")
        .unwrap();
    stderr_writer.write_all(&vec![b'x'; 8_192]).unwrap();
    stderr_writer.write_all(b"\nsecond line\n").unwrap();
    drop(stderr_writer);
    drop(host_to_server_reader);
    let first = event_pump
        .next_event(Duration::from_secs(1))
        .expect("redacted stderr line");
    let first_debug = format!("{first:?}");
    match first {
        SessionEvent::StderrLine(line) => {
            assert_eq!(line, "sidecar stderr output redacted");
        }
        other => panic!("expected redacted stderr line, got {other:?}"),
    }
    for secret in [
        "sk-test-secret",
        "C:\\Users\\private\\project",
        "private",
        "secret.rs",
    ] {
        assert!(!first_debug.contains(secret));
    }
    assert!(matches!(
        event_pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::StderrTruncated)
    ));
    assert!(!matches!(
        event_pump.next_event(Duration::from_millis(20)),
        Some(SessionEvent::StderrTruncated)
    ));
    close_session(&session);
}
