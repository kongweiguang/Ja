// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 独立 test target 编译完整私有 controller，避免为白盒回归扩大生产 façade。
#[allow(dead_code)]
mod controller {
    include!("../../src/controller/mod.rs");

    mod regression {
        use super::{CliError, ThreadContext, interactive, projection};
        use ja_cli::ui::{PendingPrompt, TimelineStatus, UiEvent, UiSnapshot, UiState, render};
        use ratatui::{Terminal, backend::TestBackend};
        use serde_json::{Value, json};
        use std::time::{Duration, Instant, UNIX_EPOCH};

        /// @author kongweiguang
        /// 直接消费真实 Tool presentation，并保留必需的空 taskActivities 合同字段。
        fn tool_preview_entry(presentation: Value) -> ja_cli::ui::TimelineEntry {
            project(json!({
                "revision": 7,
                "turns": [],
                "items": [{"itemId":"tool_preview", "turnId":"turn_projection", "kind":"tool_call", "presentation":presentation}],
                "taskActivities": [],
                "liveStream":null
            })).timeline.into_iter().next().unwrap()
        }

        /// @author kongweiguang
        /// 构造快照中的直接 Subagent 活动对，状态由任务摘要提供、文字只取安全摘要。
        fn task_activity_pair(
            task_thread_id: &str,
            task_name: &str,
            state: &str,
            sequence: u64,
            created_at: &str,
            summary: &str,
        ) -> Value {
            json!({
                "activity": {
                    "activityId": format!("activity_{task_thread_id}_{sequence}"),
                    "activitySequence": sequence,
                    "taskThreadId": task_thread_id,
                    "createdAt": created_at,
                    "summary": {"text": summary}
                },
                "task": {
                    "taskThreadId": task_thread_id,
                    "parentThreadId": "thr_projection",
                    "taskKind": "subagent",
                    "taskName": task_name,
                    "state": state,
                    "latestActivitySequence": sequence
                }
            })
        }

        /// @author kongweiguang
        /// 按各活动真实时间排序并以 taskThreadId 稳定破平局；同一任务只保留最新状态和摘要。
        #[test]
        fn subagent_activities_merge_latest_per_task_and_project_terminal_states() {
            let mut history = json!({
                "revision": 7,
                "turns": [],
                "items": [],
                "taskActivities": [
                    task_activity_pair("thr_child_a", "检索资料", "queued", 1, "2026-10-15T00:00:01Z", "旧活动"),
                    task_activity_pair("thr_child_b", "等待确认", "waiting_input", 1, "2026-10-15T00:00:02Z", "需要补充输入"),
                    task_activity_pair("thr_child_a", "检索资料", "failed", 2, "2026-10-15T00:00:04Z", "执行失败：权限不足"),
                    task_activity_pair("thr_child_c", "已取消", "cancelled", 1, "2026-10-15T00:00:03Z", "用户取消"),
                    task_activity_pair("thr_child_0", "同一时刻", "completed", 1, "2026-10-15T00:00:02Z", "已完成"),
                    task_activity_pair("thr_child_d", "运行中", "running", 1, "2026-10-15T00:00:02.500Z", "正在工作"),
                    task_activity_pair("thr_child_s", "等待审批超时", "suspended", 1, "2026-10-15T00:00:04.500Z", "等待续跑")
                ],
                "liveStream": null
            });
            let mut unrelated = task_activity_pair(
                "thr_unrelated",
                "其它父任务",
                "running",
                1,
                "2026-10-15T00:00:05Z",
                "不可见活动",
            );
            unrelated["task"]["parentThreadId"] = json!("thr_other_parent");
            history["taskActivities"]
                .as_array_mut()
                .unwrap()
                .push(unrelated);

            let snapshot = project(history);
            let child_entries = snapshot
                .timeline
                .iter()
                .filter(|entry| entry.id.starts_with("subagent:"))
                .collect::<Vec<_>>();
            assert_eq!(child_entries.len(), 6);
            assert_eq!(
                child_entries
                    .iter()
                    .map(|entry| entry.id.as_str())
                    .collect::<Vec<_>>(),
                [
                    "subagent:thr_child_0",
                    "subagent:thr_child_b",
                    "subagent:thr_child_d",
                    "subagent:thr_child_c",
                    "subagent:thr_child_a",
                    "subagent:thr_child_s"
                ]
            );

            let failed = child_entries
                .iter()
                .find(|entry| entry.id == "subagent:thr_child_a")
                .unwrap();
            assert_eq!(failed.status, Some(TimelineStatus::Failed));
            assert!(failed.detail.as_deref().unwrap().contains("状态：失败"));
            assert!(
                failed
                    .detail
                    .as_deref()
                    .unwrap()
                    .contains("执行失败：权限不足")
            );
            assert!(!failed.detail.as_deref().unwrap().contains("旧活动"));

            let waiting = child_entries
                .iter()
                .find(|entry| entry.id == "subagent:thr_child_b")
                .unwrap();
            assert_eq!(waiting.status, Some(TimelineStatus::Running));
            assert!(
                waiting
                    .detail
                    .as_deref()
                    .unwrap()
                    .contains("状态：等待输入")
            );

            let running = child_entries
                .iter()
                .find(|entry| entry.id == "subagent:thr_child_d")
                .unwrap();
            assert_eq!(running.status, Some(TimelineStatus::Running));
            assert!(
                running
                    .detail
                    .as_deref()
                    .unwrap()
                    .contains("状态：正在工作")
            );

            let cancelled = child_entries
                .iter()
                .find(|entry| entry.id == "subagent:thr_child_c")
                .unwrap();
            assert_eq!(cancelled.status, Some(TimelineStatus::Complete));
            assert!(
                cancelled
                    .detail
                    .as_deref()
                    .unwrap()
                    .contains("状态：已取消")
            );

            let suspended = child_entries
                .iter()
                .find(|entry| entry.id == "subagent:thr_child_s")
                .unwrap();
            assert_eq!(suspended.status, None);
            assert!(
                suspended
                    .detail
                    .as_deref()
                    .unwrap()
                    .contains("状态：已暂停 · 待恢复")
            );
        }

