// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::*;
use crate::attachments::AttachmentIngressErrorCode;
use arboard::Error as ClipboardError;
use std::fs;
use tauri::ipc::{Channel, InvokeResponseBody};

struct TestRun {
    root: PathBuf,
}

impl TestRun {
    /// 每个 workflow case 使用独立随机目录，清理只作用于自己创建的 fixture。
    fn new(label: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "ja-attachment-interface-{label}-{}",
            Uuid::new_v4()
        ));
        fs::create_dir_all(&root).expect("create run root");
        Self { root }
    }

    /// 源文件与 staging 分开，断言 cleanup 不会误删用户选择的文件。
    fn source(&self, name: &str, content: &[u8]) -> PathBuf {
        let source_root = self.root.join("source");
        fs::create_dir_all(&source_root).expect("create source root");
        let path = source_root.join(name);
        fs::write(&path, content).expect("write source");
        path
    }
}

impl Drop for TestRun {
    /// UUID 根只属于当前 case，best-effort 清理不会扩大到系统临时目录。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

struct FakeRuntime {
    imported: Mutex<Vec<AttachmentImportInput>>,
    discarded: Mutex<Vec<String>>,
    fail_at: Option<usize>,
    retryable: bool,
}

impl FakeRuntime {
    /// fail_at 使用一基索引制造精确 item failure，验证兄弟 item 仍保持自己的终态。
    fn new(fail_at: Option<usize>, retryable: bool) -> Self {
        Self {
            imported: Mutex::new(Vec::new()),
            discarded: Mutex::new(Vec::new()),
            fail_at,
            retryable,
        }
    }
}

impl AttachmentRuntimePort for FakeRuntime {
    /// 返回与输入 name/size 一致的脱敏 draft，或在指定序号制造稳定 Runtime failure。
    fn import_attachment(
        &self,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        let mut imported = self.imported.lock().expect("import lock");
        imported.push(input.clone());
        let ordinal = imported.len();
        if self.fail_at == Some(ordinal) {
            return Err(RuntimeCommandError {
                code: "RUNTIME_UNAVAILABLE",
                message: "runtime bridge is unavailable",
                retryable: self.retryable,
            });
        }
        Ok(AttachmentMetadata {
            attachment_id: format!("att_{ordinal}"),
            display_name: input.display_name,
            size_bytes: input.size_bytes,
            media_kind: "text".to_owned(),
            media_type: Some("text/plain".to_owned()),
            state: "draft".to_owned(),
        })
    }

