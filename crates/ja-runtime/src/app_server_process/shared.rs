// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 共享 Java App Server 的本地连接与按需启动边界。
//!
//! TCP 连接只拥有当前客户端的 Session；后台进程独立于客户端的 Drop 和退出清理。

use crate::app_server_process::client::{EventPump, Session, SessionEvent};
use crate::app_server_process::error::AppServerProcessError;
use crate::app_server_process::lifecycle::supervisor::{
    validate_initialize_result, validate_turn_identity,
};
use crate::app_server_process::lifecycle::{
    LifecycleState, ThreadCompactionLease, TurnChangeSetReadLease,
};
use crate::app_server_process::process::SidecarConfig;
use crate::app_server_process::protocol::{
    AttachmentPreviewCloseParams, AttachmentPreviewOpenParams, AttachmentPreviewReadParams,
    RpcFrame, V1_CLIENT_METHODS, default_initialize_params, error_is_incompatible,
    generate_ready_token, is_ready_notification, is_runtime_ready_notification, valid_schema_id,
};
use fs2::FileExt;
use serde::Deserialize;
use serde_json::Value;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddrV4, TcpStream};
use std::path::Path;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const ENDPOINT_FILE: &str = "app-server.endpoint.json";
const START_LOCK_FILE: &str = "app-server.start.lock";
const MAX_ENDPOINT_BYTES: u64 = 1_024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const START_POLL_INTERVAL: Duration = Duration::from_millis(30);
const WRITE_TIMEOUT: Duration = Duration::from_secs(2);

/// Java 原子发布的唯一端点形状；令牌只留在连接建立栈上，不进入公开状态或诊断。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Endpoint {
    protocol_major: u32,
    protocol_minor: u32,
    port: u16,
    server_instance_id: String,
    runtime_generation: u64,
    token: String,
}

/// 一个已认证并完成 JA-RPC initialize 的客户端连接。
///
/// 后台进程没有保存在此类型中，丢弃它只释放本连接，不能取消别的客户端或任务。
pub struct SharedAppServerClient {
    session: Session,
    event_pump: Option<EventPump>,
    socket: TcpStream,
    server_instance_id: String,
    runtime_generation: u64,
    ready_token_echo: String,
    stopping: Arc<Mutex<bool>>,
}

/// 握手失败时必须主动 shutdown 原始 TCP 句柄；仅 drop reader clone 会让
/// Session 的读取线程继续阻塞在已失去调用方的连接上。
struct PendingSocket(Option<TcpStream>);

impl PendingSocket {
    /// 成功移交后解除失败清理，后续由 SharedAppServerClient 统一断开。
    fn take(&mut self) -> Result<TcpStream, AppServerProcessError> {
        self.0.take().ok_or(AppServerProcessError::InvalidState)
    }
}

impl Drop for PendingSocket {
    /// 无论 initialize、ready 还是 context 注册失败，均唤醒同一连接的 reader。
    fn drop(&mut self) {
        if let Some(socket) = self.0.as_ref() {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }
}

impl SharedAppServerClient {
    /// 在同一 run directory 的跨进程锁内发现或启动后台，避免两个原生客户端
    /// 同时认领数据库 owner；等待与连接均消耗配置中的同一个有界启动预算。
    pub fn connect_or_start(
        config: SidecarConfig,
        host_generation: u64,
    ) -> Result<Self, AppServerProcessError> {
        config.validate()?;
        if !(1..=9_007_199_254_740_991).contains(&host_generation) {
            return Err(AppServerProcessError::InvalidConfig);
        }
        let deadline = Instant::now()
            .checked_add(config.ready_timeout)
            .ok_or(AppServerProcessError::InvalidTimeout)?;
        match Self::connect_existing_until(&config, deadline) {
            Ok(client) => return Ok(client),
            Err(AppServerProcessError::NotReady) => {}
            Err(error) => return Err(error),
        }
        let lock = acquire_start_lock(&config.run_dir, deadline)?;
        match Self::connect_existing_until(&config, deadline) {
            Ok(client) => return Ok(client),
            Err(AppServerProcessError::NotReady) => {}
            Err(error) => return Err(error),
        }
        spawn_detached(&config, host_generation)?;
        loop {
            match Self::connect_existing_until(&config, deadline) {
                Ok(client) => {
                    drop(lock);
                    return Ok(client);
                }
                Err(AppServerProcessError::NotReady) if Instant::now() < deadline => {
                    thread::sleep(START_POLL_INTERVAL);
                }
                Err(AppServerProcessError::NotReady) => {
                    return Err(AppServerProcessError::DeadlineExceeded);
                }
                Err(error) => return Err(error),
            }
        }
    }

