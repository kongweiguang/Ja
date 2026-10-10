// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.time.Instant;

/** 只在显式时间字段启用转换，防止普通文本误用毫秒存储契约。 */
public final class EpochMillisStringTypeHandler extends BaseTypeHandler<String> {
    /** 应用边界保留 ISO Instant，而 SQLite 仅存 UTC 毫秒，转换必须在具名 SQL 参数处完成。 */
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, String value, JdbcType jdbcType)
            throws SQLException {
        statement.setLong(index, Instant.parse(value).toEpochMilli());
    }

    /** 可空时刻只由 SQL null 表达；读取真实存储类型，拒绝驱动将损坏文本隐式转换为零。 */
    @Override
    public String getNullableResult(ResultSet rows, String column) throws SQLException {
        return decode(rows.getObject(column));
    }

    /** 按序号读取复用同一 UTC 转换，避免查询别名影响时间语义。 */
    @Override
    public String getNullableResult(ResultSet rows, int column) throws SQLException {
        return decode(rows.getObject(column));
    }

    /** 保持 JDBC 可调用语句的相同边界，避免驱动入口差异产生隐式时间格式。 */
    @Override
    public String getNullableResult(CallableStatement statement, int column) throws SQLException {
        return decode(statement.getObject(column));
    }

    /**
     * SQLite 与 PostgreSQL 的整数列可返回不同整数包装类型；浮点、文本和其他类型均违反毫秒契约，
     * 错误只携带稳定分类，不回显损坏值，也不兼容历史 ISO 文本存储。
     */
    private static String decode(Object value) throws SQLDataException {
        if (value == null) {
            return null;
        }
        long millis = switch (value) {
            case Byte number -> number.longValue();
            case Short number -> number.longValue();
            case Integer number -> number.longValue();
            case Long number -> number.longValue();
            default -> throw new SQLDataException("persisted UTC epoch-millis value is not an integer", "22007");
        };
        return Instant.ofEpochMilli(millis).toString();
    }
}
