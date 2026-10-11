// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Preview command 适配层的私有状态机单元测试。

use super::*;
use crate::preview::NavigationSource;
#[cfg(windows)]
use webview2_com::Microsoft::Web::WebView2::Win32::COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_ABORTED;

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

/// 共享数据目录时必须按 `main` 标签读取启动参数，不能依赖窗口配置的数组顺序。
/// @author kongweiguang
#[cfg(windows)]
#[test]
fn preview_child_inherits_additional_browser_args_from_main_window() {
    let secondary = tauri::utils::config::WindowConfig {
        label: "secondary".into(),
        additional_browser_args: Some("--secondary-only".into()),
        ..Default::default()
    };

    let mut main = tauri::utils::config::WindowConfig {
        label: "main".into(),
        additional_browser_args: Some("--remote-debugging-port=9222".into()),
        ..Default::default()
    };

    assert_eq!(
        main_window_additional_browser_args(&[secondary.clone(), main.clone()]),
        Some("--remote-debugging-port=9222")
    );
    assert_eq!(
        main_window_additional_browser_args(&[secondary]),
        None,
        "secondary window arguments must not be applied to the Preview child"
    );
    main.additional_browser_args = None;
    assert_eq!(main_window_additional_browser_args(&[main]), None);
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

/// 确认 tab 可先以隐藏状态创建，但任何原生 WebView 都先取得非零有限 bounds。
#[test]
fn preview_viewport_accepts_hidden_open_with_nonzero_bounds() {
    assert!(visible_viewport().validate_open().is_ok());
    assert!(PreviewViewportInput {
        visible: false,
        ..visible_viewport()
    }
    .validate_open()
    .is_ok());
    assert!(PreviewViewportInput {
        width: 0.0,
        ..visible_viewport()
    }
    .validate_open()
    .is_err());
    assert!(PreviewViewportInput {
        x: f64::NAN,
        ..visible_viewport()
    }
    .validate()
    .is_err());
    assert!(PreviewViewportInput {
        visible: false,
        width: 0.0,
        height: 0.0,
        ..visible_viewport()
    }
    .validate()
    .is_ok());
}

/// 旧页面 completion 不能领取新导航的 ticket，同代际新导航也必须使旧媒体探测失效。
/// @author kongweiguang
#[cfg(windows)]
#[test]
fn preview_navigation_completion_matches_native_navigation_identity() {
    let mut initial = PreviewNavigationCompletionTracker::new();
    let initial_identity = initial.completed(7, 3).expect("initial completion identity");
    assert_eq!(initial_identity.generation, 3);
    assert!(initial.is_current(initial_identity));
    assert_eq!(initial.completed(7, 4), None);

    let mut tracker = PreviewNavigationCompletionTracker::new();
    tracker.started(11, 4);
    tracker.started(12, 5);
    assert_eq!(tracker.completed(11, 5), None);
    let current = tracker.completed(12, 99).expect("matched native navigation");
    assert_eq!(current.generation, 5);
    assert!(tracker.is_current(current));
    assert_eq!(tracker.completed(12, 99), None);

    tracker.started(13, 5);
    assert!(!tracker.is_current(current));
}

/// 只有 status 9 的失败导航可进入媒体验证，成功完成或状态不可读仍沿用普通 completion。
/// @author kongweiguang
#[cfg(windows)]
#[test]
fn media_probe_is_restricted_to_connection_aborted() {
    assert!(should_probe_media_after_navigation_abort(
        false,
        Some(COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_ABORTED)
    ));
    assert!(!should_probe_media_after_navigation_abort(
        true,
        Some(COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_ABORTED)
    ));
    assert!(!should_probe_media_after_navigation_abort(false, None));
}

/// ExecuteScript 只把返回的真实数值 1 当作 loaded metadata，Promise 或任意文本不会误报成功。
/// @author kongweiguang
#[cfg(windows)]
#[test]
fn media_probe_requires_a_synchronous_numeric_metadata_result() {
    assert_eq!(
        parse_media_metadata_probe_result(true, "1"),
        MediaMetadataProbeState::Ready
    );
    assert_eq!(
        parse_media_metadata_probe_result(true, "2"),
        MediaMetadataProbeState::Pending
    );
    for invalid in ["true", "{}", "[object Promise]", "3", "private payload"] {
        assert_eq!(
            parse_media_metadata_probe_result(true, invalid),
            MediaMetadataProbeState::Failed
        );
    }
    assert_eq!(
        parse_media_metadata_probe_result(false, "1"),
        MediaMetadataProbeState::Failed
    );
}

/// 媒体探测脚本拒绝无穷 duration，并以 JSON 字符串承载 URL，避免路径文本逃逸为脚本。
/// @author kongweiguang
#[cfg(windows)]
#[test]
fn media_probe_script_requires_finite_duration_and_escapes_url() {
    let target = "file:///C:/媒体 目录/clip.wav\";globalThis.injected=true;//";
    let script = build_local_media_metadata_probe_script(target).expect("probe script");
    let encoded_url = serde_json::to_string(target).expect("JSON URL encoding");

    assert!(script.contains(&format!("location.href!=={encoded_url}")));
    assert!(script.contains("Number.isFinite(duration)"));
    assert!(script.contains("duration>=0"));
    assert_eq!(script.matches("globalThis.injected=true").count(), 1);
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

/// timeout 必须跟随当前仍在 loading 的权威 generation，但不能覆盖已经成功的终态。
#[test]
fn load_timeout_rebinds_generation_only_while_authoritative_session_is_loading() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/initial").expect("open");
    let navigated = manager
        .navigate(
            opened.snapshot.id,
            opened.snapshot.generation,
            NavigationSource::User,
            "https://example.test/next",
        )
        .expect("navigate");

    assert_eq!(
        current_loading_generation(&manager, opened.snapshot.id),
        Some(navigated.generation)
    );
    manager
        .callback_load_finished(
            opened.snapshot.id,
            navigated.generation,
            "https://example.test/next",
        )
        .expect("finish");
    assert_eq!(current_loading_generation(&manager, opened.snapshot.id), None);
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
