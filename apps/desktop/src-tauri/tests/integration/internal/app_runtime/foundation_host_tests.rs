// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 通过真实 Java25 进程覆盖 Rust/Tauri Host bridge 的跨进程合同。

use crate::app_runtime::RuntimeStatusDto as RuntimeStatus;
use crate::app_runtime::{
    ApprovalResponseInput, EventEmitError, EventSink, LaunchConfig, RuntimeHost, RuntimeStatusKind,
    TurnContentPart, TurnStartInput, WorkspaceOpenInput,
};
use crate::runtime_test_support::RuntimeHostHarness;
use base64::Engine;
use ja_runtime::app_server_process::SharedAppServerClient;
use serde_json::Value;
use std::ffi::OsString;
use std::path::PathBuf;
use std::process::Command;
use std::sync::OnceLock;
use std::sync::atomic::AtomicUsize;
use std::sync::mpsc::{self, Receiver};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

struct TempRunDir(PathBuf);

static JVM_RUNTIME_TEST_LOCK: Mutex<()> = Mutex::new(());

/// 串行化本测试目标内会启动或观察 Java sidecar 的生命周期用例：这些用例虽然隔离了目录，
/// 但仍共享宿主 CPU、进程枚举和严格启动 deadline，并行冷启动会把资源争用误判为产品超时。
/// poison 只表示前一用例失败，不应阻止后续用例执行并提供独立诊断证据。
fn jvm_runtime_test_guard() -> MutexGuard<'static, ()> {
    JVM_RUNTIME_TEST_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

impl TempRunDir {
    /// 为每个测试创建唯一目录，避免并行 worker 共享 sidecar cwd，或误读其他测试的文件。
    /// 隔离范围覆盖 configuration、SQLite、runtime recovery 与日志，而不是只隔离临时输入。
    fn create(label: &str) -> Self {
        let suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let path = std::env::temp_dir().join(format!("ja-{label}-{}-{suffix}", std::process::id()));
        std::fs::create_dir_all(&path).expect("test run directory");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700))
                .expect("private test run directory");
        }
        Self(path)
    }
}

impl Drop for TempRunDir {
    /// supervisor 在有界时间内回收子进程后，再尽力清理测试目录。
    /// 清理失败不掩盖进程生命周期结果，残留目录仍可用于定位文件句柄或恢复问题。
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// 创建仅供测试观察事件的有界 channel；512 个槽位覆盖当前事件规模，但不替代生产 bridge 的 backpressure。
fn event_sink() -> (EventSink, Receiver<Value>) {
    let (sender, receiver) = mpsc::sync_channel(512);
    let sink: EventSink = Arc::new(move |value| {
        sender
            .try_send(value)
            .map_err(|_| EventEmitError::QueueFull)
    });
    (sink, receiver)
}

/// 通过 Harness 构造隔离四目录，确保并行 JDK25 子进程不会共享配置、SQLite 或日志锁。
fn fixture_config(run_dir: &TempRunDir) -> LaunchConfig {
    fixture_config_with_jvm_args(run_dir, Vec::new())
}

/// 在固定 Java 参数前追加受控 JVM 参数，同时保持 App Server 四目录与桌面启动合同一致。
fn fixture_config_with_jvm_args(run_dir: &TempRunDir, mut jvm_args: Vec<OsString>) -> LaunchConfig {
    let java = java_executable();
    let jar = test_jar();
    let home_dir = run_dir.0.join("home");
    let data_dir = run_dir.0.join("data");
    let native_run_dir = run_dir.0.join("runtime");
    let java_logs_dir = run_dir.0.join("java-logs");
    for directory in [&home_dir, &data_dir, &native_run_dir, &java_logs_dir] {
        std::fs::create_dir_all(directory).expect("create isolated App Server directory");
    }
    let encode = |directory: &PathBuf| {
        base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(directory.to_string_lossy().as_bytes())
    };
    jvm_args.extend([
        OsString::from("-jar"),
        jar.into_os_string(),
        OsString::from(format!("--home-dir-base64={}", encode(&home_dir))),
        OsString::from(format!("--data-dir-base64={}", encode(&data_dir))),
        OsString::from(format!("--run-dir-base64={}", encode(&native_run_dir))),
        OsString::from(format!("--log-dir-base64={}", encode(&java_logs_dir))),
    ]);
    RuntimeHostHarness::launch_config_with_dirs(
        java,
        jvm_args,
        home_dir,
        data_dir,
        native_run_dir,
        java_logs_dir,
    )
}

/// 经 Java-owned 配置与凭据用例建立隔离网络失败 Turn 所需的最小 Profile。
///
/// 测试读取两个独立 CAS version 后分别写入文档和 Secret，验证 Rust 不解析配置、
/// 不缓存凭据，也不会为测试绕过生产的 generation 冻结规则。
fn configure_isolated_profile(harness: &RuntimeHostHarness) {
    configure_profile_with_provider(harness, "http://127.0.0.1:9/v1");
}

/// 真实 Turn 验收注入 loopback Provider，网络失败场景继续显式使用不可达地址。
fn configure_profile_with_provider(harness: &RuntimeHostHarness, base_url: &str) {
    let current = harness
        .config_request("configuration/read", serde_json::json!({}))
        .expect("read Java-owned configuration");
    let config_version = current["cas"]["userVersion"]
        .as_str()
        .expect("user configuration version");
    let credential_version = current["cas"]["credentialVersion"]
        .as_str()
        .expect("credential CAS version");
    harness
        .config_request(
            "configuration/replace",
            serde_json::json!({
                "scope": "user",
                "expectedVersion": config_version,
                "document": {
                    "schema_version": 2,
                    "config_revision": 1,
                    "default_access_mode": "approval_required",
                    "default_provider_id": "provider_host",
                    "default_model_id": "model_host",
                    "default_reasoning_level": "medium",
                    "disabled_skills": [],
                    "subagents": {
                        "enabled": true,
                        "provider_id": null,
                        "model_id": null,
                        "reasoning_level": null
                    },
                    "providers": [{
                        "provider_id": "provider_host",
                        "name": "Host integration",
                        "api": "openai_responses",
                        "base_url": base_url,
                        "credential_id": "cred_host",
                        "network_timeouts": {
                            "connect_timeout_ms": 100,
                            "request_timeout_ms": 1000
                        },
                        "agent_defaults": {
                            "context": {"auto_compact": true}
                        },
                        "models": [{
                            "model_id": "model_host",
                            "name": "Fake model",
                            "model": "fake-model",
                            "capabilities": {
                                "context_window_tokens": 128000,
                                "max_output_tokens": 8192
                            },
                            "reasoning_level_map": {"medium": "medium"},
                            "default_reasoning_level": "medium"
                        }]
                    }],
                    "mcp_servers": []
                }
            }),
        )
        .expect("replace Java-owned isolated profile");
    harness
        .config_request(
            "credential/set",
            serde_json::json!({
                "credentialId": "cred_host",
            "secret": "test-only-provider-secret",
                "expectedVersion": credential_version
            }),
        )
        .expect("store fake profile credential in Java-owned backend");
}

/// 从桌面宿主回到 workspace 根定位唯一 Java 产物，仍允许 CI 显式指定隔离 JAR。
fn test_jar() -> PathBuf {
    let jar = std::env::var_os("JA_TEST_JAR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("../../..")
                .join("app-server")
                .join("target")
                .join("ja-app-server.jar")
        });
    assert!(
        jar.is_file(),
        "build app-server/target/ja-app-server.jar first or set JA_TEST_JAR"
    );
    jar
}

