// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.conversation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 示例宿主持有的会话目录事实；Core Store 仍拥有消息和执行状态。
 *
 * @param threadId 会话稳定标识
 * @param agentId 创建时冻结的角色标识
 * @param title 页面展示和搜索使用的会话标题
 * @param archived 是否已归档并禁止新执行准入
 * @param version 元数据乐观锁版本
 * @param createdAt 会话创建时间
 * @param updatedAt 最近一次元数据修改时间
 */
public record ThreadMetadata(String threadId,
                             String agentId,
                             String title,
                             boolean archived,
                             long version,
                             Instant createdAt,
                             Instant updatedAt) {
    /** 仓储读回时即校验目录事实，防止损坏行被当作可执行会话。 */
    public ThreadMetadata {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("invalid thread version");
        }
    }

    /** 创建键重试只能取回原角色，避免同一 Thread 在重试中换 Persona。 */
    public void requireAgent(String requestedAgentId) {
        if (!agentId.equals(requestedAgentId)) {
            throw new IllegalStateException("create key belongs to another agent");
        }
    }

    /** 把标题和归档规则集中校验，应用层只负责取得最新执行事实与持久化 CAS。 */
    public Change change(long expectedVersion, String requestedTitle, Boolean requestedArchived,
                         String activeTurnId) {
        if (version != expectedVersion || requestedTitle == null && requestedArchived == null) {
            throw new IllegalStateException("thread metadata version changed");
        }
        String nextTitle = requestedTitle == null ? title : requestedTitle.trim();
        if (nextTitle.isBlank() || nextTitle.length() > 120
                || nextTitle.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid title");
        }
        boolean nextArchived = requestedArchived == null ? archived : requestedArchived;
        if (nextArchived && !archived && activeTurnId != null) {
            throw new IllegalStateException("active execution cannot be archived");
        }
        return new Change(nextTitle, requestedTitle != null, nextArchived);
    }

    /** 归档或其它活动 Turn 存在时拒绝新准入，Core Store 仍负责最终并发 CAS。 */
    public void requireAdmittable(String turnId, String activeTurnId) {
        if (archived || activeTurnId != null && !activeTurnId.equals(turnId)) {
            throw new IllegalStateException("thread is archived or active");
        }
    }

    /** 初始标题只由首条已准入消息派生，并按码点截断以免切开代理对或改写消息正文。 */
    public static String initialTitle(String message) {
        String title = message.strip().replaceAll("\\s+", " ");
        if (title.codePointCount(0, title.length()) <= 40) {
            return title;
        }
        return title.substring(0, title.offsetByCodePoints(0, 40));
    }

    /** 终态集合由 Core 的公开执行状态定义，等待与暂停均视为活动。 */
    public static boolean isActive(String state) {
        return !List.of("COMPLETED", "FAILED", "CANCELLED").contains(state);
    }

    /**
     * 更新事务只需要这些经过领域校验的值。
     *
     * @param title 校验后的新标题
     * @param customized 标题是否由用户显式修改
     * @param archived 更新后的归档状态
     */
    public record Change(String title,
                         boolean customized,
                         boolean archived) { }
}
