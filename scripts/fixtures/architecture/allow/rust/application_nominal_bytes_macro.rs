// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

macro_rules! define_payload {
    ($($name:ident),+ $(,)?) => {
        $(
            struct $name(Vec<u8>);
            impl $name {
                /// operation 边界只消费完整所有权，不共享可变字节。
                fn into_bytes(self) -> Vec<u8> { self.0 }
            }
        )+
    };
}

define_payload!(ThreadListPayload, ThreadReadPayload);

enum HistoryRequest {
    List(ThreadListPayload),
    Read(ThreadReadPayload),
}
