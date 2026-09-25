// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Git 仓库元数据与对象存储安全策略。
//
// 查询 façade 在启动每个 Git 进程前调用本模块；这里集中维护路径 containment、链接、
// alternates、partial clone 与对象扫描预算，避免查询用例各自复制安全判断。

use super::error::GitError;
use crate::workspace::{WorkspaceError, WorkspaceHandle};
use std::fs;
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use std::time::{Duration, Instant};

const MAX_POINTER_BYTES: usize = 4 * 1024;
const MAX_CONFIG_BYTES: usize = 1024 * 1024;
const MAX_CONFIG_LINE_BYTES: usize = 64 * 1024;
const MAX_OBJECT_DIRECTORIES: usize = 8 * 1024;
const MAX_OBJECT_FILES: usize = 200_000;
const MAX_OBJECT_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const MAX_OBJECT_DEPTH: usize = 4;
// 在 Windows 上，工作区守卫会规范化并复核每个对象文件；数千个松散对象在 NTFS 上可能超过两秒，
// 因此扫描必须有界，同时为真实项目保留足够预算。
pub(crate) const MAX_OBJECT_SCAN_TIME: Duration = Duration::from_secs(10);
const MAX_OBJECT_TEXT_BYTES: usize = 1024 * 1024;

/// 即使仓库包含大量松散对象或畸形递归元数据树，也保证对象存储准入有界。
#[derive(Debug, Clone, Copy)]
pub(crate) struct ObjectScanLimits {
    pub(crate) max_directories: usize,
    pub(crate) max_files: usize,
    pub(crate) max_bytes: u64,
    pub(crate) max_depth: usize,
    pub(crate) max_duration: Duration,
}

impl Default for ObjectScanLimits {
    /// 使用覆盖普通 SHA-1/SHA-256 仓库的生产上限，同时保证 Git 启动前校验有限且受 Deadline 约束。
    fn default() -> Self {
        Self {
            max_directories: MAX_OBJECT_DIRECTORIES,
            max_files: MAX_OBJECT_FILES,
            max_bytes: MAX_OBJECT_BYTES,
            max_depth: MAX_OBJECT_DEPTH,
            max_duration: MAX_OBJECT_SCAN_TIME,
        }
    }
}

/// 跟踪对象存储扫描的绝对预算，禁止嵌套目录各自重置上限。
#[derive(Debug)]
struct ObjectScanBudget {
    limits: ObjectScanLimits,
    directories: usize,
    files: usize,
    bytes: u64,
    deadline: Instant,
}

impl ObjectScanBudget {
    /// 为整个对象存储遍历建立单一绝对 Deadline，避免递归层级扩张总时限。
    fn new(limits: ObjectScanLimits) -> Self {
        let now = Instant::now();
        let deadline = now.checked_add(limits.max_duration).unwrap_or(now);
        Self {
            limits,
            directories: 0,
            files: 0,
            bytes: 0,
            deadline,
        }
    }

    /// 遍历超过绝对时间预算时失败关闭，不返回未经完整准入的结果。
    fn check_deadline(&self) -> Result<(), GitError> {
        if Instant::now() >= self.deadline {
            Err(GitError::ExternalWorktree)
        } else {
            Ok(())
        }
    }

    /// 为目录访问计费，并同时执行最大递归深度与目录数量约束。
    fn visit_directory(&mut self, depth: usize) -> Result<(), GitError> {
        self.check_deadline()?;
        if depth > self.limits.max_depth {
            return Err(GitError::ExternalWorktree);
        }
        self.directories = self.directories.saturating_add(1);
        if self.directories > self.limits.max_directories {
            return Err(GitError::ExternalWorktree);
        }
        Ok(())
    }

    /// 仅按文件元数据字节计费，不读取松散对象正文。
    fn visit_file(&mut self, bytes: u64) -> Result<(), GitError> {
        self.check_deadline()?;
        self.files = self.files.saturating_add(1);
        self.bytes = self
            .bytes
            .checked_add(bytes)
            .ok_or(GitError::ExternalWorktree)?;
        if self.files > self.limits.max_files || self.bytes > self.limits.max_bytes {
            return Err(GitError::ExternalWorktree);
        }
        Ok(())
    }
}

