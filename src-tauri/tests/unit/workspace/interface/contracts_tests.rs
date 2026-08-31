// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::workspace::{EntryKind, OpenWithTarget};

/// Mutation DTO 必须拒绝未知字段，避免前端误以为未实现选项已经生效。
#[test]
fn mutation_input_rejects_unknown_fields() {
    let value = serde_json::json!({
        "workspaceId": "ws_test",
        "relativePath": "a.txt",
        "kind": "file",
        "expectedRevision": null,
        "mutationId": "mutation-1",
        "content": null,
        "overwrite": true
    });
    assert!(serde_json::from_value::<WorkspaceCreateEntryInput>(value).is_err());
}

/// Watch event 只允许 camelCase 相对路径和 revision，不产生原生路径字段。
#[test]
fn watch_event_projection_is_path_redacted() {
    let value = serde_json::to_value(WorkspaceChangedEventDto {
        relative_path: "src/main.rs".to_owned(),
        generation: 3,
        revision: None,
        requires_rescan: true,
    })
    .expect("serialize watch event");
    assert_eq!(value["relativePath"], "src/main.rs");
    assert_eq!(value["requiresRescan"], true);
    assert!(value.get("absolutePath").is_none());
}

/// Open acknowledgement 保持 target 闭集和 camelCase，相对路径之外不泄露 launch 细节。
#[test]
fn open_result_has_stable_wire_shape() {
    let value = serde_json::to_value(WorkspaceOpenResultDto {
        opened: true,
        target: OpenWithTarget::Vscode,
        relative_path: "src/main.rs".to_owned(),
        entry_kind: EntryKind::File,
    })
    .expect("serialize open result");
    assert_eq!(value["target"], "vscode");
    assert_eq!(value["relativePath"], "src/main.rs");
    assert!(value.get("program").is_none());
    assert!(value.get("args").is_none());
}
