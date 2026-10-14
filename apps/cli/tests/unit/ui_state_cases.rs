// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::time::{Duration, Instant};

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers, MouseButton, MouseEvent, MouseEventKind};
use ratatui::Terminal;
use ratatui::backend::TestBackend;

use super::model::{
    TimelineEntry, TimelineKind, TimelineStatus, UiAction, UiChoice, UiEvent, UiSnapshot,
};
use super::render;
use super::state::UiState;
use super::transcript::Transcript;

/// Build deterministic key events while allowing state-level tests to advance classifier time without sleeps.
/// @author kongweiguang
fn key(code: KeyCode, modifiers: KeyModifiers) -> KeyEvent {
    KeyEvent::new(code, modifiers)
}

/// A delayed query may publish only its newest text, and closing the picker invalidates that ownership.
/// @author kongweiguang
#[test]
fn input_history_debounces_latest_query_and_rejects_stale_pages() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thread-history".into()),
        ..UiSnapshot::default()
    });
    assert_eq!(
        state.handle_key(key(KeyCode::Char('r'), KeyModifiers::CONTROL)),
        vec![UiAction::CancelInputHistorySearch]
    );
    let future = Instant::now() + Duration::from_secs(1);
    assert_eq!(
        state.take_due_input_history_search(future),
        Some(UiAction::SearchInputHistory {
            query: String::new(),
            cursor: None,
        })
    );

    state.handle_key(key(KeyCode::Char('a'), KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Char('b'), KeyModifiers::NONE));
    assert_eq!(
        state.take_due_input_history_search(future),
        Some(UiAction::SearchInputHistory {
            query: "ab".into(),
            cursor: None,
        })
    );

    state.apply(UiEvent::SetInputHistoryPage {
        query: "a".into(),
        items: vec![(
            UiChoice {
                id: "stale".into(),
                label: "stale result".into(),
                detail: None,
            },
            "stale body".into(),
            false,
        )],
        next_cursor: None,
        append: false,
    });
    assert!(state.snapshot().input_history_choices.is_empty());

    state.apply(UiEvent::SetInputHistoryPage {
        query: "ab".into(),
        items: vec![(
            UiChoice {
                id: "latest".into(),
                label: "latest result".into(),
                detail: None,
            },
            "latest body".into(),
            false,
        )],
        next_cursor: None,
        append: false,
    });
    assert_eq!(state.snapshot().input_history_choices[0].id, "latest");
    assert_eq!(
        state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CancelInputHistorySearch]
    );
    assert_eq!(state.take_due_input_history_search(future), None);
}

/// Switching Thread drops scheduled picker work so an old history result cannot cross session identity.
/// @author kongweiguang
#[test]
fn input_history_debounce_is_cleared_on_thread_change() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thread-before".into()),
        ..UiSnapshot::default()
    });
    state.handle_key(key(KeyCode::Char('r'), KeyModifiers::CONTROL));
    state.handle_key(key(KeyCode::Char('x'), KeyModifiers::NONE));
    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thread-after".into()),
        ..UiSnapshot::default()
    })));

    assert_eq!(state.input_history_query(), "");
    assert_eq!(
        state.take_due_input_history_search(Instant::now() + Duration::from_secs(1)),
        None
    );
}

/// Help punctuation stays buffered until classified, and a modified-key boundary releases held text in order.
/// @author kongweiguang
#[test]
fn held_help_shortcut_is_not_applied_before_classifier_flush() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_key(key(KeyCode::Char('?'), KeyModifiers::NONE));
    assert!(state.composer().text().is_empty());

    assert!(state.flush_paste_burst(Instant::now() + Duration::from_secs(1)));
    assert!(state.composer().text().is_empty());
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Char('x'), KeyModifiers::NONE));
    assert_eq!(
        state.handle_key(key(KeyCode::Char('g'), KeyModifiers::CONTROL)),
        vec![UiAction::EditDraft { draft: "x".into() }]
    );
    assert_eq!(state.composer().text(), "x");
}

/// Enter inside a burst is payload, and an explicit fake-clock flush commits that whole burst atomically.
/// @author kongweiguang
#[test]
fn paste_burst_enter_is_committed_as_text_without_submit() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_key(key(KeyCode::Char('a'), KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Char('b'), KeyModifiers::NONE));
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert!(
        !actions
            .iter()
            .any(|action| matches!(action, UiAction::Submit { .. }))
    );

    state.flush_paste_burst(Instant::now() + Duration::from_secs(1));
    assert_eq!(state.composer().text(), "ab\n");
}

/// F2 resolves the visible stable reading position even while the transcript follows its newest content.
/// @author kongweiguang
#[test]
fn f2_opens_the_visible_message_anchor_at_latest_position() {
    let mut state = UiState::new(UiSnapshot {
        timeline: vec![TimelineEntry {
            id: "visible-message".into(),
            kind: TimelineKind::Assistant,
            text: "visible body".into(),
            detail: None,
            status: Some(TimelineStatus::Complete),
        }],
        ..UiSnapshot::default()
    });
    let mut terminal = Terminal::new(TestBackend::new(64, 12)).unwrap();
    terminal
        .draw(|frame| render::render(frame, &state))
        .expect("the real renderer should establish the viewport anchor");

    assert_eq!(
        state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "visible-message".into(),
        }]
    );
}

/// Selection identity must change with its exact source range so an old asynchronous clipboard ACK cannot clear a new range.
/// @author kongweiguang
#[test]
fn dragging_after_copy_cancels_the_old_selection_identity() {
    let mut transcript = Transcript::default();
    transcript.append(
        TimelineEntry {
            id: "message-range".into(),
            kind: TimelineKind::Assistant,
            text: "a message to select".into(),
            detail: None,
            status: Some(TimelineStatus::Complete),
        },
        "message-range".into(),
        0,
    );
    transcript.update_layout(
        40,
        ratatui::layout::Rect::new(0, 0, 40, 3),
        ratatui::layout::Rect::default(),
        Vec::new(),
    );
    let mut terminal = Terminal::new(TestBackend::new(40, 3)).unwrap();
    terminal.draw(|frame| transcript.render(frame)).unwrap();
    let (source_row, start_column) = terminal
        .backend()
        .buffer()
        .content()
        .chunks(40)
        .enumerate()
        .find_map(|(row, cells)| {
            cells
                .iter()
                .map(|cell| cell.symbol())
                .collect::<String>()
                .find("a message to select")
                .map(|column| (row, column))
        })
        .expect("assistant source row should be present in the rendered transcript");
    let mouse = |kind, column| MouseEvent {
        kind,
        column,
        row: source_row as u16,
        modifiers: KeyModifiers::NONE,
    };
    transcript.handle_mouse(mouse(
        MouseEventKind::Down(MouseButton::Left),
        start_column as u16,
    ));
    transcript.handle_mouse(mouse(
        MouseEventKind::Drag(MouseButton::Left),
        (start_column + 7) as u16,
    ));
    let old_selection_id = transcript.selection_copy().expect("range can be copied").0;
    transcript.handle_mouse(mouse(
        MouseEventKind::Drag(MouseButton::Left),
        (start_column + 12) as u16,
    ));

    assert_eq!(transcript.pending_selection_copy_id(), None);
    assert!(!transcript.selection_copied(old_selection_id));
    let new_selection_id = transcript
        .selection_copy()
        .expect("updated range can be copied")
        .0;
    assert_ne!(new_selection_id, old_selection_id);
    assert!(!transcript.selection_copied(old_selection_id));
    assert_eq!(
        transcript.pending_selection_copy_id(),
        Some(new_selection_id)
    );
}
