// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// 直接读取父模块的私有状态来验证领域判断，避免在生产实现上保留仅测试可见的方法。
fn event_if_enabled(
    host: &NativeShortcutHost,
    command: NativeShortcutCommand,
) -> Result<Option<NativeShortcutEvent>, NativeShortcutError> {
    let state = host.lock_state()?;
    if !state.accepting || !state.renderer_ready || !state.context.enables(command) {
        return Ok(None);
    }
    Ok(Some(NativeShortcutEvent {
        epoch: state.renderer_epoch.to_string(),
        command,
        revision: state.context.revision,
    }))
}

/// 仅在测试模块内区分 registry empty 与 shutdown revoked，不扩大生产类型的访问面。
fn registry_is_empty(host: &NativeShortcutHost) -> bool {
    host.lock_state()
        .map(|state| {
            #[cfg(windows)]
            {
                state.installing.is_empty()
                    && state.registrations.is_empty()
                    && state.deferred_to_controller_close.is_empty()
            }
            #[cfg(not(windows))]
            {
                true
            }
        })
        .unwrap_or(false)
}

/// 构造一次非重复 keydown，使表驱动用例只改变必要字段。
#[cfg(windows)]
fn key(virtual_key: u32, modifiers: NativeModifiers) -> NativeKeyInput {
    NativeKeyInput {
        kind: NativeKeyEventKind::KeyDown,
        virtual_key,
        repeat_count: 1,
        was_key_down: false,
        modifiers,
    }
}

/// 五个组合必须精确映射到固定枚举，不能产生任意动作字符串。
#[cfg(windows)]
#[test]
fn classifier_maps_exact_five_shortcuts() {
    let control = NativeModifiers {
        control: true,
        ..NativeModifiers::default()
    };
    let review = NativeModifiers {
        control: true,
        shift: true,
        ..NativeModifiers::default()
    };
    let side_chat = NativeModifiers {
        control: true,
        alt: true,
        ..NativeModifiers::default()
    };
    assert_eq!(
        classify_shortcut(key(VK_G, review)),
        Some(NativeShortcutCommand::Review)
    );
    assert_eq!(
        classify_shortcut(key(VK_P, control)),
        Some(NativeShortcutCommand::Files)
    );
    assert_eq!(
        classify_shortcut(key(VK_OEM_3, control)),
        Some(NativeShortcutCommand::Terminal)
    );
    assert_eq!(
        classify_shortcut(key(VK_T, control)),
        Some(NativeShortcutCommand::Preview)
    );
    assert_eq!(
        classify_shortcut(key(VK_S, side_chat)),
        Some(NativeShortcutCommand::SideChat)
    );
}

/// key-up、auto-repeat、Win/额外 Shift 与未知键均不得被 handler 吞掉。
#[cfg(windows)]
#[test]
fn classifier_rejects_non_initial_or_extra_modifier_input() {
    let control = NativeModifiers {
        control: true,
        ..NativeModifiers::default()
    };
    assert_eq!(
        classify_shortcut(NativeKeyInput {
            kind: NativeKeyEventKind::Other,
            ..key(VK_P, control)
        }),
        None
    );
    assert_eq!(
        classify_shortcut(NativeKeyInput {
            was_key_down: true,
            ..key(VK_P, control)
        }),
        None
    );
    assert_eq!(
        classify_shortcut(NativeKeyInput {
            repeat_count: 2,
            ..key(VK_P, control)
        }),
        None
    );
    assert_eq!(
        classify_shortcut(key(
            VK_P,
            NativeModifiers {
                control: true,
                shift: true,
                ..NativeModifiers::default()
            },
        )),
        None
    );
    assert_eq!(classify_shortcut(key(0x41, control)), None);
}

/// AltGr 会表现为 Ctrl+RightAlt；即使按键是 S 也必须留给文本输入。
#[cfg(windows)]
#[test]
fn classifier_never_intercepts_alt_gr() {
    assert_eq!(
        classify_shortcut(NativeKeyInput {
            kind: NativeKeyEventKind::SystemKeyDown,
            ..key(
                VK_S,
                NativeModifiers {
                    control: true,
                    alt: true,
                    right_alt: true,
                    ..NativeModifiers::default()
                },
            )
        }),
        None
    );
}

