// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::collections::HashMap;
use std::sync::Arc;

use crossterm::event::{MouseButton, MouseEvent, MouseEventKind};
use ratatui::Frame;
use ratatui::layout::{Alignment, Position, Rect};
use ratatui::style::{Color, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Clear, Paragraph};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

use super::model::{StreamPresentation, TimelineEntry, TimelineKind};

/// 主任务与稳定正文共用同一源坐标；UI 装饰行可保留可见文字而不伪造源偏移。
#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct TranscriptBlock {
    pub(super) key: String,
    pub(super) source_key: String,
    pub(super) source_start: usize,
    pub(super) source: String,
    pub(super) lines: Vec<Line<'static>>,
}

/// 字素位置同时保留显示列和源文本偏移，滚动锚点与复制都不依赖易漂移的屏幕行号。
#[derive(Clone, Debug)]
struct Glyph {
    text: String,
    style: Style,
    x_end: u16,
    copyable: bool,
    source: Option<SourceRange>,
}

/// Arc 让同一源消息的相邻字素共享原文，复制时只恢复实际存在的空白和换行。
#[derive(Clone, Debug)]
struct SourceRange {
    key: Arc<str>,
    text: Arc<str>,
    base: usize,
    start: usize,
    end: usize,
    local_start: usize,
    local_end: usize,
    display_part: usize,
}

/// 空行也保留独立行样式，软折行与源码换行可由字素偏移区分。
#[derive(Clone, Debug)]
struct VisualRow {
    style: Style,
    glyphs: Vec<Glyph>,
}

/// 缓存按语义区块持有，活跃流只替换变化区块，不触发稳定历史重新排版。
#[derive(Clone, Debug)]
struct CachedBlock {
    block: TranscriptBlock,
    rows: Vec<Arc<VisualRow>>,
}

/// 稳定条目保留原始模型对象供业务投影读取，渲染缓存独立于历史身份。
#[derive(Clone, Debug)]
struct StableEntry {
    entry: TimelineEntry,
    source_key: String,
    source_start: usize,
    rows: Vec<Arc<VisualRow>>,
}

/// 锚点同时保留源字节及相对视口行，前插和换行重排后不丢失顶部留白位置。
#[derive(Clone, Debug)]
struct Anchor {
    source_key: Arc<str>,
    byte_offset: usize,
    row_offset: usize,
}

/// 选区以行和字素索引表达，避免把双列字符或组合字符切成无效片段。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Cursor {
    row: usize,
    glyph: usize,
}

/// 选区端点优先保存源字节身份；仅对无法映射的显示字素使用当前布局代次。
#[derive(Clone, Debug, Eq, PartialEq)]
enum SelectionEndpoint {
    Source {
        key: Arc<str>,
        byte_offset: usize,
        display_part: usize,
    },
    Visual {
        cursor: Cursor,
        generation: u64,
    },
}

/// 补齐分片边界间的原文空白，片段按其在流源中的 UTF-8 字节起点索引。
#[derive(Clone, Debug)]
struct SourcePiece {
    start: usize,
    text: Arc<str>,
}

/// Transcript 只保存业务条目、有限的视口几何和区块排版缓存；终端 scrollback 不参与正文状态。
#[derive(Debug)]
pub(super) struct Transcript {
    stable: Vec<StableEntry>,
    stable_positions: HashMap<String, usize>,
    intro: Option<TranscriptBlock>,
    intro_cache: Option<CachedBlock>,
    active: Vec<CachedBlock>,
    rows: Vec<Arc<VisualRow>>,
    source_pieces: HashMap<String, Vec<SourcePiece>>,
    layout_generation: u64,
    stable_row_count: usize,
    intro_row_count: usize,
    width: Option<u16>,
    area: Rect,
    control: Rect,
    top: usize,
    follow_latest: bool,
    anchor: Option<Anchor>,
    has_new_activity: bool,
    reached_older_edge: bool,
    dragging: bool,
    selection: Option<(SelectionEndpoint, SelectionEndpoint)>,
    selection_cursors: Option<(Cursor, Cursor)>,
    next_copy_id: u64,
    pending_copy: Option<(u64, String)>,
}

impl Transcript {
    /// 追加只接受首次稳定身份；若快照修订同一 ID，则更新该对象而保留视口源锚点。
    pub(super) fn append(&mut self, entry: TimelineEntry, source_key: String, source_start: usize) {
        let existing = self.stable_positions.get(&entry.id).copied();
        let needs_relocation =
            existing.is_some() || self.follow_latest || self.anchor_is_in_active(&source_key);
        if let Some(index) = existing {
            if self.stable[index].entry == entry
                && self.stable[index].source_key == source_key
                && self.stable[index].source_start == source_start
            {
                return;
            }
            self.stable[index].entry = entry;
            self.stable[index].source_key = source_key;
            self.stable[index].source_start = source_start;
            self.stable[index].rows = self.width.map_or_else(Vec::new, |width| {
                stable_rows(
                    &self.stable[index].entry,
                    self.stable[index].source_key.clone(),
                    self.stable[index].source_start,
                    width,
                )
            });
            self.rebuild_rows();
        } else {
            let rows = self.width.map_or_else(Vec::new, |width| {
                stable_rows(&entry, source_key.clone(), source_start, width)
            });
            let id = entry.id.clone();
            self.stable.push(StableEntry {
                entry,
                source_key,
                source_start,
                rows,
            });
            let index = self.stable.len() - 1;
            self.stable_positions.insert(id, index);
            self.append_stable_rows(index);
        }
        self.reached_older_edge = false;
        self.notify_activity();
        if needs_relocation {
            self.restore_viewport();
        } else {
            self.top = self.top.min(self.max_top());
        }
        self.reconcile_selection();
    }

    /// 登记已消费的流原文，使跨多个稳定片段的复制能恢复源文本中的真实空白。
    pub(super) fn register_source(
        &mut self,
        source_key: String,
        source_start: usize,
        text: String,
    ) {
        if text.is_empty() {
            return;
        }
        let pieces = self.source_pieces.entry(source_key).or_default();
        if pieces
            .iter()
            .any(|piece| piece.start == source_start && piece.text.as_ref() == text)
        {
            return;
        }
        pieces.push(SourcePiece {
            start: source_start,
            text: Arc::from(text),
        });
        pieces.sort_by_key(|piece| piece.start);
    }

