// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.skills.pg.infrastructure;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 显式初始化并严格校验 PostgreSQL Skill 扩展自有表；数据源与连接池始终由调用方持有。 */
public final class PostgresSkillSchema {
    private static final String MODULE_NAME = "ja-agent-skills-pg";
    private static final int SCHEMA_VERSION = 1;
    private static final int ADVISORY_LOCK_NAMESPACE = 1_229_867_347;
    private static final int ADVISORY_LOCK_KEY = 1_229_867_348;
    private static final String ADVISORY_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";
    private static final String CREATE_MODULE_VERSION_DDL = """
            CREATE TABLE agent_skill_module_version (
                module_name text NOT NULL,
                schema_version integer NOT NULL,
                CONSTRAINT pk_agent_skill_module_version PRIMARY KEY (module_name),
                CONSTRAINT ck_agent_skill_module_version_positive CHECK (schema_version > 0)
            )
            """;
    private static final String CREATE_PACKAGE_DDL = """
            CREATE TABLE agent_skill_package (
                tenant_id text NOT NULL,
                skill_name varchar(256) NOT NULL,
                description text NOT NULL,
                body text NOT NULL,
                package_version bigint NOT NULL,
                CONSTRAINT pk_agent_skill_package PRIMARY KEY (tenant_id, skill_name),
                CONSTRAINT ck_agent_skill_package_version_positive CHECK (package_version > 0)
            )
            """;
    private static final String CREATE_RESOURCE_DDL = """
            CREATE TABLE agent_skill_resource (
                tenant_id text NOT NULL,
                skill_name varchar(256) NOT NULL,
                resource_path text NOT NULL,
                content bytea NOT NULL,
                CONSTRAINT pk_agent_skill_resource PRIMARY KEY (tenant_id, skill_name, resource_path),
                CONSTRAINT fk_agent_skill_resource_package FOREIGN KEY (tenant_id, skill_name)
                    REFERENCES agent_skill_package(tenant_id, skill_name) ON DELETE CASCADE
            )
            """;
    private static final String CREATE_CATALOG_REVISION_DDL = """
            CREATE TABLE agent_skill_catalog_revision (
                tenant_id text NOT NULL,
                catalog_revision bigint NOT NULL,
                CONSTRAINT pk_agent_skill_catalog_revision PRIMARY KEY (tenant_id),
                CONSTRAINT ck_agent_skill_catalog_revision_nonnegative CHECK (catalog_revision >= 0)
            )
            """;
    private static final String INSERT_MODULE_VERSION_SQL =
            "INSERT INTO agent_skill_module_version (module_name, schema_version) VALUES (?, ?)";
    private static final Set<String> TABLES = Set.of(
            "agent_skill_module_version",
            "agent_skill_package",
            "agent_skill_resource",
            "agent_skill_catalog_revision");

    private static final Set<ColumnShape> COLUMNS = Set.of(
            column("agent_skill_module_version", "module_name", "text", null),
            column("agent_skill_module_version", "schema_version", "integer", null),
            column("agent_skill_package", "tenant_id", "text", null),
            column("agent_skill_package", "skill_name", "character varying", 256),
            column("agent_skill_package", "description", "text", null),
            column("agent_skill_package", "body", "text", null),
            column("agent_skill_package", "package_version", "bigint", null),
            column("agent_skill_resource", "tenant_id", "text", null),
            column("agent_skill_resource", "skill_name", "character varying", 256),
            column("agent_skill_resource", "resource_path", "text", null),
            column("agent_skill_resource", "content", "bytea", null),
            column("agent_skill_catalog_revision", "tenant_id", "text", null),
            column("agent_skill_catalog_revision", "catalog_revision", "bigint", null));

    private static final Set<ConstraintShape> CONSTRAINTS = Set.of(
            primary("agent_skill_module_version", "pk_agent_skill_module_version", "module_name"),
            check("agent_skill_module_version", "ck_agent_skill_module_version_positive", "schema_version > 0"),
            primary("agent_skill_package", "pk_agent_skill_package", "tenant_id,skill_name"),
            check("agent_skill_package", "ck_agent_skill_package_version_positive", "package_version > 0"),
            primary("agent_skill_resource", "pk_agent_skill_resource", "tenant_id,skill_name,resource_path"),
            foreignKey("agent_skill_resource", "fk_agent_skill_resource_package", "tenant_id,skill_name",
                    "agent_skill_package", "tenant_id,skill_name"),
            primary("agent_skill_catalog_revision", "pk_agent_skill_catalog_revision", "tenant_id"),
            check("agent_skill_catalog_revision", "ck_agent_skill_catalog_revision_nonnegative",
                    "catalog_revision >= 0"));

