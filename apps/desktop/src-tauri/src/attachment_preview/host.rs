// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! App Server preview session 到短期资源 token 的唯一映射与资源生命周期。

use super::cache::{AttachmentPreviewCache, CachedResourceKey};
use super::error::{AttachmentPreviewError, AttachmentPreviewErrorCode};
use super::image_pipeline::AttachmentImageDerivatives;
use super::model::{
    ATTACHMENT_PREVIEW_HOST, ATTACHMENT_PREVIEW_MAIN_WINDOW, ATTACHMENT_PREVIEW_SCHEME,
    AttachmentPreviewClock, AttachmentPreviewDescriptor, AttachmentPreviewKind,
    AttachmentPreviewLimits, AttachmentPreviewOpenResult, AttachmentPreviewSessionId,
    AttachmentResourceToken, AttachmentResourceVariant, MonotonicAttachmentPreviewClock,
};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

#[derive(Debug)]
struct AttachmentPreviewSession {
    descriptor: AttachmentPreviewDescriptor,
    token: AttachmentResourceToken,
    last_access: Duration,
}

struct AttachmentPreviewState {
    sessions: HashMap<String, AttachmentPreviewSession>,
    token_index: HashMap<AttachmentResourceToken, String>,
    cache: AttachmentPreviewCache,
}

/// managed host 不读取任意文件；调用方只可注入已从固定 App Server session 分段读取的字节。
pub struct AttachmentPreviewHost {
    limits: AttachmentPreviewLimits,
    clock: Arc<dyn AttachmentPreviewClock>,
    state: Mutex<AttachmentPreviewState>,
}

impl AttachmentPreviewHost {
    /// 生产构造固定使用单调时钟与产品预算，配置无效时在 setup 阶段失败关闭。
    pub fn new() -> Result<Self, AttachmentPreviewError> {
        Self::with_clock(
            AttachmentPreviewLimits::default(),
            Arc::new(MonotonicAttachmentPreviewClock::default()),
        )
    }

