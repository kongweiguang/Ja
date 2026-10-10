// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 共享后台只继承本次创建的三个 NUL 标准句柄，避免持有宿主 CLI 的管道写端。

use std::cell::Cell;
use std::cmp::Ordering;
use std::collections::BTreeMap;
use std::ffi::{OsStr, OsString, c_void};
use std::io;
use std::mem::{size_of, zeroed};
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
use std::path::Path;

type Handle = *mut c_void;

const CREATE_UNICODE_ENVIRONMENT: u32 = 0x0000_0400;
const EXTENDED_STARTUPINFO_PRESENT: u32 = 0x0008_0000;
const STARTF_USESTDHANDLES: u32 = 0x0000_0100;
const PROC_THREAD_ATTRIBUTE_HANDLE_LIST: usize = 0x0002_0002;
const FILE_ATTRIBUTE_NORMAL: u32 = 0x0000_0080;
const FILE_SHARE_READ_WRITE: u32 = 0x0000_0003;
const GENERIC_READ: u32 = 0x8000_0000;
const GENERIC_WRITE: u32 = 0x4000_0000;
const OPEN_EXISTING: u32 = 3;
const WAIT_OBJECT_0: u32 = 0;
const INFINITE: u32 = u32::MAX;
const CSTR_LESS_THAN: i32 = 1;
const CSTR_EQUAL: i32 = 2;
const CSTR_GREATER_THAN: i32 = 3;
const MAX_WINDOWS_PATH: usize = 260;
const MAX_WINDOWS_COMMAND_LINE: usize = 32_767;
const INVALID_HANDLE_VALUE: Handle = -1_isize as Handle;

#[repr(C)]
struct SecurityAttributes {
    length: u32,
    security_descriptor: *mut c_void,
    inherit_handle: i32,
}

#[repr(C)]
struct StartupInfoW {
    cb: u32,
    reserved: *mut u16,
    desktop: *mut u16,
    title: *mut u16,
    x: u32,
    y: u32,
    x_size: u32,
    y_size: u32,
    x_count_chars: u32,
    y_count_chars: u32,
    fill_attribute: u32,
    flags: u32,
    show_window: u16,
    reserved2_size: u16,
    reserved2: *mut u8,
    stdin: Handle,
    stdout: Handle,
    stderr: Handle,
}

#[repr(C)]
struct StartupInfoExW {
    startup_info: StartupInfoW,
    attribute_list: *mut c_void,
}

#[repr(C)]
struct ProcessInformation {
    process: Handle,
    thread: Handle,
    process_id: u32,
    thread_id: u32,
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn CompareStringOrdinal(
        string1: *const u16,
        count1: i32,
        string2: *const u16,
        count2: i32,
        ignore_case: i32,
    ) -> i32;
    fn CreateFileW(
        file_name: *const u16,
        desired_access: u32,
        share_mode: u32,
        security_attributes: *const c_void,
        creation_disposition: u32,
        flags_and_attributes: u32,
        template_file: Handle,
    ) -> Handle;
    fn CreateProcessW(
        application_name: *const u16,
        command_line: *mut u16,
        process_attributes: *mut SecurityAttributes,
        thread_attributes: *mut SecurityAttributes,
        inherit_handles: i32,
        creation_flags: u32,
        environment: *mut c_void,
        current_directory: *const u16,
        startup_info: *mut StartupInfoExW,
        process_information: *mut ProcessInformation,
    ) -> i32;
    fn DeleteProcThreadAttributeList(attribute_list: *mut c_void);
    fn FreeEnvironmentStringsW(environment: *mut u16) -> i32;
    fn GetEnvironmentStringsW() -> *mut u16;
    fn InitializeProcThreadAttributeList(
        attribute_list: *mut c_void,
        attribute_count: u32,
        flags: u32,
        size: *mut usize,
    ) -> i32;
    fn UpdateProcThreadAttribute(
        attribute_list: *mut c_void,
        flags: u32,
        attribute: usize,
        value: *mut c_void,
        size: usize,
        previous_value: *mut c_void,
        return_size: *mut usize,
    ) -> i32;
    fn WaitForSingleObject(handle: Handle, milliseconds: u32) -> u32;
}

/// 持有 CreateProcessW 返回的 process handle，直到唯一 reaper 完成等待。
pub(crate) struct SpawnedProcess {
    process: OwnedHandle,
}

