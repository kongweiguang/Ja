// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::app_runtime::{
    EventEmitError, EventSink, HistoryRequest, HistoryResponse, LaunchConfig, RuntimeHost,
    WorkspaceListParams, WorkspaceOpenInput,
};
use super::attachments::AttachmentIngress;
use super::native_shortcuts::NativeShortcutHost;
use super::preview::PreviewCommandHost;
use super::terminal::TerminalCommandHost;
use crate::runtime_test_support::RuntimeHostHarness;
use ja_runtime::app_server_process::{NativeClientIdentity, SharedAppServerClient, SidecarConfig};
use std::ffi::OsString as TestOsString;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::{Duration, Instant};
use tauri::Manager;

// `handle_full_exit_requested` 在单次生产退出中可同步占满 20 秒 deadline；测试必须等待
// 略高于该上限，才能断言 handler 返回后的事件顺序，而非把受载 CI 的正常清理误判为超时。
const CLEAN_FULL_EXIT_EVENT_WAIT: Duration = Duration::from_secs(25);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct FullExitCleanupFacts {
    runtime_bridge_ready: bool,
    terminal_empty: bool,
    native_shortcuts_revoked: bool,
    preview_active_count: Option<usize>,
    workspace_watchers_empty: bool,
    attachments_shutdown_complete: bool,
    attachment_previews_ready: bool,
}

impl FullExitCleanupFacts {
    /// 退出门禁与生产 owner 条件一致；Preview 查询失败仍按关闭未完成处理。
    fn is_ready(&self) -> bool {
        self.runtime_bridge_ready
            && self.terminal_empty
            && self.native_shortcuts_revoked
            && self.preview_active_count == Some(0)
            && self.workspace_watchers_empty
            && self.attachments_shutdown_complete
            && self.attachment_previews_ready
    }
}

/// 将 MockRuntime 生命周期句柄收为具名字段，调用方不依赖复杂匿名元组的字段顺序。
struct FullExitMockFixture {
    app_handle: tauri::AppHandle<tauri::test::MockRuntime>,
    window: tauri::WebviewWindow<tauri::test::MockRuntime>,
    receiver: mpsc::Receiver<(&'static str, Option<FullExitCleanupFacts>)>,
    runner: thread::JoinHandle<()>,
}

#[cfg(windows)]
const TEST_BACKEND_PROCESS_SCRIPT: &str = r#"
$ErrorActionPreference = 'Stop'
function Get-OwnedProcess {
    @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {
        $_.ProcessId -ne $PID -and $_.CommandLine -and
        $_.CommandLine.Contains($env:JA_TEST_LIFECYCLE_MARKER) -and
        $_.CommandLine.Contains($env:JA_TEST_LIFECYCLE_JAR) -and
        $_.CommandLine.Contains($env:JA_TEST_LIFECYCLE_DATA_ARG)
    })
}
try {
    switch ($env:JA_TEST_PROCESS_ACTION) {
        'list' {
            foreach ($process in @(Get-OwnedProcess)) {
                $creationFileTime = $process.CreationDate.ToFileTimeUtc()
                [Console]::Out.WriteLine(([string]$process.ProcessId) + "`t" + ([string]$creationFileTime))
            }
            exit 0
        }
        'wait' {
            $deadline = [DateTimeOffset]::UtcNow.AddMilliseconds([int]$env:JA_TEST_WAIT_MILLIS)
            do {
                $matches = @(Get-OwnedProcess)
                if ($matches.Count -eq 0) { exit 0 }
                Start-Sleep -Milliseconds 50
            } while ([DateTimeOffset]::UtcNow -lt $deadline)
            foreach ($process in $matches) {
                $creationFileTime = $process.CreationDate.ToFileTimeUtc()
                [Console]::Out.WriteLine(([string]$process.ProcessId) + "`t" + ([string]$creationFileTime))
            }
            exit 3
        }
        'terminate' {
            $expectedPid = [uint32]$env:JA_TEST_PROCESS_PID
            $expectedCreationFileTime = [long]$env:JA_TEST_PROCESS_CREATION_FILETIME
            $matches = @(Get-OwnedProcess | Where-Object {
                $_.ProcessId -eq $expectedPid -and
                $_.CreationDate.ToFileTimeUtc() -eq $expectedCreationFileTime
            })
            if ($matches.Count -ne 1) { exit 4 }
            $result = Invoke-CimMethod -InputObject $matches[0] -MethodName Terminate
            if ($result.ReturnValue -ne 0) { exit 5 }
            exit 0
        }
        default { exit 6 }
    }
} catch { exit 10 }
"#;

