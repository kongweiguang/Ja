// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! CLI 到 Java owner 的窄协议适配；任何请求错误都不被当作成功结果继续展示。

use ja_runtime::app_server_process::{
    AppServerProcessError, EventPump, SharedAppServerClient, SidecarConfig,
};
use serde_json::{Value, json};
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use super::CliError;

const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
const TURN_REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

pub struct Connection {
    client: SharedAppServerClient,
    replaced: bool,
}

impl Connection {
    /// 按需接入单一 Java owner；进程生命周期由共享后台拥有，CLI 退出只释放连接。
    pub fn connect_or_start() -> Result<Self, CliError> {
        let config = sidecar_config()?;
        let client = SharedAppServerClient::connect_or_start(config, 1).map_err(connect_error)?;
        Ok(Self {
            client,
            replaced: false,
        })
    }

    /// 状态与停止操作只连接已有后台，避免只读管理命令意外启动服务。
    pub fn connect_existing() -> Result<Self, CliError> {
        let config = sidecar_config()?;
        let client = SharedAppServerClient::connect_existing(&config, 1).map_err(connect_error)?;
        Ok(Self {
            client,
            replaced: false,
        })
    }

    /// JA-RPC response 只接受正常 result；保留服务端错误码供调用方识别冲突和缺少输入。
    pub fn request(&mut self, method: &str, params: Value) -> Result<Value, CliError> {
        let timeout = if method.starts_with("turn/") {
            TURN_REQUEST_TIMEOUT
        } else {
            REQUEST_TIMEOUT
        };
        self.request_with_timeout(method, params, timeout)
    }

