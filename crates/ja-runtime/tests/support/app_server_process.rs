// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// `app_server_process` 私有测试的集中控制口。
// 该 Harness 只调用 crate-private seam，生产 façade 不加载或重导出任何测试实现。

use crate::app_server_process::{AppServerProcessError, Limits, Session, SidecarSupervisor};
use crate::app_server_process::client::TerminalCallback;
use crate::app_server_process::client::session::wire::DEFAULT_WRITE_WATCHDOG_TIMEOUT;
use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

/// 用生产构造链创建无 terminal callback 的内存 session，避免仅为测试保留公共构造方法。
pub(crate) fn session_from_io<R, W, E>(
    reader: R,
    writer: W,
    stderr: E,
    generation: u64,
    limits: Limits,
) -> Result<Session, AppServerProcessError>
where
    R: Read + Send + 'static,
    W: Write + Send + 'static,
    E: Read + Send + 'static,
{
    Session::from_io_with_terminal(
        reader,
        writer,
        stderr,
        generation,
        limits,
        None,
        DEFAULT_WRITE_WATCHDOG_TIMEOUT,
    )
}

/// 测试清理统一复用有界 close，避免测试失败时遗留 writer actor 或永久等待。
pub(crate) fn close_session(session: &Session) {
    let _ =
        SidecarSupervisor::close_session_until(session, Instant::now() + Duration::from_secs(2));
}

/// 在独立 scoped thread 中故意让目标 Mutex 中毒，用于验证生产路径拒绝不可信状态；
/// helper 只存在于 tests/support，不向生产类型增加注入方法或 feature。
pub(crate) fn poison_mutex<T: Send>(mutex: &Mutex<T>) {
    thread::scope(|scope| {
        let result = scope
            .spawn(|| {
                let Ok(_guard) = mutex.lock() else {
                    return;
                };
                panic!("intentional mutex poison fixture");
            })
            .join();
        assert!(result.is_err(), "fixture thread must poison the mutex");
    });
    assert!(mutex.is_poisoned(), "target mutex must remain poisoned");
}

/// 构造显式 watchdog 的内存 session，使阻塞 IO 测试可控且真实 sidecar 默认值不被修改。
pub(crate) fn session_with_terminal_watchdog<R, W, E>(
    reader: R,
    writer: W,
    stderr: E,
    generation: u64,
    limits: Limits,
    terminal_callback: Option<TerminalCallback>,
    write_timeout: Duration,
) -> Result<Session, AppServerProcessError>
where
    R: Read + Send + 'static,
    W: Write + Send + 'static,
    E: Read + Send + 'static,
{
    Session::from_io_with_terminal(
        reader,
        writer,
        stderr,
        generation,
        limits,
        terminal_callback,
        write_timeout,
    )
}

pub(crate) struct PipeReader {
    pub(crate) receiver: Receiver<Vec<u8>>,
    pub(crate) buffer: VecDeque<u8>,
}

pub(crate) struct PipeWriter {
    pub(crate) sender: Sender<Vec<u8>>,
}

pub(crate) struct LateWatchdogReader {
    pub(crate) inner: PipeReader,
    pub(crate) frame_read: Sender<()>,
    pub(crate) release: Arc<AtomicBool>,
    pub(crate) first_read: bool,
}

/// 构造一对不会自动关闭的内存管道，让测试能独立控制 EOF 时机。
pub(crate) fn pipe_pair() -> (PipeReader, PipeWriter) {
    let (sender, receiver) = mpsc::channel();
    (
        PipeReader {
            receiver,
            buffer: VecDeque::new(),
        },
        PipeWriter { sender },
    )
}

impl Read for PipeReader {
    /// 阻塞到一段完整的内存管道数据可用，模拟子进程的 pipe read 语义。
    fn read(&mut self, target: &mut [u8]) -> io::Result<usize> {
        while self.buffer.is_empty() {
            let Ok(chunk) = self.receiver.recv() else {
                // 已关闭的内存 sender 模拟 OS pipe 返回零字节，使 reader 直接覆盖协议 EOF 分支。
                return Ok(0);
            };
            self.buffer.extend(chunk);
        }
        let count = target.len().min(self.buffer.len());
        for slot in &mut target[..count] {
            *slot = self.buffer.pop_front().expect("buffer length checked");
        }
        Ok(count)
    }
}