/// 创建完整退出测试独占的临时根，避免修改进程环境或接触真实 app data。
fn test_root(label: &str) -> PathBuf {
    std::env::temp_dir().join(format!(
        "ja-app-lifecycle-{}-{}-{}",
        std::process::id(),
        label,
        uuid::Uuid::new_v4()
    ))
}

/// 在不改变生产启动路径的前提下解析 JDK 25 测试可执行文件，保证 MockRuntime 生命周期测试确定性。
fn full_exit_java() -> PathBuf {
    let java = std::env::var_os("JA_TEST_JAVA")
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var_os("JAVA_HOME").map(|home| {
                PathBuf::from(home).join("bin").join(if cfg!(windows) {
                    "java.exe"
                } else {
                    "java"
                })
            })
        })
        .unwrap_or_else(|| PathBuf::from(if cfg!(windows) { "java.exe" } else { "java" }));
    assert!(java.is_file(), "JA_TEST_JAVA must point to JDK25 java");
    let version = std::process::Command::new(&java)
        .arg("-version")
        .output()
        .expect("inspect JDK25 version");
    let banner = format!(
        "{}{}",
        String::from_utf8_lossy(&version.stdout),
        String::from_utf8_lossy(&version.stderr)
    );
    assert!(
        banner.lines().any(|line| {
            line.split(|character: char| !character.is_ascii_digit())
                .find(|part| !part.is_empty())
                == Some("25")
        }),
        "full-exit test requires JDK25, got {banner:?}"
    );
    java
}

/// 解析 Maven 已构建的唯一 Ja App Server sidecar，避免完整组合根测试维护第二套协议夹具。
/// 从桌面宿主回到 workspace 根定位 Java 产物；保留环境变量供 CI 注入隔离产物。
fn full_exit_jar() -> PathBuf {
    let jar = std::env::var_os("JA_TEST_JAR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("../../..")
                .join("ja-agent")
                .join("ja-app-server")
                .join("target")
                .join("ja-app-server.jar")
        });
    assert!(
        jar.is_file(),
        "build ja-agent/ja-app-server/target/ja-app-server.jar first"
    );
    jar
}

/// 创建隔离 run 目录且不接触真实 app-data 路径，使清理失败可检查又不会引入跨测试状态。
fn full_exit_run_dir(label: &str) -> PathBuf {
    let path = test_root(label);
    fs::create_dir_all(&path).expect("full-exit run directory");
    path
}

/// 只标识由当前测试创建的 JVM；唯一 marker、JAR 和隔离 data 参数共同构成强制回收身份。
struct TestOwnedBackend {
    test_name: &'static str,
    marker: String,
    root: PathBuf,
    jar: PathBuf,
    sidecar: SidecarConfig,
}

/// 首次观测到的 PID 还必须绑定 Windows 进程创建时间，避免 PID 重用后误杀新进程。
#[cfg(windows)]
#[derive(Debug)]
struct OwnedJavaProcess {
    pid: u32,
    creation_filetime: String,
}

impl TestOwnedBackend {
    /// 为每个生命周期测试构造独立 backend；测试 owner 可显式 shutdown，panic 时只终止身份匹配的实例。
    fn new(test_name: &'static str) -> Self {
        let root = full_exit_run_dir("full-exit");
        let java = full_exit_java();
        let jar = full_exit_jar();
        assert!(jar.is_absolute(), "test JAR path must be absolute");
        let home_dir = root.join("home");
        let data_dir = root.join("data");
        let sidecar_run_dir = root.join("runtime");
        let java_logs_dir = root.join("logs").join("java");
        for directory in [&home_dir, &data_dir, &sidecar_run_dir, &java_logs_dir] {
            fs::create_dir_all(directory).expect("test backend directory");
        }

        let marker = format!("{test_name}-{}", uuid::Uuid::new_v4().simple());
        let mut sidecar = SidecarConfig::with_shared_directories(
            java,
            home_dir,
            data_dir,
            sidecar_run_dir,
            java_logs_dir,
        )
        .expect("test backend shared directories");
        sidecar.args.insert(
            0,
            TestOsString::from(format!("-Dja.test.lifecycleMarker={marker}")),
        );
        sidecar.args.insert(1, TestOsString::from("-jar"));
        sidecar.args.insert(2, jar.clone().into_os_string());
        sidecar.ready_timeout = Duration::from_secs(30);

        Self {
            test_name,
            marker,
            root,
            jar,
            sidecar,
        }
    }

