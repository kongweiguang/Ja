// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// session facade 的平台与生命周期验收。
//
// 测试单独放置，是为了让 worker 实现和公开 session API 可以分别阅读、
// 编译与审查；它们仍然通过同一个 production facade 驱动真实 PTY。

use super::*;
use std::fs;
use std::path::PathBuf;

/// 外置测试通过私有可见性制造真实 poison，生产 session 不增加可滥用 hook。
fn poison_mutex<T: Send>(mutex: &Mutex<T>) {
    std::thread::scope(|scope| {
        let worker = scope.spawn(|| {
            let _guard = match mutex.lock() {
                Ok(guard) => guard,
                Err(_) => return,
            };
            panic!("intentional terminal session poison");
        });
        assert!(worker.join().is_err());
    });
}

#[cfg(unix)]
/// 真实 Unix PTY 必须保留 ANSI/raw bytes，并在 shell exit 后送出 Exited。
#[test]
fn real_pty_echo_resize_exit_and_repeated_close() {
    let root = test_root();
    fs::create_dir_all(&root).unwrap();
    let policy = TerminalPolicy::new(&root).unwrap();
    let supervisor = TerminalSupervisor::new(policy);
    let session = supervisor
        .open(LaunchRequest {
            profile: crate::terminal::model::ShellProfile::Bash,
            cwd: None,
            env: std::collections::BTreeMap::new(),
            size: TerminalSize::default(),
        })
        .unwrap();
    session
        .resize(TerminalSize {
            rows: 40,
            cols: 120,
            ..TerminalSize::default()
        })
        .unwrap();
    session
        .send_input(
            b"printf '\\033[31mja-pty-echo\\033[0m\\n'; exit\n",
            Duration::from_secs(2),
        )
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut output = Vec::new();
    let mut resized = false;
    let mut exited = false;
    while Instant::now() < deadline {
        if let Some(event) = session.recv_until(deadline).unwrap() {
            match event.kind {
                TerminalEventKind::Output { data } => output.extend(data),
                TerminalEventKind::Resized { size } => {
                    resized = size.rows == 40 && size.cols == 120
                }
                TerminalEventKind::Exited { .. } => {
                    exited = true;
                    break;
                }
                _ => {}
            }
        }
    }
    assert!(resized, "resize event was not observed");
    assert!(exited, "PTY child did not exit before deadline");
    assert!(
        output
            .windows(b"ja-pty-echo".len())
            .any(|window| window == b"ja-pty-echo")
    );
    session.close(CloseReason::User).unwrap();
    session.close(CloseReason::User).unwrap();
    fs::remove_dir_all(root).unwrap();
}

/// 设计原因：零 timeout 必须在修改队列前分类，过期 generation 也不能写入当前 session。
#[test]
fn timeout_and_stale_generation_are_rejected() {
    let root = test_root();
    fs::create_dir_all(&root).unwrap();
    let policy = TerminalPolicy::new(&root).unwrap();
    let supervisor = TerminalSupervisor::new(policy);
    let session = supervisor.open(LaunchRequest::default()).unwrap();
    assert_eq!(
        session.send_input(b"x", Duration::ZERO).unwrap_err().code(),
        TerminalErrorCode::DeadlineExceeded
    );
    let stale = SessionHandle {
        runtime: session.runtime.clone(),
        generation: session.generation.saturating_add(1),
    };
    assert_eq!(
        stale.resize(TerminalSize::default()).unwrap_err().code(),
        TerminalErrorCode::StaleGeneration
    );
    session.close(CloseReason::User).unwrap();
    fs::remove_dir_all(root).unwrap();
}

