// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use serde_json::Value;
use std::{fs, path::PathBuf};

/// 读取已检入 Tauri Configuration，防止 Array Replacement 让平台 Window 静默偏离共享应用合同。
fn read_config(file_name: &str) -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(file_name);
    let source = fs::read_to_string(&path)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()));
    serde_json::from_str(&source)
        .unwrap_or_else(|error| panic!("failed to parse {}: {error}", path.display()))
}

/// 只返回唯一 Main Window Object；Platform Overlay 按 RFC 7396 明确替换整个 Array，
/// 接受额外 Window 会掩盖配置漂移。
fn main_window(config: &Value) -> &serde_json::Map<String, Value> {
    let windows = config["app"]["windows"]
        .as_array()
        .expect("app.windows must be an array");
    assert_eq!(
        windows.len(),
        1,
        "Ja must keep one authoritative main window"
    );
    windows[0]
        .as_object()
        .expect("main window configuration must be an object")
}

/// 公共 Geometry 与 Lifecycle Field 必须保持一致，只允许 Windows 与 macOS 的
/// Native Chrome Field 存在差异。
#[test]
fn platform_window_overlays_preserve_the_shared_contract() {
    let common = read_config("tauri.conf.json");
    let windows = read_config("tauri.windows.conf.json");
    let macos = read_config("tauri.macos.conf.json");
    let common_window = main_window(&common);
    let windows_window = main_window(&windows);
    let macos_window = main_window(&macos);

    for field in [
        "label",
        "title",
        "width",
        "height",
        "minWidth",
        "minHeight",
        "resizable",
        "center",
        "dragDropEnabled",
    ] {
        assert_eq!(
            windows_window.get(field),
            common_window.get(field),
            "Windows drifted at {field}"
        );
        assert_eq!(
            macos_window.get(field),
            common_window.get(field),
            "macOS drifted at {field}"
        );
    }

    assert_eq!(common_window.get("decorations"), Some(&Value::Bool(true)));
    assert_eq!(windows_window.get("decorations"), Some(&Value::Bool(false)));
    assert_eq!(windows_window.get("shadow"), Some(&Value::Bool(true)));
    assert_eq!(macos_window.get("decorations"), Some(&Value::Bool(true)));
    assert_eq!(
        macos_window.get("titleBarStyle").and_then(Value::as_str),
        Some("Overlay")
    );
    assert_eq!(macos_window.get("hiddenTitle"), Some(&Value::Bool(true)));
    let traffic_lights = macos_window["trafficLightPosition"]
        .as_object()
        .expect("macOS overlay must keep native traffic-light positioning");
    assert_eq!(traffic_lights.get("x").and_then(Value::as_i64), Some(14));
    assert_eq!(traffic_lights.get("y").and_then(Value::as_i64), Some(18));
}
