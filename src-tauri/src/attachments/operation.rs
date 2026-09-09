// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 附件导入 operation、逐项取消与短期 retry capability 的进程内所有权。

use super::error::{AttachmentIngressError, AttachmentIngressErrorCode};
use super::model::IngressAttachment;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;

const MAX_TRACKED_OPERATIONS: usize = 128;
const MAX_RETRY_ATTEMPTS: usize = 64;
const PENDING_CANCEL_TTL: Duration = Duration::from_secs(30);
const COMPLETED_OPERATION_TTL: Duration = Duration::from_secs(300);
pub(crate) const RETRY_ATTEMPT_TTL: Duration = Duration::from_secs(300);

#[derive(Debug)]
enum OperationEntry {
    PendingCancelled {
        expires_at: Instant,
    },
    Active {
        all: CancellationToken,
        items: HashMap<String, CancellationToken>,
    },
    Completed {
        expires_at: Instant,
    },
}

#[derive(Debug, Default)]
struct OperationState {
    entries: HashMap<String, OperationEntry>,
}

/// 一个 item 同时观察 operation 与自身取消信号，保证整批取消和独立 X 使用同一单调事实。
#[derive(Debug, Clone)]
pub(crate) struct ItemCancellation {
    operation: CancellationToken,
    item: CancellationToken,
}

impl ItemCancellation {
    /// 任一上级信号成立即停止尚未提交的复制或导入；已提交 App Server 的结果不能被伪造为取消。
    pub(crate) fn is_cancelled(&self) -> bool {
        self.operation.is_cancelled() || self.item.is_cancelled()
    }
}

/// operation registry 有界保存提前取消 tombstone，关闭 cancel/start 的跨 command 竞态。
#[derive(Debug, Default)]
pub(crate) struct AttachmentOperationRegistry {
    state: Mutex<OperationState>,
}

impl AttachmentOperationRegistry {
    /// 在启动 dialog 或 worker 前占用 operation identity；重复或刚完成的 identity 失败关闭。
    pub(crate) fn begin(&self, operation_id: &str) -> Result<(), AttachmentIngressError> {
        let mut state = self.lock()?;
        Self::prune(&mut state, Instant::now());
        let all = CancellationToken::new();
        match state.entries.remove(operation_id) {
            Some(OperationEntry::PendingCancelled { .. }) => all.cancel(),
            Some(entry @ (OperationEntry::Active { .. } | OperationEntry::Completed { .. })) => {
                state.entries.insert(operation_id.to_owned(), entry);
                return Err(AttachmentIngressError::new(
                    AttachmentIngressErrorCode::OperationConflict,
                ));
            }
            None if state.entries.len() >= MAX_TRACKED_OPERATIONS => {
                return Err(AttachmentIngressError::new(
                    AttachmentIngressErrorCode::StateUnavailable,
                ));
            }
            None => {}
        }
        state.entries.insert(
            operation_id.to_owned(),
            OperationEntry::Active {
                all,
                items: HashMap::new(),
            },
        );
        Ok(())
    }

