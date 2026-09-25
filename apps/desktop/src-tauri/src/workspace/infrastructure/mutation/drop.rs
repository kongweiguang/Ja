// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// 对原生拖入的每棵 source tree 做一次有界完整预检后才签发 30 秒单次 token；
/// token 只保留顶层物理身份与资源计数，导入时仍会重新扫描，避免预检变成长期授权。
pub fn issue_native_drop<I>(paths: I) -> Result<String, WorkspaceError>
where
    I: IntoIterator<Item = PathBuf>,
{
    let mut admitted = Vec::new();
    let mut source_path_bytes = 0usize;
    for path in paths {
        if admitted.len() >= MAX_DROP_ITEMS || !path.is_absolute() {
            return Err(WorkspaceError::DropTokenInvalid);
        }
        reject_link_components(&path).map_err(|_| WorkspaceError::DropTokenInvalid)?;
        let metadata =
            fs::symlink_metadata(&path).map_err(|error| WorkspaceError::io("drop_stat", error))?;
        if is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
            return Err(WorkspaceError::DropTokenInvalid);
        }
        if !metadata.is_file() && !metadata.is_dir() {
            return Err(WorkspaceError::DropTokenInvalid);
        }
        if metadata.is_file() && drop_hard_link_count(&path, &metadata)? > 1 {
            return Err(WorkspaceError::DropTokenInvalid);
        }
        let identity = move_identity(&path).map_err(|_| WorkspaceError::DropTokenInvalid)?;
        let canonical =
            fs::canonicalize(&path).map_err(|error| WorkspaceError::io("drop_resolve", error))?;
        reject_link_components(&canonical).map_err(|_| WorkspaceError::DropTokenInvalid)?;
        if move_identity(&canonical).map_err(|_| WorkspaceError::DropTokenInvalid)? != identity {
            return Err(WorkspaceError::DropTokenInvalid);
        }
        source_path_bytes = source_path_bytes
            .checked_add(canonical.as_os_str().as_encoded_bytes().len())
            .ok_or(WorkspaceError::EntryBudgetExceeded)?;
        if source_path_bytes > MAX_DROP_PATH_BYTES {
            return Err(WorkspaceError::EntryBudgetExceeded);
        }
        admitted.push(NativeDropSource {
            path: canonical,
            identity,
        });
    }
    if admitted.is_empty() {
        return Err(WorkspaceError::DropTokenInvalid);
    }
    let (preflight, _) = scan_drop_sources(&admitted)?;
    let now = Instant::now();
    let mut plans = drop_plans().lock().map_err(|_| WorkspaceError::Io {
        operation: "drop_plan",
        kind: std::io::ErrorKind::Other.into(),
    })?;
    insert_drop_plan_bounded(
        &mut plans,
        NativeDropPlan {
            sources: admitted,
            entry_count: preflight.entry_count,
            source_path_bytes,
            tree_path_bytes: preflight.path_bytes,
            expires: now + DROP_TTL,
        },
        now,
        RETAINED_DROP_PLAN_BUDGET,
    )
}

/// 在同一锁内原子消费未过期 Drop capability，防止 Files 与 Terminal 同时观察 source path。
pub(super) fn consume_native_drop_plan(token: &str) -> Result<NativeDropPlan, WorkspaceError> {
    if token.is_empty() || token.len() > MAX_DROP_TOKEN_BYTES {
        return Err(WorkspaceError::DropTokenInvalid);
    }
    let mut plans = drop_plans().lock().map_err(|_| WorkspaceError::Io {
        operation: "drop_plan",
        kind: std::io::ErrorKind::Other.into(),
    })?;
    let Some(plan) = plans.remove(token) else {
        return Err(WorkspaceError::DropTokenInvalid);
    };
    if plan.expires <= Instant::now() {
        return Err(WorkspaceError::DropTokenInvalid);
    }
    Ok(plan)
}

