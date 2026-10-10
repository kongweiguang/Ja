// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! WebSocket 消息与 Session 的 JSONL I/O 之间的单连接适配。

use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError};
use std::thread;
use std::time::Duration;
use tungstenite::error::{CapacityError, ProtocolError, SubProtocolError};
use tungstenite::{Error as WebSocketError, Message, WebSocket};

const POLL_INTERVAL: Duration = Duration::from_millis(20);

pub(crate) struct SessionReader {
    receiver: Receiver<Vec<u8>>,
    pending: Vec<u8>,
    position: usize,
}

pub(crate) struct SessionWriter {
    sender: SyncSender<Vec<u8>>,
    pending: Vec<u8>,
    max_frame_bytes: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PumpExitCategory {
    InvalidOutboundText,
    WebSocketSendError,
    OutboundChannelClosed,
    InboundConsumerClosed,
    InboundTextTooLarge,
    InboundTextContainsLineBreak,
    PeerClose,
    UnsupportedMessage,
    WebSocketReadError,
}

impl PumpExitCategory {
    /// 稳定日志类别使用封闭静态词表，避免将帧、错误消息或远端关闭原因当作分类值。
    fn as_str(self) -> &'static str {
        match self {
            Self::InvalidOutboundText => "invalid_outbound_text",
            Self::WebSocketSendError => "websocket_send_error",
            Self::OutboundChannelClosed => "outbound_channel_closed",
            Self::InboundConsumerClosed => "inbound_consumer_closed",
            Self::InboundTextTooLarge => "inbound_text_too_large",
            Self::InboundTextContainsLineBreak => "inbound_text_contains_line_break",
            Self::PeerClose => "peer_close",
            Self::UnsupportedMessage => "unsupported_message",
            Self::WebSocketReadError => "websocket_read_error",
        }
    }

    /// 本地生命周期收尾和对端 Close 是可预期终止；协议/传输异常仍需显眼告警。
    fn is_expected(self) -> bool {
        matches!(
            self,
            Self::OutboundChannelClosed | Self::InboundConsumerClosed | Self::PeerClose
        )
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct PumpExitDiagnostic {
    pub(crate) category: PumpExitCategory,
    pub(crate) error_variant: Option<&'static str>,
    pub(crate) error_detail: Option<&'static str>,
    pub(crate) io_kind: Option<io::ErrorKind>,
    pub(crate) close_code: Option<u16>,
}

/// 投影为静态变体名和标准 I/O 类型；不保留、格式化或克隆错误载荷。
pub(crate) fn pump_exit_diagnostic(
    category: PumpExitCategory,
    error: Option<&WebSocketError>,
    close_code: Option<u16>,
) -> PumpExitDiagnostic {
    let (error_variant, error_detail, io_kind) = error.map_or((None, None, None), |error| {
        let (variant, detail, io_kind) = websocket_error_metadata(error);
        (Some(variant), detail, io_kind)
    });
    PumpExitDiagnostic {
        category,
        error_variant,
        error_detail,
        io_kind,
        close_code,
    }
}

/// 错误只投影为编译期变体名；数值和自由文本载荷不进入诊断字段。
fn websocket_error_metadata(
    error: &WebSocketError,
) -> (&'static str, Option<&'static str>, Option<io::ErrorKind>) {
    match error {
        WebSocketError::ConnectionClosed => ("connection_closed", None, None),
        WebSocketError::AlreadyClosed => ("already_closed", None, None),
        WebSocketError::Io(error) => ("io", None, Some(error.kind())),
        WebSocketError::Tls(_) => ("tls", None, None),
        WebSocketError::Capacity(error) => ("capacity", Some(capacity_error_detail(error)), None),
        WebSocketError::Protocol(error) => ("protocol", Some(protocol_error_detail(error)), None),
        WebSocketError::WriteBufferFull(_) => ("write_buffer_full", None, None),
        WebSocketError::Utf8(_) => ("utf8", None, None),
        WebSocketError::AttackAttempt => ("attack_attempt", None, None),
        WebSocketError::Url(_) => ("url", None, None),
        WebSocketError::Http(_) => ("http", None, None),
        WebSocketError::HttpFormat(_) => ("http_format", None, None),
    }
}

/// I/O 错误种类保持标准枚举语义，并将未来新增种类收敛到固定 `other`，不格式化错误文本。
fn io_error_kind_name(kind: io::ErrorKind) -> &'static str {
    match kind {
        io::ErrorKind::NotFound => "not_found",
        io::ErrorKind::PermissionDenied => "permission_denied",
        io::ErrorKind::ConnectionRefused => "connection_refused",
        io::ErrorKind::ConnectionReset => "connection_reset",
        io::ErrorKind::ConnectionAborted => "connection_aborted",
        io::ErrorKind::NotConnected => "not_connected",
        io::ErrorKind::AddrInUse => "addr_in_use",
        io::ErrorKind::AddrNotAvailable => "addr_not_available",
        io::ErrorKind::BrokenPipe => "broken_pipe",
        io::ErrorKind::AlreadyExists => "already_exists",
        io::ErrorKind::WouldBlock => "would_block",
        io::ErrorKind::InvalidInput => "invalid_input",
        io::ErrorKind::InvalidData => "invalid_data",
        io::ErrorKind::TimedOut => "timed_out",
        io::ErrorKind::WriteZero => "write_zero",
        io::ErrorKind::Interrupted => "interrupted",
        io::ErrorKind::Unsupported => "unsupported",
        io::ErrorKind::UnexpectedEof => "unexpected_eof",
        io::ErrorKind::OutOfMemory => "out_of_memory",
        io::ErrorKind::Other => "other",
        _ => "other",
    }
}

/// 细分容量错误时仅保留静态种类，不记录实际/允许尺寸以免意外暴露数据规模。
fn capacity_error_detail(error: &CapacityError) -> &'static str {
    match error {
        CapacityError::TooManyHeaders => "too_many_headers",
        CapacityError::MessageTooLong { .. } => "message_too_long",
    }
}

