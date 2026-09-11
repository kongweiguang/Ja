// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.discovery;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** MCP 发现只缩小模型声明面；完整执行目录和权限仍由请求运行时拥有。 */
public final class McpToolExposure {
    private static final int DIRECT_TOOLS = 8;
    private static final int DIRECT_BYTES = 12_000;
    private static final int RECENT_TOOLS = 10;
    private static final int RECENT_BYTES = 24_000;
    private static final int RESULT_CHARACTERS = 16_000;
    private static final Pattern PROJECTION_FOOTER = Pattern.compile(
            "\\n\\[characters=[0-9]+ sha256=[a-f0-9]{64}\\](?:\\n\\[exit_code=-?[0-9]+\\])?");

    /** 静态策略没有跨 Thread 状态，恢复只依赖当前目录与已持久化的真实搜索结果。 */
    private McpToolExposure() { }

    /** 按数量和体积同时判断，少量巨大 Schema 也不能无条件占满上下文。 */
    public static List<AgentTool> catalog(List<AgentTool> tools, JsonValueCodec codec) {
        List<AgentTool> frozen = List.copyOf(tools);
        List<AgentTool> mcp = frozen.stream().filter(McpToolExposure::isMcp).toList();
        if (mcp.isEmpty()) return frozen;
        long bytes = mcp.stream().mapToLong(tool -> size(tool.spec())).sum();
        if (mcp.size() <= DIRECT_TOOLS && bytes <= DIRECT_BYTES) return frozen;
        if (frozen.stream().anyMatch(tool -> McpToolSearch.NAME.equals(tool.spec().name()))) {
            throw new IllegalArgumentException("reserved MCP discovery tool name");
        }
        List<AgentTool> result = new ArrayList<>(frozen);
        result.add(new McpToolSearch(mcp, codec));
        return List.copyOf(result);
    }

    /**
     * 计量与发送共用同一纯投影；只接受配对成功结果，普通文本不能伪造加载事实。
     * 不持久化第二份激活集合，历史压缩后搜索入口仍在，模型可以重新发现。
     */
    public static List<ToolSpec> modelTools(List<AgentTool> catalog,
                                            List<ContextMessage> messages, JsonValueCodec codec) {
        Objects.requireNonNull(codec, "codec");
        if (catalog.stream().noneMatch(McpToolSearch.class::isInstance)) {
            return catalog.stream().map(AgentTool::spec).toList();
        }
        Map<String, AgentTool> available = new HashMap<>();
        for (AgentTool tool : catalog) {
            if (isMcp(tool)) available.put(tool.spec().name(), tool);
        }
        LinkedHashMap<String, AgentTool> recent = new LinkedHashMap<>();
        Set<String> latestPage = Set.of();
        Map<String, String> calls = new HashMap<>();
        for (ContextMessage message : messages) {
            for (ContextMessage.Block block : message.blocks()) {
                if (message.role() == ContextMessage.Role.ASSISTANT
                        && block instanceof ContextMessage.ToolCallBlock call) {
                    calls.put(call.callId(), call.name());
                } else if (message.role() == ContextMessage.Role.TOOL
                        && block instanceof ContextMessage.ToolResultBlock result
                        && McpToolSearch.NAME.equals(calls.remove(result.callId()))
                        && McpToolSearch.NAME.equals(result.name()) && result.output().error() == null) {
                    Set<String> page = remember(result.output(), available, recent, codec);
                    if (!page.isEmpty()) latestPage = page;
                }
            }
        }
        Map<String, AgentTool> selected = boundedRecent(recent, latestPage);
        return catalog.stream().filter(tool -> !isMcp(tool) || selected.containsKey(tool.spec().name()))
                .map(AgentTool::spec).toList();
    }

    /** 只使用显式路由类型，不能把恰好带 mcp 前缀的内建工具误当扩展。 */
    private static boolean isMcp(AgentTool tool) {
        return tool.bindingDescriptor().routeKind() == AgentTool.RouteKind.MCP;
    }

    /**
     * 只剥离投影器明确添加的尾注；截断、损坏或过期结果不恢复，下一次搜索自然修复。
     * Schema 和路由摘要都要匹配，防止同名服务器被替换后复用旧搜索授权的错觉。
     */
    private static Set<String> remember(ContextMessage.ToolOutput output, Map<String, AgentTool> available,
                                 LinkedHashMap<String, AgentTool> recent, JsonValueCodec codec) {
        String content = output.content();
        if (content.length() > RESULT_CHARACTERS) return Set.of();
        if (output.promptProjection()) {
            int footer = content.lastIndexOf("\n[characters=");
            if (footer < 0 || !PROJECTION_FOOTER.matcher(content.substring(footer)).matches()
                    || content.contains("\n[tool-output-truncated")
                    || content.startsWith("[tool-output-artifact-only")) return Set.of();
            content = content.substring(0, footer);
        }
        JsonObject document;
        try {
            document = codec.decodeObject(content);
        } catch (IllegalArgumentException failure) {
            return Set.of();
        }
        if (!(document.get("tools") instanceof JsonArray entries)
                || entries.values().size() > McpToolSearch.PAGE_SIZE) return Set.of();
        Set<String> page = new java.util.HashSet<>();
        for (JsonValue value : entries.values()) {
            if (!(value instanceof JsonObject entry) || !(entry.get("name") instanceof JsonText name)) continue;
            AgentTool tool = available.get(name.value());
            if (tool == null) continue;
            AgentTool.ToolBindingDescriptor binding = tool.bindingDescriptor();
            if (!matches(entry, "schemaHash", binding.schemaHash())
                    || !matches(entry, "routeHash", binding.routeHash())) continue;
            recent.remove(name.value());
            recent.put(name.value(), tool);
            page.add(name.value());
            while (recent.size() > RECENT_TOOLS) recent.remove(recent.firstEntry().getKey());
        }
        return Set.copyOf(page);
    }

    /** 摘要必须是完整严格字符串，不将缺失值或其它 JSON 类型强转为路由凭据。 */
    private static boolean matches(JsonObject entry, String key, String expected) {
        return entry.get(key) instanceof JsonText text && expected.equals(text.value());
    }

    /** 最近一页保证全部可见；更旧结果按字节预算淘汰，单个大 Schema 交给真实模型预算检查。 */
    private static Map<String, AgentTool> boundedRecent(LinkedHashMap<String, AgentTool> recent,
                                                      Set<String> latestPage) {
        Map<String, AgentTool> selected = new HashMap<>();
        long bytes = 0;
        for (Map.Entry<String, AgentTool> entry : recent.reversed().entrySet()) {
            long next = size(entry.getValue().spec());
            if (!latestPage.contains(entry.getKey()) && bytes + next > RECENT_BYTES) continue;
            selected.put(entry.getKey(), entry.getValue());
            bytes += next;
        }
        return selected;
    }

    /** UTF-8 体积只做自动分流，不冒充 Provider 的精确 token 计量。 */
    private static long size(ToolSpec spec) {
        return (long) spec.name().getBytes(StandardCharsets.UTF_8).length
                + spec.description().getBytes(StandardCharsets.UTF_8).length
                + AgentTool.canonicalSchema(spec.inputSchema()).getBytes(StandardCharsets.UTF_8).length;
    }
}
