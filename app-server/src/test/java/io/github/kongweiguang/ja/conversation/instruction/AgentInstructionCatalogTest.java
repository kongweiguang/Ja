// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.instruction;

import io.github.kongweiguang.ja.conversation.port.out.InstructionScopeRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖 AGENTS 优先级、懒发现、安全失败与 24KiB UTF-8 预算。 */
final class AgentInstructionCatalogTest {
    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir Path temp;

    /** global override 覆盖默认文件，项目规则按 Git root 到 workspace 顺序追加。 */
    @Test
    void loadsGlobalOverrideAndTrustedProjectChainInOrder() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        write(home.resolve("AGENTS.md"), "global-default");
        write(home.resolve("AGENTS.override.md"), "global-override");
        Path root = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectory(root.resolve(".git"));
        write(root.resolve("AGENTS.md"), "root-guidance");
        Path workspace = Files.createDirectories(root.resolve("module"));
        write(workspace.resolve("AGENTS.md"), "module-guidance");

        AgentInstructionCatalog.Session session = catalog(new MemoryScopes()).open(
                new AgentInstructionCatalog.SessionRequest("thr_one", workspace, home, true));
        String guidance = session.snapshot().guidance();

        assertTrue(guidance.contains("global-override"));
        assertFalse(guidance.contains("global-default"));
        assertTrue(guidance.indexOf("root-guidance") < guidance.indexOf("module-guidance"));
        assertFalse(session.snapshot().unsafe());
    }

    /** untrusted 只加载全局规则；合并 trust 诊断由同时掌握 Workspace Skills 的上层 Session 生成。 */
    @Test
    void skipsProjectRulesForUntrustedWorkspace() throws Exception {
        Path home = Files.createDirectories(temp.resolve("untrusted-home"));
        write(home.resolve("AGENTS.md"), "global-guidance");
        Path workspace = Files.createDirectories(temp.resolve("untrusted-workspace"));
        write(workspace.resolve("AGENTS.md"), "project-secret-guidance");

        AgentInstructionCatalog.Snapshot snapshot = catalog(new MemoryScopes()).open(
                new AgentInstructionCatalog.SessionRequest("thr_untrusted", workspace, home, false)).snapshot();

        assertTrue(snapshot.guidance().contains("global-guidance"));
        assertFalse(snapshot.guidance().contains("project-secret-guidance"));
        assertTrue(snapshot.diagnostics().isEmpty());
    }

    /** read 发现嵌套规则可继续，write 必须刷新；删除保留 scope，重建 Session 后重新出现可恢复。 */
    @Test
    void discoversNestedScopesAndRestoresThemAcrossSessions() throws Exception {
        MemoryScopes scopes = new MemoryScopes();
        Path home = Files.createDirectories(temp.resolve("nested-home"));
        Path workspace = Files.createDirectories(temp.resolve("nested-workspace"));
        Path nested = Files.createDirectories(workspace.resolve("src/deep"));
        Path agents = nested.resolve("AGENTS.md");
        write(agents, "nested-v1");
        AgentInstructionCatalog catalog = catalog(scopes);
        AgentInstructionCatalog.Session first = catalog.open(
                new AgentInstructionCatalog.SessionRequest("thr_nested", workspace, home, true));

        AgentInstructionCatalog.Preflight read = first.preflight("read", "src/deep/file.txt");
        assertEquals(AgentInstructionCatalog.Decision.CONTINUE, read.decision());
        assertTrue(read.revisionChanged());
        assertTrue(read.snapshot().guidance().contains("nested-v1"));
        write(agents, "nested-v2");
        AgentInstructionCatalog.Preflight write = first.preflight("write", "src/deep/file.txt");
        assertEquals(AgentInstructionCatalog.Decision.REFRESH_REQUIRED, write.decision());

        Files.delete(agents);
        assertFalse(first.refresh().guidance().contains("nested-v2"));
        assertEquals(List.of("src/deep"), scopes.list("thr_nested"));
        write(agents, "nested-v3");
        AgentInstructionCatalog.Session restored = catalog.open(
                new AgentInstructionCatalog.SessionRequest("thr_nested", workspace, home, true));
        assertTrue(restored.snapshot().guidance().contains("nested-v3"));
    }

    /** Windows 大小写不敏感也必须忽略非精确名称；损坏 UTF-8 则令副作用 fail closed。 */
    @Test
    void enforcesExactNameAndStrictUtf8() throws Exception {
        Path home = Files.createDirectories(temp.resolve("utf8-home"));
        Path workspace = Files.createDirectories(temp.resolve("utf8-workspace"));
        write(workspace.resolve("agents.md"), "wrong-case");
        AgentInstructionCatalog catalog = catalog(new MemoryScopes());
        AgentInstructionCatalog.Session ignored = catalog.open(
                new AgentInstructionCatalog.SessionRequest("thr_case", workspace, home, true));
        assertFalse(ignored.snapshot().guidance().contains("wrong-case"));

        Files.delete(workspace.resolve("agents.md"));
        Files.write(workspace.resolve("AGENTS.md"), new byte[]{(byte) 0xc3, (byte) 0x28});
        AgentInstructionCatalog.Session invalid = catalog.open(
                new AgentInstructionCatalog.SessionRequest("thr_utf8", workspace, home, true));
        assertTrue(invalid.snapshot().unsafe());
        assertEquals(AgentInstructionCatalog.Decision.CONTINUE,
                invalid.preflight("read", "file.txt").decision());
        assertEquals(AgentInstructionCatalog.Decision.UNSAFE,
                invalid.preflight("write", "file.txt").decision());
        assertTrue(invalid.snapshot().diagnostics().stream()
                .anyMatch(value -> value.startsWith("AGENTS_INVALID_UTF8")));
    }

    /** symlink/reparse 不能把 AGENTS 指向边界外；无创建权限的平台显式跳过该能力断言。 */
    @Test
    void rejectsInstructionSymlinkOutsideBoundary() throws Exception {
        Path home = Files.createDirectories(temp.resolve("link-home"));
        Path workspace = Files.createDirectories(temp.resolve("link-workspace"));
        Path outside = temp.resolve("outside-agents.md");
        write(outside, "outside");
        try {
            Files.createSymbolicLink(workspace.resolve("AGENTS.md"), outside);
        } catch (IOException | UnsupportedOperationException failure) {
            Assumptions.abort("platform cannot create test symlink");
        }

        AgentInstructionCatalog.Snapshot snapshot = catalog(new MemoryScopes()).open(
                new AgentInstructionCatalog.SessionRequest("thr_link", workspace, home, true)).snapshot();

        assertTrue(snapshot.unsafe());
        assertFalse(snapshot.guidance().contains("outside"));
    }

    /** Windows junction 指向 workspace 外目录时必须在读取其中 AGENTS 前触发 realpath containment。 */
    @Test
    void rejectsNestedJunctionOutsideWorkspace() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        Path home = Files.createDirectories(temp.resolve("junction-home"));
        Path workspace = Files.createDirectories(temp.resolve("junction-workspace"));
        Path outside = Files.createDirectories(temp.resolve("junction-outside"));
        write(outside.resolve("AGENTS.md"), "outside-junction-guidance");
        Path junction = workspace.resolve("linked");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        Assumptions.assumeTrue(exitCode == 0, "cannot create junction fixture: " + output);
        try {
            AgentInstructionCatalog.Session session = catalog(new MemoryScopes()).open(
                    new AgentInstructionCatalog.SessionRequest("thr_junction", workspace, home, true));
            AgentInstructionCatalog.Preflight preflight = session.preflight("read", "linked/file.txt");

            assertEquals(AgentInstructionCatalog.Decision.CONTINUE, preflight.decision());
            assertTrue(preflight.snapshot().unsafe());
            assertFalse(preflight.snapshot().guidance().contains("outside-junction-guidance"));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    /** 超预算时先丢 global/root，保留最具体规则并按 code point 截断到联合 24KiB。 */
    @Test
    void preservesMostSpecificGuidanceWithinUtf8Budget() throws Exception {
        Path home = Files.createDirectories(temp.resolve("budget-home"));
        write(home.resolve("AGENTS.md"), "global-" + "甲".repeat(10_000));
        Path root = Files.createDirectories(temp.resolve("budget-repo"));
        Files.createDirectory(root.resolve(".git"));
        write(root.resolve("AGENTS.md"), "root-" + "乙".repeat(10_000));
        Path workspace = Files.createDirectories(root.resolve("module"));
        write(workspace.resolve("AGENTS.md"), "specific-token-" + "丙".repeat(10_000));

        AgentInstructionCatalog.Snapshot snapshot = catalog(new MemoryScopes()).open(
                new AgentInstructionCatalog.SessionRequest("thr_budget", workspace, home, true)).snapshot();
        int totalBytes = snapshot.guidance().getBytes(StandardCharsets.UTF_8).length
                + snapshot.diagnostics().stream()
                .mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();

        assertTrue(snapshot.guidance().contains("specific-token"));
        assertFalse(snapshot.guidance().contains("global-"));
        assertTrue(snapshot.diagnostics().stream().anyMatch(value -> value.startsWith("AGENTS_BUDGET_OMITTED")));
        assertTrue(totalBytes <= AgentInstructionCatalog.GUIDANCE_BUDGET_BYTES);
        assertFalse(snapshot.guidance().contains("\ufffd"));
    }

    /** refresh 只在真实内容变化时推进 revision，缺失 Ja home 是正常空配置。 */
    @Test
    void keepsRevisionStableWithoutFileChangesAndAllowsMissingJaHome() throws Exception {
        Path missingHome = temp.resolve("missing-home");
        Path workspace = Files.createDirectories(temp.resolve("stable-workspace"));
        write(workspace.resolve("AGENTS.md"), "stable");
        AgentInstructionCatalog.Session session = catalog(new MemoryScopes()).open(
                new AgentInstructionCatalog.SessionRequest("thr_stable", workspace, missingHome, true));
        String first = session.snapshot().revision();

        assertEquals(first, session.refresh().revision());
        assertFalse(session.snapshot().unsafe());
        write(workspace.resolve("AGENTS.md"), "changed");
        assertNotEquals(first, session.refresh().revision());
    }

    /** 构建使用固定时钟的 catalog，scope 时间断言不受本机影响。 */
    private static AgentInstructionCatalog catalog(InstructionScopeRepository scopes) {
        return new AgentInstructionCatalog(scopes, CLOCK);
    }

    /** 测试文件只写入临时目录，不触碰仓库或用户 Ja home。 */
    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /** 线程安全需求由 Session 同步和 SQLite 测试覆盖；该 fake 仅验证发现语义。 */
    private static final class MemoryScopes implements InstructionScopeRepository {
        private final Map<String, List<String>> values = new LinkedHashMap<>();

        /** 返回复制列表，模拟仓储不泄露事务内集合。 */
        @Override
        public List<String> list(String threadId) {
            return List.copyOf(values.getOrDefault(threadId, List.of()));
        }

        /** 按生产相同的 256 上限执行幂等登记。 */
        @Override
        public Registration register(String threadId, String relativeDirectory, Instant discoveredAt) {
            List<String> existing = values.computeIfAbsent(threadId, ignored -> new ArrayList<>());
            if (existing.contains(relativeDirectory)) return Registration.ALREADY_PRESENT;
            if (existing.size() >= 256) return Registration.LIMIT_REACHED;
            existing.add(relativeDirectory);
            return Registration.REGISTERED;
        }
    }
}
