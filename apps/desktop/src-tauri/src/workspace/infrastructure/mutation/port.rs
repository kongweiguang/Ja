// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::workspace::application::{
    WorkspaceCreatePort, WorkspaceDropPort, WorkspaceMovePort, WorkspaceMutationTransaction,
    WorkspaceSavePort, WorkspaceTrashPort,
};

/// 原生 mutation port 固定绑定一个 Workspace handle，避免切换期间重新解析根目录。
pub(crate) struct NativeWorkspaceMutationPort {
    workspace: WorkspaceHandle,
}

impl NativeWorkspaceMutationPort {
    /// 组合层完成 Workspace admission 后只注入不可变 handle，基础设施不感知 RuntimeHost。
    pub(crate) fn new(workspace: WorkspaceHandle) -> Self {
        Self { workspace }
    }
}

/// 通用原生事务保存 command 与真实 prepared state；application 决定阶段顺序，
/// infrastructure 回调只负责创建、发布、补偿和保留各用例的物理证据。
pub(crate) struct NativeMutationTransaction<C, S, O> {
    workspace: WorkspaceHandle,
    command: C,
    prepared: Option<S>,
    committed: bool,
    mutation_id: String,
    lock_paths: Vec<PathBuf>,
    stages: NativeMutationStages<C, S, O>,
}

/// 用例专属阶段函数作为一个不可变策略值注入事务，既收窄构造参数，也保证 begin 后不能替换任一阶段 owner。
struct NativeMutationStages<C, S, O> {
    verify: fn(&WorkspaceHandle, &C) -> Result<(), WorkspaceError>,
    stage: fn(&WorkspaceHandle, &C) -> Result<S, WorkspaceError>,
    commit: fn(&WorkspaceHandle, &C, &mut S) -> Result<O, WorkspaceError>,
    rollback: fn(&WorkspaceHandle, &mut S) -> Result<(), WorkspaceError>,
    recover: fn(&WorkspaceHandle, &S) -> Result<(), WorkspaceError>,
}

impl<C, S, O> NativeMutationTransaction<C, S, O> {
    /// Begin 固定 canonical 锁集合与全部物理阶段回调，后续不能更换 Workspace、端点或补偿策略。
    fn new(
        workspace: WorkspaceHandle,
        command: C,
        mutation_id: String,
        lock_paths: Vec<PathBuf>,
        stages: NativeMutationStages<C, S, O>,
    ) -> Self {
        Self {
            workspace,
            command,
            prepared: None,
            committed: false,
            mutation_id,
            lock_paths,
            stages,
        }
    }
}

impl<C, S, O> WorkspaceMutationTransaction for NativeMutationTransaction<C, S, O> {
    type Output = O;

    /// 全局队列对 canonical endpoint 排序并一次取得；Workspace 恢复门禁在锁前后都复核，
    /// 事务若返回 RecoveryRequired 则在释放路径锁前锁存到全部 handle clone。
    fn with_ordered_lock<R>(
        &mut self,
        operation: impl FnOnce(&mut Self) -> Result<R, WorkspaceError>,
    ) -> Result<R, WorkspaceError> {
        self.workspace.ensure_mutation_available()?;
        let paths = self.lock_paths.clone();
        with_workspace_mutation_paths(&paths, || {
            self.workspace.ensure_mutation_available()?;
            let result = operation(self);
            if matches!(&result, Err(WorkspaceError::RecoveryRequired)) {
                self.workspace.mark_mutation_recovery_required();
            }
            result
        })
    }

    /// 幂等键在锁内只消费一次，commit helper 不再维护第二个 ledger 入口。
    fn admit_idempotency(&mut self) -> Result<(), WorkspaceError> {
        reserve_mutation(self.workspace.id(), &self.mutation_id)
    }

    /// 物理 CAS 由用例函数实现，application 只拥有调用顺序。
    fn verify_cas(&mut self) -> Result<(), WorkspaceError> {
        if self.prepared.is_some() || self.committed {
            return Err(WorkspaceError::RecoveryRequired);
        }
        (self.stages.verify)(&self.workspace, &self.command)
    }

