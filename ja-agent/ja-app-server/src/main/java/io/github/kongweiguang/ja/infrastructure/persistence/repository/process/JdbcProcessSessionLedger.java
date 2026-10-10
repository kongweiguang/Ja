// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.process;

import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionScope;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionSummary;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.ProcessSessionUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite 持久化进程启动身份、有界输出后缀与重启中断事实。 */
public final class JdbcProcessSessionLedger implements ProcessSessionLedger {
    private static final int OUTPUT_RETENTION_CHARACTERS = 262_144;
    private static final DateTimeFormatter STORED_INSTANT = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final ProcessSessionUnitOfWork unitOfWork;

    /**
     * 复用应用命名数据库会话，使启动代际围栏与事务共享唯一存储 owner。
     *
     * @param sessions 应用唯一的 MyBatis 会话工厂
     */
    public JdbcProcessSessionLedger(SqlSessionFactory sessions) {
        this.unitOfWork = new ProcessSessionUnitOfWork(Objects.requireNonNull(sessions, "sessions"));
    }

    /** 在分配原生资源前提交 starting 事实，并按稳定调用身份处理重试。 */
    @Override
    public Entry createStarting(Entry entry) {
        Entry requested = Objects.requireNonNull(entry, "entry");
        return unitOfWork.required(connection -> {
            String sql = "INSERT OR IGNORE INTO ja_process_session "
                    + "(session_id,workspace_id,thread_id,started_turn_id,started_call_id,generation,command,state,"
                    + "output_sequence,output_text,started_at,ended_at) VALUES (?,?,?,?,?,?,?,'starting',0,'',?,NULL)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                insert.setString(1, requested.sessionId());
                insert.setString(2, requested.workspaceId());
                insert.setString(3, requested.threadId());
                insert.setString(4, requested.startedTurnId());
                insert.setString(5, requested.startedCallId());
                insert.setLong(6, requested.generation());
                insert.setString(7, requested.command());
                insert.setString(8, format(requested.createdAt()));
                insert.executeUpdate();
            }
            return queryByCall(connection, requested.threadId(), requested.startedTurnId(), requested.startedCallId())
                    .orElseThrow(() -> new IllegalStateException("process session call identity conflict"));
        });
    }

    /** 只以持久化工具调用身份作为启动重试键，不按可见命令文本猜测重复请求。 */
    @Override
    public Optional<Entry> findByCall(String threadId, String turnId, String callId) {
        return unitOfWork.required(connection -> queryByCall(connection, threadId, turnId, callId));
    }

    /** 工作区与线程身份同时匹配后才读取账本行，避免跨作用域查询会话。 */
    @Override
    public Optional<Entry> find(String sessionId, String workspaceId, String threadId) {
        return unitOfWork.required(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT * FROM ja_process_session WHERE session_id=? AND workspace_id=? AND thread_id=?")) {
                query.setString(1, sessionId);
                query.setString(2, workspaceId);
                query.setString(3, threadId);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? Optional.of(read(rows)) : Optional.empty();
                }
            }
        });
    }

    /** 在数据库内应用身份和调用筛选，再按稳定降序键集分页；游标格式不离开持久化实现。 */
    @Override
    public ProcessSessionLedger.Page list(ProcessSessionScope scope, int limit,
                                          String cursor, String startedCallId) {
        Objects.requireNonNull(scope, "scope");
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("invalid process session page size");
        }
        Cursor decoded = cursor == null ? null : decodeCursor(cursor);
        return unitOfWork.required(connection -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM ja_process_session WHERE workspace_id=? AND thread_id=?");
            if (startedCallId != null) {
                sql.append(" AND started_call_id=?");
            }
            if (decoded != null) {
                sql.append(" AND (started_at<? OR (started_at=? AND session_id<?))");
            }
            sql.append(" ORDER BY started_at DESC,session_id DESC LIMIT ?");
            List<Entry> entries = new ArrayList<>(limit + 1);
            try (PreparedStatement query = connection.prepareStatement(sql.toString())) {
                int parameter = 1;
                query.setString(parameter++, scope.workspaceId());
                query.setString(parameter++, scope.threadId());
                if (startedCallId != null) {
                    query.setString(parameter++, startedCallId);
                }
                if (decoded != null) {
                    String timestamp = format(decoded.createdAt());
                    query.setString(parameter++, timestamp);
                    query.setString(parameter++, timestamp);
                    query.setString(parameter++, decoded.sessionId());
                }
                query.setInt(parameter, limit + 1);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        entries.add(read(rows));
                    }
                }
            }
            String nextCursor = null;
            if (entries.size() > limit) {
                entries.removeLast();
                Entry last = entries.getLast();
                nextCursor = encodeCursor(new Cursor(last.createdAt(), last.sessionId()));
            }
            List<ProcessSessionSummary> summaries = entries.stream().map(Entry::summary).toList();
            return new ProcessSessionLedger.Page(summaries, nextCursor);
        });
    }

    /** 只查询仍可能持有原生进程的状态，供 Thread 关闭清理使用。 */
    @Override
    public List<Entry> active(String workspaceId, String threadId) {
        return unitOfWork.required(connection -> {
            List<Entry> entries = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT * FROM ja_process_session WHERE workspace_id=? AND thread_id=? "
                            + "AND state IN ('starting','running','stopping') ORDER BY started_at")) {
                query.setString(1, workspaceId);
                query.setString(2, threadId);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        entries.add(read(rows));
                    }
                }
            }
            return List.copyOf(entries);
        });
    }

    /** 只在身份核对通过后更新可变状态，拒绝过时代际回调改写其它账本行。 */
    @Override
    public void update(Entry entry) {
        Entry value = Objects.requireNonNull(entry, "entry");
        unitOfWork.required(connection -> {
            String sql = "UPDATE ja_process_session SET state=?,output_sequence=?,output_text=?,exit_code=?,ended_at=? "
                    + "WHERE session_id=? AND workspace_id=? AND thread_id=? AND started_turn_id=? "
                    + "AND started_call_id=? AND generation=?";
            try (PreparedStatement update = connection.prepareStatement(sql)) {
                update.setString(1, value.state());
                update.setLong(2, value.outputSequence());
                update.setString(3, retainSuffix(value.output()));
                if (value.exitCode() == null) {
                    update.setNull(4, java.sql.Types.INTEGER);
                } else {
                    update.setInt(4, value.exitCode());
                }
                if (value.endedAt() == null) {
                    update.setNull(5, java.sql.Types.VARCHAR);
                } else {
                    update.setString(5, format(value.endedAt()));
                }
                update.setString(6, value.sessionId());
                update.setString(7, value.workspaceId());
                update.setString(8, value.threadId());
                update.setString(9, value.startedTurnId());
                update.setString(10, value.startedCallId());
                update.setLong(11, value.generation());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("process session update identity mismatch");
                }
            }
            return null;
        });
    }

    /** 在发送输入前提交操作键，并以原始请求摘要检查重复键是否复用了不同负载。 */
    @Override
    public WriteClaim claimWrite(String sessionId, String operationKey, String requestFingerprint, Instant createdAt) {
        return unitOfWork.required(connection -> {
            int inserted;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO ja_process_write_operation "
                            + "(session_id,operation_key,request_fingerprint,state,created_at,completed_at) "
                            + "VALUES (?,?,?,'claimed',?,NULL)")) {
                insert.setString(1, sessionId);
                insert.setString(2, operationKey);
                insert.setString(3, requestFingerprint);
                insert.setString(4, format(createdAt));
                inserted = insert.executeUpdate();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT request_fingerprint,state FROM ja_process_write_operation "
                            + "WHERE session_id=? AND operation_key=?")) {
                query.setString(1, sessionId);
                query.setString(2, operationKey);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("process write claim was not persisted");
                    }
                    if (!requestFingerprint.equals(rows.getString("request_fingerprint"))) {
                        throw new IllegalArgumentException("process write operation key was reused");
                    }
                    if (inserted == 1) {
                        return WriteClaim.CLAIMED;
                    }
                    return switch (rows.getString("state")) {
                        case "applied" -> WriteClaim.APPLIED;
                        case "unknown" -> WriteClaim.UNKNOWN;
                        case "claimed" -> WriteClaim.PENDING;
                        default -> throw new IllegalStateException("invalid process write claim state");
                    };
                }
            }
        });
    }

    /** 只关闭仍待决的写入回执，拒绝重复回调覆盖已经发布的结果。 */
    @Override
    public void resolveWrite(String sessionId, String operationKey, WriteClaim state, Instant completedAt) {
        if (state != WriteClaim.APPLIED && state != WriteClaim.UNKNOWN) {
            throw new IllegalArgumentException("process write resolution must be terminal");
        }
        unitOfWork.required(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ja_process_write_operation SET state=?,completed_at=? "
                            + "WHERE session_id=? AND operation_key=? AND state='claimed'")) {
                update.setString(1, state == WriteClaim.APPLIED ? "applied" : "unknown");
                update.setString(2, format(Objects.requireNonNull(completedAt, "completedAt")));
                update.setString(3, sessionId);
                update.setString(4, operationKey);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("process write claim is no longer pending");
                }
            }
            return null;
        });
    }

    /** 新注册表接纳命令前围栏所有旧代际，只更新活动事实为中断而不重放原命令。 */
    @Override
    public void interruptOtherGenerations(long generation, Instant endedAt) {
        if (generation < 1) {
            throw new IllegalArgumentException("invalid process generation");
        }
        unitOfWork.required(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ja_process_session SET state='interrupted',ended_at=? "
                            + "WHERE generation<>? AND state IN ('starting','running','stopping')")) {
                update.setString(1, format(Objects.requireNonNull(endedAt, "endedAt")));
                update.setLong(2, generation);
                update.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 仅在账本内部解码 URL-safe 游标，拒绝伪造、损坏或超长的续页标记。
     *
     * @param cursor 客户端回传的不透明账本游标
     * @return 数据库键集分页键
     */
    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.length() > 256 || !cursor.matches("[A-Za-z0-9._~-]{1,256}")) {
            throw new IllegalArgumentException("invalid process session cursor");
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            int separator = value.indexOf('\n');
            if (separator < 1 || separator == value.length() - 1 || value.indexOf('\n', separator + 1) >= 0) {
                throw new IllegalArgumentException("invalid process session cursor");
            }
            return new Cursor(Instant.parse(value.substring(0, separator)),
                    value.substring(separator + 1));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid process session cursor", failure);
        }
    }

    /**
     * 将固定精度时间戳与会话 ID 编码为无填充 Base64URL 键集游标。
     *
     * @param cursor 下一页起点的数据库排序键
     * @return 仅供账本实现解释的续页游标
     */
    private static String encodeCursor(Cursor cursor) {
        String value = format(cursor.createdAt()) + "\n" + cursor.sessionId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 将数据库行恢复为不可变账本值，并保留可空结束时间与退出码。 */
    private static Entry read(ResultSet rows) throws SQLException {
        String endedAt = rows.getString("ended_at");
        int rawExit = rows.getInt("exit_code");
        Integer exit = rows.wasNull() ? null : rawExit;
        return new Entry(rows.getString("session_id"), rows.getString("workspace_id"), rows.getString("thread_id"),
                rows.getString("started_turn_id"), rows.getString("started_call_id"), rows.getLong("generation"),
                rows.getString("command"), rows.getString("state"), rows.getString("output_text"),
                rows.getLong("output_sequence"), exit, Instant.parse(rows.getString("started_at")),
                endedAt == null ? null : Instant.parse(endedAt));
    }

    /** 在 INSERT OR IGNORE 后按唯一模型调用身份查找记录，覆盖跨进程重试。 */
    private static Optional<Entry> queryByCall(Connection connection, String threadId, String turnId, String callId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM ja_process_session WHERE thread_id=? AND started_turn_id=? AND started_call_id=?")) {
            query.setString(1, threadId);
            query.setString(2, turnId);
            query.setString(3, callId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    /** 只保留最新有界输出，并在裁剪点保护 UTF-16 代理项对。 */
    private static String retainSuffix(String output) {
        if (output.length() <= OUTPUT_RETENTION_CHARACTERS) {
            return output;
        }
        int start = output.length() - OUTPUT_RETENTION_CHARACTERS;
        if (Character.isLowSurrogate(output.charAt(start)) && Character.isHighSurrogate(output.charAt(start - 1))) {
            start++;
        }
        return output.substring(start);
    }

    /**
     * 将时间格式化为固定宽度 UTC 文本，保证 SQLite 文本排序等同时间顺序。
     *
     * @param instant 待持久化的时间
     * @return 固定精度 UTC 文本
     */
    private static String format(Instant instant) {
        return STORED_INSTANT.format(instant);
    }

    /**
     * 表示只在账本实现内部使用的复合排序键，不把存储结构暴露给会话用例。
     *
     * @param createdAt 会话创建时间
     * @param sessionId 会话稳定标识
     */
    private record Cursor(Instant createdAt,
                          String sessionId) {
    }
}
