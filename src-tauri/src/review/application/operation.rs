// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 可取消操作注册表。
//
// application 自己维护 operation identity 与协作式信号，infrastructure 通过回调桥接到
// Git/进程取消句柄。这样用例层不依赖任何具体 adapter，也不会为测试扩大生产 API。

use super::ReviewError;
use crate::review::domain::ReviewOperationId;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;

const MAX_TRACKED_OPERATIONS: usize = 1024;
const PENDING_CANCEL_TTL: Duration = Duration::from_secs(30);
const COMPLETED_TTL: Duration = Duration::from_secs(300);

enum OperationEntry {
    PendingCancelled { expires_at: Instant },
    Active(CancellationToken),
    Completed { expires_at: Instant },
}

#[derive(Default)]
struct OperationRegistry {
    entries: HashMap<String, OperationEntry>,
}

impl OperationRegistry {
    /// 清除已经失去竞态保护价值的 tombstone；active owner 永不被容量回收。
    fn prune_expired(&mut self, now: Instant) {
        self.entries.retain(|_, entry| match entry {
            OperationEntry::Active(_) => true,
            OperationEntry::PendingCancelled { expires_at }
            | OperationEntry::Completed { expires_at } => *expires_at > now,
        });
    }
}

/// 绑定 operation id 与取消信号；Drop 必须释放注册项，避免完成后误取消后续请求。
pub(crate) struct ReviewOperation {
    id: Option<ReviewOperationId>,
    cancellation: CancellationToken,
}

impl ReviewOperation {
    /// 注册 bounded operation；identity 已由 domain 校验，重复 id 仍失败关闭。
    pub(crate) fn begin(operation_id: Option<ReviewOperationId>) -> Result<Self, ReviewError> {
        let cancellation = CancellationToken::new();
        if let Some(id) = operation_id.as_ref() {
            let mut registry = operation_registry().lock().map_err(|_| ReviewError::Io)?;
            registry.prune_expired(Instant::now());
            match registry.entries.get(id.as_str()) {
                Some(OperationEntry::PendingCancelled { .. }) => cancellation.cancel(),
                Some(OperationEntry::Active(_) | OperationEntry::Completed { .. }) => {
                    return Err(ReviewError::InvalidInput);
                }
                None if registry.entries.len() >= MAX_TRACKED_OPERATIONS => {
                    return Err(ReviewError::InvalidInput);
                }
                None => {}
            }
            registry.entries.insert(
                id.as_str().to_owned(),
                OperationEntry::Active(cancellation.clone()),
            );
        }
        Ok(Self {
            id: operation_id,
            cancellation,
        })
    }

    /// 返回共享取消令牌；application、Git runner 与事务阶段观察同一个单调取消事实。
    pub(crate) fn cancellation(&self) -> CancellationToken {
        self.cancellation.clone()
    }

    /// 幂等记录取消意图；worker 尚未注册时保留短期 tombstone 关闭 spawn 竞态。
    pub(crate) fn cancel(operation_id: &ReviewOperationId) -> Result<bool, ReviewError> {
        let mut registry = operation_registry().lock().map_err(|_| ReviewError::Io)?;
        let now = Instant::now();
        registry.prune_expired(now);
        match registry.entries.get(operation_id.as_str()) {
            Some(OperationEntry::Active(cancellation)) => {
                cancellation.cancel();
                Ok(true)
            }
            Some(OperationEntry::PendingCancelled { .. }) => Ok(true),
            Some(OperationEntry::Completed { .. }) => Ok(false),
            None if registry.entries.len() >= MAX_TRACKED_OPERATIONS => {
                Err(ReviewError::InvalidInput)
            }
            None => {
                registry.entries.insert(
                    operation_id.as_str().to_owned(),
                    OperationEntry::PendingCancelled {
                        expires_at: now + PENDING_CANCEL_TTL,
                    },
                );
                Ok(true)
            }
        }
    }
}

impl Drop for ReviewOperation {
    /// 将 operation 标记为短期 completed，区分完成后取消与尚未注册的提前取消。
    fn drop(&mut self) {
        let Some(id) = self.id.as_ref() else {
            return;
        };
        if let Ok(mut registry) = operation_registry().lock()
            && matches!(
                registry.entries.get(id.as_str()),
                Some(OperationEntry::Active(_))
            )
        {
            registry.entries.insert(
                id.as_str().to_owned(),
                OperationEntry::Completed {
                    expires_at: Instant::now() + COMPLETED_TTL,
                },
            );
        }
    }
}

/// 延迟创建进程级注册表，避免无 Review 请求时初始化额外运行时状态。
fn operation_registry() -> &'static Mutex<OperationRegistry> {
    static REGISTRY: OnceLock<Mutex<OperationRegistry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(OperationRegistry::default()))
}
