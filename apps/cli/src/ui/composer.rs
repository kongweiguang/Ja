// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::collections::VecDeque;
use std::time::{Duration, Instant};

use super::model::{UiReference, UiReferenceKind};
use unicode_segmentation::UnicodeSegmentation;

const MAX_DRAFT_BYTES: usize = 32 * 1024;
const PASTE_BURST_GAP: Duration = Duration::from_millis(12);
const PASTE_BURST_GRACE: Duration = Duration::from_millis(180);
const MAX_UNDO_STEPS: usize = 64;

/// 光标以字素边界计数，避免中文组合字符和 Emoji 被拆成无效片段。
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct Composer {
    value: String,
    cursor: usize,
    references: Vec<UiReference>,
    preferred_column: Option<usize>,
    undo: VecDeque<EditSnapshot>,
    redo: VecDeque<EditSnapshot>,
    paste_burst: PasteBurst,
}

/// 撤销快照包含引用身份，文本回滚时不能留下指向旧字素区间的引用。
#[derive(Clone, Debug, Eq, PartialEq)]
struct EditSnapshot {
    value: String,
    cursor: usize,
    references: Vec<UiReference>,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
struct PasteBurst {
    last_ascii_char: Option<Instant>,
    run_length: u8,
    active_until: Option<Instant>,
}

impl Composer {
    /// 创建独立编辑缓冲，避免输入草稿与协议层共享可变状态。
    pub fn new() -> Self {
        Self::default()
    }

    /// 读取草稿的只读视图，提交动作仍由 controller 消费。
    pub fn text(&self) -> &str {
        &self.value
    }

    /// 空草稿决定 Enter、Ctrl+C 和 Ctrl+D 的不同含义。
    pub fn is_empty(&self) -> bool {
        self.value.is_empty()
    }

    /// 返回字素位置，渲染器据此计算显示列。
    pub fn cursor(&self) -> usize {
        self.cursor
    }

    /// 提交只能读取仍与文本区间吻合的引用，删除或覆盖后的身份不会残留。
    pub fn references(&self) -> &[UiReference] {
        &self.references
    }

    /// 替换时沿用同一容量上限，恢复大文本不会绕过正常编辑预算。
    pub fn set_text(&mut self, value: &str) -> bool {
        let (accepted, complete) = safe_input_prefix(value, MAX_DRAFT_BYTES);
        if !complete {
            return false;
        }
        self.value = accepted;
        self.cursor = self.value.graphemes(true).count();
        self.references.clear();
        self.preferred_column = None;
        self.undo.clear();
        self.redo.clear();
        self.paste_burst.clear();
        true
    }

    /// 文本和 bracketed paste 共用字素安全插入，并对草稿总字节数设硬上限。
    pub fn insert_text(&mut self, value: &str) -> bool {
        let insertion = grapheme_byte_index(&self.value, self.cursor);
        let room = MAX_DRAFT_BYTES.saturating_sub(self.value.len());
        let (accepted, complete) = safe_input_prefix(value, room);
        if !complete {
            return false;
        }
        if !accepted.is_empty() {
            self.record_edit();
        }
        self.adjust_references(self.cursor, self.cursor, accepted.graphemes(true).count());
        self.value.insert_str(insertion, &accepted);
        self.cursor += accepted.graphemes(true).count();
        self.preferred_column = None;
        complete
    }

    /// 替换指定字素范围并把光标放到新文本末尾，区间之外的草稿保持不变。
    pub fn replace_range(&mut self, start: usize, end: usize, replacement: &str) -> bool {
        let count = self.value.graphemes(true).count();
        let start = start.min(count);
        let end = end.max(start).min(count);
        let start_byte = grapheme_byte_index(&self.value, start);
        let end_byte = grapheme_byte_index(&self.value, end);
        let retained_bytes = self.value.len().saturating_sub(end_byte - start_byte);
        let room = MAX_DRAFT_BYTES.saturating_sub(retained_bytes);
        let (accepted, complete) = safe_input_prefix(replacement, room);
        if !complete {
            return false;
        }
        if start_byte != end_byte || !accepted.is_empty() {
            self.record_edit();
        }
        self.adjust_references(start, end, accepted.graphemes(true).count());
        self.value.replace_range(start_byte..end_byte, &accepted);
        self.cursor = start + accepted.graphemes(true).count();
        self.preferred_column = None;
        self.paste_burst.clear();
        complete
    }

    /// 左移一整个字素，保持复杂 Unicode 字形不可拆分。
    pub fn move_left(&mut self) {
        self.cursor = self.cursor.saturating_sub(1);
        self.preferred_column = None;
    }

