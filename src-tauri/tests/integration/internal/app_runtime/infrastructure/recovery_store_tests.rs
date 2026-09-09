// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// 创建与生产 marker operation 相同 private mode 的测试 Runtime 边界，避免权限差异掩盖问题。
fn create_private_test_dir(path: &Path) {
    fs::create_dir_all(path).expect("test runtime directory");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))
            .expect("private test directory mode");
    }
}

/// Recovery JSON 必须符合唯一精确 versioned shape；future、duplicate、unknown 或错误类型字段
/// 都可能在 crash 后解锁歧义 process state，因此必须拒绝。
#[test]
fn recovery_marker_rejects_ambiguous_schema() {
    let fixtures: &[&[u8]] = &[
        br#"{"schemaVersion":1,"status":"manual_recovery_required","recoveryId":"00000000-0000-4000-8000-000000000001","revision":1,"generation":1,"extra":true}"#,
        br#"{"schemaVersion":1,"status":"manual_recovery_required","recoveryId":"00000000-0000-4000-8000-000000000001","recoveryId":"00000000-0000-4000-8000-000000000002","revision":1,"generation":1}"#,
        br#"{"schemaVersion":"1","status":"manual_recovery_required","recoveryId":"00000000-0000-4000-8000-000000000001","revision":1,"generation":1}"#,
        br#"{"schemaVersion":2,"status":"manual_recovery_required","recoveryId":"00000000-0000-4000-8000-000000000001","revision":1,"generation":1}"#,
    ];
    for fixture in fixtures {
        assert!(parse_recovery_marker(fixture).is_err());
    }
}

/// durable write 使用 private file；marker 存在期间 read gate 必须阻止 startup，即使 marker
/// 本身合法。
#[test]
fn recovery_marker_write_read_and_clear_are_durable() {
    let run_dir = std::env::temp_dir().join(format!("ja-recovery-durable-{}", std::process::id()));
    let _ = fs::remove_dir_all(&run_dir);
    create_private_test_dir(&run_dir);
    let marker = recovery_marker_path(&run_dir);
    persist_recovery_record(&marker, 3, 7).expect("durable marker");
    assert_eq!(
        ensure_recovery_clear(&run_dir)
            .expect_err("marker must block startup")
            .code,
        "RECOVERY_REQUIRED"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            fs::metadata(&marker)
                .expect("marker metadata")
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
    }
    clear_recovery_record(&marker).expect("clear marker");
    assert!(ensure_recovery_clear(&run_dir).is_ok());
    let _ = fs::remove_dir_all(&run_dir);
}

/// failed replace 遗留的 temp file 是 durable write 未完成的证据，因此下次 startup 必须关闭失败。
#[test]
fn recovery_temp_remnant_blocks_startup() {
    let run_dir = std::env::temp_dir().join(format!("ja-recovery-temp-{}", std::process::id()));
    let _ = fs::remove_dir_all(&run_dir);
    create_private_test_dir(&run_dir);
    let remnant = run_dir.join(format!("{RECOVERY_TEMP_PREFIX}power-loss"));
    fs::write(&remnant, b"partial").expect("temp remnant");
    assert_eq!(
        ensure_recovery_clear(&run_dir)
            .expect_err("temp remnant must block startup")
            .code,
        "RECOVERY_REQUIRED"
    );
    let _ = fs::remove_dir_all(&run_dir);
}

/// oversized marker input 在 JSON parsing 前被拒绝，防止损坏 disk record 在 startup recovery
/// 消耗无界内存。
#[test]
fn recovery_marker_oversize_blocks_startup() {
    let run_dir = std::env::temp_dir().join(format!("ja-recovery-oversize-{}", std::process::id()));
    let _ = fs::remove_dir_all(&run_dir);
    create_private_test_dir(&run_dir);
    let marker = recovery_marker_path(&run_dir);
    fs::write(&marker, vec![b'{'; (MAX_RECOVERY_BYTES as usize) + 1]).expect("oversize marker");
    assert_eq!(
        ensure_recovery_clear(&run_dir)
            .expect_err("oversize marker must block startup")
            .code,
        "RECOVERY_REQUIRED"
    );
    let _ = fs::remove_dir_all(&run_dir);
}

