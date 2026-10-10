// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::app_runtime::{
    ApprovalResponseInput, ConfigurationRequest, ConfigurationResponse, EventSink, HistoryRequest,
    HistoryResponse, ManualRecoveryConfirmation, RuntimeBridgePort, RuntimeCommandError,
    RuntimeHost, RuntimePlatformPort, RuntimeRecoveryState, RuntimeStatus, RuntimeStatusKind,
    RuntimeStorageInfo, SettingsRequest, SettingsResponse, TurnAccepted, TurnCancelInput,
    TurnCancelResult, TurnStartInput, WorkspaceDto, WorkspaceKind, WorkspaceOpenInput,
    WorkspaceRuntimeSource,
};
use crate::preview::local_file::{
    PreviewFileKind, browser_document_signature, classify_text_file, resolve_file,
    resolve_reveal_target,
};
use crate::preview::{PreviewErrorCode, PreviewMode, PreviewResolveFileInput};
use crate::runtime_test_support::RuntimeHostHarness;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};

/// 创建隔离的磁盘样本，真实文件读取只放在 integration target。
struct TemporaryFile(PathBuf);

/// 将目录 fixture 的清理边界限制在本测试生成的唯一临时路径。
struct TemporaryDirectory(PathBuf);

impl Drop for TemporaryDirectory {
    /// 只递归删除本测试以随机 UUID 创建的目录树，不接触外部 workspace。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

impl Drop for TemporaryFile {
    /// 只删除当前测试唯一创建的样本文件。
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// 精确写入字节样本，验证文件系统读取与有界解码的真实交界。
fn temporary_file(name: &str, bytes: &[u8]) -> TemporaryFile {
    let path = std::env::temp_dir().join(format!(
        "ja-preview-{}-{name}",
        uuid::Uuid::new_v4().simple()
    ));
    let mut file = File::create(&path).expect("create fixture");
    file.write_all(bytes).expect("write fixture");
    TemporaryFile(path)
}

/// 创建隔离的真实 workspace 根，用于覆盖相对路径经过 Host capability 绑定的链路。
fn temporary_directory() -> TemporaryDirectory {
    let path = std::env::temp_dir().join(format!(
        "ja-preview-workspace-{}",
        uuid::Uuid::new_v4().simple()
    ));
    fs::create_dir(&path).expect("create workspace fixture");
    TemporaryDirectory(path)
}

/// 用固定 Ready 状态的 fake bridge 只替代 Java RPC，保留生产 Host 的 workspace 注册与 resolver。
struct ConfiguredWorkspaceBridge {
    root: PathBuf,
}

/// 组合 fake bridge 与根目录策略，避免集成测试启动 Java sidecar 或借用用户配置。
struct ConfiguredWorkspacePlatform {
    root: PathBuf,
    bridge: std::sync::Arc<ConfiguredWorkspaceBridge>,
}

impl ConfiguredWorkspacePlatform {
    /// 将同一个规范 workspace 根注入 fake Java identity 签发和原生 Host admission。
    fn new(root: PathBuf) -> Self {
        Self {
            bridge: std::sync::Arc::new(ConfiguredWorkspaceBridge { root: root.clone() }),
            root,
        }
    }
}

/// 返回 fake 当前 generation 的 Ready 投影，让 resolver 经过真实 Host readiness gate。
fn configured_workspace_ready_status() -> RuntimeStatus {
    RuntimeStatus {
        status: RuntimeStatusKind::Ready,
        generation: 1,
        server_instance_id: Some("srv_preview_fixture".to_owned()),
    }
}

impl RuntimeBridgePort for ConfiguredWorkspaceBridge {
    /// Fake 启动直接返回 Ready；此测试关注 workspace resolver 而非协议生命周期。
    fn start(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        Ok(configured_workspace_ready_status())
    }

    /// 保持 Host 清理路径可用，返回固定 Stopped 终态。
    fn stop(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        Ok(RuntimeStatus {
            status: RuntimeStatusKind::Stopped,
            generation: 1,
            server_instance_id: None,
        })
    }

