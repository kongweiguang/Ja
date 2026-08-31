// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 单个受信 Workspace 的受控外部应用打开能力。
//
// WebView 只能选择闭集 target 与 Workspace-relative 路径；原生代码负责解析 executable、
// 重验 Workspace 路径并以结构化参数启动。IPC 合同不包含 executable path、shell 文本
// 或继承的 command line。

use super::registry::{ResolvedPath, WorkspaceHandle, entry_kind, is_reparse_point};
use crate::workspace::WorkspaceError;
use crate::workspace::application::WorkspaceOpenPort;
use crate::workspace::domain::{
    EntryKind, OpenError, OpenResult, OpenTargetAvailability, OpenTargetUnavailableReason,
    OpenWithTarget,
};
use std::ffi::OsStr;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

const MAX_PATH_ENTRIES: usize = 128;
const MAX_PATHEXT_ENTRIES: usize = 16;
const MAX_TOOLBOX_DEPTH: usize = 5;
const MAX_TOOLBOX_ENTRIES: usize = 256;

/// 原生 resolver 输出保持私有，跨 IPC 的只有不透明可用性 DTO；测试通过确定性 fake 注入选择结果。
pub(crate) trait ExecutableResolver {
    fn resolve(&self, target: OpenWithTarget) -> Result<PathBuf, OpenTargetUnavailableReason>;
}

/// 原生进程启动 seam 保持窄接口，使参数测试无需启动真实 IDE，生产仍直接使用 `std::process`。
pub(crate) trait ProcessLauncher {
    fn launch(&self, plan: &LaunchPlan) -> Result<(), ()>;
}

/// 完整解析的 launch plan 只在原生层存在，任何字段都不得序列化到 WebView。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct LaunchPlan {
    pub(crate) target: OpenWithTarget,
    pub(crate) program: PathBuf,
    pub(crate) args: Vec<OsString>,
    pub(crate) cwd: PathBuf,
    pub(crate) relative_path: String,
    pub(crate) entry_kind: EntryKind,
}

/// 进程创建使用结构化参数、detached 与空 stdio，不经过 shell 或用户可控命令文本。
struct NativeProcessLauncher;

impl ProcessLauncher for NativeProcessLauncher {
    /// 启动已解析的闭集程序后立即释放 child handle，使桌面 UI 不绑定 IDE 生命周期或输出流。
    fn launch(&self, plan: &LaunchPlan) -> Result<(), ()> {
        let mut command = Command::new(&plan.program);
        command
            .args(&plan.args)
            .current_dir(&plan.cwd)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
            const DETACHED_PROCESS: u32 = 0x0000_0008;
            command.creation_flags(CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS);
        }
        command.spawn().map(|_| ()).map_err(|_| ())
    }
}

/// resolver 只从 PATH/PATHEXT 与有界 Windows 常见安装根选择闭集候选，不向 UI 暴露命中路径。
struct NativeExecutableResolver;

impl ExecutableResolver for NativeExecutableResolver {
    /// 返回单个直接 executable 候选或稳定不可用原因，不把操作系统诊断带出 infrastructure。
    fn resolve(&self, target: OpenWithTarget) -> Result<PathBuf, OpenTargetUnavailableReason> {
        if !cfg!(windows) && matches!(target, OpenWithTarget::FileExplorer | OpenWithTarget::Wsl) {
            return Err(OpenTargetUnavailableReason::UnsupportedPlatform);
        }

        for candidate in explicit_candidates(target) {
            if usable_executable(&candidate) {
                return Ok(candidate);
            }
        }
        if let Some(candidate) = find_on_path(target) {
            return Ok(candidate);
        }
        if let Some(candidate) = find_jetbrains_toolbox(target) {
            return Ok(candidate);
        }
        Err(OpenTargetUnavailableReason::NotInstalled)
    }
}