    /// 有界目录扫描把单次 RPC 也限制在剩余 deadline 内，避免页数上限被慢请求绕过。
    pub fn request_with_timeout(
        &mut self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, CliError> {
        if timeout.is_zero() {
            return Err(CliError::transport("目录读取已超时"));
        }
        let frame = self
            .client
            .request(method, params, timeout)
            .map_err(|error| CliError::transport(format!("{method}: {error}")))?;
        if let Some(error) = frame.error() {
            return Err(CliError::rpc(
                error.code(),
                format!("{method}: {}", error.message()),
            ));
        }
        frame
            .result()
            .value()
            .cloned()
            .ok_or_else(|| CliError::protocol("服务端响应缺少 result"))
    }

    /// 会产生副作用的请求由客户端操作 ID 关联持久 ledger；ACK 丢失后只查询，不自动重发。
    pub fn request_operation(
        &mut self,
        method: &str,
        mut params: Value,
    ) -> Result<Value, CliError> {
        if !matches!(
            method,
            "turn/start"
                | "turn/continue"
                | "turn/reask"
                | "approval/respond"
                | "turn/input/enqueue"
        ) {
            return Err(CliError::protocol("该方法没有操作账本合同"));
        }
        let id = format!("op_{}", uuid::Uuid::new_v4().simple());
        let object = params
            .as_object_mut()
            .ok_or_else(|| CliError::protocol("操作参数必须是对象"))?;
        object.insert("clientOperationId".into(), Value::String(id.clone()));
        match self.request(method, params) {
            Ok(result) => Ok(result),
            Err(error) if error.rpc_code.is_some() => Err(error),
            Err(_) => {
                if let Ok(value) = self.lookup_operation(&id, method) {
                    return Ok(value);
                }
                let mut probe = Connection::connect_existing()
                    .map_err(|_| CliError::uncertain_operation(&id))?;
                let lookup = probe.lookup_operation(&id, method);
                self.client = probe.client;
                self.replaced = true;
                lookup
            }
        }
    }

    /// ledger 的 unknown 不证明未执行；只接受同 method 的 committed 原回执。
    fn lookup_operation(&mut self, id: &str, method: &str) -> Result<Value, CliError> {
        let lookup = self
            .request("operation/read", json!({"clientOperationId":id}))
            .map_err(|_| CliError::uncertain_operation(id))?;
        match lookup.get("status").and_then(Value::as_str) {
            Some("unknown") => Err(CliError::uncertain_operation(id)),
            Some("committed") if lookup.get("method").and_then(Value::as_str) == Some(method) => {
                lookup
                    .get("result")
                    .filter(|value| value.is_object())
                    .cloned()
                    .ok_or_else(|| CliError::protocol("操作账本缺少原成功回执"))
            }
            _ => Err(CliError::protocol("操作账本与原请求不一致")),
        }
    }

    /// 发生连接替换后，事件消费者必须在新连接重新 observe 并取新的 pump。
    pub fn take_replaced(&mut self) -> bool {
        std::mem::take(&mut self.replaced)
    }

    /// 观察必须先于读取基线建立，以便读取期间的事件留在同一连接队列中。
    pub fn observe(&mut self, thread_id: &str) -> Result<(), CliError> {
        let result = self.request("thread/observe", json!({"threadId":thread_id}))?;
        if result.get("accepted").and_then(Value::as_bool) != Some(true)
            || result.get("threadId").and_then(Value::as_str) != Some(thread_id)
        {
            return Err(CliError::protocol("thread/observe 回执与请求不符"));
        }
        Ok(())
    }

    /// 明确取消观察关系；断线仍由后台连接清理兜底。
    pub fn unobserve(&mut self, thread_id: &str) -> Result<(), CliError> {
        let result = self.request("thread/unobserve", json!({"threadId":thread_id}))?;
        if result.get("accepted").and_then(Value::as_bool) != Some(true)
            || result.get("threadId").and_then(Value::as_str) != Some(thread_id)
        {
            return Err(CliError::protocol("thread/unobserve 回执与请求不符"));
        }
        Ok(())
    }

    /// 事件泵只有一个消费者，CLI controller 将其与请求通道分离以免响应抢占通知。
    pub fn take_event_pump(&mut self) -> Result<EventPump, CliError> {
        self.client.take_event_pump().map_err(CliError::transport)
    }

    /// 只经 Java 的安全关闭协议执行 stop；force 也由 Java 校验并有界取消。
    pub fn stop_server(&mut self, force: bool) -> Result<(), CliError> {
        if force {
            let result = self.request("runtime/shutdown", json!({"force":true}))?;
            if result.get("accepted").and_then(Value::as_bool) != Some(true)
                || result.get("status").and_then(Value::as_str) != Some("shutting_down")
            {
                return Err(CliError::protocol("后台未确认停止"));
            }
            return Ok(());
        }
        self.client
            .stop_server(Duration::from_secs(15))
            .map_err(CliError::transport)
    }

    /// 显式连接释放与 TUI 终端恢复可并行收口，任何一端都不拥有后台执行树。
    pub fn disconnect(&mut self) -> Result<(), CliError> {
        self.client
            .disconnect_until(Instant::now() + Duration::from_secs(3))
            .map_err(CliError::transport)
    }

    /// 实例身份用于 JSONL 终态和断线诊断，绝不携带认证令牌。
    pub fn server_instance_id(&self) -> &str {
        self.client.server_instance_id()
    }
}

/// 握手阶段断连多见于旧后台拒绝新方法目录；给出可操作诊断且不自动结束可能仍有任务的 owner。
fn connect_error(error: AppServerProcessError) -> CliError {
    if matches!(
        error,
        AppServerProcessError::SessionClosed | AppServerProcessError::HandshakeFailed
    ) {
        CliError::transport(
            "App Server 握手被关闭；可能是后台与当前 CLI 版本不匹配。请先完成活动任务，再使用配套版本重启后台",
        )
    } else {
        CliError::transport(error)
    }
}

/// 与桌面共用 ~/.ja 四目录；开发调试 JAR 仅由桌面既有 JA_DEBUG_* 显式指定。
fn sidecar_config() -> Result<SidecarConfig, CliError> {
    let home = runtime_home()?;
    let data = home.join("data");
    let run = home.join("run");
    let log = home.join("logs").join("java");
    for directory in [&home, &data, &run, &log] {
        ensure_directory_tree(directory)?;
    }
    let home = canonical_dir(&home)?;
    let data = canonical_dir(&data)?;
    let run = canonical_dir(&run)?;
    let log = canonical_dir(&log)?;
    let debug_java = std::env::var_os("JA_DEBUG_JAVA");
    let debug_jar = std::env::var_os("JA_DEBUG_JAR");
    let executable = match (&debug_java, &debug_jar) {
        (Some(java), Some(_)) => PathBuf::from(java),
        (None, None) => bundled_sidecar_path()?,
        _ => {
            return Err(CliError::configuration(
                "开发态需要同时指定 JA_DEBUG_JAVA 与 JA_DEBUG_JAR",
            ));
        }
    };
    let executable = fs::canonicalize(executable)
        .map_err(|_| CliError::configuration("找不到 Ja App Server 可执行文件"))?;
    let mut config = SidecarConfig::with_shared_directories(executable, home, data, run, log)
        .map_err(CliError::transport)?;
    if let Some(jar) = debug_jar {
        let jar = fs::canonicalize(PathBuf::from(jar))
            .map_err(|_| CliError::configuration("找不到 JA_DEBUG_JAR 指定的 JAR"))?;
        config
            .args
            .splice(0..0, [OsString::from("-jar"), jar.into_os_string()]);
    }
    Ok(config)
}

/// 开发/隔离验证可显式重定向整个 Ja 根；release 固定使用当前用户的真实产品目录。
pub(super) fn runtime_home() -> Result<PathBuf, CliError> {
    #[cfg(debug_assertions)]
    if let Some(root) = std::env::var_os("JA_E2E_RUNTIME_ROOT") {
        let root = PathBuf::from(root);
        if !root.is_absolute() || root.parent().is_none() {
            return Err(CliError::configuration(
                "JA_E2E_RUNTIME_ROOT 必须是非根绝对目录",
            ));
        }
        return Ok(root);
    }
    let profile = std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .ok_or_else(|| CliError::configuration("找不到用户 Profile 目录"))?;
    Ok(PathBuf::from(profile).join(".ja"))
}

/// 统一 canonical 边界，避免同一用户目录经不同拼写生成第二份 endpoint 身份。
fn canonical_dir(path: &Path) -> Result<PathBuf, CliError> {
    fs::canonicalize(path).map_err(|_| CliError::configuration("Ja 运行目录无法规范化"))
}

/// 逐层拒绝符号链接与 Windows reparse point，然后逐层创建；endpoint/token 不能被目录别名重定向。
pub(super) fn ensure_directory_tree(path: &Path) -> Result<(), CliError> {
    if !path.is_absolute() || path.parent().is_none() {
        return Err(CliError::configuration("Ja 运行目录边界无效"));
    }
    let mut missing = Vec::new();
    let mut current = path.to_path_buf();
    loop {
        validate_existing_ancestors(&current)?;
        match fs::symlink_metadata(&current) {
            Ok(metadata) => {
                if !metadata.is_dir() || is_reparse_point(&metadata) {
                    return Err(CliError::configuration("Ja 运行目录包含重解析点或非目录"));
                }
                break;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                missing.push(current.clone());
                let parent = current
                    .parent()
                    .ok_or_else(|| CliError::configuration("Ja 运行目录边界无效"))?;
                if parent == current {
                    return Err(CliError::configuration("Ja 运行目录边界无效"));
                }
                current = parent.to_path_buf();
            }
            Err(_) => return Err(CliError::configuration("Ja 运行目录无法检查")),
        }
    }
    for directory in missing.iter().rev() {
        match fs::create_dir(directory) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(_) => return Err(CliError::configuration("无法建立 Ja 运行目录")),
        }
        validate_existing_ancestors(directory)?;
    }
    validate_existing_ancestors(path)
}

