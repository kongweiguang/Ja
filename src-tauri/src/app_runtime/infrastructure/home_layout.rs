// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Rust host 唯一持有的文件系统根目录。

use super::path_policy::is_reparse_point;
use std::env;
use std::fmt;
use std::fs;
use std::path::{Component, Path, PathBuf};

/// 路径和 home 初始化失败只暴露稳定、脱敏的错误，避免任何文件系统路径越过 command 边界或进入日志。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum HomeError {
    InvalidRoot,
    ReparsePoint,
    Io,
    Permission,
}

impl fmt::Display for HomeError {
    /// 把内部路径、serde 和 ACL 细节映射为稳定类别，防止异常凭据文件回显可能含 Secret 的解析文本。
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::InvalidRoot => "Ja home root is invalid",
            Self::ReparsePoint => "Ja home contains a symlink or reparse point",
            Self::Io => "Ja home I/O failed",
            Self::Permission => "Ja home permission denied",
        })
    }
}

impl std::error::Error for HomeError {}

/// 所有 Ja 持久路径都从同一根目录派生，调用方不能分别选择设置和数据库根目录而重新制造分裂存储。
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct HomePaths {
    root: PathBuf,
    data_dir: PathBuf,
    logs_dir: PathBuf,
    java_logs_dir: PathBuf,
    skills_dir: PathBuf,
    run_dir: PathBuf,
    backups_dir: PathBuf,
    cache_dir: PathBuf,
    exports_dir: PathBuf,
}

impl HomePaths {
    /// 一次性构造固定布局名称，避免后续服务拼接用户可控路径片段或另造 Ja 私有目录。
    fn new(root: PathBuf) -> Self {
        Self {
            data_dir: root.join("data"),
            logs_dir: root.join("logs"),
            java_logs_dir: root.join("logs").join("java"),
            skills_dir: root.join("skills"),
            run_dir: root.join("run"),
            backups_dir: root.join("backups"),
            cache_dir: root.join("cache"),
            exports_dir: root.join("exports"),
            root,
        }
    }

    /// 返回供原生 Java/runtime 组合使用的规范 home 根目录。
    pub(crate) fn root(&self) -> &Path {
        &self.root
    }

    /// 返回 Java 持有的持久数据目录；Rust 不读取也不创建数据库文件本身。
    pub(crate) fn data_dir(&self) -> &Path {
        &self.data_dir
    }

    /// 返回桌面 host 持有的日志目录。
    pub(crate) fn logs_dir(&self) -> &Path {
        &self.logs_dir
    }

    /// 返回 Java 持有的滚动日志目录，避免 sidecar 文件与 host 日志混放。
    pub(crate) fn java_logs_dir(&self) -> &Path {
        &self.java_logs_dir
    }

    /// 返回存放锁和进程状态的短生命周期 runtime 目录。
    pub(crate) fn run_dir(&self) -> &Path {
        &self.run_dir
    }
}

/// 唯一负责创建和校验 `~/.ja`，其他组件不得另建同级应用数据树或绕过 reparse-point 检查。
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct HomeLayout {
    paths: HomePaths,
}

impl HomeLayout {
    /// 解析当前用户 profile 并初始化固定 Ja 布局，使测试与生产构造共享同一安全策略。
    pub(crate) fn new() -> Result<Self, HomeError> {
        let profile = env::var_os("USERPROFILE")
            .or_else(|| env::var_os("HOME"))
            .ok_or(HomeError::InvalidRoot)?;
        Self::from_root(PathBuf::from(profile).join(".ja"))
    }

    /// 从显式绝对根目录初始化，供测试及后续 Tauri path 集成使用；拒绝相对路径和 traversal，
    /// 防止路径静默绑定到进程工作目录。
    pub(crate) fn from_root(root: PathBuf) -> Result<Self, HomeError> {
        ensure_directory_tree(&root)?;
        let paths = HomePaths::new(root);
        for directory in [
            &paths.data_dir,
            &paths.logs_dir,
            &paths.java_logs_dir,
            &paths.skills_dir,
            &paths.run_dir,
            &paths.backups_dir,
            &paths.cache_dir,
            &paths.exports_dir,
        ] {
            ensure_directory_tree(directory)?;
        }
        Ok(Self { paths })
    }

    /// 返回配置、认证和数据库 owner 共用的不可变路径集合。
    pub(crate) fn paths(&self) -> &HomePaths {
        &self.paths
    }
}

/// 检查全部既有父级后再创建缺失路径；如果先 canonicalize，会抹掉策略必须拒绝的 symlink/junction 证据。
pub(crate) fn ensure_directory_tree(path: &Path) -> Result<(), HomeError> {
    validate_root_shape(path)?;
    validate_existing_ancestor_chain(path)?;
    let mut missing = Vec::new();
    let mut current = path.to_path_buf();
    loop {
        match fs::symlink_metadata(&current) {
            Ok(metadata) => {
                validate_directory_metadata(&metadata)?;
                break;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                missing.push(current.clone());
                let parent = current.parent().ok_or(HomeError::InvalidRoot)?;
                if parent == current {
                    return Err(HomeError::InvalidRoot);
                }
                current = parent.to_path_buf();
            }
            Err(error) => return Err(map_io_error(error)),
        }
    }
    for directory in missing.iter().rev() {
        match fs::create_dir(directory) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(map_io_error(error)),
        }
        let metadata = fs::symlink_metadata(directory).map_err(map_io_error)?;
        validate_directory_metadata(&metadata)?;
    }
    // 创建后重新检查，避免忽略最近既有目录之上的别名祖先，也防止并发别名替换逃逸校验。
    validate_existing_ancestor_chain(path)
}

/// 遍历每个既有祖先而不是停在最近一级；junction 可包含普通子目录，仅检查子目录会跟随 junction 并漏掉上层 reparse point。
fn validate_existing_ancestor_chain(path: &Path) -> Result<(), HomeError> {
    let mut current = Some(path);
    while let Some(candidate) = current {
        match fs::symlink_metadata(candidate) {
            Ok(metadata) => validate_directory_metadata(&metadata)?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(map_io_error(error)),
        }
        current = candidate.parent();
    }
    Ok(())
}

/// 在任何文件系统调用前拒绝相对路径、`.` 和 `..`，避免跟随用户输入的 traversal 分量。
fn validate_root_shape(path: &Path) -> Result<(), HomeError> {
    if !path.is_absolute() {
        return Err(HomeError::InvalidRoot);
    }
    if path
        .components()
        .any(|component| matches!(component, Component::CurDir | Component::ParentDir))
    {
        return Err(HomeError::InvalidRoot);
    }
    Ok(())
}

/// 不预先解析路径，直接同时检查普通 symlink 与 Windows junction/reparse 元数据。
fn validate_directory_metadata(metadata: &fs::Metadata) -> Result<(), HomeError> {
    if metadata.file_type().is_symlink() || is_reparse_point(metadata) {
        return Err(HomeError::ReparsePoint);
    }
    if !metadata.is_dir() {
        return Err(HomeError::InvalidRoot);
    }
    Ok(())
}

/// 把 OS 错误映射为稳定类别，用户可见错误不保留盘符或文件名。
fn map_io_error(error: std::io::Error) -> HomeError {
    match error.kind() {
        std::io::ErrorKind::PermissionDenied => HomeError::Permission,
        _ => HomeError::Io,
    }
}