/// malformed marker 仍是 recovery condition，绝不能据此在未知先前状态上静默启动第二个 sidecar。
#[test]
fn malformed_recovery_marker_blocks_startup() {
    let run_dir =
        std::env::temp_dir().join(format!("ja-recovery-malformed-{}", std::process::id()));
    let _ = fs::remove_dir_all(&run_dir);
    create_private_test_dir(&run_dir);
    fs::write(recovery_marker_path(&run_dir), b"not-json").expect("malformed recovery marker");
    assert_eq!(
        ensure_recovery_clear(&run_dir)
            .expect_err("malformed marker must block startup")
            .code,
        "RECOVERY_REQUIRED"
    );
    let _ = fs::remove_dir_all(&run_dir);
}

/// 显式 acknowledgement 在原子删除 pending marker 前记录有限 user confirmation，且不检查
/// sidecar 进程 owner。
#[test]
fn explicit_recovery_acknowledgement_unlocks_startup() {
    let run_dir = std::env::temp_dir().join(format!("ja-recovery-ack-{}", std::process::id()));
    let _ = fs::remove_dir_all(&run_dir);
    create_private_test_dir(&run_dir);
    let marker = recovery_marker_path(&run_dir);
    persist_recovery_record(&marker, 4, 8).expect("recovery marker");
    acknowledge_manual_recovery(
        &run_dir,
        &ManualRecoveryConfirmation {
            recovery_id: parse_recovery_marker(&fs::read(&marker).expect("marker bytes"))
                .expect("marker schema")
                .recovery_id,
            revision: parse_recovery_marker(&fs::read(&marker).expect("marker bytes"))
                .expect("marker schema")
                .revision,
            reason: ManualRecoveryReason::ExternallyCleaned,
        },
    )
    .expect("explicit recovery acknowledgement");
    assert!(!marker.exists());
    assert!(!run_dir.join(RECOVERY_ACK_FILE_NAME).exists());
    assert!(ensure_recovery_clear(&run_dir).is_ok());
    let _ = fs::remove_dir_all(&run_dir);
}

/// 合法 power-loss acknowledgement tombstone 保持 blocking、可重复 recovery state，直到相同
/// typed confirmation 消费它。
#[test]
fn acknowledgement_tombstone_is_repeatable_and_removed() {
    let run_dir =
        std::env::temp_dir().join(format!("ja-recovery-tombstone-{}", std::process::id()));
    let _ = fs::remove_dir_all(&run_dir);
    create_private_test_dir(&run_dir);
    let id = "00000000-0000-4000-8000-000000000003";
    fs::write(
        run_dir.join(RECOVERY_ACK_FILE_NAME),
        format!(
            "{{\"schemaVersion\":1,\"status\":\"manual_recovery_ack_pending\",\"recoveryId\":\"{id}\",\"revision\":11,\"reason\":\"SystemRestarted\"}}"
        ),
    )
    .expect("ack tombstone");
    let state = recovery_state(&run_dir);
    assert!(state.required && state.acknowledgeable);
    acknowledge_manual_recovery(
        &run_dir,
        &ManualRecoveryConfirmation {
            recovery_id: id.to_owned(),
            revision: 11,
            reason: ManualRecoveryReason::SystemRestarted,
        },
    )
    .expect("repeatable tombstone acknowledgement");
    assert!(!run_dir.join(RECOVERY_ACK_FILE_NAME).exists());
    assert!(!recovery_state(&run_dir).required);
    let _ = fs::remove_dir_all(&run_dir);
}

/// stale UI acknowledgement 不得消费当前 marker 或 identity，避免 reload 清除更新的 recovery
/// 恢复尝试。
#[test]
fn stale_recovery_confirmation_is_rejected() {
    let run_dir = std::env::temp_dir().join(format!("ja-recovery-stale-{}", std::process::id()));
    let _ = fs::remove_dir_all(&run_dir);
    create_private_test_dir(&run_dir);
    let marker = recovery_marker_path(&run_dir);
    persist_recovery_record(&marker, 5, 12).expect("recovery marker");
    let error = acknowledge_manual_recovery(
        &run_dir,
        &ManualRecoveryConfirmation {
            recovery_id: "00000000-0000-4000-8000-000000000004".to_owned(),
            revision: 12,
            reason: ManualRecoveryReason::ExternallyCleaned,
        },
    )
    .expect_err("stale identity must fail closed");
    assert_eq!(error.code, "RECOVERY_STALE");
    assert!(marker.exists());
    let _ = fs::remove_dir_all(&run_dir);
}
