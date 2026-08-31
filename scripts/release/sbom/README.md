<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja 供应链报告

`generate.ps1` 是一个很薄的发布证据编排器，不实现许可证识别器，也不替代成熟工具：

- npm 许可证数据来自 `pnpm licenses list --json`；
- Rust 依赖、来源和 SPDX 表达式来自 `cargo metadata --locked --offline`；
- Java 依赖图和许可证/哈希/来源引用来自 CycloneDX Maven plugin 生成的 BOM；脚本只
  在离线阶段校验和规范化该 BOM，不重新解析 Maven 依赖。

## 离线报告

在仓库根目录执行：

```powershell
pwsh -NoProfile -File scripts/release/sbom/generate.ps1 `
  -MavenBomPath app-server/target/ja-app-server-bom.json
```

脚本只读取固定的 manifest/lockfile、许可证入口和显式传入的 Maven BOM/产物路径，输出
到被 `.gitignore` 忽略的 `release/sbom/`：

- `node-licenses.json`：去除本机路径后的 pnpm 许可证清单；
- `cargo-packages.json`：去除绝对路径后的 Cargo 包、来源、许可证和 lock checksum；
- `maven-cyclonedx.json`：去除 CycloneDX UUID/时间戳后的稳定 BOM；
- `dependency-license-report.json`：统一报告、输入哈希、工具身份和阻塞码；
- `provenance.json`：源 commit、证据哈希、对应源码/许可证归档状态；
- `SHA256SUMS`：所有上述证据文件的 SHA-256，清单自身不列入以避免自引用循环。

同样的 commit、lockfile 和 BOM 输入应生成相同的证据文件。默认只生成报告并以
`status=blocked` 记录不完整条件；发布门禁使用：

```powershell
pwsh -NoProfile -File scripts/release/sbom/generate.ps1 `
  -MavenBomPath app-server/target/ja-app-server-bom.json `
  -CorrespondingSourcePath <对应源码归档或源码目录> `
  -ArtifactPath <实际安装包或 bundle 目录> `
  -FailOnBlocker
```

`-FailOnBlocker` 遇到未完成供应链条件返回退出码 `2`；工具执行或输入损坏返回退出码
`1`。报告中的稳定阻塞码包括：

- `MAVEN_BOM_INPUT_MISSING`：没有预先生成的 CycloneDX Java BOM；
- `PROJECT_LICENSE_METADATA_MISMATCH`：BOM 根组件没有声明 Ja 的
  `GPL-3.0-or-later`；
- `LICENSE_ARCHIVE_EMPTY`：`LICENSES/approved/text/` 没有经过核对的第三方许可证正文；
- `LICENSE_ARCHIVE_MANIFEST_MISSING`：没有把正文 hash 映射到依赖和来源的归档 manifest；
- `LICENSE_ARCHIVE_MANIFEST_INVALID`：归档 manifest 不是当前 schema 或结构损坏；
- `LICENSE_ARCHIVE_INTEGRITY_INVALID`：正文集合、manifest 引用与 hash-addressed 文件名不一致；
- `LICENSE_ARCHIVE_INPUT_STALE`：已批准归档绑定的 lockfile/manifest/BOM 哈希不是当前输入；
- `LICENSE_ARCHIVE_REVIEW_PENDING`：归档已生成但仍等待明确的来源/法律复核批准；
- `ARTIFACTS_NOT_PROVIDED`：没有实际安装包/ bundle 可供校验；
- `CORRESPONDING_SOURCE_NOT_PROVIDED`：没有 GPL 对应源码归档或持久源码提供方式；
- `GIT_TREE_DIRTY`：来源不是干净 commit。

## 候选许可证归档

可先在离线缓存中生成候选原文和组件映射，供发布 owner 逐项复核：

```powershell
pwsh -NoProfile -File scripts/release/sbom/collect-license-candidates.ps1 `
  -MavenBomPath app-server/target/ja-app-server-bom.json `
  -OutputDirectory release/sbom/license-candidate-<run-id>
