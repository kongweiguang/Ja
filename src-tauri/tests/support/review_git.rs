// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::fs;
use std::path::PathBuf;
use std::process::Command;
use uuid::Uuid;

/// 为 Review Git 查询与仓库策略测试提供唯一临时仓库，避免两个职责模块复制 fixture 生命周期。
pub(super) struct TempDir(pub(super) PathBuf);

impl TempDir {
    /// 创建包含空格的路径以覆盖 Git NUL 状态模式下的真实桌面命名；使用 UUID 避免 Windows
    /// 时钟精度使并行 fixture 命中相同时间戳并破坏彼此的 `.git` 目录。
    pub(super) fn create() -> Self {
        let suffix = Uuid::new_v4();
        let path = std::env::temp_dir().join(format!("ja git fixture {suffix}"));
        fs::create_dir_all(&path).expect("create git fixture");
        Self(path)
    }
}

impl Drop for TempDir {
    /// 子进程清理后仅删除本测试唯一命名的仓库，避免影响其他并行 fixture。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// 按生产准入相同方式查找原生 Git，不假设测试主机上的固定安装路径。
pub(super) fn git_program() -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for directory in std::env::split_paths(&path) {
        let candidate = directory.join(if cfg!(windows) { "git.exe" } else { "git" });
        if candidate.is_file() {
            return candidate.canonicalize().ok();
        }
    }
    None
}

/// 仅使用 fixture 初始化命令构造真实临时仓库，保证解析与安全校验面对真实 Git 数据。
pub(super) fn run_git(git: &PathBuf, root: &PathBuf, args: &[&str]) {
    let status = Command::new(git)
        .current_dir(root)
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env(
            "GIT_CONFIG_GLOBAL",
            if cfg!(windows) { "NUL" } else { "/dev/null" },
        )
        .args(args)
        .status()
        .expect("run fixture git");
    assert!(status.success(), "fixture git failed: {args:?}");
}

/// 创建已跟踪的 Unicode 与空格文件，使机器格式解析覆盖真实 Git 数据。
pub(super) fn fixture_repo() -> Option<(TempDir, PathBuf)> {
    let git = git_program()?;
    let root = TempDir::create();
    run_git(&git, &root.0, &["init", "--initial-branch=main", "-q"]);
    fs::write(root.0.join("文件 name.txt"), "initial\n").expect("write tracked fixture");
    run_git(&git, &root.0, &["add", "--", "文件 name.txt"]);
    run_git(
        &git,
        &root.0,
        &[
            "-c",
            "user.name=Ja Test",
            "-c",
            "user.email=ja@example.invalid",
            "commit",
            "-m",
            "fixture",
            "--no-verify",
        ],
    );
    Some((root, git))
}
