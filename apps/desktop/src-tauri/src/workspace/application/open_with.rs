// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::workspace::domain::{OpenError, OpenResult, OpenTargetAvailability, OpenWithTarget};

/// Open port 只暴露闭集发现与一次受控 launch，不允许 application 传 executable 或 argv。
pub(crate) trait WorkspaceOpenPort {
    fn targets(&self) -> Result<Vec<OpenTargetAvailability>, OpenError>;
    fn open(&self, target: OpenWithTarget, relative_path: String) -> Result<OpenResult, OpenError>;
}

/// Workspace open service 是外部应用发现与启动的唯一用例入口。
pub(crate) struct WorkspaceOpenService<P> {
    port: P,
}

impl<P: WorkspaceOpenPort> WorkspaceOpenService<P> {
    /// 注入绑定单个 Workspace 的 port，保证 executable discovery 期间不会切换受信根。
    pub(crate) fn new(port: P) -> Self {
        Self { port }
    }

    /// 查询闭集可用性；返回值禁止携带安装路径，interface 只做 DTO 投影。
    pub(crate) fn targets(&self) -> Result<Vec<OpenTargetAvailability>, OpenError> {
        self.port.targets()
    }

    /// 打开前后的 containment 复核由同一原生端口实现，避免 application 创建第二套路径规则。
    pub(crate) fn open(
        &self,
        target: OpenWithTarget,
        relative_path: String,
    ) -> Result<OpenResult, OpenError> {
        self.port.open(target, relative_path)
    }
}