/// 在取得导入路径锁前只读取 capability 的不可变快照；真正消费仍延迟到锁内，
/// 这样 busy、workspace 未就绪或取消都不会提前丢失可重试的 drop token。
pub(crate) fn peek_native_drop_plan(token: &str) -> Result<NativeDropPlan, WorkspaceError> {
    if token.is_empty() || token.len() > MAX_DROP_TOKEN_BYTES {
        return Err(WorkspaceError::DropTokenInvalid);
    }
    let plans = drop_plans().lock().map_err(|_| WorkspaceError::Io {
        operation: "drop_plan",
        kind: std::io::ErrorKind::Other.into(),
    })?;
    let plan = plans
        .get(token)
        .cloned()
        .ok_or(WorkspaceError::DropTokenInvalid)?;
    if plan.expires <= Instant::now() {
        return Err(WorkspaceError::DropTokenInvalid);
    }
    Ok(plan)
}

/// Terminal 与 Files 共用同一单次 capability；即使只需要 shell quoting，消费时也
/// 必须核对签发身份，不能把后来替换的路径交给其它 native 能力。
pub(crate) fn consume_native_drop(token: &str) -> Result<Vec<PathBuf>, WorkspaceError> {
    let plan = consume_native_drop_plan(token)?;
    for source in &plan.sources {
        reject_link_components(&source.path).map_err(|_| WorkspaceError::DropTokenInvalid)?;
        let metadata =
            fs::symlink_metadata(&source.path).map_err(|_| WorkspaceError::DropTokenInvalid)?;
        if is_reparse_point(&metadata)
            || metadata.file_type().is_symlink()
            || (!metadata.is_file() && !metadata.is_dir())
            || (metadata.is_file() && drop_hard_link_count(&source.path, &metadata)? > 1)
        {
            return Err(WorkspaceError::DropTokenInvalid);
        }
        if move_identity(&source.path).map_err(|_| WorkspaceError::DropTokenInvalid)?
            != source.identity
        {
            return Err(WorkspaceError::DropTokenInvalid);
        }
    }
    Ok(plan.sources.into_iter().map(|source| source.path).collect())
}

/// 返回可比较的修改时间；缺失时间仍作为 `None` 参与 before/after 等值判断。
pub(super) fn drop_modified_unix_millis(metadata: &fs::Metadata) -> Option<u128> {
    metadata
        .modified()
        .ok()
        .and_then(|modified| modified.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis())
}

