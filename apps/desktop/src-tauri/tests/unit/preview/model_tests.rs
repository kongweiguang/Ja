// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Preview 纯规则测试覆盖 URL 策略、会话代际与有界事件。

use super::*;

/// 确认 HTTP(S)、localhost、IP 与自定义端口共享同一规范化策略，不为常见开发地址开旁路。
#[test]
fn accepts_http_https_localhost_and_custom_port() {
    let policy = PreviewPolicy::new().expect("policy");
    for raw in [
        "https://example.test:9443/a?x=1",
        "http://localhost:5173/",
        "http://127.0.0.1:4321/health",
        "http://[::1]:3000/",
        "HTTP://Example.test:8080/",
    ] {
        assert!(!policy.validate_url(raw).expect("url").as_str().is_empty());
    }
    assert_eq!(
        policy
            .validate_url("HTTP://Example.test:8080/")
            .expect("normalized")
            .as_str(),
        "http://example.test:8080/"
    );
}

/// 确认危险 scheme 与含混 authority 在创建 WebView 前失败，避免浏览器解析差异扩大权限边界。
#[test]
fn rejects_dangerous_schemes_and_authority_confusion() {
    let policy = PreviewPolicy::new().expect("policy");
    for raw in [
        "file:///C:/secret.txt",
        "javascript:alert(1)",
        "data:text/html,hello",
        "tauri://localhost",
        "http:///example.com",
        "https:////example.com",
    ] {
        assert!(policy.validate_url(raw).is_err(), "accepted {raw:?}");
    }
    assert_eq!(
        policy
            .validate_url("javascript:alert(1)")
            .unwrap_err()
            .code(),
        PreviewErrorCode::SchemeNotAllowed
    );
}

/// 确认 userinfo、控制字符、反斜杠与非法百分号编码不能绕过 URL 的显示和安全语义。
#[test]
fn rejects_userinfo_controls_backslash_and_bad_percent_encoding() {
    let policy = PreviewPolicy::new().expect("policy");
    for raw in [
        "https://user:password@example.test/",
        "https://@example.test/",
        "https://example.test/%0a",
        "https://example.test/%5c",
        "https://example.test/%zz",
        "https://example.test/unterminated%",
        "https://example.test/a\\b",
    ] {
        assert!(policy.validate_url(raw).is_err(), "accepted {raw:?}");
    }
}

