// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#![allow(dead_code)]

use std::fmt::Display;

/// 提供与 controller 错误构造器同形的测试适配，避免测试入口依赖二进制内部状态。
#[derive(Debug, PartialEq, Eq)]
pub struct CliError {
    pub category: &'static str,
    pub message: String,
    pub client_error_code: Option<&'static str>,
}

impl CliError {
    /// 标记纯输入校验失败，供 Schema 与 prompt 测试区分预期拒绝路径。
    fn usage(message: impl Into<String>) -> Self {
        Self {
            category: "usage",
            message: message.into(),
            client_error_code: None,
        }
    }

    /// 将本地读写错误保持为可断言的传输类失败，不依赖具体操作系统错误文本。
    fn transport(error: impl Display) -> Self {
        Self {
            category: "transport",
            message: error.to_string(),
            client_error_code: None,
        }
    }

    /// 把终答写入失败与连接错误区分，固定客户端交付错误码。
    fn local_output(error: impl Display) -> Self {
        Self {
            category: "local_output",
            message: error.to_string(),
            client_error_code: Some("CLIENT_OUTPUT_FAILURE"),
        }
    }
}

#[path = "../../src/controller/headless.rs"]
pub mod headless;
