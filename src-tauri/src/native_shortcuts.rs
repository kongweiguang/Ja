// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Windows WebView2 内部快捷键桥，只向受信任的 main WebView 投递固定动作。

use serde::{Deserialize, Serialize};
#[cfg(windows)]
use std::collections::{HashMap, HashSet};
use std::fmt::{Display, Formatter};
#[cfg(windows)]
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Instant;
#[cfg(windows)]
use tauri::{Emitter, EventTarget, Manager};
use uuid::Uuid;

#[cfg(windows)]
use std::time::Duration;
#[cfg(windows)]
use webview2_com::{
    AcceleratorKeyPressedEventHandler,
    Microsoft::Web::WebView2::Win32::{
        COREWEBVIEW2_KEY_EVENT_KIND, COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN,
        COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN, COREWEBVIEW2_PHYSICAL_KEY_STATUS,
        ICoreWebView2AcceleratorKeyPressedEventArgs, ICoreWebView2Controller,
    },
};
#[cfg(windows)]
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyState, VK_CONTROL, VK_LWIN, VK_MENU, VK_RMENU, VK_RWIN, VK_SHIFT,
};

pub const NATIVE_SHORTCUT_EVENT: &str = "ja://native-shortcut";
pub const NATIVE_SHORTCUT_STATUS_EVENT: &str = "ja://native-shortcut-status";
pub const MAIN_WEBVIEW_LABEL: &str = "main";

pub(crate) const MAX_SAFE_JS_REVISION: u64 = 9_007_199_254_740_991;
#[cfg(windows)]
const MAX_NATIVE_REGISTRATIONS: usize = 9;
#[cfg(windows)]
const REGISTRATION_TIMEOUT: Duration = Duration::from_secs(2);

#[cfg(windows)]
pub(crate) const VK_G: u32 = 0x47;
#[cfg(windows)]
pub(crate) const VK_P: u32 = 0x50;
#[cfg(windows)]
pub(crate) const VK_S: u32 = 0x53;
#[cfg(windows)]
pub(crate) const VK_T: u32 = 0x54;
#[cfg(windows)]
pub(crate) const VK_OEM_3: u32 = 0xC0;

/// 仅允许工作台已经公开的五个动作跨越原生事件边界。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NativeShortcutCommand {
    Review,
    Files,
    Terminal,
    Preview,
    SideChat,
}

/// main WebView 用严格递增 revision 更新可拦截能力，不传路径或任意命令名。
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NativeShortcutContextInput {
    pub epoch: String,
    pub revision: u64,
    pub project_capabilities_enabled: bool,
    pub conversation_focus_enabled: bool,
}

/// main WebView 只可激活刚由 prepare ACK 确认的 exact epoch/revision。
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NativeShortcutActivationInput {
    pub epoch: String,
    pub revision: u64,
}

/// 回读当前 renderer lease 与上下文，epoch/revision 必须同时匹配事件。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeShortcutContextSnapshot {
    pub epoch: String,
    pub ready: bool,
    pub revision: u64,
    pub project_capabilities_enabled: bool,
    pub conversation_focus_enabled: bool,
    pub main_handler_status: NativeShortcutHandlerStatus,
}

/// renderer 在 listener 全部注册后查询 Rust 签发的当前 epoch，再走 prepare/activate 两阶段。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeShortcutLeaseSnapshot {
    pub epoch: String,
    pub ready: bool,
    pub revision: u64,
    pub main_handler_status: NativeShortcutHandlerStatus,
}

/// 原生 handler 状态不包含 COM 错误或页面信息。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum NativeShortcutHandlerStatus {
    Pending,
    Ready,
    Unavailable,
    Unsupported,
}

/// 每次命中只投递动作与产生该判断的上下文 revision。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeShortcutEvent {
    pub epoch: String,
    pub command: NativeShortcutCommand,
    pub revision: u64,
}

/// 安装失败事件固定为无诊断负载，避免 WebView2 细节进入 renderer。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum NativeShortcutStatusEvent {
    Unavailable,
}

/// IPC 与安装层共用稳定、脱敏的错误分类。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum NativeShortcutErrorCode {
    CallerNotAllowed,
    InvalidEpoch,
    StaleEpoch,
    InvalidRevision,
    StaleRevision,
    ShuttingDown,
    StateUnavailable,
    RegistrationLimit,
    RegistrationInProgress,
    RegistrationStale,
    RegistrationFailed,
    RegistrationTimeout,
}

/// 错误只携带固定枚举，禁止把 label、URL 或 COM 诊断返回 WebView。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct NativeShortcutError {
    pub code: NativeShortcutErrorCode,
}

impl NativeShortcutError {
    /// 在最近责任边界构造固定错误，避免自由文本扩散到 IPC。
    pub(crate) const fn new(code: NativeShortcutErrorCode) -> Self {
        Self { code }
    }
}

