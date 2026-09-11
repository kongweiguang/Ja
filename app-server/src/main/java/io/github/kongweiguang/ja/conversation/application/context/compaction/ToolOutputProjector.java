// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.compaction;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 将完整 Tool 输出投影为有界模型提示，同时保留头尾、产物引用和退出诊断。
 */
public final class ToolOutputProjector {
    private final ToolProjectionLimits limits;
    private final Mode mode;

    /**
     * 固定一次规划使用的头尾限制，保证预算估算与实际投影采用相同参数。
     */
    public ToolOutputProjector(ToolProjectionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.mode = limits.artifactOnly() ? Mode.ARTIFACT_ONLY : Mode.HEAD_TAIL;
    }

    /** 创建最近 Tool 结果的完整投影；仍追加 hash 与诊断元数据以支持 artifact 校验。 */
    public static ToolOutputProjector full() {
        return new ToolOutputProjector(new ToolProjectionLimits(1, 0), Mode.FULL);
    }

    /** 创建只保留 artifact 引用和诊断元数据的最小投影，不复制正文片段。 */
    public static ToolOutputProjector artifactOnly() {
        return new ToolOutputProjector(new ToolProjectionLimits(1, 0), Mode.ARTIFACT_ONLY);
    }

    /** 内部构造器固定模式与合法占位限制，避免公开 nullable 配置。 */
    private ToolOutputProjector(ToolProjectionLimits limits, Mode mode) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /**
     * 暴露不可变投影限制，供 overflow 恢复在现有基线上执行单次收缩。
     */
    public ToolProjectionLimits limits() {
        return limits;
    }

    /**
     * 按 Unicode code point 保留输出头尾，避免按 UTF-16 下标切坏补充字符。
     */
    public Projection project(ContextMessage.ToolOutput output) {
        Objects.requireNonNull(output, "output");
        String content = output.content();
        int count = content.codePointCount(0, content.length());
        boolean truncated = mode != Mode.FULL
                && count > limits.headCharacters() + limits.tailCharacters();
        String head;
        String tail;
        if (mode == Mode.ARTIFACT_ONLY) {
            head = "";
            tail = "";
            truncated = count > 0;
        } else if (truncated) {
            head = firstCodePoints(content, limits.headCharacters());
            tail = lastCodePoints(content, limits.tailCharacters());
        } else {
            head = content;
            tail = "";
        }
        String prompt = composePrompt(head, tail, truncated, mode, output);
        return new Projection(prompt, head, tail, output.artifactReference(), output.exitCode(),
                output.error(), truncated);
    }

    /**
     * 以与上下文策略一致的四字符估算口径累计投影块，并用饱和值避免整数回绕。
     */
    public int estimate(java.util.List<ContextMessage.Block> blocks) {
        long characters = 0;
        for (ContextMessage.Block block : blocks) {
            if (block instanceof ContextMessage.TextBlock text) {
                characters += text.value().codePointCount(0, text.value().length());
            } else if (block instanceof ContextMessage.AttachmentBlock attachment) {
                characters += attachment.attachmentId().length() + 64L;
            } else if (block instanceof ContextMessage.ToolCallBlock call) {
                characters += call.name().length() + call.arguments().length();
            } else if (block instanceof ContextMessage.ToolResultBlock result) {
                characters += result.output().content().codePointCount(0,
                        result.output().content().length());
            } else if (block instanceof ContextMessage.ReasoningBlock reasoning) {
                /* 预算要覆盖同身份请求实际会发送的原生块，但不把其正文投影为 Tool 文本。 */
                characters += reasoning.content().nativeJson().length();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (characters + 3L) / 4L));
    }

    /**
     * 组装确定性截断标记和诊断尾注，使模型知道缺失正文及可用产物位置。
     */
    private static String composePrompt(String head, String tail, boolean truncated, Mode mode,
                                        ContextMessage.ToolOutput output) {
        StringBuilder prompt = new StringBuilder(head);
        if (mode == Mode.ARTIFACT_ONLY) {
            prompt.append("[tool-output-artifact-only");
            if (output.artifactReference() != null) {
                prompt.append(" artifact=").append(output.artifactReference());
            } else {
                prompt.append(" artifact=unavailable");
            }
            prompt.append(']');
        } else if (truncated) {
            prompt.append("\n[tool-output-truncated");
            if (output.artifactReference() != null) {
                prompt.append(" artifact=").append(output.artifactReference());
            } else {
                prompt.append(" artifact=unavailable");
            }
            prompt.append("]\n");
            prompt.append(tail);
        }
        prompt.append("\n[characters=").append(output.content().codePointCount(0, output.content().length()))
                .append(" sha256=").append(sha256(output.content())).append(']');
        if (output.exitCode() != null) {
            prompt.append("\n[exit_code=").append(output.exitCode()).append(']');
        }
        if (output.error() != null) {
            prompt.append("\n[error=").append(output.error()).append(']');
        }
        return prompt.toString();
    }

    /** 使用完整 UTF-8 Tool 结果生成审计摘要，截断投影仍可验证回读内容未漂移。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 按 code point 截取头部，避免代理项拆分导致无效提示文本。
     */
    private static String firstCodePoints(String value, int count) {
        int end = value.offsetByCodePoints(0, count);
        return value.substring(0, end);
    }

    /**
     * 按 code point 截取尾部，以保留 Tool 输出末端常见的错误和汇总信息。
     */
    private static String lastCodePoints(String value, int count) {
        int start = value.offsetByCodePoints(value.length(), -count);
        return value.substring(start);
    }

    /**
     * 同时保存最终提示与组成证据，便于测试截断边界而不回读完整 Tool 输出。
     */
    public record Projection(String promptText, String head, String tail, String artifactReference,
                             Integer exitCode, String error, boolean truncated) {
        /**
         * 要求提示、头部和尾部始终存在；无截断时以空尾部表达而非空值。
         */
        public Projection {
            Objects.requireNonNull(promptText, "promptText");
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(tail, "tail");
        }
    }

    /** 投影阶段从完整到头尾再到 artifact-only 单向降载。 */
    private enum Mode {
        /** 保留最近结果正文。 */
        FULL,
        /** 为较旧结果保留有界头尾。 */
        HEAD_TAIL,
        /** 只保留可回读引用与诊断。 */
        ARTIFACT_ONLY
    }
}
