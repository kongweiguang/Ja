// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang


use super::*;
use crate::app_runtime::EventSink;
use crate::runtime_test_support::RuntimeHostHarness;
use base64::Engine;
use std::ffi::OsString;
use std::fs;
use std::path::Path;

/// 通过受控 panic 只中毒目标 mutex；测试不读取中毒 guard，从而能准确验证生产代码的
/// fail-closed 分支，而不会在夹具中复制被禁止的恢复方式。
fn poison_mutex<T>(mutex: &std::sync::Mutex<T>) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _guard = match mutex.lock() {
            Ok(guard) => guard,
            Err(_) => panic!("mutex fixture was already poisoned"),
        };
        panic!("intentional mutex poison");
    }));
    assert!(result.is_err(), "poison fixture must unwind");
}

/// 构造四目录生产参数，确保测试直接验证 Java 进程边界而不依赖设置层对象。
fn production_directory_args(home: &Path, data: &Path, run: &Path, log: &Path) -> Vec<OsString> {
    let encode = |path: &Path| {
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(path.to_string_lossy().as_bytes())
    };
    vec![
        OsString::from(format!("--home-dir-base64={}", encode(home))),
        OsString::from(format!("--data-dir-base64={}", encode(data))),
        OsString::from(format!("--run-dir-base64={}", encode(run))),
        OsString::from(format!("--log-dir-base64={}", encode(log))),
    ]
}

/// 验证唯一构造入口完整保留四目录策略，防止宿主初始化再次引入旁路配置来源。
#[test]
fn constructor_preserves_four_directory_production_policy() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-host-constructor-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    let home_dir = root.join("home");
    let data_dir = root.join("data");
    let run_dir = root.join("run");
    let log_dir = root.join("log");
    for directory in [&home_dir, &data_dir, &run_dir, &log_dir] {
        fs::create_dir_all(directory).expect("create runtime directory fixture");
    }
    let args = production_directory_args(&home_dir, &data_dir, &run_dir, &log_dir);
    let config = RuntimeHostHarness::launch_config_with_dirs(
        std::env::current_exe().expect("test executable"),
        args.clone(),
        home_dir,
        data_dir,
        run_dir.clone(),
        log_dir,
    );
    let sink: EventSink = Arc::new(|_| Ok(()));
    let host = RuntimeHost::new(config, sink);

    let info = host.storage_info();
    assert_eq!(info.data_path, run_dir.to_string_lossy());
    assert!(info.native_image);
    fs::remove_dir_all(root).expect("remove constructor fixture");
}

/// Runtime storage 事实必须反映固定 launch policy 与既有 durable backup，且不暴露 executable
/// 或 argument data。
#[test]
fn storage_info_reports_jvm_run_directory_and_backup() {
    let root = std::env::temp_dir().join(format!("ja-runtime-storage-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&root).expect("runtime storage fixture");
    fs::write(root.join("ja.sqlite.bak"), b"backup").expect("backup fixture");
    let sink: EventSink = Arc::new(|_| Ok(()));
    let host = RuntimeHost::new(
        RuntimeHostHarness::launch_config(
            root.join("java.exe"),
            vec![OsString::from("-jar"), OsString::from("ja.jar")],
            root.clone(),
        ),
        sink,
    );

    let info = host.storage_info();

    assert!(!info.native_image);
    assert_eq!(info.data_path, root.to_string_lossy());
    assert_eq!(info.log_path, None);
    assert_eq!(info.cache_path, None);
    assert_eq!(info.last_backup.as_deref(), Some("已生成本地备份"));
    fs::remove_dir_all(root).expect("remove runtime storage fixture");
}

/// bridge owner 锁中毒后，application 无法证明 actor 与进程 owner 是否一致；所有生命周期
/// 入口必须返回同一稳定错误，且最终退出证明保持关闭。
#[test]
fn bridge_poison_fails_all_runtime_lifecycle_paths_closed() {
    let sink: EventSink = Arc::new(|_| Ok(()));
    let host = RuntimeHost::new(
        RuntimeHostHarness::launch_config(
            std::env::current_exe().expect("test executable"),
            Vec::new(),
            std::env::temp_dir(),
        ),
        sink,
    );
    poison_mutex(host.bridge.as_ref());

    assert_eq!(
        host.state().expect_err("poisoned state must fail").code,
        "RUNTIME_UNAVAILABLE"
    );
    assert_eq!(
        host.start().expect_err("poisoned start must fail").code,
        "RUNTIME_UNAVAILABLE"
    );
    assert_eq!(
        host.shutdown()
            .expect_err("poisoned shutdown must fail")
            .code,
        "RUNTIME_UNAVAILABLE"
    );
    assert!(!host.exit_ready());
}

/// Workspace binding 锁中毒后 identity、trust 与 capability handle 都不再可信；查询与通用
/// Workspace 切换必须在接触 sidecar 前关闭失败。
#[test]
fn workspace_poison_rejects_capability_lookup_and_reconfiguration() {
    let sink: EventSink = Arc::new(|_| Ok(()));
    let host = RuntimeHost::new(
        RuntimeHostHarness::launch_config(
            std::env::current_exe().expect("test executable"),
            Vec::new(),
            std::env::temp_dir(),
        ),
        sink,
    );
    poison_mutex(host.workspace.as_ref());

    assert_eq!(
        host.with_configured_workspace("ws_fixture", |_| ())
            .expect_err("poisoned binding must not authorize a handle"),
        WorkspaceLookup::Unknown
    );
    assert_eq!(
        host.general_workspace()
            .expect_err("poisoned binding must block replacement")
            .code,
        "RUNTIME_UNAVAILABLE"
    );
}
