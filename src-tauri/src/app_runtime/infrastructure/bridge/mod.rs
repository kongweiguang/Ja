// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Ja App Server sidecar 的单 owner 原生 bridge actor。
//
// actor 独占 supervisor 和全部生命周期变更；独立的 EventPump worker 独占底层一次性事件
// consumer，避免耗时 start/turn 请求阻塞通知投影或终态清理。

use crate::app_runtime::{
    ApprovalResponseInput, AttachmentDiscardInput, AttachmentImportInput, AttachmentMetadata,
    ConfigurationPatchResult, ConfigurationReadResult, ConfigurationReplaceResult,
    ConfigurationRequest, ConfigurationResetResult, ConfigurationResponse, CredentialDeleteResult,
    CredentialSetResult, EventSink, GoalMethod, GoalPayload, GoalRequest, GoalResponse,
    HistoryRequest, HistoryResponse, InputQueue, LaunchConfig, McpListResultData,
    McpTestResultData, McpToolsReadResultData, ModelTestResultData, QueuedInput, QueuedInputIssue,
    RuntimeBridgePort, RuntimeCommandError, RuntimeStatus, RuntimeStatusKind, SettingsRequest,
    SettingsResponse, SkillListResultData, TaskActivity, TaskCloseInput, TaskCloseResult,
    TaskContextSeed, TaskCreateInput, TaskCreateResult, TaskFollowupInput, TaskFollowupResult,
    TaskListInput, TaskListResult, TaskMailboxMessage, TaskMessageInput, TaskMessageResult,
    TaskMutationInput, TaskObserveInput, TaskObserveResult, TaskReadInput, TaskReadResult,
    TaskSeenInput, TaskSummary, TaskTreeDeleteInput, TaskTreeDeleteResult, TaskUnobserveInput,
    ThreadArchiveResultData, ThreadCompactResultData, ThreadCreateResultData,
    ThreadDeleteResultData, ThreadDiscoverResultData, ThreadListResultData, ThreadPinResultData,
    ThreadPreferencesUpdateResultData, ThreadReadResultData, ThreadRenameResultData,
    ThreadRestoreResultData, ThreadSearchResultData, ThreadSeenResultData, ToolArtifactReadInput,
    ToolArtifactReadResult, TurnAccepted, TurnCancelInput, TurnCancelResult,
    TurnChangeSetReadInput, TurnChangeSetReadResult, TurnInputDelete, TurnInputEnqueue,
    TurnInputPrioritize, TurnInputResult, TurnInputUpdate, TurnResumeInput, TurnStartInput,
    WorkspaceDto, WorkspaceListResultData, WorkspacePathSearchInput, WorkspacePathSearchItem,
    WorkspacePathSearchResult, clear_recovery_record, emit_frame, emit_status,
    ensure_recovery_clear, frame_to_value, persist_recovery_record, recovery_marker_path,
    valid_frozen_turn_id,
};
use base64::Engine as _;
use ja_runtime::app_server_process::{
    AttachmentPreviewCloseParams, AttachmentPreviewOpenParams, AttachmentPreviewOpenResult,
    AttachmentPreviewReadParams, AttachmentPreviewReadResult, CodecError, EventPump, RpcFrame,
    Session, SessionEvent, SidecarSupervisor, TurnChangeSetReadLease, TurnChangeSetReadParams,
    TurnChangeSetReadResult as WireTurnChangeSetReadResult, valid_protocol_timestamp,
};
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender, TrySendError};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const COMMAND_QUEUE_CAPACITY: usize = 64;
const COMMAND_DEADLINE: Duration = Duration::from_secs(15);
const COMPACTION_REQUEST_DEADLINE: Duration = Duration::from_secs(300);
const COMPACTION_REPLY_DEADLINE: Duration = Duration::from_secs(305);
const EXIT_DEADLINE: Duration = Duration::from_secs(20);
const EVENT_POLL_TIMEOUT: Duration = Duration::from_millis(25);
const ACTOR_POLL_TIMEOUT: Duration = Duration::from_millis(25);
const EVENT_CANCEL_GRACE: Duration = Duration::from_millis(100);
const MAX_OPERATION_PAYLOAD_BYTES: usize = 4 * 1024 * 1024;
const TURN_CHANGE_SET_READ_DEADLINE: Duration = Duration::from_secs(5);
const MAX_TURN_CHANGE_SET_READS_IN_FLIGHT: usize = 2;

/// bridge actor 内部的 History wire 闭集；该枚举不跨 application 或 interface 边界。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum HistoryMethod {
    WorkspaceList,
    ThreadCreate,
    ThreadDiscover,
    ThreadList,
    ThreadSearch,
    ThreadRead,
    ThreadRename,
    ThreadPin,
    ThreadSeen,
    ThreadPreferencesUpdate,
    ThreadCompact,
    ThreadArchive,
    ThreadRestore,
    ThreadDelete,
}

impl HistoryMethod {
    /// 将内部闭集映射为冻结 JA-RPC v1 拼写，调用方不能传入 method 字符串。
    const fn wire_name(self) -> &'static str {
        match self {
            Self::WorkspaceList => "workspace/list",
            Self::ThreadCreate => "thread/create",
            Self::ThreadDiscover => "thread/list",
            Self::ThreadList => "thread/list",
            Self::ThreadSearch => "thread/search",
            Self::ThreadRead => "thread/read",
            Self::ThreadRename => "thread/rename",
            Self::ThreadPin => "thread/pin",
            Self::ThreadSeen => "thread/seen",
            Self::ThreadPreferencesUpdate => "thread/preferences/update",
            Self::ThreadCompact => "thread/compact",
            Self::ThreadArchive => "thread/archive",
            Self::ThreadRestore => "thread/restore",
            Self::ThreadDelete => "thread/delete",
        }
    }
}

/// bridge actor 内部的 Settings wire 闭集；应用层只提交具体 query variant。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SettingsQueryMethod {
    SkillList,
    McpList,
    McpTest,
    ModelTest,
    McpToolsRead,
}

impl SettingsQueryMethod {
    /// 将内部闭集映射为冻结 JA-RPC v1 拼写，避免 method 与 payload 被外部组合。
    const fn wire_name(self) -> &'static str {
        match self {
            Self::SkillList => "skill/list",
            Self::McpList => "mcp/list",
            Self::McpTest => "mcp/test",
            Self::ModelTest => "model/test",
            Self::McpToolsRead => "mcp/list-tools",
        }
    }
}

