// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 白盒目标会再次编译完整生产模块；未被当前用例触达的公开 façade 仍由黑盒集成测试覆盖，
// 因此这里只压制该测试目标自身的重复编译告警，不改变生产 crate 的 lint 级别。
#![allow(dead_code, unused_imports)]

//! `ja-runtime` 白盒集成测试的唯一 Cargo 入口。
//!
//! 测试目标从 `tests/integration/internal` 编译生产模块，使测试实现、装配和支撑代码都不进入 `src`；
//! 生产模块只维持 crate 内可见性，不因此扩大 `app_server_process` 公共 façade。

#[path = "../../src/lib.rs"]
mod production;

pub(crate) use production::app_server_process;

mod unit_support_scope {
    pub(crate) use crate::app_server_process::*;

    pub(crate) mod support {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/support/app_server_process.rs"
        ));
    }
}

pub(crate) use unit_support_scope::support as unit_support_tests;
mod lifecycle_supervisor_scope {
    pub(crate) use crate::app_server_process::lifecycle::supervisor::*;
    pub(crate) use crate::app_server_process::{
        AppServerProcessError, EventPump, LifecycleState, Limits, RpcFrame, Session, SessionEvent,
        SidecarConfig,
    };
    pub(crate) use serde_json::Value;
    pub(crate) use std::collections::VecDeque;
    pub(crate) use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};
    pub(crate) use std::sync::{Arc, Mutex};
    pub(crate) use std::time::{Duration, Instant};

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/app_server_process/lifecycle/supervisor_tests.rs"
        ));
    }
}

mod process_config_scope {
    pub(crate) use crate::app_server_process::AppServerProcessError;
    pub(crate) use crate::app_server_process::process::config::*;
    pub(crate) use std::collections::BTreeMap;
    pub(crate) use std::ffi::{OsStr, OsString};
    pub(crate) use std::fs;
    pub(crate) use std::path::{Path, PathBuf};
    pub(crate) use std::sync::{Arc, Mutex};
    pub(crate) use std::time::Duration;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/app_server_process/process/config_tests.rs"
        ));
    }
}

mod process_tree_scope {
    pub(crate) use crate::app_server_process::process::tree::*;
    pub(crate) use std::io;
    pub(crate) use std::process::{Child, Command, ExitStatus};
    pub(crate) use std::sync::Arc;
    #[cfg(windows)]
    pub(crate) use std::sync::Mutex;
    pub(crate) use std::sync::atomic::{AtomicBool, Ordering};
    pub(crate) use std::thread;
    pub(crate) use std::time::{Duration, Instant};

    #[cfg(windows)]
    mod job_backend_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/app_server_process/process/job_backend_tests.rs"
        ));
    }

    mod tree_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/app_server_process/process/tree_tests.rs"
        ));
    }
}
