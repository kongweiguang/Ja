// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use windows::Win32::UI::Shell::{
    IFileOperationProgressSink, IFileOperationProgressSink_Impl, IShellItem,
};
use windows::core::{HRESULT, PCWSTR, Ref as WindowsRef, Result as WindowsResult};

#[cfg(windows)]
/// 将 Rust canonicalize 产生的 verbatim drive spelling 转成 Shell parsing name；
/// 只剥离 `\\?\`，并拒绝 UNC、device 与非 drive namespace，避免远端或设备路径进入删除边界。
pub(super) fn windows_shell_parsing_path(path: &Path) -> Result<Vec<u16>, WorkspaceError> {
    use std::os::windows::ffi::OsStrExt;

    let wide = path.as_os_str().encode_wide().collect::<Vec<_>>();
    if wide.contains(&0) {
        return Err(WorkspaceError::InvalidRelativePath);
    }
    const VERBATIM: [u16; 4] = [b'\\' as u16, b'\\' as u16, b'?' as u16, b'\\' as u16];
    const VERBATIM_UNC: [u16; 8] = [
        b'\\' as u16,
        b'\\' as u16,
        b'?' as u16,
        b'\\' as u16,
        b'U' as u16,
        b'N' as u16,
        b'C' as u16,
        b'\\' as u16,
    ];
    let is_verbatim_unc = wide.get(..VERBATIM_UNC.len()).is_some_and(|prefix| {
        prefix.iter().zip(VERBATIM_UNC).all(|(left, right)| {
            let folded = if (*left >= b'a' as u16) && (*left <= b'z' as u16) {
                *left - u16::from(b'a' - b'A')
            } else {
                *left
            };
            folded == right
        })
    });
    if is_verbatim_unc {
        return Err(WorkspaceError::io(
            "trash_path",
            std::io::ErrorKind::Unsupported,
        ));
    }
    let mut normalized = if wide.starts_with(&VERBATIM) {
        wide[VERBATIM.len()..].to_vec()
    } else if wide.starts_with(&[b'\\' as u16, b'\\' as u16]) {
        return Err(WorkspaceError::io(
            "trash_path",
            std::io::ErrorKind::Unsupported,
        ));
    } else {
        wide
    };
    if normalized.len() < 3
        || !u8::try_from(normalized[0]).is_ok_and(|letter| letter.is_ascii_alphabetic())
        || normalized[1] != b':' as u16
        || normalized[2] != b'\\' as u16
    {
        return Err(WorkspaceError::io(
            "trash_path",
            std::io::ErrorKind::Unsupported,
        ));
    }
    normalized.push(0);
    Ok(normalized)
}

#[cfg(windows)]
/// 从 Win32 canonical volume name 构造当前用户回收站策略键；严格校验 GUID，
/// 防止文件路径片段被误当作 Registry 子键。
pub(super) fn windows_recycle_policy_key(volume_name: &[u16]) -> Result<Vec<u16>, WorkspaceError> {
    let end = volume_name
        .iter()
        .position(|value| *value == 0)
        .unwrap_or(volume_name.len());
    let decoded = String::from_utf16(&volume_name[..end])
        .map_err(|_| WorkspaceError::io("trash_policy", std::io::ErrorKind::InvalidData))?;
    let guid = decoded
        .strip_prefix(r"\\?\Volume")
        .and_then(|value| value.strip_suffix('\\'))
        .ok_or_else(|| WorkspaceError::io("trash_policy", std::io::ErrorKind::InvalidData))?;
    let body = guid
        .strip_prefix('{')
        .and_then(|value| value.strip_suffix('}'))
        .ok_or_else(|| WorkspaceError::io("trash_policy", std::io::ErrorKind::InvalidData))?;
    let groups = body.split('-').collect::<Vec<_>>();
    if groups.len() != 5
        || groups
            .iter()
            .zip([8usize, 4, 4, 4, 12])
            .any(|(group, expected)| {
                group.len() != expected || !group.bytes().all(|value| value.is_ascii_hexdigit())
            })
    {
        return Err(WorkspaceError::io(
            "trash_policy",
            std::io::ErrorKind::InvalidData,
        ));
    }
    let mut key =
        format!(r"Software\Microsoft\Windows\CurrentVersion\Explorer\BitBucket\Volume\{guid}")
            .encode_utf16()
            .collect::<Vec<_>>();
    key.push(0);
    Ok(key)
}