/// 在选择进程候选前解析当前绑定 Workspace，并复用既有 anti-alias guard。
pub(crate) fn resolve_open_entry(
    workspace: &WorkspaceHandle,
    relative_path: &str,
) -> Result<(ResolvedPath, EntryKind), OpenError> {
    if relative_path.len() > 4096 || relative_path.chars().any(char::is_control) {
        return Err(OpenError::InvalidInput);
    }
    let resolved = workspace.resolve_guard(relative_path, None)?;
    workspace.verify_resolved(&resolved, None)?;
    let metadata =
        fs::symlink_metadata(&resolved.path).map_err(|error| WorkspaceError::io("stat", error))?;
    let kind = entry_kind(&metadata);
    if !matches!(kind, EntryKind::File | EntryKind::Directory) {
        return Err(OpenError::NotOpenable);
    }
    Ok((resolved, kind))
}

/// 只从闭集 target 构造参数；唯一动态路径已由调用方完成 canonical containment。
fn build_launch_plan<R: ExecutableResolver>(
    resolver: &R,
    target: OpenWithTarget,
    workspace: &WorkspaceHandle,
    resolved: &ResolvedPath,
    relative_path: &str,
    entry_kind: EntryKind,
) -> Result<LaunchPlan, OpenError> {
    let program = resolver
        .resolve(target)
        .map_err(|_| OpenError::TargetUnavailable)?;
    let path = &resolved.path;
    let (args, cwd) = match target {
        OpenWithTarget::FileExplorer => {
            #[cfg(windows)]
            let args = if entry_kind == EntryKind::File {
                vec![OsString::from(format!("/select,{}", path.display()))]
            } else {
                vec![path.as_os_str().to_os_string()]
            };
            #[cfg(not(windows))]
            let args = vec![path.as_os_str().to_os_string()];
            (args, workspace.root_path().to_path_buf())
        }
        OpenWithTarget::Terminal => {
            #[cfg(windows)]
            let args = vec![
                OsString::from("-d"),
                terminal_directory(path, entry_kind).into_os_string(),
            ];
            #[cfg(not(windows))]
            let args = Vec::new();
            (args, terminal_directory(path, entry_kind))
        }
        OpenWithTarget::GitBash => {
            let directory = terminal_directory(path, entry_kind);
            (
                vec![OsString::from(format!("--cd={}", directory.display()))],
                directory,
            )
        }
        OpenWithTarget::Wsl => {
            #[cfg(windows)]
            let args = vec![
                OsString::from("--cd"),
                terminal_directory(path, entry_kind).into_os_string(),
            ];
            #[cfg(not(windows))]
            let args = Vec::new();
            (args, terminal_directory(path, entry_kind))
        }
        OpenWithTarget::Vscode
        | OpenWithTarget::Zed
        | OpenWithTarget::Pycharm
        | OpenWithTarget::Webstorm => (
            vec![path.as_os_str().to_os_string()],
            workspace.root_path().to_path_buf(),
        ),
        OpenWithTarget::VisualStudio => {
            let visual_studio_path = visual_studio_open_path(path, entry_kind)?;
            (
                vec![visual_studio_path.as_os_str().to_os_string()],
                workspace.root_path().to_path_buf(),
            )
        }
    };
    Ok(LaunchPlan {
        target,
        program,
        args,
        cwd,
        relative_path: relative_path.to_owned(),
        entry_kind,
    })
}

/// Terminal 不能展示文件，因此文件目标使用 parent 作为 cwd；目录与空路径保留自身位置。
fn terminal_directory(path: &Path, entry_kind: EntryKind) -> PathBuf {
    if entry_kind == EntryKind::File {
        path.parent().unwrap_or(path).to_path_buf()
    } else {
        path.to_path_buf()
    }
}