/// 细分协议失败只使用固定枚举标签，不格式化可能含头字段或帧上下文的错误对象。
fn protocol_error_detail(error: &ProtocolError) -> &'static str {
    match error {
        ProtocolError::WrongHttpMethod => "wrong_http_method",
        ProtocolError::WrongHttpVersion => "wrong_http_version",
        ProtocolError::MissingConnectionUpgradeHeader => "missing_connection_upgrade_header",
        ProtocolError::MissingUpgradeWebSocketHeader => "missing_upgrade_websocket_header",
        ProtocolError::MissingSecWebSocketVersionHeader => "missing_websocket_version_header",
        ProtocolError::MissingSecWebSocketKey => "missing_websocket_key",
        ProtocolError::SecWebSocketAcceptKeyMismatch => "websocket_accept_key_mismatch",
        ProtocolError::SecWebSocketSubProtocolError(error) => subprotocol_error_detail(error),
        ProtocolError::JunkAfterRequest => "junk_after_request",
        ProtocolError::CustomResponseSuccessful => "custom_response_successful",
        ProtocolError::InvalidHeader(_) => "invalid_header",
        ProtocolError::HandshakeIncomplete => "handshake_incomplete",
        ProtocolError::HttparseError(_) => "http_parse_error",
        ProtocolError::SendAfterClosing => "send_after_closing",
        ProtocolError::ReceivedAfterClosing => "received_after_closing",
        ProtocolError::NonZeroReservedBits => "nonzero_reserved_bits",
        ProtocolError::UnmaskedFrameFromClient => "unmasked_frame_from_client",
        ProtocolError::MaskedFrameFromServer => "masked_frame_from_server",
        ProtocolError::FragmentedControlFrame => "fragmented_control_frame",
        ProtocolError::ControlFrameTooBig => "control_frame_too_big",
        ProtocolError::UnknownControlFrameType(_) => "unknown_control_frame_type",
        ProtocolError::UnknownDataFrameType(_) => "unknown_data_frame_type",
        ProtocolError::UnexpectedContinueFrame => "unexpected_continue_frame",
        ProtocolError::ExpectedFragment(_) => "expected_fragment",
        ProtocolError::ResetWithoutClosingHandshake => "reset_without_closing_handshake",
        ProtocolError::InvalidOpcode(_) => "invalid_opcode",
        ProtocolError::InvalidCloseSequence => "invalid_close_sequence",
    }
}