    /// 右移一整个字素并夹在草稿末尾。
    pub fn move_right(&mut self) {
        self.cursor = (self.cursor + 1).min(self.value.graphemes(true).count());
        self.preferred_column = None;
    }

    /// 删除光标前完整字素，不直接按字节回退。
    pub fn backspace(&mut self) {
        if self.cursor == 0 {
            return;
        }
        let start = grapheme_byte_index(&self.value, self.cursor - 1);
        let end = grapheme_byte_index(&self.value, self.cursor);
        self.record_edit();
        self.adjust_references(self.cursor - 1, self.cursor, 0);
        self.value.replace_range(start..end, "");
        self.cursor -= 1;
        self.preferred_column = None;
    }

    /// 删除光标下完整字素，空位置保持不变。
    pub fn delete(&mut self) {
        if self.cursor >= self.value.graphemes(true).count() {
            return;
        }
        let start = grapheme_byte_index(&self.value, self.cursor);
        let end = grapheme_byte_index(&self.value, self.cursor + 1);
        self.record_edit();
        self.adjust_references(self.cursor, self.cursor + 1, 0);
        self.value.replace_range(start..end, "");
        self.preferred_column = None;
    }

    /// Home 只移动到当前逻辑行开头，不跨过用户输入的换行。
    pub fn home(&mut self) {
        self.cursor = self.line_bounds().0;
        self.preferred_column = None;
    }

    /// End 只移动到当前逻辑行末尾，方便多行提示逐行修改。
    pub fn end(&mut self) {
        self.cursor = self.line_bounds().1;
        self.preferred_column = None;
    }

    /// 文本与字素光标一起清空，避免残留位置指向旧草稿。
    pub fn clear(&mut self) {
        self.value.clear();
        self.cursor = 0;
        self.references.clear();
        self.preferred_column = None;
        self.undo.clear();
        self.redo.clear();
        self.paste_burst.clear();
    }

    /// 垂直移动使用终端显示列，并在短行往返时记住原列，不把中文宽字符拆开。
    pub fn move_vertical(&mut self, width: usize, down: bool) {
        let positions = self.visual_positions(width.max(4));
        let (row, column) = positions[self.cursor];
        let desired = *self.preferred_column.get_or_insert(column);
        let target = if down {
            row.saturating_add(1)
        } else {
            row.saturating_sub(1)
        };
        if target == row || !positions.iter().any(|(candidate, _)| *candidate == target) {
            return;
        }
        self.cursor = positions
            .iter()
            .enumerate()
            .filter(|(_, (candidate, _))| *candidate == target)
            .min_by_key(|(index, (_, column))| (column.abs_diff(desired), *index))
            .map_or(self.cursor, |(index, _)| index);
    }

    /// Ctrl+Left 越过空白和一个词，标点独立成组，避免依赖 ASCII 字节位置。
    pub fn move_word_left(&mut self) {
        let parts = self.value.graphemes(true).collect::<Vec<_>>();
        let mut at = self.cursor;
        while at > 0 && parts[at - 1].chars().all(char::is_whitespace) {
            at -= 1;
        }
        if at > 0 {
            let class = word_class(parts[at - 1]);
            while at > 0 && word_class(parts[at - 1]) == class {
                at -= 1;
            }
        }
        self.cursor = at;
        self.preferred_column = None;
    }

    /// Ctrl+Right 按相同的字素分类前进，和 Ctrl+Left 保持可逆的边界语义。
    pub fn move_word_right(&mut self) {
        let parts = self.value.graphemes(true).collect::<Vec<_>>();
        let mut at = self.cursor;
        if at < parts.len() {
            let class = word_class(parts[at]);
            while at < parts.len() && word_class(parts[at]) == class {
                at += 1;
            }
        }
        while at < parts.len() && parts[at].chars().all(char::is_whitespace) {
            at += 1;
        }
        self.cursor = at;
        self.preferred_column = None;
    }

    /// Ctrl+Backspace 先找词边界再一次删除，撤销时作为一个编辑步骤恢复。
    pub fn delete_word_left(&mut self) {
        let end = self.cursor;
        self.move_word_left();
        let start = self.cursor;
        self.cursor = end;
        if start < end {
            self.replace_range(start, end, "");
        }
    }

    /// Ctrl+Delete 删除光标后的一个词，保留光标前已经输入的字素。
    pub fn delete_word_right(&mut self) {
        let start = self.cursor;
        self.move_word_right();
        let end = self.cursor;
        self.cursor = start;
        if start < end {
            self.replace_range(start, end, "");
        }
    }