        /// 没有结果的终态不能拿输入参数冒充结果；运行中仅以明确的输入标签展示参数。
        #[test]
        fn tool_preview_input_only_is_not_a_terminal_result() {
            for status in ["success", "error", "cancelled", "pending", "running"] {
                let entry = tool_preview_entry(
                    json!({"kind":"mcp", "status":status, "title":"查询", "inputPreview":"INPUT_ONLY"}),
                );
                if matches!(status, "pending" | "running") {
                    assert_eq!(entry.detail.as_deref(), Some("输入：INPUT_ONLY"));
                } else {
                    assert_eq!(entry.detail, None, "{status}");
                }
            }
        }

        /// Shell 优先展示真实标准流，失败保留 stderr；其它工具仍取服务端的安全结果预览。
        #[test]
        fn tool_preview_uses_shell_streams_and_safe_result_fields() {
            let mut presentation = json!({"kind":"shell", "status":"success", "command":"probe", "stdout":"STDOUT_FIRST\nSTDOUT_SECOND", "outputPreview":"WRAPPER_METADATA", "summary":"SUMMARY", "inputPreview":"INPUT"});
            assert_eq!(
                tool_preview_entry(presentation.clone()).detail.as_deref(),
                Some("STDOUT_FIRST\nSTDOUT_SECOND")
            );
            presentation["status"] = json!("error");
            presentation["stderr"] = json!("STDERR_REASON");
            assert_eq!(
                tool_preview_entry(presentation.clone()).detail.as_deref(),
                Some("STDERR_REASON")
            );
            presentation["kind"] = json!("mcp");
            presentation["status"] = json!("success");
            assert_eq!(
                tool_preview_entry(presentation.clone()).detail.as_deref(),
                Some("WRAPPER_METADATA")
            );
            presentation["outputPreview"] = json!(" \n");
            presentation["stdout"] = json!("");
            assert_eq!(
                tool_preview_entry(presentation).detail.as_deref(),
                Some("SUMMARY")
            );
        }

        /// 标题中的多行命令保留可读分隔，源端截断事实必须完整交给视图而不依赖本地行数。
        #[test]
        fn tool_preview_preserves_source_truncation_and_multiline_target() {
            let entry = tool_preview_entry(
                json!({"kind":"shell", "status":"success", "command":"first\nsecond", "stdout":"SAFE_RESULT", "truncated":true}),
            );
            let ja_cli::ui::TimelineKind::Tool {
                target,
                preview_truncated,
                ..
            } = entry.kind
            else {
                panic!("tool presentation must project a tool entry")
            };
            assert_eq!(target, "first ↵ second");
            assert!(preview_truncated);
            assert_eq!(entry.detail.as_deref(), Some("SAFE_RESULT"));
        }

        /// Message truncation decorates only the preview field, leaving copied/source text untouched.
        /// @author kongweiguang
        #[test]
        fn message_preview_truncation_marker_does_not_change_message_text() {
            let snapshot = project(json!({
                "revision": 1,
                "turns": [],
                "items": [
                    {"itemId":"user-truncated", "kind":"user_input", "content":[{"type":"text", "text":"USER_PREVIEW"}], "truncated":true},
                    {"itemId":"answer-truncated", "kind":"final_answer", "text":"ANSWER_PREVIEW", "truncated":true},
                    {"itemId":"answer-complete", "kind":"final_answer", "text":"FULL_ANSWER", "truncated":false}
                ],
                "taskActivities": [],
                "liveStream":null
            }));

            assert_eq!(snapshot.timeline[0].text, "USER_PREVIEW");
            assert_eq!(
                snapshot.timeline[0].detail.as_deref(),
                Some(ja_cli::ui::MESSAGE_PREVIEW_TRUNCATED_NOTICE)
            );
            assert_eq!(snapshot.timeline[1].text, "ANSWER_PREVIEW");
            assert_eq!(
                snapshot.timeline[1].detail.as_deref(),
                Some(ja_cli::ui::MESSAGE_PREVIEW_TRUNCATED_NOTICE)
            );
            assert_eq!(snapshot.timeline[2].text, "FULL_ANSWER");
            assert_eq!(snapshot.timeline[2].detail, None);
        }

        /// 自动归档跨越详情容量和多页 Unicode 偏移；不能把 UTF-8 字节当作字符数。
        #[test]
        fn automatic_transcript_reads_all_unicode_pages_beyond_detail_capacity() {
            let body = "中文🙂e\u{301}\n".repeat(18_000);
            let characters: Vec<char> = body.chars().collect();
            let mut offsets = Vec::new();
            let complete = interactive::read_item_content_pages(
                "thr_pages",
                "item_pages",
                characters.len() as u64,
                None,
                |params, timeout| {
                    assert_eq!(params["threadId"], "thr_pages");
                    assert_eq!(params["itemId"], "item_pages");
                    assert!(timeout > Duration::ZERO && timeout <= Duration::from_secs(15));
                    let offset = params["offsetCharacters"].as_u64().unwrap() as usize;
                    let limit = params["limitCharacters"].as_u64().unwrap() as usize;
                    offsets.push(offset);
                    Ok(content_page(&characters, offset, limit))
                },
            )
            .unwrap();
            assert!(complete.len() > 96 * 1024);
            assert_eq!(complete, body);
            assert_eq!(offsets, vec![0, 32_768, 65_536, 98_304]);
        }

