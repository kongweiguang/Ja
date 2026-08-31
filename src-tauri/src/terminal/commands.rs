// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// PTY supervisor 的 Tauri command/state 适配器。
//
// 本适配器只持有已配置 workspace 的 supervisor；PTY 创建、有界队列和进程树
// 回收继续由 terminal 内部模块负责，避免 command 层形成第二套终端实现。

use super::error::{TerminalError, TerminalErrorCode};
use super::model::{
    CloseReason, LaunchRequest, ShellProfile, TerminalEvent, TerminalId, TerminalSize,
};
use super::native_drop::quote_native_paths;
use super::policy::{TerminalPolicy, available_shell_profiles};
use super::session::{SessionHandle, TerminalSupervisor};
use crate::app_runtime::RuntimeHost;
use crate::workspace::{WorkspaceError, consume_native_drop};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

const MAX_WORKSPACE_ID_BYTES: usize = 128;
const MAX_RELATIVE_CWD_BYTES: usize = 4_096;
const MAX_POLL_WORKERS: usize = 8;
const MAX_CLOSE_WORKERS: usize = 8;
const HOST_POISON_CLEANUP_TIMEOUT: Duration = Duration::from_secs(30);

/// 把不透明 workspace identity 解析为原生侧持有的 canonical root。
///
/// 该端口让 terminal policy 不依赖 `RuntimeHost` 具体类型，并明确唯一特权输入边界：
/// renderer 只能提交 id，真实 root 始终由原生 resolver 提供。
pub trait TerminalWorkspaceResolver {
    /// 只解析 workspace identity；实现必须把原生 root 保持为私有状态。
    fn resolve_terminal_workspace(&self, workspace_id: &str) -> Result<PathBuf, TerminalError>;
}

impl TerminalWorkspaceResolver for RuntimeHost {
    /// 复用 `RuntimeHost` 已配置的 workspace binding，防止 Terminal 与 Files/Git 接受不同
    /// renderer 路径；解析空相对目录还会重新校验 registry 中的物理 root identity。
    fn resolve_terminal_workspace(&self, workspace_id: &str) -> Result<PathBuf, TerminalError> {
        self.with_configured_workspace(workspace_id, |workspace| workspace.resolve_directory(""))
            .map_err(|_| TerminalError::new(TerminalErrorCode::InvalidConfig))?
            .map_err(|_| TerminalError::new(TerminalErrorCode::InvalidConfig))
    }
}

/// 受管终端状态；共享 lifecycle 锁围绕唯一 PTY owner 串行化 open、workspace close 与应用退出。
#[derive(Clone)]
pub struct TerminalCommandHost {
    pub(crate) lifecycle: Arc<Mutex<TerminalHostLifecycle>>,
    pub(crate) failed: Arc<AtomicBool>,
    poll_workers: Arc<tokio::sync::Semaphore>,
    close_workers: Arc<tokio::sync::Semaphore>,
}

/// `shutdown_started` 是永久 admission fence；保留 supervisor 表示回收失败，重复 shutdown
/// 必须继续处理同一批 owner。
#[derive(Default)]
pub(crate) struct TerminalHostLifecycle {
    pub(crate) supervisor: Option<ConfiguredSupervisor>,
    pub(crate) shutdown_started: bool,
}

pub(crate) struct ConfiguredSupervisor {
    pub(crate) workspace_id: String,
    pub(crate) workspace_root: PathBuf,
    pub(crate) supervisor: TerminalSupervisor,
}

impl Default for TerminalCommandHost {
    /// 使用固定 poll/close worker budget 创建 host，renderer 请求不能扩张原生阻塞并发或另建 owner。
    fn default() -> Self {
        Self {
            lifecycle: Arc::new(Mutex::new(TerminalHostLifecycle::default())),
            failed: Arc::new(AtomicBool::new(false)),
            poll_workers: Arc::new(tokio::sync::Semaphore::new(MAX_POLL_WORKERS)),
            close_workers: Arc::new(tokio::sync::Semaphore::new(MAX_CLOSE_WORKERS)),
        }
    }
}

impl TerminalCommandHost {
    /// 创建未绑定 workspace 的 host，启动阶段不能猜测业务 workspace。
    pub fn new() -> Self {
        Self::default()
    }

