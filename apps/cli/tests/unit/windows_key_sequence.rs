// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#![allow(dead_code)]

#[path = "../../src/ui/windows_key_sequence.rs"]
mod windows_key_sequence;

use std::time::{Duration, Instant};

use crossterm::event::{Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use windows_key_sequence::WindowsKeySequence;

/// 构造无修饰键按下事件，和 Crossterm Windows console backend 的普通输入形状一致。
fn key(code: KeyCode) -> Event {
    Event::Key(KeyEvent::new(code, KeyModifiers::NONE))
}

/// 返回 Windows Terminal 对 Shift+Enter 发送的 CSI-u 按键前缀。
fn sequence() -> [Event; 7] {
    [
        key(KeyCode::Esc),
        key(KeyCode::Char('[')),
        key(KeyCode::Char('1')),
        key(KeyCode::Char('3')),
        key(KeyCode::Char(';')),
        key(KeyCode::Char('2')),
        key(KeyCode::Char('u')),
    ]
}

/// 仅完整CSI-u按下序列变成Shift+Enter，并抑制末键对应的释放事件。
#[test]
fn complete_sequence_becomes_one_shift_enter() {
    let start = Instant::now();
    let mut decoder = WindowsKeySequence::new();
    let mut output = Vec::new();
    for (index, event) in sequence().into_iter().enumerate() {
        output.extend(decoder.push(event, start + Duration::from_millis(index as u64)));
    }

    assert_eq!(
        output,
        [Event::Key(KeyEvent::new(
            KeyCode::Enter,
            KeyModifiers::SHIFT
        ))]
    );
    let release = Event::Key(KeyEvent {
        kind: KeyEventKind::Release,
        ..KeyEvent::new(KeyCode::Char('u'), KeyModifiers::NONE)
    });
    assert!(
        decoder
            .push(release, start + Duration::from_millis(8))
            .is_empty()
    );
}

/// 未匹配前缀逐项原样回放，新的Escape仍可立即作为下一候选起点。
#[test]
fn mismatch_replays_prefix_and_reuses_new_escape() {
    let start = Instant::now();
    let mut decoder = WindowsKeySequence::new();
    assert!(decoder.push(key(KeyCode::Esc), start).is_empty());
    assert!(
        decoder
            .push(key(KeyCode::Char('[')), start + Duration::from_millis(1))
            .is_empty()
    );
    let output = decoder.push(key(KeyCode::Esc), start + Duration::from_millis(2));

    assert_eq!(output, [key(KeyCode::Esc), key(KeyCode::Char('['))]);
    assert!(
        decoder
            .flush_if_due(start + Duration::from_millis(51))
            .is_empty()
    );
    let output = decoder.flush_if_due(start + Duration::from_millis(52));
    assert_eq!(output, [key(KeyCode::Esc)]);
}

/// 到达50毫秒截止时未完成的输入立即按原顺序回放，不依赖下一次键盘事件。
#[test]
fn incomplete_prefix_replays_at_deadline() {
    let start = Instant::now();
    let mut decoder = WindowsKeySequence::new();
    for event in sequence().into_iter().take(3) {
        assert!(decoder.push(event, start).is_empty());
    }

    assert_eq!(
        decoder.flush_if_due(start + Duration::from_millis(50)),
        sequence()[..3]
    );
}

/// poll 等待值始终不超过50毫秒解码截止，临近截止时按剩余时限缩短。
#[test]
fn remaining_timeout_tracks_exact_sequence_deadline() {
    let start = Instant::now();
    let mut decoder = WindowsKeySequence::new();
    assert_eq!(decoder.remaining_timeout(start), None);
    assert!(decoder.push(key(KeyCode::Esc), start).is_empty());

    assert_eq!(
        decoder.remaining_timeout(start),
        Some(Duration::from_millis(50))
    );
    assert_eq!(
        decoder.remaining_timeout(start + Duration::from_millis(49)),
        Some(Duration::from_millis(1))
    );
    assert_eq!(
        decoder.remaining_timeout(start + Duration::from_millis(50)),
        Some(Duration::ZERO)
    );
    assert_eq!(
        decoder.flush_if_due(start + Duration::from_millis(50)),
        [key(KeyCode::Esc)]
    );
    assert_eq!(
        decoder.remaining_timeout(start + Duration::from_millis(50)),
        None
    );
}

/// 序列中匹配的释放键会被暂存并在成功识别时吞掉，失败时则随原前缀回放。
#[test]
fn matching_releases_are_suppressed_only_with_a_complete_sequence() {
    let start = Instant::now();
    let mut decoder = WindowsKeySequence::new();
    let mut output = Vec::new();
    for event in sequence() {
        let Event::Key(pressed) = event else {
            unreachable!()
        };
        output.extend(decoder.push(Event::Key(pressed), start));
        output.extend(decoder.push(
            Event::Key(KeyEvent {
                kind: KeyEventKind::Release,
                ..pressed
            }),
            start,
        ));
    }

    assert_eq!(
        output,
        [Event::Key(KeyEvent::new(
            KeyCode::Enter,
            KeyModifiers::SHIFT
        ))]
    );

    let mut failed = WindowsKeySequence::new();
    let press = KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE);
    assert!(failed.push(Event::Key(press), start).is_empty());
    assert!(
        failed
            .push(
                Event::Key(KeyEvent {
                    kind: KeyEventKind::Release,
                    ..press
                }),
                start,
            )
            .is_empty()
    );
    assert_eq!(
        failed.push(key(KeyCode::Char('x')), start),
        [
            Event::Key(press),
            Event::Key(KeyEvent {
                kind: KeyEventKind::Release,
                ..press
            }),
            key(KeyCode::Char('x'))
        ]
    );
}

/// Paste 事件作为不透明文本透传，即使正文恰好包含按键序列也不做二次解析。
#[test]
fn literal_sequence_inside_paste_is_untouched() {
    let start = Instant::now();
    let mut decoder = WindowsKeySequence::new();
    let paste = Event::Paste("\u{1b}[13;2u 中文🙂".to_owned());

    assert_eq!(decoder.push(paste.clone(), start), [paste]);
}

/// 外部程序交接丢弃旧输入源的半截序列，不能和恢复后的后缀拼接。
#[test]
fn handoff_reset_discards_partial_old_input() {
    let start = Instant::now();
    let mut decoder = WindowsKeySequence::new();
    assert!(decoder.push(key(KeyCode::Esc), start).is_empty());
    decoder.reset();

    assert_eq!(
        decoder.push(key(KeyCode::Char('[')), start + Duration::from_millis(1)),
        [key(KeyCode::Char('['))]
    );
}