/// 确认反序列化仍经过统一策略，wire payload 不能直接构造未经校验的 PreviewUrl。
#[test]
fn url_deserialization_cannot_bypass_policy() {
    assert!(serde_json::from_str::<PreviewUrl>(r#""file:///secret""#).is_err());
    assert!(serde_json::from_str::<PreviewUrl>(r#""https://example.test/""#).is_ok());
}

#[test]
/// 确认新会话显式进入 Loading，并且只产生一个受队列预算约束的打开事件。
fn open_emits_snapshot_and_event() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    assert_eq!(opened.snapshot.status, PreviewSessionStatus::Open);
    assert_eq!(opened.snapshot.load_status, PreviewLoadStatus::Loading);
    assert_eq!(manager.active_count().expect("count"), 1);
    let events = manager.drain_events(opened.snapshot.id, 8).expect("events");
    assert!(matches!(events[0].kind, PreviewEventKind::Opened { .. }));
}

/// 空白页仍走相同加载完成闭环；匹配 completion 后 watchdog 不会再把它视作 Loading。
#[test]
fn blank_page_finishes_when_engine_reports_about_blank() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open_blank().expect("open blank");
    assert_eq!(opened.snapshot.url.as_str(), "about:blank");
    assert_eq!(opened.snapshot.load_status, PreviewLoadStatus::Loading);
    assert_eq!(
        crate::preview::commands::current_loading_generation(&manager, opened.snapshot.id),
        Some(opened.snapshot.generation)
    );

    let event = manager
        .callback_load_finished(
            opened.snapshot.id,
            opened.snapshot.generation,
            "about:blank",
        )
        .expect("about:blank completion")
        .expect("finished event");
    assert!(matches!(event.kind, PreviewEventKind::LoadFinished { .. }));
    assert_eq!(
        manager
            .snapshot(opened.snapshot.id)
            .expect("finished snapshot")
            .load_status,
        PreviewLoadStatus::Finished
    );
    assert!(
        crate::preview::commands::current_loading_generation(&manager, opened.snapshot.id)
            .is_none()
    );
    assert!(
        manager
            .callback_load_finished(
                opened.snapshot.id,
                opened.snapshot.generation,
                "about:blank",
            )
            .expect("duplicate completion")
            .is_none()
    );
}

/// 原生可见性只在平台操作成功后推进，重复 resize 可据此跳过 WebView2 show。
#[test]
fn native_visibility_is_internal_and_explicitly_committed() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    let id = opened.snapshot.id;

    assert!(!manager.native_visible(id).expect("initial visibility"));
    manager
        .commit_native_visibility(id, true)
        .expect("show committed");
    assert!(manager.native_visible(id).expect("visible"));
    manager
        .commit_native_visibility(id, false)
        .expect("hide committed");
    assert!(!manager.native_visible(id).expect("hidden"));
}

#[test]
/// 模拟创建子 WebView 时同步到达的回调，确保返回值重读最新代际与 URL，而非返回陈旧快照。
fn authoritative_open_result_observes_creation_callback_generation() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    let event = manager
        .callback_navigation(
            opened.snapshot.id,
            opened.snapshot.generation,
            "https://example.test/redirected",
        )
        .expect("creation callback");
    let authoritative = manager
        .authoritative_open_result(opened.snapshot.id)
        .expect("authoritative result");
    assert_eq!(authoritative.snapshot.generation, event.generation);
    assert_eq!(
        authoritative.snapshot.url.as_str(),
        "https://example.test/redirected"
    );
}

/// 确认每次导航推进代际，并拒绝旧页面在新事实提交后回写陈旧状态。
#[test]
fn navigation_advances_generation_and_rejects_stale_callback() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    let committed = manager
        .navigate(
            opened.snapshot.id,
            opened.snapshot.generation,
            NavigationSource::User,
            "http://localhost:8080/",
        )
        .expect("navigate");
    assert!(committed.generation > opened.snapshot.generation);
    assert_eq!(
        manager
            .callback_navigation(
                opened.snapshot.id,
                opened.snapshot.generation,
                "https://example.test/stale",
            )
            .unwrap_err()
            .code(),
        PreviewErrorCode::StaleGeneration
    );
}

#[test]
/// 确认外部标题与错误文本会被截断，失败状态保持明确且不会留存无界 payload。
fn title_and_load_error_callbacks_are_bounded() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    let title = "界".repeat(2000);
    let event = manager
        .callback_title(opened.snapshot.id, opened.snapshot.generation, &title)
        .expect("title");
    let PreviewEventKind::TitleChanged { title } = event.kind else {
        panic!("title event expected");
    };
    assert!(title.len() <= PreviewLimits::default().max_title_bytes);
    let error = manager
        .callback_load_error(
            opened.snapshot.id,
            opened.snapshot.generation,
            &"x".repeat(9000),
        )
        .expect("error");
    assert!(matches!(error.kind, PreviewEventKind::LoadFailed { .. }));
    assert_eq!(
        manager
            .snapshot(opened.snapshot.id)
            .expect("snapshot")
            .load_status,
        PreviewLoadStatus::Failed
    );
}

