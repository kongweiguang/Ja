// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 共享连接的文件与网络失效边界；不启动真实 App Server，不影响用户现有实例。

use ja_runtime::app_server_process::{
    AppServerProcessError, NativeClientIdentity, SharedAppServerClient, SidecarConfig,
};
use std::ffi::OsString;
use std::fs;
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{self, Receiver};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use std::time::{SystemTime, UNIX_EPOCH};
use tungstenite::{Message, WebSocket};

/// 同一个受信配置必须向 CLI 与桌面构造完全一致的四个 Java 目录参数。
#[test]
fn shared_config_uses_frozen_directory_arguments() {
    let root = temporary_root("arguments");
    let config = fixture_config(&root);
    assert_eq!(config.args.len(), 4);
    assert!(
        config.args[0]
            .to_string_lossy()
            .starts_with("--home-dir-base64=")
    );
    assert!(
        config.args[1]
            .to_string_lossy()
            .starts_with("--data-dir-base64=")
    );
    assert!(
        config.args[2]
            .to_string_lossy()
            .starts_with("--run-dir-base64=")
    );
    assert!(
        config.args[3]
            .to_string_lossy()
            .starts_with("--log-dir-base64=")
    );
    assert!(config.validate().is_ok());
    fs::remove_dir_all(root).expect("fixture cleanup");
}

/// 崩溃遗留端点的闭合端口返回 NotReady，让 connect_or_start 能在启动锁内重新启动。
#[test]
#[cfg(unix)]
fn stale_endpoint_port_is_recoverable() {
    let root = temporary_root("stale");
    let config = fixture_config(&root);
    let listener =
        TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)).expect("temporary port");
    let port = listener.local_addr().expect("bound address").port();
    drop(listener);
    write_endpoint(&root, "127.0.0.1", port, "a".repeat(64));
    let identity = NativeClientIdentity::new_current_process().expect("native identity");
    let result = SharedAppServerClient::connect_existing(&config, 1, &identity);
    assert!(matches!(result, Err(AppServerProcessError::NotReady)));
    fs::remove_dir_all(root).expect("fixture cleanup");
}

/// 无效 token 不能被当成可恢复 stale 端点触发另一个 daemon 启动。
#[test]
fn malformed_endpoint_fails_closed() {
    let root = temporary_root("malformed");
    let config = fixture_config(&root);
    write_endpoint(&root, "127.0.0.1", 42_117, "invalid".to_owned());
    let identity = NativeClientIdentity::new_current_process().expect("native identity");
    let result = SharedAppServerClient::connect_existing(&config, 1, &identity);
    assert!(matches!(result, Err(AppServerProcessError::ProtocolFault)));
    fs::remove_dir_all(root).expect("fixture cleanup");
}

/// 端点中的通配或非地址文本不能被当成连接目标，避免把 token 发往不确定网络位置。
#[test]
fn malformed_endpoint_host_fails_closed() {
    for host in ["0.0.0.0", "example.com"] {
        let root = temporary_root("malformed-host");
        let config = fixture_config(&root);
        write_endpoint(&root, host, 42_117, "a".repeat(64));
        let identity = NativeClientIdentity::new_current_process().expect("native identity");
        let result = SharedAppServerClient::connect_existing(&config, 1, &identity);
        assert!(matches!(result, Err(AppServerProcessError::ProtocolFault)));
        fs::remove_dir_all(root).expect("fixture cleanup");
    }
}

/// 后台在发布端点前退出时应立即反馈退出状态，不能让 CLI 等完整启动超时。
#[test]
fn early_daemon_exit_is_reported_without_waiting_for_timeout() {
    let root = temporary_root("early-exit");
    let mut config = fixture_config(&root);
    config.ready_timeout = Duration::from_secs(5);
    let started = Instant::now();
    let identity = NativeClientIdentity::new_current_process().expect("native identity");
    let result = SharedAppServerClient::connect_or_start(config, 1, &identity);
    assert!(matches!(result, Err(AppServerProcessError::ProcessExited)));
    assert!(started.elapsed() < Duration::from_secs(5));
    fs::remove_dir_all(root).expect("fixture cleanup");
}