/// 添加唯一 JVM marker，同时复用相同四目录合同以验证精确进程树而不扩大参数入口。
fn marked_fixture_config(run_dir: &TempRunDir, marker: &str) -> LaunchConfig {
    fixture_config_with_jvm_args(
        run_dir,
        vec![OsString::from(format!("-Dja.test.marker={marker}"))],
    )
}

/// 解析测试专用 JVM，并对显式覆盖路径也强制验证主版本为 25。
/// 这样本地 JDK 漂移不会让测试在错误的运行时合同上出现假绿。
fn java_executable() -> PathBuf {
    let java = if let Some(path) = std::env::var_os("JA_TEST_JAVA") {
        PathBuf::from(path)
    } else if let Some(home) = std::env::var_os("JAVA_HOME") {
        let candidate =
            PathBuf::from(home)
                .join("bin")
                .join(if cfg!(windows) { "java.exe" } else { "java" });
        if candidate.is_file() {
            candidate
        } else {
            resolve_path_java()
        }
    } else {
        resolve_path_java()
    };
    assert!(java.is_file(), "set JA_TEST_JAVA to Liberica JDK25 java");
    let version = Command::new(&java)
        .arg("-version")
        .output()
        .expect("inspect test java version");
    assert!(version.status.success(), "test Java -version failed");
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
        "Ja integration requires Java major 25, got {banner:?}"
    );
    java
}

/// 仅在测试准备阶段从 PATH 解析 Java；打包后的 runtime 启动不使用该回退。
/// 该边界避免把环境相关行为带入生产 sidecar 解析策略。
fn resolve_path_java() -> PathBuf {
    let command = if cfg!(windows) { "where.exe" } else { "which" };
    let binary = if cfg!(windows) { "java.exe" } else { "java" };
    let output = Command::new(command)
        .arg(binary)
        .output()
        .expect("resolve test java");
    assert!(
        output.status.success(),
        "set JA_TEST_JAVA to Liberica JDK25 java"
    );
    let path = String::from_utf8_lossy(&output.stdout)
        .lines()
        .next()
        .unwrap_or_default()
        .trim()
        .to_owned();
    assert!(!path.is_empty(), "test java path is empty");
    PathBuf::from(path)
}

/// 从 Windows 进程表观察唯一 sidecar marker，而不是相信 command response。
/// 只有操作系统视角才能证明真实 Java child 已创建或仍然存活。
#[cfg(windows)]
fn process_marker_visible(marker: &str) -> bool {
    let script = format!(
        "$me=$PID; Get-CimInstance Win32_Process | Where-Object {{ $_.ProcessId -ne $me -and $_.CommandLine -like '*{marker}*' }} | Select-Object -ExpandProperty ProcessId"
    );
    let output = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .output()
        .expect("query child process tree");
    assert!(output.status.success(), "process tree query failed");
    !String::from_utf8_lossy(&output.stdout).trim().is_empty()
}

/// 在 Unix 主机通过 `ps` 保持同一进程树断言语义，避免平台分支削弱生命周期合同。
#[cfg(not(windows))]
fn process_marker_visible(marker: &str) -> bool {
    let output = Command::new("ps")
        .args(["-axo", "command="])
        .output()
        .expect("query child process tree");
    assert!(output.status.success(), "process tree query failed");
    String::from_utf8_lossy(&output.stdout)
        .lines()
        .any(|line| line.contains(marker))
}