    /// 所有 workspace 查询都观察同一 Ready generation。
    fn state(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        Ok(configured_workspace_ready_status())
    }

    /// 配置管理不在本测试边界内，保持失败关闭。
    fn configuration(
        &self,
        _request: ConfigurationRequest,
    ) -> Result<ConfigurationResponse, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// 模拟 Java 签发 workspace identity；返回值随后仍须经 RuntimeHost 验证并绑定 capability handle。
    fn workspace_open(
        &self,
        root: PathBuf,
        display_name: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        if root != self.root {
            return Err(RuntimeCommandError::invalid_params());
        }
        Ok(WorkspaceDto {
            workspace_id: "ws_preview_fixture".to_owned(),
            root: root.to_string_lossy().into_owned(),
            display_name,
            trust: "untrusted".to_owned(),
            revision: 1,
            kind: WorkspaceKind::Project,
        })
    }

    /// 健康检查无外部进程，Ready fake 即满足该最小合同。
    fn health(&self) -> Result<(), RuntimeCommandError> {
        Ok(())
    }

    /// Turn admission 与文件 resolver 无关，拒绝误用 fake bridge。
    fn turn_start(&self, _input: TurnStartInput) -> Result<TurnAccepted, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// 取消不属于该 fixture，拒绝伪造 Java Turn 结果。
    fn turn_cancel(
        &self,
        _input: TurnCancelInput,
    ) -> Result<TurnCancelResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// Approval 不属于文件解析测试，保持失败关闭。
    fn approval_respond(&self, _input: ApprovalResponseInput) -> Result<(), RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// History 不属于该 fixture，避免把测试 bridge 扩大成协议替身。
    fn history(&self, _request: HistoryRequest) -> Result<HistoryResponse, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// Settings 不属于文件解析测试，保持失败关闭。
    fn settings(&self, _request: SettingsRequest) -> Result<SettingsResponse, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// Fake 不拥有额外清理资源，确认 shutdown 可完成。
    fn shutdown(&self) -> Result<(), RuntimeCommandError> {
        Ok(())
    }

    /// 退出 deadline 不影响 fake，保留与生产 Host 一致的接口语义。
    fn shutdown_until(&self, _deadline: std::time::Instant) -> Result<(), RuntimeCommandError> {
        Ok(())
    }

    /// Fake 未启动线程或进程，始终满足退出就绪条件。
    fn exit_ready(&self) -> bool {
        true
    }

    /// Fake 没有需要持久化的强制退出事实。
    fn record_forced_exit(&self) {}
}

impl RuntimePlatformPort for ConfiguredWorkspacePlatform {
    /// RuntimeHost 使用这一 bridge 验证 Java workspace projection 与本地 binding 流程。
    fn create_bridge(&self) -> Result<std::sync::Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        Ok(self.bridge.clone())
    }

    /// 集成 fixture 不恢复真实 runtime，因此启动门始终开放。
    fn recovery_state(&self) -> RuntimeRecoveryState {
        RuntimeRecoveryState {
            required: false,
            acknowledgeable: false,
            recovery_id: None,
            revision: None,
        }
    }

    /// 没有恢复 marker 时，确认请求保持为无副作用的关闭状态。
    fn acknowledge_recovery(
        &self,
        _confirmation: &ManualRecoveryConfirmation,
    ) -> Result<RuntimeRecoveryState, RuntimeCommandError> {
        Ok(self.recovery_state())
    }

    /// Storage 投影只引用隔离 fixture 根，不读取用户目录。
    fn storage_info(&self) -> RuntimeStorageInfo {
        RuntimeStorageInfo {
            native_image: false,
            storage_recovery: None,
            data_path: self.root.to_string_lossy().into_owned(),
            log_path: None,
            cache_path: None,
            last_backup: None,
        }
    }