    /// 记录 compensation identity；测试不伪造物理 Java blob，只证明 workflow 调用真实 port。
    fn discard_attachment(&self, attachment_id: String) -> Result<(), RuntimeCommandError> {
        self.discarded
            .lock()
            .expect("discard lock")
            .push(attachment_id);
        Ok(())
    }
}

/// Channel test adapter 仅解析 serde JSON body；生产 WebView transport 仍由 Tauri 自身负责。
fn event_channel(
    events: Arc<Mutex<Vec<AttachmentIngressEvent>>>,
) -> Channel<AttachmentIngressEvent> {
    Channel::new(move |body| {
        let InvokeResponseBody::Json(json) = body else {
            panic!("attachment event must be json");
        };
        let value = serde_json::from_str::<serde_json::Value>(&json).expect("event json");
        let event = match value["kind"].as_str().expect("event kind") {
            "started" => AttachmentIngressEvent::Started {
                operation_id: value["operationId"].as_str().unwrap().to_owned(),
                attempt_id: value["attemptId"].as_str().unwrap().to_owned(),
                item_id: value["itemId"].as_str().unwrap().to_owned(),
                file_name: value["fileName"].as_str().unwrap().to_owned(),
                size_bytes: value["sizeBytes"].as_u64().unwrap(),
            },
            "progress" => AttachmentIngressEvent::Progress {
                operation_id: value["operationId"].as_str().unwrap().to_owned(),
                attempt_id: value["attemptId"].as_str().unwrap().to_owned(),
                item_id: value["itemId"].as_str().unwrap().to_owned(),
                phase: if value["phase"] == "copying" {
                    AttachmentIngressPhase::Copying
                } else {
                    AttachmentIngressPhase::Importing
                },
                bytes_copied: value["bytesCopied"].as_u64().unwrap(),
                total_bytes: value["totalBytes"].as_u64().unwrap(),
            },
            "completed" => AttachmentIngressEvent::Completed {
                operation_id: value["operationId"].as_str().unwrap().to_owned(),
                attempt_id: value["attemptId"].as_str().unwrap().to_owned(),
                item_id: value["itemId"].as_str().unwrap().to_owned(),
                attachment: AttachmentDto {
                    attachment_id: value["attachment"]["attachmentId"]
                        .as_str()
                        .unwrap()
                        .to_owned(),
                    file_name: value["attachment"]["fileName"].as_str().unwrap().to_owned(),
                    size_bytes: value["attachment"]["sizeBytes"].as_u64().unwrap(),
                    media_kind: value["attachment"]["mediaKind"]
                        .as_str()
                        .unwrap()
                        .to_owned(),
                    media_type: value["attachment"]["mediaType"]
                        .as_str()
                        .map(ToOwned::to_owned),
                    state: value["attachment"]["state"].as_str().unwrap().to_owned(),
                },
            },
            "failed" => AttachmentIngressEvent::Failed {
                operation_id: value["operationId"].as_str().unwrap().to_owned(),
                attempt_id: value["attemptId"].as_str().unwrap().to_owned(),
                item_id: value["itemId"].as_str().unwrap().to_owned(),
                file_name: value["fileName"].as_str().map(ToOwned::to_owned),
                size_bytes: value["sizeBytes"].as_u64(),
                code: Box::leak(value["code"].as_str().unwrap().to_owned().into_boxed_str()),
                message: Box::leak(
                    value["message"]
                        .as_str()
                        .unwrap()
                        .to_owned()
                        .into_boxed_str(),
                ),
                retryable: value["retryable"].as_bool().unwrap(),
            },
            "cancelled" => AttachmentIngressEvent::Cancelled {
                operation_id: value["operationId"].as_str().unwrap().to_owned(),
                attempt_id: value["attemptId"].as_str().unwrap().to_owned(),
                item_id: value["itemId"].as_str().unwrap().to_owned(),
            },
            other => panic!("unknown event {other}"),
        };
        events.lock().expect("event lock").push(event);
        Ok(())
    })
}

/// admission 在 started 前失败时可展示字段必须省略而不是写 null，保持严格 WebView DTO 可解析。
#[test]
fn pre_admission_failure_omits_unavailable_optional_fields() {
    let event = AttachmentIngressEvent::Failed {
        operation_id: "operation_failure".to_owned(),
        attempt_id: "attempt_failure".to_owned(),
        item_id: "item_failure".to_owned(),
        file_name: None,
        size_bytes: None,
        code: "ATTACHMENT_INGRESS_FAILED",
        message: "attachment could not be imported",
        retryable: false,
    };

    let value = serde_json::to_value(event).expect("failure event json");
    let object = value.as_object().expect("failure event object");
    assert!(!object.contains_key("fileName"));
    assert!(!object.contains_key("sizeBytes"));
}

/// 成功导入逐项发 started/copying/importing/completed，终态只含脱敏 metadata。
#[test]
fn successful_channel_import_cleans_staging_and_projects_media_kind() {
    let run = TestRun::new("success");
    let paths = vec![
        run.source("first.txt", b"one"),
        run.source("second.txt", b"two"),
    ];
    let ingress = Arc::new(AttachmentIngress::new(&run.root).expect("ingress"));
    ingress.begin_operation("operation_success").expect("begin");
    let runtime = FakeRuntime::new(None, false);
    let events = Arc::new(Mutex::new(Vec::new()));

    import_paths(
        &runtime,
        Arc::clone(&ingress),
        "operation_success".to_owned(),
        paths,
        event_channel(Arc::clone(&events)),
    );

    let events = events.lock().expect("events");
    assert_eq!(
        events
            .iter()
            .filter(|event| matches!(event, AttachmentIngressEvent::Completed { .. }))
            .count(),
        2
    );
    assert!(events.iter().all(|event| match event {
        AttachmentIngressEvent::Completed { attachment, .. } => attachment.media_kind == "text",
        _ => true,
    }));
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("staging")
            .count(),
        0
    );
    let wire = serde_json::to_string(&*events).expect("events json");
    assert!(!wire.contains(run.root.to_string_lossy().as_ref()));
    assert!(!wire.contains("sha256"));
    assert!(!wire.contains("ingressToken"));
}