/// Visual Studio 首参数按公开合同只能是 solution、project 或 file；目录目标仅在存在
/// 唯一明确的直接 solution/project 时准入，不猜测未公开的文件夹打开行为。
fn visual_studio_open_path(path: &Path, entry_kind: EntryKind) -> Result<PathBuf, OpenError> {
    if entry_kind == EntryKind::File {
        return Ok(path.to_path_buf());
    }
    let mut solutions = Vec::new();
    let mut projects = Vec::new();
    let entries = fs::read_dir(path)
        .map_err(|error| WorkspaceError::io("scan", error))?
        .take(64);
    for entry in entries.flatten() {
        let candidate = entry.path();
        let Ok(metadata) = fs::symlink_metadata(&candidate) else {
            continue;
        };
        if !metadata.is_file() || metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
            continue;
        }
        let extension = candidate
            .extension()
            .and_then(OsStr::to_str)
            .map(|value| value.to_ascii_lowercase());
        match extension.as_deref() {
            Some("sln") | Some("slnx") => solutions.push(candidate),
            Some("csproj") | Some("vcxproj") | Some("vbproj") | Some("fsproj") => {
                projects.push(candidate)
            }
            _ => {}
        }
    }
    solutions.sort();
    projects.sort();
    if solutions.len() == 1 {
        return Ok(solutions.remove(0));
    }
    if solutions.is_empty() && projects.len() == 1 {
        return Ok(projects.remove(0));
    }
    Err(OpenError::NotOpenable)
}

/// 完整 guarded launch 流程通过可注入 resolver/launcher seam 验证精确 argv 与重复点击，
/// 测试不会产生真实外部进程。
pub(crate) fn open_with<R: ExecutableResolver, L: ProcessLauncher>(
    resolver: &R,
    launcher: &L,
    target: OpenWithTarget,
    workspace: &WorkspaceHandle,
    relative_path: &str,
) -> Result<OpenResult, OpenError> {
    let (resolved, entry_kind) = resolve_open_entry(workspace, relative_path)?;
    let plan = build_launch_plan(
        resolver,
        target,
        workspace,
        &resolved,
        relative_path,
        entry_kind,
    )?;
    // executable discovery 会访问文件系统，因此在外部进程副作用前再次核对同一 resolved identity。
    workspace.verify_resolved(&resolved, None)?;
    launcher
        .launch(&plan)
        .map_err(|_| OpenError::LaunchFailed)?;
    Ok(OpenResult {
        target,
        relative_path: relative_path.to_owned(),
        entry_kind,
    })
}

/// 原生 Open port 固定绑定一个 Workspace handle，不暴露 resolver 或 process launcher。
pub(crate) struct NativeWorkspaceOpenPort {
    workspace: WorkspaceHandle,
}

impl NativeWorkspaceOpenPort {
    /// RuntimeHost admission 后注入不可变 handle，避免 discovery 与 launch 使用不同根目录。
    pub(crate) fn new(workspace: WorkspaceHandle) -> Self {
        Self { workspace }
    }
}

impl WorkspaceOpenPort for NativeWorkspaceOpenPort {
    /// 查询闭集目标时先复核 Workspace root，随后丢弃所有 executable path。
    fn targets(&self) -> Result<Vec<OpenTargetAvailability>, OpenError> {
        let root = self.workspace.resolve_guard("", Some(true))?;
        self.workspace.verify_resolved(&root, Some(true))?;
        let resolver = NativeExecutableResolver;
        Ok(OpenWithTarget::ALL
            .into_iter()
            .map(|target| match resolver.resolve(target) {
                Ok(_) => OpenTargetAvailability {
                    target,
                    available: true,
                    reason: None,
                },
                Err(reason) => OpenTargetAvailability {
                    target,
                    available: false,
                    reason: Some(reason),
                },
            })
            .collect())
    }

    /// Launch 使用固定 resolver/launcher，并在进程创建前再次复核 resolved identity。
    fn open(&self, target: OpenWithTarget, relative_path: String) -> Result<OpenResult, OpenError> {
        open_with(
            &NativeExecutableResolver,
            &NativeProcessLauncher,
            target,
            &self.workspace,
            &relative_path,
        )
    }
}

