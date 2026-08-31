// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::workspace::WorkspaceDto;

/// 经 owner façade 重导出的 interface DTO 仍不能进入 application。
fn accept_workspace(workspace: WorkspaceDto) -> WorkspaceDto {
    workspace
}
