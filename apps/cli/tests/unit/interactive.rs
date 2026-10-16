// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

#[allow(dead_code)]
mod controller {
    include!("../../src/controller/mod.rs");

    mod regression {
        use super::interactive::{fork_recovery, plan_recovery};
        use super::work_activity::{ContextCompactionStatus, WorkActivity};
        use super::{
            CliError, ThreadContext, args, interactive, preference_recovery, queue_recovery,
            suspended_recovery, usage,
        };
        use ja_cli::ui::{UiEvent, UiUsageSnapshot};
        use ja_runtime::app_server_process::{
            ProviderRequestProfileDto, ThreadContextEstimateDto, ThreadContextUsageDto,
            ThreadUsageSummary,
        };
        use plan_recovery::{PlanRunAction, can_resume_plan_creation, confirms_plan_transition};
        use preference_recovery::{
            PreferenceResolution, matches_preferences, resolve, update_then_readback_once,
        };
        use serde_json::{Value, json};
        use std::sync::atomic::AtomicBool;
        use std::time::{Duration, Instant};

        /// @author kongweiguang
        /// 嵌套子任务按直接父会话刷新；共享 root 不能把孙任务活动泄漏到主会话。
        #[test]
        fn task_activity_notification_matches_direct_parent_thread() {
            let params = json!({
                "rootThreadId":"thr_root",
                "taskThreadId":"thr_child",
                "task":{"parentThreadId":"thr_parent"}
            });
            assert!(interactive::task_activity_targets_thread(&params, "thr_parent").unwrap());
            assert!(!interactive::task_activity_targets_thread(&params, "thr_root").unwrap());
            assert!(!interactive::task_activity_targets_thread(&params, "thr_sibling").unwrap());
            assert!(interactive::task_activity_targets_thread(&json!({}), "thr_parent").is_err());
        }

        /// @author kongweiguang
        /// 子任务关闭只承认严格的服务端 ID 回执，不允许空集合、缺目标、重复项或删除存活 root。
        #[test]
        fn task_close_receipt_requires_exact_nonempty_identity_set() {
            assert_eq!(
                interactive::validate_task_close_receipt(
                    &json!({"closedThreadIds":["thr_child","thr_grandchild"]}),
                    "thr_child",
                    "thr_root"
                )
                .unwrap(),
                ["thr_child", "thr_grandchild"]
            );
            for receipt in [
                json!({"closedThreadIds": []}),
                json!({"closedThreadIds": ["thr_other"]}),
                json!({"closedThreadIds": ["thr_child", "thr_child"]}),
                json!({"closedThreadIds": ["thr_child", "thr_root"]}),
                json!({"closed": true}),
            ] {
                assert!(
                    interactive::validate_task_close_receipt(&receipt, "thr_child", "thr_root")
                        .is_err(),
                    "accepted {receipt}"
                );
            }
        }

        /// @author kongweiguang
        /// An uncertain `/btw` body can follow only the user-selected SideChat under the exact original lineage.
        #[test]
        fn uncertain_side_chat_body_requires_matching_selected_identity() {
            use std::collections::HashMap;

            let mut recoveries = HashMap::from([(
                "thr_parent".to_owned(),
                interactive::PendingSideChatRecovery {
                    root_thread_id: "thr_root".into(),
                    workspace_id: "ws_main".into(),
                    candidate_thread_id: None,
                    content: "  完整正文 🙂  ".into(),
                    inspected: false,
                },
            )]);
            let candidate = |thread_id: &str,
                             workspace_id: &str,
                             root_thread_id: &str,
                             parent_thread_id: &str,
                             thread_kind| ThreadContext {
                thread_id: thread_id.into(),
                title: "侧聊".into(),
                workspace_id: workspace_id.into(),
                workspace_root: "C:/workspace".into(),
                thread_kind,
                parent_thread_id: Some(parent_thread_id.into()),
                root_thread_id: root_thread_id.into(),
                provider_id: "provider".into(),
                model_id: "model".into(),
                reasoning_level: None,
                access_mode: "default".into(),
                revision: 1,
            };

            for mismatch in [
                candidate(
                    "thr_wrong_parent",
                    "ws_main",
                    "thr_root",
                    "thr_elsewhere",
                    super::DiscoveredThreadKind::SideChat,
                ),
                candidate(
                    "thr_wrong_workspace",
                    "ws_other",
                    "thr_root",
                    "thr_parent",
                    super::DiscoveredThreadKind::SideChat,
                ),
                candidate(
                    "thr_wrong_root",
                    "ws_main",
                    "thr_other_root",
                    "thr_parent",
                    super::DiscoveredThreadKind::SideChat,
                ),
                candidate(
                    "thr_subagent",
                    "ws_main",
                    "thr_root",
                    "thr_parent",
                    super::DiscoveredThreadKind::Subagent,
                ),
            ] {
                assert!(
                    interactive::pending_side_chat_recovery_candidate(
                        &mut recoveries,
                        "thr_parent",
                        &mismatch
                    )
                    .is_none()
                );
            }
            assert_eq!(
                interactive::pending_side_chat_recovery_candidate(
                    &mut recoveries,
                    "thr_parent",
                    &candidate(
                        "thr_child",
                        "ws_main",
                        "thr_root",
                        "thr_parent",
                        super::DiscoveredThreadKind::SideChat,
                    )
                )
                .as_deref(),
                Some("  完整正文 🙂  ")
            );
            assert!(
                interactive::pending_side_chat_recovery_candidate(
                    &mut recoveries,
                    "thr_parent",
                    &candidate(
                        "thr_another_child",
                        "ws_main",
                        "thr_root",
                        "thr_parent",
                        super::DiscoveredThreadKind::SideChat,
                    )
                )
                .is_none()
            );
        }

        /// @author kongweiguang
        /// A close receipt removes only exact Thread payloads, preserving other pending first sends.
        #[test]
        fn close_receipt_preserves_other_side_chat_pending_bodies() {
            use std::collections::HashMap;

            let mut pending_content = HashMap::from([
                ("thr_closed_child".into(), "remove me".into()),
                ("thr_live_child".into(), "保留的首发正文".into()),
            ]);
            let recovery = |content: &str| interactive::PendingSideChatRecovery {
                root_thread_id: "thr_root".into(),
                workspace_id: "ws_main".into(),
                candidate_thread_id: None,
                content: content.into(),
                inspected: false,
            };
            let mut recoveries = HashMap::from([
                ("thr_closed_parent".into(), recovery("remove")),
                ("thr_live_parent".into(), recovery("keep")),
            ]);

            interactive::retain_open_side_chat_payloads(
                &mut pending_content,
                &mut recoveries,
                &["thr_closed_child".into(), "thr_closed_parent".into()],
            );

            assert_eq!(pending_content.len(), 1);
            assert_eq!(
                pending_content.get("thr_live_child").map(String::as_str),
                Some("保留的首发正文")
            );
            assert_eq!(recoveries.len(), 1);
            assert!(recoveries.contains_key("thr_live_parent"));
        }

        /// @author kongweiguang
        /// 恢复目录应把过期审批的 suspended 明确显示为暂停，而不是工作或终态取消。
        #[test]
        fn thread_choice_exposes_paused_child_for_resume() {
            assert_eq!(interactive::thread_status_label("suspended"), "已暂停");
            assert_eq!(
                interactive::combine_thread_choice_detail("subagent", Some("已暂停"), None)
                    .unwrap(),
                Some("子任务 · 已暂停".into())
            );
        }

        /// @author kongweiguang
        /// A child visible in global discovery must still fail selection if its task/read identity
        /// or lifecycle changed; candidate visibility is never permission to fall back to MAIN.
        #[test]
        fn visible_subagent_candidate_is_rejected_after_identity_or_lifecycle_changes() {
            assert_eq!(
                interactive::combine_thread_choice_detail(
                    "subagent",
                    Some("等待审批"),
                    Some("项目")
                )
                .unwrap(),
                Some("子任务 · 等待审批 · 项目".into())
            );
            let valid = json!({
                "task": {
                    "taskThreadId":"thr_child",
                    "parentThreadId":"thr_parent",
                    "rootThreadId":"thr_root",
                    "taskKind":"subagent",
                    "lifecycle":"attached",
                    "originTurnId":"turn_origin"
                },
                "thread": {
                    "threadId":"thr_child",
                    "workspaceId":"ws_main",
                    "status":"active"
                }
            });
            assert!(
                interactive::task_read_thread_metadata(&valid, "thr_child", "ws_main", "subagent")
                    .is_ok()
            );
            for stale in [
                {
                    let mut value = valid.clone();
                    value["thread"]["workspaceId"] = json!("ws_other");
                    value
                },
                {
                    let mut value = valid.clone();
                    value["thread"]["status"] = json!("closing");
                    value
                },
                {
                    let mut value = valid.clone();
                    value["task"]["taskThreadId"] = json!("thr_another");
                    value
                },
                {
                    let mut value = valid.clone();
                    value["task"]["lifecycle"] = json!("independent");
                    value
                },
            ] {
                assert!(
                    interactive::task_read_thread_metadata(
                        &stale,
                        "thr_child",
                        "ws_main",
                        "subagent"
                    )
                    .is_err()
                );
            }
        }

        /// @author kongweiguang
        /// 审批后刷新、F5/status 与偏好回读必须沿用子会话 task/read 来源，不能退回 MAIN 历史目录。
        #[test]
        fn authoritative_metadata_source_follows_thread_kind_without_main_fallback() {
            use interactive::{ThreadMetadataReadSource, thread_metadata_read_source};

            assert_eq!(
                thread_metadata_read_source(super::DiscoveredThreadKind::Main),
                ThreadMetadataReadSource::WorkspaceThreadList
            );
            assert_eq!(
                thread_metadata_read_source(super::DiscoveredThreadKind::SideChat),
                ThreadMetadataReadSource::TaskRead("side_chat")
            );
            assert_eq!(
                thread_metadata_read_source(super::DiscoveredThreadKind::Subagent),
                ThreadMetadataReadSource::TaskRead("subagent")
            );
        }

        /// @author kongweiguang
        /// 审批后的 task/read 若改了 parent/root，刷新不能把结果应用到当前 CLI 会话。
        #[test]
        fn active_task_metadata_must_keep_selected_parent_and_root() {
            let thread = ThreadContext {
                thread_id: "thr_child".into(),
                title: "子任务".into(),
                workspace_id: "ws_main".into(),
                workspace_root: "C:/workspace".into(),
                thread_kind: super::DiscoveredThreadKind::Subagent,
                parent_thread_id: Some("thr_parent".into()),
                root_thread_id: "thr_root".into(),
                provider_id: "provider".into(),
                model_id: "model".into(),
                reasoning_level: None,
                access_mode: "default".into(),
                revision: 1,
            };

            assert!(
                interactive::validate_active_task_lineage(&thread, "thr_parent", "thr_root")
                    .is_ok()
            );
            assert!(
                interactive::validate_active_task_lineage(
                    &thread,
                    "thr_changed_parent",
                    "thr_root"
                )
                .is_err()
            );
            assert!(
                interactive::validate_active_task_lineage(
                    &thread,
                    "thr_parent",
                    "thr_changed_root"
                )
                .is_err()
            );
        }

        /// 压缩快照恢复当前请求序号，重试事件与终态仅作用于对应 compaction。
        #[test]
        fn compaction_activity_tracks_retry_and_authoritative_snapshot() {
            assert_eq!(
                interactive::initial_context_compaction(false, &Value::Null).unwrap(),
                None
            );
            assert!(interactive::initial_context_compaction(true, &json!({})).is_err());
            assert_eq!(
                interactive::initial_context_compaction(true, &json!({"contextCompaction": null}))
                    .unwrap(),
                None
            );
            assert_eq!(
                interactive::initial_context_compaction(
                    true,
                    &json!({
                        "contextCompaction": {
                            "compactionId": "cmp_active",
                            "turnId": null,
                            "retryAttempt": 1
                        }
                    })
                )
                .unwrap(),
                Some(ContextCompactionStatus {
                    compaction_id: "cmp_active".into(),
                    turn_id: None,
                    retry_attempt: 1,
                })
            );
            assert!(
                interactive::initial_context_compaction(
                    true,
                    &json!({
                        "contextCompaction": {
                            "compactionId": "cmp_invalid",
                            "turnId": null,
                            "retryAttempt": 0
                        }
                    })
                )
                .is_err()
            );

            let mut activity = WorkActivity::default();
            activity.started("cmp_active".into(), Some("turn_active".into()));
            assert_eq!(activity.status_text().as_deref(), Some("正在压缩上下文"));
            assert!(!activity.retry_started("turn_other", 2));
            assert!(activity.retry_started("turn_active", 2));
            assert_eq!(
                activity.status_text().as_deref(),
                Some("摘要重试 · 第 2 次请求")
            );
            assert!(!activity.finish("cmp_old"));

            activity.restore(Some(ContextCompactionStatus {
                compaction_id: "cmp_active".into(),
                turn_id: Some("turn_active".into()),
                retry_attempt: 3,
            }));
            assert_eq!(
                activity.status_text().as_deref(),
                Some("摘要重试 · 第 3 次请求")
            );
            assert!(activity.finish("cmp_active"));
            assert_eq!(activity.status_text(), None);
            activity.restore(None);
            assert_eq!(activity.status_text(), None);
        }