impl Display for NativeShortcutError {
    /// 仅为 Rust 日志提供静态描述，不包含调用方或原生诊断。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self.code {
            NativeShortcutErrorCode::CallerNotAllowed => "native shortcut caller is not allowed",
            NativeShortcutErrorCode::InvalidEpoch => "native shortcut epoch is invalid",
            NativeShortcutErrorCode::StaleEpoch => "native shortcut epoch is stale",
            NativeShortcutErrorCode::InvalidRevision => "native shortcut revision is invalid",
            NativeShortcutErrorCode::StaleRevision => "native shortcut revision is stale",
            NativeShortcutErrorCode::ShuttingDown => "native shortcuts are shutting down",
            NativeShortcutErrorCode::StateUnavailable => "native shortcut state is unavailable",
            NativeShortcutErrorCode::RegistrationLimit => {
                "native shortcut registration limit reached"
            }
            NativeShortcutErrorCode::RegistrationInProgress => {
                "native shortcut registration is already in progress"
            }
            NativeShortcutErrorCode::RegistrationStale => {
                "native shortcut registration attempt is stale"
            }
            NativeShortcutErrorCode::RegistrationFailed => "native shortcut registration failed",
            NativeShortcutErrorCode::RegistrationTimeout => {
                "native shortcut registration timed out"
            }
        })
    }
}

impl std::error::Error for NativeShortcutError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct NativeShortcutContext {
    pub(crate) revision: u64,
    pub(crate) project_capabilities_enabled: bool,
    pub(crate) conversation_focus_enabled: bool,
}

impl Default for NativeShortcutContext {
    /// 初始关闭全部能力，避免 renderer 尚未声明上下文时吞掉按键。
    fn default() -> Self {
        Self {
            revision: 0,
            project_capabilities_enabled: false,
            conversation_focus_enabled: false,
        }
    }
}

impl NativeShortcutContext {
    /// 项目动作与会话聚焦使用不同开关，无项目时不会拦截文件/终端等快捷键。
    pub(crate) fn enables(self, command: NativeShortcutCommand) -> bool {
        match command {
            NativeShortcutCommand::Review
            | NativeShortcutCommand::Files
            | NativeShortcutCommand::Terminal
            | NativeShortcutCommand::Preview => self.project_capabilities_enabled,
            NativeShortcutCommand::SideChat => self.conversation_focus_enabled,
        }
    }
}

#[cfg(windows)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum NativeKeyEventKind {
    KeyDown,
    SystemKeyDown,
    Other,
}

#[cfg(windows)]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct NativeModifiers {
    pub(crate) control: bool,
    pub(crate) shift: bool,
    pub(crate) alt: bool,
    pub(crate) windows: bool,
    pub(crate) right_alt: bool,
}

#[cfg(windows)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct NativeKeyInput {
    pub(crate) kind: NativeKeyEventKind,
    pub(crate) virtual_key: u32,
    pub(crate) repeat_count: u32,
    pub(crate) was_key_down: bool,
    pub(crate) modifiers: NativeModifiers,
}

/// 纯分类器拒绝 key-up、自动重复、AltGr 和任何额外 modifier，只返回固定五值。
#[cfg(windows)]
pub(crate) fn classify_shortcut(input: NativeKeyInput) -> Option<NativeShortcutCommand> {
    if !matches!(
        input.kind,
        NativeKeyEventKind::KeyDown | NativeKeyEventKind::SystemKeyDown
    ) || input.repeat_count != 1
        || input.was_key_down
        || input.modifiers.right_alt
        || input.modifiers.windows
    {
        return None;
    }
    let modifiers = input.modifiers;
    match input.virtual_key {
        VK_G if modifiers.control && modifiers.shift && !modifiers.alt => {
            Some(NativeShortcutCommand::Review)
        }
        VK_P if modifiers.control && !modifiers.shift && !modifiers.alt => {
            Some(NativeShortcutCommand::Files)
        }
        VK_OEM_3 if modifiers.control && !modifiers.shift && !modifiers.alt => {
            Some(NativeShortcutCommand::Terminal)
        }
        VK_T if modifiers.control && !modifiers.shift && !modifiers.alt => {
            Some(NativeShortcutCommand::Preview)
        }
        VK_S if modifiers.control && !modifiers.shift && modifiers.alt => {
            Some(NativeShortcutCommand::SideChat)
        }
        _ => None,
    }
}

#[cfg(windows)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct NativeShortcutRegistrationIdentity {
    attempt: u64,
    // Token 只作为原生注册身份保留，绝不在 controller 的 apartment 线程之外解引用。
    _token: i64,
}

pub(crate) struct NativeShortcutState {
    pub(crate) accepting: bool,
    pub(crate) renderer_epoch: Uuid,
    pub(crate) renderer_ready: bool,
    pending_activation_revision: Option<u64>,
    pub(crate) context: NativeShortcutContext,
    main_handler_status: NativeShortcutHandlerStatus,
    #[cfg(windows)]
    next_installation_attempt: u64,
    #[cfg(windows)]
    pub(crate) installing: HashMap<String, u64>,
    #[cfg(windows)]
    pub(crate) registrations: HashMap<String, NativeShortcutRegistrationIdentity>,
    #[cfg(windows)]
    pub(crate) deferred_to_controller_close: HashSet<(String, u64)>,
}

