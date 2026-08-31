// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

struct OpaquePayload(Vec<u8>);

trait RuntimePort {
    /// selector 与共享 bytes 组合会重新形成任意协议隧道。
    fn request(&self, method: String, payload: OpaquePayload);
}
