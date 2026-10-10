// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.skills.pg.infrastructure;

import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.skills.pg.domain.SkillCatalogChangedException;
import io.github.kongweiguang.ja.agent.skills.pg.domain.SkillVersionConflictException;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 通过注入的 PostgreSQL DataSource 管理租户隔离的 Skill 包；目录分页和包更新共享显式版本。 */
public final class PostgresSkillSource implements SkillSource {
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_PAGE_SIZE = 4_096;
    private static final int MAX_BODY_CHARACTERS = 1_000_000;
    private static final int MAX_RESOURCE_BYTES = 1024 * 1024;
    private static final int MAX_RESOURCE_COUNT = 512;
    private static final long MAX_PACKAGE_BYTES = 16L * 1024 * 1024;

    private final DataSource dataSource;
    private final String id;
    private final int priority;

    /**
     * 显式绑定来源标识与覆盖顺序，并在启动边界拒绝未初始化或漂移的 schema；DataSource 生命周期仍归调用方。
     */
    public PostgresSkillSource(DataSource dataSource, String id, int priority) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (id == null || !id.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || priority < 0) {
            throw new IllegalArgumentException("invalid_skill_source_identity");
        }
        // 来源只消费显式安装的当前契约；构造时失败比首次查询时才暴露 schema 漂移更可诊断。
        PostgresSkillSchema.requireInstalled(dataSource);
        this.id = id;
        this.priority = priority;
    }

    /**
     * 返回 Core 注册时使用的来源身份，读取时还会用它拒绝其他来源的描述符。
     */
    @Override
    public String id() {
        return id;
    }

    /**
     * 返回业务方指定的覆盖优先级，不从数据库查询顺序或类路径扫描推导优先级。
     */
    @Override
    public int priority() {
        return priority;
    }

    /**
     * 按租户目录修订读取键集分页；两页之间若发生改动就明确失败，不混合不同目录代际。
     */
    @Override
    public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
        Objects.requireNonNull(context, "context");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid_skill_page_size");
        }
        Cursor decoded = cursor == null ? null : parseCursor(cursor);
        try (Connection connection = dataSource.getConnection()) {
            long before = readCatalogRevision(connection, context.tenantId());
            if (decoded != null && before != decoded.revision()) {
                throw new SkillCatalogChangedException();
            }
            List<SkillDescriptor> rows = readPage(connection, context.tenantId(),
                    decoded == null ? null : decoded.lastName(), pageSize);
            long after = readCatalogRevision(connection, context.tenantId());
            if (before != after) {
                throw new SkillCatalogChangedException();
            }
            if (rows.size() <= pageSize) {
                return new SkillPage(rows, null);
            }
            List<SkillDescriptor> visible = rows.subList(0, pageSize);
            String next = before + ":" + visible.getLast().name();
            return new SkillPage(visible, next);
        } catch (SQLException failure) {
            throw new IllegalStateException("postgres_skill_discovery_failed", failure);
        }
    }

    /**
     * 每条查询都限定租户、来源和发现时的包版本；正文或资源以 UTF-8 解码并按请求上限截断。
     */
    @Override
    public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor,
                                     SkillReadRequest request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(request, "request");
        if (!id.equals(descriptor.sourceId()) || !descriptor.name().equals(request.skillName())) {
            throw new IllegalArgumentException("skill_source_identity_mismatch:" + id);
        }
        try (Connection connection = dataSource.getConnection()) {
            String text = "SKILL.md".equals(request.resourcePath())
                    ? readBody(connection, context.tenantId(), descriptor)
                    : decodeUtf8(readResource(connection, context.tenantId(), descriptor, request.resourcePath()));
            int end = safeCharacterBoundary(text, request.maxCharacters());
            return new SkillResourceContent(text.substring(0, end), end < text.length());
        } catch (SQLException failure) {
            throw new IllegalStateException("postgres_skill_read_failed", failure);
        }
    }

    /**
     * 整包写入入口始终替换资源集合；版本校验、目录修订和事务顺序由共享协调器统一负责。
     */
    public SkillDescriptor write(SkillRequestContext context, SkillPackage skillPackage, long expectedVersion) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(skillPackage, "skillPackage");
        return writePackage(context, skillPackage, expectedVersion, true);
    }

    /**
     * 仅创建或更新 Skill 元数据和正文；空资源包只是共享值校验载体，replaceResources=false 保留已有资源。
     */
    public SkillDescriptor writeDocument(SkillRequestContext context, String name, String description,
                                         String body, long expectedVersion) {
        Objects.requireNonNull(context, "context");
        SkillPackage document = new SkillPackage(name, description, body, Map.of());
        return writePackage(context, document, expectedVersion, false);
    }

    /**
     * 单一事务协调器固定版本锁、CAS、目录修订和包头写入的先后；开关只表达真实的资源替换边界。
     */
    private SkillDescriptor writePackage(SkillRequestContext context, SkillPackage skillPackage,
                                         long expectedVersion, boolean replaceResources) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        try {
            return inTransaction(connection -> {
                long current = lockVersion(connection, context.tenantId(), skillPackage.name());
                if (current != expectedVersion) {
                    throw new SkillVersionConflictException();
                }
                long nextVersion = incrementCatalogRevision(connection, context.tenantId());
                if (current == 0) {
                    insertPackage(connection, context.tenantId(), skillPackage.name(), skillPackage.description(),
                            skillPackage.body(), nextVersion);
                } else {
                    updatePackage(connection, context.tenantId(), skillPackage.name(), skillPackage.description(),
                            skillPackage.body(), current, nextVersion);
                }
                if (replaceResources) {
                    if (current > 0) {
                        deleteResources(connection, context.tenantId(), skillPackage.name());
                    }
                    insertResources(connection, context.tenantId(), skillPackage);
                }
                return new SkillDescriptor(skillPackage.name(), skillPackage.description(), id, nextVersion);
            });
        } catch (SQLException failure) {
            if ("23505".equals(failure.getSQLState())) {
                throw new SkillVersionConflictException(failure);
            }
            throw new IllegalStateException("postgres_skill_write_failed", failure);
        }
    }

    /**
     * 只在版本匹配时删除整个包，并推进租户目录修订，使后续重建不会复用旧描述符版本。
     */
    public void delete(SkillRequestContext context, String skillName, long expectedVersion) {
        Objects.requireNonNull(context, "context");
        requireSkillName(skillName);
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        try {
            inTransaction(connection -> {
                long current = lockVersion(connection, context.tenantId(), skillName);
                if (current != expectedVersion) {
                    throw new SkillVersionConflictException();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                            DELETE FROM agent_skill_package WHERE tenant_id = ? AND skill_name = ? \
                            AND package_version = ?\
                            """)) {
                    statement.setString(1, context.tenantId());
                    statement.setString(2, skillName);
                    statement.setLong(3, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new SkillVersionConflictException();
                    }
                }
                incrementCatalogRevision(connection, context.tenantId());
                return null;
            });
        } catch (SQLException failure) {
            throw new IllegalStateException("postgres_skill_delete_failed", failure);
        }
    }

    /**
     * 表示一个不可变的 Skill 包；正文和资源作为同一版本提交，不提供本地导出或脚本执行入口。
     */
    public static final class SkillPackage {
        private final String name;
        private final String description;
        private final String body;
        private final Map<String, byte[]> resources;

        /**
         * 复用 Core 的 256 字符 Skill 身份上限，并在开启事务前校验正文、资源路径和大小、复制字节。
         */
        public SkillPackage(String name, String description, String body, Map<String, byte[]> resources) {
            this.name = requireSkillName(name);
            this.description = requireText(description, "description", 16_384);
            this.body = requireText(body, "body", MAX_BODY_CHARACTERS);
            Objects.requireNonNull(resources, "resources");
            if (resources.size() > MAX_RESOURCE_COUNT) {
                throw new IllegalArgumentException("skill_resource_count_limit");
            }
            long total = this.body.getBytes(StandardCharsets.UTF_8).length;
            LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                String rawPath = entry.getKey();
                byte[] rawBytes = entry.getValue();
                Objects.requireNonNull(rawBytes, "resource content");
                String path = requireResourcePath(name, rawPath);
                if (path.equals("SKILL.md")) {
                    throw new IllegalArgumentException("skill_body_is_not_a_resource");
                }
                if (rawBytes.length > MAX_RESOURCE_BYTES) {
                    throw new IllegalArgumentException("skill_resource_size_limit");
                }
                if (copy.putIfAbsent(path, rawBytes.clone()) != null) {
                    throw new IllegalArgumentException("skill_resource_path_duplicate");
                }
                total = Math.addExact(total, rawBytes.length);
            }
            if (total > MAX_PACKAGE_BYTES) {
                throw new IllegalArgumentException("skill_package_size_limit");
            }
            this.resources = Collections.unmodifiableMap(copy);
        }

        /**
         * 返回经过 kebab-case 校验的包名，作为租户目录键及版本冲突定位依据。
         */
        public String name() {
            return name;
        }

        /**
         * 返回发现目录展示的描述元数据，避免发现阶段提前物化模型将读取的正文。
         */
        public String description() {
            return description;
        }

        /**
         * 返回作为 `SKILL.md` 读取的原始正文；数据库存储不要求文件系统 frontmatter 格式。
         */
        public String body() {
            return body;
        }

        /**
         * 返回资源字节的深拷贝，避免校验后或提交时调用方改写同一个包对象。
         */
        public Map<String, byte[]> resources() {
            LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
            resources.forEach((path, bytes) -> copy.put(path, bytes.clone()));
            return Collections.unmodifiableMap(copy);
        }
    }

    /**
     * 按租户内唯一 Skill 名执行有界键集查询，并多取一行判断是否存在下一页。
     */
    private List<SkillDescriptor> readPage(Connection connection, String tenantId,
                                           String afterName, int pageSize) throws SQLException {
        String sql = afterName == null
                ? """
                    SELECT skill_name, description, package_version FROM agent_skill_package \
                    WHERE tenant_id = ? ORDER BY skill_name LIMIT ?\
                    """
                : """
                    SELECT skill_name, description, package_version FROM agent_skill_package \
                    WHERE tenant_id = ? AND skill_name > ? ORDER BY skill_name LIMIT ?\
                    """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            if (afterName == null) {
                statement.setInt(2, Math.addExact(pageSize, 1));
            } else {
                statement.setString(2, afterName);
                statement.setInt(3, Math.addExact(pageSize, 1));
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<SkillDescriptor> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new SkillDescriptor(rows.getString("skill_name"), rows.getString("description"),
                            id, rows.getLong("package_version")));
                }
                return List.copyOf(result);
            }
        }
    }

    /**
     * 仅在发现版本仍为当前版本时返回正文；版本变更与同版本下的不存在结果保持可区分。
     */
    private String readBody(Connection connection, String tenantId, SkillDescriptor descriptor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    SELECT body FROM agent_skill_package WHERE tenant_id = ? AND skill_name = ? \
                    AND package_version = ?\
                    """)) {
            bindDescriptor(statement, tenantId, descriptor);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getString(1);
                }
            }
        }
        requireCurrentVersion(connection, tenantId, descriptor);
        throw new IllegalArgumentException("skill_not_found");
    }

    /**
     * 用单条关联查询读取资源，让资源行与包版本在同一数据库语句快照中校验。
     */
    private byte[] readResource(Connection connection, String tenantId, SkillDescriptor descriptor,
                                String resourcePath) throws SQLException {
        String sql = """
                SELECT r.content
                FROM agent_skill_package p
                JOIN agent_skill_resource r
                    ON r.tenant_id = p.tenant_id AND r.skill_name = p.skill_name
                WHERE p.tenant_id = ?
                    AND p.skill_name = ?
                    AND p.package_version = ?
                    AND r.resource_path = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDescriptor(statement, tenantId, descriptor);
            statement.setString(4, resourcePath);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getBytes(1);
                }
            }
        }
        requireCurrentVersion(connection, tenantId, descriptor);
        throw new IllegalArgumentException("skill_resource_not_found");
    }

    /**
     * 只在同名包已升级时报告版本变化；同版本下缺少资源仍返回准确的资源不存在错误。
     */
    private static void requireCurrentVersion(Connection connection, String tenantId,
                                              SkillDescriptor descriptor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT package_version FROM agent_skill_package WHERE tenant_id = ? AND skill_name = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, descriptor.name());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && rows.getLong(1) != descriptor.version()) {
                    throw new IllegalStateException("skill_version_changed");
                }
            }
        }
    }

    /**
     * 锁定该租户的包行，使期望版本比较与完整替换在并发写入下串行执行。
     */
    private static long lockVersion(Connection connection, String tenantId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    SELECT package_version FROM agent_skill_package \
                    WHERE tenant_id = ? AND skill_name = ? FOR UPDATE\
                    """)) {
            statement.setString(1, tenantId);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    /**
     * 只插入经过值对象校验的包元数据；资源是否写入由共享协调器按入口语义决定。
     */
    private static void insertPackage(Connection connection, String tenantId, String name, String description,
                                      String body, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    INSERT INTO agent_skill_package (tenant_id, skill_name, description, body, package_version) \
                    VALUES (?, ?, ?, ?, ?)\
                    """)) {
            statement.setString(1, tenantId);
            statement.setString(2, name);
            statement.setString(3, description);
            statement.setString(4, body);
            statement.setLong(5, version);
            statement.executeUpdate();
        }
    }

    /**
     * 即使已有行锁仍在更新条件中保留期望版本，避免调用方契约只依赖某种锁实现。
     */
    private static void updatePackage(Connection connection, String tenantId, String name, String description,
                                      String body, long expected, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    UPDATE agent_skill_package SET description = ?, body = ?, package_version = ? \
                    WHERE tenant_id = ? AND skill_name = ? AND package_version = ?\
                    """)) {
            statement.setString(1, description);
            statement.setString(2, body);
            statement.setLong(3, version);
            statement.setString(4, tenantId);
            statement.setString(5, name);
            statement.setLong(6, expected);
            if (statement.executeUpdate() != 1) {
                throw new SkillVersionConflictException();
            }
        }
    }

    /**
     * 在包替换事务内清除旧资源，避免观察者看到新正文配旧附件或不完整资源集。
     */
    private static void deleteResources(Connection connection, String tenantId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM agent_skill_resource WHERE tenant_id = ? AND skill_name = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    /**
     * 在包头同一事务中批量插入完整资源集；资源集合已在事务前校验，不做部分成功提交。
     */
    private static void insertResources(Connection connection, String tenantId,
                                        SkillPackage skillPackage) throws SQLException {
        String sql = """
            INSERT INTO agent_skill_resource (tenant_id, skill_name, resource_path, content) \
            VALUES (?, ?, ?, ?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, byte[]> resource : skillPackage.resources.entrySet()) {
                statement.setString(1, tenantId);
                statement.setString(2, skillPackage.name());
                statement.setString(3, resource.getKey());
                statement.setBytes(4, resource.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 原子递增租户目录修订；同一单调序号同时用于分页游标和包描述符，删除重建也不会倒退。
     */
    private static long incrementCatalogRevision(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO agent_skill_catalog_revision (tenant_id, catalog_revision)
                VALUES (?, 1)
                ON CONFLICT (tenant_id) DO UPDATE
                    SET catalog_revision = agent_skill_catalog_revision.catalog_revision + 1
                RETURNING catalog_revision
                """)) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("catalog revision update returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /**
     * 返回租户当前目录修订；尚无 Skill 写入时明确以零作为初始代际。
     */
    private static long readCatalogRevision(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT catalog_revision FROM agent_skill_catalog_revision WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    /**
     * 将租户、名称和版本绑定到同一条参数化语句，保证读取只命中发现时的稳定描述符。
     */
    private static void bindDescriptor(PreparedStatement statement, String tenantId,
                                       SkillDescriptor descriptor) throws SQLException {
        statement.setString(1, tenantId);
        statement.setString(2, descriptor.name());
        statement.setLong(3, descriptor.version());
    }

    /**
     * 在借用连接上执行原子工作单元；回滚失败时不恢复 autocommit，避免 JDBC 隐式提交未决事务。
     */
    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean owner = connection.getAutoCommit();
            Savepoint savepoint = null;
            if (owner) {
                connection.setAutoCommit(false);
            } else {
                savepoint = connection.setSavepoint();
            }
            T result;
            try {
                result = work.apply(connection);
                if (owner) {
                    connection.commit();
                } else {
                    connection.releaseSavepoint(savepoint);
                }
            } catch (SQLException | RuntimeException | Error failure) {
                if (rollback(connection, owner, savepoint, failure) && owner) {
                    restoreAutoCommit(connection, failure);
                }
                throw failure;
            }
            if (owner) {
                restoreAutoCommit(connection, null);
            }
            return result;
        }
    }

    /**
     * 仅在事务已提交或回滚成功后恢复连接状态；恢复异常附加到原故障，不遮蔽事务失败原因。
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
     * 按事务归属回滚工作单元；只有 JDBC 明确确认回滚成功，调用方才可恢复 autocommit。
     */
    private static boolean rollback(Connection connection, boolean owner, Savepoint savepoint,
                                    Throwable failure) {
        try {
            if (owner) {
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
     * 将分页游标解析为目录修订与最后名称；租户始终来自请求上下文，不从游标接受权限数据。
     */
    private static Cursor parseCursor(String raw) {
        String[] parts = raw.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("skill_source_cursor_invalid");
        }
        try {
            long revision = Long.parseLong(parts[0]);
            requireSkillName(parts[1]);
            if (revision < 0) {
                throw new IllegalArgumentException("skill_source_cursor_invalid");
            }
            return new Cursor(revision, parts[1]);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("skill_source_cursor_invalid", invalid);
        }
    }

    /**
     * 与 Core SkillDescriptor 共用 256 字符名称上限，并在名称进入数据库键和工具请求前校验小写 kebab-case。
     */
    private static String requireSkillName(String value) {
        if (value == null || value.length() > 256 || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("skill_name_invalid");
        }
        return value;
    }

    /**
     * 复用 Core 的逻辑路径校验并限制资源深度；`SKILL.md` 保留给正文，不能被附件覆盖。
     */
    private static String requireResourcePath(String skillName, String value) {
        SkillReadRequest request = new SkillReadRequest(skillName, value, 1);
        if (request.resourcePath().length() > 1_024 || request.resourcePath().split("/").length > 8) {
            throw new IllegalArgumentException("skill_resource_path_invalid");
        }
        return request.resourcePath();
    }

    /**
     * 在事务开始前拒绝空值和超长文本，使输入问题不会留下部分数据库改动。
     */
    private static String requireText(String value, String field, int maxCharacters) {
        if (value == null || value.isBlank() || value.length() > maxCharacters) {
            throw new IllegalArgumentException("invalid_skill_" + field);
        }
        return value;
    }

    /**
     * 严格按 UTF-8 解码资源，遇到非法字节直接拒绝，避免模型看到被替换字符悄悄改写的内容。
     */
    private static String decodeUtf8(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_RESOURCE_BYTES) {
            throw new IllegalArgumentException("skill_resource_size_limit");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("skill_resource_not_utf8", malformed);
        }
    }

    /**
     * 在 UTF-16 码点边界截断，避免字符上限恰好切开代理对而生成非法字符串。
     */
    private static int safeCharacterBoundary(String text, int maxCharacters) {
        int end = Math.min(text.length(), maxCharacters);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return end;
    }

    /** 让每个读写用例共用明确的连接事务边界，并保留 SQL 故障类型供统一回滚。 */
    @FunctionalInterface
    private interface SqlWork<T> {
        /** 在调用方提供的事务连接中执行操作，不得自行获取或关闭另一条连接。 */
        T apply(Connection connection) throws SQLException;
    }

    /**
     * 保存游标中的租户目录代际与最后一条名称；租户身份只从请求上下文取得。
     *
     * @param revision 当前修订版本
     * @param lastName 最近处理的名称
     */
    private record Cursor(long revision,
                          String lastName) {
    }
}
