// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 为插入交互式 PTY 的原生路径提供 shell-specific quoting。
//
// renderer 永远看不到真实路径；先消费 native drop capability，再把全部已准入绝对路径
// 转换为 inert shell text，既不追加 Enter，也不构造命令。

use super::error::{TerminalError, TerminalErrorCode};
use super::model::ShellProfile;
use std::path::PathBuf;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ShellQuoteStyle {
    PowerShell,
    Cmd,
    Posix,
}

/// 按目标 PTY 的 profile 引用有界原生路径列表；路径间只插入空格、不追加换行，确保
/// drag/drop 不会执行用户尚未完成的命令。
pub(crate) fn quote_native_paths(
    profile: ShellProfile,
    paths: &[PathBuf],
) -> Result<Vec<u8>, TerminalError> {
    if paths.is_empty() {
        return Err(TerminalError::new(TerminalErrorCode::DropTokenInvalid));
    }
    let style = quote_style(profile)?;
    let mut quoted = String::new();
    for path in paths {
        if !path.is_absolute() {
            return Err(TerminalError::new(TerminalErrorCode::DropPathUnsupported));
        }
        let value = path
            .to_str()
            .ok_or(TerminalError::new(TerminalErrorCode::DropPathUnsupported))?;
        if !quoted.is_empty() {
            quoted.push(' ');
        }
        quoted.push_str(&quote_path(style, value)?);
    }
    Ok(quoted.into_bytes())
}

/// 与 terminal launch 使用同一规则解析 `default`，使 quoting 绑定已准入 shell，不能信任
/// renderer 提供的平台提示。
fn quote_style(profile: ShellProfile) -> Result<ShellQuoteStyle, TerminalError> {
    match profile {
        ShellProfile::PowerShell => Ok(ShellQuoteStyle::PowerShell),
        ShellProfile::Cmd => Ok(ShellQuoteStyle::Cmd),
        ShellProfile::Bash | ShellProfile::Zsh | ShellProfile::Fish => Ok(ShellQuoteStyle::Posix),
        ShellProfile::Default => {
            #[cfg(windows)]
            {
                Ok(ShellQuoteStyle::PowerShell)
            }
            #[cfg(unix)]
            {
                Ok(ShellQuoteStyle::Posix)
            }
            #[cfg(not(any(unix, windows)))]
            {
                Err(TerminalError::new(TerminalErrorCode::UnsupportedPlatform))
            }
        }
    }
}

/// 根据已准入 shell profile 分派到对应 quoting grammar，避免再次猜测运行环境。
fn quote_path(style: ShellQuoteStyle, value: &str) -> Result<String, TerminalError> {
    match style {
        ShellQuoteStyle::PowerShell => Ok(quote_powershell(value)),
        ShellQuoteStyle::Cmd => quote_cmd(value),
        ShellQuoteStyle::Posix => Ok(quote_posix(value)),
    }
}

/// PowerShell 单引号抑制 interpolation，路径中的单引号通过双写保持字面量。
pub(crate) fn quote_powershell(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

/// CMD 双引号隔离命令分隔符，末尾反斜杠按 Windows argv parser 规则双写；`%`、`!`、`^`
/// 在 delayed-expansion 两种状态下无法同时可靠保真，又不能先执行改变状态的辅助命令，故直接拒绝。
pub(crate) fn quote_cmd(value: &str) -> Result<String, TerminalError> {
    if value.contains(['%', '!', '^']) {
        return Err(TerminalError::new(TerminalErrorCode::DropPathUnsupported));
    }
    let mut result = String::with_capacity(value.len().saturating_add(2));
    result.push('"');
    result.push_str(value);
    for _ in 0..value
        .chars()
        .rev()
        .take_while(|value| *value == '\\')
        .count()
    {
        result.push('\\');
    }
    result.push('"');
    Ok(result)
}

/// POSIX-family shell 用相邻单引号片段包围字面单引号，阻断 substitution。
pub(crate) fn quote_posix(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}
