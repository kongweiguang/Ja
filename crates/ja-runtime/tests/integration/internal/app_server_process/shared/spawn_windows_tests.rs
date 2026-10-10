// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::app_server_process::shared::windows_spawn::{process_application_name, spawn_detached};
use std::collections::BTreeMap;
use std::ffi::{OsStr, OsString, c_void};
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::os::windows::ffi::{OsStrExt, OsStringExt};
use std::os::windows::io::{FromRawHandle, RawHandle};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, Receiver};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

type Handle = *mut c_void;

const HANDLE_FLAG_INHERIT: u32 = 1;
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const REPORT_ENV: &str = "JA_TEST_SHARED_SPAWN_R36_REPORT";
const READY_ENV: &str = "JA_TEST_SHARED_SPAWN_R36_READY";
const RELEASE_ENV: &str = "JA_TEST_SHARED_SPAWN_R36_RELEASE";
const OVERLAY_ENV: &str = "JA_TEST_SHARED_SPAWN_R36_OVERLAY";
const RAW_ENV: &str = "JA_TEST_SHARED_SPAWN_R36_RAW";
const FAILURE_ENV: &str = "JA_TEST_SHARED_SPAWN_R36_FAILURE";
const LARGE_ENV: &str = "JA_TEST_SHARED_SPAWN_R36_LARGE";
const ISOLATED_PIPE_TEST_ENV: &str = "JA_TEST_SHARED_SPAWN_R36_ISOLATED";

/// 验证只转换语义明确的盘符 VerbatimDisk 路径，并逐 UTF-16 单元保留其余输入。
#[test]
fn process_application_name_only_maps_unambiguous_verbatim_drive_paths() {
    let terminated = |path: &OsStr| {
        path.encode_wide()
            .chain(std::iter::once(0))
            .collect::<Vec<_>>()
    };
    let ordinary = OsStr::new(r"C:\runtime\ja 0.2.exe");
    assert_eq!(
        process_application_name(OsStr::new(r"\\?\C:\runtime\ja 0.2.exe"))
            .expect("safe VerbatimDisk path"),
        terminated(ordinary)
    );

    let mut superscript_device = OsString::from(r"\\?\C:\folder\COM");
    superscript_device.push("\u{00b9}.txt");
    let long_path = OsString::from(format!(r"\\?\C:\{}\java.exe", "x".repeat(260)));
    let unchanged_paths = [
        OsString::from(r"\\?\UNC\server\share\ja.exe"),
        OsString::from(r"\\?\GLOBALROOT\Device\HarddiskVolume1\ja.exe"),
        OsString::from(r"\\?\C:\NUL\java.exe"),
        OsString::from(r"\\?\C:\folder\CONIN$\java.exe"),
        OsString::from(r"\\?\C:\folder\COM1.txt"),
        OsString::from(r"\\?\C:\folder\LPT9.log"),
        superscript_device,
        OsString::from(r"\\?\C:\folder\bad?name.exe"),
        OsString::from(r"\\?\C:\folder\trailing.\java.exe"),
        OsString::from(r"\\?\C:\folder\trailing \java.exe"),
        OsString::from(r"\\?\C:\folder\..\java.exe"),
        long_path,
    ];
    for path in unchanged_paths {
        assert_eq!(
            process_application_name(path.as_os_str()).expect("path remains representable"),
            terminated(path.as_os_str()),
            "path must retain its original UTF-16 spelling: {path:?}"
        );
    }

    let raw_units = [
        b'\\' as u16,
        b'\\' as u16,
        b'?' as u16,
        b'\\' as u16,
        b'C' as u16,
        b':' as u16,
        b'\\' as u16,
        b'd' as u16,
        b'i' as u16,
        b'r' as u16,
        b'\\' as u16,
        0x65c5,
        0xd800,
        b'\\' as u16,
        b'j' as u16,
        b'a' as u16,
        b'.' as u16,
        b'e' as u16,
        b'x' as u16,
        b'e' as u16,
    ];
    let raw_path = OsString::from_wide(&raw_units);
    let mut expected_raw_units = raw_units[4..].to_vec();
    expected_raw_units.push(0);
    assert_eq!(
        process_application_name(raw_path.as_os_str()).expect("UTF-16 path remains representable"),
        expected_raw_units
    );
}