    /// Stage 必须创建真实候选、marker、snapshot 或 staging；重复 stage 会破坏唯一补偿 owner，因而失败关闭。
    fn stage(&mut self) -> Result<(), WorkspaceError> {
        if self.prepared.is_some() || self.committed {
            return Err(WorkspaceError::RecoveryRequired);
        }
        self.prepared = Some((self.stages.stage)(&self.workspace, &self.command)?);
        Ok(())
    }

    /// Commit 只能发布已经存在的 prepared state；状态保留到结果确定，错误时由 application 调用 rollback/recover。
    fn commit(&mut self) -> Result<Self::Output, WorkspaceError> {
        if self.committed {
            return Err(WorkspaceError::RecoveryRequired);
        }
        let prepared = self
            .prepared
            .as_mut()
            .ok_or(WorkspaceError::RecoveryRequired)?;
        let output = (self.stages.commit)(&self.workspace, &self.command, prepared)?;
        self.committed = true;
        Ok(output)
    }

    /// Rollback 调用用例专属补偿并仅在成功后丢弃 prepared state；stage 自身保证错误返回前不留残余，
    /// 因此尚未形成 prepared state 时视为已完成补偿，不能把普通输入或 IO 错误错误升级为恢复态。
    fn rollback(&mut self) -> Result<(), WorkspaceError> {
        if self.committed {
            return Err(WorkspaceError::RecoveryRequired);
        }
        let Some(prepared) = self.prepared.as_mut() else {
            return Ok(());
        };
        (self.stages.rollback)(&self.workspace, prepared)?;
        self.prepared.take();
        Ok(())
    }

    /// Recovery 在核验物理证据前先锁存 Workspace 级门禁，避免保留现场期间有新 mutation 进入。
    /// 没有 prepared state 时绝不以空成功冒充恢复。
    fn recover(&mut self) -> Result<(), WorkspaceError> {
        self.workspace.mark_mutation_recovery_required();
        let prepared = self
            .prepared
            .as_ref()
            .ok_or(WorkspaceError::RecoveryRequired)?;
        (self.stages.recover)(&self.workspace, prepared)
    }
}

/// Create CAS 验证目标仍不存在，避免 stage 后覆盖并发创建的条目。
fn verify_create(
    workspace: &WorkspaceHandle,
    command: &CreateEntryCommand,
) -> Result<(), WorkspaceError> {
    if command.expected_revision.is_some() {
        return Err(WorkspaceError::RevisionConflict);
    }
    let (_, target) = workspace.resolve_parent(command.relative_path.as_str())?;
    require_destination_absent(&target)
}

/// Create stage 的真实候选；文件在同目录落盘，目录保留固定 target，rollback 只清理本事务生成的节点。
pub(crate) struct PreparedCreate {
    target: PathBuf,
    candidate: Option<PathBuf>,
    candidate_evidence: Option<CapturedPathEvidence>,
    published: bool,
}

/// Create stage 在同目录写入文件候选；目录没有可移植的匿名目录原语，因此只固定 target，commit 使用 create_dir no-replace。
fn stage_create(
    workspace: &WorkspaceHandle,
    command: &CreateEntryCommand,
) -> Result<PreparedCreate, WorkspaceError> {
    let (parent, target) = workspace.resolve_parent(command.relative_path.as_str())?;
    workspace.verify_resolved(&parent, Some(true))?;
    require_destination_absent(&target)?;
    let (candidate, candidate_evidence) = match command.kind {
        CreateEntryKind::File => {
            let content = command.content.as_ref().cloned().unwrap_or(TextContent {
                text: String::new(),
                encoding: TextEncoding::Utf8,
                line_ending: LineEnding::Lf,
            });
            let candidate = write_temp(&parent.path, &encode_text(&content)?)?;
            let evidence = captured_path_evidence(&candidate)?;
            (Some(candidate), Some(evidence))
        }
        CreateEntryKind::Directory => (None, None),
    };
    Ok(PreparedCreate {
        target,
        candidate,
        candidate_evidence,
        published: false,
    })
}