/// 仅在显式提供隔离 Java 25 fat JAR 时运行，验证 Rust 真实启动、认证、
/// 双客户端断连和重连均不改变同一个 Java owner 的身份。
#[test]
#[ignore = "requires JA_TEST_JAVA and JA_WEBSOCKET_TEST_JAR; starts an isolated Java daemon"]
fn windows_java_daemon_survives_client_disconnect() {
    let java = std::env::var_os("JA_TEST_JAVA").expect("JA_TEST_JAVA");
    let jar = std::env::var_os("JA_WEBSOCKET_TEST_JAR").expect("JA_WEBSOCKET_TEST_JAR");
    let root = temporary_root("java-daemon");
    let home = root.join("home");
    let data = root.join("data");
    let run = root.join("run");
    let log = root.join("log");
    for directory in [&home, &data, &run, &log] {
        fs::create_dir_all(directory).expect("isolated role directory");
    }
    let mut config = SidecarConfig::with_shared_directories(java, &home, &data, &run, &log)
        .expect("shared Java config");
    config.args.splice(0..0, [OsString::from("-jar"), jar]);
    config.ready_timeout = Duration::from_secs(20);
    let first_identity = NativeClientIdentity::new_current_process().expect("first identity");
    let second_identity = NativeClientIdentity::new_current_process().expect("second identity");
    let mut first = SharedAppServerClient::connect_or_start(config.clone(), 42, &first_identity)
        .expect("first WebSocket client starts daemon");
    let mut second = SharedAppServerClient::connect_existing(&config, 43, &second_identity)
        .expect("second WebSocket client joins same daemon");
    let instance = first.server_instance_id().to_owned();
    assert_eq!(second.server_instance_id(), instance);
    assert_eq!(second.runtime_generation(), first.runtime_generation());
    first
        .disconnect_until(Instant::now() + Duration::from_secs(3))
        .expect("first client disconnect");
    let health = second
        .request(
            "runtime/health",
            serde_json::json!({}),
            Duration::from_secs(5),
        )
        .expect("second client health after first disconnect");
    assert_eq!(
        health
            .result()
            .value()
            .and_then(|result| result.get("status"))
            .and_then(serde_json::Value::as_str),
        Some("ready")
    );
    let mut third = SharedAppServerClient::connect_existing(&config, 44, &first_identity)
        .expect("third WebSocket client reconnects");
    assert_eq!(third.server_instance_id(), instance);
    third
        .stop_server(Duration::from_secs(5))
        .expect("isolated daemon shutdown");
    let _ = third.disconnect_until(Instant::now() + Duration::from_secs(3));
    let _ = second.disconnect_until(Instant::now() + Duration::from_secs(3));
    for _ in 0..100 {
        if !run.join("app-server.endpoint.json").exists() {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(!run.join("app-server.endpoint.json").exists());
    remove_daemon_fixture_after_exit(&root);
}

/// Local protocol fixture distinguishes a live transport, lost close ACK, broken connection and dead daemon.
#[derive(Clone, Copy)]
enum NativeCloseScenario {
    AcknowledgeOnFirstConnection,
    DisconnectBeforeClose,
    LoseFirstCloseAck,
    ExitAfterFirstClose,
}

struct LocalNativeCloseServer {
    config: SidecarConfig,
    accepted_connections: Arc<AtomicUsize>,
    registrations: Arc<Mutex<Vec<(u64, String)>>>,
    first_disconnect: Receiver<()>,
    server: Option<JoinHandle<()>>,
}

/// Start a loopback-only JA-RPC peer in the test's private directory; it never launches Java or touches user data.
fn local_native_close_server(label: &str, scenario: NativeCloseScenario) -> LocalNativeCloseServer {
    let root = temporary_root(label);
    let mut config = fixture_config(&root);
    config.ready_timeout = Duration::from_secs(4);
    config.env.clear();
    let listener =
        TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)).expect("local RPC listener");
    let port = listener.local_addr().expect("local RPC address").port();
    let endpoint_token = "b".repeat(64);
    write_endpoint(&root, "127.0.0.1", port, endpoint_token.clone());
    let accepted_connections = Arc::new(AtomicUsize::new(0));
    let server_connections = Arc::clone(&accepted_connections);
    let registrations = Arc::new(Mutex::new(Vec::new()));
    let server_registrations = Arc::clone(&registrations);
    let (disconnect_sender, first_disconnect) = mpsc::channel();
    let server = std::thread::spawn(move || {
        let connection_limit = match scenario {
            NativeCloseScenario::AcknowledgeOnFirstConnection
            | NativeCloseScenario::ExitAfterFirstClose => 1,
            NativeCloseScenario::DisconnectBeforeClose | NativeCloseScenario::LoseFirstCloseAck => {
                2
            }
        };
        for connection_index in 0..connection_limit {
            let (stream, _) = listener.accept().expect("accept local client");
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .expect("bound server read");
            server_connections.fetch_add(1, Ordering::AcqRel);
            let mut websocket = tungstenite::accept(stream).expect("accept websocket");
            let identity = local_rpc_handshake(&mut websocket, &endpoint_token);
            server_registrations
                .lock()
                .expect("registration lock")
                .push(identity);

            if connection_index == 0
                && matches!(scenario, NativeCloseScenario::DisconnectBeforeClose)
            {
                drop(websocket);
                let _ = disconnect_sender.send(());
                continue;
            }
            let Some(request) = read_local_rpc_frame(&mut websocket) else {
                return;
            };
            if request.get("method").and_then(serde_json::Value::as_str)
                != Some("runtime/client/close")
            {
                panic!("Native close-only connection sent a non-close RPC");
            }
            let params = request.get("params").and_then(serde_json::Value::as_object);
            assert!(params.is_some_and(|params| {
                params.len() == 1
                    && params
                        .get("timeoutMs")
                        .and_then(serde_json::Value::as_u64)
                        .is_some_and(|timeout| (1..=20_000).contains(&timeout))
            }));
            if connection_index == 0 {
                match scenario {
                    NativeCloseScenario::LoseFirstCloseAck => {
                        drop(websocket);
                        let _ = disconnect_sender.send(());
                        continue;
                    }
                    NativeCloseScenario::ExitAfterFirstClose => return,
                    NativeCloseScenario::AcknowledgeOnFirstConnection
                    | NativeCloseScenario::DisconnectBeforeClose => {}
                }
            }
            let Some(id) = request.get("id").cloned() else {
                panic!("close request must have an id");
            };
            send_local_rpc_result(&mut websocket, id, serde_json::json!({"accepted": true}));
            return;
        }
    });
    LocalNativeCloseServer {
        config,
        accepted_connections,
        registrations,
        first_disconnect,
        server: Some(server),
    }
}

