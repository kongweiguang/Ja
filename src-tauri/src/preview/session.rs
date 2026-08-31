// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 有界 Preview session 状态与陈旧 callback 处理。

use super::error::{PreviewError, PreviewErrorCode};
use super::model::{
    NavigationSource, PreviewEvent, PreviewEventKind, PreviewGeneration, PreviewId, PreviewLimits,
    PreviewLoadStatus, PreviewNavigationRequest, PreviewOpenResult, PreviewSessionSnapshot,
    PreviewSessionStatus, PreviewUrl, PreviewWindowSpec,
};
use super::policy::{PreviewNavigationDecision, PreviewPolicy};
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex, MutexGuard};

/// 拥有单个 Tauri 应用的全部内存 Preview session。
#[derive(Clone)]
pub struct PreviewManager {
    policy: Arc<PreviewPolicy>,
    state: Arc<Mutex<RegistryState>>,
}

struct RegistryState {
    accepting: bool,
    next_close_token: u64,
    sessions: HashMap<PreviewId, SessionState>,
}

struct SessionState {
    id: PreviewId,
    generation: PreviewGeneration,
    status: PreviewSessionStatus,
    load_status: PreviewLoadStatus,
    url: PreviewUrl,
    title: String,
    events: VecDeque<PreviewEvent>,
    event_bytes: usize,
    next_sequence: u64,
    dropped_events: u64,
    close_token: Option<u64>,
}

/// 不透明 claim 保证原生 close 与模型 finalization 成对执行。
#[derive(Debug, Clone)]
pub(crate) struct PreviewCloseTicket {
    session_id: PreviewId,
    token: u64,
    snapshot: PreviewSessionSnapshot,
}

impl PreviewCloseTicket {
    /// 只公开定位匹配子 WebView 所需的 UI-safe snapshot。
    pub(crate) fn snapshot(&self) -> &PreviewSessionSnapshot {
        &self.snapshot
    }
}

impl PreviewManager {
    /// 校验共享 URL/event budget 后创建 manager。
    pub fn new(policy: PreviewPolicy) -> Result<Self, PreviewError> {
        policy.limits().validate()?;
        Ok(Self {
            policy: Arc::new(policy),
            state: Arc::new(Mutex::new(RegistryState {
                accepting: true,
                next_close_token: 0,
                sessions: HashMap::new(),
            })),
        })
    }

    /// 使用产品默认且仅 HTTP(S) 的 policy。
    pub fn default_manager() -> Result<Self, PreviewError> {
        Self::new(PreviewPolicy::new()?)
    }

    /// 打开有界 session 并发送其首个事件。
    pub fn open(&self, raw_url: &str) -> Result<PreviewOpenResult, PreviewError> {
        let url = self.policy.validate_url(raw_url)?;
        let mut state = self.lock_state()?;
        Self::ensure_accepting(&state)?;
        let active = state
            .sessions
            .values()
            .filter(|session| session.status == PreviewSessionStatus::Open)
            .count();
        if active >= self.policy.limits().max_sessions {
            return Err(PreviewError::new(PreviewErrorCode::SessionLimit));
        }
        let id = PreviewId::new();
        let window = PreviewWindowSpec::new(id, url.clone());
        let mut session = SessionState {
            id,
            generation: 1,
            status: PreviewSessionStatus::Open,
            load_status: PreviewLoadStatus::Loading,
            url: url.clone(),
            title: String::new(),
            events: VecDeque::new(),
            event_bytes: 0,
            next_sequence: 1,
            dropped_events: 0,
            close_token: None,
        };
        Self::push_event(
            &mut session,
            PreviewEventKind::Opened { url },
            self.policy.limits(),
        )?;
        let snapshot = Self::snapshot_state(&session);
        state.sessions.insert(id, session);
        Ok(PreviewOpenResult { snapshot, window })
    }

    /// 原生创建 callback 可能推进状态，因此完成后重新读取 session。
    pub(crate) fn authoritative_open_result(
        &self,
        id: PreviewId,
    ) -> Result<PreviewOpenResult, PreviewError> {
        let snapshot = self.snapshot(id)?;
        let window = snapshot.window.clone();
        Ok(PreviewOpenResult { snapshot, window })
    }

