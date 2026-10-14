<!-- @author kongweiguang -->

# TUI 参考实现

- Codex `728cb12`（Apache-2.0，Copyright © OpenAI）：参考并改写了 `codex-rs/tui/src/bottom_pane/command_popup.rs`、`scroll_state.rs`、`list_selection_view.rs` 和 `selection_popup_common.rs` 的命令补全、八行滚动列表、编号选择与审批结构，以及 `chat_composer.rs` 的无边框输入与状态行。许可证见 [codex-LICENSE](third_party/codex-LICENSE)。
- 模型选择说明参考同一快照的 `codex-rs/models-manager/models.json` 与本机 Codex CLI 的真实选择器；Ja 只对可识别的上游标识使用这些说明，其余模型展示 Ja 配置中可核实的能力。
- Codex `822e58cc3d666166c7446c5b1ea2e52f5d09594c`（Apache-2.0，Copyright 2025 OpenAI）：`src/ui/wrapping.rs` 复制并改写 `codex-rs/tui/src/wrapping.rs` 的 `flatten_line`、`slice_line_spans`，复用 `textwrap 0.16.2` 的 Unicode 断词和 first-fit 算法。Ja 保留自己的字素列宽、流式账本和列表策略；源文件已标明修改，许可及原声明见 [codex-LICENSE](third_party/codex-LICENSE)、[codex-NOTICE](third_party/codex-NOTICE)。
- 同一 `822e58cc` 快照：`src/ui/paste_burst.rs` 参考并改写 `bottom_pane/paste_burst.rs`、`bottom_pane/chat_composer/paste_input.rs` 的暂存、粘贴判定及控制键交付；`src/ui/windows_key_sequence.rs` 改写 `tui/windows_key_sequence.rs` 的完整序列识别与失败回放；`src/controller/external_editor.rs` 采用 `external_editor.rs` 的 Windows 可执行路径解析方式。Ja 使用同步事件循环，不引入上游异步事件流。
- 同一快照：`src/controller/clipboard.rs` 改写 `clipboard_copy.rs`、`clipboard_copy/worker.rs` 的有界任务、写入前时限门禁与真实确认规则，只保留原生文本剪贴板；固定深色调色板学习 `style/contrast.rs` 的文字及选区对比度规则。许可与原声明仍见上面的第三方文件；新增及改写源文件注明 Ja 的修改。

Ja 使用 Ratatui 重写这些呈现规则，JA-RPC、会话状态与操作处理仍由 Ja 自身实现。