    /// 只连接已存在的后台，供状态查询和显式管理使用；连接失败不会隐式启动服务。
    pub fn connect_existing(
        config: &SidecarConfig,
        _host_generation: u64,
    ) -> Result<Self, AppServerProcessError> {
        config.validate()?;
        let deadline = Instant::now()
            .checked_add(config.ready_timeout)
            .ok_or(AppServerProcessError::InvalidTimeout)?;
        Self::connect_existing_until(config, deadline)
    }

    /// 从服务端权威端点读取实例与 generation 后再建立会话，避免重连时沿用
    /// 本地新分配的 generation 误拒绝旧后台的合法事件。
    fn connect_existing_until(
        config: &SidecarConfig,
        deadline: Instant,
    ) -> Result<Self, AppServerProcessError> {
        let endpoint = read_endpoint(&config.run_dir)?;
        let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, endpoint.port);
        let timeout = deadline
            .saturating_duration_since(Instant::now())
            .min(CONNECT_TIMEOUT);
        if timeout.is_zero() {
            return Err(AppServerProcessError::DeadlineExceeded);
        }
        let mut socket = TcpStream::connect_timeout(&address.into(), timeout).map_err(|error| {
            match error.kind() {
                io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut => {
                    AppServerProcessError::NotReady
                }
                _ => AppServerProcessError::HandshakeFailed,
            }
        })?;
        socket
            .set_write_timeout(Some(timeout))
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        let auth = serde_json::to_vec(&serde_json::json!({ "token": endpoint.token }))
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        if auth.len() + 1 > 256 {
            return Err(AppServerProcessError::HandshakeFailed);
        }
        socket
            .write_all(&auth)
            .and_then(|_| socket.write_all(b"\n"))
            .and_then(|_| socket.flush())
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        socket
            .set_write_timeout(None)
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        let mut pending_socket = PendingSocket(Some(socket));
        let reader = pending_socket
            .0
            .as_ref()
            .ok_or(AppServerProcessError::InvalidState)?
            .try_clone()
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        let writer = pending_socket
            .0
            .as_ref()
            .ok_or(AppServerProcessError::InvalidState)?
            .try_clone()
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        let session = Session::from_io_with_terminal(
            reader,
            writer,
            io::empty(),
            endpoint.runtime_generation,
            config.limits.clone(),
            None,
            WRITE_TIMEOUT,
        )?;
        let mut event_pump = session.take_event_pump()?;
        let ready_token = generate_ready_token()?;
        session.install_ready_token_challenge(ready_token.clone())?;
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(AppServerProcessError::DeadlineExceeded);
        }
        let response = session.request(
            "runtime/initialize",
            default_initialize_params(&config.limits),
            remaining,
        )?;
        if let Some(error) = response.error() {
            return Err(if error_is_incompatible(error.code(), error.data()) {
                AppServerProcessError::Incompatible
            } else {
                AppServerProcessError::HandshakeFailed
            });
        }
        let result = response
            .result()
            .value()
            .ok_or(AppServerProcessError::ProtocolFault)?;
        let instance = validate_initialize_result(result, &config.limits)?;
        if instance != endpoint.server_instance_id {
            return Err(AppServerProcessError::HandshakeFailed);
        }
        session.notify("runtime/initialized", session.initialized_params()?)?;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(AppServerProcessError::DeadlineExceeded);
            }
            match event_pump.next_event(remaining) {
                Some(SessionEvent::Notification(frame))
                    if is_runtime_ready_notification(&frame) =>
                {
                    if !is_ready_notification(&frame, Some(&instance))
                        || frame
                            .params()
                            .and_then(|params| params.get("generation"))
                            .and_then(Value::as_u64)
                            != Some(endpoint.runtime_generation)
                    {
                        return Err(AppServerProcessError::HandshakeFailed);
                    }
                    session.with_ready_promotion(&frame, || Ok(()))?;
                    register_native_context(&session, &config.env, deadline)?;
                    return Ok(Self {
                        session,
                        event_pump: Some(event_pump),
                        socket: pending_socket.take()?,
                        server_instance_id: instance,
                        runtime_generation: endpoint.runtime_generation,
                        ready_token_echo: ready_token,
                        stopping: Arc::new(Mutex::new(false)),
                    });
                }
                Some(SessionEvent::Eof | SessionEvent::HandshakeFailed) => {
                    return Err(AppServerProcessError::HandshakeFailed);
                }
                Some(SessionEvent::ProtocolFault(_) | SessionEvent::ResponseRejected) => {
                    return Err(AppServerProcessError::ProtocolFault);
                }
                Some(_) => {}
                None => return Err(AppServerProcessError::DeadlineExceeded),
            }
        }
    }

    /// 仅报告本连接状态；服务端事实由 health/read RPC 读取，不由本地状态猜测。
    pub fn state(&self) -> LifecycleState {
        if self
            .session
            .inner
            .closed
            .load(std::sync::atomic::Ordering::Acquire)
        {
            LifecycleState::Exited
        } else {
            LifecycleState::Ready
        }
    }

    /// 事件代际取自 Java endpoint；客户端新建的 host generation 不进入远端身份。
    pub const fn generation(&self) -> u64 {
        self.runtime_generation
    }

    /// 为双端事件 fence 显式命名服务端代际。
    pub const fn runtime_generation(&self) -> u64 {
        self.runtime_generation
    }

    /// 只返回已与 initialize 响应核对的实例 ID。
    pub fn server_instance_id(&self) -> &str {
        &self.server_instance_id
    }

    /// Desktop ready 投影仍须回显本连接的 challenge；不同连接绝不复用该值。
    pub fn ready_token_echo(&self) -> &str {
        &self.ready_token_echo
    }

    /// 复用冻结方法闭集与 Turn 身份准入，避免 TCP 路径比原 sidecar 放宽本地授权。
    pub fn request(
        &mut self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        if !V1_CLIENT_METHODS.contains(&method) || method == "runtime/initialize" {
            return Err(AppServerProcessError::ProtocolFault);
        }
        validate_turn_identity(method, &params)?;
        self.session
            .request_with_gate(method, params, timeout, &self.stopping)
    }

    /// 以类型化参数打开附件预览，防止 Desktop 为共享连接绕过原有资源约束。
    pub fn attachment_preview_open(
        &mut self,
        params: AttachmentPreviewOpenParams,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        let value = params
            .into_value()
            .map_err(|_| AppServerProcessError::ProtocolFault)?;
        self.request("attachment/preview/open", value, timeout)
    }

    /// 分段预览继续使用与 stdio 路径相同的 typed size 上限。
    pub fn attachment_preview_read(
        &mut self,
        params: AttachmentPreviewReadParams,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        let value = params
            .into_value()
            .map_err(|_| AppServerProcessError::ProtocolFault)?;
        self.request("attachment/preview/read", value, timeout)
    }

    /// 显式关闭当前附件预览会话；普通连接断开仍由 Java 清理连接私有资源。
    pub fn attachment_preview_close(
        &mut self,
        params: AttachmentPreviewCloseParams,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        let value = params
            .into_value()
            .map_err(|_| AppServerProcessError::ProtocolFault)?;
        self.request("attachment/preview/close", value, timeout)
    }

    /// 一次性移交本连接事件 pump，Session 的请求响应继续由 pending registry 独立接收。
    pub fn take_event_pump(&mut self) -> Result<EventPump, AppServerProcessError> {
        self.event_pump
            .take()
            .ok_or(AppServerProcessError::InvalidState)
    }

    /// 长请求取消只拿当前 TCP Session 的共享 handle，不涉及后台进程树。
    pub fn session_for_cancellation(&self) -> Option<Session> {
        (self.state() == LifecycleState::Ready).then(|| self.session.clone())
    }

    /// 退出门禁拿到的 Session 仅执行有界本地 close，不发送服务级 shutdown。
    pub fn close_session_until(
        session: &Session,
        deadline: Instant,
    ) -> Result<(), AppServerProcessError> {
        session.close_until(deadline)
    }

    /// 只读 ChangeSet 工作线程使用当前连接与服务端 generation，不与 actor 争用请求等待。
    pub fn turn_change_set_read_lease(
        &self,
    ) -> Result<TurnChangeSetReadLease, AppServerProcessError> {
        if self.state() != LifecycleState::Ready {
            return Err(AppServerProcessError::NotReady);
        }
        Ok(TurnChangeSetReadLease {
            session: self.session.clone(),
            stopping: Arc::clone(&self.stopping),
            generation: self.runtime_generation,
        })
    }

    /// Actor 只签发当前 Ready generation 的窄压缩 lease，长等待移到调用线程以保留其它命令准入。
    pub fn thread_compaction_lease(&self) -> Result<ThreadCompactionLease, AppServerProcessError> {
        if self.state() != LifecycleState::Ready {
            return Err(AppServerProcessError::NotReady);
        }
        Ok(ThreadCompactionLease {
            session: self.session.clone(),
            stopping: Arc::clone(&self.stopping),
            generation: self.runtime_generation,
        })
    }

    /// 只有显式服务管理命令才调用 runtime/shutdown；Java 可按活动任务状态拒绝。
    pub fn stop_server(&mut self, timeout: Duration) -> Result<(), AppServerProcessError> {
        let response = self.request("runtime/shutdown", serde_json::json!({}), timeout)?;
        if response.error().is_some()
            || response.result().value().is_none_or(|result| {
                result.get("status").and_then(Value::as_str) != Some("shutting_down")
                    || result.get("accepted").and_then(Value::as_bool) != Some(true)
            })
        {
            return Err(AppServerProcessError::InvalidState);
        }
        Ok(())
    }

    /// 关闭 socket 先唤醒 reader，再让 Session 的 writer 有界 join；后台本身继续运行。
    pub fn disconnect_until(&mut self, deadline: Instant) -> Result<(), AppServerProcessError> {
        let mut stopping = self
            .stopping
            .lock()
            .map_err(|_| AppServerProcessError::Faulted)?;
        *stopping = true;
        drop(stopping);
        let _ = self.socket.shutdown(Shutdown::Both);
        self.session.close_until(deadline)
    }
}

