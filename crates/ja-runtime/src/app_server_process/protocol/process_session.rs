// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Strict DTOs for the generation-fenced process session RPCs.

use serde::{Deserialize, Serialize};
use std::collections::HashSet;

const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;
const MAX_OUTPUT_BYTES: usize = 65_536;
const MAX_INPUT_BYTES: usize = 1_048_576;

/// 保留必需可空字段的 null 与缺失差异，避免解码放宽协议闭集。
fn required_option<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(deserializer)
}

/// 只校验不透明身份的前缀与字符集，不把后缀解释为主机路径。
fn valid_prefixed(value: &str, prefix: &str, maximum: usize) -> bool {
    let Some(suffix) = value.strip_prefix(prefix) else {
        return false;
    };
    !suffix.is_empty()
        && value.len() <= maximum
        && suffix.as_bytes()[0].is_ascii_alphanumeric()
        && suffix
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// 将操作键限制在 JA-RPC 的小写随机身份空间内，避免跨端幂等键语义分叉。
fn valid_operation_key(value: &str) -> bool {
    value.len() == 35
        && value.starts_with("op_")
        && value.as_bytes()[3..]
            .iter()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(byte))
}

/// Reusable identity and state projection for one retained process session.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessSessionSummary {
    pub workspace_id: String,
    pub thread_id: String,
    pub generation: u64,
    pub session_id: String,
    pub started_turn_id: String,
    pub started_call_id: String,
    pub created_at: String,
    pub state: ProcessSessionState,
    pub output_sequence: u64,
    pub output_truncated: bool,
    #[serde(deserialize_with = "required_option")]
    pub exit_code: Option<i32>,
}

impl ProcessSessionSummary {
    /// 在消费会话摘要前校验归属、代际、时间戳和退出码组合，防止把中断事实误作存活进程。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.workspace_id, "ws_", 99)
            || !valid_prefixed(&self.thread_id, "thr_", 100)
            || !valid_prefixed(&self.session_id, "proc_", 37)
            || !valid_prefixed(&self.started_turn_id, "turn_", 101)
            || !valid_call_id(&self.started_call_id)
            || self.generation == 0
            || self.generation > MAX_SAFE_INTEGER
            || self.output_sequence > MAX_SAFE_INTEGER
            || !super::valid_protocol_timestamp(&self.created_at)
        {
            return Err("process session identity or sequence is invalid");
        }
        match self.state {
            ProcessSessionState::Exited if self.exit_code.is_none() => {
                Err("exited process session lacks its exit code")
            }
            ProcessSessionState::Running
            | ProcessSessionState::Stopping
            | ProcessSessionState::Interrupted
                if self.exit_code.is_some() =>
            {
                Err("live or interrupted process session has an exit code")
            }
            _ => Ok(()),
        }
    }
}

/// Public process lifecycle values; an interrupted process is never implied to still be running.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ProcessSessionState {
    Running,
    Stopping,
    Exited,
    Interrupted,
}

/// Current-runtime owner query; generation is deliberately absent so old retained history is visible.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessSessionListParams {
    pub workspace_id: String,
    pub thread_id: String,
    pub limit: u16,
    #[serde(deserialize_with = "required_option")]
    pub cursor: Option<String>,
    pub started_call_id: Option<String>,
}

impl ProcessSessionListParams {
    /// 校验分页归属筛选，同时保留首屏游标必须显式为 null 的协议约束。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.workspace_id, "ws_", 99)
            || !valid_prefixed(&self.thread_id, "thr_", 100)
            || !(1..=200).contains(&self.limit)
            || self
                .cursor
                .as_ref()
                .is_some_and(|value| !valid_cursor(value))
            || self
                .started_call_id
                .as_ref()
                .is_some_and(|value| !valid_call_id(value))
        {
            return Err("process list parameters are invalid");
        }
        Ok(())
    }
}

/// A bounded page whose generation names the current runtime owner, not every retained item.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessSessionListResult {
    pub workspace_id: String,
    pub thread_id: String,
    pub generation: u64,
    pub sessions: Vec<ProcessSessionSummary>,
    #[serde(deserialize_with = "required_option")]
    pub next_cursor: Option<String>,
}

