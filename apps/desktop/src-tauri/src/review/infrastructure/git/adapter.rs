// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::error::GitError;
use super::model::{GitDiff, GitLogEntry, GitStatusEntry};
use super::parse::{parse_log, parse_status};
use super::process::run_git;
use super::repository_policy::validate_worktree;
use crate::workspace::{WorkspaceError, WorkspaceHandle};
use serde::{Deserialize, Serialize};
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio_util::sync::CancellationToken;

/// 为所有类型化 Git 操作提供统一上限，避免调用方通过 argv 绕过资源约束。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct GitPolicy {
    pub timeout: Duration,
    pub cleanup_timeout: Duration,
    pub poll_interval: Duration,
    pub max_output_bytes: usize,
    pub max_error_bytes: usize,
    pub max_status_records: usize,
}

impl Default for GitPolicy {
    /// 使用适合交互场景的超时与输出上限，兼顾本地仓库规模和界面响应性。
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(15),
            cleanup_timeout: Duration::from_secs(2),
            poll_interval: Duration::from_millis(10),
            max_output_bytes: 8 * 1024 * 1024,
            max_error_bytes: 256 * 1024,
            max_status_records: 100_000,
        }
    }
}

/// 将 diff 限定为 worktree 或 index 状态及可选精确相对路径，不接受用户提供的 pathspec 语法。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DiffOptions {
    pub staged: bool,
    pub relative_path: Option<String>,
}

impl Default for DiffOptions {
    /// 默认展示 worktree diff，使首次打开时对应用户尚未暂存的真实改动。
    fn default() -> Self {
        Self {
            staged: false,
            relative_path: None,
        }
    }
}

/// 绑定单个不可变规范工作区句柄的只读 Git façade。
#[derive(Debug, Clone)]
pub struct GitReadOnly {
    workspace: WorkspaceHandle,
    program: PathBuf,
    policy: GitPolicy,
}

impl GitReadOnly {
    /// 仅从可信启动环境解析一次 Git，后续即使清空子进程环境也始终使用同一规范可执行文件。
    pub fn new(workspace: WorkspaceHandle) -> Result<Self, GitError> {
        validate_worktree(&workspace)?;
        let program = resolve_git_program().ok_or(GitError::GitUnavailable)?;
        Ok(Self {
            workspace,
            program,
            policy: GitPolicy::default(),
        })
    }

    /// 在禁用 external diff 与 textconv 后返回有界且二进制安全的 diff 字节。
    pub fn diff(
        &self,
        options: &DiffOptions,
        cancellation: &CancellationToken,
    ) -> Result<GitDiff, GitError> {
        let mut args = vec![
            OsString::from("diff"),
            OsString::from("--no-ext-diff"),
            OsString::from("--no-color"),
            OsString::from("--no-textconv"),
            OsString::from("--binary"),
        ];
        if options.staged {
            args.push(OsString::from("--cached"));
        }
        args.push(OsString::from("--"));
        if let Some(path) = &options.relative_path {
            self.validate_path(path)?;
            args.push(OsString::from(path));
        }
        let output = self.run_os(&args, cancellation)?;
        Ok(GitDiff {
            bytes: output,
            truncated: false,
        })
    }

    /// 返回固定六字段、NUL 终止的日志投影；`-z` 独占记录边界，避免平台换行污染下一条 object id。
    pub fn log(
        &self,
        max_count: usize,
        cancellation: &CancellationToken,
    ) -> Result<Vec<GitLogEntry>, GitError> {
        let count = max_count.clamp(1, 500);
        let format = "%H%x00%P%x00%an%x00%ae%x00%aI%x00%s";
        let args = vec![
            OsString::from("log"),
            OsString::from("-z"),
            OsString::from("--no-color"),
            OsString::from("--no-decorate"),
            OsString::from("--no-ext-diff"),
            OsString::from("--no-textconv"),
            OsString::from(format!("--format={format}")),
            OsString::from(format!("-n{count}")),
            OsString::from("--"),
        ];
        parse_log(&self.run_os(&args, cancellation)?)
    }

