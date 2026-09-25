// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::workspace::{WorkspaceHandle, WorkspaceRegistry};
use std::fs;
use std::path::PathBuf;
use std::time::Duration;
use uuid::Uuid;

/// 以唯一临时目录承载仓库策略测试，避免并行执行时共享路径或误删其他测试数据。
struct TempDir(PathBuf);

impl TempDir {
    /// 创建不依赖系统时钟精度的唯一目录，使 Windows 并行测试不会争用同一个 fixture。
    fn create() -> Self {
        let path = std::env::temp_dir().join(format!("ja-git-policy-{}", Uuid::new_v4()));
        fs::create_dir_all(&path).expect("create Git policy fixture");
        Self(path)
    }
}

impl Drop for TempDir {
    /// 只回收本测试创建的 UUID 目录；失败时不掩盖安全策略断言结果。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// 构造同时包含松散对象与 pack 元数据的最小合法对象库。
///
/// 松散对象正文按内容寻址且不具备边界跳转语义，生产策略只校验 fanout 目录而不逐文件计费；
/// 因此文件数与字节预算必须由 Git 会实际解析的 pack 元数据触发，避免测试把性能取舍误当成回归。
fn object_store_fixture() -> (TempDir, WorkspaceHandle) {
    let root = TempDir::create();
    let fanout = root.0.join(".git").join("objects").join("aa");
    let pack = root.0.join(".git").join("objects").join("pack");
    fs::create_dir_all(&fanout).expect("create object fanout");
    fs::create_dir_all(&pack).expect("create object pack directory");
    fs::write(
        fanout.join("0123456789abcdef0123456789abcdef01234567"),
        b"fixture",
    )
    .expect("write loose object fixture");
    fs::write(
        pack.join("pack-0123456789abcdef0123456789abcdef01234567.idx"),
        b"fixture",
    )
    .expect("write pack index fixture");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register object root");
    let workspace = registry.get(info.id).expect("get object root");
    (root, workspace)
}

/// 通过私有预算对象注入边界值，证明目录、文件、字节和绝对 Deadline 均为失败关闭。
#[test]
fn object_store_scan_limits_fail_closed() {
    let (_root, workspace) = object_store_fixture();
    let cases = [
        ObjectScanLimits {
            max_directories: 1,
            max_files: 200_000,
            max_bytes: 4 * 1024 * 1024 * 1024,
            max_depth: 4,
            max_duration: Duration::from_secs(2),
        },
        ObjectScanLimits {
            max_directories: 8 * 1024,
            max_files: 0,
            max_bytes: 4 * 1024 * 1024 * 1024,
            max_depth: 4,
            max_duration: Duration::from_secs(2),
        },
        ObjectScanLimits {
            max_directories: 8 * 1024,
            max_files: 200_000,
            max_bytes: 0,
            max_depth: 4,
            max_duration: Duration::from_secs(2),
        },
        ObjectScanLimits {
            max_directories: 8 * 1024,
            max_files: 200_000,
            max_bytes: 4 * 1024 * 1024 * 1024,
            max_depth: 4,
            max_duration: Duration::ZERO,
        },
    ];

    for limits in cases {
        assert!(matches!(
            validate_object_directory_with_limits(&workspace, ".git/objects", limits),
            Err(GitError::ExternalWorktree)
        ));
    }
}

/// 锁定生产扫描窗口高于普通 NTFS 仓库的测量成本，同时不允许其侵占 Git 命令超时预算。
#[test]
fn production_object_scan_window_covers_real_windows_repositories() {
    assert!(MAX_OBJECT_SCAN_TIME >= Duration::from_secs(10));
    assert!(MAX_OBJECT_SCAN_TIME < Duration::from_secs(15));
}
