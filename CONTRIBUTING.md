<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 贡献指南

感谢参与 Ja。项目仍处于早期阶段；提交前请确认改动对应当前代码和公开文档，不要把候选设计或
尚未验证的能力写成已经交付的事实。

## 工具链

从仓库根目录工作，并使用以下版本：

- Windows 11 与 PowerShell 7.x；仅在兼容性问题明确时使用 Windows PowerShell 5.1。
- Node.js 24.x、pnpm 10.33.0。
- Rust 1.89 或更高的兼容工具链。
- JDK 25；Java、Maven、Native Image 和合同 Gate 都必须显式使用同一个 JDK 25。

安装前端依赖：

```powershell
corepack enable
pnpm install --frozen-lockfile
```

设置并确认 JDK 25：

```powershell
$env:JAVA_HOME = '<JDK-25_HOME>'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn.cmd -version
```

两个版本输出都必须明确显示 Java 25。

### Windows JVM 开发启动

可双击仓库根的 `dev.cmd`，也可从任意工作目录调用它的完整路径；脚本按自身位置定位仓库。
命令行启动也可在仓库根执行 `.\dev.cmd`（或 `pnpm tauri:dev:jar`）。脚本校验工具链，在各 Java 模块的
`target-tauri-dev/` 构建，再将 JAR 复制到本次独有的 `.tmp/tauri-dev/<id>/` 后启动 Tauri。
运行中的 JAR 不参与 Maven 清理，旧 JVM 尚未退出也不会阻止下一次开发构建；脚本不自动终止现有服务。
Tauri 退出后清理本次副本，仍被占用时保留并提示。`.\dev.cmd -SkipLaunch` 只准备 JAR，
保留副本并输出路径；这些副本可在使用它们的进程退出后删除。命令行直接启动失败时窗口会暂停并保留诊断，
pnpm/CI 调用不会暂停，且仍返回原始失败退出码。

## 目录边界

- `apps/desktop/`：React/Vite 桌面前端；测试只放在 `apps/desktop/tests/`。
- `apps/desktop/src-tauri/`：Tauri 壳与 Windows/macOS 原生能力；Rust 测试放在其 `tests/` 边界。
- `apps/cli/`：Rust CLI/TUI；复用 `crates/ja-runtime`，测试放在所属应用的 `tests/`。
- `packages/ja-npm/`：CLI 的 npm 分发包装及其打包验证。
- `crates/ja-runtime/`：不依赖 Tauri 的 Ja App Server host runtime。
- `ja-agent/`：Java 25 Maven 多模块根目录；`ja-agent-core/` 提供可复用循环，`extensions/` 提供 SQLite/PostgreSQL Store 和本地/PG Skill，`example/` 放可运行的 Spring Boot 接入，`ja-app-server/` 是保留 Solon 的 Ja 应用。测试位于各模块的 `src/test`，不设独立测试模块。
- `contracts/ja-rpc/v2/` 与 `contracts/golden/v2/`：JA-RPC 2.0 schema 与三端语料。

React 不直接读取 Ja 配置或凭据；Tauri/Rust 只代理 JA-RPC 并绑定原生能力；Ja 配置、Workspace
identity 与 Plan 由 App Server 持有，通用执行状态走 AgentStore。不要恢复旧协议、旧字段、旧目录或
兼容回退。

## 最窄验证

前端与仓库门禁：

```powershell
pnpm typecheck
pnpm lint
pnpm test
pnpm build
pnpm check:architecture
pnpm check:unused
pnpm version:check
```

Rust 变更：

```powershell
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
```

Java 变更在显式 JDK 25 环境中执行：

```powershell
mvn.cmd -B -ntp -f ja-agent/pom.xml verify
```

需要在发布阻塞项尚未解决时完成本地实现验收，可运行完整 runner 的本地模式：

```powershell
$source = (Resolve-Path -LiteralPath '<repository-relative matching source path>').Path
$artifact = (Resolve-Path -LiteralPath '<repository-relative artifact path>').Path
pwsh -NoProfile -File scripts/verification/run-production-verification.ps1 `
  -LocalVerification -CorrespondingSourcePath $source -ArtifactPath $artifact