/// Complete the frozen local initialize handshake and capture only the opaque Native owner identity.
fn local_rpc_handshake(
    websocket: &mut WebSocket<TcpStream>,
    endpoint_token: &str,
) -> (u64, String) {
    let auth = read_local_rpc_frame(websocket).expect("endpoint auth");
    assert!(auth.get("token").and_then(serde_json::Value::as_str) == Some(endpoint_token));
    loop {
        let Some(request) = read_local_rpc_frame(websocket) else {
            panic!("client handshake closed");
        };
        match request.get("method").and_then(serde_json::Value::as_str) {
            Some("runtime/initialize") => {
                let params = request.get("params").expect("initialize params");
                let id = request.get("id").cloned().expect("initialize request id");
                send_local_rpc_result(
                    websocket,
                    id,
                    serde_json::json!({
                        "protocolMajor": 2,
                        "protocolMinor": 0,
                        "serverInstanceId": "srv_fixture",
                        "runtime": {
                            "engine": "ja-kernel",
                            "engineVersion": env!("CARGO_PKG_VERSION")
                        },
                        "capabilities": params["capabilities"].clone(),
                        "limits": params["limits"].clone()
                    }),
                );
            }
            Some("runtime/initialized") => {
                let ready_token = request
                    .get("params")
                    .and_then(|params| params.get("readyToken"))
                    .and_then(serde_json::Value::as_str)
                    .expect("initialized challenge")
                    .to_owned();
                websocket
                    .send(Message::Text(
                        serde_json::json!({
                            "jsonrpc": "2.0",
                            "method": "runtime/status-changed",
                            "params": {
                                "eventId": "evt_fixture",
                                "occurredAt": "2026-10-08T00:00:00Z",
                                "serverInstanceId": "srv_fixture",
                                "generation": 1,
                                "status": "ready",
                                "readyToken": ready_token
                            }
                        })
                        .to_string()
                        .into(),
                    ))
                    .expect("ready notification");
            }
            Some("runtime/context/register") => {
                let params = request.get("params").expect("register params");
                let process_id = params["clientProcessId"]
                    .as_u64()
                    .expect("Native process id");
                let lifetime_id = params["clientLifetimeId"]
                    .as_str()
                    .expect("Native lifetime id")
                    .to_owned();
                assert!(process_id > 0);
                assert!(valid_native_lifetime_id(&lifetime_id));
                let id = request.get("id").cloned().expect("register request id");
                send_local_rpc_result(
                    websocket,
                    id,
                    serde_json::json!({
                        "contextId": "ctx_00000000000000000000000000000000"
                    }),
                );
                return (process_id, lifetime_id);
            }
            _ => panic!("unexpected handshake method"),
        }
    }
}

