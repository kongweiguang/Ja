// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 平台进程树与有界回收算法的私有模块回归测试。

use super::*;
#[cfg(unix)]
use std::fs;
#[cfg(unix)]
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(unix)]
struct TempFileGuard(std::path::PathBuf);

#[cfg(unix)]
impl TempFileGuard {
    /// 持有 fixture path，确保 assertion 或 process spawn 失败不会在 workspace 或系统
    /// temp 目录遗留协议产物。
    fn new(path: std::path::PathBuf) -> Self {
        Self(path)
    }
}

#[cfg(unix)]
impl Drop for TempFileGuard {
    /// 只删除精确 fixture path；成功后的重复 cleanup 保持幂等。
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

struct NeverReaps;

impl ProcessBackend for NeverReaps {
    /// 故意让 kill 失败，验证 helper 仍会在绝对 deadline 返回。
    fn kill(&self, _child: &mut Child) -> io::Result<()> {
        Err(io::Error::other("injected kill failure"))
    }

    /// 故意不报告退出，模拟 wait backend 卡住但不允许无界阻塞。
    fn try_wait(&self, _child: &mut Child) -> io::Result<Option<ExitStatus>> {
        Ok(None)
    }
}

/// 创建立即退出的跨平台 child，供 bounded reap fault hook 使用。
fn short_child() -> Child {
    #[cfg(windows)]
    {
        Command::new("cmd")
            .args(["/C", "exit", "0"])
            .spawn()
            .expect("short child spawned")
    }
    #[cfg(unix)]
    {
        Command::new("sh")
            .args(["-c", "exit 0"])
            .spawn()
            .expect("short child spawned")
    }
    #[cfg(not(any(unix, windows)))]
    {
        panic!("no process fixture for this platform")
    }
}

/// 注入 kill/wait 故障时必须在 deadline 内返回，并把 Child 所有权留给 caller。
#[test]
fn bounded_reap_returns_on_backend_fault() {
    let mut child = short_child();
    let deadline = Instant::now()
        .checked_add(Duration::from_millis(40))
        .expect("reap deadline fits");
    let error = bounded_reap_child_with(&mut child, deadline, &NeverReaps)
        .expect_err("faulting backend must be observable");
    assert_eq!(error.kind(), io::ErrorKind::Other);
    let _ = bounded_reap_child(&mut child, Instant::now() + Duration::from_secs(1));
}

#[cfg(unix)]
/// 证明 Unix process group 收口会覆盖 shell 启动的 descendant，而不只结束 leader。
#[test]
fn process_group_termination_reaches_descendant() {
    let pid_path = std::env::temp_dir().join(format!(
        "ja-process-tree-{}-{}.pid",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock is after unix epoch")
            .as_nanos()
    ));
    let _pid_cleanup = TempFileGuard::new(pid_path.clone());
    let mut command = Command::new("sh");
    command.args([
        "-c",
        "sleep 30 & printf '%s' \"$!\" > \"$1\"; wait",
        "ja-tree-fixture",
        pid_path.to_str().expect("temporary path is utf8"),
    ]);
    let guard = ProcessTreeGuard::prepare(&mut command).unwrap();
    let mut child = command.spawn().unwrap();
    guard.assign(&child).unwrap();
    guard.resume(&child).unwrap();
    let deadline = Instant::now()
        .checked_add(Duration::from_secs(2))
        .expect("test deadline fits in Instant");
    let descendant = loop {
        if let Ok(pid) = fs::read_to_string(&pid_path) {
            if let Ok(pid) = pid.trim().parse::<i32>() {
                break pid;
            }
        }
        assert!(
            Instant::now() < deadline,
            "descendant pid was not published"
        );
        thread::yield_now();
    };
    guard.terminate().unwrap();
    let deadline = Instant::now()
        .checked_add(Duration::from_secs(2))
        .expect("child reap deadline fits in Instant");
    bounded_reap_child(&mut child, deadline).expect("fixture child reaped");
    let deadline = Instant::now()
        .checked_add(Duration::from_secs(2))
        .expect("test deadline fits in Instant");
    while unsafe { kill(descendant, 0) } == 0 && Instant::now() < deadline {
        thread::yield_now();
    }
    let _ = fs::remove_file(pid_path);
    assert_ne!(unsafe { kill(descendant, 0) }, 0);
}