#[repr(C)]
struct SecurityAttributes {
    length: u32,
    security_descriptor: *mut c_void,
    inherit_handle: i32,
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn CloseHandle(handle: Handle) -> i32;
    fn CreatePipe(
        read: *mut Handle,
        write: *mut Handle,
        attributes: *mut SecurityAttributes,
        size: u32,
    ) -> i32;
    fn GetCurrentProcess() -> Handle;
    fn GetProcessHandleCount(process: Handle, count: *mut u32) -> i32;
    fn SetHandleInformation(handle: Handle, mask: u32, flags: u32) -> i32;
}

/// 分别用普通与 VerbatimDisk 盘符启动真实测试子进程，并证明额外管道不会延长父方输出流。
#[test]
fn detached_child_does_not_keep_parent_output_pipes_open() {
    if std::env::var_os(ISOLATED_PIPE_TEST_ENV).is_none() {
        let executable = std::env::current_exe().expect("test executable");
        let status = std::process::Command::new(executable)
            .args([
                "--exact",
                "shared_spawn_scope::detached_child_does_not_keep_parent_output_pipes_open",
                "--nocapture",
                "--test-threads=1",
            ])
            .env(ISOLATED_PIPE_TEST_ENV, "1")
            .status()
            .expect("isolated pipe inheritance test starts");
        assert!(status.success(), "isolated pipe inheritance test failed: {status}");
        return;
    }

    // Keep inheritable probe handles out of the parallel test runner process.
    let root = temporary_root();
    fs::create_dir_all(&root).expect("isolated working directory");
    let report = root.join("child-report.bin");
    let ready = root.join("child-ready");
    let release = root.join("child-release");
    let _release_guard = ReleaseGuard(release.clone());
    let mut stdout_pipe = create_inheritable_pipe().expect("stdout inheritance probe");
    let mut stderr_pipe = create_inheritable_pipe().expect("stderr inheritance probe");

    let current_executable = std::env::current_exe().expect("test executable");
    let mut ordinary_executable = current_executable
        .as_os_str()
        .encode_wide()
        .collect::<Vec<_>>();
    let verbatim_prefix = [b'\\' as u16, b'\\' as u16, b'?' as u16, b'\\' as u16];
    if ordinary_executable.starts_with(&verbatim_prefix) {
        ordinary_executable.drain(..verbatim_prefix.len());
    }
    assert!(
        ordinary_executable.len() >= 3
            && matches!(ordinary_executable[0], 0x41..=0x5a | 0x61..=0x7a)
            && ordinary_executable[1] == b':' as u16
            && ordinary_executable[2] == b'\\' as u16,
        "test executable must use a drive-letter path"
    );
    let ordinary_executable = OsString::from_wide(&ordinary_executable);
    let mut verbatim_executable = verbatim_prefix.to_vec();
    verbatim_executable.extend(ordinary_executable.encode_wide());
    let verbatim_executable = OsString::from_wide(&verbatim_executable);
    let args = vec![
        OsString::from("--exact"),
        OsString::from("shared_spawn_scope::spawn_child_probe_process"),
        OsString::from("--nocapture"),
        OsString::from("--"),
        OsString::from("argument with spaces"),
        OsString::from("embedded \" quote"),
        OsString::from(r"C:\spawn path\trailing\"),
        OsString::new(),
    ];
    let ordinary_child = spawn_detached(
        ordinary_executable.as_os_str(),
        &args,
        &root,
        &BTreeMap::new(),
        CREATE_NO_WINDOW,
    )
    .expect("ordinary executable path starts");
    let mut ordinary_child = ordinary_child;
    ordinary_child
        .wait()
        .expect("ordinary executable child exits");

    let overlay = OsString::from("overlay value = preserved");
    let raw_value = OsString::from_wide(&[b'A' as u16, 0xd800, b'Z' as u16]);
    let large_value = OsString::from(format!("start{}end", "x".repeat(32_768)));
    let mut environment = std::env::vars_os().collect::<BTreeMap<_, _>>();
    let inherited_system_root = std::env::var_os("SystemRoot").expect("inherited SystemRoot");
    environment.retain(|name, _| {
        !name
            .to_str()
            .is_some_and(|name| name.eq_ignore_ascii_case("SystemRoot"))
    });
    environment.insert(
        OsString::from("pAtH"),
        OsString::from("JA_SPAWN_PATH_OVERLAY"),
    );
    environment.insert(OsString::from(OVERLAY_ENV), overlay.clone());
    environment.insert(OsString::from(RAW_ENV), raw_value.clone());
    environment.insert(OsString::from(LARGE_ENV), large_value);
    environment.insert(OsString::from(REPORT_ENV), report.as_os_str().to_owned());
    environment.insert(OsString::from(READY_ENV), ready.as_os_str().to_owned());
    environment.insert(OsString::from(RELEASE_ENV), release.as_os_str().to_owned());
    let missing_executable = root.join("missing executable.exe");
    environment.insert(
        OsString::from(FAILURE_ENV),
        missing_executable.as_os_str().to_owned(),
    );

    let child = spawn_detached(
        verbatim_executable.as_os_str(),
        &args,
        &root,
        &environment,
        CREATE_NO_WINDOW,
    )
    .expect("isolated test child starts");
    let waiter = thread::spawn(move || {
        let mut child = child;
        child.wait()
    });
    assert!(
        wait_for_path(&ready, Duration::from_secs(10)),
        "child did not become ready"
    );

    stdout_pipe.close_writer();
    stderr_pipe.close_writer();
    let stdout_eof = read_pipe_eof(stdout_pipe.take_reader());
    let stderr_eof = read_pipe_eof(stderr_pipe.take_reader());
    let stdout_closed_while_alive_result = stdout_eof.recv_timeout(Duration::from_secs(5));
    let stderr_closed_while_alive_result = stderr_eof.recv_timeout(Duration::from_secs(5));
    let stdout_closed_while_alive = matches!(&stdout_closed_while_alive_result, Ok(Ok(0)));
    let stderr_closed_while_alive = matches!(&stderr_closed_while_alive_result, Ok(Ok(0)));
    let child_alive_before_release = !waiter.is_finished();

    drop(_release_guard);
    let release_signaled = release.exists();
    let stdout_closed_after_release = stdout_closed_while_alive_result
        .or_else(|_| stdout_eof.recv_timeout(Duration::from_secs(5)));
    let stderr_closed_after_release = stderr_closed_while_alive_result
        .or_else(|_| stderr_eof.recv_timeout(Duration::from_secs(5)));
    let child_wait = waiter.join().expect("reaper thread");

    assert!(
        release_signaled,
        "test child release marker was not written"
    );
    assert!(
        child_alive_before_release,
        "child must stay alive until released"
    );
    assert!(
        stdout_closed_while_alive,
        "stdout must reach EOF before release"
    );
    assert!(
        stderr_closed_while_alive,
        "stderr must reach EOF before release"
    );
    assert_eq!(
        stdout_closed_after_release
            .expect("stdout reader completed")
            .expect("stdout read"),
        0
    );
    assert_eq!(
        stderr_closed_after_release
            .expect("stderr reader completed")
            .expect("stderr read"),
        0
    );
    child_wait.expect("child process wait");

    let mut report_file = File::open(&report).expect("child report");
    let fields = read_os_fields(&mut report_file).expect("child environment and arguments");
    assert_eq!(fields[0], root.as_os_str());
    assert_eq!(fields[1], inherited_system_root);
    assert_eq!(fields[2], OsStr::new("JA_SPAWN_PATH_OVERLAY"));
    assert_eq!(fields[3], overlay);
    assert_eq!(fields[4], raw_value);
    assert_eq!(fields[5], verbatim_executable);
    assert_eq!(
        &fields[6..10],
        [
            OsStr::new("argument with spaces"),
            OsStr::new("embedded \" quote"),
            OsStr::new(r"C:\spawn path\trailing\"),
            OsStr::new(""),
        ]
    );
    assert_eq!(
        read_u32(&mut report_file).expect("failure handle count before"),
        read_u32(&mut report_file).expect("failure handle count after")
    );
    for attempt in 0..3 {
        assert_eq!(
            read_i32(&mut report_file).expect("CreateProcess error code"),
            2,
            "failed start {attempt} must report ERROR_FILE_NOT_FOUND"
        );
    }

    fs::remove_dir_all(root).expect("fixture cleanup");
}

/// 被隔离启动的测试进程回传 argv[0]、参数、cwd 与 UTF-16 环境，再等待父方显式释放。
/// 缺失程序先失败一次作为预热，避免一次性初始化噪声进入严格句柄基线。
#[test]
fn spawn_child_probe_process() {
    let Some(report) = std::env::var_os(REPORT_ENV) else {
        return;
    };
    let report = PathBuf::from(report);
    let ready = PathBuf::from(std::env::var_os(READY_ENV).expect("ready marker path"));
    let release = PathBuf::from(std::env::var_os(RELEASE_ENV).expect("release marker path"));
    let overlay = std::env::var_os(OVERLAY_ENV).expect("overlay variable");
    let raw = std::env::var_os(RAW_ENV).expect("raw UTF-16 variable");
    let large = std::env::var_os(LARGE_ENV).expect("large environment fixture");
    let large_text = large.to_str().expect("ASCII large environment fixture");
    assert_eq!(large.encode_wide().count(), 32_776);
    assert!(large_text.starts_with("start"));
    assert!(large_text.ends_with("end"));
    let mut fields = vec![
        std::env::current_dir()
            .expect("child current directory")
            .into_os_string(),
        std::env::var_os("SystemRoot").expect("inherited SystemRoot"),
        std::env::var_os("PATH").expect("case-insensitive PATH overlay"),
        overlay,
        raw,
    ];
    let process_args = std::env::args_os().collect::<Vec<_>>();
    fields.push(process_args.first().expect("argv[0]").clone());
    fields.extend(process_args.into_iter().skip(5));
    let missing_executable =
        PathBuf::from(std::env::var_os(FAILURE_ENV).expect("missing executable"));
    let current_directory = std::env::current_dir().expect("child current directory");
    let warm_up_failure = spawn_detached(
        missing_executable.as_os_str(),
        &[],
        &current_directory,
        &BTreeMap::new(),
        CREATE_NO_WINDOW,
    )
    .err()
    .expect("missing executable warm-up must fail");
    assert_eq!(
        warm_up_failure.raw_os_error(),
        Some(2),
        "warm-up must report ERROR_FILE_NOT_FOUND"
    );

    let before = process_handle_count().expect("handle count before failed starts");
    let mut failure_codes = [0_i32; 3];
    for failure_code in &mut failure_codes {
        let failure = spawn_detached(
            missing_executable.as_os_str(),
            &[],
            &current_directory,
            &BTreeMap::new(),
            CREATE_NO_WINDOW,
        )
        .err()
        .expect("missing executable must fail");
        *failure_code = failure.raw_os_error().unwrap_or(-1);
    }
    let after = process_handle_count().expect("handle count after failed starts");

    let mut report_file = File::create(report).expect("create child report");
    write_os_fields(&mut report_file, &fields).expect("write child report");
    write_u32(&mut report_file, before).expect("write initial handle count");
    write_u32(&mut report_file, after).expect("write final handle count");
    for failure_code in failure_codes {
        write_i32(&mut report_file, failure_code).expect("write CreateProcess error");
    }
    report_file.flush().expect("flush child report");
    drop(report_file);
    fs::write(ready, b"ready").expect("publish child readiness");

    let deadline = Instant::now() + Duration::from_secs(15);
    while !release.exists() && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(10));
    }
}