/// Decode a local JSON message without surfacing its contents in panic diagnostics.
fn read_local_rpc_frame(websocket: &mut WebSocket<TcpStream>) -> Option<serde_json::Value> {
    loop {
        match websocket.read() {
            Ok(Message::Text(text)) => return serde_json::from_str(text.trim()).ok(),
            Ok(Message::Ping(payload)) => {
                let _ = websocket.send(Message::Pong(payload));
            }
            Ok(Message::Close(_)) | Err(_) => return None,
            Ok(_) => {}
        }
    }
}

/// Reply with one JSON-RPC result; the WebSocket adapter adds the JSONL newline after receipt.
fn send_local_rpc_result(
    websocket: &mut WebSocket<TcpStream>,
    id: serde_json::Value,
    result: serde_json::Value,
) {
    websocket
        .send(Message::Text(
            serde_json::json!({ "jsonrpc": "2.0", "id": id, "result": result })
                .to_string()
                .into(),
        ))
        .expect("JSON-RPC result");
}

/// Verify the private epoch's exact lowercase 128-bit native token shape without printing its value.
fn valid_native_lifetime_id(value: &str) -> bool {
    value.len() == 39
        && value.starts_with("native_")
        && value[7..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

impl LocalNativeCloseServer {
    /// Join the isolated RPC fixture before removing only its own temporary directory.
    fn finish(mut self) {
        self.server
            .take()
            .expect("fixture server")
            .join()
            .expect("fixture server thread");
        fs::remove_dir_all(&self.config.run_dir).expect("fixture directory cleanup");
    }

    /// Wait for an intentional server-side TCP close before attempting a close-only reconnect.
    fn wait_for_first_disconnect(&self) {
        self.first_disconnect
            .recv_timeout(Duration::from_secs(2))
            .expect("fixture disconnected first client");
    }

    /// Report whether all websocket registrations used exactly the same process and lifetime token.
    fn registrations_share_lifetime(&self, expected: usize) -> bool {
        let Ok(registrations) = self.registrations.lock() else {
            return false;
        };
        registrations.len() == expected
            && registrations
                .iter()
                .all(|(process_id, _)| *process_id == u64::from(std::process::id()))
            && registrations
                .iter()
                .all(|(_, lifetime_id)| lifetime_id == &registrations[0].1)
    }
}

/// A dead server must leave cleanup unconfirmed without invoking connect-or-start or rotating identity.
#[test]
fn native_close_reconnect_does_not_start_an_exited_server() {
    let daemon =
        local_native_close_server("close-exited", NativeCloseScenario::ExitAfterFirstClose);
    let identity = NativeClientIdentity::new_current_process().expect("Native identity");
    let mut client = SharedAppServerClient::connect_existing(&daemon.config, 1, &identity)
        .expect("initial existing server");
    let result =
        client.close_native_client_until(&daemon.config, Instant::now() + Duration::from_secs(3));

    assert!(matches!(result, Err(AppServerProcessError::NotReady)));
    assert_eq!(daemon.accepted_connections.load(Ordering::Acquire), 1);
    assert!(daemon.registrations_share_lifetime(1));
    client
        .disconnect_until(Instant::now() + Duration::from_secs(2))
        .expect("close old local connection");
    daemon.finish();
}

/// A dropped websocket retains the same owner identity through a connect-existing-only close retry.
#[test]
fn native_close_reconnects_after_network_disconnect() {
    let daemon =
        local_native_close_server("close-network", NativeCloseScenario::DisconnectBeforeClose);
    let identity = NativeClientIdentity::new_current_process().expect("Native identity");
    let mut client = SharedAppServerClient::connect_existing(&daemon.config, 1, &identity)
        .expect("initial existing server");
    daemon.wait_for_first_disconnect();

    assert!(
        client
            .close_native_client_until(&daemon.config, Instant::now() + Duration::from_secs(4))
            .expect("close retry ACK")
    );
    assert_eq!(daemon.accepted_connections.load(Ordering::Acquire), 2);
    assert!(daemon.registrations_share_lifetime(2));
    client
        .disconnect_until(Instant::now() + Duration::from_secs(2))
        .expect("disconnect original and close-only retry sessions");
    daemon.finish();
}

/// A lost ACK is retried idempotently over the original Native identity, then all sockets remain under one cleanup owner.
#[test]
fn native_close_retries_lost_ack_with_same_identity() {
    let daemon =
        local_native_close_server("close-lost-ack", NativeCloseScenario::LoseFirstCloseAck);
    let identity = NativeClientIdentity::new_current_process().expect("Native identity");
    let mut client = SharedAppServerClient::connect_existing(&daemon.config, 1, &identity)
        .expect("initial existing server");

    assert!(
        client
            .close_native_client_until(&daemon.config, Instant::now() + Duration::from_secs(4))
            .expect("idempotent close ACK")
    );
    assert_eq!(daemon.accepted_connections.load(Ordering::Acquire), 2);
    assert!(daemon.registrations_share_lifetime(2));
    client
        .disconnect_until(Instant::now() + Duration::from_secs(2))
        .expect("disconnect original and close-only retry sessions");
    daemon.finish();
}

/// A live old session ACKs directly; no second connection or identity registration is created.
#[test]
fn native_close_uses_live_session_without_reconnecting() {
    let daemon = local_native_close_server(
        "close-live",
        NativeCloseScenario::AcknowledgeOnFirstConnection,
    );
    let identity = NativeClientIdentity::new_current_process().expect("Native identity");
    let mut client = SharedAppServerClient::connect_existing(&daemon.config, 1, &identity)
        .expect("initial existing server");

    assert!(
        client
            .close_native_client_until(&daemon.config, Instant::now() + Duration::from_secs(3))
            .expect("first-session close ACK")
    );
    assert_eq!(daemon.accepted_connections.load(Ordering::Acquire), 1);
    assert!(daemon.registrations_share_lifetime(1));
    client
        .disconnect_until(Instant::now() + Duration::from_secs(2))
        .expect("disconnect original session");
    daemon.finish();
}

/// Stop 回执和端点删除可能先于 Java 进程释放 Windows 文件句柄；
/// 只在本测试独立目录内等待完整关闭预算，超时仍暴露真实资源泄漏。
fn remove_daemon_fixture_after_exit(root: &std::path::Path) {
    let temp = std::env::temp_dir()
        .canonicalize()
        .expect("canonical temp root");
    let actual = root.canonicalize().expect("canonical isolated daemon root");
    assert_eq!(actual.parent(), Some(temp.as_path()));
    assert!(
        actual
            .file_name()
            .expect("fixture name")
            .to_string_lossy()
            .starts_with("ja-shared-connection-java-daemon-")
    );
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        match fs::remove_dir_all(&actual) {
            Ok(()) => return,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return,
            Err(error)
                if matches!(error.raw_os_error(), Some(5 | 32)) && Instant::now() < deadline =>
            {
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(error) => panic!("isolated daemon cleanup: {error}"),
        }
    }
}

/// 可连接隔离的已运行 Java 测试实例，以验证当前 Windows DACL、认证、
/// 环境注册以及断开后其它客户端仍可继续；该用例不会停止外部测试服务。
#[test]
#[ignore = "requires JA_TEST_JAVA and JA_WEBSOCKET_EXISTING_ROOT"]
fn existing_java_daemon_accepts_multiple_rust_clients() {
    let java = std::env::var_os("JA_TEST_JAVA").expect("JA_TEST_JAVA");
    let root = std::path::PathBuf::from(
        std::env::var_os("JA_WEBSOCKET_EXISTING_ROOT").expect("JA_WEBSOCKET_EXISTING_ROOT"),
    );
    let config = SidecarConfig::with_shared_directories(
        java,
        root.join("home"),
        root.join("data"),
        root.join("run"),
        root.join("logs"),
    )
    .expect("existing daemon directories");
    let first_identity = NativeClientIdentity::new_current_process().expect("first identity");
    let second_identity = NativeClientIdentity::new_current_process().expect("second identity");
    let mut first = SharedAppServerClient::connect_existing(&config, 10, &first_identity)
        .expect("first Rust client");
    let mut second = SharedAppServerClient::connect_existing(&config, 11, &second_identity)
        .expect("second Rust client");
    let identity = first.server_instance_id().to_owned();
    assert_eq!(second.server_instance_id(), identity);
    first
        .disconnect_until(Instant::now() + Duration::from_secs(3))
        .expect("first disconnect");
    let health = second
        .request(
            "runtime/health",
            serde_json::json!({}),
            Duration::from_secs(5),
        )
        .expect("second health");
    assert_eq!(
        health
            .result()
            .value()
            .and_then(|result| result.get("status"))
            .and_then(serde_json::Value::as_str),
        Some("ready")
    );
    let third = SharedAppServerClient::connect_existing(&config, 12, &first_identity)
        .expect("third Rust client reconnect");
    assert_eq!(third.server_instance_id(), identity);
    let _ = second.disconnect_until(Instant::now() + Duration::from_secs(3));
}

/// 每个测试有独立目录，避免端点与用户会话、其它并发测试相互污染。
fn temporary_root(label: &str) -> std::path::PathBuf {
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time")
        .as_nanos();
    let root = std::env::temp_dir().join(format!(
        "ja-shared-connection-{label}-{}-{nonce}",
        std::process::id()
    ));
    fs::create_dir_all(&root).expect("fixture directory");
    root
}

/// 用当前测试可执行文件作为稳定 executable；这些测试只连接，不执行它。
fn fixture_config(root: &std::path::Path) -> SidecarConfig {
    SidecarConfig::with_shared_directories(
        std::env::current_exe().expect("test executable"),
        root,
        root,
        root,
        root,
    )
    .expect("shared directories")
}

/// 与 Java 端点精确同形，并在 Unix 上显式应用 Java 生产权限。
fn write_endpoint(root: &std::path::Path, host: &str, port: u16, token: String) {
    let path = root.join("app-server.endpoint.json");
    let value = serde_json::json!({
        "protocolMajor": 2,
        "protocolMinor": 0,
        "host": host,
        "port": port,
        "serverInstanceId": "srv_fixture",
        "runtimeGeneration": 1,
        "token": token
    });
    fs::write(&path, serde_json::to_vec(&value).expect("endpoint JSON")).expect("write endpoint");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o600)).expect("private endpoint");
    }
    #[cfg(windows)]
    protect_endpoint_windows_acl(&path).expect("private endpoint ACL");
}