impl Default for NativeShortcutState {
    /// Windows 等待 setup 安装 ACK；其他平台明确为 unsupported，并继续使用 renderer 快捷键。
    fn default() -> Self {
        Self {
            accepting: true,
            renderer_epoch: Uuid::new_v4(),
            renderer_ready: false,
            pending_activation_revision: None,
            context: NativeShortcutContext::default(),
            main_handler_status: if cfg!(windows) {
                NativeShortcutHandlerStatus::Pending
            } else {
                NativeShortcutHandlerStatus::Unsupported
            },
            #[cfg(windows)]
            next_installation_attempt: 0,
            #[cfg(windows)]
            installing: HashMap::new(),
            #[cfg(windows)]
            registrations: HashMap::new(),
            #[cfg(windows)]
            deferred_to_controller_close: HashSet::new(),
        }
    }
}

/// 进程内唯一原生快捷键状态；不使用 global-shortcut 或任意命令字符串。
#[derive(Clone, Default)]
pub struct NativeShortcutHost {
    state: Arc<Mutex<NativeShortcutState>>,
}

impl NativeShortcutHost {
    /// main page-load started 时轮换 epoch 并立即关闭 ready/context，旧文档事件从此失效。
    pub(crate) fn rotate_main_renderer_lease(&self) -> Result<(), NativeShortcutError> {
        self.rotate_main_renderer_lease_to(Uuid::new_v4())
    }

