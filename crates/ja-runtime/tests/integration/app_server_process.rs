// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! `app_server_process` 公共 façade 合同；测试只使用 crate 外部可见 API。

use ja_runtime::app_server_process::{
    AppServerProcessError, LifecycleState, SidecarConfig, SidecarSupervisor,
};
#[cfg(windows)]
use serde_json::json;
use std::ffi::OsString;
use std::fs;
#[cfg(windows)]
use std::time::{Duration, Instant};
use std::time::{SystemTime, UNIX_EPOCH};

/// 真实 filesystem 配置校验属于公开进程边界；fixture 使用独立临时目录并在断言后
/// 清理，证明 secret 环境无法越过 `env_clear` allowlist，也不依赖私有 config 模块。
#[test]
fn facade_rejects_secret_sidecar_environment() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-public-contract-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("系统时间必须晚于 Unix epoch")
            .as_nanos()
    ));
    fs::create_dir_all(&root).unwrap();
    let executable = std::env::current_exe().unwrap();
    let mut config = SidecarConfig::with_directories(executable, &root, &root, &root, &root);
    config.env.insert(
        OsString::from("OPENAI_API_KEY"),
        OsString::from("fixture-secret"),
    );
    assert_eq!(config.validate(), Err(AppServerProcessError::InvalidConfig));
    fs::remove_dir_all(root).unwrap();
}

/// 在真实 Windows child 上只经过公共 façade 完成 start、握手、request 与 shutdown，
/// 证明进程行为已归属 crate integration test；fixture 不使用 Harness 或私有模块。
#[cfg(windows)]
#[test]
fn facade_owns_real_sidecar_process_lifecycle() {
    let Some(system_root) = std::env::var_os("SystemRoot") else {
        return;
    };
    let system_root = std::path::PathBuf::from(system_root);
    let powershell = system_root
        .join("System32")
        .join("WindowsPowerShell")
        .join("v1.0")
        .join("powershell.exe");
    if !powershell.is_file() {
        return;
    }
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-public-process-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("系统时间必须晚于 Unix epoch")
            .as_nanos()
    ));
    fs::create_dir_all(&root).unwrap();
    let script_path = root.join("public-fixture.ps1");
    let script = r#"
$ErrorActionPreference = 'Stop'
$initializeResult = '{"protocolMajor":2,"protocolMinor":0,"serverInstanceId":"srv_public_fixture","runtime":{"engine":"ja-kernel","engineVersion":"2.0.0"},"capabilities":{"methods":[],"events":[],"accessModes":["approval_required","full_access"]},"limits":{"maxFrameBytes":4194304,"maxInFlightRequests":64,"maxInboundQueueFrames":256,"maxControlOutboundQueueFrames":64,"maxDataOutboundQueueFrames":1024,"maxConcurrentTurns":8,"maxAdmittedTurns":64,"maxThreadQueuedTurns":8,"maxSnapshotPageItems":200,"maxToolBatchConcurrency":8}}'
function Write-Lf([string]$Text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text + [char]10)
    $stdout = [Console]::OpenStandardOutput()
    $stdout.Write($bytes, 0, $bytes.Length)
    $stdout.Flush()
}
while (($line = [Console]::In.ReadLine()) -ne $null) {
    $request = $line | ConvertFrom-Json
    if ($request.method -eq 'runtime/initialize') {
        Write-Lf ('{"jsonrpc":"2.0","id":"' + $request.id + '","result":' + $initializeResult + '}')
        continue
    }
    if ($request.method -eq 'runtime/initialized') {
        $token = $request.params.readyToken
        Write-Lf ('{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_public_fixture","eventId":"evt_public_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready","readyToken":"' + $token + '"}}')
        continue
    }
    if ($request.method -eq 'runtime/health') {
        Write-Lf ('{"jsonrpc":"2.0","id":"' + $request.id + '","result":{"status":"ready"}}')
        continue
    }
    if ($request.method -eq 'runtime/shutdown') {
        Write-Lf ('{"jsonrpc":"2.0","id":"' + $request.id + '","result":{}}')
        break
    }
}
"#;
    fs::write(&script_path, script).unwrap();
    let mut config = SidecarConfig::with_directories(&powershell, &root, &root, &root, &root);
    config.args = vec![
        OsString::from("-NoProfile"),
        OsString::from("-NonInteractive"),
        OsString::from("-File"),
        script_path.into_os_string(),
    ];
    config.ready_timeout = Duration::from_secs(5);
    config.shutdown_timeout = Duration::from_secs(2);
    let mut supervisor = SidecarSupervisor::new(config).unwrap();
    supervisor.start_with_session_hook(None).unwrap();
    assert_eq!(supervisor.state(), LifecycleState::Ready);
    let response = supervisor
        .request("runtime/health", json!({}), Duration::from_secs(2))
        .unwrap();
    assert_eq!(
        response
            .result()
            .value()
            .and_then(|value| value.get("status")),
        Some(&json!("ready"))
    );
    supervisor
        .shutdown_until(Instant::now() + Duration::from_secs(2))
        .unwrap();
    drop(supervisor);
    fs::remove_dir_all(root).unwrap();
}