    /// 让 Review 已校验的操作复用只读命令相同的 worktree 准入、固定环境、超时和进程树清理。
    /// 保持 crate 内窄入口，避免 Review 再构造一套安全策略更弱的 Git runner。
    pub(crate) fn run_review_command(
        &self,
        args: &[OsString],
        cancellation: &CancellationToken,
    ) -> Result<Vec<u8>, GitError> {
        self.run_os(args, cancellation)
    }

    /// 使用原生创建的临时 index 执行 Review mutation，并要求 index 是已准入 Git 元数据目录的直接子项，
    /// 防止调用方利用 `GIT_INDEX_FILE` 逃逸路径边界。
    pub(crate) fn run_review_command_with_index(
        &self,
        args: &[OsString],
        index: &Path,
        cancellation: &CancellationToken,
    ) -> Result<Vec<u8>, GitError> {
        self.run_os_with_index(args, Some(index), cancellation)
    }

    /// 通过固定命令解析 Git 的真实 index 并将结果限制在工作区内，既支持内部 linked-worktree 布局，
    /// 又无需信任调用方提供的元数据路径。
    pub(crate) fn review_index_path(
        &self,
        cancellation: &CancellationToken,
    ) -> Result<PathBuf, GitError> {
        let output = self.run(
            &["rev-parse", "--path-format=absolute", "--git-path", "index"],
            cancellation,
        )?;
        let text = std::str::from_utf8(&output)
            .map_err(|_| GitError::Parse)?
            .trim_end_matches(['\r', '\n']);
        if text.is_empty() || text.len() > 4_096 {
            return Err(GitError::ExternalWorktree);
        }
        let path = PathBuf::from(text);
        self.validate_review_index_path(&path, false)?;
        Ok(path)
    }

    /// 为 Review 的新增文件投影读取全部未跟踪文件；由此窄入口统一承担 status 查询，
    /// 避免恢复与 Review 无关的通用 Git façade。
    pub(crate) fn review_status_all(
        &self,
        cancellation: &CancellationToken,
    ) -> Result<Vec<GitStatusEntry>, GitError> {
        let output = self.run(
            &["status", "--porcelain=v2", "-z", "--untracked-files=all"],
            cancellation,
        )?;
        parse_status(&output, self.policy.max_status_records)
    }

    /// Git 输出仍是不可信外部输入，必须复用 workspace containment 权威实现后才能进入 Review 投影。
    fn validate_path(&self, path: &str) -> Result<(), GitError> {
        self.workspace
            .validate_git_path(path)
            .map_err(|error| match error {
                WorkspaceError::InvalidRelativePath => GitError::InvalidPath,
                _ => GitError::Workspace,
            })
    }

    fn run(&self, args: &[&str], cancellation: &CancellationToken) -> Result<Vec<u8>, GitError> {
        let args = args.iter().map(OsString::from).collect::<Vec<_>>();
        self.run_os(&args, cancellation)
    }

    fn run_os(
        &self,
        args: &[OsString],
        cancellation: &CancellationToken,
    ) -> Result<Vec<u8>, GitError> {
        self.run_os_with_index(args, None, cancellation)
    }

    /// 执行带可选原生临时 index 的固定命令，同时保持统一的 worktree 与进程加固检查。
    fn run_os_with_index(
        &self,
        args: &[OsString],
        index: Option<&Path>,
        cancellation: &CancellationToken,
    ) -> Result<Vec<u8>, GitError> {
        self.validate_review_context()?;
        if let Some(index) = index {
            self.validate_review_index_path(index, true)?;
        }
        let mut command = self.build_command(args);
        if let Some(index) = index {
            command.env("GIT_INDEX_FILE", index);
        }
        let output = run_git(command, &self.policy, cancellation)?;
        self.validate_review_context()?;
        if !output.status.success() {
            return Err(GitError::CommandFailed {
                code: output.status.code(),
            });
        }
        Ok(output.stdout)
    }

    /// 每条命令前后使用同一准入检查，避免性能优化绕过配置与对象目录边界。
    fn validate_review_context(&self) -> Result<(), GitError> {
        self.workspace
            .resolve_directory("")
            .map_err(|_| GitError::Workspace)?;
        validate_worktree(&self.workspace)
    }