        /// @author kongweiguang
        /// Fake port 暴露 operation/read 与提交次数，固定核对恢复顺序和原 tuple 重放身份。
        struct FakeForkRecoveryPort {
            lookup: Option<Result<Value, &'static str>>,
            submit_result: Value,
            reads: Vec<(String, String)>,
            submissions: Vec<Value>,
        }

        impl fork_recovery::ForkRecoveryPort for FakeForkRecoveryPort {
            type Error = &'static str;

            /// @author kongweiguang
            /// Fake read 返回预置 owner 回执，并记录查询身份供调用顺序断言。
            fn read_operation(
                &mut self,
                operation_key: &str,
                source_thread_id: &str,
            ) -> Result<Value, Self::Error> {
                self.reads
                    .push((operation_key.to_owned(), source_thread_id.to_owned()));
                self.lookup.take().expect("one operation read")
            }

            /// @author kongweiguang
            /// Fake submit 只记录原 PendingFork 参数，模拟端不能自行替换 operation key。
            fn submit_fork(
                &mut self,
                request: &fork_recovery::PendingFork,
            ) -> Result<Value, Self::Error> {
                self.submissions.push(request.params());
                Ok(self.submit_result.clone())
            }
        }

        /// Resume 与 Cancel 固定使用各自的 JA-RPC payload，未知响应路径每次只派发一次。
        #[test]
        fn suspended_control_request_uses_exact_payload_and_runs_once() {
            for (action, expected_method, expected_params) in [
                (
                    suspended_recovery::SuspendedTurnAction::Resume,
                    "turn/resume",
                    json!({"turnId":"turn_paused","expectedThreadRevision":19}),
                ),
                (
                    suspended_recovery::SuspendedTurnAction::Cancel,
                    "turn/cancel",
                    json!({"turnId":"turn_paused"}),
                ),
            ] {
                let mut calls = 0;
                let response = suspended_recovery::request_once(
                    action,
                    "turn_paused",
                    19,
                    |method, params| {
                        calls += 1;
                        assert_eq!(method, expected_method);
                        assert_eq!(params, expected_params);
                        Ok::<_, ()>(json!({"accepted":false}))
                    },
                )
                .unwrap();

                assert_eq!(response["accepted"], false);
                assert_eq!(calls, 1);
            }
        }

        /// 成功回执须指回原 Turn 且包含符合动作的状态；畸形或非终态取消回执视为未知。
        #[test]
        fn suspended_control_acknowledgement_requires_matching_turn_and_outcome() {
            let resume = json!({
                "accepted":true,
                "turnId":"turn_paused",
                "threadRevision":20,
                "queued":true
            });
            assert!(suspended_recovery::confirms_acknowledgement(
                suspended_recovery::SuspendedTurnAction::Resume,
                "turn_paused",
                19,
                &resume
            ));
            assert!(!suspended_recovery::confirms_acknowledgement(
                suspended_recovery::SuspendedTurnAction::Resume,
                "turn_other",
                19,
                &resume
            ));

            let cancelled = json!({
                "accepted":true,
                "turnId":"turn_paused",
                "threadRevision":20,
                "status":"cancelled"
            });
            assert!(suspended_recovery::confirms_acknowledgement(
                suspended_recovery::SuspendedTurnAction::Cancel,
                "turn_paused",
                19,
                &cancelled
            ));
            let still_queued = json!({
                "accepted":true,
                "turnId":"turn_paused",
                "threadRevision":20,
                "status":"queued"
            });
            assert!(!suspended_recovery::confirms_acknowledgement(
                suspended_recovery::SuspendedTurnAction::Cancel,
                "turn_paused",
                19,
                &still_queued
            ));
        }

        /// 读回只接受原 Thread 内的原 Turn 状态，不能用另一个会话或相似 Turn 解锁操作。
        #[test]
        fn suspended_control_readback_is_bound_to_thread_and_turn() {
            let history = json!({
                "threadId":"thr_original",
                "turns":[
                    {"turnId":"turn_other","status":"cancelled"},
                    {"turnId":"turn_original","status":"suspended"}
                ]
            });
            assert_eq!(
                suspended_recovery::observed_status(&history, "thr_original", "turn_original"),
                Some("suspended")
            );
            assert_eq!(
                suspended_recovery::observed_status(&history, "thr_other", "turn_original"),
                None
            );
            assert_eq!(
                suspended_recovery::observed_status(&history, "thr_original", "turn_missing"),
                None
            );
        }

        /// 恢复可由活动态读回确认；取消只认可终态，暂停则需 F5 才能重新明确选择。
        #[test]
        fn suspended_control_lock_reconciles_by_action_and_explicit_read() {
            for status in ["queued", "running", "waiting_input", "waiting_approval"] {
                assert!(suspended_recovery::may_release_control_lock(
                    suspended_recovery::SuspendedTurnAction::Resume,
                    status,
                    false
                ));
                assert!(!suspended_recovery::may_release_control_lock(
                    suspended_recovery::SuspendedTurnAction::Cancel,
                    status,
                    false
                ));
            }
            for status in ["completed", "failed", "cancelled"] {
                assert!(suspended_recovery::may_release_control_lock(
                    suspended_recovery::SuspendedTurnAction::Resume,
                    status,
                    false
                ));
                assert!(suspended_recovery::may_release_control_lock(
                    suspended_recovery::SuspendedTurnAction::Cancel,
                    status,
                    false
                ));
            }
            for action in [
                suspended_recovery::SuspendedTurnAction::Resume,
                suspended_recovery::SuspendedTurnAction::Cancel,
            ] {
                assert!(!suspended_recovery::may_release_control_lock(
                    action,
                    "suspended",
                    false
                ));
                assert!(suspended_recovery::may_release_control_lock(
                    action,
                    "suspended",
                    true
                ));
                assert!(!suspended_recovery::may_release_control_lock(
                    action,
                    "provider_unknown",
                    true
                ));
            }
        }

        /// 暂停控制回执自身会继续触发既有历史轮询，即使最新 Turn 已不属于活动态。
        #[test]
        fn suspended_control_pending_keeps_history_polling_without_a_new_timer() {
            assert!(!suspended_recovery::requires_history_poll(
                false, false, false
            ));
            assert!(suspended_recovery::requires_history_poll(
                false, false, true
            ));
            assert!(suspended_recovery::requires_history_poll(
                true, false, false
            ));
            assert!(suspended_recovery::requires_history_poll(
                false, true, false
            ));
        }

        /// @author kongweiguang
        /// 分叉回执未知时，重试 payload 固定回复 Item、来源 Thread、revision 与 operation key。
        #[test]
        fn pending_fork_replays_the_same_source_identity_and_validates_its_receipt() {
            let request = fork_recovery::PendingFork {
                operation_key: "op_0123456789abcdef0123456789abcdef".into(),
                source_thread_id: "thr_source".into(),
                source_item_id: "item_source".into(),
                expected_thread_revision: 27,
                child_thread_id: None,
            };
            let retry = request.clone();
            assert_eq!(request.params(), retry.params());
            assert_eq!(request.params()["operationKey"], request.operation_key);
            assert_eq!(request.params()["sourceItemId"], "item_source");
            assert!(request.params().get("sourceTurnId").is_none());
            assert_eq!(request.params()["expectedThreadRevision"], 27);

            let receipt = json!({
                "source":{"threadId":"thr_source","itemId":"item_source","threadRevision":27},
                "thread":{"threadId":"thr_child"}
            });
            assert_eq!(request.fork_id(&receipt), Ok("thr_child"));

            let wrong_source = json!({
                "source":{"threadId":"thr_other","itemId":"item_source","threadRevision":27},
                "thread":{"threadId":"thr_child"}
            });
            assert!(request.fork_id(&wrong_source).is_err());
            let wrong_item = json!({
                "source":{"threadId":"thr_source","itemId":"item_other","threadRevision":27},
                "thread":{"threadId":"thr_child"}
            });
            assert!(request.fork_id(&wrong_item).is_err());
            let same_thread = json!({
                "source":{"threadId":"thr_source","itemId":"item_source","threadRevision":27},
                "thread":{"threadId":"thr_source"}
            });
            assert!(request.fork_id(&same_thread).is_err());
        }

        /// @author kongweiguang
        /// 分叉回执恢复只允许来源或目标 child 仍是当前 Thread，避免 F5 抢走用户切换后的焦点。
        #[test]
        fn pending_fork_opening_is_scoped_to_source_or_child_focus() {
            let mut request = fork_recovery::PendingFork {
                operation_key: "op_0123456789abcdef0123456789abcdef".into(),
                source_thread_id: "thr_source".into(),
                source_item_id: "item_source".into(),
                expected_thread_revision: 27,
                child_thread_id: None,
            };
            assert!(request.allows_opening_from(Some("thr_source")));
            assert!(!request.allows_opening_from(Some("thr_child")));
            assert!(!request.allows_opening_from(None));
            request.child_thread_id = Some("thr_child".into());
            assert!(request.allows_opening_from(Some("thr_child")));
            assert!(!request.allows_opening_from(Some("thr_other")));
        }

        /// @author kongweiguang
        /// 回执查询按来源 owner 先行；committed 只返回原回执，unknown 才用原 tuple 提交。
        #[test]
        fn pending_fork_retries_only_after_source_owner_reports_unknown() {
            let request = fork_recovery::PendingFork {
                operation_key: "op_0123456789abcdef0123456789abcdef".into(),
                source_thread_id: "thr_source".into(),
                source_item_id: "item_source".into(),
                expected_thread_revision: 27,
                child_thread_id: None,
            };
            let committed_receipt = json!({
                "source":{"threadId":"thr_source","itemId":"item_source","threadRevision":27},
                "thread":{"threadId":"thr_child"}
            });
            let mut committed_port = FakeForkRecoveryPort {
                lookup: Some(Ok(json!({
                    "status":"committed",
                    "method":"thread/fork",
                    "threadId":"thr_source",
                    "result":committed_receipt
                }))),
                submit_result: Value::Null,
                reads: Vec::new(),
                submissions: Vec::new(),
            };
            let committed = match fork_recovery::read_then_retry(&mut committed_port, &request) {
                Ok(result) => result,
                Err(_) => panic!("committed receipt should not submit again"),
            };
            assert_eq!(committed, committed_receipt);
            assert_eq!(
                committed_port.reads,
                [(
                    request.operation_key.clone(),
                    request.source_thread_id.clone()
                )]
            );
            assert!(committed_port.submissions.is_empty());

            let retry_result = json!({"accepted":"same-operation"});
            let mut unknown_port = FakeForkRecoveryPort {
                lookup: Some(Ok(json!({"status":"unknown"}))),
                submit_result: retry_result.clone(),
                reads: Vec::new(),
                submissions: Vec::new(),
            };
            let retried = match fork_recovery::read_then_retry(&mut unknown_port, &request) {
                Ok(result) => result,
                Err(_) => panic!("explicit unknown should retry the original operation"),
            };
            assert_eq!(retried, retry_result);
            assert_eq!(
                unknown_port.reads,
                [(
                    request.operation_key.clone(),
                    request.source_thread_id.clone()
                )]
            );
            assert_eq!(unknown_port.submissions, [request.params()]);

            let mut unreadable_port = FakeForkRecoveryPort {
                lookup: Some(Err("operation/read unavailable")),
                submit_result: Value::Null,
                reads: Vec::new(),
                submissions: Vec::new(),
            };
            let unreadable = fork_recovery::read_then_retry(&mut unreadable_port, &request);
            assert!(matches!(
                unreadable,
                Err(fork_recovery::ForkRecoveryError::Read(
                    "operation/read unavailable"
                ))
            ));
            assert!(unreadable_port.submissions.is_empty());
        }