#[cfg(windows)]
/// 在任何 Shell 副作用前读取目标卷的 per-user 回收站策略。Windows 在
/// `NukeOnDelete=1` 时会把“删除”永久执行；Ja 必须失败关闭，不能让界面的
/// “移入回收站”静默退化为不可恢复删除。缺少卷级覆盖时沿用系统默认可回收语义。
pub(super) fn ensure_windows_recycle_policy(parsing_path: &[u16]) -> Result<(), WorkspaceError> {
    use windows::Win32::Foundation::{ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND, ERROR_SUCCESS};
    use windows::Win32::Storage::FileSystem::GetVolumeNameForVolumeMountPointW;
    use windows::Win32::System::Registry::{HKEY_CURRENT_USER, RRF_RT_REG_DWORD, RegGetValueW};

    if parsing_path.len() < 3 {
        return Err(WorkspaceError::io(
            "trash_policy",
            std::io::ErrorKind::InvalidInput,
        ));
    }
    let mount = [parsing_path[0], parsing_path[1], parsing_path[2], 0];
    let mut volume_name = [0u16; 64];
    // 安全性：两个 buffer 在调用期间均以 NUL 结尾且被持有；输出 slice 足以容纳 canonical volume GUID 与终止符。
    unsafe { GetVolumeNameForVolumeMountPointW(PCWSTR(mount.as_ptr()), &mut volume_name) }
        .map_err(|_| WorkspaceError::io("trash_policy", std::io::ErrorKind::Other))?;
    let policy_key = windows_recycle_policy_key(&volume_name)?;
    let mut nuke_on_delete = 0u32;
    let mut data_size = std::mem::size_of::<u32>() as u32;
    // 安全性：key/value string 均以 NUL 结尾，DWORD 输出指向已初始化可写存储，并显式传入字节长度。
    let status = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            PCWSTR(policy_key.as_ptr()),
            windows::core::w!("NukeOnDelete"),
            RRF_RT_REG_DWORD,
            None,
            Some((&mut nuke_on_delete as *mut u32).cast()),
            Some(&mut data_size),
        )
    };
    if status == ERROR_FILE_NOT_FOUND || status == ERROR_PATH_NOT_FOUND {
        return Ok(());
    }
    if status != ERROR_SUCCESS || data_size != std::mem::size_of::<u32>() as u32 {
        return Err(WorkspaceError::io(
            "trash_policy",
            std::io::ErrorKind::Other,
        ));
    }
    if nuke_on_delete != 0 {
        return Err(WorkspaceError::RecycleUnavailable);
    }
    Ok(())
}

#[cfg(windows)]
#[derive(Default)]
pub(super) struct TrashPostDeleteEvidence {
    callback_count: std::sync::atomic::AtomicUsize,
    delete_succeeded: std::sync::atomic::AtomicBool,
    recycled_item_reported: std::sync::atomic::AtomicBool,
}

#[cfg(windows)]
impl TrashPostDeleteEvidence {
    /// 只记录单个 DeleteItem 的 PostDelete 结果；多次回调会在最终判定中失败关闭，
    /// 避免把意外批处理的一次成功误当成目标已进入回收站。
    pub(super) fn record(&self, result: HRESULT, recycled_item_reported: bool) {
        use std::sync::atomic::Ordering;

        self.callback_count.fetch_add(1, Ordering::SeqCst);
        self.delete_succeeded
            .store(result.is_ok(), Ordering::SeqCst);
        self.recycled_item_reported
            .store(recycled_item_reported, Ordering::SeqCst);
    }

    /// 只有一次成功 PostDelete 且 Shell 返回新回收站 item 时才承认 recycle-only；
    /// 永久删除通常没有 newly-created item，必须被拒绝而不是当作成功。
    pub(super) fn proves_recycled(&self) -> bool {
        use std::sync::atomic::Ordering;

        self.callback_count.load(Ordering::SeqCst) == 1
            && self.delete_succeeded.load(Ordering::SeqCst)
            && self.recycled_item_reported.load(Ordering::SeqCst)
    }
}

#[cfg(windows)]
#[windows::core::implement(IFileOperationProgressSink)]
pub(super) struct TrashProgressSink {
    evidence: std::sync::Arc<TrashPostDeleteEvidence>,
}

