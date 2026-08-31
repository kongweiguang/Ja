// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Preview command 适配层的私有状态机单元测试。

use super::*;

/// 构造一组合法的检查器视口，集中表达后续校验用例共同依赖的边界。
fn visible_viewport() -> PreviewViewportInput {
    PreviewViewportInput {
        x: 840.0,
        y: 250.0,
        width: 430.0,
        height: 540.0,
        visible: true,
    }
}

/// 将首次回滚的占用身份转为待恢复状态，模拟 renderer 未接管 WebView 的真实失败窗口。
fn queue_unowned_identity(host: &PreviewCommandHost, id: PreviewId) {
    host.unowned_recovery
        .begin_created_rollback(id)
        .expect("mark unowned identity");
    host.unowned_recovery
        .release(id)
        .expect("queue unowned identity");
}

/// 通过模块私有 Condvar 观察关停线性化点，避免在生产类型上增加测试专用方法。
fn wait_for_shutdown_started_until(
    fence: &PreviewOperationFence,
    deadline: Instant,
) -> Result<bool, PreviewError> {
    let (lock, condition) = &*fence.shared;
    let mut state = PreviewOperationFence::lock_state(lock)?;
    while !state.shutdown_started {
        let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
            return Ok(false);
        };
        let (next, timeout) = condition
            .wait_timeout(state, remaining)
            .map_err(|_| PreviewError::new(PreviewErrorCode::InternalStateUnavailable))?;
        state = next;
        if timeout.timed_out() && !state.shutdown_started {
            return Ok(false);
        }
    }
    Ok(true)
}

/// 确认打开预览要求有限且可见的有效矩形，隐藏布局仍允许零尺寸用于收口。
#[test]
fn preview_viewport_requires_finite_visible_open_bounds() {
    assert!(visible_viewport().validate_open().is_ok());
    assert!(
        PreviewViewportInput {
            width: 0.0,
            ..visible_viewport()
        }
        .validate_open()
        .is_err()
    );
    assert!(
        PreviewViewportInput {
            x: f64::NAN,
            ..visible_viewport()
        }
        .validate()
        .is_err()
    );
    assert!(
        PreviewViewportInput {
            visible: false,
            width: 0.0,
            height: 0.0,
            ..visible_viewport()
        }
        .validate()
        .is_ok()
    );
}

/// 确认只有 URL 策略拒绝可映射为页面加载失败，陈旧回调与宿主故障不得冒充远端错误。
#[test]
fn only_navigation_policy_errors_are_reportable_load_failures() {
    assert!(is_navigation_policy_error(
        PreviewErrorCode::SchemeNotAllowed
    ));
    assert!(is_navigation_policy_error(
        PreviewErrorCode::NavigationBlocked
    ));
    assert!(!is_navigation_policy_error(
        PreviewErrorCode::StaleGeneration
    ));
    assert!(!is_navigation_policy_error(
        PreviewErrorCode::InternalStateUnavailable
    ));
}

/// 确认恢复只处理内部标记的子 WebView，并在成功后清理身份且允许幂等重试。
#[test]
fn pending_recovery_is_scoped_redacted_and_idempotent() {
    let host = PreviewCommandHost::new().expect("产品默认 Preview 配置必须有效");
    let unowned = host
        .manager
        .open("https://example.test/unowned")
        .expect("unowned preview");
    let owned = host
        .manager
        .open("https://example.test/owned")
        .expect("owned preview");
    queue_unowned_identity(&host, unowned.snapshot.id);

    let report = host
        .recover_pending_with_closer(|_| Ok(NativeCloseOutcome::Acknowledged))
        .expect("recovery report");
    assert_eq!(
        report,
        PreviewRecoveryReport {
            observed: 1,
            recovered: 1,
            failed: 0,
            pending: 0,
        }
    );
    assert_eq!(
        serde_json::to_value(report).expect("serialize report"),
        serde_json::json!({
            "observed": 1,
            "recovered": 1,
            "failed": 0,
            "pending": 0,
        })
    );
    assert_eq!(
        host.manager
            .snapshot(unowned.snapshot.id)
            .expect_err("recovered model must be removed")
            .code(),
        PreviewErrorCode::SessionNotFound
    );
    assert!(host.manager.snapshot(owned.snapshot.id).is_ok());

    let repeated = host
        .recover_pending_with_closer(|_| panic!("no identity may be retried"))
        .expect("idempotent report");
    assert_eq!(repeated.observed, 0);
    assert_eq!(repeated.recovered, 0);
    assert_eq!(repeated.failed, 0);
    assert_eq!(repeated.pending, 0);
}

