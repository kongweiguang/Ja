// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::*;
use std::fs;

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
}

impl FakeRuntime {
    /// fail_at 使用一基索引模拟批次中间 Java failure，便于验证已提交 draft 的补偿顺序。
    fn new(fail_at: Option<usize>) -> Self {
        Self {
            imported: Mutex::new(Vec::new()),
            discarded: Mutex::new(Vec::new()),
            fail_at,
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
                retryable: true,
            });
        }
        Ok(AttachmentMetadata {
            attachment_id: format!("att_{ordinal}"),
            display_name: input.display_name,
            size_bytes: input.size_bytes,
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

/// 成功导入后每个 staging 都被 complete，返回 DTO 不含 hash、token 或路径。
#[test]
fn successful_batch_cleans_staging_and_returns_redacted_metadata() {
    let run = TestRun::new("success");
    let first = run.source("first.txt", b"one");
    let second = run.source("second.txt", b"two");
    let ingress = Arc::new(AttachmentIngress::new(&run.root).expect("ingress"));
    let runtime = FakeRuntime::new(None);

    let result = import_selected(
        &runtime,
        Arc::clone(&ingress),
        vec![first.clone(), second.clone()],
    )
    .expect("import batch");

    assert_eq!(result.len(), 2);
    assert_eq!(result[0].attachment_id, "att_1");
    assert_eq!(result[0].file_name, "first.txt");
    assert_eq!(result[0].state, "draft");
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("staging")
            .count(),
        0
    );
    assert_eq!(fs::read(first).expect("source remains"), b"one");
    assert_eq!(fs::read(second).expect("source remains"), b"two");
}

/// 第二个 RPC 失败时补偿第一个 Java draft，并清理整批 Rust staging，不把 partial identity 丢给 UI。
#[test]
fn import_failure_compensates_committed_drafts_and_cleans_all_staging() {
    let run = TestRun::new("failure");
    let first = run.source("first.txt", b"one");
    let second = run.source("second.txt", b"two");
    let ingress = Arc::new(AttachmentIngress::new(&run.root).expect("ingress"));
    let runtime = FakeRuntime::new(Some(2));

    let error = import_selected(&runtime, Arc::clone(&ingress), vec![first, second])
        .expect_err("second import fails");

    assert_eq!(error.code, "ATTACHMENT_RUNTIME_FAILED");
    assert!(error.retryable);
    assert_eq!(
        runtime.discarded.lock().expect("discarded").as_slice(),
        ["att_1"]
    );
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("staging")
            .count(),
        0
    );
}

/// one-shot callback bridge 对成功终态只解析一次，command future 不依赖轮询或无界等待。
#[test]
fn callback_bridge_completes_the_event_loop_once() {
    let (sender, receiver) = tokio::sync::oneshot::channel();
    sender
        .send(Ok::<_, AttachmentCommandError>(Vec::<u8>::new()))
        .expect("send once");
    let runtime = tokio::runtime::Runtime::new().expect("runtime");
    assert_eq!(
        runtime.block_on(await_callback(receiver)).expect("resolve"),
        Vec::<u8>::new()
    );
}

/// callback 在投递前关闭时必须稳定 reject，而不是把取消和内部生命周期故障混为一谈。
#[test]
fn callback_bridge_rejects_when_the_sender_is_lost() {
    let (sender, receiver) =
        tokio::sync::oneshot::channel::<Result<Vec<u8>, AttachmentCommandError>>();
    drop(sender);
    let runtime = tokio::runtime::Runtime::new().expect("runtime");
    let error = runtime
        .block_on(await_callback(receiver))
        .expect_err("reject");
    assert_eq!(error.code, "ATTACHMENT_DIALOG_FAILED");
}