    /// 旧页按服务端顺序插入并以条目 ID 去重，前插不会重排已有的活动流后缀。
    pub(super) fn prepend(&mut self, entries: Vec<TimelineEntry>) {
        let mut additions = Vec::new();
        let mut page_ids = HashMap::new();
        for entry in entries {
            if self.stable_positions.contains_key(&entry.id)
                || page_ids.insert(entry.id.clone(), ()).is_some()
            {
                continue;
            }
            let rows = self.width.map_or_else(Vec::new, |width| {
                stable_rows(&entry, entry.id.clone(), 0, width)
            });
            additions.push(StableEntry {
                source_key: entry.id.clone(),
                source_start: 0,
                entry,
                rows,
            });
        }
        if !additions.is_empty() {
            self.stable.splice(0..0, additions);
            self.reindex_stable();
            self.rebuild_rows();
            self.reconcile_selection();
        }
        self.reached_older_edge = false;
        self.restore_viewport();
    }

    /// 清理会话内容与滚动缓存，避免旧 Thread 的迟到 UI 状态影响新会话。
    pub(super) fn clear(&mut self) {
        self.stable.clear();
        self.stable_positions.clear();
        self.source_pieces.clear();
        self.intro = None;
        self.intro_cache = None;
        self.active.clear();
        self.rows.clear();
        self.stable_row_count = 0;
        self.intro_row_count = 0;
        self.top = 0;
        self.follow_latest = true;
        self.anchor = None;
        self.has_new_activity = false;
        self.reached_older_edge = false;
        self.clear_selection();
    }

    /// 欢迎卡作为独立前导区块参与阅读锚点，但不混入 Thread 的稳定条目列表。
    pub(super) fn set_intro(&mut self, intro: Option<TranscriptBlock>) {
        if self.intro == intro {
            return;
        }
        self.intro = intro;
        self.intro_cache = self
            .intro
            .as_ref()
            .zip(self.width)
            .map(|(block, width)| cached_block(block.clone(), width));
        self.rebuild_rows();
        self.reconcile_selection();
        self.restore_viewport();
    }

    /// 只在宽度变化或缓存区块变化时重排；仅高度变化时复用源锚点调整可见窗口。
    pub(super) fn update_layout(
        &mut self,
        width: u16,
        area: Rect,
        control: Rect,
        active: Vec<TranscriptBlock>,
    ) {
        let width = width.max(1);
        let width_changed = self.width != Some(width);
        let active_changed = !same_blocks(&self.active, &active);
        let geometry_changed = self.area != area || self.control != control;
        let anchor_was_active = self.anchor.as_ref().is_some_and(|anchor| {
            self.active
                .iter()
                .any(|block| block.block.source_key == anchor.source_key.as_ref())
        });
        let anchor_moves_with_active = anchor_was_active
            || self.anchor.as_ref().is_some_and(|anchor| {
                active
                    .iter()
                    .any(|block| block.source_key == anchor.source_key.as_ref())
            });
        self.area = area;
        self.control = control;
        if width_changed {
            self.width = Some(width);
            self.intro_cache = self
                .intro
                .as_ref()
                .map(|block| cached_block(block.clone(), width));
            for item in &mut self.stable {
                item.rows = stable_rows(
                    &item.entry,
                    item.source_key.clone(),
                    item.source_start,
                    width,
                );
            }
        }
        if active_changed || width_changed {
            self.active = if width_changed {
                active
                    .into_iter()
                    .map(|block| cached_block(block, width))
                    .collect()
            } else {
                reconcile_active(std::mem::take(&mut self.active), active, width)
            };
        }
        if width_changed {
            self.rebuild_rows();
        } else if active_changed {
            self.refresh_active_suffix();
        }
        if active_changed || width_changed {
            self.reconcile_selection();
        }
        if geometry_changed
            || width_changed
            || self.follow_latest
            || (active_changed && anchor_moves_with_active)
        {
            self.restore_viewport();
        } else if active_changed {
            self.top = self.top.min(self.max_top());
        }
    }

    /// 正值向最新内容移动，负值向历史移动；到达顶部会保留分页请求信号供 State 合并。
    pub(super) fn scroll_by(&mut self, delta: isize) {
        if self.rows.is_empty() || self.area.height == 0 || delta == 0 {
            return;
        }
        let max_top = self.max_top();
        if delta < 0 {
            self.top = self.top.saturating_sub(delta.unsigned_abs());
            self.follow_latest = false;
        } else {
            self.top = self.top.saturating_add(delta as usize).min(max_top);
            self.follow_latest = self.top >= max_top;
        }
        if self.follow_latest {
            self.anchor = None;
            self.has_new_activity = false;
        } else {
            self.capture_anchor();
        }
        if delta < 0 && self.top == 0 {
            self.reached_older_edge = true;
        }
    }

    /// 按正文视口实际高度翻页，避免用固定行数造成不同窗口尺寸下的跳动。
    pub(super) fn page_by(&mut self, direction: isize) {
        let height = self.area.height.max(1) as isize;
        self.scroll_by(direction.saturating_mul(height));
    }

    /// 跳到首条已加载正文并记录源锚点；保留新内容提示，区分到达顶部与返回最新。
    pub(super) fn jump_top(&mut self) {
        self.top = 0;
        self.follow_latest = false;
        self.capture_anchor();
        self.reached_older_edge = true;
    }

    /// 返回最新正文同时清除新内容提示；输入草稿由外层 State 持有而不会被此操作改写。
    pub(super) fn follow_latest(&mut self) {
        self.follow_latest = true;
        self.top = self.max_top();
        self.anchor = None;
        self.has_new_activity = false;
        self.reached_older_edge = false;
    }

    /// 浏览期间只更新提示状态，新增行由源锚点保持在视口之外。
    pub(super) fn notify_activity(&mut self) {
        if !self.follow_latest {
            self.has_new_activity = true;
        }
    }

    /// 当前状态用于 State 把 Esc 优先解释为回到底部，再进入原有取消行为。
    pub(super) fn is_browsing(&self) -> bool {
        !self.follow_latest
    }

    /// 清掉选区和待复制快照，避免复制成功后的下一次 Ctrl+C 重放旧文本。
    pub(super) fn clear_selection(&mut self) {
        self.selection = None;
        self.selection_cursors = None;
        self.dragging = false;
        self.pending_copy = None;
    }

    /// Ctrl+C 只在有范围选区时由 State 拦截，光标或单点点击仍保留原始取消语义。
    pub(super) fn has_selection(&self) -> bool {
        self.resolved_selection()
            .is_some_and(|(start, end)| start != end)
    }