/// Create commit 只发布 staged candidate 或执行原子 create_dir，并在返回前读取权威 revision。
fn commit_create(
    workspace: &WorkspaceHandle,
    command: &CreateEntryCommand,
    prepared: &mut PreparedCreate,
) -> Result<CreateEntryResult, WorkspaceError> {
    let (parent, target) = workspace.resolve_parent(command.relative_path.as_str())?;
    if target != prepared.target {
        return Err(WorkspaceError::PathChanged);
    }
    workspace.verify_resolved(&parent, Some(true))?;
    require_destination_absent(&target)?;
    match command.kind {
        CreateEntryKind::Directory => {
            fs::create_dir(&target).map_err(|error| WorkspaceError::io("create_dir", error))?;
        }
        CreateEntryKind::File => {
            let candidate = prepared
                .candidate
                .as_ref()
                .ok_or(WorkspaceError::RecoveryRequired)?;
            atomic_create(candidate, &target)?;
        }
    }
    prepared.published = true;
    workspace.verify_resolved(&parent, Some(true))?;
    let revision = current_metadata(workspace, command.relative_path.as_str())?.revision;
    if let Some(expected) = prepared.candidate_evidence.as_ref()
        && captured_path_evidence(&target).map_or(true, |actual| actual != *expected)
    {
        return Err(WorkspaceError::RecoveryRequired);
    }
    Ok(CreateEntryResult {
        relative_path: command.relative_path.as_str().to_owned(),
        kind: revision.kind(),
        revision,
    })
}

/// Create rollback 删除未发布候选；若目标已发布，只在类型或候选身份仍匹配时删除该事务创建的节点。
fn rollback_create(
    _: &WorkspaceHandle,
    prepared: &mut PreparedCreate,
) -> Result<(), WorkspaceError> {
    if prepared.published {
        if let Some(expected) = prepared.candidate_evidence.as_ref()
            && captured_path_evidence(&prepared.target).map_or(true, |actual| actual != *expected)
        {
            return Err(WorkspaceError::RecoveryRequired);
        }
        let metadata =
            fs::symlink_metadata(&prepared.target).map_err(|_| WorkspaceError::RecoveryRequired)?;
        if metadata.is_dir() {
            fs::remove_dir(&prepared.target).map_err(|_| WorkspaceError::RecoveryRequired)?;
        } else {
            fs::remove_file(&prepared.target).map_err(|_| WorkspaceError::RecoveryRequired)?;
        }
        prepared.published = false;
    }
    if let Some(candidate) = prepared.candidate.take() {
        match fs::remove_file(candidate) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(WorkspaceError::RecoveryRequired),
        }
    }
    Ok(())
}

/// Create recovery 只确认目标或候选仍存在，避免在身份不确定时自动删除用户节点。
fn preserve_create(_: &WorkspaceHandle, prepared: &PreparedCreate) -> Result<(), WorkspaceError> {
    if prepared.target.exists()
        || prepared
            .candidate
            .as_ref()
            .is_some_and(|path| path.exists())
    {
        Ok(())
    } else {
        Err(WorkspaceError::RecoveryRequired)
    }
}

/// Save CAS 使用当前 metadata 与完整 SHA-256 revision，拒绝无 hash 的编辑证据。
fn verify_save(
    workspace: &WorkspaceHandle,
    command: &SaveFileCommand,
) -> Result<(), WorkspaceError> {
    let current = current_metadata(workspace, command.relative_path.as_str())?;
    require_revision(&command.expected_revision, &current.revision)
}

/// Save stage 把完整文本编码为同目录候选，并返回可核验的物理恢复状态。
fn stage_save(
    workspace: &WorkspaceHandle,
    command: &SaveFileCommand,
) -> Result<PreparedSave, WorkspaceError> {
    prepare_save(
        workspace,
        command.relative_path.as_str(),
        &command.expected_revision,
        &encode_text(&command.content)?,
    )
}

