<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 安全问题报告

Ja 仍处于 `v0.1.x` 预览阶段，尚未承诺固定支持周期、响应时限、漏洞悬赏或生产安全
保证。安全修复以当前维护版本为准，旧提交和非正式构建不提供单独支持。

## 当前报告渠道

当前 GitHub 仓库尚未启用 Private Vulnerability Reporting，因此暂时没有可承诺的私密
漏洞接收渠道。在该功能启用并更新本文件前：

1. 不要在公开 Issue、Discussion、Pull Request、提交或聊天记录中披露漏洞细节、利用代码、
   API Key、token、私钥、个人数据、私有源码或完整日志。
2. 仅可通过 [GitHub Issues](https://github.com/kongweiguang/ja/issues/new) 提交不含敏感信息的
   安全加固建议，或说明“需要私密报告渠道”；不要附带可利用的复现步骤。
3. 在维护者提供经本文件确认的私密渠道前，请保留敏感报告，不要尝试访问他人工作区、账号、
   模型服务或生产系统来验证影响。

启用 GitHub Private Vulnerability Reporting 后，本文件应同步改为直接链接仓库的
`Security Advisory` 私密提交通道，避免报告者根据界面状态自行猜测。

## 报告内容

非敏感摘要应包含受影响的 Ja 版本或 commit、运行平台、影响边界和已采取的缓解措施。
在私密渠道可用后，最小复现还应说明前置权限和清理方式；发送前必须删除或轮换凭据并脱敏路径、
日志与用户数据。

## Agent 特有边界

涉及 Shell、文件、终端、预览、MCP、Skills、模型请求、sidecar、Tauri Capability 或凭据
存储的问题，应说明是否需要用户确认、能否越出当前 Workspace/Thread、是否留下子进程、临时
文件或数据库残留，以及重启后是否仍可复现。这些信息用于判断影响，不代表相关能力已经通过
独立安全审计。
