// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 管理 SQLite 数据源、文件租约、Flyway V1 准入与完整性回读的单一生命周期。
 *
 * <p>本包禁止承载业务仓储、SQL Mapper 或启动恢复编排，避免数据库资源所有权与数据访问职责混合。</p>
 */
package io.github.kongweiguang.ja.infrastructure.persistence.database;