    /// command 调用 `WebView.navigate` 前校验原生 navigation。
    pub fn navigation_request(
        &self,
        id: PreviewId,
        generation: PreviewGeneration,
        source: NavigationSource,
        raw_url: &str,
    ) -> Result<PreviewNavigationRequest, PreviewError> {
        let PreviewNavigationDecision::Allow { url } = self.policy.navigation(source, raw_url)?;
        let state = self.lock_state()?;
        Self::ensure_accepting(&state)?;
        self.ensure_open_generation(&state, id, generation)?;
        Ok(PreviewNavigationRequest {
            session_id: id,
            generation,
            source,
            url,
        })
    }

    /// 提交用户或 redirect navigation，并推进其 callback generation。
    pub fn navigate(
        &self,
        id: PreviewId,
        generation: PreviewGeneration,
        source: NavigationSource,
        raw_url: &str,
    ) -> Result<PreviewSessionSnapshot, PreviewError> {
        let request = self.navigation_request(id, generation, source, raw_url)?;
        self.commit_navigation(request)
    }

    /// 重新检查 URL 与 generation 后提交 navigation callback。
    pub fn callback_navigation(
        &self,
        id: PreviewId,
        generation: PreviewGeneration,
        raw_url: &str,
    ) -> Result<PreviewEvent, PreviewError> {
        let request =
            self.navigation_request(id, generation, NavigationSource::Redirect, raw_url)?;
        self.commit_navigation_event(request)
    }

    /// 接受来自 WebView 的有界 title callback。
    pub fn callback_title(
        &self,
        id: PreviewId,
        generation: PreviewGeneration,
        title: &str,
    ) -> Result<PreviewEvent, PreviewError> {
        let mut state = self.lock_state()?;
        Self::ensure_accepting(&state)?;
        let session = self.session_mut(&mut state, id, generation)?;
        let title = truncate_utf8(title, self.policy.limits().max_title_bytes);
        session.title = title.clone();
        Self::push_event(
            session,
            PreviewEventKind::TitleChanged { title },
            self.policy.limits(),
        )
    }

    /// 接受有界 load-error callback，但不向 IPC 返回 engine 细节。
    pub fn callback_load_error(
        &self,
        id: PreviewId,
        generation: PreviewGeneration,
        message: &str,
    ) -> Result<PreviewEvent, PreviewError> {
        let mut state = self.lock_state()?;
        Self::ensure_accepting(&state)?;
        let session = self.session_mut(&mut state, id, generation)?;
        let message = truncate_utf8(message, self.policy.limits().max_error_bytes);
        let event = Self::push_event(
            session,
            PreviewEventKind::LoadFailed { message },
            self.policy.limits(),
        )?;
        session.load_status = PreviewLoadStatus::Failed;
        Ok(event)
    }

    /// 记录匹配的 engine `Finished` callback；navigation commitment 或不匹配的内部
    /// error page 不能被当作加载完成。
    pub fn callback_load_finished(
        &self,
        id: PreviewId,
        generation: PreviewGeneration,
        completed_url: &str,
    ) -> Result<Option<PreviewEvent>, PreviewError> {
        let mut state = self.lock_state()?;
        Self::ensure_accepting(&state)?;
        let session = self.session_mut(&mut state, id, generation)?;
        if session.url.as_str() != completed_url
            || matches!(
                session.load_status,
                PreviewLoadStatus::Finished | PreviewLoadStatus::Failed
            )
        {
            return Ok(None);
        }
        let event = Self::push_event(
            session,
            PreviewEventKind::LoadFinished {
                url: session.url.clone(),
            },
            self.policy.limits(),
        )?;
        session.load_status = PreviewLoadStatus::Finished;
        Ok(Some(event))
    }

    /// 原生 close 前先领取一个 session，防止并发 navigation 修改 close ACK
    /// 即将 finalize 的 identity。
    pub(crate) fn prepare_close(&self, id: PreviewId) -> Result<PreviewCloseTicket, PreviewError> {
        let mut state = self.lock_state()?;
        let token = state
            .next_close_token
            .checked_add(1)
            .ok_or(PreviewError::new(PreviewErrorCode::SequenceExhausted))?;
        let existing = state
            .sessions
            .get(&id)
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
        if existing.close_token.is_some() {
            return Err(PreviewError::new(PreviewErrorCode::SessionClosing));
        }
        state.next_close_token = token;
        let session = state
            .sessions
            .get_mut(&id)
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
        session.close_token = Some(token);
        Ok(PreviewCloseTicket {
            session_id: id,
            token,
            snapshot: Self::snapshot_state(session),
        })
    }