    /// 两条连接复用同一 Java 参数和目录；debug 构造先校验原始日志路径再 canonicalize，避免 Windows `\\?\` 前缀被输入校验拒绝。
    fn launch_config(&self) -> LaunchConfig {
        let sidecar = &self.sidecar;
        #[cfg(debug_assertions)]
        {
            let mut config = LaunchConfig::debug_java(
                sidecar.executable.clone(),
                self.jar.clone(),
                sidecar.home_dir.clone(),
                sidecar.data_dir.clone(),
                sidecar.run_dir.clone(),
                self.root.join("logs").join("java"),
            )
            .expect("debug Java25 config");
            config.sidecar.args.insert(
                0,
                TestOsString::from(format!("-Dja.test.lifecycleMarker={}", self.marker)),
            );
            // 冷 JVM 首次启动允许更长预算；清理和所有请求仍使用各自较短的 deadline。
            config.sidecar.ready_timeout = Duration::from_secs(30);
            config
        }
        #[cfg(not(debug_assertions))]
        {
            RuntimeHostHarness::launch_config_with_dirs(
                sidecar.executable.clone(),
                sidecar.args.clone(),
                sidecar.home_dir.clone(),
                sidecar.data_dir.clone(),
                sidecar.run_dir.clone(),
                sidecar.log_dir.clone(),
            )
        }
    }

    /// 只允许显式测试 owner 请求服务 shutdown；Host 的 stop/shutdown 仍只断开自己的客户端。
    fn request_server_shutdown(&self) -> Result<(), &'static str> {
        let mut config = self.sidecar.clone();
        config.ready_timeout = Duration::from_secs(2);
        config.shutdown_timeout = Duration::from_secs(2);
        let identity = NativeClientIdentity::new_current_process()
            .map_err(|_| "test client identity failed")?;
        let mut client = SharedAppServerClient::connect_existing(&config, 1, &identity)
            .map_err(|_| "test backend connection failed")?;
        client
            .stop_server(Duration::from_secs(2))
            .map_err(|_| "test backend rejected shutdown")?;
        client
            .disconnect_until(Instant::now() + Duration::from_secs(2))
            .map_err(|_| "test backend management connection did not close")
    }

    /// 正常路径必须证明服务接受 shutdown 并退出；PID 证据以 test name、唯一 marker、隔离 JAR/data 绑定。
    fn shutdown_and_assert_stopped(&self) {
        #[cfg(windows)]
        {
            let processes = self.matching_processes().expect("inspect test-owned JVM");
            assert_eq!(
                processes.len(),
                1,
                "test marker must identify one JVM: {processes:?}"
            );
            eprintln!(
                "test={} marker={} pid={}",
                self.test_name, self.marker, processes[0].pid
            );
        }
        self.request_server_shutdown()
            .expect("test-owned shared backend shutdown and EOF");
        assert!(
            self.wait_for_process_exit(Duration::from_secs(5)),
            "test-owned backend must exit after shutdown: test={} marker={}",
            self.test_name,
            self.marker
        );
        #[cfg(windows)]
        assert!(
            self.matching_processes()
                .expect("recheck test-owned JVM")
                .is_empty(),
            "test-owned JVM remained after normal shutdown: test={} marker={}",
            self.test_name,
            self.marker
        );
    }

    /// 有界等待只观察包含精确 marker、JAR 和隔离 data 参数的 java.exe。
    fn wait_for_process_exit(&self, timeout: Duration) -> bool {
        #[cfg(windows)]
        {
            self.run_process_query(
                "wait",
                timeout.saturating_add(Duration::from_secs(2)),
                Some(timeout),
                None,
            )
            .is_some_and(|output| output.status.success())
        }
        #[cfg(not(windows))]
        {
            let endpoint = self.sidecar.run_dir.join("app-server.endpoint.json");
            let deadline = Instant::now() + timeout;
            while endpoint.exists() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(25));
            }
            !endpoint.exists()
        }
    }

    /// 只接受唯一匹配进程，强制回收时通过 WMI 进程对象复核 java.exe、JAR、data 与 marker 后再终止。
    fn terminate_matching_process(&self) -> bool {
        #[cfg(windows)]
        {
            let processes = match self.matching_processes() {
                Some(processes) if processes.len() == 1 => processes,
                _ => return false,
            };
            self.run_process_query(
                "terminate",
                Duration::from_secs(3),
                None,
                Some(&processes[0]),
            )
            .is_some_and(|output| output.status.success())
        }
        #[cfg(not(windows))]
        {
            false
        }
    }

    /// CIM 查询严格限定 java.exe 与本测试的 marker/JAR/data，PID 绝不单独作为进程所有权证据。
    #[cfg(windows)]
    fn matching_processes(&self) -> Option<Vec<OwnedJavaProcess>> {
        let output = self.run_process_query("list", Duration::from_secs(3), None, None)?;
        if !output.status.success() {
            return None;
        }
        Some(
            String::from_utf8_lossy(&output.stdout)
                .lines()
                .filter_map(|line| {
                    let (pid, creation_filetime) = line.trim().split_once('\t')?;
                    Some(OwnedJavaProcess {
                        pid: pid.parse().ok()?,
                        creation_filetime: creation_filetime.to_owned(),
                    })
                })
                .collect(),
        )
    }

    /// 运行有外部 deadline 的 PowerShell 查询，并把身份参数放在子进程环境而非命令源码中。
    #[cfg(windows)]
    fn run_process_query(
        &self,
        action: &str,
        timeout: Duration,
        wait_budget: Option<Duration>,
        process: Option<&OwnedJavaProcess>,
    ) -> Option<Output> {
        let mut programs = vec![TestOsString::from("pwsh")];
        if let Some(root) = std::env::var_os("SystemRoot") {
            programs.push(
                PathBuf::from(root)
                    .join("System32")
                    .join("WindowsPowerShell")
                    .join("v1.0")
                    .join("powershell.exe")
                    .into_os_string(),
            );
        }
        let marker = format!("-Dja.test.lifecycleMarker={}", self.marker);
        let data_arg = self
            .sidecar
            .args
            .iter()
            .find_map(|argument| {
                let argument = argument.to_string_lossy();
                argument
                    .starts_with("--data-dir-base64=")
                    .then(|| argument.into_owned())
            })
            .expect("test data identity argument");
        for program in programs {
            let mut command = Command::new(program);
            command
                .args([
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    TEST_BACKEND_PROCESS_SCRIPT,
                ])
                .env("JA_TEST_LIFECYCLE_MARKER", &marker)
                .env("JA_TEST_LIFECYCLE_JAR", self.jar.as_os_str())
                .env("JA_TEST_LIFECYCLE_DATA_ARG", &data_arg)
                .env("JA_TEST_PROCESS_ACTION", action)
                .stdout(Stdio::piped())
                .stderr(Stdio::null());
            if let Some(wait_budget) = wait_budget {
                command.env(
                    "JA_TEST_WAIT_MILLIS",
                    wait_budget
                        .as_millis()
                        .min(u128::from(u32::MAX))
                        .to_string(),
                );
            }
            if let Some(process) = process {
                command
                    .env("JA_TEST_PROCESS_PID", process.pid.to_string())
                    .env(
                        "JA_TEST_PROCESS_CREATION_FILETIME",
                        &process.creation_filetime,
                    );
            }
            match bounded_command_output(command, timeout) {
                Ok(output) => return output,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
                Err(_) => return None,
            }
        }
        None
    }
}

