// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::*;
use crate::app_server_process::Limits;
use crate::app_server_process::protocol::decode_frame;

/// 构造严格 response，避免 pending 测试绕过生产 codec 私有字段。
fn response(id: &str) -> RpcFrame {
    let mut frame = format!(r#"{{"jsonrpc":"2.0","id":"{id}","result":{{}}}}"#).into_bytes();
    frame.push(b'\n');
    decode_frame(&frame, Limits::default().max_frame_bytes).expect("valid response")
}

/// pending 与 tombstone 上限都必须拒绝无界配置，避免 registry 成为绕过协议 limits 的第二入口。
#[test]
fn registry_rejects_unbounded_configuration() {
    assert!(PendingRegistry::new(usize::MAX, 1).is_err());
    assert!(PendingRegistry::new(1, usize::MAX).is_err());
}

/// 多个并发 pending 可乱序完成但只能投递到各自 receiver；容量是硬上限。
#[test]
fn concurrent_pending_resolves_by_explicit_request_identity() {
    let mut pending = PendingRegistry::new(2, 4).unwrap();
    let deadline = Instant::now() + Duration::from_secs(1);
    let first = pending.register("c:first", deadline).unwrap();
    let second = pending.register("c:second", deadline).unwrap();
    assert!(matches!(
        pending.register("c:third", deadline),
        Err(PendingRegisterError::LimitReached)
    ));
    assert_eq!(
        pending.resolve(response("c:second")),
        ResolveDisposition::Delivered
    );
    assert_eq!(
        pending.resolve(response("c:first")),
        ResolveDisposition::Delivered
    );
    assert_eq!(second.recv().unwrap().unwrap().id(), "c:second");
    assert_eq!(first.recv().unwrap().unwrap().id(), "c:first");
    assert!(pending.active.is_empty());
}

/// timeout、cancel、close 都只提交一次稳定终态，并把迟到 response 分类为 late。
#[test]
fn timeout_cancel_and_close_are_bounded_terminal_paths() {
    let mut pending = PendingRegistry::new(3, 4).unwrap();
    let expired = pending.register("c:expired", Instant::now()).unwrap();
    let cancelled = pending
        .register("c:cancelled", Instant::now() + Duration::from_secs(1))
        .unwrap();
    let closed = pending
        .register("c:closed", Instant::now() + Duration::from_secs(1))
        .unwrap();
    assert_eq!(pending.expire(Instant::now()), 1);
    assert!(pending.cancel("c:cancelled"));
    assert_eq!(pending.close(), 1);
    assert_eq!(
        expired.recv().unwrap(),
        Err(AppServerProcessError::DeadlineExceeded)
    );
    assert_eq!(
        cancelled.recv().unwrap(),
        Err(AppServerProcessError::Cancelled)
    );
    assert_eq!(
        closed.recv().unwrap(),
        Err(AppServerProcessError::SessionClosed)
    );
    for id in ["c:expired", "c:cancelled", "c:closed"] {
        assert_eq!(
            pending.resolve(response(id)),
            ResolveDisposition::LateResponse
        );
    }
}

/// 64 个 active request 到期后只保留有限 tombstone，迟到与重复响应仍必须稳定分类。
#[test]
fn pending64_deadline_late_duplicate_and_bounded_tombstones() {
    let mut pending = PendingRegistry::new(64, 2).unwrap();
    let now = Instant::now();
    for index in 0..64 {
        pending
            .register(format!("c:p-{index}"), now + Duration::from_secs(1))
            .unwrap();
    }
    assert!(
        pending
            .register("c:overflow", now + Duration::from_secs(1))
            .is_err()
    );
    assert_eq!(pending.expire(now + Duration::from_secs(2)), 64);
    // expire 的返回值证明 active 已被全部收口；再通过最早/最近 ID 的分类证明
    // tombstone 上限，而不是为测试向生产类型添加容器长度 getter。
    let mut late = 0;
    let mut unknown = 0;
    for index in 0..64 {
        match pending.resolve(response(&format!("c:p-{index}"))) {
            ResolveDisposition::LateResponse => late += 1,
            ResolveDisposition::UnknownRequest => unknown += 1,
            disposition => panic!("expired request has invalid disposition: {disposition:?}"),
        }
    }
    // HashMap 的遍历顺序不是合同；只验证恰好保留两个 tombstone，避免把内部顺序
    // 固化进测试或为测试增加生产 getter。
    assert_eq!(late, 2);
    assert_eq!(unknown, 62);
    pending
        .register("c:deadline", now + Duration::from_secs(1))
        .unwrap();
    pending.expire(now + Duration::from_secs(2));
    assert_eq!(
        pending.resolve(response("c:deadline")),
        ResolveDisposition::LateResponse
    );
    pending
        .register("c:new", now + Duration::from_secs(1))
        .unwrap();
    assert_eq!(
        pending.resolve(response("c:new")),
        ResolveDisposition::Delivered
    );
    assert_eq!(
        pending.resolve(response("c:new")),
        ResolveDisposition::DuplicateResponse
    );
}
