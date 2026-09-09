// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 白盒目标会再次编译完整生产模块；未被当前用例触达的公开 façade 仍由黑盒集成测试覆盖，
// 因此这里只压制该测试目标自身的重复编译告警，不改变生产 crate 的 lint 级别。
#![allow(dead_code, unused_imports)]

//! `ja-runtime` 白盒单元测试的唯一 Cargo 入口。
//!
//! 测试目标从 `tests/unit` 编译生产模块，使测试实现、装配和支撑代码都不进入 `src`；
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

mod client_pending_scope {
    pub(crate) use crate::app_server_process::client::pending::*;
    pub(crate) use crate::app_server_process::{AppServerProcessError, RpcFrame};
    pub(crate) use std::time::{Duration, Instant};

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/client/pending_tests.rs"
        ));
    }
}

mod client_session_scope {
    pub(crate) use crate::app_server_process::client::session::events::EventQueue;
    pub(crate) use crate::app_server_process::client::session::wire::EventPriority;
    pub(crate) use crate::app_server_process::client::session::*;
    pub(crate) use crate::app_server_process::error::QueueKind;
    pub(crate) use crate::app_server_process::protocol as codec;
    pub(crate) use crate::app_server_process::{AppServerProcessError, Limits, RpcFrame};
    pub(crate) use std::collections::{HashMap, HashSet};
    pub(crate) use std::io::{Read, Write};
    pub(crate) use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
    pub(crate) use std::sync::mpsc::{self, RecvTimeoutError};
    pub(crate) use std::sync::{Arc, Condvar, Mutex, MutexGuard};
    pub(crate) use std::thread;
    pub(crate) use std::time::{Duration, Instant};

    mod request_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/client/session/request_tests.rs"
        ));
    }

    mod ready_barrier_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/client/session/ready_barrier_tests.rs"
        ));
    }

    mod lifecycle_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/client/session/lifecycle_tests.rs"
        ));
    }

    mod stderr_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/client/session/stderr_tests.rs"
        ));
    }
}

mod client_session_events_scope {
    pub(crate) use crate::app_server_process::client::session::events::*;
    pub(crate) use crate::app_server_process::client::session::wire::{
        EventPriority, control_queue_byte_budget, is_terminal_event,
    };
    pub(crate) use crate::app_server_process::error::QueueKind;
    pub(crate) use crate::app_server_process::{RpcFrame, SessionEvent};
    pub(crate) use std::collections::VecDeque;
    pub(crate) use std::sync::atomic::{AtomicBool, Ordering};
    pub(crate) use std::sync::{Condvar, Mutex};
    pub(crate) use std::time::{Duration, Instant};

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/client/session/events_tests.rs"
        ));
    }
}

mod client_session_wire_scope {
    pub(crate) use crate::app_server_process::client::session::wire::*;
    pub(crate) use crate::app_server_process::client::session::{
        SessionEvent, SessionInner, TerminalReason,
    };
    pub(crate) use crate::app_server_process::error::{AppServerProcessError, QueueKind};
    pub(crate) use crate::app_server_process::{CodecError, RpcFrame};
    pub(crate) use std::io::{BufReader, Read, Write};
    pub(crate) use std::sync::atomic::{AtomicUsize, Ordering};
    pub(crate) use std::sync::mpsc::{self, Receiver, SyncSender};
    pub(crate) use std::sync::{Arc, Condvar, Mutex};
    pub(crate) use std::thread;
    pub(crate) use std::time::Duration;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/client/session/wire_tests.rs"
        ));
    }
}

mod lifecycle_machine_scope {
    pub(crate) use crate::app_server_process::lifecycle::machine::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/lifecycle/machine_tests.rs"
        ));
    }
}
mod protocol_catalog_scope {
    pub(crate) use crate::app_server_process::protocol::catalog::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/protocol/catalog_tests.rs"
        ));
    }
}

mod attachment_preview_scope {
    pub(crate) use crate::app_server_process::*;
    pub(crate) use serde_json::{Value, json};

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/protocol/attachment_preview_tests.rs"
        ));
    }
}

mod turn_change_set_scope {
    pub(crate) use crate::app_server_process::protocol::validate_turn_change_set_request;
    pub(crate) use crate::app_server_process::*;
    pub(crate) use serde_json::{Value, json};

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/protocol/turn_change_set_tests.rs"
        ));
    }
}

mod protocol_codec_scope {
    pub(crate) use crate::app_server_process::protocol::codec::*;
    pub(crate) use crate::app_server_process::{CodecError, RpcFrame};
    pub(crate) use serde_json::{Map, Value};
    pub(crate) use std::collections::HashSet;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/protocol/codec_tests.rs"
        ));
    }
}

mod protocol_handshake_scope {
    pub(crate) use crate::app_server_process::protocol::handshake::*;
    pub(crate) use crate::app_server_process::{AppServerProcessError, Limits, RpcFrame};
    pub(crate) use serde_json::Value;
    pub(crate) use std::collections::HashSet;
    pub(crate) use std::io::Read;
    pub(crate) use std::time::{Duration, Instant};

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/app_server_process/protocol/handshake_tests.rs"
        ));
    }
}
