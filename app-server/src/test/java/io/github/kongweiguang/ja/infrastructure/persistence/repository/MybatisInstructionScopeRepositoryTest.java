// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.InstructionScopeRepository;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;

/** 真实临时 SQLite 验证当前基线 scope 的幂等、容量和跨重启语义。 */
final class MybatisInstructionScopeRepositoryTest extends PersistenceTestSupport {
    /** 同一 scope 重放不消耗容量，读取顺序按深度和路径固定。 */
    @Test
    void persistsScopesIdempotentlyInStableOrder() throws Exception {
        try (TestDatabase database = database("instruction-scopes")) {
            initializeThread(database, "thr_scopes");
            MybatisInstructionScopeRepository repository = database.instructionScopes();

            assertEquals(InstructionScopeRepository.Registration.REGISTERED,
                    repository.register("thr_scopes", "src/deep", START));
            assertEquals(InstructionScopeRepository.Registration.REGISTERED,
                    repository.register("thr_scopes", "docs", START.plusSeconds(1)));
            assertEquals(InstructionScopeRepository.Registration.ALREADY_PRESENT,
                    repository.register("thr_scopes", "src/deep", START.plusSeconds(2)));

            assertEquals(List.of("docs", "src/deep"), repository.list("thr_scopes"));
        }
    }

    /** Scope 行保存在 SQLite 而非进程缓存，重新打开 datasource 后仍可恢复。 */
    @Test
    void restoresScopesAcrossDatabaseRestart() throws Exception {
        try (TestDatabase database = database("instruction-restart")) {
            initializeThread(database, "thr_restart");
            assertEquals(InstructionScopeRepository.Registration.REGISTERED,
                    database.instructionScopes().register("thr_restart", "module", START));
        }

        try (TestDatabase reopened = database("instruction-restart")) {
            assertEquals(List.of("module"), reopened.instructionScopes().list("thr_restart"));
        }
    }

    /** 第 257 个新 scope 被拒绝，但满容量后的既有 scope 仍保持幂等成功。 */
    @Test
    void enforcesPerThreadScopeLimitWithoutBreakingRetries() throws Exception {
        try (TestDatabase database = database("instruction-limit")) {
            initializeThread(database, "thr_limit");
            MybatisInstructionScopeRepository repository = database.instructionScopes();
            for (int index = 0; index < 256; index++) {
                assertEquals(InstructionScopeRepository.Registration.REGISTERED,
                        repository.register("thr_limit", "scope-" + index, START.plusSeconds(index)));
            }

            assertEquals(InstructionScopeRepository.Registration.LIMIT_REACHED,
                    repository.register("thr_limit", "scope-overflow", START.plusSeconds(300)));
            assertEquals(InstructionScopeRepository.Registration.ALREADY_PRESENT,
                    repository.register("thr_limit", "scope-0", START.plusSeconds(301)));
            assertEquals(256, repository.list("thr_limit").size());
        }
    }

    /** 两个独立事务竞争最后容量时只能有一个注册成功，另一方读取提交后的硬上限结果。 */
    @Test
    void serializesConcurrentRegistrationsAtTheLimit() throws Exception {
        try (TestDatabase database = database("instruction-concurrent")) {
            initializeThread(database, "thr_concurrent");
            MybatisInstructionScopeRepository repository = database.instructionScopes();
            for (int index = 0; index < 255; index++) {
                assertEquals(InstructionScopeRepository.Registration.REGISTERED,
                        repository.register("thr_concurrent", "scope-" + index, START.plusSeconds(index)));
            }
            CountDownLatch start = new CountDownLatch(1);
            try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<InstructionScopeRepository.Registration> first = executor.submit(() -> {
                    start.await();
                    return repository.register("thr_concurrent", "winner-a", START.plusSeconds(300));
                });
                Future<InstructionScopeRepository.Registration> second = executor.submit(() -> {
                    start.await();
                    return repository.register("thr_concurrent", "winner-b", START.plusSeconds(301));
                });
                start.countDown();
                List<InstructionScopeRepository.Registration> results = List.of(
                        first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(
                        result -> result == InstructionScopeRepository.Registration.REGISTERED).count());
                assertEquals(1, results.stream().filter(
                        result -> result == InstructionScopeRepository.Registration.LIMIT_REACHED).count());
            }
            assertEquals(256, repository.list("thr_concurrent").size());
        }
    }

    /** Scope 外键绑定真实 Thread，避免测试绕开当前基线身份约束。 */
    private void initializeThread(TestDatabase database, String threadId) {
        MybatisConversationRepository store = database.agentStore();
        MybatisHistoryService history = database.history(store);
        Path root = temp.resolve(threadId).toAbsolutePath().normalize();
        history.register(new Workspace.Registration(
                "ws_" + threadId, root, threadId, Workspace.Trust.TRUSTED, START));
        store.createThread(new ConversationRepository.ThreadDefinition(
                threadId, "ws_" + threadId, threadId, preferences(), START));
        assertTrue(history.readThread(threadId, null, 10).isPresent());
    }
}
