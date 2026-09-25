// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

mod controller;

/// CLI 错误统一经 main 映射退出码，避免异常路径绕过 TUI 终端恢复。
fn main() -> std::process::ExitCode {
    match controller::run(std::env::args().skip(1)) {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("Ja: {}", error.message());
            std::process::ExitCode::from(error.exit_code())
        }
    }
}
