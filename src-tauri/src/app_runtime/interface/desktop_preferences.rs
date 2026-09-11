// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 桌面生命周期偏好；该文件刻意独立于 App Server 配置与 WebView localStorage。

use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};
use tauri::Manager;

const CURRENT_SCHEMA_VERSION: u32 = 1;
const PREFERENCES_FILE_NAME: &str = "desktop-preferences.json";
const MAX_PREFERENCES_BYTES: usize = 64 * 1024;
static TEMP_FILE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

/// 窗口关闭后的两种用户可见生命周期；默认值保持既有收起到托盘行为。
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum CloseBehavior {
    Background,
    Exit,
}

impl Default for CloseBehavior {
    /// 新安装或偏好不可读时保留既有后台运行语义，避免意外退出丢失工作面。
    fn default() -> Self {
        Self::Background
    }
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct DesktopPreferencesFile {
    schema_version: u32,
    close_behavior: CloseBehavior,
}

/// 原生偏好唯一 owner；内存值与磁盘文件由同一把锁串行，避免多次设置互相覆盖。
pub(crate) struct DesktopPreferences {
    path: PathBuf,
    close_behavior: Arc<Mutex<CloseBehavior>>,
}

impl Clone for DesktopPreferences {
    /// 只复制受控路径与共享锁，供 blocking worker 使用同一份内存真相。
    fn clone(&self) -> Self {
        Self {
            path: self.path.clone(),
            close_behavior: Arc::clone(&self.close_behavior),
        }
    }
}

impl DesktopPreferences {
    /// 冷启动读取配置；缺失或非法内容只回退默认值，不把回退伪装成一次成功保存。
    pub(crate) fn load(path: PathBuf) -> Self {
        let close_behavior = match read_bounded_file(&path) {
            Ok(bytes) => match serde_json::from_slice::<DesktopPreferencesFile>(&bytes) {
                Ok(document) if document.schema_version == CURRENT_SCHEMA_VERSION => {
                    document.close_behavior
                }
                Ok(_) | Err(_) => {
                    tracing::warn!("desktop preferences are invalid; using defaults");
                    CloseBehavior::default()
                }
            },
            Err(error) if error.kind() == io::ErrorKind::NotFound => CloseBehavior::default(),
            Err(_) => {
                tracing::warn!("desktop preferences could not be read; using defaults");
                CloseBehavior::default()
            }
        };
        Self {
            path,
            close_behavior: Arc::new(Mutex::new(close_behavior)),
        }
    }

    /// 返回当前内存快照；读取失败时使用安全默认值而不阻断关闭事件。
    pub(crate) fn read(&self) -> CloseBehavior {
        self.close_behavior
            .lock()
            .map(|value| *value)
            .unwrap_or_default()
    }

    /// 在 blocking worker 中先完成原子持久化再更新内存，写失败时保留旧值并可重试。
    pub(crate) fn save(&self, value: CloseBehavior) -> Result<(), &'static str> {
        let mut current = self
            .close_behavior
            .lock()
            .map_err(|_| "DESKTOP_PREFERENCES_STATE_UNAVAILABLE")?;
        let document = DesktopPreferencesFile {
            schema_version: CURRENT_SCHEMA_VERSION,
            close_behavior: value,
        };
        let bytes = serde_json::to_vec_pretty(&document)
            .map_err(|_| "DESKTOP_PREFERENCES_SERIALIZE_FAILED")?;
        atomic_write(&self.path, &bytes).map_err(|_| "DESKTOP_PREFERENCES_WRITE_FAILED")?;
        *current = value;
        Ok(())
    }

    /// 返回 HomeLayout root 下的固定文件名，避免调用方重新选择配置或 AppData 根。
    pub(crate) fn file_path(home_root: &Path) -> PathBuf {
        home_root.join(PREFERENCES_FILE_NAME)
    }
}

/// 读取当前桌面关闭偏好；生命周期状态来自 Rust managed state，而非 renderer 缓存。
#[tauri::command]
pub(crate) fn ja_desktop_close_behavior_read(
    state: tauri::State<'_, DesktopPreferences>,
) -> CloseBehavior {
    state.read()
}

/// 把同步文件 I/O 移出 IPC 线程；原子写成功后才改变下一次事件使用的内存值。
#[tauri::command]
pub(crate) async fn ja_desktop_close_behavior_save(
    value: CloseBehavior,
    app: tauri::AppHandle,
) -> Result<(), &'static str> {
    let preferences = app.state::<DesktopPreferences>().inner().clone();
    tauri::async_runtime::spawn_blocking(move || preferences.save(value))
        .await
        .map_err(|_| "DESKTOP_PREFERENCES_WRITE_TASK_FAILED")?
}

/// 限制冷启动只读取小型结构化偏好，避免损坏文件消耗不受控内存。
fn read_bounded_file(path: &Path) -> io::Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let mut bytes = Vec::with_capacity(MAX_PREFERENCES_BYTES.min(1024));
    Read::by_ref(&mut file)
        .take((MAX_PREFERENCES_BYTES + 1) as u64)
        .read_to_end(&mut bytes)?;
    if bytes.len() > MAX_PREFERENCES_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "desktop preferences exceed size limit",
        ));
    }
    Ok(bytes)
}

/// 通过临时文件和同卷替换发布完整 JSON，防止进程中断留下半个偏好文件。
fn atomic_write(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let parent = path.parent().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "preferences path has no parent",
        )
    })?;
    fs::create_dir_all(parent)?;
    let sequence = TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let temp = parent.join(format!(
        ".{}.tmp-{}-{}",
        path.file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("preferences"),
        std::process::id(),
        sequence
    ));
    let result = (|| {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        atomic_replace(&temp, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}

#[cfg(windows)]
/// Windows 使用 MoveFileEx 原子覆盖，并要求写穿磁盘缓存。
fn atomic_replace(temp: &Path, target: &Path) -> io::Result<()> {
    use std::os::windows::ffi::OsStrExt;

    const MOVEFILE_REPLACE_EXISTING: u32 = 0x1;
    const MOVEFILE_WRITE_THROUGH: u32 = 0x8;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn MoveFileExW(existing: *const u16, replacement: *const u16, flags: u32) -> i32;
    }
    let source = temp
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();
    let destination = target
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect::<Vec<_>>();
    // SAFETY: buffers are NUL-terminated and live for the synchronous Win32 call.
    if unsafe {
        MoveFileExW(
            source.as_ptr(),
            destination.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    } == 0
    {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(not(windows))]
/// Unix 文件系统的 rename 在同一目录内原子替换目标文件。
fn atomic_replace(temp: &Path, target: &Path) -> io::Result<()> {
    fs::rename(temp, target)
}
