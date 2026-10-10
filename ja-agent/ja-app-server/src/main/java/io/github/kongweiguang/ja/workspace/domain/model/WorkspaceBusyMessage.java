// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain.model;

import java.util.LinkedHashSet;
import java.util.List;

/** 统一构造可安全展示的根目录重绑忙碌说明，沿用 RPC error message 而不扩展协议字段。 */
public final class WorkspaceBusyMessage {
    public static final String PREFIX = "暂时无法更改项目目录：";
    private static final int MAX_ITEMS = 3;
    private static final int MAX_TITLE_UTF8_BYTES = 60;
    private static final int MAX_TITLE_SCAN_CODE_POINTS = 512;
    private static final int MAX_MESSAGE_UTF8_BYTES = 512;

    /** 静态消息由专用工厂提供，避免把格式化工具实例当作领域服务注入。 */
    private WorkspaceBusyMessage() {
    }

    /** admission lease 正在提交运行事实时给出可重试说明，不猜测尚未落库的任务类型。 */
    public static String admissionInProgress() {
        return PREFIX + "正在启动任务，请稍后重试。";
    }

    /** 最多展示三条数据库阻塞事实并汇总余数，标题只用于识别会话且不读取任何正文。 */
    public static String durableWork(List<Blocker> blockers, long totalBlockers) {
        if (blockers == null || blockers.isEmpty()) {
            return PREFIX + "项目仍有运行或待处理的任务，请稍后重试。";
        }
        int displayed = Math.min(MAX_ITEMS, blockers.size());
        long total = Math.max(totalBlockers, displayed);
        StringBuilder message = new StringBuilder(PREFIX).append("占用会话：");
        for (int index = 0; index < displayed; index++) {
            message.append(index == 0 ? " " : "； ").append(format(blockers.get(index)));
        }
        long omitted = total - displayed;
        if (omitted > 0) {
            message.append("；另有 ").append(omitted).append(" 项未列出");
        }
        message.append(action(blockers));
        return boundedMessage(message.toString());
    }

    /** 只转发带固定前缀且满足 Native UTF-8 上界、无控制字符的内部消息。 */
    public static String forRpc(String candidate) {
        if (candidate == null || !candidate.startsWith(PREFIX)
            || utf8Length(candidate) > MAX_MESSAGE_UTF8_BYTES
            || candidate.codePoints().anyMatch(Character::isISOControl)) {
            return PREFIX + "项目仍有运行或待处理的任务，请稍后重试。";
        }
        return candidate;
    }

    /** 以会话标题与简短中文状态形成一项摘要，不把内部对象类型或状态码暴露给用户。 */
    private static String format(Blocker blocker) {
        StatusLabel status = status(blocker.kind(), blocker.state());
        return "会话“" + safeTitle(blocker.threadTitle()) + "”：" + status.label();
    }