        /// @author kongweiguang
        /// 清理码与桌面端 allowlist 一致；暂态、超时、关停及内部错误必须保留 fork journal。
        #[test]
        fn fork_rpc_error_clears_only_explicit_rejections() {
            for code in [-32_007, -32_019, -32_028, -32_029, -32_034] {
                assert!(fork_recovery::is_definitive_fork_rejection(Some(code)));
            }
            for code in [-32_012, -32_014, -32_020, -32_080, -32_081, -32_030] {
                assert!(!fork_recovery::is_definitive_fork_rejection(Some(code)));
            }
            assert!(!fork_recovery::is_definitive_fork_rejection(None));
        }

        /// @author kongweiguang
        /// child metadata 成功后首屏读取失败不得切换来源；重试动作必须打开已记账 child。
        #[test]
        fn failed_child_activation_keeps_source_and_retries_existing_child() {
            let active_thread = ThreadContext {
                thread_id: "thr_source".into(),
                title: "来源".into(),
                workspace_id: "ws_source".into(),
                workspace_root: "C:/workspace".into(),
                thread_kind: super::DiscoveredThreadKind::Main,
                parent_thread_id: None,
                root_thread_id: "thr_source".into(),
                provider_id: "provider".into(),
                model_id: "model".into(),
                reasoning_level: None,
                access_mode: "default".into(),
                revision: 27,
            };
            let request = fork_recovery::PendingFork {
                operation_key: "op_0123456789abcdef0123456789abcdef".into(),
                source_thread_id: "thr_source".into(),
                source_item_id: "item_source".into(),
                expected_thread_revision: 27,
                child_thread_id: Some("thr_child".into()),
            };
            // load_context already returned this candidate; the injected error represents the later
            // authoritative thread/read that activation stages before mutating the active Thread.
            let candidate = ThreadContext {
                thread_id: "thr_child".into(),
                title: "来源（分叉）".into(),
                workspace_id: "ws_source".into(),
                workspace_root: "C:/workspace".into(),
                thread_kind: super::DiscoveredThreadKind::Main,
                parent_thread_id: None,
                root_thread_id: "thr_child".into(),
                provider_id: "provider".into(),
                model_id: "model".into(),
                reasoning_level: None,
                access_mode: "default".into(),
                revision: 0,
            };
            let activation = interactive::prepare_thread_activation(candidate, |thread| {
                assert_eq!(thread.thread_id, "thr_child");
                Err(CliError::usage("injected child thread/read failure"))
            });

            assert!(activation.is_err());
            assert_eq!(active_thread.thread_id, "thr_source");
            assert_eq!(request.params()["sourceThreadId"], "thr_source");
            assert_eq!(request.params()["sourceItemId"], "item_source");
            assert_eq!(request.params()["expectedThreadRevision"], 27);
            assert_eq!(request.operation_key, "op_0123456789abcdef0123456789abcdef");
            assert_eq!(request.child_thread_id.as_deref(), Some("thr_child"));
            assert!(matches!(
                interactive::pending_fork_action(&request, Some(&active_thread.thread_id)),
                Ok(interactive::PendingForkAction::OpenExistingChild(child_thread_id))
                    if child_thread_id == "thr_child"
            ));
        }

        /// @author kongweiguang
        /// 多回复同 Turn 仍各自成为可选锚点，继承答复可再次分叉而过程项不可选。
        #[test]
        fn fork_picker_uses_settled_answer_items_and_requires_origin() {
            let history = json!({
                "items":[
                    {"itemId":"item_progress","turnId":"turn_native","kind":"assistant_progress","origin":"native","text":"仍在运行"},
                    {"itemId":"item_answer_one","turnId":"turn_native","kind":"final_answer","origin":"native","createdAt":"2026-10-10T08:00:00Z","text":"第一个答复"},
                    {"itemId":"item_answer_two","turnId":"turn_native","kind":"final_answer","origin":"native","createdAt":"2026-10-10T08:01:00Z","text":"第二个答复"},
                    {"itemId":"item_inherited","turnId":"turn_inherited","kind":"final_answer","origin":"inherited","createdAt":"2026-10-10T08:02:00Z","text":"继承答复"}
                ],
                "turns":[
                    {"turnId":"turn_native","origin":"native"},
                    {"turnId":"turn_inherited","origin":"inherited"}
                ]
            });
            let choices = interactive::fork_reply_choices(&history).expect("valid origins");
            assert_eq!(
                choices
                    .iter()
                    .map(|choice| choice.id.as_str())
                    .collect::<Vec<_>>(),
                ["item_answer_one", "item_answer_two", "item_inherited"]
            );
            assert!(choices[2].detail.as_deref().unwrap().contains("继承"));

            let missing_origin = json!({
                "items":[{"itemId":"item_answer","turnId":"turn","kind":"final_answer","text":"answer"}],
                "turns":[{"turnId":"turn"}]
            });
            assert!(interactive::fork_reply_choices(&missing_origin).is_err());
            let unknown_origin = json!({
                "items":[{"itemId":"item_answer","turnId":"turn","kind":"final_answer","origin":"legacy","text":"answer"}],
                "turns":[{"turnId":"turn","origin":"native"}]
            });
            assert!(interactive::fork_reply_choices(&unknown_origin).is_err());
        }

        /// @author kongweiguang
        /// Picker 只能从权威当前页拿续页边界；Transcript 消费自身游标后也不应丢失该边界。
        #[test]
        fn fork_reply_cursor_comes_from_authoritative_history_page() {
            assert_eq!(
                interactive::fork_reply_page_cursor(&json!({
                    "nextCursor": "cursor_fork_older"
                }))
                .as_deref(),
                Some("cursor_fork_older")
            );
            assert_eq!(
                interactive::fork_reply_page_cursor(&json!({"nextCursor": null})),
                None
            );
            assert_eq!(interactive::fork_reply_page_cursor(&json!({})), None);
        }

        /// @author kongweiguang
        /// revision 冲突后的首选项等待旧 Item 摘要加载，避免刷新后悄悄分叉到最新答复。
        #[test]
        fn fork_revision_retry_prefers_the_same_item_after_its_page_loads() {
            let choice = |id: &str| ja_cli::ui::UiChoice {
                id: id.into(),
                label: id.into(),
                detail: None,
            };
            let mut preferred = Some("item_old".to_owned());

            assert_eq!(
                interactive::take_loaded_fork_retry_item(&mut preferred, &[choice("item_recent")]),
                None
            );
            assert_eq!(preferred.as_deref(), Some("item_old"));
            assert_eq!(
                interactive::take_loaded_fork_retry_item(
                    &mut preferred,
                    &[choice("item_old"), choice("item_recent")]
                ),
                Some("item_old".into())
            );
            assert_eq!(preferred, None);
            assert!(interactive::is_fork_revision_conflict_message(
                "请求失败 (-32009)：thread fork source revision changed"
            ));
            assert!(!interactive::is_fork_revision_conflict_message(
                "请求失败 (-32009)：operation key conflicts"
            ));
        }

        /// Queue 的恢复参数必须一次固定 Turn 与 operation key，不能留下会推导到新 Turn 的半请求。
        #[test]
        fn queue_retry_requires_exact_turn_and_lowercase_operation_identity() {
            let command = args::parse(
                [
                    "queue",
                    "thr_source",
                    "--kind",
                    "steering",
                    "--turn-id",
                    "turn_source",
                    "--operation-key",
                    "op_0123456789abcdef0123456789abcdef",
                    "keep this exact input",
                ]
                .map(str::to_owned),
            )
            .expect("valid queue recovery command");
            assert_eq!(
                command,
                args::Command::Queue {
                    thread_id: "thr_source".into(),
                    turn_id: Some("turn_source".into()),
                    text: "keep this exact input".into(),
                    kind: "steering".into(),
                    operation_key: Some("op_0123456789abcdef0123456789abcdef".into()),
                }
            );

            for arguments in [
                vec![
                    "queue",
                    "thr_source",
                    "--operation-key",
                    "op_0123456789abcdef0123456789abcdef",
                    "text",
                ],
                vec!["queue", "thr_source", "--turn-id", "turn_source", "text"],
                vec![
                    "queue",
                    "thr_source",
                    "--turn-id",
                    "turn_source",
                    "--operation-key",
                    "OP_bad",
                    "text",
                ],
            ] {
                assert!(args::parse(arguments.into_iter().map(str::to_owned)).is_err());
            }
        }

        /// 终答恢复命令必须绑定唯一 Thread、Turn 与本地目标文件。
        #[test]
        fn export_answer_parses_only_explicit_thread_turn_and_path() {
            assert_eq!(
                args::parse(
                    ["export-answer", "thr_source", "turn_source", "answer.md"].map(str::to_owned)
                )
                .expect("explicit answer export"),
                args::Command::ExportAnswer {
                    thread_id: "thr_source".into(),
                    turn_id: "turn_source".into(),
                    output: "answer.md".into(),
                }
            );
            assert!(args::parse(["export-answer", "thr_source"].map(str::to_owned)).is_err());
            assert!(
                args::parse(
                    [
                        "export-answer",
                        "thr_source",
                        "turn_source",
                        "answer.md",
                        "extra"
                    ]
                    .map(str::to_owned)
                )
                .is_err()
            );
        }

        /// PowerShell 的逐项字面量引用保留空格和单引号，使 queue 错误提示能原样重放正文。
        #[test]
        fn queue_recovery_command_quotes_every_request_identity() {
            assert_eq!(
                queue_recovery::retry_command(
                    "thr source",
                    "turn source",
                    "follow_up",
                    "keep 'these' words",
                    "op_0123456789abcdef0123456789abcdef",
                ),
                "ja queue 'thr source' --turn-id 'turn source' --operation-key 'op_0123456789abcdef0123456789abcdef' --kind 'follow_up' 'keep ''these'' words'"
            );
        }

        /// 只有同 owner、同目标的 DRAFT 可续用；终态允许新建，其余活动状态必须显式处理。
        #[test]
        fn plan_create_reuses_only_the_matching_draft() {
            let draft = json!({"plan":{"owner":{"kind":"thread","threadId":"thr_a"},"objective":"修复构建","status":"draft"}});
            assert_eq!(
                can_resume_plan_creation(&draft, "thr_a", "修复构建"),
                Ok(true)
            );
            assert!(can_resume_plan_creation(&draft, "thr_b", "修复构建").is_err());
            assert!(can_resume_plan_creation(&draft, "thr_a", "新增页面").is_err());

            let awaiting_approval = json!({"plan":{"owner":{"kind":"thread","threadId":"thr_a"},"objective":"修复构建","status":"awaiting_approval"}});
            assert!(can_resume_plan_creation(&awaiting_approval, "thr_a", "修复构建").is_err());

            let completed = json!({"plan":{"owner":{"kind":"thread","threadId":"thr_a"},"objective":"旧目标","status":"COMPLETED"}});
            assert_eq!(
                can_resume_plan_creation(&completed, "thr_a", "新目标"),
                Ok(false)
            );
        }

        /// Plan 回执丢失后只接受同一 revision 前进且 Run 状态吻合的权威证据。
        #[test]
        fn plan_write_receipt_reconciliation_requires_the_requested_transition() {
            let cases = [
                (
                    PlanRunAction::Execute,
                    None,
                    json!({"planId":"plan_a","revision":8,"status":"executing","activeRunId":"run_b"}),
                ),
                (
                    PlanRunAction::Pause,
                    Some("run_a"),
                    json!({"planId":"plan_a","revision":8,"status":"paused","activeRunId":"run_a"}),
                ),
                (
                    PlanRunAction::Resume,
                    Some("run_a"),
                    json!({"planId":"plan_a","revision":8,"status":"verifying","activeRunId":"run_a"}),
                ),
                (
                    PlanRunAction::Stop,
                    Some("run_a"),
                    json!({"planId":"plan_a","revision":8,"status":"stopped","activeRunId":"run_a"}),
                ),
            ];

            for (action, previous_run_id, current) in cases {
                assert!(confirms_plan_transition(
                    action,
                    "plan_a",
                    7,
                    previous_run_id,
                    &current
                ));
            }

            let unchanged =
                json!({"planId":"plan_a","revision":7,"status":"paused","activeRunId":"run_a"});
            let wrong_run =
                json!({"planId":"plan_a","revision":8,"status":"paused","activeRunId":"run_b"});
            let wrong_plan =
                json!({"planId":"plan_b","revision":8,"status":"paused","activeRunId":"run_a"});
            assert!(!confirms_plan_transition(
                PlanRunAction::Pause,
                "plan_a",
                7,
                Some("run_a"),
                &unchanged
            ));
            assert!(!confirms_plan_transition(
                PlanRunAction::Pause,
                "plan_a",
                7,
                Some("run_a"),
                &wrong_run
            ));
            assert!(!confirms_plan_transition(
                PlanRunAction::Pause,
                "plan_a",
                7,
                Some("run_a"),
                &wrong_plan
            ));
        }