/// 查询当前 Rust epoch，保持测试输入与真实 subscribe-before-update 流程一致。
fn current_epoch(host: &NativeShortcutHost) -> String {
    host.query_from_label(MAIN_WEBVIEW_LABEL)
        .expect("lease query")
        .epoch
}

/// 用当前 epoch prepare 一次 renderer context，确保测试也遵循严格 revision 协议。
fn prepare_context(
    host: &NativeShortcutHost,
    revision: u64,
    project_capabilities_enabled: bool,
    conversation_focus_enabled: bool,
) -> NativeShortcutContextSnapshot {
    host.update_from_label(
        MAIN_WEBVIEW_LABEL,
        NativeShortcutContextInput {
            epoch: current_epoch(host),
            revision,
            project_capabilities_enabled,
            conversation_focus_enabled,
        },
    )
    .expect("prepare context")
}

/// 先取得 prepare ACK 再消费 exact permit，复现 renderer 预置 identity 后的激活顺序。
fn activate_context(
    host: &NativeShortcutHost,
    revision: u64,
    project_capabilities_enabled: bool,
    conversation_focus_enabled: bool,
) -> NativeShortcutContextSnapshot {
    let prepared = prepare_context(
        host,
        revision,
        project_capabilities_enabled,
        conversation_focus_enabled,
    );
    assert!(!prepared.ready);
    host.activate_from_label(
        MAIN_WEBVIEW_LABEL,
        NativeShortcutActivationInput {
            epoch: prepared.epoch,
            revision: prepared.revision,
        },
    )
    .expect("activate context")
}

/// 项目动作与侧边聊天独立授权，ready 之前不会生成可 SetHandled 的事件。
#[test]
fn context_enables_only_declared_command_groups_after_activation() {
    let host = NativeShortcutHost::default();
    assert!(
        event_if_enabled(&host, NativeShortcutCommand::Files)
            .expect("unready files")
            .is_none()
    );
    let snapshot = activate_context(&host, 1, true, false);
    assert!(snapshot.ready);
    assert!(
        event_if_enabled(&host, NativeShortcutCommand::Files)
            .expect("files")
            .is_some()
    );
    assert!(
        event_if_enabled(&host, NativeShortcutCommand::SideChat)
            .expect("side chat")
            .is_none()
    );
}

/// rebind/query 会先 suspend 旧 ready lease，并保留 revision 供下一次 CAS 恢复。
#[test]
fn lease_query_suspends_ready_until_next_revision_update() {
    let host = NativeShortcutHost::default();
    let active = activate_context(&host, 1, true, true);
    assert!(active.ready);
    let suspended = host
        .query_from_label(MAIN_WEBVIEW_LABEL)
        .expect("suspend query");
    assert_eq!(suspended.epoch, active.epoch);
    assert_eq!(suspended.revision, 1);
    assert!(!suspended.ready);
    assert!(
        event_if_enabled(&host, NativeShortcutCommand::Review)
            .expect("suspended event")
            .is_none()
    );
    let prepared = prepare_context(&host, suspended.revision + 1, true, true);
    assert!(!prepared.ready);
    let resumed = host
        .activate_from_label(
            MAIN_WEBVIEW_LABEL,
            NativeShortcutActivationInput {
                epoch: prepared.epoch,
                revision: prepared.revision,
            },
        )
        .expect("resume activation");
    assert!(resumed.ready);
    assert_eq!(resumed.revision, 2);
}

