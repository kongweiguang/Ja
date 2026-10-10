// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ja_cli::ui::{UiAction, UiCommand, UiEvent, UiSnapshot, UiState, UiUsageSnapshot, render};
use ja_runtime::app_server_process::{
    ProviderRequestProfileDto, ThreadContextUsageDto, ThreadUsageSummary,
};
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::style::Color;

/// 使用完整账本样本，区分含缓存输入与未缓存输入，避免把缓存再次叠加到总量。
fn usage() -> UiUsageSnapshot {
    UiUsageSnapshot {
        summary: Some(ThreadUsageSummary {
            thread_id: "thr_usage".into(),
            snapshot_revision: 4,
            request_count: 1,
            measured_request_count: 1,
            new_input_request_count: 1,
            new_input_tokens: 2_000,
            output_request_count: 1,
            output_tokens: 449,
            total_request_count: 1,
            total_tokens: 26_449,
            cache_read_request_count: 1,
            cache_read_tokens: 23_000,
            cache_write_request_count: 1,
            cache_write_tokens: 1_000,
            cache_complete_request_count: 1,
            cache_complete_input_tokens: 26_000,
            cache_complete_read_tokens: 23_000,
        }),
        stale: false,
        context: Some(ThreadContextUsageDto {
            turn_id: "turn_usage".into(),
            model_request_id: "modelreq_usage".into(),
            model_request_ordinal: 1,
            model_round: 1,
            purpose: "assistant".into(),
            certainty: "known".into(),
            profile: ProviderRequestProfileDto {
                provider_id: "provider_usage".into(),
                model_id: "model_usage".into(),
                api: "openai_responses".into(),
                upstream_model: "gpt-6-luna".into(),
                requested_reasoning: None,
                effective_reasoning: None,
                access_mode: "approval_required".into(),
                collaboration_mode: "default".into(),
                config_generation: "cfg_usage".into(),
                prompt_revision: "prompt_usage".into(),
                tool_catalog_revision: "tools_usage".into(),
                context_window_tokens: 1_000_000,
                max_output_tokens: 16_000,
            },
            input_tokens: Some(26_000),
            output_tokens: Some(449),
            total_tokens: Some(26_449),
            measured_at: "2026-10-09T08:00:00Z".into(),
            context_estimate: None,
        }),
    }
}

/// 真实渲染入口加载完整 UiState，使布局、操作提示和字素宽度一起参与验收。
fn state_with_usage(usage: UiUsageSnapshot) -> UiState {
    UiState::new(UiSnapshot {
        thread_id: Some("thr_usage".into()),
        thread_title: Some("中文标题🙂".into()),
        model_identifier: Some("gpt-6-luna".into()),
        workspace_path: Some(r"C:\dev\rust\ja".into()),
        usage: Some(usage),
        ..UiSnapshot::default()
    })
}

/// 输出按终端 cell 去除宽字符后继占位，只保留真实文字以断言完整字段而非手写排版。
fn rows(state: &UiState, width: u16, height: u16) -> (Vec<String>, Terminal<TestBackend>) {
    let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
    terminal.draw(|frame| render(frame, state)).unwrap();
    let rows = terminal
        .backend()
        .buffer()
        .content()
        .chunks(width as usize)
        .map(|row| {
            row.iter()
                .map(|cell| cell.symbol())
                .collect::<String>()
                .replace(' ', "")
        })
        .collect();
    (rows, terminal)
}

/// 用量独占第二行；空闲帮助与实际操作反馈共用第三行，不侵入会话身份。
#[test]
fn usage_footer_keeps_identity_metrics_and_actions_separate() {
    let state = state_with_usage(usage());
    let (rows, _) = rows(&state, 120, 24);
    assert!(rows[21].contains("gpt-6-luna"), "{rows:?}");
    assert!(rows[21].contains("中文标题🙂"), "{rows:?}");
    assert!(rows[22].contains("累计26k输入26k输出449"), "{rows:?}");
    assert!(rows[22].contains("含缓存读23k"), "{rows:?}");
    assert!(!rows[22].contains("含缓存写"), "{rows:?}");
    assert!(!rows[22].contains("命中"), "{rows:?}");
    assert!(rows[22].contains("上下文26k/1.0M2.6%"), "{rows:?}");
    assert!(!rows[21].contains('?'), "{rows:?}");
    assert!(rows[23].contains("?查看快捷键"), "{rows:?}");
}