    /// 复制请求带稳定 ID，异步剪贴板失败时保留选区并允许调用方显示诊断。
    pub(super) fn selection_copy(&mut self) -> Option<(u64, String)> {
        if !self.has_selection() {
            return None;
        }
        if let Some(pending) = &self.pending_copy {
            return Some(pending.clone());
        }
        let text = self.selected_text();
        if text.is_empty() {
            return None;
        }
        self.next_copy_id = self.next_copy_id.wrapping_add(1).max(1);
        let pending = (self.next_copy_id, text);
        self.pending_copy = Some(pending.clone());
        Some(pending)
    }

    /// 仅匹配本次复制回执才清空选区并确认成功，迟到的旧结果不改写选择或反馈。
    pub(super) fn selection_copied(&mut self, id: u64) -> bool {
        if self
            .pending_copy
            .as_ref()
            .is_some_and(|pending| pending.0 == id)
        {
            self.clear_selection();
            true
        } else {
            false
        }
    }

    /// 仅当前复制请求失败时保留选区并释放请求快照，以新 ID 安全重试。
    pub(super) fn selection_copy_failed(&mut self, id: u64) -> bool {
        if self
            .pending_copy
            .as_ref()
            .is_some_and(|pending| pending.0 == id)
        {
            self.pending_copy = None;
            true
        } else {
            false
        }
    }

    /// 拖选松手先完成选择，不能把相邻提示行误当返回入口；普通点击才执行返回最新。
    pub(super) fn handle_mouse(&mut self, event: MouseEvent) -> bool {
        let position = Position::new(event.column, event.row);
        if self.is_browsing()
            && !self.dragging
            && !self.control.is_empty()
            && self.control.contains(position)
            && matches!(
                event.kind,
                MouseEventKind::Down(MouseButton::Left) | MouseEventKind::Up(MouseButton::Left)
            )
        {
            self.follow_latest();
            return true;
        }
        match event.kind {
            MouseEventKind::ScrollUp if self.area.contains(position) => {
                self.scroll_by(-3);
                true
            }
            MouseEventKind::ScrollDown if self.area.contains(position) => {
                self.scroll_by(3);
                true
            }
            MouseEventKind::Down(MouseButton::Left) if self.area.contains(position) => {
                let cursor = self.cursor_at(event.column, event.row);
                self.pending_copy = None;
                let endpoint = self.selection_endpoint(cursor);
                self.selection = Some((endpoint.clone(), endpoint));
                self.selection_cursors = Some((cursor, cursor));
                self.dragging = true;
                true
            }
            MouseEventKind::Drag(MouseButton::Left) if self.dragging => {
                let cursor = self.cursor_at_clamped(event.column, event.row);
                let endpoint = self.selection_endpoint(cursor);
                if let Some((start, _)) = &self.selection {
                    self.selection = Some((start.clone(), endpoint));
                    self.selection_cursors = self
                        .selection_cursors
                        .map(|(start_cursor, _)| (start_cursor, cursor));
                }
                true
            }
            MouseEventKind::Up(MouseButton::Left) if self.dragging => {
                let cursor = self.cursor_at_clamped(event.column, event.row);
                let endpoint = self.selection_endpoint(cursor);
                if let Some((start, _)) = &self.selection {
                    self.selection = Some((start.clone(), endpoint));
                    self.selection_cursors = self
                        .selection_cursors
                        .map(|(start_cursor, _)| (start_cursor, cursor));
                }
                self.dragging = false;
                true
            }
            _ => false,
        }
    }

    /// 触顶条件由 State 与服务端游标状态合并判断，Transcript 不推断是否仍有更早记录。
    pub(super) fn needs_older(&self) -> bool {
        self.reached_older_edge
    }

    /// 正文与控件共享外层给出的坐标，控件行始终清空并保留，即使当前处于跟随状态。
    pub(super) fn render(&self, frame: &mut Frame<'_>) {
        if !self.area.is_empty() {
            frame.render_widget(Clear, self.area);
            let height = usize::from(self.area.height);
            let end = self.top.saturating_add(height).min(self.rows.len());
            let selection = self.resolved_selection();
            for (visible, row_index) in (self.top..end).enumerate() {
                let row = &self.rows[row_index];
                let line = visible_line(row, row_index, selection);
                let target = Rect::new(
                    self.area.x,
                    self.area.y.saturating_add(visible as u16),
                    self.area.width,
                    1,
                );
                frame.render_widget(line, target);
            }
        }
        if !self.control.is_empty() {
            frame.render_widget(Clear, self.control);
            if self.is_browsing() {
                let label = if self.has_new_activity {
                    match self.control.width {
                        30.. => "有新内容 · ↓ 回到底部 · Esc",
                        17..=29 => "新内容 · ↓ 返回 · Esc",
                        9..=16 => "↓ 返回 · Esc",
                        _ => "↓",
                    }
                } else {
                    match self.control.width {
                        20.. => "↓ 回到底部 · Esc",
                        9..=19 => "↓ 返回 · Esc",
                        _ => "↓",
                    }
                };
                let label = truncate_cells(label, usize::from(self.control.width));
                frame.render_widget(
                    Paragraph::new(Line::from(Span::styled(
                        label,
                        Style::default().fg(Color::Blue),
                    )))
                    .alignment(Alignment::Center),
                    self.control,
                );
            }
        }
    }

    /// 以最新区域高度限制滚动范围，矮窗中不保留已离开可滚动内容的空偏移。
    fn max_top(&self) -> usize {
        self.rows
            .len()
            .saturating_sub(usize::from(self.area.height))
    }

    /// 只有当前锚点落在被提交或重排的活动流中才需搜索重定位；稳定旧页不会随尾部增长移动。
    fn anchor_is_in_active(&self, additional_source_key: &str) -> bool {
        self.anchor.as_ref().is_some_and(|anchor| {
            anchor.source_key.as_ref() == additional_source_key
                || self
                    .active
                    .iter()
                    .any(|block| block.block.source_key == anchor.source_key.as_ref())
        })
    }

    /// 活动内容替换后重组轻量 Arc 索引，稳定正文排版缓存本身不会复制或重新渲染。
    fn rebuild_rows(&mut self) {
        let mut rows = Vec::new();
        if let Some(intro) = &self.intro_cache {
            self.intro_row_count = intro.rows.len();
            rows.extend(intro.rows.iter().cloned());
        } else {
            self.intro_row_count = 0;
        }
        self.stable_row_count = 0;
        for item in &self.stable {
            self.stable_row_count = self.stable_row_count.saturating_add(item.rows.len());
            rows.extend(item.rows.iter().cloned());
        }
        for block in &self.active {
            rows.extend(block.rows.iter().cloned());
        }
        self.rows = rows;
        self.bump_layout_generation();
    }

