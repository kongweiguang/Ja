// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只比对受控 V1 的 CREATE 定义，不执行 DDL，也不为历史数据库生成修复或迁移。 */
final class JaSchemaContract {
    private static final String RESOURCE = "/db/migration/V1__kernel.sql";
    private static final int TABLE_COUNT = 60;
    private static final int INDEX_COUNT = 46;
    private static final int TRIGGER_COUNT = 28;
    private static final Pattern TABLE = Pattern.compile(
            "CREATE TABLE (?:IF NOT EXISTS )?\"?(ja_\\w+)\"?\\s*\\(.*?\\);", Pattern.DOTALL);
    private static final Pattern INDEX = Pattern.compile(
            "CREATE (?:UNIQUE )?INDEX (?:IF NOT EXISTS )?(\\w+)\\s+ON\\s+.*?;", Pattern.DOTALL);
    private static final Pattern TRIGGER = Pattern.compile(
            "CREATE TRIGGER (\\w+)\\b.*?\\bEND;", Pattern.DOTALL);

    /** 当前产品 schema 是内部准入契约，不提供通用 SQL 解析器或外部初始化入口。 */
    private JaSchemaContract() {
    }

    /** Flyway checksum 只证明迁移记录；实际表、索引与触发器同样必须匹配当前 V1。 */
    static void verify(Connection connection) throws SQLException {
        Map<String, String> expected = definitions(readResource());
        Map<String, String> actual = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT type,name,sql FROM sqlite_schema
                WHERE tbl_name GLOB 'ja_*' AND sql IS NOT NULL
                AND type IN ('table','index','trigger','view')
                """)) {
            while (rows.next()) {
                actual.put(rows.getString(1) + ':' + rows.getString(2), sqlTokens(rows.getString(3)));
            }
        }
        if (!expected.equals(actual)) {
            throw invalid();
        }
    }

    /**
     * 从唯一打包 V1 契约派生 Ja 表名，避免准入检查维护第二份可漂移的产品表清单。
     */
    static Set<String> expectedTableNames() {
        return definitions(readResource()).keySet().stream()
                .filter(key -> key.startsWith("table:"))
                .map(key -> key.substring("table:".length()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 资源只来自打包 V1，不能由待校验库提供期望结构，避免漂移变成新的权威基线。 */
    private static String readResource() {
        try (InputStream stream = JaSchemaContract.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("Ja database baseline resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StorageException(StorageException.Code.IO, "SCHEMA_VALIDATION_FAILED", failure);
        }
    }

    /** 固定 V1 的对象数量同时校验 parser 覆盖面；不处理外部 SQL 或推断任意方言、兼容布局。 */
    private static Map<String, String> definitions(String source) {
        Map<String, String> definitions = new HashMap<>();
        addDefinitions(definitions, source, "table", TABLE);
        addDefinitions(definitions, source, "index", INDEX);
        addDefinitions(definitions, source, "trigger", TRIGGER);
        long tables = definitions.keySet().stream().filter(key -> key.startsWith("table:")).count();
        long indexes = definitions.keySet().stream().filter(key -> key.startsWith("index:")).count();
        long triggers = definitions.keySet().stream().filter(key -> key.startsWith("trigger:")).count();
        if (tables != TABLE_COUNT || indexes != INDEX_COUNT || triggers != TRIGGER_COUNT) {
            throw invalid();
        }
        return Map.copyOf(definitions);
    }

    /** SQLite 保存 CREATE 时移除 IF NOT EXISTS 与末尾分号，其余约束语义必须保持一致。 */
    private static void addDefinitions(Map<String, String> definitions, String source, String type, Pattern pattern) {
        Matcher matches = pattern.matcher(source);
        while (matches.find()) {
            String statement = matches.group().replace("IF NOT EXISTS ", "");
            String identity = type + ':' + matches.group(1);
            if (definitions.putIfAbsent(identity, sqlTokens(statement.substring(0, statement.length() - 1))) != null) {
                throw invalid();
            }
        }
    }

    /** 排版空白可以忽略，但 CHECK/触发器中的字符串及转义必须逐字保留。 */
    private static String sqlTokens(String sql) {
        StringBuilder tokens = new StringBuilder(sql.length());
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char value = sql.charAt(index);
            if (value == '\'') {
                tokens.append(value);
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    tokens.append(sql.charAt(++index));
                } else {
                    quoted = !quoted;
                }
            } else if (quoted || !Character.isWhitespace(value)) {
                tokens.append(value);
            }
        }
        return tokens.toString();
    }

    /** 结构漂移要求修复原格式或显式离线处置，不能触发在线初始化和删除。 */
    private static StorageException invalid() {
        return new StorageException(StorageException.Code.STORAGE_CONFLICT, "SCHEMA_VALIDATION_FAILED");
    }
}