/// 窄窗只按最大读数预算决定字段组合，不能利用当前数字短暂变小反复增删标签。
#[test]
fn narrow_footer_removes_whole_optional_metrics_in_priority_order() {
    let state = state_with_usage(usage());
    let (medium, _) = rows(&state, 65, 24);
    assert!(!medium[22].contains("缓存写"), "{medium:?}");
    assert!(!medium[22].contains("缓存读"), "{medium:?}");
    assert!(medium[22].contains("输入26k输出449"), "{medium:?}");
    let (narrow, _) = rows(&state, 55, 24);
    assert!(!narrow[22].contains("缓存读"), "{narrow:?}");
    assert!(!narrow[22].contains("输入"), "{narrow:?}");
    assert!(narrow[22].contains("上下文26k/1.0M2.6%"), "{narrow:?}");
    let (compact, _) = rows(&state, 40, 24);
    assert!(compact[22].contains("累计26k"), "{compact:?}");
    assert!(compact[22].contains("上下文2.6%"), "{compact:?}");
    assert!(!compact[22].contains("输入"), "{compact:?}");
    let (brief, _) = rows(&state, 32, 24);
    assert!(brief[22].contains("累计26k"), "{brief:?}");
    assert!(brief[22].contains("上下文2.6%"), "{brief:?}");
    assert!(!brief[22].contains("/1.0M"), "{brief:?}");
    assert!(!brief[22].contains("缓存"), "{brief:?}");
    let (tiny, _) = rows(&state, 12, 24);
    assert_eq!(tiny[22], "2.6%");
}

/// 真实 cell 位置对比覆盖首次读取、零缓存、估算/实测和刷新失败，防止同宽终端的文字再次跳位。
#[test]
fn footer_labels_and_columns_remain_fixed_through_usage_updates() {
    let mut missing = usage();
    missing.summary = None;
    missing.context = None;
    let mut zero_cache = usage();
    zero_cache.summary.as_mut().unwrap().cache_read_tokens = 0;
    let mut unknown_cache = usage();
    unknown_cache
        .summary
        .as_mut()
        .unwrap()
        .cache_read_request_count = 0;
    let mut estimated = usage();
    let context = estimated.context.as_mut().unwrap();
    context.certainty = "unknown".into();
    context.total_tokens = None;
    context.context_estimate = Some(ja_runtime::app_server_process::ThreadContextEstimateDto {
        certainty: "estimated".into(),
        source: "local".into(),
        used_tokens: 54_000,
        limit_tokens: 1_000_000,
        measured_at: "2026-10-09T08:00:01Z".into(),
    });
    let mut stale = usage();
    stale.stale = true;
    stale.summary.as_mut().unwrap().request_count = 3;
    let mut large = usage();
    let summary = large.summary.as_mut().unwrap();
    summary.total_tokens = u64::MAX;
    summary.output_tokens = 9_950;
    summary.cache_read_tokens = u64::MAX / 2;
    let context = large.context.as_mut().unwrap();
    context.total_tokens = Some(u64::MAX);
    context.profile.context_window_tokens = 1;
    let updates = [
        missing,
        zero_cache,
        unknown_cache,
        estimated,
        stale,
        large,
        usage(),
    ];

    for width in [
        120, 78, 77, 65, 63, 62, 55, 41, 40, 34, 29, 28, 24, 20, 17, 16, 12,
    ] {
        let mut expected_columns = None;
        for value in &updates {
            let (visible, terminal) = rows(&state_with_usage(value.clone()), width, 24);
            let columns = terminal
                .backend()
                .buffer()
                .content()
                .chunks(width as usize)
                .nth(22)
                .unwrap()
                .iter()
                .enumerate()
                .filter(|(_, cell)| ["累", "输", "出", "含", "上"].contains(&cell.symbol()))
                .map(|(column, cell)| (column, cell.symbol().to_owned()))
                .collect::<Vec<_>>();
            if let Some(expected) = &expected_columns {
                assert_eq!(&columns, expected, "width={width}, row={}", visible[22]);
            } else {
                expected_columns = Some(columns);
            }
            assert!(!visible[22].contains("约"), "{visible:?}");
            assert!(!visible[22].contains("正在读取"), "{visible:?}");
            assert!(!visible[22].contains("待刷新"), "{visible:?}");
            assert!(!visible[22].contains("已报告"), "{visible:?}");
            assert!(!visible[22].contains("缓存写"), "{visible:?}");
            if width >= 78 {
                for label in ["累计", "输入", "输出", "含缓存读", "上下文"] {
                    assert!(visible[22].contains(label), "{visible:?}");
                }
                assert!(visible[22].ends_with('%'), "{visible:?}");
            }
        }
    }
    let (zero, _) = rows(&state_with_usage(updates[1].clone()), 120, 24);
    assert!(zero[22].contains("含缓存读0"), "{zero:?}");
    let (unknown, _) = rows(&state_with_usage(updates[2].clone()), 120, 24);
    assert!(unknown[22].contains("含缓存读—"), "{unknown:?}");
}

