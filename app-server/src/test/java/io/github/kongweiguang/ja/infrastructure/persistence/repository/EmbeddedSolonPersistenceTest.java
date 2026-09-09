// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.model.TextContent;

import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseLeaseProbe;
import io.github.kongweiguang.ja.infrastructure.persistence.support.EmbeddedPersistenceProbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.execution;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.usageFact;

import io.github.kongweiguang.ja.bootstrap.App;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;

/** Embedded Solon 验证 named ja Mapper 注入与官方 transaction rollback。 */
final class EmbeddedSolonPersistenceTest {
    /** constraint failure 必须由真实 Solon transaction 回滚 terminal、message 与usage facts。 */
    @Test
    void injectedConversationRepositoryRollsBackConstraintFailure() throws Exception {
        Path data = Files.createTempDirectory("ja-persistence-solon-");
        String previousData = System.getProperty("ja.data-dir");
        String previousRun = System.getProperty("ja.run-dir");
        try {
            System.setProperty("ja.data-dir", data.toString());
            System.setProperty("ja.run-dir", Files.createTempDirectory("ja-persistence-run-").toString());
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));
            ConversationRepository store = Solon.context().getBean(ConversationRepository.class);
            WorkspaceRepository workspaces = Solon.context().getBean(WorkspaceRepository.class);
            assertSame(store, Solon.context().getBean(ConversationRepository.class));
            EmbeddedPersistenceProbe probe = new EmbeddedPersistenceProbe();
            Solon.context().beanInject(probe);
            probe.requireWalCheckpointBinding(Solon.context().getBean(JaDatabase.class));
            Instant now = Instant.parse("2026-08-25T12:00:00Z");
            workspaces.register(new Workspace.Registration("ws_embedded", data,
                    "embedded", Workspace.Trust.TRUSTED, now));
            store.createThread(new ConversationRepository.ThreadDefinition("thr_embedded", "ws_embedded",
                    "embedded", preferences("provider_embedded", "model_embedded"), now));
            ConversationRepository.AdmissionReceipt admission = store.admit(new ConversationRepository.TurnAdmission(
                    "thr_embedded", "turn_embedded",
                    "item_user",
                    new ModelMessage(ModelRole.USER,
                            List.of(new TextContent("hello"))), List.of(), 0, now,
                    execution("cfg_embedded")));
            ConversationRepository.CommitReceipt running = store.commit(new ConversationRepository.CommitRequest(
                    "thr_embedded", "turn_embedded", TurnState.RUNNING, List.of(),
                    admission.turnMutationVersion(), now.plusSeconds(1), execution("cfg_embedded")));
            ModelUsage usage = new ModelUsage(3, 2, 5);
            long expected = running.turnMutationVersion();
            assertThrows(StorageException.class, () -> store.commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_embedded", "turn_embedded", TurnState.COMPLETED, "done", null, null,
                    "item_final", new ModelMessage(ModelRole.ASSISTANT,
                    List.of(new TextContent("done"))),
                    List.of(usageFact(1, 1, usage), usageFact(1, 1, usage)),
                    expected, now.plusSeconds(2))));
            assertEquals(TurnState.RUNNING,
                    store.findTurn("thr_embedded", "turn_embedded").orElseThrow().state());
            assertEquals(1, store.readThread("thr_embedded").orElseThrow().messages().size());
            assertEquals(0, probe.countUsage("turn_embedded"));
        } finally {
            if (Solon.context() != null) Solon.stopBlock(false, 0);
            restore("ja.data-dir", previousData);
            restore("ja.run-dir", previousRun);
        }
        try (AutoCloseable released = DatabaseLeaseProbe.acquire(data.resolve("ja.db"))) {
            assertNotNull(released);
        }
    }

    /** 恢复 process-global 属性，防止后续 embedded generation 复用本测试目录。 */
    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}
