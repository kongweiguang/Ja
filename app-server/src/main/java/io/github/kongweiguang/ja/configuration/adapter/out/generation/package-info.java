// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 负责冻结配置 generation、维护租约引用、投影运行时状态与解析配置目录值。
 *
 * <p>本包禁止直接读写配置文件、操作 Windows ACL 或持有 WatchService，所有外部状态必须由
 * 文档与安全适配器通过窄接口提供。</p>
 */
package io.github.kongweiguang.ja.configuration.adapter.out.generation;
