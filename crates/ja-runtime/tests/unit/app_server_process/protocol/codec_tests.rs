// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super as codec;
use super::*;
use crate::app_server_process::Limits;
use crate::app_server_process::protocol::read_frame_with_forbidden;
use serde_json::json;
use std::io;

/// 序列化严格 JSONL fixture，使精确 token 测试复用生产 stdout reader 的 raw extension 审计。
fn jsonl(value: Value) -> Vec<u8> {
    let mut bytes = serde_json::to_vec(&value).expect("fixture serialization");
    bytes.push(b'\n');
    bytes
}

/// 通过生产 decoder 构造 response，确保 reader 连帧测试不会绕过 ID namespace 与信封校验。
fn success_frame(id: &str) -> RpcFrame {
    decode_frame(
        &jsonl(json!({"jsonrpc":"2.0", "id":id, "result":{}})),
        Limits::default().max_frame_bytes,
    )
    .expect("response fixture must satisfy the v1 codec")
}

/// 有界协议配置必须拒绝任何无界队列或日志行，避免攻击者把协商值变成内存分配指令。
#[test]
fn limits_reject_unbounded_configuration() {
    let inbound = Limits {
        inbound_queue_frames: usize::MAX,
        ..Limits::default()
    };
    assert_eq!(inbound.validate(), Err(CodecError::InvalidLimit));
    let stderr = Limits {
        max_stderr_line_bytes: usize::MAX,
        ..Limits::default()
    };
    assert_eq!(stderr.validate(), Err(CodecError::InvalidLimit));
}

/// active challenge 在 typed 与未知字段中都必须被阻止，但同为 32-hex 形状且无关的
/// provider replyId 仍然合法，避免扩大拒绝范围。
#[test]
fn exact_challenge_guard_does_not_reject_business_ids() {
    let challenge = "0123456789abcdef0123456789abcdef";
    let reply_id = "fedcba9876543210fedcba9876543210";
    let forbidden = HashSet::from([challenge.to_owned()]);
    let business = jsonl(serde_json::json!({
        "jsonrpc":"2.0",
        "method":"assistant/text-delta",
        "params":{"turnId":"turn_one","streamSeq":1,"text":reply_id}
    }));
    assert!(decode_frame_with_forbidden(&business, 4096, &forbidden).is_ok());

    let typed_replay = jsonl(serde_json::json!({
        "jsonrpc":"2.0",
        "method":"assistant/text-delta",
        "params":{"turnId":"turn_one","streamSeq":1,"text":challenge}
    }));
    assert_eq!(
        decode_frame_with_forbidden(&typed_replay, 4096, &forbidden),
        Err(CodecError::InvalidEnvelope)
    );

    let unknown_replay = jsonl(serde_json::json!({
        "jsonrpc":"2.0",
        "method":"assistant/text-delta",
        "params":{"turnId":"turn_one","streamSeq":1,"text":"safe"},
        "extension":{"value":challenge}
    }));
    assert_eq!(
        decode_frame_with_forbidden(&unknown_replay, 4096, &forbidden),
        Err(CodecError::InvalidEnvelope)
    );
}

/// v1 response 不接受 unknown root/error 字段或非 object result，
/// 防止 codec 丢字段后把新旧协议混合 frame 误判为合法。
#[test]
fn response_envelope_is_closed_and_object_typed() {
    for invalid in [
        serde_json::json!({"jsonrpc":"2.0","id":"c:one","result":null}),
        serde_json::json!({"jsonrpc":"2.0","id":"c:one","result":{},"extra":true}),
        serde_json::json!({
            "jsonrpc":"2.0",
            "id":"c:one",
            "error":{
                "code":-32080,
                "message":"internal error",
                "data":{"errorCode":"INTERNAL_ERROR","category":"internal","retryable":false,
                    "errorId":"err_00000000000000000000000000000001"},
                "cause":"hidden"
            }
        }),
    ] {
        assert_eq!(
            decode_frame(&jsonl(invalid), 4096),
            Err(CodecError::InvalidEnvelope)
        );
    }
}