    /// 使用显式 epoch 的窄 seam 让 reload/late-update 测试无需依赖随机值。
    pub(crate) fn rotate_main_renderer_lease_to(
        &self,
        epoch: Uuid,
    ) -> Result<(), NativeShortcutError> {
        let mut state = self.lock_state()?;
        if !state.accepting {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::ShuttingDown,
            ));
        }
        state.renderer_epoch = epoch;
        state.renderer_ready = false;
        state.pending_activation_revision = None;
        state.context = NativeShortcutContext::default();
        Ok(())
    }

    /// listener 注册完成后 renderer 查询当前 epoch；查询会先 suspend 旧 ready lease，
    /// 保留 epoch/revision 供调用方用 revision+1 重新激活。
    pub(crate) fn query_from_label(
        &self,
        caller_label: &str,
    ) -> Result<NativeShortcutLeaseSnapshot, NativeShortcutError> {
        Self::ensure_main_caller(caller_label)?;
        let mut state = self.lock_state()?;
        if !state.accepting {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::ShuttingDown,
            ));
        }
        state.renderer_ready = false;
        state.pending_activation_revision = None;
        Ok(Self::lease_snapshot_state(&state))
    }

    /// prepare 只接受当前 epoch 内严格递增的 main WebView 上下文，并保持 ready=false；
    /// renderer 收到 ACK、预置 exact event identity 后，才可调用 activate。
    pub(crate) fn update_from_label(
        &self,
        caller_label: &str,
        input: NativeShortcutContextInput,
    ) -> Result<NativeShortcutContextSnapshot, NativeShortcutError> {
        Self::ensure_main_caller(caller_label)?;
        let epoch = Uuid::parse_str(&input.epoch)
            .map_err(|_| NativeShortcutError::new(NativeShortcutErrorCode::InvalidEpoch))?;
        if input.revision == 0 || input.revision > MAX_SAFE_JS_REVISION {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::InvalidRevision,
            ));
        }
        let mut state = self.lock_state()?;
        if !state.accepting {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::ShuttingDown,
            ));
        }
        if epoch != state.renderer_epoch {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::StaleEpoch,
            ));
        }
        let next = NativeShortcutContext {
            revision: input.revision,
            project_capabilities_enabled: input.project_capabilities_enabled,
            conversation_focus_enabled: input.conversation_focus_enabled,
        };
        if next.revision <= state.context.revision {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::StaleRevision,
            ));
        }
        state.context = next;
        state.renderer_ready = false;
        state.pending_activation_revision = Some(next.revision);
        Ok(Self::snapshot_state(&state))
    }

    /// activate 消耗一次 prepare permit；query/reload/status/shutdown 都会撤销 permit，因此
    /// 晚到或重复 activate 不能把已经 suspend 的同 revision lease 重新打开。
    pub(crate) fn activate_from_label(
        &self,
        caller_label: &str,
        input: NativeShortcutActivationInput,
    ) -> Result<NativeShortcutContextSnapshot, NativeShortcutError> {
        Self::ensure_main_caller(caller_label)?;
        let epoch = Uuid::parse_str(&input.epoch)
            .map_err(|_| NativeShortcutError::new(NativeShortcutErrorCode::InvalidEpoch))?;
        if input.revision == 0 || input.revision > MAX_SAFE_JS_REVISION {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::InvalidRevision,
            ));
        }
        let mut state = self.lock_state()?;
        if !state.accepting {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::ShuttingDown,
            ));
        }
        if epoch != state.renderer_epoch {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::StaleEpoch,
            ));
        }
        if state.context.revision != input.revision
            || state.pending_activation_revision != Some(input.revision)
        {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::StaleRevision,
            ));
        }
        state.pending_activation_revision = None;
        state.renderer_ready = matches!(
            state.main_handler_status,
            NativeShortcutHandlerStatus::Pending | NativeShortcutHandlerStatus::Ready
        );
        Ok(Self::snapshot_state(&state))
    }

    /// label 是信任角色；所有 renderer lease/query/update 命令仅允许 main 调用。
    fn ensure_main_caller(caller_label: &str) -> Result<(), NativeShortcutError> {
        if caller_label == MAIN_WEBVIEW_LABEL {
            Ok(())
        } else {
            Err(NativeShortcutError::new(
                NativeShortcutErrorCode::CallerNotAllowed,
            ))
        }
    }

    #[cfg(windows)]
    /// handler 在一个锁周期内校验 attempt、ready epoch、enable 与 revision；旧 attempt
    /// 即使同 label 后续注册也不会重新激活。
    pub(crate) fn event_if_enabled_for_registration(
        &self,
        label: &str,
        attempt: u64,
        command: NativeShortcutCommand,
    ) -> Result<Option<NativeShortcutEvent>, NativeShortcutError> {
        let state = self.lock_state()?;
        if !state.accepting
            || !state.renderer_ready
            || state.main_handler_status != NativeShortcutHandlerStatus::Ready
            || state
                .registrations
                .get(label)
                .is_none_or(|registration| registration.attempt != attempt)
            || !state.context.enables(command)
        {
            return Ok(None);
        }
        Ok(Some(NativeShortcutEvent {
            epoch: state.renderer_epoch.to_string(),
            command,
            revision: state.context.revision,
        }))
    }

    /// 应用退出只撤销 admission/ready 并把未完成 attempt 转入 deferred；已注册 COM
    /// handler 不伪装成主动 remove，仍由 child close ACK 或最终 controller destroy 释放。
    pub fn shutdown_until(
        &self,
        _deadline: Instant,
    ) -> Result<NativeShortcutShutdownReport, NativeShortcutError> {
        #[cfg(windows)]
        let report = {
            let mut state = self.lock_state()?;
            state.accepting = false;
            state.renderer_ready = false;
            state.pending_activation_revision = None;
            state.context = NativeShortcutContext::default();
            state.main_handler_status = NativeShortcutHandlerStatus::Unavailable;
            let registered_callbacks_revoked = state.registrations.len();
            let installation_attempts_revoked = state.installing.len();
            let installing = std::mem::take(&mut state.installing);
            state.deferred_to_controller_close.extend(installing);
            NativeShortcutShutdownReport {
                admission_closed: true,
                registered_callbacks_revoked,
                installation_attempts_revoked,
                deferred_to_controller_close: state
                    .registrations
                    .len()
                    .saturating_add(state.deferred_to_controller_close.len()),
                registry_empty: state.registrations.is_empty()
                    && state.installing.is_empty()
                    && state.deferred_to_controller_close.is_empty(),
            }
        };
        #[cfg(not(windows))]
        let report = {
            let mut state = self.lock_state()?;
            state.accepting = false;
            state.renderer_ready = false;
            state.pending_activation_revision = None;
            state.context = NativeShortcutContext::default();
            state.main_handler_status = NativeShortcutHandlerStatus::Unsupported;
            NativeShortcutShutdownReport {
                admission_closed: true,
                registered_callbacks_revoked: 0,
                installation_attempts_revoked: 0,
                deferred_to_controller_close: 0,
                registry_empty: true,
            }
        };
        Ok(report)
    }

    /// 退出门禁只确认 callbacks 已惰性撤销且没有 active installation；它不声称 apartment
    /// 线程上的 COM handler 已经执行 remove。
    pub fn is_revoked_for_controller_close(&self) -> bool {
        self.lock_state()
            .map(|state| {
                #[cfg(windows)]
                {
                    !state.accepting && !state.renderer_ready && state.installing.is_empty()
                }
                #[cfg(not(windows))]
                {
                    !state.accepting && !state.renderer_ready
                }
            })
            .unwrap_or(false)
    }

    /// 锁中毒代表 token 所有权不再可信，必须以稳定错误失败关闭。
    pub(crate) fn lock_state(
        &self,
    ) -> Result<MutexGuard<'_, NativeShortcutState>, NativeShortcutError> {
        self.state
            .lock()
            .map_err(|_| NativeShortcutError::new(NativeShortcutErrorCode::StateUnavailable))
    }

    /// 从同一个锁快照生成 IPC DTO，避免状态字段来自不同 revision。
    fn snapshot_state(state: &NativeShortcutState) -> NativeShortcutContextSnapshot {
        NativeShortcutContextSnapshot {
            epoch: state.renderer_epoch.to_string(),
            ready: state.renderer_ready,
            revision: state.context.revision,
            project_capabilities_enabled: state.context.project_capabilities_enabled,
            conversation_focus_enabled: state.context.conversation_focus_enabled,
            main_handler_status: state.main_handler_status,
        }
    }

    /// lease query 只返回 epoch/readiness/revision 与 handler 状态，不提前激活上下文。
    fn lease_snapshot_state(state: &NativeShortcutState) -> NativeShortcutLeaseSnapshot {
        NativeShortcutLeaseSnapshot {
            epoch: state.renderer_epoch.to_string(),
            ready: state.renderer_ready,
            revision: state.context.revision,
            main_handler_status: state.main_handler_status,
        }
    }

    #[cfg(windows)]
    /// 安装 claim 签发进程内单调 attempt；deferred handler 仍占 slot，禁止同 label ABA。
    pub(crate) fn claim_installation(
        &self,
        label: &str,
    ) -> Result<Option<u64>, NativeShortcutError> {
        let mut state = self.lock_state()?;
        if !state.accepting {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::ShuttingDown,
            ));
        }
        if state.registrations.contains_key(label) {
            return Ok(None);
        }
        if state.installing.contains_key(label)
            || state
                .deferred_to_controller_close
                .iter()
                .any(|(deferred_label, _)| deferred_label == label)
        {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::RegistrationInProgress,
            ));
        }
        if state
            .registrations
            .len()
            .saturating_add(state.installing.len())
            .saturating_add(state.deferred_to_controller_close.len())
            >= MAX_NATIVE_REGISTRATIONS
        {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::RegistrationLimit,
            ));
        }
        let attempt = state
            .next_installation_attempt
            .checked_add(1)
            .ok_or_else(|| NativeShortcutError::new(NativeShortcutErrorCode::RegistrationLimit))?;
        state.next_installation_attempt = attempt;
        state.installing.insert(label.to_owned(), attempt);
        Ok(Some(attempt))
    }

    #[cfg(windows)]
    /// 只有仍在 accepting 且 attempt 精确匹配的 claim 能发布 registration identity。
    pub(crate) fn finish_installation(
        &self,
        label: &str,
        attempt: u64,
        token: i64,
    ) -> Result<(), NativeShortcutError> {
        let mut state = self.lock_state()?;
        if !state.accepting {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::ShuttingDown,
            ));
        }
        if state.installing.get(label).copied() != Some(attempt) {
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::RegistrationStale,
            ));
        }
        state.installing.remove(label);
        state.registrations.insert(
            label.to_owned(),
            NativeShortcutRegistrationIdentity {
                attempt,
                _token: token,
            },
        );
        if label == MAIN_WEBVIEW_LABEL {
            state.main_handler_status = NativeShortcutHandlerStatus::Ready;
        }
        Ok(())
    }

    #[cfg(windows)]
    /// 已确认 handler 未创建或已实际 remove 后释放 exact attempt；不会清除后续 attempt。
    fn release_failed_installation(
        &self,
        label: &str,
        attempt: u64,
    ) -> Result<(), NativeShortcutError> {
        let mut state = self.lock_state()?;
        let removed_installing = state.installing.get(label).copied() == Some(attempt);
        if removed_installing {
            state.installing.remove(label);
        }
        let removed_deferred = state
            .deferred_to_controller_close
            .remove(&(label.to_owned(), attempt));
        if label == MAIN_WEBVIEW_LABEL && (removed_installing || removed_deferred) {
            state.main_handler_status = NativeShortcutHandlerStatus::Unavailable;
            state.renderer_ready = false;
            state.pending_activation_revision = None;
            state.context = NativeShortcutContext::default();
        }
        Ok(())
    }

    #[cfg(windows)]
    /// timeout/receiver 消失时把 exact attempt 转为 deferred slot，直到原线程 remove 或
    /// WebView close ACK；不能仅清 map 就宣称 native handler 已释放。
    fn defer_installation(&self, label: &str, attempt: u64) -> Result<(), NativeShortcutError> {
        let mut state = self.lock_state()?;
        if state.installing.get(label).copied() == Some(attempt) {
            state.installing.remove(label);
            state
                .deferred_to_controller_close
                .insert((label.to_owned(), attempt));
        }
        if label == MAIN_WEBVIEW_LABEL {
            state.main_handler_status = NativeShortcutHandlerStatus::Unavailable;
            state.renderer_ready = false;
            state.pending_activation_revision = None;
            state.context = NativeShortcutContext::default();
        }
        Ok(())
    }

    #[cfg(windows)]
    /// UI-thread closure 在 add 后复核 attempt，shutdown/timeout 已撤销时立即 remove。
    fn installation_is_current(
        &self,
        label: &str,
        attempt: u64,
    ) -> Result<bool, NativeShortcutError> {
        let state = self.lock_state()?;
        Ok(state.accepting && state.installing.get(label).copied() == Some(attempt))
    }

    #[cfg(windows)]
    /// child native close ACK 后按 UUID label 释放全部 attempt/registration slot；PreviewId
    /// 每次由 UUID v4 新建且 label 不复用，因此这里不存在未来同 label 的 close ABA。
    pub(crate) fn uninstall_after_close(&self, label: &str) {
        if !is_preview_webview_label(label) {
            return;
        }
        if let Ok(mut state) = self.lock_state() {
            state.installing.remove(label);
            state.registrations.remove(label);
            state
                .deferred_to_controller_close
                .retain(|(deferred_label, _)| deferred_label != label);
        }
    }
}