    private static final Set<IndexShape> INDEXES = Set.of(
            index("agent_skill_module_version", "pk_agent_skill_module_version", "module_name"),
            index("agent_skill_package", "pk_agent_skill_package", "tenant_id,skill_name"),
            index("agent_skill_resource", "pk_agent_skill_resource", "tenant_id,skill_name,resource_path"),
            index("agent_skill_catalog_revision", "pk_agent_skill_catalog_revision", "tenant_id"));

    /**
     * 在空 schema 中一次性创建当前完整结构；已存在结构只做严格校验，绝不补建或迁移对象。
     * 安装器使用事务级 advisory lock 串行化同一数据库中的并发首次启动，并尊重调用方事务边界。
     */
    public static void install(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            boolean transactionOwner = connection.getAutoCommit();
            Savepoint savepoint = null;
            if (transactionOwner) {
                connection.setAutoCommit(false);
            } else {
                savepoint = connection.setSavepoint();
            }
            try {
                lockInstaller(connection);
                if (!isInstalled(connection)) {
                    createSchema(connection);
                }
                validateSchema(connection);
                if (transactionOwner) {
                    connection.commit();
                } else {
                    connection.releaseSavepoint(savepoint);
                }
            } catch (SQLException | RuntimeException | Error failure) {
                if (rollback(connection, transactionOwner, savepoint, failure) && transactionOwner) {
                    restoreAutoCommit(connection, failure);
                }
                throw failure;
            }
            if (transactionOwner) {
                restoreAutoCommit(connection, null);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("postgres_skill_schema_install_failed", failure);
        }
    }

    /**
     * 只读确认扩展已显式初始化且结构完整，供来源构造阶段 fail-closed；校验路径不拥有初始化职责。
     */
    static void requireInstalled(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            if (!isInstalled(connection)) {
                throw new IllegalStateException("postgres_skill_schema_not_initialized");
            }
            validateSchema(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("postgres_skill_schema_validation_failed", failure);
        }
    }