    /// 只接受已 canonicalize 的 fixture 根，模拟生产 platform 的 Workspace 根准入。
    fn workspace_source(
        &self,
        cwd: &str,
        display_name: Option<&str>,
    ) -> Result<WorkspaceRuntimeSource, RuntimeCommandError> {
        if Path::new(cwd) != self.root.as_path() {
            return Err(RuntimeCommandError::invalid_params());
        }
        Ok(WorkspaceRuntimeSource {
            root: self.root.clone(),
            display_name: display_name.unwrap_or("Preview fixture").to_owned(),
        })
    }
}

/// 由 fake Java identity 配合生产 RuntimeHost 注册真实目录句柄，返回可供解析调用的 binding。
fn runtime_with_configured_workspace(root: &Path) -> (RuntimeHost, String) {
    let root = fs::canonicalize(root).expect("canonicalize workspace fixture");
    let platform = std::sync::Arc::new(ConfiguredWorkspacePlatform::new(root.clone()));
    let runtime = RuntimeHost::compose(platform);
    let workspace = runtime
        .start_and_open_workspace(WorkspaceOpenInput {
            cwd: root.to_string_lossy().into_owned(),
            display_name: Some("Preview fixture".to_owned()),
        })
        .expect("configure workspace through Host");
    (runtime, workspace.workspace_id)
}

/// 构造未启动 sidecar 的 RuntimeHost；绝对目标解析不需要请求 App Server。
fn runtime_host_for_absolute_resolution() -> RuntimeHost {
    let run_dir = std::env::temp_dir().join("ja-preview-file-resolution-runtime");
    let config = RuntimeHostHarness::launch_config(
        run_dir.join("unused-app-server.exe"),
        Vec::new(),
        run_dir,
    );
    let sink: EventSink = std::sync::Arc::new(|_| Ok(()));
    RuntimeHostHarness::new(config, sink).host()
}

/// 相对路径必须通过 Java-issued identity 解析到 RuntimeHost 已配置的物理 Workspace。
#[test]
fn workspace_relative_path_resolution_uses_configured_canonical_binding() {
    let workspace = temporary_directory();
    let nested = workspace.0.join("资料");
    fs::create_dir(&nested).expect("create nested fixture directory");
    let file_path = nested.join("说明 文件.txt");
    fs::write(&file_path, "workspace-bound text\n").expect("write workspace fixture");
    let canonical = fs::canonicalize(&file_path).expect("canonicalize workspace file");
    let (runtime, workspace_id) = runtime_with_configured_workspace(&workspace.0);

    let resolution = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: "资料/说明 文件.txt".to_owned(),
            workspace_id: Some(workspace_id.clone()),
            line: None,
            column: None,
            preview_mode: PreviewMode::Editor,
        },
    )
    .expect("resolve relative workspace target");

    assert_eq!(
        resolution.workspace_id.as_deref(),
        Some(workspace_id.as_str())
    );
    assert_eq!(
        resolution.canonical_path,
        canonical.to_string_lossy().into_owned()
    );
    assert_eq!(
        resolution.workspace_relative_path.as_deref(),
        Some("资料/说明 文件.txt")
    );
    assert!(resolution.within_workspace);
    assert!(!resolution.read_only);
    assert_eq!(
        resolution.content.as_deref(),
        Some("workspace-bound text\n")
    );
}

/// 真文件解析 canonicalize 已存在的任意绝对路径，并保留文本位置且标记为只读。
#[test]
fn absolute_text_file_resolution_returns_canonical_read_only_target() {
    let fixture = temporary_file("说明 文件.txt", b"line one\nline two\n");
    let runtime = runtime_host_for_absolute_resolution();
    let target = fixture.0.to_string_lossy().into_owned();
    let resolution = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target,
            workspace_id: None,
            line: Some(2),
            column: Some(3),
            preview_mode: PreviewMode::Editor,
        },
    )
    .expect("resolve existing text");
    let canonical = fs::canonicalize(&fixture.0).expect("canonical fixture");

    assert_eq!(
        resolution.canonical_path,
        canonical.to_string_lossy().into_owned()
    );
    assert_eq!(
        resolution.display_name,
        canonical
            .file_name()
            .expect("fixture basename")
            .to_string_lossy()
    );
    assert_eq!(resolution.kind, PreviewFileKind::Text);
    assert_eq!(resolution.content.as_deref(), Some("line one\nline two\n"));
    assert_eq!(resolution.line, Some(2));
    assert_eq!(resolution.column, Some(3));
    assert!(resolution.read_only);
    assert!(!resolution.within_workspace);
    assert!(resolution.workspace_relative_path.is_none());
}

