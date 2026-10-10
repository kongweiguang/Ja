// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::*;
use crate::app_runtime::{WorkspaceDto, WorkspaceKind};

/// 通过真实 Workspace handle 测试 host 的 capability fence，避免只验证脱离路径语义的 mock id。
fn configured_workspace(
    workspace_id: &str,
    identity: RuntimeIdentity,
    handle: WorkspaceHandle,
) -> ConfiguredWorkspace {
    ConfiguredWorkspace::from_projection(
        WorkspaceDto {
            workspace_id: workspace_id.to_owned(),
            root: handle.root_path().to_string_lossy().into_owned(),
            display_name: "Project".to_owned(),
            trust: "untrusted".to_owned(),
            revision: 1,
            kind: WorkspaceKind::Project,
        },
        handle,
        identity,
    )
}

/// 创建每项测试独立目录；不同 server instance 可复用同一物理根以隔离测试变量。
fn temporary_workspace() -> (PathBuf, WorkspaceHandle) {
    let root = std::env::temp_dir().join(format!("ja-host-identity-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&root).expect("temporary workspace should be created");
    let registry = crate::workspace::WorkspaceRegistry::default();
    let info = registry
        .register(&root)
        .expect("temporary workspace should pass native admission");
    let handle = registry
        .get(info.id)
        .expect("registered workspace should resolve");
    (root, handle)
}

/// 同 generation 的 serverInstanceId 变化仍是新 owner：撤销旧权限，但保留尚未提交给 Java 的用户选择。
#[test]
fn same_generation_instance_change_revokes_bindings_and_preserves_pending_project() {
    let (root, handle) = temporary_workspace();
    let first = RuntimeIdentity::new("srv_instance_a".to_owned(), 7).expect("identity A");
    let second = RuntimeIdentity::new("srv_instance_b".to_owned(), 7).expect("identity B");
    let mut bindings = WorkspaceBindings::default();
    bindings.pending_project = Some(PendingProjectWorkspace::new(
        "Pending project".to_owned(),
        handle.clone(),
    ));
    bindings.synchronize_identity(first.clone());
    bindings
        .insert_binding(configured_workspace("ws_old", first, handle.clone()))
        .expect("current identity binding should be accepted");
    bindings.active_workspace_id = Some("ws_old".to_owned());

    bindings.synchronize_identity(second);

    assert!(bindings.by_id.is_empty(), "old workspace ids must be revoked");
    assert_eq!(bindings.active_workspace_id, None);
    assert!(bindings.pending_project.is_some());
    drop(bindings);
    drop(handle);
    std::fs::remove_dir_all(root).expect("temporary workspace should be removed");
}

/// Java activate 回包可能晚于服务重启；注册这一提交点必须拒绝旧 pair，不能让旧 ID 再次获得原生句柄。
#[test]
fn late_activation_projection_from_old_instance_cannot_rebind_stale_workspace_id() {
    let (root, handle) = temporary_workspace();
    let first = RuntimeIdentity::new("srv_instance_a".to_owned(), 7).expect("identity A");
    let second = RuntimeIdentity::new("srv_instance_b".to_owned(), 7).expect("identity B");
    let mut bindings = WorkspaceBindings::default();
    bindings.synchronize_identity(first.clone());
    bindings.active_workspace_id = Some("ws_old".to_owned());

    // 模拟 activate RPC 尚未返回期间观察到 B；旧回包随后抵达同一 registration commit。
    bindings.synchronize_identity(second);
    let late_projection = configured_workspace("ws_old", first, handle.clone());

    assert!(bindings.insert_binding(late_projection).is_err());
    assert!(!bindings.by_id.contains_key("ws_old"));
    assert_eq!(bindings.active_workspace_id, None);
    drop(bindings);
    drop(handle);
    std::fs::remove_dir_all(root).expect("temporary workspace should be removed");
}