    /**
     * 事务级锁只覆盖本模块的首次初始化判断与 DDL，避免并发启动都看到空 schema 后互相冲突。
     */
    private static void lockInstaller(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ADVISORY_LOCK_SQL)) {
            statement.setInt(1, ADVISORY_LOCK_NAMESPACE);
            statement.setInt(2, ADVISORY_LOCK_KEY);
            statement.execute();
        }
    }

    /**
     * 空结构返回 false，完整结构返回 true；部分对象、非表对象或额外模块标记都属于不兼容结构。
     */
    private static boolean isInstalled(Connection connection) throws SQLException {
        Set<String> found = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT relation.relname, relation.relkind
                FROM pg_class relation
                JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = current_schema()
                  AND left(relation.relname, 12) = 'agent_skill_'
                """)) {
            while (rows.next()) {
                String name = rows.getString("relname");
                if (!"r".equals(rows.getString("relkind"))) {
                    throw schemaMismatch();
                }
                found.add(name);
            }
        }
        if (found.isEmpty()) {
            return false;
        }
        if (!found.equals(TABLES)) {
            throw schemaMismatch();
        }
        return true;
    }

    /**
     * 创建仅由此扩展拥有的表和版本行；不使用 IF NOT EXISTS，避免把局部残缺状态伪装成初始化成功。
     */
    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_MODULE_VERSION_DDL);
            statement.execute(CREATE_PACKAGE_DDL);
            statement.execute(CREATE_RESOURCE_DDL);
            statement.execute(CREATE_CATALOG_REVISION_DDL);
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_MODULE_VERSION_SQL)) {
            statement.setString(1, MODULE_NAME);
            statement.setInt(2, SCHEMA_VERSION);
            statement.executeUpdate();
        }
    }

    /**
     * 对列、约束、索引与唯一版本行做精确结构比较；未知版本和任何部分漂移均明确拒绝。
     */
    private static void validateSchema(Connection connection) throws SQLException {
        validateColumns(connection);
        validateConstraints(connection);
        validateIndexes(connection);
        validateVersion(connection);
    }

    /**
     * 验证字段类型、长度、非空、默认值及生成属性，防止同名表的弱化列继续承载写入。
     */
    private static void validateColumns(Connection connection) throws SQLException {
        Set<ColumnShape> found = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT column_row.table_name, column_row.column_name, column_row.data_type,
                       column_row.character_maximum_length, column_row.domain_name,
                       column_row.domain_schema,
                       column_attribute.attcollation = column_type.typcollation AS default_collation,
                       column_row.is_nullable, column_row.column_default,
                       column_row.is_identity, column_row.is_generated
                FROM information_schema.columns column_row
                JOIN pg_namespace column_namespace ON column_namespace.nspname = column_row.table_schema
                JOIN pg_class column_table
                  ON column_table.relnamespace = column_namespace.oid
                 AND column_table.relname = column_row.table_name
                JOIN pg_attribute column_attribute
                  ON column_attribute.attrelid = column_table.oid
                 AND column_attribute.attname = column_row.column_name
                 AND column_attribute.attnum > 0 AND NOT column_attribute.attisdropped
                JOIN pg_type column_type ON column_type.oid = column_attribute.atttypid
                WHERE column_row.table_schema = current_schema()
                  AND column_row.table_name IN (
                      'agent_skill_module_version', 'agent_skill_package',
                      'agent_skill_resource', 'agent_skill_catalog_revision')
                """)) {
            while (rows.next()) {
                long length = rows.getLong("character_maximum_length");
                Integer maxLength = rows.wasNull() ? null : Math.toIntExact(length);
                ColumnShape shape = new ColumnShape(rows.getString("table_name"), rows.getString("column_name"),
                        rows.getString("data_type"), maxLength, rows.getString("domain_name"),
                        rows.getString("domain_schema"), rows.getBoolean("default_collation"),
                        "YES".equals(rows.getString("is_nullable")), rows.getString("column_default"),
                        rows.getString("is_identity"), rows.getString("is_generated"));
                found.add(shape);
            }
        }
        if (!found.equals(COLUMNS)) {
            throw schemaMismatch();
        }
    }

    /**
     * 比较约束语义而非依赖服务器格式化的 SQL 文本：主键列序、外键级联目标及 CHECK 表达式都必须一致。
     */
    private static void validateConstraints(Connection connection) throws SQLException {
        Set<ConstraintShape> found = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT table_class.relname AS table_name, constraint_row.conname AS constraint_name,
                       constraint_row.contype AS constraint_type,
                       CASE WHEN constraint_row.contype IN ('p', 'u', 'f') THEN
                           (SELECT string_agg(attribute.attname, ',' ORDER BY key_column.ordinality)
                            FROM unnest(constraint_row.conkey) WITH ORDINALITY key_column(attnum, ordinality)
                            JOIN pg_attribute attribute
                              ON attribute.attrelid = constraint_row.conrelid
                             AND attribute.attnum = key_column.attnum)
                       END AS key_columns,
                       CASE WHEN referenced_namespace.oid = table_namespace.oid THEN NULL
                            ELSE referenced_namespace.nspname END AS referenced_schema,
                       referenced_table.relname AS referenced_table,
                       (SELECT string_agg(attribute.attname, ',' ORDER BY key_column.ordinality)
                        FROM unnest(constraint_row.confkey) WITH ORDINALITY key_column(attnum, ordinality)
                        JOIN pg_attribute attribute
                          ON attribute.attrelid = constraint_row.confrelid
                         AND attribute.attnum = key_column.attnum) AS referenced_columns,
                       CASE WHEN constraint_row.contype = 'f' THEN constraint_row.confdeltype::text END
                           AS delete_action,
                       CASE WHEN constraint_row.contype = 'f' THEN constraint_row.confupdtype::text END
                           AS update_action,
                       CASE WHEN constraint_row.contype = 'f' THEN constraint_row.confmatchtype::text END
                           AS match_type,
                       pg_get_expr(constraint_row.conbin, constraint_row.conrelid)
                           AS check_expression,
                       constraint_row.convalidated, constraint_row.condeferrable, constraint_row.condeferred
                FROM pg_constraint constraint_row
                JOIN pg_class table_class ON table_class.oid = constraint_row.conrelid
                JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
                LEFT JOIN pg_class referenced_table ON referenced_table.oid = constraint_row.confrelid
                LEFT JOIN pg_namespace referenced_namespace
                    ON referenced_namespace.oid = referenced_table.relnamespace
                WHERE table_namespace.nspname = current_schema()
                  AND table_class.relname IN (
                      'agent_skill_module_version', 'agent_skill_package',
                      'agent_skill_resource', 'agent_skill_catalog_revision')
                """)) {
            while (rows.next()) {
                found.add(new ConstraintShape(rows.getString("table_name"), rows.getString("constraint_name"),
                        rows.getString("constraint_type"), rows.getString("key_columns"),
                        rows.getString("referenced_schema"), rows.getString("referenced_table"),
                        rows.getString("referenced_columns"), rows.getString("delete_action"),
                        rows.getString("update_action"), rows.getString("match_type"),
                        normalizeExpression(rows.getString("check_expression")),
                        rows.getBoolean("convalidated"), rows.getBoolean("condeferrable"),
                        rows.getBoolean("condeferred")));
            }
        }
        if (!found.equals(CONSTRAINTS)) {
            throw schemaMismatch();
        }
    }

    /**
     * 限定四个主键索引均为默认形状的有效唯一 btree，并核对排序、opclass、collation 和完整键列集合。
     */
    private static void validateIndexes(Connection connection) throws SQLException {
        Set<IndexShape> found = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT table_class.relname AS table_name, index_class.relname AS index_name,
                       access_method.amname, index_row.indisunique, index_row.indisprimary,
                       index_row.indisvalid, index_row.indisready, index_row.indislive,
                       index_row.indpred IS NULL AS has_no_predicate,
                       index_row.indexprs IS NULL AS has_no_expressions,
                       (SELECT bool_and(ordering_row.indoption_value = 0)
                        FROM unnest(index_row.indoption) WITH ORDINALITY
                             ordering_row(indoption_value, ordinality)
                        WHERE ordering_row.ordinality <= index_row.indnkeyatts) AS default_ordering,
                       (SELECT bool_and(operator_class.opcdefault)
                        FROM unnest(index_row.indclass) WITH ORDINALITY
                             operator_class_key(operator_class_oid, ordinality)
                        JOIN pg_opclass operator_class
                          ON operator_class.oid = operator_class_key.operator_class_oid
                        WHERE operator_class_key.ordinality <= index_row.indnkeyatts)
                           AS default_operator_classes,
                       (SELECT bool_and(index_collation.collation_oid = attribute.attcollation)
                        FROM unnest(index_row.indcollation) WITH ORDINALITY
                             index_collation(collation_oid, ordinality)
                        JOIN unnest(index_row.indkey) WITH ORDINALITY index_key(attnum, ordinality)
                          ON index_key.ordinality = index_collation.ordinality
                        JOIN pg_attribute attribute
                          ON attribute.attrelid = table_class.oid AND attribute.attnum = index_key.attnum
                        WHERE index_collation.ordinality <= index_row.indnkeyatts) AS default_collations,
                       index_row.indnatts, index_row.indnkeyatts,
                       (SELECT string_agg(COALESCE(attribute.attname, '<expression>'), ','
                                          ORDER BY index_key.ordinality)
                        FROM unnest(index_row.indkey) WITH ORDINALITY index_key(attnum, ordinality)
                        LEFT JOIN pg_attribute attribute
                          ON attribute.attrelid = table_class.oid
                         AND attribute.attnum = index_key.attnum
                        WHERE index_key.ordinality <= index_row.indnkeyatts) AS key_columns
                FROM pg_index index_row
                JOIN pg_class table_class ON table_class.oid = index_row.indrelid
                JOIN pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
                JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
                JOIN pg_am access_method ON access_method.oid = index_class.relam
                WHERE table_namespace.nspname = current_schema()
                  AND table_class.relname IN (
                      'agent_skill_module_version', 'agent_skill_package',
                      'agent_skill_resource', 'agent_skill_catalog_revision')
                """)) {
            while (rows.next()) {
                found.add(new IndexShape(rows.getString("table_name"), rows.getString("index_name"),
                        rows.getString("amname"), rows.getBoolean("indisunique"),
                        rows.getBoolean("indisprimary"), rows.getBoolean("indisvalid"),
                        rows.getBoolean("indisready"), rows.getBoolean("indislive"),
                        rows.getBoolean("has_no_predicate"), rows.getBoolean("has_no_expressions"),
                        rows.getBoolean("default_ordering"), rows.getBoolean("default_operator_classes"),
                        rows.getBoolean("default_collations"),
                        rows.getInt("indnatts"), rows.getInt("indnkeyatts"),
                        rows.getString("key_columns")));
            }
        }
        if (!found.equals(INDEXES)) {
            throw schemaMismatch();
        }
    }

    /**
     * 版本行必须且只能标记本模块的当前版本；不把旧版或未知版本静默升级为当前语义。
     */
    private static void validateVersion(Connection connection) throws SQLException {
        int rowCount = 0;
        Integer version = null;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT module_name, schema_version FROM agent_skill_module_version")) {
            while (rows.next()) {
                rowCount++;
                if (!MODULE_NAME.equals(rows.getString("module_name"))) {
                    throw schemaMismatch();
                }
                version = rows.getInt("schema_version");
            }
        }
        if (rowCount != 1 || version == null) {
            throw schemaMismatch();
        }
        if (version != SCHEMA_VERSION) {
            throw new IllegalStateException("postgres_skill_schema_version_unsupported");
        }
    }

    /**
     * PostgreSQL 输出的 CHECK 表达式只允许空白和括号排版差异，操作符及标识符语义必须逐字一致。
     */
    private static String normalizeExpression(String expression) {
        if (expression == null) {
            return null;
        }
        return expression.replaceAll("[\\s()]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** 为不可变预期列构造无 NULL、无默认值、非生成的基线字段形状。 */
    private static ColumnShape column(String table, String name, String type, Integer maxLength) {
        return new ColumnShape(table, name, type, maxLength, null, null, true,
                false, null, "NO", "NEVER");
    }

    /** 构造主键语义；列序属于存储契约的一部分，不能只比较列集合。 */
    private static ConstraintShape primary(String table, String name, String columns) {
        return new ConstraintShape(table, name, "p", columns, null, null, null, null, null, null, null,
                true, false, false);
    }

    /** 构造非延迟、已验证 CHECK 约束并保存规范化表达式。 */
    private static ConstraintShape check(String table, String name, String expression) {
        return new ConstraintShape(table, name, "c", null, null, null, null, null, null, null,
                normalizeExpression(expression), true, false, false);
    }

    /** 构造租户与 Skill 身份复合外键，并要求引用扩展自有的同 schema 包表。 */
    private static ConstraintShape foreignKey(String table, String name, String columns,
                                              String referencedTable, String referencedColumns) {
        return new ConstraintShape(table, name, "f", columns, null, referencedTable, referencedColumns,
                "c", "a", "s", null, true, false, false);
    }

    /** 主键对应的唯一 btree 索引必须有效，且不能含额外包含列、谓词或表达式。 */
    private static IndexShape index(String table, String name, String columns) {
        int keyCount = columns.split(",").length;
        return new IndexShape(table, name, "btree", true, true, true, true, true,
                true, true, true, true, true, keyCount, keyCount, columns);
    }

    /** 将安装失败统一标记为稳定错误码，不向调用方泄露数据库结构或驱动细节。 */
    private static IllegalStateException schemaMismatch() {
        return new IllegalStateException("postgres_skill_schema_mismatch");
    }

    /**
     * 按事务归属回滚本次 DDL；不接管外层事务，并把回滚错误保留为原始安装错误的 suppressed 异常。
     */
    private static boolean rollback(Connection connection, boolean transactionOwner, Savepoint savepoint,
                                    Throwable failure) {
        try {
            if (transactionOwner) {
                connection.rollback();
            } else {
                connection.rollback(savepoint);
            }
            return true;
        } catch (SQLException | RuntimeException | Error rollbackFailure) {
            if (failure != rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            return false;
        }
    }

    /**
     * 仅在提交或回滚已明确完成后复原由安装器开启的事务状态；恢复失败不覆盖原始异常。
     */
    private static void restoreAutoCommit(Connection connection, Throwable failure) throws SQLException {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException | Error restoreFailure) {
            if (failure == null) {
                throw restoreFailure;
            }
            if (failure != restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    /**
     * 明确描述列类型及所有 PostgreSQL 列级约束，用值相等完成完整 schema 比较。
     * @param table 列所属的目标表名，决定列形状的校验上下文。
     * @param name 数据库列名，作为目标结构与目录结果的比对键。
     * @param type 规范化 PostgreSQL 基础类型，用于拒绝同名异型列。
     * @param maxLength 字符类型允许的最大长度；不适用时为空。
     * @param domainName 列引用的 PostgreSQL 域名；当前基线要求为空。
     * @param domainSchema 域定义所在 schema；当前基线要求为空。
     * @param defaultCollation 列是否使用数据库默认排序规则，用于拒绝显式排序覆盖。
     * @param nullable 列是否允许空值，与安装基线约束保持一致。
     * @param defaultValue 数据库目录返回的列默认表达式，核验初始化语义。
     * @param identity identity 列生成方式；基线不声明 identity。
     * @param generated 生成列表达式标记；基线不声明生成列。
     */
    private record ColumnShape(String table,
                               String name,
                               String type,
                               Integer maxLength,
                               String domainName,
                               String domainSchema,
                               boolean defaultCollation,
                               boolean nullable,
                               String defaultValue,
                               String identity,
                               String generated) {
    }

    /**
     * PostgreSQL 目录表投影出的主键、CHECK 或外键结构。
     * @param table 约束所属表名，限定要核验的关系。
     * @param name 数据库约束名，作为目标结构与目录结果的比对键。
     * @param type PostgreSQL 约束类型码，区分主键、CHECK 与外键。
     * @param keyColumns 约束覆盖的本地列顺序，保证复合键语义一致。
     * @param referencedSchema 外键目标 schema；非外键时为空。
     * @param referencedTable 外键目标表名；非外键时为空。
     * @param referencedColumns 外键目标列顺序；非外键时为空。
     * @param deleteAction 删除目标行时采用的外键动作码。
     * @param updateAction 更新目标键时采用的外键动作码。
     * @param matchType 外键匹配模式码，用于拒绝非默认匹配语义。
     * @param checkExpression CHECK 约束的规范化表达式；非 CHECK 约束时为空。
     * @param validated 约束是否已验证，未验证约束不能作为完整基线接纳。
     * @param deferrable 事务中是否允许延后约束检查。
     * @param deferred 约束是否默认延后至事务结束检查。
     */
    private record ConstraintShape(String table,
                                   String name,
                                   String type,
                                   String keyColumns,
                                   String referencedSchema,
                                   String referencedTable,
                                   String referencedColumns,
                                   String deleteAction,
                                   String updateAction,
                                   String matchType,
                                   String checkExpression,
                                   boolean validated,
                                   boolean deferrable,
                                   boolean deferred) {
    }

    /**
     * PostgreSQL 目录表投影出的索引列顺序、类型和构建状态。
     * @param table 索引所属表名，限定索引结构的校验上下文。
     * @param name 数据库索引名，作为目标结构与目录结果的比对键。
     * @param method PostgreSQL 索引访问方法，当前基线要求 B-tree。
     * @param unique 索引是否要求唯一性。
     * @param primary 索引是否支撑主键约束。
     * @param valid 索引是否可用于查询计划。
     * @param ready 索引是否已就绪并可接收写入。
     * @param live 索引是否仍关联有效表结构。
     * @param noPredicate 索引是否没有部分索引谓词。
     * @param noExpressions 索引是否只包含普通列而没有表达式。
     * @param defaultOrdering 索引键是否使用默认升序与空值排序方式。
     * @param defaultOperatorClasses 索引键是否使用列类型的默认操作符类。
     * @param defaultCollations 索引键是否使用列的默认排序规则。
     * @param attributeCount 索引实际包含的全部属性数量。
     * @param keyCount 参与索引键比较的属性数量，不含 INCLUDE 属性。
     * @param keyColumns 索引键列顺序，保留多列索引的查找语义。
     */
    private record IndexShape(String table,
                              String name,
                              String method,
                              boolean unique,
                              boolean primary,
                              boolean valid,
                              boolean ready,
                              boolean live,
                              boolean noPredicate,
                              boolean noExpressions,
                              boolean defaultOrdering,
                              boolean defaultOperatorClasses,
                              boolean defaultCollations,
                              int attributeCount,
                              int keyCount,
                              String keyColumns) {
    }

    /** 禁止实例化只含静态 schema 入口的工具类型，避免误以为安装器持有连接状态。 */
    private PostgresSkillSchema() {
        throw new AssertionError("static schema installer");
    }
}