/// HTML 默认保留源码；显式 Browser 才返回 file URL，目录则返回无 URL 的独立类型。
#[test]
fn html_preview_and_directory_resolution_have_explicit_closed_kinds() {
    let fixture = temporary_file("本地 页面.html", b"<html><img src='pixel.svg'></html>");
    let runtime = runtime_host_for_absolute_resolution();
    let file_url = url::Url::from_file_path(&fixture.0)
        .expect("file URL")
        .to_string();
    let source = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: file_url.clone(),
            workspace_id: None,
            line: None,
            column: None,
            preview_mode: PreviewMode::Editor,
        },
    )
    .expect("resolve HTML source");
    assert_eq!(source.kind, PreviewFileKind::Text);
    assert!(
        source
            .content
            .as_deref()
            .unwrap_or_default()
            .starts_with("<html>")
    );
    assert!(source.file_url.is_some());

    let browser = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: file_url,
            workspace_id: None,
            line: None,
            column: None,
            preview_mode: PreviewMode::Browser,
        },
    )
    .expect("explicitly preview HTML");
    assert_eq!(browser.kind, PreviewFileKind::Browser);
    assert!(browser.content.is_none());
    assert_eq!(browser.mime_type.as_deref(), Some("text/html"));
    assert!(
        browser
            .file_url
            .as_deref()
            .is_some_and(|url| url.starts_with("file:///"))
    );

    let extensionless_html = temporary_file("document.data", b"<!doctype html><html></html>");
    let extensionless_target = extensionless_html.0.to_string_lossy().into_owned();
    let source = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: extensionless_target.clone(),
            workspace_id: None,
            line: None,
            column: None,
            preview_mode: PreviewMode::Editor,
        },
    )
    .expect("extensionless HTML source");
    assert_eq!(source.kind, PreviewFileKind::Text);
    let preview = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: extensionless_target,
            workspace_id: None,
            line: None,
            column: None,
            preview_mode: PreviewMode::Browser,
        },
    )
    .expect("explicit extensionless HTML preview");
    assert_eq!(preview.kind, PreviewFileKind::Browser);
    assert!(preview.content.is_none());

    let pdf_without_extension = temporary_file("document", b"%PDF-1.7\nfixture");
    let pdf = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: pdf_without_extension.0.to_string_lossy().into_owned(),
            workspace_id: None,
            line: None,
            column: None,
            preview_mode: PreviewMode::Editor,
        },
    )
    .expect("signature-recognized PDF");
    assert_eq!(pdf.kind, PreviewFileKind::Browser);
    assert_eq!(pdf.mime_type, None);

    let opaque = temporary_file("opaque.bin", &[0, 1, 2, 0xff]);
    let unsupported = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: opaque.0.to_string_lossy().into_owned(),
            workspace_id: None,
            line: None,
            column: None,
            preview_mode: PreviewMode::Editor,
        },
    )
    .expect("opaque binary is resolved but not browser-enabled");
    assert_eq!(unsupported.kind, PreviewFileKind::Unsupported);

    let missing = std::env::temp_dir()
        .join(format!("ja-preview-missing-{}.txt", uuid::Uuid::new_v4()))
        .to_string_lossy()
        .into_owned();
    assert_eq!(
        resolve_file(
            &runtime,
            &PreviewResolveFileInput {
                target: missing,
                workspace_id: None,
                line: None,
                column: None,
                preview_mode: PreviewMode::Editor,
            }
        )
        .expect_err("missing target")
        .code(),
        PreviewErrorCode::FileNotFound
    );
    let directory = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: std::env::temp_dir().to_string_lossy().into_owned(),
            workspace_id: None,
            line: None,
            column: None,
            preview_mode: PreviewMode::Editor,
        },
    )
    .expect("directory target resolves without enumerating it");
    assert_eq!(directory.kind, PreviewFileKind::Directory);
    assert!(directory.file_url.is_none());
    assert!(directory.content.is_none());
    assert!(directory.read_only);
    assert!(!directory.within_workspace);
    let wire = serde_json::to_value(directory).expect("serialize directory resolution");
    assert_eq!(wire["kind"], "directory");
    assert!(wire["fileUrl"].is_null());
    assert!(wire["workspaceRelativePath"].is_null());
}