    /// 新稳定片段插在 active suffix 之前，只复制 suffix 行指针而不重建整段历史索引。
    fn append_stable_rows(&mut self, index: usize) {
        if index + 1 != self.stable.len() {
            self.rebuild_rows();
            return;
        }
        let stable_end = self.intro_row_count.saturating_add(self.stable_row_count);
        self.rows.truncate(stable_end);
        let rows = &self.stable[index].rows;
        self.stable_row_count = self.stable_row_count.saturating_add(rows.len());
        self.rows.extend(rows.iter().cloned());
        for block in &self.active {
            self.rows.extend(block.rows.iter().cloned());
        }
        self.bump_layout_generation();
    }

    /// 活动流变化只替换稳定正文之后的短后缀，长历史的 row index 保持原地址和顺序。
    fn refresh_active_suffix(&mut self) {
        let prefix = self.intro_row_count.saturating_add(self.stable_row_count);
        self.rows.truncate(prefix);
        for block in &self.active {
            self.rows.extend(block.rows.iter().cloned());
        }
        self.bump_layout_generation();
    }

    /// 每次可见行身份变化都使非源映射端点失效，源端点则可在新布局中重新定位。
    fn bump_layout_generation(&mut self) {
        self.layout_generation = self.layout_generation.wrapping_add(1);
    }

    /// 旧页前插会移动全部稳定索引，仅在分页完成时重建身份到位置的映射。
    fn reindex_stable(&mut self) {
        self.stable_positions.clear();
        for (index, entry) in self.stable.iter().enumerate() {
            self.stable_positions.insert(entry.entry.id.clone(), index);
        }
    }

    /// 浏览位置优先按源身份还原；缺失的活动片段结算时退到最近可见源位置。
    fn restore_viewport(&mut self) {
        if self.follow_latest {
            self.top = self.max_top();
            self.anchor = None;
            return;
        }
        if let Some(anchor) = &self.anchor {
            let current_anchor_row = self.top.saturating_add(anchor.row_offset);
            let anchor_is_still_visible = self.rows.get(current_anchor_row).is_some_and(|row| {
                row.glyphs.iter().any(|glyph| {
                    glyph.source.as_ref().is_some_and(|source| {
                        source.key == anchor.source_key && source.start == anchor.byte_offset
                    })
                })
            });
            if anchor_is_still_visible {
                self.top = self.top.min(self.max_top());
                self.capture_anchor();
                return;
            }
            let located = self.rows.iter().enumerate().find_map(|(row_index, row)| {
                row.glyphs
                    .iter()
                    .any(|glyph| {
                        glyph.source.as_ref().is_some_and(|source| {
                            source.key == anchor.source_key && source.end > anchor.byte_offset
                        })
                    })
                    .then_some(row_index)
            });
            if let Some(row) = located {
                self.top = row.saturating_sub(anchor.row_offset);
            }
        }
        self.top = self.top.min(self.max_top());
        self.capture_anchor();
    }

    /// 锚点采用视口首个可寻址字素，Decorative 行不会让缩放把用户带到另一条消息。
    fn capture_anchor(&mut self) {
        let end = self.top.saturating_add(usize::from(self.area.height));
        self.anchor = self.rows[self.top.min(self.rows.len())..end.min(self.rows.len())]
            .iter()
            .enumerate()
            .flat_map(|(row_offset, row)| row.glyphs.iter().map(move |glyph| (row_offset, glyph)))
            .find_map(|(row_offset, glyph)| {
                glyph.source.as_ref().map(|source| Anchor {
                    source_key: Arc::clone(&source.key),
                    byte_offset: source.start,
                    row_offset,
                })
            });
    }

    /// 鼠标点映射到整字素边界，宽字符中任意列都选中完整字符。
    fn cursor_at(&self, x: u16, y: u16) -> Cursor {
        if self.rows.is_empty() {
            return Cursor { row: 0, glyph: 0 };
        }
        let row = usize::from(y.saturating_sub(self.area.y))
            .saturating_add(self.top)
            .min(self.rows.len() - 1);
        let column = x.saturating_sub(self.area.x);
        let glyph = self.rows[row]
            .glyphs
            .iter()
            .position(|glyph| column < glyph.x_end)
            .unwrap_or_else(|| self.rows[row].glyphs.len().saturating_sub(1));
        Cursor { row, glyph }
    }

    /// 拖选越过正文边缘时钳制到视口边界，使长选区可以从可见区域开始或结束。
    fn cursor_at_clamped(&self, x: u16, y: u16) -> Cursor {
        let max_x = self
            .area
            .x
            .saturating_add(self.area.width.saturating_sub(1));
        let max_y = self
            .area
            .y
            .saturating_add(self.area.height.saturating_sub(1));
        self.cursor_at(x.clamp(self.area.x, max_x), y.clamp(self.area.y, max_y))
    }

    /// 把当前字素转换成稳定源端点，装饰性可见文字只在本次布局生命周期内可选。
    fn selection_endpoint(&self, cursor: Cursor) -> SelectionEndpoint {
        self.rows
            .get(cursor.row)
            .and_then(|row| row.glyphs.get(cursor.glyph))
            .and_then(|glyph| glyph.source.as_ref())
            .map_or(
                SelectionEndpoint::Visual {
                    cursor,
                    generation: self.layout_generation,
                },
                |source| SelectionEndpoint::Source {
                    key: Arc::clone(&source.key),
                    byte_offset: source.start,
                    display_part: source.display_part,
                },
            )
    }

    /// 先核对缓存位置的源身份；稳定选区只需常数查找，前插或重排失配时才扫描定位。
    fn resolve_endpoint(
        &self,
        endpoint: &SelectionEndpoint,
        hint: Option<Cursor>,
    ) -> Option<Cursor> {
        if let SelectionEndpoint::Source {
            key,
            byte_offset,
            display_part,
        } = endpoint
            && let Some(cursor) = hint
            && self
                .rows
                .get(cursor.row)
                .and_then(|row| row.glyphs.get(cursor.glyph))
                .and_then(|glyph| glyph.source.as_ref())
                .is_some_and(|source| {
                    source.key.as_ref() == key.as_ref()
                        && source.start == *byte_offset
                        && source.display_part == *display_part
                })
        {
            return Some(cursor);
        }
        match endpoint {
            SelectionEndpoint::Source {
                key,
                byte_offset,
                display_part,
            } => self.rows.iter().enumerate().find_map(|(row_index, row)| {
                row.glyphs
                    .iter()
                    .position(|glyph| {
                        glyph.source.as_ref().is_some_and(|source| {
                            source.key.as_ref() == key.as_ref()
                                && source.start == *byte_offset
                                && source.display_part == *display_part
                        })
                    })
                    .map(|glyph| Cursor {
                        row: row_index,
                        glyph,
                    })
            }),
            SelectionEndpoint::Visual { cursor, generation }
                if *generation == self.layout_generation
                    && self
                        .rows
                        .get(cursor.row)
                        .is_some_and(|row| cursor.glyph < row.glyphs.len()) =>
            {
                Some(*cursor)
            }
            SelectionEndpoint::Visual { .. } => None,
        }
    }

