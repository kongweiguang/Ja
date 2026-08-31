// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::error::{AttachmentIngressError, AttachmentIngressErrorCode, map_io_error};
use super::model::{IngressAttachment, IngressLimits, IngressToken};
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

/// 外置测试使用的不含路径 checkpoint；生产默认 observer 为空，且不会扩大日志/IPC 表面。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum IngressCheckpoint {
    PreflightComplete { index: usize },
    SourceOpened { index: usize },
    CopyComplete { index: usize },
}

#[derive(Debug)]
struct PreflightFile {
    source: PathBuf,
    display_name: String,
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
            closed: AtomicBool::new(false),
        })
    }

    /// dialog 取消映射为空成功；非空 batch 才执行数量、总量与安全复制。
    pub(crate) fn stage_paths(
        &self,
        paths: Vec<PathBuf>,
    ) -> Result<Vec<IngressAttachment>, AttachmentIngressError> {
        self.stage_paths_with_observer(paths, |_| {})
    }

    /// observer 只暴露 index/checkpoint，用于确定性制造 TOCTOU；绝对路径仍留在测试 closure 自身。
    pub(crate) fn stage_paths_with_observer<F>(
        &self,
        paths: Vec<PathBuf>,
        mut observer: F,
    ) -> Result<Vec<IngressAttachment>, AttachmentIngressError>
    where
        F: FnMut(IngressCheckpoint),
    {
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
        let mut preflight = Vec::with_capacity(paths.len());
        for (index, source) in paths.into_iter().enumerate() {
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
            preflight.push(current);
            observer(IngressCheckpoint::PreflightComplete { index });
        }

        let mut prepared = Vec::with_capacity(preflight.len());
        for (index, source) in preflight.into_iter().enumerate() {
            prepared.push(self.stage_one(source, index, &mut observer)?);
        }
        let mut registry = self.staged.lock().map_err(|_| {
            AttachmentIngressError::new(AttachmentIngressErrorCode::StateUnavailable)
        })?;
        if self.closed.load(Ordering::Acquire) {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::LifecycleClosed,
            ));
        }
        for attachment in &prepared {
            if registry.contains_key(&attachment.value.ingress_token) {
                return Err(AttachmentIngressError::new(
                    AttachmentIngressErrorCode::StagingFailed,
                ));
            }
        }
        for attachment in &prepared {
            registry.insert(
                attachment.value.ingress_token.clone(),
                attachment.guard.path.clone(),
            );
        }
        let mut result = Vec::with_capacity(prepared.len());
        for mut attachment in prepared {
            attachment.guard.disarm();
            result.push(attachment.value);
        }
        Ok(result)
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
    fn preflight(&self, source: PathBuf) -> Result<PreflightFile, AttachmentIngressError> {
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
        Ok(PreflightFile {
            source,
            display_name,
            snapshot,
        })
    }

    /// 单文件复制绑定实际 handle，在 publish 前后复核 identity/size/mtime，任何变化都回滚自己生成的 staging。
    fn stage_one<F>(
        &self,
        preflight: PreflightFile,
        index: usize,
        observer: &mut F,
    ) -> Result<PreparedAttachment, AttachmentIngressError>
    where
        F: FnMut(IngressCheckpoint),
    {
        validate_source_path(&preflight.source)?;
        let mut source = open_source(&preflight.source)?;
        let opened = snapshot_file(&source)?;
        self.require_acceptable_snapshot(opened)?;
        if opened != preflight.snapshot {
            return Err(AttachmentIngressError::new(
                AttachmentIngressErrorCode::SourceChanged,
            ));
        }
        observer(IngressCheckpoint::SourceOpened { index });

        let (token, part_path, final_path, mut staging) = self.create_unique_staging()?;
        let mut guard = StagedGuard::new(part_path);
        let mut digest = Sha256::new();
        let mut copied = 0_u64;
        let mut buffer = [0_u8; COPY_BUFFER_BYTES];
        loop {
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
        }
        observer(IngressCheckpoint::CopyComplete { index });
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
