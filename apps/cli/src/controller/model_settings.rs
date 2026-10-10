// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 设置连接独占局部工作线程；主对话的事件泵和终端输入不会等待网络或文件保存。

use super::CliError;
use super::model_settings_recovery::{ModelSettingsSession, SettingsRequestError};
use super::rpc::Connection;
use ja_cli::ui::{
    ModelSettingsOutcome, ModelSettingsRequest, ModelSettingsResult, TuiBridge, UiAction, UiEvent,
    UiSnapshot,
};
use ja_runtime::app_server_process::EventPump;
use std::io::{self, IsTerminal};
use std::sync::mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError};
use std::thread::{self, JoinHandle};
use std::time::Duration;

const EVENT_WAIT: Duration = Duration::from_millis(40);

enum WorkerCommand {
    Request(ModelSettingsRequest),
    Shutdown,
}

/// 每个打开的设置面板有独立连接和操作队列；关闭面板只清理该连接的资源。
pub(super) struct ModelSettingsRunner {
    commands: SyncSender<WorkerCommand>,
    results: Receiver<ModelSettingsResult>,
    worker: Option<JoinHandle<()>>,
    pending_operation: Option<String>,
}

impl ModelSettingsRunner {
    /// 线程内才连接已有 owner，UI 先显示加载态；不存在第二次 App Server 启动或阻塞主事件泵。
    pub fn spawn() -> Result<Self, CliError> {
        let (command_tx, command_rx) = mpsc::sync_channel(1);
        let (result_tx, result_rx) = mpsc::channel();
        let worker = thread::Builder::new()
            .name("ja-model-settings".into())
            .spawn(move || settings_worker(command_rx, result_tx))
            .map_err(CliError::transport)?;
        Ok(Self {
            commands: command_tx,
            results: result_rx,
            worker: Some(worker),
            pending_operation: None,
        })
    }

    /// 单次操作保持唯一 ID；忙碌时不排第二次副作用，键盘及绘制仍继续响应。
    pub fn submit(&mut self, request: ModelSettingsRequest) -> Result<(), CliError> {
        if self.pending_operation.is_some() {
            return Err(CliError::usage("设置操作仍在处理，请等待当前结果。"));
        }
        let id = operation_id(&request).to_owned();
        self.commands
            .try_send(WorkerCommand::Request(request))
            .map_err(|error| match error {
                TrySendError::Full(_) => CliError::usage("设置操作仍在处理，请等待当前结果。"),
                TrySendError::Disconnected(_) => {
                    CliError::transport("设置连接已关闭，请重新打开模型设置")
                }
            })?;
        self.pending_operation = Some(id);
        Ok(())
    }

    /// 结果 ID 交给 UiState 再核对，已关闭或新打开的面板不会接纳旧响应。
    pub fn poll(&mut self) -> Option<ModelSettingsResult> {
        match self.results.try_recv() {
            Ok(result) => {
                if self.pending_operation.as_deref() == Some(&result.operation_id) {
                    self.pending_operation = None;
                }
                Some(result)
            }
            Err(TryRecvError::Disconnected) => {
                self.pending_operation
                    .take()
                    .map(|operation_id| ModelSettingsResult {
                        operation_id,
                        outcome: ModelSettingsOutcome::Failed {
                            message: "设置连接已关闭；草稿已保留，请重新打开设置。".into(),
                        },
                    })
            }
            Err(TryRecvError::Empty) => None,
        }
    }
}

impl Drop for ModelSettingsRunner {
    /// 终端不等待未完成网络请求；工作线程在既有 RPC 截止内释放连接，不持有终端或对话观察。
    fn drop(&mut self) {
        let _ = self.commands.try_send(WorkerCommand::Shutdown);
        if let Some(worker) = self.worker.take()
            && worker.is_finished()
        {
            let _ = worker.join();
        }
    }
}

