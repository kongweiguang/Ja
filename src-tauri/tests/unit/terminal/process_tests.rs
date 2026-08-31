// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 测试与生产实现分文件，既保持对模块私有不变量的覆盖，也避免生产文件承载测试体。


use super::*;
use std::sync::{Arc, Barrier};
use std::thread;

/// 并发 close 只能有一个 caller 获得 Job handle，避免 CAS 前重复触碰句柄。
#[test]
fn concurrent_job_claim_has_one_owner() {
    let slot = Arc::new(AtomicPtr::new(std::ptr::dangling_mut::<std::ffi::c_void>()));
    let barrier = Arc::new(Barrier::new(8));
    let mut callers = Vec::new();
    for _ in 0..8 {
        let slot = Arc::clone(&slot);
        let barrier = Arc::clone(&barrier);
        callers.push(thread::spawn(move || {
            barrier.wait();
            claim_job_slot(&slot).is_some()
        }));
    }
    let owners = callers
        .into_iter()
        .map(|caller| caller.join().unwrap())
        .filter(|claimed| *claimed)
        .count();
    assert_eq!(owners, 1);
    assert!(slot.load(Ordering::Acquire).is_null());
}
