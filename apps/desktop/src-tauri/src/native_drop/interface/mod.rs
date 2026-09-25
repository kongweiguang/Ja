// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Windows WebView2 晚创建渲染窗口的原生文件拖放桥。
//!
//! Wry 只在 controller 创建时枚举一次 child HWND；WebView2 的实际命中面可能在导航后
//! 才出现。本模块在页面完成后重新发现该命中面，并继续复用 crate 根的脱敏拖放投影。

use std::cell::RefCell;
use std::collections::HashSet;
use std::ffi::{OsString, c_void};
use std::fmt::{Display, Formatter};
use std::os::windows::ffi::OsStringExt;
use std::path::{Component, Path, PathBuf, Prefix};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::ThreadId;
use std::time::Duration;

use webview2_com::Microsoft::Web::WebView2::Win32::ICoreWebView2Controller;
use windows::Win32::Foundation::{
    DRAGDROP_E_INVALIDHWND, DRAGDROP_E_NOTREGISTERED, HWND, LPARAM, POINT, POINTL,
};
use windows::Win32::Graphics::Gdi::ScreenToClient;
use windows::Win32::System::Com::{
    DVASPECT_CONTENT, FORMATETC, IDataObject, STGMEDIUM, TYMED_HGLOBAL,
};
use windows::Win32::System::Ole::{
    CF_HDROP, DROPEFFECT, DROPEFFECT_COPY, DROPEFFECT_NONE, IDropTarget, IDropTarget_Impl,
    RegisterDragDrop, ReleaseStgMedium, RevokeDragDrop,
};
use windows::Win32::System::SystemServices::MODIFIERKEYS_FLAGS;
use windows::Win32::UI::HiDpi::GetDpiForWindow;
use windows::Win32::UI::Shell::{DragQueryFileW, HDROP};
use windows::Win32::UI::WindowsAndMessaging::{EnumChildWindows, GetClassNameW};
use windows::core::{BOOL, implement};

const RENDER_TARGET_CLASS: &str = "Chrome_RenderWidgetHostHWND";
const MAX_RENDER_TARGETS: usize = 8;
const MAX_DROP_ITEMS: usize = 32;
const MAX_PATH_UTF16_UNITS: usize = 32_767;
const MAX_TOTAL_PATH_UTF16_UNITS: usize = MAX_DROP_ITEMS * MAX_PATH_UTF16_UNITS;
const REFRESH_DELAYS: [Duration; 3] = [
    Duration::ZERO,
    Duration::from_millis(40),
    Duration::from_millis(160),
];

static NEXT_HOST_ID: AtomicU64 = AtomicU64::new(1);

type NativeDropListener = Arc<dyn Fn(tauri::DragDropEvent, f64) -> bool + Send + Sync>;