/// 每个 target 只构造小型显式候选集；Windows 路径仅来自已知安装根，不来自 WebView 输入。
fn explicit_candidates(target: OpenWithTarget) -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    let local_app_data = std::env::var_os("LOCALAPPDATA").map(PathBuf::from);
    let program_files = std::env::var_os("ProgramFiles").map(PathBuf::from);
    let program_files_x86 = std::env::var_os("ProgramFiles(x86)").map(PathBuf::from);
    let system_root = std::env::var_os("SystemRoot").map(PathBuf::from);

    match target {
        OpenWithTarget::Vscode => {
            if let Some(root) = local_app_data {
                candidates.push(
                    root.join("Programs")
                        .join("Microsoft VS Code")
                        .join("Code.exe"),
                );
                candidates.push(
                    root.join("Programs")
                        .join("Microsoft VS Code Insiders")
                        .join("Code - Insiders.exe"),
                );
            }
            for root in [program_files, program_files_x86].into_iter().flatten() {
                candidates.push(root.join("Microsoft VS Code").join("Code.exe"));
            }
        }
        OpenWithTarget::VisualStudio => {
            for root in [program_files, program_files_x86].into_iter().flatten() {
                for edition in ["Community", "Professional", "Enterprise", "Preview"] {
                    candidates.push(
                        root.join("Microsoft Visual Studio")
                            .join("2022")
                            .join(edition)
                            .join("Common7")
                            .join("IDE")
                            .join("devenv.exe"),
                    );
                }
            }
        }
        OpenWithTarget::Zed => {
            if let Some(root) = local_app_data {
                candidates.push(root.join("Programs").join("Zed").join("zed.exe"));
            }
            if let Some(root) = program_files {
                candidates.push(root.join("Zed").join("zed.exe"));
            }
        }
        OpenWithTarget::FileExplorer => {
            if let Some(root) = system_root {
                candidates.push(root.join("explorer.exe"));
            }
        }
        OpenWithTarget::Terminal => {
            if let Some(root) = local_app_data {
                candidates.push(root.join("Microsoft").join("WindowsApps").join("wt.exe"));
            }
            if let Some(root) = system_root {
                candidates.push(root.join("System32").join("wt.exe"));
            }
        }
        OpenWithTarget::GitBash => {
            for root in [program_files, program_files_x86].into_iter().flatten() {
                candidates.push(root.join("Git").join("git-bash.exe"));
            }
        }
        OpenWithTarget::Wsl => {
            if let Some(root) = system_root {
                candidates.push(root.join("System32").join("wsl.exe"));
            }
        }
        OpenWithTarget::Pycharm | OpenWithTarget::Webstorm => {}
    }
    candidates
}

/// PATH 搜索使用有界 PATHEXT 展开；脚本 launcher 会要求 shell，超出 adapter 安全合同，
/// 因而必须跳过。
fn find_on_path(target: OpenWithTarget) -> Option<PathBuf> {
    let names = path_names(target);
    let directories = std::env::var_os("PATH")
        .map(|value| std::env::split_paths(&value).collect::<Vec<_>>())
        .unwrap_or_default();
    let extensions = pathextensions();
    for directory in directories.into_iter().take(MAX_PATH_ENTRIES) {
        for name in names {
            let direct = directory.join(name);
            if usable_executable(&direct) {
                return Some(direct);
            }
            if Path::new(name).extension().is_none() {
                for extension in &extensions {
                    let candidate = directory.join(format!("{name}{extension}"));
                    if usable_executable(&candidate) {
                        return Some(candidate);
                    }
                }
            }
        }
    }
    None
}

/// 为每个闭集 target 提供固定原生名称，PATH discovery 不接受动态产品名。
fn path_names(target: OpenWithTarget) -> &'static [&'static str] {
    match target {
        OpenWithTarget::Vscode => &["Code.exe", "code.exe", "code"],
        OpenWithTarget::VisualStudio => &["devenv.exe", "devenv"],
        OpenWithTarget::Zed => &["zed.exe", "zed"],
        OpenWithTarget::FileExplorer => &["explorer.exe", "explorer"],
        OpenWithTarget::Terminal => &["wt.exe", "wt"],
        OpenWithTarget::GitBash => &["git-bash.exe", "git-bash"],
        OpenWithTarget::Wsl => &["wsl.exe", "wsl"],
        OpenWithTarget::Pycharm => &["pycharm64.exe", "pycharm.exe", "pycharm64", "pycharm"],
        OpenWithTarget::Webstorm => &["webstorm64.exe", "webstorm.exe", "webstorm64", "webstorm"],
    }
}

