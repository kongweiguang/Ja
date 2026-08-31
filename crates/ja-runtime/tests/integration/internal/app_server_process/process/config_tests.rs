// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::{SidecarConfig, default_runtime_environment_from};
use crate::app_server_process::AppServerProcessError;
use crate::unit_support_tests::poison_mutex;
use std::collections::BTreeMap;
use std::ffi::{OsStr, OsString};
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// 测试显式展开全部目录角色；调用方只在验证目录等价场景时复用同一路径。
fn sidecar_config(
    executable: impl Into<PathBuf>,
    run_dir: impl Into<PathBuf>,
) -> SidecarConfig {
    let run_dir = run_dir.into();
    SidecarConfig::with_directories(
        executable,
        run_dir.clone(),
        run_dir.clone(),
        run_dir.clone(),
        run_dir,
    )
}

/// 锁定 Windows matrix 验证过的 OS/runtime 启动基线，防止 PATH 或无关用户变量成为
/// sidecar 隐式能力。
#[test]
fn default_runtime_environment_is_narrow() {
    let run_dir = PathBuf::from("ja-owned-run-dir");
    let process_env = BTreeMap::from([
        ("SystemRoot".to_owned(), OsString::from("C:\\Windows")),
        (
            "PATH".to_owned(),
            OsString::from("C:\\secret-project\\bin;C:\\Windows\\System32"),
        ),
        (
            "ComSpec".to_owned(),
            OsString::from("C:\\Windows\\System32\\cmd.exe"),
        ),
        (
            "OPENAI_API_KEY".to_owned(),
            OsString::from("should-not-cross"),
        ),
        (
            "HTTP_PROXY".to_owned(),
            OsString::from("http://proxy.invalid"),
        ),
    ]);
    let environment =
        default_runtime_environment_from(&run_dir, |name| process_env.get(name).cloned());
    let names = environment
        .keys()
        .map(|name| name.to_string_lossy().into_owned())
        .collect::<Vec<_>>();
    #[cfg(windows)]
    assert!(names.iter().all(|name| matches!(
        name.as_str(),
        "SystemRoot" | "PATH" | "ComSpec" | "TEMP" | "TMP"
    )));
    #[cfg(windows)]
    assert_eq!(
        environment.get(OsStr::new("PATH")),
        Some(&OsString::from(
            "C:\\secret-project\\bin;C:\\Windows\\System32"
        ))
    );
    #[cfg(windows)]
    assert_eq!(
        environment.get(OsStr::new("ComSpec")),
        Some(&OsString::from("C:\\Windows\\System32\\cmd.exe"))
    );
    #[cfg(windows)]
    for name in ["TEMP", "TMP"] {
        assert_eq!(
            environment.get(OsStr::new(name)).map(OsString::as_os_str),
            Some(run_dir.as_os_str())
        );
    }
    #[cfg(target_os = "macos")]
    assert!(
        names
            .iter()
            .all(|name| matches!(name.as_str(), "PATH" | "TMPDIR"))
    );
    #[cfg(any(windows, target_os = "macos"))]
    assert_eq!(
        environment.get(OsStr::new("PATH")),
        Some(&OsString::from(
            "C:\\secret-project\\bin;C:\\Windows\\System32"
        ))
    );
    #[cfg(target_os = "macos")]
    assert_eq!(
        environment
            .get(OsStr::new("TMPDIR"))
            .map(OsString::as_os_str),
        Some(run_dir.as_os_str())
    );
    #[cfg(not(any(windows, target_os = "macos")))]
    assert!(names.is_empty());
    assert!(!names.iter().any(|name| name == "OPENAI_API_KEY"));
    assert!(!names.iter().any(|name| name == "HTTP_PROXY"));
}

/// 保持 home/data/run/log 物理独立，防止未来启动变更把 SQLite 静默路由到短生命周期目录。
#[test]
fn independent_directory_roles_are_validated() {
    let root = std::env::temp_dir().join(format!(
        "ja-sidecar-dirs-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock")
            .as_nanos()
    ));
    let home = root.join("home");
    let data = root.join("data");
    let run = root.join("run");
    let log = root.join("log");
    for directory in [&home, &data, &run, &log] {
        std::fs::create_dir_all(directory).expect("sidecar directory");
    }
    let config = SidecarConfig::with_directories(
        std::env::current_exe().expect("test executable"),
        &home,
        &data,
        &run,
        &log,
    );
    assert_ne!(config.home_dir, config.data_dir);
    assert_ne!(config.data_dir, config.run_dir);
    assert_ne!(config.run_dir, config.log_dir);
    assert!(config.validate().is_ok());
    let _ = std::fs::remove_dir_all(root);
}