```

两条路径都必须指向仓库内存在且 24 小时内更新的对应源码与一个或多个产物；runner 还会校验本轮新鲜 BOM 和实际输入证据。
该模式仍生成 SBOM/许可证报告并保留 blocker；只有新鲜 BOM 和实际输入证据有效时，才允许继续本地实现验证。
报告标记 `verificationPurpose=local-implementation`、`releaseApproval=false`，不表示发布门禁通过或获得发布批准。
默认模式仍按严格发布准备门禁执行。

并行本地构建为每个 Maven 调用选一个独有目录名，并在 PowerShell 中把 `-D` 参数作为单个
native argument 传递：

```powershell
mvn.cmd -B -ntp -f ja-agent/pom.xml '-Dja.build.directory=target-core-agent' verify
```

该值相对每个模块目录生效，因此不同模块仍有各自输出，不会共写一个 Reactor `target`。

修改 JA-RPC、DTO、schema 或跨 Java/Rust/TypeScript 合同时，必须运行完整三端 Gate：

```powershell
pwsh -NoProfile -File tests/contract/run.ps1
```

只运行与改动相关的最窄命令是开发中的起点，不等于可以省略受影响的集成门禁。提交说明必须列出
实际执行的命令、结果和未覆盖的平台，不得把静态检查或单元测试写成真机验收。

## 修改与归属

### Java 格式与 Record 注释

Java 排版参考阿里巴巴官方 [代码格式](https://github.com/alibaba/p3c/blob/master/p3c-gitbook/编程规约/代码格式.md)
和 [注释规约](https://github.com/alibaba/p3c/blob/master/p3c-gitbook/编程规约/注释规约.md)：
4 空格缩进、UTF-8、LF 换行、120 字符行宽，换行在逗号之后，类型与方法使用 Javadoc。
IDE 配置见 `ja-agent/.editorconfig`；本次未对整个 Java 工程执行其它排版重写，不能据此视为完整手册合规审计。

Ja 对 `record` 补充统一约定：多个组件必须逐字段换行，首字段紧接声明行的左括号，
后续字段与首字段对齐；单字段和无字段声明可保留一行。
每个组件都在声明上方的 Javadoc 中写 `@param 字段名 中文业务说明`，解释用途，涉及单位时说明单位。
该规则覆盖 `ja-agent` 所有模块的生产、测试与嵌套声明，由 `JavaSourcePolicyTest` 检查。

```java
/**
 * 一次可恢复执行的稳定身份。
 *
 * @param threadId 所属对话标识
 * @param turnId 本次执行标识
 */
public record ExecutionIdentity(String threadId,
                                String turnId) {}
```

多行 SQL、JSON、配置夹具和较长提示词优先使用 Java 文本块 `"""`，保留实际字符串的换行、
缩进与尾部空格。仅为源码排版换行时，使用文本块续行符 `\`；动态模板可用 `.formatted(...)`，
插入 JSON 数据时继续使用 JSON 编码器，不能用插值代替转义。
短字符串、路径、正则和逐项累积内容按可读性保留普通字符串或 `StringBuilder`。

### 维护约定

- 保留现有 SPDX、版权和第三方归属头。新增人工维护文件按当前仓库门禁包含
  `@author kongweiguang`；该标记表示项目维护责任，不覆盖贡献者的 Git authorship 或合法版权。
- 函数级注释解释设计原因、约束和取舍，不复述函数名或控制流。
- 不提交 secret、访问令牌、个人绝对路径、真实用户数据、私有源码或未经脱敏的日志。
- 行为、隐私、安全或外部契约改变时，同步公开 README 或相应契约文档。

## Pull Request

Pull Request 应说明用户可观察变化、非目标、修改范围、验证证据、未覆盖平台，以及涉及数据、权限、
进程、网络、凭据或发布时的风险与恢复方式。新增第三方内容必须同时说明来源和复核状态。

当前项目没有 DCO、CLA 或强制 commit message 约定。原创贡献默认按
[GPL-3.0-or-later](LICENSE) 提供；贡献者必须确认自己有权提交，并保留第三方声明。
