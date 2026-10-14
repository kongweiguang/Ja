// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang
#![allow(dead_code)]

// 将生产 UI 模块作为本测试 crate 的顶层兄弟模块加载；这样白盒断言可见 pub(super)
// Transcript 状态，同时不在生产代码新增测试 façade 或扩大可见性。
mod bridge {
    include!("../../src/ui/bridge.rs");

    /// Critical detail events remain ordered when queue pressure is recovered through a snapshot.
    /// @author kongweiguang
    #[test]
    fn detail_read_sequence_survives_pressure_and_snapshot_replacement() {
        let events = UiEventSender::default();
        for index in 0..32 {
            events
                .send(UiEvent::UpsertEntry(super::model::TimelineEntry {
                    id: format!("entry-{index}"),
                    kind: super::model::TimelineKind::Assistant,
                    text: "incremental".into(),
                    detail: None,
                    status: None,
                }))
                .unwrap();
        }
        for event in [
            UiEvent::ShowDetails {
                entry_id: "message-1".into(),
                title: "消息全文".into(),
                body: "正在读取安全预览".into(),
            },
            UiEvent::UpdateDetailsIfOpen {
                entry_id: "message-1".into(),
                title: "消息全文".into(),
                body: "完整正文".into(),
            },
            UiEvent::SetDetailsCopyReady {
                entry_id: "message-1".into(),
                ready: true,
            },
        ] {
            assert_eq!(events.send(event).unwrap(), EventDelivery::SnapshotRequired);
        }
        events
            .send(UiEvent::ReplaceSnapshot(Box::default()))
            .unwrap();

        let queued = events.drain();
        assert!(matches!(queued.as_slice(), [
            UiEvent::ReplaceSnapshot(_),
            UiEvent::ShowDetails { entry_id, .. },
            UiEvent::UpdateDetailsIfOpen { entry_id: updated_id, body, .. },
            UiEvent::SetDetailsCopyReady { entry_id: ready_id, ready: true },
        ] if entry_id == "message-1" && updated_id == entry_id && body == "完整正文" && ready_id == entry_id));
    }
}
#[path = "../../src/ui/composer.rs"]
mod composer;
#[path = "../../src/ui/markdown.rs"]
mod markdown;
#[path = "../../src/ui/markdown_stream.rs"]
mod markdown_stream;
#[path = "../../src/ui/model.rs"]
mod model;
#[path = "../../src/ui/model_settings.rs"]
mod model_settings;
#[path = "../../src/ui/native_backend.rs"]
mod native_backend;
#[path = "../../src/ui/paste_burst.rs"]
mod paste_burst;
#[path = "../../src/ui/render.rs"]
mod render;
#[path = "../../src/ui/state.rs"]
mod state;
#[path = "../../src/ui/theme.rs"]
mod theme;
#[path = "../../src/ui/transcript.rs"]
mod transcript;
#[path = "../../src/ui/usage.rs"]
mod usage;
#[path = "../../src/ui/windows_key_sequence.rs"]
mod windows_key_sequence;
#[path = "../../src/ui/wrapping.rs"]
mod wrapping;

#[path = "transcript_cases.rs"]
mod cases;
#[path = "ui_state_cases.rs"]
mod state_cases;
