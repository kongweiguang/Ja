// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 无第三方状态依赖的严格字节预算 LRU。

use super::error::{AttachmentPreviewError, AttachmentPreviewErrorCode};
use super::model::{AttachmentResourceToken, AttachmentResourceVariant};
use std::collections::HashMap;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) struct CachedResourceKey {
    pub token: AttachmentResourceToken,
    pub variant: AttachmentResourceVariant,
}

#[derive(Debug)]
struct CachedResource {
    bytes: Arc<[u8]>,
    last_used: u64,
}

/// entry 数量受 session 数约束、总 bytes 受硬预算约束；访问序列只用于确定淘汰顺序。
#[derive(Debug)]
pub(crate) struct AttachmentPreviewCache {
    maximum_bytes: usize,
    used_bytes: usize,
    access_sequence: u64,
    entries: HashMap<CachedResourceKey, CachedResource>,
}

impl AttachmentPreviewCache {
    /// 零容量无法服务任何资源，直接视为配置错误而不创建退化缓存。
    pub(crate) fn new(maximum_bytes: usize) -> Result<Self, AttachmentPreviewError> {
        if maximum_bytes == 0 {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::InvalidInput,
            ));
        }
        Ok(Self {
            maximum_bytes,
            used_bytes: 0,
            access_sequence: 0,
            entries: HashMap::new(),
        })
    }

    /// 单项超过总预算时不驱逐已有预览，避免一次恶意资源破坏仍可使用的会话。
    pub(crate) fn insert(
        &mut self,
        key: CachedResourceKey,
        bytes: Vec<u8>,
    ) -> Result<Arc<[u8]>, AttachmentPreviewError> {
        if bytes.len() > self.maximum_bytes {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::CacheBudgetExceeded,
            ));
        }
        self.remove(key);
        while self.used_bytes.saturating_add(bytes.len()) > self.maximum_bytes {
            let Some(oldest) = self
                .entries
                .iter()
                .min_by_key(|(_, value)| value.last_used)
                .map(|(key, _)| *key)
            else {
                return Err(AttachmentPreviewError::new(
                    AttachmentPreviewErrorCode::CacheBudgetExceeded,
                ));
            };
            self.remove(oldest);
        }
        let bytes: Arc<[u8]> = bytes.into();
        self.used_bytes = self.used_bytes.saturating_add(bytes.len());
        let last_used = self.next_sequence();
        self.entries.insert(
            key,
            CachedResource {
                bytes: Arc::clone(&bytes),
                last_used,
            },
        );
        Ok(bytes)
    }

    /// 命中时刷新最近访问顺序；返回 Arc 让 protocol response 在锁外安全发送。
    pub(crate) fn get(&mut self, key: CachedResourceKey) -> Option<Arc<[u8]>> {
        let last_used = self.next_sequence();
        let resource = self.entries.get_mut(&key)?;
        resource.last_used = last_used;
        Some(Arc::clone(&resource.bytes))
    }

    /// session/token 清理会删除两个衍生物并立刻归还预算。
    pub(crate) fn remove_token(&mut self, token: AttachmentResourceToken) {
        for variant in [
            AttachmentResourceVariant::Thumbnail,
            AttachmentResourceVariant::Preview,
        ] {
            self.remove(CachedResourceKey { token, variant });
        }
    }

    /// Workspace 切换和应用退出必须同步释放全部资源，不等待 LRU 自然淘汰。
    pub(crate) fn clear(&mut self) {
        self.entries.clear();
        self.used_bytes = 0;
    }

    /// 单项淘汰同步扣减实际字节，避免预算只按 entry 数量估算而被大图绕过。
    fn remove(&mut self, key: CachedResourceKey) {
        if let Some(resource) = self.entries.remove(&key) {
            self.used_bytes = self.used_bytes.saturating_sub(resource.bytes.len());
        }
    }

    /// wrapping 只会降低最近性精度，不影响授权或容量；达到上限时重新编号保持顺序可比。
    fn next_sequence(&mut self) -> u64 {
        if self.access_sequence == u64::MAX {
            let mut ordered = self.entries.values_mut().collect::<Vec<_>>();
            ordered.sort_by_key(|value| value.last_used);
            for (index, value) in ordered.into_iter().enumerate() {
                value.last_used = u64::try_from(index).unwrap_or(u64::MAX - 1);
            }
            self.access_sequence = u64::try_from(self.entries.len()).unwrap_or(u64::MAX - 1);
        }
        self.access_sequence = self.access_sequence.saturating_add(1);
        self.access_sequence
    }

    /// 测试与退出门禁只读取聚合预算，不暴露缓存 entry 或 bearer token。
    pub(crate) fn used_bytes(&self) -> usize {
        self.used_bytes
    }
}