impl ProcessSessionListResult {
    /// 将结果页绑定到请求归属，并阻止旧代际会话被投影为仍可操作状态。
    pub fn validate_for(&self, request: &ProcessSessionListParams) -> Result<(), &'static str> {
        request.validate()?;
        if self.workspace_id != request.workspace_id
            || self.thread_id != request.thread_id
            || self.generation == 0
            || self.generation > MAX_SAFE_INTEGER
            || self.sessions.len() > 200
            || self
                .next_cursor
                .as_ref()
                .is_some_and(|value| !valid_cursor(value))
        {
            return Err("process list result crossed its owner or page budget");
        }
        let mut session_ids = HashSet::with_capacity(self.sessions.len());
        for session in &self.sessions {
            session.validate()?;
            if session.workspace_id != self.workspace_id
                || session.thread_id != self.thread_id
                || session.generation > self.generation
                || !session_ids.insert(session.session_id.as_str())
                || request
                    .started_call_id
                    .as_ref()
                    .is_some_and(|call_id| &session.started_call_id != call_id)
                || (session.generation < self.generation
                    && matches!(
                        session.state,
                        ProcessSessionState::Running | ProcessSessionState::Stopping
                    ))
            {
                return Err("process list contains a foreign or live old-generation session");
            }
        }
        Ok(())
    }
}

/// Parameters for reading output from the current or a retained prior-generation session.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessSessionReadParams {
    pub workspace_id: String,
    pub thread_id: String,
    pub generation: u64,
    pub session_id: String,
    pub after_sequence: u64,
    pub max_bytes: u32,
}

impl ProcessSessionReadParams {
    /// 发出读取前校验归属、UTF-8 字节游标和代际，历史代际仅允许只读回看。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.workspace_id, "ws_", 99)
            || !valid_prefixed(&self.thread_id, "thr_", 100)
            || !valid_prefixed(&self.session_id, "proc_", 37)
            || self.generation == 0
            || self.generation > MAX_SAFE_INTEGER
            || self.after_sequence > MAX_SAFE_INTEGER
            || !(1..=65_536).contains(&self.max_bytes)
        {
            return Err("process read parameters are invalid");
        }
        Ok(())
    }
}

/// Parameters for an idempotent input write to a session in the current runtime generation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessSessionWriteParams {
    pub workspace_id: String,
    pub thread_id: String,
    pub generation: u64,
    pub session_id: String,
    pub after_sequence: u64,
    pub input: String,
    pub close_input: bool,
    pub operation_key: String,
}

impl ProcessSessionWriteParams {
    /// 写入产生副作用前校验会话所有者、当前代际、幂等身份和 UTF-8 输入预算。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.workspace_id, "ws_", 99)
            || !valid_prefixed(&self.thread_id, "thr_", 100)
            || !valid_prefixed(&self.session_id, "proc_", 37)
            || self.generation == 0
            || self.generation > MAX_SAFE_INTEGER
            || self.after_sequence > MAX_SAFE_INTEGER
            || self.input.len() > MAX_INPUT_BYTES
            || !valid_operation_key(&self.operation_key)
        {
            return Err("process write parameters are invalid");
        }
        Ok(())
    }
}

/// Parameters for stopping only a live session owned by the named current runtime generation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessSessionStopParams {
    pub workspace_id: String,
    pub thread_id: String,
    pub generation: u64,
    pub session_id: String,
}

impl ProcessSessionStopParams {
    /// 停止子进程前校验精确归属和当前代际，避免迟到请求影响新 Runtime 资源。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.workspace_id, "ws_", 99)
            || !valid_prefixed(&self.thread_id, "thr_", 100)
            || !valid_prefixed(&self.session_id, "proc_", 37)
            || self.generation == 0
            || self.generation > MAX_SAFE_INTEGER
        {
            return Err("process stop parameters are invalid");
        }
        Ok(())
    }
}

