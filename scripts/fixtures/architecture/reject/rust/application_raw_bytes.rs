// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

trait HistoryPort {
    /// raw bytes 缺少 operation identity，不能作为 application port。
    fn request(&self, payload: Vec<u8>);
}