    /// 撤销只恢复本草稿有界快照，成功提交后的 clear 会清空旧历史。
    pub fn undo(&mut self) {
        if let Some(snapshot) = self.undo.pop_back() {
            let current = self.snapshot();
            bounded_push(&mut self.redo, current);
            self.value = snapshot.value;
            self.cursor = snapshot.cursor;
            self.references = snapshot.references;
            self.preferred_column = None;
        }
    }

    /// 重做仅重放当前草稿的编辑快照，不跨越一次成功提交。
    pub fn redo(&mut self) {
        if let Some(snapshot) = self.redo.pop_back() {
            let current = self.snapshot();
            bounded_push(&mut self.undo, current);
            self.value = snapshot.value;
            self.cursor = snapshot.cursor;
            self.references = snapshot.references;
            self.preferred_column = None;
        }
    }

    /// 每次实际改动先保存字素光标与文本，历史长度固定以约束大草稿内存。
    fn record_edit(&mut self) {
        let current = self.snapshot();
        bounded_push(&mut self.undo, current);
        self.redo.clear();
    }

    /// 提交前插入的标签记录精确区间；后续编辑会移动或删除这份身份绑定。
    pub fn replace_with_reference(
        &mut self,
        start: usize,
        end: usize,
        label: &str,
        kind: UiReferenceKind,
        separator: bool,
    ) -> bool {
        let replacement = if separator {
            format!("{label} ")
        } else {
            label.to_owned()
        };
        if !self.replace_range(start, end, &replacement) {
            return false;
        }
        self.references.push(UiReference {
            id: uuid::Uuid::new_v4().simple().to_string(),
            start,
            end: start + label.graphemes(true).count(),
            kind,
        });
        true
    }

    /// 编辑区间前后的引用按字素数平移，任何覆盖引用内部的编辑使其失效。
    fn adjust_references(&mut self, start: usize, end: usize, inserted: usize) {
        let removed = end.saturating_sub(start);
        self.references.retain_mut(|reference| {
            if reference.end <= start {
                return true;
            }
            if reference.start >= end {
                reference.start = reference
                    .start
                    .saturating_sub(removed)
                    .saturating_add(inserted);
                reference.end = reference
                    .end
                    .saturating_sub(removed)
                    .saturating_add(inserted);
                return true;
            }
            false
        });
    }

    /// 复制文本和引用作为同一编辑事实，撤销不能恢复一半状态。
    fn snapshot(&self) -> EditSnapshot {
        EditSnapshot {
            value: self.value.clone(),
            cursor: self.cursor,
            references: self.references.clone(),
        }
    }

    /// 统一计算逻辑换行和软折行后的光标位置，供垂直移动沿用渲染列宽。
    fn visual_positions(&self, width: usize) -> Vec<(usize, usize)> {
        let mut positions = Vec::new();
        let (mut row, mut column) = (0usize, 2usize);
        positions.push((row, column));
        for part in self.value.graphemes(true) {
            if part == "\n" {
                row += 1;
                column = 2;
            } else {
                let cells = unicode_width::UnicodeWidthStr::width(part).max(1);
                if column + cells > width {
                    row += 1;
                    column = 2;
                }
                column += cells;
            }
            positions.push((row, column));
        }
        positions
    }

    /// ASCII 连发达到保守阈值时短暂抑制紧随其后的回车发送，兼容无 bracketed-paste 终端。
    pub(crate) fn observe_plain_char(&mut self, ch: char, now: Instant) {
        self.paste_burst.observe(ch, now);
    }

    /// 处于 paste burst 窗口内的回车作为换行插入，并延长连续多行粘贴保护。
    pub(crate) fn consume_paste_newline(&mut self, now: Instant) -> bool {
        self.paste_burst.consume_newline(now)
    }

    /// 显式 bracketed paste 已完整框定边界，因此清理普通按键启发状态。
    pub(crate) fn reset_paste_burst(&mut self) {
        self.paste_burst.clear();
    }

    /// 按终端列宽预先换行，保证窄窗口中光标和输入文本使用相同几何规则。
    pub(crate) fn visual_lines(&self, width: usize) -> Vec<String> {
        let width = width.max(4);
        let mut lines = Vec::new();
        for (line_index, logical) in self.value.split('\n').enumerate() {
            let mut current = if line_index == 0 { "› " } else { "  " }.to_owned();
            let mut cells = 2usize;
            if logical.is_empty() {
                lines.push(current);
                continue;
            }
            for grapheme in logical.graphemes(true) {
                let cell_width = unicode_width::UnicodeWidthStr::width(grapheme).max(1);
                if cells + cell_width > width {
                    lines.push(current);
                    current = "  ".to_owned();
                    cells = 2;
                }
                current.push_str(grapheme);
                cells += cell_width;
            }
            lines.push(current);
        }
        if lines.is_empty() {
            lines.push("› ".to_owned());
        }
        lines
    }