        #[derive(Default)]
        struct MockConnection {
            update_count: usize,
            readback_count: usize,
            update: Option<Result<(), &'static str>>,
            readback: Option<Result<Value, &'static str>>,
        }

        /// 写回执丢失时仍读一次权威状态；读回吻合即可确认，不再重复发送偏好副作用。
        #[test]
        fn unknown_write_receipt_is_confirmed_from_single_authoritative_readback() {
            let mut connection = MockConnection {
                update: Some(Err("receipt lost")),
                readback: Some(Ok(requested_metadata())),
                ..MockConnection::default()
            };
            let attempt = update_then_readback_once(
                &mut connection,
                |connection| {
                    connection.update_count += 1;
                    connection.update.take().expect("write result")
                },
                |connection| {
                    connection.readback_count += 1;
                    connection.readback.take().expect("readback result")
                },
            );

            assert_eq!(
                resolve(
                    &attempt.update,
                    attempt
                        .readback
                        .as_ref()
                        .ok()
                        .and_then(|metadata| matches_preferences(
                            metadata,
                            &requested_preferences()
                        )),
                ),
                PreferenceResolution::Applied
            );
            assert_eq!(connection.update_count, 1);
            assert_eq!(connection.readback_count, 1);
        }

        /// 后台明确读回不同值时不把失败包装为成功，且只允许用户确认状态后显式重试。
        #[test]
        fn failed_write_with_different_readback_is_not_replayed() {
            let mut connection = MockConnection {
                update: Some(Err("write rejected")),
                readback: Some(Ok(metadata_with_model("model_b"))),
                ..MockConnection::default()
            };
            let attempt = update_then_readback_once(
                &mut connection,
                |connection| {
                    connection.update_count += 1;
                    connection.update.take().expect("write result")
                },
                |connection| {
                    connection.readback_count += 1;
                    connection.readback.take().expect("readback result")
                },
            );

            assert_eq!(
                resolve(
                    &attempt.update,
                    attempt
                        .readback
                        .as_ref()
                        .ok()
                        .and_then(|metadata| matches_preferences(
                            metadata,
                            &requested_preferences()
                        )),
                ),
                PreferenceResolution::NotApplied
            );
            assert_eq!(connection.update_count, 1);
            assert_eq!(connection.readback_count, 1);
        }

        /// 写请求成功但读回被其他客户端改写时，当前值优先于过期成功回执。
        #[test]
        fn acknowledged_write_with_different_readback_is_superseded() {
            let attempt = update_then_readback_once(
                &mut (),
                |_| Ok::<(), &'static str>(()),
                |_| Ok::<Value, &'static str>(metadata_with_model("model_b")),
            );

            assert_eq!(
                resolve(
                    &attempt.update,
                    attempt
                        .readback
                        .as_ref()
                        .ok()
                        .and_then(|metadata| matches_preferences(
                            metadata,
                            &requested_preferences()
                        )),
                ),
                PreferenceResolution::Superseded
            );
        }

        /// 服务端暂不可读时保留未知状态；不能因拿不到证据而假定保存失败并立即重发。
        #[test]
        fn unreadable_preferences_remain_unknown_after_ack_or_transport_failure() {
            for update in [Ok(()), Err("disconnected")] {
                let mut connection = MockConnection {
                    update_count: 0,
                    readback_count: 0,
                    update: Some(update),
                    readback: Some(Err("metadata unavailable")),
                };
                let attempt = update_then_readback_once(
                    &mut connection,
                    |connection| {
                        connection.update_count += 1;
                        connection.update.take().expect("write result")
                    },
                    |connection| {
                        connection.readback_count += 1;
                        connection.readback.take().expect("readback result")
                    },
                );

                assert_eq!(
                    resolve(
                        &attempt.update,
                        attempt
                            .readback
                            .as_ref()
                            .ok()
                            .and_then(|metadata| matches_preferences(
                                metadata,
                                &requested_preferences()
                            )),
                    ),
                    PreferenceResolution::Unknown
                );
                assert_eq!(connection.update_count, 1);
                assert_eq!(connection.readback_count, 1);
            }
        }

        /// 最后状态必须覆盖 provider、模型、推理与权限，缺少协作字段不能当作完整读回。
        #[test]
        fn readback_requires_every_saved_preference_field() {
            let requested = requested_preferences();
            let mut metadata = requested_metadata();
            assert_eq!(matches_preferences(&metadata, &requested), Some(true));

            metadata["preferences"]["collaborationMode"] = json!("plan");
            assert_eq!(matches_preferences(&metadata, &requested), Some(false));
            metadata["preferences"]
                .as_object_mut()
                .unwrap()
                .remove("collaborationMode");
            assert_eq!(matches_preferences(&metadata, &requested), None);
        }

        /// 缺失字段即使前面的字段已不同时仍是未知，不能把损坏快照解释为可以直接重试。
        #[test]
        fn incomplete_different_readback_remains_unknown() {
            let mut metadata = requested_metadata();
            metadata["preferences"]["modelId"] = json!("model_b");
            metadata["preferences"]
                .as_object_mut()
                .unwrap()
                .remove("collaborationMode");

            assert_eq!(
                matches_preferences(&metadata, &requested_preferences()),
                None
            );
        }

        /// 错误字段类型不能作为“未生效”的证据，以免把协议异常转成可重试状态。
        #[test]
        fn malformed_readback_remains_unknown() {
            let mut metadata = requested_metadata();
            metadata["preferences"]["accessMode"] = json!(true);

            assert_eq!(
                matches_preferences(&metadata, &requested_preferences()),
                None
            );
        }

        /// 回读 fixture 与写入载荷使用完整字段，确保 CAS 之后的核实不漏掉推理和权限选择。
        fn requested_preferences() -> Value {
            json!({
                "providerId":"provider_a",
                "modelId":"model_a",
                "reasoningLevel":"high",
                "accessMode":"approval_required",
                "collaborationMode":"default"
            })
        }

        /// metadata fixture 保持服务端嵌套结构，缺字段场景可与正常权威快照区分。
        fn requested_metadata() -> Value {
            json!({"preferences":requested_preferences()})
        }

        /// 用除模型名外均完整一致的快照模拟另一客户端或旧写入结果。
        fn metadata_with_model(model_id: &str) -> Value {
            let mut metadata = requested_metadata();
            metadata["preferences"]["modelId"] = json!(model_id);
            metadata
        }

        /// 用混合缓存覆盖的紧凑摘要固定完整输入、未缓存输入与输出口径，避免测试数据暗示缓存可重复累加。
        fn usage_summary(thread_id: &str, revision: u64) -> ThreadUsageSummary {
            ThreadUsageSummary {
                thread_id: thread_id.to_owned(),
                snapshot_revision: revision,
                request_count: 5,
                measured_request_count: 4,
                new_input_request_count: 3,
                new_input_tokens: 140_000,
                output_request_count: 4,
                output_tokens: 449,
                total_request_count: 4,
                total_tokens: 210_449,
                cache_read_request_count: 3,
                cache_read_tokens: 28_000,
                cache_write_request_count: 1,
                cache_write_tokens: 2_000,
                cache_complete_request_count: 3,
                cache_complete_input_tokens: 170_000,
                cache_complete_read_tokens: 28_000,
            }
        }

        /// 构造冻结模型画像和可选估算，保证 status 文案不从当前配置反推请求窗口。
        fn usage_context(estimate: Option<ThreadContextEstimateDto>) -> ThreadContextUsageDto {
            ThreadContextUsageDto {
                turn_id: "turn_12345678".into(),
                model_request_id: "modelreq_12345678".into(),
                model_request_ordinal: 2,
                model_round: 1,
                purpose: "assistant".into(),
                certainty: if estimate.is_some() {
                    "unknown"
                } else {
                    "known"
                }
                .into(),
                profile: ProviderRequestProfileDto {
                    provider_id: "provider_a".into(),
                    model_id: "model_frozen".into(),
                    api: "responses".into(),
                    upstream_model: "gpt-6-luna".into(),
                    requested_reasoning: Some("high".into()),
                    effective_reasoning: Some("high".into()),
                    access_mode: "approval_required".into(),
                    collaboration_mode: "default".into(),
                    config_generation: "config_1".into(),
                    prompt_revision: "prompt_1".into(),
                    tool_catalog_revision: "tools_1".into(),
                    context_window_tokens: 1_000_000,
                    max_output_tokens: 32_000,
                },
                input_tokens: if estimate.is_some() {
                    None
                } else {
                    Some(24_000)
                },
                output_tokens: if estimate.is_some() { None } else { Some(400) },
                total_tokens: if estimate.is_some() {
                    None
                } else {
                    Some(24_400)
                },
                measured_at: "2026-10-09T00:00:00Z".into(),
                context_estimate: estimate,
            }
        }

        /// 同会话累计值允许刷新，但旧 revision 与旧连接回包不得覆盖当前权威摘要。
        #[test]
        fn usage_summary_requires_current_thread_generation_and_monotonic_revision() {
            let now = Instant::now();
            let mut state = usage::UsageState::default();
            state.bind_connection_generation(1, now);
            state.bind_thread(Some("thr_usage_a"), now);
            let first_identity = state.active_identity().unwrap();

            assert!(state.apply_summary(&first_identity, 4, usage_summary("thr_usage_a", 4)));
            assert!(state.mark_summary_stale(&first_identity));
            assert!(!state.apply_summary(&first_identity, 4, usage_summary("thr_usage_a", 3)));
            // 比缓存 revision 更新但仍旧于最近 history 的摘要不能恢复为“已刷新”。
            assert!(!state.apply_summary(&first_identity, 6, usage_summary("thr_usage_a", 5)));
            let retained = state.snapshot().unwrap();
            assert!(retained.stale);
            assert_eq!(retained.summary.unwrap().snapshot_revision, 4);

            state.advance_connection_generation(now + Duration::from_secs(1));
            assert_eq!(state.connection_generation(), 2);
            assert!(state.snapshot().unwrap().stale);
            assert!(!state.apply_summary(&first_identity, 0, usage_summary("thr_usage_a", 5)));
            let current_identity = state.active_identity().unwrap();
            assert!(state.apply_summary(&current_identity, 6, usage_summary("thr_usage_a", 6)));
            let current = state.snapshot().unwrap();
            assert!(!current.stale);
            assert_eq!(current.summary.unwrap().snapshot_revision, 6);
        }

        /// 切换 Thread 会清空旧显示，迟到摘要即使携带合法数据也不能串入新会话。
        #[test]
        fn switching_usage_thread_clears_cache_and_rejects_late_response() {
            let now = Instant::now();
            let mut state = usage::UsageState::default();
            state.bind_connection_generation(1, now);
            state.bind_thread(Some("thr_usage_a"), now);
            state.apply_summary(
                &state.active_identity().unwrap(),
                7,
                usage_summary("thr_usage_a", 7),
            );
            state.set_context("thr_usage_a", 1, Some(usage_context(None)));
            let late_response = state.active_identity().unwrap();

            assert_eq!(
                usage::clear_event_before_thread_switch(Some("thr_usage_a"), "thr_usage_b"),
                Some(UiEvent::SetUsage {
                    thread_id: "thr_usage_a".into(),
                    usage: None,
                })
            );
            assert_eq!(
                usage::clear_event_before_thread_switch(Some("thr_usage_a"), "thr_usage_a"),
                None
            );
            assert_eq!(
                usage::clear_event_before_thread_switch(None, "thr_usage_b"),
                None
            );

            assert!(state.bind_thread(Some("thr_usage_b"), now + Duration::from_secs(1)));
            let current = state.snapshot().unwrap();
            assert_eq!(current.summary, None);
            assert_eq!(current.context, None);
            assert!(!state.apply_summary(&late_response, 0, usage_summary("thr_usage_a", 8)));
        }