impl Read for LateWatchdogReader {
    /// 在阻塞 EOF 前让 ready frame 到达生产 dispatcher，证明 reader 正在等待 barrier，
    /// 而不是遗漏输入。
    fn read(&mut self, target: &mut [u8]) -> io::Result<usize> {
        if self.first_read {
            self.first_read = false;
            let count = self.inner.read(target)?;
            self.frame_read
                .send(())
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "frame ack closed"))?;
            return Ok(count);
        }
        while !self.release.load(Ordering::Acquire) {
            thread::yield_now();
        }
        Ok(0)
    }
}

impl Write for PipeWriter {
    /// 把每次 host 写入复制到 channel，便于测试逐帧检查 writer actor 输出。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.sender
            .send(bytes.to_vec())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "pipe closed"))?;
        Ok(bytes.len())
    }

    /// 内存管道不需要 flush，但保留 Write 合约以覆盖真实 writer 调用路径。
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

pub(crate) struct EmptyReader;

impl Read for EmptyReader {
    /// 立即 EOF，模拟 sidecar pipe 已关闭的边界。
    fn read(&mut self, _target: &mut [u8]) -> io::Result<usize> {
        Ok(0)
    }
}

pub(crate) struct ControlledReader {
    pub(crate) release: Arc<AtomicBool>,
}

impl Read for ControlledReader {
    /// terminal cleanup 前保持 stdout 打开，确保 callback 触发后 watchdog 测试不遗留
    /// 永久阻塞的 reader thread。
    fn read(&mut self, _target: &mut [u8]) -> io::Result<usize> {
        while !self.release.load(Ordering::Acquire) {
            thread::yield_now();
        }
        Ok(0)
    }
}

pub(crate) struct BlockingWriter {
    pub(crate) entered: Sender<()>,
    pub(crate) release: Arc<AtomicBool>,
    pub(crate) finished: Sender<()>,
}

impl Write for BlockingWriter {
    /// 模拟 child 停止读取 stdin 后阻塞的 OS write，用于验证 watchdog 收口。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.entered
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "watchdog entry closed"))?;
        while !self.release.load(Ordering::Acquire) {
            thread::yield_now();
        }
        self.finished
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "watchdog finish closed"))?;
        Ok(bytes.len())
    }

    /// fixture 在 write 阶段阻塞，因此只有 cancellation 后才会到达 flush。
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

pub(crate) struct AckWriter {
    pub(crate) writes: Sender<()>,
}

pub(crate) struct GateWriter {
    pub(crate) entered: Sender<()>,
    pub(crate) release: Receiver<()>,
    pub(crate) completed: Sender<()>,
    pub(crate) blocked: bool,
}

impl Write for GateWriter {
    /// 第一帧入队后暂停，使测试无需 wall-clock sleep 即可在 writer confirmation 前投递
    /// immediate ready token 的投递时序。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if !self.blocked {
            self.blocked = true;
            self.entered
                .send(())
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "gate closed"))?;
            self.release
                .recv()
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "gate released"))?;
        }
        Ok(bytes.len())
    }

    /// gated fixture 不含 buffer，因此 flush 无需额外工作，只用于标记阶段完成。
    fn flush(&mut self) -> io::Result<()> {
        self.completed
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "completion closed"))?;
        Ok(())
    }
}

pub(crate) struct ImmediateReadyWriter {
    pub(crate) ready_sender: Sender<Vec<u8>>,
    pub(crate) ready_payload: Option<Vec<u8>>,
    pub(crate) ready_sent: Sender<()>,
    pub(crate) release_flush: Receiver<()>,
    pub(crate) flush_done: Sender<()>,
}

pub(crate) struct ReadyBeforeWriteReturnWriter {
    pub(crate) ready_sender: Sender<Vec<u8>>,
    pub(crate) ready_payload: Option<Vec<u8>>,
    pub(crate) ready_sent: Sender<()>,
    pub(crate) release_write: Receiver<()>,
    pub(crate) write_done: Sender<()>,
    pub(crate) flush_done: Sender<()>,
}

