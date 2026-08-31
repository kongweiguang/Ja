// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// RuntimeHost application port 的原生组合实现。

use super::bridge::RuntimeBridge;
use super::bridge::runtime_control::{RuntimeControlPort, production_runtime_control};
use super::workspace_capability::RuntimeConfigSource;
use crate::app_runtime::{
    EventSink, LaunchConfig, ManualRecoveryConfirmation, RuntimeBridgePort, RuntimeCommandError,
    RuntimePlatformPort, RuntimeRecoveryState, RuntimeStorageInfo, WorkspaceOpenInput,
    WorkspaceRuntimeSource, recovery_state,
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
    /// 每次惰性构造都先走 RuntimeBridge 的恢复门和 LaunchConfig 校验，失败不会暴露半初始化 owner。
    fn create_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        let bridge = RuntimeBridge::new_with_control(
            self.config.clone(),
            Arc::clone(&self.sink),
            Arc::clone(&self.runtime_control),
        )?;
        Ok(Arc::new(bridge))
    }

    /// 恢复状态只读取固定 run directory，application 不接触 marker 路径或文件格式。
    fn recovery_state(&self) -> RuntimeRecoveryState {
        recovery_state(&self.config.sidecar.run_dir)
    }

    /// CAS 确认委托给唯一恢复存储实现，禁止 application 自行删除 marker。
    fn acknowledge_recovery(
        &self,
        confirmation: &ManualRecoveryConfirmation,
    ) -> Result<RuntimeRecoveryState, RuntimeCommandError> {
        self.config.acknowledge_manual_recovery(confirmation)?;
        Ok(self.recovery_state())
    }

    /// 只投影用户可见目录事实；executable、参数、环境和完整原生路径诊断不离开基础设施层。
    fn storage_info(&self) -> RuntimeStorageInfo {
        let run_dir = &self.config.sidecar.run_dir;
        let native_image = !self
            .config
            .sidecar
            .args
            .iter()
            .any(|argument| argument == std::ffi::OsStr::new("-jar"));
        RuntimeStorageInfo {
            native_image,
            data_path: display_run_directory(run_dir),
            log_path: None,
            cache_path: None,
            last_backup: run_dir
                .join("ja.sqlite.bak")
                .is_file()
                .then(|| "已生成本地备份".to_owned()),
        }
    }

    /// WebView 字符串先经过 canonical containment 与 trust 校验，再形成 application source。
    fn workspace_source(
        &self,
        cwd: &str,
        display_name: Option<&str>,
        trust: &str,
    ) -> Result<WorkspaceRuntimeSource, RuntimeCommandError> {
        let source = RuntimeConfigSource::from_input(WorkspaceOpenInput {
            cwd: cwd.to_owned(),
            display_name: display_name.map(ToOwned::to_owned),
            trust: trust.to_owned(),
        })?;
        Ok(WorkspaceRuntimeSource {
            root: source.root_path,
            display_name: source.display_name.unwrap_or_default(),
            trust: source.trust,
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
