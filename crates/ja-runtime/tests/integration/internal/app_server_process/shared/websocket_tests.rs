// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::app_server_process::shared::websocket::{
    PumpExitCategory, attach, close_status_code, pump, pump_exit_diagnostic,
};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;
use tungstenite::protocol::{CloseFrame, frame::coding::CloseCode};
use tungstenite::{Error as WebSocketError, Message};

/// I/O 诊断只保留 ErrorKind；真实错误消息可能包含对端或本机私有文本。
#[test]
fn websocket_pump_diagnostic_projects_io_kind_without_error_message() {
    let private_message = "private-error-message-must-not-be-retained";
    let error = WebSocketError::Io(std::io::Error::new(
        std::io::ErrorKind::ConnectionReset,
        private_message,
    ));

    let diagnostic = pump_exit_diagnostic(PumpExitCategory::WebSocketReadError, Some(&error), None);

    assert_eq!(diagnostic.category, PumpExitCategory::WebSocketReadError);
    assert_eq!(diagnostic.error_variant, Some("io"));
    assert_eq!(diagnostic.error_detail, None);
    assert_eq!(
        diagnostic.io_kind,
        Some(std::io::ErrorKind::ConnectionReset)
    );
    assert_eq!(diagnostic.close_code, None);
    assert!(!format!("{diagnostic:?}").contains(private_message));
}

/// tungstenite 的 Utf8 变体携带原始错误文本，因此日志投影必须只保留固定变体名。
#[test]
fn websocket_pump_diagnostic_does_not_retain_error_variant_payload() {
    let private_message = "private-utf8-error-payload-must-not-escape";
    let error = WebSocketError::Utf8(private_message.to_owned());

    let diagnostic = pump_exit_diagnostic(PumpExitCategory::WebSocketReadError, Some(&error), None);

    assert_eq!(diagnostic.error_variant, Some("utf8"));
    assert_eq!(diagnostic.error_detail, None);
    assert_eq!(diagnostic.io_kind, None);
    assert!(!format!("{diagnostic:?}").contains(private_message));
}

/// 协议与容量子变体只保留静态类别，具体长度、帧字段和嵌套错误载荷不得入日志。
#[test]
fn websocket_pump_diagnostic_projects_protocol_and_capacity_subvariants_safely() {
    let protocol_error =
        WebSocketError::Protocol(tungstenite::error::ProtocolError::ResetWithoutClosingHandshake);
    let protocol = pump_exit_diagnostic(
        PumpExitCategory::WebSocketReadError,
        Some(&protocol_error),
        None,
    );
    assert_eq!(protocol.error_variant, Some("protocol"));
    assert_eq!(
        protocol.error_detail,
        Some("reset_without_closing_handshake")
    );

    let capacity_error =
        WebSocketError::Capacity(tungstenite::error::CapacityError::MessageTooLong {
            size: 8_192,
            max_size: 1_024,
        });
    let capacity = pump_exit_diagnostic(
        PumpExitCategory::WebSocketReadError,
        Some(&capacity_error),
        None,
    );
    assert_eq!(capacity.error_variant, Some("capacity"));
    assert_eq!(capacity.error_detail, Some("message_too_long"));
    assert!(!format!("{capacity:?}").contains("8192"));
    assert!(!format!("{capacity:?}").contains("1024"));
}

/// CloseFrame 的 reason 是远端自由文本；只保留 RFC close 数码用于区分关闭类别。
#[test]
fn websocket_pump_diagnostic_projects_close_code_without_reason() {
    let private_reason = "private-close-reason-must-not-escape";
    let frame = CloseFrame {
        code: CloseCode::Away,
        reason: private_reason.into(),
    };
    let close_code = close_status_code(Some(&frame));
    let diagnostic = pump_exit_diagnostic(PumpExitCategory::PeerClose, None, close_code);

    assert_eq!(diagnostic.category, PumpExitCategory::PeerClose);
    assert_eq!(diagnostic.close_code, Some(1001));
    assert!(!format!("{diagnostic:?}").contains(private_reason));
}

/// 真实 Close 帧沿 pump 的读分支返回 peer-close 与数值码，而不携带 reason。
#[test]
fn websocket_pump_returns_peer_close_diagnostic_from_loopback() {
    let private_reason = "private-loopback-close-reason";
    let listener = TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let address = listener.local_addr().expect("address");
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        let mut websocket = tungstenite::accept(stream).expect("websocket handshake");
        websocket
            .send(Message::Close(Some(CloseFrame {
                code: CloseCode::Away,
                reason: private_reason.into(),
            })))
            .expect("close frame");
    });
    let stream = TcpStream::connect(address).expect("client connect");
    let (mut websocket, _) =
        tungstenite::client(format!("ws://{address}/ja-rpc"), stream).expect("client handshake");
    websocket
        .get_mut()
        .set_read_timeout(Some(Duration::from_secs(1)))
        .expect("read timeout");
    let (inbound_sender, _inbound_receiver) = mpsc::sync_channel(4);
    let (_outbound_sender, outbound_receiver) = mpsc::sync_channel(4);

    let diagnostic = pump(websocket, inbound_sender, outbound_receiver, 1_024);

    assert_eq!(diagnostic.category, PumpExitCategory::PeerClose);
    assert_eq!(diagnostic.close_code, Some(1001));
    assert!(!format!("{diagnostic:?}").contains(private_reason));
    server.join().expect("server");
}

