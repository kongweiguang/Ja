// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::{SidecarConfig, default_runtime_environment_from};
use crate::app_server_process::AppServerProcessError;
use crate::unit_support_tests::poison_mutex;
use std::collections::BTreeMap;
use std::ffi::OsString;
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

/// 锁定环境快照原样保留用户变量，防止 App Server 与普通终端出现隐式环境差异。
#[test]
fn default_runtime_environment_preserves_host_snapshot() {
    let process_env = BTreeMap::from([
        (OsString::from("SystemRoot"), OsString::from("C:\\Windows")),
        (
            OsString::from("PATH"),
            OsString::from("C:\\secret-project\\bin;C:\\Windows\\System32"),
        ),
        (
            OsString::from("ComSpec"),
            OsString::from("C:\\Windows\\System32\\cmd.exe"),
        ),
        (
            OsString::from("PSModuleAnalysisCachePath"),
            OsString::from("C:\\PSModuleAnalysisCachePath\\ModuleAnalysisCache"),
        ),
        (
            OsString::from("TEMP"),
            OsString::from("C:\\Users\\test\\AppData\\Local\\Temp"),
        ),
        (
            OsString::from("TMP"),
            OsString::from("C:\\Users\\test\\AppData\\Local\\Temp"),
        ),
        (
            OsString::from("APPDATA"),
            OsString::from("C:\\Users\\test\\AppData\\Roaming"),
        ),
        (
            OsString::from("OPENAI_API_KEY"),
            OsString::from("should-not-cross"),
        ),
        (
            OsString::from("HTTP_PROXY"),
            OsString::from("http://proxy.invalid"),
        ),
    ]);
    let environment = default_runtime_environment_from(process_env.clone());
    assert_eq!(environment, process_env);
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

/// 用户环境名和值不再由关键字推断权限；普通终端可用的变量可以原样传给 sidecar。
#[test]
fn user_environment_is_accepted_without_keyword_filter() {
    let executable = std::env::current_exe().expect("current test executable");
    let run_dir = std::env::temp_dir();
    let mut config = sidecar_config(&executable, &run_dir);
    config.env.insert(
        OsString::from("OPENAI_API_KEY"),
        OsString::from("fixture-value"),
    );
    config.env.insert(
        OsString::from("HTTP_PROXY"),
        OsString::from("http://proxy.invalid"),
    );
    config
        .env
        .insert(OsString::from("GH_CONFIG_DIR"), OsString::from("C:\\gh"));
    assert!(config.validate().is_ok());
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

/// 证明 sidecar 接受完整用户环境，同时仍保留 argv secret 与 Rust generation 参数边界。
#[test]
fn runtime_config_accepts_user_environment_and_rejects_reserved_inputs() {
    let executable = std::env::current_exe().unwrap();
    let run_dir = std::env::temp_dir();
    let mut java_home = sidecar_config(&executable, &run_dir);
    java_home
        .env
        .insert(OsString::from("JAVA_HOME"), OsString::from("C:\\JDK"));
    assert!(java_home.validate().is_ok());

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
        .insert(OsString::from("OPENAI_API_KEY"), OsString::from("fixture-value"));
    assert!(secret_env.validate().is_ok());

    let mut secret_arg = sidecar_config(&executable, &run_dir);
    secret_arg
        .args
        .push(OsString::from("api_key=sk-test-secret"));
    assert_eq!(
        secret_arg.validate(),
        Err(AppServerProcessError::InvalidConfig)
    );

    let mut reserved_generation = sidecar_config(&executable, &run_dir);
    reserved_generation
        .args
        .push(OsString::from("--ja-runtime-generation=99"));
    assert_eq!(
        reserved_generation.validate(),
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

/// 捕获真实 Windows child 的继承边界，确保 PATH、用户配置路径、代理与临时目录都与
/// 宿主一致；该测试刻意不调用 `env_clear`，覆盖生产侧“继承再覆盖”的实际语义。
#[cfg(windows)]
#[test]
fn real_child_receives_complete_host_environment_without_temp_rewrite() {
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
    let path = std::env::var("PATH").expect("PATH");
    let comspec = std::env::var("ComSpec").unwrap_or_default();
    let temp = std::env::var("TEMP").expect("TEMP");
    let tmp = std::env::var("TMP").expect("TMP");
    let appdata = std::env::var("APPDATA").unwrap_or_default();
    let script = "[Console]::WriteLine(('PATH=' + $env:PATH)); [Console]::WriteLine(('ComSpec=' + $env:ComSpec)); [Console]::WriteLine(('TEMP=' + $env:TEMP)); [Console]::WriteLine(('TMP=' + $env:TMP)); [Console]::WriteLine(('APPDATA=' + $env:APPDATA))".to_string();
    let output = Command::new(&powershell)
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .current_dir(&run_dir)
        .envs(config.env.iter())
        .output()
        .expect("coding runtime child spawned");
    assert!(output.status.success());
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains(&format!("PATH={path}")));
    assert!(stdout.contains(&format!("ComSpec={comspec}")));
    assert!(stdout.contains(&format!("TEMP={temp}")));
    assert!(stdout.contains(&format!("TMP={tmp}")));
    assert!(stdout.contains(&format!("APPDATA={appdata}")));
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
