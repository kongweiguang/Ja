// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use uuid::Uuid;

/// 只有验证 cwd 边界后才接受 Workspace configuration；profile/config ownership 仍在 Java。
#[test]
fn configure_source_validates_workspace_boundary() {
    let root = std::env::temp_dir().join(format!("ja-config-profile-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&root).expect("workspace root");
    let source = RuntimeConfigSource::from_input(WorkspaceOpenInput {
        cwd: root.to_string_lossy().into_owned(),
        display_name: None,
        trust: "trusted".to_owned(),
    })
    .expect("valid workspace source");
    assert_eq!(
        source.root_path,
        fs::canonicalize(&root).expect("canonical root")
    );
    let _ = std::fs::remove_dir_all(root);
}