/// epoch+revision 共同 CAS；旧值、同 revision 改值、非法 epoch 与非 main caller 都拒绝。
#[test]
fn context_epoch_revision_cas_is_strict_and_main_only() {
    let host = NativeShortcutHost::default();
    let epoch = current_epoch(&host);
    let first = NativeShortcutContextInput {
        epoch: epoch.clone(),
        revision: 7,
        project_capabilities_enabled: true,
        conversation_focus_enabled: true,
    };
    let prepared = host
        .update_from_label(MAIN_WEBVIEW_LABEL, first.clone())
        .expect("first prepare");
    assert!(!prepared.ready);
    assert_eq!(
        host.update_from_label(MAIN_WEBVIEW_LABEL, first.clone())
            .expect_err("duplicate prepare")
            .code,
        NativeShortcutErrorCode::StaleRevision
    );
    host.activate_from_label(
        MAIN_WEBVIEW_LABEL,
        NativeShortcutActivationInput {
            epoch: epoch.clone(),
            revision: 7,
        },
    )
    .expect("first activation");
    let suspended = host
        .query_from_label(MAIN_WEBVIEW_LABEL)
        .expect("suspend activation");
    assert!(!suspended.ready);
    assert_eq!(
        host.activate_from_label(
            MAIN_WEBVIEW_LABEL,
            NativeShortcutActivationInput {
                epoch: epoch.clone(),
                revision: 7,
            },
        )
        .expect_err("late duplicate activation")
        .code,
        NativeShortcutErrorCode::StaleRevision
    );
    assert_eq!(
        host.update_from_label(
            MAIN_WEBVIEW_LABEL,
            NativeShortcutContextInput {
                revision: 6,
                ..first.clone()
            },
        )
        .expect_err("stale revision")
        .code,
        NativeShortcutErrorCode::StaleRevision
    );
    assert_eq!(
        host.update_from_label(
            MAIN_WEBVIEW_LABEL,
            NativeShortcutContextInput {
                project_capabilities_enabled: false,
                ..first.clone()
            },
        )
        .expect_err("same revision mutation")
        .code,
        NativeShortcutErrorCode::StaleRevision
    );
    assert_eq!(
        host.update_from_label("preview_untrusted", first.clone())
            .expect_err("untrusted caller")
            .code,
        NativeShortcutErrorCode::CallerNotAllowed
    );
    assert_eq!(
        host.update_from_label(
            MAIN_WEBVIEW_LABEL,
            NativeShortcutContextInput {
                epoch: "not-an-epoch".to_owned(),
                ..first.clone()
            },
        )
        .expect_err("invalid epoch")
        .code,
        NativeShortcutErrorCode::InvalidEpoch
    );
    assert_eq!(
        host.update_from_label(
            MAIN_WEBVIEW_LABEL,
            NativeShortcutContextInput {
                revision: MAX_SAFE_JS_REVISION + 1,
                ..first
            },
        )
        .expect_err("unsafe integer")
        .code,
        NativeShortcutErrorCode::InvalidRevision
    );
}

/// hard reload 轮换 epoch、撤销 ready 与旧 enable，并允许新文档 revision 从 1 重启。
#[test]
fn page_reload_rotates_epoch_and_rejects_late_old_document() {
    let host = NativeShortcutHost::default();
    let old = activate_context(&host, 3, true, true);
    let replacement = Uuid::from_u128(0x123e4567_e89b_42d3_a456_426614174000);
    host.rotate_main_renderer_lease_to(replacement)
        .expect("rotate renderer lease");
    let query = host
        .query_from_label(MAIN_WEBVIEW_LABEL)
        .expect("replacement query");
    assert_eq!(query.epoch, replacement.to_string());
    assert_eq!(query.revision, 0);
    assert!(!query.ready);
    assert!(
        event_if_enabled(&host, NativeShortcutCommand::Review)
            .expect("revoked event")
            .is_none()
    );
    assert_eq!(
        host.update_from_label(
            MAIN_WEBVIEW_LABEL,
            NativeShortcutContextInput {
                epoch: old.epoch,
                revision: 4,
                project_capabilities_enabled: true,
                conversation_focus_enabled: true,
            },
        )
        .expect_err("late old document")
        .code,
        NativeShortcutErrorCode::StaleEpoch
    );
    let replacement_snapshot = activate_context(&host, 1, true, false);
    assert_eq!(replacement_snapshot.epoch, replacement.to_string());
    assert_eq!(replacement_snapshot.revision, 1);
    assert!(replacement_snapshot.ready);
}

