// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use serde_json::{Value, json};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::thread::JoinHandle;
use std::time::Duration;

/// 生命周期集成测试通过真实 HTTP/SSE 驱动 Java，避免依赖已删除的模型测试后门。
pub struct ResponsesProvider {
    address: std::net::SocketAddr,
    stop: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
}

impl ResponsesProvider {
    /// 只监听独占 loopback 端口；后台标题请求也能正常完成，不与 Turn 请求争抢固定序号。
    pub fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind test provider");
        let address = listener.local_addr().expect("provider address");
        listener.set_nonblocking(true).expect("nonblocking accept");
        let stop = Arc::new(AtomicBool::new(false));
        let stopped = Arc::clone(&stop);
        let worker = std::thread::spawn(move || {
            while !stopped.load(Ordering::Acquire) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let _ = serve(&mut stream);
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(10));
                    }
                    Err(error) => panic!("provider accept failed: {error}"),
                }
            }
        });
        Self {
            address,
            stop,
            worker: Some(worker),
        }
    }

    /// 把 fixture 地址写入 Java-owned 配置，生产代码不感知测试模式。
    pub fn base_url(&self) -> String {
        format!("http://{}/v1", self.address)
    }
}

impl Drop for ResponsesProvider {
    /// 停止 accept 并 join 有界读取线程，测试失败也不遗留端口或网络服务。
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            worker.join().expect("provider worker joined");
        }
    }
}

/// 读取当前 Adapter 的 Content-Length 请求；大小和超时均受限，不实现通用 HTTP 服务。
fn serve(stream: &mut TcpStream) -> std::io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(2)))?;
    stream.set_write_timeout(Some(Duration::from_secs(2)))?;
    let mut bytes = Vec::new();
    let mut buffer = [0_u8; 8192];
    let (header_end, length) = loop {
        let count = stream.read(&mut buffer)?;
        if count == 0 {
            return Ok(());
        }
        bytes.extend_from_slice(&buffer[..count]);
        assert!(
            bytes.len() <= 2 * 1024 * 1024,
            "provider request exceeds fixture bound"
        );
        if let Some(index) = bytes.windows(4).position(|part| part == b"\r\n\r\n") {
            let header = String::from_utf8_lossy(&bytes[..index]);
            let length = header
                .lines()
                .find_map(|line| {
                    let (key, value) = line.split_once(':')?;
                    key.eq_ignore_ascii_case("content-length")
                        .then(|| value.trim().parse::<usize>().expect("content length"))
                })
                .expect("content-length request");
            assert!(length <= 2 * 1024 * 1024, "bounded provider body");
            break (index + 4, length);
        }
    };
    while bytes.len() < header_end + length {
        let count = stream.read(&mut buffer)?;
        if count == 0 {
            return Ok(());
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
    let request: Value =
        serde_json::from_slice(&bytes[header_end..header_end + length]).expect("provider JSON");
    let input = request["input"].to_string();
    let needs_approval =
        input.contains("__JA_FAKE_REVIEW_FIXTURE__") && !input.contains("function_call_output");
    let item = if needs_approval {
        json!({"id":"item_host","type":"function_call","call_id":"call_host_approval","name":"shell",
            "arguments":json!({"command":"Write-Output JA_HOST_APPROVAL"}).to_string()})
    } else {
        json!({"id":"item_host","type":"message","role":"assistant","status":"completed",
            "content":[{"type":"output_text","text":"已完成。","annotations":[]}]})
    };
    let response = json!({"id":"resp_host","object":"response","created_at":0,"model":"fake-model",
        "status":"completed","output":[item],"parallel_tool_calls":true,"tool_choice":"auto","tools":[],
        "usage":{"input_tokens":5,"input_tokens_details":{"cached_tokens":0},
            "output_tokens":5,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":10}});
    let mut body = String::new();
    let mut added = item.clone();
    if needs_approval {
        added["arguments"] = json!("");
    } else {
        added["content"] = json!([]);
    }
    body.push_str(&event(
        "response.output_item.added",
        0,
        json!({"output_index":0,"item":added}),
    ));
    if needs_approval {
        body.push_str(&event(
            "response.function_call_arguments.done",
            1,
            json!({"item_id":"item_host","output_index":0,"arguments":item["arguments"]}),
        ));
    } else {
        body.push_str(&event("response.output_text.delta", 1,
            json!({"item_id":"item_host","output_index":0,"content_index":0,"delta":"已完成。","logprobs":[]})));
    }
    if !needs_approval {
        body.push_str(&event(
            "response.output_text.done",
            2,
            json!({"item_id":"item_host","output_index":0,"content_index":0,"text":"已完成。"}),
        ));
    }
    body.push_str(&event(
        "response.output_item.done",
        3,
        json!({"output_index":0,"item":item}),
    ));
    body.push_str(&event(
        "response.completed",
        4,
        json!({"response":response}),
    ));
    write!(
        stream,
        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.len(),
        body
    )?;
    stream.flush()
}

/// 事件序号与 SSE 类型同步生成，避免 fixture 只覆盖终帧却遗漏生产流解码路径。
fn event(kind: &str, sequence: u32, mut payload: Value) -> String {
    payload["type"] = json!(kind);
    payload["sequence_number"] = json!(sequence);
    format!("event: {kind}\ndata: {payload}\n\n")
}
