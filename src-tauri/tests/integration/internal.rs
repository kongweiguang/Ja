// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! `ja` crate 白盒集成测试的唯一 Cargo 入口。
//!
//! 生产模块树只在本测试 crate 中编译一次；真实文件、Git、进程与平台适配器测试通过
//! crate 内可见边界读取被测对象，避免扩大生产 façade 或把 Harness 放回生产 `src`。

#![allow(dead_code, unused_imports, unused_macros)]

#[macro_use]
#[path = "../../src/lib.rs"]
mod production;

pub(crate) use production::*;

/// 外置白盒 crate 复刻生产组合根的固定窗口 identity，使重新编译的托盘模块仍解析同一闭集常量。
const MAIN_WINDOW_LABEL: &str = "main";

#[path = "../support/app_runtime.rs"]
pub(crate) mod runtime_test_support;

#[path = "internal/app_runtime/foundation_host_tests.rs"]
mod foundation_host_tests;

/// 集中提供外置测试反复使用的标准库与协议类型；该 prelude 只属于 Cargo test
/// target，生产 crate 不会因此增加依赖、导入或公开面。
mod test_prelude {
    pub(crate) use crate::app_runtime::{
        ApprovalResponseInput, EventSink, ManualRecoveryConfirmation, ManualRecoveryReason,
        RuntimeCommandError, RuntimeHost, RuntimeStatusKind, WorkspaceLookup, WorkspaceOpenInput,
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
    pub(crate) use std::fs;
    pub(crate) use std::path::{Path, PathBuf};
    pub(crate) use std::process::Command;
    pub(crate) use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU64, AtomicUsize, Ordering};
    pub(crate) use std::sync::mpsc::{self, Receiver, SyncSender, TryRecvError};
    pub(crate) use std::sync::{Arc, Condvar, Mutex};
    pub(crate) use std::thread;
    pub(crate) use std::time::{Duration, Instant};
    pub(crate) use tokio_util::sync::CancellationToken;
    pub(crate) use uuid::Uuid;
}

/// 为真实适配器建立外置集成测试子作用域；这里只重导出 crate 内边界，
/// 不重复编译源码，也不把私有实现提升为跨 crate 公共 API。
macro_rules! integration_scope {
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

integration_scope!(
    attachments_ingress_scope,
    crate::attachments,
    "/tests/integration/internal/attachments/ingress_tests.rs"
);

mod attachments_interface_scope {
    pub(crate) use crate::app_runtime::{
        AttachmentImportInput, AttachmentMetadata, RuntimeCommandError,
    };
    pub(crate) use crate::attachments::interface::commands::*;
    pub(crate) use crate::attachments::{AttachmentIngress, IngressLimits};
    pub(crate) use crate::test_prelude::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/attachments/interface_tests.rs"
        ));
    }
}

mod composition_scope {
    pub(crate) use crate::production::*;
    pub(crate) use crate::test_prelude::*;
    #[cfg(desktop)]
    pub(crate) use tauri_plugin_window_state::StateFlags as WindowStateFlags;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/composition_tests.rs"
        ));
    }
}

integration_scope!(
    app_runtime_application_host_scope,
    crate::app_runtime::application::host,
    "/tests/integration/internal/app_runtime/application/host_tests.rs"
);
integration_scope!(
    app_runtime_application_host_lock_order_scope,
    crate::app_runtime::application::host,
    "/tests/integration/internal/app_runtime/application/host_lock_order_tests.rs"
);

mod app_runtime_support_scope {
    pub(crate) use crate::runtime_test_support::*;
    pub(crate) use crate::test_prelude::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/app_runtime/application/test_support_tests.rs"
        ));
    }
}

mod app_runtime_bridge_scope {
    pub(crate) use crate::app_runtime::infrastructure::bridge::actor::*;
    pub(crate) use crate::app_runtime::infrastructure::bridge::event_projection::*;
    pub(crate) use crate::app_runtime::infrastructure::bridge::exit_cleanup::*;
    pub(crate) use crate::app_runtime::infrastructure::bridge::lifecycle::*;
    pub(crate) use crate::app_runtime::infrastructure::bridge::operations::*;
    pub(crate) use crate::app_runtime::infrastructure::bridge::runtime_control::*;
    pub(crate) use crate::app_runtime::infrastructure::bridge::*;
    pub(crate) use crate::test_prelude::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/app_runtime/infrastructure/bridge_tests.rs"
        ));
    }
}
integration_scope!(
    app_runtime_home_layout_scope,
    crate::app_runtime::infrastructure::home_layout,
    "/tests/integration/internal/app_runtime/infrastructure/home_layout_tests.rs"
);
integration_scope!(
    app_runtime_launch_config_scope,
    crate::app_runtime::infrastructure::launch_config,
    "/tests/integration/internal/app_runtime/infrastructure/launch_config_tests.rs"
);
integration_scope!(
    app_runtime_recovery_store_scope,
    crate::app_runtime::infrastructure::recovery_store,
    "/tests/integration/internal/app_runtime/infrastructure/recovery_store_tests.rs"
);
integration_scope!(
    app_runtime_workspace_capability_scope,
    crate::app_runtime::infrastructure::workspace_capability,
    "/tests/integration/internal/app_runtime/infrastructure/workspace_capability_tests.rs"
);