/// 在 Git 解析对象前拒绝 `.git` 间接引用、alternates、partial-clone 元数据及配置钩子，
/// 防止访问工作区外对象或启动外部进程。
pub(super) fn validate_worktree(workspace: &WorkspaceHandle) -> Result<(), GitError> {
    workspace
        .resolve_directory("")
        .map_err(|_| GitError::ExternalWorktree)?;
    let git_path = workspace.root_path().join(".git");
    let metadata = match fs::symlink_metadata(&git_path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            // 对于 Bare 仓库，相同对象间接引用会直接放在工作区根下，因此必须在 Git 读取 HEAD 前校验。
            let has_config = match workspace.resolve_file("config") {
                Ok(_) => true,
                Err(WorkspaceError::PathNotFound) => false,
                Err(_) => return Err(GitError::ExternalWorktree),
            };
            if !has_config {
                return Err(GitError::NotRepository);
            }
            match workspace.resolve_file("HEAD") {
                Ok(_) => {}
                Err(WorkspaceError::PathNotFound) => return Err(GitError::NotRepository),
                Err(_) => return Err(GitError::ExternalWorktree),
            }
            match workspace.resolve_directory("objects") {
                Ok(_) => {
                    validate_repo_location(workspace, "", workspace.root_path(), true)?;
                    reject_submodule_metadata(workspace, "")?;
                }
                Err(WorkspaceError::PathNotFound) => return Err(GitError::NotRepository),
                Err(_) => return Err(GitError::ExternalWorktree),
            }
            return Ok(());
        }
        Err(_) => return Err(GitError::ExternalWorktree),
    };
    if metadata.file_type().is_symlink() || crate::workspace::is_reparse_point(&metadata) {
        return Err(GitError::ExternalWorktree);
    }
    if metadata.is_dir() {
        let git_dir = fs::canonicalize(&git_path).map_err(|_| GitError::ExternalWorktree)?;
        ensure_internal_pointer(workspace, &git_path, &git_dir)?;
        validate_repo_location(workspace, ".git", &git_dir, true)?;
        let common_rel = repo_child(".git", "commondir");
        if let Some(pointer) = read_optional_text_file(workspace, &common_rel, MAX_POINTER_BYTES)? {
            let common_dir = resolve_git_pointer(workspace.root_path(), &git_dir, pointer.trim())?;
            let common_rel = relative_path_text(workspace.root_path(), &common_dir)?;
            validate_repo_location(workspace, &common_rel, &common_dir, true)?;
            reject_submodule_metadata(workspace, &common_rel)?;
        }
        reject_submodule_metadata(workspace, ".git")?;
        return Ok(());
    }
    if metadata.is_file() {
        let contents = read_required_text_file(workspace, ".git", MAX_POINTER_BYTES)?;
        let value = contents
            .strip_prefix("gitdir:")
            .ok_or(GitError::ExternalWorktree)?
            .trim();
        let git_dir = resolve_git_pointer(workspace.root_path(), workspace.root_path(), value)?;
        let git_rel = relative_path_text(workspace.root_path(), &git_dir)?;
        validate_repo_location(workspace, &git_rel, &git_dir, false)?;
        let common_rel = repo_child(&git_rel, "commondir");
        if let Some(pointer) = read_optional_text_file(workspace, &common_rel, MAX_POINTER_BYTES)? {
            let common_dir = resolve_git_pointer(workspace.root_path(), &git_dir, pointer.trim())?;
            let common_rel = relative_path_text(workspace.root_path(), &common_dir)?;
            validate_repo_location(workspace, &common_rel, &common_dir, true)?;
            reject_submodule_metadata(workspace, &common_rel)?;
        }
        reject_submodule_metadata(workspace, &git_rel)?;
        return Ok(());
    }
    Err(GitError::ExternalWorktree)
}