impl SpawnedProcess {
    /// reaper 等待后台退出并释放句柄；等待失败由调用方沿用既有尽力清理语义。
    pub(crate) fn wait(&mut self) -> io::Result<()> {
        let result = unsafe { WaitForSingleObject(self.process.as_raw_handle(), INFINITE) };
        if result == WAIT_OBJECT_0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }
}

/// 将成功取得的 Kernel32 handle 转交标准 owner，确保 reaper 线程安全持有且只关闭一次。
fn own_handle(handle: Handle) -> OwnedHandle {
    // SAFETY: 调用方仅传入成功 API 返回且尚未交给其他 owner 的有效 handle。
    unsafe { OwnedHandle::from_raw_handle(handle) }
}

/// 在不改写进程级 inherit flag 的前提下启动后台，并把继承范围限制到三个新 NUL handle。
/// 环境从宿主原生 UTF-16 block 开始，再应用与 `Command.envs` 相同的继承式覆盖语义。
pub(crate) fn spawn_detached(
    executable: &OsStr,
    args: &[OsString],
    current_directory: &Path,
    environment_overlays: &BTreeMap<OsString, OsString>,
    creation_flags: u32,
) -> io::Result<SpawnedProcess> {
    let mut command_line = command_line(executable, args)?;
    let application_name = process_application_name(executable)?;
    let current_directory = null_terminated(current_directory.as_os_str())?;
    let mut environment = environment_block(environment_overlays)?;

    let security = SecurityAttributes {
        length: size_of::<SecurityAttributes>() as u32,
        security_descriptor: std::ptr::null_mut(),
        inherit_handle: 1,
    };
    let stdin = open_null(GENERIC_READ, &security)?;
    let stdout = open_null(GENERIC_WRITE, &security)?;
    let stderr = open_null(GENERIC_WRITE, &security)?;
    let standard_handles = [
        stdin.as_raw_handle(),
        stdout.as_raw_handle(),
        stderr.as_raw_handle(),
    ];
    let attributes = AttributeList::new(&standard_handles)?;

    let mut startup: StartupInfoExW = unsafe { zeroed() };
    startup.startup_info.cb = size_of::<StartupInfoExW>() as u32;
    startup.startup_info.flags = STARTF_USESTDHANDLES;
    startup.startup_info.stdin = stdin.as_raw_handle();
    startup.startup_info.stdout = stdout.as_raw_handle();
    startup.startup_info.stderr = stderr.as_raw_handle();
    startup.attribute_list = attributes.raw();

    let mut process_information: ProcessInformation = unsafe { zeroed() };
    let created = unsafe {
        CreateProcessW(
            application_name.as_ptr(),
            command_line.as_mut_ptr(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            1,
            creation_flags | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT,
            environment.as_mut_ptr().cast(),
            current_directory.as_ptr(),
            &mut startup,
            &mut process_information,
        )
    };
    if created == 0 {
        return Err(io::Error::last_os_error());
    }

    let process = own_handle(process_information.process);
    let thread = own_handle(process_information.thread);
    drop(thread);
    Ok(SpawnedProcess { process })
}

/// 仅为可由普通 Win32 路径无歧义表达且未超 MAX_PATH 的应用名去前缀，argv[0] 保留原路径。
pub(crate) fn process_application_name(executable: &OsStr) -> io::Result<Vec<u16>> {
    const VERBATIM_PREFIX: [u16; 4] = [b'\\' as u16, b'\\' as u16, b'?' as u16, b'\\' as u16];
    let path = executable.encode_wide().collect::<Vec<_>>();
    if path.contains(&0) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Windows process path contains NUL",
        ));
    }

    let is_verbatim_drive_path = path.starts_with(&VERBATIM_PREFIX)
        && path.len() >= 7
        && matches!(path[4], 0x41..=0x5a | 0x61..=0x7a)
        && path[5] == b':' as u16
        && path[6] == b'\\' as u16;
    let can_remove_prefix = is_verbatim_drive_path
        && path[4..].len() < MAX_WINDOWS_PATH
        && path[7..]
            .split(|unit| *unit == b'\\' as u16)
            .all(is_normal_windows_component);

    let mut application_name = if can_remove_prefix {
        path[4..].to_vec()
    } else {
        path
    };
    application_name.push(0);
    Ok(application_name)
}

