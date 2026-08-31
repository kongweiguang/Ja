// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::workspace::domain::Revision;

/// application 只编排领域端口，不拥有原生序列化或文件系统细节。
fn accept_revision(revision: Revision) -> Revision {
    revision
}