/// 校验可信 Git 元数据目录及所有对象间接引用，避免本地读取解析到外部对象存储。
fn validate_repo_location(
    workspace: &WorkspaceHandle,
    base_rel: &str,
    base_abs: &Path,
    objects_required: bool,
) -> Result<(), GitError> {
    if !validate_directory(workspace, base_rel, true)? {
        return Err(GitError::ExternalWorktree);
    }
    for config_name in ["config", "config.worktree"] {
        let config_rel = repo_child(base_rel, config_name);
        if let Some(config) = read_optional_text_file(workspace, &config_rel, MAX_CONFIG_BYTES)? {
            validate_config_text(workspace, base_abs, &config)?;
        }
    }
    let objects_rel = repo_child(base_rel, "objects");
    if !validate_directory(workspace, &objects_rel, objects_required)? {
        return Ok(());
    }
    validate_object_directory(workspace, &objects_rel)?;
    Ok(())
}

/// 通过工作区守卫检查现有目录，使链接、reparse point、硬链接别名与根替换均失败关闭。
fn validate_directory(
    workspace: &WorkspaceHandle,
    relative_path: &str,
    required: bool,
) -> Result<bool, GitError> {
    match workspace.resolve_directory(relative_path) {
        Ok(_) => Ok(true),
        Err(WorkspaceError::PathNotFound) if !required => Ok(false),
        Err(WorkspaceError::PathNotFound) => Err(GitError::ExternalWorktree),
        Err(_) => Err(GitError::ExternalWorktree),
    }
}

/// 在 Git 解析松散或打包对象前校验对象根及所有可读条目，禁止越过已准入目录树。
fn validate_object_directory(
    workspace: &WorkspaceHandle,
    objects_rel: &str,
) -> Result<(), GitError> {
    validate_object_directory_with_limits(workspace, objects_rel, ObjectScanLimits::default())
}

/// 以显式上限执行有界对象遍历，使边界测试无需创建数十万个文件即可验证溢出与 Deadline。
pub(crate) fn validate_object_directory_with_limits(
    workspace: &WorkspaceHandle,
    objects_rel: &str,
    limits: ObjectScanLimits,
) -> Result<(), GitError> {
    let objects = workspace
        .resolve_guard(objects_rel, Some(true))
        .map_err(|_| GitError::ExternalWorktree)?;
    let mut budget = ObjectScanBudget::new(limits);
    budget.visit_directory(0)?;
    workspace
        .verify_resolved(&objects, Some(true))
        .map_err(|_| GitError::ExternalWorktree)?;
    for entry in fs::read_dir(&objects.path).map_err(|_| GitError::ExternalWorktree)? {
        budget.check_deadline()?;
        let (name, path, metadata) = object_entry(entry.map_err(|_| GitError::ExternalWorktree)?)?;
        let child_rel = repo_child(objects_rel, &name);
        if metadata.is_dir() {
            if name == "info" {
                scan_info_directory(workspace, &child_rel, &mut budget, 1)?;
            } else if name == "pack" {
                scan_pack_directory(workspace, &child_rel, &mut budget, 1)?;
            } else if is_fanout_directory_name(&name) {
                scan_fanout_directory(workspace, &objects, &path, &metadata, &mut budget, 1)?;
            } else {
                return Err(GitError::ExternalWorktree);
            }
        } else {
            return Err(GitError::ExternalWorktree);
        }
    }
    workspace
        .verify_resolved(&objects, Some(true))
        .map_err(|_| GitError::ExternalWorktree)
}

/// 读取单个目录项时不跟随链接且不接受有损名称，并保留精确路径供受守卫父目录低成本复核子项。
fn object_entry(entry: fs::DirEntry) -> Result<(String, PathBuf, fs::Metadata), GitError> {
    let name = entry
        .file_name()
        .into_string()
        .map_err(|_| GitError::ExternalWorktree)?;
    let path = entry.path();
    let metadata = fs::symlink_metadata(&path).map_err(|_| GitError::ExternalWorktree)?;
    if metadata.file_type().is_symlink() || crate::workspace::is_reparse_point(&metadata) {
        return Err(GitError::ExternalWorktree);
    }
    Ok((name, path, metadata))
}

