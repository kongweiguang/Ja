<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja 命令行客户端

Ja CLI 是 Ja 的原生命令行客户端，适用于 Windows x64。它与 Ja Desktop 共用本机 App Server、模型配置和会话记录。npm 包内包含 CLI 和 App Server，无需另装 JDK。

## 环境要求

- Windows 11 x64
- Node.js 24.x

此包名为 `@kongweiguang/ja`，目前只支持 Windows x64。

## 安装与启动

```powershell
npm install --global @kongweiguang/ja
ja
```

要在指定项目中启动，可运行 `ja -C C:\项目目录`。未配置模型时，可运行 `ja setup` 管理供应商、模型、API Key 和全局默认模型；已有配置也可继续使用该入口。CLI 与桌面共享配置，不需要手动编辑文件。若当前项目尚未受信任，Ja 会先征求许可，获准后才读取项目配置并允许工具执行；默认选项为拒绝。

## 命令

```text
ja                         在当前目录开始交互会话
ja -C <目录>                在指定目录开始会话
ja resume [thread-id]       恢复已有会话
ja setup                    管理全局供应商、模型与 API Key
ja exec "任务内容"           非交互执行任务
ja exec --json "任务内容"    输出版本化 JSONL 事件
ja server status            查看本机 App Server 状态
```

交互界面使用 `/` 查找命令、`@` 引用文件；按 `Ctrl+J` 换行，输入为空时按 `Ctrl+D` 退出。需要审批或澄清的非交互任务会返回会话标识，可在 Ja CLI 或 Ja Desktop 中继续处理。

会话内 `/model` 选择本次会话的模型，也提供“管理模型”。设置面板使用 `Tab/Shift+Tab` 切换字段，
`F2` 展开高级项，`Ctrl+S` 保存，`Esc` 返回，`F5` 刷新或核实。修改全局默认不会改变既有会话。
配置或密钥保存失败会保留输入并提供重试；结果待核实时只读取原操作状态，不自动重复写入。
“验证连接”会明确确认一次真实请求可能产生费用，保存本身不调用模型。

## 本地数据与源码

对话、配置和附件默认保存在 `%USERPROFILE%\.ja`。请求会发送给本机配置中选择的模型服务。安装包内的 `runtime/manifest.json` 记录二进制对应的源码 commit；源码可在 [Ja 仓库](https://github.com/kongweiguang/Ja)查看。

本包采用 GPL-3.0-or-later。`NOTICE.md` 和 `third_party/codex-LICENSE` 记录了参考 TUI 内容的归属与许可证。