/// Save commit 只发布 stage 已落盘的候选，不再重新编码或创建第二候选。
fn commit_save(
    workspace: &WorkspaceHandle,
    command: &SaveFileCommand,
    prepared: &mut PreparedSave,
) -> Result<FileSaveResult, WorkspaceError> {
    let revision = commit_prepared_save(workspace, prepared)?;
    Ok(FileSaveResult {
        relative_path: command.relative_path.as_str().to_owned(),
        revision,
    })
}

/// Move CAS 同时确认 source revision 与 destination absence，两个端点已由 begin 固定。
fn verify_move(
    workspace: &WorkspaceHandle,
    command: &MoveEntryCommand,
) -> Result<(), WorkspaceError> {
    let source = current_metadata(workspace, command.from_relative_path.as_str())?;
    require_revision(&command.expected_revision, &source.revision)?;
    let (_, destination) = workspace.resolve_parent(command.to_relative_path.as_str())?;
    require_destination_absent(&destination)
}

/// Move stage 固定物理身份并创建同步 recovery marker，commit 不再重新构造计划。
fn stage_move(
    workspace: &WorkspaceHandle,
    command: &MoveEntryCommand,
) -> Result<PreparedMove, WorkspaceError> {
    prepare_move(
        workspace,
        command.from_relative_path.as_str(),
        command.to_relative_path.as_str(),
        &command.expected_revision,
    )
}

/// Move commit 发布已准备的源身份，并以 command 只构造稳定 application 输出。
fn commit_move(
    workspace: &WorkspaceHandle,
    command: &MoveEntryCommand,
    prepared: &mut PreparedMove,
) -> Result<MoveEntryResult, WorkspaceError> {
    let revision = commit_prepared_move(workspace, prepared)?;
    Ok(MoveEntryResult {
        from_relative_path: command.from_relative_path.as_str().to_owned(),
        to_relative_path: command.to_relative_path.as_str().to_owned(),
        revision,
    })
}

/// Trash prepare/commit 都必须在各自事务中重新比较目标 revision。
fn verify_trash_prepare(
    workspace: &WorkspaceHandle,
    command: &TrashPrepareCommand,
) -> Result<(), WorkspaceError> {
    let current = current_metadata(workspace, command.relative_path.as_str())?;
    require_revision(&command.expected_revision, &current.revision)
}

/// Trash prepare stage 保存一次完整子树快照；commit 直接发布同一 snapshot，不再重新扫描。
fn stage_trash_prepare(
    workspace: &WorkspaceHandle,
    command: &TrashPrepareCommand,
) -> Result<PreparedTrashPlan, WorkspaceError> {
    prepare_trash_plan(
        workspace,
        command.relative_path.as_str().to_owned(),
        &command.expected_revision,
    )
}

/// Trash prepare commit 只把 staged snapshot 放入有界 token 表。
fn commit_trash_prepare(
    _: &WorkspaceHandle,
    _: &TrashPrepareCommand,
    prepared: &mut PreparedTrashPlan,
) -> Result<TrashPrepareResult, WorkspaceError> {
    commit_prepared_trash_plan(prepared)
}

/// 未发布的 Trash snapshot 只存在于事务内存；清空其 entry 释放真实预算并防止误复用。
fn rollback_trash_prepare(
    _: &WorkspaceHandle,
    prepared: &mut PreparedTrashPlan,
) -> Result<(), WorkspaceError> {
    discard_prepared_trash_plan(prepared)
}

/// Trash prepare 没有平台副作用；若进入 recovery，必须仍持有完整 snapshot 证据。
fn preserve_trash_prepare(
    _: &WorkspaceHandle,
    prepared: &PreparedTrashPlan,
) -> Result<(), WorkspaceError> {
    preserve_prepared_trash_plan(prepared)
}

/// Trash commit 在消费 token 前先复核调用方 CAS，token 快照仍在 commit helper 内再次验证。
fn verify_trash_commit(
    workspace: &WorkspaceHandle,
    command: &TrashCommitCommand,
) -> Result<(), WorkspaceError> {
    let current = current_metadata(workspace, command.relative_path.as_str())?;
    require_revision(&command.expected_revision, &current.revision)
}

