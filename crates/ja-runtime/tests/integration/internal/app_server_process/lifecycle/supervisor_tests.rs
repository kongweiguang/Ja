// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::*;
use crate::app_server_process;
use crate::app_server_process::lifecycle::LifecycleState;
use crate::app_server_process::process::SidecarConfig;
use serde_json::json;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::unit_support_tests::poison_mutex;

/// 仅在私有单元测试中观察当前 writer 的 join 结果，避免为测试断言扩大生产 façade；
/// 复用 Session 的绝对 deadline 能确保测试与真实 shutdown 使用相同的有界等待语义。
fn join_current_writer_until(
    supervisor: &SidecarSupervisor,
    deadline: Instant,
) -> Result<(), AppServerProcessError> {
    supervisor
        .session
        .as_ref()
        .ok_or(AppServerProcessError::NotReady)?
        .close_until(deadline)
}

/// 测试显式给出全部目录角色，确保 fixture 不依赖已删除的单目录兼容构造器。
fn sidecar_config(executable: &Path, run_dir: &Path) -> SidecarConfig {
    SidecarConfig::with_directories(executable, run_dir, run_dir, run_dir, run_dir)
}

/// 创建不启动 child 的合法 supervisor，专门验证 admission/signal 锁异常；使用当前
/// test binary 只满足 canonical executable 校验，不进入 sidecar 握手。
fn idle_supervisor_fixture() -> SidecarSupervisor {
    let executable = std::env::current_exe().expect("current test executable");
    let run_dir = executable
        .parent()
        .expect("test executable parent")
        .to_path_buf();
    SidecarSupervisor::new(sidecar_config(&executable, &run_dir)).expect("idle supervisor fixture")
}

/// stopping gate 中毒后 client 准入必须返回稳定 Faulted 并终结 lifecycle，不能从
/// poisoned bool 推断仍可接收请求。
#[test]
fn poisoned_stopping_gate_faults_supervisor() {
    let mut supervisor = idle_supervisor_fixture();
    poison_mutex(&supervisor.stopping);

    assert!(matches!(
        supervisor.request("workspace/open-general", json!({}), Duration::from_secs(1)),
        Err(AppServerProcessError::Faulted)
    ));
    assert_eq!(supervisor.lifecycle.state(), LifecycleState::Faulted);
}

/// terminal signal 队列中毒后顺序与 generation 归属不可再证明；同步状态必须直接
/// 进入 Faulted，而不是 drain poisoned queue 继续转换生命周期。
#[test]
fn poisoned_terminal_signal_queue_faults_supervisor() {
    let mut supervisor = idle_supervisor_fixture();
    poison_mutex(&supervisor.terminal_signals);

    assert_eq!(supervisor.state(), LifecycleState::Faulted);
}

/// 构造最小合法 v2 initialize result，使 schema 测试不依赖真实 child process。
fn valid_initialize_result() -> Value {
    serde_json::json!({
        "protocolMajor": 2,
        "protocolMinor": 0,
        "serverInstanceId": "srv_fixture",
        "runtime": {
            "engine": "ja-kernel",
            "engineVersion": "2.0.0"
        },
        "capabilities": {
            "methods": [],
            "events": [],
            "accessModes": ["approval_required", "full_access"]
        },
        "limits": Limits::default().to_value()
    })
}

/// 只接受冻结的六字段 initialize result，并绑定其中的 server identity。
#[test]
fn initialize_result_accepts_frozen_shape() {
    let instance = validate_initialize_result(&valid_initialize_result(), &Limits::default())
        .expect("frozen initialize result");
    assert_eq!(instance, "srv_fixture");
}

/// 在信任 capability 或 limit 前拒绝缺失的顶层字段，避免部分握手进入生命周期。
#[test]
fn initialize_result_rejects_missing_field() {
    let mut result = valid_initialize_result();
    result
        .as_object_mut()
        .expect("object fixture")
        .remove("limits");
    assert!(matches!(
        validate_initialize_result(&result, &Limits::default()),
        Err(AppServerProcessError::ProtocolFault)
    ));
}

