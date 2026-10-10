// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

/**
 * 队列命令使用条目 CAS，身份和正文都放 POST 请求体。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param action 动作
 * @param inputId 输入标识
 * @param kind 类型分类
 * @param expectedInputRevision 预期输入修订版本
 * @param content 新的正文、Skill 与附件快照；仅 ENQUEUE/UPDATE 使用
 */
public record QueueCommand(String threadId,
                           String turnId,
                           String operationKey,
                           String action,
                           String inputId,
                           String kind,
                           long expectedInputRevision,
                           InputContent content) {
    /**
     * 内容写操作要求正文快照，且 ENQUEUE 必须显式给出类型；删除和优先级变更不能携带伪造输入。
     */
    public QueueCommand {
        new TurnCommand(threadId, turnId);
        if (operationKey == null || operationKey.isBlank() || action == null || inputId == null || inputId.isBlank()) {
            throw new IllegalArgumentException("queue identity required");
        }
        boolean contentAction = action.equals("ENQUEUE") || action.equals("UPDATE");
        if (contentAction != (content != null)) {
            throw new IllegalArgumentException("queue content does not match action");
        }
        if (action.equals("ENQUEUE") && (kind == null || kind.isBlank())) {
            throw new IllegalArgumentException("queue kind required for enqueue");
        }
        if (!contentAction && !action.equals("DELETE") && !action.equals("PRIORITIZE")) {
            throw new IllegalArgumentException("unknown queue action");
        }
    }
}
