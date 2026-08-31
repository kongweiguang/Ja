// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! RuntimeHost 的内部测试控制口。
//!
//! 测试能力集中在 Harness，生产 `RuntimeHost` 不公开构造捷径或协议旁路。

use crate::app_runtime::infrastructure::bridge::RuntimeBridge;
use crate::app_runtime::infrastructure::bridge::runtime_control::{
    RuntimeControlPhase, RuntimeControlPort, default_exit_timeout, production_runtime_control,
};
use crate::app_runtime::interface::history_model::{
    HistoryMethod, ThreadCreateInput, parse_thread, request_history, validate_thread_create,
};
use crate::app_runtime::{
    ConfigurationPatchParams, ConfigurationReadParams, ConfigurationReplaceParams,
    ConfigurationRequest, ConfigurationResetParams, ConfigurationResponse, CredentialDeleteParams,
    CredentialSetParams, EventSink, LaunchConfig, NativeRuntimePlatform, RuntimeCommandError,
    RuntimeHost,
};
use ja_runtime::app_server_process::{SidecarConfig, SidecarSupervisor};
use serde_json::Value;
use std::ffi::OsString;
use std::path::PathBuf;
use std::sync::atomic::AtomicUsize;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, TrySendError};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// 保存 queue Harness 的确定性同步状态；所有字段均由单个测试实例独占。
struct QueueControlGate {
    released: AtomicBool,
    armed: AtomicBool,
    admitted: AtomicU64,
    processed: AtomicU64,
    lock: Mutex<()>,
    wake: Condvar,
}

impl QueueControlGate {
    /// 创建关闭的 gate，确保测试可以先填满队列再允许 actor 消费。
    fn new() -> Self {
        Self {
            released: AtomicBool::new(false),
            armed: AtomicBool::new(false),
            admitted: AtomicU64::new(0),
            processed: AtomicU64::new(0),
            lock: Mutex::new(()),
            wake: Condvar::new(),
        }
    }

    /// 在 actor 线程停到精确栅栏，并用条件变量等待释放，避免任意 sleep。
    fn wait(&self) {
        self.armed.store(true, Ordering::Release);
        self.wake.notify_all();
        let mut guard = self
            .lock
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        while !self.released.load(Ordering::Acquire) {
            guard = self
                .wake
                .wait(guard)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
        }
    }

    /// 等待 actor 已进入栅栏；deadline 到期后失败，不延长测试预算。
    fn wait_until_armed(&self, deadline: Instant) -> bool {
        let mut guard = self
            .lock
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        while !self.armed.load(Ordering::Acquire) {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return false;
            }
            let (next, timeout) = self
                .wake
                .wait_timeout(guard, remaining)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            guard = next;
            if timeout.timed_out() && !self.armed.load(Ordering::Acquire) {
                return false;
            }
        }
        true
    }

    /// 幂等释放 actor；原子状态先发布，再唤醒等待线程。
    fn release(&self) {
        self.released.store(true, Ordering::Release);
        self.wake.notify_all();
    }

    /// 记录一个 command 已成功进入 bounded queue，并唤醒等待容量事实的测试线程。
    fn mark_admitted(&self) {
        self.admitted.fetch_add(1, Ordering::AcqRel);
        self.wake.notify_all();
    }

    /// 等待准入数达到预期；只有真实 try_send 成功才推进计数。
    fn wait_until_admitted(&self, expected: u64, deadline: Instant) -> bool {
        self.wait_until_count(&self.admitted, expected, deadline)
    }

    /// 记录一个已消费 probe，并唤醒等待排空事实的测试线程。
    fn mark_processed(&self) {
        self.processed.fetch_add(1, Ordering::AcqRel);
        self.wake.notify_all();
    }

    /// 等待 probe 数达到预期；超时不推断 actor 已排空。
    fn wait_until_processed(&self, expected: u64, deadline: Instant) -> bool {
        self.wait_until_count(&self.processed, expected, deadline)
    }

    /// 复用同一条件变量等待单调计数，避免准入与处理阶段维护两套竞态循环。
    fn wait_until_count(&self, counter: &AtomicU64, expected: u64, deadline: Instant) -> bool {
        let mut guard = self
            .lock
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        while counter.load(Ordering::Acquire) < expected {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return false;
            }
            let (next, timeout) = self
                .wake
                .wait_timeout(guard, remaining)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            guard = next;
            if timeout.timed_out() && counter.load(Ordering::Acquire) < expected {
                return false;
            }
        }
        true
    }
}