/// 拒绝已删除的 serverVersion 字段，防止实现元数据扩张协议合同。
#[test]
fn initialize_result_rejects_extra_field() {
    let mut result = valid_initialize_result();
    result.as_object_mut().expect("object fixture").insert(
        "serverVersion".to_owned(),
        Value::String("2.0.0".to_owned()),
    );
    assert!(matches!(
        validate_initialize_result(&result, &Limits::default()),
        Err(AppServerProcessError::ProtocolFault)
    ));
}

/// 等待真实 child fixture 写出文件而不依赖固定 sleep，避免慢机器上的竞态猜测。
#[cfg(windows)]
fn wait_for_path(path: &std::path::Path, timeout: Duration) -> bool {
    let deadline = Instant::now()
        .checked_add(timeout)
        .expect("fixture deadline fits in Instant");
    while Instant::now() < deadline {
        if path.is_file() {
            return true;
        }
        thread::yield_now();
    }
    false
}

/// 查询 PID 是否仍在 Windows 进程表中，用于验证 Job tree 的 descendant 收口。
#[cfg(windows)]
fn process_exists(pid: u32, system_root: &std::path::Path) -> bool {
    let tasklist = system_root.join("System32").join("tasklist.exe");
    let Ok(output) = Command::new(tasklist)
        .args(["/FI", &format!("PID eq {pid}"), "/FO", "CSV", "/NH"])
        .output()
    else {
        return false;
    };
    String::from_utf8_lossy(&output.stdout).contains(&format!("\"{pid}\""))
}

/// 通过真实 PowerShell child 回归每种 token 握手错误都立即返回 stable error。
#[test]
#[cfg(windows)]
fn real_child_invalid_ready_tokens_return_handshake_failed() {
    let system_root = PathBuf::from(std::env::var_os("SystemRoot").expect("SystemRoot"));
    let powershell = system_root
        .join("System32")
        .join("WindowsPowerShell")
        .join("v1.0")
        .join("powershell.exe");
    if !powershell.is_file() {
        return;
    }
    for mode in ["missing", "malformed", "wrong", "old"] {
        let fixture_dir = TempFixtureDir::new();
        let run_dir = fixture_dir.path.clone();
        let script = r#"
param([string]$Mode)
$ErrorActionPreference = 'Stop'
    $init = '{"jsonrpc":"2.0","id":"c:rpc-1","result":{"protocolMajor":2,"protocolMinor":0,"serverInstanceId":"srv_fixture","runtime":{"engine":"ja-kernel","engineVersion":"2.0.0"},"capabilities":{"methods":[],"events":[],"accessModes":["approval_required","full_access"]},"limits":{"maxFrameBytes":4194304,"maxInFlightRequests":64,"maxInboundQueueFrames":256,"maxControlOutboundQueueFrames":64,"maxDataOutboundQueueFrames":1024,"maxConcurrentTurns":8,"maxAdmittedTurns":64,"maxThreadQueuedTurns":8,"maxSnapshotPageItems":200,"maxToolBatchConcurrency":8}}}'
    $businessRequests = 0
function Write-Lf([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text + [char]10)
    $stdout = [Console]::OpenStandardOutput()
    $stdout.Write($bytes, 0, $bytes.Length)
    $stdout.Flush()
}
while (($line = [Console]::In.ReadLine()) -ne $null) {
    if ($line -match '"method":"runtime/initialize"') {
        Write-Lf $init
        continue
    }
    if ($line -match '"method":"runtime/initialized"') {
        switch ($Mode) {
            'missing' {
                $ready = '{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_fixture","eventId":"evt_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready"}}'
            }
            'malformed' {
                $ready = '{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_fixture","eventId":"evt_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready","readyToken":"0123456789ABCDEF0123456789ABCDEF"}}'
            }
            'wrong' {
                $ready = '{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_fixture","eventId":"evt_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready","readyToken":"00000000000000000000000000000000"}}'
            }
            default {
                $ready = '{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_fixture","eventId":"evt_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready","readyToken":"fedcba9876543210fedcba9876543210"}}'
            }
        }
        Write-Lf $ready
        Start-Sleep -Seconds 30
        break
    }
}
"#;
        let script_path = run_dir.join("fixture.ps1");
        fs::write(&script_path, script).unwrap();
        let mut config = sidecar_config(&powershell, &run_dir);
        config.args = vec![
            OsString::from("-NoProfile"),
            OsString::from("-NonInteractive"),
            OsString::from("-File"),
            script_path.clone().into_os_string(),
            OsString::from("-Mode"),
            OsString::from(mode),
        ];
        config.env.insert(
            OsString::from("SystemRoot"),
            system_root.clone().into_os_string(),
        );
        config.env.insert(
            OsString::from("WINDIR"),
            system_root.clone().into_os_string(),
        );
        config.env.insert(
            OsString::from("SystemDrive"),
            system_root
                .components()
                .next()
                .map(|component| component.as_os_str().to_owned())
                .unwrap_or_else(|| OsString::from("C:")),
        );
        config.ready_timeout = Duration::from_secs(5);
        config.shutdown_timeout = Duration::from_secs(2);
        let mut supervisor = SidecarSupervisor::new(config).unwrap();
        assert_eq!(
            supervisor.start_with_session_hook(None),
            Err(app_server_process::AppServerProcessError::HandshakeFailed),
            "mode {mode} must not degrade to a ready timeout"
        );
        assert_eq!(supervisor.state(), LifecycleState::Faulted);
        supervisor
            .shutdown_until(Instant::now() + Duration::from_secs(1))
            .unwrap();
    }
}