/// 单项 Runtime 失败不回滚 ready 兄弟；retry capability 只通过 attemptId 暴露并可显式丢弃。
#[test]
fn retryable_item_failure_keeps_sibling_ready_and_discards_opaque_attempt() {
    let run = TestRun::new("retry");
    let paths = vec![
        run.source("first.txt", b"one"),
        run.source("second.txt", b"two"),
    ];
    let ingress = Arc::new(AttachmentIngress::new(&run.root).expect("ingress"));
    ingress.begin_operation("operation_retry").expect("begin");
    let runtime = FakeRuntime::new(Some(2), true);
    let events = Arc::new(Mutex::new(Vec::new()));

    import_paths(
        &runtime,
        Arc::clone(&ingress),
        "operation_retry".to_owned(),
        paths,
        event_channel(Arc::clone(&events)),
    );

    let attempt_id = events
        .lock()
        .expect("events")
        .iter()
        .find_map(|event| match event {
            AttachmentIngressEvent::Failed {
                attempt_id,
                retryable: true,
                ..
            } => Some(attempt_id.clone()),
            _ => None,
        })
        .expect("retryable failure");
    assert_eq!(runtime.discarded.lock().expect("discarded").len(), 0);
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("staging")
            .count(),
        1
    );
    ingress
        .discard_retry_attempt(&attempt_id)
        .expect("discard attempt");
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("staging")
            .count(),
        0
    );
    assert!(ingress.discard_retry_attempt(&attempt_id).is_err());
}

/// 两个 operation 的 Channel identity 始终各自闭合，不能因共享 ingress registry 串线。
#[test]
fn concurrent_operations_never_cross_channel_identity() {
    let run = TestRun::new("concurrent");
    let first = run.source("first.txt", b"first");
    let second = run.source("second.txt", b"second");
    let ingress = Arc::new(AttachmentIngress::new(&run.root).expect("ingress"));
    ingress.begin_operation("operation_a").expect("begin a");
    ingress.begin_operation("operation_b").expect("begin b");
    let runtime = Arc::new(FakeRuntime::new(None, false));
    let a = Arc::new(Mutex::new(Vec::new()));
    let b = Arc::new(Mutex::new(Vec::new()));
    let left = {
        let ingress = Arc::clone(&ingress);
        let runtime = Arc::clone(&runtime);
        let channel = event_channel(Arc::clone(&a));
        thread::spawn(move || {
            import_paths(
                runtime.as_ref(),
                ingress,
                "operation_a".to_owned(),
                vec![first],
                channel,
            )
        })
    };
    let right = {
        let ingress = Arc::clone(&ingress);
        let runtime = Arc::clone(&runtime);
        let channel = event_channel(Arc::clone(&b));
        thread::spawn(move || {
            import_paths(
                runtime.as_ref(),
                ingress,
                "operation_b".to_owned(),
                vec![second],
                channel,
            )
        })
    };
    left.join().expect("left");
    right.join().expect("right");

    assert!(
        a.lock()
            .expect("a")
            .iter()
            .all(|event| event_operation(event) == "operation_a")
    );
    assert!(
        b.lock()
            .expect("b")
            .iter()
            .all(|event| event_operation(event) == "operation_b")
    );
}

/// 所有 tagged variant 共用同一 operationId getter，仅用于并发归属断言。
fn event_operation(event: &AttachmentIngressEvent) -> &str {
    match event {
        AttachmentIngressEvent::Started { operation_id, .. }
        | AttachmentIngressEvent::Progress { operation_id, .. }
        | AttachmentIngressEvent::Completed { operation_id, .. }
        | AttachmentIngressEvent::Failed { operation_id, .. }
        | AttachmentIngressEvent::Cancelled { operation_id, .. } => operation_id,
    }
}

/// 剪贴板编码拒绝零尺寸、buffer 不匹配和超过 40MP，并为合法 RGBA 生成真实 PNG。
#[test]
fn clipboard_png_enforces_pixel_and_decoded_byte_budgets() {
    assert_eq!(
        encode_clipboard_png(0, 1, Vec::new())
            .expect_err("zero width")
            .code,
        AttachmentIngressErrorCode::ImageTooLarge
    );
    assert_eq!(
        encode_clipboard_png(2, 2, vec![0; 15])
            .expect_err("short rgba")
            .code,
        AttachmentIngressErrorCode::ImageTooLarge
    );
    assert_eq!(
        encode_clipboard_png(40_000_001, 1, Vec::new())
            .expect_err("pixel budget")
            .code,
        AttachmentIngressErrorCode::ImageTooLarge
    );
    let png = encode_clipboard_png(1, 1, vec![255, 0, 0, 255]).expect("png");
    assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
}

