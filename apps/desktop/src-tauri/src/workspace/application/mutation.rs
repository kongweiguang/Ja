// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::workspace::WorkspaceError;
use crate::workspace::domain::{
    CreateEntryCommand, CreateEntryResult, DropImportCommand, DropImportResult, FileSaveResult,
    MoveEntryCommand, MoveEntryResult, SaveFileCommand, TrashCommitCommand, TrashCommitResult,
    TrashPrepareCommand, TrashPrepareResult,
};

/// 窄事务 port 由 application 主动驱动，锁、CAS 与平台 IO 的实现细节仍留在 infrastructure。
pub(crate) trait WorkspaceMutationTransaction {
    type Output;

    /// 有序锁必须包围幂等准入、CAS、staging 与 commit，避免多个阶段各自取得不同锁快照。
    fn with_ordered_lock<R>(
        &mut self,
        operation: impl FnOnce(&mut Self) -> Result<R, WorkspaceError>,
    ) -> Result<R, WorkspaceError>;

    /// Mutation id 在持锁后消费，失败重试不能绕过同一 canonical endpoint 的线性化边界。
    fn admit_idempotency(&mut self) -> Result<(), WorkspaceError>;

    /// CAS 使用基础设施提供的物理证据，但调用时机由 application 固定在任何副作用之前。
    fn verify_cas(&mut self) -> Result<(), WorkspaceError>;

    /// Stage 创建提交所需候选或计划，不能提前发布最终路径或跨平台副作用。
    fn stage(&mut self) -> Result<(), WorkspaceError>;

    /// Commit 是唯一可见提交点；成功后事务不能再次 commit。
    fn commit(&mut self) -> Result<Self::Output, WorkspaceError>;

    /// 普通失败必须完成补偿或确认没有副作用，才能把原错误交还调用方。
    fn rollback(&mut self) -> Result<(), WorkspaceError>;

    /// 无法证明补偿完成时执行恢复协议，并最终返回 RecoveryRequired 阻止盲目重试。
    fn recover(&mut self) -> Result<(), WorkspaceError>;
}

/// Create port 只负责创建事务的 admission，不再拥有整个 mutation 用例。
pub(crate) trait WorkspaceCreatePort {
    type Transaction: WorkspaceMutationTransaction<Output = CreateEntryResult>;

    fn begin_create(
        &self,
        command: CreateEntryCommand,
    ) -> Result<Self::Transaction, WorkspaceError>;
}

/// Save port 只负责构造绑定 expected revision 的事务。
pub(crate) trait WorkspaceSavePort {
    type Transaction: WorkspaceMutationTransaction<Output = FileSaveResult>;

    fn begin_save(&self, command: SaveFileCommand) -> Result<Self::Transaction, WorkspaceError>;
}

/// Move port 在 admission 时确定两个 canonical endpoint，后续统一按排序后的集合加锁。
pub(crate) trait WorkspaceMovePort {
    type Transaction: WorkspaceMutationTransaction<Output = MoveEntryResult>;

    fn begin_move(&self, command: MoveEntryCommand) -> Result<Self::Transaction, WorkspaceError>;
}

/// Trash prepare 与 commit 各有独立事务，防止复用 mutation id 或提前执行系统回收站。
pub(crate) trait WorkspaceTrashPort {
    type PrepareTransaction: WorkspaceMutationTransaction<Output = TrashPrepareResult>;
    type CommitTransaction: WorkspaceMutationTransaction<Output = TrashCommitResult>;

    fn begin_trash_prepare(
        &self,
        command: TrashPrepareCommand,
    ) -> Result<Self::PrepareTransaction, WorkspaceError>;

    fn begin_trash_commit(
        &self,
        command: TrashCommitCommand,
    ) -> Result<Self::CommitTransaction, WorkspaceError>;
}

/// Drop port 在 admission 时解析 opaque capability 和所有目标锁，不把 native path 暴露给 application。
pub(crate) trait WorkspaceDropPort {
    type Transaction: WorkspaceMutationTransaction<Output = DropImportResult>;

    fn begin_drop(&self, command: DropImportCommand) -> Result<Self::Transaction, WorkspaceError>;
}