        /// 第二页失败只能返回错误；重新只读从原条目起点恢复，不能暴露半截缓存或提交操作。
        #[test]
        fn failed_page_keeps_original_display_and_read_retry_starts_from_zero() {
            let body = "长回复🙂\n".repeat(9_000);
            let characters: Vec<char> = body.chars().collect();
            let mut calls = 0;
            let failed = interactive::read_item_content_pages(
                "thr_pages",
                "item_pages",
                characters.len() as u64,
                None,
                |params, _| {
                    calls += 1;
                    if calls == 2 {
                        return Err(CliError::rpc(
                            -32012,
                            "storage temporarily unavailable".into(),
                        ));
                    }
                    Ok(content_page(
                        &characters,
                        params["offsetCharacters"].as_u64().unwrap() as usize,
                        32_768,
                    ))
                },
            );
            assert!(failed.is_err());
            assert_eq!(calls, 2);
            let mut pending = false;
            let mut next = Instant::now();
            let before = Instant::now();
            let message =
                interactive::recover_background_read(failed.map(|_| ()), &mut pending, &mut next)
                    .unwrap()
                    .unwrap();
            assert!(pending && next > before && message.contains("F5"));
            let mut offsets = Vec::new();
            let restored = interactive::read_item_content_pages(
                "thr_pages",
                "item_pages",
                characters.len() as u64,
                None,
                |params, _| {
                    let offset = params["offsetCharacters"].as_u64().unwrap() as usize;
                    offsets.push(offset);
                    Ok(content_page(&characters, offset, 32_768))
                },
            )
            .unwrap();
            assert_eq!(offsets, vec![0, 32_768]);
            assert_eq!(restored, body);
        }

        /// 身份、页游标与终态不一致必须停止，不能用重新尝试掩盖协议损坏。
        #[test]
        fn invalid_content_page_is_fatal_to_background_read_recovery() {
            let characters: Vec<char> = "正文🙂".chars().collect();
            for (field, value) in [
                ("itemId", json!("other")),
                ("offsetCharacters", json!(1)),
                ("totalCharacters", json!(999)),
                ("truncated", json!(true)),
                ("nextOffsetCharacters", json!(3)),
            ] {
                let result = interactive::read_item_content_pages(
                    "thr_pages",
                    "item_pages",
                    3,
                    None,
                    |_, _| {
                        let mut page = content_page(&characters, 0, 32_768);
                        page[field] = value.clone();
                        Ok(page)
                    },
                );
                let mut pending = false;
                let mut next = Instant::now();
                let original_next = next;
                assert!(
                    interactive::recover_background_read(
                        result.map(|_| ()),
                        &mut pending,
                        &mut next
                    )
                    .is_err(),
                    "{field}"
                );
                assert!(!pending);
                assert_eq!(next, original_next);
            }
        }

        /// 详情仍按 UTF-8 字节预算拒绝，自动归档不应为此恢复旧容量限制。
        #[test]
        fn explicit_details_retain_byte_budget() {
            let characters: Vec<char> = "中".repeat(40_000).chars().collect();
            let error = interactive::read_item_content_pages(
                "thr_pages",
                "item_pages",
                40_000,
                Some(96 * 1024),
                |params, _| {
                    Ok(content_page(
                        &characters,
                        params["offsetCharacters"].as_u64().unwrap() as usize,
                        32_768,
                    ))
                },
            )
            .unwrap_err();
            assert!(error.message().contains("详情容量"));
        }

        /// 已过期的上层操作 deadline 必须在正文 RPC 前失败，不能重新获得详情读取预算。
        #[test]
        fn content_pages_respect_the_callers_expired_deadline() {
            let mut calls = 0;
            let error = interactive::read_item_content_pages_until(
                "thr_pages",
                "item_pages",
                1,
                None,
                Instant::now() - Duration::from_millis(1),
                |_, _| {
                    calls += 1;
                    Ok(json!({}))
                },
            )
            .unwrap_err();

            assert_eq!(calls, 0);
            assert!(error.message().contains("当前操作时限"));
        }

        /// 无回执副作用、永久权限和身份错误不能被误判为后台读取的自动重试。
        #[test]
        fn background_recovery_preserves_fatal_and_uncertain_errors() {
            for error in [
                CliError::uncertain_operation("op_unknown"),
                CliError::protocol("identity mismatch"),
                CliError::rpc(-32026, "workspace confinement".into()),
                CliError::rpc(-32029, "thread missing".into()),
            ] {
                let mut pending = false;
                let mut next = Instant::now();
                assert!(
                    interactive::recover_background_read(Err(error), &mut pending, &mut next)
                        .is_err()
                );
                assert!(!pending);
            }
            let mut pending = false;
            let mut next = Instant::now();
            assert!(
                interactive::recover_background_read(
                    Err(CliError::transport("read timed out")),
                    &mut pending,
                    &mut next
                )
                .unwrap()
                .is_some()
            );
            assert!(pending);
        }

        /// Java 未提供原因时只展示已确认失败，不能给用户一个不存在的 UNKNOWN 错误码。
        #[test]
        fn failed_turn_does_not_invent_an_error_code() {
            let snapshot = project(history("failed", "", false));
            let error = snapshot
                .timeline
                .iter()
                .find(|entry| entry.id.starts_with("turn-error:"))
                .unwrap();
            assert_eq!(error.text, "任务未完成");
            let mut with_reason = history("failed", "", false);
            with_reason["turns"][0]["errorCode"] = json!("MODEL_PROTOCOL_ERROR");
            let snapshot = project(with_reason);
            let error = snapshot
                .timeline
                .iter()
                .find(|entry| entry.id.starts_with("turn-error:"))
                .unwrap();
            assert!(error.text.contains("模型返回内容无法处理"));
        }