#[cfg(windows)]
/// Preview label 由 UUID v4 派生（`preview_<simple uuid>`），因此 close ACK 可以释放该 label
/// 的全部 attempt，且不会与未来 child 身份冲突。
pub(crate) fn is_preview_webview_label(label: &str) -> bool {
    let Some(raw_uuid) = label.strip_prefix("preview_") else {
        return false;
    };
    Uuid::parse_str(raw_uuid)
        .ok()
        .is_some_and(|id| id.get_version_num() == 4 && id.simple().to_string() == raw_uuid)
}

/// 退出报告只描述 Rust gate/registry；`deferred_to_controller_close` 明确表示 COM handler
/// 没有主动 remove，仍等待 child close ACK 或最终 controller destroy。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NativeShortcutShutdownReport {
    pub admission_closed: bool,
    pub registered_callbacks_revoked: usize,
    pub installation_attempts_revoked: usize,
    pub deferred_to_controller_close: usize,
    pub registry_empty: bool,
}

/// renderer 在 listener 全部注册后查询当前 epoch；查询不会把 lease 标记为 ready。
#[tauri::command]
pub fn ja_native_shortcut_lease_query(
    webview: tauri::Webview,
    state: tauri::State<'_, NativeShortcutHost>,
) -> Result<NativeShortcutLeaseSnapshot, NativeShortcutError> {
    state.query_from_label(webview.label())
}

