// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use proptest::prelude::*;

/// 验证 revision、operation 与 target identity 共享控制字符禁令，但保留各自长度上限。
#[test]
fn opaque_value_objects_reject_empty_control_and_oversized_input() {
    assert!(ReviewRevision::parse("").is_err());
    assert!(ReviewRevision::parse("revision\nunsafe").is_err());
    assert!(ReviewOperationId::parse("x".repeat(129)).is_err());
    assert!(ReviewFileId::parse("f".repeat(256)).is_ok());
    assert!(ReviewHunkId::parse("h".repeat(257)).is_err());
}

/// Catalog window 是领域资源不变量，interface 和 application 不应再复制 1..=100 判断。
#[test]
fn catalog_limit_has_single_closed_range_and_default() {
    assert!(ReviewCatalogLimit::parse(0).is_err());
    assert_eq!(ReviewCatalogLimit::parse(1).expect("minimum").get(), 1);
    assert_eq!(ReviewCatalogLimit::default_window().get(), 50);
    assert_eq!(ReviewCatalogLimit::parse(100).expect("maximum").get(), 100);
    assert!(ReviewCatalogLimit::parse(101).is_err());
}

/// 历史来源只接受 commit identity，且不会获得写能力。
#[test]
fn commit_source_is_read_only() {
    assert!(
        ReviewSource::Commit {
            commit_id: ReviewCommitId::parse("deadbeef").expect("commit id"),
        }
        .is_read_only()
    );
}

/// 未提交聚合仍是可写来源，其具体 action 必须由每个文件的层身份继续收窄。
#[test]
fn uncommitted_source_is_not_read_only() {
    assert!(!ReviewSource::Uncommitted.is_read_only());
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(1_024))]

    /// 任意 Unicode operation identity 的接受结果必须精确等价于生产长度与控制字符不变量。
    #[test]
    fn operation_identity_parser_matches_the_closed_invariant(value in any::<String>()) {
        let expected = !value.is_empty()
            && value.len() <= 128
            && !value.bytes().any(|byte| byte < 0x20 || byte == 0x7f);
        prop_assert_eq!(ReviewOperationId::parse(value).is_ok(), expected);
    }
}