/// 无 WebSocket Close 握手的回环断开须落到真实 read-error 分支并保留安全协议变体。
#[test]
fn websocket_pump_returns_abrupt_eof_diagnostic_from_loopback() {
    let listener = TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let address = listener.local_addr().expect("address");
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        let websocket = tungstenite::accept(stream).expect("websocket handshake");
        drop(websocket);
    });
    let stream = TcpStream::connect(address).expect("client connect");
    let (mut websocket, _) =
        tungstenite::client(format!("ws://{address}/ja-rpc"), stream).expect("client handshake");
    websocket
        .get_mut()
        .set_read_timeout(Some(Duration::from_secs(1)))
        .expect("read timeout");
    let (inbound_sender, _inbound_receiver) = mpsc::sync_channel(4);
    let (_outbound_sender, outbound_receiver) = mpsc::sync_channel(4);

    let diagnostic = pump(websocket, inbound_sender, outbound_receiver, 1_024);

    assert_eq!(diagnostic.category, PumpExitCategory::WebSocketReadError);
    assert_eq!(diagnostic.error_variant, Some("protocol"));
    assert_eq!(
        diagnostic.error_detail,
        Some("reset_without_closing_handshake")
    );
    assert_eq!(diagnostic.io_kind, None);
    server.join().expect("server");
}

/// 在真实回环连接上验证 JSONL 与 WS text 的逐帧映射，以及服务端关闭后的 EOF。
#[test]
fn websocket_adapter_preserves_message_boundaries() {
    let listener = TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let address = listener.local_addr().expect("address");
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        let mut websocket = tungstenite::accept(stream).expect("websocket handshake");
        assert_eq!(
            websocket.read().expect("request text"),
            Message::Text("{\"id\":1}".into())
        );
        websocket
            .send(Message::Text("{\"id\":1}".into()))
            .expect("response text");
        websocket.close(None).expect("close");
    });
    let stream = TcpStream::connect(address).expect("client connect");
    let (websocket, _) =
        tungstenite::client(format!("ws://{address}/ja-rpc"), stream).expect("client handshake");
    let (mut reader, mut writer) = attach(websocket, 1_024).expect("adapter");
    writer.write_all(b"{\"id\":1}\n").expect("request frame");
    writer.flush().expect("request flush");
    let mut response = [0_u8; 9];
    reader.read_exact(&mut response).expect("response frame");
    assert_eq!(&response, b"{\"id\":1}\n");
    let mut eof = [0_u8; 1];
    assert_eq!(reader.read(&mut eof).expect("closed connection"), 0);
    server.join().expect("server");
}

/// 不接受将两帧塞进一次写入或未结束的 JSONL，避免客户端和服务端关联错位。
#[test]
fn websocket_adapter_rejects_invalid_outbound_framing() {
    let listener = TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let address = listener.local_addr().expect("address");
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        let mut websocket = tungstenite::accept(stream).expect("websocket handshake");
        websocket
            .get_mut()
            .set_read_timeout(Some(Duration::from_secs(1)))
            .expect("read timeout");
        let _ = websocket.read();
    });
    let stream = TcpStream::connect(address).expect("client connect");
    let (websocket, _) =
        tungstenite::client(format!("ws://{address}/ja-rpc"), stream).expect("client handshake");
    let (_, mut writer) = attach(websocket, 1_024).expect("adapter");
    writer.write_all(b"{}\n{}\n").expect("buffer");
    assert_eq!(
        writer.flush().expect_err("two frames").kind(),
        std::io::ErrorKind::InvalidData
    );
    server.join().expect("server");
}

/// 瞬时流量可在 actor 内施加背压，不能把暂时未消费的 Session 误判为断线。
#[test]
fn websocket_adapter_backpressures_burst_without_dropping_connection() {
    let listener = TcpListener::bind(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let address = listener.local_addr().expect("address");
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        let mut websocket = tungstenite::accept(stream).expect("websocket handshake");
        for index in 0..12 {
            websocket
                .send(Message::Text(format!("{{\"id\":{index}}}").into()))
                .expect("event text");
        }
        websocket.close(None).expect("close");
    });
    let stream = TcpStream::connect(address).expect("client connect");
    let (websocket, _) =
        tungstenite::client(format!("ws://{address}/ja-rpc"), stream).expect("client handshake");
    let (mut reader, _writer) = attach(websocket, 1_024).expect("adapter");
    thread::sleep(Duration::from_millis(100));
    for index in 0..12 {
        let expected = format!("{{\"id\":{index}}}\n");
        let mut frame = vec![0; expected.len()];
        reader.read_exact(&mut frame).expect("event frame");
        assert_eq!(frame, expected.as_bytes());
    }
    server.join().expect("server");
}
