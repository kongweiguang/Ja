// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[path = "../../src/ui/paste_burst.rs"]
mod paste_burst;

use std::time::{Duration, Instant};

use paste_burst::{CharDecision, ControlDecision, FlushResult, PasteBurst};

/// Synthetic timestamps make the hold, active-idle and suppression boundaries deterministic.
/// @author kongweiguang
fn after_active_idle(burst: &PasteBurst) -> Instant {
    burst
        .next_deadline()
        .expect("active burst has an idle deadline")
        + Duration::from_millis(1)
}

/// A short ASCII prefix followed by Enter stays one edit instead of accidentally submitting.
/// @author kongweiguang
#[test]
fn one_to_four_fast_characters_and_enter_form_one_paste() {
    let start = Instant::now();
    for count in 1..=4 {
        let mut burst = PasteBurst::default();
        let text = "abcd".chars().take(count).collect::<String>();
        for (index, ch) in text.chars().enumerate() {
            let input = burst.on_plain_char(ch, start + Duration::from_millis(index as u64 * 2));
            assert_eq!(input.flushed, FlushResult::None);
            assert_eq!(
                input.decision,
                if index == 0 {
                    CharDecision::Hold
                } else {
                    CharDecision::Buffered
                }
            );
        }

        let enter_at = start + Duration::from_millis(count as u64 * 2);
        let control = burst.append_control_char('\n', enter_at);
        assert_eq!(control.flushed, FlushResult::None);
        assert_eq!(control.decision, ControlDecision::Buffered);
        let due = after_active_idle(&burst);
        assert_eq!(
            burst.flush_if_due(due),
            FlushResult::Paste(format!("{text}\n"))
        );
    }
}

/// Fast non-bracketed multiline text keeps embedded Enter events inside the same bounded buffer.
/// @author kongweiguang
#[test]
fn multiline_fast_stream_keeps_each_newline_in_order() {
    let start = Instant::now();
    let mut burst = PasteBurst::default();
    assert_eq!(burst.on_plain_char('a', start).decision, CharDecision::Hold);
    assert_eq!(
        burst
            .append_control_char('\n', start + Duration::from_millis(2))
            .decision,
        ControlDecision::Buffered
    );
    assert_eq!(
        burst
            .on_plain_char('b', start + Duration::from_millis(4))
            .decision,
        CharDecision::Buffered
    );
    assert_eq!(
        burst
            .append_control_char('\n', start + Duration::from_millis(6))
            .decision,
        ControlDecision::Buffered
    );
    assert_eq!(
        burst
            .on_plain_char('c', start + Duration::from_millis(8))
            .decision,
        CharDecision::Buffered
    );

    let due = after_active_idle(&burst);
    assert_eq!(
        burst.flush_if_due(due),
        FlushResult::Paste("a\nb\nc".to_owned())
    );
}

/// Tab is data only during a classified stream; otherwise its normal picker/submit meaning stays.
/// @author kongweiguang
#[test]
fn tab_is_buffered_only_for_a_fast_prefix_or_live_paste_window() {
    let start = Instant::now();
    let mut burst = PasteBurst::default();
    assert_eq!(burst.on_plain_char('x', start).decision, CharDecision::Hold);
    let tab = burst.append_control_char('\t', start + Duration::from_millis(2));
    assert_eq!(tab.flushed, FlushResult::None);
    assert_eq!(tab.decision, ControlDecision::Buffered);
    let idle_deadline = burst.next_deadline().expect("buffer has idle deadline");
    assert_eq!(
        burst.flush_if_due(idle_deadline + Duration::from_millis(1)),
        FlushResult::Paste("x\t".to_owned())
    );
    assert_eq!(
        burst
            .append_control_char('\t', idle_deadline + Duration::from_millis(20))
            .decision,
        ControlDecision::Insert
    );
    assert_eq!(
        burst
            .append_control_char('\t', start + Duration::from_millis(210))
            .decision,
        ControlDecision::NotPaste
    );
}

/// Slow typing releases each character separately, preserving ordinary Enter submission.
/// @author kongweiguang
#[test]
fn slow_typing_is_released_as_text_and_enter_is_not_consumed() {
    let start = Instant::now();
    let mut burst = PasteBurst::default();
    assert_eq!(burst.on_plain_char('o', start).decision, CharDecision::Hold);
    let next = burst.on_plain_char('k', start + Duration::from_millis(30));
    assert_eq!(next.flushed, FlushResult::Typed('o'));
    assert_eq!(next.decision, CharDecision::Hold);
    assert_eq!(
        burst.flush_if_due(start + Duration::from_millis(39)),
        FlushResult::Typed('k')
    );
    let enter = burst.append_control_char('\n', start + Duration::from_millis(45));
    assert_eq!(enter.flushed, FlushResult::None);
    assert_eq!(enter.decision, ControlDecision::NotPaste);
}

/// Non-ASCII IME input is never held by the classifier and therefore remains immediately visible.
/// @author kongweiguang
#[test]
fn non_ascii_input_is_direct() {
    let start = Instant::now();
    let mut burst = PasteBurst::default();
    let input = burst.on_plain_char('中', start);
    assert_eq!(input.flushed, FlushResult::None);
    assert_eq!(input.decision, CharDecision::Insert);
}

