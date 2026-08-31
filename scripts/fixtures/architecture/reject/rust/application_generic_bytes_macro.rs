// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

macro_rules! define_tunnel {
    ($name:ident) => {
        struct $name(Vec<u8>);
        impl $name {
            /// 生成 selector+bytes 请求的宏仍是通用隧道，不能因宏展开逃逸。
            fn request(method: String, bytes: Vec<u8>) -> Self {
                let _ = method;
                Self(bytes)
            }
        }
    };
}

define_tunnel!(OpaquePayload);