/// 在已持有的 objects 根 guard 下校验两位十六进制 fanout，不重复解析共同祖先。
///
/// Git 在写对象时会创建 `tmp_obj_*`，异常退出后也可能留下该类普通文件。Git 不会按对象哈希
/// 读取这些名字。松散对象本身又由哈希寻址，只是数据文件，不具备 Git 配置、对象目录跳转或
/// 进程执行语义；若在 Windows 上逐个 canonicalize/打开数万个对象，会让同一 Turn 的多条 Git
/// 命令反复线性扫描并稳定超时。因此只验证 fanout 是 objects 的直属规范目录且不是链接；
/// 能够改变 Git 读取边界的 config、alternates、pack/info 元数据仍逐文件执行 containment 与
/// hard-link 校验。此取舍让安全判断与 Git 实际的信任/寻址边界一致，而不与仓库对象数量耦合。
fn scan_fanout_directory(
    workspace: &WorkspaceHandle,
    parent: &crate::workspace::ResolvedPath,
    path: &Path,
    observed: &fs::Metadata,
    budget: &mut ObjectScanBudget,
    depth: usize,
) -> Result<(), GitError> {
    budget.visit_directory(depth)?;
    if path.parent() != Some(parent.path.as_path())
        || !observed.is_dir()
        || observed.file_type().is_symlink()
        || crate::workspace::is_reparse_point(observed)
    {
        return Err(GitError::ExternalWorktree);
    }
    let canonical = fs::canonicalize(path).map_err(|_| GitError::ExternalWorktree)?;
    if canonical != path || !crate::workspace::path_is_within(workspace.root_path(), &canonical) {
        return Err(GitError::ExternalWorktree);
    }
    let current = fs::symlink_metadata(&canonical).map_err(|_| GitError::ExternalWorktree)?;
    if !current.is_dir()
        || current.file_type().is_symlink()
        || crate::workspace::is_reparse_point(&current)
    {
        return Err(GitError::ExternalWorktree);
    }
    Ok(())
}

/// 扫描对象 info 目录及 split commit-graph 元数据。
fn scan_info_directory(
    workspace: &WorkspaceHandle,
    relative: &str,
    budget: &mut ObjectScanBudget,
    depth: usize,
) -> Result<(), GitError> {
    let directory = workspace
        .resolve_guard(relative, Some(true))
        .map_err(|_| GitError::ExternalWorktree)?;
    budget.visit_directory(depth)?;
    workspace
        .verify_resolved(&directory, Some(true))
        .map_err(|_| GitError::ExternalWorktree)?;
    for entry in fs::read_dir(&directory.path).map_err(|_| GitError::ExternalWorktree)? {
        budget.check_deadline()?;
        let (name, path, metadata) = object_entry(entry.map_err(|_| GitError::ExternalWorktree)?)?;
        let child = repo_child(relative, &name);
        if metadata.is_dir() && name == "commit-graphs" {
            scan_commit_graph_directory(workspace, &child, budget, depth + 1)?;
        } else if metadata.is_file() && is_info_file_name(&name) {
            scan_object_file(workspace, &directory, &path, &metadata, budget)?;
            if matches!(name.as_str(), "alternates" | "http-alternates") {
                validate_alternates(workspace, relative)?;
            } else if name == "packs" {
                validate_pack_listing(workspace, &child)?;
            }
        } else {
            return Err(GitError::ExternalWorktree);
        }
    }
    workspace
        .verify_resolved(&directory, Some(true))
        .map_err(|_| GitError::ExternalWorktree)
}

/// 扫描 split commit-graph 文件并校验链内本地文件名。
fn scan_commit_graph_directory(
    workspace: &WorkspaceHandle,
    relative: &str,
    budget: &mut ObjectScanBudget,
    depth: usize,
) -> Result<(), GitError> {
    let directory = workspace
        .resolve_guard(relative, Some(true))
        .map_err(|_| GitError::ExternalWorktree)?;
    budget.visit_directory(depth)?;
    workspace
        .verify_resolved(&directory, Some(true))
        .map_err(|_| GitError::ExternalWorktree)?;
    for entry in fs::read_dir(&directory.path).map_err(|_| GitError::ExternalWorktree)? {
        budget.check_deadline()?;
        let (name, path, metadata) = object_entry(entry.map_err(|_| GitError::ExternalWorktree)?)?;
        if !metadata.is_file() || !(name == "commit-graph-chain" || is_graph_file_name(&name)) {
            return Err(GitError::ExternalWorktree);
        }
        let child = repo_child(relative, &name);
        scan_object_file(workspace, &directory, &path, &metadata, budget)?;
        if name == "commit-graph-chain" {
            validate_commit_graph_chain(workspace, &child)?;
        }
    }
    workspace
        .verify_resolved(&directory, Some(true))
        .map_err(|_| GitError::ExternalWorktree)
}

