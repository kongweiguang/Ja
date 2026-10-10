// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! PowerShell 重试命令保持首次 queue 请求的全部不可变参数。

/// 生成使用相同 Thread、Turn、正文、类型与持久 operation key 的直接恢复命令。
pub(super) fn retry_command(
    thread_id: &str,
    turn_id: &str,
    kind: &str,
    text: &str,
    operation_key: &str,
) -> String {
    format!(
        "ja queue {} --turn-id {} --operation-key {} --kind {} {}",
        powershell_literal(thread_id),
        powershell_literal(turn_id),
        powershell_literal(operation_key),
        powershell_literal(kind),
        powershell_literal(text)
    )
}

/// PowerShell 字符串使用单引号定界，正文内单引号必须双写才会保留原始内容。
fn powershell_literal(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}