    /// 布局只重定位选区，保留同一复制请求的回执；无法定位时才清除悬空范围。
    fn reconcile_selection(&mut self) {
        if self.selection.is_none() {
            return;
        }
        match self.resolved_selection() {
            Some(cursors) => self.selection_cursors = Some(cursors),
            None => self.clear_selection(),
        }
    }

    /// 将可持久化的两个源端点映射回光标，绘制和复制共用同一范围解释。
    fn resolved_selection(&self) -> Option<(Cursor, Cursor)> {
        let (start, end) = self.selection.as_ref()?;
        Some((
            self.resolve_endpoint(start, self.selection_cursors.map(|cursors| cursors.0))?,
            self.resolve_endpoint(end, self.selection_cursors.map(|cursors| cursors.1))?,
        ))
    }

    /// 复制还原源 UTF-8 与可读排版字素；Tab 只输出一次，链接的显示分隔也不能丢失。
    fn selected_text(&self) -> String {
        let Some((start, end)) = self.resolved_selection() else {
            return String::new();
        };
        let (start, end) = ordered_cursors(start, end);
        let mut output = String::new();
        let mut previous: Option<&Glyph> = None;
        for row_index in start.row..=end.row.min(self.rows.len().saturating_sub(1)) {
            let row = &self.rows[row_index];
            let first = if row_index == start.row {
                start.glyph
            } else {
                0
            };
            let last = if row_index == end.row {
                end.glyph.min(row.glyphs.len().saturating_sub(1))
            } else {
                row.glyphs.len().saturating_sub(1)
            };
            if row.glyphs.is_empty() || first > last {
                continue;
            }
            for glyph in &row.glyphs[first..=last] {
                if !glyph.copyable {
                    continue;
                }
                let Some(current_source) = glyph.source.as_ref() else {
                    output.push_str(&glyph.text);
                    previous = None;
                    continue;
                };
                if previous
                    .and_then(|glyph| glyph.source.as_ref())
                    .is_some_and(|previous_source| {
                        same_source_range(previous_source, current_source)
                    })
                {
                    continue;
                }
                if let Some(previous_glyph) = previous
                    && let Some(previous_source) = previous_glyph.source.as_ref()
                    && let Some(gap) = self.source_gap(previous_source, current_source)
                {
                    output.push_str(&rendered_source_gap(&gap));
                }
                if let Some(text) = current_source
                    .text
                    .get(current_source.local_start..current_source.local_end)
                {
                    output.push_str(text);
                }
                previous = Some(glyph);
            }
        }
        output
    }

    /// 跨消息使用语义段落间隔，同一流源从局部或登记片段恢复原始 Markdown 间隙。
    fn source_gap(&self, previous: &SourceRange, next: &SourceRange) -> Option<String> {
        if previous.key != next.key {
            return Some("\n\n".to_owned());
        }
        source_content_gap(previous, next).or_else(|| {
            registered_source_gap(
                self.source_pieces.get(previous.key.as_ref())?,
                previous.end,
                next.start,
            )
        })
    }
}

impl Default for Transcript {
    /// 新会话从最新位置开始跟随，空的历史列表不能被误认为正在浏览。
    fn default() -> Self {
        Self {
            stable: Vec::new(),
            stable_positions: HashMap::new(),
            intro: None,
            intro_cache: None,
            active: Vec::new(),
            rows: Vec::new(),
            source_pieces: HashMap::new(),
            layout_generation: 0,
            stable_row_count: 0,
            intro_row_count: 0,
            width: None,
            area: Rect::default(),
            control: Rect::default(),
            top: 0,
            follow_latest: true,
            anchor: None,
            has_new_activity: false,
            reached_older_edge: false,
            dragging: false,
            selection: None,
            selection_cursors: None,
            next_copy_id: 0,
            pending_copy: None,
        }
    }
}

/// 稳定条目复用主 render 的语义渲染，避免 Transcript 自行实现 Markdown 和工具样式。
fn stable_rows(
    entry: &TimelineEntry,
    source_key: String,
    source_start: usize,
    width: u16,
) -> Vec<Arc<VisualRow>> {
    let copyable = !matches!(
        &entry.kind,
        TimelineKind::AssistantFragment {
            presentation: StreamPresentation::CodeLabel,
            ..
        }
    );
    let block = TranscriptBlock {
        key: entry.id.clone(),
        source_key,
        source_start,
        source: entry.text.clone(),
        lines: super::render::transcript_entry_lines(entry, usize::from(width)),
    };
    render_block_lines_with_copy_policy(&block, width, copyable)
}

/// 区块 identity、源坐标或渲染行任一变化才重排 active suffix。
fn same_blocks(old: &[CachedBlock], new: &[TranscriptBlock]) -> bool {
    old.len() == new.len()
        && old
            .iter()
            .zip(new)
            .all(|(cached, block)| cached.block == *block)
}

/// 保留内容未变的 active 区块缓存，只对新片段或发生增量的片段生成显示字素索引。
fn reconcile_active(
    old: Vec<CachedBlock>,
    new: Vec<TranscriptBlock>,
    width: u16,
) -> Vec<CachedBlock> {
    let mut old = old
        .into_iter()
        .map(|block| (block.block.key.clone(), block))
        .collect::<HashMap<_, _>>();
    new.into_iter()
        .map(|block| match old.remove(&block.key) {
            Some(cached) if cached.block == block => cached,
            _ => cached_block(block, width),
        })
        .collect()
}

/// 将主任务渲染好的内容排成字素行，并缓存源范围供锚点和复制使用。
fn cached_block(block: TranscriptBlock, width: u16) -> CachedBlock {
    let rows = render_block_lines(&block, width);
    CachedBlock { block, rows }
}