/// 在连接交还调用方前冻结本机完整环境；Java 以连接内存保存，不向其它客户端
/// 传播。非 UTF-8 环境无法经过 JSONL 无损表示时明确失败，不能悄悄删掉 PATH 等键。
fn register_native_context(
    session: &Session,
    environment: &std::collections::BTreeMap<std::ffi::OsString, std::ffi::OsString>,
    deadline: Instant,
) -> Result<(), AppServerProcessError> {
    let mut encoded = serde_json::Map::new();
    for (key, value) in environment {
        let key = key.to_str().ok_or(AppServerProcessError::InvalidConfig)?;
        let value = value.to_str().ok_or(AppServerProcessError::InvalidConfig)?;
        encoded.insert(key.to_owned(), Value::String(value.to_owned()));
    }
    let remaining = deadline.saturating_duration_since(Instant::now());
    if remaining.is_zero() {
        return Err(AppServerProcessError::DeadlineExceeded);
    }
    let response = session.request(
        "runtime/context/register",
        serde_json::json!({ "environment": encoded, "shell": null }),
        remaining,
    )?;
    if response.error().is_some() {
        return Err(AppServerProcessError::HandshakeFailed);
    }
    let context_id = response
        .result()
        .value()
        .and_then(|result| result.as_object())
        .filter(|object| object.len() == 1)
        .and_then(|object| object.get("contextId"))
        .and_then(Value::as_str)
        .ok_or(AppServerProcessError::ProtocolFault)?;
    if context_id.len() != 36
        || !context_id.starts_with("ctx_")
        || !context_id[4..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(AppServerProcessError::ProtocolFault);
    }
    Ok(())
}