/// 真实 child 的首次超时清理必须保留 owner；第二次 shutdown 在同一
/// supervisor 上重试并确认 tree reap 后才允许 Exited，防止失败即假绿。
#[test]
#[cfg(windows)]
fn real_child_shutdown_retries_retained_owner_after_reap_timeout() {
    let system_root = PathBuf::from(std::env::var_os("SystemRoot").expect("SystemRoot"));
    let powershell = system_root
        .join("System32")
        .join("WindowsPowerShell")
        .join("v1.0")
        .join("powershell.exe");
    if !powershell.is_file() {
        return;
    }
    let fixture_dir = TempFixtureDir::new();
    let script_path = fixture_dir.path.join("retry-shutdown.ps1");
    let script = r#"
$ErrorActionPreference = 'Stop'
    $init = '{"jsonrpc":"2.0","id":"c:rpc-1","result":{"protocolMajor":2,"protocolMinor":0,"serverInstanceId":"srv_fixture","runtime":{"engine":"ja-kernel","engineVersion":"2.0.0"},"capabilities":{"methods":[],"events":[],"accessModes":["approval_required","full_access"]},"limits":{"maxFrameBytes":4194304,"maxInFlightRequests":64,"maxInboundQueueFrames":256,"maxControlOutboundQueueFrames":64,"maxDataOutboundQueueFrames":1024,"maxConcurrentTurns":8,"maxAdmittedTurns":64,"maxThreadQueuedTurns":8,"maxSnapshotPageItems":200,"maxToolBatchConcurrency":8}}}'
function Write-Lf([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text + [char]10)
    $stdout = [Console]::OpenStandardOutput()
    $stdout.Write($bytes, 0, $bytes.Length)
    $stdout.Flush()
}
while (($line = [Console]::In.ReadLine()) -ne $null) {
    if ($line -match '"method":"runtime/initialize"') {
        Write-Lf $init
        continue
    }
    if ($line -match '"method":"runtime/initialized"') {
        $token = ($line | ConvertFrom-Json).params.readyToken
        Write-Lf ('{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_fixture","eventId":"evt_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready","readyToken":"' + $token + '"}}')
        Start-Sleep -Seconds 30
        break
    }
}
"#;
    fs::write(&script_path, script).expect("retry fixture script");
    let mut config = sidecar_config(&powershell, &fixture_dir.path);
    config.args = vec![
        OsString::from("-NoProfile"),
        OsString::from("-NonInteractive"),
        OsString::from("-File"),
        script_path.into_os_string(),
    ];
    config.env.insert(
        OsString::from("SystemRoot"),
        system_root.clone().into_os_string(),
    );
    config.env.insert(
        OsString::from("WINDIR"),
        system_root.clone().into_os_string(),
    );
    config.env.insert(
        OsString::from("SystemDrive"),
        system_root
            .components()
            .next()
            .map(|component| component.as_os_str().to_owned())
            .unwrap_or_else(|| OsString::from("C:")),
    );
    config.ready_timeout = Duration::from_secs(5);
    config.shutdown_timeout = Duration::from_secs(2);
    let mut supervisor = SidecarSupervisor::new(config).expect("retry supervisor");
    supervisor
        .start_with_session_hook(None)
        .expect("retry fixture ready");
    let first = supervisor.shutdown_until(Instant::now());
    assert!(first.is_err(), "zero cleanup budget must retain the owner");
    assert_eq!(supervisor.state(), LifecycleState::Stopping);
    let retry_deadline = Instant::now() + Duration::from_secs(4);
    let second = supervisor.shutdown_until(Instant::now() + Duration::from_secs(3));
    assert!(
        second.is_ok(),
        "retained owner must be retryable: {second:?}"
    );
    assert!(Instant::now() < retry_deadline);
    assert_eq!(supervisor.state(), LifecycleState::Exited);
}

