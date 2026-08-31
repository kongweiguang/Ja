// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 测试与生产实现分文件，既保持对模块私有不变量的覆盖，也避免生产文件承载测试体。

use super::*;

/// PowerShell metacharacter 必须保持在 literal string 内，单引号通过双写保真。
#[test]
fn powershell_paths_disable_interpolation() {
    assert_eq!(
        quote_powershell(r"C:\work\$(Get-Item env:SECRET)\O'Brien.txt"),
        r"'C:\work\$(Get-Item env:SECRET)\O''Brien.txt'"
    );
}

/// 安全 CMD 文件名必须保持为单个 quoted argv value，末尾反斜杠也要经过 Windows argv parser 保留。
#[test]
fn cmd_paths_quote_safe_characters_and_double_trailing_slashes() {
    assert_eq!(
        quote_cmd(r"C:\space & paren()\").expect("safe CMD path"),
        r#""C:\space & paren()\\""#
    );
}

/// PTY 启动后 delayed expansion 可能开启或关闭，不存在同时覆盖两种状态的单一 caret encoding。
#[test]
fn cmd_paths_fail_closed_for_state_dependent_metacharacters() {
    for value in [
        r"C:\work\100%READY%\file.txt",
        r"C:\work\bang!READY!\file.txt",
        r"C:\work\caret^name\file.txt",
    ] {
        assert_eq!(
            quote_cmd(value).expect_err("state-dependent CMD path"),
            TerminalError::new(TerminalErrorCode::DropPathUnsupported)
        );
    }
}

/// native-path batch 必须传播同一稳定错误；后续路径对 CMD 不安全时不能返回部分 quoted prefix。
#[test]
fn cmd_path_batch_is_all_or_nothing() {
    let paths = [
        std::env::temp_dir().join("safe path"),
        std::env::temp_dir().join("unsafe!path"),
    ];
    assert_eq!(
        quote_native_paths(ShellProfile::Cmd, &paths).expect_err("unsafe CMD batch"),
        TerminalError::new(TerminalErrorCode::DropPathUnsupported)
    );
}

/// POSIX command substitution 必须保持 inert，字面单引号不能提前关闭 quoting。
#[test]
fn posix_paths_disable_substitution() {
    assert_eq!(
        quote_posix("/tmp/$(id)/O'Brien"),
        "'/tmp/$(id)/O'\\''Brien'"
    );
}

/// 多路径只在当前命令行中以空格分隔，不能合成 Enter。
#[test]
fn path_list_never_appends_a_command_terminator() {
    let paths = [
        std::env::temp_dir().join("one"),
        std::env::temp_dir().join("two"),
    ];
    let quoted = quote_native_paths(ShellProfile::Bash, &paths).expect("quoted paths");
    let expected = format!(
        "{} {}",
        quote_posix(paths[0].to_str().expect("unicode temp path")),
        quote_posix(paths[1].to_str().expect("unicode temp path"))
    );
    assert_eq!(quoted, expected.as_bytes());
    assert!(!quoted.ends_with(b"\n"));
    assert!(!quoted.ends_with(b"\r"));
}