    /// item identity 在任何 started event 前登记，使用户立即点击 X 也能命中精确取消句柄。
    pub(crate) fn register_item(
        &self,
        operation_id: &str,
        item_id: &str,
    ) -> Result<ItemCancellation, AttachmentIngressError> {
        let mut state = self.lock()?;
        let Some(OperationEntry::Active { all, items }) = state.entries.get_mut(operation_id)
        else {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::InvalidOperation,
            ));
        };
        if items.contains_key(item_id) {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::OperationConflict,
            ));
        }
        let item = CancellationToken::new();
        items.insert(item_id.to_owned(), item.clone());
        Ok(ItemCancellation {
            operation: all.clone(),
            item,
        })
    }

    /// itemId 缺省时取消整个 operation；显式 itemId 只影响对应附件，避免多选批次相互连坐。
    pub(crate) fn cancel(
        &self,
        operation_id: &str,
        item_id: Option<&str>,
    ) -> Result<bool, AttachmentIngressError> {
        let mut state = self.lock()?;
        let now = Instant::now();
        Self::prune(&mut state, now);
        match state.entries.get(operation_id) {
            Some(OperationEntry::Active { all, items }) => {
                if let Some(item_id) = item_id {
                    let Some(item) = items.get(item_id) else {
                        return Ok(false);
                    };
                    item.cancel();
                } else {
                    all.cancel();
                }
                Ok(true)
            }
            Some(OperationEntry::PendingCancelled { .. }) => Ok(true),
            Some(OperationEntry::Completed { .. }) => Ok(false),
            None if item_id.is_some() => Ok(false),
            None if state.entries.len() >= MAX_TRACKED_OPERATIONS => Err(
                AttachmentIngressError::new(AttachmentIngressErrorCode::StateUnavailable),
            ),
            None => {
                state.entries.insert(
                    operation_id.to_owned(),
                    OperationEntry::PendingCancelled {
                        expires_at: now + PENDING_CANCEL_TTL,
                    },
                );
                Ok(true)
            }
        }
    }

    /// worker 终态留下短期 tombstone，迟到 cancel 不得命中新一轮复用 identity。
    pub(crate) fn finish(&self, operation_id: &str) {
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        if matches!(
            state.entries.get(operation_id),
            Some(OperationEntry::Active { .. })
        ) {
            state.entries.insert(
                operation_id.to_owned(),
                OperationEntry::Completed {
                    expires_at: Instant::now() + COMPLETED_OPERATION_TTL,
                },
            );
        }
    }

    /// app shutdown 单调取消全部 active worker 并清空 tombstone，避免下一生命周期继承旧身份。
    pub(crate) fn shutdown(&self) {
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        for entry in state.entries.values() {
            if let OperationEntry::Active { all, .. } = entry {
                all.cancel();
            }
        }
        state.entries.clear();
    }

    /// Mutex poison 表示取消状态不再可信，必须稳定失败而不是恢复半个 registry。
    fn lock(&self) -> Result<std::sync::MutexGuard<'_, OperationState>, AttachmentIngressError> {
        self.state
            .lock()
            .map_err(|_| AttachmentIngressError::new(AttachmentIngressErrorCode::StateUnavailable))
    }

    /// 仅清理失去竞态保护价值的 tombstone；active owner 永不被容量策略回收。
    fn prune(state: &mut OperationState, now: Instant) {
        state.entries.retain(|_, entry| match entry {
            OperationEntry::Active { .. } => true,
            OperationEntry::PendingCancelled { expires_at }
            | OperationEntry::Completed { expires_at } => *expires_at > now,
        });
    }
}

/// 失败项的 retry capability 只持有 Rust staging identity 和 UI item identity，不保存源路径。
#[derive(Debug, Clone)]
pub(crate) struct RetryAttempt {
    pub(crate) item_id: String,
    pub(crate) attachment: IngressAttachment,
    expires_at: Instant,
}

/// retry registry 使用一次性 take；重试开始后旧 attempt 永远不能再次消费。
#[derive(Debug, Default)]
pub(crate) struct RetryAttemptRegistry {
    attempts: Mutex<HashMap<String, RetryAttempt>>,
}

impl RetryAttemptRegistry {
    /// 只有 Runtime 明确可重试的失败进入 registry；达到上限时 caller 必须清理 staging。
    pub(crate) fn insert(
        &self,
        attempt_id: String,
        item_id: String,
        attachment: IngressAttachment,
    ) -> Result<(), AttachmentIngressError> {
        let mut attempts = self.lock()?;
        if attempts.len() >= MAX_RETRY_ATTEMPTS || attempts.contains_key(&attempt_id) {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::StateUnavailable,
            ));
        }
        attempts.insert(
            attempt_id,
            RetryAttempt {
                item_id,
                attachment,
                expires_at: Instant::now() + RETRY_ATTEMPT_TTL,
            },
        );
        Ok(())
    }

    /// retry/discard 都消费 capability；TTL 判定交给持有 staging owner 的 service 执行清理。
    pub(crate) fn take(&self, attempt_id: &str) -> Result<RetryAttempt, AttachmentIngressError> {
        let mut attempts = self.lock()?;
        attempts
            .remove(attempt_id)
            .ok_or_else(|| AttachmentIngressError::new(AttachmentIngressErrorCode::AttemptNotFound))
    }

    /// TTL worker 只移除仍匹配的唯一 attempt，返回值交给 ingress owner 删除对应 staging。
    pub(crate) fn expire(&self, attempt_id: &str) -> Option<RetryAttempt> {
        self.attempts.lock().ok()?.remove(attempt_id)
    }

    /// shutdown 返回全部记录，由 staging registry 统一执行真实文件清理。
    pub(crate) fn drain(&self) -> Vec<RetryAttempt> {
        self.attempts
            .lock()
            .map(|mut attempts| attempts.drain().map(|(_, value)| value).collect())
            .unwrap_or_default()
    }

    /// Mutex poison 后 capability ownership 不可信，禁止猜测恢复。
    fn lock(
        &self,
    ) -> Result<std::sync::MutexGuard<'_, HashMap<String, RetryAttempt>>, AttachmentIngressError>
    {
        self.attempts
            .lock()
            .map_err(|_| AttachmentIngressError::new(AttachmentIngressErrorCode::StateUnavailable))
    }
}

impl RetryAttempt {
    /// service 在消费 capability 后判定 TTL，才能在过期分支同时删除其 staging ownership。
    pub(crate) fn is_expired(&self) -> bool {
        self.expires_at <= Instant::now()
    }
}