        /// 活动 Turn 用服务端受理时间驱动时长，终态则展示唯一且稳定的工作汇总。
        #[test]
        fn turn_lifecycle_projects_start_and_terminal_work_summary() {
            let mut running = history("running", "", false);
            running["turns"][0]["requestedAt"] = json!("2024-02-29T23:59:59.25Z");
            let snapshot = project(running);
            assert_eq!(
                snapshot.turn_started_at,
                Some(UNIX_EPOCH + Duration::new(1_709_251_199, 250_000_000))
            );
            assert!(work_summaries(&snapshot).is_empty());

            for status in ["completed", "failed", "cancelled"] {
                let snapshot = project(timed_history(
                    status,
                    "2024-02-28T23:59:59.500Z",
                    Some("2024-03-01T00:00:00.500000000Z"),
                ));
                let summaries = work_summaries(&snapshot);
                assert_eq!(summaries.len(), 1, "{status}");
                assert_eq!(summaries[0].id, "turn-summary:turn_projection");
                assert_eq!(
                    summaries[0].kind,
                    ja_cli::ui::TimelineKind::WorkSummary {
                        elapsed_seconds: 86_401
                    },
                    "{status}"
                );
                assert_eq!(
                    snapshot
                        .turn_id_by_entry_id
                        .get(&summaries[0].id)
                        .map(String::as_str),
                    Some("turn_projection")
                );
                assert_eq!(snapshot.turn_started_at, None);
            }
        }

        /// Turn 状态只确认挂起，不推断来自 App Server 恢复还是 Plan Run 暂停。
        #[test]
        fn suspended_turn_projects_paused_state_without_activity_timing() {
            let snapshot = snapshot("suspended", "", false);
            assert_eq!(snapshot.turn_state, ja_cli::ui::TurnState::Paused);
            assert_eq!(snapshot.turn_started_at, None);
        }

        /// 缺少 Plan 与 Turn 的绑定身份时，展示通用挂起操作而不展示 Plan 专属控制。
        #[test]
        fn suspended_turn_uses_generic_recovery_hints() {
            let snapshot = snapshot("suspended", "", false);
            let mut state = UiState::new_for_terminal(snapshot, 120, 32);
            let mut terminal = terminal();
            flush(&mut state, &mut terminal);
            let visible = output(&terminal).replace(' ', "");
            assert!(visible.contains("运行已暂停或中断"), "{visible}");
            assert!(visible.contains("F6恢复任务·F7取消任务"), "{visible}");
            assert!(!visible.contains("/planresume"), "{visible}");
            assert!(!visible.contains("/planstop"), "{visible}");
            assert!(!visible.contains("正在工作"), "{visible}");
            assert!(!visible.contains("Esc中断"), "{visible}");
        }

        /// 多个挂起 Turn 选择最早请求；时间无效时拒绝猜测目标。
        #[test]
        fn suspended_turn_selection_is_oldest_and_rejects_invalid_time() {
            let history = json!({
                "turns":[
                    {"turnId":"turn_new","status":"suspended","requestedAt":"2026-10-02T00:00:02Z"},
                    {"turnId":"turn_old","status":"suspended","requestedAt":"2026-10-02T00:00:01Z"},
                    {"turnId":"turn_done","status":"completed","requestedAt":"2026-10-01T00:00:00Z"}
                ]
            });
            assert_eq!(
                projection::earliest_suspended_turn_id(&history).unwrap(),
                Some("turn_old".into())
            );

            let invalid = json!({"turns":[{
                "turnId":"turn_bad",
                "status":"suspended",
                "requestedAt":"2026-02-30T00:00:00Z"
            }]});
            assert!(projection::earliest_suspended_turn_id(&invalid).is_err());
        }

        /// 汇总插在所属 Turn 尾项之后，多轮按原时间线顺序排列且重投影不重复。
        #[test]
        fn turn_summaries_follow_each_turn_and_keep_stable_identity() {
            let mut history = history("completed", "", false);
            history["turns"] = json!([
                {"turnId":"turn_first","status":"completed","requestedAt":"2025-01-01T00:00:00Z","completedAt":"2025-01-01T00:00:05Z"},
                {"turnId":"turn_second","status":"completed","requestedAt":"2025-01-01T00:00:10Z","completedAt":"2025-01-01T00:00:18Z"}
            ]);
            history["items"] = json!([
                {"itemId":"final_first","turnId":"turn_first","kind":"final_answer","text":"第一轮"},
                {"itemId":"final_second","turnId":"turn_second","kind":"final_answer","text":"第二轮"}
            ]);
            history["liveStream"] = Value::Null;

            let snapshot = project(history.clone());
            assert_eq!(
                snapshot
                    .timeline
                    .iter()
                    .map(|entry| entry.id.as_str())
                    .collect::<Vec<_>>(),
                [
                    "final_first",
                    "turn-summary:turn_first",
                    "final_second",
                    "turn-summary:turn_second"
                ]
            );
            assert_eq!(work_summaries(&snapshot).len(), 2);
            assert_eq!(
                snapshot
                    .turn_id_by_entry_id
                    .get("turn-summary:turn_first")
                    .map(String::as_str),
                Some("turn_first")
            );
            assert_eq!(
                snapshot
                    .turn_id_by_entry_id
                    .get("turn-summary:turn_second")
                    .map(String::as_str),
                Some("turn_second")
            );
            assert_eq!(project(history).timeline, snapshot.timeline);
        }

        /// 非公历日期、超精度、非 UTC 或逆序时间一律保持未知，不伪造开始时刻或工作时长。
        #[test]
        fn malformed_or_reversed_turn_timestamps_are_not_projected() {
            for requested_at in [
                "2023-02-29T00:00:00Z",
                "2024-02-30T00:00:00Z",
                "2024-13-01T00:00:00Z",
                "2024-01-01T24:00:00Z",
                "2024-01-01T00:00:60Z",
                "2024-01-01T00:00:00.1234567890Z",
                "2024-01-01T00:00:00+00:00",
            ] {
                let snapshot = project(timed_history(
                    "completed",
                    requested_at,
                    Some("2024-03-01T00:00:00Z"),
                ));
                assert!(work_summaries(&snapshot).is_empty(), "{requested_at}");
            }

            let missing_end = project(timed_history("cancelled", "2024-02-29T23:59:59Z", None));
            assert!(work_summaries(&missing_end).is_empty());
            let reversed = project(timed_history(
                "failed",
                "2024-03-01T00:00:01Z",
                Some("2024-03-01T00:00:00Z"),
            ));
            assert!(work_summaries(&reversed).is_empty());

            let mut active = history("running", "", false);
            active["turns"][0]["requestedAt"] = json!("2024-02-30T00:00:00Z");
            assert_eq!(project(active).turn_started_at, None);
        }

