// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::workspace::domain::{
    CreateEntryCommand, CreateEntryResult, DropImportCommand, DropImportResult, FileSaveResult,
    MoveEntryCommand, MoveEntryResult, OpenError, OpenResult, OpenTargetAvailability,
    SaveFileCommand, TextContent, TrashCommitCommand, TrashCommitResult, TrashPrepareCommand,
    TrashPrepareResult, WatchCommand, WatchRescanResult, WatchStartResult, WatchStopResult,
};
use crate::workspace::{
    EntryKind, FileRevision, LineEnding, OpenTargetUnavailableReason, OpenWithTarget, TextEncoding,
    WorkspaceError,
};
use std::sync::{Arc, Mutex};

/// Fake 模式覆盖成功、阶段失败、补偿和恢复，不用真实文件系统制造竞态。
#[derive(Clone, Copy)]
enum FakeMode {
    Committed,
    CommitFailed,
    Recovery,
    RollbackFailed,
}

/// Generic fake transaction 记录 application 实际调用顺序，而不是事后返回阶段收据。
struct FakeTransaction<T> {
    mode: FakeMode,
    output: Option<T>,
    calls: Arc<Mutex<Vec<&'static str>>>,
}

impl<T> FakeTransaction<T> {
    /// 每个事务持有自己的输出，但共享调用日志以便 port 与事务形成完整断言。
    fn new(mode: FakeMode, output: T, calls: Arc<Mutex<Vec<&'static str>>>) -> Self {
        Self {
            mode,
            output: Some(output),
            calls,
        }
    }

    /// 追加阶段名时恢复 poisoned fixture，测试 panic 不应掩盖原始阶段断言。
    fn record(&self, call: &'static str) {
        self.calls
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push(call);
    }
}

impl<T> WorkspaceMutationTransaction for FakeTransaction<T> {
    type Output = T;

    /// Fake lock 包围完整 closure，并记录释放点证明阶段没有跨锁执行。
    fn with_ordered_lock<R>(
        &mut self,
        operation: impl FnOnce(&mut Self) -> Result<R, WorkspaceError>,
    ) -> Result<R, WorkspaceError> {
        self.record("lock");
        let result = operation(self);
        self.record("unlock");
        result
    }

    /// 幂等准入必须是锁内第一阶段。
    fn admit_idempotency(&mut self) -> Result<(), WorkspaceError> {
        self.record("idempotency");
        Ok(())
    }

    /// CAS 必须发生在 stage 之前。
    fn verify_cas(&mut self) -> Result<(), WorkspaceError> {
        self.record("cas");
        Ok(())
    }

    /// Stage 只记录候选准备，不产生成功投影。
    fn stage(&mut self) -> Result<(), WorkspaceError> {
        self.record("stage");
        Ok(())
    }

    /// Commit 根据模式返回成功、普通失败或需要恢复的失败。
    fn commit(&mut self) -> Result<Self::Output, WorkspaceError> {
        self.record("commit");
        match self.mode {
            FakeMode::Committed => self.output.take().ok_or(WorkspaceError::RecoveryRequired),
            FakeMode::CommitFailed | FakeMode::RollbackFailed => {
                Err(WorkspaceError::RevisionConflict)
            }
            FakeMode::Recovery => Err(WorkspaceError::RecoveryRequired),
        }
    }

    /// 普通失败由 application 主动调用 rollback；补偿失败模式迫使其继续 recovery。
    fn rollback(&mut self) -> Result<(), WorkspaceError> {
        self.record("rollback");
        if matches!(self.mode, FakeMode::RollbackFailed) {
            Err(WorkspaceError::RecoveryRequired)
        } else {
            Ok(())
        }
    }

    /// Recovery 只记录恢复协议已被调用，最终错误仍由 application 固定为 RecoveryRequired。
    fn recover(&mut self) -> Result<(), WorkspaceError> {
        self.record("recovery");
        Ok(())
    }
}

/// Mutation fake 同时实现五个窄工厂 port，但每个 begin 都返回独立类型化事务。
struct MutationFake {
    mode: FakeMode,
    calls: Arc<Mutex<Vec<&'static str>>>,
}

impl MutationFake {
    /// 独立调用日志避免并行测试共享全局 mutation 状态。
    fn new(mode: FakeMode) -> Self {
        Self {
            mode,
            calls: Arc::new(Mutex::new(Vec::new())),
        }
    }

    /// 返回稳定日志快照，断言不持锁，避免测试自身改变事务顺序。
    fn calls(&self) -> Vec<&'static str> {
        self.calls
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// 所有 begin 通过同一 helper 记录 admission，输出仍由具体 port 构造。
    fn begin<T>(&self, output: T) -> FakeTransaction<T> {
        self.calls
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push("begin");
        FakeTransaction::new(self.mode, output, Arc::clone(&self.calls))
    }
}

impl WorkspaceCreatePort for &MutationFake {
    type Transaction = FakeTransaction<CreateEntryResult>;

