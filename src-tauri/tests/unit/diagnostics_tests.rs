// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::io::{self, Write};
use std::sync::{Arc, Mutex};

use tracing_subscriber::prelude::*;

use super::RedactedEventFormat;

/// 在 tracing 写入器工厂与断言之间共享内存 sink，避免测试输出引入任何文件路径。
#[derive(Clone)]
struct SharedWriter(Arc<Mutex<Vec<u8>>>);

impl Write for SharedWriter {
    /// 把单次写入追加到共享缓冲区，使断言能够观察 formatter 的完整输出边界。
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        self.0
            .lock()
            .expect("test log sink poisoned")
            .extend_from_slice(buffer);
        Ok(buffer.len())
    }

    /// 内存 sink 没有外部缓冲层，flush 保持成功即可维持 `Write` 契约。
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// 验证原生日志丢弃敏感载荷，只保留 Rust 白名单允许的 UI 故障码。
#[test]
fn formatter_omits_native_payloads_and_keeps_only_allowlisted_ui_codes() {
    let output = Arc::new(Mutex::new(Vec::new()));
    let writer_output = Arc::clone(&output);
    let subscriber = tracing_subscriber::fmt::layer()
        .with_ansi(false)
        .event_format(RedactedEventFormat::default())
        .with_writer(move || SharedWriter(Arc::clone(&writer_output)));
    tracing::subscriber::with_default(tracing_subscriber::registry().with(subscriber), || {
        tracing::error!(
            path = %r"C:\private\prompt.txt",
            command = %"print secret",
            "sensitive payload"
        );
        tracing::event!(
            target: tauri_plugin_log::WEBVIEW_TARGET,
            tracing::Level::ERROR,
            message = %"ui.react_error_boundary",
            file = %r"C:\private\ui.tsx"
        );
        tracing::event!(
            target: tauri_plugin_log::WEBVIEW_TARGET,
            tracing::Level::ERROR,
            message = %"prompt content must not pass"
        );
    });
    let rendered = String::from_utf8(output.lock().expect("test log sink poisoned").clone())
        .expect("formatter must emit UTF-8");

    assert!(rendered.contains("code=ui.react_error_boundary"));
    assert!(!rendered.contains("private"));
    assert!(!rendered.contains("print secret"));
    assert!(!rendered.contains("sensitive payload"));
    assert!(!rendered.contains("prompt content"));
}