/// 创建临时目录名含空格，确保 current directory 也经过实际 Windows UTF-16 启动路径。
fn temporary_root() -> PathBuf {
    let ticks = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after epoch")
        .as_nanos();
    std::env::temp_dir().join(format!(
        "ja shared spawn r36 {} {ticks}",
        std::process::id()
    ))
}

/// 创建读写端均可继承的匿名管道，并将父方读端改为不可继承以收窄被测句柄。
fn create_inheritable_pipe() -> io::Result<PipePair> {
    let mut attributes = SecurityAttributes {
        length: std::mem::size_of::<SecurityAttributes>() as u32,
        security_descriptor: std::ptr::null_mut(),
        inherit_handle: 1,
    };
    let mut reader = std::ptr::null_mut();
    let mut writer = std::ptr::null_mut();
    if unsafe { CreatePipe(&mut reader, &mut writer, &mut attributes, 0) } == 0 {
        return Err(io::Error::last_os_error());
    }
    let reader = TestHandle(reader);
    let writer = TestHandle(writer);
    if unsafe { SetHandleInformation(reader.raw(), HANDLE_FLAG_INHERIT, 0) } == 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(PipePair {
        reader: Some(unsafe { File::from_raw_handle(reader.take() as RawHandle) }),
        writer: Some(writer),
    })
}

