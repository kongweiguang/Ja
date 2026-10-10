// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 未知 Thread fork 回执的不可变来源身份与重放参数。

use serde_json::{Value, json};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

/// 保存首次 fork 的完整输入，使调用方的重试不能漂移到当前最新 Turn 或 revision。
#[derive(Clone)]
pub(in crate::controller) struct PendingFork {
    pub(in crate::controller) operation_key: String,
    pub(in crate::controller) source_thread_id: String,
    pub(in crate::controller) source_item_id: String,
    pub(in crate::controller) expected_thread_revision: u64,
    pub(in crate::controller) child_thread_id: Option<String>,
}

/// @author kongweiguang
/// 扫描结果隔离损坏记录，允许其他 operation 的最小恢复身份继续可用。
pub(in crate::controller) struct PendingForkJournals {
    pub(in crate::controller) pending: Vec<PendingFork>,
    pub(in crate::controller) errors: Vec<String>,
}

/// @author kongweiguang
/// 把 fork 的只读回查与可能重放隔离成小端口，测试可验证调用顺序而不启动后台。
pub(in crate::controller) trait ForkRecoveryPort {
    type Error;

    /// @author kongweiguang
    /// 用 operation key 与来源 owner 查询唯一原回执，不接受省略 owner 的回退查询。
    fn read_operation(
        &mut self,
        operation_key: &str,
        source_thread_id: &str,
    ) -> Result<Value, Self::Error>;

    /// @author kongweiguang
    /// 只在 owner 查询明确返回 unknown 后提交 journal 保存的原始 tuple。
    fn submit_fork(&mut self, request: &PendingFork) -> Result<Value, Self::Error>;
}

/// @author kongweiguang
/// 区分只读失败、畸形/错 owner 回执与实际提交错误，避免前两者触发副作用。
pub(in crate::controller) enum ForkRecoveryError<E> {
    Read(E),
    InvalidReceipt(&'static str),
    Submit(E),
}

/// @author kongweiguang
/// committed 只交付同 owner 的原结果；只有显式 unknown 才可用同一 tuple 重放一次。
pub(in crate::controller) fn read_then_retry<P: ForkRecoveryPort>(
    port: &mut P,
    request: &PendingFork,
) -> Result<Value, ForkRecoveryError<P::Error>> {
    let lookup = port
        .read_operation(&request.operation_key, &request.source_thread_id)
        .map_err(ForkRecoveryError::Read)?;
    match fork_operation_read_result(&lookup, request).map_err(ForkRecoveryError::InvalidReceipt)? {
        Some(result) => Ok(result),
        None => port.submit_fork(request).map_err(ForkRecoveryError::Submit),
    }
}

/// @author kongweiguang
/// operation/read 的 unknown 没有 committed owner；成功态必须同时匹配来源和 fork 方法。
pub(in crate::controller) fn fork_operation_read_result(
    lookup: &Value,
    request: &PendingFork,
) -> Result<Option<Value>, &'static str> {
    match lookup.get("status").and_then(Value::as_str) {
        Some("unknown") => Ok(None),
        Some("committed") => {
            if lookup.get("method").and_then(Value::as_str) != Some("thread/fork")
                || lookup.get("threadId").and_then(Value::as_str)
                    != Some(request.source_thread_id.as_str())
            {
                return Err("分叉操作账本与原来源或方法不一致");
            }
            lookup
                .get("result")
                .filter(|result| result.is_object())
                .cloned()
                .map(Some)
                .ok_or("分叉操作账本缺少原回执")
        }
        _ => Err("分叉操作账本状态无效"),
    }
}

/// @author kongweiguang
/// 只清理与桌面端相同的明确拒绝码；服务不可用、超时和内部错误都保留原恢复 tuple。
pub(in crate::controller) fn is_definitive_fork_rejection(error_code: Option<i64>) -> bool {
    matches!(
        error_code,
        Some(-32_007 | -32_019 | -32_028 | -32_029 | -32_034)
    )
}

impl ForkRecoveryPort for super::super::rpc::Connection {
    type Error = super::super::CliError;

    /// @author kongweiguang
    /// operation/read 显式携带 sourceThreadId，确保 Java 只查该来源 owner 的 fork 账本。
    fn read_operation(
        &mut self,
        operation_key: &str,
        source_thread_id: &str,
    ) -> Result<Value, Self::Error> {
        self.request(
            "operation/read",
            json!({
                "operationKey": operation_key,
                "sourceThreadId": source_thread_id
            }),
        )
    }

    /// @author kongweiguang
    /// 重放沿用持久化请求身份；不能重新生成 operationKey 或改写回复锚点。
    fn submit_fork(&mut self, request: &PendingFork) -> Result<Value, Self::Error> {
        self.request_operation_with_key("thread/fork", request.params(), &request.operation_key)
    }
}