/// 仅接受普通 Win32 组件，避免去掉前缀后改变点段、尾空格、特殊字符或设备名语义。
fn is_normal_windows_component(component: &[u16]) -> bool {
    if component.is_empty()
        || component == [b'.' as u16]
        || component == [b'.' as u16, b'.' as u16]
        || component
            .last()
            .is_some_and(|unit| *unit == b'.' as u16 || *unit == b' ' as u16)
        || component.iter().any(|unit| {
            matches!(
                *unit,
                0x00..=0x1f | 0x22 | 0x2a | 0x2f | 0x3a | 0x3c | 0x3e | 0x3f | 0x5c | 0x7c
            )
        })
    {
        return false;
    }

    !is_reserved_device_component(component)
}

/// 普通 Win32 会把 DOS 保留设备名及带扩展名的形式解析成设备，而 verbatim 会保留文件名。
fn is_reserved_device_component(component: &[u16]) -> bool {
    let mut base_name = component
        .split(|unit| *unit == b'.' as u16)
        .next()
        .unwrap_or_default();
    while base_name.last() == Some(&(b' ' as u16)) {
        base_name = &base_name[..base_name.len() - 1];
    }

    const FIXED_DEVICE_NAMES: [&[u8]; 6] = [b"CON", b"PRN", b"AUX", b"NUL", b"CONIN$", b"CONOUT$"];
    if FIXED_DEVICE_NAMES
        .iter()
        .any(|name| equals_ascii_ignore_case(base_name, name))
    {
        return true;
    }

    base_name.len() == 4
        && (equals_ascii_ignore_case(&base_name[..3], b"COM")
            || equals_ascii_ignore_case(&base_name[..3], b"LPT"))
        && matches!(base_name[3], 0x31..=0x39 | 0x00b2 | 0x00b3 | 0x00b9)
}

/// 比较 UTF-16 中的 ASCII 设备名，不把路径经 lossy 字符串转换后再作安全判断。
fn equals_ascii_ignore_case(value: &[u16], expected: &[u8]) -> bool {
    value.len() == expected.len()
        && value.iter().zip(expected).all(|(actual, expected)| {
            u8::try_from(*actual).is_ok_and(|actual| actual.eq_ignore_ascii_case(expected))
        })
}

/// 为三个标准流分别打开可继承的 NUL 句柄，避免输出共享宿主管道。
fn open_null(access: u32, security: &SecurityAttributes) -> io::Result<OwnedHandle> {
    let path = [b'N' as u16, b'U' as u16, b'L' as u16, 0];
    let handle = unsafe {
        CreateFileW(
            path.as_ptr(),
            access,
            FILE_SHARE_READ_WRITE,
            security as *const SecurityAttributes as *const c_void,
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL,
            std::ptr::null_mut(),
        )
    };
    if handle == INVALID_HANDLE_VALUE || handle.is_null() {
        return Err(io::Error::last_os_error());
    }
    Ok(own_handle(handle))
}

/// 初始化线程属性内存并只注册本次创建的标准句柄，阻止其他 inheritable handle 被子进程捎带。
struct AttributeList {
    pointer: *mut c_void,
    _storage: Vec<usize>,
}