mod review_git_query_scope {
    pub(crate) use crate::review::infrastructure::git::adapter::*;
    pub(crate) use crate::review::infrastructure::git::error::*;
    pub(crate) use crate::review::infrastructure::git::*;
    pub(crate) use crate::test_prelude::*;

    mod review_git_support {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/support/review_git.rs"
        ));
    }

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/review/infrastructure/git/query_tests.rs"
        ));
    }
}

mod review_git_adapter_scope {
    pub(crate) use crate::review::infrastructure::git::adapter::*;
    pub(crate) use crate::test_prelude::*;

    mod review_git_support {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/support/review_git.rs"
        ));
    }

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/review/infrastructure/git/adapter_tests.rs"
        ));
    }
}
mod review_git_process_scope {
    pub(crate) use crate::review::infrastructure::git::adapter::GitPolicy;
    pub(crate) use crate::review::infrastructure::git::error::GitError;
    pub(crate) use crate::review::infrastructure::git::process::*;
    pub(crate) use crate::test_prelude::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/review/infrastructure/git/process_tests.rs"
        ));
    }
}

mod review_repository_policy_scope {
    pub(crate) use crate::review::infrastructure::git::adapter::*;
    pub(crate) use crate::review::infrastructure::git::error::*;
    pub(crate) use crate::review::infrastructure::git::repository_policy::*;
    pub(crate) use crate::test_prelude::*;

    mod review_git_support {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/support/review_git.rs"
        ));
    }

    mod limits_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/review/infrastructure/git/repository_policy_limits_tests.rs"
        ));
    }

    mod repository_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/review/infrastructure/git/repository_policy_tests.rs"
        ));
    }
}

mod review_native_scope {
    pub(crate) use crate::review::infrastructure::parse;
    pub(crate) use crate::review::infrastructure::*;
    pub(crate) use crate::test_prelude::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/review/infrastructure/native_tests.rs"
        ));
    }
}

mod terminal_commands_scope {
    pub(crate) use crate::terminal::commands::*;
    pub(crate) use crate::terminal::native_drop::quote_native_paths;
    pub(crate) use crate::terminal::policy::available_shell_profiles;
    pub(crate) use crate::test_prelude::*;
    pub(crate) use crate::workspace::consume_native_drop;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/terminal/commands_tests.rs"
        ));
    }
}
integration_scope!(
    terminal_policy_scope,
    crate::terminal::policy,
    "/tests/integration/internal/terminal/policy_tests.rs"
);
integration_scope!(
    terminal_session_scope,
    crate::terminal::session,
    "/tests/integration/internal/terminal/session_tests.rs"
);

mod workspace_root_scope {
    pub(crate) use crate::test_prelude::*;
    pub(crate) use crate::workspace::*;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/workspace/tests.rs"
        ));
    }
}

integration_scope!(
    workspace_changes_scope,
    crate::workspace::infrastructure::changes,
    "/tests/integration/internal/workspace/infrastructure/changes_tests.rs"
);
integration_scope!(
    workspace_open_with_scope,
    crate::workspace::infrastructure::open_with,
    "/tests/integration/internal/workspace/infrastructure/open_with_tests.rs"
);
integration_scope!(
    workspace_registry_scope,
    crate::workspace::infrastructure::registry,
    "/tests/integration/internal/workspace/infrastructure/registry_tests.rs"
);
integration_scope!(
    workspace_watch_scope,
    crate::workspace::infrastructure::watch,
    "/tests/integration/internal/workspace/infrastructure/watch_tests.rs"
);

mod workspace_mutation_scope {
    pub(crate) use crate::test_prelude::*;
    pub(crate) use crate::workspace::infrastructure::mutation::drop::*;
    pub(crate) use crate::workspace::infrastructure::mutation::move_entry::*;
    pub(crate) use crate::workspace::infrastructure::mutation::save::*;
    pub(crate) use crate::workspace::infrastructure::mutation::trash::*;
    pub(crate) use crate::workspace::infrastructure::mutation::*;

    mod contract_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/workspace/infrastructure/mutation_tests.rs"
        ));
    }

    mod transaction_tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/internal/workspace/infrastructure/mutation/prepared_transaction_tests.rs"
        ));
    }
}

integration_scope!(
    workspace_interface_query_scope,
    crate::workspace::interface::query,
    "/tests/integration/internal/workspace/interface/query_tests.rs"
);
