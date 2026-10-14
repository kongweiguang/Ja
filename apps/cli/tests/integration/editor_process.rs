// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#![allow(dead_code)]

#[path = "../../src/controller/external_editor.rs"]
mod external_editor;

use std::ffi::OsString;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

static PROCESS_ENV_LOCK: Mutex<()> = Mutex::new(());

/// 每个子进程 fixture 使用独立临时名，避免并行 Cargo 测试共享脚本或草稿。
fn unique_path(extension: &str) -> PathBuf {
    std::env::temp_dir().join(format!(
        "ja-cli-editor-{}-{}.{}",
        std::process::id(),
        uuid::Uuid::new_v4().simple(),
        extension
    ))
}

/// 测试脚本始终由 RAII owner 删除，失败断言也不会遗留可执行内容。
struct ScriptCleanup(PathBuf);

impl Drop for ScriptCleanup {
    /// 子进程已退出后回收测试专用脚本，避免在系统临时目录留测试命令。
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// PATH/PATHEXT 的进程级修改通过同一把锁串行化，并在测试结束或 panic 时还原。
#[cfg(windows)]
struct EditorSearchPath {
    path: Option<OsString>,
    pathext: Option<OsString>,
    capture_path: Option<OsString>,
}

#[cfg(windows)]
impl EditorSearchPath {
    /// 把仅供测试的 shim 目录置于 PATH 首位，验证 Windows 的 PATHEXT 搜索契约。
    #[cfg(windows)]
    fn prepend(directory: &std::path::Path, capture_path: &std::path::Path) -> Self {
        let previous = Self {
            path: std::env::var_os("PATH"),
            pathext: std::env::var_os("PATHEXT"),
            capture_path: std::env::var_os("JA_CLI_EDITOR_ARGV_CAPTURE"),
        };
        let mut paths = vec![directory.to_path_buf()];
        if let Some(existing) = previous.path.as_ref() {
            paths.extend(std::env::split_paths(existing));
        }
        let joined = std::env::join_paths(paths).expect("join isolated PATH");
        // SAFETY: tests that execute processes serialize all environment access with PROCESS_ENV_LOCK.
        unsafe {
            std::env::set_var("PATH", joined);
            std::env::set_var("PATHEXT", ".COM;.EXE;.BAT;.CMD");
            std::env::set_var("JA_CLI_EDITOR_ARGV_CAPTURE", capture_path);
        }
        previous
    }
}

#[cfg(windows)]
impl Drop for EditorSearchPath {
    /// 只恢复本测试暂存的 PATH/PATHEXT，确保进程全局环境不泄漏到后续测试。
    fn drop(&mut self) {
        #[cfg(windows)]
        // SAFETY: every process test takes PROCESS_ENV_LOCK before using environment variables.
        unsafe {
            if let Some(path) = self.path.take() {
                std::env::set_var("PATH", path);
            } else {
                std::env::remove_var("PATH");
            }
            if let Some(pathext) = self.pathext.take() {
                std::env::set_var("PATHEXT", pathext);
            } else {
                std::env::remove_var("PATHEXT");
            }
            if let Some(capture_path) = self.capture_path.take() {
                std::env::set_var("JA_CLI_EDITOR_ARGV_CAPTURE", capture_path);
            } else {
                std::env::remove_var("JA_CLI_EDITOR_ARGV_CAPTURE");
            }
        }
    }
}

/// 仅回收本测试已命名的临时 shim 文件，不递归删除可能被其他进程使用的目录树。
#[cfg(windows)]
struct EditorShimCleanup(PathBuf);

#[cfg(windows)]
impl Drop for EditorShimCleanup {
    /// 失败断言与进程异常后仍清理该测试创建的三个确定文件和空目录。
    fn drop(&mut self) {
        for name in ["ja测试编辑器.cmd", "读取参数.ps1", "argv.json"] {
            let _ = fs::remove_file(self.0.join(name));
        }
        let _ = fs::remove_dir(&self.0);
    }
}

/// 通过系统 Shell 执行小脚本，覆盖真实 argv 传递与进程退出状态。
fn editor_script(body: &str) -> (ScriptCleanup, Vec<String>) {
    let path = unique_path(if cfg!(windows) { "ps1" } else { "sh" });
    #[cfg(windows)]
    let (contents, command) = (
        format!("param([string]$DraftPath)\n{body}\n"),
        vec![
            "powershell.exe".to_owned(),
            "-NoProfile".to_owned(),
            "-NonInteractive".to_owned(),
            "-ExecutionPolicy".to_owned(),
            "Bypass".to_owned(),
            "-File".to_owned(),
            path.to_string_lossy().into_owned(),
        ],
    );
    #[cfg(unix)]
    let (contents, command) = (
        format!("#!/bin/sh\n{body}\n"),
        vec!["sh".to_owned(), path.to_string_lossy().into_owned()],
    );
    fs::write(&path, contents).expect("write editor fixture");
    (ScriptCleanup(path), command)
}

/// 成功退出时采用编辑器写回正文，完成后由临时文件 owner 删除独立草稿。
#[test]
fn successful_editor_result_replaces_draft_and_cleans_temporary_file() {
    let _environment = PROCESS_ENV_LOCK.lock().unwrap();
    #[cfg(windows)]
    let (script, command) =
        editor_script("[System.IO.File]::WriteAllText($DraftPath, 'updated draft')");
    #[cfg(unix)]
    let (script, command) = editor_script("printf 'updated draft' > \"$1\"");
    let draft = external_editor::create_editor_draft("original draft").expect("create draft");
    let path = draft.path.clone();

    assert_eq!(
        external_editor::run_editor(&command, &path).expect("run editor"),
        Some("updated draft".to_owned())
    );
    drop(draft);
    assert!(!path.exists(), "temporary editor draft must be removed");
    drop(script);
}

/// 非零退出视作编辑器取消，原草稿保持可恢复，临时文件仍在作用域结束时清理。
#[test]
fn nonzero_editor_exit_preserves_original_draft_and_cleans_temporary_file() {
    let _environment = PROCESS_ENV_LOCK.lock().unwrap();
    let (script, command) = editor_script("exit 7");
    let draft = external_editor::create_editor_draft("keep this draft").expect("create draft");
    let path = draft.path.clone();

    assert_eq!(
        external_editor::run_editor(&command, &path).expect("run editor"),
        None
    );
    assert_eq!(
        fs::read_to_string(&path).expect("read preserved draft"),
        "keep this draft"
    );
    drop(draft);
    assert!(!path.exists(), "temporary editor draft must be removed");
    drop(script);
}

/// 启动失败不改写原草稿，并通过同一个清理 owner 回收临时路径。
#[test]
fn editor_launch_failure_preserves_original_draft_and_cleans_temporary_file() {
    let _environment = PROCESS_ENV_LOCK.lock().unwrap();
    let draft = external_editor::create_editor_draft("keep this draft").expect("create draft");
    let path = draft.path.clone();
    let missing_command = vec![format!("ja-missing-editor-{}", uuid::Uuid::new_v4())];

    assert!(external_editor::run_editor(&missing_command, &path).is_err());
    assert_eq!(
        fs::read_to_string(&path).expect("read preserved draft"),
        "keep this draft"
    );
    drop(draft);
    assert!(!path.exists(), "temporary editor draft must be removed");
}

/// PATH/PATHEXT 可定位含空格和中文的 .cmd shim，且参数按原argv及最终草稿路径传递。
#[cfg(windows)]
#[test]
fn windows_cmd_shim_resolves_from_pathext_and_preserves_unicode_arguments() {
    let _environment = PROCESS_ENV_LOCK.lock().unwrap();
    let directory = std::env::temp_dir().join(format!(
        "ja cli 编辑器 shim {}",
        uuid::Uuid::new_v4().simple()
    ));
    fs::create_dir(&directory).expect("create shim directory");
    let cleanup = EditorShimCleanup(directory.clone());
    let shim = directory.join("ja测试编辑器.cmd");
    let recorder = directory.join("读取参数.ps1");
    let result_path = directory.join("argv.json");
    let recorder_contents = "param([string]$First, [string]$Second, [string]$Draft)\n$line = ConvertTo-Json -InputObject @($First, $Second, $Draft) -Compress\n[System.IO.File]::WriteAllText($env:JA_CLI_EDITOR_ARGV_CAPTURE, $line, [System.Text.UTF8Encoding]::new($false))\n";
    fs::write(&recorder, recorder_contents).expect("write Unicode argument recorder");
    fs::write(
        &shim,
        format!(
            "@echo off\r\npowershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"{}\" \"%~1\" \"%~2\" \"%~3\"\r\nexit /b %errorlevel%\r\n",
            recorder.display()
        ),
    )
    .expect("write command shim");
    let _search_path = EditorSearchPath::prepend(&directory, &result_path);
    let draft = external_editor::create_editor_draft("original draft").expect("create draft");
    let draft_path = draft.path.to_string_lossy().into_owned();
    let command = vec![
        "ja测试编辑器".to_owned(),
        "first argument with spaces".to_owned(),
        "中文参数".to_owned(),
    ];

    assert_eq!(
        external_editor::run_editor(&command, &draft.path).expect("run resolved .cmd editor"),
        Some("original draft".to_owned())
    );
    let captured: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&result_path).expect("read captured argv"))
            .expect("parse captured argv");
    assert_eq!(
        captured,
        serde_json::json!(["first argument with spaces", "中文参数", draft_path])
    );

    drop(draft);
    drop(_search_path);
    drop(cleanup);
    assert!(!directory.exists(), "test shim directory must be removed");
}