    /// 原生 close 失败时释放 claim，但保留精确 session identity，
    /// 使 UI 或 shutdown recovery 可以安全重试。
    pub(crate) fn abort_close(&self, ticket: &PreviewCloseTicket) -> Result<(), PreviewError> {
        let mut state = self.lock_state()?;
        let session = state
            .sessions
            .get_mut(&ticket.session_id)
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
        if session.close_token != Some(ticket.token) {
            return Err(PreviewError::new(PreviewErrorCode::SessionClosing));
        }
        session.close_token = None;
        Ok(())
    }

    /// 只有原生 close 已 ACK 或原生 identity 本就不存在时，才删除全部 model/event 状态，
    /// 防止 tombstone 增长。
    pub(crate) fn finalize_close(
        &self,
        ticket: PreviewCloseTicket,
    ) -> Result<PreviewSessionSnapshot, PreviewError> {
        let mut state = self.lock_state()?;
        let matches = state
            .sessions
            .get(&ticket.session_id)
            .is_some_and(|session| session.close_token == Some(ticket.token));
        if !matches {
            return Err(PreviewError::new(PreviewErrorCode::SessionClosing));
        }
        let mut session = state
            .sessions
            .remove(&ticket.session_id)
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
        session.status = PreviewSessionStatus::Closed;
        session.close_token = None;
        Ok(Self::snapshot_state(&session))
    }