        /// @author kongweiguang
        /// 普通 metadata 重读把权威改名送入下一次投影；失败或不完整结果不污染已确认标题。
        #[test]
        fn metadata_refresh_updates_title_atomically_and_keeps_it_on_read_failure() {
            let mut thread = Some(thread_context());
            let mut preferences_pending = false;
            interactive::reload_thread_metadata(&mut thread, &mut preferences_pending, |thread| {
                assert_eq!(thread.workspace_id, "ws_projection");
                assert_eq!(thread.thread_id, "thr_projection");
                Ok(json!({"title":"桌面端改名","revision":8,"preferences":null}))
            })
            .unwrap();
            let thread = thread.unwrap();
            assert_eq!(thread.title, "桌面端改名");
            assert_eq!(thread.revision, 8);
            let snapshot = projection::project(
                &thread,
                &history("completed", "正文", true),
                &json!({"request":null}),
                &json!({}),
                Some(&thread.title),
            )
            .unwrap();
            assert_eq!(snapshot.thread_title.as_deref(), Some("桌面端改名"));

            let mut thread = Some(thread);
            let mut preferences_pending = true;
            let old_revision = thread.as_ref().unwrap().revision;
            let error =
                interactive::reload_thread_metadata(&mut thread, &mut preferences_pending, |_| {
                    Err(CliError::transport("thread metadata unavailable"))
                })
                .unwrap_err();
            assert!(error.read_retryable);
            assert!(preferences_pending);
            assert_eq!(thread.as_ref().unwrap().title, "桌面端改名");
            assert_eq!(thread.as_ref().unwrap().revision, old_revision);

            let invalid =
                interactive::reload_thread_metadata(&mut thread, &mut preferences_pending, |_| {
                    Ok(json!({"title":"不应提交","revision":9,"preferences":null}))
                });
            assert!(invalid.is_err());
            assert_eq!(thread.as_ref().unwrap().title, "桌面端改名");
            assert_eq!(thread.as_ref().unwrap().revision, old_revision);
        }

        /// 握手与普通读取采用相同协议边界，不能因连接文案把版本不兼容变成无限读取重试。
        #[test]
        fn handshake_protocol_errors_do_not_enter_background_retry() {
            use ja_runtime::app_server_process::AppServerProcessError;
            for error in [
                AppServerProcessError::ProtocolFault,
                AppServerProcessError::Incompatible,
                AppServerProcessError::InvalidErrorCatalog,
            ] {
                let mapped = super::rpc::connect_error(error);
                assert!(!mapped.read_retryable);
                assert!(mapped.message().contains("配套版本"));
            }
            assert!(super::rpc::connect_error(AppServerProcessError::NotReady).read_retryable);
        }

        /// 宿主身份不可读也会关闭握手，诊断不能误导用户按版本冲突停止仍在工作的后台。
        #[test]
        fn closed_handshake_does_not_guess_version_mismatch_or_request_restart() {
            use ja_runtime::app_server_process::AppServerProcessError;
            for error in [
                AppServerProcessError::SessionClosed,
                AppServerProcessError::HandshakeFailed,
            ] {
                let mapped = super::rpc::connect_error(error);
                assert!(mapped.message().contains("握手未完成"));
                assert!(mapped.message().contains("终端权限"));
                assert!(!mapped.message().contains("版本不匹配"));
                assert!(!mapped.message().contains("重启后台"));
                assert!(mapped.read_retryable);
            }
        }

        /// fixture 按公开 Unicode code point 合同分页，故障案例只修改单个合同字段。
        fn content_page(characters: &[char], offset: usize, limit: usize) -> Value {
            let end = (offset + limit).min(characters.len());
            json!({"itemId":"item_pages","offsetCharacters":offset,"totalCharacters":characters.len(),
                "content":characters[offset..end].iter().collect::<String>(),"truncated":end < characters.len(),
                "nextOffsetCharacters":if end < characters.len() {Some(end)} else {None}})
        }

        /// @author kongweiguang
        /// 同一正文身份复用已 hydration 文本；Turn 或长度变化必须通过读取闭包重新确认。
        #[test]
        fn completed_answer_cache_survives_preview_refresh_only_for_same_identity() {
            let body = "JA_CACHED 完整中文回答的尾部。";
            let mut cached = history("completed", body, true);
            cached["items"][0]["truncated"] = json!(false);
            cached["items"][0]["totalCharacters"] = json!(body.chars().count());
            let mut preview = cached.clone();
            preview["items"][0]["truncated"] = json!(true);
            preview["items"][0]["text"] = json!("JA_CACHED");
            let original = preview.clone();
            interactive::hydrate_history_public_items(&mut preview, &cached, |_, _| {
                panic!("matching item identity must reuse the hydrated body without I/O")
            })
            .unwrap();
            assert_eq!(preview["items"][0]["text"], json!(body));
            assert_eq!(preview["items"][0]["truncated"], json!(false));
            for (field, value) in [
                ("turnId", json!("another_turn")),
                ("totalCharacters", json!(999)),
            ] {
                let mut changed = original.clone();
                changed["items"][0][field] = value;
                let mut reads = 0;
                interactive::hydrate_history_public_items(&mut changed, &cached, |item_id, _| {
                    reads += 1;
                    assert_eq!(item_id, "final_projection");
                    Ok("RELOADED_AFTER_IDENTITY_CHANGE".to_owned())
                })
                .unwrap();
                assert_eq!(reads, 1, "{field} changed, so the cache cannot be reused");
                assert_eq!(
                    changed["items"][0]["text"],
                    json!("RELOADED_AFTER_IDENTITY_CHANGE")
                );
                assert_eq!(changed["items"][0]["truncated"], json!(false));
            }
        }

