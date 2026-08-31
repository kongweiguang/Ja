// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 测试位于独立 Cargo test target；生产 `src` 不携带 cfg(test) 或测试装配。

use super::*;
use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use uuid::Uuid;

struct TestRun {
    root: PathBuf,
}

impl TestRun {
    /// 每个 case 使用不可预测的独立目录，避免并行测试共享 staging 或误删其它进程文件。
    fn new(label: &str) -> Self {
        let root = std::env::temp_dir().join(format!("ja-attachment-{label}-{}", Uuid::new_v4()));
        fs::create_dir_all(&root).expect("create isolated test run");
        Self { root }
    }

    /// fixture 始终位于 run 兄弟目录；shutdown 只应删除 attachment-ingress，不能触碰这里。
    fn source(&self, name: &str, bytes: &[u8]) -> PathBuf {
        let source_root = self.root.join("user source");
        fs::create_dir_all(&source_root).expect("create source root");
        let path = source_root.join(name);
        fs::write(&path, bytes).expect("write source fixture");
        path
    }
}

impl Drop for TestRun {
    /// 只清理本 fixture 创建的 UUID 目录，失败留给系统临时目录回收且不影响断言。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

/// 小预算 service 避免用大文件覆盖生产上限逻辑，同时保留完全相同的复制路径。
fn ingress(run: &TestRun, files: usize, file_bytes: u64, batch_bytes: u64) -> AttachmentIngress {
    let limits = IngressLimits {
        max_files: files,
        max_file_bytes: file_bytes,
        max_batch_bytes: batch_bytes,
    };
    AttachmentIngress::with_limits(&run.root, limits).expect("create ingress")
}

/// 测试期显式 lower-hex，和标准 SHA-256 向量比较而不依赖生产 helper。
fn sha256(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// 产品合同的三个默认上限必须精确固定，测试小预算不能意外替换生产默认值。
#[test]
fn production_limits_are_ten_files_one_hundred_mib_and_two_hundred_fifty_mib() {
    let limits = IngressLimits::default();
    assert_eq!(limits.max_files, 10);
    assert_eq!(limits.max_file_bytes, 100 * 1024 * 1024);
    assert_eq!(limits.max_batch_bytes, 250 * 1024 * 1024);
}

/// 取消 dialog 的领域结果为空成功，且不创建任何 staging 文件。
#[test]
fn empty_selection_is_success_without_staging() {
    let run = TestRun::new("cancel");
    let ingress = ingress(&run, 10, 1024, 2048);

    assert_eq!(
        ingress.stage_paths(Vec::new()).expect("cancel success"),
        Vec::new()
    );
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("read ingress root")
            .count(),
        0
    );
}

/// Unicode 与空格只进入 displayName；serialized DTO 不得包含 source path 或 Rust-only token。
#[test]
fn stages_unicode_and_space_with_hash_and_redacted_serialization() {
    let run = TestRun::new("unicode");
    let source = run.source("设计 说明.txt", b"abc");
    let ingress = ingress(&run, 10, 1024, 2048);

    let values = ingress
        .stage_paths(vec![source.clone()])
        .expect("stage safe file");
    assert_eq!(values.len(), 1);
    assert_eq!(values[0].display_name, "设计 说明.txt");
    assert_eq!(values[0].size_bytes, 3);
    assert_eq!(values[0].sha256, sha256(b"abc"));
    let wire = serde_json::to_string(&values[0]).expect("serialize metadata");
    assert!(!wire.contains(values[0].ingress_token.as_str()));
    assert!(!wire.contains(source.to_string_lossy().as_ref()));
    assert_eq!(
        fs::read(
            run.root
                .join("attachment-ingress")
                .join(values[0].ingress_token.as_str())
        )
        .expect("read staged copy"),
        b"abc"
    );
    assert!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("read ingress root")
            .all(|entry| !entry
                .expect("directory entry")
                .file_name()
                .to_string_lossy()
                .ends_with(".part"))
    );
}

/// 数量策略在读取任意 path 前触发，非法第 11 个路径也不能产生 partial staging。
#[test]
fn rejects_more_than_ten_files_before_source_io() {
    let run = TestRun::new("count");
    let ingress = AttachmentIngress::new(&run.root).expect("production ingress");
    let paths = (0..11)
        .map(|index| run.root.join(format!("missing-{index}")))
        .collect();

    let error = ingress.stage_paths(paths).expect_err("count limit");
    assert_eq!(error.code, AttachmentIngressErrorCode::TooManyFiles);
}

