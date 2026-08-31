// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime Harness 的确定性同步合同测试。

use super::*;
use std::sync::{Arc, mpsc};
use std::time::{Duration, Instant};

/// 创建当前测试独占的临时目录；UUID 防止并行测试共享 sidecar owner 或恢复文件。
fn test_root() -> std::path::PathBuf {
    std::env::temp_dir().join(format!(
        "ja-runtime-start-failure-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ))
}

/// 验证 start 失败 reply 被栅栏暂停时，高优先级 shutdown 仍可先完成准入并最终回收 actor。
#[test]
fn start_failure_barrier_preserves_priority_shutdown_order() {
    let root = test_root();
    std::fs::create_dir_all(&root).expect("create isolated runtime root");
    let executable = root.join("sidecar-that-will-disappear.exe");
    std::fs::write(&executable, b"test-owned launch placeholder")
        .expect("create launch placeholder");
    let sink: EventSink = Arc::new(|_| Ok(()));
    let (bridge, barrier) = RuntimeHostHarness::bridge_with_start_failure_control(
        RuntimeHostHarness::launch_config(executable.clone(), Vec::new(), root.clone()),
        sink,
    )
    .expect("create controlled bridge");
    let deadline = Instant::now() + Duration::from_secs(10);

    // 先让 actor 在线性化点暂停，证明 shutdown 不是依靠 start caller 先返回才完成准入。
    let (start_sender, start_receiver) = mpsc::sync_channel(1);
    let start_bridge = bridge.clone();
    let starter = std::thread::spawn(move || {
        let _ = start_sender.send(start_bridge.start());
    });
    assert!(
        barrier.wait_until_armed(deadline),
        "start failure did not reach reply barrier"
    );

    // shutdown 使用独立 control lane；只有观察到发送事实后才释放失败 reply。
    let (shutdown_sender, shutdown_receiver) = mpsc::sync_channel(1);
    let shutdown_bridge = bridge.clone();
    let shutdowner = std::thread::spawn(move || {
        let _ = shutdown_sender.send(shutdown_bridge.shutdown());
    });
    assert!(
        barrier.wait_for("inner_shutdown_sent", deadline),
        "priority shutdown was not admitted"
    );
    barrier.release();

    let start_error = start_receiver
        .recv_timeout(deadline.saturating_duration_since(Instant::now()))
        .expect("receive bounded start result")
        .expect_err("missing executable must fail");
    assert_eq!(start_error.code, "SIDECAR_CRASHED");
    shutdown_receiver
        .recv_timeout(deadline.saturating_duration_since(Instant::now()))
        .expect("receive bounded shutdown result")
        .expect("priority shutdown must complete");
    starter.join().expect("join start caller");
    shutdowner.join().expect("join shutdown caller");

    let events = barrier.events();
    let position = |name: &str| {
        events
            .iter()
            .position(|event| event == name)
            .unwrap_or_else(|| panic!("missing lifecycle event {name}: {events:?}"))
    };
    assert!(position("inner_shutdown_sent") < position("start_failure_gate_released"));
    assert!(position("start_supervisor_new") < position("actor_start_reply_err"));
    assert!(position("actor_start_reply_err") < position("actor_shutdown_received"));
    assert!(position("actor_shutdown_received") < position("actor_shutdown_confirmed"));

    std::fs::remove_dir_all(&root).expect("remove isolated runtime root");
}
