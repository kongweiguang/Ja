// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证极简 Tool 集及 Workspace 外路径语义。 */
class BuiltInToolsTest {
    @TempDir Path temporary;

    /** Shell 可用时四个内置名称固定，write/read/edit 可通过绝对路径和 .. 访问隔离外部目录。 */
    @Test
    void exposesFourToolsAndAllowsPathsOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), snapshot(), shellCapability(),
                promptSession(), unusedAttachments());
        assertEquals(List.of("edit", "read", "read_attachment", "shell", "write"),
                registry.snapshot().stream().map(tool -> tool.spec().name()).toList());

        execute(registry, "write", JsonObjects.builder()
                .putText("path", outside.resolve("note.txt").toString()).putText("content", "one").build());
        assertEquals("one", execute(registry, "read",
                JsonObjects.builder().putText("path", "../outside/note.txt").build()).content());
        execute(registry, "edit", JsonObjects.builder()
                .putText("path", "../outside/note.txt").putText("oldText", "one").putText("newText", "two")
                .build());
        assertEquals("two", Files.readString(outside.resolve("note.txt")));
    }

    /** edit 对多重匹配返回失败结果，不能静默修改任意一个位置。 */
    @Test
    void editRequiresUniqueOldText() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("unique-workspace"));
        Files.writeString(workspace.resolve("duplicate.txt"), "same same");
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), snapshot(), shellCapability(),
                promptSession(), unusedAttachments());
        assertEquals("tool_execution_failed", execute(registry, "edit",
                JsonObjects.builder().putText("path", "duplicate.txt").putText("oldText", "same")
                        .putText("newText", "next").build()).errorCode());
        assertEquals("same same", Files.readString(workspace.resolve("duplicate.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.require(invocation("read_file", JsonObject.empty())));
    }

    /** read 只返回内容与范围元数据，文件字节和修改时间都不能形成任何修改事实。 */
    @Test
    void readLeavesWorkspaceFileUnchanged() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("read-only-workspace"));
        Path source = workspace.resolve("source.txt");
        Files.writeString(source, "unchanged");
        java.nio.file.attribute.FileTime modifiedAt = Files.getLastModifiedTime(source);
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), snapshot(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "read",
                JsonObjects.builder().putText("path", "source.txt").build());

        assertEquals("unchanged", result.content());
        assertEquals("unchanged", Files.readString(source));
        assertEquals(modifiedAt, Files.getLastModifiedTime(source));
    }

    /** Shell 缺失时只移除该 Tool，文件和 Skill 读取能力仍保持可执行。 */
    @Test
    void omitsShellWhenCapabilityIsUnavailable() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("no-shell-workspace"));
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), snapshot(),
                ShellCapability.unavailable(ShellProfile.OperatingSystem.WINDOWS, "windows"), promptSession(),
                unusedAttachments());
        assertEquals(List.of("edit", "read", "read_attachment", "write"),
                registry.snapshot().stream().map(tool -> tool.spec().name()).toList());
    }

    /** read_attachment 只能把模型参数与冻结 Thread 身份组合，且图片仍通过 Base64 Tool 路由。 */
    @Test
    void readsAttachmentThroughBoundedToolWithContextThreadIdentity() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("attachment-workspace"));
        AtomicReference<ManagedAttachmentReader.ReadRequest> observed = new AtomicReference<>();
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), snapshot(), shellCapability(),
                promptSession(), visibleAttachment(observed));

        AgentTool.ToolResult result = execute(registry, "read_attachment", JsonObjects.builder()
                .putText("attachmentId", "att_fixture")
                .putNumber("offsetBytes", 8).putNumber("maxBytes", 64).build());

        assertEquals("thr_fixture", observed.get().threadId());
        assertEquals(8, observed.get().offsetBytes());
        assertEquals(64, observed.get().maxBytes());
        assertEquals("AAEC", result.content());
    }

    /** 通过真实 AgentTool 端口执行，避免测试私有实现细节。 */
    private AgentTool.ToolResult execute(ToolRegistry registry, String name, JsonObject arguments) {
        AgentTool.Invocation invocation = invocation(name, arguments);
        return registry.require(invocation).execute(invocation, context(), CancellationToken.none())
                .toCompletableFuture().join();
    }

    /** 每次调用使用合法稳定身份，测试只变化 Tool 名和参数。 */
    private static AgentTool.Invocation invocation(String name, JsonObject arguments) {
        return new AgentTool.Invocation("call_fixture", name, arguments, 0);
    }

    /** Workspace 仅作为相对路径基准；权限值不改变文件 Tool 路径解析。 */
    private AgentTool.ExecutionContext context() {
        return new AgentTool.ExecutionContext("thr_fixture", "turn_fixture", temporary.toAbsolutePath(),
                AccessMode.FULL_ACCESS, "cfg_fixture", Instant.now().plusSeconds(30), "ws_fixture");
    }

    /** Shell 本测试不执行，仅需冻结一份合法 Profile 供注册表创建。 */
    private static ShellCapability shellCapability() {
        return ShellCapability.available(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                ShellProfile.Dialect.POWERSHELL,
                Path.of(System.getProperty("java.home"), "bin", "java.exe"), List.of(), "windows",
                Map.of("PATHEXT", ".EXE;.CMD")));
    }

    /** 空快照证明普通文件读取不会反向发现 Skill。 */
    private static SkillCatalog.SkillSnapshot snapshot() {
        return new SkillCatalog.SkillSnapshot("skills_fixture", List.of(), Instant.EPOCH);
    }

    /** 文件 Tool 测试不覆盖 Prompt 刷新，固定 Session 只满足当前生产边界。 */
    private static FixedAgentPromptSession promptSession() {
        return new FixedAgentPromptSession(ContextBudget.capabilities(1_000_000, 8_192, true));
    }

    /** 普通文件用例不读取附件，严格代理确保 Tool 被意外调用时立即失败。 */
    private static ManagedAttachmentReader unusedAttachments() {
        return (ManagedAttachmentReader) java.lang.reflect.Proxy.newProxyInstance(
                BuiltInToolsTest.class.getClassLoader(), new Class<?>[]{ManagedAttachmentReader.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("unexpected attachment access: " + method.getName());
                });
    }

    /** 返回消费者自有的窄读取端口，测试不再反向依赖附件切片的入站用例。 */
    private static ManagedAttachmentReader visibleAttachment(
            AtomicReference<ManagedAttachmentReader.ReadRequest> observed) {
        return request -> {
            observed.set(request);
            return new ManagedAttachmentReader.ReadResult(
                    "att_fixture", "image.png", 11, "image", "image/png",
                    request.offsetBytes(), 11, true, "base64", "AAEC");
        };
    }

    /** 不参与本用例的 Skill 端口保持严格失败，防止意外读取被忽略。 */
    private static final class EmptySkills implements SkillCatalog {
        /** 返回同一空快照，避免测试引入文件发现行为。 */
        @Override public SkillSnapshot snapshot(SnapshotRequest request) { return BuiltInToolsTest.snapshot(); }
        /** 未启用 Skill 时显式返回空快照，不触发任何目录扫描。 */
        @Override public SkillSnapshot emptySnapshot() { return BuiltInToolsTest.snapshot(); }
        /** 当前用例没有可选条目，过滤只保持同一冻结快照。 */
        @Override public SkillSnapshot select(SkillSnapshot snapshot, List<String> allowedRevisions) {
            return snapshot;
        }
        /** 普通文件用例若触发 Skill 读取即说明路由发生回归。 */
        @Override public SkillDocument read(SkillSnapshot snapshot, SkillReadRequest request) {
            throw new AssertionError("unexpected skill read");
        }
    }
}
