// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.admission;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.conversation.application.gateway.PreparedAdmissionRuntimePort;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把同步发起的 Child admission 元数据短暂绑定到 Core 的稳定执行身份；真正业务事实仍由同一
 * AgentStore admission 事务写入，注册表只跨越运行时解析边界，不承担恢复或持久化职责。
 */
public final class JaAdmissionParticipants implements PreparedAdmissionRuntimePort {
    private final ConcurrentMap<AgentIdentity, PreparedAdmission> prepared = new ConcurrentHashMap<>();

    /**
     * Ja 的本地用户身份在 Core request 与 Task admission 间保持一致，不从会话状态临时推断。
     */
    public static AgentIdentity identityFor(String threadId, String turnId) {
        return AppServerIdentity.local().execution(Objects.requireNonNull(threadId, "threadId"),
                Objects.requireNonNull(turnId, "turnId"));
    }

    /**
     * 在调用 Child Turn 前登记一次 admission 快照；身份冲突直接失败，关闭 Registration 才移除快照。
     */
    public Registration register(AgentIdentity identity, PreparedAdmission admission) {
        AgentIdentity key = Objects.requireNonNull(identity, "identity");
        PreparedAdmission value = Objects.requireNonNull(admission, "admission");
        if (!key.equals(value.facts().identity())
            || value.runtimeSnapshot().stream()
                    .anyMatch(snapshot -> !key.equals(snapshot.requestIdentity()))) {
            throw new IllegalArgumentException("prepared admission identity does not match its registration");
        }
        if (prepared.putIfAbsent(key, value) != null) {
            throw new IllegalStateException("an admission is already registered for this execution");
        }
        return new Registration() {
            private final AtomicBoolean closed = new AtomicBoolean();

            /**
             * 只删除自己登记的相同快照，避免迟到 close 清理掉同身份的新一代 admission。
             */
            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    prepared.remove(key, value);
                }
            }
        };
    }

    /**
     * 解析时可重复读取已冻结快照，保留到同步 admission 成功或失败后由拥有者关闭注册句柄。
     */
    public Optional<PreparedAdmission> find(AgentIdentity identity) {
        return Optional.ofNullable(prepared.get(Objects.requireNonNull(identity, "identity")));
    }

    /** 跨域读取仅暴露精确身份的不可变快照，临时注册表的事务参与者与生命周期句柄仍由本域持有。 */
    @Override
    public Optional<TaskCapabilityCeilingPort.RuntimeSnapshot> read(AgentIdentity identity) {
        return find(identity).flatMap(PreparedAdmission::runtimeSnapshot);
    }

    /**
     * 作用域注册句柄确保接纳成功或失败后只清理本次执行的临时绑定。
     */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /**
         * 同步接纳返回后释放当前句柄登记的精确绑定。
         */
        @Override
        void close();
    }

    /**
     * 一次同步 admission 的完整 Ja 身份快照；Thread 初始化与来源关系在 AdmissionFacts 中显式携带。
     *
     * @param facts 事实列表
     * @param transactionParticipant 参与同一存储事务的业务结算组件
     * @param runtimeSnapshot 新 Child 首轮使用的冻结运行时快照
     */
    public record PreparedAdmission(AdmissionFacts facts,
                                    JaAdmissionTransactionParticipant transactionParticipant,
                                    Optional<TaskCapabilityCeilingPort.RuntimeSnapshot> runtimeSnapshot) {
        /**
         * 冻结业务关联与事务参与者，拒绝用默认元数据填充缺失来源事实。
         */
        public PreparedAdmission {
            Objects.requireNonNull(facts, "facts");
            Objects.requireNonNull(transactionParticipant, "transactionParticipant");
            runtimeSnapshot = Objects.requireNonNull(runtimeSnapshot, "runtimeSnapshot");
        }
    }
}