impl Write for ReadyBeforeWriteReturnWriter {
    /// write 返回前发送 ready，迫使 reader 等待共享 barrier，而不是观察中间 atomic 状态。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if let Some(ready) = self.ready_payload.take() {
            self.ready_sender
                .send(ready)
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ready pipe closed"))?;
            self.ready_sent
                .send(())
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ready ack closed"))?;
            self.release_write
                .recv_timeout(Duration::from_secs(1))
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "write release timeout"))?;
        }
        self.write_done
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "write ack closed"))?;
        Ok(bytes.len())
    }

    /// 显式保留成功路径，确保完整 write 与 flush 都返回后才提交 barrier。
    fn flush(&mut self) -> io::Result<()> {
        self.flush_done
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "flush ack closed"))?;
        Ok(())
    }
}

pub(crate) struct FlushFailureWriter {
    pub(crate) ready_sender: Sender<Vec<u8>>,
    pub(crate) ready_payload: Option<Vec<u8>>,
    pub(crate) ready_sent: Sender<()>,
    pub(crate) release_flush: Receiver<()>,
    pub(crate) flush_done: Sender<()>,
}

pub(crate) struct LateWatchdogReadyWriter {
    pub(crate) ready_sender: Sender<Vec<u8>>,
    pub(crate) ready_payload: Option<Vec<u8>>,
    pub(crate) ready_sent: Sender<()>,
    pub(crate) release_write: Arc<AtomicBool>,
    pub(crate) write_done: Sender<()>,
}

impl Write for LateWatchdogReadyWriter {
    /// 先发布 ready，再等 watchdog 关闭 session 后完成 write，从而不创建第二 writer 即可
    /// 重现迟到 OS write completion。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if let Some(ready) = self.ready_payload.take() {
            self.ready_sender
                .send(ready)
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ready pipe closed"))?;
            self.ready_sent
                .send(())
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ready ack closed"))?;
            while !self.release_write.load(Ordering::Acquire) {
                thread::yield_now();
            }
        }
        self.write_done
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "write ack closed"))?;
        Ok(bytes.len())
    }

    /// late completion fixture 只有在 watchdog release 后才会到达 flush。
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Write for FlushFailureWriter {
    /// 让 write 成功并把 flush 作为唯一失败点，证明 flush 失败后 barrier 不会保留 Confirmed。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        Ok(bytes.len())
    }

    /// 让 ready reply 与 blocked flush 竞态，并在 release 后执行 fail-closed。
    fn flush(&mut self) -> io::Result<()> {
        if let Some(ready) = self.ready_payload.take() {
            self.ready_sender
                .send(ready)
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ready pipe closed"))?;
            self.ready_sent
                .send(())
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ready ack closed"))?;
            self.release_flush
                .recv_timeout(Duration::from_secs(1))
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "flush release timeout"))?;
        }
        self.flush_done
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "flush ack closed"))?;
        Err(io::Error::new(
            io::ErrorKind::BrokenPipe,
            "flush fixture failure",
        ))
    }
}

impl Write for ImmediateReadyWriter {
    /// writer actor 仍在操作内时从 flush 发布 ready，不使用 sleep 或第二套协议实现即可
    /// 重现 child 在 initialized 可见后立即回复的行为。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        Ok(bytes.len())
    }

    /// 发布 ready 后保持 writer 阻塞，证明 barrier 在 flush 返回和 lifecycle promotion
    /// 执行前已经可见。
    fn flush(&mut self) -> io::Result<()> {
        if let Some(ready) = self.ready_payload.take() {
            self.ready_sender
                .send(ready)
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ready pipe closed"))?;
            self.ready_sent
                .send(())
                .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ready ack closed"))?;
            self.release_flush
                .recv_timeout(Duration::from_secs(1))
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "flush release timeout"))?;
        }
        self.flush_done
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "flush ack closed"))?;
        Ok(())
    }
}

impl Write for AckWriter {
    /// 每收到一帧就发确认，使 pending 注册完成后测试无需 sleep 猜测时序。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.writes
            .send(())
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "ack receiver closed"))?;
        Ok(bytes.len())
    }

    /// Ack writer 没有缓冲，flush 只需保持成功。
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

pub(crate) struct FailingWriter;

impl Write for FailingWriter {
    /// 强制 writer actor 走 IO fault 分支，回归 fail-closed 语义。
    fn write(&mut self, _bytes: &[u8]) -> io::Result<usize> {
        Err(io::Error::new(io::ErrorKind::BrokenPipe, "fixture failure"))
    }

    /// 即使 write 未发生，flush 也保持同一失败模型。
    fn flush(&mut self) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::BrokenPipe, "fixture failure"))
    }
}
