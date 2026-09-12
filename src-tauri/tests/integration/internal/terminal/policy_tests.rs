// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 测试与生产实现分文件，既保持对模块私有不变量的覆盖，也避免生产文件承载测试体。

use super::*;
use std::fs;

/// 限制关系必须在构造时失败，避免后续队列出现不可证明的组合。
#[test]
fn limits_reject_inverted_byte_budgets() {
    let mut limits = TerminalLimits::default();
    limits.max_input_chunk_bytes = limits.max_input_queue_bytes + 1;
    assert_eq!(
        limits.validate().unwrap_err().code(),
        TerminalErrorCode::InvalidConfig
    );
}

/// 即使调用方提供极小 output budget，也必须能表示 reserved terminal lane。
#[test]
fn limits_reserve_terminal_event_bytes() {
    let limits = TerminalLimits {
        max_output_queue_bytes: MIN_TERMINAL_EVENT_BYTES - 1,
        ..TerminalLimits::default()
    };
    assert_eq!(
        limits.validate().unwrap_err().code(),
        TerminalErrorCode::InvalidConfig
    );
}

/// 环境 override 只做 OS 结构校验，正常 CLI 使用的凭据、路径和多行值不能被名称过滤。
#[test]
fn environment_override_preserves_normal_cli_values() {
    let environment = build_environment(&BTreeMap::from([
        (String::from("OPENAI_API_KEY"), String::from("hidden")),
        (String::from("ProgramFiles(x86)"), String::from(r"C:\Program Files (x86)")),
        (String::from("CERTIFICATE_DATA"), String::from("line one\nline two")),
    ]))
    .expect("normal environment overrides");
    assert_eq!(
        environment.get(&OsString::from("OPENAI_API_KEY")),
        Some(&OsString::from("hidden"))
    );
    assert_eq!(
        environment.get(&OsString::from("ProgramFiles(x86)")),
        Some(&OsString::from(r"C:\Program Files (x86)"))
    );
    assert_eq!(
        environment.get(&OsString::from("CERTIFICATE_DATA")),
        Some(&OsString::from("line one\nline two"))
    );
}

/// 结构校验仍拒绝平台无法表达的键值，但不把安全含义扩展成 secret/dangerous 名称规则。
#[test]
fn environment_override_rejects_only_invalid_os_shapes() {
    for (key, value) in [
        (String::new(), String::from("value")),
        (String::from("NAME=OTHER"), String::from("value")),
        (String::from("NAME\0"), String::from("value")),
        (String::from("NAME"), String::from("value\0")),
    ] {
        let error = build_environment(&BTreeMap::from([(key, value)])).unwrap_err();
        assert_eq!(error.code(), TerminalErrorCode::EnvironmentNotAllowed);
    }
}

/// 可见 profile 必须逐项通过真实 resolver，Windows 也绝不能混入 Unix shell。
#[test]
fn available_profiles_match_resolver_and_platform_closed_set() {
    let available = available_shell_profiles();
    assert!(
        available
            .iter()
            .all(|profile| resolve_shell(*profile).is_ok())
    );

    #[cfg(windows)]
    {
        let windows_profiles = [
            ShellProfile::Default,
            ShellProfile::PowerShell,
            ShellProfile::Cmd,
        ];
        for profile in windows_profiles {
            assert_eq!(available.contains(&profile), resolve_shell(profile).is_ok());
        }
        assert!(available.iter().all(|profile| matches!(
            profile,
            ShellProfile::Default | ShellProfile::PowerShell | ShellProfile::Cmd
        )));
    }
}

/// Windows 终端只跳过重复 banner，必须加载用户宿主 PowerShell Profile。
#[cfg(windows)]
#[test]
fn windows_powershell_profiles_load_host_profile() {
    for profile in [ShellProfile::Default, ShellProfile::PowerShell] {
        let resolved = resolve_shell(profile).expect("Windows PowerShell profile");
        assert_eq!(resolved.args, vec![OsString::from("-NoLogo")]);
    }
}

/// policy 只接受 canonical workspace 内的目录，验证正常子目录仍可启动。
#[test]
fn policy_accepts_child_directory() {
    let root = std::env::temp_dir().join(format!("ja-terminal-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(root.join("child")).unwrap();
    let policy = TerminalPolicy::new(&root).unwrap();
    let prepared = policy
        .prepare(&LaunchRequest {
            cwd: Some(PathBuf::from("child")),
            ..LaunchRequest::default()
        })
        .unwrap();
    assert!(prepared.cwd.ends_with("child"));
    fs::remove_dir_all(root).unwrap();
}