/// 生产 decode 拒绝已删除的 request/event method，同时接受 v1 event 名与唯一的
/// initialized notification 合同。
#[test]
fn decoded_method_roles_use_the_frozen_v1_closures() {
    for invalid in [
        serde_json::json!({
            "jsonrpc":"2.0", "id":"c:removed", "method":"removed/method", "params":{}
        }),
        serde_json::json!({
            "jsonrpc":"2.0", "method":"assistant/messageCommitted", "params":{}
        }),
        serde_json::json!({
            "jsonrpc":"2.0", "method":"tool/completed", "params":{}
        }),
        serde_json::json!({
            "jsonrpc":"2.0", "id":"c:host", "method":"host-tool/invoke",
            "params":{"generation":1,"workspaceId":"ws_demo","threadId":"thr_demo",
                "turnId":"turn_demo","callId":"call_demo","operationId":"op_demo",
                "timeoutMs":1000,"input":{"kind":"write_file","path":"a.txt",
                    "content":"x","expectedSha256":null}}
        }),
    ] {
        assert_eq!(
            decode_frame(&jsonl(invalid), 4_096),
            Err(CodecError::InvalidEnvelope)
        );
    }
    for valid in [
        serde_json::json!({
            "jsonrpc":"2.0", "id":"c:health", "method":"runtime/health", "params":{}
        }),
        serde_json::json!({
            "jsonrpc":"2.0", "id":"c:general", "method":"workspace/open-general", "params":{}
        }),
        serde_json::json!({
            "jsonrpc":"2.0", "method":"assistant/model-step-committed", "params":{}
        }),
        serde_json::json!({
            "jsonrpc":"2.0", "method":"tool/started", "params":{}
        }),
        serde_json::json!({
            "jsonrpc":"2.0", "method":"tool/batch-committed", "params":{}
        }),
        serde_json::json!({
            "jsonrpc":"2.0", "method":"runtime/initialized",
            "params":{"readyToken":"0123456789abcdef0123456789abcdef"}
        }),
    ] {
        decode_frame(&jsonl(valid), 4_096).expect("method belongs to the v1 role closure");
    }
}

