// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 负责配置文档的解析、校验、CAS 原子持久化与文件变更监听。
 *
 * <p>本包禁止承载 generation 租约缓存、Windows 原生安全实现或 RPC DTO，避免文档生命周期
 * 与进程内快照及平台细节耦合。</p>
 */
package io.github.kongweiguang.ja.configuration.adapter.out.document;