/// 扫描 pack、index 与 multi-pack-index sidecar，但不解析其字节内容。
fn scan_pack_directory(
    workspace: &WorkspaceHandle,
    relative: &str,
    budget: &mut ObjectScanBudget,
    depth: usize,
) -> Result<(), GitError> {
    let directory = workspace
        .resolve_guard(relative, Some(true))
        .map_err(|_| GitError::ExternalWorktree)?;
    budget.visit_directory(depth)?;
    workspace
        .verify_resolved(&directory, Some(true))
        .map_err(|_| GitError::ExternalWorktree)?;
    for entry in fs::read_dir(&directory.path).map_err(|_| GitError::ExternalWorktree)? {
        budget.check_deadline()?;
        let (name, path, metadata) = object_entry(entry.map_err(|_| GitError::ExternalWorktree)?)?;
        if !metadata.is_file() {
            return Err(GitError::ExternalWorktree);
        }
        // Git 的 pack writer 可能在取消或异常退出后留下不可寻址的 tmp_pack/tmp_idx；Git 不会
        // 把这些普通文件作为 pack 元数据读取，因此只计数而不把正常仓库误判为外部工作树。
        if is_git_temporary_pack_file(&name) {
            budget.visit_file(0)?;
            continue;
        }
        if !is_pack_file_name(&name) || is_promisor_file_name(&name) {
            return Err(GitError::ExternalWorktree);
        }
        scan_object_file(workspace, &directory, &path, &metadata, budget)?;
    }
    workspace
        .verify_resolved(&directory, Some(true))
        .map_err(|_| GitError::ExternalWorktree)
}

/// 通过已受守卫的父目录校验枚举出的对象文件，保留身份与硬链接检查，同时避免在 NTFS 上为每个同级项
/// 重新遍历 `.git`；该路径刻意不读取对象正文。
fn scan_object_file(
    workspace: &WorkspaceHandle,
    parent: &crate::workspace::ResolvedPath,
    path: &Path,
    metadata: &fs::Metadata,
    budget: &mut ObjectScanBudget,
) -> Result<(), GitError> {
    workspace
        .verify_enumerated_child_file(parent, path, metadata)
        .map_err(|_| GitError::ExternalWorktree)?;
    budget.visit_file(metadata.len())?;
    Ok(())
}

/// 即使指回工作区内部也拒绝两种 alternates transport；空文件无副作用，任何非空值均不准入。
fn validate_alternates(workspace: &WorkspaceHandle, info_rel: &str) -> Result<(), GitError> {
    for name in ["alternates", "http-alternates"] {
        let relative_path = repo_child(info_rel, name);
        if let Some(value) = read_optional_text_file(workspace, &relative_path, MAX_POINTER_BYTES)?
            && !value.is_empty()
        {
            return Err(GitError::ExternalWorktree);
        }
    }
    Ok(())
}

/// 校验 `info/packs`，不允许内容行命名外部或包含路径语义的 pack 文件。
fn validate_pack_listing(workspace: &WorkspaceHandle, relative: &str) -> Result<(), GitError> {
    let Some(contents) = read_optional_text_file(workspace, relative, MAX_OBJECT_TEXT_BYTES)?
    else {
        return Ok(());
    };
    for line in contents.lines() {
        if line.is_empty() {
            continue;
        }
        let name = line.strip_prefix("P ").ok_or(GitError::ExternalWorktree)?;
        if !is_pack_file_name(name) || is_promisor_file_name(name) {
            return Err(GitError::ExternalWorktree);
        }
    }
    Ok(())
}

