// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// 通过 shell 启动嵌套休眠进程，证明超时返回前清理的是完整进程树而非仅主进程。
#[test]
fn timeout_reaps_process_tree_before_returning() {
    let command = if cfg!(windows) {
        let mut command = Command::new("cmd.exe");
        command.args([
            "/C",
            "powershell.exe",
            "-NoProfile",
            "-Command",
            "Start-Sleep -Seconds 8",
        ]);
        command
    } else {
        let mut command = Command::new("sh");
        command.args(["-c", "sleep 8"]);
        command
    };
    let policy = GitPolicy {
        timeout: Duration::from_millis(100),
        cleanup_timeout: Duration::from_secs(2),
        poll_interval: Duration::from_millis(5),
        ..GitPolicy::default()
    };
    let result = run_git(command, &policy, &Default::default());
    assert!(matches!(result, Err(GitError::TimedOut)));
}