        /// 请求读回可先于事件泵到达；终态后的旧通知必须丢弃，运行、重试和未知 Turn 可继续。
        #[test]
        fn queued_delta_is_rejected_only_after_authoritative_terminal_snapshot() {
            for status in ["completed", "failed", "cancelled"] {
                assert!(projection::turn_is_terminal(
                    &history(status, "正文", true),
                    "turn_projection"
                ));
            }
            for status in [
                "queued",
                "running",
                "waiting_input",
                "waiting_approval",
                "suspended",
            ] {
                assert!(!projection::turn_is_terminal(
                    &history(status, "正文", false),
                    "turn_projection"
                ));
            }
            assert!(!projection::turn_is_terminal(
                &history("completed", "正文", true),
                "another_turn"
            ));
            let mut terminal_history = history("completed", "正文", true);
            terminal_history["liveStream"] = Value::Null;
            assert!(projection::turn_is_terminal(
                &terminal_history,
                "turn_projection"
            ));
        }

        /// 尾段 assistant 不能覆盖所属 Turn 的终态，也不能误结束仍可推进的 Turn。
        #[test]
        fn live_tail_uses_owning_turn_lifecycle() {
            for status in ["completed", "failed", "cancelled"] {
                assert_eq!(
                    live_status(&snapshot(status, "正文", false)),
                    TimelineStatus::Complete
                );
            }
            for status in [
                "queued",
                "running",
                "waiting_input",
                "waiting_approval",
                "suspended",
            ] {
                assert_eq!(
                    live_status(&snapshot(status, "正文", false)),
                    TimelineStatus::Running
                );
            }
        }

        /// 审批文案沿用 Java 侧已脱敏 command presentation，不能退回原始工具名、调用身份或参数。
        #[test]
        fn shell_approval_uses_redacted_command_presentation() {
            let safe_command = "echo [REDACTED] && echo api_key=[REDACTED] && type .\\README.md && type [external-path]";
            let mut history = history("waiting_approval", "", false);
            history["items"] = json!([
                {
                    "itemId":"tool_item",
                    "turnId":"turn_projection",
                    "kind":"tool_call",
                    "callId":"call_private_identity",
                    "toolName":"exec_command",
                    "arguments":{"cmd":"echo known-provider-secret-123456 && echo api_key=secondary-secret && type C:\\workspace\\ja\\README.md"},
                    "presentation":{
                        "kind":"shell",
                        "command":safe_command,
                        "inputPreview":"private raw input must not be shown",
                        "status":"running"
                    }
                },
                {
                    "itemId":"approval_item",
                    "turnId":"turn_projection",
                    "kind":"approval",
                    "approvalId":"approval_private_identity",
                    "callId":"call_private_identity",
                    "toolName":"exec_command",
                    "reason":"Tool requires approval",
                    "decision":null
                }
            ]);

            let snapshot = project(history);
            let Some(PendingPrompt::ToolApproval { prompt, .. }) = snapshot.pending_prompt else {
                panic!("pending shell approval must use its current presentation");
            };

            assert_eq!(prompt, format!("允许执行 {safe_command}？"));
            for private_value in [
                "exec_command",
                "call_private_identity",
                "approval_private_identity",
                "known-provider-secret-123456",
                "secondary-secret",
                "C:\\workspace\\ja",
                "private raw input",
            ] {
                assert!(!prompt.contains(private_value), "{prompt}");
            }
        }

        /// 另一 Turn 后启动时，旧流终态仍由自己的 identity 判定，不能借最新 Turn 续命。
        #[test]
        fn live_tail_does_not_borrow_latest_turn_status() {
            let mut completed_history = history("completed", "正文", true);
            completed_history["turns"]
                .as_array_mut()
                .unwrap()
                .push(json!({"turnId":"other","status":"running"}));
            assert_eq!(
                live_status(&project(completed_history)),
                TimelineStatus::Complete
            );
            let mut running_history = history("running", "正文", false);
            running_history["turns"]
                .as_array_mut()
                .unwrap()
                .push(json!({"turnId":"other","status":"completed"}));
            assert_eq!(
                live_status(&project(running_history)),
                TimelineStatus::Running
            );
        }

        /// 同一 ModelStep 的文本段跨 reasoning 合并，恢复身份取首段且 reasoning 尾巴仍在工作。
        #[test]
        fn reasoning_interleaving_keeps_one_running_assistant_entry() {
            let mut history = history("running", "", false);
            history["liveStream"]["streamSeq"] = json!(3);
            history["liveStream"]["segments"] = json!([
                {"kind":"assistant","segmentStartSeq":1,"streamSeq":1,"text":"JA_STREAM_A"},
                {"kind":"reasoningSummary","segmentStartSeq":2,"streamSeq":2,"text":"internal reasoning"},
                {"kind":"assistant","segmentStartSeq":3,"streamSeq":3,"text":"JA_STREAM_B"}
            ]);
            let snapshot = project(history.clone());
            let live = snapshot
                .timeline
                .iter()
                .filter(|entry| entry.id.starts_with("live:"))
                .collect::<Vec<_>>();
            assert_eq!(live.len(), 1);
            assert_eq!(live[0].id, "live:turn_projection:1");
            assert_eq!(live[0].text, "JA_STREAM_AJA_STREAM_B");
            assert_eq!(live[0].status, Some(TimelineStatus::Running));
            assert_eq!(
                interactive::resumed_live_assistant_entry_id(&history["liveStream"]),
                Some("live:turn_projection:1".into())
            );
            let reasoning = snapshot
                .timeline
                .iter()
                .filter(|entry| entry.id.starts_with("live-reasoning:"))
                .collect::<Vec<_>>();
            assert_eq!(reasoning.len(), 1);
            assert_eq!(reasoning[0].id, "live-reasoning:turn_projection:2");
            assert_eq!(
                reasoning[0].kind,
                ja_cli::ui::TimelineKind::ReasoningSummary
            );
            assert_eq!(reasoning[0].text, "internal reasoning");
            assert_eq!(
                interactive::resumed_live_reasoning_entry_id(&history["liveStream"]),
                Some("live-reasoning:turn_projection:2".into())
            );

            history["liveStream"]["streamSeq"] = json!(2);
            history["liveStream"]["segments"] = json!([
                {"kind":"assistant","segmentStartSeq":1,"streamSeq":1,"text":"JA_STREAM_A"},
                {"kind":"reasoningSummary","segmentStartSeq":2,"streamSeq":2,"text":"internal reasoning"}
            ]);
            let reasoning_tail = project(history.clone());
            assert_eq!(live_status(&reasoning_tail), TimelineStatus::Running);
            assert_eq!(
                interactive::resumed_live_assistant_entry_id(&history["liveStream"]),
                Some("live:turn_projection:1".into())
            );
            assert_eq!(
                interactive::resumed_live_reasoning_entry_id(&history["liveStream"]),
                Some("live-reasoning:turn_projection:2".into())
            );
        }

