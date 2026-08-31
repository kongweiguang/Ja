// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/// application 自有的封闭用例契约不是动态 JSON 树，应保持可用。
enum RuntimeOperation {
    Start,
    Stop,
}

/// 封闭 typed contract 只表达 use-case intent，不携带 interface DTO。
fn accept_operation(operation: RuntimeOperation) -> RuntimeOperation {
    operation
}
