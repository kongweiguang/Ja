<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja 发布编排

`.github/workflows/native-app-server.yml` 同时承担两种明确分开的路径：

- `pull_request`、`main` 和普通手工运行：只构建 unsigned Native/NSIS/DMG smoke，不需要签名凭据，也不能作为发布证据。
- `v*` 标签或手工 `release=true`：先通过凭据门禁，再由 Tauri 完成 Windows Authenticode 和 macOS Developer ID/公证；任一凭据、签名、时间戳、staple 或验证缺失都会失败。

正式路径只接受 GitHub Secrets，不接受命令行参数、仓库文件或普通环境变量中的私钥：

Windows：`WINDOWS_CERTIFICATE`（base64 PFX）、`WINDOWS_CERTIFICATE_PASSWORD`、`WINDOWS_CERTIFICATE_THUMBPRINT`、可选 `WINDOWS_TIMESTAMP_URL`。

macOS：`APPLE_CERTIFICATE`、`APPLE_CERTIFICATE_PASSWORD`、`APPLE_SIGNING_IDENTITY`、`APPLE_ID`、`APPLE_PASSWORD`、`APPLE_TEAM_ID`。

桌面更新签名：`TAURI_SIGNING_PRIVATE_KEY`，以及密钥设有密码时所需的 `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`。私钥必须与应用内置 updater 公钥匹配，不得为通过构建而临时替换。

Windows 证书由 `scripts/release/prepare-windows-signing.ps1` 临时导入当前用户证书存储，并只向后续步骤输出配置路径、signtool 路径和清理用 thumbprint。工作流结束时会删除 PFX、临时配置和证书对象。

`v*` 标签路径在 Native 矩阵通过后，汇总签名产物并生成 `latest.json`，自动创建未公开的 GitHub draft Release；手工 `release=true` 不执行该汇总任务。工作流不会自动公开 Release，也不会覆盖同名 Release。

公开发布仍须在签名、供应链归档、安装与更新验收全部通过后，由发布 owner 在用户授权范围内执行。缺少凭据或产物时保持未公开；已经公开的版本不得移动 tag 或静默复用版本号，修复应使用新版本。