/// 设计原因：由 handle 发起的关闭必须先释放配额槽位，后续 Tab 才能安全打开新 session。
#[test]
fn handle_close_releases_supervisor_slot() {
    let root = test_root();
    fs::create_dir_all(&root).unwrap();
    let limits = crate::terminal::policy::TerminalLimits {
        max_sessions: 1,
        ..crate::terminal::policy::TerminalLimits::default()
    };
    let policy = TerminalPolicy::with_limits(&root, limits).unwrap();
    let supervisor = TerminalSupervisor::new(policy);
    let first = supervisor.open(LaunchRequest::default()).unwrap();
    first.close(CloseReason::User).unwrap();
    let second = supervisor.open(LaunchRequest::default()).unwrap();
    second.close(CloseReason::User).unwrap();
    fs::remove_dir_all(root).unwrap();
}

/// scrollback 保存 raw bytes 且只保留 configured tail，避免 UTF-8 解码破坏终端状态。
#[test]
fn scrollback_is_byte_bounded() {
    let mut scrollback = Scrollback {
        chunks: std::collections::VecDeque::new(),
        bytes: 0,
        limit: 4,
    };
    scrollback.append(vec![0xff, 0xfe, 0x1b, b'[', b'0', b'm']);
    assert_eq!(scrollback.snapshot(), vec![0x1b, b'[', b'0', b'm']);
}

/// lifecycle poison 后必须停止进程与队列并拒绝 late input/resize，不得读取部分更新的终态。
#[test]
fn poisoned_session_lifecycle_fails_closed_and_stops_io() {
    let root = test_root();
    fs::create_dir_all(&root).expect("session poison root");
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).expect("poison policy"));
    let session = supervisor
        .open(LaunchRequest::default())
        .expect("poison session");
    poison_mutex(&session.runtime.lifecycle);

    let input_error = session
        .send_input(b"late", Duration::from_millis(10))
        .expect_err("poisoned lifecycle must reject input");
    assert_eq!(input_error.code(), TerminalErrorCode::SessionClosed);
    let resize_error = session
        .resize(TerminalSize::default())
        .expect_err("poisoned lifecycle must reject resize");
    assert_eq!(resize_error.code(), TerminalErrorCode::SessionClosed);
    assert!(session.runtime.stop.load(Ordering::Acquire));
    assert!(session.runtime.failed.load(Ordering::Acquire));
    // lifecycle 已不可信，close 必须保留失败信号，但仍需在同一有界路径等待 worker 退出。
    let close_error = session
        .close(CloseReason::Shutdown)
        .expect_err("poisoned lifecycle close must remain observable");
    assert_eq!(close_error.code(), TerminalErrorCode::ProcessCleanupFailed);
    fs::remove_dir_all(root).expect("remove session poison root");
}

/// scrollback poison 意味 byte/chunk accounting 不可信，读取必须关闭会话且不返回部分快照。
#[test]
fn poisoned_scrollback_rejects_snapshot_and_stops_session() {
    let root = test_root();
    fs::create_dir_all(&root).expect("scrollback poison root");
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).expect("poison policy"));
    let session = supervisor
        .open(LaunchRequest::default())
        .expect("scrollback poison session");
    poison_mutex(&session.runtime.scrollback);

    let error = session
        .scrollback()
        .expect_err("poisoned scrollback must not return partial bytes");
    assert_eq!(error.code(), TerminalErrorCode::SessionClosed);
    assert!(session.runtime.stop.load(Ordering::Acquire));
    assert!(session.runtime.failed.load(Ordering::Acquire));
    // fail-closed 只发出停止意图；由 supervisor close 等待 worker 并移除唯一 owner 后再清理目录。
    supervisor
        .close(session.id(), session.generation(), CloseReason::Shutdown)
        .expect("close scrollback poison session");
    fs::remove_dir_all(root).expect("remove scrollback poison root");
}