impl Drop for TestOwnedBackend {
    /// 清理受 panic、查询 deadline 与进程身份共同约束，避免测试失败时无限等待或误终止其他 JVM。
    fn drop(&mut self) {
        #[cfg(windows)]
        let active = match self.matching_processes() {
            Some(process_ids) => !process_ids.is_empty(),
            None => true,
        };
        #[cfg(not(windows))]
        let active = self
            .sidecar
            .run_dir
            .join("app-server.endpoint.json")
            .exists();

        if active {
            let _ = self.request_server_shutdown();
            if !self.wait_for_process_exit(Duration::from_secs(3)) {
                let _ = self.terminate_matching_process();
                if !self.wait_for_process_exit(Duration::from_secs(2)) {
                    eprintln!(
                        "test backend cleanup incomplete: test={} marker={}",
                        self.test_name, self.marker
                    );
                    return;
                }
            }
        }
        let _ = fs::remove_dir_all(&self.root);
        let _ = fs::remove_file(&self.root);
    }
}

/// 轮询短命的 PowerShell 控制进程；超时先终止查询本身，不让 RAII 清理无限阻塞。
#[cfg(windows)]
fn bounded_command_output(
    mut command: Command,
    timeout: Duration,
) -> std::io::Result<Option<Output>> {
    let mut child = command.spawn()?;
    let deadline = Instant::now() + timeout;
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Ok(None);
        }
        thread::sleep(Duration::from_millis(25));
    };
    let mut stdout = Vec::new();
    if let Some(mut pipe) = child.stdout.take() {
        pipe.read_to_end(&mut stdout)?;
    }
    Ok(Some(Output {
        status,
        stdout,
        stderr: Vec::new(),
    }))
}

