// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 固定深色终端调色板，避免 ANSI 默认色随宿主主题改变信息层级。
//! Contrast rules follow Codex `codex-rs/tui/src/style/contrast.rs` at `822e58cc`;
//! Ja defines these fixed RGB tokens and verifies their actual foreground/background pairs.

use ratatui::style::Color;

pub(super) const BACKGROUND: Color = Color::Rgb(18, 20, 25);
pub(super) const SURFACE: Color = Color::Rgb(29, 32, 40);
pub(super) const TEXT: Color = Color::Rgb(238, 239, 241);
pub(super) const MUTED: Color = Color::Rgb(180, 186, 198);

pub(super) const AMBER: Color = Color::Rgb(255, 197, 112);
pub(super) const AMBER_SECONDARY: Color = Color::Rgb(236, 188, 117);
pub(super) const AMBER_TERTIARY: Color = Color::Rgb(201, 162, 111);
pub(super) const BLUE: Color = Color::Rgb(137, 180, 250);
pub(super) const GREEN: Color = Color::Rgb(166, 217, 146);
pub(super) const RED: Color = Color::Rgb(243, 139, 168);
pub(super) const YELLOW: Color = Color::Rgb(249, 226, 175);
pub(super) const CYAN: Color = Color::Rgb(137, 220, 235);

pub(super) const CODE_FOREGROUND: Color = Color::Rgb(218, 222, 232);
pub(super) const CODE_BACKGROUND: Color = Color::Rgb(34, 38, 48);
pub(super) const CODE_LABEL: Color = Color::Rgb(183, 194, 212);
pub(super) const INLINE_CODE_FOREGROUND: Color = Color::Rgb(224, 206, 255);
pub(super) const INLINE_CODE_BACKGROUND: Color = Color::Rgb(43, 40, 53);
pub(super) const RULE: Color = Color::Rgb(156, 165, 180);

pub(super) const SELECTION_FOREGROUND: Color = Color::Rgb(244, 246, 252);
pub(super) const SELECTION_BACKGROUND: Color = Color::Rgb(66, 86, 128);

pub(super) const COMPOSER_FOREGROUND: Color = TEXT;
pub(super) const COMPOSER_BACKGROUND: Color = Color::Rgb(38, 40, 43);