thread_local! {
    /// COM drop target 只能在创建它的 WebView UI apartment 中持有和撤销，不能进入
    /// Tauri managed state 的跨线程 `Send + Sync` 边界。
    static REGISTERED_TARGETS: RefCell<Vec<RegisteredTarget>> = const { RefCell::new(Vec::new()) };
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum NativeDropTargetErrorCode {
    ControllerUnavailable,
    RenderSurfaceUnavailable,
    RegistrationFailed,
    StateUnavailable,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct NativeDropTargetError {
    pub(crate) code: NativeDropTargetErrorCode,
}

impl NativeDropTargetError {
    /// 错误只保留固定分类，避免窗口句柄、文件路径或 COM 诊断细节进入日志。
    fn new(code: NativeDropTargetErrorCode) -> Self {
        Self { code }
    }
}

impl Display for NativeDropTargetError {
    /// 用户可见诊断只表达稳定错误类，详细系统值不跨越日志边界。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "native drop target error: {:?}", self.code)
    }
}

impl std::error::Error for NativeDropTargetError {}

#[derive(Debug, Eq, PartialEq)]
pub(crate) struct TargetRefreshPlan {
    pub(crate) revoke: Vec<isize>,
    pub(crate) install: Vec<isize>,
}

/// 对 reload 前后的 HWND 集合做确定性差量，保留稳定目标，避免重复注册和 target 累积。
pub(crate) fn plan_target_refresh(current: &[isize], discovered: &[isize]) -> TargetRefreshPlan {
    let current = current.iter().copied().collect::<HashSet<_>>();
    let discovered = discovered.iter().copied().collect::<HashSet<_>>();
    let mut revoke = current.difference(&discovered).copied().collect::<Vec<_>>();
    let mut install = discovered.difference(&current).copied().collect::<Vec<_>>();
    revoke.sort_unstable();
    install.sort_unstable();
    TargetRefreshPlan { revoke, install }
}

/// 只接纳 WebView2 的真实渲染命中面，按 Win32 枚举顺序去重并设置数量上限。
pub(crate) fn select_render_target_ids<I>(windows: I) -> Vec<isize>
where
    I: IntoIterator<Item = (isize, String)>,
{
    let mut seen = HashSet::new();
    windows
        .into_iter()
        .filter_map(|(id, class_name)| {
            (id != 0 && class_name == RENDER_TARGET_CLASS && seen.insert(id)).then_some(id)
        })
        .take(MAX_RENDER_TARGETS)
        .collect()
}

struct RegisteredTarget {
    host_id: u64,
    hwnd: isize,
    _target: IDropTarget,
}

struct NativeDropLifecycle {
    shutdown: AtomicBool,
    installed: AtomicUsize,
    ui_thread: Mutex<Option<ThreadId>>,
}

/// managed state 只保存可跨线程的生命周期事实；apartment-bound COM 引用留在 UI thread-local。
#[derive(Clone)]
pub(crate) struct NativeDropTargetHost {
    id: u64,
    lifecycle: Arc<NativeDropLifecycle>,
}

impl Default for NativeDropTargetHost {
    /// 每个 Tauri composition root 使用独立 owner id，隔离同进程测试或未来多实例宿主。
    fn default() -> Self {
        Self {
            id: NEXT_HOST_ID.fetch_add(1, Ordering::Relaxed),
            lifecycle: Arc::new(NativeDropLifecycle {
                shutdown: AtomicBool::new(false),
                installed: AtomicUsize::new(0),
                ui_thread: Mutex::new(None),
            }),
        }
    }
}

impl NativeDropTargetHost {
    /// 首次 refresh 固定 WebView UI thread；后续 COM 注册与撤销必须留在同一 apartment。
    pub(crate) fn claim_ui_thread(&self) -> Result<(), NativeDropTargetError> {
        let current = std::thread::current().id();
        let mut owner =
            self.lifecycle.ui_thread.lock().map_err(|_| {
                NativeDropTargetError::new(NativeDropTargetErrorCode::StateUnavailable)
            })?;
        match owner.as_ref() {
            Some(owner) if *owner != current => Err(NativeDropTargetError::new(
                NativeDropTargetErrorCode::StateUnavailable,
            )),
            Some(_) => Ok(()),
            None => {
                *owner = Some(current);
                Ok(())
            }
        }
    }

    /// 退出只核对已经建立的 apartment；从未安装目标时无需人为绑定退出调用线程。
    pub(crate) fn require_claimed_ui_thread(&self) -> Result<(), NativeDropTargetError> {
        let current = std::thread::current().id();
        let owner =
            self.lifecycle.ui_thread.lock().map_err(|_| {
                NativeDropTargetError::new(NativeDropTargetErrorCode::StateUnavailable)
            })?;
        match owner.as_ref() {
            Some(owner) if *owner != current => Err(NativeDropTargetError::new(
                NativeDropTargetErrorCode::StateUnavailable,
            )),
            _ => Ok(()),
        }
    }

    /// 在 WebView UI apartment 中差量替换晚创建的渲染目标；路径只进入事件 callback，
    /// managed state 和日志都不保存文件事实。
    fn refresh(
        &self,
        parent: HWND,
        listener: NativeDropListener,
    ) -> Result<usize, NativeDropTargetError> {
        if self.lifecycle.shutdown.load(Ordering::Acquire) {
            return Err(NativeDropTargetError::new(
                NativeDropTargetErrorCode::StateUnavailable,
            ));
        }
        self.claim_ui_thread()?;
        let discovered = enumerate_render_targets(parent)?;
        if discovered.is_empty() {
            return Err(NativeDropTargetError::new(
                NativeDropTargetErrorCode::RenderSurfaceUnavailable,
            ));
        }
        REGISTERED_TARGETS.with(|registered| {
            let mut registered = registered.try_borrow_mut().map_err(|_| {
                NativeDropTargetError::new(NativeDropTargetErrorCode::StateUnavailable)
            })?;
            let current = registered
                .iter()
                .filter(|entry| entry.host_id == self.id)
                .map(|entry| entry.hwnd)
                .collect::<Vec<_>>();
            let plan = plan_target_refresh(&current, &discovered);
            let mut failed = false;
            for hwnd in plan.revoke {
                if revoke_target(hwnd).is_ok() {
                    registered.retain(|entry| !(entry.host_id == self.id && entry.hwnd == hwnd));
                } else {
                    failed = true;
                }
            }
            for hwnd in plan.install {
                let target: IDropTarget = NativeDropTarget::new(hwnd, listener.clone()).into();
                // Wry may already own this dynamically-created child. Revoke first so Ja becomes
                // the single target and can guarantee STGMEDIUM release and path redaction.
                if revoke_target(hwnd).is_err()
                    || unsafe { RegisterDragDrop(hwnd_from_id(hwnd), &target) }.is_err()
                {
                    failed = true;
                } else {
                    registered.push(RegisteredTarget {
                        host_id: self.id,
                        hwnd,
                        _target: target,
                    });
                }
            }
            let count = registered
                .iter()
                .filter(|entry| entry.host_id == self.id)
                .count();
            self.lifecycle.installed.store(count, Ordering::Release);
            if failed || count == 0 {
                Err(NativeDropTargetError::new(
                    NativeDropTargetErrorCode::RegistrationFailed,
                ))
            } else {
                Ok(count)
            }
        })
    }

    /// 在退出事件所在 UI apartment 撤销所有目标并永久关闭后续 refresh，避免窗口销毁后
    /// 延迟 page-load callback 再次注册 COM target。
    pub(crate) fn shutdown(&self) -> Result<(), NativeDropTargetError> {
        self.lifecycle.shutdown.store(true, Ordering::Release);
        self.require_claimed_ui_thread()?;
        REGISTERED_TARGETS.with(|registered| {
            let mut registered = registered.try_borrow_mut().map_err(|_| {
                NativeDropTargetError::new(NativeDropTargetErrorCode::StateUnavailable)
            })?;
            let mut failed = false;
            let mut index = 0;
            while index < registered.len() {
                if registered[index].host_id != self.id {
                    index += 1;
                    continue;
                }
                if revoke_target(registered[index].hwnd).is_ok() {
                    registered.remove(index);
                } else {
                    failed = true;
                    index += 1;
                }
            }
            let count = registered
                .iter()
                .filter(|entry| entry.host_id == self.id)
                .count();
            self.lifecycle.installed.store(count, Ordering::Release);
            if failed {
                Err(NativeDropTargetError::new(
                    NativeDropTargetErrorCode::RegistrationFailed,
                ))
            } else {
                Ok(())
            }
        })
    }

    /// 退出门禁同时核对 admission 已关闭且 UI apartment 不再持有已注册目标。
    pub(crate) fn is_shutdown_complete(&self) -> bool {
        self.lifecycle.shutdown.load(Ordering::Acquire)
            && self.lifecycle.installed.load(Ordering::Acquire) == 0
    }
}

/// PageLoad Finished 后做三次短间隔刷新，覆盖 WebView2 renderer HWND 稍晚于导航完成出现的时序。
pub(crate) fn schedule_main_refresh(
    webview: tauri::Webview,
    app: tauri::AppHandle,
    host: NativeDropTargetHost,
) {
    tauri::async_runtime::spawn(async move {
        for (index, delay) in REFRESH_DELAYS.into_iter().enumerate() {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            if host.lifecycle.shutdown.load(Ordering::Acquire) {
                break;
            }
            let callback_app = app.clone();
            let callback_host = host.clone();
            let is_last = index + 1 == REFRESH_DELAYS.len();
            if webview
                .with_webview(move |platform_webview| {
                    let controller = platform_webview.controller();
                    let listener_app = callback_app.clone();
                    let listener = Arc::new(move |event, scale_factor| {
                        crate::handle_native_drag_drop_at_scale(
                            &listener_app,
                            crate::MAIN_WINDOW_LABEL,
                            &event,
                            scale_factor,
                        )
                    });
                    match refresh_controller(&callback_host, &controller, listener) {
                        Ok(_) => {}
                        Err(error) if is_last => tracing::warn!(
                            code = ?error.code,
                            "native drop render target could not be installed"
                        ),
                        Err(error) => tracing::debug!(
                            code = ?error.code,
                            "native drop render target is not ready"
                        ),
                    }
                })
                .is_err()
            {
                tracing::warn!("native drop refresh could not enter the WebView UI thread");
                break;
            }
        }
    });
}

/// controller 只在 `with_webview` closure 内使用；ParentWindow 不会越过 apartment 边界。
fn refresh_controller(
    host: &NativeDropTargetHost,
    controller: &ICoreWebView2Controller,
    listener: NativeDropListener,
) -> Result<usize, NativeDropTargetError> {
    let mut parent = HWND::default();
    unsafe { controller.ParentWindow(&mut parent) }.map_err(|_| {
        NativeDropTargetError::new(NativeDropTargetErrorCode::ControllerUnavailable)
    })?;
    if parent.0.is_null() {
        return Err(NativeDropTargetError::new(
            NativeDropTargetErrorCode::ControllerUnavailable,
        ));
    }
    host.refresh(parent, listener)
}

/// 枚举结果先转为纯值再筛选，Win32 callback 不持有 Rust trait object 或 COM 引用。
fn enumerate_render_targets(parent: HWND) -> Result<Vec<isize>, NativeDropTargetError> {
    let mut windows = Vec::<(isize, String)>::new();
    let parameter = LPARAM((&mut windows as *mut Vec<(isize, String)>) as isize);
    let result = unsafe { EnumChildWindows(Some(parent), Some(enumerate_child), parameter) };
    if !result.as_bool() {
        return Err(NativeDropTargetError::new(
            NativeDropTargetErrorCode::RenderSurfaceUnavailable,
        ));
    }
    Ok(select_render_target_ids(windows))
}

/// Win32 枚举 callback 只复制固定长度 class name 和数值 HWND；返回后不保留借用。
unsafe extern "system" fn enumerate_child(hwnd: HWND, parameter: LPARAM) -> BOOL {
    let windows = unsafe { &mut *(parameter.0 as *mut Vec<(isize, String)>) };
    let mut class_name = [0u16; 128];
    let length = unsafe { GetClassNameW(hwnd, &mut class_name) };
    if length > 0 {
        windows.push((
            hwnd.0 as isize,
            String::from_utf16_lossy(&class_name[..length as usize]),
        ));
    }
    true.into()
}

/// 句柄只在 UI thread-local 中以整数保存，避免 HWND 的原始指针进入 managed state。
fn hwnd_from_id(id: isize) -> HWND {
    HWND(id as *mut c_void)
}

/// 未注册或已销毁句柄视为幂等成功；其它 OLE 失败必须保留 target 引用供后续重试。
fn revoke_target(id: isize) -> Result<(), NativeDropTargetError> {
    match unsafe { RevokeDragDrop(hwnd_from_id(id)) } {
        Ok(()) => Ok(()),
        Err(error)
            if error.code() == DRAGDROP_E_NOTREGISTERED
                || error.code() == DRAGDROP_E_INVALIDHWND =>
        {
            Ok(())
        }
        Err(_) => Err(NativeDropTargetError::new(
            NativeDropTargetErrorCode::RegistrationFailed,
        )),
    }
}

struct StorageMedium(STGMEDIUM);

impl Drop for StorageMedium {
    /// `IDataObject::GetData` 的所有成功路径统一释放 STGMEDIUM，禁止把 HDROP 误当 WM_DROPFILES
    /// 句柄调用 `DragFinish` 而造成泄漏或双重释放。
    fn drop(&mut self) {
        unsafe { ReleaseStgMedium(&mut self.0) };
    }
}

#[derive(Clone, Copy)]
struct DragState {
    enter_is_valid: bool,
    cursor_effect: DROPEFFECT,
    scale_factor: f64,
}

#[implement(IDropTarget)]
struct NativeDropTarget {
    hwnd: isize,
    listener: NativeDropListener,
    state: Mutex<DragState>,
}

impl NativeDropTarget {
    /// 每个渲染 HWND 拥有独立拖放状态，避免多命中面之间共享 enter/leave 事实。
    fn new(hwnd: isize, listener: NativeDropListener) -> Self {
        Self {
            hwnd,
            listener,
            state: Mutex::new(DragState {
                enter_is_valid: false,
                cursor_effect: DROPEFFECT_NONE,
                scale_factor: 0.0,
            }),
        }
    }

    /// 只读取 CF_HDROP；数量和 UTF-16 分配均有上限，HTML、URL 和其它格式直接拒绝。
    unsafe fn read_paths(data_object: windows_core::Ref<'_, IDataObject>) -> Option<Vec<PathBuf>> {
        let object = data_object.as_ref()?;
        let format = FORMATETC {
            cfFormat: CF_HDROP.0,
            ptd: ptr::null_mut(),
            dwAspect: DVASPECT_CONTENT.0,
            lindex: -1,
            tymed: TYMED_HGLOBAL.0 as u32,
        };
        let medium = StorageMedium(unsafe { object.GetData(&format) }.ok()?);
        let hdrop = HDROP(unsafe { medium.0.u.hGlobal.0 });
        let item_count = unsafe { DragQueryFileW(hdrop, u32::MAX, None) } as usize;
        if item_count == 0 || item_count > MAX_DROP_ITEMS {
            return None;
        }
        let mut total_units = 0usize;
        let mut paths = Vec::with_capacity(item_count);
        for index in 0..item_count {
            let character_count = unsafe { DragQueryFileW(hdrop, index as u32, None) } as usize;
            if character_count == 0 || character_count > MAX_PATH_UTF16_UNITS {
                return None;
            }
            total_units = total_units.checked_add(character_count + 1)?;
            if total_units > MAX_TOTAL_PATH_UTF16_UNITS {
                return None;
            }
            let mut buffer = vec![0u16; character_count + 1];
            let written =
                unsafe { DragQueryFileW(hdrop, index as u32, Some(&mut buffer)) } as usize;
            if written != character_count || buffer[character_count] != 0 {
                return None;
            }
            let path = PathBuf::from(OsString::from_wide(&buffer[..character_count]));
            let Some(path) = normalize_shell_drop_path(&path) else {
                tracing::warn!(
                    target: "ja.attachment.drop.unsupported_namespace",
                    "native drop path namespace is unsupported"
                );
                return None;
            };
            if !path.is_absolute() {
                tracing::warn!(
                    target: "ja.attachment.drop.relative_path",
                    "native drop path is relative"
                );
            }
            paths.push(path);
        }
        Some(paths)
    }

    /// COM 给出屏幕物理坐标；统一转换到当前渲染 HWND 客户区，保持现有 DPI 投影合同。
    fn client_position(&self, point: &POINTL) -> Option<tauri::PhysicalPosition<f64>> {
        let mut point = POINT {
            x: point.x,
            y: point.y,
        };
        unsafe { ScreenToClient(hwnd_from_id(self.hwnd), &mut point) }
            .as_bool()
            .then_some((point.x as f64, point.y as f64).into())
    }

    /// OLE 回调发生在 WebView UI apartment，直接从命中 HWND 读取实时 DPI，避免在 COM
    /// 回调内同步反查 Tauri event loop；窗口跨显示器后也不会沿用 page-load 时的旧比例。
    fn scale_factor(&self) -> Option<f64> {
        scale_factor_from_dpi(unsafe { GetDpiForWindow(hwnd_from_id(self.hwnd)) })
    }

    /// effect 指针由 OLE 提供；防御空指针使异常调用方也只能得到无操作结果。
    fn set_effect(effect: *mut DROPEFFECT, value: DROPEFFECT) {
        if !effect.is_null() {
            unsafe { *effect = value };
        }
    }
}

/// Win32 以 96 DPI 为 1x；零值表示系统查询失败，必须失败关闭而不能伪造 1x 坐标。
pub(crate) fn scale_factor_from_dpi(dpi: u32) -> Option<f64> {
    (dpi > 0).then_some(f64::from(dpi) / 96.0)
}

/// Explorer 的 `CF_HDROP` 可使用 Win32 verbatim disk/UNC spelling；只在 Shell 解码边界
/// 去掉这两种等价前缀，device namespace 与相对路径仍交给统一 ingress 校验拒绝。
pub(crate) fn normalize_shell_drop_path(path: &Path) -> Option<PathBuf> {
    let mut components = path.components();
    let prefix = match components.next()? {
        Component::Prefix(prefix) => prefix.kind(),
        _ => return Some(path.to_path_buf()),
    };
    let mut normalized = match prefix {
        Prefix::VerbatimDisk(drive) => PathBuf::from(format!("{}:\\", char::from(drive))),
        Prefix::VerbatimUNC(server, share) => {
            let mut value = PathBuf::from(r"\\");
            value.push(server);
            value.push(share);
            value
        }
        Prefix::Disk(_) | Prefix::UNC(_, _) => return Some(path.to_path_buf()),
        _ => return None,
    };
    for component in components {
        match component {
            Component::RootDir => {}
            Component::Normal(value) => normalized.push(value),
            Component::CurDir | Component::ParentDir | Component::Prefix(_) => return None,
        }
    }
    Some(normalized)
}

#[allow(non_snake_case)]
impl IDropTarget_Impl for NativeDropTarget_Impl {
    /// Enter 只在 CF_HDROP、预算、坐标和 renderer 投影全部接纳后显示 Copy 光标。
    fn DragEnter(
        &self,
        data_object: windows_core::Ref<'_, IDataObject>,
        _key_state: MODIFIERKEYS_FLAGS,
        point: &POINTL,
        effect: *mut DROPEFFECT,
    ) -> windows::core::Result<()> {
        let paths = unsafe { NativeDropTarget::read_paths(data_object) };
        let position = self.client_position(point);
        let scale_factor = self.scale_factor();
        let accepted = match (paths, position, scale_factor) {
            (Some(paths), Some(position), Some(scale_factor)) => (self.listener)(
                tauri::DragDropEvent::Enter { paths, position },
                scale_factor,
            ),
            _ => false,
        };
        let cursor_effect = if accepted {
            DROPEFFECT_COPY
        } else {
            DROPEFFECT_NONE
        };
        if let Ok(mut state) = self.state.lock() {
            state.enter_is_valid = accepted;
            state.cursor_effect = cursor_effect;
            state.scale_factor = scale_factor.unwrap_or(0.0);
        }
        NativeDropTarget::set_effect(effect, cursor_effect);
        Ok(())
    }

    /// Over 沿用 Enter 的接纳结果，锁中毒时 fail-closed 并清除系统光标效果。
    fn DragOver(
        &self,
        _key_state: MODIFIERKEYS_FLAGS,
        point: &POINTL,
        effect: *mut DROPEFFECT,
    ) -> windows::core::Result<()> {
        let state = self.state.lock().map(|state| *state).unwrap_or(DragState {
            enter_is_valid: false,
            cursor_effect: DROPEFFECT_NONE,
            scale_factor: 0.0,
        });
        let scale_factor = self.scale_factor();
        let accepted = state.enter_is_valid
            && self.client_position(point).is_some_and(|position| {
                scale_factor.is_some_and(|scale_factor| {
                    (self.listener)(tauri::DragDropEvent::Over { position }, scale_factor)
                })
            });
        if state.enter_is_valid && !accepted {
            let _ = (self.listener)(tauri::DragDropEvent::Leave, state.scale_factor);
            if let Ok(mut state) = self.state.lock() {
                state.enter_is_valid = false;
                state.cursor_effect = DROPEFFECT_NONE;
            }
        }
        NativeDropTarget::set_effect(
            effect,
            if accepted {
                state.cursor_effect
            } else {
                DROPEFFECT_NONE
            },
        );
        Ok(())
    }

    /// Leave 只为已接纳的序列发出一次，并立即清空目标局部状态。
    fn DragLeave(&self) -> windows::core::Result<()> {
        let (accepted, scale_factor) = self
            .state
            .lock()
            .map(|mut state| {
                let accepted = state.enter_is_valid;
                let scale_factor = state.scale_factor;
                state.enter_is_valid = false;
                state.cursor_effect = DROPEFFECT_NONE;
                state.scale_factor = 0.0;
                (accepted, scale_factor)
            })
            .unwrap_or((false, 0.0));
        if accepted {
            let _ = (self.listener)(tauri::DragDropEvent::Leave, scale_factor);
        }
        Ok(())
    }

    /// Drop 重新读取 IDataObject，只有本次仍满足预算且 token 投影成功才返回 Copy。
    fn Drop(
        &self,
        data_object: windows_core::Ref<'_, IDataObject>,
        _key_state: MODIFIERKEYS_FLAGS,
        point: &POINTL,
        effect: *mut DROPEFFECT,
    ) -> windows::core::Result<()> {
        let (entered, entered_scale_factor) = self
            .state
            .lock()
            .map(|mut state| {
                let entered = state.enter_is_valid;
                let scale_factor = state.scale_factor;
                state.enter_is_valid = false;
                state.cursor_effect = DROPEFFECT_NONE;
                state.scale_factor = 0.0;
                (entered, scale_factor)
            })
            .unwrap_or((false, 0.0));
        let accepted = if entered {
            match (
                unsafe { NativeDropTarget::read_paths(data_object) },
                self.client_position(point),
                self.scale_factor(),
            ) {
                (Some(paths), Some(position), Some(scale_factor)) => {
                    (self.listener)(tauri::DragDropEvent::Drop { paths, position }, scale_factor)
                }
                _ => {
                    let _ = (self.listener)(tauri::DragDropEvent::Leave, entered_scale_factor);
                    false
                }
            }
        } else {
            false
        };
        NativeDropTarget::set_effect(
            effect,
            if accepted {
                DROPEFFECT_COPY
            } else {
                DROPEFFECT_NONE
            },
        );
        Ok(())
    }
}
