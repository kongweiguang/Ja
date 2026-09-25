// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::error::{AttachmentIngressError, AttachmentIngressErrorCode, map_io_error};
use super::model::{IngressAttachment, IngressLimits, IngressToken};
use super::operation::{
    AttachmentOperationRegistry, ItemCancellation, RetryAttempt, RetryAttemptRegistry,
};
use super::platform::{
    FileSnapshot, create_staging_file, open_source, prepare_ingress_root, rename_no_replace,
    require_regular_metadata, snapshot_file, snapshot_path, validate_source_path,
    verify_ingress_root,
};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

const COPY_BUFFER_BYTES: usize = 64 * 1024;
const TOKEN_COLLISION_RETRIES: usize = 16;

#[derive(Debug)]
pub(crate) struct AdmittedAttachmentSource {
    source: PathBuf,
    pub(crate) display_name: String,
    pub(crate) size_bytes: u64,
    snapshot: FileSnapshot,
}

#[derive(Debug)]
struct StagedGuard {
    path: PathBuf,
    armed: bool,
}

impl StagedGuard {
    /// guard 在每个失败分支只删除本次生成的随机 staging 节点；源文件始终由用户持有。
    fn new(path: PathBuf) -> Self {
        Self { path, armed: true }
    }

    /// batch 全部写入 registry 后才解除 guard，确保 partial failure 保持全有或全无。
    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for StagedGuard {
    /// Drop 是失败与 panic 的最后清理网；删除目标只来自 Rust-owned root 与随机 token。
    fn drop(&mut self) {
        if self.armed {
            let _ = fs::remove_file(&self.path);
        }
    }
}

#[derive(Debug)]
struct PreparedAttachment {
    value: IngressAttachment,
    guard: StagedGuard,
}

/// 单个 app run 的附件 ingress owner；registry 是 token→staging path 的唯一映射。
#[derive(Debug)]
pub(crate) struct AttachmentIngress {
    root: PathBuf,
    limits: IngressLimits,
    staged: Mutex<HashMap<IngressToken, PathBuf>>,
    operations: AttachmentOperationRegistry,
    retry_attempts: RetryAttemptRegistry,
    closed: AtomicBool,
}

impl AttachmentIngress {
    /// 在 Rust-owned run 下建立唯一 staging 子目录；相对路径或 reparse root 一律拒绝。
    pub(crate) fn new(run_root: impl AsRef<Path>) -> Result<Self, AttachmentIngressError> {
        Self::with_limits(run_root, IngressLimits::default())
    }

    /// 显式预算构造主要服务快速边界测试；生产 composition 必须使用 `new` 的固定产品预算。
    pub(crate) fn with_limits(
        run_root: impl AsRef<Path>,
        limits: IngressLimits,
    ) -> Result<Self, AttachmentIngressError> {
        let root = prepare_ingress_root(run_root.as_ref())?;
        Ok(Self {
            root,
            limits,
            staged: Mutex::new(HashMap::new()),
            operations: AttachmentOperationRegistry::default(),
            retry_attempts: RetryAttemptRegistry::default(),
            closed: AtomicBool::new(false),
        })
    }