/// Unicode remains immediate while recent IME input still gives a following Enter text meaning.
/// @author kongweiguang
#[test]
fn fast_chinese_and_emoji_keep_enter_inside_the_input_burst() {
    let start = Instant::now();
    let mut chinese = PasteBurst::default();
    assert_eq!(
        chinese.on_plain_char('中', start).decision,
        CharDecision::Insert
    );
    assert_eq!(
        chinese
            .on_plain_char('文', start + Duration::from_millis(2))
            .decision,
        CharDecision::Insert
    );
    assert_eq!(
        chinese
            .append_control_char('\n', start + Duration::from_millis(4))
            .decision,
        ControlDecision::Insert
    );

    let mut emoji = PasteBurst::default();
    assert_eq!(
        emoji.on_plain_char('🙂', start).decision,
        CharDecision::Insert
    );
    assert_eq!(
        emoji
            .append_control_char('\n', start + Duration::from_millis(4))
            .decision,
        ControlDecision::Insert
    );
}

/// Unicode keys separated beyond both timing windows keep Enter's ordinary submit behavior.
/// @author kongweiguang
#[test]
fn spaced_chinese_input_does_not_leave_a_stale_enter_window() {
    let start = Instant::now();
    let mut burst = PasteBurst::default();
    assert_eq!(
        burst.on_plain_char('普', start).decision,
        CharDecision::Insert
    );
    assert_eq!(
        burst
            .append_control_char('\n', start + Duration::from_millis(25))
            .decision,
        ControlDecision::NotPaste
    );
    assert_eq!(
        burst
            .on_plain_char('通', start + Duration::from_millis(50))
            .decision,
        CharDecision::Insert
    );
    assert_eq!(
        burst
            .append_control_char('\n', start + Duration::from_millis(70))
            .decision,
        ControlDecision::NotPaste
    );
}

/// Switching scripts flushes earlier ASCII in order and keeps only direct insertion timing state.
/// @author kongweiguang
#[test]
fn ascii_and_unicode_transitions_preserve_input_order_and_enter_ownership() {
    let start = Instant::now();
    let mut ascii_to_unicode = PasteBurst::default();
    assert_eq!(
        ascii_to_unicode.on_plain_char('a', start).decision,
        CharDecision::Hold
    );
    let unicode = ascii_to_unicode.on_plain_char('中', start + Duration::from_millis(2));
    assert_eq!(unicode.flushed, FlushResult::Typed('a'));
    assert_eq!(unicode.decision, CharDecision::Insert);
    assert_eq!(
        ascii_to_unicode
            .append_control_char('\n', start + Duration::from_millis(4))
            .decision,
        ControlDecision::Insert
    );

    let mut active_ascii_to_unicode = PasteBurst::default();
    assert_eq!(
        active_ascii_to_unicode.on_plain_char('a', start).decision,
        CharDecision::Hold
    );
    assert_eq!(
        active_ascii_to_unicode
            .on_plain_char('b', start + Duration::from_millis(2))
            .decision,
        CharDecision::Buffered
    );
    let unicode = active_ascii_to_unicode.on_plain_char('文', start + Duration::from_millis(4));
    assert_eq!(unicode.flushed, FlushResult::Paste("ab".to_owned()));
    assert_eq!(unicode.decision, CharDecision::Insert);
    assert_eq!(
        active_ascii_to_unicode
            .append_control_char('\n', start + Duration::from_millis(6))
            .decision,
        ControlDecision::Insert
    );

    let mut unicode_to_ascii = PasteBurst::default();
    assert_eq!(
        unicode_to_ascii.on_plain_char('中', start).decision,
        CharDecision::Insert
    );
    assert_eq!(
        unicode_to_ascii
            .on_plain_char('a', start + Duration::from_millis(2))
            .decision,
        CharDecision::Insert
    );
    assert_eq!(
        unicode_to_ascii
            .append_control_char('\n', start + Duration::from_millis(4))
            .decision,
        ControlDecision::Insert
    );
}

/// A modified-key boundary returns the full accepted prefix and clears Enter suppression.
/// @author kongweiguang
#[test]
fn modified_input_flushes_the_buffer_and_ends_the_paste_window() {
    let start = Instant::now();
    let mut burst = PasteBurst::default();
    assert_eq!(burst.on_plain_char('a', start).decision, CharDecision::Hold);
    assert_eq!(
        burst
            .on_plain_char('b', start + Duration::from_millis(2))
            .decision,
        CharDecision::Buffered
    );
    assert_eq!(
        burst.flush_before_modified_input(),
        FlushResult::Paste("ab".to_owned())
    );
    assert_eq!(
        burst
            .append_control_char('\n', start + Duration::from_millis(4))
            .decision,
        ControlDecision::NotPaste
    );
}

/// An oversized burst is rejected as a whole while its retained buffer stays bounded.
/// @author kongweiguang
#[test]
fn oversized_burst_is_rejected_without_a_partial_payload() {
    let start = Instant::now();
    let mut burst = PasteBurst::default();
    assert_eq!(burst.on_plain_char('a', start).decision, CharDecision::Hold);
    assert_eq!(
        burst
            .on_plain_char('b', start + Duration::from_millis(1))
            .decision,
        CharDecision::Buffered
    );
    for _ in 0..(32 * 1024) {
        assert_eq!(
            burst
                .on_plain_char('x', start + Duration::from_millis(2))
                .decision,
            CharDecision::Buffered
        );
    }
    assert_eq!(
        burst.flush_if_due(after_active_idle(&burst)),
        FlushResult::Rejected
    );
}
