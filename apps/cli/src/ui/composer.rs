// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::collections::VecDeque;
use std::time::Instant;

use super::model::{UiReference, UiReferenceKind};
use super::paste_burst::{CharInput, ControlInput, FlushResult, PasteBurst};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

const MAX_DRAFT_BYTES: usize = 32 * 1024;
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

    /// 垂直移动沿用渲染器相同的内边距折行几何，短行往返时仍保留原显示列。
    pub fn move_vertical(&mut self, width: usize, down: bool) {
        let positions = self.visual_positions(width);
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

    /// 发送失败恢复完整引用和光标；等待期间新写的输入留在撤销栈，不能被恢复操作悄悄丢弃。
    pub(super) fn restore_submission(&mut self, submitted: Self) {
        let next_input = (!self.is_empty()).then(|| self.snapshot());
        *self = submitted;
        if let Some(next_input) = next_input {
            bounded_push(&mut self.undo, next_input);
            self.redo.clear();
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

    /// 与历史问题共用两列正文前缀和字素折行，保证上下移动与左缘对齐后的绘制一致。
    fn visual_positions(&self, width: usize) -> Vec<(usize, usize)> {
        let width = composer_content_width(width);
        let continuation_indent = width.min(2);
        let mut positions = Vec::new();
        let (mut row, mut column) = (0usize, continuation_indent);
        positions.push((row, column));
        for part in self.value.graphemes(true) {
            if part == "\n" {
                row += 1;
                column = continuation_indent;
            } else {
                let cells = unicode_width::UnicodeWidthStr::width(part).max(1);
                if column + cells > width {
                    row += 1;
                    column = continuation_indent;
                    if column + cells > width {
                        column = 0;
                    }
                }
                column += cells;
            }
            positions.push((row, column));
        }
        positions
    }

    /// 先结算已到期输入，再返回当前字符应立即插入、短暂保留或并入粘贴缓冲的决定。
    ///
    /// 复合结果让 controller 在输入延迟或 tick 与按键重合时仍能按原顺序提交文本；
    /// bridge 仍须定期调用 `flush_paste_burst`，使空闲时的单字符也能及时显示。
    /// @author kongweiguang
    pub(crate) fn observe_plain_char(&mut self, ch: char, now: Instant) -> CharInput {
        self.paste_burst.on_plain_char(ch, now)
    }

    /// 识别粘贴中的换行，并区分由 burst 延迟插入或立即插入的情况。
    /// @author kongweiguang
    pub(crate) fn consume_paste_newline(&mut self, now: Instant) -> ControlInput {
        self.paste_burst.append_control_char('\n', now)
    }

    /// 识别无 bracketed-paste 终端把 Tab 作为文本发送的情况，避免误触命令或提交动作。
    /// @author kongweiguang
    pub(crate) fn consume_paste_tab(&mut self, now: Instant) -> ControlInput {
        self.paste_burst.append_control_char('\t', now)
    }

    /// 由终端循环定期调用，释放超时的普通字符或把已完成 burst 交给一次原子插入。
    /// @author kongweiguang
    pub(crate) fn flush_paste_burst(&mut self, now: Instant) -> FlushResult {
        self.paste_burst.flush_if_due(now)
    }

    /// 在光标导航或快捷键前取回所有尚未插入的文本，避免输入丢失或越过编辑动作。
    /// @author kongweiguang
    pub(crate) fn flush_paste_burst_before_modified_input(&mut self) -> FlushResult {
        self.paste_burst.flush_before_modified_input()
    }

    /// 提供最早到期时间，让终端 poll 在字符释放或 paste 完成时唤醒。
    /// @author kongweiguang
    pub(crate) fn paste_burst_deadline(&self) -> Option<Instant> {
        self.paste_burst.next_deadline()
    }

    /// bracketed paste 本身已经提供完整边界；调用者先 flush 后可清理普通按键状态。
    /// @author kongweiguang
    pub(crate) fn reset_paste_burst(&mut self) {
        self.paste_burst.clear();
    }

    /// 草稿与已发送问题共用同一提示符和折行结果，发送前后不会改变正文起始列。
    pub(crate) fn visual_lines(&self, width: usize) -> Vec<String> {
        input_visual_lines(&self.value, width)
    }

    /// 光标按同一提示符后的可用列定位，且与多行绘制共享宽字符和折行规则。
    pub(crate) fn visual_cursor(&self, width: usize) -> (usize, usize) {
        let width = composer_content_width(width);
        let continuation_indent = width.min(2);
        let mut row = 0usize;
        let mut column = continuation_indent;
        for (index, grapheme) in self.value.graphemes(true).enumerate() {
            if index >= self.cursor {
                break;
            }
            if grapheme == "\n" {
                row += 1;
                column = continuation_indent;
                continue;
            }
            let cell_width = unicode_width::UnicodeWidthStr::width(grapheme).max(1);
            if column + cell_width > width {
                row += 1;
                column = continuation_indent;
                if column + cell_width > width {
                    column = 0;
                }
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

/// 提示符与历史消息左缘对齐，不另加外侧缩进；两列正文前缀由共同折行函数承担。
pub(crate) fn composer_content_width(terminal_width: usize) -> usize {
    terminal_width.max(1)
}

/// 已发送问题与草稿复用字素折行和续行缩进，避免灰底消息与编辑光标使用两套宽度。
pub(crate) fn input_visual_lines(value: &str, width: usize) -> Vec<String> {
    let width = composer_content_width(width);
    let continuation_indent = width.min(2);
    let prompt = if width >= 2 { "› " } else { "›" };
    let mut lines = Vec::new();
    for (line_index, logical) in value.split('\n').enumerate() {
        let mut current = if line_index == 0 {
            prompt.to_owned()
        } else {
            " ".repeat(continuation_indent)
        };
        let mut cells = continuation_indent;
        for grapheme in logical.graphemes(true) {
            let cell_width = UnicodeWidthStr::width(grapheme).max(1);
            if cells + cell_width > width {
                lines.push(current);
                current = " ".repeat(continuation_indent);
                cells = continuation_indent;
                if cells + cell_width > width {
                    current.clear();
                    cells = 0;
                }
            }
            current.push_str(grapheme);
            cells += cell_width;
        }
        lines.push(current);
    }
    lines
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