/// 单文件上限取 metadata 与实际读取双重门禁，超过预算时不留下 `.part`。
#[test]
fn rejects_file_larger_than_budget_without_partial_file() {
    let run = TestRun::new("file-limit");
    let source = run.source("large.bin", b"12345");
    let ingress = ingress(&run, 10, 4, 20);

    let error = ingress.stage_paths(vec![source]).expect_err("file limit");
    assert_eq!(error.code, AttachmentIngressErrorCode::FileTooLarge);
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("read ingress root")
            .count(),
        0
    );
}

/// batch 预算使用 checked addition 并在复制前完成，避免前几个文件先产生可见结果。
#[test]
fn rejects_batch_larger_than_budget_atomically() {
    let run = TestRun::new("batch-limit");
    let first = run.source("first.txt", b"123");
    let second = run.source("second.txt", b"456");
    let ingress = ingress(&run, 10, 4, 5);

    let error = ingress
        .stage_paths(vec![first, second])
        .expect_err("batch limit");
    assert_eq!(error.code, AttachmentIngressErrorCode::BatchTooLarge);
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("read ingress root")
            .count(),
        0
    );
}

/// 目录与硬链接都不是独占普通源文件；拒绝时绝不删除用户节点。
#[test]
fn rejects_directory_and_hardlink_without_touching_sources() {
    let run = TestRun::new("node-types");
    let directory = run.root.join("selected-directory");
    fs::create_dir(&directory).expect("create directory");
    let ingress = ingress(&run, 10, 1024, 2048);
    assert_eq!(
        ingress
            .stage_paths(vec![directory.clone()])
            .expect_err("directory")
            .code,
        AttachmentIngressErrorCode::NotRegularFile
    );
    assert!(directory.is_dir());

    let original = run.source("original.txt", b"owned by user");
    let alias = run.root.join("user source").join("alias.txt");
    fs::hard_link(&original, &alias).expect("create hardlink");
    assert_eq!(
        ingress
            .stage_paths(vec![original.clone()])
            .expect_err("hardlink")
            .code,
        AttachmentIngressErrorCode::LinkNotAllowed
    );
    assert!(original.exists());
    assert!(alias.exists());
}

/// preflight 后替换路径必须由物理 identity 发现；前一个已复制文件也随 batch rollback 清理。
#[test]
fn detects_path_swap_and_rolls_back_earlier_staging() {
    let run = TestRun::new("toctou");
    let first = run.source("first.txt", b"first");
    let second = run.source("second.txt", b"second");
    let replacement = run.source("replacement.txt", b"changed");
    let backup = run.root.join("user source").join("second.backup");
    let ingress = ingress(&run, 10, 1024, 2048);

    let error = ingress
        .stage_paths_with_observer(vec![first, second.clone()], |checkpoint| {
            if checkpoint == (IngressCheckpoint::PreflightComplete { index: 1 }) {
                fs::rename(&second, &backup).expect("move approved identity away");
                fs::rename(&replacement, &second).expect("swap source path");
            }
        })
        .expect_err("identity swap");
    assert_eq!(error.code, AttachmentIngressErrorCode::SourceChanged);
    assert_eq!(
        fs::read_dir(run.root.join("attachment-ingress"))
            .expect("read ingress root")
            .count(),
        0
    );
    assert_eq!(fs::read(&backup).expect("user backup remains"), b"second");
    assert_eq!(fs::read(&second).expect("replacement remains"), b"changed");
}

/// complete/discard 只清理对应 staging，源文件与其它 token 保持不变；未知 token 失败关闭。
#[test]
fn complete_discard_and_shutdown_have_explicit_cleanup_ownership() {
    let run = TestRun::new("lifecycle");
    let first = run.source("first.txt", b"first");
    let second = run.source("second.txt", b"second");
    let ingress = ingress(&run, 10, 1024, 2048);
    let values = ingress
        .stage_paths(vec![first.clone(), second.clone()])
        .expect("stage");
    let first_staged = run
        .root
        .join("attachment-ingress")
        .join(values[0].ingress_token.as_str());
    let second_staged = run
        .root
        .join("attachment-ingress")
        .join(values[1].ingress_token.as_str());

    ingress
        .complete(&values[0].ingress_token)
        .expect("complete");
    assert!(!first_staged.exists());
    assert!(second_staged.exists());
    ingress.discard(&values[1].ingress_token).expect("discard");
    assert!(!second_staged.exists());
    assert!(first.exists());
    assert!(second.exists());
    assert_eq!(
        ingress
            .discard(&values[1].ingress_token)
            .expect_err("unknown token")
            .code,
        AttachmentIngressErrorCode::TokenNotFound
    );

    let third = run.source("third.txt", b"third");
    let third = ingress
        .stage_paths(vec![third])
        .expect("stage third")
        .remove(0);
    let third_staged = run
        .root
        .join("attachment-ingress")
        .join(third.ingress_token.as_str());
    ingress.shutdown().expect("shutdown cleanup");
    assert!(!third_staged.exists());
    assert_eq!(
        ingress
            .stage_paths(Vec::new())
            .expect_err("closed admission")
            .code,
        AttachmentIngressErrorCode::LifecycleClosed
    );
    ingress.shutdown().expect("idempotent shutdown");
}

