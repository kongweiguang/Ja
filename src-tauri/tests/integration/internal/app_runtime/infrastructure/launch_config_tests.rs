// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use uuid::Uuid;

/// staged native resource 缺失时必须关闭失败，release app 不得静默搜索 PATH 或 JAVA_HOME。
#[test]
fn bundled_resource_requires_staged_sidecar() {
    let path = std::env::temp_dir().join(format!("ja-resource-test-{}", std::process::id()));
    std::fs::create_dir_all(&path).expect("resource test directory");
    let result = bundled_launch_config(&path, &path, &path);
    let _ = std::fs::remove_dir_all(&path);
    assert!(matches!(
        result,
        Err(error) if error == RuntimeCommandError::configuration()
    ));
}

/// debug E2E root 就是最终 Runtime 目录；只有通过绝对路径、无 traversal、canonical directory
/// 验证后才创建。
#[test]
fn runtime_root_override_is_created_and_canonicalized() {
    let parent = std::env::temp_dir().join(format!("ja-e2e-runtime-parent-{}", Uuid::new_v4()));
    let requested = parent.join("runtime");
    let default = parent.join("default-runtime");
    let resolved =
        resolve_runtime_root(default, Some(requested.as_os_str())).expect("valid runtime override");
    assert_eq!(
        resolved,
        fs::canonicalize(&requested).expect("canonical runtime root")
    );
    assert!(resolved.is_dir());
    let _ = fs::remove_dir_all(parent);
}

/// relative、parent-traversing 与 file-valued override 在创建或重定向 Runtime state 前关闭失败。
#[test]
fn runtime_root_override_rejects_invalid_boundaries() {
    let parent = std::env::temp_dir().join(format!("ja-e2e-runtime-invalid-{}", Uuid::new_v4()));
    let default = parent.join("default-runtime");
    assert_eq!(
        resolve_runtime_root(default.clone(), Some(OsStr::new("relative-runtime"))),
        Err(RuntimeCommandError::configuration())
    );
    assert_eq!(
        resolve_runtime_root(
            default.clone(),
            Some(parent.join("..").join("escape").as_os_str()),
        ),
        Err(RuntimeCommandError::configuration())
    );
    let file = parent.join("runtime-file");
    fs::create_dir_all(&parent).expect("invalid test parent");
    fs::write(&file, b"not a directory").expect("invalid runtime file");
    assert_eq!(
        resolve_runtime_root(default, Some(file.as_os_str())),
        Err(RuntimeCommandError::configuration())
    );
    let _ = fs::remove_dir_all(parent);
}

/// sidecar argument 必须携带 canonical 最终 Runtime 目录，证明 Java 收到隔离 E2E target 而非
/// 父级基础路径。
#[test]
fn runtime_root_override_is_the_encoded_sidecar_directory() {
    let parent = std::env::temp_dir().join(format!("ja-e2e-runtime-argv-{}", Uuid::new_v4()));
    let requested = parent.join("runtime");
    let resolved = resolve_runtime_root(parent.join("default"), Some(requested.as_os_str()))
        .expect("runtime override");
    let sidecar = SidecarConfig::with_directories(
        PathBuf::from("java"),
        resolved.clone(),
        resolved.clone(),
        resolved.clone(),
        resolved.clone(),
    );
    let encoded = encode_directory_argument(&sidecar.run_dir).expect("encoded runtime directory");
    let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(encoded)
        .expect("decoded runtime directory");
    assert_eq!(
        PathBuf::from(String::from_utf8(decoded).expect("utf8 runtime path")),
        resolved
    );
    let _ = fs::remove_dir_all(parent);
}

/// Debug build 只读取 Host-controlled E2E variable 的精确名称，使 test seam 显式且可审查。
#[cfg(debug_assertions)]
#[test]
fn debug_runtime_root_hook_reads_expected_name() {
    assert_eq!(
        runtime_root_override_from(|name| Some(OsString::from(name))),
        Some(OsString::from("JA_E2E_RUNTIME_ROOT"))
    );
}