/// 在调用者给定的绝对 deadline 内等待 marker 从进程表消失，证明整个 sidecar 进程树已回收。
fn assert_process_tree_gone(marker: &str, deadline: Instant) {
    loop {
        if !process_marker_visible(marker) {
            return;
        }
        if Instant::now() >= deadline {
            panic!("sidecar process marker remains: {marker}");
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// 测试夹具在桌面断连后显式管理自己启动的后台；生产窗口退出没有此权限或副作用。
fn stop_test_shared_server(config: &LaunchConfig, marker: &str, deadline: Instant) {
    let mut admin = SharedAppServerClient::connect_existing(&config.sidecar, 1)
        .expect("connect test-owned shared server");
    admin
        .stop_server(deadline.saturating_duration_since(Instant::now()))
        .expect("stop test-owned shared server");
    drop(admin);
    assert_process_tree_gone(marker, deadline);
}

/// 在关闭前等待操作系统观测到真实 child，避免 Java 或慢启动夹具根本未启动时测试仍然通过。
fn assert_process_marker_visible(marker: &str, deadline: Instant) {
    loop {
        if process_marker_visible(marker) {
            return;
        }
        if Instant::now() >= deadline {
            panic!("sidecar process marker never became visible: {marker}");
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// 通过 waiter channel 在同一绝对 deadline 内 join 测试 worker。
/// worker 即使卡死，也不能把 Host 测试延长到进程清理预算之外。
fn join_with_deadline<T: Send + 'static>(
    handle: JoinHandle<T>,
    deadline: Instant,
) -> Result<T, &'static str> {
    let (sender, receiver) = mpsc::sync_channel(1);
    std::thread::spawn(move || {
        let result = handle.join().map_err(|_| "test worker panicked");
        let _ = sender.send(result);
    });
    receiver
        .recv_timeout(deadline.saturating_duration_since(Instant::now()))
        .map_err(|_| "test worker join deadline elapsed")?
}

/// 只构造 JA-RPC v1 当前 Turn 输入；Thread 身份必须先由 Java 持久化用例签发。
fn valid_turn(thread_id: String, text: &str) -> TurnStartInput {
    TurnStartInput {
        client_operation_id: format!("op_{}", uuid::Uuid::new_v4().simple()),
        thread_id,
        content: vec![TurnContentPart::Text {
            text: text.to_owned(),
        }],
    }
}

/// 递归检查公开事件投影，确保 ready token 或疑似握手 token 不会跨越 Rust/Tauri 边界泄漏。
fn assert_no_token(value: &Value) {
    match value {
        Value::Object(object) => {
            assert!(
                !object
                    .keys()
                    .any(|key| key.eq_ignore_ascii_case("readyToken"))
            );
            object.values().for_each(assert_no_token);
        }
        Value::Array(values) => values.iter().for_each(assert_no_token),
        Value::String(text) => {
            assert!(!(text.len() == 32 && text.bytes().all(|byte| byte.is_ascii_hexdigit())))
        }
        Value::Null | Value::Bool(_) | Value::Number(_) => {}
    }
}

/// 只读取公开的 `method` 字段，避免测试绑定完整内部 JA-RPC v1 帧结构。
fn method(value: &Value) -> Option<&str> {
    value.get("method").and_then(Value::as_str)
}

/// 在调用者拥有的同一绝对 deadline 内接收事件，每次 recv 只消费剩余预算。
/// 这样持续到达的事件不能反复重置超时并掩盖 Host 卡死。
fn receive_until_at<F>(receiver: &Receiver<Value>, deadline: Instant, mut done: F) -> Vec<Value>
where
    F: FnMut(&Value) -> bool,
{
    let mut values = Vec::new();
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        let value = receiver.recv_timeout(remaining).unwrap_or_else(|error| {
            panic!("bridge event before deadline: {error:?}; received={values:?}")
        });
        assert_no_token(&value);
        let complete = done(&value);
        values.push(value);
        if complete {
            return values;
        }
    }
    panic!("bridge event deadline elapsed");
}

/// 验证原生有界队列只准入 64 个 command，并稳定拒绝第 65 个。
/// 每实例 actor gate 排除调度时序干扰，使 backpressure 成为确定的 admission 合同。
#[test]
fn concurrent_calls_have_bounded_queue_admission() {
    let _jvm_guard = jvm_runtime_test_guard();
    let run_dir = TempRunDir::create("queue");
    let sink: EventSink = Arc::new(|_| Ok(()));
    let placeholder = run_dir.0.join("queue-sidecar-placeholder.exe");
    std::fs::write(&placeholder, b"test-owned launch placeholder")
        .expect("create queue sidecar placeholder");
    let (bridge, admission) = RuntimeHostHarness::bridge_with_queue_control(
        RuntimeHostHarness::launch_config(placeholder, Vec::new(), run_dir.0.clone()),
        sink,
    )
    .expect("paused bridge actor");
    assert!(
        admission.wait_until_armed(Instant::now() + Duration::from_secs(5)),
        "queue actor did not reach its admission barrier"
    );
    for _ in 0..64 {
        RuntimeHostHarness::try_queue_probe(&bridge)
            .expect("each bounded slot admits exactly one command");
    }
    let full = RuntimeHostHarness::try_queue_probe(&bridge)
        .expect_err("the 65th command must be rejected while actor is paused");
    assert_eq!(full.code, "RUNTIME_QUEUE_FULL");
    admission.release();
    assert!(
        admission.wait_until_processed(64, Instant::now() + Duration::from_secs(5)),
        "actor did not drain all admitted probes"
    );
    bridge.shutdown().unwrap_or_else(|error| {
        panic!(
            "priority actor shutdown: {error:?}; actor phase={}; exit_ready={}",
            RuntimeHostHarness::bridge_phase(&bridge),
            bridge.exit_ready()
        )
    });
}

/// 使用 Tauri 官方 MockRuntime 覆盖 command 注册、managed state、原生事件投影、
/// Java25 隔离 Turn 与有界关闭，确保测试经过同一 production composition。
#[test]
fn tauri_mock_composition_smoke_uses_typed_commands() {
    let _jvm_guard = jvm_runtime_test_guard();
    use crate::app_runtime::{RPC_FRAME_EVENT, cleanup_on_exit, register_commands};
    use tauri::Emitter;
    use tauri::Listener;
    use tauri::Manager;
    use tauri::ipc::{CallbackFn, InvokeBody};
    use tauri::test::{INVOKE_KEY, get_ipc_response, mock_builder, mock_context, noop_assets};
    use tauri::webview::InvokeRequest;

    let run_dir = TempRunDir::create("tauri-mock");
    let marker = format!("ja-tauri-cancel-{}", std::process::id());
    let workspace_root = run_dir.0.join("workspace");
    std::fs::create_dir_all(&workspace_root).expect("mock workspace");
    std::fs::write(
        workspace_root.join("README.md"),
        "hello from the Tauri command composition test\n",
    )
    .expect("mock workspace file");
    Command::new("git")
        .args(["init", "--quiet"])
        .current_dir(&workspace_root)
        .status()
        .expect("git executable")
        .success()
        .then_some(())
        .expect("initialize temporary repository");
    Command::new("git")
        .args(["add", "README.md"])
        .current_dir(&workspace_root)
        .status()
        .expect("git executable")
        .success()
        .then_some(())
        .expect("stage temporary file for read-only diff");
    let app_slot: Arc<OnceLock<tauri::AppHandle<tauri::test::MockRuntime>>> =
        Arc::new(OnceLock::new());
    let slot_for_sink = Arc::clone(&app_slot);
    let sink: EventSink = Arc::new(move |value| {
        let app = slot_for_sink.get().ok_or(EventEmitError::DeliveryFailed)?;
        app.emit(RPC_FRAME_EVENT, value)
            .map_err(|_| EventEmitError::DeliveryFailed)
    });
    let config = marked_fixture_config(&run_dir, &marker);
    let host = RuntimeHost::new(config.clone(), sink);
    // Mock composition 必须托管与生产 workspace-open 相同的附件预览状态；这里使用空 host，
    // 只验证 Workspace 切换时的有界清理，不为测试创建第二套协议或文件读取能力。
    let attachment_preview_host = Arc::new(
        crate::attachment_preview::AttachmentPreviewHost::new()
            .expect("mock attachment preview host"),
    );
    let app = register_commands(mock_builder())
        .manage(
            crate::attachment_preview::AttachmentPreviewRuntimeState::new(Arc::new(host.clone())),
        )
        .manage(attachment_preview_host)
        .manage(host)
        .build(mock_context(noop_assets()))
        .expect("mock Tauri app");
    let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("mock webview");
    app_slot
        .set(app.handle().clone())
        .expect("single mock app handle");
    // 该有界 channel 只观察 MockRuntime 测试帧。512 个槽位覆盖当前 1 MiB 隔离 Turn
    // 按协商 delta 大小切分后的数据及生命周期帧；生产 backpressure 仍由 bridge 自身队列保证。
    let (event_sender, event_receiver) = mpsc::sync_channel(512);
    let _event_id = app.listen(RPC_FRAME_EVENT, move |event| {
        let payload = serde_json::from_str::<Value>(event.payload()).expect("event JSON");
        let _ = event_sender.try_send(payload);
    });
    let total_deadline = Instant::now() + Duration::from_secs(30);
    let invoke = |cmd: &str, body: Value| -> Result<Value, Value> {
        let request = InvokeRequest {
            cmd: cmd.to_owned(),
            callback: CallbackFn(0),
            error: CallbackFn(1),
            url: if cfg!(any(windows, target_os = "android")) {
                "http://tauri.localhost"
            } else {
                "tauri://localhost"
            }
            .parse()
            .expect("mock invoke URL"),
            body: InvokeBody::Json(body),
            headers: Default::default(),
            invoke_key: INVOKE_KEY.to_owned(),
        };
        get_ipc_response(&webview, request).and_then(|body| {
            body.deserialize::<Value>()
                .map_err(|error| Value::String(error.to_string()))
        })
    };
    let workspace: crate::app_runtime::WorkspaceWireDto = serde_json::from_value(
        invoke(
            "ja_runtime_workspace_open",
            serde_json::json!({
                "input": {
                    "cwd": workspace_root.to_string_lossy(),
                    "displayName": "Tauri mock workspace",
                    "trust": "trusted"
                }
            }),
        )
        .expect("typed workspace open command"),
    )
    .expect("workspace response");
    let workspace_id = workspace.workspace_id;
    let started: RuntimeStatus = serde_json::from_value(
        invoke("ja_runtime_state", serde_json::json!({})).expect("typed state command"),
    )
    .expect("runtime state response");
    assert_eq!(started.status, RuntimeStatusKind::Ready);
    assert_process_marker_visible(&marker, total_deadline);
    // 配置和凭据必须经 Java-owned Tauri commands 写入；测试不再借由 Runtime 启动参数注入配置快照。
    let configuration = invoke(
        "ja_configuration_read",
        serde_json::json!({"input": {"workspaceId": workspace_id.clone()}}),
    )
    .expect("typed configuration read command");
    let user_version = configuration["cas"]["userVersion"]
        .as_str()
        .expect("user configuration version");
    let credential_version = configuration["cas"]["credentialVersion"]
        .as_str()
        .expect("credential version");
    invoke(
        "ja_configuration_replace",
        serde_json::json!({
            "input": {
                "scope": "user",
                "expectedVersion": user_version,
                "document": {
                    "schema_version": 2,
                    "config_revision": 1,
                    "default_access_mode": "full_access",
                    "default_provider_id": "provider_host",
                    "default_model_id": "model_host",
                    "default_reasoning_level": "medium",
                    "interaction": {
                        "clarification_enabled": true
                    },
                    "subagents": {
                        "enabled": true,
                        "provider_id": null,
                        "model_id": null,
                        "reasoning_level": null
                    },
                    "providers": [{
                        "provider_id": "provider_host",
                        "name": "Fixture",
                        "api": "openai_responses",
                        "base_url": "http://127.0.0.1:8080/v1",
                        "credential_id": "cred_host",
                        "network_timeouts": {
                            "connect_timeout_ms": 5000,
                            "request_timeout_ms": 60000
                        },
                        "agent_defaults": {
                            "context": {"auto_compact": true}
                        },
                        "models": [{
                            "model_id": "model_host",
                            "name": "Fixture model",
                            "model": "fixture-model",
                            "capabilities": {
                                "context_window_tokens": 128000,
                                "max_output_tokens": 8192
                            },
                            "reasoning_level_map": {"medium": "medium"},
                            "default_reasoning_level": "medium"
                        }]
                    }],
                    "mcp_servers": [],
                    "disabled_skills": []
                }
            }
        }),
    )
    .expect("typed configuration replace command");
    invoke(
        "ja_credential_set",
        serde_json::json!({
            "input": {
                "credentialId": "cred_host",
                "secret": "fixture-secret",
                "expectedVersion": credential_version
            }
        }),
    )
    .expect("typed credential command");
    let revealed_credential = invoke(
        "ja_credential_reveal_provider",
        serde_json::json!({"input": {"providerId": "provider_host"}}),
    )
    .expect("typed provider credential reveal command");
    assert_eq!(revealed_credential["secret"], "fixture-secret");
    for input in [
        serde_json::json!({"input": {}}),
        serde_json::json!({"input": {"providerId": null}}),
        serde_json::json!({"input": {"providerId": "provider_host", "credentialId": "cred_host"}}),
        serde_json::json!({"input": {"providerId": "mcp_host"}}),
    ] {
        assert!(
            invoke("ja_credential_reveal_provider", input).is_err(),
            "malformed Provider credential reveal must fail"
        );
    }
    let workspaces = invoke(
        "ja_workspace_list",
        serde_json::json!({"input": {"limit": 10}}),
    )
    .expect("typed workspace history command");
    assert!(
        workspaces["items"]
            .as_array()
            .expect("workspace history rows")
            .iter()
            .any(|workspace| workspace["workspaceId"] == workspace_id)
    );
    let created_thread = invoke(
        "ja_thread_create",
        serde_json::json!({
            "input": {
                "cwd": workspace_root.to_string_lossy(),
                "title": "Tauri history",
                "providerId": "provider_host",
                "modelId": "model_host",
                "reasoningLevel": "medium",
                "accessMode": "full_access",
                "collaborationMode": "default"
            }
        }),
    )
    .expect("typed thread create command");
    let history_thread_id = created_thread["threadId"]
        .as_str()
        .expect("created thread id")
        .to_owned();
    let listed_threads = invoke(
        "ja_thread_list",
        serde_json::json!({
            "input": {"workspaceId": workspace_id.clone(), "limit": 10}
        }),
    )
    .expect("typed thread list command");
    assert!(
        listed_threads["items"]
            .as_array()
            .expect("thread history rows")
            .iter()
            .any(|thread| thread["threadId"] == history_thread_id)
    );
    let initial_read = invoke(
        "ja_thread_read",
        serde_json::json!({"input": {"threadId": history_thread_id.clone()}}),
    )
    .expect("typed initial thread read command");
    assert_eq!(
        initial_read["items"]
            .as_array()
            .expect("initial items")
            .len(),
        0
    );
    let tree = invoke(
        "ja_workspace_tree",
        serde_json::json!({
            "input": {"workspaceId": workspace_id.clone(), "relativePath": ""}
        }),
    )
    .expect("typed workspace tree command");
    assert!(
        tree["entries"]
            .as_array()
            .expect("tree entries")
            .iter()
            .any(|entry| entry["relativePath"] == "README.md")
    );
    let file = invoke(
        "ja_workspace_read_file",
        serde_json::json!({
            "input": {"workspaceId": workspace_id.clone(), "relativePath": "README.md"}
        }),
    )
    .expect("typed workspace read command");
    assert_eq!(
        file["text"],
        "hello from the Tauri command composition test\n"
    );
    let search = invoke(
        "ja_workspace_search",
        serde_json::json!({
            "input": {"workspaceId": workspace_id.clone(), "relativePath": "", "query": "hello"}
        }),
    )
    .expect("typed workspace search command");
    assert_eq!(search["hits"].as_array().expect("search hits").len(), 1);
    let ready = event_receiver
        .recv_timeout(total_deadline.saturating_duration_since(Instant::now()))
        .expect("ready event");
    assert_eq!(method(&ready), Some("runtime/status-changed"));
    assert_no_token(&ready);
    let stopped: RuntimeStatus = serde_json::from_value(
        invoke("ja_runtime_stop", serde_json::json!({})).expect("typed stop command"),
    )
    .expect("runtime stop response");
    assert_eq!(stopped.status, RuntimeStatusKind::Stopped);
    assert!(
        process_marker_visible(&marker),
        "typed stop must only disconnect desktop"
    );
    cleanup_on_exit(&app.state::<RuntimeHost>()).expect("mock actor shutdown");
    stop_test_shared_server(&config, &marker, total_deadline);
}

/// 旧窗口恢复标记是历史本地文件；MockRuntime 仍应经认证共享端点启动，且不删除该文件。
#[test]
fn tauri_mock_legacy_marker_does_not_block_shared_attach() {
    let _jvm_guard = jvm_runtime_test_guard();
    use crate::app_runtime::{
        RPC_FRAME_EVENT, RuntimeRecoveryStateDto as RuntimeRecoveryState, cleanup_on_exit,
        register_commands,
    };
    use tauri::Emitter;
    use tauri::Manager;
    use tauri::ipc::{CallbackFn, InvokeBody};
    use tauri::test::{INVOKE_KEY, get_ipc_response, mock_builder, mock_context, noop_assets};
    use tauri::webview::InvokeRequest;

    let run_dir = TempRunDir::create("tauri-recovery");
    let marker = format!("ja-tauri-recovery-marker-{}", std::process::id());
    let recovery_path = run_dir.0.join("runtime").join("ja-runtime-recovery.json");
    std::fs::create_dir_all(recovery_path.parent().expect("recovery parent"))
        .expect("create recovery directory");
    std::fs::write(
        &recovery_path,
        br#"{"schemaVersion":1,"status":"manual_recovery_required","recoveryId":"00000000-0000-4000-8000-000000000002","revision":9,"generation":1}"#,
    )
    .expect("recovery marker");
    let app_slot: Arc<OnceLock<tauri::AppHandle<tauri::test::MockRuntime>>> =
        Arc::new(OnceLock::new());
    let slot_for_sink = Arc::clone(&app_slot);
    let sink: EventSink = Arc::new(move |value| {
        let app = slot_for_sink.get().ok_or(EventEmitError::DeliveryFailed)?;
        app.emit(RPC_FRAME_EVENT, value)
            .map_err(|_| EventEmitError::DeliveryFailed)
    });
    let config = marked_fixture_config(&run_dir, &marker);
    let host = RuntimeHost::new(config.clone(), sink);
    let app = register_commands(mock_builder())
        .manage(host)
        .build(mock_context(noop_assets()))
        .expect("setup must succeed with recovery marker");
    let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("window must be created with a legacy marker");
    app_slot
        .set(app.handle().clone())
        .expect("single mock app handle");
    let invoke = |cmd: &str, body: Value| -> Result<Value, Value> {
        let request = InvokeRequest {
            cmd: cmd.to_owned(),
            callback: CallbackFn(0),
            error: CallbackFn(1),
            url: if cfg!(any(windows, target_os = "android")) {
                "http://tauri.localhost"
            } else {
                "tauri://localhost"
            }
            .parse()
            .expect("mock invoke URL"),
            body: InvokeBody::Json(body),
            headers: Default::default(),
            invoke_key: INVOKE_KEY.to_owned(),
        };
        get_ipc_response(&webview, request).and_then(|body| {
            body.deserialize::<Value>()
                .map_err(|error| Value::String(error.to_string()))
        })
    };
    let state: RuntimeStatus = serde_json::from_value(
        invoke("ja_runtime_state", serde_json::json!({})).expect("state command"),
    )
    .expect("state envelope");
    assert_eq!(state.status, RuntimeStatusKind::Stopped);
    let recovery: RuntimeRecoveryState = serde_json::from_value(
        invoke("ja_runtime_recovery_state", serde_json::json!({})).expect("recovery state"),
    )
    .expect("recovery projection");
    assert!(!recovery.required && !recovery.acknowledgeable);
    assert!(
        recovery_path.exists(),
        "shared attach must not delete the existing marker"
    );
    let started: RuntimeStatus = serde_json::from_value(
        invoke("ja_runtime_start", serde_json::json!({})).expect("shared attach despite marker"),
    )
    .expect("start response");
    assert_eq!(started.status, RuntimeStatusKind::Ready);
    let stopped: RuntimeStatus = serde_json::from_value(
        invoke("ja_runtime_stop", serde_json::json!({})).expect("typed stop"),
    )
    .expect("stop response");
    assert_eq!(stopped.status, RuntimeStatusKind::Stopped);
    cleanup_on_exit(&app.state::<RuntimeHost>()).expect("recovery host cleanup");
    stop_test_shared_server(&config, &marker, Instant::now() + Duration::from_secs(10));
}

/// 覆盖真实 MockRuntime exit-request 路径：永久清理故障必须阻止首次关闭，并保留 Java owner 的 Crashed 状态。
/// 只有显式重试成功回收进程后才能再次关闭，避免窗口退出掩盖隔离区中的活跃 owner。
#[test]
fn tauri_exit_request_denied_then_retry_allowed_with_quarantine() {
    let _jvm_guard = jvm_runtime_test_guard();
    use crate::handle_exit_requested;
    use tauri::Manager;
    use tauri::RunEvent;
    use tauri::test::{mock_builder, mock_context, noop_assets};

    let run_dir = TempRunDir::create("exit-quarantine");
    let marker = format!("ja-exit-marker-{}", std::process::id());
    let sink: EventSink = Arc::new(|_| Ok(()));
    let shutdown_failures = Arc::new(AtomicUsize::new(usize::MAX));
    let config = marked_fixture_config(&run_dir, &marker);
    let host = RuntimeHostHarness::with_exit_control(
        config.clone(),
        sink,
        Duration::from_millis(500),
        Arc::clone(&shutdown_failures),
    )
    .host();
    host.start().expect("sidecar ready");
    assert_process_marker_visible(&marker, Instant::now() + Duration::from_secs(10));
    let observer = host.clone();
    let app = mock_builder()
        .manage(host)
        .build(mock_context(noop_assets()))
        .expect("mock Tauri app");
    let first_window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("first mock window");
    let app_handle = app.handle().clone();
    let (event_sender, event_receiver) = mpsc::sync_channel(8);
    let (duration_sender, duration_receiver) = mpsc::sync_channel(4);
    let runner = std::thread::spawn(move || {
        app.run(move |app_handle, event| match event {
            RunEvent::Ready => {
                let _ = event_sender.send("ready");
            }
            RunEvent::ExitRequested { api, .. } => {
                let started = Instant::now();
                let host = app_handle.state::<RuntimeHost>();
                handle_exit_requested(&host, &api);
                let _ = duration_sender.send(started.elapsed());
                let _ = event_sender.send("exit-requested");
            }
            RunEvent::Exit => {
                let host = app_handle.state::<RuntimeHost>();
                let _ = event_sender.send(if host.exit_ready() {
                    "exit-ready"
                } else {
                    "exit-unsafe"
                });
            }
            _ => {}
        });
    });
    event_receiver
        .recv_timeout(Duration::from_secs(5))
        .expect("mock runtime ready");

    first_window.close().expect("first close request");
    assert_eq!(
        event_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("first exit request"),
        "exit-requested"
    );
    assert!(
        duration_receiver
            .recv_timeout(Duration::from_secs(1))
            .expect("first exit duration")
            < Duration::from_millis(500),
        "ExitRequested must observe the shared short deadline without waiting on a default writer timeout"
    );
    let crash_deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if let Ok(state) = observer.state()
            && state.status == RuntimeStatusKind::Crashed
        {
            break;
        }
        assert!(
            Instant::now() < crash_deadline,
            "first exit was not quarantined"
        );
        std::thread::sleep(Duration::from_millis(25));
    }
    assert!(
        process_marker_visible(&marker),
        "permanent cleanup fault must retain the real Java owner"
    );

    shutdown_failures.store(0, std::sync::atomic::Ordering::Release);
    let retry_window = tauri::WebviewWindowBuilder::new(&app_handle, "retry", Default::default())
        .build()
        .expect("retry mock window");
    retry_window.close().expect("retry close request");
    assert_eq!(
        event_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("second exit request"),
        "exit-requested"
    );
    assert_eq!(
        event_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("allowed exit"),
        "exit-ready"
    );
    runner.join().expect("mock runtime exit");
    assert!(
        process_marker_visible(&marker),
        "desktop exit must preserve shared backend"
    );
    stop_test_shared_server(&config, &marker, Instant::now() + Duration::from_secs(10));
}

/// 通过真实 JVM sidecar 验证 Tauri Host actor、Rust supervisor、连续失败终态与优雅关闭。
/// 两个 Thread 串行启动用于锁定 accepted 提交边界：先经生产 general workspace 入口绑定
/// App Server identity 与原生 capability，后续 Turn 才能捕获不混入既有 dirty 状态的基线；
/// 前一个 Turn 完成后，后一个 Turn 也不能因生命周期投影而误报失败并诱发 UI 重试。
/// 总预算覆盖真实 JVM 冷启动、配置与 workspace 写入以及两个连续 Turn，为 hosted CI 负载留出余量；
/// 本地 Provider 用真实结构化调用产生审批，不依赖生产测试分支或不可达网络的最终失败。
#[test]
fn real_java_turn_and_shutdown_close_without_token_leak() {
    let _jvm_guard = jvm_runtime_test_guard();
    let provider = crate::responses_provider::ResponsesProvider::start();
    let run_dir = TempRunDir::create("turn");
    let marker = format!("ja-marker-{}", std::process::id());
    let total_deadline = Instant::now() + Duration::from_secs(60);
    let (sink, receiver) = event_sink();
    let config = marked_fixture_config(&run_dir, &marker);
    let harness = RuntimeHostHarness::new(config.clone(), sink);
    let host = harness.host();
    let started = host.start().expect("sidecar ready");
    assert_eq!(started.status, RuntimeStatusKind::Ready);
    let ready_events = receive_until_at(&receiver, total_deadline, |value| {
        value
            .get("params")
            .and_then(|params| params.get("status"))
            .and_then(Value::as_str)
            == Some("ready")
    });
    assert!(ready_events.iter().any(|value| {
        value
            .get("params")
            .and_then(|params| params.get("status"))
            .and_then(Value::as_str)
            == Some("ready")
    }));

    configure_profile_with_provider(&harness, &provider.base_url());
    let thread_id = harness
        .create_thread("Rust host integration", "provider_host", "model_host")
        .expect("Java-owned thread created");
    harness
        .observe_thread(&thread_id)
        .expect("observe first Thread before Turn");
    let accepted = host
        .turn_start(valid_turn(thread_id, "hello from rust host"))
        .expect("fake turn accepted");
    assert!(accepted.accepted);
    let events = receive_until_at(&receiver, total_deadline, |value| {
        method(value) == Some("turn/terminal")
    });
    let methods: Vec<&str> = events
        .iter()
        .filter_map(method)
        .filter(|name| {
            matches!(
                *name,
                "turn/state-changed" | "assistant/text-delta" | "turn/terminal"
            )
        })
        .collect();
    assert_eq!(methods.first(), Some(&"turn/state-changed"));
    assert_eq!(methods.last(), Some(&"turn/terminal"));
    assert!(
        events
            .iter()
            .any(|value| method(value) == Some("turn/terminal")
                && value["params"]["state"] == "completed"),
        "terminal: {:?}",
        events
            .iter()
            .filter(|value| method(value) == Some("turn/terminal"))
            .collect::<Vec<_>>()
    );

    let project_root = run_dir.0.join("project");
    std::fs::create_dir_all(project_root.join(".ja-fixture"))
        .expect("create project fixture directory");
    std::fs::write(
        project_root.join(".ja-fixture").join("change.txt"),
        b"before\n",
    )
    .expect("create review fixture file");
    host.open_workspace(WorkspaceOpenInput {
        cwd: project_root.to_string_lossy().into_owned(),
        display_name: Some("Rust host project".to_owned()),
        trust: "trusted".to_owned(),
    })
    .expect("project workspace opened");
    let second_thread_id = harness
        .create_workspace_thread(
            "Rust host second integration",
            "provider_host",
            "model_host",
            project_root.to_string_lossy().into_owned(),
        )
        .expect("second Java-owned thread created");
    harness
        .observe_thread(&second_thread_id)
        .expect("observe review Thread before Turn");
    let second_started_at = Instant::now();
    let second_accepted = host
        .turn_start(valid_turn(
            second_thread_id.clone(),
            "__JA_FAKE_REVIEW_FIXTURE__",
        ))
        .expect("approval fixture accepted before waiting for user decision");
    assert!(
        second_started_at.elapsed() < Duration::from_secs(5),
        "审批 Turn 的 accepted 不得等待用户决定或逼近 Host command deadline"
    );
    assert!(second_accepted.accepted);
    let mut second_events = Vec::new();
    loop {
        let event = receiver
            .recv_timeout(total_deadline.saturating_duration_since(Instant::now()))
            .expect("approval fixture event before deadline");
        let params = event.get("params").and_then(Value::as_object);
        let belongs_to_second = params
            .and_then(|value| value.get("threadId"))
            .and_then(Value::as_str)
            == Some(second_thread_id.as_str());
        if belongs_to_second && method(&event) == Some("approval/requested") {
            let params = params.expect("approval params");
            host.approval_respond(ApprovalResponseInput {
                client_operation_id: format!("op_{}", uuid::Uuid::new_v4().simple()),
                approval_id: params
                    .get("approvalId")
                    .and_then(Value::as_str)
                    .expect("approval id")
                    .to_owned(),
                turn_id: params
                    .get("turnId")
                    .and_then(Value::as_str)
                    .expect("approval turn id")
                    .to_owned(),
                decision: "approve".to_owned(),
                expected_thread_revision: params
                    .get("threadRevision")
                    .and_then(Value::as_u64)
                    .expect("approval revision"),
            })
            .expect("approval response accepted");
        }
        let terminal = belongs_to_second && method(&event) == Some("turn/terminal");
        second_events.push(event);
        if terminal {
            break;
        }
    }
    let second_first_revision = second_events
        .iter()
        .filter(|value| {
            value
                .get("params")
                .and_then(|params| params.get("threadId"))
                .and_then(Value::as_str)
                == Some(second_thread_id.as_str())
        })
        .filter_map(|value| {
            value
                .get("params")
                .and_then(|params| params.get("threadRevision"))
                .and_then(Value::as_u64)
        })
        .min()
        .expect("second turn revision event");
    assert_eq!(
        second_first_revision,
        second_accepted.thread_revision.saturating_add(1),
        "accepted revision 必须与第二个 Turn 的首个持久事件连续"
    );

    assert_process_marker_visible(&marker, total_deadline);
    let stopped = host.stop().expect("desktop disconnect");
    assert_eq!(stopped.status, RuntimeStatusKind::Stopped);
    assert!(
        process_marker_visible(&marker),
        "desktop stop must retain shared Java owner"
    );
    let _ = receive_until_at(&receiver, total_deadline, |value| {
        value
            .get("params")
            .and_then(|params| params.get("status"))
            .and_then(Value::as_str)
            == Some("stopped")
    });
    host.shutdown().expect("desktop actor shutdown");
    stop_test_shared_server(&config, &marker, total_deadline);
}

/// 桌面在审批等待中退出后，第二个原生宿主必须从 Java 权威快照找回同一个 Turn 并继续完成。
#[test]
fn desktop_disconnect_preserves_pending_turn_for_reconnected_host() {
    let _jvm_guard = jvm_runtime_test_guard();
    let provider = crate::responses_provider::ResponsesProvider::start();
    let run_dir = TempRunDir::create("shared-turn-reconnect");
    let marker = format!("ja-shared-turn-marker-{}", std::process::id());
    let config = marked_fixture_config(&run_dir, &marker);
    let deadline = Instant::now() + Duration::from_secs(60);
    let (first_sink, first_events) = event_sink();
    let first_harness = RuntimeHostHarness::new(config.clone(), first_sink);
    let first_host = first_harness.host();
    let original_status = first_host.start().expect("first desktop connected");
    configure_profile_with_provider(&first_harness, &provider.base_url());

    let project_root = run_dir.0.join("project");
    std::fs::create_dir_all(project_root.join(".ja-fixture"))
        .expect("create review fixture directory");
    std::fs::write(
        project_root.join(".ja-fixture").join("change.txt"),
        b"before\n",
    )
    .expect("create review fixture file");
    first_host
        .open_workspace(WorkspaceOpenInput {
            cwd: project_root.to_string_lossy().into_owned(),
            display_name: Some("Shared turn project".to_owned()),
            trust: "trusted".to_owned(),
        })
        .expect("open Java-owned project");
    let thread_id = first_harness
        .create_workspace_thread(
            "Shared pending turn",
            "provider_host",
            "model_host",
            project_root.to_string_lossy().into_owned(),
        )
        .expect("create shared thread");
    first_harness
        .observe_thread(&thread_id)
        .expect("observe pending Thread");
    let accepted = first_host
        .turn_start(valid_turn(thread_id.clone(), "__JA_FAKE_REVIEW_FIXTURE__"))
        .expect("turn accepted");
    let approval = receive_until_at(&first_events, deadline, |value| {
        method(value) == Some("approval/requested") && value["params"]["threadId"] == thread_id
    })
    .into_iter()
    .find(|value| method(value) == Some("approval/requested"))
    .expect("approval request");
    let params = approval["params"].as_object().expect("approval params");
    let approval_id = params["approvalId"]
        .as_str()
        .expect("approval id")
        .to_owned();
    let approval_revision = params["threadRevision"]
        .as_u64()
        .expect("approval revision");

    first_host.stop().expect("first desktop disconnect");
    first_host.shutdown().expect("first desktop actor closes");
    assert!(
        process_marker_visible(&marker),
        "pending Turn must keep backend alive"
    );

    let (second_sink, second_events) = event_sink();
    let second_harness = RuntimeHostHarness::new(config.clone(), second_sink);
    let second_host = second_harness.host();
    let reconnected = second_host.start().expect("second desktop reconnected");
    assert_eq!(
        reconnected.server_instance_id,
        original_status.server_instance_id
    );
    assert_eq!(reconnected.generation, original_status.generation);
    second_harness
        .observe_thread(&thread_id)
        .expect("observe resumed Thread");
    let snapshot = second_harness
        .read_thread(&thread_id)
        .expect("read authoritative thread");
    assert!(
        snapshot["turns"]
            .as_array()
            .is_some_and(|turns| turns.iter().any(|turn| turn["turnId"] == accepted.turn_id)),
        "reconnected client must find the accepted Turn"
    );
    second_host
        .approval_respond(ApprovalResponseInput {
            client_operation_id: format!("op_{}", uuid::Uuid::new_v4().simple()),
            approval_id,
            turn_id: accepted.turn_id.clone(),
            decision: "approve".to_owned(),
            expected_thread_revision: approval_revision,
        })
        .expect("approval from reconnected client accepted");
    let terminal = receive_until_at(&second_events, deadline, |value| {
        method(value) == Some("turn/terminal") && value["params"]["turnId"] == accepted.turn_id
    });
    assert!(
        terminal
            .iter()
            .any(|value| method(value) == Some("turn/terminal"))
    );
    second_host.shutdown().expect("second desktop disconnect");
    assert!(
        process_marker_visible(&marker),
        "desktop exit must preserve shared backend"
    );
    stop_test_shared_server(&config, &marker, deadline);
}

/// 设计原因：当前 Cargo test 所在的 Windows Job 禁止 breakaway；此测试只连接由隔离 CLI
/// 预启的健康 owner，实测两个桌面 Host 的观察、权威基线和断连，不把测试宿主限制伪装为产品失败。
#[test]
#[ignore = "set JA_TEST_ATTACH_ROOT to a CLI-prestarted isolated Ja root"]
fn prestarted_cli_daemon_survives_two_desktop_hosts() {
    let _jvm_guard = jvm_runtime_test_guard();
    let root = PathBuf::from(std::env::var_os("JA_TEST_ATTACH_ROOT").expect("isolated root"));
    assert!(
        root.is_absolute() && root.is_dir(),
        "isolated root must exist"
    );
    let java = java_executable();
    let jar = test_jar();
    let data = root.join("data");
    let run = root.join("run");
    let logs = root.join("logs").join("java");
    for directory in [&data, &run, &logs] {
        assert!(directory.is_dir(), "CLI must prepare all four directories");
    }
    let legacy_marker = run.join("ja-runtime-recovery.json");
    let legacy_bytes = br#"{"schemaVersion":1,"status":"manual_recovery_required","recoveryId":"00000000-0000-4000-8000-000000000003","revision":11,"generation":1}"#;
    std::fs::write(&legacy_marker, legacy_bytes).expect("write isolated legacy marker");
    let config = RuntimeHostHarness::launch_config_with_dirs(
        java,
        vec![OsString::from("-jar"), jar.into_os_string()],
        root.clone(),
        data,
        run,
        logs,
    );
    let original_instance = SharedAppServerClient::connect_existing(&config.sidecar, 1)
        .expect("CLI-prestarted backend must be healthy")
        .server_instance_id()
        .to_owned();

    let first_harness = RuntimeHostHarness::new(config.clone(), Arc::new(|_| Ok(())));
    let first_host = first_harness.host();
    let first = first_host.start().expect("first Desktop attaches");
    assert_eq!(
        first.server_instance_id.as_deref(),
        Some(original_instance.as_str())
    );
    configure_isolated_profile(&first_harness);
    let project = root.join("project");
    std::fs::create_dir_all(&project).expect("isolated project");
    first_host
        .open_workspace(WorkspaceOpenInput {
            cwd: project.to_string_lossy().into_owned(),
            display_name: Some("Shared Desktop attach".to_owned()),
            trust: "trusted".to_owned(),
        })
        .expect("open Java-owned workspace");
    let thread_id = first_harness
        .create_workspace_thread(
            "Shared observed thread",
            "provider_host",
            "model_host",
            project.to_string_lossy().into_owned(),
        )
        .expect("create Thread without Provider call");
    first_harness
        .observe_thread(&thread_id)
        .expect("first observe ACK");
    assert_eq!(
        first_harness
            .read_thread(&thread_id)
            .expect("first baseline")["threadId"],
        thread_id
    );
    first_host.stop().expect("first Desktop disconnects");
    first_host.shutdown().expect("first Desktop actor exits");

    let second_harness = RuntimeHostHarness::new(config.clone(), Arc::new(|_| Ok(())));
    let second_host = second_harness.host();
    let second = second_host.start().expect("second Desktop attaches");
    assert_eq!(second.server_instance_id, first.server_instance_id);
    assert_eq!(second.generation, first.generation);
    second_harness
        .observe_thread(&thread_id)
        .expect("second observe ACK");
    assert_eq!(
        second_harness
            .read_thread(&thread_id)
            .expect("reconnect baseline")["threadId"],
        thread_id
    );
    second_host.stop().expect("second Desktop disconnects");
    second_host.shutdown().expect("second Desktop actor exits");
    assert_eq!(
        std::fs::read(&legacy_marker).expect("legacy marker retained"),
        legacy_bytes,
        "shared attach may not delete historical user files"
    );
    SharedAppServerClient::connect_existing(&config.sidecar, 1)
        .expect("CLI owner survives both Desktop disconnects");
}

/// 验证配置读取只经过 Java-owned JA-RPC 闭集，Rust Harness 不构造配置快照或解析凭据。
/// 测试仅断言脱敏投影和进程清理，避免把已经删除的 Rust 配置 owner 重新带回生产边界。
#[test]
fn real_java_configuration_read_is_java_owned_and_secret_free() {
    let _jvm_guard = jvm_runtime_test_guard();
    let run_dir = TempRunDir::create("replay");
    let marker = format!("ja-config-marker-{}", std::process::id());
    let (sink, receiver) = event_sink();
    let config = marked_fixture_config(&run_dir, &marker);
    let harness = RuntimeHostHarness::new(config.clone(), sink);
    let host = harness.host();
    let started = host.start().expect("configured sidecar ready");
    assert_eq!(started.status, RuntimeStatusKind::Ready);
    let configuration = harness
        .config_request("configuration/read", serde_json::json!({}))
        .expect("Java-owned configuration projection");
    assert!(configuration.get("effective").is_some());
    assert!(configuration.get("credentials").is_some());
    let projection = serde_json::to_string(&configuration).expect("serialize configuration");
    assert!(!projection.contains("secret"));
    host.shutdown().expect("configured host shutdown");
    let events = receiver.try_iter().collect::<Vec<_>>();
    let event_text = serde_json::to_string(&events).expect("serialize observed events");
    assert!(!event_text.contains("secret"));
    assert!(
        process_marker_visible(&marker),
        "desktop exit must retain shared backend"
    );
    stop_test_shared_server(&config, &marker, Instant::now() + Duration::from_secs(10));
}

/// 不依赖 WebView2 调试端口，以真实 Java/Tauri 连接验收压缩窄 lease 和后续普通历史读取。
#[test]
fn manual_compaction_lease_returns_without_blocking_later_history_reads() {
    let _jvm_guard = jvm_runtime_test_guard();
    let run_dir = TempRunDir::create("compact-lease");
    let marker = format!("ja-compact-lease-{}", std::process::id());
    let (sink, _events) = event_sink();
    let config = marked_fixture_config(&run_dir, &marker);
    let harness = RuntimeHostHarness::new(config.clone(), sink);
    let host = harness.host();
    assert_eq!(
        host.start().expect("isolated Java ready").status,
        RuntimeStatusKind::Ready
    );
    configure_isolated_profile(&harness);
    let thread_id = harness
        .create_thread("Compaction lease", "provider_host", "model_host")
        .expect("create empty Java-owned thread");
    let before = harness
        .read_thread(&thread_id)
        .expect("read initial thread");
    let revision = before["revision"].as_u64().expect("thread revision");
    let compacted = harness
        .compact_thread(&thread_id, revision)
        .expect("empty thread compaction result");
    assert_eq!(compacted["outcome"], "unchanged");
    assert_eq!(compacted["threadRevision"], revision);
    let after = harness
        .read_thread(&thread_id)
        .expect("history remains available");
    assert_eq!(after["revision"], revision);
    let cancelled = harness
        .cancel_compaction(&thread_id)
        .expect("idle cancellation must use same live connection");
    assert_eq!(cancelled["accepted"], false);
    host.shutdown().expect("disconnect desktop host");
    stop_test_shared_server(&config, &marker, Instant::now() + Duration::from_secs(10));
}

/// 旧独占 sidecar 的 marker 不属于新共享后台；健康连接应保留 marker 原样并完成 attach。
#[test]
fn legacy_recovery_marker_does_not_block_shared_client() {
    let _jvm_guard = jvm_runtime_test_guard();
    let run_dir = TempRunDir::create("recovery-gate");
    let marker = format!("ja-recovery-marker-{}", std::process::id());
    let recovery_path = run_dir.0.join("runtime").join("ja-runtime-recovery.json");
    std::fs::create_dir_all(recovery_path.parent().expect("recovery parent"))
        .expect("create recovery directory");
    std::fs::write(
        &recovery_path,
        br#"{"schemaVersion":1,"status":"manual_recovery_required","recoveryId":"00000000-0000-4000-8000-000000000001","revision":7,"generation":1}"#,
    )
    .expect("recovery marker");
    let sink: EventSink = Arc::new(|_| Ok(()));
    let config = marked_fixture_config(&run_dir, &marker);
    let host = RuntimeHost::new(config.clone(), sink);
    assert_eq!(
        host.state().expect("client state").status,
        RuntimeStatusKind::Stopped
    );
    host.start().expect("shared daemon ready despite marker");
    assert!(
        recovery_path.exists(),
        "legacy marker must remain untouched"
    );
    assert_process_marker_visible(&marker, Instant::now() + Duration::from_secs(10));
    host.stop().expect("desktop disconnect");
    assert!(
        process_marker_visible(&marker),
        "shared backend must survive desktop stop"
    );
    host.shutdown().expect("desktop actor shutdown");
    stop_test_shared_server(&config, &marker, Instant::now() + Duration::from_secs(10));
}

/// 验证重复生命周期调用保持幂等，后续 start 必须创建干净进程而不能复用陈旧 event pump。
#[test]
fn repeated_start_stop_is_bounded_and_idempotent() {
    let _jvm_guard = jvm_runtime_test_guard();
    let run_dir = TempRunDir::create("lifecycle");
    let marker = format!("ja-lifecycle-marker-{}", std::process::id());
    let sink: EventSink = Arc::new(|_| Ok(()));
    let config = marked_fixture_config(&run_dir, &marker);
    let bridge = RuntimeHostHarness::bridge(config.clone(), sink).expect("bridge actor");
    let first = bridge.start().expect("first start");
    assert_eq!(first.status, RuntimeStatusKind::Ready);
    assert_eq!(
        bridge.start().expect("duplicate start").status,
        RuntimeStatusKind::Ready
    );
    assert_eq!(
        bridge.stop().expect("first stop").status,
        RuntimeStatusKind::Stopped
    );
    assert_eq!(
        bridge.stop().expect("duplicate stop").status,
        RuntimeStatusKind::Stopped
    );
    let reconnected = bridge.start().expect("reattach shared backend");
    assert_eq!(reconnected.status, RuntimeStatusKind::Ready);
    assert_eq!(reconnected.server_instance_id, first.server_instance_id);
    assert_eq!(reconnected.generation, first.generation);
    assert_eq!(
        bridge.stop().expect("second stop").status,
        RuntimeStatusKind::Stopped
    );
    bridge.shutdown().expect("actor shutdown");
    assert!(
        process_marker_visible(&marker),
        "bridge close must retain shared backend"
    );
    stop_test_shared_server(&config, &marker, Instant::now() + Duration::from_secs(10));
}

/// 验证 launch failure 保持稳定错误，不触发自动重启循环，也不残留任何 child 进程。
#[test]
fn launch_failure_does_not_crash_loop() {
    let _jvm_guard = jvm_runtime_test_guard();
    let run_dir = TempRunDir::create("failure");
    let missing = run_dir.0.join("missing-sidecar");
    let sink: EventSink = Arc::new(|_| Ok(()));
    let invalid = RuntimeHostHarness::bridge(
        RuntimeHostHarness::launch_config(missing, Vec::new(), run_dir.0.clone()),
        sink,
    )
    .err()
    .expect("missing executable must fail before actor creation");
    assert_eq!(invalid.code, "RUNTIME_CONFIG_INVALID");

    let run_dir = TempRunDir::create("failure-admission");
    let missing = run_dir.0.join("missing-sidecar");
    std::fs::write(&missing, b"test-owned launch placeholder")
        .expect("create invalid sidecar placeholder");
    let sink: EventSink = Arc::new(|_| Ok(()));
    let (bridge, admission) = RuntimeHostHarness::bridge_with_start_failure_control(
        RuntimeHostHarness::launch_config(missing, Vec::new(), run_dir.0.clone()),
        sink,
    )
    .expect("gated bridge actor");
    let total_deadline = Instant::now() + Duration::from_secs(10);
    let (start_sender, start_receiver) = mpsc::sync_channel(1);
    let starter_bridge = bridge.clone();
    let starter = std::thread::spawn(move || {
        let _ = start_sender.send(starter_bridge.start());
    });
    assert!(
        admission.wait_until_armed(total_deadline),
        "start command did not reach the post-admission barrier"
    );

    let (shutdown_sender, shutdown_receiver) = mpsc::sync_channel(1);
    let shutdown_bridge = bridge.clone();
    let shutdowner = std::thread::spawn(move || {
        let _ = shutdown_sender.send(shutdown_bridge.shutdown());
    });
    let shutdown_admitted = admission.wait_for("inner_shutdown_sent", total_deadline);
    admission.release();
    assert!(
        shutdown_admitted,
        "priority shutdown was not admitted; trace={:?}",
        admission.events()
    );

    let start_result = start_receiver
        .recv_timeout(total_deadline.saturating_duration_since(Instant::now()))
        .expect("failed start result before deadline")
        .expect_err("missing executable must fail");
    assert_eq!(start_result.code, "SIDECAR_CRASHED");
    shutdown_receiver
        .recv_timeout(total_deadline.saturating_duration_since(Instant::now()))
        .expect("shutdown result before deadline")
        .expect("priority shutdown after failed start");
    join_with_deadline(starter, total_deadline).expect("starter joined");
    join_with_deadline(shutdowner, total_deadline).expect("shutdowner joined");
    let events = admission.events();
    let position = |name: &str| {
        events
            .iter()
            .position(|event| event == name)
            .unwrap_or_else(|| panic!("missing lifecycle event {name}: {events:?}"))
    };
    assert!(position("inner_shutdown_sent") < position("start_failure_gate_released"));
    assert!(position("start_client_connect_error") < position("actor_start_reply_err"));
    assert!(position("actor_start_reply_err") < position("actor_shutdown_received"));
    assert!(position("actor_shutdown_received") < position("actor_shutdown_confirmed"));
}