/// 通过已打开句柄流式 hash 一个源文件，并在读前、读后同时验证路径与句柄身份。
/// 这既允许大于编辑器 4MiB 阈值的二进制文件，也不会一次性把内容放进内存。
pub(super) fn hash_drop_file(
    path: &Path,
    expected_identity: MoveIdentity,
    expected_size: u64,
    expected_modified: Option<u128>,
    deadline: Instant,
) -> Result<String, WorkspaceError> {
    reject_link_components(path).map_err(|_| WorkspaceError::DropTokenInvalid)?;
    let path_metadata =
        fs::symlink_metadata(path).map_err(|error| WorkspaceError::io("drop_stat", error))?;
    if !path_metadata.is_file()
        || is_reparse_point(&path_metadata)
        || path_metadata.file_type().is_symlink()
        || drop_hard_link_count(path, &path_metadata)? > 1
        || move_identity(path)? != expected_identity
    {
        return Err(WorkspaceError::PathChanged);
    }
    let mut file = fs::File::open(path).map_err(|error| WorkspaceError::io("drop_open", error))?;
    if open_file_identity(&file)? != expected_identity {
        return Err(WorkspaceError::PathChanged);
    }
    let before = file
        .metadata()
        .map_err(|error| WorkspaceError::io("drop_stat", error))?;
    if before.len() != expected_size || drop_modified_unix_millis(&before) != expected_modified {
        return Err(WorkspaceError::PathChanged);
    }
    let mut digest = Sha256::new();
    let mut copied = 0u64;
    let mut buffer = [0u8; 64 * 1024];
    loop {
        if Instant::now() >= deadline {
            return Err(WorkspaceError::ScanDeadlineExceeded);
        }
        let count = file
            .read(&mut buffer)
            .map_err(|error| WorkspaceError::io("drop_read", error))?;
        if count == 0 {
            break;
        }
        copied = copied
            .checked_add(u64::try_from(count).map_err(|_| WorkspaceError::FileTooLarge)?)
            .ok_or(WorkspaceError::FileTooLarge)?;
        if copied > expected_size {
            return Err(WorkspaceError::PathChanged);
        }
        digest.update(&buffer[..count]);
    }
    let after = file
        .metadata()
        .map_err(|error| WorkspaceError::io("drop_recheck", error))?;
    reject_link_components(path).map_err(|_| WorkspaceError::PathChanged)?;
    let path_after =
        fs::symlink_metadata(path).map_err(|error| WorkspaceError::io("drop_recheck", error))?;
    if copied != expected_size
        || after.len() != expected_size
        || drop_modified_unix_millis(&after) != expected_modified
        || !path_after.is_file()
        || is_reparse_point(&path_after)
        || path_after.file_type().is_symlink()
        || drop_hard_link_count(path, &path_after)? > 1
        || open_file_identity(&file)? != expected_identity
        || move_identity(path)? != expected_identity
    {
        return Err(WorkspaceError::PathChanged);
    }
    Ok(hex_lower(&digest.finalize()))
}

/// 完整 source manifest 的有界累加器；所有配额在创建 staging 目录前生效。
pub(super) struct DropManifestBuilder {
    entries: Vec<DropManifestEntry>,
    entry_count: usize,
    total_bytes: u64,
    path_bytes: usize,
    deadline: Instant,
}

/// 递归预检一棵原生源树。每层都重查所有祖先 link/reparse、物理身份、孩子集合
/// 和内容 hash，防止根通过后再把中间目录换成 junction 越界读取。
pub(super) fn scan_drop_source_node(
    source: &Path,
    relative_path: &str,
    expected_root_identity: Option<MoveIdentity>,
    depth: usize,
    builder: &mut DropManifestBuilder,
) -> Result<(), WorkspaceError> {
    if Instant::now() >= builder.deadline {
        return Err(WorkspaceError::ScanDeadlineExceeded);
    }
    if depth > MAX_DROP_DEPTH {
        return Err(WorkspaceError::DepthLimitExceeded);
    }
    validate_relative_path(relative_path)?;
    builder.entry_count = builder.entry_count.saturating_add(1);
    if builder.entry_count > MAX_DROP_ENTRIES {
        return Err(WorkspaceError::EntryBudgetExceeded);
    }
    builder.path_bytes = builder
        .path_bytes
        .checked_add(relative_path.len())
        .ok_or(WorkspaceError::EntryBudgetExceeded)?;
    if builder.path_bytes > MAX_DROP_PATH_BYTES {
        return Err(WorkspaceError::EntryBudgetExceeded);
    }
    reject_link_components(source).map_err(|_| WorkspaceError::DropTokenInvalid)?;
    let metadata =
        fs::symlink_metadata(source).map_err(|error| WorkspaceError::io("drop_stat", error))?;
    if is_reparse_point(&metadata) || metadata.file_type().is_symlink() {
        return Err(WorkspaceError::DropTokenInvalid);
    }
    if metadata.is_file() && drop_hard_link_count(source, &metadata)? > 1 {
        return Err(WorkspaceError::DropTokenInvalid);
    }
    let identity = move_identity(source)?;
    if expected_root_identity.is_some_and(|expected| expected != identity) {
        return Err(WorkspaceError::DropTokenInvalid);
    }
    let modified_unix_millis = drop_modified_unix_millis(&metadata);
    let kind = if metadata.is_dir() {
        EntryKind::Directory
    } else if metadata.is_file() {
        EntryKind::File
    } else {
        return Err(WorkspaceError::DropTokenInvalid);
    };
    let children = if kind == EntryKind::Directory {
        Some(sorted_trash_children(source)?)
    } else {
        None
    };
    let sha256 = if kind == EntryKind::File {
        builder.total_bytes = builder
            .total_bytes
            .checked_add(metadata.len())
            .ok_or(WorkspaceError::FileTooLarge)?;
        if builder.total_bytes > MAX_DROP_BYTES {
            return Err(WorkspaceError::FileTooLarge);
        }
        Some(hash_drop_file(
            source,
            identity,
            metadata.len(),
            modified_unix_millis,
            builder.deadline,
        )?)
    } else {
        None
    };
    builder.entries.push(DropManifestEntry {
        source: source.to_path_buf(),
        relative_path: relative_path.to_owned(),
        kind,
        identity,
        size: metadata.len(),
        modified_unix_millis,
        sha256,
        children: children.clone(),
    });
    if let Some(children) = children {
        for name in &children {
            let child_relative = join_trash_relative(relative_path, name)?;
            scan_drop_source_node(
                &source.join(name),
                &child_relative,
                None,
                depth.saturating_add(1),
                builder,
            )?;
        }
        reject_link_components(source).map_err(|_| WorkspaceError::PathChanged)?;
        if sorted_trash_children(source)? != children || move_identity(source)? != identity {
            return Err(WorkspaceError::PathChanged);
        }
    }
    Ok(())
}