/// 使用 Windows desktop QA 相同 external Runtime-root seam 验证完整 debug Java 构造；
/// home/data 必须保持调用方给出的 canonical 布局，不能从 run 父目录偷偷派生。
#[cfg(debug_assertions)]
#[test]
fn debug_java_constructor_accepts_desktop_runtime_layout() {
    let executable = std::env::var_os("JA_TEST_JAVA")
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::current_exe().expect("test executable"));
    let jar = std::env::var_os("JA_DEBUG_JAR")
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::current_exe().expect("test jar fixture"));
    let root = std::env::temp_dir().join(format!("ja-debug-java-{}", Uuid::new_v4()));
    let home = root.join("settings").join(".ja");
    let data = home.join("data");
    let run = root.join("runtime").join("run");
    let logs = std::env::var_os("JA_TEST_JAVA_LOGS")
        .map(PathBuf::from)
        .unwrap_or_else(|| home.join("logs").join("java"));
    fs::create_dir_all(&logs).expect("debug Java logs");
    let config = LaunchConfig::debug_java(executable, jar, home.clone(), data.clone(), run, logs)
        .expect("debug Java launch config");
    assert_eq!(
        config.sidecar.home_dir,
        fs::canonicalize(&home).expect("canonical home")
    );
    assert_eq!(
        config.sidecar.data_dir,
        fs::canonicalize(&data).expect("canonical data")
    );
    assert_ne!(config.sidecar.home_dir, root.join("runtime").join("home"));
    let _ = fs::remove_dir_all(root);
}

/// Release build 必须忽略传入的 lookup closure，证明 debug-only environment seam 不能被生产
/// packaging 启用。
#[cfg(not(debug_assertions))]
#[test]
fn release_runtime_root_hook_is_compile_time_disabled() {
    let value = debug_runtime_root_override();
    assert_eq!(value, None);
}