/// 创建生命周期测试使用的同一固定轨迹目标；release 构建改用零大小的禁用边界。
fn full_exit_trace(run_dir: &Path) -> (PathBuf, super::DebugExitTrace) {
    let path = run_dir.join("exit-trace.log");
    #[cfg(debug_assertions)]
    {
        let runtime_root = run_dir.to_path_buf();
        let trace = super::debug_exit_trace_from_lookup(|name| match name {
            super::EXIT_TRACE_PATH_ENV => Some(path.clone().into_os_string()),
            super::E2E_RUNTIME_ROOT_ENV => Some(runtime_root.clone().into_os_string()),
            _ => None,
        });
        (path, trace)
    }
    #[cfg(not(debug_assertions))]
    {
        (path, super::DebugExitTrace::disabled())
    }
}

#[cfg(debug_assertions)]
/// 等待 MockRuntime join 后检查完整轨迹，使断言观察到与 E2E harness 相同的持久化文件。
fn assert_full_exit_trace(path: &Path) {
    let content = fs::read_to_string(path).expect("full-exit trace");
    assert_eq!(
        content,
        "stage=exit_requested_enter\nstage=exit_requested_return\nstage=exit_enter\nstage=exit_return\n"
    );
}

/// 构造不触碰真实 App Server 的文本预览 identity，并验证它通过 close wire namespace 校验。
fn full_exit_attachment_preview_host() -> Arc<super::attachment_preview::AttachmentPreviewHost> {
    use super::attachment_preview::{
        AttachmentPreviewDescriptor, AttachmentPreviewKind, AttachmentPreviewSessionId,
    };
    use ja_runtime::app_server_process::AttachmentPreviewCloseParams;

    let preview_session_id =
        AttachmentPreviewSessionId::try_new("apv_exit_preview_session".to_owned())
            .expect("valid mock server session identity");
    AttachmentPreviewCloseParams::new(preview_session_id.as_str())
        .expect("mock session identity must satisfy close wire schema");

    let host = Arc::new(
        super::attachment_preview::AttachmentPreviewHost::new()
            .expect("mock attachment preview host"),
    );
    host.open(
        "main",
        "workspace-exit-test".to_owned(),
        AttachmentPreviewDescriptor {
            preview_session_id,
            attachment_id: "exit-attachment".to_owned(),
            display_name: "notes.txt".to_owned(),
            size_bytes: 1,
            media_kind: "text".to_owned(),
            media_type: "text/plain".to_owned(),
            preview_kind: AttachmentPreviewKind::Text,
        },
        None,
    )
    .expect("register mock text preview session");
    host
}

