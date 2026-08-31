// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

struct SharedPayload(Vec<u8>);

trait HistoryPort {
    /// 同一 bytes 类型不能同时承载两个 operation。
    fn thread_list(&self, payload: SharedPayload);

    /// 第二个不同方法复用 SharedPayload，必须被 Gate 拒绝。
    fn thread_read(&self, payload: SharedPayload);
}
