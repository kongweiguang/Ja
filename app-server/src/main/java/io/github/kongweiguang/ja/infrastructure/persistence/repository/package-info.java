// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 实现领域与应用端口的 MyBatis 持久化适配器，负责把用例请求映射为事务内数据操作。
 *
 * <p>本包禁止创建数据源、定义 SQL 映射或复制事务基础设施，所有数据库操作必须经统一 Unit of Work。</p>
 */
package io.github.kongweiguang.ja.infrastructure.persistence.repository;