        /// 新 thread/read 的显式 null 清掉旧上下文，连接代际不匹配的快照则保持无副作用。
        #[test]
        fn context_null_replaces_old_measurement_and_identity_mismatch_is_ignored() {
            let now = Instant::now();
            let mut state = usage::UsageState::default();
            state.bind_connection_generation(1, now);
            state.bind_thread(Some("thr_usage_a"), now);
            assert!(state.set_context("thr_usage_a", 1, Some(usage_context(None))));
            assert!(state.set_context("thr_usage_a", 1, None));
            assert_eq!(state.snapshot().unwrap().context, None);

            assert!(state.set_context("thr_usage_a", 1, Some(usage_context(None))));
            state.bind_connection_generation(2, now + Duration::from_secs(1));
            assert!(!state.set_context("thr_usage_a", 1, None));
            assert!(state.snapshot().unwrap().context.is_some());
        }

        /// 持久事实在短 debounce 窗口内合并；文本增量不属于累计摘要刷新信号。
        #[test]
        fn persistent_usage_notifications_debounce_and_text_deltas_do_not_query() {
            let now = Instant::now();
            let mut state = usage::UsageState::default();
            state.bind_connection_generation(1, now);
            state.bind_thread(Some("thr_usage_a"), now);
            let immediate_identity = state.request_refresh_now().unwrap();
            assert_eq!(immediate_identity.thread_id, "thr_usage_a");
            assert_eq!(immediate_identity.connection_generation, 1);

            for method in [
                "assistant/model-step-committed",
                "assistant/context-estimated",
                "turn/input-consumed",
                "turn/retry-started",
                "turn/terminal",
                "context/compacted",
                "context/compaction-failed",
            ] {
                assert!(usage::UsageState::summary_changes_on_notification(method));
            }
            assert!(!usage::UsageState::summary_changes_on_notification(
                "assistant/text-delta"
            ));
            assert!(!usage::UsageState::summary_changes_on_notification(
                "assistant/reasoning-summary-delta"
            ));
            assert!(!usage::UsageState::summary_changes_on_notification(
                "context/compaction-started"
            ));

            state.schedule_summary_refresh(now);
            state.schedule_summary_refresh(now + Duration::from_millis(50));
            assert_eq!(
                state.take_due_refresh(now + Duration::from_millis(100)),
                None
            );
            let identity = state
                .take_due_refresh(now + Duration::from_millis(151))
                .unwrap();
            assert_eq!(identity.thread_id, "thr_usage_a");
            assert_eq!(state.take_due_refresh(now + Duration::from_secs(1)), None);
        }

        /// @author kongweiguang
        /// `/status` 即使元数据和历史都读失败，也要尝试摘要并用缓存重建详情，最后保留首要诊断。
        #[test]
        fn status_refresh_attempts_usage_and_cached_details_after_read_failures() {
            let mut attempted = Vec::new();
            let error = usage::run_status_attempts(
                |step| {
                    attempted.push(step);
                    match step {
                        usage::StatusAttemptStep::Metadata => Err("metadata unavailable"),
                        usage::StatusAttemptStep::History => Err("history timeout"),
                        usage::StatusAttemptStep::Usage | usage::StatusAttemptStep::Details => {
                            Ok(())
                        }
                    }
                },
                |error| *error == "history timeout",
            );
            assert_eq!(
                attempted,
                [
                    usage::StatusAttemptStep::Metadata,
                    usage::StatusAttemptStep::History,
                    usage::StatusAttemptStep::Usage,
                    usage::StatusAttemptStep::Details,
                ]
            );
            assert_eq!(error, Some("metadata unavailable"));

            let mut rendered_from_cache = false;
            let error = usage::run_status_attempts(
                |step| match step {
                    usage::StatusAttemptStep::Metadata | usage::StatusAttemptStep::History => {
                        Ok(())
                    }
                    usage::StatusAttemptStep::Usage => Err("summary unavailable"),
                    usage::StatusAttemptStep::Details => {
                        rendered_from_cache = true;
                        Ok(())
                    }
                },
                |_| false,
            );
            assert!(rendered_from_cache);
            assert_eq!(error, None);
        }

        /// @author kongweiguang
        /// 慢 refresh 超过 debounce 后，已排队的同批持久事实先重置计时，摘要只在队列空闲后读取。
        #[test]
        fn due_usage_read_waits_for_slow_and_already_queued_batch_events() {
            use std::collections::VecDeque;

            let started = Instant::now();
            let mut state = usage::UsageState::default();
            state.bind_connection_generation(1, started);
            state.bind_thread(Some("thr_usage_a"), started);
            state.schedule_summary_refresh(started);
            let mut queue = VecDeque::from(["slow-refresh", "model-step-committed"]);
            let mut processed = Vec::new();
            let mut now = started;
            let mut summary_reads = 0;

            let first_drain =
                usage::drain_ready_before(Instant::now(), 3, Duration::from_millis(50), || {
                    if let Some(event) = queue.pop_front() {
                        processed.push(event);
                        if event == "slow-refresh" {
                            now = started + Duration::from_millis(150);
                            std::thread::sleep(Duration::from_millis(55));
                        } else {
                            state.schedule_summary_refresh(now);
                            now = started + Duration::from_millis(300);
                        }
                        return Ok::<_, ()>(usage::ReadyEventDrain::Handled);
                    }
                    summary_reads += 1;
                    Ok(usage::ReadyEventDrain::Idle(
                        state
                            .take_due_refresh(now)
                            .expect("coalesced debounce has elapsed"),
                    ))
                })
                .unwrap();
            assert_eq!(first_drain, None);
            assert_eq!(processed, ["slow-refresh"]);
            assert_eq!(summary_reads, 0);

            let identity =
                usage::drain_ready_before(Instant::now(), 3, Duration::from_millis(50), || {
                    if let Some(event) = queue.pop_front() {
                        processed.push(event);
                        state.schedule_summary_refresh(now);
                        now = started + Duration::from_millis(300);
                        return Ok::<_, ()>(usage::ReadyEventDrain::Handled);
                    }
                    summary_reads += 1;
                    Ok(usage::ReadyEventDrain::Idle(
                        state
                            .take_due_refresh(now)
                            .expect("coalesced debounce has elapsed"),
                    ))
                })
                .unwrap()
                .expect("idle queue yields a due identity");

            assert_eq!(processed, ["slow-refresh", "model-step-committed"]);
            assert_eq!(summary_reads, 1);
            assert_eq!(identity.thread_id, "thr_usage_a");
        }

        /// @author kongweiguang
        /// Ready 通知持续补入时单轮 drain 有界返回，让 event loop 回去处理 F5、取消和退出动作。
        #[test]
        fn continuously_ready_usage_queue_yields_without_reading_summary() {
            let mut processed = 0;
            let mut due_checks = 0;
            let result =
                usage::drain_ready_before(Instant::now(), 4, Duration::from_secs(1), || {
                    processed += 1;
                    if processed <= 4 {
                        Ok::<_, ()>(
                            usage::ReadyEventDrain::<Option<usage::UsageReadIdentity>>::Handled,
                        )
                    } else {
                        due_checks += 1;
                        Ok(usage::ReadyEventDrain::Idle(None))
                    }
                })
                .unwrap();

            assert_eq!(processed, 4);
            assert_eq!(due_checks, 0);
            assert_eq!(result, None);
        }

        /// @author kongweiguang
        /// 状态读取诊断在详情正文可见，沿用现有错误说明并给出 F5 恢复操作。
        #[test]
        fn status_read_warning_is_visible_inside_details_and_off_when_verified() {
            assert_eq!(
                usage::status_read_warning(Some("元数据：连接失败")),
                "\n\n状态部分未核实；按 F5 重试。\n元数据：连接失败"
            );
            assert!(usage::status_read_warning(None).is_empty());
        }

        /// 构造完整的冻结文件响应，让回归测试遵守生产合同中的字节长度与摘要字段。
        fn diff_file_response(artifact_id: &str, file_path: &str, content: &[u8]) -> Value {
            use base64::Engine;
            use sha2::Digest;

            let digest = sha2::Sha256::digest(content);
            let mut sha256 = String::with_capacity(64);
            for byte in digest {
                sha256.push_str(&format!("{byte:02x}"));
            }
            json!({
                "artifactId":artifact_id,
                "filePath":file_path,
                "byteLength":content.len(),
                "sha256":sha256,
                "contentBase64":base64::engine::general_purpose::STANDARD.encode(content)
            })
        }

        /// @author kongweiguang
        /// F5 只补齐同轮 native 快照；后续轮和继承轮不能替换身份，已冻结正文必须保持原样。
        #[test]
        fn diff_refresh_fills_only_uncaptured_matching_native_turn() {
            let completed_change_set = json!({
                "state":"complete","artifactId":"artifact_same",
                "files":[{"path":"same.txt","truncated":false}]
            });
            let mut frozen_history = json!({"turns":[{
                "origin":"native","turnId":"turn_frozen","changeSet":null
            }]});
            let current_history = json!({"turns":[
                {"origin":"native","turnId":"turn_frozen","changeSet":completed_change_set},
                {"origin":"inherited","turnId":"turn_frozen","changeSet":{
                    "state":"complete","artifactId":"artifact_inherited",
                    "files":[{"path":"inherited.txt","truncated":false}]
                }},
                {"origin":"native","turnId":"turn_later","changeSet":{
                    "state":"complete","artifactId":"artifact_later",
                    "files":[{"path":"later.txt","truncated":false}]
                }}
            ]});

            interactive::refresh_uncaptured_diff_turn(&mut frozen_history, &current_history);
            assert_eq!(
                frozen_history["turns"][0]["changeSet"],
                current_history["turns"][0]["changeSet"]
            );

            let mut inherited_only = json!({"turns":[{
                "origin":"native","turnId":"turn_frozen","changeSet":null
            }]});
            let inherited_history = json!({"turns":[{
                "origin":"inherited","turnId":"turn_frozen","changeSet":{
                    "state":"complete","artifactId":"artifact_inherited",
                    "files":[{"path":"inherited.txt","truncated":false}]
                }
            }]});
            interactive::refresh_uncaptured_diff_turn(&mut inherited_only, &inherited_history);
            assert!(inherited_only["turns"][0]["changeSet"].is_null());

            let frozen_change_set = json!({
                "state":"complete","artifactId":"artifact_frozen",
                "files":[{"path":"frozen.txt","truncated":false}]
            });
            let mut captured_history = json!({"turns":[{
                "origin":"native","turnId":"turn_frozen","changeSet":frozen_change_set
            }]});
            let captured_snapshot = captured_history["turns"][0]["changeSet"].clone();
            interactive::refresh_uncaptured_diff_turn(&mut captured_history, &current_history);
            assert_eq!(captured_history["turns"][0]["changeSet"], captured_snapshot);
            let body = interactive::read_turn_diff("thr_diff", &captured_history, |params| {
                assert_eq!(
                    params,
                    json!({
                        "threadId":"thr_diff","turnId":"turn_frozen",
                        "artifactId":"artifact_frozen","filePath":"frozen.txt"
                    })
                );
                Ok(diff_file_response(
                    "artifact_frozen",
                    "frozen.txt",
                    b"+original frozen body\n",
                ))
            })
            .unwrap();
            assert!(body.contains("+original frozen body"));
            assert!(!body.contains("same.txt"));
        }

        /// 空回复和捕获不完整均是合法事实；不得读取 artifact 或回退到更早有修改的 Turn。
        #[test]
        fn diff_without_artifact_reports_empty_partial_and_unknown_changes() {
            for (change_set, expected) in [
                (
                    json!({"state":"complete","files":[]}),
                    "本次回复没有文件修改",
                ),
                (json!({"state":"partial","files":[]}), "本次变更记录不完整"),
                (Value::Null, "本次回复尚无冻结变更记录"),
                (
                    json!({"state":"partial","files":[{"path":"large.txt","truncated":true}]}),
                    "此文件的差异正文未捕获",
                ),
            ] {
                let history = json!({"turns":[
                    {"turnId":"turn_old","changeSet":{
                        "state":"complete","artifactId":"artifact_old",
                        "files":[{"path":"old.txt","truncated":false}]
                    }},
                    {"turnId":"turn_latest","changeSet":change_set}
                ]});
                let body = interactive::read_turn_diff("thr_diff", &history, |_| {
                    panic!("no captured diff should trigger an artifact read")
                })
                .unwrap();
                assert!(body.contains(expected), "{body}");
                assert!(!body.contains("old.txt"));
            }
            let empty = interactive::read_turn_diff("thr_diff", &json!({"turns":[]}), |_| {
                panic!("an empty thread should not read artifacts")
            })
            .unwrap();
            assert_eq!(empty, "当前会话还没有回复记录");
        }