```

最终 lockfile 稳定后可对同一生成目录显式传入 `-ReplaceExisting`。该模式只接受
`README.md`、`manifest.json` 和纯 hash-addressed `text/*.txt` 的候选目录形状，发现未知
文件或子目录即拒绝替换，不会递归清理任意目录。

该脚本只复制 npm 包目录、Cargo registry/source 和 Maven 本地 JAR 中实际存在的
`LICENSE`/`LICENCE`/`COPYING`/`NOTICE` 字节，并按 SHA-256 去重；`manifest.json` 会列出
每个组件的声明、来源、映射和缺失项。输出状态固定为 `candidate-review-pending`，不计入
`LICENSES/approved/`，也不会自动关闭来源/法律复核门。缺少原文时必须从固定的
官方来源补齐并保留版权/NOTICE，不能根据 SPDX 名称自行重写文本。

候选 schema 3 会按角色记录 `package.json`、`pnpm-lock.yaml`、根/桌面 Cargo manifest、
根 `Cargo.lock`、`app-server/pom.xml` 与 Maven BOM 的内容身份。manifest/lockfile 使用原始
bytes SHA-256；BOM 使用 `cyclonedx-dependency-graph-v1`，去除生成 timestamp/UUID 并排序
component/dependency 集合后再计算 SHA-256，因此相同依赖图的重新生成不会制造假漂移。
promotion 会在复制任何归档前重新计算全部哈希，真实输入变化要求从最终 lockfile 重新生成
候选；历史 495 项归档不属于当前候选输入，不能机械恢复。

不改变审核状态的完整性验证使用同一 promotion 入口：

```powershell
pwsh -NoProfile -File scripts/release/sbom/promote-license-candidates.ps1 `
  -CandidateDirectory release/sbom/license-candidate-<run-id> -ValidateOnly
```

它会校验 7 类输入哈希、所有 mapping/text bytes、hash-addressed 文件名和候选 summary，
不会创建 `LICENSES/approved`，也不等于 source review。

候选经过逐项来源复核后，可用固定 SPDX 数据提交生成 hash-addressed archive：

```powershell
pwsh -NoProfile -File scripts/release/sbom/promote-license-candidates.ps1 `
  -CandidateDirectory release/sbom/license-candidate-<run-id> `
  -AllowNetwork -ConfirmSourceReview
```

该命令默认生成 `status=source-verified-pending-legal-review`，不会把“目录非空”误当作
发布批准；只有发布 owner 完成版权/NOTICE、Native/Tauri 再分发和 GPL 兼容性复核后，才
能显式增加 `-MarkApproved` 生成 `status=approved`。SPDX 文本来源由固定 commit、URL 和
SHA-256 写入 `LICENSES/approved/manifest.json`，已有非空归档不会被覆盖。
`generate.ps1` 还会重新校验批准归档 schema 4、正文哈希/引用闭集，并把本次显式 Maven BOM 与
当前 lockfile/manifest 哈希和批准归档逐角色对账。

## Java BOM 的边界

当前 `cyclonedx-maven-plugin:2.9.1:makeBom` 会声明 Maven 在线执行要求，即使
本地缓存已经存在插件，因此不能把它伪装成离线生成。由受控网络准备阶段执行：

```powershell
mvn -f app-server/pom.xml `
  org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom `
  -DskipTests -DoutputFormat=json -DoutputName=ja-app-server-bom
```

随后把生成的 BOM 作为受控输入，在干净/离线环境运行本报告脚本。发布 owner 仍需固定
该插件及其缓存/下载来源，并在 CI 中保留 BOM 的 SHA-256；没有 BOM、完整 license
archive、对应源码或法律复核时，不得把报告状态改写为 complete。

脚本不会从依赖名称猜测许可证，不会复制没有清晰来源/许可证的源码，也不会把
`LICENSES/` 的占位 README 当作第三方 license archive。
