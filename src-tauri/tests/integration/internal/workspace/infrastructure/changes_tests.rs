// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

struct TempWorkspace(PathBuf);

impl TempWorkspace {
    /// 创建隔离原生目录，因为轮询正确性依赖真实 metadata 与内容 hash，不能由 mock 代替。
    fn create() -> Self {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "ja-rescan-{}-{stamp}-{}",
            std::process::id(),
            Uuid::new_v4()
        ));
        fs::create_dir_all(&path).expect("create polling fixture");
        Self(path)
    }

    /// fixture 通过原生 Watcher command 使用的同一物理身份边界注册，避免测试绕过准入规则。
    fn handle(&self) -> WorkspaceHandle {
        let registry = crate::workspace::WorkspaceRegistry::default();
        let info = registry.register(&self.0).expect("register fixture");
        registry.get(info.id).expect("workspace handle")
    }
}

impl Drop for TempWorkspace {
    /// 断言后只做尽力清理；生产检测器从不拥有或删除用户 Workspace 根目录。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// 使用真实目录项触发初始 entry budget，而不是 mock 扫描结果；构造必须
/// 成功保留降级 detector，并在目录缩回预算后建立完整基线。
#[test]
fn oversized_initial_baseline_starts_degraded_and_recovers() {
    let root = TempWorkspace::create();
    for index in 0..5 {
        fs::write(root.0.join(format!("entry-{index}.txt")), "fixture")
            .expect("large baseline entry");
    }
    let mut detector = PollingChangeDetector::new(
        root.handle(),
        PollingPolicy {
            max_entries: 4,
            ..PollingPolicy::default()
        },
    )
    .expect("overflowed baseline remains a live detector");
    assert!(detector.requires_initial_rescan());

    fs::remove_file(root.0.join("entry-4.txt")).expect("shrink baseline into budget");
    let recovered = detector.rescan().expect("recover complete baseline");

    assert_eq!(recovered.state, PollState::Updated);
    assert!(recovered.requires_rescan);
    assert!(recovered.changes.is_empty());
    assert!(!detector.requires_initial_rescan());
}

/// 可再生目录不能消耗 watcher 的 entry 预算，也不能因内部编译 churn 进入轮询差异。
#[test]
fn generated_directories_are_excluded_from_polling_baselines() {
    let root = TempWorkspace::create();
    let target = root.0.join("target");
    fs::create_dir_all(&target).expect("create ignored target");
    for index in 0..32 {
        fs::write(target.join(format!("artifact-{index}.bin")), "generated")
            .expect("generated artifact");
    }
    fs::write(root.0.join("source.rs"), "before").expect("source fixture");
    let mut detector = PollingChangeDetector::new(
        root.handle(),
        PollingPolicy {
            max_entries: 2,
            ..PollingPolicy::default()
        },
    )
    .expect("ignored children do not overflow baseline");
    assert!(!detector.requires_initial_rescan());

    fs::write(target.join("artifact-0.bin"), "changed generated").expect("change artifact");
    let batch = detector.rescan().expect("rescan ignored tree");
    assert!(!batch.requires_rescan);
    assert!(batch.changes.is_empty());
}

/// Watcher start 使用未初始化 detector，确保初始基线不会重新进入切换关键路径。
#[test]
fn uninitialized_detector_defers_baseline_until_reconciliation() {
    let root = TempWorkspace::create();
    fs::write(root.0.join("source.rs"), "source").expect("source fixture");
    let mut detector =
        PollingChangeDetector::new_uninitialized(root.handle(), PollingPolicy::default());

    assert!(detector.requires_initial_rescan());
    let recovered = detector.rescan().expect("lazy baseline");
    assert!(recovered.requires_rescan);
    assert!(!detector.requires_initial_rescan());
}

/// 直接 rescan 必须返回触发对账的编辑，包括粗粒度文件系统 mtime 无法识别的等长内容变化。
#[test]
fn rescan_emits_diff_before_replacing_baseline() {
    let root = TempWorkspace::create();
    fs::write(root.0.join("note.txt"), "old").expect("initial file");
    let mut detector =
        PollingChangeDetector::new(root.handle(), PollingPolicy::default()).expect("detector");
    fs::write(root.0.join("note.txt"), "new").expect("external edit");

    let batch = detector.rescan().expect("authoritative rescan");

    assert_eq!(batch.state, PollState::Updated);
    assert!(!batch.requires_rescan);
    assert_eq!(batch.changes.len(), 1);
    assert_eq!(batch.changes[0].relative_path, "note.txt");
    assert!(matches!(
        batch.changes[0].kind,
        ChangeKind::Modified | ChangeKind::Replaced
    ));
}

/// rescan 事件预算截断完整差异时，只返回有界前缀并保持 `requires_rescan`，
/// 迫使调用方通过 tree/read 恢复权威状态。
#[test]
fn rescan_overflow_keeps_bounded_diff_and_reconciliation_hint() {
    let root = TempWorkspace::create();
    fs::write(root.0.join("a.txt"), "old-a").expect("initial a");
    fs::write(root.0.join("b.txt"), "old-b").expect("initial b");
    let mut detector = PollingChangeDetector::new(
        root.handle(),
        PollingPolicy {
            max_changes: 1,
            ..PollingPolicy::default()
        },
    )
    .expect("detector");
    fs::write(root.0.join("a.txt"), "new-a").expect("external a");
    fs::write(root.0.join("b.txt"), "new-b").expect("external b");

    let batch = detector.rescan().expect("bounded rescan");

    assert_eq!(batch.state, PollState::Overflow);
    assert!(batch.requires_rescan);
    assert_eq!(batch.changes.len(), 1);
}