impl Drop for SharedAppServerClient {
    /// 异常退出也只回收本连接；这里永不持有或终止 Java Child。
    fn drop(&mut self) {
        let deadline = Instant::now() + Duration::from_secs(3);
        if let Err(error) = self.disconnect_until(deadline) {
            tracing::warn!(?error, "shared client connection cleanup incomplete");
        }
    }
}

/// 严格解析 Java 原子发布的 endpoint；损坏或权限异常不会触发不安全的重新启动。
fn read_endpoint(run_dir: &Path) -> Result<Endpoint, AppServerProcessError> {
    let path = run_dir.join(ENDPOINT_FILE);
    let path_metadata = match fs::symlink_metadata(&path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Err(AppServerProcessError::NotReady);
        }
        Err(_) => return Err(AppServerProcessError::ProtocolFault),
    };
    if !path_metadata.is_file() || path_metadata.file_type().is_symlink() {
        return Err(AppServerProcessError::ProtocolFault);
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if path_metadata.file_attributes() & 0x0000_0400 != 0 {
            return Err(AppServerProcessError::ProtocolFault);
        }
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        // 保留 delete sharing 供 Java 原子替换；OPEN_REPARSE_POINT 让重解析点本身进入后续句柄检查。
        options
            .share_mode(0x0000_0001 | 0x0000_0004)
            .custom_flags(0x0020_0000);
    }
    let mut file = options
        .open(&path)
        .map_err(|_| AppServerProcessError::ProtocolFault)?;
    let metadata = file
        .metadata()
        .map_err(|_| AppServerProcessError::ProtocolFault)?;
    if !metadata.is_file() || metadata.len() > MAX_ENDPOINT_BYTES {
        return Err(AppServerProcessError::ProtocolFault);
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if metadata.file_attributes() & 0x0000_0400 != 0 {
            return Err(AppServerProcessError::ProtocolFault);
        }
    }
    #[cfg(windows)]
    verify_private_windows_acl(&file)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o077 != 0 {
            return Err(AppServerProcessError::ProtocolFault);
        }
    }
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    Read::by_ref(&mut file)
        .take(MAX_ENDPOINT_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| AppServerProcessError::ProtocolFault)?;
    if bytes.len() as u64 > MAX_ENDPOINT_BYTES {
        return Err(AppServerProcessError::ProtocolFault);
    }
    let endpoint: Endpoint =
        serde_json::from_slice(&bytes).map_err(|_| AppServerProcessError::ProtocolFault)?;
    if endpoint.protocol_major != 1
        || endpoint.protocol_minor != 0
        || endpoint.port == 0
        || !(1..=9_007_199_254_740_991).contains(&endpoint.runtime_generation)
        || !valid_schema_id(&endpoint.server_instance_id, "srv_", 101)
        || endpoint.token.len() != 64
        || !endpoint
            .token
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(AppServerProcessError::ProtocolFault);
    }
    Ok(endpoint)
}