#[cfg(windows)]
/// Win32 device/NT namespace 与 DOS 保留名必须在任何 filesystem open 前拒绝。
#[test]
fn windows_rejects_device_and_nt_namespace_spellings() {
    let run = TestRun::new("windows-device");
    let ingress = ingress(&run, 10, 1024, 2048);
    for path in [
        PathBuf::from(r"\\.\NUL"),
        PathBuf::from(r"\\?\C:\Windows\notepad.exe"),
        PathBuf::from(r"C:\NUL.txt"),
        PathBuf::from(r"C:\safe\file.txt:stream"),
    ] {
        assert_eq!(
            ingress
                .stage_paths(vec![path])
                .expect_err("unsupported namespace")
                .code,
            AttachmentIngressErrorCode::UnsupportedPath
        );
    }
}

#[cfg(windows)]
/// 最终 symlink 与祖先 junction 都带 reparse attribute，canonicalize 之前必须拒绝。
#[test]
fn windows_rejects_symlink_and_junction_components() {
    use std::os::windows::fs::symlink_file;
    use std::process::Command;

    let run = TestRun::new("windows-reparse");
    let target = run.source("target.txt", b"target");
    let link = run.root.join("user source").join("link.txt");
    let ingress = ingress(&run, 10, 1024, 2048);
    match symlink_file(&target, &link) {
        Ok(()) => assert_eq!(
            ingress.stage_paths(vec![link]).expect_err("symlink").code,
            AttachmentIngressErrorCode::LinkNotAllowed
        ),
        Err(error) if error.raw_os_error() == Some(1314) => {
            // CI/开发机未启用 Developer Mode 时没有创建 symlink 的权限；下方 junction
            // 仍以真实 reparse point 强制覆盖同一 Windows attribute 拒绝分支。
        }
        Err(error) => panic!("create file symlink fixture: {error:?}"),
    }

    let actual_directory = run.root.join("actual-directory");
    fs::create_dir(&actual_directory).expect("create junction target");
    fs::write(actual_directory.join("inside.txt"), b"inside").expect("write junction target");
    let junction = run.root.join("junction-directory");
    let status = Command::new("cmd.exe")
        .args(["/D", "/C", "mklink", "/J"])
        .arg(&junction)
        .arg(&actual_directory)
        .status()
        .expect("launch mklink");
    assert!(status.success(), "create junction fixture");
    assert_eq!(
        ingress
            .stage_paths(vec![junction.join("inside.txt")])
            .expect_err("junction ancestor")
            .code,
        AttachmentIngressErrorCode::LinkNotAllowed
    );
}

#[cfg(windows)]
/// 正式读取句柄不共享 write/delete，阻止复制窗口内的 size/mtime/content 并发修改。
#[test]
fn windows_read_handle_denies_concurrent_mutation() {
    let run = TestRun::new("windows-share-lock");
    let source = run.source("locked.txt", b"stable");
    let ingress = ingress(&run, 10, 1024, 2048);
    let mutation_denied = AtomicBool::new(false);

    let values = ingress
        .stage_paths_with_observer(vec![source.clone()], |checkpoint| {
            if checkpoint == (IngressCheckpoint::SourceOpened { index: 0 }) {
                let mutation = OpenOptions::new().write(true).truncate(true).open(&source);
                mutation_denied.store(mutation.is_err(), Ordering::Release);
            }
        })
        .expect("stage locked source");
    assert!(mutation_denied.load(Ordering::Acquire));
    assert_eq!(values[0].sha256, sha256(b"stable"));
    assert_eq!(fs::read(source).expect("source preserved"), b"stable");
}