    /// 配置一个原生解析的 workspace，并在 binding 完全一致时复用现有实例。
    ///
    /// 切换 workspace 必须显式经过 `close_all`；相同 id 也只有在旧 supervisor 为空后才能
    /// 绑定新 canonical root，避免 stale renderer 搬动仍存活的 PTY。
    pub fn configure(
        &self,
        workspace_id: String,
        workspace_root: PathBuf,
    ) -> Result<(), TerminalError> {
        validate_workspace_id(&workspace_id)?;
        let (workspace_root, policy) = terminal_policy_for_root(workspace_root)?;
        let mut lifecycle = self.lock_lifecycle(Self::poison_cleanup_deadline())?;
        if lifecycle.shutdown_started {
            return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
        }
        Self::bind_supervisor(
            &mut lifecycle.supervisor,
            workspace_id,
            workspace_root,
            policy,
        )
        .map(|_| ())
    }

    /// 为 canonical workspace binding 选择唯一原生 supervisor。
    ///
    /// 调用方必须持有 host mutex；一致 binding 复用 supervisor 以支持多 pane，拒绝存活期间
    /// 的 root 变化则保证每个 pane 始终留在最初安全边界内。
    fn bind_supervisor(
        slot: &mut Option<ConfiguredSupervisor>,
        workspace_id: String,
        workspace_root: PathBuf,
        policy: TerminalPolicy,
    ) -> Result<TerminalSupervisor, TerminalError> {
        if let Some(current) = slot.as_ref() {
            if current.workspace_id == workspace_id && current.workspace_root == workspace_root {
                return Ok(current.supervisor.clone());
            }
            if current.workspace_id != workspace_id || current.supervisor.active_count() != 0 {
                return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
            }
        }
        let supervisor = TerminalSupervisor::new(policy);
        *slot = Some(ConfiguredSupervisor {
            workspace_id,
            workspace_root,
            supervisor: supervisor.clone(),
        });
        Ok(supervisor)
    }

    /// 只有原子选择原生 workspace binding 后才创建 session。
    ///
    /// `supervisor.open` 期间刻意保持 host mutex；否则 root 重绑定可能插入一个脱离 command
    /// host 的 PTY，使后续 close/input 无法可靠定位 owner。
    pub fn open(
        &self,
        workspace_id: &str,
        workspace_root: PathBuf,
        request: LaunchRequest,
    ) -> Result<SessionHandle, TerminalError> {
        validate_workspace_id(workspace_id)?;
        let (workspace_root, policy) = terminal_policy_for_root(workspace_root)?;
        let mut lifecycle = self.lock_lifecycle(Self::poison_cleanup_deadline())?;
        if lifecycle.shutdown_started {
            return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
        }
        let supervisor = Self::bind_supervisor(
            &mut lifecycle.supervisor,
            workspace_id.to_owned(),
            workspace_root,
            policy,
        )?;
        supervisor.open(request)
    }

    /// workspace 切换前关闭其全部 PTY；只有进程树与 worker 都确认退出后才清空 supervisor，
    /// 因此失败 owner 始终可观察并可重试。
    pub fn close_all(&self, workspace_id: &str, deadline: Instant) -> Result<(), TerminalError> {
        validate_workspace_id(workspace_id)?;
        let mut lifecycle = self.lock_lifecycle(deadline)?;
        if lifecycle.shutdown_started {
            return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
        }
        let Some(current) = lifecycle.supervisor.as_ref() else {
            return Ok(());
        };
        if current.workspace_id != workspace_id {
            return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
        }
        // shutdown 完成前持续锁定 binding，防止并发 open 在 supervisor 快照后插入孤儿 PTY。
        current.supervisor.shutdown_until(deadline)?;
        lifecycle.supervisor = None;
        Ok(())
    }

    /// 永久关闭 terminal admission，并在调用方 deadline 内关闭全部 PTY；失败 owner 保留供幂等重试。
    ///
    /// 回收期间保持 lifecycle mutex，保证 open 要么在 shutdown 快照前完成，要么观察到 fence。
    pub fn shutdown_until(&self, deadline: Instant) -> Result<(), TerminalError> {
        let mut lifecycle = self.lock_lifecycle(deadline)?;
        lifecycle.shutdown_started = true;
        let result = lifecycle
            .supervisor
            .as_ref()
            .map_or(Ok(()), |value| value.supervisor.shutdown_until(deadline));
        if result.is_ok() {
            lifecycle.supervisor = None;
        }
        result
    }