/// 同组总量减输出才是含缓存输入；相同的部分覆盖数也不能证明来自同一组请求。
#[test]
fn cumulative_input_requires_complete_matching_measured_coverage() {
    let mut value = usage();
    assert_eq!(value.cumulative_input_tokens(), Some(26_000));
    let summary = value.summary.as_mut().unwrap();
    summary.new_input_request_count = 0;
    summary.new_input_tokens = 0;
    summary.cache_read_request_count = 0;
    summary.cache_read_tokens = 0;
    assert_eq!(value.cumulative_input_tokens(), Some(26_000));

    let summary = value.summary.as_mut().unwrap();
    summary.request_count = 3;
    summary.measured_request_count = 2;
    // 两个字段同为一个样本，仍可能分别覆盖不同请求，不能相减。
    assert_eq!(value.cumulative_input_tokens(), None);
    let (partial, _) = rows(&state_with_usage(value.clone()), 120, 24);
    assert!(partial[22].contains("累计26k输入—"), "{partial:?}");

    let summary = value.summary.as_mut().unwrap();
    summary.total_request_count = 2;
    summary.output_request_count = 2;
    summary.total_tokens = 400;
    summary.output_tokens = 449;
    assert_eq!(value.cumulative_input_tokens(), None);
}

/// 缓存命中影响输入组成和计费口径，不减少请求实际占用，也不能从累计总量再次扣除缓存。
#[test]
fn cache_hit_changes_do_not_reduce_total_or_context() {
    let mut uncached = usage();
    let summary = uncached.summary.as_mut().unwrap();
    summary.new_input_tokens = 26_000;
    summary.cache_read_tokens = 0;
    summary.cache_write_tokens = 0;
    summary.cache_complete_read_tokens = 0;
    for value in [usage(), uncached] {
        let (visible, _) = rows(&state_with_usage(value), 120, 24);
        assert!(visible[22].contains("累计26k输入26k输出449"), "{visible:?}");
        assert!(visible[22].contains("上下文26k/1.0M2.6%"), "{visible:?}");
    }
}

/// 最近请求的估算可被较低实测校正；跨请求累计仍由独立账本增加，草稿不能影响占用。
#[test]
fn measured_context_can_correct_downward_without_decreasing_cumulative_usage() {
    let mut estimated = usage();
    let context = estimated.context.as_mut().unwrap();
    context.certainty = "unknown".into();
    context.total_tokens = None;
    context.context_estimate = Some(ja_runtime::app_server_process::ThreadContextEstimateDto {
        certainty: "estimated".into(),
        source: "local".into(),
        used_tokens: 54_000,
        limit_tokens: 1_000_000,
        measured_at: "2026-10-09T08:00:01Z".into(),
    });
    let mut state = state_with_usage(estimated);
    state.handle_paste("未发送的草稿不参与用量");
    let (before, _) = rows(&state, 120, 24);
    assert!(before[22].contains("上下文54k/1.0M5.4%"), "{before:?}");

    let mut measured = usage();
    let summary = measured.summary.as_mut().unwrap();
    summary.total_tokens = 127_000;
    summary.output_tokens = 2_000;
    measured.context.as_mut().unwrap().total_tokens = Some(45_000);
    state.apply(UiEvent::SetUsage {
        thread_id: "thr_usage".into(),
        usage: Some(Box::new(measured)),
    });
    let (after, _) = rows(&state, 120, 24);
    assert!(after[22].contains("累计127k输入125k输出2.0k"), "{after:?}");
    assert!(after[22].contains("上下文45k/1.0M4.5%"), "{after:?}");
    assert!(!after[22].contains("上下文约"), "{after:?}");
    assert_eq!(state.composer().text(), "未发送的草稿不参与用量");
}

/// 极矮终端优先保留正在编辑的内容和可执行提示，不为用量牺牲输入区。
#[test]
fn short_footer_yields_usage_room_to_the_composer() {
    let mut state = state_with_usage(usage());
    state.handle_paste("中文🙂");
    for (width, height) in [(20, 3), (40, 2)] {
        let (rows, _) = rows(&state, width, height);
        assert!(rows.iter().any(|row| row.contains('›')), "{rows:?}");
        assert!(!rows.iter().any(|row| row.contains("上下文")), "{rows:?}");
    }
}