impl AttributeList {
    /// 用对齐的本地缓冲区承载系统不透明结构，Drop 与创建失败路径共用同一释放责任。
    fn new(handles: &[Handle]) -> io::Result<Self> {
        let mut bytes = 0_usize;
        unsafe {
            InitializeProcThreadAttributeList(std::ptr::null_mut(), 1, 0, &mut bytes);
        }
        if bytes == 0 {
            return Err(io::Error::last_os_error());
        }
        let words = bytes.div_ceil(size_of::<usize>());
        let mut storage = vec![0_usize; words];
        let pointer = storage.as_mut_ptr().cast::<c_void>();
        if unsafe { InitializeProcThreadAttributeList(pointer, 1, 0, &mut bytes) } == 0 {
            return Err(io::Error::last_os_error());
        }
        let list = Self {
            pointer,
            _storage: storage,
        };
        let updated = unsafe {
            UpdateProcThreadAttribute(
                list.pointer,
                0,
                PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                handles.as_ptr() as *mut c_void,
                std::mem::size_of_val(handles),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        };
        if updated == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(list)
    }

    /// 在 CreateProcessW 调用期间借用属性列表指针，实际存储仍由本对象持有。
    fn raw(&self) -> *mut c_void {
        self.pointer
    }
}

impl Drop for AttributeList {
    /// DeleteProcThreadAttributeList 只销毁不透明内容，随后 Vec 自动释放对齐缓冲区。
    fn drop(&mut self) {
        unsafe {
            DeleteProcThreadAttributeList(self.pointer);
        }
    }
}

/// 编码当前 Windows 环境后应用大小写不敏感覆盖；未出现在覆盖表中的变量继续继承。
fn environment_block(overlays: &BTreeMap<OsString, OsString>) -> io::Result<Vec<u16>> {
    let mut entries = inherited_environment()?;
    for (name, value) in overlays {
        let name = name.encode_wide().collect::<Vec<_>>();
        let value = value.encode_wide().collect::<Vec<_>>();
        validate_environment_entry(&name, &value)?;
        let mut index = 0;
        while index < entries.len() {
            if compare_ordinal_ignore_case(&entries[index].name, &name)? == Ordering::Equal {
                entries.remove(index);
            } else {
                index += 1;
            }
        }
        entries.push(EnvironmentEntry { name, value });
    }

    let comparison_failed = Cell::new(false);
    entries.sort_by(
        |left, right| match compare_ordinal_ignore_case(&left.name, &right.name) {
            Ok(ordering) => ordering.then_with(|| left.name.cmp(&right.name)),
            Err(_) => {
                comparison_failed.set(true);
                left.name.cmp(&right.name)
            }
        },
    );
    if comparison_failed.get() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "environment variable name cannot be compared",
        ));
    }

    let character_count = entries.iter().try_fold(1_usize, |count, entry| {
        count
            .checked_add(entry.name.len())
            .and_then(|count| count.checked_add(entry.value.len()))
            .and_then(|count| count.checked_add(2))
    });
    let character_count = character_count.ok_or_else(invalid_environment)?;

    let mut block = Vec::new();
    block
        .try_reserve_exact(character_count.max(2))
        .map_err(|_| {
            io::Error::new(
                io::ErrorKind::OutOfMemory,
                "Windows environment allocation failed",
            )
        })?;
    for entry in entries {
        block.extend_from_slice(&entry.name);
        block.push(b'=' as u16);
        block.extend_from_slice(&entry.value);
        block.push(0);
    }
    block.push(0);
    if block.len() == 1 {
        block.push(0);
    }
    Ok(block)
}

/// 复制 GetEnvironmentStringsW 的原生块，保留非 Unicode UTF-16 与 `=C:` 驱动器变量。
fn inherited_environment() -> io::Result<Vec<EnvironmentEntry>> {
    let pointer = unsafe { GetEnvironmentStringsW() };
    if pointer.is_null() {
        return Err(io::Error::last_os_error());
    }
    let environment = EnvironmentStrings(pointer);
    let mut entries = Vec::new();
    let mut cursor = environment.0;
    loop {
        let first = unsafe { *cursor };
        if first == 0 {
            break;
        }
        let mut length = 0_usize;
        while unsafe { *cursor.add(length) } != 0 {
            length += 1;
        }
        let raw = unsafe { std::slice::from_raw_parts(cursor, length) };
        if let Some(entry) = parse_environment_entry(raw) {
            entries.push(entry);
        }
        cursor = unsafe { cursor.add(length + 1) };
    }
    Ok(entries)
}

/// 以首个分隔符切分普通变量，并以第二个分隔符识别 Windows 的 `=C:` 隐藏变量名。
fn parse_environment_entry(raw: &[u16]) -> Option<EnvironmentEntry> {
    let separator_start = usize::from(raw.first() == Some(&(b'=' as u16)));
    let separator = raw
        .iter()
        .enumerate()
        .skip(separator_start)
        .find_map(|(index, value)| (*value == b'=' as u16).then_some(index))?;
    let name = raw[..separator].to_vec();
    let value = raw[separator + 1..].to_vec();
    (!name.is_empty()).then_some(EnvironmentEntry { name, value })
}

/// 拒绝 CreateProcessW 无法表示的 NUL 和变量名分隔符，保留合法隐藏驱动器变量。
fn validate_environment_entry(name: &[u16], value: &[u16]) -> io::Result<()> {
    let invalid_name = name.is_empty()
        || name.contains(&0)
        || (name.first() == Some(&(b'=' as u16))
            && (name.len() == 1 || name[1..].contains(&(b'=' as u16))))
        || (name.first() != Some(&(b'=' as u16)) && name.contains(&(b'=' as u16)));
    if invalid_name || value.contains(&0) {
        return Err(invalid_environment());
    }
    if name.len() > i32::MAX as usize {
        return Err(invalid_environment());
    }
    Ok(())
}

