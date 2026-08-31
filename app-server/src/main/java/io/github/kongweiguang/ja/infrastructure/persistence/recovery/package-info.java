// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 在 RPC admission 前编排崩溃遗留状态的事务化启动恢复。
 *
 * <p>本包禁止定义恢复 SQL、普通运行期仓储或数据库资源 owner；恢复规则只能组合 Mapper 与 Unit of Work。</p>
 */
package io.github.kongweiguang.ja.infrastructure.persistence.recovery;
