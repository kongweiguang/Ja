// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Rust client request 的有界 pending registry。
//!
//! Pending 是 session 的资源边界，不应借用 UI 或业务状态的生命周期；因此这里
//! 只保存请求 ID、deadline 和一次性投递器，并用有限 tombstone 拒绝迟到副作用。

use crate::app_server_process::error::AppServerProcessError;
use crate::app_server_process::protocol::RpcFrame;
use std::collections::{HashMap, VecDeque};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::time::{Duration, Instant};

const MAX_PENDING_ENTRIES: usize = 1_024;
const MAX_TOMBSTONES: usize = 8_192;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PendingTerminal {
    Resolved,
    TimedOut,
    Cancelled,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ResolveDisposition {
    Delivered,
    DuplicateResponse,
    LateResponse,
    UnknownRequest,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PendingRegisterError {
    DuplicateRequest,
    LimitReached,
}

#[derive(Debug)]
pub(crate) struct PendingEntry {
    deadline: Instant,
    completion: SyncSender<Result<RpcFrame, AppServerProcessError>>,
}

/// 保存已结束 request 的有限分类，避免 tombstone 本身无限增长。
#[derive(Debug, Clone)]
struct Tombstone {
    id: String,
    terminal: PendingTerminal,
}

/// 线程安全由 session 外层 Mutex 提供；内部结构刻意保持单一 owner，方便审计。
#[derive(Debug)]
pub(crate) struct PendingRegistry {
    pub(crate) active: HashMap<String, PendingEntry>,
    tombstones: VecDeque<Tombstone>,
    max_active: usize,
    max_tombstones: usize,
}

impl PendingRegistry {
    /// 创建有界 pending，保证嵌套 approval 不会耗尽 session 内存。
    pub(crate) fn new(
        max_active: usize,
        max_tombstones: usize,
    ) -> Result<Self, PendingRegisterError> {
        if max_active == 0
            || max_active > MAX_PENDING_ENTRIES
            || max_tombstones == 0
            || max_tombstones > MAX_TOMBSTONES
        {
            return Err(PendingRegisterError::LimitReached);
        }
        Ok(Self {
            active: HashMap::with_capacity(max_active),
            tombstones: VecDeque::with_capacity(max_tombstones),
            max_active,
            max_tombstones,
        })
    }

    /// 登记请求后才允许把 frame 放入 writer queue，防止未登记 response 产生副作用。
    pub(crate) fn register(
        &mut self,
        id: impl Into<String>,
        deadline: Instant,
    ) -> Result<Receiver<Result<RpcFrame, AppServerProcessError>>, PendingRegisterError> {
        let id = id.into();
        if self.active.contains_key(&id) || self.tombstones.iter().any(|item| item.id == id) {
            return Err(PendingRegisterError::DuplicateRequest);
        }
        if self.active.len() >= self.max_active {
            return Err(PendingRegisterError::LimitReached);
        }
        // 每个 pending 只允许一个 terminal 投递；容量 1 让该不变量同时成为
        // 内存边界，不能因未来增加重复完成路径而退化为无界 channel。
        let (completion, receiver) = mpsc::sync_channel(1);
        self.active.insert(
            id,
            PendingEntry {
                deadline,
                completion,
            },
        );
        Ok(receiver)
    }

    /// 只有 active entry 可以被 response 恢复，确保 approval/tool exactly-once。
    pub(crate) fn resolve(&mut self, frame: RpcFrame) -> ResolveDisposition {
        let id = frame.id().to_owned();
        if let Some(entry) = self.active.remove(&id) {
            let _ = entry.completion.send(Ok(frame));
            self.remember(id, PendingTerminal::Resolved);
            ResolveDisposition::Delivered
        } else {
            self.tombstone_disposition(&id)
        }
    }

    /// 按 deadline 收口 pending，调用方随后只能把迟到 response 分类为 late。
    pub(crate) fn expire(&mut self, now: Instant) -> usize {
        let expired: Vec<String> = self
            .active
            .iter()
            .filter(|(_, entry)| entry.deadline <= now)
            .map(|(id, _)| id.clone())
            .collect();
        for id in &expired {
            if let Some(entry) = self.active.remove(id) {
                let _ = entry
                    .completion
                    .send(Err(AppServerProcessError::DeadlineExceeded));
                self.remember(id.clone(), PendingTerminal::TimedOut);
            }
        }
        expired.len()
    }

    /// 取消必须和 timeout 一样留下 tombstone，不允许晚到 response 恢复动作。
    pub(crate) fn cancel(&mut self, id: &str) -> bool {
        let Some(entry) = self.active.remove(id) else {
            return false;
        };
        let _ = entry.completion.send(Err(AppServerProcessError::Cancelled));
        self.remember(id.to_owned(), PendingTerminal::Cancelled);
        true
    }

    /// 关闭 session 时一次性释放 waiters，避免 UI 永久等待无意义的 receiver。
    pub(crate) fn close(&mut self) -> usize {
        let ids: Vec<String> = self.active.keys().cloned().collect();
        for id in &ids {
            if let Some(entry) = self.active.remove(id) {
                let _ = entry
                    .completion
                    .send(Err(AppServerProcessError::SessionClosed));
                self.remember(id.clone(), PendingTerminal::Cancelled);
            }
        }
        ids.len()
    }

    /// response 只按 tombstone 分类，未知 ID 不会被当成可恢复的 response。
    fn tombstone_disposition(&self, id: &str) -> ResolveDisposition {
        match self.tombstones.iter().find(|item| item.id == id) {
            Some(Tombstone {
                terminal: PendingTerminal::Resolved,
                ..
            }) => ResolveDisposition::DuplicateResponse,
            Some(Tombstone {
                terminal: PendingTerminal::TimedOut | PendingTerminal::Cancelled,
                ..
            }) => ResolveDisposition::LateResponse,
            None => ResolveDisposition::UnknownRequest,
        }
    }

    /// 淘汰最旧 tombstone，保证其审计价值不会变成无界内存占用。
    fn remember(&mut self, id: String, terminal: PendingTerminal) {
        if let Some(index) = self.tombstones.iter().position(|item| item.id == id) {
            self.tombstones.remove(index);
        }
        while self.tombstones.len() >= self.max_tombstones {
            self.tombstones.pop_front();
        }
        self.tombstones.push_back(Tombstone { id, terminal });
    }
}

/// 把协议超时转换为独立的 deadline，避免依赖线程 sleep 做超时判断。
pub(crate) fn deadline_after(timeout: Duration) -> Result<Instant, AppServerProcessError> {
    Instant::now()
        .checked_add(timeout)
        .ok_or(AppServerProcessError::InvalidTimeout)
}

impl From<PendingRegisterError> for AppServerProcessError {
    /// 在 registry 所属模块内完成错误投影，使私有登记分类无需穿过 client 边界。
    fn from(error: PendingRegisterError) -> Self {
        match error {
            PendingRegisterError::DuplicateRequest => Self::DuplicateRequest,
            PendingRegisterError::LimitReached => Self::PendingLimit,
        }
    }
}