/// Windows endpoint fixtures must satisfy the same owner-only DACL that production clients enforce.
#[cfg(windows)]
fn protect_endpoint_windows_acl(path: &std::path::Path) -> std::io::Result<()> {
    use std::ffi::c_void;
    use std::os::windows::fs::OpenOptionsExt;
    use std::os::windows::io::AsRawHandle;

    #[link(name = "advapi32")]
    unsafe extern "system" {
        fn OpenProcessToken(process: *mut c_void, access: u32, token: *mut *mut c_void) -> i32;
        fn GetTokenInformation(
            token: *mut c_void,
            class: u32,
            information: *mut c_void,
            length: u32,
            returned: *mut u32,
        ) -> i32;
        fn GetLengthSid(sid: *mut c_void) -> u32;
        fn InitializeAcl(acl: *mut c_void, bytes: u32, revision: u32) -> i32;
        fn AddAccessAllowedAceEx(
            acl: *mut c_void,
            revision: u32,
            flags: u32,
            mask: u32,
            sid: *mut c_void,
        ) -> i32;
        fn SetSecurityInfo(
            handle: *mut c_void,
            object_type: u32,
            security_info: u32,
            owner: *mut c_void,
            group: *mut c_void,
            dacl: *mut c_void,
            sacl: *mut c_void,
        ) -> u32;
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetCurrentProcess() -> *mut c_void;
        fn CloseHandle(handle: *mut c_void) -> i32;
    }

    let mut token = std::ptr::null_mut();
    // SAFETY: the token is opened for query only and is closed on every exit below.
    if unsafe { OpenProcessToken(GetCurrentProcess(), 0x0008, &mut token) } == 0 || token.is_null()
    {
        return Err(std::io::Error::last_os_error());
    }
    let result = (|| {
        let mut required = 0_u32;
        // SAFETY: the zero-length call only requests the required TOKEN_USER buffer size.
        unsafe {
            GetTokenInformation(token, 1, std::ptr::null_mut(), 0, &mut required);
        }
        if required < std::mem::size_of::<*mut c_void>() as u32 || required > 16_384 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "current Windows token user is invalid",
            ));
        }
        let mut user = vec![0_u64; required.div_ceil(std::mem::size_of::<u64>() as u32) as usize];
        // SAFETY: user is aligned, has the exact required capacity, and token remains open.
        if unsafe {
            GetTokenInformation(token, 1, user.as_mut_ptr().cast(), required, &mut required)
        } == 0
        {
            return Err(std::io::Error::last_os_error());
        }
        // TOKEN_USER starts with a SID pointer; use an unaligned read to match Win32 layout safely.
        let sid = unsafe { std::ptr::read_unaligned(user.as_ptr().cast::<*mut c_void>()) };
        if sid.is_null() {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "current Windows user SID is invalid",
            ));
        }
        // SAFETY: null SID was rejected above and the backing TOKEN_USER buffer remains alive.
        let sid_bytes = unsafe { GetLengthSid(sid) };
        if sid_bytes == 0 || sid_bytes > 16_384 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "current Windows user SID is invalid",
            ));
        }
        let acl_bytes = 16_u32.checked_add(sid_bytes).ok_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::InvalidData, "endpoint ACL is too large")
        })?;
        let mut acl = vec![0_u32; acl_bytes.div_ceil(std::mem::size_of::<u32>() as u32) as usize];
        // SAFETY: ACL storage is 4-byte aligned and sized for one ACCESS_ALLOWED_ACE plus the SID.
        if unsafe { InitializeAcl(acl.as_mut_ptr().cast(), acl_bytes, 2) } == 0
            || unsafe { AddAccessAllowedAceEx(acl.as_mut_ptr().cast(), 2, 0, 0x001F_01FF, sid) }
                == 0
        {
            return Err(std::io::Error::last_os_error());
        }
        let file = std::fs::OpenOptions::new()
            .access_mode(0x0002_0000 | 0x0004_0000)
            .open(path)?;
        // Owner is inherited from the test's private temp root; replace inherited ACEs with one
        // non-inheriting current-user ACE, matching WebSocketDaemon.protectCurrentUser.
        // SAFETY: the handle, SID, and ACL all remain live for the synchronous Win32 call.
        let status = unsafe {
            SetSecurityInfo(
                file.as_raw_handle(),
                1,
                0x0000_0004 | 0x8000_0000,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                acl.as_mut_ptr().cast(),
                std::ptr::null_mut(),
            )
        };
        if status != 0 {
            return Err(std::io::Error::from_raw_os_error(status as i32));
        }
        Ok(())
    })();
    // SAFETY: token was successfully opened above and is owned by this function.
    unsafe { CloseHandle(token) };
    result
}
