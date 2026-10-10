---
name: ja-docs
description: Ja 已安装应用的使用文档。回答 Ja 桌面操作、对话与项目、模型配置、Skills、MCP、CLI 和故障恢复问题，或按用户要求操作 Ja 时使用；不用于开发 Ja 源码。
---

<!-- @author kongweiguang -->

# Ja 用户文档

本文面向安装后使用 Ja 的用户及代用户操作 Ja 的 Agent。先确定用户使用桌面版还是单独安装的 CLI，以及当前对话是否属于可信项目；只读取与问题相关的专题，不把开发源码路径、构建命令或尚未发布的功能当成用户操作步骤。

| 用户要做什么 | 按需读取 |
| --- | --- |
| 安装后开始对话、选择项目、查看过程、使用 Plan 或工作台 | `skill://ja-docs/references/getting-started.md` |
| 设置模型、权限、子智能体，查配置路径、TOML 格式和覆盖规则 | `skill://ja-docs/references/configuration.md` |
| 安装或使用 Skills，接入和检查 MCP | `skill://ja-docs/references/extensions.md` |
| 使用已单独安装的 `ja` 命令行 | `skill://ja-docs/references/cli.md` |
| 无模型、配置出错、会话中断、任务结果不明或数据位置 | `skill://ja-docs/references/troubleshooting.md` |

Ja App Server 是配置、凭据、工作区信任、会话和本地数据的唯一所有者。操作前读取当前状态和用户意图；优先通过 Ja 设置页处理支持的配置和凭据。必须编辑文件时，只改目标层的目标字段，保留其它内容，完成后回读 Ja 展示的生效值与问题。不要直接修改 `auth.json`、SQLite 数据库或安装目录来修复用户设置。运行中的任务可能继续使用已冻结的状态；以 Ja 的权威回读判断何时生效。
