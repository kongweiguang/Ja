// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::fs;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{SystemTime, UNIX_EPOCH};

/// 子进程从真实 binary 入口验证参数拒绝发生在任何后台接入之前。
#[test]
fn rejects_unknown_option_before_starting_server() {
    let output = Command::new(env!("CARGO_BIN_EXE_ja"))
        .arg("--unknown")
        .output()
        .expect("launch ja");
    assert_eq!(output.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&output.stderr).contains("未知选项"));
}

/// 以中文触发字节上限且保持 Windows argv 可启动；拒绝发生在创建 Home、接入后台及写入会话之前。
#[test]
fn rejects_oversized_interactive_prompt_before_starting_server() {
    let missing_home = temp_profile();
    let output = Command::new(env!("CARGO_BIN_EXE_ja"))
        .arg("界".repeat(12 * 1024))
        .env("JA_HOME", &missing_home)
        .output()
        .expect("launch ja with oversized interactive prompt");
    assert_eq!(output.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&output.stderr).contains("输入内容过长"));
    assert!(
        !missing_home.exists(),
        "invalid prompt must not initialize a Home"
    );
}

/// 首次配置必须显式触发；帮助列出设置入口，畸形设置命令仍在连接后台前被拒绝。
#[test]
fn setup_is_an_explicit_command() {
    let help = Command::new(env!("CARGO_BIN_EXE_ja"))
        .arg("--help")
        .output()
        .expect("launch ja help");
    assert!(help.status.success());
    assert!(String::from_utf8_lossy(&help.stdout).contains("ja setup"));

    let invalid = Command::new(env!("CARGO_BIN_EXE_ja"))
        .args(["setup", "unexpected"])
        .output()
        .expect("launch invalid setup command");
    assert_eq!(invalid.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&invalid.stderr).contains("setup 不接受参数"));
}

/// 版本查询直接从匹配的 CLI 二进制给出结果，不创建配置目录或后台连接。
#[test]
fn version_reports_packaged_cli_without_starting_server() {
    let missing_profile = temp_profile();
    for flag in ["--version", "-V"] {
        let output = Command::new(env!("CARGO_BIN_EXE_ja"))
            .arg(flag)
            .env("USERPROFILE", &missing_profile)
            .env("HOME", &missing_profile)
            .output()
            .expect("launch ja version");
        assert!(output.status.success());
        assert_eq!(
            String::from_utf8_lossy(&output.stdout).trim(),
            format!("ja {}", env!("CARGO_PKG_VERSION"))
        );
    }
    assert!(!missing_profile.exists());
}

/// 管道 EOF 必须让 `exec -` 有界返回，不能因空 stdin 等待后台或创建会话。
#[test]
fn exec_dash_rejects_empty_stdin_without_starting_server() {
    let output = Command::new(env!("CARGO_BIN_EXE_ja"))
        .args(["exec", "-"])
        .stdin(Stdio::null())
        .output()
        .expect("launch ja exec");
    assert_eq!(output.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&output.stderr).contains("exec 需要任务文本"));
}

/// 已存在的非目录 `.ja` 必须阻止 endpoint 初始化，不能在别处创建共享后台身份。
#[test]
fn rejects_non_directory_home_boundary() {
    let profile = temp_profile();
    fs::create_dir(&profile).expect("create profile");
    fs::write(profile.join(".ja"), b"not a directory").expect("create blocked home");
    let output = Command::new(env!("CARGO_BIN_EXE_ja"))
        .args(["server", "status"])
        .env_remove("JA_HOME")
        .env_remove("JA_E2E_RUNTIME_ROOT")
        .env("USERPROFILE", &profile)
        .env("HOME", &profile)
        .output()
        .expect("launch ja");
    assert_eq!(output.status.code(), Some(4));
    assert!(String::from_utf8_lossy(&output.stderr).contains("重解析点或非目录"));
    fs::remove_file(profile.join(".ja")).expect("remove blocked home");
    fs::remove_dir(&profile).expect("remove profile");
}

