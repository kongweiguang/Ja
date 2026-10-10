// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 白盒副本还包含仅供应用入口调用的真实适配器；此处测试只驱动注入适配器。
#[allow(dead_code)]
#[path = "../../src/ui/terminal_guard.rs"]
mod terminal_guard;

use std::io::{self, Write};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex};

use terminal_guard::{
    TerminalGuard, TerminalOperation as Op, TerminalOperations, TerminalUpdateGuard,
};

#[derive(Default)]
struct OperationLog {
    calls: Vec<Op>,
    fail_once: Vec<Op>,
    panic_once: Vec<Op>,
}

#[derive(Clone, Default)]
struct FakeOperations(Arc<Mutex<OperationLog>>);

impl FakeOperations {
    /// 共享日志让 guard 被 Drop 后仍可检查真实调用顺序，不把测试接口带入生产 API。
    fn new() -> Self {
        Self::default()
    }

    /// 下一次命中指定操作时只失败一次，随后允许 Drop 的重试验证闭环恢复。
    fn fail_once(&self, operation: Op) {
        self.0.lock().unwrap().fail_once.push(operation);
    }

    /// 下一次命中指定操作时触发 panic，验证意图登记和 unwinding 下的 RAII 恢复。
    fn panic_once(&self, operation: Op) {
        self.0.lock().unwrap().panic_once.push(operation);
    }

    /// 返回独立快照，避免断言持锁时 guard 清理再次进入 fake 操作。
    fn calls(&self) -> Vec<Op> {
        self.0.lock().unwrap().calls.clone()
    }
}

impl TerminalOperations for FakeOperations {
    /// 将特定副作用替换为一次性失败/异常注入；失败后的操作仍由生产 guard 编排。
    fn run(&mut self, operation: Op) -> io::Result<()> {
        let mut log = self.0.lock().unwrap();
        log.calls.push(operation);
        if let Some(index) = log
            .panic_once
            .iter()
            .position(|candidate| *candidate == operation)
        {
            log.panic_once.remove(index);
            drop(log);
            panic!("injected terminal operation panic");
        }
        if let Some(index) = log
            .fail_once
            .iter()
            .position(|candidate| *candidate == operation)
        {
            log.fail_once.remove(index);
            return Err(io::Error::other("injected terminal I/O error"));
        }
        Ok(())
    }
}

/// 将已尝试的启用序列反转为必须执行的恢复序列，并始终包含光标恢复。
fn expected_recovery(enabled: &[Op]) -> Vec<Op> {
    enabled
        .iter()
        .rev()
        .map(|operation| match operation {
            Op::AlternateScreen(true) => Op::AlternateScreen(false),
            Op::BracketedPaste(true) => Op::BracketedPaste(false),
            Op::MouseCapture(true) => Op::MouseCapture(false),
            Op::RawMode(true) => Op::RawMode(false),
            other => panic!("unexpected enable operation: {other:?}"),
        })
        .chain([Op::ShowCursor])
        .collect()
}

/// 部分写入可能已切换终端状态；四个初始化阶段失败都必须回滚到失败操作本身。
#[test]
fn each_partial_activation_failure_rolls_back_attempted_modes() {
    let enable = [
        Op::AlternateScreen(true),
        Op::BracketedPaste(true),
        Op::MouseCapture(true),
        Op::RawMode(true),
    ];

    for failed_index in 0..enable.len() {
        let fake = FakeOperations::new();
        fake.fail_once(enable[failed_index]);
        let result = TerminalGuard::activate_with(fake.clone());
        assert!(result.is_err(), "stage {failed_index} should fail");

        let attempted = &enable[..=failed_index];
        let mut expected = attempted.to_vec();
        expected.extend(expected_recovery_without_cursor(attempted));
        expected.push(Op::ShowCursor);
        assert_eq!(fake.calls(), expected, "stage {failed_index}");
    }
}

/// 启用阶段失败时恢复列表不含光标动作，供完整期望序列按相同注册边界拼接。
fn expected_recovery_without_cursor(enabled: &[Op]) -> Vec<Op> {
    expected_recovery(enabled)
        .into_iter()
        .filter(|operation| *operation != Op::ShowCursor)
        .collect()
}

