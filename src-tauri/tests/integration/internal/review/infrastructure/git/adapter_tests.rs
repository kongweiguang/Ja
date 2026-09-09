// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::review_git_support::{TempDir, fixture_repo};
use super::*;
use crate::workspace::WorkspaceRegistry;
use std::collections::BTreeMap;
use std::ffi::OsString;

/// 使用真实最小仓库满足生产 adapter 的准入策略；空目录会被正确拒绝为 NotRepository，
/// 因此不能再把构造失败误判为 Windows 并行测试争用。
fn command_adapter() -> Option<(TempDir, GitReadOnly)> {
    let (root, _git) = fixture_repo()?;
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register command root");
    let workspace = registry.get(info.id).expect("get command root");
    let adapter = GitReadOnly::new(workspace).expect("create command adapter");
    Some((root, adapter))
}

/// 直接检查私有命令构造结果，避免为了测试而给生产类型增加 `*_for_test` 方法。
#[test]
fn git_command_environment_is_hardened() {
    let Some((_root, adapter)) = command_adapter() else {
        return;
    };
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