/// 用独立 reader thread 做有界 EOF 观测；若子进程持有 writer，调用方超时后仍可释放子进程。
fn read_pipe_eof(mut reader: File) -> Receiver<io::Result<usize>> {
    let (sender, receiver) = mpsc::channel();
    thread::spawn(move || {
        let mut byte = [0_u8; 1];
        let _ = sender.send(reader.read(&mut byte));
    });
    receiver
}

/// 等待唯一子进程发布 ready 文件，避免把尚未执行的 CreateProcess 当作存活证据。
fn wait_for_path(path: &Path, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if path.exists() {
            return true;
        }
        thread::sleep(Duration::from_millis(10));
    }
    path.exists()
}

/// 编码 OsString 的原始 UTF-16 单元，使非 Unicode surrogate 在跨进程断言中可见。
fn write_os_fields(writer: &mut impl Write, fields: &[OsString]) -> io::Result<()> {
    write_u32(writer, fields.len() as u32)?;
    for field in fields {
        let wide = field.encode_wide().collect::<Vec<_>>();
        write_u32(writer, wide.len() as u32)?;
        for unit in wide {
            writer.write_all(&unit.to_le_bytes())?;
        }
    }
    Ok(())
}

/// 读取子进程提交的有界 UTF-16 字段，并重建 Windows OsString 供逐值比较。
fn read_os_fields(reader: &mut impl Read) -> io::Result<Vec<OsString>> {
    let count = read_u32(reader)? as usize;
    let mut fields = Vec::with_capacity(count);
    for _ in 0..count {
        let length = read_u32(reader)? as usize;
        let mut wide = Vec::with_capacity(length);
        for _ in 0..length {
            let mut unit = [0_u8; 2];
            reader.read_exact(&mut unit)?;
            wide.push(u16::from_le_bytes(unit));
        }
        fields.push(OsString::from_wide(&wide));
    }
    Ok(fields)
}