/// Native launch argument 中 data/log path 只使用 URL-safe ASCII，防止 Unicode Windows path
/// 回退到 legacy argv 解释。
#[test]
fn bundled_resource_uses_ascii_base64_directory_arguments() {
    let root = std::env::temp_dir().join(format!("ja-resource-unicode-{}", Uuid::new_v4()));
    let run_dir = root.join("运行目录");
    let logs_dir = root.join("logs");
    let staged = root.join(sidecar_resource_name());
    std::fs::create_dir_all(staged.parent().expect("sidecar parent")).expect("sidecar dir");
    std::fs::create_dir_all(&run_dir).expect("run dir");
    std::fs::create_dir_all(&logs_dir).expect("logs dir");
    std::fs::write(&staged, b"fixture").expect("staged sidecar");
    let config = bundled_launch_config(&root, &run_dir, &logs_dir).expect("launch config");
    let data_arg = config
        .sidecar
        .args
        .iter()
        .find_map(|arg| {
            arg.to_str()
                .filter(|value| value.starts_with("--data-dir-base64="))
        })
        .expect("base64 data argument");
    let encoded = data_arg.trim_start_matches("--data-dir-base64=");
    assert!(!encoded.is_empty());
    assert!(
        encoded
            .bytes()
            .all(|byte| { byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_') })
    );
    assert!(
        config
            .sidecar
            .args
            .iter()
            .all(|arg| !arg.to_string_lossy().starts_with("--data-dir="))
    );
    let log_arg = config
        .sidecar
        .args
        .iter()
        .find_map(|arg| {
            arg.to_str()
                .filter(|value| value.starts_with("--log-dir-base64="))
        })
        .expect("base64 log argument");
    let encoded_log = log_arg.trim_start_matches("--log-dir-base64=");
    assert!(
        encoded_log
            .bytes()
            .all(|byte| { byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_') })
    );
    let decoded_log = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(encoded_log)
        .expect("decoded log directory");
    assert_eq!(
        PathBuf::from(String::from_utf8(decoded_log).expect("UTF-8 log directory")),
        fs::canonicalize(&logs_dir).expect("canonical log directory")
    );
    let _ = std::fs::remove_dir_all(root);
}

/// 即使 parent app-data location 继承了更宽权限，Unix Runtime directory 仍必须保持 private。
#[cfg(unix)]
#[test]
fn prepare_run_dir_sets_private_mode() {
    use std::os::unix::fs::PermissionsExt;

    let path = std::env::temp_dir().join(format!("ja-private-run-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&path);
    let prepared = prepare_run_dir(&path).expect("private run directory");
    let mode = std::fs::metadata(prepared)
        .expect("run directory metadata")
        .permissions()
        .mode()
        & 0o777;
    let _ = std::fs::remove_dir_all(&path);
    assert_eq!(mode, 0o700);
}

/// 解析到 resource root 外的 staged symlink 不能作为 packaged executable，否则 bundle
/// replacement 可把 sidecar 重定向到不可信路径。
#[cfg(unix)]
#[test]
fn bundled_resource_rejects_symlink_escape() {
    use std::os::unix::fs::symlink;

    let root = std::env::temp_dir().join(format!("ja-resource-root-{}", std::process::id()));
    let outside = std::env::temp_dir().join(format!("ja-resource-outside-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&outside);
    std::fs::create_dir_all(root.join("sidecars")).expect("resource root");
    std::fs::create_dir_all(&outside).expect("outside root");
    let target = root.join(sidecar_resource_name());
    let outside_file = outside.join("sidecar");
    std::fs::write(&outside_file, b"not executable").expect("outside file");
    symlink(&outside_file, &target).expect("resource symlink");
    let result = bundled_launch_config(&root, &root, &root);
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&outside);
    assert!(matches!(
        result,
        Err(error) if error == RuntimeCommandError::configuration()
    ));
}

/// 即使最终文件名看似仍在 canonical resource root 下，中间 directory link 也必须被拒绝。
#[cfg(unix)]
#[test]
fn bundled_resource_rejects_intermediate_symlink_escape() {
    use std::os::unix::fs::symlink;

    let root = std::env::temp_dir().join(format!(
        "ja-resource-intermediate-root-{}",
        std::process::id()
    ));
    let outside = std::env::temp_dir().join(format!(
        "ja-resource-intermediate-outside-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&outside);
    std::fs::create_dir_all(&root).expect("resource root");
    std::fs::create_dir_all(&outside).expect("outside root");
    let outside_file = outside.join("ja-app-server");
    std::fs::write(&outside_file, b"not executable").expect("outside file");
    symlink(&outside, root.join("sidecars")).expect("intermediate resource symlink");
    let result = bundled_launch_config(&root, &root, &root);
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&outside);
    assert!(matches!(
        result,
        Err(error) if error == RuntimeCommandError::configuration()
    ));
}

/// Windows reparse-point replacement 在 canonical path resolution 前被拒绝；无 symlink
/// privilege 的环境按测试条件跳过。
#[cfg(windows)]
#[test]
fn bundled_resource_rejects_windows_reparse_escape() {
    use std::os::windows::fs::symlink_file;

    let root = std::env::temp_dir().join(format!("ja-resource-root-{}", std::process::id()));
    let outside = std::env::temp_dir().join(format!("ja-resource-outside-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&outside);
    std::fs::create_dir_all(root.join("sidecars")).expect("resource root");
    std::fs::create_dir_all(&outside).expect("outside root");
    let target = root.join(sidecar_resource_name());
    let outside_file = outside.join("sidecar");
    std::fs::write(&outside_file, b"not executable").expect("outside file");
    if symlink_file(&outside_file, &target).is_err() {
        let _ = std::fs::remove_dir_all(&root);
        let _ = std::fs::remove_dir_all(&outside);
        return;
    }
    let result = bundled_launch_config(&root, &root, &root);
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&outside);
    assert!(matches!(
        result,
        Err(error) if error == RuntimeCommandError::configuration()
    ));
}

/// staged executable 被 canonicalize 到 bundle root 外前，必须拒绝 Windows directory reparse
/// 间接跳转。
#[cfg(windows)]
#[test]
fn bundled_resource_rejects_windows_intermediate_reparse_escape() {
    use std::os::windows::fs::symlink_dir;

    let root = std::env::temp_dir().join(format!(
        "ja-resource-intermediate-root-{}",
        std::process::id()
    ));
    let outside = std::env::temp_dir().join(format!(
        "ja-resource-intermediate-outside-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&outside);
    std::fs::create_dir_all(&root).expect("resource root");
    std::fs::create_dir_all(&outside).expect("outside root");
    let outside_file = outside.join("ja-app-server.exe");
    std::fs::write(&outside_file, b"not executable").expect("outside file");
    if symlink_dir(&outside, root.join("sidecars")).is_err() {
        let _ = std::fs::remove_dir_all(&root);
        let _ = std::fs::remove_dir_all(&outside);
        return;
    }
    let result = bundled_launch_config(&root, &root, &root);
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&outside);
    assert!(matches!(
        result,
        Err(error) if error == RuntimeCommandError::configuration()
    ));
}