/// 在 Git 跟随 split commit-graph chain 名称前校验所有条目。
fn validate_commit_graph_chain(
    workspace: &WorkspaceHandle,
    relative: &str,
) -> Result<(), GitError> {
    let Some(contents) = read_optional_text_file(workspace, relative, MAX_OBJECT_TEXT_BYTES)?
    else {
        return Ok(());
    };
    for line in contents.lines() {
        if !matches!(line.len(), 40 | 64) || !is_lower_hex(line.as_bytes()) {
            return Err(GitError::ExternalWorktree);
        }
    }
    Ok(())
}

/// 仅允许 Git 两位小写十六进制 fanout 目录名。
fn is_fanout_directory_name(name: &str) -> bool {
    name.len() == 2 && is_lower_hex(name.as_bytes())
}

/// 仅允许 Git 可从 `objects/info` 读取的有界对象元数据文件集合。
fn is_info_file_name(name: &str) -> bool {
    matches!(
        name,
        "alternates" | "http-alternates" | "packs" | "commit-graph" | "commit-graph-chain"
    )
}

/// 允许 split commit-graph 文件名，但不接受任何路径语法。
fn is_graph_file_name(name: &str) -> bool {
    let Some(hash) = name
        .strip_prefix("graph-")
        .and_then(|value| value.strip_suffix(".graph"))
    else {
        return false;
    };
    matches!(hash.len(), 40 | 64) && is_lower_hex(hash.as_bytes())
}

/// 仅允许 pack、index、bitmap、reverse-index 与 multi-pack-index sidecar。
fn is_pack_file_name(name: &str) -> bool {
    if name == "multi-pack-index" {
        return true;
    }
    if let Some(hash) = name
        .strip_prefix("multi-pack-index-")
        .and_then(|value| value.strip_suffix(".bitmap"))
    {
        return matches!(hash.len(), 40 | 64) && is_lower_hex(hash.as_bytes());
    }
    let Some(rest) = name.strip_prefix("pack-") else {
        return false;
    };
    let Some((hash, extension)) = rest.rsplit_once('.') else {
        return false;
    };
    matches!(hash.len(), 40 | 64)
        && is_lower_hex(hash.as_bytes())
        && matches!(
            extension,
            "pack" | "idx" | "bitmap" | "rev" | "mtimes" | "keep"
        )
}

/// 在通用 pack 语法接受扩展名前先识别 lazy-fetch 标记，避免未来样式绕过拒绝策略。
fn is_promisor_file_name(name: &str) -> bool {
    name.strip_prefix("pack-")
        .and_then(|value| value.rsplit_once('.'))
        .is_some_and(|(_, extension)| extension == "promisor")
}

/// 只接受 Git 自身 mkstemp 生成的六位 ASCII 临时 pack/index 名称，不把任意未知 pack 文件放行。
fn is_git_temporary_pack_file(name: &str) -> bool {
    let Some(suffix) = name
        .strip_prefix("tmp_pack_")
        .or_else(|| name.strip_prefix("tmp_idx_"))
    else {
        return false;
    };
    suffix.len() == 6 && suffix.bytes().all(|byte| byte.is_ascii_alphanumeric())
}

/// 仅接受 ASCII 小写十六进制，避免大小写或 Unicode 别名。
fn is_lower_hex(bytes: &[u8]) -> bool {
    !bytes.is_empty()
        && bytes
            .iter()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(byte))
}

/// 当前只读边界拒绝 submodule 元数据，避免 Git 解析到具有独立信任根的另一仓库。
fn reject_submodule_metadata(workspace: &WorkspaceHandle, base_rel: &str) -> Result<(), GitError> {
    let modules_rel = repo_child(base_rel, "modules");
    if validate_directory(workspace, &modules_rel, false)? {
        return Err(GitError::ExternalWorktree);
    }
    if read_optional_text_file(workspace, ".gitmodules", MAX_CONFIG_BYTES)?.is_some() {
        return Err(GitError::ExternalWorktree);
    }
    Ok(())
}

