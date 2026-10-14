// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Synchronous no-bracketed-paste classifier adapted from Codex `822e58cc`.
//! The small state machine preserves held text until the caller can apply a paste atomically;
//! upstream Apache-2.0 attribution is recorded in `apps/cli/NOTICE.md` and `third_party/`.

use std::time::{Duration, Instant};

const MAX_BUFFER_BYTES: usize = 32 * 1024;
const PASTE_BURST_CHAR_INTERVAL: Duration = Duration::from_millis(8);
const PASTE_ENTER_SUPPRESS_WINDOW: Duration = Duration::from_millis(120);

#[cfg(windows)]
const PASTE_BURST_ACTIVE_IDLE_TIMEOUT: Duration = Duration::from_millis(60);
#[cfg(not(windows))]
const PASTE_BURST_ACTIVE_IDLE_TIMEOUT: Duration = Duration::from_millis(8);

/// A character is either ready for normal insertion or held until the stream is classified.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CharDecision {
    Insert,
    Hold,
    Buffered,
}

/// Tick and key-boundary flushes preserve whether the held data is typing or a paste.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) enum FlushResult {
    None,
    Typed(char),
    Paste(String),
    Rejected,
}

/// Controls distinguish an ordinary key from a direct insertion and a buffered paste member.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ControlDecision {
    NotPaste,
    Insert,
    Buffered,
}

/// An input event carries due prior text so the caller can preserve event ordering under load.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct CharInput {
    pub(crate) flushed: FlushResult,
    pub(crate) decision: CharDecision,
}

/// A control event first returns any expired text, then tells the caller how to handle the key.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct ControlInput {
    pub(crate) flushed: FlushResult,
    pub(crate) decision: ControlDecision,
}

/// Burst state is deliberately bounded and contains no terminal, clock, or editor dependencies.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub(crate) struct PasteBurst {
    last_plain_char_time: Option<Instant>,
    active_until: Option<Instant>,
    buffer: String,
    active: bool,
    overflowed: bool,
    pending_first_char: Option<(char, Instant)>,
}

impl PasteBurst {
    /// Flush due prior input, hold only an initial ASCII key, and keep IME text immediate.
    ///
    /// Returning both operations closes the race where a delayed terminal loop receives a key at
    /// the same time its idle deadline expires. Non-ASCII input releases held ASCII text without
    /// delaying the IME character, then keeps only the timing needed to classify a nearby Enter.
    /// @author kongweiguang
    pub(crate) fn on_plain_char(&mut self, ch: char, now: Instant) -> CharInput {
        if !ch.is_ascii() {
            // IME commits stay visible immediately. Preserve only a recent/burst marker so an
            // adjacent Enter remains text, while an isolated Unicode key cannot suppress Enter
            // after the ordinary inter-key window has elapsed.
            let burst_like = self.active_until.is_some_and(|until| now <= until)
                || self.last_plain_char_time.is_some_and(|last| {
                    now.saturating_duration_since(last) <= PASTE_BURST_CHAR_INTERVAL
                });
            let due = self.flush_if_due(now);
            let flushed = if due == FlushResult::None {
                self.flush_before_modified_input()
            } else {
                due
            };
            self.note_direct_char(now, burst_like);
            return CharInput {
                flushed,
                decision: CharDecision::Insert,
            };
        }

        let flushed = self.flush_if_due(now);
        let decision = self.classify_plain_char(ch, now);
        CharInput { flushed, decision }
    }

    /// Classify an ASCII key after its due predecessor has already been returned to the caller.
    /// @author kongweiguang
    fn classify_plain_char(&mut self, ch: char, now: Instant) -> CharDecision {
        if self.active {
            self.append(ch);
            self.note_activity(now);
            return CharDecision::Buffered;
        }

        if let Some((first, _)) = self.pending_first_char.take() {
            self.active = true;
            self.append(first);
            self.append(ch);
            self.note_activity(now);
            return CharDecision::Buffered;
        }

        // After a directly inserted Unicode/IME character, immediately inserting a fast ASCII
        // continuation preserves visual order and carries the same short Enter ownership window.
        if self.active_until.is_some_and(|until| now <= until)
            || self.last_plain_char_time.is_some_and(|last| {
                now.saturating_duration_since(last) <= PASTE_BURST_CHAR_INTERVAL
            })
        {
            self.note_activity(now);
            return CharDecision::Insert;
        }

        self.pending_first_char = Some((ch, now));
        self.last_plain_char_time = Some(now);
        CharDecision::Hold
    }

    /// Accumulate Enter or Tab only while a held/active burst owns the input sequence.
    ///
    /// Once a buffered burst has flushed, its short suppression window still turns a following
    /// control key into direct text, matching the existing editor's Enter behavior.
    /// @author kongweiguang
    pub(crate) fn append_control_char(&mut self, ch: char, now: Instant) -> ControlInput {
        let flushed = self.flush_if_due(now);
        let decision = self.classify_control_char(ch, now);
        ControlInput { flushed, decision }
    }

