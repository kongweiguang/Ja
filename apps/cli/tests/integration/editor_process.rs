// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#![allow(dead_code)]

#[path = "../../src/controller/external_editor.rs"]
mod external_editor;

use std::fs;
use std::path::PathBuf;

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