/// 单个甚至多个关闭操作失败时继续恢复后续模式，并由 Drop 重试仍登记的模式。
#[test]
fn restoration_continues_after_errors_and_drop_retries_remaining_modes() {
    let fake = FakeOperations::new();
    let mut guard = TerminalGuard::activate_with(fake.clone()).unwrap();
    fake.fail_once(Op::RawMode(false));
    fake.fail_once(Op::MouseCapture(false));

    assert!(guard.suspend_for_external_program().is_err());
    drop(guard);

    assert_eq!(
        fake.calls(),
        [
            Op::AlternateScreen(true),
            Op::BracketedPaste(true),
            Op::MouseCapture(true),
            Op::RawMode(true),
            Op::RawMode(false),
            Op::MouseCapture(false),
            Op::BracketedPaste(false),
            Op::AlternateScreen(false),
            Op::ShowCursor,
            Op::RawMode(false),
            Op::MouseCapture(false),
            Op::ShowCursor,
        ]
    );
}

/// 外部程序挂起清理失败时不把不确定模式重新当作成功恢复，退出时由 Drop 重试残余模式。
#[test]
fn failed_editor_suspend_keeps_uncertain_mode_for_drop_retry() {
    let fake = FakeOperations::new();
    let mut guard = TerminalGuard::activate_with(fake.clone()).unwrap();
    fake.fail_once(Op::MouseCapture(false));

    assert!(guard.suspend_for_external_program().is_err());
    drop(guard);

    assert_eq!(
        fake.calls(),
        [
            Op::AlternateScreen(true),
            Op::BracketedPaste(true),
            Op::MouseCapture(true),
            Op::RawMode(true),
            Op::RawMode(false),
            Op::MouseCapture(false),
            Op::BracketedPaste(false),
            Op::AlternateScreen(false),
            Op::ShowCursor,
            Op::MouseCapture(false),
            Op::ShowCursor,
        ]
    );
}

/// 编辑器返回时任一重新启用失败，桥接可按相反方向挂起已恢复部分并保留可重试状态。
#[test]
fn failed_editor_resume_can_suspend_partially_reentered_modes() {
    let fake = FakeOperations::new();
    let mut guard = TerminalGuard::activate_with(fake.clone()).unwrap();
    guard.suspend_for_external_program().unwrap();
    fake.fail_once(Op::MouseCapture(true));

    assert!(guard.resume_after_external_program().is_err());
    guard.suspend_for_external_program().unwrap();
    drop(guard);

    let calls = fake.calls();
    let initial_suspend = calls
        .iter()
        .position(|operation| *operation == Op::ShowCursor)
        .unwrap()
        + 1;
    assert_eq!(
        &calls[initial_suspend..],
        &[
            Op::AlternateScreen(true),
            Op::BracketedPaste(true),
            Op::MouseCapture(true),
            Op::MouseCapture(false),
            Op::BracketedPaste(false),
            Op::AlternateScreen(false),
            Op::ShowCursor,
            Op::ShowCursor,
        ]
    );
}

/// 绘制或终端构造遇到普通错误時提前返回，guard 的 Drop 仍关闭所有已开启模式。
#[test]
fn early_error_and_unwind_restore_activated_terminal_modes() {
    let fake = FakeOperations::new();
    let result = (|| -> io::Result<()> {
        let _guard = TerminalGuard::activate_with(fake.clone())?;
        Err(io::Error::other("injected terminal setup failure"))
    })();
    assert!(result.is_err());
    assert_eq!(
        fake.calls(),
        [
            Op::AlternateScreen(true),
            Op::BracketedPaste(true),
            Op::MouseCapture(true),
            Op::RawMode(true),
            Op::RawMode(false),
            Op::MouseCapture(false),
            Op::BracketedPaste(false),
            Op::AlternateScreen(false),
            Op::ShowCursor,
        ]
    );

    let panic_fake = FakeOperations::new();
    panic_fake.panic_once(Op::MouseCapture(true));
    let panic = catch_unwind(AssertUnwindSafe(|| {
        let _ = TerminalGuard::activate_with(panic_fake.clone());
    }));
    assert!(panic.is_err());
    assert_eq!(
        panic_fake.calls(),
        [
            Op::AlternateScreen(true),
            Op::BracketedPaste(true),
            Op::MouseCapture(true),
            Op::MouseCapture(false),
            Op::BracketedPaste(false),
            Op::AlternateScreen(false),
            Op::ShowCursor,
        ]
    );
}

