// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** request binding 只保存恢复请求路由和历史选择，不泄漏模型密钥或重复保存正文。 */
final class AgentRequestBindingCodecTest {
    /** 往返与哨兵共享当前 v3 编码样本，核验新增空 Schema 不改变历史 CAS 或泄露 Provider 凭据。 */
    @Test
    void bindingRoundTripsEmptyHistoryAndOmitsProviderSecret() {
        AgentStore.RecordWrite write = encodedBinding();
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");
        Instant acceptedAt = Instant.parse("2026-09-26T03:04:05Z");
        AgentRequest publicRequest = AgentRequest.text(identity, "the private user question", "op_a")
                .withHistory(AgentExecutionRequest.HistorySelection.explicit(List.of(), 17))
                .withAcceptedAt(acceptedAt)
                .withContextAttribute("ja.origin", "desktop");
        ModelPort.ModelConfiguration configuration = modelConfiguration();
        AgentRequestFingerprint.PreparedRequest prepared = AgentRequestFingerprint.prepare(publicRequest, configuration, "a".repeat(64));
        AgentRequestBindingCodec codec = new AgentRequestBindingCodec();

        AgentStore.StoredRecord stored = new AgentStore.StoredRecord(write.recordId(), write.recordType(),
                write.payloadSchemaVersion(), 1, write.payload(), write.occurredAt());
        AgentRequestBinding decoded = codec.decode(stored);

        assertEquals(identity, decoded.identity());
        assertEquals(AgentExecutionRequest.HistorySelection.explicit(List.of(), 17), decoded.history());
        assertEquals(Map.of("ja.origin", "desktop"), decoded.contextAttributes());
        assertEquals(acceptedAt, decoded.acceptedAt());
        assertEquals("model_a", decoded.modelSelection().modelId());
        assertEquals("a".repeat(64), decoded.runtimeBindingFingerprint());
        assertEquals(AgentInitialQueuedInputsCodec.recordId(new AgentStore.ExecutionKey(
                        new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                        identity.turnId()),
                prepared.operation().operationKey()), decoded.initialQueuedInputsRecordId());
        assertEquals(3, write.payloadSchemaVersion());
        assertEquals(Optional.empty(), decoded.outputSchema());
        String encoded = JsonValueCodecFactory.standard().encode(write.payload());
        assertFalse(encoded.contains("private user question"));
        assertFalse(encoded.contains("secret_test"));
    }

    /** 未知枚举和非法 JSON 正文不能通过 decode 的任何 cause 层回显持久文本。 */
    @Test
    void rejectsUnknownEnumsWithoutLeakingValuesThroughCauseChain() {
        AgentStore.RecordWrite write = encodedBinding();
        AgentRequestBindingCodec codec = new AgentRequestBindingCodec();
        String sentinel = "unknown-binding-enum-sentinel";
        String encoded = JsonValueCodecFactory.standard().encode(write.payload());
        List<String> payloads = List.of(
                encoded.replace("\"kind\":\"USER_INPUT\"", "\"kind\":\"" + sentinel + "\""),
                encoded.replace("\"mode\":\"EXPLICIT\"", "\"mode\":\"" + sentinel + "\""),
                encoded.replace("\"api\":\"OPENAI_RESPONSES\"", "\"api\":\"" + sentinel + "\""));

        for (String payload : payloads) {
            assertFalse(payload.equals(encoded));
            AgentStore.StoredRecord stored = new AgentStore.StoredRecord(write.recordId(), write.recordType(),
                    write.payloadSchemaVersion(), 1, JsonValueCodecFactory.standard().decodeObject(payload),
                    write.occurredAt());
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> codec.decode(stored));

            assertEquals("invalid stored agent request binding", failure.getMessage());
            assertFalse(failure.toString().contains(sentinel));
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                assertFalse(cause.toString().contains(sentinel));
            }
        }
    }

    /** 显式声明本用例无结构化终答和父上下文，避免新增可选能力改变原绑定回归的语义。 */
    private static AgentStore.RecordWrite encodedBinding() {
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");
        Instant acceptedAt = Instant.parse("2026-09-26T03:04:05Z");
        AgentRequest publicRequest = AgentRequest.text(identity, "the private user question", "op_a")
                .withHistory(AgentExecutionRequest.HistorySelection.explicit(List.of(), 17))
                .withAcceptedAt(acceptedAt)
                .withContextAttribute("ja.origin", "desktop");
        ModelPort.ModelConfiguration configuration = modelConfiguration();
        AgentRequestFingerprint.PreparedRequest prepared = AgentRequestFingerprint.prepare(publicRequest, configuration, "a".repeat(64));
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT, Optional.of(publicRequest.input()),
                prepared.inputMessageId(), Optional.empty(), prepared.operation(), publicRequest.contextAttributes(),
                publicRequest.history(), acceptedAt, List.of(), Optional.empty(), Optional.empty());
        AgentRequestRuntime runtime = runtime(configuration);
        try {
            return new AgentRequestBindingCodec().encode(request, runtime);
        } finally {
            runtime.close();
        }
    }

    /** 提供不访问真实 Provider 的 prompt/model 租约供严格 typed codec 测试使用。 */
    private static AgentRequestRuntime runtime(ModelPort.ModelConfiguration configuration) {
        ModelPort model = (ModelPort) Proxy.newProxyInstance(ModelPort.class.getClassLoader(),
                new Class<?>[]{ModelPort.class}, (proxy, method, arguments) -> null);
        AgentPromptSession prompt = (AgentPromptSession) Proxy.newProxyInstance(AgentPromptSession.class.getClassLoader(),
                new Class<?>[]{AgentPromptSession.class}, (proxy, method, arguments) -> null);
        return new AgentRequestRuntime(configuration, model, binding -> {
            throw new AssertionError("summary model is not used by binding codec tests");
        }, new ExtensionRegistry().freeze(), prompt,
                "a".repeat(64),
                io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE,
                request -> {
                    throw new AssertionError("managed attachment is not used by binding codec tests");
                }, new ToolProjectionLimits(12_000, 2_000), Map.of(), () -> { });
    }

    /** 使用合规测试凭据构建持久化模型选择样本。 */
    private static ModelPort.ModelConfiguration modelConfiguration() {
        return new ModelPort.ModelConfiguration("provider_a", "model_a", "cfg_a", ModelPort.Api.OPENAI_RESPONSES,
                "upstream_a", URI.create("https://example.invalid"), "secret_test", Duration.ofSeconds(2),
                Duration.ofSeconds(4), Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
    }
}
