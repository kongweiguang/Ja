// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.support;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper;

import org.apache.ibatis.solon.annotation.Db;
import org.apache.ibatis.session.SqlSessionFactory;
import org.noear.solon.annotation.Component;

/** Embedded Solon 测试探针；通过官方 @Db 注入验证 named ja Mapper，不参与生产构件。 */
@Component
public final class EmbeddedPersistenceProbe {
    @Db("ja")
    private AgentMapper mapper;

    @Db("ja")
    private SqlSessionFactory sessions;

    /** 查询 committed usage，证明 XML Mapper 注入与 production transaction rollback 同时生效。 */
    public int countUsage(String turnId) {
        return mapper.countUsageForTurn(turnId);
    }

    /** 重绑必须被拒绝，证明 production composition 已把同一 named factory 交给 WAL close owner。 */
    public void requireWalCheckpointBinding(JaDatabase database) {
        try {
            database.bindWalCheckpoint(sessions);
        } catch (StorageException failure) {
            if (failure.code() == StorageException.Code.INVALID_STATE) return;
            throw failure;
        }
        throw new AssertionError("production WAL checkpoint factory was not bound");
    }
}