/// 保存脱敏生命周期标记；仅测试读取顺序，不保存 RPC 参数、路径或进程输出。
struct RuntimeControlTrace {
    events: Mutex<Vec<String>>,
    wake: Condvar,
}

impl RuntimeControlTrace {
    /// 创建空 trace；每个 Harness 独占实例，避免并发测试互相污染。
    fn new() -> Self {
        Self {
            events: Mutex::new(Vec::new()),
            wake: Condvar::new(),
        }
    }

    /// 在线性化点追加固定内部标记，并唤醒等待该事实的测试线程。
    fn record(&self, event: &str) {
        self.events
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .push(event.to_owned());
        self.wake.notify_all();
    }

    /// 等待固定标记出现；使用绝对 deadline，避免任意 sleep 和重复延长预算。
    fn wait_for(&self, event: &str, deadline: Instant) -> bool {
        let mut events = self
            .events
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        loop {
            if events.iter().any(|candidate| candidate == event) {
                return true;
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return false;
            }
            let (next, timeout) = self
                .wake
                .wait_timeout(events, remaining)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            events = next;
            if timeout.timed_out() && !events.iter().any(|candidate| candidate == event) {
                return false;
            }
        }
    }

    /// 返回当前有序快照；clone 只发生在测试断言和失败诊断中。
    fn events(&self) -> Vec<String> {
        self.events
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    }
}

/// 统一实现 queue、phase、deadline 与 cleanup 故障注入，生产 actor 只看到窄 trait。
struct HarnessRuntimeControl {
    exit_timeout: Duration,
    queue: Option<Arc<QueueControlGate>>,
    start_failure: Option<Arc<QueueControlGate>>,
    trace: Option<Arc<RuntimeControlTrace>>,
    shutdown_failure_injector: Option<Arc<AtomicUsize>>,
}

impl RuntimeControlPort for HarnessRuntimeControl {
    /// 返回实例级 deadline，确保并发 Harness 不共享退出预算。
    fn exit_timeout(&self) -> Duration {
        self.exit_timeout
    }

    /// 在真实 supervisor 清理前消费一次实例级故障配额；失败时保留同一 owner，
    /// 后续显式 retry 仍会进入生产清理路径，且不要求 `ja-runtime` 公开测试 API。
    fn shutdown_supervisor_until(
        &self,
        supervisor: &mut SidecarSupervisor,
        deadline: Instant,
    ) -> Result<(), RuntimeCommandError> {
        if let Some(injector) = &self.shutdown_failure_injector
            && injector
                .fetch_update(Ordering::AcqRel, Ordering::Acquire, |remaining| {
                    remaining.checked_sub(1)
                })
                .is_ok()
        {
            return Err(RuntimeCommandError::shutdown_timeout());
        }
        supervisor
            .shutdown_until(deadline)
            .map_err(|error| RuntimeCommandError::from_process(&error))
    }

    /// queue Harness 在 actor 消费前等待；exit Harness 没有 queue gate，因此直接返回。
    fn before_actor_loop(&self) {
        if let Some(queue) = &self.queue {
            queue.wait();
        }
    }

    /// 只在 queue Harness 记录成功准入，普通 Host Harness 不分配计数状态。
    fn command_admitted(&self) {
        if let Some(queue) = &self.queue {
            queue.mark_admitted();
        }
    }

    /// 只在 queue Harness 记录 State probe 已处理，其它 command 不推进该计数。
    fn state_command_processed(&self) {
        if let Some(queue) = &self.queue {
            queue.mark_processed();
        }
    }

    /// 仅 start-failure Harness 在 reply 线性化点暂停；生产和其它 Harness 不受影响。
    fn before_start_failure_reply(&self) {
        if let Some(start_failure) = &self.start_failure {
            start_failure.wait();
            self.record("start_failure_gate_released");
        }
    }

    /// 只保存 actor 内预定义标记；不接受测试方注入任意业务数据。
    fn record(&self, event: &str) {
        if let Some(trace) = &self.trace {
            trace.record(event);
        }
    }
}