/// UNKNOWN 不能复活旧数字，估算转实测不改变标签；微小正数仍保留原显示精度。
#[test]
fn context_certainty_and_small_positive_percent_remain_visible() {
    let mut value = usage();
    let context = value.context.as_mut().unwrap();
    context.input_tokens = Some(143);
    context.output_tokens = Some(0);
    context.total_tokens = Some(143);
    let (small, _) = rows(&state_with_usage(value.clone()), 120, 24);
    assert!(small[22].contains("上下文143/1.0M<0.1%"), "{small:?}");
    let context = value.context.as_mut().unwrap();
    context.certainty = "unknown".into();
    context.input_tokens = None;
    context.output_tokens = None;
    context.total_tokens = None;
    let (unknown, _) = rows(&state_with_usage(value.clone()), 120, 24);
    assert!(unknown[22].contains("上下文—/1.0M—%"), "{unknown:?}");
    value.context.as_mut().unwrap().context_estimate =
        Some(ja_runtime::app_server_process::ThreadContextEstimateDto {
            certainty: "estimated".into(),
            source: "local".into(),
            used_tokens: 38_000,
            limit_tokens: 1_000_000,
            measured_at: "2026-10-09T08:00:01Z".into(),
        });
    let (estimated, _) = rows(&state_with_usage(value), 120, 24);
    assert!(
        estimated[22].contains("上下文38k/1.0M3.8%"),
        "{estimated:?}"
    );
}

/// 读取失败保留数字和标签，仅操作行提供 F5；未计量用占位值而非零，覆盖范围留在状态详情。
#[test]
fn stale_and_partial_usage_keep_honest_coverage() {
    let mut value = usage();
    value.stale = true;
    value.summary.as_mut().unwrap().request_count = 2;
    let (partial, _) = rows(&state_with_usage(value.clone()), 120, 24);
    assert!(partial[22].contains("累计26k输入26k输出449"), "{partial:?}");
    assert!(!partial[22].contains("待刷新"), "{partial:?}");
    assert!(!partial[22].contains("已报告"), "{partial:?}");
    assert!(!partial[21].contains('?'), "{partial:?}");
    assert!(partial[23].contains("F5刷新用量"), "{partial:?}");
    let summary = value.summary.as_mut().unwrap();
    summary.measured_request_count = 0;
    summary.new_input_request_count = 0;
    summary.new_input_tokens = 0;
    summary.output_request_count = 0;
    summary.output_tokens = 0;
    summary.total_request_count = 0;
    summary.total_tokens = 0;
    summary.cache_read_request_count = 0;
    summary.cache_read_tokens = 0;
    summary.cache_write_request_count = 0;
    summary.cache_write_tokens = 0;
    summary.cache_complete_request_count = 0;
    summary.cache_complete_input_tokens = 0;
    summary.cache_complete_read_tokens = 0;
    let (unmeasured, _) = rows(&state_with_usage(value), 120, 24);
    assert!(
        unmeasured[22].contains("累计—输入—输出—含缓存读—"),
        "{unmeasured:?}"
    );
    assert!(!unmeasured[22].contains("累计0"), "{unmeasured:?}");
}

