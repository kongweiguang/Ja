// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::workspace::WorkspaceRegistry;
use std::collections::BTreeMap;
use std::ffi::OsString;
use std::fs;
use std::path::PathBuf;
use uuid::Uuid;

/// 以唯一临时目录承载查询适配器测试，避免并行执行时共享路径或误删其他测试数据。
struct TempDir(PathBuf);

impl TempDir {
    /// 创建不依赖系统时钟精度的唯一目录，使 Windows 并行测试不会争用同一个 fixture。
    fn create() -> Self {
        let path = std::env::temp_dir().join(format!("ja-git-query-{}", Uuid::new_v4()));
        fs::create_dir_all(&path).expect("create Git query fixture");
        Self(path)
    }
}

impl Drop for TempDir {
    /// 只回收本测试创建的 UUID 目录；失败时不掩盖查询环境断言结果。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// 注册一个空工作区即可构造 adapter，因为本测试只检查命令环境，不启动 Git 子进程。
fn command_adapter() -> (TempDir, GitReadOnly) {
    let root = TempDir::create();
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register command root");
    let workspace = registry.get(info.id).expect("get command root");
    let adapter = GitReadOnly::new(workspace).expect("create command adapter");
    (root, adapter)
}

/// 直接检查私有命令构造结果，避免为了测试而给生产类型增加 `*_for_test` 方法。
#[test]
fn git_command_environment_is_hardened() {
    let (_root, adapter) = command_adapter();
    let command = adapter.build_command(&[OsString::from("status")]);
    let environment = command
        .get_envs()
        .map(|(key, value)| {
            (
                key.to_string_lossy().into_owned(),
                value.map(|value| value.to_string_lossy().into_owned()),
            )
        })
        .collect::<BTreeMap<_, _>>();

    assert_eq!(
        environment.get("GIT_NO_REPLACE_OBJECTS"),
        Some(&Some("1".to_owned()))
    );
    assert_eq!(
        environment.get("GIT_NO_LAZY_FETCH"),
        Some(&Some("1".to_owned()))
    );
    assert!(
        !environment
            .get("GIT_OBJECT_DIRECTORY")
            .is_some_and(Option::is_some)
    );
    assert!(
        !environment
            .get("GIT_ALTERNATE_OBJECT_DIRECTORIES")
            .is_some_and(Option::is_some)
    );
}