/// 允许 PATH 目录包含类似 marker 的名称，但仍拒绝任意 credential 变量；PATH/ComSpec
/// 只是精确的非 secret runtime slot。
#[cfg(windows)]
#[test]
fn coding_runtime_environment_validates_without_secret_value_false_positive() {
    let executable = std::env::current_exe().expect("current test executable");
    let run_dir = std::env::temp_dir();
    let mut config = sidecar_config(&executable, &run_dir);
    config.env.insert(
        OsString::from("PATH"),
        OsString::from("C:\\secret-project\\bin;C:\\Windows\\System32"),
    );
    config.env.insert(
        OsString::from("ComSpec"),
        OsString::from("C:\\Windows\\System32\\cmd.exe"),
    );
    assert!(config.validate().is_ok());
    config
        .env
        .insert(OsString::from("OPENAI_API_KEY"), OsString::from("sk-test"));
    assert_eq!(config.validate(), Err(AppServerProcessError::InvalidConfig));
}

/// 证明真实 Java 25 executable 可按 debug Tauri composition root 的同一四目录策略固定
/// identity；目录与 executable 校验不再绑定已删除的 fake runtime 或 sidecar JAR 参数。
#[cfg(windows)]
#[test]
fn configured_java25_executable_accepts_independent_directory_policy() {
    let executable =
        PathBuf::from(std::env::var_os("JA_TEST_JAVA").expect("JA_TEST_JAVA must name Java 25"));
    let root = std::env::temp_dir().join(format!(
        "ja-sidecar-java25-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock")
            .as_nanos()
    ));
    let home = root.join("home");
    let data = root.join("data");
    let run = root.join("run");
    let log = root.join("log");
    for directory in [&home, &data, &run, &log] {
        std::fs::create_dir_all(directory).expect("sidecar directory");
    }
    let config = SidecarConfig::with_directories(executable, &home, &data, &run, &log);
    assert!(config.validate().is_ok());
    let _ = std::fs::remove_dir_all(root);
}

/// executable identity 锁中毒后不得读取或覆盖可能只初始化一半的 OS handle；配置边界
/// 必须返回稳定 InvalidConfig，要求调用方重建整个 SidecarConfig。
#[cfg(windows)]
#[test]
fn poisoned_executable_identity_rejects_spawn_verification() {
    let executable = std::env::current_exe().expect("current test executable");
    let run_dir = executable
        .parent()
        .expect("test executable parent")
        .to_path_buf();
    let config = sidecar_config(executable, run_dir);
    poison_mutex(&config.executable_identity);

    assert_eq!(
        config.verify_executable_identity(),
        Err(AppServerProcessError::InvalidConfig)
    );
}

/// 证明 Native-only 启动边界允许固定 coding runtime，但不允许 JRE 回退或 secret 进入 argv。
#[test]
fn native_only_config_rejects_jre_fallback_and_secret_environment() {
    let executable = std::env::current_exe().unwrap();
    let run_dir = std::env::temp_dir();
    let mut java_home = sidecar_config(&executable, &run_dir);
    java_home
        .env
        .insert(OsString::from("JAVA_HOME"), OsString::from("C:\\JDK"));
    assert_eq!(
        java_home.validate(),
        Err(AppServerProcessError::InvalidConfig)
    );

    let mut path = sidecar_config(&executable, &run_dir);
    path.env.insert(
        OsString::from("PATH"),
        OsString::from("C:\\secret-project\\bin;C:\\Windows\\System32"),
    );
    assert!(path.validate().is_ok());

    let mut comspec = sidecar_config(&executable, &run_dir);
    comspec.env.insert(
        OsString::from("ComSpec"),
        OsString::from("C:\\Windows\\System32\\cmd.exe"),
    );
    assert!(comspec.validate().is_ok());

    let mut secret_env = sidecar_config(&executable, &run_dir);
    secret_env
        .env
        .insert(OsString::from("OPENAI_API_KEY"), OsString::from("sk-test"));
    assert_eq!(
        secret_env.validate(),
        Err(AppServerProcessError::InvalidConfig)
    );

    let mut secret_arg = sidecar_config(&executable, &run_dir);
    secret_arg
        .args
        .push(OsString::from("api_key=sk-test-secret"));
    assert_eq!(
        secret_arg.validate(),
        Err(AppServerProcessError::InvalidConfig)
    );

    let mut contained = sidecar_config(&executable, &run_dir);
    contained.workspace_root = Some(run_dir.clone());
    assert_eq!(
        contained.validate(),
        Err(AppServerProcessError::InvalidConfig)
    );

    let mut long_ready = sidecar_config(&executable, &run_dir);
    long_ready.ready_timeout = Duration::from_secs(601);
    assert_eq!(
        long_ready.validate(),
        Err(AppServerProcessError::InvalidConfig)
    );

    let mut replaced_path = sidecar_config(&executable, &run_dir);
    replaced_path.run_dir = run_dir.join("..");
    assert_eq!(
        replaced_path.validate(),
        Err(AppServerProcessError::InvalidConfig)
    );
}