    /// Create begin 回显已验证路径，测试不复制真实 containment。
    fn begin_create(
        &self,
        command: CreateEntryCommand,
    ) -> Result<Self::Transaction, WorkspaceError> {
        Ok(self.begin(CreateEntryResult {
            relative_path: command.relative_path.as_str().to_owned(),
            kind: EntryKind::File,
            revision: revision(),
        }))
    }
}

impl WorkspaceSavePort for &MutationFake {
    type Transaction = FakeTransaction<FileSaveResult>;

    /// Save begin 保留调用方 revision，避免 fake 形成第二套 CAS owner。
    fn begin_save(&self, command: SaveFileCommand) -> Result<Self::Transaction, WorkspaceError> {
        Ok(self.begin(FileSaveResult {
            relative_path: command.relative_path.as_str().to_owned(),
            revision: command.expected_revision,
        }))
    }
}

impl WorkspaceMovePort for &MutationFake {
    type Transaction = FakeTransaction<MoveEntryResult>;

    /// Move begin 回显两个领域端点，平台路径仍不进入 application 测试。
    fn begin_move(&self, command: MoveEntryCommand) -> Result<Self::Transaction, WorkspaceError> {
        Ok(self.begin(MoveEntryResult {
            from_relative_path: command.from_relative_path.as_str().to_owned(),
            to_relative_path: command.to_relative_path.as_str().to_owned(),
            revision: command.expected_revision,
        }))
    }
}

impl WorkspaceTrashPort for &MutationFake {
    type PrepareTransaction = FakeTransaction<TrashPrepareResult>;
    type CommitTransaction = FakeTransaction<TrashCommitResult>;

    /// Prepare begin 只签发测试 capability，不模拟已删除的跨域投影通知。
    fn begin_trash_prepare(
        &self,
        _: TrashPrepareCommand,
    ) -> Result<Self::PrepareTransaction, WorkspaceError> {
        Ok(self.begin(TrashPrepareResult {
            operation_token: "token".to_owned(),
            file_count: 1,
            total_bytes: 3,
            expires_at_unix_millis: 7,
        }))
    }

    /// Commit begin 返回路径脱敏的成功事实。
    fn begin_trash_commit(
        &self,
        _: TrashCommitCommand,
    ) -> Result<Self::CommitTransaction, WorkspaceError> {
        Ok(self.begin(TrashCommitResult {
            committed: true,
            revision: None,
        }))
    }
}

impl WorkspaceDropPort for &MutationFake {
    type Transaction = FakeTransaction<DropImportResult>;

    /// Drop begin 只返回 Workspace-relative 结果，native source path 永不进入 application。
    fn begin_drop(&self, _: DropImportCommand) -> Result<Self::Transaction, WorkspaceError> {
        Ok(self.begin(DropImportResult {
            imported_relative_paths: vec!["drop.txt".to_owned()],
        }))
    }
}

/// 所有 mutation fixture 复用受控 revision，避免绕过领域摘要校验。
fn revision() -> FileRevision {
    FileRevision::try_new(EntryKind::File, 3, Some(1), Some("a".repeat(64)))
        .expect("valid revision")
}

/// Save fixture 使用领域构造器，非法路径不能借 fake 绕过 admission。
fn save_command() -> SaveFileCommand {
    SaveFileCommand::new(
        "a.txt".to_owned(),
        revision(),
        "save".to_owned(),
        TextContent {
            text: "abc".to_owned(),
            encoding: TextEncoding::Utf8,
            line_ending: LineEnding::Lf,
        },
    )
    .expect("valid save")
}

/// Save 成功顺序必须闭合为 begin→lock→幂等→CAS→stage→commit→unlock。
#[test]
fn save_application_drives_complete_transaction_order() {
    let fake = MutationFake::new(FakeMode::Committed);
    WorkspaceMutationService::new(&fake)
        .save_file(save_command())
        .expect("save");
    assert_eq!(
        fake.calls(),
        [
            "begin",
            "lock",
            "idempotency",
            "cas",
            "stage",
            "commit",
            "unlock"
        ]
    );
}

/// Commit 普通失败由 application 主动 rollback，且不得发布成功投影。
#[test]
fn save_commit_failure_rolls_back_before_unlock() {
    let fake = MutationFake::new(FakeMode::CommitFailed);
    assert!(matches!(
        WorkspaceMutationService::new(&fake).save_file(save_command()),
        Err(WorkspaceError::RevisionConflict)
    ));
    assert_eq!(
        fake.calls(),
        [
            "begin",
            "lock",
            "idempotency",
            "cas",
            "stage",
            "commit",
            "rollback",
            "unlock"
        ]
    );
}

/// Rollback 失败必须继续 recovery 并统一返回 RecoveryRequired。
#[test]
fn failed_rollback_enters_recovery_before_unlock() {
    let fake = MutationFake::new(FakeMode::RollbackFailed);
    assert!(matches!(
        WorkspaceMutationService::new(&fake).save_file(save_command()),
        Err(WorkspaceError::RecoveryRequired)
    ));
    assert_eq!(
        fake.calls(),
        [
            "begin",
            "lock",
            "idempotency",
            "cas",
            "stage",
            "commit",
            "rollback",
            "recovery",
            "unlock"
        ]
    );
}