#[cfg(windows)]
struct TempFixtureDir {
    path: PathBuf,
}

#[cfg(windows)]
impl TempFixtureDir {
    /// 创建带进程/时间熵的临时 fixture 根，避免并发测试复用或污染仓库 cwd。
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "ja-app-server-fixture-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).unwrap();
        Self { path }
    }
}

#[cfg(windows)]
impl Drop for TempFixtureDir {
    /// 无论断言或 child 握手何处失败，都回收 fixture 文件与临时目录。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

/// 真实 child 并发请求、握手和 Job cleanup 必须形成单一闭环，防止测试 seam 掩盖进程树泄漏。
#[test]
#[cfg(windows)]
fn real_child_handshake_concurrency_and_job_cleanup() {
    let system_root = PathBuf::from(std::env::var_os("SystemRoot").expect("SystemRoot"));
    let powershell = system_root
        .join("System32")
        .join("WindowsPowerShell")
        .join("v1.0")
        .join("powershell.exe");
    if !powershell.is_file() {
        return;
    }
    let fixture_dir = TempFixtureDir::new();
    let run_dir = fixture_dir.path.clone();
    let pid_path = run_dir.join("grandchild.pid");
    assert!(pid_path.is_absolute());
    let script = r#"
param([string]$PidPath)
$ErrorActionPreference = 'Stop'
    $init = '{"jsonrpc":"2.0","id":"c:rpc-1","result":{"protocolMajor":2,"protocolMinor":0,"serverInstanceId":"srv_fixture","runtime":{"engine":"ja-kernel","engineVersion":"2.0.0"},"capabilities":{"methods":[],"events":[],"accessModes":["approval_required","full_access"]},"limits":{"maxFrameBytes":4194304,"maxInFlightRequests":64,"maxInboundQueueFrames":256,"maxControlOutboundQueueFrames":64,"maxDataOutboundQueueFrames":1024,"maxConcurrentTurns":8,"maxAdmittedTurns":64,"maxThreadQueuedTurns":8,"maxSnapshotPageItems":200,"maxToolBatchConcurrency":8}}}'
function Write-Lf([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text + "`n")
    $stdout = [Console]::OpenStandardOutput()
    $stdout.Write($bytes, 0, $bytes.Length)
    $stdout.Flush()
}
while (($line = [Console]::In.ReadLine()) -ne $null) {
    Add-Content -Path ($PidPath + '.log') -Value $line
    if ($line -match '"method":"runtime/initialize"') {
        Write-Lf $init
        $grandchild = Start-Process -FilePath ($env:SystemRoot + '\System32\WindowsPowerShell\v1.0\powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 30') -WindowStyle Hidden -PassThru
        [IO.File]::WriteAllText($PidPath, [string]$grandchild.Id)
        continue
    }
    if ($line -match '"method":"runtime/initialized"') {
        $token = ($line | ConvertFrom-Json).params.readyToken
        $ready = '{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_fixture","eventId":"evt_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready","readyToken":"' + $token + '"}}'
        Write-Lf $ready
        continue
    }
    if ($line -match '"method":"workspace/open-general"') {
        if ($line -match '"id":"(?<id>c:[^"]+)"') {
            Write-Lf ('{"jsonrpc":"2.0","id":"' + $Matches['id'] + '","result":{"ok":true}}')
        }
        $businessRequests += 1
        if ($businessRequests -ge 3) { break }
    }
}
"#;
    let script_path = run_dir.join("fixture.ps1");
    fs::write(&script_path, script).unwrap();
    let mut config = sidecar_config(&powershell, &run_dir);
    config.args = vec![
        OsString::from("-NoProfile"),
        OsString::from("-NonInteractive"),
        OsString::from("-File"),
        script_path.clone().into_os_string(),
        OsString::from("-PidPath"),
        pid_path.clone().into_os_string(),
    ];
    config.env.insert(
        OsString::from("SystemRoot"),
        system_root.clone().into_os_string(),
    );
    config.env.insert(
        OsString::from("WINDIR"),
        system_root.clone().into_os_string(),
    );
    config.env.insert(
        OsString::from("SystemDrive"),
        system_root
            .components()
            .next()
            .map(|component| component.as_os_str().to_owned())
            .unwrap_or_else(|| OsString::from("C:")),
    );
    config.ready_timeout = Duration::from_secs(5);
    config.shutdown_timeout = Duration::from_secs(2);
    let mut supervisor = SidecarSupervisor::new(config).unwrap();
    supervisor.start_with_session_hook(None).unwrap();
    assert_eq!(supervisor.state(), LifecycleState::Ready);
    assert!(
        supervisor
            .request("workspace/open-general", json!({}), Duration::from_secs(2))
            .is_ok()
    );
    assert!(
        supervisor
            .request("workspace/open-general", json!({}), Duration::from_secs(2))
            .is_ok()
    );

    assert!(wait_for_path(&pid_path, Duration::from_secs(2)));
    let grandchild_pid: u32 = fs::read_to_string(&pid_path).unwrap().parse().unwrap();
    assert!(process_exists(grandchild_pid, &system_root));
    assert!(
        supervisor
            .request("workspace/open-general", json!({}), Duration::from_secs(2))
            .is_ok()
    );
    let mut events = supervisor.take_event_pump().unwrap();
    let deadline = Instant::now()
        .checked_add(Duration::from_secs(2))
        .expect("fixture deadline fits in Instant");
    let mut saw_exit = false;
    while Instant::now() < deadline {
        if matches!(
            events.next_event(Duration::from_millis(50)),
            Some(SessionEvent::ProcessExited { .. } | SessionEvent::Eof)
        ) {
            saw_exit = true;
            break;
        }
    }
    assert!(saw_exit);
    let cleanup_deadline = Instant::now()
        .checked_add(Duration::from_secs(2))
        .expect("fixture cleanup deadline fits in Instant");
    while process_exists(grandchild_pid, &system_root) && Instant::now() < cleanup_deadline {
        thread::yield_now();
    }
    assert!(!process_exists(grandchild_pid, &system_root));
    supervisor
        .shutdown_until(Instant::now() + Duration::from_secs(1))
        .unwrap();
    assert_eq!(
        supervisor.request("workspace/open-general", json!({}), Duration::from_secs(1)),
        Err(app_server_process::AppServerProcessError::ShuttingDown)
    );
}

/// 证明 stdout EOF 会在没有 event consumer 的情况下立即收口 sidecar Job。
#[test]
#[cfg(windows)]
fn real_child_stdout_eof_kills_tree_without_event_consumer() {
    let system_root = PathBuf::from(std::env::var_os("SystemRoot").expect("SystemRoot"));
    let powershell = system_root
        .join("System32")
        .join("WindowsPowerShell")
        .join("v1.0")
        .join("powershell.exe");
    if !powershell.is_file() {
        return;
    }
    let fixture_dir = TempFixtureDir::new();
    let run_dir = fixture_dir.path.clone();
    let pid_path = run_dir.join("sidecar.pid");
    let script = r#"
param([string]$PidPath)
$ErrorActionPreference = 'Stop'
    $init = '{"jsonrpc":"2.0","id":"c:rpc-1","result":{"protocolMajor":2,"protocolMinor":0,"serverInstanceId":"srv_fixture","runtime":{"engine":"ja-kernel","engineVersion":"2.0.0"},"capabilities":{"methods":[],"events":[],"accessModes":["approval_required","full_access"]},"limits":{"maxFrameBytes":4194304,"maxInFlightRequests":64,"maxInboundQueueFrames":256,"maxControlOutboundQueueFrames":64,"maxDataOutboundQueueFrames":1024,"maxConcurrentTurns":8,"maxAdmittedTurns":64,"maxThreadQueuedTurns":8,"maxSnapshotPageItems":200,"maxToolBatchConcurrency":8}}}'
function Write-Lf([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text + "`n")
    $stdout = [Console]::OpenStandardOutput()
    $stdout.Write($bytes, 0, $bytes.Length)
    $stdout.Flush()
}
while (($line = [Console]::In.ReadLine()) -ne $null) {
    if ($line -match '"method":"runtime/initialize"') {
        [IO.File]::WriteAllText($PidPath, [string]$PID)
        Write-Lf $init
        continue
    }
    if ($line -match '"method":"runtime/initialized"') {
        $token = ($line | ConvertFrom-Json).params.readyToken
        $ready = '{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_fixture","eventId":"evt_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready","readyToken":"' + $token + '"}}'
        Write-Lf $ready
        continue
    }
    if ($line -match '"method":"workspace/open-general"') {
        $stdout = [Console]::OpenStandardOutput()
        $stdout.SafeFileHandle.Close()
        $stdout.Dispose()
        Start-Sleep -Seconds 30
        continue
    }
}
"#;
    let script_path = run_dir.join("fixture.ps1");
    fs::write(&script_path, script).unwrap();
    let mut config = sidecar_config(&powershell, &run_dir);
    config.args = vec![
        OsString::from("-NoProfile"),
        OsString::from("-NonInteractive"),
        OsString::from("-File"),
        script_path.clone().into_os_string(),
        OsString::from("-PidPath"),
        pid_path.clone().into_os_string(),
    ];
    config.env.insert(
        OsString::from("SystemRoot"),
        system_root.clone().into_os_string(),
    );
    config.env.insert(
        OsString::from("WINDIR"),
        system_root.clone().into_os_string(),
    );
    config.env.insert(
        OsString::from("SystemDrive"),
        system_root
            .components()
            .next()
            .map(|component| component.as_os_str().to_owned())
            .unwrap_or_else(|| OsString::from("C:")),
    );
    config.ready_timeout = Duration::from_secs(5);
    config.shutdown_timeout = Duration::from_secs(2);
    let mut supervisor = SidecarSupervisor::new(config).unwrap();
    supervisor.start_with_session_hook(None).unwrap();
    assert!(wait_for_path(&pid_path, Duration::from_secs(2)));
    let child_pid: u32 = fs::read_to_string(&pid_path).unwrap().parse().unwrap();
    assert!(process_exists(child_pid, &system_root));

    // 不消费 supervisor event；即使 UI idle，EOF 本身也必须调用 terminal callback 并
    // 关闭完整 Job。
    let result = supervisor.request(
        "workspace/open-general",
        json!({}),
        Duration::from_secs(2),
    );
    assert_eq!(
        result,
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    assert_eq!(supervisor.state(), LifecycleState::Backoff);
    let deadline = Instant::now()
        .checked_add(Duration::from_secs(2))
        .expect("fixture deadline fits in Instant");
    while process_exists(child_pid, &system_root) && Instant::now() < deadline {
        thread::yield_now();
    }
    assert!(!process_exists(child_pid, &system_root));
    supervisor
        .shutdown_until(Instant::now() + Duration::from_secs(1))
        .unwrap();
}

/// 真实 ChildStdin 不读数据时，watchdog 必须经 process-tree callback
/// 终止 leader/descendant，并在同一 deadline 内 join writer、释放 pending。
#[test]
#[cfg(windows)]
fn real_child_blocked_stdin_watchdog_joins_and_reaps_tree() {
    let system_root = PathBuf::from(std::env::var_os("SystemRoot").expect("SystemRoot"));
    let powershell = system_root
        .join("System32")
        .join("WindowsPowerShell")
        .join("v1.0")
        .join("powershell.exe");
    if !powershell.is_file() {
        return;
    }
    let fixture_dir = TempFixtureDir::new();
    let run_dir = fixture_dir.path.clone();
    let pid_path = run_dir.join("grandchild.pid");
    let script = r#"
param([string]$PidPath)
$ErrorActionPreference = 'Stop'
    $init = '{"jsonrpc":"2.0","id":"c:rpc-1","result":{"protocolMajor":2,"protocolMinor":0,"serverInstanceId":"srv_fixture","runtime":{"engine":"ja-kernel","engineVersion":"2.0.0"},"capabilities":{"methods":[],"events":[],"accessModes":["approval_required","full_access"]},"limits":{"maxFrameBytes":4194304,"maxInFlightRequests":64,"maxInboundQueueFrames":256,"maxControlOutboundQueueFrames":64,"maxDataOutboundQueueFrames":1024,"maxConcurrentTurns":8,"maxAdmittedTurns":64,"maxThreadQueuedTurns":8,"maxSnapshotPageItems":200,"maxToolBatchConcurrency":8}}}'
function Write-Lf([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text + [char]10)
    $stdout = [Console]::OpenStandardOutput()
    $stdout.Write($bytes, 0, $bytes.Length)
    $stdout.Flush()
}
while (($line = [Console]::In.ReadLine()) -ne $null) {
    if ($line -match '"method":"runtime/initialize"') {
        Write-Lf $init
        $grandchild = Start-Process -FilePath ($env:SystemRoot + '\System32\WindowsPowerShell\v1.0\powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 30') -WindowStyle Hidden -PassThru
        [IO.File]::WriteAllText($PidPath, [string]$grandchild.Id)
        continue
    }
    if ($line -match '"method":"runtime/initialized"') {
        $token = ($line | ConvertFrom-Json).params.readyToken
        $ready = '{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"serverInstanceId":"srv_fixture","eventId":"evt_ready","occurredAt":"2099-01-01T00:00:00Z","status":"ready","readyToken":"' + $token + '"}}'
        Write-Lf $ready
        Start-Sleep -Seconds 30
        break
    }
}
"#;
    let script_path = run_dir.join("fixture.ps1");
    fs::write(&script_path, script).unwrap();
    let mut config = sidecar_config(&powershell, &run_dir);
    config.args = vec![
        OsString::from("-NoProfile"),
        OsString::from("-NonInteractive"),
        OsString::from("-File"),
        script_path.clone().into_os_string(),
        OsString::from("-PidPath"),
        pid_path.clone().into_os_string(),
    ];
    config.env.insert(
        OsString::from("SystemRoot"),
        system_root.clone().into_os_string(),
    );
    config.env.insert(
        OsString::from("WINDIR"),
        system_root.clone().into_os_string(),
    );
    config.env.insert(
        OsString::from("SystemDrive"),
        system_root
            .components()
            .next()
            .map(|component| component.as_os_str().to_owned())
            .unwrap_or_else(|| OsString::from("C:")),
    );
    config.ready_timeout = Duration::from_secs(5);
    config.shutdown_timeout = Duration::from_secs(3);
    let mut supervisor = SidecarSupervisor::new(config).unwrap();
    supervisor.start_with_session_hook(None).unwrap();
    assert!(wait_for_path(&pid_path, Duration::from_secs(2)));
    let grandchild_pid: u32 = fs::read_to_string(&pid_path).unwrap().parse().unwrap();
    assert!(process_exists(grandchild_pid, &system_root));

    let watchdog_started = Instant::now();
    let pending_error = supervisor.request(
        "turn/start",
        json!({
            "threadId": "thr_blocked_stdin",
            "content": [{"type": "text", "text": "x".repeat(900_000)}],
            "deadlineMs": 30_000
        }),
        Duration::from_secs(30),
    );
    assert_eq!(
        pending_error,
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    assert!(
        watchdog_started.elapsed() < Duration::from_secs(10),
        "real ChildStdin watchdog exceeded its total deadline"
    );

    let state_deadline = Instant::now() + Duration::from_secs(2);
    while supervisor.state() == LifecycleState::Ready && Instant::now() < state_deadline {
        thread::yield_now();
    }
    assert_eq!(supervisor.state(), LifecycleState::Backoff);
    let join_deadline = Instant::now() + Duration::from_secs(2);
    join_current_writer_until(&supervisor, join_deadline)
        .expect("writer actor must be joined after ChildStdin cancellation");
    let cleanup_deadline = Instant::now() + Duration::from_secs(2);
    while process_exists(grandchild_pid, &system_root) && Instant::now() < cleanup_deadline {
        thread::yield_now();
    }
    assert!(!process_exists(grandchild_pid, &system_root));
    supervisor
        .shutdown_until(Instant::now() + Duration::from_secs(2))
        .unwrap();
}