const TERMINAL_NONE: u8 = 0;
pub(crate) const TERMINAL_SIGNAL_QUEUE: u8 = 3;
const TERMINAL_SIDECAR_EOF: u8 = 4;
const TERMINAL_SIDECAR_HANDSHAKE: u8 = 5;
const TERMINAL_SIDECAR_WRITER_TIMEOUT: u8 = 6;
const TERMINAL_SIDECAR_PROTOCOL_ENVELOPE: u8 = 7;
const TERMINAL_SIDECAR_PROTOCOL_FRAME: u8 = 8;
const TERMINAL_SIDECAR_QUEUE: u8 = 9;
const TERMINAL_SIDECAR_RESPONSE: u8 = 10;
const TERMINAL_SIDECAR_EXIT_ZERO: u8 = 11;
pub(crate) const TERMINAL_SIDECAR_EXIT_NONZERO: u8 = 12;
const TERMINAL_SIDECAR_EXIT_UNKNOWN: u8 = 13;
const TERMINAL_SIDECAR_PROTOCOL_INVALID_ID: u8 = 14;
const TERMINAL_SIDECAR_PROTOCOL_INVALID_ERROR_CATALOG: u8 = 15;
const TERMINAL_SIDECAR_PROTOCOL_INVALID_JSON: u8 = 16;
const TERMINAL_SIDECAR_PROTOCOL_PARTIAL_FRAME: u8 = 17;
const TERMINAL_SIDECAR_PROTOCOL_IO: u8 = 18;
const TERMINAL_SIDECAR_PROTOCOL_INVALID_UTF8: u8 = 19;
const TERMINAL_SIDECAR_PROTOCOL_DUPLICATE_KEY: u8 = 20;
const TERMINAL_SIDECAR_PROTOCOL_EMPTY_FRAME: u8 = 21;
const TERMINAL_SIDECAR_PROTOCOL_NON_OBJECT: u8 = 22;
const TERMINAL_SIDECAR_PROTOCOL_FRAME_TOO_LARGE: u8 = 23;
const TERMINAL_SIDECAR_PROTOCOL_INVALID_LIMIT: u8 = 24;

pub(crate) mod actor;
pub(crate) mod event_projection;
pub(crate) mod exit_cleanup;
pub(crate) mod goal;
pub(crate) mod lifecycle;
pub(crate) mod operations;
pub(crate) mod runtime_control;
pub(crate) mod tasks;

pub(crate) use actor::BridgeCommand;
use actor::*;
use event_projection::*;
use exit_cleanup::*;
use goal::*;
use lifecycle::*;
use operations::*;
use runtime_control::{RuntimeControlPhase, RuntimeControlPort};
use tasks::*;
/// 可克隆的 Tauri managed state；可变 supervisor 始终只由其 actor 线程持有。
#[derive(Clone)]
pub(crate) struct RuntimeBridge {
    pub(crate) inner: Arc<RuntimeBridgeInner>,
}

/// ChangeSet 正文读取的独立双槽门禁；RAII 释放保证超时、协议错误和 generation 迟到都不泄漏槽位。
pub(crate) struct ChangeSetReadPermit {
    in_flight: Arc<AtomicUsize>,
}

impl ChangeSetReadPermit {
    /// 使用 CAS 在 actor 队列外完成有界准入，第三个并发读取立即返回 backpressure。
    pub(crate) fn acquire(in_flight: Arc<AtomicUsize>) -> Result<Self, RuntimeCommandError> {
        let mut observed = in_flight.load(Ordering::Acquire);
        loop {
            if observed >= MAX_TURN_CHANGE_SET_READS_IN_FLIGHT {
                return Err(RuntimeCommandError::queue_full());
            }
            match in_flight.compare_exchange_weak(
                observed,
                observed + 1,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => return Ok(Self { in_flight }),
                Err(current) => observed = current,
            }
        }
    }
}

impl Drop for ChangeSetReadPermit {
    /// 所有返回路径统一释放槽位，避免一次损坏响应阻塞后续文件选择。
    fn drop(&mut self) {
        self.in_flight.fetch_sub(1, Ordering::AcqRel);
    }
}

/// 严格解码单文件正文并复核 identity、bytes 与 SHA-256；规范 Base64 必须能原样重编码。
pub(crate) fn validate_turn_change_set_content(
    wire: WireTurnChangeSetReadResult,
    expected_artifact_id: &str,
    expected_file_path: &str,
) -> Result<TurnChangeSetReadResult, RuntimeCommandError> {
    if wire.artifact_id != expected_artifact_id || wire.file_path != expected_file_path {
        return Err(RuntimeCommandError::unavailable());
    }
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(wire.content_base64.as_bytes())
        .map_err(|_| RuntimeCommandError::unavailable())?;
    if base64::engine::general_purpose::STANDARD.encode(&bytes) != wire.content_base64
        || bytes.len() as u64 != wire.byte_length
        || bytes.len() as u64 > ja_runtime::app_server_process::TURN_CHANGE_SET_MAX_BYTES
    {
        return Err(RuntimeCommandError::unavailable());
    }
    let sha256 = hex_digest(Sha256::digest(&bytes));
    if sha256 != wire.sha256 {
        return Err(RuntimeCommandError::unavailable());
    }
    let content = String::from_utf8(bytes).map_err(|_| RuntimeCommandError::unavailable())?;
    Ok(TurnChangeSetReadResult {
        artifact_id: wire.artifact_id,
        file_path: wire.file_path,
        byte_length: wire.byte_length,
        sha256: wire.sha256,
        content,
    })
}