/// PATHEXT 只读取小型有界集合，异常环境不能制造无界 discovery 循环或把脚本变成 shell command。
fn pathextensions() -> Vec<String> {
    std::env::var_os("PATHEXT")
        .into_iter()
        .flat_map(|value| {
            value
                .to_string_lossy()
                .split(';')
                .map(str::to_owned)
                .collect::<Vec<_>>()
        })
        .take(MAX_PATHEXT_ENTRIES)
        .filter(|extension| {
            let lower = extension.to_ascii_lowercase();
            matches!(lower.as_str(), ".exe" | ".com")
        })
        .collect()
}

/// 候选进入 `Command::new` 前拒绝 symlink/reparse alias 与非文件，确保 discovery root 可信。
fn usable_executable(path: &Path) -> bool {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return false;
    };
    if !metadata.is_file() || metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
        return false;
    }
    #[cfg(windows)]
    {
        let extension = path
            .extension()
            .and_then(OsStr::to_str)
            .map(|value| value.to_ascii_lowercase());
        if !matches!(extension.as_deref(), Some("exe") | Some("com")) {
            return false;
        }
    }
    true
}

/// 只搜索有界 JetBrains Toolbox 根且不跟随 link/reparse point；产品专用 executable 名称
/// 防止跨产品误选。
fn find_jetbrains_toolbox(target: OpenWithTarget) -> Option<PathBuf> {
    let product_names: &[&str] = match target {
        OpenWithTarget::Pycharm => &["pycharm"],
        OpenWithTarget::Webstorm => &["webstorm"],
        _ => return None,
    };
    let executable_names = path_names(target);
    let local_app_data = std::env::var_os("LOCALAPPDATA").map(PathBuf::from);
    let roots = local_app_data.into_iter().flat_map(|root| {
        [
            root.join("JetBrains").join("Toolbox").join("apps"),
            root.join("JetBrains").join("Installations"),
        ]
    });
    for root in roots {
        if let Some(candidate) = bounded_toolbox_search(&root, product_names, executable_names) {
            return Some(candidate);
        }
    }
    None
}

/// 对单个 Toolbox 根执行确定且有界的广度优先扫描，深度与条目预算均不可扩大。
fn bounded_toolbox_search(
    root: &Path,
    product_names: &[&str],
    executable_names: &[&str],
) -> Option<PathBuf> {
    let root_metadata = fs::symlink_metadata(root).ok()?;
    if !root_metadata.is_dir()
        || root_metadata.file_type().is_symlink()
        || is_reparse_point(&root_metadata)
    {
        return None;
    }
    let mut queue = vec![(root.to_path_buf(), 0_usize)];
    let mut inspected = 0_usize;
    while let Some((directory, depth)) = queue.pop() {
        if depth > MAX_TOOLBOX_DEPTH {
            continue;
        }
        let mut entries = fs::read_dir(&directory)
            .ok()?
            .filter_map(Result::ok)
            .take(MAX_TOOLBOX_ENTRIES.saturating_sub(inspected))
            .collect::<Vec<_>>();
        inspected = inspected.saturating_add(entries.len());
        entries.sort_by_key(|entry| entry.file_name());
        for entry in entries {
            let path = entry.path();
            let Ok(metadata) = fs::symlink_metadata(&path) else {
                continue;
            };
            if metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
                continue;
            }
            if metadata.is_file() {
                continue;
            }
            if !metadata.is_dir() {
                continue;
            }
            let name = path.to_string_lossy().to_ascii_lowercase();
            if product_names.iter().any(|product| name.contains(product)) {
                for executable in executable_names {
                    let candidate = path.join("bin").join(executable);
                    if usable_executable(&candidate) {
                        return Some(candidate);
                    }
                }
            }
            if depth < MAX_TOOLBOX_DEPTH && inspected < MAX_TOOLBOX_ENTRIES {
                queue.push((path, depth.saturating_add(1)));
            }
        }
        if inspected >= MAX_TOOLBOX_ENTRIES {
            break;
        }
    }
    None
}