/// Trash commit stage 在锁内消费一次性 capability，并把完整计划持有到 commit/rollback 完成。
fn stage_trash_commit(
    workspace: &WorkspaceHandle,
    command: &TrashCommitCommand,
) -> Result<PreparedTrashCommit, WorkspaceError> {
    prepare_trash_commit(workspace, command)
}

/// Trash commit 执行 staged plan，不再二次消费 token。
fn commit_trash_stage(
    workspace: &WorkspaceHandle,
    _: &TrashCommitCommand,
    prepared: &mut PreparedTrashCommit,
) -> Result<TrashCommitResult, WorkspaceError> {
    commit_prepared_trash(workspace, prepared)
}

/// Drop CAS 验证 destination directory revision，source capability 由 stage 独立复核。
fn verify_drop(
    workspace: &WorkspaceHandle,
    command: &DropImportCommand,
) -> Result<(), WorkspaceError> {
    let current = current_metadata(workspace, command.destination_relative_path.as_str())?;
    require_revision(&command.expected_revision, &current.revision)?;
    if current.kind != EntryKind::Directory {
        return Err(WorkspaceError::NotDirectory);
    }
    Ok(())
}

/// Drop stage 消费 capability、构建 manifest 并完成真实 staging；commit 只原子发布。
fn stage_drop(
    workspace: &WorkspaceHandle,
    command: &DropImportCommand,
) -> Result<PreparedDropImport, WorkspaceError> {
    prepare_drop_import(workspace, command)
}

/// Drop commit 发布 prepared staging，并返回 stage 已固定的相对路径投影。
fn commit_drop_stage(
    workspace: &WorkspaceHandle,
    _: &DropImportCommand,
    prepared: &mut PreparedDropImport,
) -> Result<DropImportResult, WorkspaceError> {
    commit_prepared_drop(workspace, prepared)
}

impl WorkspaceCreatePort for NativeWorkspaceMutationPort {
    type Transaction =
        NativeMutationTransaction<CreateEntryCommand, PreparedCreate, CreateEntryResult>;

    /// Begin 解析唯一目标锁，路径 containment 在 application 执行幂等准入前完成。
    fn begin_create(
        &self,
        command: CreateEntryCommand,
    ) -> Result<Self::Transaction, WorkspaceError> {
        let target = resolve_relative(self.workspace.root_path(), command.relative_path.as_str())?;
        let mutation_id = command.mutation_id.as_str().to_owned();
        Ok(NativeMutationTransaction::new(
            self.workspace.clone(),
            command,
            mutation_id,
            vec![target],
            NativeMutationStages {
                verify: verify_create,
                stage: stage_create,
                commit: commit_create,
                rollback: rollback_create,
                recover: preserve_create,
            },
        ))
    }
}

impl WorkspaceSavePort for NativeWorkspaceMutationPort {
    type Transaction = NativeMutationTransaction<SaveFileCommand, PreparedSave, FileSaveResult>;

    /// Begin 固定保存目标；后续 CAS、candidate 与 commit 全部在同一锁闭包中执行。
    fn begin_save(&self, command: SaveFileCommand) -> Result<Self::Transaction, WorkspaceError> {
        let target = resolve_relative(self.workspace.root_path(), command.relative_path.as_str())?;
        let mutation_id = command.mutation_id.as_str().to_owned();
        Ok(NativeMutationTransaction::new(
            self.workspace.clone(),
            command,
            mutation_id,
            vec![target],
            NativeMutationStages {
                verify: verify_save,
                stage: stage_save,
                commit: commit_save,
                rollback: rollback_prepared_save,
                recover: |_, prepared| preserve_prepared_save(prepared),
            },
        ))
    }
}

impl WorkspaceMovePort for NativeWorkspaceMutationPort {
    type Transaction = NativeMutationTransaction<MoveEntryCommand, PreparedMove, MoveEntryResult>;