/// 每次检查所有祖先；只检查末级会漏掉 `USERPROFILE` 与 `.ja` 上的 junction。
fn validate_existing_ancestors(path: &Path) -> Result<(), CliError> {
    for candidate in path.ancestors() {
        match fs::symlink_metadata(candidate) {
            Ok(metadata) if metadata.is_dir() && !is_reparse_point(&metadata) => {}
            Ok(_) => return Err(CliError::configuration("Ja 运行目录包含重解析点或非目录")),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(CliError::configuration("Ja 运行目录无法检查")),
        }
    }
    Ok(())
}

/// Windows junction 和 symlink 均带 reparse 标志；Unix 由 symlink_metadata 的 file type 拒绝。
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    if metadata.file_type().is_symlink() {
        return true;
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        metadata.file_attributes() & 0x400 != 0
    }
    #[cfg(not(windows))]
    {
        false
    }
}

/// 发行包仅加载同架构的固定 sidecar 资源；不从 PATH 搜索不明可执行文件。
fn bundled_sidecar_path() -> Result<PathBuf, CliError> {
    let current =
        std::env::current_exe().map_err(|_| CliError::configuration("无法定位 ja 可执行文件"))?;
    let parent = current
        .parent()
        .ok_or_else(|| CliError::configuration("ja 可执行文件目录无效"))?;
    Ok(parent.join(sidecar_resource_name()))
}

/// 与桌面构建资源名保持一致；其他架构显式失败，避免运行错误平台的后端。
fn sidecar_resource_name() -> &'static str {
    #[cfg(all(windows, target_arch = "x86_64"))]
    {
        "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe"
    }
    #[cfg(all(windows, target_arch = "aarch64"))]
    {
        "sidecars/ja-app-server-aarch64-pc-windows-msvc.exe"
    }
    #[cfg(all(target_os = "macos", target_arch = "x86_64"))]
    {
        "sidecars/ja-app-server-x86_64-apple-darwin"
    }
    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    {
        "sidecars/ja-app-server-aarch64-apple-darwin"
    }
    #[cfg(all(not(windows), not(target_os = "macos"), target_arch = "x86_64"))]
    {
        "sidecars/ja-app-server-x86_64-unknown-linux-gnu"
    }
    #[cfg(not(any(
        all(windows, any(target_arch = "x86_64", target_arch = "aarch64")),
        all(
            target_os = "macos",
            any(target_arch = "x86_64", target_arch = "aarch64")
        ),
        all(not(windows), not(target_os = "macos"), target_arch = "x86_64")
    )))]
    {
        "sidecars/ja-app-server-unsupported-target"
    }
}
