<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja 发布编排

Ja 沿用 Kerminal/GMark 的分发策略：不要求 Windows Authenticode 或 macOS Developer ID/公证证书，更新包仍必须使用应用内置公钥对应的 Tauri 密钥签名。系统可能显示 SmartScreen 或未识别开发者提示；这不代表更新签名被关闭。

`.github/workflows/native-app-server.yml` 分为两条路径：

- `pull_request`、`main` 和普通手工运行：只构建 unsigned Native/NSIS/DMG smoke，不需要签名凭据，也不能作为发布证据。
- 手工 `release=true`：使用 Tauri 更新签名构建 Windows x64 NSIS、macOS Intel/Apple Silicon DMG 和更新归档，继续执行 Native、安装 smoke 与供应链门禁，不要求系统证书。

JVM 常规验证与 Jazzer fuzz 使用独立 Maven/JVM 调用：常规 `verify` 排除 `ProviderInputFuzzTest`，紧随其后的两个必需 fuzz 步骤分别执行该类的全部两个方法，防止全局插桩状态传播到后续 FFM/Win32 测试。任何一步失败均阻止原生构建。

多命令 PowerShell 验证步骤显式启用原生命令失败传播，非零退出码会立即中止，不允许后续成功命令覆盖失败结果。Rust 外层测试 worker 串行运行，隔离真实 JVM/Git/Shell 用例的宿主资源争用；测试内部的显式并发场景和产品 deadline 保持不变。前端源码通过 Git attributes 固定 LF，保证 Windows 干净检出与 Prettier 的检查规则一致。

CI 前端测试限制为 2 个 Vitest worker，完整 App composition 测试组使用局部 10 秒预算；其它单元测试与产品超时不变。该配置保留全部断言和测试内部并发，不通过重试或跳过用例获得通过结果。

Rust 编译器、Clippy 与 rustfmt 由根 `rust-toolchain.toml` 固定为 1.98.0，保证本地与 CI 使用相同检查基线；不依赖开发机的全局默认版本，也不随 runner 镜像自动漂移。

Windows Host 与 Shell 的环境白名单会转发宿主提供的绝对 `PSModuleAnalysisCachePath`。GitHub Windows 镜像预热此非敏感缓存来加速 cmdlet 发现；丢弃它会触发大型模块集合的重新分析。该项不允许配置、凭据或任意环境变量穿过 `env_clear` 边界。

正式路径只接受 GitHub Secrets，不接受命令行参数、仓库文件或普通环境变量中的私钥：

桌面更新签名：`TAURI_SIGNING_PRIVATE_KEY`，以及密钥设有密码时所需的 `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`。私钥必须与应用内置 updater 公钥匹配，不得为通过构建而临时替换。

发布从指定提交手工触发 `release=true`。Native 矩阵通过后，汇总签名产物并生成 `latest.json`，从权威产品版本派生 `v<version>`，为该提交创建未公开的 GitHub draft Release。工作流不由 tag push 触发，不提前创建或移动发布 tag，也不会自动公开或覆盖同名 Release；公开时确认 tag 指向通过验收的提交。

`package.json` 是产品版本的唯一来源。`pnpm version:sync` 和 `pnpm version:check` 同时覆盖 Cargo、Tauri、Maven 与协议 golden 中 `runtime/initialize` 响应的 `engineVersion`，不改写请求中的 `clientVersion` 示例。运行 JVM 桌面验收前还需重新打包并核对 JAR 内嵌版本，源码版本同步不会替换已有 JAR。

公开发布须在更新签名验证、安装验收和完整平台矩阵通过后，由发布 owner 在用户授权范围内执行。产物清单必须如实标注系统未签名与未公证，不能将更新签名冒充系统签名。缺少更新密钥或产物时保持未公开；已经公开的版本不得移动 tag 或静默复用版本号，修复应使用新版本。

CI 继续生成 Maven BOM、依赖许可证清单与供应链报告。当前仓库没有 `LICENSES/approved` 批准归档，报告会如实保留 `LICENSE_ARCHIVE_EMPTY` 等未完成事项；发布不调用遗留的 `-FailOnBlocker` 审批模式，也不把归档状态改写为 `approved/complete`。报告生成失败仍会阻止构建，报告中的审查未完成状态应随发布说明披露。