/// wire 值固定为五个 command，事件只携带当前 epoch、command 与 revision。
#[test]
fn event_wire_shape_is_fixed_and_epoch_scoped() {
    let values = [
        NativeShortcutCommand::Review,
        NativeShortcutCommand::Files,
        NativeShortcutCommand::Terminal,
        NativeShortcutCommand::Preview,
        NativeShortcutCommand::SideChat,
    ]
    .map(|command| serde_json::to_value(command).expect("serialize command"));
    assert_eq!(
        values,
        [
            serde_json::json!("review"),
            serde_json::json!("files"),
            serde_json::json!("terminal"),
            serde_json::json!("preview"),
            serde_json::json!("side_chat"),
        ]
    );
    let epoch = "123e4567-e89b-42d3-a456-426614174000";
    let event = serde_json::to_value(NativeShortcutEvent {
        epoch: epoch.to_owned(),
        command: NativeShortcutCommand::Review,
        revision: 3,
    })
    .expect("serialize event");
    assert_eq!(
        event,
        serde_json::json!({"epoch": epoch, "command": "review", "revision": 3})
    );
}

/// child focus 失败后 emit/handled 都不得执行，确保按键完整留在原页面。
#[cfg(windows)]
#[test]
fn child_focus_failure_is_fail_open_before_emit_and_handled() {
    use std::cell::RefCell;
    let steps = RefCell::new(Vec::new());
    let delivered = dispatch_shortcut_effects(
        "preview_child",
        || {
            steps.borrow_mut().push("focus");
            false
        },
        || {
            steps.borrow_mut().push("emit");
            true
        },
        || {
            steps.borrow_mut().push("handled");
            Ok::<(), ()>(())
        },
    )
    .expect("dispatch policy");
    assert!(!delivered);
    assert_eq!(&*steps.borrow(), &["focus"]);
}

/// main 跳过冗余 focus；child 成功路径严格按 focus→emit→handled 顺序执行。
#[cfg(windows)]
#[test]
fn successful_dispatch_orders_main_and_child_effects() {
    use std::cell::RefCell;
    let main_steps = RefCell::new(Vec::new());
    let main_delivered = dispatch_shortcut_effects(
        MAIN_WEBVIEW_LABEL,
        || {
            main_steps.borrow_mut().push("focus");
            true
        },
        || {
            main_steps.borrow_mut().push("emit");
            true
        },
        || {
            main_steps.borrow_mut().push("handled");
            Ok::<(), ()>(())
        },
    )
    .expect("main dispatch");
    assert!(main_delivered);
    assert_eq!(&*main_steps.borrow(), &["emit", "handled"]);

    let child_steps = RefCell::new(Vec::new());
    let child_delivered = dispatch_shortcut_effects(
        "preview_child",
        || {
            child_steps.borrow_mut().push("focus");
            true
        },
        || {
            child_steps.borrow_mut().push("emit");
            true
        },
        || {
            child_steps.borrow_mut().push("handled");
            Ok::<(), ()>(())
        },
    )
    .expect("child dispatch");
    assert!(child_delivered);
    assert_eq!(&*child_steps.borrow(), &["focus", "emit", "handled"]);
}

/// Preview child label 必须来自 UUID v4 simple 形式，锁定 close ACK 的不复用前提。
#[cfg(windows)]
#[test]
fn preview_label_policy_requires_uuid_v4_identity() {
    let label = format!("preview_{}", Uuid::new_v4().simple());
    assert!(is_preview_webview_label(&label));
    assert!(!is_preview_webview_label("preview_child"));
    assert!(!is_preview_webview_label(MAIN_WEBVIEW_LABEL));
}

