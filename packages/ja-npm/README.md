<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja CLI

在终端里驾驭 Agent Harness：读懂项目、修改代码、执行命令、检查结果。

Ja CLI 是 [Ja](https://github.com/kongweiguang/Ja) 的原生命令行客户端，可独立使用，也可与桌面端共享本机 App Server、模型配置和持久会话。不需要先安装桌面端。

## 安装

要求 **Windows 11 x64、Node.js 24.x**。当前 npm 包尚不支持 macOS / Linux；macOS 用户可使用 [Ja 桌面端](https://github.com/kongweiguang/Ja/releases/latest)。

```powershell
npm install -g @kongweiguang/ja@latest
ja --version
ja setup
ja
```

包内包含 Rust CLI、原生 App Server、`fd` 和 `rg` 文件搜索工具，无需另装 JDK、Rust 或搜索工具。

`ja` 使用当前目录，也可以指定项目：

```powershell
ja -C "C:\projects\my-app"
```

## 首次配置

运行 `ja setup`，填写供应商、API 规范、Base URL 和 API Key，添加真实上游模型标识并选择默认模型。支持 Anthropic Messages、OpenAI Chat Completions 和 OpenAI Responses 接口。已有桌面端配置时无需重复添加。

CLI 与桌面使用同一用户及 `JA_HOME` 时共享配置，不需要手动编辑文件。未配置模型时仍可浏览历史和打开设置。首次交互运行 `ja` 进入未受信任项目时，会征求许可；获准后才读取项目配置并允许工具执行，信任确认默认拒绝。非交互 `ja exec` 不会代替你确认信任，需先在该项目中交互运行 `ja` 完成授权。

Ja 不附带模型订阅或 API 额度；费用由你选择的服务商收取。保存配置不会调用模型，“验证连接”会在确认后发送真实请求，可能产生费用。

> [!IMPORTANT]
> 执行确认默认是“完全访问”。初次使用建议切换为“需要审批”，并先尝试只读任务。审批不提供操作系统级文件或网络隔离，重要项目请使用版本控制或备份。

## 常用命令

| 命令                        | 用途                                     |
| --------------------------- | ---------------------------------------- |
| `ja`                        | 在当前目录开始交互会话                   |
| `ja -C <目录> [任务]`       | 在指定目录开始会话，可同时给出首条任务   |
| `ja setup`                  | 管理全局供应商、模型、API Key 与默认模型 |
| `ja resume`                 | 选择当前范围内已有会话                   |
| `ja resume --last`          | 打开最近的会话                           |
| `ja resume <thread-id>`     | 按 ID 打开指定会话                       |
| `ja exec "任务内容"`        | 非交互执行，stdout 只输出最终回答        |
| `ja exec --json "任务内容"` | 输出版本化 JSONL 事件                    |
| `ja server status`          | 查看本机 App Server 状态                 |

完整参数以 `ja --help` 和各子命令的 `--help` 为准。

### 交互快捷方式

| 输入 / 快捷键             | 用途                                    |
| ------------------------- | --------------------------------------- |
| `/`、`@`、`$`             | 查找命令、引用文件、选择已发现 Skill    |
| `/model`                  | 切换会话模型，或进入“管理模型”          |
| `/resume`、`/fork`        | 打开历史会话，或从已完成回复分叉新会话  |
| `/diff`                   | 查看当前会话最近一轮的冻结文件差异      |
| `/review`                 | 审查当前 Git 未提交变更                 |
| `/plan`                   | 切换规划偏好；方案确认后再授权执行      |
| `/skills`、`/mcp`         | 查看当前可用 Skill / MCP 目录           |
| `/copy`、`/export [路径]` | 复制最后一条完整终答、导出会话 Markdown |
| `Ctrl+J`                  | 换行                                    |
| `Ctrl+D`                  | 输入为空时退出客户端                    |

模型设置中，`Tab / Shift+Tab` 切换字段，`F2` 展开高级项，`Ctrl+S` 保存，`Esc` 返回，`F5` 刷新或核实。修改全局默认模型不会改变既有会话；已有会话请用 `/model` 切换。

配置或密钥保存失败会保留输入并提供重试；结果待核实时按提示核实，不要反复提交。

## 用于脚本

在项目目录中运行以下示例；需要先完成模型配置：

```powershell
ja exec --permissions approval-required "说明这个项目如何运行，不要修改文件"
git diff | ja exec --permissions approval-required "审查这些变更，指出风险，不要修改文件"
ja exec --json "总结项目结构"
ja exec --output-last-message .\summary.md "把项目结构整理成 Markdown"
```

`--output-last-message` 会在成功取得完整终答后替换指定文件，请选用合适的输出路径。`--image <文件>` 可附加图片，`--output-schema <JSON文件>` 可约束终答；这些能力还取决于所选模型及其配置。

普通模式的最终回答写入 stdout，诊断写入 stderr。自动化必须检查退出码；使用 `--json` 时同时按最终事件判定结果。

| 退出码 | 含义与下一步                                                                                            |
| ------ | ------------------------------------------------------------------------------------------------------- |
| `0`    | 执行成功                                                                                                |
| `1`    | 执行失败，查看诊断                                                                                      |
| `2`    | 参数错误，检查子命令帮助                                                                                |
| `3`    | 需要用户处理：按诊断完成模型配置、项目信任、审批或澄清；返回会话 ID 时可用 `ja resume <thread-id>` 继续 |
| `4`    | 连接、协议或终答文件写入失败，查看具体诊断                                                              |
| `130`  | 已取消                                                                                                  |

等待用户处理不等于执行成功；不要在收到退出码 `3` 后自动重新提交同一个任务。

终答写入文件失败不代表模型任务失败。若诊断提供了会话与执行 ID，可用 `ja export-answer <thread-id> <turn-id> <文件>` 重新导出已保存的完整终答，无需再次运行原任务；写入失败时原目标文件保持不变。

## 升级与卸载

升级到最新公开版本：

```powershell
npm install -g @kongweiguang/ja@latest
ja --version
```

卸载 CLI：

```powershell
npm uninstall -g @kongweiguang/ja
```

卸载 npm 包不会删除 Ja 的本机数据。CLI 与桌面共用后台，退出终端或卸载包不等于取消正在执行的任务；升级、卸载前先处理进行中的工作。两端同时安装时，建议保持版本一致。

若安装后找不到 `ja`，重新打开终端，确认 Node.js 为 24.x，并检查 `npm prefix -g` 返回的 Windows 全局安装目录是否在 `PATH` 中。

## 数据与隐私

对话、配置和附件默认保存在 `%USERPROFILE%\.ja`。`JA_HOME` 可指定新的绝对数据目录；切换目录不会迁移旧数据。对话、相关文件内容和附件会按需发送给你配置的模型服务，MCP 也可能连接外部服务。本机运行不代表完全离线。

不要公开 API Key、`auth.json` 或私有日志。0.1 升级到 0.2 的数据注意事项见 [v0.2.0 发布说明](https://github.com/kongweiguang/Ja/releases/tag/v0.2.0)。

## 源码与反馈

- [Ja 仓库与桌面端指南](https://github.com/kongweiguang/Ja)
- [问题与建议](https://github.com/kongweiguang/Ja/issues)：请提供版本、系统和复现步骤。
- [安全说明](https://github.com/kongweiguang/Ja/blob/main/SECURITY.md)

包内 `runtime/manifest.json` 记录二进制对应的源码 commit。本包采用 GPL-3.0-or-later；`NOTICE.md` 和 `third_party/codex-LICENSE` 保留参考 TUI 内容的归属与许可证。