/// renderer 用 query 返回的 epoch + 严格递增 revision prepare 能力；此阶段保持 ready=false。
#[tauri::command]
pub fn ja_native_shortcut_context_update(
    input: NativeShortcutContextInput,
    webview: tauri::Webview,
    state: tauri::State<'_, NativeShortcutHost>,
) -> Result<NativeShortcutContextSnapshot, NativeShortcutError> {
    state.update_from_label(webview.label(), input)
}

/// renderer 收到 prepare ACK 并预置事件 identity 后，才消费 exact revision permit 激活。
#[tauri::command]
pub fn ja_native_shortcut_context_activate(
    input: NativeShortcutActivationInput,
    webview: tauri::Webview,
    state: tauri::State<'_, NativeShortcutHost>,
) -> Result<NativeShortcutContextSnapshot, NativeShortcutError> {
    state.activate_from_label(webview.label(), input)
}

#[cfg(windows)]
/// setup 只调度一次 main 安装；异步 COM 失败会定向通知 main，不阻塞 UI 线程等待自身消息泵。
pub(crate) fn schedule_main_installation(
    webview: tauri::Webview,
    app: tauri::AppHandle,
    host: NativeShortcutHost,
) -> Result<(), NativeShortcutError> {
    let Some(attempt) = host.claim_installation(MAIN_WEBVIEW_LABEL)? else {
        return Ok(());
    };
    tauri::async_runtime::spawn(async move {
        if install_claimed_webview(webview, app.clone(), host.clone(), attempt)
            .await
            .is_err()
        {
            let _ = app.emit_to(
                EventTarget::webview(MAIN_WEBVIEW_LABEL),
                NATIVE_SHORTCUT_STATUS_EVENT,
                NativeShortcutStatusEvent::Unavailable,
            );
        }
    });
    Ok(())
}

#[cfg(windows)]
/// Preview open 等待注册 ACK；失败时调用方必须关闭 child，不能显示“支持快捷键”的半成品页面。
pub(crate) async fn install_preview_webview(
    webview: tauri::Webview,
    app: tauri::AppHandle,
    host: NativeShortcutHost,
) -> Result<(), NativeShortcutError> {
    if !is_preview_webview_label(webview.label()) {
        return Err(NativeShortcutError::new(
            NativeShortcutErrorCode::RegistrationFailed,
        ));
    }
    let Some(attempt) = host.claim_installation(webview.label())? else {
        return Ok(());
    };
    install_claimed_webview(webview, app, host, attempt).await
}

