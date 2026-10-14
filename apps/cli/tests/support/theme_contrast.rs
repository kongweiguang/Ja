// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 只在测试 target 中计算调色板对比度，避免生产渲染路径增加颜色转换成本。

use ratatui::style::Color;

/// 将 sRGB 通道线性化以按 WCAG 公式核验固定前景与背景组合。
fn linear_channel(channel: u8) -> f64 {
    let value = f64::from(channel) / 255.0;
    if value <= 0.04045 {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).powf(2.4)
    }
}

/// 计算标准 RGB 的相对亮度；非 RGB 颜色不应混入固定深色主题验收。
fn luminance(color: Color) -> f64 {
    let Color::Rgb(red, green, blue) = color else {
        panic!("theme contrast accepts only explicit RGB colors");
    };
    0.2126 * linear_channel(red) + 0.7152 * linear_channel(green) + 0.0722 * linear_channel(blue)
}

/// 返回亮暗顺序无关的 WCAG 对比度，供语义文字颜色共享同一验收门槛。
pub(super) fn contrast_ratio(foreground: Color, background: Color) -> f64 {
    let (lighter, darker) = {
        let foreground = luminance(foreground);
        let background = luminance(background);
        if foreground >= background {
            (foreground, background)
        } else {
            (background, foreground)
        }
    };
    (lighter + 0.05) / (darker + 0.05)
}