        /// 恢复快照保留正文和摘要各自的首段身份，并按首个 streamSeq 排列可见过程。
        #[test]
        fn live_reasoning_projection_keeps_event_order_and_resume_identity() {
            let mut history = history("running", "", false);
            history["liveStream"]["streamSeq"] = json!(4);
            history["liveStream"]["segments"] = json!([
                {"kind":"reasoningSummary","segmentStartSeq":1,"streamSeq":2,"text":"我准备说明"},
                {"kind":"assistant","segmentStartSeq":3,"streamSeq":4,"text":"Ja CLI 回复"}
            ]);

            let snapshot = project(history.clone());
            let live = snapshot
                .timeline
                .iter()
                .filter(|entry| {
                    entry.id.starts_with("live:") || entry.id.starts_with("live-reasoning:")
                })
                .collect::<Vec<_>>();
            assert_eq!(live.len(), 2);
            assert_eq!(live[0].id, "live-reasoning:turn_projection:1");
            assert_eq!(live[0].kind, ja_cli::ui::TimelineKind::ReasoningSummary);
            assert_eq!(live[0].text, "我准备说明");
            assert_eq!(live[1].id, "live:turn_projection:3");
            assert_eq!(live[1].kind, ja_cli::ui::TimelineKind::Assistant);
            assert_eq!(live[1].text, "Ja CLI 回复");
            assert_eq!(
                interactive::resumed_live_reasoning_entry_id(&history["liveStream"]),
                Some("live-reasoning:turn_projection:1".into())
            );
        }

        /// committed final 对账收掉唯一流式副本，但保留同一 Turn 的持久合法 Commentary。
        #[test]
        fn terminal_live_null_keeps_complete_answer_once_and_commentary_visible() {
            let mut active = history("running", "", false);
            active["liveStream"]["streamSeq"] = json!(3);
            active["liveStream"]["segments"] = json!([
                {"kind":"assistant","segmentStartSeq":1,"streamSeq":1,"text":"JA_FINAL_A"},
                {"kind":"reasoningSummary","segmentStartSeq":2,"streamSeq":2,"text":"JA_COMMENTARY_VISIBLE"},
                {"kind":"assistant","segmentStartSeq":3,"streamSeq":3,"text":"JA_FINAL_B"}
            ]);
            let mut state = UiState::new_for_terminal(project(active), 120, 32);
            let mut terminal = terminal();
            flush(&mut state, &mut terminal);

            let mut completed = history("completed", "JA_FINAL_AJA_FINAL_B", true);
            completed["liveStream"] = Value::Null;
            completed["items"] = json!([
                {"itemId":"reasoning_projection","turnId":"turn_projection","kind":"reasoning_summary","text":"JA_COMMENTARY_VISIBLE"},
                {"itemId":"final_projection","turnId":"turn_projection","kind":"final_answer","text":"JA_FINAL_AJA_FINAL_B"}
            ]);
            let completed = project(completed);
            assert!(completed.timeline.iter().any(|entry| {
                entry.kind == ja_cli::ui::TimelineKind::ReasoningSummary
                    && entry.text == "JA_COMMENTARY_VISIBLE"
            }));
            state.apply(UiEvent::ReplaceSnapshot(Box::new(completed)));
            flush(&mut state, &mut terminal);
            let visible = output(&terminal);
            assert_eq!(visible.matches("JA_FINAL_A").count(), 1, "{visible}");
            assert_eq!(visible.matches("JA_FINAL_B").count(), 1, "{visible}");
            assert_eq!(
                visible.matches("JA_COMMENTARY_VISIBLE").count(),
                1,
                "{visible}"
            );
        }

        /// 恢复冷启动和终态刷新都可能同时带持久 final 与 live；终端合并正文必须只有一份。
        #[test]
        fn completed_snapshot_without_stream_events_is_displayed_once() {
            let body = "JA_FINAL_ONLY 一次完整回答。";
            let mut cold = UiState::new_for_terminal(snapshot("completed", body, true), 120, 32);
            assert_once(&mut cold, body);
            let mut refreshed = UiState::new_for_terminal(snapshot("running", "", false), 120, 32);
            refreshed.apply(UiEvent::ReplaceSnapshot(Box::new(snapshot(
                "completed",
                body,
                true,
            ))));
            assert_once(&mut refreshed, body);
        }

        /// 终态快照可能补上尚未通知的末尾；必须保留其全文核对已输出前缀，不能直接删流。
        #[test]
        fn completed_snapshot_finishes_existing_prefix_without_repeating_it() {
            let prefix = "JA_PREFIX 已写入的中文前缀。\n";
            let body = format!("{prefix}JA_TAIL 最后通知尚未到达。");
            let mut state = UiState::new_for_terminal(snapshot("running", prefix, false), 120, 32);
            let mut terminal = terminal();
            flush(&mut state, &mut terminal);
            state.apply(UiEvent::ReplaceSnapshot(Box::new(snapshot(
                "completed",
                &body,
                true,
            ))));
            flush(&mut state, &mut terminal);
            let visible = output(&terminal);
            assert_eq!(visible.matches("JA_PREFIX").count(), 1, "{visible}");
            assert_eq!(visible.matches("JA_TAIL").count(), 1, "{visible}");
            state.apply(UiEvent::ReplaceSnapshot(Box::new(snapshot(
                "completed",
                &body,
                true,
            ))));
            let visible = output(&terminal);
            assert_eq!(visible.matches("JA_PREFIX").count(), 1, "{visible}");
            assert_eq!(visible.matches("JA_TAIL").count(), 1, "{visible}");
        }