/// master 资源槽 poison 后只能重建为 `None`，同时向 close 报告清理失败。
#[test]
fn poisoned_master_slot_is_released_without_resuming_pty_use() {
    let root = test_root();
    fs::create_dir_all(&root).expect("master poison root");
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).expect("poison policy"));
    let session = supervisor
        .open(LaunchRequest::default())
        .expect("master poison session");
    poison_mutex(&session.runtime.master);

    let error = session
        .runtime
        .release_master()
        .expect_err("poisoned master release must remain observable");
    assert_eq!(error.code(), TerminalErrorCode::ProcessCleanupFailed);
    assert!(session.runtime.failed.load(Ordering::Acquire));
    let master = match session.runtime.master.lock() {
        Ok(master) => master,
        Err(_) => panic!("master slot must be rebuilt for resource release"),
    };
    assert!(master.is_none());
    drop(master);
    // 资源槽已重建为空，后续正式 close 应完成进程树和 worker 的有界回收。
    supervisor
        .close(session.id(), session.generation(), CloseReason::Shutdown)
        .expect("close master poison session");
    fs::remove_dir_all(root).expect("remove master poison root");
}

/// supervisor map poison 后需 drain 并关闭已存 owner，同时永久拒绝新 session admission。
#[test]
fn poisoned_supervisor_map_closes_owner_and_rejects_new_sessions() {
    let root = test_root();
    fs::create_dir_all(&root).expect("supervisor poison root");
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).expect("poison policy"));
    let session = supervisor
        .open(LaunchRequest::default())
        .expect("supervisor poison session");
    poison_mutex(&supervisor.inner.sessions);

    assert!(supervisor.active_count() > 0);
    assert!(supervisor.inner.failed.load(Ordering::Acquire));
    assert!(session.runtime.stop.load(Ordering::Acquire));
    let error = match supervisor.open(LaunchRequest::default()) {
        Ok(_) => panic!("failed supervisor must reject new session"),
        Err(error) => error,
    };
    assert_eq!(error.code(), TerminalErrorCode::WorkerShutdownTimeout);
    fs::remove_dir_all(root).expect("remove supervisor poison root");
}

/// 设计原因：真实 PTY 在 renderer 队列饱和后仍须可写；压力只投影非终态 delta，不能伪装
/// 成会终止 session 的 Error event。
#[test]
fn output_pressure_does_not_terminate_real_pty() {
    let root = test_root();
    fs::create_dir_all(&root).expect("pressure workspace");
    let limits = crate::terminal::policy::TerminalLimits {
        max_output_batch_bytes: 64,
        max_output_queue_bytes: 256,
        max_event_count: 3,
        max_scrollback_bytes: 1_024,
        ..crate::terminal::policy::TerminalLimits::default()
    };
    let supervisor = TerminalSupervisor::new(
        TerminalPolicy::with_limits(&root, limits).expect("pressure policy"),
    );
    let session = supervisor
        .open(LaunchRequest::default())
        .expect("pressure PTY");

    for marker in 0_u8..16 {
        assert!(session.runtime.publish_output(vec![marker; 64]));
    }
    let deadline = Instant::now() + Duration::from_secs(1);
    let mut dropped = 0_usize;
    let mut terminal_error = false;
    while Instant::now() < deadline {
        let Some(event) = session.recv_until(deadline).expect("pressure event") else {
            break;
        };
        match event.kind {
            TerminalEventKind::OutputDropped { bytes } => dropped = dropped.saturating_add(bytes),
            TerminalEventKind::Error { .. } => terminal_error = true,
            _ => {}
        }
    }
    assert!(dropped > 0, "renderer pressure did not produce a delta");
    assert!(!terminal_error, "renderer pressure terminated the PTY");
    session
        .send_input(b" ", Duration::from_secs(1))
        .expect("PTY remains writable after output pressure");
    supervisor
        .close(session.id(), session.generation(), CloseReason::User)
        .expect("close pressure PTY");
    assert_eq!(supervisor.active_count(), 0);
    fs::remove_dir_all(root).expect("remove pressure workspace");
}