/// 复用生产退出处理器驱动 MockRuntime；具名 fixture 保持退出事件、诊断事实与 runner 的所有权清晰。
fn run_full_exit_mock<C>(
    host: RuntimeHost,
    attachment_ingress: Arc<AttachmentIngress>,
    attachment_preview_host: Arc<super::attachment_preview::AttachmentPreviewHost>,
    mut close_preview: C,
    trace: super::DebugExitTrace,
) -> FullExitMockFixture
where
    C: FnMut(
            ja_runtime::app_server_process::AttachmentPreviewCloseParams,
        ) -> Result<(), super::attachment_preview::AttachmentPreviewError>
        + Send
        + 'static,
{
    use tauri::RunEvent;
    use tauri::test::{mock_builder, mock_context, noop_assets};

    // Runtime adapter 绑定同一 RuntimeHost；close callback 只替换远端 ACK 来源。
    let attachment_preview_runtime =
        super::attachment_preview::AttachmentPreviewRuntimeState::new(Arc::new(host.clone()));
    let app = mock_builder()
        .manage(attachment_preview_runtime)
        .manage(attachment_preview_host)
        .manage(host)
        // MockRuntime 必须托管与生产 composition root 相同的退出 owner；否则生产退出
        // handler 读取 AttachmentIngress 时会 panic，并把真正的生命周期断言掩盖为断连。
        .manage(attachment_ingress)
        .manage(TerminalCommandHost::new())
        .manage(NativeShortcutHost::default())
        .manage(PreviewCommandHost::new().expect("产品默认 Preview 配置必须有效"))
        .build(mock_context(noop_assets()))
        .expect("mock full-exit app");
    let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("mock full-exit window");
    let app_handle = app.handle().clone();
    let (sender, receiver) = mpsc::sync_channel(8);
    #[cfg(debug_assertions)]
    let callback_trace = trace.clone();
    #[cfg(not(debug_assertions))]
    let callback_trace = trace;
    let runner = thread::spawn(move || {
        app.run(move |app_handle, event| match event {
            RunEvent::Ready => {
                let _ = sender.send(("ready", None));
            }
            RunEvent::ExitRequested { code, .. } => {
                let initial = super::handle_full_exit_request_event_with_preview_close(
                    app_handle,
                    code,
                    &callback_trace,
                    &mut close_preview,
                );
                if initial {
                    let _ = sender.send(("exit-requested", None));
                    // Tauri MockRuntime 未实现 request_exit；把携带 code 的后续请求送入同一生产
                    // handler，避免调用不支持的 runtime API 或引入另一套生命周期门禁。
                    let _ =
                        super::handle_full_exit_request_event(app_handle, Some(0), &callback_trace);
                    let _ = sender.send(("exit-programmatic-requested", None));
                }
            }
            RunEvent::Exit => {
                callback_trace.record(super::DebugExitTraceEvent::ExitEntered);
                let host = app_handle.state::<RuntimeHost>();
                let terminal = app_handle.state::<TerminalCommandHost>();
                let native_shortcuts = app_handle.state::<NativeShortcutHost>();
                let preview = app_handle.state::<PreviewCommandHost>();
                let attachments = app_handle.state::<Arc<AttachmentIngress>>();
                let attachment_previews =
                    app_handle.state::<Arc<super::attachment_preview::AttachmentPreviewHost>>();
                let facts = FullExitCleanupFacts {
                    runtime_bridge_ready: host.exit_ready(),
                    terminal_empty: terminal.is_empty(),
                    native_shortcuts_revoked: native_shortcuts.is_revoked_for_controller_close(),
                    preview_active_count: preview.manager().active_count().ok(),
                    workspace_watchers_empty: crate::workspace::is_shutdown_complete(),
                    attachments_shutdown_complete: attachments.is_shutdown_complete(),
                    attachment_previews_ready: super::attachment_previews_exit_ready(
                        &attachment_previews,
                    ),
                };
                let exit_safe = facts.is_ready();
                if !exit_safe {
                    host.record_forced_exit();
                }
                let _ = sender.send((
                    if exit_safe {
                        "exit-ready"
                    } else {
                        "exit-unsafe"
                    },
                    Some(facts),
                ));
                callback_trace.record(super::DebugExitTraceEvent::ExitReturned);
            }
            _ => {}
        });
    });
    FullExitMockFixture {
        app_handle,
        window,
        receiver,
        runner,
    }
}

/// 验证完整组合根共享同一绝对 deadline；事件在同步 cleanup 返回后才发出，故等待预算必须覆盖
/// 生产 20 秒 deadline 加上有限调度余量，不能以更短的测试超时否定正常的资源清理。
#[test]
fn full_exit_request_allows_clean_runtime_cleanup() {
    let backend = TestOwnedBackend::new("full_exit_request_allows_clean_runtime_cleanup");
    let run_dir = backend.root.clone();
    let (_trace_path, trace) = full_exit_trace(&run_dir);
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let host = RuntimeHost::new(backend.launch_config(), sink);
    let attachment_ingress = Arc::new(AttachmentIngress::new(&run_dir).expect("test ingress"));
    let attachment_preview_host = full_exit_attachment_preview_host();
    let close_attempts = Arc::new(AtomicUsize::new(0));
    let callback_attempts = Arc::clone(&close_attempts);
    host.start().expect("test sidecar ready");
    let FullExitMockFixture {
        app_handle,
        window,
        receiver,
        runner,
    } = run_full_exit_mock(
        host,
        attachment_ingress,
        Arc::clone(&attachment_preview_host),
        move |_| {
            callback_attempts.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        },
        trace,
    );
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok(("ready", None))
    );
    window.close().expect("full-exit close request");
    assert_eq!(
        receiver.recv_timeout(CLEAN_FULL_EXIT_EVENT_WAIT),
        Ok(("exit-requested", None))
    );
    assert_eq!(
        receiver.recv_timeout(CLEAN_FULL_EXIT_EVENT_WAIT),
        Ok(("exit-programmatic-requested", None))
    );
    let (exit_status, cleanup_facts) = receiver
        .recv_timeout(CLEAN_FULL_EXIT_EVENT_WAIT)
        .expect("full-exit completion event");
    let cleanup_facts = cleanup_facts.expect("full-exit owner facts");
    let close_attempt_count = close_attempts.load(std::sync::atomic::Ordering::SeqCst);
    assert_eq!(
        close_attempt_count, 1,
        "exit status: {exit_status}; owner facts: {cleanup_facts:?}"
    );
    assert!(
        attachment_preview_host
            .server_sessions()
            .expect("preview sessions after ACK")
            .is_empty()
    );
    assert_eq!(
        exit_status,
        "exit-ready",
        "cleanup owner facts: {cleanup_facts:?}; runtime snapshot: {:?}",
        app_handle
            .state::<RuntimeHost>()
            .state()
            .map(|status| (status.status, status.generation))
            .map_err(|error| error.code)
    );
    runner.join().expect("mock full-exit runner");
    assert!(app_handle.state::<RuntimeHost>().exit_ready());
    assert!(cleanup_facts.attachment_previews_ready);
    assert!(
        app_handle
            .state::<Arc<AttachmentIngress>>()
            .is_shutdown_complete()
    );
    #[cfg(debug_assertions)]
    assert_full_exit_trace(&_trace_path);
    backend.shutdown_and_assert_stopped();
}