    /// Begin 一次固定 source 与 destination 两个端点，队列在执行时排序避免反向死锁。
    fn begin_move(&self, command: MoveEntryCommand) -> Result<Self::Transaction, WorkspaceError> {
        let source = self
            .workspace
            .resolve_guard(command.from_relative_path.as_str(), None)?
            .path;
        let destination = resolve_relative(
            self.workspace.root_path(),
            command.to_relative_path.as_str(),
        )?;
        let mutation_id = command.mutation_id.as_str().to_owned();
        Ok(NativeMutationTransaction::new(
            self.workspace.clone(),
            command,
            mutation_id,
            vec![source, destination],
            NativeMutationStages {
                verify: verify_move,
                stage: stage_move,
                commit: commit_move,
                rollback: rollback_prepared_move,
                recover: |_, prepared| preserve_prepared_move(prepared),
            },
        ))
    }
}

impl WorkspaceTrashPort for NativeWorkspaceMutationPort {
    type PrepareTransaction =
        NativeMutationTransaction<TrashPrepareCommand, PreparedTrashPlan, TrashPrepareResult>;
    type CommitTransaction =
        NativeMutationTransaction<TrashCommitCommand, PreparedTrashCommit, TrashCommitResult>;

    /// Prepare begin 固定目标锁，子树快照与 token 写入不会跨越锁边界。
    fn begin_trash_prepare(
        &self,
        command: TrashPrepareCommand,
    ) -> Result<Self::PrepareTransaction, WorkspaceError> {
        let target = self
            .workspace
            .resolve_guard(command.relative_path.as_str(), None)?
            .path;
        let mutation_id = command.mutation_id.as_str().to_owned();
        Ok(NativeMutationTransaction::new(
            self.workspace.clone(),
            command,
            mutation_id,
            vec![target],
            NativeMutationStages {
                verify: verify_trash_prepare,
                stage: stage_trash_prepare,
                commit: commit_trash_prepare,
                rollback: rollback_trash_prepare,
                recover: preserve_trash_prepare,
            },
        ))
    }

    /// Commit begin 重新固定目标锁，prepare 阶段捕获的路径不能直接作为平台副作用输入。
    fn begin_trash_commit(
        &self,
        command: TrashCommitCommand,
    ) -> Result<Self::CommitTransaction, WorkspaceError> {
        let target = self
            .workspace
            .resolve_guard(command.relative_path.as_str(), None)?
            .path;
        let mutation_id = command.mutation_id.as_str().to_owned();
        Ok(NativeMutationTransaction::new(
            self.workspace.clone(),
            command,
            mutation_id,
            vec![target],
            NativeMutationStages {
                verify: verify_trash_commit,
                stage: stage_trash_commit,
                commit: commit_trash_stage,
                rollback: rollback_prepared_trash,
                recover: |_, prepared| preserve_prepared_trash(prepared),
            },
        ))
    }
}

impl WorkspaceDropPort for NativeWorkspaceMutationPort {
    type Transaction =
        NativeMutationTransaction<DropImportCommand, PreparedDropImport, DropImportResult>;

    /// Begin 从未消费 capability 计算全部目标锁，真正消费仍在锁内 commit 阶段。
    fn begin_drop(&self, command: DropImportCommand) -> Result<Self::Transaction, WorkspaceError> {
        let plan = peek_native_drop_plan(command.drop_token.as_str())?;
        let destination = self
            .workspace
            .resolve_guard(command.destination_relative_path.as_str(), Some(true))?;
        let mut lock_paths = vec![destination.path];
        for source in &plan.sources {
            let name = source
                .path
                .file_name()
                .and_then(|value| value.to_str())
                .ok_or(WorkspaceError::DropTokenInvalid)?;
            let target_relative = if command.destination_relative_path.is_root() {
                name.to_owned()
            } else {
                format!("{}/{name}", command.destination_relative_path.as_str())
            };
            lock_paths.push(resolve_relative(
                self.workspace.root_path(),
                &target_relative,
            )?);
        }
        let mutation_id = command.mutation_id.as_str().to_owned();
        Ok(NativeMutationTransaction::new(
            self.workspace.clone(),
            command,
            mutation_id,
            lock_paths,
            NativeMutationStages {
                verify: verify_drop,
                stage: stage_drop,
                commit: commit_drop_stage,
                rollback: rollback_prepared_drop,
                recover: |_, prepared| preserve_prepared_drop(prepared),
            },
        ))
    }
}