        /// 继承前缀不属于当前会话；最近 native Turn 为零变更时不回退到更早的文件记录。
        #[test]
        fn diff_uses_latest_native_turn_and_ignores_inherited_changes() {
            let history = json!({"turns":[
                {"origin":"inherited","turnId":"turn_inherited","changeSet":{
                    "state":"complete","artifactId":"artifact_inherited",
                    "files":[{"path":"parent.txt","truncated":false}]
                }},
                {"origin":"native","turnId":"turn_previous","changeSet":{
                    "state":"complete","artifactId":"artifact_previous",
                    "files":[{"path":"previous.txt","truncated":false}]
                }},
                {"origin":"native","turnId":"turn_latest","changeSet":{
                    "state":"complete","files":[]
                }}
            ]});

            let body = interactive::read_turn_diff("thr_diff", &history, |_| {
                panic!("latest native zero-change turn must not read earlier artifacts")
            })
            .unwrap();

            assert_eq!(body, "本次回复没有文件修改");
        }

        /// 混合捕获只读取有正文的原轮次文件，超限文件仍显示且不会发出必然失败的 RPC。
        #[test]
        fn diff_reads_frozen_identity_and_skips_uncaptured_files() {
            let history = json!({"turns":[{"turnId":"turn_latest","changeSet":{
                "state":"partial","artifactId":"artifact_diff","files":[
                    {"path":"large.txt","truncated":true},
                    {"path":"正常.txt","truncated":false}
                ]
            }}]});
            let mut calls = 0;
            let body = interactive::read_turn_diff("thr_diff", &history, |params| {
                calls += 1;
                assert_eq!(
                    params,
                    json!({
                        "threadId":"thr_diff","turnId":"turn_latest",
                        "artifactId":"artifact_diff","filePath":"正常.txt"
                    })
                );
                Ok(diff_file_response(
                    "artifact_diff",
                    "正常.txt",
                    "+中文🙂\n".as_bytes(),
                ))
            })
            .unwrap();
            assert_eq!(calls, 1);
            assert!(body.contains("变更记录不完整"));
            assert!(body.contains("large.txt"));
            assert!(body.contains("+中文🙂"));
        }

        /// 错误响应身份、字段、长度、摘要、Base64 与 UTF-8 一律拒绝，不能伪装成可读差异。
        #[test]
        fn diff_rejects_malformed_artifact_response() {
            let history = json!({"turns":[{"turnId":"turn_latest","changeSet":{
                "state":"complete","artifactId":"artifact_diff",
                "files":[{"path":"x.txt","truncated":false}]
            }}]});
            let valid = diff_file_response("artifact_diff", "x.txt", b"+ok\n");
            let mut missing_field = valid.clone();
            missing_field.as_object_mut().unwrap().remove("byteLength");
            let mut wrong_artifact = valid.clone();
            wrong_artifact["artifactId"] = json!("artifact_other");
            let mut wrong_path = valid.clone();
            wrong_path["filePath"] = json!("other.txt");
            let mut wrong_length = valid.clone();
            wrong_length["byteLength"] = json!(5);
            let mut wrong_digest = valid.clone();
            wrong_digest["sha256"] = json!("0".repeat(64));
            let mut noncanonical_base64 = valid;
            noncanonical_base64["contentBase64"] = json!("eB==");

            let cases = [
                ("缺失字段", missing_field),
                ("artifact 身份", wrong_artifact),
                ("文件路径身份", wrong_path),
                ("字节长度", wrong_length),
                ("SHA-256", wrong_digest),
                ("非规范 Base64", noncanonical_base64),
                (
                    "非法 UTF-8",
                    diff_file_response("artifact_diff", "x.txt", &[0xff]),
                ),
            ];
            for (label, response) in cases {
                let result =
                    interactive::read_turn_diff("thr_diff", &history, |_| Ok(response.clone()));
                assert!(result.is_err(), "{label}响应不应被接受");
            }
        }

        /// 详情遵守 UI 96 KiB 总预算；首次文件超限后保留 UTF-8 提示并停止后续读取。
        #[test]
        fn diff_stops_reading_after_unicode_display_budget() {
            let history = json!({"turns":[{"turnId":"turn_latest","changeSet":{
                "state":"complete","artifactId":"artifact_diff","files":[
                    {"path":"large.txt","truncated":false},
                    {"path":"not-read.txt","truncated":false}
                ]
            }}]});
            let content = "🙂".repeat(30_000);
            let mut calls = 0;

            let body = interactive::read_turn_diff("thr_diff", &history, |params| {
                calls += 1;
                assert_eq!(params["filePath"], "large.txt");
                Ok(diff_file_response(
                    "artifact_diff",
                    "large.txt",
                    content.as_bytes(),
                ))
            })
            .unwrap();

            assert_eq!(calls, 1, "达到详情预算后不应读取下一文件");
            assert!(body.len() <= 96 * 1024);
            assert!(body.ends_with("后续文件未读取）"));
            assert!(body.contains("96 KiB"));
            assert!(body.is_char_boundary(body.len()));
        }

        /// 请求构造器在调用 RPC 前拒绝非法冻结路径，避免把文件读取扩展成任意路径探测。
        #[test]
        fn diff_rejects_invalid_frozen_file_request_before_rpc() {
            let history = json!({"turns":[{"turnId":"turn_latest","changeSet":{
                "state":"complete","artifactId":"artifact_diff",
                "files":[{"path":"../secret.txt","truncated":false}]
            }}]});

            let result = interactive::read_turn_diff("thr_diff", &history, |_| {
                panic!("invalid frozen path must be rejected before RPC")
            });

            assert!(result.is_err());
        }

        /// @author kongweiguang
        /// Tool 预览必须标清截断和可读取产物状态，只有成功读回 artifact 才能称完整产物。
        #[test]
        fn tool_detail_labels_preview_truncation_and_full_artifact() {
            assert_eq!(
                interactive::tool_detail_body("安全预览", true, false, None),
                "结果已截断；以下为安全预览，当前没有可读取的完整产物。\n\n安全预览"
            );
            let truncated_preview = interactive::tool_detail_body("安全预览", true, true, None);
            assert_eq!(
                truncated_preview,
                "结果已截断；以下为安全预览，完整产物读取成功后更新。\n\n安全预览"
            );
            assert!(!truncated_preview.contains("正在读取"));
            let available_preview = interactive::tool_detail_body("安全预览", false, true, None);
            assert_eq!(
                available_preview,
                "以下为安全展示预览，完整产物读取成功后更新。\n\n安全预览"
            );
            assert!(!available_preview.contains("正在读取"));
            assert_eq!(
                interactive::tool_detail_body("", true, true, Some("完整输出")),
                "工具产物\n\n完整输出"
            );
        }

        /// @author kongweiguang
        /// 分页只接纳同一 artifact 的连续 code point 范围，并在错误身份或超限时失败关闭。
        #[test]
        fn tool_artifact_pages_validate_identity_offsets_and_size() {
            let mut offsets = Vec::new();
            let complete = interactive::read_tool_artifact_pages(
                "thr_tool",
                "turn_tool",
                "call_tool",
                "artifact_tool",
                |params, timeout| {
                    assert_eq!(params["threadId"], "thr_tool");
                    assert_eq!(params["turnId"], "turn_tool");
                    assert_eq!(params["callId"], "call_tool");
                    assert_eq!(params["artifactId"], "artifact_tool");
                    assert_eq!(params["limitCharacters"], 32_768);
                    assert!(timeout > Duration::ZERO && timeout <= Duration::from_secs(15));
                    let offset = params["offsetCharacters"].as_u64().unwrap();
                    offsets.push(offset);
                    Ok::<_, CliError>(match offset {
                        0 => json!({
                            "artifactId":"artifact_tool",
                            "offsetCharacters":0,
                            "totalCharacters":3,
                            "truncated":true,
                            "nextOffsetCharacters":2,
                            "content":"甲🙂"
                        }),
                        2 => json!({
                            "artifactId":"artifact_tool",
                            "offsetCharacters":2,
                            "totalCharacters":3,
                            "truncated":false,
                            "nextOffsetCharacters":null,
                            "content":"乙"
                        }),
                        other => panic!("unexpected artifact page offset: {other}"),
                    })
                },
            )
            .unwrap();
            assert_eq!(complete, "甲🙂乙");
            assert_eq!(offsets, [0, 2]);

            let mismatch = interactive::read_tool_artifact_pages(
                "thr_tool",
                "turn_tool",
                "call_tool",
                "artifact_tool",
                |_, _| {
                    Ok::<_, CliError>(json!({
                        "artifactId":"artifact_other",
                        "offsetCharacters":0,
                        "totalCharacters":0,
                        "truncated":false,
                        "nextOffsetCharacters":null,
                        "content":""
                    }))
                },
            );
            assert!(mismatch.is_err());

            let oversized = interactive::read_tool_artifact_pages(
                "thr_tool",
                "turn_tool",
                "call_tool",
                "artifact_tool",
                |_, _| {
                    Ok::<_, CliError>(json!({
                        "artifactId":"artifact_tool",
                        "offsetCharacters":0,
                        "totalCharacters":4_194_305,
                        "truncated":false,
                        "nextOffsetCharacters":null,
                        "content":""
                    }))
                },
            );
            assert!(oversized.is_err());
        }

        /// `/status` 标清缓存已含在完整输入中，并保留各账本覆盖数与冻结请求估算身份。
        #[test]
        fn usage_status_explains_exact_coverage_cache_and_frozen_context_estimate() {
            let usage = UiUsageSnapshot {
                summary: Some(usage_summary("thr_usage_a", 12)),
                stale: false,
                context: Some(usage_context(Some(ThreadContextEstimateDto {
                    certainty: "estimated".into(),
                    source: "local".into(),
                    used_tokens: 24_000,
                    limit_tokens: 1_000_000,
                    measured_at: "2026-10-09T00:00:00Z".into(),
                }))),
            };
            let detail = usage::status_lines(Some(&usage)).join("\n");

            assert!(detail.contains("输入（含缓存）：210,000 tokens（4/4 个已计量请求）"));
            assert!(detail.contains("新输入（未缓存）：140,000 tokens（3/4 个已计量请求）"));
            assert!(detail.contains("输出：449 tokens（4/4 个已计量请求）"));
            assert!(
                detail.contains("总计（输入含缓存 + 输出）：210,449 tokens（4/4 个已计量请求）")
            );
            assert!(detail.contains(
        "缓存明细（已含在输入中，不重复累加）：读取 28,000 tokens（3/4 个已计量请求）；写入 2,000 tokens（1/4 个已计量请求）"
    ));
            assert!(detail.contains("28,000/170,000 tokens（16.5%）"));
            assert!(detail.contains(
                "最近请求上下文：约 24,000 / 1,000,000 tokens（Java 本地估算，冻结模型 gpt-6-luna）"
            ));
            assert!(detail.contains("本地输入估算，Provider 实测返回后校正；成功压缩会降低占用"));
            assert!(detail.contains("累计为本会话多次请求合计"));
            assert!(!usage::status_lines(None).join("\n").contains("0 tokens"));
        }

