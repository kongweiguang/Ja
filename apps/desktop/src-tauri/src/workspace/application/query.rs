// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::workspace::{FileContent, SearchHit, TreePage, WorkspaceError};

/// 分页请求属于查询用例而非文件系统实现，使 application 可以在 fake port 下验证快照语义。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TreePageRequest {
    pub relative_path: String,
    pub cursor: Option<String>,
    pub page_size: Option<usize>,
    pub snapshot_token: Option<String>,
}

/// 查询端口只表达 Workspace 用例所需能力，application 不依赖文件系统 reader 的实现。
pub(crate) trait WorkspaceQueryPort {
    fn tree(&self, request: &TreePageRequest) -> Result<TreePage, WorkspaceError>;
    fn read_file(&self, relative_path: &str) -> Result<FileContent, WorkspaceError>;
    fn search(
        &self,
        relative_path: &str,
        query: &str,
    ) -> Result<WorkspaceSearchResult, WorkspaceError>;
}

/// 搜索结果属于 application 返回值，显式保留预算统计，防止 interface 把截断结果包装成完整事实。
pub(crate) struct WorkspaceSearchResult {
    pub hits: Vec<SearchHit>,
    pub truncated: bool,
    pub scanned_entries: usize,
    pub skipped_files: usize,
}

/// Workspace 查询服务是读取用例的唯一入口；它只编排端口，不复制 containment 或 IO 策略。
pub(crate) struct WorkspaceQueryService<P> {
    port: P,
}

impl<P: WorkspaceQueryPort> WorkspaceQueryService<P> {
    /// 注入一个已绑定 Workspace handle 的端口，保证一次用例不会跨 Workspace 切换根目录。
    pub(crate) fn new(port: P) -> Self {
        Self { port }
    }

    /// 读取单个目录分页；分页陈旧性与 containment 仍由基础设施的唯一实现负责。
    pub(crate) fn tree(&self, request: &TreePageRequest) -> Result<TreePage, WorkspaceError> {
        self.port.tree(request)
    }

    /// 读取单个受限文件；application 不解释编码或二进制分类，避免形成第二套内容规则。
    pub(crate) fn read_file(&self, relative_path: &str) -> Result<FileContent, WorkspaceError> {
        self.port.read_file(relative_path)
    }

    /// 执行字面量搜索并保留预算结果，使 interface 只承担 DTO 投影。
    pub(crate) fn search(
        &self,
        relative_path: &str,
        query: &str,
    ) -> Result<WorkspaceSearchResult, WorkspaceError> {
        self.port.search(relative_path, query)
    }
}