/// 子协议错误同样投影为静态类别，不泄露对端返回的协议名或值。
fn subprotocol_error_detail(error: &SubProtocolError) -> &'static str {
    match error {
        SubProtocolError::ServerSentSubProtocolNoneRequested => {
            "server_sent_unrequested_subprotocol"
        }
        SubProtocolError::InvalidSubProtocol => "invalid_subprotocol",
        SubProtocolError::NoSubProtocol => "missing_subprotocol",
    }
}

/// 记录单次 pump 终止的安全结构字段；独立 target 供 Desktop formatter 精确白名单投影。
/// 缺省关闭码用 0 表示并由 formatter 丢弃；CloseFrame reason 和任意错误文本不进入日志。
fn record_pump_exit(diagnostic: PumpExitDiagnostic) {
    let category = diagnostic.category.as_str();
    let io_kind = diagnostic.io_kind.map_or("none", io_error_kind_name);
    let close_code = u64::from(diagnostic.close_code.unwrap_or_default());
    if diagnostic.category.is_expected() {
        tracing::info!(
            target: "ja.diagnostics.websocket_pump",
            exit_category = category,
            error_variant = diagnostic.error_variant.unwrap_or("none"),
            error_detail = diagnostic.error_detail.unwrap_or("none"),
            io_kind,
            close_code,
            "shared WebSocket pump exited"
        );
    } else {
        tracing::warn!(
            target: "ja.diagnostics.websocket_pump",
            exit_category = category,
            error_variant = diagnostic.error_variant.unwrap_or("none"),
            error_detail = diagnostic.error_detail.unwrap_or("none"),
            io_kind,
            close_code,
            "shared WebSocket pump exited"
        );
    }
}

/// 关闭码是协议枚举的数值投影；关闭 reason 可能含远端正文，因此绝不序列化。
pub(crate) fn close_status_code(frame: Option<&tungstenite::protocol::CloseFrame>) -> Option<u16> {
    frame.map(|frame| u16::from(frame.code))
}

/// WebSocket 必须由单一 actor 持有协议状态；两个有界通道使现有 Session 的
/// reader/writer 保持独立。日志集中在 actor 边界，pump 只返回安全终止投影以便直接验证。
pub(crate) fn attach(
    mut websocket: WebSocket<TcpStream>,
    max_frame_bytes: usize,
) -> io::Result<(SessionReader, SessionWriter)> {
    websocket.get_mut().set_read_timeout(Some(POLL_INTERVAL))?;
    let (inbound_sender, inbound_receiver) = mpsc::sync_channel(4);
    let (outbound_sender, outbound_receiver) = mpsc::sync_channel(4);
    thread::Builder::new()
        .name("ja-shared-websocket".into())
        .spawn(move || {
            let diagnostic = pump(
                websocket,
                inbound_sender,
                outbound_receiver,
                max_frame_bytes,
            );
            record_pump_exit(diagnostic);
        })?;
    Ok((
        SessionReader {
            receiver: inbound_receiver,
            pending: Vec::new(),
            position: 0,
        },
        SessionWriter {
            sender: outbound_sender,
            pending: Vec::new(),
            max_frame_bytes,
        },
    ))
}