/// 明确路径只解析被点中的 ignored 子目录，不递归扫描，并保留工作区相对定位语义。
#[test]
fn nested_ignored_directory_resolves_as_workspace_directory() {
    let workspace = temporary_directory();
    let nested = workspace.0.join(".git").join("objects");
    fs::create_dir_all(&nested).expect("create explicit ignored directory");
    let (runtime, workspace_id) = runtime_with_configured_workspace(&workspace.0);
    let resolution = resolve_file(
        &runtime,
        &PreviewResolveFileInput {
            target: ".git/objects".to_owned(),
            workspace_id: Some(workspace_id),
            line: None,
            column: None,
            preview_mode: PreviewMode::Editor,
        },
    )
    .expect("resolve explicitly named ignored directory");

    assert_eq!(resolution.kind, PreviewFileKind::Directory);
    assert_eq!(
        resolution.workspace_relative_path.as_deref(),
        Some(".git/objects")
    );
    assert!(resolution.within_workspace);
    assert!(!resolution.read_only);
    assert!(resolution.file_url.is_none());
    assert!(resolution.content.is_none());
}

/// 尾分隔符只用于表明目录；中文与空格路径仍由 Workspace 核验且目录不得签发 file URL。
#[test]
fn workspace_relative_directory_targets_accept_trailing_separators() {
    let workspace = temporary_directory();
    let nested = workspace.0.join("web").join("图形 示例");
    fs::create_dir_all(&nested).expect("create directory with Unicode and spaces");
    let (runtime, workspace_id) = runtime_with_configured_workspace(&workspace.0);

    for target in ["web/图形 示例/", "web\\图形 示例\\"] {
        let resolution = resolve_file(
            &runtime,
            &PreviewResolveFileInput {
                target: target.to_owned(),
                workspace_id: Some(workspace_id.clone()),
                line: None,
                column: None,
                preview_mode: PreviewMode::Editor,
            },
        )
        .expect("resolve an explicitly linked directory");

        assert_eq!(resolution.kind, PreviewFileKind::Directory, "{target}");
        assert_eq!(
            resolution.workspace_relative_path.as_deref(),
            Some("web/图形 示例"),
            "{target}"
        );
        assert!(resolution.within_workspace, "{target}");
        assert!(!resolution.read_only, "{target}");
        assert!(resolution.file_url.is_none(), "{target}");
        assert!(resolution.content.is_none(), "{target}");
    }

    for target in [".", "./"] {
        assert_eq!(
            resolve_file(
                &runtime,
                &PreviewResolveFileInput {
                    target: target.to_owned(),
                    workspace_id: Some(workspace_id.clone()),
                    line: None,
                    column: None,
                    preview_mode: PreviewMode::Editor,
                },
            )
            .expect_err("dot aliases must remain rejected by the existing Workspace guard")
            .code(),
            PreviewErrorCode::FileTargetInvalid,
            "{target}"
        );
    }
}

/// Explorer reveal 核验文件与目录且保留外部绝对路径的 UTF-8/空格 spelling。
#[test]
fn reveal_resolves_external_file_and_directory_targets() {
    let fixture = temporary_file("资料 空间.svg", b"<svg></svg>");
    let directory = temporary_directory();
    let runtime = runtime_host_for_absolute_resolution();
    let input = PreviewResolveFileInput {
        target: fixture.0.to_string_lossy().into_owned(),
        workspace_id: None,
        line: None,
        column: None,
        preview_mode: PreviewMode::Editor,
    };
    let file_target = resolve_reveal_target(&runtime, &input).expect("reveal file target");
    assert_eq!(
        file_target.path,
        fs::canonicalize(&fixture.0).expect("canonical fixture")
    );
    assert_eq!(file_target.kind, crate::workspace::domain::EntryKind::File);

    let directory_input = PreviewResolveFileInput {
        target: directory.0.to_string_lossy().into_owned(),
        ..input.clone()
    };
    let directory_target =
        resolve_reveal_target(&runtime, &directory_input).expect("reveal directory target");
    assert_eq!(
        directory_target.path,
        fs::canonicalize(&directory.0).expect("canonical directory")
    );
    assert_eq!(
        directory_target.kind,
        crate::workspace::domain::EntryKind::Directory
    );

    let missing = PreviewResolveFileInput {
        target: format!("{}-missing", input.target),
        ..input
    };
    assert_eq!(
        resolve_reveal_target(&runtime, &missing)
            .expect_err("missing target")
            .code(),
        PreviewErrorCode::FileNotFound
    );
}

