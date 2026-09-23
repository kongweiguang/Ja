<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja 发布编排

Ja 沿用 Kerminal/GMark 的分发策略：不要求 Windows Authenticode 或 macOS Developer ID/公证证书，更新包仍必须使用应用内置公钥对应的 Tauri 密钥签名。系统可能显示 SmartScreen 或未识别开发者提示；这不代表更新签名被关闭。

`.github/workflows/native-app-server.yml` 与 `.github/workflows/release.yml` 分为两条路径：

- `pull_request`、`main` 和普通 `native-app-server.yml` 手工运行：完整执行合同、JVM、前端、Rust、脚本和 unsigned Native/NSIS/DMG smoke；成功的 `main` run 是唯一可发布候选证据。
- `release.yml` 手工输入该成功 `main` run 的完整 commit SHA：先重新确认该 SHA 仍是 `main`，选择该 SHA 最近成功的完整 CI run，再检查其产物完整且未过期。发布复用该 run 的 Native Image，不再重复编译 Java 原生程序；仍重新执行启动和文件搜索 smoke、Tauri 构建与更新签名、安装 smoke、供应链报告与 Draft 汇总。

候选已通过普通 CI 后，从 `main` 触发：`gh workflow run release.yml --ref main -f source_commit=<完整40位SHA>`。

普通 CI 先执行脚本、版本、格式、类型、架构及未使用代码等快速检查。通过后，前端完整测试与生产构建独立运行，与合同/JVM/Rust 验证并行；JVM 与 Rust 保持在同一个 job 中，确保 Rust 测试使用本轮生成的 App Server JAR。两条验证链全部通过后才启动三个平台的原生矩阵，任何检查失败都不能被签名或聚合步骤绕过。

Native 复用通过三个独立的 `ja-native-reuse-<platform>-<arch>` Actions artifact 传递，每份只包含原始可执行文件、Native build report 和 Maven SBOM。发布使用固定 run ID 下载，恢复脚本核对提交、平台/架构、NIK 版本及分发包哈希、`--no-fallback`、成功 smoke、可执行文件与 SBOM 的大小和 SHA-256。macOS 下载后恢复执行权限；原始构建报告及来源 run 单独留在发布证据中，随后重新检查运行行为，不把复用描述成新编译。

发布预检还会在矩阵前检查版本投影、对应版本的非空发布说明、更新签名密钥，以及同名 Release/tag 冲突。复用 artifact 缺失、过期或不匹配时立即失败；应对同一候选重新运行完整 CI，获得新的成功 run 后再发布。旧工作流没有生成这些精简 artifact，不能直接作为新流程的候选；不回退到临时重编或跳过校验。

没有源码变更的临时 runner/网络故障，可在确认失败原因后使用 `gh run rerun <run-id> --failed`，保留该 run 已成功 job 的结果；例如只有 Draft 聚合失败时，不必重新编译三个平台。若修复涉及代码、合同或配置变更，必须让新 SHA 重新通过完整 CI，不能把旧 run 的证据嫁接到新提交。预检发现已存在的 Release/tag 时，应先检查远端状态，禁止为省时间静默覆盖或移动。

耗时基线来自 2026-09-22 的 [完整 CI 35705750373](https://github.com/kongweiguang/Ja/actions/runs/35705750373) 与 [签名发布 35710433096](https://github.com/kongweiguang/Ja/actions/runs/35710433096)：分别约 49 分 32 秒、24 分 10 秒。原验证 job 的前端步骤约 7 分 26 秒，签名矩阵重复的 Native Image 编译在 Windows、macOS Intel、Apple Silicon 上分别约 4 分 51 秒、6 分 44 秒、12 分 49 秒。本次优化移除重复 Native 编译，并缩短串行验证路径；实际端到端收益须在新工作流推送后，用同类 runner 的完整运行复测，不能把这些步骤时长直接当作已经实测的节省时间。

JVM 常规验证与 Jazzer fuzz 使用独立 Maven/JVM 调用：常规 `verify` 排除 `ProviderInputFuzzTest`，紧随其后的两个必需 fuzz 步骤分别执行该类的全部两个方法，防止全局插桩状态传播到后续 FFM/Win32 测试。任何一步失败均阻止原生构建。

多命令 PowerShell 验证步骤显式启用原生命令失败传播，非零退出码会立即中止，不允许后续成功命令覆盖失败结果。Rust 外层测试 worker 串行运行，隔离真实 JVM/Git/Shell 用例的宿主资源争用；测试内部的显式并发场景和产品 deadline 保持不变。前端源码通过 Git attributes 固定 LF，保证 Windows 干净检出与 Prettier 的检查规则一致。

CI 前端完整测试固定为 1 个 Vitest worker，避免 jsdom 虚拟列表 teardown 与延迟回调互相争用；独立 job 的并行不改变测试内部的并发场景、断言和产品超时，也不通过重试或跳过用例获得通过结果。

Rust 编译器、Clippy 与 rustfmt 由根 `rust-toolchain.toml` 固定为 1.98.0，保证本地与 CI 使用相同检查基线；不依赖开发机的全局默认版本，也不随 runner 镜像自动漂移。

Windows Host 与 Shell 的环境白名单会转发宿主提供的绝对 `PSModuleAnalysisCachePath`。GitHub Windows 镜像预热此非敏感缓存来加速 cmdlet 发现；丢弃它会触发大型模块集合的重新分析。该项不允许配置、凭据或任意环境变量穿过 `env_clear` 边界。

正式路径只接受 GitHub Secrets，不接受命令行参数、仓库文件或普通环境变量中的私钥：

桌面更新签名：`TAURI_SIGNING_PRIVATE_KEY`，以及密钥设有密码时所需的 `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`。私钥必须与应用内置 updater 公钥匹配，不得为通过构建而临时替换。

发布通过 `release.yml` 从指定的完整 SHA 手工触发。调用前先确认该 SHA 是当前 `main`，并且已有成功的 `native-app-server.yml` push run；任何提交漂移、未通过或缺失验证都会在签名前失败。签名 Native 矩阵通过后，汇总产物并生成 `latest.json`，从权威产品版本派生 `v<version>`，为该提交创建未公开的 GitHub draft Release。工作流不由 tag push 触发，不提前创建或移动发布 tag，也不会自动公开或覆盖同名 Release；公开时确认 tag 指向通过验收的提交。

`package.json` 是产品版本的唯一来源。`pnpm version:sync` 和 `pnpm version:check` 同时覆盖 Cargo、Tauri、Maven 与协议 golden 中 `runtime/initialize` 响应的 `engineVersion`，不改写请求中的 `clientVersion` 示例。运行 JVM 桌面验收前还需重新打包并核对 JAR 内嵌版本，源码版本同步不会替换已有 JAR。

公开发布须在更新签名验证、安装验收和完整平台矩阵通过后，由发布 owner 在用户授权范围内执行。产物清单必须如实标注系统未签名与未公证，不能将更新签名冒充系统签名。缺少更新密钥或产物时保持未公开；已经公开的版本不得移动 tag 或静默复用版本号，修复应使用新版本。

CI 继续生成 Maven BOM、依赖许可证清单与供应链报告。当前仓库没有 `LICENSES/approved` 批准归档，报告会如实保留 `LICENSE_ARCHIVE_EMPTY` 等未完成事项；发布不调用遗留的 `-FailOnBlocker` 审批模式，也不把归档状态改写为 `approved/complete`。报告生成失败仍会阻止构建，报告中的审查未完成状态应随发布说明披露。