    /// 将真实及临时 index 限制在同一已准入元数据目录，临时文件名仅接受 Review 生成的封闭前缀。
    fn validate_review_index_path(&self, index: &Path, temporary: bool) -> Result<(), GitError> {
        if !index.is_absolute() {
            return Err(GitError::ExternalWorktree);
        }
        let parent = index.parent().ok_or(GitError::ExternalWorktree)?;
        let parent = fs::canonicalize(parent).map_err(|_| GitError::ExternalWorktree)?;
        let root =
            fs::canonicalize(self.workspace.root_path()).map_err(|_| GitError::ExternalWorktree)?;
        if !parent.starts_with(&root) {
            return Err(GitError::ExternalWorktree);
        }
        let name = index
            .file_name()
            .and_then(|value| value.to_str())
            .ok_or(GitError::ExternalWorktree)?;
        if temporary && (!name.starts_with(".ja-review-index-") || !name.ends_with(".tmp")) {
            return Err(GitError::ExternalWorktree);
        }
        Ok(())
    }

    /// 为每次操作构造固定环境和选项前缀，使本地配置无法启用 pager、textconv、external diff 或交互提示。
    pub(crate) fn build_command(&self, args: &[OsString]) -> std::process::Command {
        let mut command = std::process::Command::new(&self.program);
        command
            .env_clear()
            .current_dir(self.workspace.root_path())
            .args([
                OsString::from("--no-optional-locks"),
                OsString::from("--no-pager"),
                OsString::from("-c"),
                OsString::from("core.pager=cat"),
                OsString::from("-c"),
                OsString::from("pager.diff=false"),
                OsString::from("-c"),
                OsString::from("color.ui=false"),
                OsString::from("-c"),
                OsString::from("diff.external="),
                OsString::from("-c"),
                OsString::from("diff.trustExitCode=false"),
                OsString::from("-c"),
                OsString::from("core.fsmonitor=false"),
            ])
            .args(args)
            .env("GIT_CONFIG_NOSYSTEM", "1")
            .env("GIT_CONFIG_GLOBAL", null_device())
            .env("GIT_CONFIG_SYSTEM", null_device())
            .env("GIT_CONFIG_COUNT", "4")
            .env("GIT_CONFIG_KEY_0", "alias.status")
            .env("GIT_CONFIG_VALUE_0", "")
            .env("GIT_CONFIG_KEY_1", "alias.diff")
            .env("GIT_CONFIG_VALUE_1", "")
            .env("GIT_CONFIG_KEY_2", "alias.log")
            .env("GIT_CONFIG_VALUE_2", "")
            .env("GIT_CONFIG_KEY_3", "alias.show")
            .env("GIT_CONFIG_VALUE_3", "")
            .env("GIT_TERMINAL_PROMPT", "0")
            .env("GIT_ASKPASS", "")
            .env("SSH_ASKPASS", "")
            .env("GCM_INTERACTIVE", "Never")
            .env("GIT_OPTIONAL_LOCKS", "0")
            .env("GIT_NO_REPLACE_OBJECTS", "1")
            .env_remove("GIT_OBJECT_DIRECTORY")
            .env_remove("GIT_ALTERNATE_OBJECT_DIRECTORIES")
            .env("GIT_NO_LAZY_FETCH", "1")
            .env("GIT_PAGER", "cat")
            .env("GIT_EDITOR", ":")
            .env("GIT_SEQUENCE_EDITOR", ":")
            .env("LC_ALL", "C")
            .env("LANG", "C")
            .env("TZ", "UTC");
        command
    }
}

/// 在子进程环境隔离移除 PATH 前解析规范 Git 可执行文件，防止后续命令通过继承状态切换程序身份。
fn resolve_git_program() -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    let names: &[&str] = if cfg!(windows) {
        &["git.exe", "git"]
    } else {
        &["git"]
    };
    for directory in std::env::split_paths(&path) {
        for name in names {
            let candidate = directory.join(name);
            if candidate.is_file()
                && let Ok(canonical) = candidate.canonicalize()
            {
                return Some(canonical);
            }
        }
    }
    None
}

/// 选择平台空设备以关闭 Git 的全局与系统配置输入。
fn null_device() -> &'static str {
    if cfg!(windows) { "NUL" } else { "/dev/null" }
}
