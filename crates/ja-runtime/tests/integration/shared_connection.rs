// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 共享连接的文件与网络失效边界；不启动真实 App Server，不影响用户现有实例。

use ja_runtime::app_server_process::{AppServerProcessError, SharedAppServerClient, SidecarConfig};
use std::ffi::OsString;
use std::fs;
#[cfg(unix)]
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener};
use std::time::{Duration, Instant};
use std::time::{SystemTime, UNIX_EPOCH};

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
    write_endpoint(&root, port, "a".repeat(64));
    let result = SharedAppServerClient::connect_existing(&config, 1);
    assert!(matches!(result, Err(AppServerProcessError::NotReady)));
    fs::remove_dir_all(root).expect("fixture cleanup");
}

/// 无效 token 不能被当成可恢复 stale 端点触发另一个 daemon 启动。
#[test]
fn malformed_endpoint_fails_closed() {
    let root = temporary_root("malformed");
    let config = fixture_config(&root);
    write_endpoint(&root, 42_117, "invalid".to_owned());
    let result = SharedAppServerClient::connect_existing(&config, 1);
    assert!(matches!(result, Err(AppServerProcessError::ProtocolFault)));
    fs::remove_dir_all(root).expect("fixture cleanup");
}

/// 仅在显式提供隔离 Java 25 fat JAR 时运行，验证 Rust 真实启动、认证、
/// 双客户端断连和重连均不改变同一个 Java owner 的身份。
#[test]
#[ignore = "requires JA_TEST_JAVA and JA_TCP_TEST_JAR; starts an isolated Java daemon"]
fn windows_java_daemon_survives_client_disconnect() {
    let java = std::env::var_os("JA_TEST_JAVA").expect("JA_TEST_JAVA");
    let jar = std::env::var_os("JA_TCP_TEST_JAR").expect("JA_TCP_TEST_JAR");
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
    let mut first = SharedAppServerClient::connect_or_start(config.clone(), 42)
        .expect("first TCP client starts daemon");
    let mut second = SharedAppServerClient::connect_existing(&config, 43)
        .expect("second TCP client joins same daemon");
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
    let mut third =
        SharedAppServerClient::connect_existing(&config, 44).expect("third TCP client reconnects");
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

/// Stop 回执和端点删除可能先于 Windows 释放 SQLite 句柄；只重试本测试创建的独立临时目录。
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
    for _ in 0..100 {
        match fs::remove_dir_all(&actual) {
            Ok(()) => return,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return,
            Err(error) if matches!(error.raw_os_error(), Some(5 | 32)) => {
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(error) => panic!("isolated daemon cleanup: {error}"),
        }
    }
    fs::remove_dir_all(&actual).expect("isolated daemon cleanup after shutdown");
}

/// 可连接隔离的已运行 Java 测试实例，以验证当前 Windows DACL、认证、
/// 环境注册以及断开后其它客户端仍可继续；该用例不会停止外部测试服务。
#[test]
#[ignore = "requires JA_TEST_JAVA and JA_TCP_EXISTING_ROOT"]
fn existing_java_daemon_accepts_multiple_rust_clients() {
    let java = std::env::var_os("JA_TEST_JAVA").expect("JA_TEST_JAVA");
    let root = std::path::PathBuf::from(
        std::env::var_os("JA_TCP_EXISTING_ROOT").expect("JA_TCP_EXISTING_ROOT"),
    );
    let config = SidecarConfig::with_shared_directories(
        java,
        root.join("home"),
        root.join("data"),
        root.join("run"),
        root.join("logs"),
    )
    .expect("existing daemon directories");
    let mut first =
        SharedAppServerClient::connect_existing(&config, 10).expect("first Rust client");
    let mut second =
        SharedAppServerClient::connect_existing(&config, 11).expect("second Rust client");
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
    let third =
        SharedAppServerClient::connect_existing(&config, 12).expect("third Rust client reconnect");
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
fn write_endpoint(root: &std::path::Path, port: u16, token: String) {
    let path = root.join("app-server.endpoint.json");
    let value = serde_json::json!({
        "protocolMajor": 1,
        "protocolMinor": 0,
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
}