/// 捕获真实 `env_clear/envs` child 边界，确保 Java shell adapter 收到 PATH/ComSpec，
/// 同时 provider key 与 proxy 变量保持缺失。
#[cfg(windows)]
#[test]
fn real_child_receives_coding_runtime_allowlist_without_secret_or_proxy() {
    let system_root = PathBuf::from(std::env::var_os("SystemRoot").expect("SystemRoot"));
    let powershell = system_root
        .join("System32")
        .join("WindowsPowerShell")
        .join("v1.0")
        .join("powershell.exe");
    if !powershell.is_file() {
        return;
    }
    let run_dir = std::env::temp_dir();
    let config = sidecar_config(&powershell, &run_dir);
    let path = config
        .env
        .get(&OsString::from("PATH"))
        .expect("coding PATH must be captured")
        .to_string_lossy()
        .into_owned();
    let comspec = config
        .env
        .get(&OsString::from("ComSpec"))
        .map(|value| value.to_string_lossy().into_owned())
        .unwrap_or_default();
    // 在 runtime 构造敏感环境变量名，避免合同 fixture 形成 credential 形状源码 literal，
    // 同时仍覆盖精确 child lookup 边界。
    let api_key_name = ["OPENAI", "API", "KEY"].join("_");
    let proxy_name = ["HTTP", "PROXY"].join("_");
    let script = format!(
        "[Console]::WriteLine(('PATH=' + $env:PATH)); [Console]::WriteLine(('ComSpec=' + $env:ComSpec)); [Console]::WriteLine(('{api_key_name}=' + [Environment]::GetEnvironmentVariable('{api_key_name}'))); [Console]::WriteLine(('{proxy_name}=' + [Environment]::GetEnvironmentVariable('{proxy_name}')))"
    );
    let output = Command::new(&powershell)
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .current_dir(&run_dir)
        .env_clear()
        .envs(config.env.iter())
        .output()
        .expect("coding runtime child spawned");
    assert!(output.status.success());
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains(&format!("PATH={path}")));
    assert!(stdout.contains(&format!("ComSpec={comspec}")));
    assert!(stdout.contains("OPENAI_API_KEY="));
    assert!(stdout.contains("HTTP_PROXY="));
    assert!(!stdout.contains("sk-"));
    assert!(!stdout.contains("proxy.invalid"));
}

/// config 固定 canonical identity 后不得跟随被替换的链接目标，避免校验与启动发生 TOCTOU 漂移。
#[test]
fn canonical_config_survives_link_replacement_without_following_new_target() {
    let root = std::env::temp_dir().join(format!(
        "ja-canonical-link-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let original = root.join("original");
    let replacement = root.join("replacement");
    let link = root.join("run");
    fs::create_dir_all(&original).unwrap();
    fs::create_dir_all(&replacement).unwrap();

    #[cfg(windows)]
    let link_created = Command::new("cmd")
        .args([
            "/C",
            "mklink",
            "/J",
            link.to_str().unwrap(),
            original.to_str().unwrap(),
        ])
        .status()
        .map(|status| status.success())
        .unwrap_or(false);
    #[cfg(unix)]
    let link_created = std::os::unix::fs::symlink(&original, &link).is_ok();
    #[cfg(not(any(unix, windows)))]
    let link_created = false;

    if !link_created {
        let _ = fs::remove_dir_all(&root);
        return;
    }
    let executable = std::env::current_exe().unwrap();
    let mut config = sidecar_config(&executable, &link);
    assert!(config.validate().is_ok());

    #[cfg(windows)]
    {
        fs::remove_dir(&link).unwrap();
        assert!(
            Command::new("cmd")
                .args([
                    "/C",
                    "mklink",
                    "/J",
                    link.to_str().unwrap(),
                    replacement.to_str().unwrap(),
                ])
                .status()
                .unwrap()
                .success()
        );
    }
    #[cfg(unix)]
    {
        fs::remove_file(&link).unwrap();
        std::os::unix::fs::symlink(&replacement, &link).unwrap();
    }
    assert!(config.validate().is_ok());
    config.run_dir = link;
    assert_eq!(config.validate(), Err(AppServerProcessError::InvalidConfig));
    let _ = fs::remove_dir_all(&root);
}

#[cfg(windows)]
/// Windows executable guard 必须持有目标 identity 到 config drop，阻止启动前替换二进制。
#[test]
fn executable_identity_guard_blocks_target_replacement_until_config_drop() {
    let root = std::env::temp_dir().join(format!(
        "ja-executable-identity-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&root).unwrap();
    let executable = root.join("sidecar.exe");
    fs::copy(std::env::current_exe().unwrap(), &executable).unwrap();
    let config = sidecar_config(&executable, &root);
    config.validate().expect("identity guard opens executable");
    assert!(
        fs::OpenOptions::new()
            .write(true)
            .open(&executable)
            .is_err(),
        "read-only sharing must block writes while config is alive"
    );
    drop(config);
    fs::remove_file(&executable).expect("identity guard releases target");
    fs::remove_dir_all(root).unwrap();
}