/// 只解析本地配置文本，并在打开引用路径前拒绝 include 及进程或网络间接引用。
fn validate_config_text(
    workspace: &WorkspaceHandle,
    base_abs: &Path,
    config: &str,
) -> Result<(), GitError> {
    let mut section = String::new();
    for raw_line in config.split('\n') {
        let line = raw_line.strip_suffix('\r').unwrap_or(raw_line);
        if line.len() > MAX_CONFIG_LINE_BYTES {
            return Err(GitError::ExternalWorktree);
        }
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
            continue;
        }
        if trimmed.starts_with('[') {
            if !trimmed.ends_with(']') {
                return Err(GitError::ExternalWorktree);
            }
            let inside = trimmed[1..trimmed.len() - 1].trim();
            section = inside
                .split_whitespace()
                .next()
                .filter(|value| !value.is_empty())
                .map(|value| value.trim_matches('"').to_ascii_lowercase())
                .ok_or(GitError::ExternalWorktree)?;
            continue;
        }
        let (key, value) = trimmed
            .split_once('=')
            .map(|(key, value)| (key.trim(), value.trim()))
            .or_else(|| {
                trimmed
                    .split_once(char::is_whitespace)
                    .map(|(key, value)| (key.trim(), value.trim()))
            })
            .ok_or(GitError::ExternalWorktree)?;
        if key.is_empty() || key.chars().any(char::is_whitespace) {
            return Err(GitError::ExternalWorktree);
        }
        let key = key.to_ascii_lowercase();
        let value = value.trim_matches('"').trim_matches('\'');
        if section.starts_with("include") && key == "path" {
            return Err(GitError::ExternalWorktree);
        }
        // worktreeConfig 只会让 Git 读取同一 Git 目录内的 config.worktree；上层已经用同一
        // containment 与危险键策略校验该文件。继续拒绝 partial clone，避免对象缺失时
        // 触发 promisor remote 或进入工作区外对象来源。
        if section == "extensions" && matches!(key.as_str(), "partialclone" | "partialclonefilter")
        {
            return Err(GitError::ExternalWorktree);
        }
        if section == "remote" && key == "promisor" {
            return Err(GitError::ExternalWorktree);
        }
        if section == "core" {
            match key.as_str() {
                "worktree" => validate_config_worktree(workspace, base_abs, value)?,
                "fsmonitor" | "sshcommand" | "hookspath" => return Err(GitError::ExternalWorktree),
                _ => {}
            }
        }
        if (section == "filter" && matches!(key.as_str(), "process" | "clean" | "smudge"))
            || (section == "diff" && matches!(key.as_str(), "external" | "textconv"))
            || (section == "credential" && key == "helper")
            || section == "submodule"
            || (section == "url" && matches!(key.as_str(), "insteadof" | "pushinsteadof"))
        {
            return Err(GitError::ExternalWorktree);
        }
    }
    Ok(())
}

/// 仅允许内部 `core.worktree` 目录，判断配置安全性时不打开外部路径。
fn validate_config_worktree(
    workspace: &WorkspaceHandle,
    base_abs: &Path,
    value: &str,
) -> Result<(), GitError> {
    let target = resolve_internal_pointer(workspace.root_path(), base_abs, value)?;
    let relative = relative_path_text(workspace.root_path(), &target)?;
    workspace
        .resolve_directory(&relative)
        .map_err(|_| GitError::ExternalWorktree)?;
    Ok(())
}

