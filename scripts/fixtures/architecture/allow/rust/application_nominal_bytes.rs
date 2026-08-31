// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

struct ThreadListPayload(Vec<u8>);
struct ThreadReadPayload(Vec<u8>);

trait HistoryPort {
    /// 每个 operation 使用独立 nominal bytes，application 不解释 Java-owned schema。
    fn thread_list(&self, payload: ThreadListPayload);

    /// 独立类型阻止调用方把 read 载荷误送到 list operation。
    fn thread_read(&self, payload: ThreadReadPayload);
}
