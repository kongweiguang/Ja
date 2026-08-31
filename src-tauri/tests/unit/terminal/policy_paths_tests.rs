// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 测试与生产实现分文件，既保持对模块私有不变量的覆盖，也避免生产文件承载测试体。


use super::interactive_shell_directory;
use std::path::PathBuf;

/// 普通 drive path 应在 PowerShell prompt 中保持可读，不暴露 verbatim namespace。
#[test]
fn interactive_shell_directory_removes_verbatim_drive_prefix() {
    assert_eq!(
        interactive_shell_directory(PathBuf::from(r"\\?\C:\dev\rust\ja")),
        PathBuf::from(r"C:\dev\rust\ja")
    );
}

/// 移除 verbatim namespace 时必须恢复 UNC network prefix，不能把网络路径变成相对路径。
#[test]
fn interactive_shell_directory_restores_unc_prefix() {
    assert_eq!(
        interactive_shell_directory(PathBuf::from(r"\\?\UNC\server\share\ja")),
        PathBuf::from(r"\\server\share\ja")
    );
}
