// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// Command 边界测试与生产实现分文件，测试仍能读取父模块私有状态但不会污染生产阅读路径。

use super::*;
use std::fs;
use std::sync::atomic::{AtomicUsize, Ordering};

/// 测试直接从父模块私有生命周期读取 owner 数量，避免生产类型为断言暴露专用 API。
fn active_owner_count(host: &TerminalCommandHost) -> usize {
    match host.lifecycle.lock() {
        Ok(lifecycle) => lifecycle
            .supervisor
            .as_ref()
            .map_or(0, |configured| configured.supervisor.active_count()),
        Err(_) => usize::MAX,
    }
}

/// 仅在外置测试内中断 host lifecycle 临界区，避免生产公开 test hook。
fn poison_host_lifecycle(host: &TerminalCommandHost) {
    std::thread::scope(|scope| {
        let worker = scope.spawn(|| {
            let _guard = match host.lifecycle.lock() {
                Ok(guard) => guard,
                Err(_) => return,
            };
            panic!("intentional terminal host poison");
        });
        assert!(worker.join().is_err());
    });
}

/// workspace id 必须保持不透明 protocol identity，任何路径形态都不得准入。
#[test]
fn workspace_id_validation_rejects_paths() {
    assert!(validate_workspace_id("ws_fixture").is_ok());
    assert!(validate_workspace_id(r"C:\workspace").is_err());
    assert!(validate_workspace_id("workspace").is_err());
    assert!(validate_workspace_id("ws_../escape").is_err());
}

/// 相对 cwd admission 必须同时拒绝 traversal 和 Windows/Unix rooted 形式。
#[test]
fn relative_cwd_validation_is_path_safe() {
    assert_eq!(
        parse_relative_cwd(Some(String::from("src\\bin"))).unwrap(),
        Some(PathBuf::from("src\\bin"))
    );
    assert!(parse_relative_cwd(Some(String::from("..\\outside"))).is_err());
    assert!(parse_relative_cwd(Some(String::from(r"C:\workspace"))).is_err());
    assert!(parse_relative_cwd(Some(String::from(r"\root"))).is_err());
}

/// profile 查询的 wire 结果只能是受控枚举字符串，不能携带 executable 或 path。
#[test]
fn profile_query_serializes_only_closed_profile_ids() {
    let profiles = ja_terminal_profiles();
    let value = serde_json::to_value(&profiles).expect("serialize terminal profiles");
    let encoded = value.to_string();

    assert_eq!(profiles, available_shell_profiles());
    assert!(
        value
            .as_array()
            .is_some_and(|items| items.iter().all(|item| {
                item.as_str().is_some_and(|profile| {
                    matches!(
                        profile,
                        "default" | "power_shell" | "cmd" | "bash" | "zsh" | "fish"
                    )
                })
            }))
    );
    assert!(!encoded.contains("executable"));
    assert!(!encoded.contains("path"));
}

/// canonical path 比较不能把普通文件误当作 workspace root。
#[test]
fn command_host_rejects_non_directory_workspace_root() {
    let fixture = std::env::temp_dir().join(format!(
        "ja-terminal-command-file-root-{}",
        uuid::Uuid::new_v4()
    ));
    fs::write(&fixture, b"not a directory").expect("write file root fixture");
    let error = TerminalCommandHost::new()
        .configure(String::from("ws_fixture"), fixture.clone())
        .expect_err("file root must be rejected");
    assert_eq!(error.code(), TerminalErrorCode::InvalidCwd);
    fs::remove_file(fixture).expect("remove file root fixture");
}