/// Move 复用同一主动事务协议，证明两个端点锁不会绕过 CAS 或 stage。
#[test]
fn move_uses_same_ordered_transaction_protocol() {
    let fake = MutationFake::new(FakeMode::Committed);
    let command = MoveEntryCommand::new(
        "a.txt".to_owned(),
        "b.txt".to_owned(),
        revision(),
        "move".to_owned(),
    )
    .expect("move");
    WorkspaceMutationService::new(&fake)
        .move_entry(command)
        .expect("move");
    assert_eq!(fake.calls().last(), Some(&"unlock"));
    assert_eq!(
        &fake.calls()[..6],
        ["begin", "lock", "idempotency", "cas", "stage", "commit"]
    );
}

/// Trash prepare 与 commit 使用独立事务，且都只在 unlock 后返回已提交事实。
#[test]
fn trash_prepare_and_commit_keep_distinct_transaction_boundaries() {
    let fake = MutationFake::new(FakeMode::Committed);
    let service = WorkspaceMutationService::new(&fake);
    service
        .prepare_trash(
            TrashPrepareCommand::new("a.txt".to_owned(), revision(), "prepare".to_owned())
                .expect("prepare"),
        )
        .expect("prepare");
    assert_eq!(fake.calls().last(), Some(&"unlock"));
    service
        .commit_trash(
            TrashCommitCommand::new(
                "a.txt".to_owned(),
                revision(),
                "token".to_owned(),
                "commit".to_owned(),
            )
            .expect("commit"),
        )
        .expect("commit");
    assert_eq!(fake.calls().last(), Some(&"unlock"));
}

/// Drop commit 报告恢复态时 application 必须执行 recovery，不能投影 imported paths。
#[test]
fn drop_recovery_is_driven_before_lock_release() {
    let fake = MutationFake::new(FakeMode::Recovery);
    let command = DropImportCommand::new(
        String::new(),
        revision(),
        "drop-token".to_owned(),
        "drop".to_owned(),
    )
    .expect("drop");
    assert!(matches!(
        WorkspaceMutationService::new(&fake).import_drop(command),
        Err(WorkspaceError::RecoveryRequired)
    ));
    assert_eq!(fake.calls().last(), Some(&"unlock"));
    assert!(fake.calls().contains(&"recovery"));
}

/// Watch fake 回显 generation，用于证明 service 不改写竞态栅栏。
struct WatchFake;

impl WorkspaceWatchPort for WatchFake {
    /// Start 只回显调用方 generation，fake 不创建线程。
    fn start(&self, command: WatchCommand) -> Result<WatchStartResult, WorkspaceError> {
        Ok(WatchStartResult {
            started: true,
            generation: command.generation,
        })
    }

    /// Stop 不模拟 watcher cleanup，只验证 application 端口边界。
    fn stop(&self, _: WatchCommand) -> Result<WatchStopResult, WorkspaceError> {
        Ok(WatchStopResult { stopped: true })
    }

    /// Rescan 回显 generation，避免 fake 成为第二个竞态 owner。
    fn rescan(&self, command: WatchCommand) -> Result<WatchRescanResult, WorkspaceError> {
        Ok(WatchRescanResult {
            generation: command.generation,
            requires_rescan: false,
            emitted_paths: 0,
        })
    }
}

/// Application 不生成自己的 generation，避免和 native session 形成两个 owner。
#[test]
fn watch_service_preserves_generation() {
    let result = WorkspaceWatchService::new(WatchFake)
        .start(WatchCommand { generation: 42 })
        .expect("start");
    assert_eq!(result.generation, 42);
}

/// Open fake 不持有 executable path，用于证明 application 契约不会扩大 process surface。
struct OpenFake;

impl WorkspaceOpenPort for OpenFake {
    /// Targets 只返回闭集可用性，不泄露搜索到的原生 executable。
    fn targets(&self) -> Result<Vec<OpenTargetAvailability>, OpenError> {
        Ok(vec![OpenTargetAvailability {
            target: OpenWithTarget::Vscode,
            available: false,
            reason: Some(OpenTargetUnavailableReason::NotInstalled),
        }])
    }

    /// Open fake 回显相对路径，证明 argv 仍在基础设施边界之外。
    fn open(&self, target: OpenWithTarget, relative_path: String) -> Result<OpenResult, OpenError> {
        Ok(OpenResult {
            target,
            relative_path,
            entry_kind: EntryKind::File,
        })
    }
}

/// Open service 只传递闭集 target 与相对路径，不生成 executable 或 argv。
#[test]
fn open_service_keeps_process_details_behind_port() {
    let result = WorkspaceOpenService::new(OpenFake)
        .open(OpenWithTarget::Vscode, "src/main.rs".to_owned())
        .expect("open");
    assert_eq!(result.target, OpenWithTarget::Vscode);
    assert_eq!(result.relative_path, "src/main.rs");
}