    /// 只报告 host 是否已不再持有终端进程，不暴露内部 session 集合。
    pub fn is_empty(&self) -> bool {
        match self.lock_lifecycle(Self::poison_cleanup_deadline()) {
            Ok(lifecycle) => lifecycle
                .supervisor
                .as_ref()
                .is_none_or(|configured| configured.supervisor.active_count() == 0),
            Err(_) => false,
        }
    }

    /// 尝试预留一个有界原生 worker；长 poll 饱和时立即失败，不能在现有 pane 后无限排队。
    fn try_poll_permit(&self) -> Result<tokio::sync::OwnedSemaphorePermit, TerminalError> {
        self.poll_workers
            .clone()
            .try_acquire_owned()
            .map_err(|_| TerminalError::new(TerminalErrorCode::QueueFull))
    }

    /// 仅在检查 shutdown fence 与查找 owner 时持有 host mutex，并捕获已准入 generation；
    /// 返回的 clone 不携带 lifecycle guard，PTY 回收不会阻塞 workspace close 或应用退出。
    pub(crate) fn close_owner(
        &self,
        session_id: TerminalId,
        generation: u64,
    ) -> Result<TerminalCloseOwner, TerminalError> {
        let lifecycle = self.lock_lifecycle(Self::poison_cleanup_deadline())?;
        if lifecycle.shutdown_started {
            return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
        }
        let supervisor = lifecycle
            .supervisor
            .as_ref()
            .map(|configured| configured.supervisor.clone())
            .ok_or(TerminalError::new(TerminalErrorCode::InvalidConfig))?;
        let session = supervisor.get(session_id, generation)?;
        Ok(TerminalCloseOwner {
            supervisor,
            session,
        })
    }

    /// host lifecycle poison 会使 workspace binding 与 shutdown fence 的线性化点失效；
    /// 首次检测必须先执行资源关闭，之后所有命令永久失败关闭。
    fn lock_lifecycle(
        &self,
        deadline: Instant,
    ) -> Result<MutexGuard<'_, TerminalHostLifecycle>, TerminalError> {
        if self.failed.load(Ordering::Acquire) {
            return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
        }
        match self.lifecycle.lock() {
            Ok(lifecycle) => Ok(lifecycle),
            Err(error) => {
                drop(error);
                self.fail_closed_lifecycle(deadline);
                Err(TerminalError::new(TerminalErrorCode::InvalidConfig))
            }
        }
    }

    /// 重建只保留“shutdown 已开始”不变量：取出 supervisor 后有界关闭，
    /// 失败 owner 放回关闭态 lifecycle，禁止资源因局部重建而丢失。
    fn fail_closed_lifecycle(&self, deadline: Instant) {
        if self.failed.swap(true, Ordering::AcqRel) {
            return;
        }
        self.lifecycle.clear_poison();
        let configured = match self.lifecycle.lock() {
            Ok(mut lifecycle) => {
                lifecycle.shutdown_started = true;
                lifecycle.supervisor.take()
            }
            Err(_) => None,
        };
        let Some(configured) = configured else {
            return;
        };
        if configured.supervisor.shutdown_until(deadline).is_err()
            && let Ok(mut lifecycle) = self.lifecycle.lock()
        {
            lifecycle.supervisor = Some(configured);
        }
    }

    /// 非 shutdown 命令使用固定上限处理 poison 清理，不把异常锁变成无界阻塞。
    fn poison_cleanup_deadline() -> Instant {
        Instant::now()
            .checked_add(HOST_POISON_CLEANUP_TIMEOUT)
            .unwrap_or_else(|| Instant::now() + Duration::from_secs(30))
    }
}

/// 持有 host 已准入的精确 supervisor generation，但不长期持有 lifecycle mutex；并发
/// close-all 即使先移除 map entry，捕获的 handle 仍会完成同一条幂等 runtime close。
pub(crate) struct TerminalCloseOwner {
    supervisor: TerminalSupervisor,
    session: SessionHandle,
}