/// 用量重试不夺走草稿、错误或审批的操作提示，窄窗和隐藏用量的矮窗也可发现 F5。
#[test]
fn stale_usage_retry_hint_yields_to_chat_feedback_and_preserves_a_draft() {
    let mut value = usage();
    value.stale = true;
    value.summary = None;
    let mut state = state_with_usage(value);
    state.handle_paste("保留草稿🙂");
    let (wide, _) = rows(&state, 120, 24);
    assert!(wide[23].contains("F5刷新用量"), "{wide:?}");
    let (short, _) = rows(&state, 20, 3);
    assert!(short[2].contains("F5"), "{short:?}");
    assert_eq!(
        state.handle_key(KeyEvent::new(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::RefreshSnapshot],
    );
    assert_eq!(state.composer().text(), "保留草稿🙂");

    state.apply(UiEvent::SetNotice(Some("聊天读取失败".into())));
    let (error, _) = rows(&state, 120, 24);
    assert!(error[23].contains("聊天读取失败"), "{error:?}");
    assert!(!error[23].contains("F5刷新用量"), "{error:?}");
}

/// 状态色使用与桌面一致的阈值，超过窗口的真实占用仍保留原始百分比。
#[test]
fn context_warning_thresholds_match_desktop() {
    for (tokens, expected_color) in [
        (799_000, Color::Reset),
        (800_000, Color::Yellow),
        (950_000, Color::Red),
    ] {
        let mut value = usage();
        let context = value.context.as_mut().unwrap();
        context.input_tokens = Some(tokens);
        context.output_tokens = Some(0);
        context.total_tokens = Some(tokens);
        let (_, terminal) = rows(&state_with_usage(value), 120, 24);
        let context = terminal
            .backend()
            .buffer()
            .content()
            .chunks(120)
            .nth(22)
            .unwrap();
        assert!(
            context
                .iter()
                .any(|cell| cell.symbol() == "上" && cell.fg == expected_color)
        );
    }
}

/// 用量增量只更新底栏读数与操作提示，不改变正文、草稿或光标；旧 Thread 的迟到事件在 reducer 丢弃。
#[test]
fn usage_event_preserves_editing_and_rejects_a_different_thread() {
    let mut state = state_with_usage(usage());
    state.handle_paste("保留中文🙂");
    state.handle_key(KeyEvent::new(KeyCode::Left, KeyModifiers::NONE));
    let cursor = state.composer().cursor();
    let (before, _) = rows(&state, 120, 24);
    let mut updated = usage();
    updated.stale = true;
    state.apply(UiEvent::SetUsage {
        thread_id: "thr_old".into(),
        usage: Some(Box::new(updated.clone())),
    });
    assert!(!state.snapshot().usage.as_ref().unwrap().stale);
    state.apply(UiEvent::SetUsage {
        thread_id: "thr_usage".into(),
        usage: Some(Box::new(updated)),
    });
    assert!(state.snapshot().usage.as_ref().unwrap().stale);
    assert_eq!(state.composer().text(), "保留中文🙂");
    assert_eq!(state.composer().cursor(), cursor);
    let (after, _) = rows(&state, 120, 24);
    assert_eq!(&after[..22], &before[..22]);
}

/// 状态详情的核实需重建精确读数正文；其他面板沿用普通刷新，草稿仍归原编辑器所有。
#[test]
fn status_f5_refreshes_the_detail_body_without_consuming_a_draft() {
    let mut state = state_with_usage(usage());
    state.handle_paste("保留草稿🙂");
    state.apply(UiEvent::ShowDetails {
        entry_id: "status".into(),
        title: "会话状态".into(),
        body: "累计摘要待刷新；按 F5 重试".into(),
    });
    assert_eq!(
        state.handle_key(KeyEvent::new(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::Command(UiCommand::ShowStatus)]
    );
    assert_eq!(state.composer().text(), "保留草稿🙂");
    state.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    assert_eq!(
        state.handle_key(KeyEvent::new(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::RefreshSnapshot]
    );
}

/// @author kongweiguang
/// Status 的即时缓存与迟到回读只更新仍打开的同一面板，并保留滚动锚点和编辑草稿。
#[test]
fn status_refresh_keeps_bottom_scroll_and_late_response_does_not_reopen_after_escape() {
    let mut state = state_with_usage(usage());
    state.handle_paste("保留草稿🙂");
    let body = |prefix: &str| {
        (1..=30)
            .map(|line| format!("{prefix}-{line:02}"))
            .collect::<Vec<_>>()
            .join("\n")
    };
    state.apply(UiEvent::ShowDetails {
        entry_id: "status".into(),
        title: "会话状态".into(),
        body: body("旧状态"),
    });
    state.handle_key(KeyEvent::new(KeyCode::End, KeyModifiers::NONE));
    assert_eq!(
        state.handle_key(KeyEvent::new(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::Command(UiCommand::ShowStatus)]
    );

    // Controller 先发缓存 ShowDetails；同 entry 的重发不能把已浏览位置拉回顶部。
    state.apply(UiEvent::ShowDetails {
        entry_id: "status".into(),
        title: "会话状态".into(),
        body: body("缓存"),
    });
    let (cached, _) = rows(&state, 80, 12);
    assert!(
        cached.iter().any(|row| row.contains("缓存-30")),
        "{cached:?}"
    );
    assert!(
        !cached.iter().any(|row| row.contains("缓存-01")),
        "{cached:?}"
    );

    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "status".into(),
        title: "会话状态".into(),
        body: body("最新"),
    });
    let (latest, _) = rows(&state, 80, 12);
    assert!(
        latest.iter().any(|row| row.contains("最新-30")),
        "{latest:?}"
    );
    assert!(
        !latest.iter().any(|row| row.contains("最新-01")),
        "{latest:?}"
    );
    assert_eq!(state.composer().text(), "保留草稿🙂");

    state.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "status".into(),
        title: "会话状态".into(),
        body: body("迟到"),
    });
    assert_eq!(
        state.handle_key(KeyEvent::new(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::RefreshSnapshot]
    );
    assert_eq!(state.composer().text(), "保留草稿🙂");
}