#[cfg(any(windows, unix))]
/// 默认与正式 JA_HOME 的 alias 均在接入端点前拒绝，覆盖 Windows junction 的实际边界。
#[test]
fn rejects_linked_home_boundary() {
    let profile = temp_profile();
    fs::create_dir(&profile).expect("create profile");
    let target = profile.join("target");
    fs::create_dir(&target).expect("create target");
    let link = profile.join(".ja");
    #[cfg(windows)]
    let linked = std::os::windows::fs::symlink_dir(&target, &link);
    #[cfg(unix)]
    let linked = std::os::unix::fs::symlink(&target, &link);
    if let Err(error) = linked {
        #[cfg(windows)]
        {
            if error.kind() == std::io::ErrorKind::PermissionDenied
                || error.raw_os_error() == Some(1314)
            {
                // Windows Developer Mode 未启用时用无特权 junction 覆盖同一 reparse 边界。
                let status = Command::new("cmd")
                    .args(["/C", "mklink", "/J"])
                    .arg(&link)
                    .arg(&target)
                    .status()
                    .expect("create junction");
                assert!(status.success(), "junction fixture must be available");
            } else {
                panic!("create linked home: {error}");
            }
        }
        #[cfg(unix)]
        panic!("create linked home: {error}");
    }
    for explicit in [false, true] {
        let mut command = Command::new(env!("CARGO_BIN_EXE_ja"));
        command
            .args(["server", "status"])
            .env_remove("JA_HOME")
            .env_remove("JA_E2E_RUNTIME_ROOT")
            .env("USERPROFILE", &profile)
            .env("HOME", &profile);
        if explicit {
            command.env("JA_HOME", &link);
        }
        let output = command.output().expect("launch ja");
        assert_eq!(output.status.code(), Some(4));
        assert!(String::from_utf8_lossy(&output.stderr).contains("重解析点或非目录"));
    }
    #[cfg(windows)]
    fs::remove_dir(&link).expect("remove link");
    #[cfg(unix)]
    fs::remove_file(&link).expect("remove link");
    fs::remove_dir(&target).expect("remove target");
    fs::remove_dir(&profile).expect("remove profile");
}

/// 独立进程证明正式 JA_HOME 覆盖默认及 debug seam，错误目录在任何后台连接前被拒绝。
#[test]
fn ja_home_overrides_profile_and_debug_runtime_root() {
    let root = temp_profile();
    let profile = temp_profile();
    fs::write(&root, b"blocked explicit home").expect("create blocked explicit home");
    let output = Command::new(env!("CARGO_BIN_EXE_ja"))
        .args(["server", "status"])
        .env("JA_HOME", &root)
        .env("JA_E2E_RUNTIME_ROOT", profile.join("debug-home"))
        .env("USERPROFILE", &profile)
        .env("HOME", &profile)
        .output()
        .expect("launch ja with explicit home");
    assert_eq!(output.status.code(), Some(4));
    assert!(String::from_utf8_lossy(&output.stderr).contains("重解析点或非目录"));
    assert!(!profile.exists());
    fs::remove_file(root).expect("remove owned blocked home");
}

/// 错误覆盖不得悄悄连接用户默认库；相对路径、空值和 traversal 均在目录创建前失败。
#[test]
fn invalid_ja_home_does_not_create_profile_or_connect_server() {
    let profile = temp_profile();
    let filesystem_root = profile.ancestors().last().expect("filesystem root");
    for input in [
        PathBuf::new(),
        "relative-home".into(),
        profile.join("../escape"),
        filesystem_root.to_path_buf(),
    ] {
        let output = Command::new(env!("CARGO_BIN_EXE_ja"))
            .args(["server", "status"])
            .env("JA_HOME", input)
            .env("USERPROFILE", &profile)
            .env("HOME", &profile)
            .output()
            .expect("launch ja with invalid home");
        assert_eq!(output.status.code(), Some(4));
        assert!(String::from_utf8_lossy(&output.stderr).contains("JA_HOME"));
        assert!(!profile.exists());
    }
}

/// 私有测试目录由进程与时间共同标识，不触碰用户真实 Ja 配置。
fn temp_profile() -> PathBuf {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_nanos();
    std::env::temp_dir().join(format!("ja-cli-paths-{}-{stamp}", std::process::id()))
}
