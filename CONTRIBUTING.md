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

## 目录边界

- `apps/desktop/`：React/Vite 桌面前端；测试只放在 `apps/desktop/tests/`。
- `src-tauri/`：Tauri 壳与 Windows/macOS 原生能力；Rust 测试放在其 `tests/` 边界。
- `crates/ja-runtime/`：不依赖 Tauri 的 Ja App Server host runtime。
- `app-server/`：唯一 Java App Server 和 Agent Kernel，构建入口是 `app-server/pom.xml`。
- `contracts/ja-rpc/v1/` 与 `contracts/golden/v1/`：JA-RPC v1 schema 与三端语料。

React 不直接读取 Ja 配置或凭据；Tauri/Rust 只代理 JA-RPC 并绑定原生能力；配置、Workspace
identity、SQLite 和 Agent Runtime 由 Ja App Server 持有。不要恢复旧协议、旧字段、旧目录或
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
mvn.cmd -B -ntp -f app-server/pom.xml verify
```

修改 JA-RPC、DTO、schema 或跨 Java/Rust/TypeScript 合同时，必须运行完整三端 Gate：

```powershell
pwsh -NoProfile -File tests/contract/run.ps1
```

只运行与改动相关的最窄命令是开发中的起点，不等于可以省略受影响的集成门禁。提交说明必须列出
实际执行的命令、结果和未覆盖的平台，不得把静态检查或单元测试写成真机验收。

## 修改与归属

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