        /// 完整输入只有在总量与输出都覆盖所有已计量请求且减法合法时才展示，避免凑齐不同子集或显示负值。
        #[test]
        fn usage_status_withholds_full_input_when_coverage_or_subtraction_is_invalid() {
            let mut incomplete = usage_summary("thr_usage_a", 12);
            incomplete.output_request_count = 3;
            let incomplete_detail = usage::status_lines(Some(&UiUsageSnapshot {
                summary: Some(incomplete),
                stale: false,
                context: None,
            }))
            .join("\n");
            assert!(incomplete_detail.contains(
        "输入（含缓存）：未报告（总量 4/4、输出 3/4 个已计量请求，需完整覆盖且总量不少于输出）"
    ));

            let mut impossible = usage_summary("thr_usage_a", 13);
            impossible.total_tokens = 448;
            let impossible_detail = usage::status_lines(Some(&UiUsageSnapshot {
                summary: Some(impossible),
                stale: false,
                context: None,
            }))
            .join("\n");
            assert!(impossible_detail.contains(
        "输入（含缓存）：未报告（总量 4/4、输出 4/4 个已计量请求，需完整覆盖且总量不少于输出）"
    ));
            assert!(!impossible_detail.contains("输入（含缓存）：-1 tokens"));
        }

        /// 无 coverage 的服务端零值保持未报告，只有正 coverage 才能显示确切零 token。
        #[test]
        fn usage_status_distinguishes_missing_measurement_from_reported_zero() {
            let mut empty = usage_summary("thr_usage_a", 12);
            empty.request_count = 2;
            empty.measured_request_count = 0;
            empty.new_input_request_count = 0;
            empty.new_input_tokens = 0;
            empty.output_request_count = 0;
            empty.output_tokens = 0;
            empty.total_request_count = 0;
            empty.total_tokens = 0;
            empty.cache_read_request_count = 0;
            empty.cache_read_tokens = 0;
            empty.cache_write_request_count = 0;
            empty.cache_write_tokens = 0;
            empty.cache_complete_request_count = 0;
            empty.cache_complete_input_tokens = 0;
            empty.cache_complete_read_tokens = 0;
            let empty_detail = usage::status_lines(Some(&UiUsageSnapshot {
                summary: Some(empty),
                stale: false,
                context: None,
            }))
            .join("\n");
            assert!(empty_detail.contains("累计：尚无已报告用量（0/2 个请求已计量）"));
            assert!(empty_detail.contains(
        "输入（含缓存）：未报告（总量 0/0、输出 0/0 个已计量请求，需完整覆盖且总量不少于输出）"
    ));
            assert!(empty_detail.contains("新输入（未缓存）：未报告（0/0 个已计量请求）"));
            assert!(empty_detail.contains("输出：未报告（0/0 个已计量请求）"));
            assert!(empty_detail.contains("总计（输入含缓存 + 输出）：未报告（0/0 个已计量请求）"));
            assert!(
                empty_detail.contains(
                    "缓存明细（已含在输入中，不重复累加）：读取 未报告（0/0 个已计量请求）"
                )
            );
            assert!(empty_detail.contains("缓存命中：未报告（0/0 个完整样本请求）"));
            assert!(!empty_detail.contains("0 tokens"));

            let mut reported_zero = usage_summary("thr_usage_a", 13);
            reported_zero.new_input_request_count = 2;
            reported_zero.new_input_tokens = 0;
            let zero_detail = usage::status_lines(Some(&UiUsageSnapshot {
                summary: Some(reported_zero),
                stale: false,
                context: None,
            }))
            .join("\n");
            assert!(zero_detail.contains("新输入（未缓存）：0 tokens（2/4 个已计量请求）"));
        }

        /// 摘要 Provider 请求不能冒充当前普通 Assistant 上下文，即使其冻结画像含估算也不展示。
        #[test]
        fn summary_request_is_not_projected_as_current_assistant_context() {
            let mut context = usage_context(Some(ThreadContextEstimateDto {
                certainty: "estimated".into(),
                source: "local".into(),
                used_tokens: 24_000,
                limit_tokens: 1_000_000,
                measured_at: "2026-10-09T00:00:00Z".into(),
            }));
            context.purpose = "summary".into();
            let detail = usage::status_lines(Some(&UiUsageSnapshot {
                summary: None,
                stale: false,
                context: Some(context),
            }))
            .join("\n");

            assert!(detail.contains("最近请求上下文：尚无已报告的普通 Assistant 请求用量"));
            assert!(!detail.contains("冻结模型"));
            assert!(!detail.contains("24,000"));
        }

        /// UNKNOWN 是内部 certainty 枚举，用户状态改用“未报告”并清楚标注仍冻结的请求模型。
        #[test]
        fn unknown_context_status_uses_user_facing_unreported_copy() {
            let mut context = usage_context(None);
            context.certainty = "unknown".into();
            context.input_tokens = None;
            context.output_tokens = None;
            context.total_tokens = None;
            let detail = usage::status_lines(Some(&UiUsageSnapshot {
                summary: None,
                stale: false,
                context: Some(context),
            }))
            .join("\n");

            assert!(detail.contains(
        "最近请求上下文：未知（Provider 未报告且暂无本地估算，冻结模型 gpt-6-luna，窗口 1,000,000 tokens）"
    ));
            assert!(!detail.contains("UNKNOWN"));
        }

        /// history 必须携带共享协议定义的 nullable 槽位，缺字段不能被宽松当作 UNKNOWN。
        #[test]
        fn context_history_requires_explicit_nullable_usage_field() {
            assert!(
                usage::context_usage_from_history(&json!({"contextUsage":null}))
                    .unwrap()
                    .is_none()
            );
            assert!(usage::context_usage_from_history(&json!({})).is_err());
        }

        /// @author kongweiguang
        /// 旧页去重只依赖 item identity，不以已浏览条数截断；游标页与最新页交叠时只新增一次。
        #[test]
        fn older_history_page_deduplicates_without_a_display_count_limit() {
            let older_items = (0..1_005)
                .map(|index| json!({"itemId":format!("item_old_{index}")}))
                .collect::<Vec<_>>();
            let latest_items = vec![json!({"itemId":"item_latest"})];
            let page_items = vec![
                json!({"itemId":"item_before_latest"}),
                json!({"itemId":"item_old_1004"}),
                json!({"itemId":"item_latest"}),
                json!({"itemId":"item_after_latest"}),
            ];

            let page =
                interactive::unseen_history_page_items(&older_items, &latest_items, &page_items)
                    .unwrap();

            assert_eq!(
                page.iter()
                    .map(|item| item["itemId"].as_str().unwrap())
                    .collect::<Vec<_>>(),
                ["item_before_latest", "item_after_latest"]
            );
        }

        /// @author kongweiguang
        /// 旧页 Turn 元数据需保留在最新页前，并按稳定 turnId 合并边界重叠。
        #[test]
        fn merged_history_turns_preserve_old_source_and_order() {
            let older = vec![
                json!({"turnId":"turn_old","origin":"inherited"}),
                json!({"turnId":"turn_boundary","origin":"inherited","status":"running"}),
            ];
            let latest = vec![
                json!({"turnId":"turn_boundary","origin":"inherited","status":"completed"}),
                json!({"turnId":"turn_latest","origin":"native"}),
            ];

            let merged = interactive::merged_history_turns(&older, &latest).unwrap();

            assert_eq!(
                merged
                    .iter()
                    .map(|turn| turn["turnId"].as_str().unwrap())
                    .collect::<Vec<_>>(),
                ["turn_old", "turn_boundary", "turn_latest"]
            );
            assert_eq!(merged[1]["origin"], "inherited");
            assert_eq!(merged[1]["status"], "completed");
            assert!(interactive::merged_history_turns(&[json!({})], &[]).is_err());
        }

        /// @author kongweiguang
        /// Hydration 需保留结构化引用顺序并传回完整 UTF-8 正文，不能用预览长度重切 Unicode 字符。
        #[test]
        fn older_user_input_hydration_preserves_references_and_unicode_body() {
            let mut item = json!({
                "itemId":"item_old_input",
                "kind":"user_input",
                "totalCharacters":25,
                "truncated":true,
                "content":[
                    {"type":"text","text":"旧预览"},
                    {"type":"workspace_reference","relativePath":"src/lib.rs"},
                    {"type":"text","text":"另一个预览"}
                ]
            });
            let mut reads = 0;

            interactive::hydrate_public_history_item(&mut item, |item_id, total| {
                reads += 1;
                assert_eq!(item_id, "item_old_input");
                assert_eq!(total, 25);
                Ok("中文 🙂 e\u{301} 完整正文".into())
            })
            .unwrap();

            assert_eq!(reads, 1);
            assert_eq!(item["truncated"], false);
            let content = item["content"].as_array().unwrap();
            assert_eq!(content.len(), 2);
            assert_eq!(content[0]["text"], "中文 🙂 e\u{301} 完整正文");
            assert_eq!(content[1]["relativePath"], "src/lib.rs");
        }

        /// @author kongweiguang
        /// 尾页首次归档须补齐多 Turn 的全部公开文本；无字节上限且后续快照复用已核验全文。
        #[test]
        fn baseline_hydrates_every_public_text_and_reuses_complete_bodies() {
            let long_body = "界🙂e\u{301}".repeat(20_000);
            assert!(long_body.len() > 96 * 1024);
            let bodies = std::collections::HashMap::from([
                ("item_user".to_owned(), "问题：中文和 🙂".to_owned()),
                ("item_progress".to_owned(), "正在检索 ✅".to_owned()),
                ("item_answer".to_owned(), long_body),
                (
                    "item_reasoning".to_owned(),
                    "摘要：组合字符 e\u{301}".to_owned(),
                ),
                ("item_message".to_owned(), "来自另一会话的消息".to_owned()),
            ]);
            let item = |item_id: &str, kind: &str, turn_id: &str, preview: Value| {
                let total_characters = bodies[item_id].chars().count() as u64;
                let mut item = json!({
                    "itemId":item_id,
                    "kind":kind,
                    "turnId":turn_id,
                    "totalCharacters":total_characters,
                    "truncated":true
                });
                match kind {
                    "user_input" => item["content"] = preview,
                    "thread_message" => item["content"] = preview,
                    _ => item["text"] = preview,
                }
                item
            };
            let original = json!({
                "threadId":"thr_baseline",
                "turns":[{"turnId":"turn_old"},{"turnId":"turn_latest"}],
                "items":[
                    item("item_user", "user_input", "turn_old", json!([
                        {"type":"text","text":"问题预览"},
                        {"type":"workspace_reference","relativePath":"src/main.rs"},
                        {"type":"text","text":"续文预览"}
                    ])),
                    item("item_progress", "assistant_progress", "turn_old", json!("过程预览")),
                    item("item_answer", "final_answer", "turn_old", json!("答案预览")),
                    item("item_reasoning", "reasoning_summary", "turn_latest", json!("摘要预览")),
                    item("item_message", "thread_message", "turn_latest", json!("消息预览"))
                ]
            });
            let mut first_read = original.clone();
            let mut item_reads = std::collections::HashMap::<String, usize>::new();
            let mut page_reads = 0;

            interactive::hydrate_history_public_items(
                &mut first_read,
                &Value::Null,
                |item_id, expected_total| {
                    let body = &bodies[item_id];
                    assert_eq!(body.chars().count() as u64, expected_total);
                    *item_reads.entry(item_id.to_owned()).or_default() += 1;
                    interactive::read_item_content_pages(
                        "thr_baseline",
                        item_id,
                        expected_total,
                        None,
                        |params, _| {
                            page_reads += 1;
                            assert_eq!(params["threadId"], "thr_baseline");
                            assert_eq!(params["itemId"], item_id);
                            let offset = params["offsetCharacters"].as_u64().unwrap();
                            let limit = params["limitCharacters"].as_u64().unwrap();
                            let content = body
                                .chars()
                                .skip(offset as usize)
                                .take(limit as usize)
                                .collect::<String>();
                            let next = offset + content.chars().count() as u64;
                            let truncated = next < expected_total;
                            Ok(json!({
                                "itemId":item_id,
                                "offsetCharacters":offset,
                                "totalCharacters":expected_total,
                                "content":content,
                                "truncated":truncated,
                                "nextOffsetCharacters":if truncated { json!(next) } else { Value::Null }
                            }))
                        },
                    )
                },
            )
            .unwrap();

            assert_eq!(item_reads.len(), bodies.len());
            assert!(item_reads.values().all(|count| *count == 1));
            assert!(page_reads > bodies.len());
            assert_eq!(first_read["turns"].as_array().unwrap().len(), 2);
            for item in first_read["items"].as_array().unwrap() {
                assert_eq!(item["truncated"], false);
                let item_id = item["itemId"].as_str().unwrap();
                let actual = match item["kind"].as_str().unwrap() {
                    "user_input" => item["content"][0]["text"].as_str().unwrap(),
                    "thread_message" => item["content"].as_str().unwrap(),
                    _ => item["text"].as_str().unwrap(),
                };
                assert_eq!(actual, bodies[item_id]);
            }
            assert_eq!(
                first_read["items"][0]["content"].as_array().unwrap().len(),
                2
            );
            assert_eq!(
                first_read["items"][0]["content"][1]["relativePath"],
                "src/main.rs"
            );

            let mut reread = original;
            let mut unexpected_reads = 0;
            interactive::hydrate_history_public_items(&mut reread, &first_read, |_, _| {
                unexpected_reads += 1;
                Err(CliError::protocol("完整缓存正文不应重新读取"))
            })
            .unwrap();
            assert_eq!(unexpected_reads, 0);
            assert_eq!(reread, first_read);
        }