impl TerminalCloseOwner {
    /// 关闭并移除已捕获 owner；只有本对象已验证精确 id/generation 后，`SessionNotFound`
    /// 才能回退为幂等成功，伪造或 stale identity 不能借此关闭其它 session。
    pub(crate) fn close(self) -> Result<(), TerminalError> {
        match self.supervisor.close(
            self.session.id(),
            self.session.generation(),
            CloseReason::User,
        ) {
            Err(error) if error.code() == TerminalErrorCode::SessionNotFound => {
                self.session.close(CloseReason::User)
            }
            result => result,
        }
    }
}

/// renderer 只能提交不透明 workspace id、allow-listed profile、相对 cwd 和有界 viewport；
/// executable/environment 注入不属于前端契约。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerminalOpenInput {
    pub workspace_id: String,
    #[serde(default)]
    pub profile: ShellProfile,
    #[serde(default)]
    pub relative_cwd: Option<String>,
    #[serde(default)]
    pub size: TerminalSize,
}

/// 在替换原生 binding 前关闭一个 workspace 的全部 PTY。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerminalCloseAllInput {
    pub workspace_id: String,
}

/// 标识一个已打开终端，但不向 WebView 暴露原生 handle。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TerminalSessionInfo {
    pub session_id: TerminalId,
    pub generation: u64,
}

/// 定位已配置 supervisor 持有的终端。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerminalInput {
    pub session_id: TerminalId,
    pub generation: u64,
    pub data: Vec<u8>,
}

/// 标识一个 PTY 和一个原生签发的不透明 drag/drop capability；不接受 renderer 路径、
/// workspace root、命令字符串或 executable。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerminalDropInput {
    pub session_id: TerminalId,
    pub generation: u64,
    pub drop_token: String,
}

/// 每次最多轮询一条有界事件，等待时间不会超过五秒。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerminalPollInput {
    pub session_id: TerminalId,
    pub generation: u64,
    #[serde(default)]
    pub timeout_ms: u64,
}

/// 对原生 root 做一次 canonicalize 后同时用于 host identity 与 policy，保证 binding 和 cwd
/// containment 使用同一物理路径基准。
fn terminal_policy_for_root(
    workspace_root: PathBuf,
) -> Result<(PathBuf, TerminalPolicy), TerminalError> {
    let workspace_root = std::fs::canonicalize(workspace_root)
        .map_err(|_| TerminalError::new(TerminalErrorCode::InvalidCwd))?;
    let policy = TerminalPolicy::new(&workspace_root)?;
    Ok((workspace_root, policy))
}

/// 校验不透明 id 的长度和字符闭集，禁止把它解释为路径或命令。
pub(crate) fn validate_workspace_id(workspace_id: &str) -> Result<(), TerminalError> {
    if workspace_id.is_empty()
        || workspace_id.len() > MAX_WORKSPACE_ID_BYTES
        || !workspace_id.starts_with("ws_")
        || !workspace_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
    {
        return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
    }
    Ok(())
}

/// 在构造 `PathBuf` 前拒绝绝对、rooted 或父级穿越 cwd；policy 随后还会 canonicalize
/// 已存在目录并再次检查物理 containment。
pub(crate) fn parse_relative_cwd(value: Option<String>) -> Result<Option<PathBuf>, TerminalError> {
    let Some(value) = value else {
        return Ok(None);
    };
    if value.is_empty() || value.len() > MAX_RELATIVE_CWD_BYTES {
        return Err(TerminalError::new(TerminalErrorCode::InvalidCwd));
    }
    let path = PathBuf::from(value);
    if path.is_absolute()
        || path.has_root()
        || path
            .components()
            .any(|component| matches!(component, std::path::Component::ParentDir))
    {
        return Err(TerminalError::new(TerminalErrorCode::CwdOutsideWorkspace));
    }
    Ok(Some(path))
}

/// shutdown 路径没有独立 UI DTO，因此复用稳定、脱敏的终端错误类型。
pub type TerminalShutdownError = TerminalError;

/// 查询当前平台真实可启动的 profile；响应只含闭集枚举，不暴露原生路径或参数。
#[tauri::command]
pub fn ja_terminal_profiles() -> Vec<ShellProfile> {
    available_shell_profiles()
}