/// 跨 Span 的源码搜索单调前进；无法映射的装饰字素仍保留自身显示文字。
fn render_block_lines(block: &TranscriptBlock, width: u16) -> Vec<Arc<VisualRow>> {
    render_block_lines_with_copy_policy(block, width, true)
}

/// 代码语言标记仍参加源锚定，但复制只输出真实代码正文而不把标签混入剪贴板。
fn render_block_lines_with_copy_policy(
    block: &TranscriptBlock,
    width: u16,
    copyable: bool,
) -> Vec<Arc<VisualRow>> {
    let width = width.max(1);
    let source: Arc<str> = Arc::from(block.source.as_str());
    let source_key: Arc<str> = Arc::from(block.source_key.as_str());
    let source_text = block.source.as_str();
    let mut source_cursor = 0usize;
    let mut expanded_tab: Option<(usize, usize, usize, usize)> = None;
    let mut rows = Vec::new();
    for (line_index, line) in block.lines.iter().enumerate() {
        let generated_prefix = generated_prefix_width(
            line,
            source_text,
            source_cursor,
            width,
            line_index,
            block.lines.len(),
        );
        let code_label = is_code_label_line(line);
        if code_label {
            skip_code_fence_header(source_text, &mut source_cursor);
            expanded_tab = None;
        }
        let mut row = VisualRow {
            style: line.style,
            glyphs: Vec::new(),
        };
        let mut column = 0u16;
        let mut line_column = 0usize;
        for span in &line.spans {
            for grapheme in span.content.graphemes(true) {
                let glyph_width = UnicodeWidthStr::width(grapheme).max(1);
                if column > 0
                    && usize::from(column).saturating_add(glyph_width) > usize::from(width)
                {
                    rows.push(Arc::new(row));
                    row = VisualRow {
                        style: line.style,
                        glyphs: Vec::new(),
                    };
                    column = 0;
                }
                let mut mapped = None;
                if copyable && !code_label && line_column >= generated_prefix {
                    if let Some((tab_start, tab_end, tab_width, next_part)) = expanded_tab {
                        if grapheme == " " && next_part < tab_width {
                            mapped = Some(source_range(
                                &source_key,
                                &source,
                                block.source_start,
                                tab_start,
                                tab_end,
                                next_part,
                            ));
                            if next_part + 1 == tab_width {
                                source_cursor = tab_end;
                                expanded_tab = None;
                            } else {
                                expanded_tab = Some((tab_start, tab_end, tab_width, next_part + 1));
                            }
                        } else {
                            source_cursor = tab_end;
                            expanded_tab = None;
                        }
                    }
                    if mapped.is_none() {
                        if grapheme == " "
                            && source_text
                                .get(source_cursor..)
                                .is_some_and(|tail| tail.starts_with('\t'))
                        {
                            let tab_end = source_cursor + '\t'.len_utf8();
                            let tab_width = tab_expansion_width(source_text, source_cursor);
                            mapped = Some(source_range(
                                &source_key,
                                &source,
                                block.source_start,
                                source_cursor,
                                tab_end,
                                0,
                            ));
                            if tab_width <= 1 {
                                source_cursor = tab_end;
                            } else {
                                expanded_tab = Some((source_cursor, tab_end, tab_width, 1));
                            }
                        } else if let Some((local_start, local_end)) =
                            find_source_grapheme(source_text, source_cursor, grapheme)
                        {
                            source_cursor = local_end;
                            mapped = Some(source_range(
                                &source_key,
                                &source,
                                block.source_start,
                                local_start,
                                local_end,
                                0,
                            ));
                        }
                    }
                }
                let cell_width = glyph_width.min(usize::from(width)).max(1) as u16;
                row.glyphs.push(Glyph {
                    text: grapheme.to_owned(),
                    style: span.style,
                    x_end: column.saturating_add(cell_width),
                    copyable: copyable && !code_label && line_column >= generated_prefix,
                    source: mapped,
                });
                column = column.saturating_add(cell_width);
                line_column = line_column.saturating_add(glyph_width);
            }
        }
        rows.push(Arc::new(row));
    }
    rows
}

/// 以浅拷贝共享区块身份与原文，避免每个显示字素各自分配源键字符串。
fn source_range(
    key: &Arc<str>,
    text: &Arc<str>,
    base: usize,
    local_start: usize,
    local_end: usize,
    display_part: usize,
) -> SourceRange {
    SourceRange {
        key: Arc::clone(key),
        text: Arc::clone(text),
        base,
        start: base.saturating_add(local_start),
        end: base.saturating_add(local_end),
        local_start,
        local_end,
        display_part,
    }
}

/// 源搜索只可越过空白或明确的 Markdown 语法，不能跨正文字符误认后续同形字素。
fn find_source_grapheme(source: &str, cursor: usize, grapheme: &str) -> Option<(usize, usize)> {
    let remaining = source.get(cursor..)?;
    remaining
        .match_indices(grapheme)
        .find(|(relative, _)| source_prefix_is_render_syntax(&remaining[..*relative]))
        .map(|(relative, matched)| {
            let start = cursor.saturating_add(relative);
            (start, start.saturating_add(matched.len()))
        })
}

/// 只越过已被 Markdown renderer 消去的分隔符、列表标记和行间空白。
fn source_prefix_is_render_syntax(prefix: &str) -> bool {
    let mut cursor = 0usize;
    while cursor < prefix.len() {
        let Some(character) = prefix[cursor..].chars().next() else {
            return false;
        };
        if character.is_whitespace() {
            cursor += character.len_utf8();
            continue;
        }
        let remaining = &prefix[cursor..];
        if remaining.starts_with("[x]")
            || remaining.starts_with("[X]")
            || remaining.starts_with("[ ]")
        {
            cursor += 3;
            continue;
        }
        if character.is_ascii_digit() {
            let digits = remaining.bytes().take_while(u8::is_ascii_digit).count();
            let suffix = &remaining[digits..];
            if suffix.starts_with(". ") || suffix.starts_with(") ") {
                cursor += digits + 2;
                continue;
            }
            return false;
        }
        if matches!(
            character,
            '*' | '_'
                | '`'
                | '~'
                | '#'
                | '>'
                | '-'
                | '+'
                | '['
                | ']'
                | '('
                | ')'
                | '|'
                | '!'
                | '\\'
        ) {
            cursor += character.len_utf8();
            continue;
        }
        return false;
    }
    true
}