    /// 测试与未来平台适配可注入时钟，但预算仍在进入状态前校验。
    pub fn with_clock(
        limits: AttachmentPreviewLimits,
        clock: Arc<dyn AttachmentPreviewClock>,
    ) -> Result<Self, AttachmentPreviewError> {
        if limits.idle_ttl.is_zero() || limits.max_sessions == 0 || limits.max_sessions > 32 {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::InvalidInput,
            ));
        }
        Ok(Self {
            limits,
            clock,
            state: Mutex::new(AttachmentPreviewState {
                sessions: HashMap::new(),
                token_index: HashMap::new(),
                cache: AttachmentPreviewCache::new(limits.max_cache_bytes)?,
            }),
        })
    }

    /// open 只为 image/text 建立授权；图片 URL 在衍生物成功进入缓存后才返回，防止假入口。
    pub fn open(
        &self,
        window_label: &str,
        workspace_identity: String,
        descriptor: AttachmentPreviewDescriptor,
        derivatives: Option<AttachmentImageDerivatives>,
    ) -> Result<AttachmentPreviewOpenResult, AttachmentPreviewError> {
        Self::authorize_window(window_label)?;
        validate_descriptor(&descriptor)?;
        if workspace_identity.is_empty() || workspace_identity.len() > 128 {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::InvalidInput,
            ));
        }
        match descriptor.preview_kind {
            AttachmentPreviewKind::Image if derivatives.is_none() => {
                return Err(AttachmentPreviewError::new(
                    AttachmentPreviewErrorCode::SourceUnavailable,
                ));
            }
            AttachmentPreviewKind::Text if derivatives.is_some() => {
                return Err(AttachmentPreviewError::new(
                    AttachmentPreviewErrorCode::InvalidInput,
                ));
            }
            _ => {}
        }
        let now = self.clock.now();
        let mut state = self.lock_state()?;
        self.expire_locked(&mut state, now);
        if state.sessions.len() >= self.limits.max_sessions {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::SourceUnavailable,
            ));
        }
        if state
            .sessions
            .values()
            .any(|session| session.descriptor.preview_session_id == descriptor.preview_session_id)
        {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::InvalidInput,
            ));
        }
        let token = AttachmentResourceToken::generate();
        // UI session 与资源 token 可关联但均不复用 App Server identity；WebView 因此无法
        // 绕过 host 直接选择 Java session，close/read 仍需经过本地 registry 授权。
        let public_session_id = format!("prv_{token}");
        if let Some(derivatives) = derivatives {
            state.cache.insert(
                CachedResourceKey {
                    token,
                    variant: AttachmentResourceVariant::Thumbnail,
                },
                derivatives.thumbnail_png,
            )?;
            if let Err(error) = state.cache.insert(
                CachedResourceKey {
                    token,
                    variant: AttachmentResourceVariant::Preview,
                },
                derivatives.preview_png,
            ) {
                state.cache.remove_token(token);
                return Err(error);
            }
        }
        let (resource_url, thumbnail_url) =
            if descriptor.preview_kind == AttachmentPreviewKind::Image {
                (
                    Some(resource_url(AttachmentResourceVariant::Preview, token)),
                    Some(resource_url(AttachmentResourceVariant::Thumbnail, token)),
                )
            } else {
                (None, None)
            };
        let result = AttachmentPreviewOpenResult {
            preview_session_id: public_session_id.clone(),
            attachment_id: descriptor.attachment_id.clone(),
            display_name: descriptor.display_name.clone(),
            size_bytes: descriptor.size_bytes,
            media_kind: descriptor.media_kind.clone(),
            media_type: descriptor.media_type.clone(),
            preview_kind: descriptor.preview_kind,
            resource_url,
            thumbnail_url,
        };
        state.token_index.insert(token, public_session_id.clone());
        state.sessions.insert(
            public_session_id,
            AttachmentPreviewSession {
                descriptor,
                token,
                last_access: now,
            },
        );
        Ok(result)
    }

    /// protocol lookup 同时校验主窗口、token、TTL 与固定资源 variant，并在命中时刷新 idle TTL。
    pub fn resource(
        &self,
        window_label: &str,
        variant: AttachmentResourceVariant,
        token: AttachmentResourceToken,
    ) -> Result<Arc<[u8]>, AttachmentPreviewError> {
        Self::authorize_window(window_label)?;
        let now = self.clock.now();
        let mut state = self.lock_state()?;
        let session_id = state.token_index.get(&token).cloned().ok_or_else(|| {
            AttachmentPreviewError::new(AttachmentPreviewErrorCode::TokenNotFound)
        })?;
        let expired = state
            .sessions
            .get(&session_id)
            .is_none_or(|session| now.saturating_sub(session.last_access) >= self.limits.idle_ttl);
        if expired {
            Self::remove_session_locked(&mut state, &session_id);
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::TokenExpired,
            ));
        }
        let session = state.sessions.get_mut(&session_id).ok_or_else(|| {
            AttachmentPreviewError::new(AttachmentPreviewErrorCode::TokenNotFound)
        })?;
        session.last_access = now;
        state
            .cache
            .get(CachedResourceKey { token, variant })
            .ok_or_else(|| AttachmentPreviewError::new(AttachmentPreviewErrorCode::TokenNotFound))
    }

    /// text read 前验证 session 与 window；返回 opaque server identity 仅供 native runtime adapter 调用。
    pub fn authorize_text_read(
        &self,
        window_label: &str,
        preview_session_id: &str,
    ) -> Result<AttachmentPreviewSessionId, AttachmentPreviewError> {
        Self::authorize_window(window_label)?;
        let now = self.clock.now();
        let mut state = self.lock_state()?;
        let expired = state
            .sessions
            .get(preview_session_id)
            .is_some_and(|session| now.saturating_sub(session.last_access) >= self.limits.idle_ttl);
        if expired {
            Self::remove_session_locked(&mut state, preview_session_id);
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::TokenExpired,
            ));
        }
        let session = state.sessions.get_mut(preview_session_id).ok_or_else(|| {
            AttachmentPreviewError::new(AttachmentPreviewErrorCode::TokenNotFound)
        })?;
        if session.descriptor.preview_kind != AttachmentPreviewKind::Text {
            return Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::Unsupported,
            ));
        }
        session.last_access = now;
        Ok(session.descriptor.preview_session_id.clone())
    }

    /// close 在调用 App Server 前只解析本地 public identity；RPC 成功后再调用 `close` 提交释放。
    pub fn authorize_close(
        &self,
        window_label: &str,
        preview_session_id: &str,
    ) -> Result<AttachmentPreviewSessionId, AttachmentPreviewError> {
        Self::authorize_window(window_label)?;
        let state = self.lock_state()?;
        state
            .sessions
            .get(preview_session_id)
            .map(|session| session.descriptor.preview_session_id.clone())
            .ok_or_else(|| AttachmentPreviewError::new(AttachmentPreviewErrorCode::TokenNotFound))
    }

    /// close 在 session 缺失时也成功，避免 teardown/retry 需要额外查询造成竞态。
    pub fn close(
        &self,
        window_label: &str,
        preview_session_id: &str,
    ) -> Result<bool, AttachmentPreviewError> {
        Self::authorize_window(window_label)?;
        let mut state = self.lock_state()?;
        Ok(Self::remove_session_locked(&mut state, preview_session_id).is_some())
    }

    /// Workspace 切换先快照 server identity；只有远端全部幂等 close 成功后才由调用方 clear。
    pub fn server_sessions(
        &self,
    ) -> Result<Vec<AttachmentPreviewSessionId>, AttachmentPreviewError> {
        Ok(self
            .lock_state()?
            .sessions
            .values()
            .map(|session| session.descriptor.preview_session_id.clone())
            .collect())
    }

    /// Preview Tab 关闭或应用退出清理所有资源；返回 session 数供生命周期诊断而不泄露 identity。
    pub fn clear(&self) -> Result<usize, AttachmentPreviewError> {
        let mut state = self.lock_state()?;
        let count = state.sessions.len();
        state.sessions.clear();
        state.token_index.clear();
        state.cache.clear();
        Ok(count)
    }

    /// shutdown adapter 先取得 native-only server identity，再清空本地 bearer token；远端失败仍由其 TTL 收口。
    pub fn drain_server_sessions(
        &self,
    ) -> Result<Vec<AttachmentPreviewSessionId>, AttachmentPreviewError> {
        let mut state = self.lock_state()?;
        let sessions = state
            .sessions
            .values()
            .map(|session| session.descriptor.preview_session_id.clone())
            .collect::<Vec<_>>();
        state.sessions.clear();
        state.token_index.clear();
        state.cache.clear();
        Ok(sessions)
    }

    /// 主 WebView 是唯一资源消费者；HTTP 子 Preview 等其它窗口没有 capability。
    fn authorize_window(window_label: &str) -> Result<(), AttachmentPreviewError> {
        if window_label == ATTACHMENT_PREVIEW_MAIN_WINDOW {
            Ok(())
        } else {
            Err(AttachmentPreviewError::new(
                AttachmentPreviewErrorCode::WrongWindow,
            ))
        }
    }

    /// TTL 在每次受控访问前惰性执行，避免为最多 32 个 session 常驻后台定时任务。
    fn expire_locked(&self, state: &mut AttachmentPreviewState, now: Duration) {
        let expired = state
            .sessions
            .iter()
            .filter(|(_, session)| now.saturating_sub(session.last_access) >= self.limits.idle_ttl)
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        for id in expired {
            Self::remove_session_locked(state, &id);
        }
    }

    /// 撤销 session 时原子删除 token 索引与两个衍生物，防止 URL 在 close 后继续命中缓存。
    fn remove_session_locked(
        state: &mut AttachmentPreviewState,
        preview_session_id: &str,
    ) -> Option<AttachmentPreviewSession> {
        let session = state.sessions.remove(preview_session_id)?;
        state.token_index.remove(&session.token);
        state.cache.remove_token(session.token);
        Some(session)
    }

    /// poisoned state 无法证明授权索引仍一致，因此返回稳定错误而不恢复内部 map。
    fn lock_state(&self) -> Result<MutexGuard<'_, AttachmentPreviewState>, AttachmentPreviewError> {
        self.state
            .lock()
            .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::StateUnavailable))
    }

    /// 只公开聚合字节供退出 gate 验证资源归零，不形成 renderer 可枚举接口。
    pub fn cache_bytes(&self) -> Result<usize, AttachmentPreviewError> {
        Ok(self.lock_state()?.cache.used_bytes())
    }
}

