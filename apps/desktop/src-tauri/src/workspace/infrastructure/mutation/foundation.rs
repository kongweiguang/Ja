// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Workspace 与 Review 共享的进程内文件变更基础设施。

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

/// 文件变更基础设施只暴露调用方可稳定映射的失败分类，不携带路径或平台错误正文。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MutationInfrastructureError {
    OutsideWorkspace,
    Busy,
    Io,
    AtomicUnsupported,
}

/// 每个 canonical path 独立串行化；不同文件仍可并发，避免把整个 workspace 变成全局锁。
#[derive(Clone, Default)]
pub(crate) struct PathMutationQueue {
    locks: Arc<Mutex<HashMap<PathBuf, Arc<Mutex<()>>>>>,
}

static GLOBAL_QUEUE: OnceLock<PathMutationQueue> = OnceLock::new();

impl PathMutationQueue {
    /// 返回所有原生写入口共享的进程级队列，关闭 service 实例之间的 CAS 竞态窗口。
    pub(crate) fn global() -> Self {
        GLOBAL_QUEUE.get_or_init(Self::default).clone()
    }

    /// 按确定顺序非阻塞获取去重路径锁；容量竞争有界失败，避免桌面线程无限等待。
    pub(crate) fn with_paths<T, E, F>(&self, paths: &[PathBuf], operation: F) -> Result<T, E>
    where
        E: From<MutationInfrastructureError>,
        F: FnOnce() -> Result<T, E>,
    {
        let mut keys = paths.to_vec();
        keys.sort();
        keys.dedup();
        let locks = {
            let mut registry = self
                .locks
                .lock()
                .map_err(|_| MutationInfrastructureError::Busy)
                .map_err(E::from)?;
            keys.iter()
                .map(|key| {
                    registry
                        .entry(key.clone())
                        .or_insert_with(|| Arc::new(Mutex::new(())))
                        .clone()
                })
                .collect::<Vec<_>>()
        };
        let mut guards = Vec::with_capacity(locks.len());
        for lock in &locks {
            guards.push(
                lock.try_lock()
                    .map_err(|_| MutationInfrastructureError::Busy)
                    .map_err(E::from)?,
            );
        }
        let result = operation();
        drop(guards);
        result
    }
}

/// 解析已准入 workspace 下的相对路径，并拒绝 traversal、绝对注入与符号链接越界。
pub(crate) fn resolve_relative(
    root: &Path,
    relative: &str,
) -> Result<PathBuf, MutationInfrastructureError> {
    if relative.is_empty()
        || relative.len() > 4_096
        || relative.starts_with(['/', '\\'])
        || relative.contains(':')
    {
        return Err(MutationInfrastructureError::OutsideWorkspace);
    }
    let path = Path::new(relative);
    if path.components().any(|component| {
        matches!(
            component,
            std::path::Component::Prefix(_)
                | std::path::Component::RootDir
                | std::path::Component::ParentDir
                | std::path::Component::CurDir
        )
    }) {
        return Err(MutationInfrastructureError::OutsideWorkspace);
    }
    let candidate = root.join(path);
    let mut current = root.to_path_buf();
    for component in path.components() {
        current.push(component.as_os_str());
        if let Ok(metadata) = fs::symlink_metadata(&current)
            && metadata.file_type().is_symlink()
        {
            return Err(MutationInfrastructureError::OutsideWorkspace);
        }
    }
    let resolved = if candidate.exists() {
        fs::canonicalize(&candidate).map_err(|_| MutationInfrastructureError::Io)?
    } else {
        let parent = candidate
            .parent()
            .ok_or(MutationInfrastructureError::OutsideWorkspace)?;
        let parent =
            fs::canonicalize(parent).map_err(|_| MutationInfrastructureError::OutsideWorkspace)?;
        parent.join(
            candidate
                .file_name()
                .ok_or(MutationInfrastructureError::OutsideWorkspace)?,
        )
    };
    if !resolved.starts_with(root) {
        return Err(MutationInfrastructureError::OutsideWorkspace);
    }
    Ok(resolved)
}

/// 发布同目录且已同步的临时文件；平台不能保证替换原子性时失败关闭，不降级为覆盖写。
pub(crate) fn replace_atomically(
    temporary: &Path,
    target: &Path,
) -> Result<(), MutationInfrastructureError> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;

        let source: Vec<u16> = temporary.as_os_str().encode_wide().chain([0]).collect();
        let destination: Vec<u16> = target.as_os_str().encode_wide().chain([0]).collect();
        const REPLACE_EXISTING: u32 = 0x1;
        const WRITE_THROUGH: u32 = 0x8;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn MoveFileExW(source: *const u16, destination: *const u16, flags: u32) -> i32;
        }
        // Windows 只有 MoveFileExW 能在保留同目录原子替换与 write-through 约束时覆盖目标。
        let result = unsafe {
            MoveFileExW(
                source.as_ptr(),
                destination.as_ptr(),
                REPLACE_EXISTING | WRITE_THROUGH,
            )
        };
        if result == 0 {
            return Err(MutationInfrastructureError::AtomicUnsupported);
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        fs::rename(temporary, target).map_err(|_| MutationInfrastructureError::AtomicUnsupported)
    }
}