    /// Channel workflow 先对整批完成数量与总量 admission，再逐项复制，避免重复添加绕过原生预算。
    pub(crate) fn admit_paths(
        &self,
        paths: Vec<PathBuf>,
    ) -> Result<Vec<AdmittedAttachmentSource>, AttachmentIngressError> {
        self.require_open()?;
        verify_ingress_root(&self.root)?;
        if paths.is_empty() {
            return Ok(Vec::new());
        }
        if paths.len() > self.limits.max_files {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::TooManyFiles,
            ));
        }
        let mut batch_bytes = 0_u64;
        let mut admitted = Vec::with_capacity(paths.len());
        for source in paths {
            let current = self.preflight(source)?;
            batch_bytes = batch_bytes
                .checked_add(current.snapshot.size)
                .ok_or_else(|| {
                    AttachmentIngressError::new(AttachmentIngressErrorCode::BatchTooLarge)
                })?;
            if batch_bytes > self.limits.max_batch_bytes {
                return Err(AttachmentIngressError::new(
                    AttachmentIngressErrorCode::BatchTooLarge,
                ));
            }
            admitted.push(current);
        }
        Ok(admitted)
    }

    /// 已 admission 的单项复制可独立取消并报告确定字节进度；成功后立即进入 token registry。
    pub(crate) fn stage_admitted_with_control(
        &self,
        source: AdmittedAttachmentSource,
        cancellation: &ItemCancellation,
        mut progress: impl FnMut(u64, u64),
    ) -> Result<IngressAttachment, AttachmentIngressError> {
        self.require_open()?;
        let prepared = self.stage_one(source, &|| cancellation.is_cancelled(), &mut progress)?;
        self.register_prepared(prepared)
    }

    /// 剪贴板像素编码后直接进入 Rust-owned staging，避免制造临时源路径或扩大 filesystem scope。
    pub(crate) fn stage_bytes_with_control(
        &self,
        display_name: String,
        bytes: &[u8],
        cancellation: &ItemCancellation,
        mut progress: impl FnMut(u64, u64),
    ) -> Result<IngressAttachment, AttachmentIngressError> {
        self.require_open()?;
        verify_ingress_root(&self.root)?;
        let total = u64::try_from(bytes.len())
            .map_err(|_| AttachmentIngressError::new(AttachmentIngressErrorCode::FileTooLarge))?;
        if total > self.limits.max_file_bytes || total > self.limits.max_batch_bytes {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::FileTooLarge,
            ));
        }
        let (token, part_path, final_path, mut staging) = self.create_unique_staging()?;
        let mut guard = StagedGuard::new(part_path);
        let mut digest = Sha256::new();
        let mut copied = 0_u64;
        for chunk in bytes.chunks(COPY_BUFFER_BYTES) {
            if cancellation.is_cancelled() {
                return Err(AttachmentIngressError::new(
                    AttachmentIngressErrorCode::Cancelled,
                ));
            }
            staging.write_all(chunk).map_err(|error| {
                map_io_error(
                    AttachmentIngressErrorCode::StagingFailed,
                    "staging_bytes_write",
                    error,
                )
            })?;
            digest.update(chunk);
            copied = copied.saturating_add(u64::try_from(chunk.len()).unwrap_or(u64::MAX));
            progress(copied, total);
        }
        if cancellation.is_cancelled() {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::Cancelled,
            ));
        }
        staging.flush().map_err(|error| {
            map_io_error(
                AttachmentIngressErrorCode::StagingFailed,
                "staging_bytes_flush",
                error,
            )
        })?;
        staging.sync_all().map_err(|error| {
            map_io_error(
                AttachmentIngressErrorCode::StagingFailed,
                "staging_bytes_sync",
                error,
            )
        })?;
        drop(staging);
        rename_no_replace(&guard.path, &final_path)?;
        guard.path = final_path;
        self.register_prepared(PreparedAttachment {
            value: IngressAttachment {
                ingress_token: token,
                display_name,
                size_bytes: copied,
                sha256: hex_lower(&digest.finalize()),
            },
            guard,
        })
    }

    /// operation 在 dialog 前登记，确保取消命令不会和异步 picker callback 丢失竞态。
    pub(crate) fn begin_operation(&self, operation_id: &str) -> Result<(), AttachmentIngressError> {
        self.require_open()?;
        self.operations.begin(operation_id)
    }

    /// item 在 started event 前绑定独立取消句柄，整批和逐项取消均不泄漏源文件信息。
    pub(crate) fn register_operation_item(
        &self,
        operation_id: &str,
        item_id: &str,
    ) -> Result<ItemCancellation, AttachmentIngressError> {
        self.operations.register_item(operation_id, item_id)
    }

    /// cancel 只接受 UI 已持有的 opaque identity，不接受路径或 ingress token。
    pub(crate) fn cancel_operation(
        &self,
        operation_id: &str,
        item_id: Option<&str>,
    ) -> Result<bool, AttachmentIngressError> {
        self.operations.cancel(operation_id, item_id)
    }

    /// worker 所有终态共用 finish tombstone，迟到 cancel 不会作用到未来复用 identity。
    pub(crate) fn finish_operation(&self, operation_id: &str) {
        self.operations.finish(operation_id);
    }

    /// Runtime 可重试失败保留一次性 attempt；容量失败时 caller 负责立即删除 staging。
    pub(crate) fn retain_retry_attempt(
        &self,
        attempt_id: String,
        item_id: String,
        attachment: IngressAttachment,
    ) -> Result<(), AttachmentIngressError> {
        self.retry_attempts.insert(attempt_id, item_id, attachment)
    }

    /// retry 消费旧 attempt，确保双击或并发调用只能有一个 worker 获得 staging 所有权。
    pub(crate) fn take_retry_attempt(
        &self,
        attempt_id: &str,
    ) -> Result<RetryAttempt, AttachmentIngressError> {
        let attempt = self.retry_attempts.take(attempt_id)?;
        if attempt.is_expired() {
            let _ = self.discard(&attempt.attachment.ingress_token);
            Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::AttemptNotFound,
            ))
        } else {
            Ok(attempt)
        }
    }

    /// 显式移除或 TTL 到期都删除 retry staging；未知 capability 稳定失败关闭。
    pub(crate) fn discard_retry_attempt(
        &self,
        attempt_id: &str,
    ) -> Result<(), AttachmentIngressError> {
        let attempt = self.retry_attempts.take(attempt_id)?;
        self.discard(&attempt.attachment.ingress_token)
    }

    /// TTL worker 幂等清理仍存在的 attempt；已被 retry/discard 消费时为空成功。
    pub(crate) fn expire_retry_attempt(&self, attempt_id: &str) {
        if let Some(attempt) = self.retry_attempts.expire(attempt_id) {
            let _ = self.discard(&attempt.attachment.ingress_token);
        }
    }

    /// App Server 已完成受管导入后删除 staging；失败保留 registry 映射，调用方可重试 cleanup。
    pub(crate) fn complete(&self, token: &IngressToken) -> Result<(), AttachmentIngressError> {
        self.remove_staged(token)
    }

    /// 用户移除草稿或 JA-RPC 失败时复用同一幂等边界之外的显式 discard；未知 token 仍是错误。
    pub(crate) fn discard(&self, token: &IngressToken) -> Result<(), AttachmentIngressError> {
        self.remove_staged(token)
    }

    /// app shutdown 关闭 admission 并清理仍受 registry 管理的全部 staging；重复调用安全为空成功。
    pub(crate) fn shutdown(&self) -> Result<(), AttachmentIngressError> {
        self.closed.store(true, Ordering::Release);
        self.operations.shutdown();
        for attempt in self.retry_attempts.drain() {
            let _ = self.discard(&attempt.attachment.ingress_token);
        }
        let mut registry = self.staged.lock().map_err(|_| {
            AttachmentIngressError::new(AttachmentIngressErrorCode::StateUnavailable)
        })?;
        let mut cleanup_failed = false;
        registry.retain(|_, path| match fs::remove_file(path) {
            Ok(()) => false,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
            Err(error) => {
                let _ = map_io_error(
                    AttachmentIngressErrorCode::CleanupFailed,
                    "staging_shutdown_remove",
                    error,
                );
                cleanup_failed = true;
                true
            }
        });
        if cleanup_failed {
            Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::CleanupFailed,
            ))
        } else {
            Ok(())
        }
    }

    /// 最终退出门禁同时要求 admission 已关闭且 registry 为空，不能只凭 shutdown 被调用过推断清理完成。
    pub(crate) fn is_shutdown_complete(&self) -> bool {
        self.closed.load(Ordering::Acquire)
            && self
                .staged
                .lock()
                .map(|registry| registry.is_empty())
                .unwrap_or(false)
    }

    /// preflight 同时验证 spelling、节点类型、物理 identity、hardlink 与产品上限。
    fn preflight(
        &self,
        source: PathBuf,
    ) -> Result<AdmittedAttachmentSource, AttachmentIngressError> {
        validate_source_path(&source)?;
        let metadata = require_regular_metadata(&source)?;
        let snapshot = snapshot_path(&source)?;
        self.require_acceptable_snapshot(snapshot)?;
        if metadata.len() != snapshot.size {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::SourceChanged,
            ));
        }
        let display_name = source
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .filter(|name| !name.is_empty())
            .ok_or_else(|| {
                AttachmentIngressError::new(AttachmentIngressErrorCode::UnsupportedPath)
            })?;
        Ok(AdmittedAttachmentSource {
            source,
            display_name,
            size_bytes: snapshot.size,
            snapshot,
        })
    }

    /// 单文件复制绑定实际 handle，在 publish 前后复核 identity/size/mtime，任何变化都回滚自己生成的 staging。
    fn stage_one(
        &self,
        preflight: AdmittedAttachmentSource,
        should_cancel: &dyn Fn() -> bool,
        progress: &mut dyn FnMut(u64, u64),
    ) -> Result<PreparedAttachment, AttachmentIngressError> {
        if should_cancel() {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::Cancelled,
            ));
        }
        validate_source_path(&preflight.source)?;
        let mut source = open_source(&preflight.source)?;
        let opened = snapshot_file(&source)?;
        self.require_acceptable_snapshot(opened)?;
        if opened != preflight.snapshot {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::SourceChanged,
            ));
        }
        let (token, part_path, final_path, mut staging) = self.create_unique_staging()?;
        let mut guard = StagedGuard::new(part_path);
        let mut digest = Sha256::new();
        let mut copied = 0_u64;
        let mut buffer = [0_u8; COPY_BUFFER_BYTES];
        loop {
            if should_cancel() {
                return Err(AttachmentIngressError::new(
                    AttachmentIngressErrorCode::Cancelled,
                ));
            }
            let count = source.read(&mut buffer).map_err(|error| {
                map_io_error(
                    AttachmentIngressErrorCode::SourceReadFailed,
                    "source_read",
                    error,
                )
            })?;
            if count == 0 {
                break;
            }
            copied = copied
                .checked_add(u64::try_from(count).map_err(|_| {
                    AttachmentIngressError::new(AttachmentIngressErrorCode::FileTooLarge)
                })?)
                .ok_or_else(|| {
                    AttachmentIngressError::new(AttachmentIngressErrorCode::FileTooLarge)
                })?;
            if copied > self.limits.max_file_bytes || copied > opened.size {
                return Err(AttachmentIngressError::new(
                    AttachmentIngressErrorCode::FileTooLarge,
                ));
            }
            staging.write_all(&buffer[..count]).map_err(|error| {
                map_io_error(
                    AttachmentIngressErrorCode::StagingFailed,
                    "staging_write",
                    error,
                )
            })?;
            digest.update(&buffer[..count]);
            progress(copied, opened.size);
        }
        if should_cancel() {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::Cancelled,
            ));
        }
        if copied != opened.size || snapshot_file(&source)? != opened {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::SourceChanged,
            ));
        }
        validate_source_path(&preflight.source)?;
        if snapshot_path(&preflight.source)? != opened {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::SourceChanged,
            ));
        }
        staging.flush().map_err(|error| {
            map_io_error(
                AttachmentIngressErrorCode::StagingFailed,
                "staging_flush",
                error,
            )
        })?;
        staging.sync_all().map_err(|error| {
            map_io_error(
                AttachmentIngressErrorCode::StagingFailed,
                "staging_sync",
                error,
            )
        })?;
        drop(staging);
        drop(source);
        rename_no_replace(&guard.path, &final_path)?;
        guard.path = final_path;
        Ok(PreparedAttachment {
            value: IngressAttachment {
                ingress_token: token,
                display_name: preflight.display_name,
                size_bytes: copied,
                sha256: hex_lower(&digest.finalize()),
            },
            guard,
        })
    }

    /// 单项 staging 在 registry 内原子取得 ownership 后才解除 guard，避免取消竞态留下孤儿文件。
    fn register_prepared(
        &self,
        mut prepared: PreparedAttachment,
    ) -> Result<IngressAttachment, AttachmentIngressError> {
        let mut registry = self.staged.lock().map_err(|_| {
            AttachmentIngressError::new(AttachmentIngressErrorCode::StateUnavailable)
        })?;
        if self.closed.load(Ordering::Acquire)
            || registry.contains_key(&prepared.value.ingress_token)
        {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::LifecycleClosed,
            ));
        }
        registry.insert(
            prepared.value.ingress_token.clone(),
            prepared.guard.path.clone(),
        );
        prepared.guard.disarm();
        Ok(prepared.value)
    }

    /// token 与两个目标名都由 Rust 生成；碰撞只重试，绝不接受 caller 指定 staging 文件名。
    fn create_unique_staging(
        &self,
    ) -> Result<(IngressToken, PathBuf, PathBuf, fs::File), AttachmentIngressError> {
        verify_ingress_root(&self.root)?;
        for _ in 0..TOKEN_COLLISION_RETRIES {
            let token = IngressToken::generate();
            let final_path = self.root.join(token.as_str());
            let part_path = self.root.join(format!("{}.part", token.as_str()));
            match create_staging_file(&part_path) {
                Ok(file) => return Ok((token, part_path, final_path, file)),
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => {
                    return Err(map_io_error(
                        AttachmentIngressErrorCode::StagingFailed,
                        "staging_create",
                        error,
                    ));
                }
            }
        }
        Err(AttachmentIngressError::new(
            AttachmentIngressErrorCode::StagingFailed,
        ))
    }

    /// regular/reparse/hardlink/size 规则以句柄事实为准，preflight 与 postflight 共用同一判断。
    fn require_acceptable_snapshot(
        &self,
        snapshot: FileSnapshot,
    ) -> Result<(), AttachmentIngressError> {
        if snapshot.reparse || snapshot.links > 1 {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::LinkNotAllowed,
            ));
        }
        if !snapshot.regular {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::NotRegularFile,
            ));
        }
        if snapshot.size > self.limits.max_file_bytes {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::FileTooLarge,
            ));
        }
        Ok(())
    }

    /// closed 状态是单向栅栏；shutdown 开始后新 batch 即使 dialog 已返回也不能准入。
    fn require_open(&self) -> Result<(), AttachmentIngressError> {
        if self.closed.load(Ordering::Acquire) {
            Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::LifecycleClosed,
            ))
        } else {
            Ok(())
        }
    }

    /// 删除成功或已不存在后才移除 registry；其它 IO failure 保留 token 供 shutdown/retry 清理。
    fn remove_staged(&self, token: &IngressToken) -> Result<(), AttachmentIngressError> {
        let mut registry = self.staged.lock().map_err(|_| {
            AttachmentIngressError::new(AttachmentIngressErrorCode::StateUnavailable)
        })?;
        let path = registry.get(token).ok_or_else(|| {
            AttachmentIngressError::new(AttachmentIngressErrorCode::TokenNotFound)
        })?;
        match fs::remove_file(path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err(map_io_error(
                    AttachmentIngressErrorCode::CleanupFailed,
                    "staging_remove",
                    error,
                ));
            }
        }
        registry.remove(token);
        Ok(())
    }
}

impl Drop for AttachmentIngress {
    /// Drop 只能 best-effort；显式 app shutdown 应先调用 `shutdown` 并处理 cleanup failure。
    fn drop(&mut self) {
        self.closed.store(true, Ordering::Release);
        let registry = match self.staged.get_mut() {
            Ok(registry) => registry,
            // Mutex 在 `&mut self` 下没有并发访问；poison 只表明此前临界区 panic，
            // token/path map 仍由类型保证指向本 owner 的随机节点，可安全用于收尾删除。
            Err(poisoned) => poisoned.into_inner(),
        };
        for path in registry.values() {
            let _ = fs::remove_file(path);
        }
        registry.clear();
    }
}

/// sha2 0.11 的 digest 不依赖格式 trait；显式 lower-hex 保持 Rust 版本间 wire 一致。
fn hex_lower(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut value = String::with_capacity(bytes.len().saturating_mul(2));
    for byte in bytes {
        value.push(HEX[usize::from(byte >> 4)] as char);
        value.push(HEX[usize::from(byte & 0x0f)] as char);
    }
    value
}