/// Windows 端点必须由当前用户拥有，且 DACL 只能有授予该 SID 的单个 ACE；
/// 校验直接基于已打开文件句柄，和随后读取 token 的对象保持同一 identity。
#[cfg(windows)]
fn verify_private_windows_acl(file: &File) -> Result<(), AppServerProcessError> {
    use std::ffi::c_void;
    use std::os::windows::io::AsRawHandle;

    #[repr(C)]
    struct AclSizeInformation {
        ace_count: u32,
        acl_bytes_in_use: u32,
        acl_bytes_free: u32,
    }

    #[link(name = "advapi32")]
    unsafe extern "system" {
        fn GetSecurityInfo(
            handle: *mut c_void,
            object_type: u32,
            security_info: u32,
            owner: *mut *mut c_void,
            group: *mut *mut c_void,
            dacl: *mut *mut c_void,
            sacl: *mut *mut c_void,
            security_descriptor: *mut *mut c_void,
        ) -> u32;
        fn OpenProcessToken(
            process: *mut c_void,
            desired_access: u32,
            token: *mut *mut c_void,
        ) -> i32;
        fn GetTokenInformation(
            token: *mut c_void,
            information_class: u32,
            information: *mut c_void,
            length: u32,
            returned_length: *mut u32,
        ) -> i32;
        fn EqualSid(left: *mut c_void, right: *mut c_void) -> i32;
        fn GetAclInformation(
            acl: *mut c_void,
            information: *mut c_void,
            information_length: u32,
            information_class: u32,
        ) -> i32;
        fn GetAce(acl: *mut c_void, index: u32, ace: *mut *mut c_void) -> i32;
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetCurrentProcess() -> *mut c_void;
        fn CloseHandle(handle: *mut c_void) -> i32;
        fn LocalFree(memory: *mut c_void) -> *mut c_void;
    }

    let mut owner = std::ptr::null_mut();
    let mut dacl = std::ptr::null_mut();
    let mut descriptor = std::ptr::null_mut();
    // SAFETY: file 在整个函数内保持打开；Win32 仅写入指针槽，descriptor 由 LocalFree 释放。
    let status = unsafe {
        GetSecurityInfo(
            file.as_raw_handle(),
            1,                         // SE_FILE_OBJECT
            0x0000_0001 | 0x0000_0004, // OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION
            &mut owner,
            std::ptr::null_mut(),
            &mut dacl,
            std::ptr::null_mut(),
            &mut descriptor,
        )
    };
    if status != 0 || owner.is_null() || dacl.is_null() || descriptor.is_null() {
        if !descriptor.is_null() {
            unsafe { LocalFree(descriptor) };
        }
        return Err(AppServerProcessError::ProtocolFault);
    }

    let mut token = std::ptr::null_mut();
    // SAFETY: 获取当前进程 token 的最小查询权限；成功时由 CloseHandle 释放。
    let opened = unsafe { OpenProcessToken(GetCurrentProcess(), 0x0008, &mut token) } != 0;
    if !opened || token.is_null() {
        unsafe { LocalFree(descriptor) };
        return Err(AppServerProcessError::ProtocolFault);
    }
    let result = (|| {
        let mut required = 0_u32;
        // TokenUser = 1；第一次查询只取所需长度，缓冲区上限阻止异常令牌数据膨胀。
        unsafe {
            GetTokenInformation(token, 1, std::ptr::null_mut(), 0, &mut required);
        }
        if required < std::mem::size_of::<*mut c_void>() as u32 || required > 16_384 {
            return Err(AppServerProcessError::ProtocolFault);
        }
        let mut user = vec![0_u8; required as usize];
        if unsafe {
            GetTokenInformation(token, 1, user.as_mut_ptr().cast(), required, &mut required)
        } == 0
        {
            return Err(AppServerProcessError::ProtocolFault);
        }
        // TOKEN_USER 的首字段是 SID 指针；Vec<u8> 不承诺指针对齐，必须使用 unaligned read。
        let user_sid = unsafe { std::ptr::read_unaligned(user.as_ptr().cast::<*mut c_void>()) };
        if user_sid.is_null() || unsafe { EqualSid(owner, user_sid) } == 0 {
            return Err(AppServerProcessError::ProtocolFault);
        }
        let mut info = AclSizeInformation {
            ace_count: 0,
            acl_bytes_in_use: 0,
            acl_bytes_free: 0,
        };
        if unsafe {
            GetAclInformation(
                dacl,
                (&mut info as *mut AclSizeInformation).cast(),
                std::mem::size_of::<AclSizeInformation>() as u32,
                2, // AclSizeInformation
            )
        } == 0
            || info.ace_count != 1
        {
            return Err(AppServerProcessError::ProtocolFault);
        }
        let mut ace = std::ptr::null_mut();
        if unsafe { GetAce(dacl, 0, &mut ace) } == 0 || ace.is_null() {
            return Err(AppServerProcessError::ProtocolFault);
        }
        // ACCESS_ALLOWED_ACE 的 AceType=0 且 SidStart 固定偏移 8；拒绝 DENY、继承项或其它 SID。
        let ace_type = unsafe { *(ace as *const u8) };
        let ace_sid = unsafe { (ace as *mut u8).add(8).cast::<c_void>() };
        if ace_type != 0 || unsafe { EqualSid(owner, ace_sid) } == 0 {
            return Err(AppServerProcessError::ProtocolFault);
        }
        Ok(())
    })();
    unsafe {
        CloseHandle(token);
        LocalFree(descriptor);
    }
    result
}