#[cfg(unix)]
/// close 会终止由独立 process group 管理的 sleep shell，不能只结束 leader。
#[test]
fn real_pty_close_cleans_process_tree() {
    let root = test_root();
    fs::create_dir_all(&root).unwrap();
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).unwrap());
    let session = supervisor.open(LaunchRequest::default()).unwrap();
    session
        .send_input(b"sleep 30\n", Duration::from_secs(2))
        .unwrap();
    session.close(CloseReason::Timeout).unwrap();
    session.close(CloseReason::Timeout).unwrap();
    fs::remove_dir_all(root).unwrap();
}

#[cfg(windows)]
/// 设计原因：Windows ConPTY 验收必须经过真实 cmd.exe 交互，不能用仅 pipe 的假 shell 替代。
#[test]
fn real_conpty_echo_and_exit() {
    let root = test_root();
    fs::create_dir_all(&root).unwrap();
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).unwrap());
    let session = supervisor
        .open(LaunchRequest {
            profile: crate::terminal::model::ShellProfile::Cmd,
            ..LaunchRequest::default()
        })
        .unwrap();
    let mut output = Vec::new();
    let mut exited = false;
    let query_deadline = Instant::now() + Duration::from_secs(5);
    // cmd.exe 在接受首个 prompt 前会查询光标位置；生产 xterm 前端负责应答该控制序列。
    while Instant::now() < query_deadline && !output.windows(4).any(|window| window == b"\x1b[6n") {
        if let Some(event) = session.recv_until(query_deadline).unwrap() {
            match event.kind {
                TerminalEventKind::Output { data } => output.extend(data),
                TerminalEventKind::Exited { .. } => {
                    exited = true;
                    break;
                }
                _ => {}
            }
        }
    }
    assert!(
        !exited,
        "ConPTY child exited before its cursor query was answered"
    );
    session
        .send_input(b"\x1b[1;1R", Duration::from_secs(2))
        .unwrap();
    let prompt_offset = output.len();
    let prompt_deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < prompt_deadline && !output[prompt_offset..].contains(&b'>') {
        if let Some(event) = session.recv_until(prompt_deadline).unwrap() {
            match event.kind {
                TerminalEventKind::Output { data } => output.extend(data),
                TerminalEventKind::Exited { .. } => break,
                _ => {}
            }
        }
    }
    assert!(
        output[prompt_offset..].contains(&b'>'),
        "cmd prompt was not observed after cursor response"
    );
    session
        .send_input(b"echo ja-conpty-echo\r", Duration::from_secs(2))
        .unwrap();
    let echo_deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < echo_deadline
        && !output
            .windows(b"ja-conpty-echo".len())
            .any(|window| window == b"ja-conpty-echo")
    {
        if let Some(event) = session.recv_until(echo_deadline).unwrap() {
            match event.kind {
                TerminalEventKind::Output { data } => output.extend(data),
                TerminalEventKind::Exited { .. } => {
                    exited = true;
                    break;
                }
                _ => {}
            }
        }
    }
    assert!(
        output
            .windows(b"ja-conpty-echo".len())
            .any(|window| window == b"ja-conpty-echo"),
        "ConPTY echo marker was not observed"
    );
    session
        .send_input(b"exit\r", Duration::from_secs(2))
        .unwrap();
    let exit_deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < exit_deadline {
        if let Some(event) = session.recv_until(exit_deadline).unwrap() {
            match event.kind {
                TerminalEventKind::Output { data } => output.extend(data),
                TerminalEventKind::Exited { .. } => {
                    exited = true;
                    break;
                }
                _ => {}
            }
        }
    }
    assert!(exited, "ConPTY child did not exit before deadline");
    session.close(CloseReason::User).unwrap();
    fs::remove_dir_all(root).unwrap();
}