/// Output-bearing snapshot returned by read/write/stop; stop-specific invariants are method-bound.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProcessSessionSnapshot {
    pub workspace_id: String,
    pub thread_id: String,
    pub generation: u64,
    pub session_id: String,
    pub started_turn_id: String,
    pub started_call_id: String,
    pub created_at: String,
    pub state: ProcessSessionState,
    pub output_sequence: u64,
    pub output_truncated: bool,
    #[serde(deserialize_with = "required_option")]
    pub exit_code: Option<i32>,
    pub output: String,
    pub next_sequence: u64,
}

impl ProcessSessionSnapshot {
    /// 校验读取回执并允许旧代际历史事实，但不把旧进程重新解释为活动进程。
    pub fn validate_for_read(
        &self,
        request: &ProcessSessionReadParams,
    ) -> Result<(), &'static str> {
        request.validate()?;
        self.validate_common()?;
        if !self.matches_owner(
            &request.workspace_id,
            &request.thread_id,
            &request.session_id,
        ) || self.generation > request.generation
            || (self.generation < request.generation
                && matches!(
                    self.state,
                    ProcessSessionState::Running | ProcessSessionState::Stopping
                ))
            || self.next_sequence < request.after_sequence
            || self.next_sequence > self.output_sequence
            || self.output.len() > request.max_bytes as usize
        {
            return Err("process read result crossed its owner or cursor");
        }
        Ok(())
    }

    /// 写入回执必须留在当前 Runtime 代际，且不能回退已确认的输出游标。
    pub fn validate_for_write(
        &self,
        request: &ProcessSessionWriteParams,
    ) -> Result<(), &'static str> {
        request.validate()?;
        self.validate_common()?;
        if !self.matches_owner(
            &request.workspace_id,
            &request.thread_id,
            &request.session_id,
        ) || self.generation != request.generation
            || self.next_sequence < request.after_sequence
            || self.next_sequence > self.output_sequence
        {
            return Err("process write result crossed its owner or generation");
        }
        Ok(())
    }

    /// 停止回执必须满足当前代际、停止状态和无输出确认，避免把停止误报为完成。
    pub fn validate_for_stop(
        &self,
        request: &ProcessSessionStopParams,
    ) -> Result<(), &'static str> {
        request.validate()?;
        self.validate_common()?;
        if !self.matches_owner(
            &request.workspace_id,
            &request.thread_id,
            &request.session_id,
        ) || self.generation != request.generation
            || !matches!(
                self.state,
                ProcessSessionState::Stopping | ProcessSessionState::Exited
            )
            || !self.output.is_empty()
            || self.output_truncated
        {
            return Err("process stop result is inconsistent with its request");
        }
        Ok(())
    }

    /// 先校验各读取方法共用的身份与输出预算，再执行各自的归属和代际约束。
    fn validate_common(&self) -> Result<(), &'static str> {
        ProcessSessionSummary {
            workspace_id: self.workspace_id.clone(),
            thread_id: self.thread_id.clone(),
            generation: self.generation,
            session_id: self.session_id.clone(),
            started_turn_id: self.started_turn_id.clone(),
            started_call_id: self.started_call_id.clone(),
            created_at: self.created_at.clone(),
            state: self.state,
            output_sequence: self.output_sequence,
            output_truncated: self.output_truncated,
            exit_code: self.exit_code,
        }
        .validate()?;
        if self.output.len() > MAX_OUTPUT_BYTES || self.next_sequence > self.output_sequence {
            return Err("process snapshot exceeds its output or sequence budget");
        }
        Ok(())
    }

    /// 精确比较不透明会话归属，不将调用方提供的身份值当作物理路径处理。
    fn matches_owner(&self, workspace_id: &str, thread_id: &str, session_id: &str) -> bool {
        self.workspace_id == workspace_id
            && self.thread_id == thread_id
            && self.session_id == session_id
    }
}

/// 复用 Tool 调用身份的有界字符集，使进程会话可绑定到唯一启动调用。
fn valid_call_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.as_bytes()[0].is_ascii_alphanumeric()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b':' | b'-'))
}

/// 拒绝游标中的路径分隔符和控制字符，避免把不透明分页令牌变成路径输入。
fn valid_cursor(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'~' | b'-'))
}
