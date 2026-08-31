// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.support;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;

import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockResponseBody;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import okio.BufferedSink;

/** 共享 loopback fixture 在不产生付费调用的前提下覆盖 Ja 严格 Provider Codec。 */
public final class ModelAdapterTestSupport {
    public static final String SYSTEM_PROMPT = """
            You are Ja, a coding agent.

            Work in the user's workspace with the available tools.
            Be concise, follow applicable workspace guidance, verify material changes, and report results truthfully.

            <environment>
            Environment: Windows 11
            </environment>""";

    /** 禁止创建全局可变支持实例，避免测试 fixture 在用例之间泄漏。 */
    private ModelAdapterTestSupport() {
    }

    /** 构造短且确定性超时的 Provider/Model 快照，并使用 loopback 端点。 */
    public static ModelPort.ModelConfiguration configuration(URI baseUri, ModelPort.Provider provider,
                                                       ModelPort.Api api, Duration timeout) {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test", provider, api, "test-model",
                baseUri, "test-secret", Duration.ofSeconds(2), timeout,
                java.util.Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(
                        api == ModelPort.Api.OPENAI_RESPONSES ? 0.2 : null,
                        api == ModelPort.Api.OPENAI_RESPONSES ? 0.9 : null,
                        1024, "medium"));
    }

    /** 只移除凭据，使 loopback 测试证明占位 Key 不会离开 Adapter。 */
    public static ModelPort.ModelConfiguration withoutCredential(ModelPort.ModelConfiguration configuration) {
        return new ModelPort.ModelConfiguration(
                configuration.providerId(), configuration.modelId(), configuration.configGeneration(),
                configuration.provider(),
                configuration.api(), configuration.model(), configuration.baseUri(), "",
                configuration.connectTimeout(), configuration.requestTimeout(),
                configuration.inputModalities(), configuration.generation());
    }

    /** 构造包含用户文本和严格 fixture Tool Schema 的请求。 */
    public static ModelPort.ModelRequest request(ModelPort.ModelConfiguration configuration) {
        ToolSpec tool = new ToolSpec("read_file", "Read one file", JsonObjects.builder()
                .putText("type", "object")
                .put("properties", JsonObjects.builder()
                        .put("path", JsonObjects.builder().putText("type", "string").putNumber("minLength", 1)
                                .build())
                        .build())
                .put("required", new JsonArray(List.of(new JsonText("path"))))
                .putBoolean("additionalProperties", false)
                .build());
        return new ModelPort.ModelRequest(configuration,
                new ModelPort.PromptPayload(SYSTEM_PROMPT, "prompt-revision-1"),
                List.of(new ModelMessage(ModelRole.USER,
                        List.of(new TextContent("你好, model")))),
                List.of(tool), null, 1);
    }

    /** 构造 Ja 严格 OpenAI 流校验要求的完整 Response 形状。 */
    public static String openAiResponse(String id, String status) {
        return openAiResponse(id, status, null);
    }

    /** 在保留全部必填强类型 Response 字段的同时加入精确 Token 计量。 */
    public static String openAiResponse(String id, String status, ModelUsage usage) {
        return openAiResponse(id, status, usage, "[]");
    }

    /** 加入权威最终输出，使流式 Tool fixture 能按失败关闭原则完成对账。 */
    public static String openAiResponse(String id, String status, ModelUsage usage, String outputJson) {
        String usageJson = usage == null ? "" : ",\"usage\":{"
                + "\"input_tokens\":" + usage.inputTokens()
                + ",\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0}"
                + ",\"output_tokens\":" + usage.outputTokens()
                + ",\"output_tokens_details\":{\"reasoning_tokens\":0}"
                + ",\"total_tokens\":" + usage.totalTokens() + "}";
        return "{\"id\":\"" + id + "\",\"created_at\":0.0,\"model\":\"test-model\","
                + "\"object\":\"response\",\"output\":" + outputJson
                + ",\"parallel_tool_calls\":true,"
                + "\"tool_choice\":\"auto\",\"tools\":[],\"status\":\"" + status + "\""
                + usageJson + "}";
    }

    /** 按语义比较完整请求文档，避免字段顺序削弱 wire fixture。 */
    public static void assertJsonEquals(String expected, String actual) throws IOException {
        org.junit.jupiter.api.Assertions.assertEquals(
                AbstractStreamingModelAdapter.JSON.readTree(expected),
                AbstractStreamingModelAdapter.JSON.readTree(actual));
    }

    /** 以任意字节分块写入 SSE 响应，覆盖 UTF-8 和分帧边界。 */
    public static void sse(Exchange exchange, String body, int chunkSize) {
        exchange.responseBuilder.code(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .chunkedBody(body, chunkSize);
    }

    /** 发送空状态响应，使重试测试能够控制瞬时 HTTP 结果。 */
    public static void status(Exchange exchange, int status) {
        status(exchange, status, null);
    }

    /** 发送带有界 Retry-After 提示且不含正文的状态响应。 */
    public static void status(Exchange exchange, int status, String retryAfter) {
        exchange.responseBuilder.code(status);
        if (retryAfter != null) exchange.responseBuilder.setHeader("Retry-After", retryAfter);
    }

    /** 发送强类型 Provider 错误正文，以便不进行付费调用即可测试状态映射。 */
    public static void json(Exchange exchange, int status, String body) {
        exchange.responseBuilder.code(status)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .body(body);
    }

    /** 返回已发送响应头但持续等待客户端取消的 SSE 正文，覆盖真实 socket 清理。 */
    public static void stallingSse(Exchange exchange, CountDownLatch responseStarted) {
        exchange.responseBuilder.code(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .body(new StallingBody(responseStarted));
    }

    /** 记录请求次数，并将每次真实 loopback 交换交给测试持有的 Responder。 */
    public static final class Loopback implements AutoCloseable {
        private final MockWebServer server;
        private final AtomicInteger calls = new AtomicInteger();

        /** 启动临时 IPv4 loopback 服务，以满足生产 base URI 限制规则。 */
        public Loopback(Responder responder) throws IOException {
            server = new MockWebServer();
            server.setDispatcher(new Dispatcher() {
                /** 在 MockWebServer 工作线程中把真实请求适配为最小 Ja fixture 交换。 */
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    Exchange exchange = new Exchange(request);
                    try {
                        responder.respond(calls.incrementAndGet(), exchange);
                        return exchange.responseBuilder.build();
                    } catch (IOException failure) {
                        return new MockResponse.Builder().code(500).build();
                    }
                }
            });
            server.start(InetAddress.getByName("127.0.0.1"), 0);
        }

        /** 返回仅含 authority 的基址，使 Adapter 端点解析仍处于测试覆盖内。 */
        public URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getPort());
        }

        /** 返回真实 HTTP 尝试次数，用于重试门禁断言。 */
        public int calls() {
            return calls.get();
        }

        /** 停止进程内服务，避免执行器线程残留到后续测试。 */
        @Override
        public void close() {
            server.close();
        }
    }

    /** 测试 Responder 接收从一开始的尝试序号和 MockWebServer 请求适配器。 */
    @FunctionalInterface
    public interface Responder {
        /** 在不访问外部网络的前提下生成一个有界 fixture 响应。 */
        void respond(int call, Exchange exchange) throws IOException;
    }

    /** 只暴露现有 Provider 测试所需的请求视图与成熟服务响应 Builder。 */
    public static final class Exchange {
        private final RecordedRequest request;
        private final MockResponse.Builder responseBuilder = new MockResponse.Builder();

        /** 保存不可变请求；响应只能通过同一次 dispatch 的 Builder 构造。 */
        private Exchange(RecordedRequest request) {
            this.request = request;
        }

        /** 保留既有测试的单值 Header 读取语义。 */
        public RequestHeaders getRequestHeaders() {
            return new RequestHeaders(request.getHeaders());
        }

        /** 返回 MockWebServer 解析后的精确请求 URI。 */
        public URI getRequestURI() {
            return request.getUrl().uri();
        }

        /** 为断言提供完整已缓存请求体，不持有真实 socket 输入流。 */
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(request.getBody().toByteArray());
        }
    }

    /** 最小 Header 视图保持旧测试可读性，但底层已由 OkHttp 完成规范化。 */
    public record RequestHeaders(Headers headers) {
        /** 返回首个同名值；不存在时返回 null。 */
        public String getFirst(String name) {
            return headers.get(name);
        }
    }

    /** 持续输出心跳直到 OkHttp 取消关闭连接，避免测试线程手搓 HTTP 服务生命周期。 */
    private record StallingBody(CountDownLatch responseStarted) implements MockResponseBody {
        /** 未知长度强制采用流式正文，不提前缓冲测试数据。 */
        @Override
        public long getContentLength() {
            return -1;
        }

        /** 首次写入即发布响应已开始证据，随后只等待客户端关闭 socket。 */
        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            responseStarted.countDown();
            while (!Thread.currentThread().isInterrupted()) {
                sink.writeUtf8(": keepalive\r\n\r\n");
                sink.flush();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 可变一次性 Token 用于校验清理登记和活动流中断。 */
    public static final class TestCancellation implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

        /** 在调用回调前设置单调标记，使每个回调都能观察到取消。 */
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            callbacks.forEach(Runnable::run);
        }

        /** 返回保留的回调数量，使完成屏障测试能够发现生命周期泄漏。 */
        public int callbackCount() {
            return callbacks.size();
        }

        /** 提供协作式 Adapter 边界所需的单调取消状态。 */
        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        /** 提供不含 Provider 或 secret 内容的稳定原因。 */
        @Override
        public Optional<String> reason() {
            return cancelled.get() ? Optional.of("test cancellation") : Optional.empty();
        }

        /** 以竞态安全方式登记回调，并返回幂等移除操作。 */
        @Override
        public Registration onCancellation(Runnable callback) {
            if (cancelled.get()) {
                callback.run();
                return Registration.noop();
            }
            callbacks.add(callback);
            if (cancelled.get() && callbacks.remove(callback)) callback.run();
            return () -> callbacks.remove(callback);
        }
    }
}