#[cfg(windows)]
impl TrashProgressSink {
    /// 同时返回 COM sink 与进程内证据句柄，使 `PerformOperations` 完成后无需从
    /// WebView 或全局状态读取任何路径即可核验 PostDelete。
    pub(super) fn create() -> (
        IFileOperationProgressSink,
        std::sync::Arc<TrashPostDeleteEvidence>,
    ) {
        let evidence = std::sync::Arc::new(TrashPostDeleteEvidence::default());
        (
            Self {
                evidence: std::sync::Arc::clone(&evidence),
            }
            .into(),
            evidence,
        )
    }
}

#[cfg(windows)]
#[allow(non_snake_case)]
impl IFileOperationProgressSink_Impl for TrashProgressSink_Impl {
    /// 不在回调中改变 Shell 生命周期；所有准入已在执行前完成。
    fn StartOperations(&self) -> WindowsResult<()> {
        Ok(())
    }

    /// 总体 HRESULT 仍由 `PerformOperations` 返回值负责，避免维护两份冲突状态。
    fn FinishOperations(&self, _result: HRESULT) -> WindowsResult<()> {
        Ok(())
    }

    /// 本 sink 不支持 rename；中性返回可避免影响同一 COM 对象的内部流程。
    fn PreRenameItem(
        &self,
        _flags: u32,
        _item: WindowsRef<'_, IShellItem>,
        _new_name: &PCWSTR,
    ) -> WindowsResult<()> {
        Ok(())
    }

    /// rename 不是该 adapter 的证据来源，故不写入 recycle 状态。
    fn PostRenameItem(
        &self,
        _flags: u32,
        _item: WindowsRef<'_, IShellItem>,
        _new_name: &PCWSTR,
        _result: HRESULT,
        _created: WindowsRef<'_, IShellItem>,
    ) -> WindowsResult<()> {
        Ok(())
    }

    /// 本 sink 不批准额外 move，仅保持 Shell 回调链可继续。
    fn PreMoveItem(
        &self,
        _flags: u32,
        _item: WindowsRef<'_, IShellItem>,
        _destination: WindowsRef<'_, IShellItem>,
        _new_name: &PCWSTR,
    ) -> WindowsResult<()> {
        Ok(())
    }

    /// move 回调不参与删除证据，避免将普通移动误判成回收站提交。
    fn PostMoveItem(
        &self,
        _flags: u32,
        _item: WindowsRef<'_, IShellItem>,
        _destination: WindowsRef<'_, IShellItem>,
        _new_name: &PCWSTR,
        _result: HRESULT,
        _created: WindowsRef<'_, IShellItem>,
    ) -> WindowsResult<()> {
        Ok(())
    }

    /// 本 sink 不批准额外 copy，仅保持无副作用回调。
    fn PreCopyItem(
        &self,
        _flags: u32,
        _item: WindowsRef<'_, IShellItem>,
        _destination: WindowsRef<'_, IShellItem>,
        _new_name: &PCWSTR,
    ) -> WindowsResult<()> {
        Ok(())
    }

    /// copy 回调不参与删除证据，避免无关 Shell 行为污染最终判定。
    fn PostCopyItem(
        &self,
        _flags: u32,
        _item: WindowsRef<'_, IShellItem>,
        _destination: WindowsRef<'_, IShellItem>,
        _new_name: &PCWSTR,
        _result: HRESULT,
        _created: WindowsRef<'_, IShellItem>,
    ) -> WindowsResult<()> {
        Ok(())
    }

    /// DeleteItem 的最后一次 workspace 快照校验仍在 `PerformOperations` 紧前执行；
    /// PreDelete 不再读取路径，避免扩大竞态窗口。
    fn PreDeleteItem(&self, _flags: u32, _item: WindowsRef<'_, IShellItem>) -> WindowsResult<()> {
        Ok(())
    }

    /// 记录 Shell 对目标 delete 的 HRESULT 与 newly-created recycle item；只有两者
    /// 同时成立，调用方才可能在后续 aborted/source 检查后返回成功。
    fn PostDeleteItem(
        &self,
        _flags: u32,
        _item: WindowsRef<'_, IShellItem>,
        result: HRESULT,
        created: WindowsRef<'_, IShellItem>,
    ) -> WindowsResult<()> {
        self.evidence.record(result, !created.is_null());
        Ok(())
    }

