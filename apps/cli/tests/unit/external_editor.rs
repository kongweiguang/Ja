// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#![allow(dead_code)]

#[path = "../../src/controller/external_editor.rs"]
mod external_editor;

/// VISUAL 命令优先，且引号里的可执行路径按单个 argv 保留。
#[test]
fn visual_editor_precedes_editor_and_preserves_quoted_path() {
    let selected = external_editor::select_editor(
        Some(r#""C:\Program Files\VS Code\Code.exe" --reuse-window"#),
        Some("nano"),
    )
    .expect("parse VISUAL")
    .expect("configured editor");

    assert_eq!(
        selected,
        vec![
            r"C:\Program Files\VS Code\Code.exe".to_owned(),
            "--reuse-window".to_owned()
        ]
    );
    assert_eq!(
        external_editor::select_editor(Some(r#"editor "a\"b""#), None),
        Ok(Some(vec!["editor".to_owned(), "a\"b".to_owned()]))
    );
}

/// 空的 VISUAL 不遮蔽 EDITOR，两个变量均未设置时明确表示不可用。
#[test]
fn empty_visual_falls_back_to_editor_and_missing_configuration_is_none() {
    assert_eq!(
        external_editor::select_editor(Some("  "), Some("nano")),
        Ok(Some(vec!["nano".to_owned()]))
    );
    assert_eq!(external_editor::select_editor(None, None), Ok(None));
}

/// 不完整的 argv 引号应报配置错误，且不能把损坏值拼成 Shell 命令运行。
#[test]
fn malformed_visual_command_is_rejected_without_shell_expansion() {
    let error = external_editor::select_editor(Some("editor 'unfinished"), Some("nano"))
        .expect_err("malformed VISUAL must be rejected");

    assert!(error.contains("引号未闭合"));
}
