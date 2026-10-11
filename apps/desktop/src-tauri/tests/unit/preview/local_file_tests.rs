// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::preview::local_file::{
    browser_document_extension, browser_target_error_code, html_document_extension,
    normalize_workspace_relative_target, is_local_media_preview_url, parse_absolute_or_file_target,
    validate_target,
};
use crate::preview::{PreviewErrorCode, PreviewMode, PreviewResolveFileInput};
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
    assert!(
        parse_absolute_or_file_target(&absolute)
            .expect("parse absolute")
            .is_some()
    );
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

/// 相对 Markdown 路径采用协议 slash 并去除目录尾分隔符，空化根别名不能绕过 Workspace guard。
#[test]
fn workspace_relative_target_normalization_preserves_guarded_paths() {
    assert_eq!(
        normalize_workspace_relative_target("web/图形 示例/").expect("slash path"),
        "web/图形 示例"
    );
    assert_eq!(
        normalize_workspace_relative_target("web\\图形 示例\\").expect("Windows relative path"),
        "web/图形 示例"
    );
    for root_alias in ["/", "\\", "///", "\\\\"] {
        assert_eq!(
            normalize_workspace_relative_target(root_alias)
                .expect_err("separator-only path must not become the workspace root")
                .code(),
            PreviewErrorCode::FileTargetInvalid
        );
    }
    assert_eq!(
        normalize_workspace_relative_target(".").expect("leave dot for workspace validation"),
        "."
    );
}

/// 只有媒体扩展名自动进入预览；HTML 必须由用户明确选择浏览器预览。
#[test]
fn common_browser_document_extensions_are_recognized() {
    assert!(browser_document_extension("svg"));
    assert!(browser_document_extension("pdf"));
    assert!(html_document_extension("html"));
    assert!(html_document_extension("xhtml"));
    assert!(!browser_document_extension("unknown"));
    assert!(!html_document_extension("unknown"));
}

/// A resolved directory has a distinct browser rejection while all non-browser file kinds stay unsupported.
/// @author kongweiguang
#[test]
fn browser_target_rejection_preserves_directory_and_unsupported_kinds() {
    assert_eq!(
        browser_target_error_code(crate::preview::PreviewFileKind::Directory),
        Some(PreviewErrorCode::FileIsDirectory),
    );
    assert_eq!(
        browser_target_error_code(crate::preview::PreviewFileKind::Unsupported),
        Some(PreviewErrorCode::LocalFileUnsupported),
    );
    assert_eq!(
        browser_target_error_code(crate::preview::PreviewFileKind::Text),
        Some(PreviewErrorCode::LocalFileUnsupported),
    );
    assert_eq!(
        browser_target_error_code(crate::preview::PreviewFileKind::Browser),
        None,
    );
}

/// 媒体 abort 豁免只接受 file URI 中已有 MIME 映射的本地音视频，覆盖中文、空格与大小写扩展名。
/// @author kongweiguang
#[test]
fn local_media_probe_target_is_narrow_and_accepts_windows_file_uris() {
    let wav = PathBuf::from(r"C:\临时 目录\录音.WAV");
    let wav_url = url::Url::from_file_path(&wav)
        .expect("Windows media path must serialize as file URL")
        .to_string();
    assert!(wav_url.contains("%20"));
    assert!(is_local_media_preview_url(&wav_url));

    let mp4 = url::Url::from_file_path(PathBuf::from(r"C:\临时 目录\影片.mp4"))
        .expect("video path must serialize as file URL")
        .to_string();
    assert!(is_local_media_preview_url(&mp4));
    assert!(!is_local_media_preview_url("https://example.test/recording.wav"));
    assert!(!is_local_media_preview_url("file:///C:/临时%20目录/image.png"));
    assert!(!is_local_media_preview_url("file:///C:/临时%20目录/report.pdf"));
    assert!(!is_local_media_preview_url("file:///C:/临时%20目录/page.html"));
    assert!(!is_local_media_preview_url("file:///C:/临时%20目录/recording.wav?download=1"));
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
    assert_eq!(decoded.preview_mode, PreviewMode::Editor);
    let browser = serde_json::from_value::<PreviewResolveFileInput>(serde_json::json!({
        "target": "C:/work/page.html",
        "previewMode": "browser"
    }))
    .expect("explicit browser preview input");
    assert_eq!(browser.preview_mode, PreviewMode::Browser);
    assert!(
        serde_json::from_value::<PreviewResolveFileInput>(serde_json::json!({
            "target": "src/main.rs",
            "workspaceRoot": "C:/untrusted"
        }))
        .is_err()
    );
}