/// 用 Windows ordinal ignore-case 语义比较变量名，不经 Unicode lossy 转换或 PATH 特例。
fn compare_ordinal_ignore_case(left: &[u16], right: &[u16]) -> io::Result<Ordering> {
    let left_len = i32::try_from(left.len()).map_err(|_| invalid_environment())?;
    let right_len = i32::try_from(right.len()).map_err(|_| invalid_environment())?;
    let result =
        unsafe { CompareStringOrdinal(left.as_ptr(), left_len, right.as_ptr(), right_len, 1) };
    match result {
        CSTR_LESS_THAN => Ok(Ordering::Less),
        CSTR_EQUAL => Ok(Ordering::Equal),
        CSTR_GREATER_THAN => Ok(Ordering::Greater),
        _ => Err(io::Error::last_os_error()),
    }
}

/// 环境只拒绝无法表示或长度计算溢出的输入；总块大小不设人为上限。
fn invalid_environment() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidInput,
        "Windows environment block is invalid",
    )
}

/// 将 executable 和 argv 按 Windows CRT 反转义规则编码为单个可变 UTF-16 命令行。
fn command_line(executable: &OsStr, args: &[OsString]) -> io::Result<Vec<u16>> {
    let mut line = Vec::new();
    append_argument(&mut line, &executable.encode_wide().collect::<Vec<_>>())?;
    for argument in args {
        line.push(b' ' as u16);
        append_argument(&mut line, &argument.encode_wide().collect::<Vec<_>>())?;
    }
    if line
        .len()
        .checked_add(1)
        .is_none_or(|length| length > MAX_WINDOWS_COMMAND_LINE)
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Windows command line exceeds the process limit",
        ));
    }
    line.push(0);
    Ok(line)
}

/// 只在参数需要时加引号，并按反斜线数量处理引号和参数末尾，保留字面 argv 边界。
fn append_argument(line: &mut Vec<u16>, argument: &[u16]) -> io::Result<()> {
    if argument.contains(&0) || argument.len() >= MAX_WINDOWS_COMMAND_LINE {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Windows command argument is not representable",
        ));
    }
    let needs_quotes = argument.is_empty()
        || argument
            .iter()
            .any(|character| matches!(*character, 0x20 | 0x09 | 0x22));
    if !needs_quotes {
        line.extend_from_slice(argument);
        return Ok(());
    }

    line.push(b'"' as u16);
    let mut backslashes = 0_usize;
    for character in argument {
        match *character {
            value if value == b'\\' as u16 => backslashes += 1,
            value if value == b'"' as u16 => {
                line.extend(std::iter::repeat_n(b'\\' as u16, backslashes * 2 + 1));
                line.push(value);
                backslashes = 0;
            }
            value => {
                line.extend(std::iter::repeat_n(b'\\' as u16, backslashes));
                line.push(value);
                backslashes = 0;
            }
        }
    }
    line.extend(std::iter::repeat_n(b'\\' as u16, backslashes * 2));
    line.push(b'"' as u16);
    Ok(())
}

/// 为固定绝对路径编码 NUL 结尾 UTF-16，拒绝无法由 Win32 C 字符串表达的路径。
fn null_terminated(value: &OsStr) -> io::Result<Vec<u16>> {
    let mut wide = value.encode_wide().collect::<Vec<_>>();
    if wide.contains(&0) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Windows process path contains NUL",
        ));
    }
    wide.push(0);
    Ok(wide)
}

/// 原生环境块释放器；GetEnvironmentStringsW 的指针不得跨越其配对 Free 调用。
struct EnvironmentStrings(*mut u16);

impl Drop for EnvironmentStrings {
    /// 始终用 Kernel32 配对释放环境快照，不让失败分支遗留进程级资源。
    fn drop(&mut self) {
        unsafe {
            FreeEnvironmentStringsW(self.0);
        }
    }
}

/// UTF-16 名称和值独立保存，避免遍历或排序时把 `=` 值误拆成字段。
struct EnvironmentEntry {
    name: Vec<u16>,
    value: Vec<u16>,
}