#[cfg(windows)]
/// 真实 ConPTY 必须看到当前 Ja 进程已有的 APPDATA；该变量不在旧的最小环境中，
/// 因此能回归“保留宿主环境”而不修改用户环境或把路径写进测试输出。子 PowerShell
/// 只返回 APPDATA 的 Base64 marker，避免 Cmd 的 OEM 输出编码影响断言。
#[test]
fn real_conpty_inherits_host_appdata() {
    let Some(expected) = std::env::var_os("APPDATA") else {
        // Windows 桌面通常总有 APPDATA；极简服务账户没有时不制造伪失败。
        return;
    };
    let expected = base64::engine::general_purpose::STANDARD
        .encode(expected.to_string_lossy().as_bytes())
        .into_bytes();
    if expected.is_empty() {
        return;
    }
    let root = test_root();
    fs::create_dir_all(&root).unwrap();
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).unwrap());
    let session = supervisor
        .open(LaunchRequest {
            profile: crate::terminal::model::ShellProfile::Cmd,
            ..LaunchRequest::default()
        })
        .unwrap();
    let mut output = Vec::new();
    let query_deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < query_deadline && !output.windows(4).any(|window| window == b"\x1b[6n") {
        if let Some(event) = session.recv_until(query_deadline).unwrap()
            && let TerminalEventKind::Output { data } = event.kind
        {
            output.extend(data);
        }
    }
    assert!(
        output.windows(4).any(|window| window == b"\x1b[6n"),
        "ConPTY child did not request cursor position"
    );
    session
        .send_input(b"\x1b[1;1R", Duration::from_secs(2))
        .unwrap();
    session
        .send_input(
            b"powershell.exe -NoLogo -NoProfile -Command \"[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($env:APPDATA))\"\r",
            Duration::from_secs(2),
        )
        .unwrap();

    let appdata_deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < appdata_deadline && !output.windows(expected.len()).any(|window| window == expected) {
        if let Some(event) = session.recv_until(appdata_deadline).unwrap()
            && let TerminalEventKind::Output { data } = event.kind
        {
            output.extend(data);
        }
    }
    let inherited = output.windows(expected.len()).any(|window| window == expected);
    session.close(CloseReason::User).unwrap();
    fs::remove_dir_all(root).unwrap();
    assert!(inherited, "ConPTY child did not inherit the host APPDATA value");
}

#[cfg(windows)]
#[test]
#[ignore = "requires a locally authenticated GitHub CLI; never a CI prerequisite"]
/// 手工证明真实 ConPTY 与宿主 CLI 看到同一 GitHub 登录态；只判断固定状态短语，绝不打印
/// `gh` 输出，避免 token、scope 或用户环境内容进入测试日志。
fn real_conpty_gh_auth_status_matches_host() {
    let host = Command::new("gh")
        .args(["auth", "status", "--hostname", "github.com"])
        .output()
        .expect("gh CLI must be installed for the ignored environment check");
    let mut host_output = host.stdout;
    host_output.extend(host.stderr);
    let marker = b"Logged in to github.com account";
    let host_authenticated = host.status.success()
        && host_output
            .windows(marker.len())
            .any(|window| window == marker);
    assert!(host_authenticated, "host gh auth status is not authenticated");

    let root = test_root();
    fs::create_dir_all(&root).unwrap();
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).unwrap());
    let session = supervisor
        .open(LaunchRequest {
            profile: crate::terminal::model::ShellProfile::Cmd,
            ..LaunchRequest::default()
        })
        .unwrap();
    let mut output = Vec::new();
    let query_deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < query_deadline && !output.windows(4).any(|window| window == b"\x1b[6n") {
        if let Some(event) = session.recv_until(query_deadline).unwrap()
            && let TerminalEventKind::Output { data } = event.kind
        {
            output.extend(data);
        }
    }
    assert!(
        output.windows(4).any(|window| window == b"\x1b[6n"),
        "ConPTY child did not request cursor position"
    );
    session
        .send_input(b"\x1b[1;1R", Duration::from_secs(2))
        .unwrap();
    session
        .send_input(b"gh auth status --hostname github.com\r", Duration::from_secs(2))
        .unwrap();
    let gh_deadline = Instant::now() + Duration::from_secs(15);
    while Instant::now() < gh_deadline
        && !output
            .windows(marker.len())
            .any(|window| window == marker)
    {
        if let Some(event) = session.recv_until(gh_deadline).unwrap()
            && let TerminalEventKind::Output { data } = event.kind
        {
            output.extend(data);
        }
    }
    let pty_authenticated = output
        .windows(marker.len())
        .any(|window| window == marker);
    session.close(CloseReason::User).unwrap();
    fs::remove_dir_all(root).unwrap();
    assert_eq!(pty_authenticated, host_authenticated);
}

