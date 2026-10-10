// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// RuntimeHost application port 的原生组合实现。

use super::bridge::RuntimeBridge;
use super::bridge::runtime_control::{RuntimeControlPort, production_runtime_control};
use super::workspace_capability::RuntimeConfigSource;
use crate::app_runtime::{
    EventSink, LaunchConfig, ManualRecoveryConfirmation, RuntimeBridgePort, RuntimeCommandError,
    RuntimePlatformPort, RuntimeRecoveryState, RuntimeStorageInfo, WorkspaceOpenInput,
    WorkspaceRuntimeSource,
};
use std::sync::Arc;

/// 同一实例冻结 LaunchConfig、事件 sink 与控制端口，application 无法在 generation 间替换它们。
pub(crate) struct NativeRuntimePlatform {
    config: LaunchConfig,
    sink: EventSink,
    runtime_control: Arc<dyn RuntimeControlPort>,
}

impl NativeRuntimePlatform {
    /// 生产组合只安装真实控制端口；测试差异必须经统一 Harness 显式注入。
    pub(crate) fn new(config: LaunchConfig, sink: EventSink) -> Self {
        Self::with_control(config, sink, production_runtime_control())
    }

    /// Harness 只替换 deadline 与故障同步端口，不能替换协议响应或路径策略。
    pub(crate) fn with_control(
        config: LaunchConfig,
        sink: EventSink,
        runtime_control: Arc<dyn RuntimeControlPort>,
    ) -> Self {
        Self {
            config,
            sink,
            runtime_control,
        }
    }
}

impl RuntimePlatformPort for NativeRuntimePlatform {
    /// 惰性构造只校验本机 LaunchConfig；共享后台身份由认证握手确认。
    fn create_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        let bridge = RuntimeBridge::new_with_control(
            self.config.clone(),
            Arc::clone(&self.sink),
            Arc::clone(&self.runtime_control),
        )?;
        Ok(Arc::new(bridge))
    }

    /// 旧窗口进程的恢复标记不属于共享后台状态；Java 在连接时报告持久任务事实。
    fn recovery_state(&self) -> RuntimeRecoveryState {
        RuntimeRecoveryState {
            required: false,
            acknowledgeable: false,
            recovery_id: None,
            revision: None,
        }
    }

    /// 共享连接从不清除旧 marker 文件；任何旧 UI 调用都不能删除用户数据。
    fn acknowledge_recovery(
        &self,
        _confirmation: &ManualRecoveryConfirmation,
    ) -> Result<RuntimeRecoveryState, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// 从启动所有者提供的内容展示存储角色；宿主从 Java 获取恢复决策并补充显示。
    fn storage_info(&self) -> RuntimeStorageInfo {
        let data_dir = &self.config.sidecar.data_dir;
        let native_image = !self
            .config
            .sidecar
            .args
            .iter()
            .any(|argument| argument == std::ffi::OsStr::new("-jar"));
        RuntimeStorageInfo {
            native_image,
            data_path: display_run_directory(data_dir),
            log_path: Some(display_run_directory(&self.config.sidecar.log_dir)),
            cache_path: None,
            last_backup: None,
            storage_recovery: None,
        }
    }

    /// 仅解析通过校验的恢复目录；调用操作系统打开器前拒绝任何被重定向的祖先目录。
    fn open_storage_backup(&self, recovery_id: &str) -> Result<(), RuntimeCommandError> {
        if recovery_id.len() != 36
            || !recovery_id
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() || byte == b'-')
        {
            return Err(RuntimeCommandError::invalid_params());
        }
        let root = self.config.sidecar.data_dir.join("db-backups");
        let backup = root.join(recovery_id);
        for path in [&self.config.sidecar.data_dir, &root, &backup] {
            let metadata =
                std::fs::symlink_metadata(path).map_err(|_| RuntimeCommandError::unavailable())?;
            if !metadata.is_dir()
                || metadata.file_type().is_symlink()
                || super::path_policy::is_reparse_point(&metadata)
            {
                return Err(RuntimeCommandError::unavailable());
            }
        }
        let root = root
            .canonicalize()
            .map_err(|_| RuntimeCommandError::unavailable())?;
        let backup = backup
            .canonicalize()
            .map_err(|_| RuntimeCommandError::unavailable())?;
        if !backup.starts_with(&root) || !backup.join("original.db").is_file() {
            return Err(RuntimeCommandError::unavailable());
        }
        tauri_plugin_opener::open_path(backup, None::<&str>)
            .map_err(|_| RuntimeCommandError::unavailable())
    }

    /// WebView 字符串先经过 canonical containment 与 trust 校验，再形成 application source。
    fn workspace_source(
        &self,
        cwd: &str,
        display_name: Option<&str>,
    ) -> Result<WorkspaceRuntimeSource, RuntimeCommandError> {
        let source = RuntimeConfigSource::from_input(WorkspaceOpenInput {
            cwd: cwd.to_owned(),
            display_name: display_name.map(ToOwned::to_owned),
        })?;
        Ok(WorkspaceRuntimeSource {
            root: source.root_path,
            display_name: source.display_name.unwrap_or_default(),
        })
    }
}

/// 仅为展示移除 Windows extended-length 前缀；所有文件操作仍使用 canonical PathBuf。
fn display_run_directory(path: &std::path::Path) -> String {
    let value = path.to_string_lossy();
    #[cfg(windows)]
    {
        if let Some(unc) = value.strip_prefix(r"\\?\UNC\") {
            return format!(r"\\{unc}");
        }
        if let Some(local) = value.strip_prefix(r"\\?\") {
            return local.to_owned();
        }
    }
    value.into_owned()
}