#[cfg(windows)]
/// callback 必须匹配 exact attempt；未来同 label registration 也不能复活旧 callback。
#[test]
fn registration_attempt_gates_callback_and_prevents_label_aba() {
    let child_label = format!("preview_{}", Uuid::new_v4().simple());
    let host = NativeShortcutHost::default();
    let main_attempt = host
        .claim_installation(MAIN_WEBVIEW_LABEL)
        .expect("main claim")
        .expect("new main attempt");
    host.finish_installation(MAIN_WEBVIEW_LABEL, main_attempt, 11)
        .expect("main registration");
    activate_context(&host, 1, true, true);

    let first_attempt = host
        .claim_installation(&child_label)
        .expect("child claim")
        .expect("new child attempt");
    host.finish_installation(&child_label, first_attempt, 41)
        .expect("publish child registration");
    assert!(
        host.event_if_enabled_for_registration(
            &child_label,
            first_attempt,
            NativeShortcutCommand::Files,
        )
        .expect("current callback")
        .is_some()
    );
    host.uninstall_after_close(&child_label);
    let second_attempt = host
        .claim_installation(&child_label)
        .expect("second claim")
        .expect("new second attempt");
    host.finish_installation(&child_label, second_attempt, 42)
        .expect("second registration");
    assert!(
        host.event_if_enabled_for_registration(
            &child_label,
            first_attempt,
            NativeShortcutCommand::Files,
        )
        .expect("old callback")
        .is_none()
    );
    assert!(
        host.event_if_enabled_for_registration(
            &child_label,
            second_attempt,
            NativeShortcutCommand::Files,
        )
        .expect("new callback")
        .is_some()
    );
}

#[cfg(windows)]
/// shutdown 只报告 revoked/deferred；已注册 main handler 不会被虚假计为主动 remove。
#[test]
fn shutdown_reports_revoked_and_deferred_without_claiming_native_remove() {
    let child_label = format!("preview_{}", Uuid::new_v4().simple());
    let host = NativeShortcutHost::default();
    let main_attempt = host
        .claim_installation(MAIN_WEBVIEW_LABEL)
        .expect("main claim")
        .expect("main attempt");
    host.finish_installation(MAIN_WEBVIEW_LABEL, main_attempt, 11)
        .expect("main registration");
    let child_attempt = host
        .claim_installation(&child_label)
        .expect("child claim")
        .expect("child attempt");
    let report = host
        .shutdown_until(Instant::now())
        .expect("shortcut shutdown");
    assert!(report.admission_closed);
    assert_eq!(report.registered_callbacks_revoked, 1);
    assert_eq!(report.installation_attempts_revoked, 1);
    assert_eq!(report.deferred_to_controller_close, 2);
    assert!(!report.registry_empty);
    assert!(host.is_revoked_for_controller_close());
    assert!(!registry_is_empty(&host));
    assert_eq!(
        host.finish_installation(&child_label, child_attempt, 12)
            .expect_err("late finish")
            .code,
        NativeShortcutErrorCode::ShuttingDown
    );
    host.uninstall_after_close(&child_label);
    assert!(!registry_is_empty(&host));
}

/// shutdown 单调关闭 lease/context，晚到 query/update 与事件都失败关闭。
#[test]
fn shutdown_permanently_disables_renderer_lease() {
    let host = NativeShortcutHost::default();
    let active = activate_context(&host, 1, true, true);
    let report = host
        .shutdown_until(Instant::now() + std::time::Duration::from_secs(1))
        .expect("shutdown");
    assert!(report.admission_closed);
    assert!(host.is_revoked_for_controller_close());
    assert!(
        event_if_enabled(&host, NativeShortcutCommand::Review)
            .expect("disabled event")
            .is_none()
    );
    assert_eq!(
        host.query_from_label(MAIN_WEBVIEW_LABEL)
            .expect_err("late query")
            .code,
        NativeShortcutErrorCode::ShuttingDown
    );
    assert_eq!(
        host.update_from_label(
            MAIN_WEBVIEW_LABEL,
            NativeShortcutContextInput {
                epoch: active.epoch,
                revision: 2,
                project_capabilities_enabled: true,
                conversation_focus_enabled: true,
            },
        )
        .expect_err("late update")
        .code,
        NativeShortcutErrorCode::ShuttingDown
    );
}
