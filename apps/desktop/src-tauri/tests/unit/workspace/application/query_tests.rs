// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::{WorkspaceQueryPort, WorkspaceQueryService};
use crate::workspace::{FileContent, TreePage, TreePageRequest, WorkspaceError};

struct FailingPort;

impl WorkspaceQueryPort for FailingPort {
    /// fake 固定返回错误，用于证明 application 不吞掉基础设施失败或改写恢复语义。
    fn tree(&self, _request: &TreePageRequest) -> Result<TreePage, WorkspaceError> {
        Err(WorkspaceError::PathChanged)
    }

    /// fake 不构造无意义文件 DTO，测试只验证错误边界原样穿透。
    fn read_file(&self, _relative_path: &str) -> Result<FileContent, WorkspaceError> {
        Err(WorkspaceError::ChangedDuringRead)
    }

    /// fake 保留搜索预算失败，避免测试依赖真实文件系统。
    fn search(
        &self,
        _relative_path: &str,
        _query: &str,
    ) -> Result<crate::workspace::application::query::WorkspaceSearchResult, WorkspaceError> {
        Err(WorkspaceError::ScanDeadlineExceeded)
    }
}

/// 查询服务必须保留各端口错误，interface 才能稳定映射成不同恢复动作。
#[test]
fn query_service_preserves_port_failures() {
    let service = WorkspaceQueryService::new(FailingPort);
    let request = TreePageRequest {
        relative_path: String::new(),
        cursor: None,
        page_size: None,
        snapshot_token: None,
    };
    assert!(matches!(
        service.tree(&request),
        Err(WorkspaceError::PathChanged)
    ));
    assert!(matches!(
        service.read_file("main.rs"),
        Err(WorkspaceError::ChangedDuringRead)
    ));
    assert!(matches!(
        service.search("", "needle"),
        Err(WorkspaceError::ScanDeadlineExceeded)
    ));
}