/// 摘要使用固定小写十六进制，避免额外依赖和大小写等价值穿过 Tauri 合同。
fn hex_digest(digest: impl AsRef<[u8]>) -> String {
    digest
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

impl RuntimeBridge {
    /// 构造单 owner actor，并把测试差异限制在窄控制端口；端口不能替换协议或业务结果。
    pub(crate) fn new_with_control(
        config: LaunchConfig,
        sink: EventSink,
        runtime_control: Arc<dyn RuntimeControlPort>,
    ) -> Result<Self, RuntimeCommandError> {
        ensure_recovery_clear(&config.sidecar.run_dir)?;
        config.validate()?;
        let recovery_path = recovery_marker_path(&config.sidecar.run_dir);
        let (commands, command_receiver) = mpsc::sync_channel(COMMAND_QUEUE_CAPACITY);
        let (shutdown, shutdown_receiver) = mpsc::sync_channel(1);
        let (terminal_wake, terminal_receiver) = mpsc::sync_channel(1);
        let completion = Arc::new(Completion::new());
        let current_generation = Arc::new(AtomicU64::new(0));
        let change_set_reads_in_flight = Arc::new(AtomicUsize::new(0));
        let terminal_fault = Arc::new(TerminalFault::new(terminal_wake));
        let detached_event_generation = Arc::new(AtomicU64::new(0));
        let cleanup_fault = Arc::new(CleanupFault::new());
        let exit_control = Arc::new(ExitControl::new(runtime_control.exit_timeout()));
        let quarantine = Arc::new(ExitQuarantine::new());
        let shutdown_completed = Arc::new(AtomicBool::new(false));
        let task_observations = Arc::new(TaskObservationRegistry::default());
        let actor_completion = Arc::clone(&completion);
        let actor_epoch = Arc::clone(&current_generation);
        let actor_fault = Arc::clone(&terminal_fault);
        let actor_detached_event = Arc::clone(&detached_event_generation);
        let actor_cleanup_fault = Arc::clone(&cleanup_fault);
        let actor_exit_control = Arc::clone(&exit_control);
        let actor_quarantine = Arc::clone(&quarantine);
        let actor_shutdown_completed = Arc::clone(&shutdown_completed);
        let actor_runtime_control = Arc::clone(&runtime_control);
        let actor_task_observations = Arc::clone(&task_observations);
        let actor = thread::Builder::new()
            .name("ja-runtime-bridge".to_owned())
            .spawn(move || {
                actor_loop(
                    ActorContext {
                        config,
                        sink,
                        current_generation: actor_epoch,
                        terminal_fault: actor_fault,
                        detached_event_generation: actor_detached_event,
                        cleanup_fault: actor_cleanup_fault,
                        exit_control: actor_exit_control,
                        quarantine: actor_quarantine,
                        shutdown_completed: actor_shutdown_completed,
                        completion: actor_completion,
                        runtime_control: actor_runtime_control,
                        task_observations: actor_task_observations,
                    },
                    command_receiver,
                    shutdown_receiver,
                    terminal_receiver,
                )
            })
            .map_err(|_| RuntimeCommandError::unavailable())?;
        Ok(Self {
            inner: Arc::new(RuntimeBridgeInner {
                commands,
                shutdown,
                completion,
                detached_event_generation,
                cleanup_fault,
                exit_control,
                quarantine,
                shutdown_completed,
                recovery_path,
                actor_join: Mutex::new(Some(actor)),
                runtime_control,
                current_generation,
                change_set_reads_in_flight,
            }),
        })
    }

    /// 以非阻塞方式准入 start，命令队列满时返回稳定且可重试的 `RUNTIME_QUEUE_FULL`，
    /// 防止 UI 线程被反压永久占用。
    pub fn start(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::Start { reply })
    }

    /// 将 graceful stop 交给 actor，确保进程树清理仍由 supervisor owner 串行完成。
    pub fn stop(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::Stop { reply })
    }

    /// 仅代理 Java-owned config/credential 闭集方法；调用方不能传任意方法名，Settings
    /// command 负责选择 allowlist 操作并验证响应投影。
    pub(crate) fn config_request(
        &self,
        method: &'static str,
        params: Value,
    ) -> Result<Value, RuntimeCommandError> {
        if !matches!(
            method,
            "configuration/read"
                | "configuration/patch"
                | "configuration/replace"
                | "configuration/reset"
                | "credential/set"
                | "credential/delete"
        ) {
            return Err(RuntimeCommandError::invalid_params());
        }
        self.call(|reply| BridgeCommand::Config {
            method: method.to_owned(),
            params,
            reply,
        })
    }

    /// 向当前 Java generation 注册一个 Rust-owned canonical root，并显式应用 trust；
    /// 不重放 active Workspace，也不接受 renderer 选择 RPC 方法。
    pub(crate) fn workspace_open(
        &self,
        root: PathBuf,
        display_name: String,
        trust: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::WorkspaceOpen {
            root,
            display_name,
            trust,
            reply,
        })
    }

    /// 通过专用 typed lane 读取 Java-owned general Workspace，native 调用方不能替换方法名
    /// 或请求参数。
    pub(crate) fn general_workspace_read(&self) -> Result<Value, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::GeneralWorkspaceRead { reply })
    }

    /// `@` 路径建议通过固定 actor command 转发，避免把 method 字符串或扫描预算暴露给 WebView。
    pub(crate) fn workspace_path_search(
        &self,
        input: WorkspacePathSearchInput,
    ) -> Result<WorkspacePathSearchResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.call(|reply| BridgeCommand::WorkspacePathSearch { input, reply })
    }

    /// Host 暴露可用 composition state 前，必须用固定 `runtime/health` 验证当前 generation。
    pub(crate) fn health_read(&self) -> Result<(), RuntimeCommandError> {
        self.call(|reply| BridgeCommand::HealthRead { reply })
    }

    /// 通过高优先级控制通道请求应用退出，并等待 actor join 以保留资源 owner。
    pub fn shutdown(&self) -> Result<(), RuntimeCommandError> {
        self.inner.shutdown()
    }

    /// 在调用方绝对退出 deadline 内请求高优先级 shutdown；即使 Terminal 清理已消耗部分
    /// 预算，Tauri callback 也不能回退到 bridge 更长的独立 timeout。
    pub fn shutdown_until(&self, deadline: Instant) -> Result<(), RuntimeCommandError> {
        self.inner.shutdown_until(deadline)
    }

    /// 为 Tauri run loop 提供最终 Exit 事件可安全放行的权威证明。
    pub fn exit_ready(&self) -> bool {
        self.inner.exit_ready()
    }

    /// OS 已无法继续阻止退出且清理尚未确认时，记录不含 token 的 manual-recovery 诊断。
    pub fn record_forced_exit(&self) {
        self.inner.record_forced_exit();
    }

    /// 返回 actor 串行化后的权威生命周期投影，不读取旁路缓存。
    pub fn state(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        if self.inner.completion.done.load(Ordering::Acquire) {
            return self.inner.quarantine.state(&self.inner.cleanup_fault);
        }
        self.call(|reply| BridgeCommand::State { reply })
    }

    /// 通过生产使用的同一有界通道发送 typed Turn，测试与生产不分叉。
    pub fn turn_start(&self, input: TurnStartInput) -> Result<TurnAccepted, RuntimeCommandError> {
        let params = turn_start_params(&input)?;
        self.call(|reply| BridgeCommand::TurnStart { params, reply })
    }

    /// 通过现有 bridge actor 准入取消；sidecar 保持存活，最终完成事件仍是权威事实。
    pub fn turn_cancel(
        &self,
        input: TurnCancelInput,
    ) -> Result<TurnCancelResult, RuntimeCommandError> {
        let params = turn_cancel_params(&input)?;
        self.call(|reply| BridgeCommand::TurnCancel { params, reply })
    }

    /// 通过 actor 的当前 supervised session 恢复既有 Turn，不建立 Rust execution state 或进程恢复旁路。
    pub fn turn_resume(&self, input: TurnResumeInput) -> Result<TurnAccepted, RuntimeCommandError> {
        let params = turn_resume_params(&input)?;
        self.call(|reply| BridgeCommand::TurnResume { params, reply })
    }

    /// 默认入队普通 follow-up；renderer 不能通过输入字段指定 steering 或排序值。
    pub fn turn_input_enqueue(
        &self,
        input: TurnInputEnqueue,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        self.turn_input_request("turn/input/enqueue", turn_input_enqueue_params(&input)?)
    }

    /// 将已入队条目提升为 steering，实际点击序号由 Java transaction 分配。
    pub fn turn_input_prioritize(
        &self,
        input: TurnInputPrioritize,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        self.turn_input_request(
            "turn/input/prioritize",
            turn_input_prioritize_params(&input)?,
        )
    }

    /// 更新队列文本仍走同一 actor/generation fence，不修改 Turn execution CAS。
    pub fn turn_input_update(
        &self,
        input: TurnInputUpdate,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        self.turn_input_request("turn/input/update", turn_input_update_params(&input)?)
    }

    /// 删除尚未消费的条目，响应必须携带删除后的完整权威队列。
    pub fn turn_input_delete(
        &self,
        input: TurnInputDelete,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        self.turn_input_request("turn/input/delete", turn_input_delete_params(&input)?)
    }

    /// 读取 Java 持久化的冻结 Turn diff；固定 method 与 typed input 防止 generic RPC tunnel。
    pub fn turn_change_set_read(
        &self,
        input: TurnChangeSetReadInput,
    ) -> Result<TurnChangeSetReadResult, RuntimeCommandError> {
        let deadline = Instant::now() + TURN_CHANGE_SET_READ_DEADLINE;
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let _permit =
            ChangeSetReadPermit::acquire(Arc::clone(&self.inner.change_set_reads_in_flight))?;
        let params = TurnChangeSetReadParams::new(
            input.thread_id,
            input.turn_id,
            input.artifact_id.clone(),
            input.file_path.clone(),
        )
        .map_err(|_| RuntimeCommandError::invalid_params())?;
        let lease = self.turn_change_set_read_lease_until(deadline)?;
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(RuntimeCommandError::deadline());
        }
        let response = lease
            .read(params, remaining)
            .map_err(|error| RuntimeCommandError::from_process(&error))?;
        if lease.generation() != self.inner.current_generation.load(Ordering::Acquire) {
            return Err(RuntimeCommandError::unavailable());
        }
        let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
        if let Some(error) = value.get("error") {
            return Err(command_error_from_rpc(error));
        }
        let result = value
            .get("result")
            .filter(|result| result.is_object())
            .ok_or_else(RuntimeCommandError::unavailable)
            .and_then(|result| {
                WireTurnChangeSetReadResult::try_from(result)
                    .map_err(|_| RuntimeCommandError::unavailable())
            })?;
        let result =
            validate_turn_change_set_content(result, &input.artifact_id, &input.file_path)?;
        if Instant::now() > deadline {
            return Err(RuntimeCommandError::deadline());
        }
        if lease.generation() != self.inner.current_generation.load(Ordering::Acquire) {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(result)
    }

    /// Tool 完整输出只通过 Java 四元归属 reader 读取，Rust 不保留 raw result 副本。
    pub fn tool_artifact_read(
        &self,
        input: ToolArtifactReadInput,
    ) -> Result<ToolArtifactReadResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.call(|reply| BridgeCommand::ToolArtifactRead { input, reply })
    }

    /// 只准入四个队列 mutation，避免共享 helper 演变为 renderer 可控的 generic RPC tunnel。
    fn turn_input_request(
        &self,
        method: &'static str,
        params: Value,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        if !matches!(
            method,
            "turn/input/enqueue"
                | "turn/input/prioritize"
                | "turn/input/update"
                | "turn/input/delete"
        ) {
            return Err(RuntimeCommandError::invalid_params());
        }
        self.call(|reply| BridgeCommand::TurnInput {
            method,
            params,
            reply,
        })
    }

    /// 准入 typed approval 决策；actor 从当前 generation 的有界 map 解析私有 server request ID。
    pub fn approval_respond(
        &self,
        input: ApprovalResponseInput,
    ) -> Result<(), RuntimeCommandError> {
        self.call(|reply| BridgeCommand::ApprovalRespond { input, reply })
    }

    /// 通过固定 actor variant 导入一个已安全 stage 的文件；workspace identity 只能来自 Host binding。
    pub(crate) fn attachment_import(
        &self,
        workspace_id: String,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.call(|reply| BridgeCommand::AttachmentImport {
            workspace_id,
            input,
            reply,
        })
    }

    /// 通过固定 `attachment/discard` lane 删除 Java-owned 草稿，拒绝 generic method passthrough。
    pub(crate) fn attachment_discard(
        &self,
        input: AttachmentDiscardInput,
    ) -> Result<(), RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.call(|reply| BridgeCommand::AttachmentDiscard { input, reply })
    }

    /// 预览 open 通过独立 actor variant 串行到当前 supervisor owner。
    pub(crate) fn attachment_preview_open(
        &self,
        input: AttachmentPreviewOpenParams,
    ) -> Result<AttachmentPreviewOpenResult, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::AttachmentPreviewOpen { input, reply })
    }

    /// 预览 read 保持分段请求顺序，多个 command 不会并发读乱同一 stdio stream。
    pub(crate) fn attachment_preview_read(
        &self,
        input: AttachmentPreviewReadParams,
    ) -> Result<AttachmentPreviewReadResult, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::AttachmentPreviewRead { input, reply })
    }

    /// 预览 close 是固定 actor 终态，不通过 generic history/config lane。
    pub(crate) fn attachment_preview_close(
        &self,
        input: AttachmentPreviewCloseParams,
    ) -> Result<(), RuntimeCommandError> {
        self.call(|reply| BridgeCommand::AttachmentPreviewClose { input, reply })
    }

    /// 通过与 Turn 相同的 Ready supervisor 和 timeout 路径发送固定 history 方法，
    /// 不增加 generic WebView RPC。
    pub(crate) fn history_request(
        &self,
        method: HistoryMethod,
        params: Value,
    ) -> Result<Value, RuntimeCommandError> {
        let reply_deadline = if method == HistoryMethod::ThreadCompact {
            COMPACTION_REPLY_DEADLINE
        } else {
            COMMAND_DEADLINE
        };
        self.call_with_deadline(
            |reply| BridgeCommand::History {
                method,
                params,
                reply,
            },
            reply_deadline,
        )
    }

    /// Goal/Plan 经过独立 actor variant 发送，renderer 无法借此访问目录外方法。
    pub(crate) fn goal_request(
        &self,
        method: GoalMethod,
        params: Value,
    ) -> Result<Value, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::Goal {
            method,
            params,
            reply,
        })
    }

    /// 通过与 history 相同的有界 actor lane 发送固定 Skills/MCP 查询；`mcp/test` 只能响应其
    /// 目的受限的 secret 请求。
    pub(crate) fn settings_query(
        &self,
        method: SettingsQueryMethod,
        params: Value,
    ) -> Result<Value, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::SettingsQuery {
            method,
            params,
            reply,
        })
    }

    /// 使用非阻塞有界准入和有界 reply 等待，防止饱和 WebView 永久占用 native 线程。
    fn call<T>(
        &self,
        build: impl FnOnce(Reply<T>) -> BridgeCommand,
    ) -> Result<T, RuntimeCommandError> {
        self.call_with_deadline(build, COMMAND_DEADLINE)
    }

    /// ChangeSet 使用覆盖 actor 准入与正文读取的同一绝对期限，lease 排队不能额外获得 5 秒。
    fn turn_change_set_read_lease_until(
        &self,
        deadline: Instant,
    ) -> Result<TurnChangeSetReadLease, RuntimeCommandError> {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(RuntimeCommandError::deadline());
        }
        let (reply, receiver) = mpsc::sync_channel(1);
        match self
            .inner
            .commands
            .try_send(BridgeCommand::TurnChangeSetReadLease { reply })
        {
            Ok(()) => self.inner.runtime_control.command_admitted(),
            Err(TrySendError::Full(_)) => return Err(RuntimeCommandError::queue_full()),
            Err(TrySendError::Disconnected(_)) => {
                return Err(RuntimeCommandError::unavailable());
            }
        }
        match receiver.recv_timeout(remaining) {
            Ok(result) => result,
            Err(RecvTimeoutError::Timeout) => Err(RuntimeCommandError::deadline()),
            Err(RecvTimeoutError::Disconnected) => Err(RuntimeCommandError::unavailable()),
        }
    }

    /// 仅为具有独立绝对预算的固定操作选择 reply 上限；调用方不能从 WebView 提供 timeout，
    /// 因此手动压缩的五分钟等待不会扩张其它 JA-RPC 方法的默认生命周期。
    fn call_with_deadline<T>(
        &self,
        build: impl FnOnce(Reply<T>) -> BridgeCommand,
        deadline: Duration,
    ) -> Result<T, RuntimeCommandError> {
        let (reply, receiver) = mpsc::sync_channel(1);
        match self.inner.commands.try_send(build(reply)) {
            Ok(()) => self.inner.runtime_control.command_admitted(),
            Err(TrySendError::Full(_)) => return Err(RuntimeCommandError::queue_full()),
            Err(TrySendError::Disconnected(_)) => return Err(RuntimeCommandError::unavailable()),
        }
        receiver
            .recv_timeout(deadline)
            .map_err(|_| RuntimeCommandError::unavailable())?
    }
}