/// 工作线程串行处理配置意图；只读重连可重试，写入由 Session 保持提交一次和待核实状态。
fn settings_worker(commands: Receiver<WorkerCommand>, results: mpsc::Sender<ModelSettingsResult>) {
    let mut session = ModelSettingsSession::default();
    let mut connection: Option<Connection> = None;
    let mut event_pump: Option<EventPump> = None;
    let mut reconnect = false;
    loop {
        // 设置连接不观察 Thread；仍需排空配置通知，避免长时间编辑耗尽有界事件队列。
        if let Some(pump) = &mut event_pump {
            while pump.next_event(Duration::ZERO).is_some() {}
        }
        let command = match commands.recv_timeout(EVENT_WAIT) {
            Ok(command) => command,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        };
        let WorkerCommand::Request(request) = command else {
            break;
        };
        let result = session.execute(request, |method, params| {
            if reconnect || connection.is_none() {
                let mut replacement = match &connection {
                    Some(connection) => connection.reconnect_existing(),
                    None => Connection::connect_existing(),
                }
                .map_err(request_error)?;
                event_pump = Some(replacement.take_event_pump().map_err(request_error)?);
                connection = Some(replacement);
                reconnect = false;
            }
            let response = if method == "model/test" {
                connection
                    .as_mut()
                    .expect("settings connection initialized")
                    .request_with_timeout(method, params, Duration::from_secs(35))
            } else {
                connection
                    .as_mut()
                    .expect("settings connection initialized")
                    .request(method, params)
            };
            if response
                .as_ref()
                .is_err_and(|error| error.rpc_code.is_none())
            {
                reconnect = true;
            }
            response.map_err(request_error)
        });
        if results.send(result).is_err() {
            break;
        }
    }
    if let Some(mut connection) = connection {
        let _ = connection.disconnect();
    }
}

/// 原生错误只传稳定分类和安全文案，不格式化请求参数或 Secret。
fn request_error(error: CliError) -> SettingsRequestError {
    SettingsRequestError {
        rpc_code: error.rpc_code,
        message: error.message,
    }
}

/// 请求身份来自 UI 操作，不以方法名或列表索引推断响应属于哪个草稿。
pub(super) fn operation_id(request: &ModelSettingsRequest) -> &str {
    match request {
        ModelSettingsRequest::Read { operation_id }
        | ModelSettingsRequest::Save { operation_id, .. }
        | ModelSettingsRequest::Verify { operation_id }
        | ModelSettingsRequest::Test { operation_id, .. }
        | ModelSettingsRequest::Repair { operation_id, .. } => operation_id,
    }
}

/// 显式 setup 使用与会话完全相同的面板；离开后先恢复 Shell，再按用户动作进入对话。
pub(super) fn run_standalone() -> Result<bool, CliError> {
    if !io::stdin().is_terminal() || !io::stdout().is_terminal() {
        return Err(CliError::usage(
            "ja setup 需要交互终端；请在 PowerShell 或 Windows Terminal 中运行。",
        ));
    }
    let mut connection = Connection::connect_or_start()?;
    let mut event_pump = connection.take_event_pump()?;
    let mut runner = ModelSettingsRunner::spawn()?;
    let mut bridge = TuiBridge::spawn(UiSnapshot::default()).map_err(CliError::transport)?;
    let initial_operation_id = uuid::Uuid::new_v4().simple().to_string();
    bridge
        .events()
        .send(UiEvent::OpenModelSettings {
            operation_id: initial_operation_id.clone(),
            standalone: true,
        })
        .map_err(CliError::transport)?;
    runner.submit(ModelSettingsRequest::Read {
        operation_id: initial_operation_id,
    })?;
    let result = (|| {
        loop {
            // 独立入口的启动连接只维持 owner 存活；权威读写全部由设置线程完成。
            while event_pump.next_event(Duration::ZERO).is_some() {}
            while let Some(result) = runner.poll() {
                bridge
                    .events()
                    .send(UiEvent::ModelSettingsResult(result))
                    .map_err(CliError::transport)?;
            }
            while let Some(action) = bridge.try_recv_action().map_err(CliError::transport)? {
                match action {
                    UiAction::Quit => return Ok(false),
                    UiAction::CloseModelSettings { start_conversation } => {
                        return Ok(start_conversation);
                    }
                    UiAction::ModelSettings(request) => {
                        let operation_id = operation_id(&request).to_owned();
                        if let Err(error) = runner.submit(request) {
                            bridge
                                .events()
                                .send(UiEvent::ModelSettingsResult(ModelSettingsResult {
                                    operation_id,
                                    outcome: ModelSettingsOutcome::Failed {
                                        message: error.message,
                                    },
                                }))
                                .map_err(CliError::transport)?;
                        }
                    }
                    _ => {}
                }
            }
            thread::sleep(EVENT_WAIT);
        }
    })();
    let cleanup = bridge.shutdown().map_err(CliError::transport);
    drop(runner);
    let _ = connection.disconnect();
    cleanup?;
    result
}
