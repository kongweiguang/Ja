// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.port.in.InteractionUseCase;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 问答恢复依赖连接观察的先注册后快照语义，不能由单纯 DTO 测试替代。 */
class InteractionObservationTest {
    /** 快照内触发并发事件，证明注册间隙的请求不会丢失，取消观察不会取消问题。 */
    @Test void registersBeforeReadingAndReleasesOnlyItsOwnObservation() {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger released = new AtomicInteger();
        List<Consumer<InteractionEvent>> subscribers = new ArrayList<>();
        InteractionUseCase interactions = (InteractionUseCase) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{InteractionUseCase.class}, (proxy, method, args) -> {
                    if (method.getName().equals("subscribe")) {
                        @SuppressWarnings("unchecked") Consumer<InteractionEvent> observer = (Consumer<InteractionEvent>) args[1];
                        subscribers.add(observer);
                        return (AutoCloseable) () -> { subscribers.remove(observer); released.incrementAndGet(); };
                    }
                    if (method.getName().equals("read")) {
                        assertFalse(subscribers.isEmpty());
                        for (Consumer<InteractionEvent> observer : List.copyOf(subscribers)) {
                            observer.accept(new InteractionEvent("thr_observed", "interaction_observed", 0, 1,
                                    InteractionEvent.Kind.CREATED, Instant.parse("2026-09-10T00:00:00Z")));
                        }
                        return Optional.of(new InteractionSnapshot("thr_observed", 1, Optional.empty(), Optional.empty()));
                    }
                    throw new AssertionError("unexpected Interaction mutation: " + method.getName());
                });
        RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null, null);
        RpcServiceBindings services = new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(),
                base.threads(), base.turns(), base.compactions(), base.approvals(), base.catalog(),
                base.attachments(), base.attachmentPreviews(), base.tasks(), base.goals(), interactions, base.lifecycle());
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-observation-test").toAbsolutePath();
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                     root.resolve("run"), root.resolve("logs")), mapper, Clock.systemUTC(), writer,
                     ignored -> services, TestConfigurationPorts.unavailable())) {
            session.initialize();
            RpcRuntimeTestAccess.markReady(session, "0123456789abcdef0123456789abcdef");
            String observation = session.observeInteraction("thr_observed").path("observationId").asText();
            session.unobserveInteraction(observation);
            session.unobserveInteraction(observation);
            assertEquals(1, released.get());
            assertTrue(subscribers.isEmpty());
        }
        assertEquals(1, released.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("interaction/changed"));
    }
}
