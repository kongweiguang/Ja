// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[path = "../support/theme_contrast.rs"]
mod contrast;
#[path = "../../src/ui/theme.rs"]
mod theme;

use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::style::Color;

/// 固定深色画布上的正文、状态和控件色均保持 WCAG AA 普通文字对比度。
#[test]
fn semantic_foregrounds_remain_readable_on_terminal_surfaces() {
    let foregrounds = [
        theme::TEXT,
        theme::COMPOSER_FOREGROUND,
        theme::MUTED,
        theme::AMBER,
        theme::AMBER_SECONDARY,
        theme::AMBER_TERTIARY,
        theme::BLUE,
        theme::GREEN,
        theme::RED,
        theme::YELLOW,
        theme::CYAN,
        theme::CODE_LABEL,
        theme::RULE,
    ];
    let backgrounds = [
        theme::BACKGROUND,
        theme::SURFACE,
        theme::COMPOSER_BACKGROUND,
    ];

    for foreground in foregrounds {
        for background in backgrounds {
            let ratio = contrast::contrast_ratio(foreground, background);
            assert!(
                ratio >= 4.5,
                "{foreground:?} on {background:?} has contrast {ratio:.2}:1"
            );
        }
    }
}

/// 代码、行内代码与反白选区分别对自己的底色验收，避免局部背景压低可读性。
#[test]
fn code_and_selection_pairs_remain_distinguishable() {
    for (foreground, background) in [
        (theme::CODE_FOREGROUND, theme::CODE_BACKGROUND),
        (theme::CODE_LABEL, theme::CODE_BACKGROUND),
        (theme::INLINE_CODE_FOREGROUND, theme::INLINE_CODE_BACKGROUND),
        (theme::SELECTION_FOREGROUND, theme::SELECTION_BACKGROUND),
    ] {
        assert!(
            contrast::contrast_ratio(foreground, background) >= 4.5,
            "{foreground:?} on {background:?} is not readable"
        );
    }
}

/// 控制台底色和正文始终为显式 RGB，避免恢复 Windows Terminal 的 ANSI 默认色依赖。
#[test]
fn fixed_canvas_and_text_do_not_depend_on_terminal_ansi_defaults() {
    for color in [theme::BACKGROUND, theme::TEXT] {
        assert!(matches!(color, Color::Rgb(_, _, _)));
    }
}

/// 空白 transcript cell 仍使用 Ja 的固定画布底色，避免滚动和短内容露出宿主主题。
#[test]
fn rendered_transcript_uses_the_fixed_canvas_background() {
    let state = ja_cli::ui::UiState::new(ja_cli::ui::UiSnapshot::default());
    let mut terminal = Terminal::new(TestBackend::new(100, 24)).unwrap();
    terminal
        .draw(|frame| ja_cli::ui::render(frame, &state))
        .unwrap();

    let blank_cell = &terminal.backend().buffer().content()[99];
    assert_eq!(blank_cell.bg, theme::BACKGROUND);
}