/// 对外只暴露 queue 同步语义，内部 gate 和 actor 字段仍属于 bridge 实现。
pub struct RuntimeQueueBarrier {
    inner: Arc<QueueControlGate>,
}

/// 暴露 start-failure 竞态所需的最小 barrier/trace，不公开生产 actor 字段或构造器。
pub struct RuntimeStartFailureBarrier {
    gate: Arc<QueueControlGate>,
    trace: Arc<RuntimeControlTrace>,
}

impl RuntimeStartFailureBarrier {
    /// 等待 actor 到达失败 reply 前栅栏，证明 start 已完成 supervisor 构造尝试。
    pub fn wait_until_armed(&self, deadline: Instant) -> bool {
        self.gate.wait_until_armed(deadline)
    }

    /// 幂等释放失败 reply，使优先级 shutdown 与普通 command 的顺序可确定验证。
    pub fn release(&self) {
        self.gate.release();
    }

    /// 等待一个固定 lifecycle 标记；标记来自生产路径，不由测试伪造。
    pub fn wait_for(&self, event: &str, deadline: Instant) -> bool {
        self.trace.wait_for(event, deadline)
    }

    /// 返回有序标记快照，用于断言线性化顺序和失败诊断。
    pub fn events(&self) -> Vec<String> {
        self.trace.events()
    }
}

impl RuntimeQueueBarrier {
    /// 等待 actor 到达消费前栅栏，避免依赖线程调度或任意 sleep。
    pub fn wait_until_armed(&self, deadline: Instant) -> bool {
        self.inner.wait_until_armed(deadline)
    }

    /// 释放唯一 actor；重复调用由内部原子 gate 保持幂等。
    pub fn release(&self) {
        self.inner.release();
    }

    /// 等待指定数量的 State probe 已成功准入，用真实 bounded queue 事实替代调度 sleep。
    pub fn wait_until_admitted(&self, expected: u64, deadline: Instant) -> bool {
        self.inner.wait_until_admitted(expected, deadline)
    }

    /// 等待精确数量的 probe 被消费，用 actor 事实证明队列已完成排空。
    pub fn wait_until_processed(&self, expected: u64, deadline: Instant) -> bool {
        self.inner.wait_until_processed(expected, deadline)
    }
}

/// 仅在独立 unit test target 内持有真实 RuntimeHost；控制口不会进入生产 crate 或集成测试。
pub(crate) struct RuntimeHostHarness {
    host: RuntimeHost,
}

impl RuntimeHostHarness {
    /// 通过 Harness 构造固定 executable/args 的 LaunchConfig，不向生产类型公开测试入口。
    pub(crate) fn launch_config(
        executable: PathBuf,
        args: Vec<OsString>,
        run_dir: PathBuf,
    ) -> LaunchConfig {
        let mut sidecar = SidecarConfig::with_directories(
            executable,
            run_dir.clone(),
            run_dir.clone(),
            run_dir.clone(),
            run_dir,
        );
        sidecar.args = args;
        LaunchConfig::from_sidecar(sidecar)
    }

    /// 构造四目录 LaunchConfig，目录角色与生产 sidecar 所有权保持一致。
    pub(crate) fn launch_config_with_dirs(
        executable: PathBuf,
        args: Vec<OsString>,
        home_dir: PathBuf,
        data_dir: PathBuf,
        run_dir: PathBuf,
        log_dir: PathBuf,
    ) -> LaunchConfig {
        let mut sidecar =
            SidecarConfig::with_directories(executable, home_dir, data_dir, run_dir, log_dir);
        sidecar.args = args;
        LaunchConfig::from_sidecar(sidecar)
    }

    /// 使用唯一生产构造入口创建 Host；Harness 不改变目录、启动或恢复语义。
    pub(crate) fn new(config: LaunchConfig, sink: EventSink) -> Self {
        Self {
            host: RuntimeHost::new(config, sink),
        }
    }

    /// 注入实例级退出时限与 cleanup 故障，只用于验证拒绝退出和恢复闭环。
    pub(crate) fn with_exit_control(
        config: LaunchConfig,
        sink: EventSink,
        timeout: Duration,
        shutdown_failure_injector: Arc<AtomicUsize>,
    ) -> Self {
        let control: Arc<dyn RuntimeControlPort> = Arc::new(HarnessRuntimeControl {
            exit_timeout: timeout,
            queue: None,
            start_failure: None,
            trace: None,
            shutdown_failure_injector: Some(shutdown_failure_injector),
        });
        Self {
            host: RuntimeHost::compose(Arc::new(NativeRuntimePlatform::with_control(
                config, sink, control,
            ))),
        }
    }