/// 计算固定四列制表位对应的展开宽度，与共享代码 renderer 的列语义保持一致。
fn tab_expansion_width(source: &str, tab_offset: usize) -> usize {
    let prefix = source
        .get(..tab_offset)
        .unwrap_or_default()
        .rsplit('\n')
        .next()
        .unwrap_or_default();
    let mut column = 0usize;
    for grapheme in prefix.graphemes(true) {
        if grapheme == "\t" {
            column = column.saturating_add(4 - column % 4);
        } else {
            column = column.saturating_add(UnicodeWidthStr::width(grapheme));
        }
    }
    4 - column % 4
}

/// 标出外层两列边距及 Markdown 列表、引用标记，保留字形绘制但不映射到原文。
fn generated_prefix_width(
    line: &Line<'_>,
    source_text: &str,
    source_cursor: usize,
    width: u16,
    line_index: usize,
    line_count: usize,
) -> usize {
    let rendered = line
        .spans
        .iter()
        .map(|span| span.content.as_ref())
        .collect::<String>();

    // 用户问题的灰底样式区分提示符、续行缩进和横向填充，避免误删原文的前导空格。
    if line.style.bg.is_some() {
        let is_outer_padding = rendered.chars().all(char::is_whitespace)
            && (line_index == 1 || line_index.saturating_add(1) == line_count);
        if is_outer_padding {
            return UnicodeWidthStr::width(rendered.as_str());
        }
        if rendered.starts_with("› ") {
            return 2;
        }
        if rendered.starts_with('›') {
            return 1;
        }
        return rendered
            .chars()
            .take(usize::from(width.min(2)))
            .take_while(|character| *character == ' ')
            .count();
    }

    let mut outer_width = 0usize;
    let mut remaining = rendered.as_str();
    if line
        .spans
        .first()
        .is_some_and(|span| span.content.as_ref() == "  ")
    {
        outer_width = 2;
        remaining = &remaining[2..];
    } else if line.spans.len() > 1
        && line.spans.first().is_some_and(|span| {
            span.content.as_ref().starts_with("  ")
                && !source_text
                    .get(source_cursor..)
                    .is_some_and(|source| source.starts_with(span.content.as_ref()))
        })
    {
        let first_span = line.spans[0].content.as_ref();
        let marker = first_span.trim_start_matches(' ');
        let marker_width = ["• ", "◦ ", "▪ ", "‣ "]
            .iter()
            .find(|candidate| marker.starts_with(**candidate))
            .map_or(0, |candidate| UnicodeWidthStr::width(*candidate));
        if marker_width > 0 {
            outer_width = 2usize.saturating_add(marker_width);
            remaining = &remaining[first_span.len()..];
        }
    }
    let indentation = remaining.bytes().take_while(|byte| *byte == b' ').count();
    let marker = &remaining[indentation..];
    if marker.starts_with("│ ") && source_line_has_render_marker(source_text, source_cursor, '>')
    {
        return outer_width.saturating_add(indentation).saturating_add(2);
    }
    let list_marker = ["• ", "◦ ", "▪ ", "‣ "]
        .iter()
        .find(|candidate| marker.starts_with(**candidate))
        .copied();
    let marker_width = if let Some(list_marker) = list_marker
        && source_line_has_list_marker(source_text, source_cursor)
    {
        let marker_tail = &marker[list_marker.len()..];
        UnicodeWidthStr::width(list_marker)
            + if marker_tail.starts_with("[x] ")
                || marker_tail.starts_with("[X] ")
                || marker_tail.starts_with("[ ] ")
            {
                4
            } else {
                0
            }
    } else {
        let digits = marker.bytes().take_while(u8::is_ascii_digit).count();
        if digits > 0
            && source_line_has_list_marker(source_text, source_cursor)
            && (marker[digits..].starts_with(". ") || marker[digits..].starts_with(") "))
        {
            let marker_end = digits + 2;
            digits
                + 2
                + if marker[marker_end..].starts_with("[x] ")
                    || marker[marker_end..].starts_with("[X] ")
                    || marker[marker_end..].starts_with("[ ] ")
                {
                    4
                } else {
                    0
                }
        } else {
            0
        }
    };
    if marker_width > 0 {
        outer_width
            .saturating_add(indentation)
            .saturating_add(marker_width)
    } else {
        outer_width
    }
}

/// 只有原文确实含列表语法时才略过绘制标记，普通正文中的相同字形仍可复制。
fn source_line_has_list_marker(source: &str, cursor: usize) -> bool {
    let Some(line) = source
        .get(cursor..)
        .and_then(|rest| rest.split('\n').find(|line| !line.trim().is_empty()))
    else {
        return false;
    };
    let line = line.trim_start();
    if line.starts_with("- ") || line.starts_with("* ") || line.starts_with("+ ") {
        return true;
    }
    if line.starts_with("- [ ] ") || line.starts_with("- [x] ") || line.starts_with("- [X] ") {
        return true;
    }
    let digits = line.bytes().take_while(u8::is_ascii_digit).count();
    digits > 0 && (line[digits..].starts_with(". ") || line[digits..].starts_with(") "))
}

/// 引用前缀只在源码具有引用语法时跳过，正文字符 │ 空格不会被误判为装饰。
fn source_line_has_render_marker(source: &str, cursor: usize, marker: char) -> bool {
    let Some(line) = source
        .get(cursor..)
        .and_then(|rest| rest.split('\n').find(|line| !line.trim().is_empty()))
    else {
        return false;
    };
    let line = line.trim_start();
    line.starts_with(marker)
        && line
            .get(marker.len_utf8()..)
            .is_some_and(|tail| tail.starts_with(' '))
}

/// 识别语言标题行，后续代码正文仍可沿源码游标映射且标题字形永不进入复制结果。
fn is_code_label_line(line: &Line<'_>) -> bool {
    let rendered = line
        .spans
        .iter()
        .map(|span| span.content.as_ref())
        .collect::<String>();
    rendered.trim_start().starts_with("╭─ ")
}

/// 稳定 Markdown 代码标题对应源围栏及语言行，跳过该语法头而让下一显示行锚定代码首字。
fn skip_code_fence_header(source: &str, cursor: &mut usize) {
    let Some(remaining) = source.get(*cursor..) else {
        return;
    };
    let mut offset = 0usize;
    for line in remaining.split_inclusive('\n') {
        let content = line.trim_end_matches('\n').trim_end_matches('\r');
        let trimmed = content.trim_start();
        if trimmed.starts_with("```") || trimmed.starts_with("~~~") {
            *cursor = cursor.saturating_add(offset).saturating_add(line.len());
            return;
        }
        if !content.trim().is_empty() {
            return;
        }
        offset = offset.saturating_add(line.len());
    }
}

