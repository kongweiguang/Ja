// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use std::sync::{Arc, Mutex};

struct FakeResolver {
    program: PathBuf,
}

impl ExecutableResolver for FakeResolver {
    /// 返回确定的 fake executable，测试永不启动真实 IDE 或依赖本机安装状态。
    fn resolve(&self, _target: OpenWithTarget) -> Result<PathBuf, OpenTargetUnavailableReason> {
        Ok(self.program.clone())
    }
}

#[derive(Clone, Default)]
struct RecordingLauncher {
    plans: Arc<Mutex<Vec<LaunchPlan>>>,
}

impl ProcessLauncher for RecordingLauncher {
    /// 记录精确结构化 plan 且不执行 IO，用于验证参数边界与重复调用。
    fn launch(&self, plan: &LaunchPlan) -> Result<(), ()> {
        self.plans
            .lock()
            .expect("recording launcher lock")
            .push(plan.clone());
        Ok(())
    }
}

fn fixture_workspace() -> (tempfile_like::TempDir, WorkspaceHandle) {
    let root = tempfile_like::TempDir::create();
    fs::write(root.path.join("main.rs"), "fn main() {}\n").expect("fixture file");
    let registry = crate::workspace::WorkspaceRegistry::default();
    let info = registry.register(&root.path).expect("fixture root");
    (root, registry.get(info.id).expect("fixture handle"))
}

/// 测试自有的最小临时目录避免为 fake 进程断言引入额外依赖，清理由 fixture 自身负责。
mod tempfile_like {
    use super::*;

    pub struct TempDir {
        pub path: PathBuf,
    }

    impl TempDir {
        pub fn create() -> Self {
            let path = std::env::temp_dir().join(format!("ja-open-with-{}", uuid::Uuid::new_v4()));
            fs::create_dir_all(&path).expect("temp root");
            Self { path }
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }
}

/// 验证每个公开 target 都有稳定且唯一的 wire spelling，防止 UI 与 resolver 列表漂移。
#[test]
fn target_catalog_is_closed_and_unique() {
    let names = OpenWithTarget::ALL
        .into_iter()
        .map(|target| serde_json::to_string(&target).expect("target json"))
        .collect::<Vec<_>>();
    let unique = names.iter().collect::<std::collections::HashSet<_>>();
    assert_eq!(names.len(), unique.len());
    assert_eq!(OpenWithTarget::parse("powershell"), None);
    assert_eq!(
        OpenWithTarget::parse("file_explorer"),
        Some(OpenWithTarget::FileExplorer)
    );
}

/// 证明 traversal、绝对路径与 link alias 均在 launch 副作用前失败关闭。
#[test]
fn resolve_open_entry_rejects_invalid_and_link_paths() {
    let (_root, workspace) = fixture_workspace();
    for path in ["../escape", "./main.rs", "C:/private", "main\\rs"] {
        assert!(matches!(
            resolve_open_entry(&workspace, path),
            Err(OpenError::Workspace(WorkspaceError::InvalidRelativePath))
        ));
    }
    let (root, workspace) = fixture_workspace();
    let outside = tempfile_like::TempDir::create();
    fs::write(outside.path.join("secret.txt"), "secret").expect("outside file");
    let alias = root.path.join("alias");
    if create_directory_alias(&outside.path, &alias) {
        assert!(matches!(
            resolve_open_entry(&workspace, "alias/secret.txt"),
            Err(OpenError::Workspace(
                WorkspaceError::OutsideWorkspace
                    | WorkspaceError::PathChanged
                    | WorkspaceError::LinkNotAllowed
            ))
        ));
    }
}

/// 测试主机允许时创建平台原生目录 alias；不支持时保留其余路径策略断言。
fn create_directory_alias(target: &Path, alias: &Path) -> bool {
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(target, alias).is_ok()
    }
    #[cfg(windows)]
    {
        std::os::windows::fs::symlink_dir(target, alias).is_ok()
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (target, alias);
        false
    }
}

/// 捕获文件 reveal 的精确 argv/cwd，并证明快速双击仍是两次有界结构化 launch，
/// 不会退化为通用 command pipe。
#[test]
fn fake_launcher_receives_safe_file_reveal_and_repeat_clicks() {
    let (_root, workspace) = fixture_workspace();
    let resolver = FakeResolver {
        program: PathBuf::from("C:\\Windows\\explorer.exe"),
    };
    let launcher = RecordingLauncher::default();
    let first = open_with(
        &resolver,
        &launcher,
        OpenWithTarget::FileExplorer,
        &workspace,
        "main.rs",
    )
    .expect("first open");
    let second = open_with(
        &resolver,
        &launcher,
        OpenWithTarget::FileExplorer,
        &workspace,
        "main.rs",
    )
    .expect("second open");
    assert_eq!(first.target, OpenWithTarget::FileExplorer);
    assert_eq!(second.target, OpenWithTarget::FileExplorer);
    let plans = launcher.plans.lock().expect("plans");
    assert_eq!(plans.len(), 2);
    assert_eq!(plans[0].target, OpenWithTarget::FileExplorer);
    assert_eq!(plans[0].relative_path, "main.rs");
    assert!(plans[0].args[0].to_string_lossy().starts_with("/select,"));
    assert_eq!(plans[0].args, plans[1].args);
    assert_eq!(plans[0].cwd, plans[1].cwd);
}

/// Visual Studio 目录打开保持确定性：仅准入唯一直接 solution/project，歧义目录失败关闭。
#[test]
fn visual_studio_directory_requires_one_documented_solution_or_project() {
    let (root, workspace) = fixture_workspace();
    let resolver = FakeResolver {
        program: PathBuf::from("C:\\Program Files\\Microsoft Visual Studio\\devenv.exe"),
    };
    let launcher = RecordingLauncher::default();
    assert!(matches!(
        open_with(
            &resolver,
            &launcher,
            OpenWithTarget::VisualStudio,
            &workspace,
            "",
        ),
        Err(OpenError::NotOpenable)
    ));
    fs::write(
        root.path.join("ja.sln"),
        "Microsoft Visual Studio Solution File",
    )
    .expect("solution");
    let result = open_with(
        &resolver,
        &launcher,
        OpenWithTarget::VisualStudio,
        &workspace,
        "",
    )
    .expect("solution open");
    assert_eq!(result.entry_kind, EntryKind::Directory);
    let plans = launcher.plans.lock().expect("plans");
    assert!(
        plans.last().expect("solution plan").args[0]
            .to_string_lossy()
            .ends_with("ja.sln")
    );
}

/// 验证不支持的 target discovery 只返回稳定原因，不返回 executable path 或平台错误文本。
#[test]
fn unavailable_reason_is_path_free() {
    let reason = OpenTargetUnavailableReason::NotInstalled;
    let value = serde_json::to_value(reason).expect("reason json");
    assert_eq!(value, "not_installed");
    assert!(!value.to_string().contains("\\"));
}