#[test]
/// 确认导航提交后继续保持 Loading，只有匹配的引擎 Finished 回调才能幂等记录完成事实。
fn finished_callback_is_distinct_from_navigation_commitment() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    assert!(
        manager
            .callback_load_finished(
                opened.snapshot.id,
                opened.snapshot.generation,
                "chrome-error://chromewebdata/",
            )
            .expect("mismatched finish")
            .is_none()
    );
    assert_eq!(
        manager
            .snapshot(opened.snapshot.id)
            .expect("still loading")
            .load_status,
        PreviewLoadStatus::Loading
    );
    let finished = manager
        .callback_load_finished(
            opened.snapshot.id,
            opened.snapshot.generation,
            "https://example.test/",
        )
        .expect("finish")
        .expect("finish event");
    assert!(matches!(
        finished.kind,
        PreviewEventKind::LoadFinished { .. }
    ));
    assert_eq!(
        manager
            .snapshot(opened.snapshot.id)
            .expect("snapshot")
            .load_status,
        PreviewLoadStatus::Finished
    );
    assert!(
        manager
            .callback_load_finished(
                opened.snapshot.id,
                opened.snapshot.generation,
                "https://example.test/",
            )
            .expect("duplicate finish")
            .is_none()
    );
}

#[test]
/// 确认 native 失败对当前代际是终态，即使 Wry 随后为 WebView2 内置错误页报告匹配 URL 的 Finished。
fn finished_callback_cannot_overwrite_failed_navigation() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    manager
        .callback_load_error(
            opened.snapshot.id,
            opened.snapshot.generation,
            "preview navigation failed",
        )
        .expect("failure");

    assert!(
        manager
            .callback_load_finished(
                opened.snapshot.id,
                opened.snapshot.generation,
                "https://example.test/",
            )
            .expect("late finish")
            .is_none()
    );
    assert_eq!(
        manager
            .snapshot(opened.snapshot.id)
            .expect("snapshot")
            .load_status,
        PreviewLoadStatus::Failed
    );
}

#[test]
/// 将 native close 失败建模为释放 claim，确认只有后续 ACK 才能删除身份与重放队列。
fn native_close_failure_retains_identity_and_ack_finalize_removes_it() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/1").expect("open");
    let first_ticket = manager
        .prepare_close(opened.snapshot.id)
        .expect("prepare close");
    assert_eq!(
        manager
            .navigate(
                opened.snapshot.id,
                opened.snapshot.generation,
                NavigationSource::User,
                "https://example.test/blocked",
            )
            .expect_err("closing session must reject navigation")
            .code(),
        PreviewErrorCode::SessionClosing
    );
    manager
        .abort_close(&first_ticket)
        .expect("failed native close retains identity");
    assert_eq!(manager.active_count().expect("retained count"), 1);
    let retry = manager
        .prepare_close(opened.snapshot.id)
        .expect("retry close");
    let closed = manager.finalize_close(retry).expect("close ACK");
    assert_eq!(closed.status, PreviewSessionStatus::Closed);
    assert_eq!(manager.active_count().expect("released count"), 0);
    assert_eq!(
        manager
            .snapshot(opened.snapshot.id)
            .expect_err("finalized session is removed")
            .code(),
        PreviewErrorCode::SessionNotFound
    );
}

#[test]
/// 确认重复 ACK-close 不保留已关闭的重放队列或 tombstone。
fn repeated_acknowledged_closes_do_not_accumulate_sessions_or_events() {
    let manager = PreviewManager::default_manager().expect("manager");
    for index in 0..128 {
        let opened = manager
            .open(&format!("https://example.test/{index}"))
            .expect("open after prior finalize");
        manager
            .callback_title(opened.snapshot.id, opened.snapshot.generation, "bounded")
            .expect("event");
        let ticket = manager.prepare_close(opened.snapshot.id).expect("prepare");
        manager.finalize_close(ticket).expect("finalize");
        assert_eq!(manager.active_count().expect("count"), 0);
    }
}