/// 将固定 32 位长度按 little-endian 写入测试证据流。
fn write_u32(writer: &mut impl Write, value: u32) -> io::Result<()> {
    writer.write_all(&value.to_le_bytes())
}

/// 从测试证据流恢复固定 32 位 little-endian 值。
fn read_u32(reader: &mut impl Read) -> io::Result<u32> {
    let mut bytes = [0_u8; 4];
    reader.read_exact(&mut bytes)?;
    Ok(u32::from_le_bytes(bytes))
}

/// 将原始 Windows 错误码按有符号位型写入证据，保留 -1 表示缺失错误码。
fn write_i32(writer: &mut impl Write, value: i32) -> io::Result<()> {
    writer.write_all(&value.to_le_bytes())
}

/// 读取有符号 Windows 错误码，避免测试把错误传播折叠成单一失败分类。
fn read_i32(reader: &mut impl Read) -> io::Result<i32> {
    let mut bytes = [0_u8; 4];
    reader.read_exact(&mut bytes)?;
    Ok(i32::from_le_bytes(bytes))
}

/// 读取当前进程句柄数，用重复失败启动核对 NUL 与进程属性资源均已释放。
fn process_handle_count() -> io::Result<u32> {
    let mut count = 0_u32;
    if unsafe { GetProcessHandleCount(GetCurrentProcess(), &mut count) } == 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(count)
}

/// 所有者在测试提前 panic 时仍释放子进程，避免遗留长驻 probe。
struct ReleaseGuard(PathBuf);

impl Drop for ReleaseGuard {
    /// 文件标记只控制本测试子进程，不接触其他 App Server 或系统进程。
    fn drop(&mut self) {
        let _ = fs::write(&self.0, b"release");
    }
}

/// 仅在当前测试独占一个 Kernel32 handle，失败路径按同一 RAII owner 关闭。
struct TestHandle(Handle);

impl TestHandle {
    /// 转交一次原始 handle 所有权，后续由 File 或目标进程负责关闭。
    fn take(self) -> Handle {
        let raw = self.0;
        std::mem::forget(self);
        raw
    }

    /// 仅供 SetHandleInformation 借用，不改变 RAII 所有权。
    fn raw(&self) -> Handle {
        self.0
    }
}

impl Drop for TestHandle {
    /// 即使 pipe 后续设置失败，也关闭 CreatePipe 已返回的两个 handle。
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

/// 父方只在 spawn 返回后关闭 writer；reader 转入独立线程等待真正 EOF。
struct PipePair {
    reader: Option<File>,
    writer: Option<TestHandle>,
}

impl PipePair {
    /// 关闭父方 writer 后，EOF 取决于子进程是否误继承了额外副本。
    fn close_writer(&mut self) {
        self.writer.take();
    }

    /// 将 reader 唯一移交给阻塞读取线程。
    fn take_reader(&mut self) -> File {
        self.reader.take().expect("pipe reader owner")
    }
}