/// 在一个共享 deadline 与累计预算下扫描全部顶层 source，并在签发 token 前拒绝
/// 同名目标；返回的完整 entries 可在导入阶段直接组成 manifest。
pub(super) fn scan_drop_sources(
    sources: &[NativeDropSource],
) -> Result<(DropManifestBuilder, Vec<String>), WorkspaceError> {
    let mut builder = DropManifestBuilder {
        entries: Vec::new(),
        entry_count: 0,
        total_bytes: 0,
        path_bytes: 0,
        deadline: Instant::now() + DROP_SCAN_DEADLINE,
    };
    let mut top_level_names = HashSet::new();
    let mut top_level_relative_paths = Vec::new();
    for source in sources {
        let name = source
            .path
            .file_name()
            .and_then(|value| value.to_str())
            .ok_or(WorkspaceError::DropTokenInvalid)?;
        let relative = join_trash_relative("", std::ffi::OsStr::new(name))?;
        validate_relative_path(&relative)?;
        if !top_level_names.insert(relative.clone()) {
            return Err(WorkspaceError::AlreadyExists);
        }
        scan_drop_source_node(
            &source.path,
            &relative,
            Some(source.identity),
            0,
            &mut builder,
        )?;
        top_level_relative_paths.push(relative);
    }
    Ok((builder, top_level_relative_paths))
}

/// 将已消费 token 重新扫描成完整 manifest；签发时的计数与第二次扫描必须一致，
/// 否则视为 source tree 已改变，不能依赖 30 秒前的预检继续 staging。
pub(super) fn build_drop_manifest(
    plan: &NativeDropPlan,
    destination_relative: &str,
) -> Result<DropManifest, WorkspaceError> {
    let (builder, top_level_relative_paths) = scan_drop_sources(&plan.sources)?;
    if builder.entry_count != plan.entry_count || builder.path_bytes != plan.tree_path_bytes {
        return Err(WorkspaceError::PathChanged);
    }
    let imported_relative_paths = builder
        .entries
        .iter()
        .map(|entry| {
            let relative = if destination_relative.is_empty() {
                entry.relative_path.clone()
            } else {
                format!("{destination_relative}/{}", entry.relative_path)
            };
            validate_relative_path(&relative)?;
            Ok(relative)
        })
        .collect::<Result<Vec<_>, WorkspaceError>>()?;
    Ok(DropManifest {
        entries: builder.entries,
        top_level_relative_paths,
        imported_relative_paths,
    })
}