#[test]
/// 确认 manager 关停门是单调的，并保留已有 native 身份供宿主执行 ACK-first 清理。
fn manager_shutdown_fence_is_permanent_and_preserves_native_identity() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    manager.begin_shutdown().expect("begin shutdown");
    manager.begin_shutdown().expect("repeat shutdown");
    assert_eq!(manager.active_count().expect("identity retained"), 1);
    assert_eq!(
        manager
            .open("https://example.test/late")
            .expect_err("late open")
            .code(),
        PreviewErrorCode::ShutdownStarted
    );
    assert_eq!(
        manager
            .navigation_request(
                opened.snapshot.id,
                opened.snapshot.generation,
                NavigationSource::User,
                "https://example.test/late",
            )
            .expect_err("late navigation")
            .code(),
        PreviewErrorCode::ShutdownStarted
    );
}

/// 确认事件队列超过预算时只丢弃旧历史，并显式累计丢弃数量供 UI 重读权威快照。
#[test]
fn event_queue_is_bounded_and_drops_old_history() {
    let policy = PreviewPolicy::with_limits(PreviewLimits {
        max_event_count: 2,
        ..PreviewLimits::default()
    })
    .expect("policy");
    let manager = PreviewManager::new(policy).expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    manager
        .callback_title(opened.snapshot.id, opened.snapshot.generation, "one")
        .expect("title");
    manager
        .callback_title(opened.snapshot.id, opened.snapshot.generation, "two")
        .expect("title");
    assert!(
        manager
            .snapshot(opened.snapshot.id)
            .expect("snapshot")
            .dropped_events
            > 0
    );
}

/// 确认 tab 数量不再受原生 adapter 的旧八页产品上限约束。
#[test]
fn preview_manager_has_no_eight_session_product_cap() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = (0..16)
        .map(|index| {
            manager
                .open(&format!("https://example.test/{index}"))
                .expect("open without product cap")
        })
        .collect::<Vec<_>>();
    assert_eq!(manager.active_count().expect("active count"), 16);
    for page in opened {
        let ticket = manager
            .prepare_close(page.snapshot.id)
            .expect("prepare close");
        manager.finalize_close(ticket).expect("close ACK");
    }
    assert_eq!(manager.active_count().expect("empty after close"), 0);
}

/// 远程页面只有在主 UI 显式签发精确文件目标后才能导航到 file://。
#[test]
fn local_navigation_requires_an_explicit_exact_file_grant() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("remote page");
    let target = url::Url::parse("file:///C:/work/说明%20文件.html").expect("file URL");
    assert_eq!(
        manager
            .navigation_request(
                opened.snapshot.id,
                opened.snapshot.generation,
                NavigationSource::User,
                target.as_str(),
            )
            .expect_err("remote page cannot self-navigate to files")
            .code(),
        PreviewErrorCode::SchemeNotAllowed
    );
    manager
        .local_file_navigation_request(
            opened.snapshot.id,
            opened.snapshot.generation,
            target.as_str(),
        )
        .expect("explicit user grant");
    let event = manager
        .callback_navigation(
            opened.snapshot.id,
            opened.snapshot.generation,
            target.as_str(),
        )
        .expect("matching WebView callback");
    assert!(matches!(
        event.kind,
        PreviewEventKind::NavigationCommitted { ref url, .. } if url.as_str() == target.as_str()
    ));
    assert_eq!(
        manager
            .snapshot(opened.snapshot.id)
            .expect("snapshot")
            .generation,
        2
    );
}