#[derive(Default)]
struct WriterLog {
    bytes: Vec<u8>,
    fail_at: Vec<usize>,
    flush_failures: usize,
}

#[derive(Clone, Default)]
struct FaultWriter(Arc<Mutex<WriterLog>>);

impl FaultWriter {
    /// 以共享可编排故障点记录实际字节，使 write_all 的短写与重试路径可稳定复现。
    fn new(fail_at: Vec<usize>, flush_failures: usize) -> Self {
        Self(Arc::new(Mutex::new(WriterLog {
            bytes: Vec::new(),
            fail_at,
            flush_failures,
        })))
    }

    /// 绘制开始后追加 flush 故障，避免通过 begin 阶段的失败间接覆盖 finish 路径。
    fn fail_flushes(&self, count: usize) {
        self.0.lock().unwrap().flush_failures = count;
    }
}

impl Write for FaultWriter {
    /// 在指定字节边界先返回短写再注入一次失败，模拟终端只收到 VT 序列前缀。
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        let mut log = self.0.lock().unwrap();
        let written = log.bytes.len();
        if let Some(index) = log.fail_at.iter().position(|offset| *offset == written) {
            log.fail_at.remove(index);
            return Err(io::Error::other("injected partial terminal write"));
        }
        let next_failure = log
            .fail_at
            .iter()
            .copied()
            .filter(|offset| *offset > written)
            .min()
            .unwrap_or(usize::MAX);
        let accepted = buffer.len().min(next_failure.saturating_sub(written));
        if accepted == 0 {
            return Err(io::Error::other("injected terminal write failure"));
        }
        log.bytes.extend_from_slice(&buffer[..accepted]);
        Ok(accepted)
    }

    /// 对 flush 故障单独计数，覆盖同步写入失败后 Drop 的恢复尝试。
    fn flush(&mut self) -> io::Result<()> {
        let mut log = self.0.lock().unwrap();
        if log.flush_failures > 0 {
            log.flush_failures -= 1;
            return Err(io::Error::other("injected terminal flush failure"));
        }
        Ok(())
    }
}

/// 初始化同步帧的写入若只到达部分字节，也须尝试显光标和结束同步模式。
#[test]
fn partial_begin_write_is_followed_by_best_effort_terminal_restore() {
    let writer = FaultWriter::new(vec![4], 0);
    let bytes = writer.0.clone();

    assert!(TerminalUpdateGuard::begin(writer).is_err());
    assert!(
        bytes
            .lock()
            .unwrap()
            .bytes
            .ends_with(b"\x1b[?25h\x1b[?2026l"),
        "restore sequence must follow the partial synchronized-update prefix"
    );
}

/// 开始帧的 flush 失败也释放 guard，并尝试 flush 恢复序列后终结同步模式。
#[test]
fn begin_flush_failure_runs_update_guard_cleanup() {
    let writer = FaultWriter::new(Vec::new(), 1);
    let bytes = writer.0.clone();

    assert!(TerminalUpdateGuard::begin(writer).is_err());
    let written = bytes.lock().unwrap().bytes.clone();
    assert!(written.starts_with(b"\x1b[?2026h\x1b[?25l"));
    assert!(written.ends_with(b"\x1b[?25h\x1b[?2026l"));
}

/// 帧结束写入和其 flush 失败时，Drop 再次尝试恢复；恢复首项失败不跳过同步模式关闭。
#[test]
fn finish_write_and_flush_failures_still_attempt_remaining_restore() {
    let start = b"\x1b[?2026h\x1b[?25l";
    let end = b"\x1b[?2026l";
    let writer = FaultWriter::new(vec![start.len(), start.len()], 0);
    let bytes = writer.0.clone();
    let update = TerminalUpdateGuard::begin(writer).unwrap();
    assert!(update.finish().is_err());
    assert_eq!(
        bytes.lock().unwrap().bytes,
        [start.as_slice(), end.as_slice()].concat()
    );

    let writer = FaultWriter::new(Vec::new(), 0);
    let bytes = writer.0.clone();
    let failure_control = writer.clone();
    let update = TerminalUpdateGuard::begin(writer).unwrap();
    failure_control.fail_flushes(1);
    assert!(update.finish().is_err());
    let written = bytes.lock().unwrap().bytes.clone();
    assert!(written.starts_with(start));
    assert!(written.ends_with(b"\x1b[?2026l\x1b[?25h\x1b[?2026l"));
}