/// 在 staging 前后核对 manifest 节点的类型、物理身份、硬链接与目录孩子集合；
/// 目录也必须验证，否则空目录可在 hash 缺席时被同名替换后悄然导入。
pub(super) fn verify_drop_manifest_entry(entry: &DropManifestEntry) -> Result<(), WorkspaceError> {
    reject_link_components(&entry.source).map_err(|_| WorkspaceError::PathChanged)?;
    let metadata = fs::symlink_metadata(&entry.source)
        .map_err(|error| WorkspaceError::io("drop_recheck", error))?;
    if is_reparse_point(&metadata)
        || metadata.file_type().is_symlink()
        || move_identity(&entry.source)? != entry.identity
        || metadata.len() != entry.size
        || drop_modified_unix_millis(&metadata) != entry.modified_unix_millis
    {
        return Err(WorkspaceError::PathChanged);
    }
    match entry.kind {
        EntryKind::File => {
            if !metadata.is_file()
                || entry.children.is_some()
                || entry.sha256.is_none()
                || drop_hard_link_count(&entry.source, &metadata)? > 1
            {
                return Err(WorkspaceError::PathChanged);
            }
        }
        EntryKind::Directory => {
            if !metadata.is_dir()
                || entry.sha256.is_some()
                || entry.children.as_deref()
                    != Some(sorted_trash_children(&entry.source)?.as_slice())
            {
                return Err(WorkspaceError::PathChanged);
            }
        }
        EntryKind::Symlink | EntryKind::ReparsePoint | EntryKind::Other => {
            return Err(WorkspaceError::PathChanged);
        }
    }
    Ok(())
}

/// 将一个 manifest 文件流式复制到 staging，并要求复制摘要与预检摘要一致；
/// 临时副本可安全删除，但任何 source 原件都不会成为清理对象。
pub(super) fn copy_drop_file_to_staging(
    entry: &DropManifestEntry,
    target: &Path,
    deadline: Instant,
) -> Result<(), WorkspaceError> {
    verify_drop_manifest_entry(entry)?;
    let mut source =
        fs::File::open(&entry.source).map_err(|error| WorkspaceError::io("drop_open", error))?;
    if open_file_identity(&source)? != entry.identity
        || move_identity(&entry.source)? != entry.identity
    {
        return Err(WorkspaceError::PathChanged);
    }
    let mut destination = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(target)
        .map_err(|error| WorkspaceError::io("drop_stage_file", error))?;
    let mut digest = Sha256::new();
    let mut copied = 0u64;
    let mut buffer = [0u8; 64 * 1024];
    loop {
        if Instant::now() >= deadline {
            return Err(WorkspaceError::ScanDeadlineExceeded);
        }
        let count = source
            .read(&mut buffer)
            .map_err(|error| WorkspaceError::io("drop_read", error))?;
        if count == 0 {
            break;
        }
        copied = copied
            .checked_add(u64::try_from(count).map_err(|_| WorkspaceError::FileTooLarge)?)
            .ok_or(WorkspaceError::FileTooLarge)?;
        if copied > entry.size {
            return Err(WorkspaceError::PathChanged);
        }
        destination
            .write_all(&buffer[..count])
            .map_err(|error| WorkspaceError::io("drop_stage_write", error))?;
        digest.update(&buffer[..count]);
    }
    destination
        .flush()
        .map_err(|error| WorkspaceError::io("drop_stage_flush", error))?;
    destination
        .sync_all()
        .map_err(|error| WorkspaceError::io("drop_stage_sync", error))?;
    let after = source
        .metadata()
        .map_err(|error| WorkspaceError::io("drop_recheck", error))?;
    reject_link_components(&entry.source).map_err(|_| WorkspaceError::PathChanged)?;
    let path_after = fs::symlink_metadata(&entry.source)
        .map_err(|error| WorkspaceError::io("drop_recheck", error))?;
    if copied != entry.size
        || after.len() != entry.size
        || drop_modified_unix_millis(&after) != entry.modified_unix_millis
        || !path_after.is_file()
        || is_reparse_point(&path_after)
        || path_after.file_type().is_symlink()
        || drop_hard_link_count(&entry.source, &path_after)? > 1
        || open_file_identity(&source)? != entry.identity
        || move_identity(&entry.source)? != entry.identity
        || entry.sha256.as_deref() != Some(hex_lower(&digest.finalize()).as_str())
    {
        return Err(WorkspaceError::PathChanged);
    }
    Ok(())
}