    /// 返回生产 Host 的共享克隆；测试只能经真实命令入口驱动生命周期。
    pub(crate) fn host(&self) -> RuntimeHost {
        self.host.clone()
    }

    /// 只读检查测试 Host 的 Workspace binding 是否存在；该探针留在外置 Harness，生产
    /// API 继续只暴露 fail-closed 的 WorkspaceLookup，锁中毒也不会被恢复成可信状态。
    pub(crate) fn workspace_binding_present(host: &RuntimeHost) -> Result<bool, &'static str> {
        host.workspace
            .lock()
            .map(|binding| binding.is_some())
            .map_err(|_| "runtime workspace binding lock poisoned")
    }

    /// 构造未注入故障或时序控制的生产 Bridge，供进程合同测试验证真实 actor 行为。
    /// 返回类型保持 opaque 使用，测试不能据此访问 actor 字段或扩大生产 façade。
    pub(crate) fn bridge(
        config: LaunchConfig,
        sink: EventSink,
    ) -> Result<RuntimeBridge, RuntimeCommandError> {
        RuntimeBridge::new_with_control(config, sink, production_runtime_control())
    }

    /// 经统一 Harness 构造 queue 受控 bridge，生产 `RuntimeBridge` 不公开测试构造器。
    pub(crate) fn bridge_with_queue_control(
        config: LaunchConfig,
        sink: EventSink,
    ) -> Result<(RuntimeBridge, RuntimeQueueBarrier), RuntimeCommandError> {
        let gate = Arc::new(QueueControlGate::new());
        let control: Arc<dyn RuntimeControlPort> = Arc::new(HarnessRuntimeControl {
            exit_timeout: default_exit_timeout(),
            queue: Some(Arc::clone(&gate)),
            start_failure: None,
            trace: None,
            shutdown_failure_injector: None,
        });
        let bridge = RuntimeBridge::new_with_control(config, sink, control)?;
        Ok((bridge, RuntimeQueueBarrier { inner: gate }))
    }

    /// 在 start 失败 reply 前注入确定栅栏与脱敏 trace，验证高优先级 shutdown 不被阻塞。
    pub(crate) fn bridge_with_start_failure_control(
        config: LaunchConfig,
        sink: EventSink,
    ) -> Result<(RuntimeBridge, RuntimeStartFailureBarrier), RuntimeCommandError> {
        let gate = Arc::new(QueueControlGate::new());
        let trace = Arc::new(RuntimeControlTrace::new());
        let control: Arc<dyn RuntimeControlPort> = Arc::new(HarnessRuntimeControl {
            exit_timeout: default_exit_timeout(),
            queue: None,
            start_failure: Some(Arc::clone(&gate)),
            trace: Some(Arc::clone(&trace)),
            shutdown_failure_injector: None,
        });
        let bridge = RuntimeBridge::new_with_control(config, sink, control)?;
        Ok((bridge, RuntimeStartFailureBarrier { gate, trace }))
    }

    /// 读取隔离 actor 的内部 phase，用于失败诊断而不向生产 Bridge 暴露测试方法。
    pub(crate) fn bridge_phase(bridge: &RuntimeBridge) -> u8 {
        if bridge.exit_ready() {
            RuntimeControlPhase::ActorCompleted as u8
        } else {
            0
        }
    }

    /// 探测 Bridge bounded queue 的准入边界，调用不会等待未消费的 reply。
    pub(crate) fn try_queue_probe(bridge: &RuntimeBridge) -> Result<(), RuntimeCommandError> {
        let (reply, receiver) = mpsc::sync_channel(1);
        match bridge
            .inner
            .commands
            .try_send(crate::app_runtime::infrastructure::bridge::BridgeCommand::State { reply })
        {
            Ok(()) => {
                bridge.inner.runtime_control.command_admitted();
                drop(receiver);
                Ok(())
            }
            Err(TrySendError::Full(_)) => Err(RuntimeCommandError::queue_full()),
            Err(TrySendError::Disconnected(_)) => Err(RuntimeCommandError::unavailable()),
        }
    }

    /// 经 JA-RPC v2 配置白名单写入测试 Profile，不开放任意 RPC 方法。
    pub fn config_request(
        &self,
        method: &'static str,
        params: Value,
    ) -> Result<Value, RuntimeCommandError> {
        // Harness 兼容既有夹具字符串，但每个分支立即收窄为不同 nominal request/response。
        let bytes =
            serde_json::to_vec(&params).map_err(|_| RuntimeCommandError::invalid_params())?;
        let response = match method {
            "configuration/read" => self.host.config_request(ConfigurationRequest::Read(
                ConfigurationReadParams::try_new(bytes)?,
            ))?,
            "configuration/patch" => self.host.config_request(ConfigurationRequest::Patch(
                ConfigurationPatchParams::try_new(bytes)?,
            ))?,
            "configuration/replace" => self.host.config_request(ConfigurationRequest::Replace(
                ConfigurationReplaceParams::try_new(bytes)?,
            ))?,
            "configuration/reset" => self.host.config_request(ConfigurationRequest::Reset(
                ConfigurationResetParams::try_new(bytes)?,
            ))?,
            "credential/set" => self
                .host
                .config_request(ConfigurationRequest::CredentialSet(
                    CredentialSetParams::try_new(bytes)?,
                ))?,
            "credential/delete" => {
                self.host
                    .config_request(ConfigurationRequest::CredentialDelete(
                        CredentialDeleteParams::try_new(bytes)?,
                    ))?
            }
            _ => return Err(RuntimeCommandError::invalid_params()),
        };
        let bytes = match response {
            ConfigurationResponse::Read(value) => value.into_bytes(),
            ConfigurationResponse::Patch(value) => value.into_bytes(),
            ConfigurationResponse::Replace(value) => value.into_bytes(),
            ConfigurationResponse::Reset(value) => value.into_bytes(),
            ConfigurationResponse::CredentialSet(value) => value.into_bytes(),
            ConfigurationResponse::CredentialDelete(value) => value.into_bytes(),
        };
        serde_json::from_slice(&bytes).map_err(|_| RuntimeCommandError::unavailable())
    }

    /// 通过固定 `thread/create` 用例为真实 Turn 集成测试建立 Java-owned Thread。
    ///
    /// Harness 只返回后续 Turn 所需的不透明 ID，不开放任意 History 方法或通用 RPC，
    /// 因而测试仍经过生产 bridge、Java 事务和当前 DTO 校验边界。
    pub fn create_thread(
        &self,
        title: &str,
        provider_id: &str,
        model_id: &str,
    ) -> Result<String, RuntimeCommandError> {
        self.create_thread_with_cwd(title, provider_id, model_id, None)
    }

    /// 通过固定 `thread/create` 用例创建绑定到调用方已在 Host 注册的 Workspace Thread。
    /// cwd 只供真实集成测试重放生产项目链路，Java 仍会用 `workspace/open` 的 canonical root
    /// 解析 identity；Harness 不据此创建另一份 Workspace owner。
    pub fn create_workspace_thread(
        &self,
        title: &str,
        provider_id: &str,
        model_id: &str,
        cwd: String,
    ) -> Result<String, RuntimeCommandError> {
        self.create_thread_with_cwd(title, provider_id, model_id, Some(cwd))
    }

    /// 共享 Thread DTO 校验与响应解析，避免 general/project 两种夹具形成协议分叉。
    fn create_thread_with_cwd(
        &self,
        title: &str,
        provider_id: &str,
        model_id: &str,
        cwd: Option<String>,
    ) -> Result<String, RuntimeCommandError> {
        let input = ThreadCreateInput {
            cwd,
            title: title.to_owned(),
            provider_id: provider_id.to_owned(),
            model_id: model_id.to_owned(),
            reasoning_level: Some("medium".to_owned()),
            access_mode: "approval_required".to_owned(),
        };
        validate_thread_create(&input)?;
        let result = request_history(
            &self.host,
            HistoryMethod::ThreadCreate,
            serde_json::to_value(input).map_err(|_| RuntimeCommandError::invalid_params())?,
        )?;
        Ok(parse_thread(result)?.thread_id)
    }
}