/// 在组件守卫覆盖整个读取期间读取可信有界 UTF-8 文件，防止 alternates/config 交换绕过校验。
fn read_optional_text_file(
    workspace: &WorkspaceHandle,
    relative_path: &str,
    max_bytes: usize,
) -> Result<Option<String>, GitError> {
    let resolved = match workspace.resolve_guard(relative_path, Some(false)) {
        Ok(resolved) => resolved,
        Err(WorkspaceError::PathNotFound) => return Ok(None),
        Err(_) => return Err(GitError::ExternalWorktree),
    };
    let file = fs::File::open(&resolved.path).map_err(|_| GitError::ExternalWorktree)?;
    let mut bytes = Vec::new();
    file.take(max_bytes.saturating_add(1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|_| GitError::ExternalWorktree)?;
    workspace
        .verify_resolved(&resolved, Some(false))
        .map_err(|_| GitError::ExternalWorktree)?;
    if bytes.len() > max_bytes || bytes.contains(&0) {
        return Err(GitError::ExternalWorktree);
    }
    let text = String::from_utf8(bytes).map_err(|_| GitError::ExternalWorktree)?;
    if text.lines().any(|line| line.len() > MAX_CONFIG_LINE_BYTES) {
        return Err(GitError::ExternalWorktree);
    }
    Ok(Some(text))
}

/// 将必需元数据文件的读取失败统一映射为稳定 worktree 错误。
fn read_required_text_file(
    workspace: &WorkspaceHandle,
    relative_path: &str,
    max_bytes: usize,
) -> Result<String, GitError> {
    read_optional_text_file(workspace, relative_path, max_bytes)?.ok_or(GitError::ExternalWorktree)
}

/// 构造斜杠分隔的根相对元数据路径，避免未来 IPC 契约暴露绝对路径。
fn repo_child(parent: &str, child: &str) -> String {
    if parent.is_empty() {
        child.to_owned()
    } else {
        format!("{parent}/{child}")
    }
}

/// 拒绝原始写法已包含链接的内部指针，再确认其规范目标仍位于已准入根目录下。
fn resolve_git_pointer(root: &Path, base: &Path, value: &str) -> Result<PathBuf, GitError> {
    resolve_internal_pointer(root, base, value)
}

/// 仅在词法 containment 检查后解析配置或指针路径，使外部值在任何外部文件系统访问前被拒绝。
fn resolve_internal_pointer(root: &Path, base: &Path, value: &str) -> Result<PathBuf, GitError> {
    if value.is_empty() || value.len() > MAX_POINTER_BYTES || value.contains('\0') {
        return Err(GitError::ExternalWorktree);
    }
    let path = Path::new(value);
    let candidate = if path.is_absolute() {
        path.to_path_buf()
    } else {
        base.join(path)
    };
    if !lexical_path_is_within(root, &candidate) {
        return Err(GitError::ExternalWorktree);
    }
    crate::workspace::reject_link_components(&candidate).map_err(|_| GitError::ExternalWorktree)?;
    let canonical = fs::canonicalize(candidate).map_err(|_| GitError::ExternalWorktree)?;
    if !crate::workspace::path_is_within(root, &canonical) {
        return Err(GitError::ExternalWorktree);
    }
    Ok(canonical)
}

/// 仅规范化路径语法而不解析文件系统链接，用于配置及 worktree 指针值的 I/O 前逃逸检查。
fn lexical_path_is_within(root: &Path, candidate: &Path) -> bool {
    let mut normalized = PathBuf::new();
    for component in candidate.components() {
        match component {
            Component::Prefix(_) | Component::RootDir => normalized.push(component.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => {
                normalized.pop();
            }
            Component::Normal(name) => normalized.push(name),
        }
    }
    crate::workspace::path_is_within(root, &normalized)
}

/// 从已规范化路径推导严格 UTF-8 相对写法，拒绝无法无损表示的组件。
fn relative_path_text(root: &Path, path: &Path) -> Result<String, GitError> {
    if !crate::workspace::path_is_within(root, path) {
        return Err(GitError::ExternalWorktree);
    }
    let root_count = root.components().count();
    let mut parts = Vec::new();
    for component in path.components().skip(root_count) {
        let Component::Normal(name) = component else {
            return Err(GitError::ExternalWorktree);
        };
        parts.push(name.to_str().ok_or(GitError::ExternalWorktree)?);
    }
    Ok(parts.join("/"))
}

/// 在接受任何指针派生元数据路径前，确认原始与规范 `.git` 目录身份一致且受限于工作区。
fn ensure_internal_pointer(
    workspace: &WorkspaceHandle,
    raw: &Path,
    canonical: &Path,
) -> Result<(), GitError> {
    crate::workspace::reject_link_components(raw).map_err(|_| GitError::ExternalWorktree)?;
    if !crate::workspace::path_is_within(workspace.root_path(), canonical) {
        return Err(GitError::ExternalWorktree);
    }
    Ok(())
}
