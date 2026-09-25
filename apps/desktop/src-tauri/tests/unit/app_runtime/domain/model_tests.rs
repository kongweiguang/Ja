// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// Turn identity 的冻结前缀、字符集和长度必须由领域层一次性约束。
#[test]
fn frozen_turn_identity_is_strict_and_bounded() {
    assert!(valid_frozen_turn_id("turn_abc-123.DEF"));
    assert!(!valid_frozen_turn_id("thr_abc"));
    assert!(!valid_frozen_turn_id("turn_"));
    assert!(!valid_frozen_turn_id(&format!("turn_{}", "a".repeat(97))));
}

/// Runtime Busy 是 Host 内部细节，投影到 wire 时仍必须保持 ready 语义。
#[test]
fn busy_status_preserves_ready_wire_contract() {
    assert_eq!(RuntimeStatusKind::Busy.protocol_name(), "ready");
    assert_eq!(
        RuntimeStatusKind::RecoveryRequired.protocol_name(),
        "recovery_required"
    );
}