impl RuntimeBridgePort for RuntimeBridge {
    /// 生命周期调用仍进入同一有界 actor；trait 只隔离 application 与具体进程类型。
    fn start(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        RuntimeBridge::start(self)
    }

    /// 停止必须由 actor 串行清理进程树，port 不提供直接 kill 旁路。
    fn stop(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        RuntimeBridge::stop(self)
    }

    /// 状态读取来自 actor/quarantine 权威状态，不从最后一个事件推断。
    fn state(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        RuntimeBridge::state(self)
    }

    /// 配置仅接受 application 枚举闭集；wire method 与 JSON 结构映射留在 infrastructure。
    fn configuration(
        &self,
        request: ConfigurationRequest,
    ) -> Result<ConfigurationResponse, RuntimeCommandError> {
        match request {
            ConfigurationRequest::Read(params) => Ok(ConfigurationResponse::Read(
                ConfigurationReadResult::try_new(encode_result(self.config_request(
                    "configuration/read",
                    decode_params(params.into_bytes())?,
                )?)?)?,
            )),
            ConfigurationRequest::Patch(params) => Ok(ConfigurationResponse::Patch(
                ConfigurationPatchResult::try_new(encode_result(self.config_request(
                    "configuration/patch",
                    decode_params(params.into_bytes())?,
                )?)?)?,
            )),
            ConfigurationRequest::Replace(params) => Ok(ConfigurationResponse::Replace(
                ConfigurationReplaceResult::try_new(encode_result(self.config_request(
                    "configuration/replace",
                    decode_params(params.into_bytes())?,
                )?)?)?,
            )),
            ConfigurationRequest::Reset(params) => Ok(ConfigurationResponse::Reset(
                ConfigurationResetResult::try_new(encode_result(self.config_request(
                    "configuration/reset",
                    decode_params(params.into_bytes())?,
                )?)?)?,
            )),
            ConfigurationRequest::CredentialSet(params) => Ok(
                ConfigurationResponse::CredentialSet(CredentialSetResult::try_new(encode_result(
                    self.config_request("credential/set", decode_params(params.into_bytes())?)?,
                )?)?),
            ),
            ConfigurationRequest::CredentialDelete(params) => {
                Ok(ConfigurationResponse::CredentialDelete(
                    CredentialDeleteResult::try_new(encode_result(self.config_request(
                        "credential/delete",
                        decode_params(params.into_bytes())?,
                    )?)?)?,
                ))
            }
        }
    }