/// 清理的对象只能是本次生成的 staging 副本；清理失败升级为 recovery，不能让
/// 调用方误以为事务已完全回滚。
pub(super) fn cleanup_drop_staging(path: &Path) -> Result<(), WorkspaceError> {
    match fs::remove_dir_all(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(_) => Err(WorkspaceError::RecoveryRequired),
    }
}

/// 比较 destination 的直接孩子集合并忽略唯一 staging 名；它补偿 staging 自身
/// 改变目录 revision，同时仍能发现外部新增、删除和重命名。
pub(super) fn verify_drop_destination_shape(
    destination: &Path,
    staging_name: &std::ffi::OsStr,
    expected_children: &[std::ffi::OsString],
) -> Result<(), WorkspaceError> {
    let mut current = sorted_trash_children(destination)?;
    current.retain(|name| name != staging_name);
    if current == expected_children {
        Ok(())
    } else {
        Err(WorkspaceError::RevisionConflict)
    }
}

/// 已发布的顶层节点必须保留 staging/target/identity 三元组，application 才能在 commit
/// 失败后按严格逆序执行真实补偿。
struct PromotedDropEntry {
    staged: PathBuf,
    target: PathBuf,
    identity: MoveIdentity,
}

/// Drop stage 的完整真实状态；token 在构建 manifest 前消费，全部源节点已复制到同目录
/// staging，commit 只负责原子发布，rollback 负责逆序恢复、清理并返还 capability。
pub(crate) struct PreparedDropImport {
    token: Option<String>,
    plan: Option<NativeDropPlan>,
    destination_relative: String,
    manifest: DropManifest,
    destination_path: PathBuf,
    stage_name: String,
    stage_path: PathBuf,
    baseline_children: Vec<std::ffi::OsString>,
    promoted: Vec<PromotedDropEntry>,
    completed: bool,
}

/// 把未提交的 Drop capability 放回唯一计划表；只允许原 token 空缺且仍未过期，避免恢复覆盖新计划。
fn restore_native_drop_plan(token: &str, plan: NativeDropPlan) -> Result<(), WorkspaceError> {
    if plan.expires <= Instant::now() {
        return Err(WorkspaceError::RecoveryRequired);
    }
    let mut plans = drop_plans()
        .lock()
        .map_err(|_| WorkspaceError::RecoveryRequired)?;
    if plans.contains_key(token) {
        return Err(WorkspaceError::RecoveryRequired);
    }
    plans.insert(token.to_owned(), plan);
    Ok(())
}

