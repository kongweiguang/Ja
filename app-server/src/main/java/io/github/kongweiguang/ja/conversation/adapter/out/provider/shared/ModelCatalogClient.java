// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 以 Provider 中立的方式读取上游 OpenAI 形状的模型目录。该客户端只处理已保存配置代际中的
 * 最小目录请求，不复用聊天 Codec 或把厂商原始响应对象带出 Java。
 */
public final class ModelCatalogClient {
    private static final int MAX_MODELS = 200;
    private static final int MAX_RESPONSE_BYTES = 1_024 * 1_024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 静态目录客户端不拥有 HTTP、线程或凭据资源；禁止实例化以确保这些资源继续由
     * {@link ModelTransport} 的 composition owner 统一管理和关闭。
     */
    private ModelCatalogClient() {
        // 静态无状态客户端只复用由 composition owner 管理的 ModelTransport。
    }

    /**
     * 在共享传输资源上启动一次不重试的目录读取。用户的显式点击可以再次尝试，但单击期间不
     * 自动重放请求，避免目录端点的供应商限流被隐式放大。
     */
    public static CompletionStage<ModelPort.ModelDiscoveryResult> discover(
            ModelTransport transport, ModelPort.ModelDiscoveryRequest request,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        RequestController controller = new RequestController(cancellationToken);
        transport.register(controller);
        CompletableFuture<ModelPort.ModelDiscoveryResult> result;
        ScheduledFuture<?> timeout;
        CancellationToken.Registration registration;
        try {
            result = CompletableFuture.supplyAsync(() -> execute(transport, request, controller),
                    transport.requestExecutor());
            timeout = transport.deadlineExecutor().schedule(controller::timeout,
                    request.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
            registration = cancellationToken.onCancellation(controller::cancel);
        } catch (RuntimeException failure) {
            controller.shutdown();
            transport.unregister(controller);
            throw failure;
        }
        return result.whenComplete((ignored, failure) -> {
            timeout.cancel(false);
            registration.close();
            controller.complete();
            transport.unregister(controller);
        });
    }

    /**
     * 执行单次 HTTP 交换并将有界 JSON 映射为稳定目录。HTTP 状态和原始正文永远不离开
     * adapter，调用方只得到固定的可重试或协议错误类别。
     */
    private static ModelPort.ModelDiscoveryResult execute(
            ModelTransport transport, ModelPort.ModelDiscoveryRequest request,
            RequestController controller) {
        controller.bindThread(Thread.currentThread());
        controller.throwIfStopped();
        Request httpRequest = requestFor(request);
        Call call = transport.clientFor(request.connectTimeout(), request.requestTimeout()).newCall(httpRequest);
        controller.bindCall(call);
        try (Response response = call.execute()) {
            controller.bindStream(response);
            controller.throwIfStopped();
            if (!response.isSuccessful()) {
                throw new ProviderProtocolException("MODEL_DISCOVERY_HTTP",
                        "provider model list is unavailable", response.code() >= 500);
            }
            return decode(readBody(response), controller);
        } catch (IOException failure) {
            controller.throwIfStopped();
            throw new ProviderProtocolException("MODEL_DISCOVERY_NETWORK",
                    "provider model list is unavailable", true, failure);
        } finally {
            controller.clearCall(call);
        }
    }

    /**
     * 保留用户配置的反向代理路径，并只在路径末端缺失时补入 v1，保证 `base_url` 是否已含 `/v1`
     * 都会收敛到同一个 `/v1/models` 端点而不会丢失路径前缀。
     */
    private static Request requestFor(ModelPort.ModelDiscoveryRequest request) {
        Request.Builder builder = new Request.Builder().url(modelsEndpoint(request.baseUri()).toString())
                .get().header("Accept", "application/json");
        if (request.api() == ModelPort.Api.ANTHROPIC_MESSAGES) {
            builder.header("x-api-key", request.apiKey()).header("anthropic-version", "2023-06-01");
        } else {
            builder.header("Authorization", "Bearer " + request.apiKey());
        }
        return builder.build();
    }

    /**
     * 以 URI 构造器合并端点而不是字符串拼接，避免 IPv6、端口和编码路径在路径归一化时丢失。
     */
    private static URI modelsEndpoint(URI baseUri) {
        String basePath = baseUri.getPath();
        String normalized = basePath == null || basePath.isBlank() ? "" : basePath.replaceAll("/+$", "");
        String path = normalized.endsWith("/v1") ? normalized + "/models" : normalized + "/v1/models";
        try {
            return new URI(baseUri.getScheme(), null, baseUri.getHost(), baseUri.getPort(), path, null, null);
        } catch (URISyntaxException failure) {
            throw new ProviderProtocolException("MODEL_DISCOVERY_ENDPOINT",
                    "provider model list is unavailable", false, failure);
        }
    }

    /**
     * 对 JSON 响应施加明确的字节上限，防止模型目录被错误或恶意上游扩大为无界堆内存占用。
     */
    private static byte[] readBody(Response response) throws IOException {
        if (response.body() == null) {
            throw new ProviderProtocolException("MODEL_DISCOVERY_PROTOCOL",
                    "provider model list is invalid", false);
        }
        try (InputStream input = response.body().byteStream()) {
            byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new ProviderProtocolException("MODEL_DISCOVERY_LIMIT",
                        "provider model list is invalid", false);
            }
            return body;
        }
    }

    /**
     * 只接受 OpenAI 风格 `{data:[{id:string}]}`；排序和去重由 Java 完成，使 UI 重复点击时得到
     * 可预测的增量合并结果，未分页的额外项通过 `truncated` 明确告知用户。
     */
    private static ModelPort.ModelDiscoveryResult decode(byte[] body, RequestController controller) {
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (!data.isArray()) {
                throw invalidPayload();
            }
            TreeSet<String> all = new TreeSet<>();
            for (JsonNode entry : data) {
                controller.throwIfStopped();
                JsonNode id = entry.path("id");
                if (!id.isTextual() || !validModelId(id.textValue())) {
                    throw invalidPayload();
                }
                all.add(id.textValue());
            }
            boolean truncated = all.size() > MAX_MODELS;
            List<String> items = new ArrayList<>(Math.min(all.size(), MAX_MODELS));
            all.stream().limit(MAX_MODELS).forEach(items::add);
            return new ModelPort.ModelDiscoveryResult(items, truncated);
        } catch (IOException failure) {
            throw new ProviderProtocolException("MODEL_DISCOVERY_PROTOCOL",
                    "provider model list is invalid", false, failure);
        }
    }

    /**
     * 模型标识只允许可展示的有界文本；不复用 Ja 的 modelId 语法，因上游合法名称可以包含 `/`。
     */
    private static boolean validModelId(String value) {
        return value != null && !value.isBlank() && value.length() <= 512
                && value.chars().noneMatch(Character::isISOControl);
    }

    /**
     * 用统一且不含供应商正文的协议错误结束畸形目录，避免不同 JSON 解析分支泄露差异化诊断。
     */
    private static ProviderProtocolException invalidPayload() {
        return new ProviderProtocolException("MODEL_DISCOVERY_PROTOCOL",
                "provider model list is invalid", false);
    }
}
