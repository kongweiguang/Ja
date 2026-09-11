// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! `ja` crate 白盒单元测试的唯一 Cargo 入口。
//!
//! 生产模块树只在本测试 crate 中编译一次；纯规则、状态机与 fake port 测试通过
//! crate 内可见边界读取被测对象，避免扩大生产 façade 或把测试装配放回生产 `src`。

#![allow(dead_code, unused_imports, unused_macros)]

#[macro_use]
#[path = "../../src/lib.rs"]
mod production;

pub(crate) use production::*;

/// 外置白盒 crate 复刻生产组合根的固定窗口 identity，使重新编译的托盘模块仍解析同一闭集常量。
const MAIN_WINDOW_LABEL: &str = "main";

/// 集中提供外置测试反复使用的标准库与协议类型；该 prelude 只属于 Cargo test
/// target，生产 crate 不会因此增加依赖、导入或公开面。
mod test_prelude {
    pub(crate) use crate::app_runtime::{
        ApprovalResponseInput, EventSink, ManualRecoveryConfirmation, ManualRecoveryReason,
        RuntimeCommandError, RuntimeHost, RuntimeStatusKind, TaskCloseResult, TaskCreateInput,
        TaskFollowupInput, TaskMessageInput, TaskMutationInput, TaskTreeDeleteInput,
        TurnContentPart, WorkspaceLookup, WorkspaceOpenInput,
    };
    pub(crate) use crate::preview::{
        PreviewError, PreviewErrorCode, PreviewId, PreviewManager, PreviewPolicy,
    };
    pub(crate) use crate::review::{
        ReviewInvalidatedEventDto, ReviewInvalidatedReason, ReviewSource,
    };
    pub(crate) use crate::terminal::policy::env::build_environment;
    pub(crate) use crate::terminal::{
        CloseReason, LaunchRequest, ShellProfile, TerminalError, TerminalErrorCode,
        TerminalEventKind, TerminalId, TerminalPolicy, TerminalSize,
    };
    pub(crate) use crate::workspace::domain::OpenError;
    pub(crate) use crate::workspace::{
        EntryKind, FileMetadata, FileRevision, OpenTargetUnavailableReason, OpenWithTarget,
        PollingChangeDetector, PollingPolicy, TreeEntry, TreePage, WorkspaceError, WorkspaceHandle,
    };
    pub(crate) use base64::Engine;
    pub(crate) use ja_runtime::app_server_process::{RpcFrame, SidecarConfig};
    pub(crate) use notify::Event;
    pub(crate) use serde_json::{Value, json};
    pub(crate) use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
    pub(crate) use std::ffi::{OsStr, OsString};
    pub(crate) use std::path::{Path, PathBuf};
    pub(crate) use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU64, AtomicUsize, Ordering};
    pub(crate) use std::sync::mpsc::{self, Receiver, SyncSender, TryRecvError};
    pub(crate) use std::sync::{Arc, Condvar, Mutex};
    pub(crate) use std::thread;
    pub(crate) use std::time::{Duration, Instant};
    pub(crate) use uuid::Uuid;
}

/// 为一个真实生产模块建立外置测试子作用域；这里只重导出 crate 内边界，
/// 不重复编译源码，也不把私有实现提升为跨 crate 公共 API。
macro_rules! unit_scope {
    ($scope:ident, $subject:path, $test:literal) => {
        mod $scope {
            pub(crate) use crate::test_prelude::*;
            pub(crate) use subject::*;
            pub(crate) use $subject as subject;

            mod tests {
                include!(concat!(env!("CARGO_MANIFEST_DIR"), $test));
            }
        }
    };
}

unit_scope!(
    diagnostics_scope,
    crate::diagnostics,
    "/tests/unit/diagnostics_tests.rs"
);
unit_scope!(
    native_shortcuts_scope,
    crate::native_shortcuts,
    "/tests/unit/native_shortcuts_tests.rs"
);
unit_scope!(
    attachment_preview_scope,
    crate::attachment_preview,
    "/tests/unit/attachment_preview/host_tests.rs"
);
unit_scope!(
    attachment_preview_commands_scope,
    crate::attachment_preview::commands,
    "/tests/unit/attachment_preview/commands_tests.rs"
);
unit_scope!(
    app_tray_scope,
    crate::app_runtime::interface::app_tray,
    "/tests/unit/app_tray_tests.rs"
);
unit_scope!(
    app_runtime_domain_commands_scope,
    crate::app_runtime::domain::commands,
    "/tests/unit/app_runtime/domain/command_tests.rs"
);
unit_scope!(
    app_runtime_domain_tasks_scope,
    crate::app_runtime::domain::tasks,
    "/tests/unit/app_runtime/domain/task_tests.rs"
);
unit_scope!(
    app_runtime_domain_runtime_scope,
    crate::app_runtime::domain,
    "/tests/unit/app_runtime/domain/model_tests.rs"
);

