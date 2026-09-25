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
    assert!(String::from_utf8_lossy(&output.stderr).contains("任务正文必须"));
}

/// 已存在的非目录 `.ja` 必须阻止 endpoint 初始化，不能在别处创建共享后台身份。
#[test]
fn rejects_non_directory_home_boundary() {
    let profile = temp_profile();
    fs::create_dir(&profile).expect("create profile");
    fs::write(profile.join(".ja"), b"not a directory").expect("create blocked home");
    let output = Command::new(env!("CARGO_BIN_EXE_ja"))
        .args(["server", "status"])
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
/// 能创建符号链接的平台必须证明 `.ja` alias 在接入共享端点前被拒绝。
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
    let output = Command::new(env!("CARGO_BIN_EXE_ja"))
        .args(["server", "status"])
        .env("USERPROFILE", &profile)
        .env("HOME", &profile)
        .output()
        .expect("launch ja");
    assert_eq!(output.status.code(), Some(4));
    assert!(String::from_utf8_lossy(&output.stderr).contains("重解析点或非目录"));
    #[cfg(windows)]
    fs::remove_dir(&link).expect("remove link");
    #[cfg(unix)]
    fs::remove_file(&link).expect("remove link");
    fs::remove_dir(&target).expect("remove target");
    fs::remove_dir(&profile).expect("remove profile");
}

/// 私有测试目录由进程与时间共同标识，不触碰用户真实 Ja 配置。
fn temp_profile() -> PathBuf {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_nanos();
    std::env::temp_dir().join(format!("ja-cli-paths-{}-{stamp}", std::process::id()))
}
