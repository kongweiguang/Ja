// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::catalog_entry;

/// 锁定已删除错误和旧 Host CANCELLED 数值码，防止目录重新引入兼容别名。
#[test]
fn retired_errors_are_not_catalogued() {
    for (code, error_code, category) in [
        (-32_037, "CHANGE_CONFLICT", "conflict"),
        (-32_038, "CHANGE_NOT_FOUND", "not_found"),
        (-32_039, "CHANGE_CONTENT_UNAVAILABLE", "unavailable"),
        (-32_106, "CANCELLED", "cancelled"),
    ] {
        assert!(catalog_entry(code, error_code, category, false).is_none());
    }
}

/// 锁定上下文压缩新增错误的完整 tuple，避免 decoder 与 Java catalog 独立漂移。
#[test]
fn context_compaction_errors_are_catalogued() {
    assert_eq!(
        catalog_entry(-32_030, "THREAD_BUSY", "conflict", true),
        Some(("THREAD_BUSY", "thread busy"))
    );
    assert_eq!(
        catalog_entry(-32_048, "TOKEN_COUNT_UNAVAILABLE", "unavailable", true),
        Some(("TOKEN_COUNT_UNAVAILABLE", "token count unavailable"))
    );
    assert_eq!(
        catalog_entry(-32_049, "SUMMARY_FAILURE", "unavailable", true),
        Some(("SUMMARY_FAILURE", "summary failure"))
    );
}