/// Native result 只序列化一个 snake_case outcome，拒绝恢复旧图片专用返回形状。
#[test]
fn clipboard_result_wire_shape_is_strict() {
    assert_eq!(
        serde_json::to_value(ClipboardImportResult {
            outcome: ClipboardImportOutcome::Accepted,
        })
        .expect("accepted result"),
        serde_json::json!({ "outcome": "accepted" })
    );
    assert_eq!(
        serde_json::to_value(ClipboardImportResult {
            outcome: ClipboardImportOutcome::NothingImportable,
        })
        .expect("empty result"),
        serde_json::json!({ "outcome": "nothing_importable" })
    );
    assert_eq!(
        serde_json::to_value(ClipboardImportResult {
            outcome: ClipboardImportOutcome::Busy,
        })
        .expect("busy result"),
        serde_json::json!({ "outcome": "busy" })
    );
}

struct FakeClipboard {
    paths: Option<Result<Vec<PathBuf>, ClipboardError>>,
    image: Option<Result<ClipboardImage, ClipboardError>>,
    image_reads: usize,
}

impl ClipboardReader for FakeClipboard {
    /// fixture 只消费一次文件结果，重复调用表示优先级策略发生了意外变化。
    fn read_file_list(&mut self) -> Result<Vec<PathBuf>, ClipboardError> {
        self.paths.take().expect("file list read once")
    }

    /// 记录位图分支是否被触达，用于证明 CF_HDROP 成功时不会读取图片。
    fn read_image(&mut self) -> Result<ClipboardImage, ClipboardError> {
        self.image_reads += 1;
        self.image.take().expect("image read once")
    }
}

/// CF_HDROP 保留 Explorer 原始顺序且短路图片读取，防止多格式剪贴板重复导入。
#[test]
fn clipboard_prefers_file_list_before_image() {
    let paths = vec![PathBuf::from("first.txt"), PathBuf::from("second.png")];
    let mut clipboard = FakeClipboard {
        paths: Some(Ok(paths.clone())),
        image: Some(Ok(ClipboardImage {
            width: 1,
            height: 1,
            rgba: vec![0, 0, 0, 255],
        })),
        image_reads: 0,
    };

    let result = select_clipboard_payload(&mut clipboard).expect("clipboard selection");
    let Some(ClipboardPayload::Paths(actual)) = result else {
        panic!("file list must win");
    };
    assert_eq!(actual, paths);
    assert_eq!(clipboard.image_reads, 0);
}

/// 无文件或文件描述不可转换时继续读取图片；只有两者都不可用才返回空内容。
#[test]
fn clipboard_falls_back_to_image_without_importing_html_or_urls() {
    let mut clipboard = FakeClipboard {
        paths: Some(Err(ClipboardError::ConversionFailure)),
        image: Some(Ok(ClipboardImage {
            width: 1,
            height: 1,
            rgba: vec![255, 0, 0, 255],
        })),
        image_reads: 0,
    };

    let result = select_clipboard_payload(&mut clipboard).expect("clipboard selection");
    assert!(matches!(result, Some(ClipboardPayload::Png(_))));
    assert_eq!(clipboard.image_reads, 1);

    let mut empty = FakeClipboard {
        paths: Some(Err(ClipboardError::ContentNotAvailable)),
        image: Some(Err(ClipboardError::ContentNotAvailable)),
        image_reads: 0,
    };
    assert!(
        select_clipboard_payload(&mut empty)
            .expect("empty clipboard")
            .is_none()
    );
}

/// ClipboardOccupied 只执行 15/35/70ms 三段退避，第四次仍占用时返回 busy。
#[test]
fn clipboard_busy_retry_budget_is_exactly_120_milliseconds() {
    let mut attempts = 0;
    let mut delays = Vec::new();
    let result = retry_clipboard_read::<()>(
        || {
            attempts += 1;
            Err(ClipboardError::ClipboardOccupied)
        },
        |delay| delays.push(delay),
    );

    assert_eq!(result, ClipboardRetryResult::Busy);
    assert_eq!(attempts, 4);
    assert_eq!(delays, [15_u64, 35, 70].map(Duration::from_millis).to_vec());
    assert_eq!(
        delays.into_iter().sum::<Duration>(),
        Duration::from_millis(120)
    );
}

/// 非占用错误立即收敛为空内容，不重试也不生成失败附件终态。
#[test]
fn clipboard_unknown_format_does_not_retry() {
    let mut attempts = 0;
    let result = retry_clipboard_read::<()>(
        || {
            attempts += 1;
            Err(ClipboardError::ConversionFailure)
        },
        |_| panic!("unknown format must not sleep"),
    );

    assert_eq!(result, ClipboardRetryResult::NothingImportable);
    assert_eq!(attempts, 1);
}