impl PendingFork {
    /// 以冻结来源重建相同参数对象，避免回执未知时生成新的副作用身份。
    pub(in crate::controller) fn params(&self) -> Value {
        json!({
            "sourceThreadId":self.source_thread_id,
            "sourceItemId":self.source_item_id,
            "expectedThreadRevision":self.expected_thread_revision,
            "operationKey":self.operation_key
        })
    }

    /// @author kongweiguang
    /// 迟到回执只能打开仍聚焦的来源或其已知 child；其他 Thread 的焦点不随恢复操作切换。
    pub(in crate::controller) fn allows_opening_from(
        &self,
        current_thread_id: Option<&str>,
    ) -> bool {
        current_thread_id.is_some_and(|current| {
            current == self.source_thread_id || self.child_thread_id.as_deref() == Some(current)
        })
    }

    /// @author kongweiguang
    /// 同一 journal 可按来源或已知 child 找回；切换焦点时不靠扫描 Thread 历史推导关联。
    pub(in crate::controller) fn matches_thread_id(&self, thread_id: &str) -> bool {
        self.source_thread_id == thread_id || self.child_thread_id.as_deref() == Some(thread_id)
    }

    /// @author kongweiguang
    /// 单次只激活一个匹配记录，其余 operation 文件留在目录供后续切换或重启恢复。
    pub(in crate::controller) fn take_for_thread(
        pending: &mut Vec<Self>,
        thread_id: &str,
    ) -> Option<Self> {
        pending
            .iter()
            .position(|request| request.matches_thread_id(thread_id))
            .map(|index| pending.remove(index))
    }

    /// @author kongweiguang
    /// 文件名只由校验过的 operationKey 构造，避免 journal 读写越出 forks 目录。
    pub(in crate::controller) fn journal_path(
        directory: &Path,
        operation_key: &str,
    ) -> Result<PathBuf, String> {
        if !super::super::args::valid_operation_key(operation_key) {
            return Err("待核实分叉 operationKey 无效".into());
        }
        Ok(directory.join(format!("{operation_key}.json")))
    }