/// 带 BOM 的 UTF-8 与 UTF-16 文档必须保持文本分类，不能误判为浏览器二进制。
#[test]
fn resolver_text_classification_reuses_workspace_decoding_rules() {
    let mut utf8 = vec![0xef, 0xbb, 0xbf];
    utf8.extend_from_slice("你好".as_bytes());
    let fixture = temporary_file("readme.data", &utf8);
    let (kind, content, truncated) = classify_text_file(
        File::open(&fixture.0).expect("open UTF-8"),
        utf8.len() as u64,
    )
    .expect("classify UTF-8");
    assert_eq!(kind, PreviewFileKind::Text);
    assert_eq!(content.as_deref(), Some("你好"));
    assert!(!truncated);

    let mut utf16 = vec![0xff, 0xfe];
    for unit in "文档".encode_utf16() {
        utf16.extend_from_slice(&unit.to_le_bytes());
    }
    let fixture = temporary_file("utf16.bin", &utf16);
    let (kind, content, truncated) = classify_text_file(
        File::open(&fixture.0).expect("open UTF-16"),
        utf16.len() as u64,
    )
    .expect("classify UTF-16");
    assert_eq!(kind, PreviewFileKind::Text);
    assert_eq!(content.as_deref(), Some("文档"));
    assert!(!truncated);
}

/// 未知扩展名按字节解码；文本进入 Files，无法解码的二进制失败关闭。
#[test]
fn unknown_plain_text_and_binary_are_routed_by_bounded_content() {
    let text = temporary_file("README", b"a plain README without an extension");
    let (kind, content, truncated) = classify_text_file(
        File::open(&text.0).expect("open README"),
        fs::metadata(&text.0).expect("README metadata").len(),
    )
    .expect("classify README");
    assert_eq!(kind, PreviewFileKind::Text);
    assert_eq!(
        content.as_deref(),
        Some("a plain README without an extension")
    );
    assert!(!truncated);

    let binary = temporary_file("opaque.dat", &[0, 1, 2, 0xff]);
    let (kind, content, truncated) =
        classify_text_file(File::open(&binary.0).expect("open binary"), 4)
            .expect("classify binary");
    assert_eq!(kind, PreviewFileKind::Unsupported);
    assert!(content.is_none());
    assert!(!truncated);

    let pdf_without_extension = temporary_file("document", b"%PDF-1.7\n...");
    let mut opened = File::open(&pdf_without_extension.0).expect("open extensionless PDF");
    assert!(browser_document_signature(&mut opened).expect("read signature"));
}

/// 1 MiB 文本预览保留有效 UTF-8 前缀，并明确标记后续仍有未读取字节。
#[test]
fn large_text_returns_a_utf8_safe_bounded_prefix() {
    const LIMIT: usize = 1024 * 1024;
    let mut bytes = vec![b'a'; LIMIT - 1];
    bytes.extend_from_slice("😀".as_bytes());
    let fixture = temporary_file("large.rs", &bytes);
    let (kind, content, truncated) = classify_text_file(
        File::open(&fixture.0).expect("open large text"),
        bytes.len() as u64,
    )
    .expect("classify large text");
    assert_eq!(kind, PreviewFileKind::Text);
    assert!(truncated);
    assert_eq!(content.as_deref().map(str::len), Some(LIMIT - 1));
}