#[cfg(windows)]
/// `with_webview` 在 UI 线程注册 handler；oneshot 只跨线程返回可发送的 token。
/// timeout/caller 消失时 callback 若仍持有 controller，会在原线程立即撤销刚注册的 handler。
async fn install_claimed_webview(
    webview: tauri::Webview,
    app: tauri::AppHandle,
    host: NativeShortcutHost,
    attempt: u64,
) -> Result<(), NativeShortcutError> {
    let label = webview.label().to_owned();
    let callback_label = label.clone();
    let callback_host = host.clone();
    let installation_active = Arc::new(AtomicBool::new(true));
    let callback_active = installation_active.clone();
    let shared_token = Arc::new(Mutex::new(None::<i64>));
    let callback_token = shared_token.clone();
    let (sender, receiver) = tokio::sync::oneshot::channel();
    if webview
        .with_webview(move |platform_webview| {
            let controller = platform_webview.controller();
            match install_controller(
                &controller,
                app,
                callback_host.clone(),
                callback_label.clone(),
                attempt,
            ) {
                Ok(token) => {
                    let token_stored = callback_token
                        .lock()
                        .map(|mut stored| *stored = Some(token))
                        .is_ok();
                    let attempt_current = callback_host
                        .installation_is_current(&callback_label, attempt)
                        .unwrap_or(false);
                    if !token_stored || !callback_active.load(Ordering::Acquire) || !attempt_current
                    {
                        settle_controller_removal(
                            &callback_host,
                            &callback_label,
                            attempt,
                            remove_controller_handler(&controller, token),
                        );
                        if let Ok(mut stored) = callback_token.lock() {
                            *stored = None;
                        }
                        return;
                    }
                    if let Err(Ok(orphaned_token)) = sender.send(Ok(token)) {
                        settle_controller_removal(
                            &callback_host,
                            &callback_label,
                            attempt,
                            remove_controller_handler(&controller, orphaned_token),
                        );
                        if let Ok(mut stored) = callback_token.lock() {
                            *stored = None;
                        }
                    }
                }
                Err(error) => {
                    let _ = callback_host.release_failed_installation(&callback_label, attempt);
                    let _ = sender.send(Err(error));
                }
            }
        })
        .is_err()
    {
        installation_active.store(false, Ordering::Release);
        let _ = host.release_failed_installation(&label, attempt);
        return Err(NativeShortcutError::new(
            NativeShortcutErrorCode::RegistrationFailed,
        ));
    }
    let token = match tokio::time::timeout(REGISTRATION_TIMEOUT, receiver).await {
        Ok(Ok(Ok(token))) => {
            if let Ok(mut stored) = shared_token.lock() {
                *stored = None;
            }
            token
        }
        Ok(Ok(Err(error))) => {
            installation_active.store(false, Ordering::Release);
            let _ = host.release_failed_installation(&label, attempt);
            return Err(error);
        }
        Ok(Err(_)) => {
            installation_active.store(false, Ordering::Release);
            abandon_installation(&webview, &host, &label, attempt, &shared_token).await;
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::RegistrationFailed,
            ));
        }
        Err(_) => {
            installation_active.store(false, Ordering::Release);
            abandon_installation(&webview, &host, &label, attempt, &shared_token).await;
            return Err(NativeShortcutError::new(
                NativeShortcutErrorCode::RegistrationTimeout,
            ));
        }
    };
    installation_active.store(false, Ordering::Release);
    match host.finish_installation(&label, attempt, token) {
        Ok(()) => Ok(()),
        Err(error) => {
            let removal = remove_webview_handler(&webview, token).await;
            settle_controller_removal(&host, &label, attempt, removal);
            Err(error)
        }
    }
}

#[cfg(windows)]
/// 注册 closure 不捕获 controller，避免 event source 与 callback 形成 COM 引用环；label
/// 仅用于核对该 WebView 仍持有已发布 token。
fn install_controller(
    controller: &ICoreWebView2Controller,
    app: tauri::AppHandle,
    host: NativeShortcutHost,
    label: String,
    attempt: u64,
) -> Result<i64, NativeShortcutError> {
    let handler = AcceleratorKeyPressedEventHandler::create(Box::new(move |_, args| {
        let Some(args) = args else {
            return Ok(());
        };
        handle_accelerator_key(&args, &app, &host, &label, attempt)
    }));
    let mut token = 0i64;
    unsafe {
        controller
            .add_AcceleratorKeyPressed(&handler, &mut token)
            .map_err(|_| NativeShortcutError::new(NativeShortcutErrorCode::RegistrationFailed))?;
    }
    Ok(token)
}

#[cfg(windows)]
/// 仅在仍持有 apartment-bound controller 的 UI-thread closure 内撤销失败或晚到安装。
fn remove_controller_handler(
    controller: &ICoreWebView2Controller,
    token: i64,
) -> Result<(), NativeShortcutError> {
    unsafe {
        controller
            .remove_AcceleratorKeyPressed(token)
            .map_err(|_| NativeShortcutError::new(NativeShortcutErrorCode::RegistrationFailed))
    }
}

#[cfg(windows)]
/// 安装等待终止时先把 attempt 计入 deferred slot；若 token 已知，再通过同一 WebView
/// 的 UI-thread closure 尝试主动 remove，成功后才释放 slot。
async fn abandon_installation(
    webview: &tauri::Webview,
    host: &NativeShortcutHost,
    label: &str,
    attempt: u64,
    shared_token: &Arc<Mutex<Option<i64>>>,
) {
    let _ = host.defer_installation(label, attempt);
    let token = shared_token
        .lock()
        .ok()
        .and_then(|mut stored| stored.take());
    if let Some(token) = token {
        let removal = remove_webview_handler(webview, token).await;
        settle_controller_removal(host, label, attempt, removal);
    }
}