/// 在同目录完成全部复制并再次核对源身份与 destination shape；返回后磁盘 staging 就是可回滚资源。
fn prepare_drop_manifest(
    workspace: &WorkspaceHandle,
    destination_relative: &str,
    expected_revision: &FileRevision,
    manifest: DropManifest,
    token: Option<String>,
    plan: Option<NativeDropPlan>,
) -> Result<PreparedDropImport, WorkspaceError> {
    // 先固定 destination CAS、直接孩子与所有顶层缺席事实，stage 绝不覆盖已有用户节点。
    let destination = current_metadata(workspace, destination_relative)?;
    require_revision(expected_revision, &destination.revision)?;
    if destination.kind != EntryKind::Directory {
        return Err(WorkspaceError::NotDirectory);
    }
    let destination_guard = workspace.resolve_guard(destination_relative, Some(true))?;
    let baseline_children = sorted_trash_children(&destination_guard.path)?;
    for relative in &manifest.top_level_relative_paths {
        let target_relative = if destination_relative.is_empty() {
            relative.clone()
        } else {
            format!("{destination_relative}/{relative}")
        };
        let (_, target) = workspace.resolve_parent(&target_relative)?;
        require_destination_absent(&target)?;
    }
    workspace.verify_resolved(&destination_guard, Some(true))?;

    // 全量 source 复制只进入唯一 staging；失败时不会改变任何最终目标。
    let stage_name = format!(".ja-drop-{}.tmp", Uuid::new_v4());
    let stage_path = destination_guard.path.join(&stage_name);
    fs::create_dir(&stage_path).map_err(|error| WorkspaceError::io("drop_stage", error))?;
    let stage_deadline = Instant::now() + DROP_SCAN_DEADLINE;
    let stage_result = (|| {
        for entry in &manifest.entries {
            let target = stage_path.join(Path::new(&entry.relative_path));
            if entry.kind == EntryKind::Directory {
                verify_drop_manifest_entry(entry)?;
                fs::create_dir(&target)
                    .map_err(|error| WorkspaceError::io("drop_stage_dir", error))?;
            } else {
                copy_drop_file_to_staging(entry, &target, stage_deadline)?;
            }
        }
        for entry in &manifest.entries {
            verify_drop_manifest_entry(entry)?;
        }
        workspace.verify_resolved(&destination_guard, Some(true))?;
        verify_drop_destination_shape(
            &destination_guard.path,
            std::ffi::OsStr::new(&stage_name),
            &baseline_children,
        )
    })();
    if let Err(error) = stage_result {
        cleanup_drop_staging(&stage_path)?;
        return Err(error);
    }
    Ok(PreparedDropImport {
        token,
        plan,
        destination_relative: destination_relative.to_owned(),
        manifest,
        destination_path: destination_guard.path,
        stage_name,
        stage_path,
        baseline_children,
        promoted: Vec::new(),
        completed: false,
    })
}

/// Stage 在锁内消费一次性 token、构建 manifest 并复制到 staging；任何 stage 错误都会返还 capability。
pub(crate) fn prepare_drop_import(
    workspace: &WorkspaceHandle,
    command: &DropImportCommand,
) -> Result<PreparedDropImport, WorkspaceError> {
    let token = command.drop_token.as_str().to_owned();
    let plan = consume_native_drop_plan(&token)?;
    let manifest = match build_drop_manifest(&plan, command.destination_relative_path.as_str()) {
        Ok(manifest) => manifest,
        Err(error) => {
            restore_native_drop_plan(&token, plan)?;
            return Err(error);
        }
    };
    match prepare_drop_manifest(
        workspace,
        command.destination_relative_path.as_str(),
        &command.expected_revision,
        manifest,
        Some(token.clone()),
        Some(plan.clone()),
    ) {
        Ok(prepared) => Ok(prepared),
        Err(error) => {
            restore_native_drop_plan(&token, plan)?;
            Err(error)
        }
    }
}