/// 受控 Back/Forward 只能给下一次 history callback 一次文件通行权，未匹配来源或过期许可不能放宽远程页面。
#[test]
fn file_history_navigation_uses_one_shot_ui_grant() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager
        .open("https://example.test/start")
        .expect("remote page");
    let id = opened.snapshot.id;
    let file_url = "file:///C:/work/preview.html";

    manager
        .local_file_navigation_request(id, opened.snapshot.generation, file_url)
        .expect("explicit local file open");
    let file_event = manager
        .callback_navigation(id, opened.snapshot.generation, file_url)
        .expect("file open callback");
    let file_generation = file_event.generation;

    let back_token = manager
        .prepare_history_navigation(id, file_generation)
        .expect("controlled Back grant");
    let back_event = manager
        .callback_navigation(id, file_generation, "https://example.test/start")
        .expect("Back to remote history item");
    assert_eq!(back_event.generation, file_generation + 1);

    let forward_token = manager
        .prepare_history_navigation(id, back_event.generation)
        .expect("controlled Forward grant");
    let forward_event = manager
        .callback_navigation(id, back_event.generation, file_url)
        .expect("Forward to prior local history item");
    assert_eq!(forward_event.generation, back_event.generation + 1);
    assert_eq!(
        manager.snapshot(id).expect("snapshot").url.as_str(),
        file_url
    );

    // 后续远程导航离开本地页后，已消费的 grant 不能重复使用。
    manager
        .callback_navigation(id, forward_event.generation, "https://example.test/again")
        .expect("remote history item");
    let remote_generation = manager.snapshot(id).expect("remote snapshot").generation;
    assert_eq!(
        manager
            .callback_navigation(id, remote_generation, file_url)
            .expect_err("remote page cannot repeat history file access")
            .code(),
        PreviewErrorCode::SchemeNotAllowed
    );

    // 较早 timer 只清除自己的 token，不能撤销较新的 Back/Forward 操作。
    let expired = manager
        .prepare_history_navigation(id, remote_generation)
        .expect("old grant");
    manager
        .clear_pending_history_navigation(id, expired)
        .expect("expire old grant");
    let active = manager
        .prepare_history_navigation(id, remote_generation)
        .expect("new grant");
    manager
        .clear_pending_history_navigation(id, expired)
        .expect("old timer cleanup");
    let allowed = manager
        .callback_navigation(id, remote_generation, file_url)
        .expect("new grant remains active");
    assert_eq!(allowed.generation, remote_generation + 1);
    manager
        .clear_pending_history_navigation(id, active)
        .expect("consumed grant cleanup is idempotent");
    assert_eq!(back_token + 1, forward_token);
}

/// 未触发 history callback 的 timer 到期后撤销一次性 grant，不允许任意远程 redirect 借用许可。
#[test]
fn expired_file_history_grant_cannot_authorize_remote_redirect() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager
        .open("https://example.test/start")
        .expect("remote page");
    let token = manager
        .prepare_history_navigation(opened.snapshot.id, opened.snapshot.generation)
        .expect("controlled history grant");
    manager
        .clear_pending_history_navigation(opened.snapshot.id, token)
        .expect("timeout cleanup");

    assert_eq!(
        manager
            .callback_navigation(
                opened.snapshot.id,
                opened.snapshot.generation,
                "file:///C:/work/redirected.html",
            )
            .expect_err("expired grant cannot authorize file redirect")
            .code(),
        PreviewErrorCode::SchemeNotAllowed
    );
}

/// 仅 WebView2 HistoryChanged 更新前进/后退状态，陈旧 generation 必须被拒绝。
#[test]
fn history_snapshot_tracks_native_capabilities_and_rejects_stale_events() {
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    let event = manager
        .callback_history_changed(opened.snapshot.id, opened.snapshot.generation, true, false)
        .expect("history event")
        .expect("state changed");
    assert!(matches!(
        event.kind,
        PreviewEventKind::HistoryChanged {
            can_go_back: true,
            can_go_forward: false
        }
    ));
    let snapshot = manager.snapshot(opened.snapshot.id).expect("snapshot");
    assert!(snapshot.can_go_back);
    assert!(!snapshot.can_go_forward);
    assert_eq!(
        manager
            .callback_history_changed(
                opened.snapshot.id,
                opened.snapshot.generation + 1,
                false,
                true
            )
            .expect_err("stale generation")
            .code(),
        PreviewErrorCode::StaleGeneration
    );
}