#[cfg(windows)]
/// 把原生 remove 结果映射回 attempt slot：成功才释放，失败继续等待 controller close。
fn settle_controller_removal(
    host: &NativeShortcutHost,
    label: &str,
    attempt: u64,
    removal: Result<(), NativeShortcutError>,
) {
    if removal.is_ok() {
        let _ = host.release_failed_installation(label, attempt);
    } else {
        let _ = host.defer_installation(label, attempt);
    }
}

#[cfg(windows)]
/// 只把可发送的 token 调度回 WebView UI 线程；controller 不进入 future 或 managed state。
async fn remove_webview_handler(
    webview: &tauri::Webview,
    token: i64,
) -> Result<(), NativeShortcutError> {
    let (sender, receiver) = tokio::sync::oneshot::channel();
    webview
        .with_webview(move |platform_webview| {
            let controller = platform_webview.controller();
            let _ = sender.send(remove_controller_handler(&controller, token));
        })
        .map_err(|_| NativeShortcutError::new(NativeShortcutErrorCode::RegistrationFailed))?;
    match tokio::time::timeout(REGISTRATION_TIMEOUT, receiver).await {
        Ok(Ok(result)) => result,
        Ok(Err(_)) => Err(NativeShortcutError::new(
            NativeShortcutErrorCode::RegistrationFailed,
        )),
        Err(_) => Err(NativeShortcutError::new(
            NativeShortcutErrorCode::RegistrationTimeout,
        )),
    }
}

#[cfg(windows)]
/// 固定执行顺序为 child focus main（main 自身跳过）→ emit → SetHandled；任一步失败即
/// fail-open，后续副作用不会执行。
pub(crate) fn dispatch_shortcut_effects<FocusMain, EmitMain, SetHandled, Error>(
    source_label: &str,
    focus_main: FocusMain,
    emit_main: EmitMain,
    set_handled: SetHandled,
) -> Result<bool, Error>
where
    FocusMain: FnOnce() -> bool,
    EmitMain: FnOnce() -> bool,
    SetHandled: FnOnce() -> Result<(), Error>,
{
    if source_label != MAIN_WEBVIEW_LABEL && !focus_main() {
        return Ok(false);
    }
    if !emit_main() {
        return Ok(false);
    }
    set_handled()?;
    Ok(true)
}

#[cfg(windows)]
/// callback 只有在固定动作启用且定向 emit 成功时才 SetHandled，其他按键完整交还页面。
fn handle_accelerator_key(
    args: &ICoreWebView2AcceleratorKeyPressedEventArgs,
    app: &tauri::AppHandle,
    host: &NativeShortcutHost,
    label: &str,
    attempt: u64,
) -> windows_core::Result<()> {
    let Ok(input) = read_key_input(args) else {
        return Ok(());
    };
    let Some(command) = classify_shortcut(input) else {
        return Ok(());
    };
    let Ok(Some(event)) = host.event_if_enabled_for_registration(label, attempt, command) else {
        return Ok(());
    };
    let _ = dispatch_shortcut_effects(
        label,
        || {
            app.get_webview(MAIN_WEBVIEW_LABEL)
                .is_some_and(|main| main.set_focus().is_ok())
        },
        || {
            app.emit_to(
                EventTarget::webview(MAIN_WEBVIEW_LABEL),
                NATIVE_SHORTCUT_EVENT,
                event,
            )
            .is_ok()
        },
        || unsafe { args.SetHandled(true) },
    )?;
    Ok(())
}

#[cfg(windows)]
/// 从 WebView2 参数与当前线程键状态生成纯输入；右 Alt 单独保留以识别 AltGr。
fn read_key_input(
    args: &ICoreWebView2AcceleratorKeyPressedEventArgs,
) -> windows_core::Result<NativeKeyInput> {
    let mut event_kind = COREWEBVIEW2_KEY_EVENT_KIND::default();
    let mut virtual_key = 0u32;
    let mut physical = COREWEBVIEW2_PHYSICAL_KEY_STATUS::default();
    unsafe {
        args.KeyEventKind(&mut event_kind)?;
        args.VirtualKey(&mut virtual_key)?;
        args.PhysicalKeyStatus(&mut physical)?;
    }
    let kind = if event_kind == COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN {
        NativeKeyEventKind::KeyDown
    } else if event_kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN {
        NativeKeyEventKind::SystemKeyDown
    } else {
        NativeKeyEventKind::Other
    };
    let key_down = |virtual_key: u16| unsafe { GetKeyState(i32::from(virtual_key)) < 0 };
    Ok(NativeKeyInput {
        kind,
        virtual_key,
        repeat_count: physical.RepeatCount,
        was_key_down: physical.WasKeyDown.as_bool(),
        modifiers: NativeModifiers {
            control: key_down(VK_CONTROL.0),
            shift: key_down(VK_SHIFT.0),
            alt: physical.IsMenuKeyDown.as_bool() || key_down(VK_MENU.0),
            windows: key_down(VK_LWIN.0) || key_down(VK_RWIN.0),
            right_alt: key_down(VK_RMENU.0),
        },
    })
}

// 快捷键单元测试物理放在 `tests/unit`，生产模块不保留测试状态或测试行为分支。
