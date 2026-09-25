// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 终端环境继承与显式 override 的结构校验。
//
// `portable-pty::CommandBuilder` 会从当前 Ja 进程建立完整的宿主环境；这里不能
// 再复制一份 allowlist，否则用户在正常终端中可用的工具配置、凭据后端和代理会被
// 静默丢弃。该模块只校验调用方主动注入的键值，阻止操作系统明确不接受的形状。

use super::super::error::{TerminalError, TerminalErrorCode};
use std::collections::BTreeMap;
use std::ffi::OsString;
/// 只复制调用方显式提供的 override；未显式覆盖的变量由 PTY builder 继承宿主环境。
pub(crate) fn build_environment(
    overrides: &BTreeMap<String, String>,
) -> Result<BTreeMap<OsString, OsString>, TerminalError> {
    overrides
        .iter()
        .map(|(key, value)| {
            validate_environment_override(key, value)?;
            Ok((OsString::from(key), OsString::from(value)))
        })
        .collect()
}

/// 保留操作系统对环境键值的基本约束，但不按名称推断敏感性或人为限制长度。
///
/// `CommandBuilder` 会把这些值直接交给 Windows/Unix 的进程创建 API；空键、包含
/// `=` 的键和 NUL 会被平台拒绝，值中的 NUL 同样无法编码。多行值、Unicode 名称、
/// `ProgramFiles(x86)` 等正常环境内容必须原样保留。
fn validate_environment_override(key: &str, value: &str) -> Result<(), TerminalError> {
    if key.is_empty() || key.contains('=') || key.contains('\0') || value.contains('\0') {
        return Err(TerminalError::new(TerminalErrorCode::EnvironmentNotAllowed));
    }
    Ok(())
}