    /// 以同一宽字符规则计算光标位置，避免中文文本和窄屏换行错位。
    pub(crate) fn visual_cursor(&self, width: usize) -> (usize, usize) {
        let width = width.max(4);
        let mut row = 0usize;
        let mut column = 2usize;
        for (index, grapheme) in self.value.graphemes(true).enumerate() {
            if index >= self.cursor {
                break;
            }
            if grapheme == "\n" {
                row += 1;
                column = 2;
                continue;
            }
            let cell_width = unicode_width::UnicodeWidthStr::width(grapheme).max(1);
            if column + cell_width > width {
                row += 1;
                column = 2;
            }
            column += cell_width;
        }
        (row, column.min(width.saturating_sub(1)))
    }

    /// 计算当前逻辑行的字素边界，Home/End 不扫描整个多行输入以外的位置。
    fn line_bounds(&self) -> (usize, usize) {
        let graphemes = self.value.graphemes(true).collect::<Vec<_>>();
        let start = graphemes[..self.cursor]
            .iter()
            .rposition(|part| *part == "\n")
            .map_or(0, |at| at + 1);
        let end = graphemes[self.cursor..]
            .iter()
            .position(|part| *part == "\n")
            .map_or(graphemes.len(), |at| self.cursor + at);
        (start, end)
    }
}

impl PasteBurst {
    /// 只观察高频 ASCII，避免中文 IME 输入被延迟或误判成粘贴。
    fn observe(&mut self, ch: char, now: Instant) {
        if !ch.is_ascii_graphic() && !ch.is_ascii_whitespace() {
            self.clear();
            return;
        }
        self.run_length = match self.last_ascii_char {
            Some(previous) if now.saturating_duration_since(previous) <= PASTE_BURST_GAP => {
                self.run_length.saturating_add(1)
            }
            _ => 1,
        };
        self.last_ascii_char = Some(now);
        if self.run_length >= 5 {
            self.active_until = now.checked_add(PASTE_BURST_GRACE);
        }
    }

    /// 有效期内的换行续长窗口；过期后回到普通 Enter 发送语义。
    fn consume_newline(&mut self, now: Instant) -> bool {
        if self.active_until.is_some_and(|until| now <= until) {
            self.active_until = now.checked_add(PASTE_BURST_GRACE);
            true
        } else {
            self.clear();
            false
        }
    }

    /// 清理时间戳和计数，显式粘贴与非 ASCII 输入不继承旧启发状态。
    fn clear(&mut self) {
        self.last_ascii_char = None;
        self.run_length = 0;
        self.active_until = None;
    }
}

/// 把字素偏移映射为 UTF-8 字节边界，集中处理插入和删除下标。
fn grapheme_byte_index(value: &str, offset: usize) -> usize {
    value
        .grapheme_indices(true)
        .nth(offset)
        .map_or(value.len(), |(index, _)| index)
}

/// 对撤销和重做使用同一个上界，长期输入不会无限保留完整草稿副本。
fn bounded_push(history: &mut VecDeque<EditSnapshot>, item: EditSnapshot) {
    if history.len() == MAX_UNDO_STEPS {
        history.pop_front();
    }
    history.push_back(item);
}

/// 词移动区分空白、文字与标点，Unicode 字母和汉字共用文字类别。
fn word_class(part: &str) -> u8 {
    if part.chars().all(char::is_whitespace) {
        0
    } else if part.chars().any(char::is_alphanumeric) {
        1
    } else {
        2
    }
}

/// 粘贴输入先剔除终端控制字符、归一化换行和 Tab，再按完整字素遵守容量上限。
fn safe_input_prefix(value: &str, max_bytes: usize) -> (String, bool) {
    let mut accepted = String::new();
    for grapheme in value.graphemes(true) {
        let mut safe = String::new();
        for ch in grapheme.chars() {
            match ch {
                '\n' => safe.push('\n'),
                '\r' => {}
                '\t' => safe.push_str("    "),
                _ if ch.is_control() => {}
                _ => safe.push(ch),
            }
        }
        if accepted.len().saturating_add(safe.len()) > max_bytes {
            return (accepted, false);
        }
        accepted.push_str(&safe);
    }
    (accepted, true)
}