    /// Workspace 只传递原生层已验证的 canonical root 与有限 trust 值。
    fn workspace_open(
        &self,
        root: PathBuf,
        display_name: String,
        trust: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        RuntimeBridge::workspace_open(self, root, display_name, trust)
    }

    /// 通用 Workspace 在 infrastructure 内解析为固定投影，不把动态 JSON 交给 application。
    fn general_workspace(&self) -> Result<WorkspaceDto, RuntimeCommandError> {
        parse_general_workspace(self.general_workspace_read()?)
    }

    /// 路径搜索沿用同一 supervised generation，并对返回栅栏做严格解析。
    fn workspace_path_search(
        &self,
        input: WorkspacePathSearchInput,
    ) -> Result<WorkspacePathSearchResult, RuntimeCommandError> {
        RuntimeBridge::workspace_path_search(self, input)
    }

    /// 健康检查使用固定 `runtime/health`，application 无法选择方法名。
    fn health(&self) -> Result<(), RuntimeCommandError> {
        self.health_read()
    }

    /// Turn Start 已是强类型 command，不经过动态 payload。
    fn turn_start(&self, input: TurnStartInput) -> Result<TurnAccepted, RuntimeCommandError> {
        RuntimeBridge::turn_start(self, input)
    }

    /// Turn Cancel 已是强类型 command，不经过动态 payload。
    fn turn_cancel(&self, input: TurnCancelInput) -> Result<TurnCancelResult, RuntimeCommandError> {
        RuntimeBridge::turn_cancel(self, input)
    }

