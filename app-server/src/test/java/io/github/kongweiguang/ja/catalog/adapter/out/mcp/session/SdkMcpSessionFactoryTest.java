// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.session;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 MCP stdio 的宿主环境快照与配置覆盖采用 Windows 变量命名规则。 */
final class SdkMcpSessionFactoryTest {
    /** MCP 配置覆盖继承值时按 Windows 大小写不敏感规则替换同一变量。 */
    @Test
    void configuredValuesOverrideInheritedEnvironmentCaseInsensitively() {
        Map<String, String> host = new LinkedHashMap<>();
        host.put("Path", "C:\\host\\bin");
        host.put("USERPROFILE", "C:\\Users\\fixture");
        Map<String, String> configured = Map.of("PATH", "C:\\mcp\\bin", "MCP_SECRET", "resolved-value");

        Map<String, String> merged = SdkMcpSessionFactory.inheritedEnvironment(host, configured);

        assertEquals("C:\\mcp\\bin", valueIgnoreCase(merged, "path"));
        assertEquals("C:\\Users\\fixture", merged.get("USERPROFILE"));
        assertEquals("resolved-value", merged.get("MCP_SECRET"));
        assertEquals(1, merged.keySet().stream().filter(name -> name.equalsIgnoreCase("PATH")).count());
    }

    /** 显式配置自身出现大小写冲突时失败关闭，避免依赖 Map 遍历顺序选取值。 */
    @Test
    void configuredCaseConflictsAreRejected() {
        Map<String, String> configured = new HashMap<>();
        configured.put("Path", "C:\\first");
        configured.put("PATH", "C:\\second");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SdkMcpSessionFactory.inheritedEnvironment(Map.of("PATH", "C:\\host"), configured));

        assertEquals("mcp_stdio_environment_conflict", failure.getMessage());
    }

    /** 只按 Windows 环境名语义读取值，避免测试依赖大小写保留策略。 */
    private static String valueIgnoreCase(Map<String, String> environment, String name) {
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
