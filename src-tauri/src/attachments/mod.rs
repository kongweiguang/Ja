// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 附件原生 ingress 的安全边界。
//!
//! WebView 不能接触选择得到的绝对路径；本模块把源文件复制到 Rust 拥有的 run 目录，
//! 并只向后续 Runtime 适配器提供 opaque token 与经过校验的元数据。

mod error;
pub(crate) mod interface;
mod model;
mod operation;
mod platform;
mod service;

#[allow(unused_imports)]
pub(crate) use error::{AttachmentIngressError, AttachmentIngressErrorCode};
#[allow(unused_imports)]
pub(crate) use model::{IngressAttachment, IngressLimits, IngressToken};
#[allow(unused_imports)]
pub(crate) use operation::{ItemCancellation, RETRY_ATTEMPT_TTL, RetryAttempt};
#[allow(unused_imports)]
pub(crate) use service::{AdmittedAttachmentSource, AttachmentIngress};
