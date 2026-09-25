// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::workspace::application::WorkspaceQueryPort;
use crate::workspace::application::WorkspaceSearchResult;
use crate::workspace::{
    ContentPolicy, FileContent, FileReader, SearchPolicy, TextSearch, TreePage, TreePageRequest,
    TreePolicy, TreeReader, WorkspaceError, WorkspaceHandle,
};

/// 绑定单个原生 Workspace handle，并集中创建受限 reader/search 实现。
pub(crate) struct NativeWorkspaceQueryPort {
    workspace: WorkspaceHandle,
}

impl NativeWorkspaceQueryPort {
    /// handle 已由 RuntimeHost admission，端口只保存该不可变能力而不接收绝对路径。
    pub(crate) fn new(workspace: WorkspaceHandle) -> Self {
        Self { workspace }
    }
}

impl WorkspaceQueryPort for NativeWorkspaceQueryPort {
    /// 使用统一 TreePolicy 执行分页，containment 与快照校验仍只有 TreeReader 一份实现。
    fn tree(&self, request: &TreePageRequest) -> Result<TreePage, WorkspaceError> {
        TreeReader::new(self.workspace.clone(), TreePolicy::default()).read_page(request)
    }

    /// 使用统一 ContentPolicy 读取，避免 Tauri command 选择更宽的读取预算。
    fn read_file(&self, relative_path: &str) -> Result<FileContent, WorkspaceError> {
        FileReader::new(self.workspace.clone(), ContentPolicy::default()).read(relative_path)
    }

    /// 将基础设施统计投影成 application 结果，搜索预算和安全遍历仍由 TextSearch 独占。
    fn search(
        &self,
        relative_path: &str,
        query: &str,
    ) -> Result<WorkspaceSearchResult, WorkspaceError> {
        let result = TextSearch::new(self.workspace.clone(), SearchPolicy::default())
            .search(relative_path, query)?;
        Ok(WorkspaceSearchResult {
            hits: result.hits,
            truncated: result.truncated,
            scanned_entries: result.scanned_entries,
            skipped_files: result.skipped_files,
        })
    }
}
