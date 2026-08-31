// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::workspace::WorkspaceServiceError;

fn reject(error: WorkspaceServiceError) -> WorkspaceServiceError {
    error
}