/// 只持有启动协调锁，不持有后台进程；OS 文件锁在客户端崩溃时自动释放。
fn acquire_start_lock(run_dir: &Path, deadline: Instant) -> Result<File, AppServerProcessError> {
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(run_dir.join(START_LOCK_FILE))
        .map_err(|_| AppServerProcessError::Spawn)?;
    loop {
        match file.try_lock_exclusive() {
            Ok(()) => return Ok(file),
            Err(error)
                if error.raw_os_error() == fs2::lock_contended_error().raw_os_error()
                    && Instant::now() < deadline =>
            {
                thread::sleep(START_POLL_INTERVAL);
            }
            Err(error) if error.raw_os_error() == fs2::lock_contended_error().raw_os_error() => {
                return Err(AppServerProcessError::DeadlineExceeded);
            }
            Err(_) => return Err(AppServerProcessError::Spawn),
        }
    }
}

/// Java 直接作为后台 owner 启动；受限 Windows Job 拒绝脱离时退回同 Job，避免整个桌面不可用。
/// 启动告警使用固定 target 标识阶段，原生日志丢弃自由字段时仍可区分失败位置且不泄漏路径。
fn spawn_detached(
    config: &SidecarConfig,
    host_generation: u64,
) -> Result<(), AppServerProcessError> {
    config.verify_executable_identity()?;
    let mut command = Command::new(config.canonical_executable());
    command
        .args(&config.args)
        .arg(format!("--ja-runtime-generation={host_generation}"))
        .arg("--ja-transport=tcp")
        .current_dir(config.canonical_run_dir())
        .envs(config.env.iter())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    let in_job = {
        use std::os::windows::process::CommandExt;
        const CREATE_BREAKAWAY_FROM_JOB: u32 = 0x0100_0000;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let in_job = current_process_in_job()?;
        let flags = if in_job {
            CREATE_BREAKAWAY_FROM_JOB | CREATE_NO_WINDOW
        } else {
            CREATE_NO_WINDOW
        };
        command.creation_flags(flags);
        in_job
    };
    #[cfg(windows)]
    let child = match command.spawn() {
        Ok(child) => child,
        Err(error) if in_job && error.raw_os_error() == Some(5) => {
            use std::os::windows::process::CommandExt;
            // ERROR_ACCESS_DENIED 表示宿主不允许 Job breakaway；首次 spawn 明确未创建进程，
            // 因而仅此一次可安全退回同 Job。退出时由宿主 Job 终止子进程，重启再按游标恢复。
            tracing::warn!(
                target: "ja.runtime.spawn.breakaway_denied",
                os_code = 5,
                "shared daemon breakaway denied; using host job"
            );
            command.creation_flags(0x0800_0000);
            command.spawn().map_err(|fallback| {
                tracing::warn!(target: "ja.runtime.spawn.fallback_failed", os_code = ?fallback.raw_os_error(), "shared daemon spawn failed");
                AppServerProcessError::Spawn
            })?
        }
        Err(error) => {
            tracing::warn!(target: "ja.runtime.spawn.detached_failed", os_code = ?error.raw_os_error(), "shared daemon detached spawn failed");
            return Err(AppServerProcessError::Spawn);
        }
    };
    #[cfg(not(windows))]
    let child = command.spawn().map_err(|error| {
        // 只记录 OS 错误码，不记录 executable、argv、环境或 endpoint；
        // 非 Windows 失败也只保留错误类别，不能把启动参数写入普通日志。
        tracing::warn!(os_code = ?error.raw_os_error(), "shared daemon detached spawn failed");
        AppServerProcessError::Spawn
    })?;
    let mut child = child;
    if thread::Builder::new()
        .name("ja-daemon-reaper".to_owned())
        .spawn(move || {
            let _ = child.wait();
        })
        .is_err()
    {
        // Child 已经成功启动；把 reaper 故障误报为启动失败会使下一次连接
        // 再创建第二个 owner。Windows 句柄由 Child Drop 释放，Java 仍继续服务。
        tracing::warn!(target: "ja.runtime.spawn.reaper_failed", "shared daemon reaper could not start");
    }
    Ok(())
}

/// 仅在当前进程确实属于 Job 时要求 breakaway；无 Job 时添加该 flag
/// 在部分 Windows 宿主会被拒绝，且本来就没有可继承的 Job 归属。查询失败以固定 target 留证，
/// 避免脱敏日志吞掉阶段信息后把真实启动故障误判为普通连接超时。
#[cfg(windows)]
fn current_process_in_job() -> Result<bool, AppServerProcessError> {
    use std::ffi::c_void;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetCurrentProcess() -> *mut c_void;
        fn IsProcessInJob(process: *mut c_void, job: *mut c_void, result: *mut i32) -> i32;
    }
    let mut in_job = 0_i32;
    // SAFETY: GetCurrentProcess 是当前进程 pseudo handle；NULL job 查询任意 Job。
    if unsafe { IsProcessInJob(GetCurrentProcess(), std::ptr::null_mut(), &mut in_job) } == 0 {
        tracing::warn!(
            target: "ja.runtime.spawn.job_query_failed",
            os_code = ?std::io::Error::last_os_error().raw_os_error(),
            "shared daemon job query failed"
        );
        return Err(AppServerProcessError::Spawn);
    }
    Ok(in_job != 0)
}