    /// @author kongweiguang
    /// 启动和切换时仅读取 forks 目录中的小型身份记录，不访问 Thread 历史或正文。
    pub(in crate::controller) fn read_journals(directory: &Path) -> PendingForkJournals {
        let entries = match fs::read_dir(directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return PendingForkJournals {
                    pending: Vec::new(),
                    errors: Vec::new(),
                };
            }
            Err(_) => {
                return PendingForkJournals {
                    pending: Vec::new(),
                    errors: vec!["无法读取待核实分叉目录".into()],
                };
            }
        };
        let mut files = Vec::new();
        let mut errors = Vec::new();
        for entry in entries {
            let entry = match entry {
                Ok(entry) => entry,
                Err(_) => {
                    errors.push("无法枚举待核实分叉目录".into());
                    continue;
                }
            };
            let path = entry.path();
            let is_fork_json = path.extension().and_then(|value| value.to_str()) == Some("json")
                && path
                    .file_stem()
                    .and_then(|value| value.to_str())
                    .is_some_and(|value| value.starts_with("op_"));
            if !is_fork_json {
                continue;
            }
            let Some(operation_key) = path.file_stem().and_then(|value| value.to_str()) else {
                errors.push("待核实分叉文件名无效".into());
                continue;
            };
            if !super::super::args::valid_operation_key(operation_key) {
                errors.push("待核实分叉文件名的 operationKey 无效".into());
                continue;
            }
            match entry.file_type() {
                Ok(file_type) if file_type.is_file() => {}
                Ok(_) => continue,
                Err(_) => {
                    errors.push("无法读取待核实分叉文件类型".into());
                    continue;
                }
            }
            files.push((operation_key.to_owned(), path));
        }
        files.sort_by(|left, right| left.0.cmp(&right.0));
        let mut pending = Vec::new();
        for (operation_key, path) in files {
            match Self::read_journal_file(&path) {
                Ok(Some(request)) if request.operation_key == operation_key => {
                    pending.push(request)
                }
                Ok(Some(_)) => errors.push("待核实分叉文件身份与内容不一致".into()),
                Ok(None) => {}
                Err(error) => errors.push(error),
            }
        }
        PendingForkJournals { pending, errors }
    }

    /// 只接受与原请求完全对应且指向不同 Thread 的持久回执。
    pub(in crate::controller) fn fork_id<'a>(
        &self,
        result: &'a Value,
    ) -> Result<&'a str, &'static str> {
        let source = result.get("source").ok_or("分叉回执缺少 source")?;
        if source.get("threadId").and_then(Value::as_str) != Some(self.source_thread_id.as_str())
            || source.get("itemId").and_then(Value::as_str) != Some(self.source_item_id.as_str())
            || source.get("threadRevision").and_then(Value::as_u64)
                != Some(self.expected_thread_revision)
        {
            return Err("分叉回执与来源会话不一致");
        }
        let thread = result.get("thread").ok_or("分叉回执缺少 Thread")?;
        let fork_id = thread
            .get("threadId")
            .and_then(Value::as_str)
            .filter(|identity| !identity.is_empty())
            .ok_or("分叉回执缺少 Thread identity")?;
        if fork_id == self.source_thread_id {
            return Err("分叉 Thread identity 与来源重复");
        }
        Ok(fork_id)
    }

    /// @author kongweiguang
    /// 单文件解析只供按 operationKey 命名的目录扫描使用，损坏元数据不会变成可安全重发的请求。
    fn read_journal_file(path: &Path) -> Result<Option<Self>, String> {
        let bytes = match fs::read(path) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(_) => return Err("无法读取待核实分叉记录".into()),
        };
        if bytes.len() > 16 * 1024 {
            return Err("待核实分叉记录超出大小限制".into());
        }
        let value: Value =
            serde_json::from_slice(&bytes).map_err(|_| "待核实分叉记录格式损坏".to_owned())?;
        let string = |name: &str| {
            value
                .get(name)
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty() && value.len() <= 512)
                .map(str::to_owned)
                .ok_or_else(|| format!("待核实分叉记录缺少 {name}"))
        };
        let child_thread_id = value
            .get("childThreadId")
            .filter(|value| !value.is_null())
            .map(|value| {
                value
                    .as_str()
                    .filter(|value| !value.is_empty() && value.len() <= 512)
                    .map(str::to_owned)
                    .ok_or_else(|| "待核实分叉记录的 childThreadId 无效".to_owned())
            })
            .transpose()?;
        let revision = value
            .get("expectedThreadRevision")
            .and_then(Value::as_u64)
            .ok_or_else(|| "待核实分叉记录缺少 expectedThreadRevision".to_owned())?;
        let operation_key = string("operationKey")?;
        if !super::super::args::valid_operation_key(&operation_key) {
            return Err("待核实分叉 operationKey 无效".into());
        }
        Ok(Some(Self {
            operation_key,
            source_thread_id: string("sourceThreadId")?,
            source_item_id: string("sourceItemId")?,
            expected_thread_revision: revision,
            child_thread_id,
        }))
    }

    /// @author kongweiguang
    /// 每个 operationKey 独立原子写入，避免两个 CLI 实例互相覆盖未知回执身份。
    pub(in crate::controller) fn write_journal(&self, directory: &Path) -> Result<(), String> {
        let path = Self::journal_path(directory, &self.operation_key)?;
        let parent = path
            .parent()
            .ok_or_else(|| "待核实分叉记录路径无效".to_owned())?;
        super::rpc::ensure_directory_tree(parent)
            .map_err(|_| "无法创建 Ja data 子目录".to_owned())?;
        let value = json!({
            "operationKey": self.operation_key,
            "sourceThreadId": self.source_thread_id,
            "sourceItemId": self.source_item_id,
            "expectedThreadRevision": self.expected_thread_revision,
            "childThreadId": self.child_thread_id,
        });
        let bytes = serde_json::to_vec(&value).map_err(|_| "无法编码待核实分叉记录".to_owned())?;
        if bytes.len() > 16 * 1024 {
            return Err("待核实分叉记录超出大小限制".into());
        }
        let temporary = path.with_extension(format!("{}.tmp", uuid::Uuid::new_v4().simple()));
        let result = (|| {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)
                .map_err(|_| "无法创建待核实分叉记录".to_owned())?;
            file.write_all(&bytes)
                .and_then(|()| file.sync_all())
                .map_err(|_| "无法保存待核实分叉记录".to_owned())?;
            atomic_replace(&temporary, &path).map_err(|_| "无法提交待核实分叉记录".to_owned())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result
    }

    /// @author kongweiguang
    /// 清理按 operationKey 精确定位，只删除明确拒绝或已打开的本操作记录。
    pub(in crate::controller) fn clear_journal(
        directory: &Path,
        operation_key: &str,
    ) -> Result<(), String> {
        let path = Self::journal_path(directory, operation_key)?;
        match fs::remove_file(path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err("无法清理待核实分叉记录".into()),
        }
    }
}

/// Windows 需要 replace-existing 才能原子更新恢复记录，避免重写时留下旧 tuple。
#[cfg(windows)]
fn atomic_replace(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;

    let source = source
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();
    let destination = destination
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();
    const MOVEFILE_REPLACE_EXISTING: u32 = 0x1;
    const MOVEFILE_WRITE_THROUGH: u32 = 0x8;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn MoveFileExW(existing: *const u16, replacement: *const u16, flags: u32) -> i32;
    }
    // SAFETY: both NUL-terminated UTF-16 buffers live through the synchronous Win32 call.
    if unsafe {
        MoveFileExW(
            source.as_ptr(),
            destination.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    } == 0
    {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

/// POSIX 同目录 rename 原子替换同一操作身份的旧记录。
#[cfg(not(windows))]
fn atomic_replace(source: &Path, destination: &Path) -> std::io::Result<()> {
    fs::rename(source, destination)
}