    /// 只有未创建子 WebView 或其 close 已 ACK 时才丢弃 session；close 失败后调用方
    /// 绝不能使用此路径。
    pub(crate) fn finalize_absent(&self, id: PreviewId) -> Result<(), PreviewError> {
        let mut state = self.lock_state()?;
        state
            .sessions
            .remove(&id)
            .map(|_| ())
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))
    }

    /// 按 sequence 顺序排空有界事件批次。
    pub fn drain_events(
        &self,
        id: PreviewId,
        max_events: usize,
    ) -> Result<Vec<PreviewEvent>, PreviewError> {
        let mut state = self.lock_state()?;
        let session = state
            .sessions
            .get_mut(&id)
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
        let count = max_events.min(self.policy.limits().max_event_count);
        let mut events = Vec::with_capacity(count.min(session.events.len()));
        for _ in 0..count {
            let Some(event) = session.events.pop_front() else {
                break;
            };
            session.event_bytes = session.event_bytes.saturating_sub(event.encoded_bytes());
            events.push(event);
        }
        Ok(events)
    }

    /// 返回 UI reload 后使用的权威状态。
    pub fn snapshot(&self, id: PreviewId) -> Result<PreviewSessionSnapshot, PreviewError> {
        let state = self.lock_state()?;
        state
            .sessions
            .get(&id)
            .map(Self::snapshot_state)
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))
    }

    /// 报告活动 WebView，供应用 shutdown 验证。
    pub fn active_count(&self) -> Result<usize, PreviewError> {
        let state = self.lock_state()?;
        Ok(state.sessions.len())
    }

    /// 永久拒绝新 mutation，同时为 ACK-first shutdown 保留原生 identity；
    /// 重复调用刻意保持幂等。
    pub(crate) fn begin_shutdown(&self) -> Result<(), PreviewError> {
        self.lock_state()?.accepting = false;
        Ok(())
    }

    /// operation fence 排空后返回 shutdown 使用的有界副本。
    pub(crate) fn pending_snapshots(&self) -> Result<Vec<PreviewSessionSnapshot>, PreviewError> {
        let state = self.lock_state()?;
        Ok(state.sessions.values().map(Self::snapshot_state).collect())
    }

    /// 提交已验证 navigation 并返回新 snapshot。
    fn commit_navigation(
        &self,
        request: PreviewNavigationRequest,
    ) -> Result<PreviewSessionSnapshot, PreviewError> {
        let id = request.session_id;
        self.commit_navigation_event(request)?;
        self.snapshot(id)
    }

    /// 更新 URL/title/generation，并追加一个有序事件。
    fn commit_navigation_event(
        &self,
        request: PreviewNavigationRequest,
    ) -> Result<PreviewEvent, PreviewError> {
        let mut state = self.lock_state()?;
        Self::ensure_accepting(&state)?;
        let session = self.session_mut(&mut state, request.session_id, request.generation)?;
        session.generation = next_generation(session.generation)?;
        session.url = request.url.clone();
        session.title.clear();
        session.load_status = PreviewLoadStatus::Loading;
        Self::push_event(
            session,
            PreviewEventKind::NavigationCommitted {
                source: request.source,
                url: request.url,
            },
            self.policy.limits(),
        )
    }

    /// 持有 registry lock 时查找一个 open generation。
    fn session_mut<'a>(
        &self,
        state: &'a mut RegistryState,
        id: PreviewId,
        generation: PreviewGeneration,
    ) -> Result<&'a mut SessionState, PreviewError> {
        let session = state
            .sessions
            .get_mut(&id)
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
        if session.status == PreviewSessionStatus::Closed {
            return Err(PreviewError::new(PreviewErrorCode::SessionClosed));
        }
        if session.close_token.is_some() {
            return Err(PreviewError::new(PreviewErrorCode::SessionClosing));
        }
        if session.generation != generation {
            return Err(PreviewError::new(PreviewErrorCode::StaleGeneration));
        }
        Ok(session)
    }

    /// lock poison 保持显式失败，不使用陈旧 map 恢复。
    fn lock_state(&self) -> Result<MutexGuard<'_, RegistryState>, PreviewError> {
        self.state
            .lock()
            .map_err(|_| PreviewError::new(PreviewErrorCode::InternalStateUnavailable))
    }

    /// 为 navigation request 执行只读 generation/status 检查。
    fn ensure_open_generation(
        &self,
        state: &RegistryState,
        id: PreviewId,
        generation: PreviewGeneration,
    ) -> Result<(), PreviewError> {
        let session = state
            .sessions
            .get(&id)
            .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
        if session.status == PreviewSessionStatus::Closed {
            return Err(PreviewError::new(PreviewErrorCode::SessionClosed));
        }
        if session.close_token.is_some() {
            return Err(PreviewError::new(PreviewErrorCode::SessionClosing));
        }
        if session.generation != generation {
            return Err(PreviewError::new(PreviewErrorCode::StaleGeneration));
        }
        Ok(())
    }

    /// 持有 registry lock 时检查单调 process-level 生命周期。
    fn ensure_accepting(state: &RegistryState) -> Result<(), PreviewError> {
        if !state.accepting {
            return Err(PreviewError::new(PreviewErrorCode::ShutdownStarted));
        }
        Ok(())
    }

    /// 添加单个事件；达到 byte cap 时只丢弃旧的非终态 history。
    fn push_event(
        session: &mut SessionState,
        kind: PreviewEventKind,
        limits: PreviewLimits,
    ) -> Result<PreviewEvent, PreviewError> {
        let sequence = session
            .next_sequence
            .checked_add(1)
            .ok_or(PreviewError::new(PreviewErrorCode::SequenceExhausted))?;
        let event = PreviewEvent {
            session_id: session.id,
            generation: session.generation,
            sequence: session.next_sequence,
            kind,
        };
        let payload_bytes = event.encoded_bytes();
        if payload_bytes > limits.max_event_payload_bytes {
            return Err(PreviewError::new(PreviewErrorCode::EventPayloadTooLarge));
        }
        session.next_sequence = sequence;
        while session.events.len() >= limits.max_event_count
            || session.event_bytes.saturating_add(payload_bytes) > limits.max_event_queue_bytes
        {
            let Some(oldest) = session.events.pop_front() else {
                break;
            };
            session.event_bytes = session.event_bytes.saturating_sub(oldest.encoded_bytes());
            session.dropped_events = session.dropped_events.saturating_add(1);
        }
        if session.event_bytes.saturating_add(payload_bytes) > limits.max_event_queue_bytes {
            return Err(PreviewError::new(PreviewErrorCode::EventQueueFull));
        }
        session.event_bytes = session.event_bytes.saturating_add(payload_bytes);
        session.events.push_back(event.clone());
        Ok(event)
    }

    /// 投影模型状态，但不暴露 WebView handle。
    fn snapshot_state(session: &SessionState) -> PreviewSessionSnapshot {
        PreviewSessionSnapshot {
            id: session.id,
            generation: session.generation,
            status: session.status,
            load_status: session.load_status,
            url: session.url.clone(),
            title: session.title.clone(),
            window: PreviewWindowSpec::new(session.id, session.url.clone()),
            dropped_events: session.dropped_events,
        }
    }
}

/// UTF-8 safe 的有界 title/error 投影。
fn truncate_utf8(value: &str, max_bytes: usize) -> String {
    if value.len() <= max_bytes {
        return value.to_owned();
    }
    let mut end = max_bytes;
    while end > 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
}

/// checked generation 防止 wraparound 后重新验证旧 callback。
fn next_generation(current: PreviewGeneration) -> Result<PreviewGeneration, PreviewError> {
    current
        .checked_add(1)
        .ok_or(PreviewError::new(PreviewErrorCode::GenerationExhausted))
}