/// 对空 workspace 的 close-all 必须幂等，且不能遗留 owner。
#[test]
fn close_all_clears_empty_workspace_binding() {
    let root = std::env::temp_dir().join(format!("ja-terminal-command-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&root).unwrap();
    let host = TerminalCommandHost::new();
    host.configure(String::from("ws_fixture"), root.clone())
        .unwrap();
    host.close_all("ws_fixture", Instant::now() + Duration::from_secs(1))
        .unwrap();
    assert!(host.is_empty());
    fs::remove_dir_all(root).unwrap();
}

/// host lifecycle poison 后必须永久拒绝重配置，不能把已失去线性化点的 binding 恢复使用。
#[test]
fn poisoned_command_host_fences_workspace_binding_permanently() {
    let root = std::env::temp_dir().join(format!(
        "ja-terminal-command-poison-{}",
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&root).expect("poison root");
    let host = TerminalCommandHost::new();
    let session = host
        .open("ws_fixture", root.clone(), LaunchRequest::default())
        .expect("open poison fixture");
    poison_host_lifecycle(&host);

    assert!(!host.is_empty());
    let session_error = session
        .send_input(b"late", Duration::from_millis(10))
        .expect_err("poison cleanup must stop the existing owner");
    assert_eq!(session_error.code(), TerminalErrorCode::SessionClosed);
    let error = host
        .configure(String::from("ws_fixture"), root.clone())
        .expect_err("poisoned host must reject reconfiguration");
    assert_eq!(error.code(), TerminalErrorCode::InvalidConfig);
    assert!(host.failed.load(Ordering::Acquire));
    fs::remove_dir_all(root).expect("remove poison root");
}

/// 连续八次 open 必须共享同一 configured supervisor；第九次应由原生 session budget 拒绝，
/// 而不是触发 reconfiguration。
#[test]
fn command_host_reuses_workspace_until_session_budget() {
    let root = std::env::temp_dir().join(format!(
        "ja-terminal-command-budget-{}",
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&root).expect("terminal root");
    let host = TerminalCommandHost::new();
    let mut sessions = Vec::new();
    for _ in 0..8 {
        sessions.push(
            host.open("ws_fixture", root.clone(), LaunchRequest::default())
                .expect("same-workspace pane"),
        );
    }

    let error = match host.open("ws_fixture", root.clone(), LaunchRequest::default()) {
        Ok(_) => panic!("ninth pane must exceed budget"),
        Err(error) => error,
    };
    assert_eq!(error.code(), TerminalErrorCode::SessionLimit);
    assert_eq!(sessions.len(), 8);
    host.close_all("ws_fixture", Instant::now() + Duration::from_secs(15))
        .expect("close budget fixtures");
    assert!(host.is_empty());
    fs::remove_dir_all(root).expect("remove terminal root");
}

/// close-all 清理 process owner 与 stale workspace identity 前，其它 workspace 不能替换 binding。
#[test]
fn command_host_requires_close_before_workspace_replacement() {
    let first_root = std::env::temp_dir().join(format!(
        "ja-terminal-command-first-{}",
        uuid::Uuid::new_v4()
    ));
    let second_root = std::env::temp_dir().join(format!(
        "ja-terminal-command-second-{}",
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&first_root).expect("first terminal root");
    fs::create_dir_all(&second_root).expect("second terminal root");
    let host = TerminalCommandHost::new();
    let session = host
        .open("ws_first", first_root.clone(), LaunchRequest::default())
        .expect("first workspace pane");

    let error = host
        .configure(String::from("ws_second"), second_root.clone())
        .expect_err("live workspace replacement");
    assert_eq!(error.code(), TerminalErrorCode::InvalidConfig);
    assert_eq!(session.generation(), 1);
    host.close_all("ws_first", Instant::now() + Duration::from_secs(15))
        .expect("close first workspace");
    let replacement = host
        .open("ws_second", second_root.clone(), LaunchRequest::default())
        .expect("open after workspace close-all");
    assert_eq!(replacement.generation(), 1);
    host.close_all("ws_second", Instant::now() + Duration::from_secs(15))
        .expect("clear replacement binding");
    fs::remove_dir_all(first_root).expect("remove first root");
    fs::remove_dir_all(second_root).expect("remove second root");
}

/// 真实 PTY open 与应用 shutdown 竞态时，要么先线性化并由 shutdown 关闭，要么观察永久 fence 后失败。
#[test]
fn command_host_shutdown_fences_concurrent_and_late_open() {
    let root = std::env::temp_dir().join(format!(
        "ja-terminal-command-shutdown-race-{}",
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&root).expect("shutdown race root");
    let host = TerminalCommandHost::new();
    let initial = host
        .open("ws_fixture", root.clone(), LaunchRequest::default())
        .expect("initial shutdown race pane");
    let start = Arc::new(std::sync::Barrier::new(3));

    let open_host = host.clone();
    let open_root = root.clone();
    let open_start = start.clone();
    let open_thread = std::thread::spawn(move || {
        open_start.wait();
        open_host
            .open("ws_fixture", open_root, LaunchRequest::default())
            .map(|_| ())
            .map_err(TerminalError::code)
    });
    let shutdown_host = host.clone();
    let shutdown_start = start.clone();
    let shutdown_thread = std::thread::spawn(move || {
        shutdown_start.wait();
        shutdown_host.shutdown_until(Instant::now() + Duration::from_secs(15))
    });
    start.wait();

    let open_result = open_thread.join().expect("join racing open");
    let shutdown_result = shutdown_thread.join().expect("join shutdown");
    shutdown_result.expect("concurrent shutdown");
    assert!(matches!(
        open_result,
        Ok(()) | Err(TerminalErrorCode::InvalidConfig)
    ));
    assert_eq!(active_owner_count(&host), 0);
    assert!(host.is_empty());
    let late_close = match host.close_owner(initial.id(), initial.generation()) {
        Ok(_) => panic!("late close must observe shutdown fence"),
        Err(error) => error,
    };
    assert_eq!(late_close.code(), TerminalErrorCode::InvalidConfig);
    assert_eq!(
        initial
            .send_input(b"late", Duration::from_millis(10))
            .expect_err("initial child is closed")
            .code(),
        TerminalErrorCode::SessionClosed
    );

    let late_open = match host.open("ws_fixture", root.clone(), LaunchRequest::default()) {
        Ok(_) => panic!("late open must observe shutdown fence"),
        Err(error) => error,
    };
    assert_eq!(late_open.code(), TerminalErrorCode::InvalidConfig);
    let late_configure = host
        .configure(String::from("ws_fixture"), root.clone())
        .expect_err("late configure must observe shutdown fence");
    assert_eq!(late_configure.code(), TerminalErrorCode::InvalidConfig);
    host.shutdown_until(Instant::now() + Duration::from_secs(1))
        .expect("repeated shutdown is idempotent");
    assert_eq!(active_owner_count(&host), 0);
    fs::remove_dir_all(root).expect("remove shutdown race root");
}

/// 存活 session 同时固定不透明 workspace id 与 canonical root；同一 root 的 alias 可复用，
/// 新物理 root 必须拒绝。
#[test]
fn command_host_rejects_live_workspace_or_root_identity_change() {
    let first_root = std::env::temp_dir().join(format!(
        "ja-terminal-command-bound-root-{}",
        uuid::Uuid::new_v4()
    ));
    let second_root = std::env::temp_dir().join(format!(
        "ja-terminal-command-other-root-{}",
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&first_root).expect("first terminal root");
    fs::create_dir_all(&second_root).expect("second terminal root");
    let host = TerminalCommandHost::new();
    host.open("ws_fixture", first_root.clone(), LaunchRequest::default())
        .expect("first workspace pane");
    host.open("ws_fixture", first_root.join("."), LaunchRequest::default())
        .expect("canonical alias pane");

    let changed_root = match host.open("ws_fixture", second_root.clone(), LaunchRequest::default())
    {
        Ok(_) => panic!("live root replacement must fail"),
        Err(error) => error,
    };
    assert_eq!(changed_root.code(), TerminalErrorCode::InvalidConfig);
    let changed_workspace =
        match host.open("ws_other", first_root.clone(), LaunchRequest::default()) {
            Ok(_) => panic!("live workspace replacement must fail"),
            Err(error) => error,
        };
    assert_eq!(changed_workspace.code(), TerminalErrorCode::InvalidConfig);

    host.close_all("ws_fixture", Instant::now() + Duration::from_secs(15))
        .expect("close identity fixtures");
    fs::remove_dir_all(first_root).expect("remove first root");
    fs::remove_dir_all(second_root).expect("remove second root");
}

/// 捕获的 close owner 在不固定 host mutex 的前提下保留 generation authority，从而覆盖
/// admission 后、single-close worker 运行前 close-all 先完成回收的确定性交错。
#[test]
fn captured_close_owner_is_idempotent_after_close_all_wins() {
    let root = std::env::temp_dir().join(format!(
        "ja-terminal-command-close-owner-{}",
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&root).expect("close owner root");
    let host = TerminalCommandHost::new();
    let session = host
        .open("ws_fixture", root.clone(), LaunchRequest::default())
        .expect("close owner pane");
    let stale = match host.close_owner(session.id(), session.generation() + 1) {
        Ok(_) => panic!("stale close owner must be rejected"),
        Err(error) => error,
    };
    assert_eq!(stale.code(), TerminalErrorCode::StaleGeneration);

    let owner = host
        .close_owner(session.id(), session.generation())
        .expect("capture admitted close owner");
    host.close_all("ws_fixture", Instant::now() + Duration::from_secs(15))
        .expect("close-all wins deterministic interleaving");
    owner
        .close()
        .expect("captured single close remains idempotent");
    assert!(host.is_empty());
    fs::remove_dir_all(root).expect("remove close owner root");
}

/// 一个 semaphore permit 必须覆盖完整 blocking close；第二个 close 饱和时不排队，
/// current-thread runtime 仍可继续向原生 worker 发送 release signal。
#[tokio::test]
async fn bounded_close_worker_does_not_block_async_executor() {
    let workers = Arc::new(tokio::sync::Semaphore::new(1));
    let (started_sender, started_receiver) = tokio::sync::oneshot::channel();
    let (release_sender, release_receiver) = std::sync::mpsc::channel();
    let first = tokio::spawn(run_bounded_terminal_close(workers.clone(), move || {
        let _ = started_sender.send(());
        release_receiver
            .recv()
            .map_err(|_| TerminalError::new(TerminalErrorCode::Cancelled))?;
        Ok(())
    }));
    started_receiver.await.expect("blocking worker started");
    assert_eq!(workers.available_permits(), 0);

    let second_started = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let second_marker = second_started.clone();
    let second_error = run_bounded_terminal_close(workers.clone(), move || {
        second_marker.store(true, std::sync::atomic::Ordering::Release);
        Ok(())
    })
    .await
    .expect_err("saturated close worker must not queue");
    assert_eq!(second_error.code(), TerminalErrorCode::QueueFull);
    assert!(!second_started.load(std::sync::atomic::Ordering::Acquire));

    release_sender.send(()).expect("release first close worker");
    first
        .await
        .expect("join first close task")
        .expect("first close result");
    assert_eq!(workers.available_permits(), 1);
}

/// 伪造 session 不能消耗共享 native token，合法 Files 或 Terminal target 之后仍可使用它。
#[test]
fn wrong_session_is_rejected_before_drop_token_consumption() {
    let root =
        std::env::temp_dir().join(format!("ja-terminal-drop-owner-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&root).expect("terminal root");
    let dropped = root.join("dropped.txt");
    fs::write(&dropped, "drop").expect("drop fixture");
    let token = crate::workspace::issue_native_drop([dropped]).expect("drop token");
    let host = TerminalCommandHost::new();
    host.configure(String::from("ws_fixture"), root.clone())
        .expect("configured host");
    let error = terminal_drop(
        &host,
        &TerminalDropInput {
            session_id: TerminalId::new(),
            generation: 1,
            drop_token: token.clone(),
        },
    )
    .expect_err("forged session");
    assert_eq!(error.code(), TerminalErrorCode::SessionNotFound);
    assert!(consume_native_drop(&token).is_ok());
    fs::remove_dir_all(root).expect("remove fixture");
}

/// 首次消费只写入一个 quoted payload，重放不能再次调用 PTY writer，证明 token 复用为零副作用。
#[test]
fn native_drop_replay_is_zero_write() {
    let root =
        std::env::temp_dir().join(format!("ja-terminal-drop-replay-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&root).expect("terminal root");
    let dropped = root.join("dropped.txt");
    fs::write(&dropped, "drop").expect("drop fixture");
    let canonical = fs::canonicalize(&dropped).expect("canonical drop path");
    let token = crate::workspace::issue_native_drop([dropped]).expect("drop token");
    let writes = AtomicUsize::new(0);
    let mut payload = Vec::new();
    consume_quote_and_send(ShellProfile::PowerShell, &token, |data| {
        writes.fetch_add(1, Ordering::Relaxed);
        payload.extend_from_slice(data);
        Ok(())
    })
    .expect("first drop");
    assert_eq!(
        payload,
        quote_native_paths(ShellProfile::PowerShell, &[canonical]).expect("quoted fixture")
    );
    let replay = consume_quote_and_send(ShellProfile::PowerShell, &token, |_| {
        writes.fetch_add(1, Ordering::Relaxed);
        Ok(())
    })
    .expect_err("replay");
    assert_eq!(replay.code(), TerminalErrorCode::DropTokenInvalid);
    assert_eq!(writes.load(Ordering::Relaxed), 1);
    fs::remove_dir_all(root).expect("remove fixture");
}
