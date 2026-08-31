// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 生命周期算法单元测试；generation、退避和终态不通过公共 façade 暴露。

use super::{Clock, LifecycleMachine, LifecycleState, RestartPolicy};
use crate::app_server_process::AppServerProcessError;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

#[derive(Default)]
struct TestClock(AtomicU64);

impl Clock for TestClock {
    /// 测试时钟只按用例显式推进，避免 sleep 和墙上时钟让退避断言产生竞态。
    fn now_millis(&self) -> u64 {
        self.0.load(Ordering::Acquire)
    }
}

/// generation 的正常启动、ready 与退出必须保持单向转换；该算法是 supervisor 私有实现。
#[test]
fn generation_state_transitions_are_linear() {
    let mut lifecycle = LifecycleMachine::new(RestartPolicy::default()).unwrap();
    let generation = lifecycle.begin_start().unwrap();
    lifecycle.mark_ready(generation).unwrap();
    assert_eq!(lifecycle.state(), LifecycleState::Ready);
    assert!(lifecycle.mark_exited(generation));
    assert_eq!(lifecycle.state(), LifecycleState::Exited);
}

/// 注入单调时钟精确覆盖指数退避、重试上限和旧 generation 隔离，避免用 integration sleep
/// 把私有算法误写成公共进程合同。
#[test]
fn generation_and_crash_backoff_are_deterministic() {
    let clock = Arc::new(TestClock::default());
    let mut lifecycle = LifecycleMachine::with_clock(
        RestartPolicy {
            max_attempts: 2,
            base_delay: Duration::from_millis(10),
            max_delay: Duration::from_millis(100),
        },
        clock.clone(),
    )
    .unwrap();
    let generation = lifecycle.begin_start().unwrap();
    lifecycle.mark_ready(generation).unwrap();
    assert_eq!(lifecycle.record_crash(generation), LifecycleState::Backoff);
    assert!(!lifecycle.backoff_due());
    clock.0.store(10, Ordering::Release);
    assert!(lifecycle.backoff_due());
    let next = lifecycle.begin_start().unwrap();
    assert_ne!(generation, next);
    lifecycle.mark_ready(next).unwrap();
    assert_eq!(lifecycle.record_crash(next), LifecycleState::Backoff);
    clock.0.store(30, Ordering::Release);
    assert!(lifecycle.begin_start().is_ok());
    let current = lifecycle.generation();
    lifecycle.mark_ready(current).unwrap();
    assert_eq!(lifecycle.record_crash(current), LifecycleState::Faulted);
    assert_eq!(lifecycle.begin_start(), Err(AppServerProcessError::Faulted));
    assert!(!lifecycle.mark_exited(generation));

    let mut single_attempt = LifecycleMachine::with_clock(
        RestartPolicy {
            max_attempts: 1,
            base_delay: Duration::from_millis(10),
            max_delay: Duration::from_millis(10),
        },
        clock.clone(),
    )
    .unwrap();
    let first_generation = single_attempt.begin_start().unwrap();
    single_attempt.mark_ready(first_generation).unwrap();
    assert_eq!(
        single_attempt.record_crash(first_generation),
        LifecycleState::Backoff
    );
    clock.0.store(40, Ordering::Release);
    let second_generation = single_attempt.begin_start().unwrap();
    single_attempt.mark_ready(second_generation).unwrap();
    assert_eq!(
        single_attempt.record_crash(second_generation),
        LifecycleState::Faulted
    );
    assert_eq!(
        single_attempt.record_crash(first_generation),
        LifecycleState::Faulted
    );

    let mut exited = LifecycleMachine::with_clock(RestartPolicy::default(), clock.clone()).unwrap();
    let exited_generation = exited.begin_start().unwrap();
    assert!(exited.mark_exited(exited_generation));
    assert_eq!(
        exited.record_crash(exited_generation),
        LifecycleState::Exited
    );

    let mut incompatible =
        LifecycleMachine::with_clock(RestartPolicy::default(), clock.clone()).unwrap();
    let incompatible_generation = incompatible.begin_start().unwrap();
    assert!(incompatible.mark_incompatible(incompatible_generation));
    assert_eq!(
        incompatible.record_crash(incompatible_generation),
        LifecycleState::Incompatible
    );
}

/// 非法重启策略在状态机构造前被拒绝，防止零延迟或无界退避进入 supervisor。
#[test]
fn restart_policy_rejects_invalid_bounds() {
    assert_eq!(
        RestartPolicy {
            max_attempts: 0,
            base_delay: Duration::from_millis(10),
            max_delay: Duration::from_millis(10),
        }
        .validate(),
        Err(AppServerProcessError::InvalidConfig)
    );
    assert_eq!(
        RestartPolicy {
            max_attempts: 1,
            base_delay: Duration::from_millis(100),
            max_delay: Duration::from_millis(10),
        }
        .validate(),
        Err(AppServerProcessError::InvalidConfig)
    );
}
