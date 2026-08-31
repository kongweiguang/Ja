// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! JA-RPC v2 的 framing、严格 JSON 与握手协议。
//!
//! 协议语义集中在这里，是为了让进程和 client 生命周期只能消费已校验 frame；
//! 子模块保持 crate 私有，外部调用方只能经过 `app_server_process` façade。

pub(crate) mod catalog;
pub(crate) mod codec;
mod error_policy;
mod frame;
mod framing;
pub(crate) mod handshake;
mod json;
mod limits;

pub(crate) use catalog::V2_CLIENT_METHODS;
pub(crate) use codec::decode_frame;
pub(crate) use codec::is_ready_token_key;
pub use codec::valid_ready_token;
pub use error_policy::CodecError;
pub(crate) use frame::FrameKind;
pub use frame::RpcFrame;
pub(crate) use framing::read_frame_with_forbidden;
pub(crate) use handshake::*;
pub use limits::Limits;