unit_scope!(
    app_runtime_configuration_scope,
    crate::app_runtime::interface::configuration,
    "/tests/unit/app_runtime/interface/configuration_tests.rs"
);
unit_scope!(
    app_runtime_dto_scope,
    crate::app_runtime::interface::dto,
    "/tests/unit/app_runtime/interface/dto_tests.rs"
);
unit_scope!(
    app_runtime_event_projection_scope,
    crate::app_runtime::interface::event_projection,
    "/tests/unit/app_runtime/interface/event_projection_tests.rs"
);
unit_scope!(
    app_runtime_bridge_tasks_scope,
    crate::app_runtime::infrastructure::bridge::tasks,
    "/tests/unit/app_runtime/infrastructure/task_bridge_tests.rs"
);
unit_scope!(
    app_runtime_history_model_scope,
    crate::app_runtime::interface::history_model,
    "/tests/unit/app_runtime/interface/history_tests.rs"
);
unit_scope!(
    app_runtime_goal_interface_scope,
    crate::app_runtime::interface::goal,
    "/tests/unit/app_runtime/interface/goal_tests.rs"
);
unit_scope!(
    app_runtime_settings_model_scope,
    crate::app_runtime::interface::settings_model,
    "/tests/unit/app_runtime/interface/settings_tests.rs"
);

unit_scope!(
    preview_commands_scope,
    crate::preview::commands,
    "/tests/unit/preview/commands_tests.rs"
);
unit_scope!(
    preview_load_watchdog_scope,
    crate::preview::load_watchdog,
    "/tests/unit/preview/load_watchdog_tests.rs"
);
unit_scope!(
    preview_model_scope,
    crate::preview::model,
    "/tests/unit/preview/model_tests.rs"
);

unit_scope!(
    review_domain_scope,
    crate::review::domain::model,
    "/tests/unit/review/domain/model_tests.rs"
);
mod review_application_scope {
    pub(crate) use crate::review::application::service::*;
    pub(crate) use crate::review::application::{ReviewError, ReviewNativePort};
    pub(crate) use crate::test_prelude::*;
    pub(crate) use tokio_util::sync::CancellationToken;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/review/application/service_tests.rs"
        ));
    }
}

mod review_interface_scope {
    pub(crate) use crate::review::interface::commands::*;
    pub(crate) use crate::review::interface::dto::*;
    pub(crate) use crate::review::interface::projection::*;
    pub(crate) use crate::test_prelude::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/review/interface/commands_tests.rs"
        ));
    }
}

unit_scope!(
    terminal_native_drop_scope,
    crate::terminal::native_drop,
    "/tests/unit/terminal/native_drop_tests.rs"
);
mod terminal_policy_paths_scope {
    pub(crate) use crate::terminal::policy::paths::*;
    pub(crate) use crate::test_prelude::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/terminal/policy_paths_tests.rs"
        ));
    }
}

unit_scope!(
    terminal_process_scope,
    crate::terminal::process,
    "/tests/unit/terminal/process_tests.rs"
);
unit_scope!(
    terminal_queue_scope,
    crate::terminal::queue,
    "/tests/unit/terminal/queue_tests.rs"
);
mod terminal_session_workers_scope {
    pub(crate) use crate::terminal::session::session_workers::*;
    pub(crate) use crate::terminal::session::*;
    pub(crate) use crate::test_prelude::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/terminal/session_workers_tests.rs"
        ));
    }
}

unit_scope!(
    workspace_domain_scope,
    crate::workspace::domain::value_objects,
    "/tests/unit/workspace/domain/value_objects_tests.rs"
);
unit_scope!(
    workspace_query_application_scope,
    crate::workspace::application::query,
    "/tests/unit/workspace/application/query_tests.rs"
);

mod workspace_application_scope {
    pub(crate) use crate::test_prelude::*;
    pub(crate) use crate::workspace::application::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/workspace/application/services_tests.rs"
        ));
    }
}
unit_scope!(
    workspace_search_scope,
    crate::workspace::infrastructure::search,
    "/tests/unit/workspace/infrastructure/search_tests.rs"
);

mod workspace_interface_contract_scope {
    pub(crate) use crate::test_prelude::*;
    pub(crate) use crate::workspace::interface::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/unit/workspace/interface/contracts_tests.rs"
        ));
    }
}