/// 通过原生 workspace owner 打开一个 portable-pty session，并返回不透明 identity。
#[tauri::command]
pub fn ja_terminal_open(
    input: TerminalOpenInput,
    state: tauri::State<'_, TerminalCommandHost>,
    runtime: tauri::State<'_, RuntimeHost>,
) -> Result<TerminalSessionInfo, TerminalError> {
    validate_workspace_id(&input.workspace_id)?;
    let cwd = parse_relative_cwd(input.relative_cwd)?;
    let root = runtime.resolve_terminal_workspace(&input.workspace_id)?;
    let request = LaunchRequest {
        profile: input.profile,
        cwd,
        env: Default::default(),
        size: input.size,
    };
    let handle = state.open(&input.workspace_id, root, request)?;
    Ok(TerminalSessionInfo {
        session_id: handle.id(),
        generation: handle.generation(),
    })
}

/// 用同一个绝对 deadline 关闭 workspace 持有的全部 session，避免逐个延长退出预算。
#[tauri::command]
pub async fn ja_terminal_close_all(
    input: TerminalCloseAllInput,
    state: tauri::State<'_, TerminalCommandHost>,
) -> Result<(), TerminalError> {
    let state = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let deadline = Instant::now()
            .checked_add(Duration::from_secs(10))
            .ok_or(TerminalError::new(TerminalErrorCode::DeadlineExceeded))?;
        state.close_all(&input.workspace_id, deadline)
    })
    .await
    .map_err(|_| TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout))?
}

/// 通过 session 的 single writer queue 发送有界 raw bytes，保持输入顺序。
#[tauri::command]
pub fn ja_terminal_input(
    input: TerminalInput,
    state: tauri::State<'_, TerminalCommandHost>,
) -> Result<(), TerminalError> {
    state.with_any_supervisor(|supervisor| {
        supervisor
            .get(input.session_id, input.generation)?
            .send_input(&input.data, Duration::from_secs(5))
    })
}

/// 把安全引用的原生路径插入存活 PTY，但不追加命令终止符；消费 token 前必须先验证 session owner。
#[tauri::command]
pub fn ja_terminal_drop(
    input: TerminalDropInput,
    state: tauri::State<'_, TerminalCommandHost>,
) -> Result<(), TerminalError> {
    terminal_drop(state.inner(), &input)
}

/// 将 command 编排放在 Tauri glue 之外，使“先验 owner、后消费 token”和 single-use 不变量可直接测试。
pub(crate) fn terminal_drop(
    state: &TerminalCommandHost,
    input: &TerminalDropInput,
) -> Result<(), TerminalError> {
    let session = state.owned_session(input.session_id, input.generation)?;
    consume_quote_and_send(session.profile(), &input.drop_token, |data| {
        session.send_input(data, Duration::from_secs(5))
    })
}

/// 先消费 token，再对全部路径完成引用；只有所有路径都可表示时才调用 writer，过期、重放或
/// malformed token 必须保持 zero-write。
pub(crate) fn consume_quote_and_send(
    profile: ShellProfile,
    drop_token: &str,
    send: impl FnOnce(&[u8]) -> Result<(), TerminalError>,
) -> Result<(), TerminalError> {
    let paths = consume_native_drop(drop_token).map_err(map_native_drop_error)?;
    let quoted = quote_native_paths(profile, &paths)?;
    send(&quoted)
}

/// 将 workspace token 失败映射为终端专用、路径脱敏的稳定分类。
fn map_native_drop_error(error: WorkspaceError) -> TerminalError {
    match error {
        WorkspaceError::DropTokenInvalid => TerminalError::new(TerminalErrorCode::DropTokenInvalid),
        _ => TerminalError::new(TerminalErrorCode::InvalidConfig),
    }
}

/// 通过 session worker 共享的 latest-value queue 调整 PTY，避免 resize 请求堆积。
#[tauri::command]
pub fn ja_terminal_resize(
    input: TerminalResizeInput,
    state: tauri::State<'_, TerminalCommandHost>,
) -> Result<(), TerminalError> {
    state.with_any_supervisor(|supervisor| {
        supervisor
            .get(input.session_id, input.generation)?
            .resize(input.size)
    })
}

/// 每次只轮询一条 output/control event，避免把 Tauri command queue 变成无界 stream；
/// Tab 存活期间由前端重复调用。
#[tauri::command]
pub async fn ja_terminal_poll(
    input: TerminalPollInput,
    state: tauri::State<'_, TerminalCommandHost>,
) -> Result<Option<TerminalEvent>, TerminalError> {
    let timeout = Duration::from_millis(input.timeout_ms.min(5_000));
    let deadline = Instant::now()
        .checked_add(timeout)
        .ok_or(TerminalError::new(TerminalErrorCode::DeadlineExceeded))?;
    let permit = state.try_poll_permit()?;
    let handle = state
        .with_any_supervisor(|supervisor| supervisor.get(input.session_id, input.generation))?;
    tauri::async_runtime::spawn_blocking(move || {
        let _permit = permit;
        handle.recv_until(deadline)
    })
    .await
    .map_err(|_| TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout))?
}

