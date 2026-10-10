// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use ratatui::buffer::Buffer;
use unicode_width::UnicodeWidthStr;

/// 仅显式导出实际样式与字素列宽；宽字符后继 cell 是占位格，视觉回放不能当作真实空白。
pub fn write(name: &str, buffer: &Buffer, directory: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(directory)?;
    let cells: Vec<_> = buffer
        .content
        .iter()
        .map(|cell| {
            serde_json::json!({
                "text": cell.symbol(),
                "width": cell.symbol().width().max(1),
                "fg": format!("{:?}", cell.fg),
                "bg": format!("{:?}", cell.bg),
                "bold": cell.modifier.contains(ratatui::style::Modifier::BOLD),
                "italic": cell.modifier.contains(ratatui::style::Modifier::ITALIC),
                "underline": cell.modifier.contains(ratatui::style::Modifier::UNDERLINED),
            })
        })
        .collect();
    let rows = (0..buffer.area.height)
        .map(|y| {
            let mut row = String::new();
            let mut x = 0;
            while x < buffer.area.width {
                let symbol = buffer[(x, y)].symbol();
                row.push_str(symbol);
                x += symbol.width().max(1) as u16;
            }
            row
        })
        .collect::<Vec<_>>();
    let data = serde_json::json!({
        "width": buffer.area.width,
        "height": buffer.area.height,
        "cells": cells,
        "rows": rows,
    });
    std::fs::write(
        directory.join(format!("{name}.json")),
        serde_json::to_vec_pretty(&data)?,
    )
}