    /// 本 sink 不创建新文件，仅保持无副作用回调。
    fn PreNewItem(
        &self,
        _flags: u32,
        _destination: WindowsRef<'_, IShellItem>,
        _new_name: &PCWSTR,
    ) -> WindowsResult<()> {
        Ok(())
    }

    /// new-item 回调不参与回收站证据，避免与 PostDelete newly-created 混淆。
    fn PostNewItem(
        &self,
        _flags: u32,
        _destination: WindowsRef<'_, IShellItem>,
        _new_name: &PCWSTR,
        _template_name: &PCWSTR,
        _attributes: u32,
        _result: HRESULT,
        _new_item: WindowsRef<'_, IShellItem>,
    ) -> WindowsResult<()> {
        Ok(())
    }

    /// 进度数值不跨线程或 IPC 暴露，防止形成无界事件流。
    fn UpdateProgress(&self, _total: u32, _completed: u32) -> WindowsResult<()> {
        Ok(())
    }

    /// 计时器由 Shell 自己持有，本 sink 不改变其状态。
    fn ResetTimer(&self) -> WindowsResult<()> {
        Ok(())
    }

    /// 计时器暂停不是回收站结果证据，本 sink 保持中性。
    fn PauseTimer(&self) -> WindowsResult<()> {
        Ok(())
    }

    /// 计时器恢复不是回收站结果证据，本 sink 保持中性。
    fn ResumeTimer(&self) -> WindowsResult<()> {
        Ok(())
    }
}

#[cfg(windows)]
/// 仅在本次 blocking worker 负责初始化 COM 时配对释放；若宿主已用另一 apartment
/// 初始化，继续复用现有 apartment，而不是把可恢复的 RPC_E_CHANGED_MODE 当崩溃。
pub(super) struct TrashComGuard {
    uninitialize: bool,
}

#[cfg(windows)]
impl TrashComGuard {
    /// 初始化 IFileOperation 所需 COM 边界，并把 HRESULT 收敛为脱敏文件错误。
    pub(super) fn initialize() -> Result<Self, WorkspaceError> {
        use windows::Win32::System::Com::{
            COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE, CoInitializeEx,
        };

        // 安全性：未传 reserved pointer；成功初始化由同一 blocking-worker 线程上的 guard 配对释放。
        let status =
            unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE) };
        if status.is_ok() {
            Ok(Self { uninitialize: true })
        } else {
            Err(WorkspaceError::io("trash_com", std::io::ErrorKind::Other))
        }
    }
}

#[cfg(windows)]
impl Drop for TrashComGuard {
    /// 只释放本 guard 成功取得的 COM 引用计数，避免破坏宿主线程的 apartment。
    fn drop(&mut self) {
        if self.uninitialize {
            // 安全性：initialize 与 drop 发生在同一同步 worker。
            unsafe { windows::Win32::System::Com::CoUninitialize() };
        }
    }
}

#[cfg(windows)]
/// 以源路径已消失作为最后一项本机证据；若路径仍存在或状态不可读，则结果不确定并
/// 失败关闭。检查后仍可能被外部进程重建同名路径，因此不能把它解释为持久不存在。
pub(super) fn verify_windows_trash_source_absent(path: &Path) -> Result<(), WorkspaceError> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Ok(_) => Err(WorkspaceError::io(
            "trash_source_state",
            std::io::ErrorKind::AlreadyExists,
        )),
        Err(error) => Err(WorkspaceError::io("trash_source_state", error)),
    }
}

#[cfg(windows)]
/// 返回唯一允许的 Windows 删除 flags：强制回收站、首错即停、无 UI 且不扩展到
/// connected items；集中构造便于测试锁定，防止后续误加永久删除或确认 fallback。
pub(super) fn windows_trash_operation_flags() -> windows::Win32::UI::Shell::FILEOPERATION_FLAGS {
    use windows::Win32::UI::Shell::{
        FOF_NO_CONNECTED_ELEMENTS, FOF_NO_UI, FOFX_EARLYFAILURE, FOFX_RECYCLEONDELETE,
    };

    FOF_NO_UI | FOF_NO_CONNECTED_ELEMENTS | FOFX_EARLYFAILURE | FOFX_RECYCLEONDELETE
}