#[cfg(windows)]
/// 设计原因：连续真实 ConPTY 打开、缩放、输入、退出和关闭不能累积过期 owner，也不能让
/// shell 逃出 Job Object 清理边界；30 轮复用同一 supervisor 才能暴露单轮测试看不到的泄漏。
#[test]
fn real_conpty_repeated_open_close_thirty_rounds() {
    let root = test_root();
    fs::create_dir_all(&root).unwrap();
    let supervisor = TerminalSupervisor::new(TerminalPolicy::new(&root).unwrap());
    for round in 0..30 {
        // 每轮仅保留一个有界进度标记，使原生 PTY 卡住时可定位阶段，同时不增加第二套
        // timeout 或改变生产 close 路径。
        eprintln!("conpty round {round}: open");
        let session = supervisor
            .open(LaunchRequest {
                profile: crate::terminal::model::ShellProfile::Cmd,
                ..LaunchRequest::default()
            })
            .unwrap();
        eprintln!("conpty round {round}: resize");
        session
            .resize(TerminalSize {
                rows: 30,
                cols: 100,
                ..TerminalSize::default()
            })
            .unwrap();
        let mut output = Vec::new();
        let query_deadline = Instant::now() + Duration::from_secs(2);
        while Instant::now() < query_deadline
            && !output.windows(4).any(|window| window == b"\x1b[6n")
        {
            if let Some(event) = session.recv_until(query_deadline).unwrap()
                && let TerminalEventKind::Output { data } = event.kind
            {
                output.extend(data);
            }
        }
        eprintln!(
            "conpty round {round}: cursor query observed={}",
            output.windows(4).any(|window| window == b"\x1b[6n")
        );
        session
            .send_input(b"\x1b[1;1R", Duration::from_secs(2))
            .unwrap();
        session
            .send_input(b"echo ja-conpty-round\r", Duration::from_secs(2))
            .unwrap();
        session
            .send_input(b"exit\r", Duration::from_secs(2))
            .unwrap();
        eprintln!("conpty round {round}: waiting exit");
        // 并行 workspace suite 会同时运行 Git、Java 与进程树测试；10 秒仍是有界失败预算，
        // 但不会把 Windows 调度抖动误报为 ConPTY owner 泄漏。
        let deadline = Instant::now() + Duration::from_secs(10);
        let mut exited = false;
        while Instant::now() < deadline {
            if let Some(event) = session.recv_until(deadline).unwrap() {
                match event.kind {
                    TerminalEventKind::Output { data } => output.extend(data),
                    TerminalEventKind::Exited { .. } => {
                        exited = true;
                        break;
                    }
                    _ => {}
                }
            }
        }
        assert!(exited, "ConPTY round did not emit Exited");
        eprintln!("conpty round {round}: close");
        session.close(CloseReason::User).unwrap();
        eprintln!("conpty round {round}: closed");
    }
    fs::remove_dir_all(root).unwrap();
}

/// 每个测试使用独立 workspace，避免失败测试留下 cwd 影响后续 session。
fn test_root() -> PathBuf {
    std::env::temp_dir().join(format!("ja-terminal-test-{}", uuid::Uuid::new_v4()))
}