/// 多个 Tab 展示列共享同一源范围，比较范围身份以便复制时只还原一个原始制表符。
fn same_source_range(left: &SourceRange, right: &SourceRange) -> bool {
    left.key == right.key
        && left.start == right.start
        && left.end == right.end
        && left.local_start == right.local_start
        && left.local_end == right.local_end
}

/// 跨稳定片段恢复原始字节间隙；展示层再剥离 Markdown 装饰而保留换行。
fn source_content_gap(previous: &SourceRange, next: &SourceRange) -> Option<String> {
    if previous.key != next.key || previous.end > next.start {
        return None;
    }
    let gap_len = next.start.saturating_sub(previous.end);
    if previous.base == next.base && previous.text == next.text {
        if previous.local_end > next.local_start {
            return None;
        }
        let gap = &previous.text[previous.local_end..next.local_start];
        return Some(gap.to_owned());
    }
    let previous_piece_end = previous.base.saturating_add(previous.text.len());
    if previous_piece_end != next.base {
        return None;
    }
    let mut gap = String::new();
    gap.push_str(&previous.text[previous.local_end..]);
    gap.push_str(&next.text[..next.local_start]);
    (gap.len() == gap_len).then_some(gap)
}

/// 从登记流片段拼接完整字节范围，不把 Markdown 标记误当正文或丢弃真实换行。
fn registered_source_gap(pieces: &[SourcePiece], start: usize, end: usize) -> Option<String> {
    if start > end {
        return None;
    }
    let mut cursor = start;
    let mut gap = String::new();
    while cursor < end {
        let piece = pieces
            .iter()
            .filter(|piece| piece.start <= cursor)
            .filter(|piece| piece.start.saturating_add(piece.text.len()) > cursor)
            .max_by_key(|piece| piece.start.saturating_add(piece.text.len()))?;
        let piece_end = piece.start.saturating_add(piece.text.len());
        let next_cursor = end.min(piece_end);
        let local_start = cursor.saturating_sub(piece.start);
        let local_end = next_cursor.saturating_sub(piece.start);
        let text = piece.text.get(local_start..local_end)?;
        gap.push_str(text);
        cursor = next_cursor;
    }
    Some(gap)
}

/// 复制使用渲染可读的段落分隔，移除 Markdown 行首标记并保留软折行的空间语义。
fn rendered_source_gap(gap: &str) -> String {
    let mut output = String::new();
    for line in gap.split_inclusive('\n') {
        let (content, ending) = line.strip_suffix('\n').map_or((line, ""), |content| {
            if let Some(content) = content.strip_suffix('\r') {
                (content, "\r\n")
            } else {
                (content, "\n")
            }
        });
        output.push_str(&rendered_source_gap_line(content));
        output.push_str(ending);
    }
    output
}

/// 未映射的间隙只补真实空白，链接目标等隐藏语法不能凭源码间隙混入选区正文。
fn rendered_source_gap_line(line: &str) -> String {
    if line.chars().all(char::is_whitespace) {
        return line.to_owned();
    }
    if markdown_block_prefix_width(line) > 0 {
        return String::new();
    }
    line.chars()
        .filter(|character| character.is_whitespace())
        .collect()
}

/// 识别只在行首生效的标题、列表与引用语法，避免把分隔符后的正文空格复制出来。
fn markdown_block_prefix_width(line: &str) -> usize {
    let trimmed = line.trim_start();
    let indentation = line.len().saturating_sub(trimmed.len());
    let bytes = trimmed.as_bytes();
    if bytes.first() == Some(&b'#') {
        let hashes = bytes.iter().take_while(|byte| **byte == b'#').count();
        if (1..=6).contains(&hashes) && trimmed.as_bytes().get(hashes) == Some(&b' ') {
            return indentation.saturating_add(hashes + 1);
        }
    }
    if trimmed.starts_with("> ") {
        return indentation.saturating_add(2);
    }
    if trimmed.starts_with("- ")
        || trimmed.starts_with("* ")
        || trimmed.starts_with("+ ")
        || trimmed.starts_with("- [ ] ")
        || trimmed.starts_with("- [x] ")
        || trimmed.starts_with("- [X] ")
    {
        let marker_width = if trimmed.starts_with("- [ ] ")
            || trimmed.starts_with("- [x] ")
            || trimmed.starts_with("- [X] ")
        {
            6
        } else {
            2
        };
        return indentation.saturating_add(marker_width);
    }
    let digits = bytes
        .iter()
        .take_while(|byte| byte.is_ascii_digit())
        .count();
    if digits > 0 && (trimmed[digits..].starts_with(". ") || trimmed[digits..].starts_with(") ")) {
        return indentation.saturating_add(digits + 2);
    }
    0
}

/// 输出仅物化可见行；选区覆盖到完整 grapheme cell 而保留原有 Markdown 样式。
fn visible_line(
    row: &VisualRow,
    row_index: usize,
    selection: Option<(Cursor, Cursor)>,
) -> Line<'static> {
    let selected_range = selection.map(|(start, end)| ordered_cursors(start, end));
    let mut spans = Vec::with_capacity(row.glyphs.len());
    for (glyph_index, glyph) in row.glyphs.iter().enumerate() {
        let selected = selected_range.is_some_and(|(start, end)| {
            let cursor = Cursor {
                row: row_index,
                glyph: glyph_index,
            };
            (cursor.row, cursor.glyph) >= (start.row, start.glyph)
                && (cursor.row, cursor.glyph) <= (end.row, end.glyph)
        });
        let style = if selected {
            glyph
                .style
                .patch(Style::default().fg(Color::White).bg(Color::Blue))
        } else {
            glyph.style
        };
        spans.push(Span::styled(glyph.text.clone(), style));
    }
    Line::from(spans).style(row.style)
}

/// 规范化拖选起止顺序，允许鼠标向任意方向完成同一可读范围。
fn ordered_cursors(first: Cursor, second: Cursor) -> (Cursor, Cursor) {
    if (first.row, first.glyph) <= (second.row, second.glyph) {
        (first, second)
    } else {
        (second, first)
    }
}

/// 控件窄于提示時按列宽裁剪，不让返回入口覆盖相邻区域。
fn truncate_cells(text: &str, width: usize) -> String {
    let mut result = String::new();
    let mut cells = 0usize;
    for grapheme in text.graphemes(true) {
        let glyph_width = UnicodeWidthStr::width(grapheme).max(1);
        if cells.saturating_add(glyph_width) > width {
            break;
        }
        cells += glyph_width;
        result.push_str(grapheme);
    }
    result
}