        /// @author kongweiguang
        /// 历史 hydration 预算按真实 UTF-8 字节累计，恰好命中上限的多页中文正文应完整返回。
        #[test]
        fn bounded_history_reader_counts_utf8_bytes_across_pages() {
            let mut pages = [
                json!({
                    "itemId":"item_utf8",
                    "offsetCharacters":0,
                    "totalCharacters":2,
                    "content":"中",
                    "truncated":true,
                    "nextOffsetCharacters":1
                }),
                json!({
                    "itemId":"item_utf8",
                    "offsetCharacters":1,
                    "totalCharacters":2,
                    "content":"🙂",
                    "truncated":false,
                    "nextOffsetCharacters":null
                }),
            ]
            .into_iter();
            let cancelled = AtomicBool::new(false);
            let mut consumed_bytes = 0;
            let body = interactive::read_bounded_item_content_pages(
                "thr_utf8",
                "item_utf8",
                2,
                interactive::Utf8ReadBudget {
                    maximum_bytes: "中🙂".len(),
                    consumed_bytes: &mut consumed_bytes,
                },
                Instant::now() + Duration::from_secs(1),
                &cancelled,
                |params, _| {
                    assert_eq!(params["threadId"], "thr_utf8");
                    assert_eq!(params["itemId"], "item_utf8");
                    Ok(pages.next().expect("one response per requested page"))
                },
            )
            .unwrap();

            assert_eq!(body.as_deref(), Some("中🙂"));
            assert_eq!(consumed_bytes, "中🙂".len());
        }

        /// @author kongweiguang
        /// 连接期间收到取消时不能发首个 thread/read；新请求仍可读，旧请求返回后取消也拒绝落视图。
        #[test]
        fn older_history_page_rechecks_cancel_after_connect_and_after_rpc() {
            use std::sync::atomic::Ordering;

            let cancelled = AtomicBool::new(false);
            let connect = || cancelled.store(true, Ordering::Release);
            connect();
            let result = interactive::request_older_history_page(
                "thr_old",
                "cursor_old",
                Instant::now() + Duration::from_secs(1),
                &cancelled,
                |_, _| panic!("cancel received during connect must prevent thread/read"),
            );
            assert!(result.is_err());

            cancelled.store(false, Ordering::Release);
            let page = json!({"threadId":"thr_current", "items":[]});
            let current = interactive::request_older_history_page(
                "thr_current",
                "cursor_current",
                Instant::now() + Duration::from_secs(1),
                &cancelled,
                |params, timeout| {
                    assert_eq!(params["threadId"], "thr_current");
                    assert_eq!(params["cursor"], "cursor_current");
                    assert!(timeout <= Duration::from_secs(1));
                    Ok(page.clone())
                },
            )
            .unwrap();
            assert_eq!(current, page);

            let late = interactive::request_older_history_page(
                "thr_current",
                "cursor_current",
                Instant::now() + Duration::from_secs(1),
                &cancelled,
                |_, _| {
                    cancelled.store(true, Ordering::Release);
                    Ok(page)
                },
            );
            assert!(late.is_err());
        }

        /// @author kongweiguang
        /// 超预算的正文保留原 truncated 标记；取消在首个 RPC 前生效，不能把预览误认作全文。
        #[test]
        fn bounded_history_reader_preserves_preview_on_overflow_and_cancel() {
            let item = json!({
                "itemId":"item_preview",
                "kind":"final_answer",
                "totalCharacters":2,
                "text":"预览",
                "truncated":true
            });
            let cancelled = AtomicBool::new(false);
            let mut consumed_bytes = 0;
            let mut reads = 0;
            let result = interactive::read_bounded_item_content_pages(
                "thr_preview",
                "item_preview",
                2,
                interactive::Utf8ReadBudget {
                    maximum_bytes: "中文".len() - 1,
                    consumed_bytes: &mut consumed_bytes,
                },
                Instant::now() + Duration::from_secs(1),
                &cancelled,
                |_, _| {
                    reads += 1;
                    Ok(json!({
                        "itemId":"item_preview",
                        "offsetCharacters":0,
                        "totalCharacters":2,
                        "content":"中",
                        "truncated":true,
                        "nextOffsetCharacters":1
                    }))
                },
            )
            .unwrap();
            assert_eq!(result, None);
            assert_eq!(reads, 1);
            assert_eq!(consumed_bytes, "中".len());
            assert_eq!(item["truncated"], true);
            assert_eq!(item["text"], "预览");

            let cancelled = AtomicBool::new(true);
            let mut consumed_bytes = 0;
            let result = interactive::read_bounded_item_content_pages(
                "thr_preview",
                "item_preview",
                2,
                interactive::Utf8ReadBudget {
                    maximum_bytes: 8,
                    consumed_bytes: &mut consumed_bytes,
                },
                Instant::now() + Duration::from_secs(1),
                &cancelled,
                |_, _| panic!("cancelled work must not issue another page read"),
            );
            assert!(result.is_err());
        }

        /// @author kongweiguang
        /// 页预算先计入 thread/read 的完整正文与预览，再计入每个已返回正文页，部分读取也会耗尽后续预算。
        #[test]
        fn older_history_budget_counts_retained_bodies_and_partial_reads_across_items() {
            let mut items = vec![
                json!({
                    "itemId":"item_full",
                    "kind":"user_input",
                    "truncated":false,
                    "content":[
                        {"type":"text","text":"full"},
                        {"type":"workspace_reference","relativePath":"src/lib.rs"},
                        {"type":"text","text":"body"}
                    ]
                }),
                json!({
                    "itemId":"item_partial",
                    "kind":"final_answer",
                    "totalCharacters":2,
                    "truncated":true,
                    "text":"p"
                }),
                json!({
                    "itemId":"item_next",
                    "kind":"final_answer",
                    "totalCharacters":1,
                    "truncated":true,
                    "text":"q"
                }),
            ];
            let cancelled = AtomicBool::new(false);
            let mut page_reads = std::collections::HashMap::<String, usize>::new();

            let used_bytes = interactive::hydrate_older_history_items(
                &mut items,
                14,
                Instant::now() + Duration::from_secs(1),
                &cancelled,
                |item_id, total_characters, maximum_bytes, consumed_bytes| {
                    interactive::read_bounded_item_content_pages(
                        "thr_budget",
                        item_id,
                        total_characters,
                        interactive::Utf8ReadBudget {
                            maximum_bytes,
                            consumed_bytes,
                        },
                        Instant::now() + Duration::from_secs(1),
                        &cancelled,
                        |params, _| {
                            assert_eq!(params["itemId"], item_id);
                            *page_reads.entry(item_id.to_owned()).or_default() += 1;
                            let offset = params["offsetCharacters"].as_u64().unwrap();
                            let limit = params["limitCharacters"].as_u64().unwrap();
                            let content = "x".repeat(limit as usize);
                            let next = offset + limit;
                            let truncated = next < total_characters;
                            Ok(json!({
                                "itemId":item_id,
                                "offsetCharacters":offset,
                                "totalCharacters":total_characters,
                                "content":content,
                                "truncated":truncated,
                                "nextOffsetCharacters":if truncated { json!(next) } else { Value::Null }
                            }))
                        },
                    )
                },
            )
            .unwrap();

            assert_eq!(used_bytes, "fullbodypq".len() + 1);
            assert_eq!(page_reads.get("item_partial"), Some(&1));
            assert!(!page_reads.contains_key("item_next"));
            assert_eq!(items[1]["truncated"], true);
            assert_eq!(items[1]["text"], "p");
            assert_eq!(items[2]["truncated"], true);
            assert_eq!(items[2]["text"], "q");
        }

        /// @author kongweiguang
        /// 模拟详情关闭后的取消：写入门禁已经跨过时，成功、失败和未知结果仍须交付会话反馈。
        #[test]
        fn admitted_detail_copy_keeps_late_terminal_feedback_after_cancel() {
            use super::clipboard::{ClipboardBackend, ClipboardWorker, CopyState};
            use std::sync::{Arc, Condvar, Mutex};

            struct DelayedClipboard {
                release: Arc<(Mutex<bool>, Condvar)>,
                outcome: CopyState,
            }

            impl ClipboardBackend for DelayedClipboard {
                /// @author kongweiguang
                /// 后端无需真实桌面资源，测试只制造已开始写入和终态回执之间的关闭窗口。
                fn prepare(&mut self) -> Result<(), String> {
                    Ok(())
                }

                /// @author kongweiguang
                /// 门闩固定写入时序，避免调度速度让测试误走可取消的准备阶段。
                fn set_text(&mut self, _: &str) -> Result<(), String> {
                    let (lock, wake) = &*self.release;
                    let mut released = lock.lock().unwrap();
                    while !*released {
                        released = wake.wait(released).unwrap();
                    }
                    drop(released);
                    match &self.outcome {
                        CopyState::Copied => Ok(()),
                        CopyState::Failed(error) => Err(error.clone()),
                        CopyState::OutcomeUnknown => panic!("injected native write panic"),
                        _ => unreachable!(),
                    }
                }
            }

            for (outcome, expected_notice) in [
                (CopyState::Copied, "完整消息详情已复制"),
                (CopyState::Failed("held".into()), "完整消息详情复制失败"),
                (CopyState::OutcomeUnknown, "请先核查系统剪贴板"),
            ] {
                let release = Arc::new((Mutex::new(false), Condvar::new()));
                let backend_release = Arc::clone(&release);
                let mut worker = ClipboardWorker::with_backend_factory(move || {
                    Box::new(DelayedClipboard {
                        release: Arc::clone(&backend_release),
                        outcome: outcome.clone(),
                    })
                });
                let request = worker
                    .submit("thr_detail".into(), None, "完整中文".into())
                    .unwrap();
                let deadline = Instant::now() + Duration::from_secs(2);
                loop {
                    if let Some(event) = worker.try_recv() {
                        assert_eq!(event.state, CopyState::Delivering);
                        break;
                    }
                    assert!(Instant::now() < deadline, "delivery never began");
                    std::thread::sleep(Duration::from_millis(1));
                }
                // close_details 的取消请求无法撤回已跨入 OS 的操作；后续反馈不依赖面板仍然打开。
                assert!(!worker.cancel(request));
                *release.0.lock().unwrap() = true;
                release.1.notify_one();
                loop {
                    if let Some(event) = worker.try_recv() {
                        assert_eq!(event.request_id, request);
                        assert_eq!(event.thread_id, "thr_detail");
                        let feedback = interactive::clipboard_feedback(
                            interactive::ClipboardPurpose::MessageDetail {
                                entry_id: "item_closed".into(),
                            },
                            event.selection_id,
                            event.state,
                        );
                        assert!(matches!(feedback, Some(UiEvent::SetNotice(Some(notice)))
                            if notice.contains(expected_notice)));
                        break;
                    }
                    assert!(Instant::now() < deadline, "terminal feedback never arrived");
                    std::thread::sleep(Duration::from_millis(1));
                }
            }
        }

        /// @author kongweiguang
        /// 只有成功读出的完整公开正文可以成为详情复制来源，截断预览必须拒绝。
        #[test]
        fn detail_copy_gate_requires_complete_public_item() {
            let full = json!({
                "itemId":"item_full",
                "kind":"final_answer",
                "truncated":false,
                "text":"完整中文正文"
            });
            let preview = json!({
                "itemId":"item_full",
                "kind":"final_answer",
                "truncated":true,
                "text":"完整"
            });

            assert_eq!(
                interactive::full_public_item_display(&full)
                    .unwrap()
                    .as_deref(),
                Some("完整中文正文")
            );
            assert_eq!(
                interactive::full_public_item_display(&preview).unwrap(),
                None
            );
        }
    }
}
