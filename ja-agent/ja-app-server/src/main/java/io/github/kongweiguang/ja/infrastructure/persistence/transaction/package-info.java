// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 提供 MyBatis SqlSession 与 Solon transaction manager 之间的统一 Unit of Work 边界。
 *
 * <p>本包禁止包含业务决策、SQL 语句或数据库生命周期管理，确保提交与回滚语义只有一个 owner。</p>
 */
package io.github.kongweiguang.ja.infrastructure.persistence.transaction;
