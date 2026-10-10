// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 外部编辑器只接收独立草稿文件与继承的终端流，不承担会话或草稿状态所有权。

use std::env;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

/// 按 VISUAL、EDITOR 顺序解析命令；环境变量保持为 argv，不执行 Shell 展开。
pub(super) fn editor_command() -> Result<Option<Vec<String>>, String> {
    let visual = env::var_os("VISUAL").map(|value| value.to_string_lossy().into_owned());
    let editor = env::var_os("EDITOR").map(|value| value.to_string_lossy().into_owned());
    select_editor(visual.as_deref(), editor.as_deref())
}

/// 纯选择入口让优先级和空配置行为可在不改动进程环境的情况下验证。
pub(super) fn select_editor(
    visual: Option<&str>,
    editor: Option<&str>,
) -> Result<Option<Vec<String>>, String> {
    for value in [visual, editor].into_iter().flatten() {
        if value.trim().is_empty() {
            continue;
        }
        let arguments = split_editor_command(value)?;
        if !arguments.is_empty() {
            return Ok(Some(arguments));
        }
    }
    Ok(None)
}

/// 支持带引号的可执行路径和普通参数，不解释 Shell 运算符或替换表达式。
fn split_editor_command(value: &str) -> Result<Vec<String>, String> {
    let mut args = Vec::new();
    let mut current = String::new();
    let mut quote = None;
    let mut characters = value.chars().peekable();
    while let Some(character) = characters.next() {
        if character == '\\' && quote == Some('"') {
            let mut slash_count = 1;
            while characters.peek() == Some(&'\\') {
                characters.next();
                slash_count += 1;
            }
            if characters.peek() == Some(&'"') {
                current.extend(std::iter::repeat_n('\\', slash_count / 2));
                if slash_count % 2 == 1 {
                    characters.next();
                    current.push('"');
                }
            } else {
                current.extend(std::iter::repeat_n('\\', slash_count));
            }
            continue;
        }
        if matches!(character, '\'' | '"') {
            if quote == Some(character) {
                quote = None;
            } else if quote.is_none() {
                quote = Some(character);
            } else {
                current.push(character);
            }
            continue;
        }
        if character.is_whitespace() && quote.is_none() {
            if !current.is_empty() {
                args.push(std::mem::take(&mut current));
            }
        } else {
            current.push(character);
        }
    }
    if quote.is_some() {
        return Err("编辑器命令中的引号未闭合".into());
    }
    if !current.is_empty() {
        args.push(current);
    }
    Ok(args)
}

/// 临时草稿只在成功创建后持有删除责任，不覆盖任何用户指定路径。
struct EditorTemporaryPath(PathBuf);

impl Drop for EditorTemporaryPath {
    /// 清理失败不覆盖编辑器结果或遮蔽控制器正在报告的主错误。
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// 保留临时路径的清理 owner，同时给控制器只读的编辑器文件名。
pub(super) struct TemporaryEditorFile {
    pub(super) path: PathBuf,
    _cleanup: EditorTemporaryPath,
}

/// 使用独占创建与关闭文件句柄，避免并发覆盖及 Windows 编辑器共享锁冲突。
pub(super) fn create_editor_draft(draft: &str) -> io::Result<TemporaryEditorFile> {
    for _ in 0..3 {
        let path = env::temp_dir().join(format!(
            "ja-draft-{}-{}.md",
            std::process::id(),
            uuid::Uuid::new_v4().simple()
        ));
        match OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(mut file) => {
                let cleanup = EditorTemporaryPath(path.clone());
                file.write_all(draft.as_bytes())?;
                file.flush()?;
                drop(file);
                return Ok(TemporaryEditorFile {
                    path,
                    _cleanup: cleanup,
                });
            }
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error),
        }
    }
    Err(io::Error::other("无法创建唯一的临时编辑文件"))
}

/// 继承终端流运行已配置程序，仅在程序成功退出后接收编辑文本。
pub(super) fn run_editor(command: &[String], path: &Path) -> Result<Option<String>, String> {
    let (program, arguments) = command
        .split_first()
        .ok_or_else(|| "编辑器命令为空".to_owned())?;
    let mut process = Command::new(program);
    process.args(arguments);
    let executable = Path::new(program)
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase();
    if matches!(executable.as_str(), "code" | "code-insiders")
        && !arguments.iter().any(|argument| argument == "--wait")
    {
        process.arg("--wait");
    }
    let status = process
        .arg(path)
        .status()
        .map_err(|error| format!("无法启动编辑器：{error}"))?;
    if !status.success() {
        return Ok(None);
    }
    fs::read_to_string(path)
        .map(Some)
        .map_err(|error| format!("无法读取编辑器结果：{error}"))
}