/// 所有 WS 控制帧和 RPC text 帧在同一线程处理；超时仅用于检查出站队列。
/// 只返回安全结构诊断，由 attach 的唯一线程边界记录，避免分支遗漏或泄露载荷。
pub(crate) fn pump(
    mut websocket: WebSocket<TcpStream>,
    inbound: SyncSender<Vec<u8>>,
    outbound: Receiver<Vec<u8>>,
    max_frame_bytes: usize,
) -> PumpExitDiagnostic {
    let mut pending_inbound = None;
    loop {
        for _ in 0..8 {
            match outbound.try_recv() {
                Ok(frame) => {
                    let Ok(text) = String::from_utf8(frame) else {
                        return pump_exit_diagnostic(
                            PumpExitCategory::InvalidOutboundText,
                            None,
                            None,
                        );
                    };
                    if let Err(error) = websocket.send(Message::Text(text.into())) {
                        return pump_exit_diagnostic(
                            PumpExitCategory::WebSocketSendError,
                            Some(&error),
                            None,
                        );
                    }
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => {
                    return pump_exit_diagnostic(
                        PumpExitCategory::OutboundChannelClosed,
                        None,
                        None,
                    );
                }
            }
        }
        if let Some(frame) = pending_inbound.take() {
            match inbound.try_send(frame) {
                Ok(()) => {}
                Err(TrySendError::Full(frame)) => {
                    pending_inbound = Some(frame);
                    thread::sleep(POLL_INTERVAL);
                    continue;
                }
                Err(TrySendError::Disconnected(_)) => {
                    return pump_exit_diagnostic(
                        PumpExitCategory::InboundConsumerClosed,
                        None,
                        None,
                    );
                }
            }
        }
        match websocket.read() {
            Ok(Message::Text(text)) => {
                if text.len() > max_frame_bytes || text.contains('\n') || text.contains('\r') {
                    let category = if text.len() > max_frame_bytes {
                        PumpExitCategory::InboundTextTooLarge
                    } else {
                        PumpExitCategory::InboundTextContainsLineBreak
                    };
                    return pump_exit_diagnostic(category, None, None);
                }
                let mut frame = text.as_bytes().to_vec();
                frame.push(b'\n');
                match inbound.try_send(frame) {
                    Ok(()) => {}
                    Err(TrySendError::Full(frame)) => pending_inbound = Some(frame),
                    Err(TrySendError::Disconnected(_)) => {
                        return pump_exit_diagnostic(
                            PumpExitCategory::InboundConsumerClosed,
                            None,
                            None,
                        );
                    }
                }
            }
            Ok(Message::Close(frame)) => {
                return pump_exit_diagnostic(
                    PumpExitCategory::PeerClose,
                    None,
                    close_status_code(frame.as_ref()),
                );
            }
            Ok(Message::Ping(_) | Message::Pong(_)) => {}
            Ok(_) => {
                return pump_exit_diagnostic(PumpExitCategory::UnsupportedMessage, None, None);
            }
            Err(tungstenite::Error::Io(error))
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) => {}
            Err(error) => {
                return pump_exit_diagnostic(
                    PumpExitCategory::WebSocketReadError,
                    Some(&error),
                    None,
                );
            }
        }
    }
}

impl Read for SessionReader {
    /// 只交付完整 WS text 消息形成的 JSONL 帧；消息边界不会泄漏到 Session decoder。
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        if self.position == self.pending.len() {
            self.pending = match self.receiver.recv() {
                Ok(frame) => frame,
                Err(_) => return Ok(0),
            };
            self.position = 0;
        }
        let count = buffer.len().min(self.pending.len() - self.position);
        buffer[..count].copy_from_slice(&self.pending[self.position..self.position + count]);
        self.position += count;
        Ok(count)
    }
}

impl Write for SessionWriter {
    /// Session 先写完整 JSONL 帧、后 flush；这里保留字节直到 flush 才提交一个 WS text。
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        if buffer.len()
            > self
                .max_frame_bytes
                .saturating_add(1)
                .saturating_sub(self.pending.len())
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "RPC frame too large",
            ));
        }
        self.pending.extend_from_slice(buffer);
        Ok(buffer.len())
    }

    /// 拒绝拼接或非 UTF-8 帧，保证一条 JA-RPC JSON 对应一条 WS text 消息。
    fn flush(&mut self) -> io::Result<()> {
        if self.pending.last() != Some(&b'\n')
            || self.pending[..self.pending.len() - 1]
                .iter()
                .any(|byte| *byte == b'\n' || *byte == b'\r')
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid RPC frame",
            ));
        }
        self.pending.pop();
        std::str::from_utf8(&self.pending)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        self.sender
            .send(std::mem::take(&mut self.pending))
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "websocket closed"))
    }
}