    /// Decide whether a control belongs to buffered paste text or remains a direct input action.
    /// @author kongweiguang
    fn classify_control_char(&mut self, ch: char, now: Instant) -> ControlDecision {
        if self.active || self.pending_first_char.is_some() {
            if let Some((first, _)) = self.pending_first_char.take() {
                self.active = true;
                self.append(first);
            }
            self.active = true;
            self.append(ch);
            self.note_activity(now);
            return ControlDecision::Buffered;
        }

        if self.active_until.is_some_and(|until| now <= until) {
            self.active_until = now.checked_add(PASTE_ENTER_SUPPRESS_WINDOW);
            return ControlDecision::Insert;
        }

        // A lone directly inserted IME character owns only the immediate Enter edge; extending
        // the full window here makes consecutive newlines in an actual burst remain together.
        if self
            .last_plain_char_time
            .is_some_and(|last| now.saturating_duration_since(last) <= PASTE_BURST_CHAR_INTERVAL)
        {
            self.active_until = now.checked_add(PASTE_ENTER_SUPPRESS_WINDOW);
            return ControlDecision::Insert;
        }

        self.clear();
        ControlDecision::NotPaste
    }

    /// Release held text after the relevant idle interval; paste overflow is rejected as a unit.
    ///
    /// The active buffer has a Windows-specific idle allowance because console input can arrive
    /// in slower bursts there, while ordinary typing is held for only one short character window.
    /// @author kongweiguang
    pub(crate) fn flush_if_due(&mut self, now: Instant) -> FlushResult {
        if let Some((ch, started)) = self.pending_first_char
            && now.saturating_duration_since(started) > PASTE_BURST_CHAR_INTERVAL
        {
            self.pending_first_char = None;
            self.last_plain_char_time = None;
            return FlushResult::Typed(ch);
        }

        if self.active
            && self.last_plain_char_time.is_some_and(|last| {
                now.saturating_duration_since(last) > PASTE_BURST_ACTIVE_IDLE_TIMEOUT
            })
        {
            self.active = false;
            self.pending_first_char = None;
            self.last_plain_char_time = None;
            if self.overflowed {
                self.buffer.clear();
                self.overflowed = false;
                return FlushResult::Rejected;
            }
            return FlushResult::Paste(std::mem::take(&mut self.buffer));
        }

        if self.active_until.is_some_and(|until| now > until) {
            self.active_until = None;
        }
        FlushResult::None
    }

    /// Flush accepted text before navigation or shortcuts so no key is reordered behind a paste.
    ///
    /// This also clears the Enter window: moving the cursor or invoking a shortcut ends the
    /// current physical input sequence and must restore the normal key meaning immediately.
    /// @author kongweiguang
    pub(crate) fn flush_before_modified_input(&mut self) -> FlushResult {
        let result = if self.active {
            if self.overflowed {
                FlushResult::Rejected
            } else {
                FlushResult::Paste(std::mem::take(&mut self.buffer))
            }
        } else if let Some((ch, _)) = self.pending_first_char {
            FlushResult::Typed(ch)
        } else {
            FlushResult::None
        };
        self.clear();
        result
    }

    /// Return the next classifier deadline so terminal polling can wake to release a held key.
    /// @author kongweiguang
    pub(crate) fn next_deadline(&self) -> Option<Instant> {
        if self.active {
            self.last_plain_char_time
                .and_then(|last| last.checked_add(PASTE_BURST_ACTIVE_IDLE_TIMEOUT))
        } else {
            self.pending_first_char
                .and_then(|(_, started)| started.checked_add(PASTE_BURST_CHAR_INTERVAL))
                .or(self.active_until)
        }
    }

    /// Drop transient state after an already-flushed explicit paste or a deliberate hard reset.
    /// @author kongweiguang
    pub(crate) fn clear(&mut self) {
        self.last_plain_char_time = None;
        self.active_until = None;
        self.buffer.clear();
        self.active = false;
        self.overflowed = false;
        self.pending_first_char = None;
    }

    /// Extend the control-key suppression window and record the end of the current burst payload.
    /// @author kongweiguang
    fn note_activity(&mut self, now: Instant) {
        self.last_plain_char_time = Some(now);
        self.active_until = now.checked_add(PASTE_ENTER_SUPPRESS_WINDOW);
    }

    /// Record an immediately inserted Unicode character without retaining it in classifier memory.
    /// @author kongweiguang
    fn note_direct_char(&mut self, now: Instant, burst_like: bool) {
        self.last_plain_char_time = Some(now);
        self.active_until = burst_like
            .then(|| now.checked_add(PASTE_ENTER_SUPPRESS_WINDOW))
            .flatten();
    }

    /// Cap retained memory while remembering that a paste must be rejected without partial text.
    /// @author kongweiguang
    fn append(&mut self, ch: char) {
        if self.buffer.len().saturating_add(ch.len_utf8()) > MAX_BUFFER_BYTES {
            self.overflowed = true;
        } else if !self.overflowed {
            self.buffer.push(ch);
        }
    }
}
