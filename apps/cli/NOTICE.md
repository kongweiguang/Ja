<!-- @author kongweiguang -->

# TUI 参考实现

- Codex `728cb12`（Apache-2.0，Copyright © OpenAI）：参考并改写了 `codex-rs/tui/src/bottom_pane/command_popup.rs`、`scroll_state.rs`、`list_selection_view.rs` 和 `selection_popup_common.rs` 的命令补全、八行滚动列表、编号选择与审批结构，以及 `chat_composer.rs` 的无边框输入与状态行。许可证见 [codex-LICENSE](third_party/codex-LICENSE)。
- 模型选择说明参考同一快照的 `codex-rs/models-manager/models.json` 与本机 Codex CLI 的真实选择器；Ja 只对可识别的上游标识使用这些说明，其余模型展示 Ja 配置中可核实的能力。

Ja 使用 Ratatui 重写这些呈现规则，JA-RPC、会话状态与操作处理仍由 Ja 自身实现。