    /** 只为当前已展示的真实阻塞状态附加简短操作，避免提示用户处理并不存在的任务类型。 */
    private static String action(List<Blocker> blockers) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        for (Blocker blocker : blockers.subList(0, Math.min(MAX_ITEMS, blockers.size()))) {
            if ("TURN".equals(blocker.kind())) {
                switch (blocker.state() == null ? "" : blocker.state()) {
                    case "QUEUED" -> actions.add("取消不需要的排队任务");
                    case "RUNNING" -> actions.add("等待运行任务结束");
                    case "WAITING_APPROVAL" -> actions.add("处理审批");
                    case "WAITING_INPUT" -> actions.add("补充会话信息");
                    case "SUSPENDED" -> actions.add("继续或取消已暂停任务");
                    default -> { }
                }
            } else if ("INPUT".equals(blocker.kind()) && "PENDING".equals(blocker.state())) {
                actions.add("提交或取消待处理输入");
            } else if ("PLAN".equals(blocker.kind())) {
                switch (blocker.state() == null ? "" : blocker.state()) {
                    case "EXECUTING", "VERIFYING" -> actions.add("等待计划运行结束");
                    case "PAUSED" -> actions.add("继续或取消暂停计划");
                    default -> { }
                }
            }
        }
        return actions.isEmpty() ? "。请处理上列会话后重试。"
                : "。请先" + String.join("、", actions) + "后重试。";
    }

    /** 将持久化闭集映射为面向用户的状态，未知值也保持为普通中文提示。 */
    private static StatusLabel status(String kind, String state) {
        if ("TURN".equals(kind)) {
            return switch (state == null ? "" : state) {
                case "QUEUED" -> new StatusLabel("排队中");
                case "RUNNING" -> new StatusLabel("运行中");
                case "WAITING_APPROVAL" -> new StatusLabel("等待审批");
                case "WAITING_INPUT" -> new StatusLabel("等待补充信息");
                case "SUSPENDED" -> new StatusLabel("已暂停");
                default -> new StatusLabel("状态待确认");
            };
        }
        if ("INPUT".equals(kind)) {
            return "PENDING".equals(state) ? new StatusLabel("待处理输入") : new StatusLabel("输入待处理");
        }
        if ("PLAN".equals(kind)) {
            return switch (state == null ? "" : state) {
                case "EXECUTING" -> new StatusLabel("计划执行中");
                case "VERIFYING" -> new StatusLabel("计划验收中");
                case "PAUSED" -> new StatusLabel("计划已暂停");
                default -> new StatusLabel("计划待处理");
            };
        }
        return new StatusLabel("任务待处理");
    }

    /** 清理控制字符并按 UTF-8 字节预算缩短标题，保证 Rust 解码器不会因长会话名拒绝错误帧。 */
    private static String safeTitle(String value) {
        if (value == null || value.isEmpty()) {
            return "未命名会话";
        }
        StringBuilder title = new StringBuilder();
        int offset = 0;
        int scanned = 0;
        int titleBytes = 0;
        boolean pendingSpace = false;
        boolean truncated = false;
        while (offset < value.length() && scanned < MAX_TITLE_SCAN_CODE_POINTS) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            scanned++;
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                pendingSpace = title.length() > 0;
                continue;
            }
            int additionBytes = utf8Bytes(codePoint) + (pendingSpace ? 1 : 0);
            if (titleBytes + additionBytes > MAX_TITLE_UTF8_BYTES - utf8Bytes('…')) {
                truncated = true;
                break;
            }
            if (pendingSpace) {
                title.append(' ');
                titleBytes++;
                pendingSpace = false;
            }
            title.appendCodePoint(codePoint);
            titleBytes += utf8Bytes(codePoint);
        }
        truncated |= offset < value.length();
        if (truncated) {
            title.append('…');
        }
        return title.isEmpty() ? "未命名会话" : title.toString();
    }

    /** 为未来状态或长字段保留固定 UTF-8 上界，并且只在 Unicode 码点边界截断。 */
    private static String boundedMessage(String value) {
        if (utf8Length(value) <= MAX_MESSAGE_UTF8_BYTES) {
            return value;
        }
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int codePointBytes = utf8Bytes(codePoint);
            if (bytes + codePointBytes > MAX_MESSAGE_UTF8_BYTES - utf8Bytes('…')) {
                break;
            }
            end += Character.charCount(codePoint);
            bytes += codePointBytes;
        }
        return value.substring(0, end) + "…";
    }

    /** 以 UTF-8 编码字节为协议预算单位，避免 code point 与传输长度估算不一致。 */
    private static int utf8Length(String value) {
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            bytes += utf8Bytes(codePoint);
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }

    /** 返回标准 UTF-8 单个 Unicode 码点的字节数；标题已先移除孤立代理项。 */
    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        return codePoint <= 0xffff ? 3 : 4;
    }

    /**
     * 只承载线程标题与阻塞类别的持久化摘要，不承载消息、输入或 Plan 正文。
     *
     * @param threadTitle 被阻塞会话的用户可见标题
     * @param kind 持久化阻塞事实类别
     * @param state 阻塞事实当前状态
     * @param occurrences 此会话与类别组合的持久事实数量
     * @author kongweiguang
     */
    public record Blocker(String threadTitle,
                          String kind,
                          String state,
                          long occurrences) {
        /** SQL 聚合项至少对应一个持久阻塞事实。 */
        public Blocker {
            if (occurrences < 1) {
                throw new IllegalArgumentException("workspace blocker occurrences must be positive");
            }
        }
    }

    /**
     * 已归一化为用户中文的状态文案，不暴露持久层枚举值。
     *
     * @param label 面向用户展示的状态说明
     * @author kongweiguang
     */
    private record StatusLabel(String label) {
    }
}