    /// Resume 使用专用强类型方法，同时复用生产 actor 的 instance/generation fence。
    fn turn_resume(&self, input: TurnResumeInput) -> Result<TurnAccepted, RuntimeCommandError> {
        RuntimeBridge::turn_resume(self, input)
    }

    /// Enqueue 通过固定方法实现，调用方不能传 JA-RPC method 或初始 kind。
    fn turn_input_enqueue(
        &self,
        input: TurnInputEnqueue,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        RuntimeBridge::turn_input_enqueue(self, input)
    }

    /// Prioritize 固定到条目 CAS lane，不复用旧 steering admission。
    fn turn_input_prioritize(
        &self,
        input: TurnInputPrioritize,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        RuntimeBridge::turn_input_prioritize(self, input)
    }

    /// Update 固定到文本 CAS lane，响应仍使用完整队列投影。
    fn turn_input_update(
        &self,
        input: TurnInputUpdate,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        RuntimeBridge::turn_input_update(self, input)
    }

    /// Delete 固定到删除 CAS lane，不接受通用 mutation payload。
    fn turn_input_delete(
        &self,
        input: TurnInputDelete,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        RuntimeBridge::turn_input_delete(self, input)
    }

    /// production task/create 始终走 actor 当前 generation，不落地 Rust lineage。
    fn task_create(&self, input: TaskCreateInput) -> Result<TaskCreateResult, RuntimeCommandError> {
        RuntimeBridge::task_create(self, input)
    }

    /// production task/list 返回 Java projection 的严格解析结果。
    fn task_list(&self, input: TaskListInput) -> Result<TaskListResult, RuntimeCommandError> {
        RuntimeBridge::task_list(self, input)
    }

    /// production task/read 不缓存 seed/activity/mailbox，只返回当前请求页。
    fn task_read(&self, input: TaskReadInput) -> Result<TaskReadResult, RuntimeCommandError> {
        RuntimeBridge::task_read(self, input)
    }

    /// observation handle 只由 Java connection registry 签发。
    fn task_observe(
        &self,
        input: TaskObserveInput,
    ) -> Result<TaskObserveResult, RuntimeCommandError> {
        RuntimeBridge::task_observe(self, input)
    }

    /// unobserve 必须等待 Java ACK，不能本地删除即成功。
    fn task_unobserve(&self, input: TaskUnobserveInput) -> Result<(), RuntimeCommandError> {
        RuntimeBridge::task_unobserve(self, input)
    }

    /// Renderer reload 通过 actor 在同一 connection 上补偿释放全部观察句柄。
    fn release_task_observations(&self, owner: &'static str) -> Result<usize, RuntimeCommandError> {
        RuntimeBridge::release_task_observations(self, owner)
    }

    /// seen 直接返回 Java CAS 后的 Task projection。
    fn task_seen(&self, input: TaskSeenInput) -> Result<TaskSummary, RuntimeCommandError> {
        RuntimeBridge::task_seen(self, input)
    }

    /// QueueOnly message 通过固定 thread/message/send 方法代理。
    fn task_message_send(
        &self,
        input: TaskMessageInput,
    ) -> Result<TaskMessageResult, RuntimeCommandError> {
        RuntimeBridge::task_message_send(self, input)
    }

    /// followup 不拆成多个 Rust 请求，保留 Java 事务与调度原子性。
    fn task_followup(
        &self,
        input: TaskFollowupInput,
    ) -> Result<TaskFollowupResult, RuntimeCommandError> {
        RuntimeBridge::task_followup(self, input)
    }

    /// task cancel 不在 Rust 计算递归传播范围。
    fn task_cancel(&self, input: TaskMutationInput) -> Result<TaskSummary, RuntimeCommandError> {
        RuntimeBridge::task_cancel(self, input)
    }

    /// task tree delete 仅返回 Java 原子提交后的删除数量。
    fn task_tree_delete(
        &self,
        input: TaskTreeDeleteInput,
    ) -> Result<TaskTreeDeleteResult, RuntimeCommandError> {
        RuntimeBridge::task_tree_delete(self, input)
    }

    /// production task/close 只接受 App Server 幂等终态，不在 Rust actor 保存侧聊生命周期。
    fn task_close(&self, input: TaskCloseInput) -> Result<TaskCloseResult, RuntimeCommandError> {
        RuntimeBridge::task_close(self, input)
    }

    /// production Goal lane 只搬运 nominal payload，并保留 request/result method 身份。
    fn goal(&self, request: GoalRequest) -> Result<GoalResponse, RuntimeCommandError> {
        let method = request.method;
        let params = decode_params(request.payload.into_bytes())?;
        let result = self.goal_request(method, params)?;
        Ok(GoalResponse {
            method,
            payload: GoalPayload::try_new(encode_result(result)?)?,
        })
    }

    /// 固定 Turn artifact reader 仍走同一 actor/supervisor session。
    fn turn_change_set_read(
        &self,
        input: TurnChangeSetReadInput,
    ) -> Result<TurnChangeSetReadResult, RuntimeCommandError> {
        RuntimeBridge::turn_change_set_read(self, input)
    }

    /// 固定 Tool artifact reader 仍走同一 actor/supervisor session。
    fn tool_artifact_read(
        &self,
        input: ToolArtifactReadInput,
    ) -> Result<ToolArtifactReadResult, RuntimeCommandError> {
        RuntimeBridge::tool_artifact_read(self, input)
    }

    /// Approval 只携带冻结业务身份，私有 request id 留在 actor pending registry。
    fn approval_respond(&self, input: ApprovalResponseInput) -> Result<(), RuntimeCommandError> {
        RuntimeBridge::approval_respond(self, input)
    }