/// 远端 close 未 ACK 时保留 identity 并让最终 Exit 报 unsafe，同时继续清理其他本地 owner。
#[test]
fn full_exit_preview_close_failure_retains_session_and_cleans_other_owners() {
    let backend = TestOwnedBackend::new(
        "full_exit_preview_close_failure_retains_session_and_cleans_other_owners",
    );
    let run_dir = backend.root.clone();
    let (_trace_path, trace) = full_exit_trace(&run_dir);
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let host = RuntimeHost::new(backend.launch_config(), sink);
    let attachment_ingress = Arc::new(AttachmentIngress::new(&run_dir).expect("test ingress"));
    let attachment_preview_host = full_exit_attachment_preview_host();
    let close_attempts = Arc::new(AtomicUsize::new(0));
    let callback_attempts = Arc::clone(&close_attempts);
    host.start().expect("test sidecar ready");
    let FullExitMockFixture {
        window,
        receiver,
        runner,
        ..
    } = run_full_exit_mock(
        host,
        attachment_ingress,
        Arc::clone(&attachment_preview_host),
        move |_| {
            callback_attempts.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Err(super::attachment_preview::AttachmentPreviewError::runtime(
                true,
            ))
        },
        trace,
    );
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok(("ready", None))
    );
    window.close().expect("full-exit close request");
    assert_eq!(
        receiver.recv_timeout(CLEAN_FULL_EXIT_EVENT_WAIT),
        Ok(("exit-requested", None))
    );
    assert_eq!(
        receiver.recv_timeout(CLEAN_FULL_EXIT_EVENT_WAIT),
        Ok(("exit-programmatic-requested", None))
    );
    let (exit_status, cleanup_facts) = receiver
        .recv_timeout(CLEAN_FULL_EXIT_EVENT_WAIT)
        .expect("mock close-failure completion event");
    let cleanup_facts = cleanup_facts.expect("close-failure owner facts");
    assert_eq!(exit_status, "exit-unsafe");
    let close_attempt_count = close_attempts.load(std::sync::atomic::Ordering::SeqCst);
    assert_eq!(
        close_attempt_count, 1,
        "exit status: {exit_status}; owner facts: {cleanup_facts:?}"
    );
    assert!(!cleanup_facts.attachment_previews_ready);
    assert!(cleanup_facts.runtime_bridge_ready);
    assert!(cleanup_facts.terminal_empty);
    assert!(cleanup_facts.native_shortcuts_revoked);
    assert_eq!(cleanup_facts.preview_active_count, Some(0));
    assert!(cleanup_facts.workspace_watchers_empty);
    assert!(cleanup_facts.attachments_shutdown_complete);
    assert_eq!(attachment_preview_host.cache_bytes(), Ok(0));
    assert_eq!(
        attachment_preview_host
            .server_sessions()
            .expect("unacknowledged preview session")
            .len(),
        1
    );
    runner.join().expect("mock close-failure runner");
    #[cfg(debug_assertions)]
    assert_full_exit_trace(&_trace_path);
    backend.shutdown_and_assert_stopped();
}