/// 同时覆盖截断、重复 key 和非法 ID namespace，证明 decoder 在任何 typed 投影前都会 fail-closed。
#[test]
fn codec_rejects_partial_duplicate_and_invalid_namespace() {
    assert_eq!(
        codec::decode_frame(br#"{"jsonrpc":"2.0","id":"c:a"}"#, 1024),
        Err(CodecError::PartialFrame)
    );
    assert_eq!(
        codec::decode_frame(
            br#"{"jsonrpc":"2.0","id":"c:a","id":"c:b","result":null}
"#,
            1024
        ),
        Err(CodecError::DuplicateKey)
    );
    assert_eq!(
        codec::decode_frame(
            br#"{"jsonrpc":"2.0","id":"c:a","result":{"nested":{"x":1,"x":2}}}
"#,
            1024
        ),
        Err(CodecError::DuplicateKey)
    );
    assert_eq!(
        codec::decode_frame(
            br#"{"jsonrpc":"2.0","id":"c:.bad","result":null}
"#,
            1024
        ),
        Err(CodecError::InvalidId)
    );
    assert!(RpcFrame::client_request("s:wrong", "x", json!({})).is_err());
    assert!(RpcFrame::client_request("c:.bad", "x", json!({})).is_err());
}

/// 手工构造的错误也必须通过完整 typed 目录，空 result 仍按严格信封拒绝。
#[test]
fn codec_rejects_null_result_and_validates_hand_built_error() {
    assert_eq!(
        codec::decode_frame(
            br#"{"jsonrpc":"2.0","id":"c:null","result":null}
"#,
            1024,
        ),
        Err(CodecError::InvalidEnvelope)
    );
    let invalid_catalog = json!({
        "jsonrpc":"2.0",
        "id":"c:bad",
        "error":{"code":-1,"message":"x","data":{"errorCode":"bad-code","category":"internal","retryable":false,"errorId":"err_00000000000000000000000000000001"}}
    });
    let mut invalid_bytes = serde_json::to_vec(&invalid_catalog).unwrap();
    invalid_bytes.push(b'\n');
    assert_eq!(
        codec::decode_frame(&invalid_bytes, 1024),
        Err(CodecError::InvalidErrorCatalog)
    );
}

/// 构造 frozen error fixture，证明 code/errorCode/category/retryable 决定分类且 message
/// 只服从 v1 的 1..512 字符边界；内容脱敏由 Java error mapper 负责。
#[test]
fn codec_accepts_localized_catalog_errors_and_rejects_unsafe_messages() {
    /// fixture 只参数化允许变化的展示文本和 retryable，避免测试无意放宽冻结 error catalog。
    fn fixture(message: &str, retryable: bool) -> Vec<u8> {
        let value = json!({
            "jsonrpc":"2.0",
            "id":"c:localized",
            "error":{
                "code":-32020,
                "message":message,
                "data":{"errorCode":"SHUTTING_DOWN","category":"unavailable","retryable":retryable,
                    "errorId":"err_00000000000000000000000000000001"}
            }
        });
        let mut bytes = serde_json::to_vec(&value).unwrap();
        bytes.push(b'\n');
        bytes
    }

    let localized = codec::decode_frame(&fixture("正在关闭", true), 4096)
        .expect("bounded localized message is valid");
    assert_eq!(localized.error().unwrap().message(), "正在关闭");

    let invalid_messages = ["", &"x".repeat(513)];
    for message in invalid_messages {
        assert_eq!(
            codec::decode_frame(&fixture(message, true), 4096),
            Err(CodecError::InvalidEnvelope),
            "unsafe error message must fail closed: {message:?}"
        );
    }
    assert_eq!(
        codec::decode_frame(&fixture("正在关闭", false), 4096),
        Err(CodecError::InvalidErrorCatalog)
    );
    assert!(codec::decode_frame(&fixture(&"x".repeat(512), true), 4096).is_ok());
}

/// 锁定 readyToken 的 schema 形状与递归拒绝边界，防止伪 ready 进入 supervisor。
#[test]
fn ready_token_codec_rejects_missing_malformed_and_nested_markers() {
    let missing_initialized = br#"{"jsonrpc":"2.0","method":"runtime/initialized","params":{}}
"#;
    assert_eq!(
        codec::decode_frame(missing_initialized, 4096),
        Err(CodecError::HandshakeFailed)
    );
    let malformed_ready = br#"{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"status":"ready","readyToken":"short"}}
"#;
    assert_eq!(
        codec::decode_frame(malformed_ready, 4096),
        Err(CodecError::HandshakeFailed)
    );
    let nested_ready = br#"{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"status":"ready","readyToken":"0123456789abcdef0123456789abcdef","details":{"readyToken":"0123456789abcdef0123456789abcdef"}}}
"#;
    assert_eq!(
        codec::decode_frame(nested_ready, 4096),
        Err(CodecError::HandshakeFailed)
    );
    let non_ready_token = br#"{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"status":"starting","readyToken":"0123456789abcdef0123456789abcdef"}}
"#;
    assert_eq!(
        codec::decode_frame(non_ready_token, 4096),
        Err(CodecError::InvalidEnvelope)
    );
    let root_token = br#"{"jsonrpc":"2.0","id":"c:result","result":{},"meta":{"readyToken":"0123456789abcdef0123456789abcdef"}}
"#;
    assert_eq!(
        codec::decode_frame(root_token, 4096),
        Err(CodecError::InvalidEnvelope)
    );
    let uppercase_token = br#"{"jsonrpc":"2.0","method":"runtime/initialized","params":{"readyToken":"0123456789ABCDEF0123456789ABCDEF"}}
"#;
    assert_eq!(
        codec::decode_frame(uppercase_token, 4096),
        Err(CodecError::HandshakeFailed)
    );
    let ready_nested_value = br#"{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"status":"ready","readyToken":"0123456789abcdef0123456789abcdef","details":{"value":"fedcba9876543210fedcba9876543210"}}}
"#;
    assert!(codec::decode_frame(ready_nested_value, 4096).is_ok());
    let ready_token_key = br#"{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"status":"ready","readyToken":"0123456789abcdef0123456789abcdef","details":{"0123456789abcdef0123456789abcdef":true}}}
"#;
    assert!(codec::decode_frame(ready_token_key, 4096).is_ok());
    let ready_root_token = br#"{"jsonrpc":"2.0","method":"runtime/status-changed","params":{"status":"ready","readyToken":"0123456789abcdef0123456789abcdef"},"meta":{"old":"fedcba9876543210fedcba9876543210"}}
"#;
    assert_eq!(
        codec::decode_frame(ready_root_token, 4096),
        Err(CodecError::InvalidEnvelope)
    );
}

/// opaque provider ID 可以与握手 token 形状相同；Session 应用精确 active challenge 前，
/// 只拒绝保留 marker key。
#[test]
fn token_marker_policy_allows_opaque_hex_and_rejects_reserved_keys() {
    let token_variants = [
        "0123456789abcdef0123456789abcdef",
        "0123456789ABCDEF0123456789ABCDEF",
        "0123456789aBcDeF0123456789aBcDeF",
    ];
    for token in token_variants {
        let delta = json!({"turnId":"turn_token", "streamSeq":1, "text":token});
        assert!(RpcFrame::notification("assistant/text-delta", delta.clone()).is_ok());
        assert!(
            RpcFrame::client_request(
                "c:token",
                "thread/create",
                json!({
                    "cwd":"C:/token",
                    "title":token
                }),
            )
            .is_ok()
        );
        let inbound = serde_json::json!({
            "jsonrpc":"2.0",
            "method":"assistant/text-delta",
            "params":delta
        });
        let mut inbound_bytes = serde_json::to_vec(&inbound).expect("serialize fixture");
        inbound_bytes.push(b'\n');
        assert!(codec::decode_frame(&inbound_bytes, 4096).is_ok());

        let error = serde_json::json!({
            "jsonrpc":"2.0",
            "id":"c:token",
            "error":{
                "code":-32020,
                "message":"shutting down",
                "data":{
                    "errorCode":"SHUTTING_DOWN",
                    "category":"unavailable",
                    "retryable":true,
                    "errorId":"err_00000000000000000000000000000001",
                    "details":{"deep":[{"value":token}]}
                }
            }
        });
        let mut error_bytes = serde_json::to_vec(&error).expect("serialize error fixture");
        error_bytes.push(b'\n');
        assert_eq!(
            codec::decode_frame(&error_bytes, 4096),
            Err(CodecError::InvalidEnvelope)
        );
    }

    for key in ["readyToken", "READYTOKEN", "readyTOKEN"] {
        let mut object = serde_json::Map::new();
        object.insert(key.to_owned(), json!("ordinary value"));
        let payload = json!({"outer":[serde_json::Value::Object(object)]});
        assert!(
            RpcFrame::notification("assistant/text-delta", payload.clone()).is_err(),
            "marker key variant must reject {key:?}"
        );
        let inbound = serde_json::json!({
            "jsonrpc":"2.0",
            "method":"assistant/text-delta",
            "params":payload
        });
        let mut inbound_bytes = serde_json::to_vec(&inbound).expect("serialize key fixture");
        inbound_bytes.push(b'\n');
        assert_eq!(
            codec::decode_frame(&inbound_bytes, 4096),
            Err(CodecError::InvalidEnvelope),
            "inbound marker key variant must reject {key:?}"
        );
    }

    for safe in [
        "0123456789abcdef0123456789abcde",
        "0123456789abcdef0123456789abcdef0",
        "普通中文描述，不是 token",
    ] {
        let frame = RpcFrame::notification(
            "assistant/text-delta",
            json!({"turnId":"turn_safe", "streamSeq":1, "text":safe}),
        )
        .expect("non-token-shaped text remains valid");
        let encoded = frame.encode(4096).expect("safe outbound frame");
        assert!(codec::decode_frame(&encoded, 4096).is_ok());
    }

    let escaped_value = br#"{"jsonrpc":"2.0","method":"assistant/text-delta","params":{"turnId":"turn_safe","streamSeq":1,"text":"0123456789abcdef0123456789abcde\u0046"}}
"#;
    assert!(codec::decode_frame(escaped_value, 4096).is_ok());
    let escaped_key =
        br#"{"jsonrpc":"2.0","method":"assistant/text-delta","params":{"turnId":"turn_safe","streamSeq":1,"text":"safe","\u0052EADYTOKEN":"safe"}}
"#;
    assert_eq!(
        codec::decode_frame(escaped_key, 4096),
        Err(CodecError::InvalidEnvelope)
    );
}

/// error data 必须重建为冻结的安全投影，未知详情和 challenge marker 都不能穿过日志边界。
#[test]
fn inbound_error_data_is_rebuilt_as_safe_projection() {
    let frame = codec::decode_frame(
        br#"{"jsonrpc":"2.0","id":"c:error","error":{"code":-32020,"message":"shutting down","data":{"errorCode":"SHUTTING_DOWN","category":"unavailable","retryable":true,"errorId":"err_00000000000000000000000000000001"}}}
"#,
        4096,
    )
    .expect("catalog error is valid");
    let debug = format!("{frame:?}");
    let error = frame.error().expect("error projection");
    assert_eq!(
        error.data(),
        &json!({"errorCode":"SHUTTING_DOWN","category":"unavailable","retryable":true,"errorId":"err_00000000000000000000000000000001"})
    );
    assert!(!debug.contains("readyToken"));

    let unexpected_details = br#"{"jsonrpc":"2.0","id":"c:error-extra","error":{"code":-32020,"message":"shutting down","data":{"errorCode":"SHUTTING_DOWN","category":"unavailable","retryable":true,"errorId":"err_00000000000000000000000000000001","details":"api-key=secret"}}}
"#;
    assert_eq!(
        codec::decode_frame(unexpected_details, 4096),
        Err(CodecError::InvalidEnvelope)
    );

    // codec 审计完整 error object 前，projection 不得擦除 raw challenge marker；该回归
    // 防止 error.detail 变成 token 外泄侧信道。
    let nested_ready_token = br#"{"jsonrpc":"2.0","id":"c:error-token","error":{"code":-32020,"message":"shutting down","data":{"errorCode":"SHUTTING_DOWN","category":"unavailable","retryable":true,"errorId":"err_00000000000000000000000000000001","details":{"readyToken":"0123456789abcdef0123456789abcdef"}}}}
"#;
    assert_eq!(
        codec::decode_frame(nested_ready_token, 4096),
        Err(CodecError::InvalidEnvelope)
    );
}

/// 连续 JSONL 帧必须由同一 reader 完整保留，同时 EOF 和超限帧维持稳定错误分类。
#[test]
fn codec_limits_and_reader_keep_consecutive_frames() {
    let first = success_frame("c:one").encode(1024).unwrap();
    let second = success_frame("c:two").encode(1024).unwrap();
    let mut reader = io::BufReader::new(std::io::Cursor::new([first, second].concat()));
    assert_eq!(
        read_frame_with_forbidden(&mut reader, 1024, &HashSet::new())
            .unwrap()
            .id_opt(),
        Some("c:one")
    );
    assert_eq!(
        read_frame_with_forbidden(&mut reader, 1024, &HashSet::new())
            .unwrap()
            .id_opt(),
        Some("c:two")
    );
    assert_eq!(
        read_frame_with_forbidden(&mut reader, 1024, &HashSet::new()),
        Err(CodecError::UnexpectedEof)
    );
    let mut oversized = vec![b'x'; 1025];
    oversized.push(b'\n');
    assert_eq!(
        codec::decode_frame(&oversized, 1024),
        Err(CodecError::FrameTooLarge {
            actual: 1025,
            max: 1024
        })
    );
}