/// 交叉校验 App Server 的 preview/media 类型与字段上限，拒绝把异常元数据签成原生资源 URL。
fn validate_descriptor(
    descriptor: &AttachmentPreviewDescriptor,
) -> Result<(), AttachmentPreviewError> {
    let valid_kind = match descriptor.preview_kind {
        AttachmentPreviewKind::Image => {
            descriptor.media_kind == "image" && descriptor.media_type.starts_with("image/")
        }
        AttachmentPreviewKind::Text => {
            descriptor.media_kind == "text" && descriptor.media_type.starts_with("text/")
        }
    };
    if !valid_kind
        || descriptor.attachment_id.is_empty()
        || descriptor.attachment_id.len() > 128
        || descriptor.display_name.is_empty()
        || descriptor.display_name.len() > 1024
        || descriptor.size_bytes == 0
        || descriptor.media_type.len() > 128
    {
        return Err(AttachmentPreviewError::new(
            AttachmentPreviewErrorCode::InvalidInput,
        ));
    }
    Ok(())
}

/// URL 字符串由 native host 完整构造；React 无需也不能取得 token 单字段。
fn resource_url(variant: AttachmentResourceVariant, token: AttachmentResourceToken) -> String {
    format!(
        "{ATTACHMENT_PREVIEW_SCHEME}://{ATTACHMENT_PREVIEW_HOST}/{}/{token}",
        variant.as_path()
    )
}