/// 验证 full-exit 清理失败仍能到达原生 Exit 事件并报告本连接清理不安全；
/// 共享后台不写旧窗口级 recovery marker，也不因本窗口清理失败删除后台状态。
#[test]
fn full_exit_request_allows_forced_exit_after_cleanup_fault() {
    let backend = TestOwnedBackend::new("full_exit_request_allows_forced_exit_after_cleanup_fault");
    let run_dir = backend.root.clone();
    let (_trace_path, trace) = full_exit_trace(&run_dir);
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let failures = Arc::new(AtomicUsize::new(usize::MAX));
    let harness = RuntimeHostHarness::with_exit_control(
        backend.launch_config(),
        sink,
        Duration::from_millis(500),
        Arc::clone(&failures),
    );
    let host = harness.host();
    let attachment_ingress = Arc::new(AttachmentIngress::new(&run_dir).expect("test ingress"));
    let attachment_preview_host = Arc::new(
        super::attachment_preview::AttachmentPreviewHost::new()
            .expect("mock attachment preview host"),
    );
    host.start().expect("test sidecar ready");
    let FullExitMockFixture {
        app_handle,
        window: first_window,
        receiver,
        runner,
    } = run_full_exit_mock(
        host,
        attachment_ingress,
        attachment_preview_host,
        |_| Ok(()),
        trace,
    );
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok(("ready", None))
    );
    first_window.close().expect("first close request");
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok(("exit-requested", None))
    );
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok(("exit-programmatic-requested", None))
    );
    let (exit_status, cleanup_facts) = receiver
        .recv_timeout(Duration::from_secs(5))
        .expect("mock forced-exit completion event");
    let cleanup_facts = cleanup_facts.expect("forced-exit owner facts");
    assert_eq!(
        exit_status,
        "exit-unsafe",
        "cleanup owner facts: {cleanup_facts:?}; runtime snapshot: {:?}",
        app_handle
            .state::<RuntimeHost>()
            .state()
            .map(|status| (status.status, status.generation))
            .map_err(|error| error.code)
    );
    runner.join().expect("mock forced-exit runner");
    let host = app_handle.state::<RuntimeHost>();
    assert!(!host.exit_ready());
    let recovery = host.recovery_state();
    assert!(!recovery.required);
    assert!(recovery.recovery_id.is_none());
    assert!(
        app_handle
            .state::<Arc<AttachmentIngress>>()
            .is_shutdown_complete()
    );
    #[cfg(debug_assertions)]
    assert_full_exit_trace(&_trace_path);
    backend.shutdown_and_assert_stopped();
}

/// 真实 Java sidecar 验证 Host 授信闭环；隔离 home/data 与 TestOwnedBackend 身份用于阻止读取用户 profile 或清理其他 JVM。
#[test]
fn workspace_trust_roundtrips_through_the_java_owner() {
    let backend = TestOwnedBackend::new("workspace_trust_roundtrips_through_the_java_owner");
    let project_root = backend.root.join("project");
    fs::create_dir_all(&project_root).expect("create isolated project root");
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let host = RuntimeHost::new(backend.launch_config(), sink);
    host.start().expect("test sidecar ready");
    let workspace = host
        .open_workspace_projection(WorkspaceOpenInput {
            cwd: project_root.to_string_lossy().into_owned(),
            display_name: Some("Trust integration".to_owned()),
        })
        .expect("register isolated project in Java");
    assert_eq!(workspace.trust, "untrusted");

    let trust_update =
        host.set_workspace_trust(workspace.workspace_id.clone(), "trusted".to_owned());
    let update_error_code = trust_update.as_ref().err().map(|error| error.code);
    let params = WorkspaceListParams::try_new(
        serde_json::to_vec(&serde_json::json!({ "kind": "project", "limit": 200 }))
            .expect("encode fixed workspace list request"),
    )
    .expect("validate fixed workspace list request");
    let page = match host.history_request(HistoryRequest::WorkspaceList(params)) {
        Ok(HistoryResponse::WorkspaceList(page)) => page,
        Ok(_) => panic!("stage=workspace_list error_code=RESPONSE_VARIANT_MISMATCH"),
        Err(error) => panic!("stage=workspace_list error_code={}", error.code),
    };
    let page: serde_json::Value =
        serde_json::from_slice(&page.into_bytes()).expect("decode workspace list projection");
    let authority_trust = page
        .get("items")
        .and_then(serde_json::Value::as_array)
        .and_then(|items| {
            items.iter().find(|item| {
                item.get("workspaceId").and_then(serde_json::Value::as_str)
                    == Some(workspace.workspace_id.as_str())
            })
        })
        .and_then(|item| item.get("trust"))
        .and_then(serde_json::Value::as_str)
        .unwrap_or("missing");
    assert_eq!(
        authority_trust,
        "trusted",
        "stage=workspace_list_authority host_error_code={}",
        update_error_code.unwrap_or("NONE")
    );
    assert!(
        trust_update.is_ok(),
        "stage=host_result error_code={}",
        update_error_code.unwrap_or("NONE")
    );

    host.shutdown().expect("disconnect test Host");
    backend.shutdown_and_assert_stopped();
}
