// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 有界 JSONL BufRead framing；只负责逐帧消费，不解释业务方法。

use super::codec::decode_frame_with_forbidden;
use super::error_policy::CodecError;
use super::frame::RpcFrame;
use std::collections::HashSet;
use std::io::BufRead;

/// 读取一帧 JSONL，并在 typed envelope 丢弃未知字段前应用调用方当前 challenge 集合。
pub(crate) fn read_frame_with_forbidden<R: BufRead>(
    reader: &mut R,
    max_frame_bytes: usize,
    forbidden: &HashSet<String>,
) -> Result<RpcFrame, CodecError> {
    let mut line = Vec::with_capacity(max_frame_bytes.min(8192).saturating_add(1));
    loop {
        let chunk = reader.fill_buf().map_err(|_| CodecError::Io)?;
        if chunk.is_empty() {
            return if line.is_empty() {
                Err(CodecError::UnexpectedEof)
            } else {
                Err(CodecError::PartialFrame)
            };
        }
        if let Some(index) = chunk.iter().position(|byte| *byte == b'\n') {
            let payload_len = line.len().saturating_add(index);
            if payload_len > max_frame_bytes {
                return Err(CodecError::FrameTooLarge {
                    actual: payload_len,
                    max: max_frame_bytes,
                });
            }
            line.extend_from_slice(&chunk[..=index]);
            reader.consume(index + 1);
            return decode_frame_with_forbidden(&line, max_frame_bytes, forbidden);
        }
        if line.len().saturating_add(chunk.len()) > max_frame_bytes {
            return Err(CodecError::FrameTooLarge {
                actual: max_frame_bytes.saturating_add(1),
                max: max_frame_bytes,
            });
        }
        line.extend_from_slice(chunk);
        let consumed = chunk.len();
        reader.consume(consumed);
    }
}
