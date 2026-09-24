// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::preview::local_file::{
    browser_document_extension, parse_absolute_or_file_target, validate_target,
};
use crate::preview::{PreviewErrorCode, PreviewResolveFileInput};
use std::path::PathBuf;

/// 验证带空格和 Unicode 的绝对路径经 file URL 编解码后保持一致；路径无需落盘。
#[test]
fn absolute_and_file_targets_preserve_windows_unicode_paths() {
    let target = if cfg!(windows) {
        PathBuf::from(r"C:\temp\中文 文件.html")
    } else {
        PathBuf::from("/tmp/中文 文件.html")
    };
    let file_url = url::Url::from_file_path(&target)
        .expect("file URL")
        .to_string();
    let parsed = parse_absolute_or_file_target(&file_url)
        .expect("parse file URL")
        .expect("file target");
    assert_eq!(parsed, target);
    let absolute = target.to_string_lossy();
    assert!(parse_absolute_or_file_target(&absolute)
        .expect("parse absolute")
        .is_some());
    assert_eq!(
        parse_absolute_or_file_target("src/说明.rs").expect("relative target"),
        None
    );
}

/// 在原生 path/URL API 重新解释输入前拒绝格式错误和超长目标。
#[test]
fn invalid_file_targets_have_static_preview_errors() {
    assert_eq!(
        validate_target("").expect_err("empty target").code(),
        PreviewErrorCode::FileTargetInvalid
    );
    assert_eq!(
        parse_absolute_or_file_target("file:///%zz")
            .expect_err("invalid escape")
            .code(),
        PreviewErrorCode::FileTargetInvalid
    );
    assert_eq!(
        parse_absolute_or_file_target("C:relative.txt")
            .expect_err("drive-relative paths are ambiguous")
            .code(),
        PreviewErrorCode::FileTargetInvalid
    );
}

/// 已知浏览器文档扩展名只影响路由，不构成拒绝未知格式的白名单。
#[test]
fn common_browser_document_extensions_are_recognized() {
    assert!(browser_document_extension("svg"));
    assert!(browser_document_extension("pdf"));
    assert!(!browser_document_extension("unknown"));
}

/// DTO serde 字段名与 renderer 的 camelCase 解析契约保持一致。
#[test]
fn resolver_input_rejects_renderer_supplied_workspace_roots() {
    let decoded = serde_json::from_value::<PreviewResolveFileInput>(serde_json::json!({
        "target": "C:/work/说明.txt",
        "workspaceId": "ws_abc",
        "line": 4
    }))
    .expect("camelCase input");
    assert_eq!(decoded.workspace_id.as_deref(), Some("ws_abc"));
    assert!(
        serde_json::from_value::<PreviewResolveFileInput>(serde_json::json!({
            "target": "src/main.rs",
            "workspaceRoot": "C:/untrusted"
        }))
        .is_err()
    );
}