/// Commit 只发布 prepared staging；每次成功发布立即写入账本，错误直接交回 application 触发 rollback。
pub(crate) fn commit_prepared_drop_with<F>(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedDropImport,
    mut promote: F,
) -> Result<DropImportResult, WorkspaceError>
where
    F: FnMut(&Path, &Path, &dyn Fn() -> Result<(), WorkspaceError>) -> Result<(), WorkspaceError>,
{
    let destination_guard = workspace.resolve_guard(&prepared.destination_relative, Some(true))?;
    if destination_guard.path != prepared.destination_path {
        return Err(WorkspaceError::PathChanged);
    }
    // Stage 目录本身会改变 destination revision，因此 commit 不能拿 stage 前 revision
    // 与包含内部 staging 的目录重新比较；这里改用 stage 前捕获的直接孩子集合，并显式忽略唯一 staging。
    verify_drop_destination_shape(
        &prepared.destination_path,
        std::ffi::OsStr::new(&prepared.stage_name),
        &prepared.baseline_children,
    )?;
    let mut expected_children = prepared.baseline_children.clone();
    for relative in &prepared.manifest.top_level_relative_paths {
        let target_relative = if prepared.destination_relative.is_empty() {
            relative.clone()
        } else {
            format!("{}/{relative}", prepared.destination_relative)
        };
        let (_, target) = workspace.resolve_parent(&target_relative)?;
        let staged = prepared.stage_path.join(relative);
        let identity = move_identity(&staged)?;
        let verify = || {
            workspace.verify_resolved(&destination_guard, Some(true))?;
            verify_drop_destination_shape(
                &prepared.destination_path,
                std::ffi::OsStr::new(&prepared.stage_name),
                &expected_children,
            )?;
            require_destination_absent(&target)?;
            if move_identity(&staged)? != identity {
                return Err(WorkspaceError::PathChanged);
            }
            Ok(())
        };
        promote(&staged, &target, &verify)?;
        prepared.promoted.push(PromotedDropEntry {
            staged,
            target,
            identity,
        });
        expected_children.push(std::ffi::OsString::from(relative));
        expected_children.sort();
    }
    // staging 清理是 commit 的最后持久化步骤；失败时保留空目录作为 recovery 证据。
    fs::remove_dir(&prepared.stage_path).map_err(|_| WorkspaceError::RecoveryRequired)?;
    prepared.completed = true;
    Ok(DropImportResult {
        imported_relative_paths: prepared.manifest.imported_relative_paths.clone(),
    })
}

/// Production Drop 通过同一 no-replace 原语发布 prepared staging。
pub(crate) fn commit_prepared_drop(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedDropImport,
) -> Result<DropImportResult, WorkspaceError> {
    commit_prepared_drop_with(workspace, prepared, |source, target, verify| {
        rename_no_replace(source, target, verify)
    })
}

/// 逆序恢复已发布节点、删除 staging，并仅在磁盘恢复原状后返还一次性 token。
pub(crate) fn rollback_prepared_drop(
    workspace: &WorkspaceHandle,
    prepared: &mut PreparedDropImport,
) -> Result<(), WorkspaceError> {
    if prepared.completed {
        return Ok(());
    }
    let destination_guard = workspace.resolve_guard(&prepared.destination_relative, Some(true))?;
    for committed in prepared.promoted.iter().rev() {
        let rollback_verify = || {
            workspace.verify_resolved(&destination_guard, Some(true))?;
            require_destination_absent(&committed.staged)?;
            if move_identity(&committed.target)? != committed.identity {
                return Err(WorkspaceError::PathChanged);
            }
            Ok(())
        };
        rename_no_replace(&committed.target, &committed.staged, rollback_verify)
            .map_err(|_| WorkspaceError::RecoveryRequired)?;
    }
    prepared.promoted.clear();
    cleanup_drop_staging(&prepared.stage_path)?;
    if let (Some(token), Some(plan)) = (prepared.token.take(), prepared.plan.take()) {
        restore_native_drop_plan(&token, plan)?;
    }
    Ok(())
}

/// Recovery 保留 staging 或已发布目标以及已消费 capability 的内存证据，不自动重试可能重复的导入。
pub(crate) fn preserve_prepared_drop(prepared: &PreparedDropImport) -> Result<(), WorkspaceError> {
    if prepared.stage_path.exists() || prepared.promoted.iter().any(|entry| entry.target.exists()) {
        Ok(())
    } else {
        Err(WorkspaceError::RecoveryRequired)
    }
}