/// 为 reload/reconnect 返回有界 scrollback，并保留 PTY 原始 bytes 不做解码。
#[tauri::command]
pub fn ja_terminal_scrollback(
    input: TerminalPollInput,
    state: tauri::State<'_, TerminalCommandHost>,
) -> Result<Vec<u8>, TerminalError> {
    state.with_any_supervisor(|supervisor| {
        supervisor
            .get(input.session_id, input.generation)
            .and_then(|handle| handle.scrollback())
    })
}

/// 在有界 blocking worker 中关闭 session；先在 host mutex 下捕获已准入 generation，释放锁后
/// 才等待 PTY/进程回收，避免最长 30 秒清理预算阻塞 workspace lifecycle。
#[tauri::command]
pub async fn ja_terminal_close(
    input: TerminalCloseInput,
    state: tauri::State<'_, TerminalCommandHost>,
) -> Result<(), TerminalError> {
    let state = state.inner().clone();
    run_bounded_terminal_close(state.close_workers.clone(), move || {
        state
            .close_owner(input.session_id, input.generation)?
            .close()
    })
    .await
}

/// 限制并发原生 close worker，饱和时不进行同步阻塞或增长 async waiter queue；permit 随
/// blocking closure 持有到有界 PTY 回收结束。
pub(crate) async fn run_bounded_terminal_close(
    workers: Arc<tokio::sync::Semaphore>,
    operation: impl FnOnce() -> Result<(), TerminalError> + Send + 'static,
) -> Result<(), TerminalError> {
    let permit = workers
        .try_acquire_owned()
        .map_err(|_| TerminalError::new(TerminalErrorCode::QueueFull))?;
    tauri::async_runtime::spawn_blocking(move || {
        let _permit = permit;
        operation()
    })
    .await
    .map_err(|_| TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout))?
}

impl TerminalCommandHost {
    /// 只从当前原生 workspace 绑定的 supervisor 解析 generation；伪造或 stale identity
    /// 必须在消费 drop token 前失败。
    fn owned_session(
        &self,
        session_id: TerminalId,
        generation: u64,
    ) -> Result<SessionHandle, TerminalError> {
        let lifecycle = self.lock_lifecycle(Self::poison_cleanup_deadline())?;
        if lifecycle.shutdown_started {
            return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
        }
        let (workspace_id, supervisor) = lifecycle
            .supervisor
            .as_ref()
            .map(|configured| {
                (
                    configured.workspace_id.clone(),
                    configured.supervisor.clone(),
                )
            })
            .ok_or(TerminalError::new(TerminalErrorCode::InvalidConfig))?;
        validate_workspace_id(&workspace_id)?;
        supervisor.get(session_id, generation)
    }

    /// session identity command 只使用已配置的原生 supervisor；workspace 切换会先执行
    /// close-all，因此这里不接受路径参数。
    fn with_any_supervisor<T>(
        &self,
        operation: impl FnOnce(&TerminalSupervisor) -> Result<T, TerminalError>,
    ) -> Result<T, TerminalError> {
        let lifecycle = self.lock_lifecycle(Self::poison_cleanup_deadline())?;
        if lifecycle.shutdown_started {
            return Err(TerminalError::new(TerminalErrorCode::InvalidConfig));
        }
        let supervisor = lifecycle
            .supervisor
            .as_ref()
            .map(|configured| configured.supervisor.clone())
            .ok_or(TerminalError::new(TerminalErrorCode::InvalidConfig))?;
        operation(&supervisor)
    }
}

/// 用不透明 identity 定位需要 viewport resize 的 PTY。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerminalResizeInput {
    pub session_id: TerminalId,
    pub generation: u64,
    pub size: TerminalSize,
}

/// 用不透明 identity 定位待关闭 PTY，不允许 renderer 注入任意 close reason。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TerminalCloseInput {
    pub session_id: TerminalId,
    pub generation: u64,
}