    /// 附件导入沿用同一 supervisor/actor owner，application 不接触 JSON 或私有 session。
    fn attachment_import(
        &self,
        workspace_id: String,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        RuntimeBridge::attachment_import(self, workspace_id, input)
    }

    /// 附件丢弃沿用固定 RPC method，不能由外层替换 wire 名称。
    fn attachment_discard(&self, input: AttachmentDiscardInput) -> Result<(), RuntimeCommandError> {
        RuntimeBridge::attachment_discard(self, input)
    }

    /// trait adapter 保留 open 强类型结果，不向 application 暴露 RpcFrame。
    fn attachment_preview_open(
        &self,
        input: AttachmentPreviewOpenParams,
    ) -> Result<AttachmentPreviewOpenResult, RuntimeCommandError> {
        RuntimeBridge::attachment_preview_open(self, input)
    }

    /// trait adapter 只返回已验证的单个 read chunk。
    fn attachment_preview_read(
        &self,
        input: AttachmentPreviewReadParams,
    ) -> Result<AttachmentPreviewReadResult, RuntimeCommandError> {
        RuntimeBridge::attachment_preview_read(self, input)
    }

    /// trait adapter 只有在 App Server 返回 closed=true 后成功。
    fn attachment_preview_close(
        &self,
        input: AttachmentPreviewCloseParams,
    ) -> Result<(), RuntimeCommandError> {
        RuntimeBridge::attachment_preview_close(self, input)
    }

    /// History 方法来自 application 枚举闭集，只有多形 params/result 使用私有结构树。
    fn history(&self, request: HistoryRequest) -> Result<HistoryResponse, RuntimeCommandError> {
        macro_rules! dispatch_history {
            ($params:expr, $method:expr, $variant:path, $result:ty) => {{
                let value = self.history_request($method, decode_params($params.into_bytes())?)?;
                Ok($variant(<$result>::try_new(encode_result(value)?)?))
            }};
        }
        match request {
            HistoryRequest::WorkspaceList(params) => dispatch_history!(
                params,
                HistoryMethod::WorkspaceList,
                HistoryResponse::WorkspaceList,
                WorkspaceListResultData
            ),
            HistoryRequest::ThreadCreate(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadCreate,
                HistoryResponse::ThreadCreate,
                ThreadCreateResultData
            ),
            HistoryRequest::ThreadDiscover(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadDiscover,
                HistoryResponse::ThreadDiscover,
                ThreadDiscoverResultData
            ),
            HistoryRequest::ThreadList(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadList,
                HistoryResponse::ThreadList,
                ThreadListResultData
            ),
            HistoryRequest::ThreadSearch(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadSearch,
                HistoryResponse::ThreadSearch,
                ThreadSearchResultData
            ),
            HistoryRequest::ThreadRead(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadRead,
                HistoryResponse::ThreadRead,
                ThreadReadResultData
            ),
            HistoryRequest::ThreadRename(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadRename,
                HistoryResponse::ThreadRename,
                ThreadRenameResultData
            ),
            HistoryRequest::ThreadPin(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadPin,
                HistoryResponse::ThreadPin,
                ThreadPinResultData
            ),
            HistoryRequest::ThreadSeen(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadSeen,
                HistoryResponse::ThreadSeen,
                ThreadSeenResultData
            ),
            HistoryRequest::ThreadPreferencesUpdate(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadPreferencesUpdate,
                HistoryResponse::ThreadPreferencesUpdate,
                ThreadPreferencesUpdateResultData
            ),
            HistoryRequest::ThreadCompact(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadCompact,
                HistoryResponse::ThreadCompact,
                ThreadCompactResultData
            ),
            HistoryRequest::ThreadArchive(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadArchive,
                HistoryResponse::ThreadArchive,
                ThreadArchiveResultData
            ),
            HistoryRequest::ThreadRestore(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadRestore,
                HistoryResponse::ThreadRestore,
                ThreadRestoreResultData
            ),
            HistoryRequest::ThreadDelete(params) => dispatch_history!(
                params,
                HistoryMethod::ThreadDelete,
                HistoryResponse::ThreadDelete,
                ThreadDeleteResultData
            ),
        }
    }

    /// Settings 方法来自 application 枚举闭集，不能通过结构树夹带 method name。
    fn settings(&self, request: SettingsRequest) -> Result<SettingsResponse, RuntimeCommandError> {
        macro_rules! dispatch_settings {
            ($params:expr, $method:expr, $variant:path, $result:ty) => {{
                let value = self.settings_query($method, decode_params($params.into_bytes())?)?;
                Ok($variant(<$result>::try_new(encode_result(value)?)?))
            }};
        }
        match request {
            SettingsRequest::SkillList(params) => dispatch_settings!(
                params,
                SettingsQueryMethod::SkillList,
                SettingsResponse::SkillList,
                SkillListResultData
            ),
            SettingsRequest::McpList(params) => dispatch_settings!(
                params,
                SettingsQueryMethod::McpList,
                SettingsResponse::McpList,
                McpListResultData
            ),
            SettingsRequest::McpTest(params) => dispatch_settings!(
                params,
                SettingsQueryMethod::McpTest,
                SettingsResponse::McpTest,
                McpTestResultData
            ),
            SettingsRequest::ModelTest(params) => dispatch_settings!(
                params,
                SettingsQueryMethod::ModelTest,
                SettingsResponse::ModelTest,
                ModelTestResultData
            ),
            SettingsRequest::McpToolsRead(params) => dispatch_settings!(
                params,
                SettingsQueryMethod::McpToolsRead,
                SettingsResponse::McpToolsRead,
                McpToolsReadResultData
            ),
        }
    }

    /// Shutdown 复用 bridge 的高优先级控制通道与清理债务状态机。
    fn shutdown(&self) -> Result<(), RuntimeCommandError> {
        RuntimeBridge::shutdown(self)
    }

    /// 外部绝对 deadline 必须贯穿原生清理，不能在 port 层重建预算。
    fn shutdown_until(&self, deadline: Instant) -> Result<(), RuntimeCommandError> {
        RuntimeBridge::shutdown_until(self, deadline)
    }

    /// Exit-ready 只在所有进程、事件 worker 和清理债务均确认释放后成立。
    fn exit_ready(&self) -> bool {
        RuntimeBridge::exit_ready(self)
    }

