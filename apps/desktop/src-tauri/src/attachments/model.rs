// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use serde::Serialize;

pub(crate) const MAX_ATTACHMENT_FILES: usize = 10;
pub(crate) const MAX_ATTACHMENT_BYTES: u64 = 100 * 1024 * 1024;
pub(crate) const MAX_ATTACHMENT_BATCH_BYTES: u64 = 250 * 1024 * 1024;

/// Rust 与 App Server 之间使用的 opaque ingress identity；UI 最终 DTO 不包含此类型。
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) struct IngressToken(String);

impl IngressToken {
    /// token 只由 Rust 生成；不提供从任意字符串构造的公共入口，避免路径或用户输入混入 identity。
    pub(super) fn generate() -> Self {
        Self(uuid::Uuid::new_v4().simple().to_string())
    }

    /// Runtime 桥接只在同一次原生调用内部读取 token，不能把它直接投影到 WebView。
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

/// 已完成安全复制的领域值；源绝对路径和 staging 绝对路径均不属于可序列化状态。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct IngressAttachment {
    #[serde(skip)]
    pub(crate) ingress_token: IngressToken,
    pub(crate) display_name: String,
    pub(crate) size_bytes: u64,
    pub(crate) sha256: String,
}

/// 默认生产预算固定为产品合同，测试可用更小预算覆盖边界而不创建百 MiB fixture。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct IngressLimits {
    pub(crate) max_files: usize,
    pub(crate) max_file_bytes: u64,
    pub(crate) max_batch_bytes: u64,
}

impl Default for IngressLimits {
    /// 产品默认值集中在领域类型中，避免 dialog、command 与 service 各自漂移。
    fn default() -> Self {
        Self {
            max_files: MAX_ATTACHMENT_FILES,
            max_file_bytes: MAX_ATTACHMENT_BYTES,
            max_batch_bytes: MAX_ATTACHMENT_BATCH_BYTES,
        }
    }
}
