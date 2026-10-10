// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.store.pg.infrastructure;

import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;

/** 当前八表的目录快照只用于校验；结构化 keys/refs 避免依赖 PostgreSQL DDL pretty-printer。 */
final class PostgresSchemaContract {
    private static final String RESOURCE = "/io/github/kongweiguang/ja/agent/store/pg/infrastructure/schema-contract.json";
    private static final JsonValueCodec JSON = JsonValueCodecFactory.standard();
    private static final String SHAPE_QUERY = """
            WITH owned AS (
              SELECT r.oid,r.relname,r.relnamespace FROM pg_catalog.pg_class r
              JOIN pg_catalog.pg_namespace n ON n.oid=r.relnamespace
              WHERE n.nspname=current_schema() AND r.relname IN (
                'agent_schema_version','agent_conversation','agent_execution','agent_record',
                'agent_event','agent_operation_receipt','agent_commit_receipt','agent_lease')
              AND r.relkind='r'
            )
            SELECT jsonb_build_object(
              'columns',(SELECT jsonb_agg(jsonb_build_object(
                'table',r.relname,'name',a.attname,'ordinal',a.attnum,
                'type',pg_catalog.format_type(a.atttypid,a.atttypmod),'notNull',a.attnotnull,
                'default',pg_catalog.pg_get_expr(d.adbin,d.adrelid,false),
                'identity',a.attidentity,'generated',a.attgenerated) ORDER BY r.relname,a.attnum)
                FROM owned r JOIN pg_catalog.pg_attribute a ON a.attrelid=r.oid
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=r.oid AND d.adnum=a.attnum
                WHERE a.attnum>0 AND NOT a.attisdropped),
              'constraints',(SELECT jsonb_agg(jsonb_build_object(
                'table',r.relname,'name',c.conname,'type',c.contype,'columns',c.conkey,
                'referenceTable',p.relname,'referenceColumns',c.confkey,
                'referenceInOwnerSchema',CASE WHEN p.oid IS NULL THEN NULL ELSE p.relnamespace=r.relnamespace END,
                'updateAction',c.confupdtype,'deleteAction',c.confdeltype,'match',c.confmatchtype,
                'deferrable',c.condeferrable,'deferred',c.condeferred,'validated',c.convalidated,
                'expression',pg_catalog.pg_get_expr(c.conbin,c.conrelid,false)) ORDER BY r.relname,c.conname)
                FROM owned r JOIN pg_catalog.pg_constraint c ON c.conrelid=r.oid
                LEFT JOIN pg_catalog.pg_class p ON p.oid=c.confrelid
                WHERE c.contype IN ('p','u','f','c')))
            """;

    /** 私有固定契约不提供实例或通用 DDL API，初始化 owner 仍由宿主显式持有。 */
    private PostgresSchemaContract() {
    }

    /** 只读比对列与完整约束；任何缺失、额外或未验证的约束都阻断 Store 构造。 */
    static void verify(Connection connection) throws SQLException {
        JsonObject expected = readContract();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(SHAPE_QUERY)) {
            if (!rows.next()) {
                throw invalid();
            }
            JsonObject actual;
            try {
                actual = JSON.decodeObject(rows.getString(1));
            } catch (RuntimeException malformed) {
                throw new AgentStore.StoreIntegrityException("PostgreSQL AgentStore catalog is invalid", malformed);
            }
            if (rows.next() || !normalize(expected).equals(normalize(actual))) {
                throw invalid();
            }
        }
    }

    /** 固定资源随原始 schema 交付，不从数据库或环境回填期望值，避免把漂移认作基线。 */
    private static JsonObject readContract() {
        try (InputStream input = PostgresSchemaContract.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new AgentStore.StoreIntegrityException("PostgreSQL AgentStore schema contract is missing", null);
            }
            return JSON.decodeObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException failure) {
            throw new AgentStore.StoreIntegrityException("PostgreSQL AgentStore schema contract is invalid", failure);
        }
    }

    /** 只规范目录表达式的排版，keys/refs/types 保持结构化精确比较，字符串字面量逐字保留。 */
    private static JsonObject normalize(JsonObject shape) {
        LinkedHashMap<String, JsonValue> normalized = new LinkedHashMap<>(shape.members());
        for (String collection : new String[]{"columns", "constraints"}) {
            if (!(shape.get(collection) instanceof JsonArray array)) {
                throw invalid();
            }
            normalized.put(collection, new JsonArray(array.values().stream().map(value -> {
                if (!(value instanceof JsonObject row)) {
                    throw invalid();
                }
                LinkedHashMap<String, JsonValue> fields = new LinkedHashMap<>(row.members());
                for (String expression : new String[]{"expression", "default"}) {
                    if (fields.get(expression) instanceof JsonText text) {
                        fields.put(expression, new JsonText(expressionTokens(text.value())));
                    }
                }
                return (JsonValue) new JsonObject(fields);
            }).toList()));
        }
        return new JsonObject(normalized);
    }

    /** pg_get_expr 的排版空白不影响 CHECK，但引号内空白、转义及 casts 都属于约束语义。 */
    private static String expressionTokens(String sql) {
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

    /** 结构漂移属于完整性故障，不伪装成连接不可用或触发自动初始化。 */
    private static AgentStore.StoreIntegrityException invalid() {
        return new AgentStore.StoreIntegrityException("PostgreSQL AgentStore schema structure is invalid", null);
    }
}