    /// 强制退出仅记录脱敏恢复 marker，不把原生错误或路径投影给 application。
    fn record_forced_exit(&self) {
        RuntimeBridge::record_forced_exit(self);
    }
}

/// 严格解析 Java-owned 通用 Workspace；路径仍需由 application 注册到原生 capability registry。
pub(crate) fn parse_general_workspace(value: Value) -> Result<WorkspaceDto, RuntimeCommandError> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct WorkspaceWire {
        workspace_id: String,
        root: String,
        display_name: String,
        trust: String,
        revision: u64,
    }
    let projection: WorkspaceWire =
        serde_json::from_value(value).map_err(|_| RuntimeCommandError::unavailable())?;
    if !projection.workspace_id.starts_with("ws_")
        || !valid_id(&projection.workspace_id, 99)
        || projection.root.is_empty()
        || projection.root.len() > 4_096
        || projection.root.chars().any(char::is_control)
        || !std::path::Path::new(&projection.root).is_absolute()
        || projection.display_name.is_empty()
        || projection.display_name.len() > 1_024
        || projection.display_name.chars().any(char::is_control)
        || !matches!(projection.trust.as_str(), "trusted" | "untrusted")
        || projection.revision > 9_007_199_254_740_991
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(WorkspaceDto {
        workspace_id: projection.workspace_id,
        root: projection.root,
        display_name: projection.display_name,
        trust: projection.trust,
        revision: projection.revision,
    })
}

/// infrastructure 只在固定 operation 分支解码参数；payload 必须是有界 JSON object，且从不写日志。
fn decode_params(bytes: Vec<u8>) -> Result<Value, RuntimeCommandError> {
    if bytes.is_empty() || bytes.len() > MAX_OPERATION_PAYLOAD_BYTES {
        return Err(RuntimeCommandError::invalid_params());
    }
    serde_json::from_slice(&bytes)
        .ok()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::invalid_params)
}

/// JA-RPC 结果在返回 application 前编码并再次限制大小；具体 nominal result 由调用分支构造。
fn encode_result(value: Value) -> Result<Vec<u8>, RuntimeCommandError> {
    let bytes = serde_json::to_vec(&value).map_err(|_| RuntimeCommandError::unavailable())?;
    if bytes.is_empty() || bytes.len() > MAX_OPERATION_PAYLOAD_BYTES {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(bytes)
}

/// infrastructure 在领域校验通过后构造固定 `turn/start` JSON；deadline 缺失时不发送 null。
fn turn_start_params(input: &TurnStartInput) -> Result<Value, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    let content = turn_content_value(&input.content);
    let mut params = json!({"threadId": input.thread_id, "content": content});
    if let Some(deadline_ms) = input.deadline_ms {
        params["deadlineMs"] = json!(deadline_ms);
    }
    Ok(params)
}

/// infrastructure 构造固定取消 envelope，domain 已排除非法 identity 与超大 revision。
fn turn_cancel_params(input: &TurnCancelInput) -> Result<Value, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    Ok(json!({
        "turnId": input.turn_id,
        "expectedThreadRevision": input.expected_thread_revision,
    }))
}

/// infrastructure 构造固定 Resume CAS envelope；执行游标、配置代际和目录指纹不离开 Java owner。
fn turn_resume_params(input: &TurnResumeInput) -> Result<Value, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    Ok(json!({
        "turnId": input.turn_id,
        "expectedThreadRevision": input.expected_thread_revision,
    }))
}

/// 将严格领域枚举映射为唯一 tagged wire 形状，所有消息入口共享这一实现。
fn turn_content_value(content: &[crate::app_runtime::TurnContentPart]) -> Vec<Value> {
    content
        .iter()
        .map(|part| match part {
            crate::app_runtime::TurnContentPart::Text { text } => {
                json!({"type": "text", "text": text})
            }
            crate::app_runtime::TurnContentPart::Attachment { attachment_id } => {
                json!({"type": "attachment", "attachmentId": attachment_id})
            }
            crate::app_runtime::TurnContentPart::WorkspaceReference {
                workspace_id,
                relative_path,
                kind,
            } => json!({
                "type": "workspace_reference",
                "workspaceId": workspace_id,
                "relativePath": relative_path,
                "kind": kind,
            }),
            crate::app_runtime::TurnContentPart::SkillReference { skill_id } => {
                json!({"type": "skill_reference", "skillId": skill_id})
            }
        })
        .collect()
}

/// 普通入队只构造 `{turnId,content}`，禁止 renderer 提交 kind、revision 或排序字段。
fn turn_input_enqueue_params(input: &TurnInputEnqueue) -> Result<Value, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    let content = turn_content_value(&input.content);
    if serde_json::to_vec(&content)
        .map_err(|_| RuntimeCommandError::invalid_params())?
        .len()
        > 524_288
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(json!({"turnId": input.turn_id, "content": content}))
}

/// prioritize 使用条目 revision CAS；队列 revision 不属于写入条件，避免锁住无关条目变化。
fn turn_input_prioritize_params(input: &TurnInputPrioritize) -> Result<Value, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    Ok(json!({
        "turnId": input.turn_id,
        "inputId": input.input_id,
        "expectedInputRevision": input.expected_input_revision,
    }))
}

/// update 把新结构化内容与 item CAS 原子提交，防止读改写覆盖随后发生的 prioritize。
fn turn_input_update_params(input: &TurnInputUpdate) -> Result<Value, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    let content = turn_content_value(&input.content);
    if serde_json::to_vec(&content)
        .map_err(|_| RuntimeCommandError::invalid_params())?
        .len()
        > 524_288
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(json!({
        "turnId": input.turn_id,
        "inputId": input.input_id,
        "expectedInputRevision": input.expected_input_revision,
        "content": content,
    }))
}

/// delete 不携带位置或 kind，防止陈旧 UI 通过本地顺序删除另一条记录。
fn turn_input_delete_params(input: &TurnInputDelete) -> Result<Value, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    Ok(json!({
        "turnId": input.turn_id,
        "inputId": input.input_id,
        "expectedInputRevision": input.expected_input_revision,
    }))
}

/// Approval JSON 只在 pending registry 所属 infrastructure 中构造，不把私有 request id 暴露给 domain。
pub(crate) fn approval_params(input: &ApprovalResponseInput) -> Result<Value, RuntimeCommandError> {
    input
        .validate()
        .map_err(|_| RuntimeCommandError::invalid_params())?;
    Ok(json!({
        "approvalId": input.approval_id,
        "turnId": input.turn_id,
        "decision": input.decision,
        "expectedThreadRevision": input.expected_thread_revision,
    }))
}