/// 主动执行固定事务序列；任一步失败都由 application 决定 rollback 或 recovery。
fn execute_transaction<T>(
    transaction: &mut impl WorkspaceMutationTransaction<Output = T>,
) -> Result<T, WorkspaceError> {
    transaction.with_ordered_lock(|transaction| {
        transaction.admit_idempotency()?;
        transaction.verify_cas()?;
        if let Err(error) = transaction.stage() {
            return match transaction.rollback() {
                Ok(()) => Err(error),
                Err(_) => {
                    transaction.recover()?;
                    Err(WorkspaceError::RecoveryRequired)
                }
            };
        }
        match transaction.commit() {
            Ok(output) => Ok(output),
            Err(WorkspaceError::RecoveryRequired) => {
                transaction.recover()?;
                Err(WorkspaceError::RecoveryRequired)
            }
            Err(error) => match transaction.rollback() {
                Ok(()) => Err(error),
                Err(_) => {
                    transaction.recover()?;
                    Err(WorkspaceError::RecoveryRequired)
                }
            },
        }
    })
}

/// Workspace mutation service 是 begin→lock→CAS→stage→commit→补偿协议的唯一 owner。
pub(crate) struct WorkspaceMutationService<M> {
    mutation: M,
}

impl<M> WorkspaceMutationService<M> {
    /// 注入唯一事务工厂；跨域 Last Turn 投影已经删除，不再保留空通知端口。
    pub(crate) fn new(mutation: M) -> Self {
        Self { mutation }
    }
}

impl<M: WorkspaceCreatePort> WorkspaceMutationService<M> {
    /// Create 由 application 主动驱动完整事务并只返回 commit 后事实。
    pub(crate) fn create_entry(
        &self,
        command: CreateEntryCommand,
    ) -> Result<CreateEntryResult, WorkspaceError> {
        let mut transaction = self.mutation.begin_create(command)?;
        execute_transaction(&mut transaction)
    }
}

impl<M: WorkspaceSavePort> WorkspaceMutationService<M> {
    /// Save 的 CAS 与 staging 均在同一有序锁内，失败补偿完成前不会返回成功。
    pub(crate) fn save_file(
        &self,
        command: SaveFileCommand,
    ) -> Result<FileSaveResult, WorkspaceError> {
        let mut transaction = self.mutation.begin_save(command)?;
        execute_transaction(&mut transaction)
    }
}

impl<M: WorkspaceMovePort> WorkspaceMutationService<M> {
    /// Move 在 begin 后一次锁定两个端点，no-replace 平台提交失败会进入补偿或恢复。
    pub(crate) fn move_entry(
        &self,
        command: MoveEntryCommand,
    ) -> Result<MoveEntryResult, WorkspaceError> {
        let mut transaction = self.mutation.begin_move(command)?;
        execute_transaction(&mut transaction)
    }
}

impl<M: WorkspaceTrashPort> WorkspaceMutationService<M> {
    /// Prepare 只提交短期 capability，不改变磁盘。
    pub(crate) fn prepare_trash(
        &self,
        command: TrashPrepareCommand,
    ) -> Result<TrashPrepareResult, WorkspaceError> {
        let mut transaction = self.mutation.begin_trash_prepare(command)?;
        execute_transaction(&mut transaction)
    }

    /// Commit 重新走锁、CAS 与 token stage 后执行系统回收站，成功后才失效投影。
    pub(crate) fn commit_trash(
        &self,
        command: TrashCommitCommand,
    ) -> Result<TrashCommitResult, WorkspaceError> {
        let mut transaction = self.mutation.begin_trash_commit(command)?;
        execute_transaction(&mut transaction)
    }
}

impl<M: WorkspaceDropPort> WorkspaceMutationService<M> {
    /// Drop 的 token 消费、目标 CAS、staging 与发布都位于 application 驱动的同一锁事务。
    pub(crate) fn import_drop(
        &self,
        command: DropImportCommand,
    ) -> Result<DropImportResult, WorkspaceError> {
        let mut transaction = self.mutation.begin_drop(command)?;
        execute_transaction(&mut transaction)
    }
}