/// 确认 native close 失败会保留精确身份，后续 ACK 重试再同时清理模型与恢复元数据。
#[test]
fn pending_recovery_retains_close_failure_for_retry() {
    let host = PreviewCommandHost::new().expect("产品默认 Preview 配置必须有效");
    let opened = host
        .manager
        .open("https://example.test/retry")
        .expect("preview");
    let rollback_error = host
        .rollback_created_with_closer(opened.snapshot.id, |_| {
            Err(PreviewError::new(PreviewErrorCode::DependencyRequest))
        })
        .expect_err("initial native close must fail");
    assert_eq!(rollback_error.code(), PreviewErrorCode::DependencyRequest);
    assert_eq!(
        host.unowned_recovery
            .unresolved_count()
            .expect("pending rollback count"),
        1
    );

    let failed = host
        .recover_pending_with_closer(|_| {
            Err(PreviewError::new(PreviewErrorCode::DependencyRequest))
        })
        .expect("failed recovery report");
    assert_eq!(failed.observed, 1);
    assert_eq!(failed.recovered, 0);
    assert_eq!(failed.failed, 1);
    assert_eq!(failed.pending, 1);
    assert!(host.manager.snapshot(opened.snapshot.id).is_ok());

    let retried = host
        .recover_pending_with_closer(|_| Ok(NativeCloseOutcome::AlreadyAbsent))
        .expect("retry recovery report");
    assert_eq!(retried.observed, 1);
    assert_eq!(retried.recovered, 1);
    assert_eq!(retried.failed, 0);
    assert_eq!(retried.pending, 0);
    assert_eq!(host.manager.active_count().expect("active count"), 0);
}

/// 确认恢复注册表严格受容量上限约束，溢出时不驱逐已有清理义务。
#[test]
fn unowned_recovery_registry_preserves_capacity_boundary() {
    let registry = PreviewUnownedRecoveryRegistry::new(2);
    let first = PreviewId::new();
    let second = PreviewId::new();
    let overflow = PreviewId::new();
    for id in [first, second] {
        registry
            .begin_created_rollback(id)
            .expect("claim within capacity");
        registry.release(id).expect("queue within capacity");
    }
    let error = registry
        .begin_created_rollback(overflow)
        .expect_err("overflow must fail closed");
    assert_eq!(error.code(), PreviewErrorCode::InternalStateUnavailable);
    assert_eq!(registry.unresolved_count().expect("bounded count"), 2);
}

/// 确认关停只线性化一次，拒绝所有晚到操作，并等待此前准入的命令释放许可。
#[test]
fn shutdown_and_open_have_deterministic_linearization() {
    let host = PreviewCommandHost::new().expect("产品默认 Preview 配置必须有效");
    let admitted = host.enter_operation().expect("admitted operation");
    let shutdown_host = host.clone();
    let shutdown = std::thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(2);
        shutdown_host.begin_shutdown_until(deadline)
    });
    assert!(
        wait_for_shutdown_started_until(
            &host.operation_fence,
            Instant::now() + Duration::from_secs(2)
        )
        .expect("observe shutdown")
    );
    let late_error = match host.enter_operation() {
        Ok(_) => panic!("late operation must be rejected"),
        Err(error) => error,
    };
    assert_eq!(late_error.code(), PreviewErrorCode::ShutdownStarted);
    drop(admitted);
    let drain = shutdown
        .join()
        .expect("shutdown thread")
        .expect("shutdown drain");
    assert!(drain.drained);
    assert_eq!(drain.in_flight, 0);
}

/// 确认部分 native close 失败保持有界且可重试，成功身份立即消失，失败身份继续保留。
#[test]
fn shutdown_report_retains_only_failed_native_close_identity() {
    let host = PreviewCommandHost::new().expect("产品默认 Preview 配置必须有效");
    host.manager
        .open("https://example.test/one")
        .expect("first");
    host.manager
        .open("https://example.test/two")
        .expect("second");
    let mut attempt = 0usize;
    let report = host
        .shutdown_with_closer_until(Instant::now() + Duration::from_secs(2), |_| {
            attempt = attempt.saturating_add(1);
            if attempt == 1 {
                Err(PreviewError::new(PreviewErrorCode::DependencyRequest))
            } else {
                Ok(NativeCloseOutcome::Acknowledged)
            }
        })
        .expect("partial shutdown report");
    assert_eq!(report.sessions_observed, 2);
    assert_eq!(report.close_acknowledged, 1);
    assert_eq!(report.close_failed, 1);
    assert_eq!(report.pending_sessions, 1);
    assert!(!report.complete);

    let retry = host
        .shutdown_with_closer_until(Instant::now() + Duration::from_secs(2), |_| {
            Ok(NativeCloseOutcome::Acknowledged)
        })
        .expect("retry report");
    assert_eq!(retry.sessions_observed, 1);
    assert_eq!(retry.close_acknowledged, 1);
    assert_eq!(retry.pending_sessions, 0);
    assert!(retry.complete);
}
