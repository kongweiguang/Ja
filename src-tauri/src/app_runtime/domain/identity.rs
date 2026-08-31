// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// JA-RPC identity 的纯领域约束。

/// 校验允许跨协议传播的通用文本 identity。
/// 领域层集中维护字符集和长度不变量，避免 Turn、Approval 与事件投影各自形成略有差异的判断。
pub(crate) fn valid_text_id(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.' | ':')
        })
}

/// 校验带固定前缀的 JA-RPC identity。
/// 前缀属于协议领域事实，因此这里拒绝空尾部和非 ASCII 字符，不把容错扩散到进程适配器。
pub(crate) fn valid_protocol_id(value: &str, prefix: &str, max: usize) -> bool {
    let Some(tail) = value.strip_prefix(prefix) else {
        return false;
    };
    value.len() <= max
        && !tail.is_empty()
        && tail.as_bytes()[0].is_ascii_alphanumeric()
        && tail
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.'))
}

/// 校验冻结版本允许的 Turn identity。
/// 单独表达 96 字节尾部上限，是为了让取消、队列和事件关联共享同一个权威约束。
pub(crate) fn valid_frozen_turn_id(value: &str) -> bool {
    let Some(tail) = value.strip_prefix("turn_") else {
        return false;
    };
    value.len() <= 101
        && !tail.is_empty()
        && tail.len() <= 96
        && tail.as_bytes()[0].is_ascii_alphanumeric()
        && tail
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}