        /// 失败和取消保留服务器已经确认的正文，不能因终态不同重复输出或丢失尾部。
        #[test]
        fn failed_and_cancelled_snapshots_preserve_one_answer() {
            for status in ["failed", "cancelled"] {
                let body = "JA_TERMINAL 已保留正文。";
                let mut state = UiState::new_for_terminal(snapshot(status, body, true), 120, 32);
                assert_once(&mut state, body);
            }
        }

        /// 不同 Turn 可生成相同正文，去重必须保留它们各自的业务身份。
        #[test]
        fn identical_text_in_another_turn_is_not_suppressed() {
            let body = "JA_DIFFERENT_TURN 相同正文。";
            let mut history = history("completed", body, true);
            history["items"][0]["turnId"] = json!("other");
            let mut state = UiState::new_for_terminal(project(history), 120, 32);
            let mut terminal = terminal();
            flush(&mut state, &mut terminal);
            assert_eq!(output(&terminal).matches("JA_DIFFERENT_TURN").count(), 2);
        }

        /// @author kongweiguang
        /// fixture 提供真实投影所需字段与必需 Task 活动集合，避免手写 UiSnapshot 绕开投影合同。
        fn history(status: &str, body: &str, durable: bool) -> Value {
            json!({
                "revision":7,
                "turns":[{"turnId":"turn_projection","status":status}],
                "items":if durable {vec![json!({"itemId":"final_projection","turnId":"turn_projection","kind":"final_answer","text":body})]} else {vec![]},
                "taskActivities":[],
                "liveStream":{"turnId":"turn_projection","segments":[{"kind":"assistant","segmentStartSeq":1,"streamSeq":1,"text":body}]}
            })
        }

        /// 时间生命周期 fixture 仅扩展 turns 字段，保留投影所需的真实项目级快照形状。
        fn timed_history(status: &str, requested_at: &str, completed_at: Option<&str>) -> Value {
            let mut history = history(status, "最终回复", true);
            history["turns"][0]["requestedAt"] = json!(requested_at);
            history["turns"][0]["completedAt"] =
                completed_at.map_or(Value::Null, |value| json!(value));
            history
        }

        /// 用 fixture 对应的完整上下文标识逐项筛出产品可见的工作汇总。
        fn work_summaries(snapshot: &UiSnapshot) -> Vec<&ja_cli::ui::TimelineEntry> {
            snapshot
                .timeline
                .iter()
                .filter(|entry| matches!(&entry.kind, ja_cli::ui::TimelineKind::WorkSummary { .. }))
                .collect()
        }

        /// metadata reload 回归复用与 project fixture 相同的 Thread 身份和权限边界。
        fn thread_context() -> ThreadContext {
            ThreadContext {
                thread_id: "thr_projection".into(),
                title: "投影回归".into(),
                workspace_id: "ws_projection".into(),
                workspace_root: "C:/projection".into(),
                thread_kind: crate::controller::DiscoveredThreadKind::Main,
                parent_thread_id: None,
                root_thread_id: "thr_projection".into(),
                model_id: "model".into(),
                provider_id: "provider".into(),
                reasoning_level: None,
                access_mode: "full_access".into(),
                revision: 7,
            }
        }

        /// 用完整生产 ThreadContext 与投影函数编译回归，不复制身份或协议解析实现。
        fn project(history: Value) -> UiSnapshot {
            projection::project(
                &thread_context(),
                &history,
                &json!({"request":null}),
                &json!({}),
                None,
            )
            .unwrap()
        }

        /// 按状态构造权威快照，所有被测 synthetic 标记都来自生产投影。
        fn snapshot(status: &str, body: &str, durable: bool) -> UiSnapshot {
            project(history(status, body, durable))
        }

        /// 只观察服务端流对应条目，持久 final 的状态不能掩盖 synthetic 投影错误。
        fn live_status(snapshot: &UiSnapshot) -> TimelineStatus {
            snapshot
                .timeline
                .iter()
                .find(|entry| entry.id.starts_with("live:"))
                .unwrap()
                .status
                .unwrap()
        }

        /// 断言当前应用正文 buffer，验证会话投影由 Transcript 承载。
        fn assert_once(state: &mut UiState, body: &str) {
            let mut terminal = terminal();
            flush(state, &mut terminal);
            let visible = output(&terminal);
            let marker = body.split_whitespace().next().unwrap();
            assert_eq!(visible.matches(marker).count(), 1, "{visible}");
            assert!(
                visible.replace(' ', "").contains(&body.replace(' ', "")),
                "{visible}"
            );
        }

        /// 使用全屏 buffer 验证应用拥有的正文视口。
        fn terminal() -> Terminal<TestBackend> {
            Terminal::new(TestBackend::new(120, 32)).unwrap()
        }

        /// 全屏 Transcript 每帧按当前状态绘制，稳定与流式条目共享去重账本。
        fn flush(state: &mut UiState, terminal: &mut Terminal<TestBackend>) {
            terminal.draw(|frame| render(frame, state)).unwrap();
        }

        /// 只回读本帧真实可见正文，确保投影核对落在用户拥有的界面 buffer。
        fn output(terminal: &Terminal<TestBackend>) -> String {
            terminal
                .backend()
                .buffer()
                .content()
                .iter()
                .map(|cell| cell.symbol())
                .collect()
        }
    }
}
