// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.domain;

import java.util.Objects;

/**
 * Skill 授权以来源和名称共同标识，避免工作区同名包借用用户级授权。
 *
 * <p>该类型只表达持久化授权，不保存描述、路径或正文；这些随每次发现结果重新取得，
 * 因而文件变化只会影响后续 Turn 的安全点。</p>
 */
public record SkillReference(Source source, String name) {
    /** Skill 来源与磁盘发现优先级一一对应，builtin 不允许写入用户配置。 */
    public enum Source {
        /** `~/.agents/skills` 的用户 Skill。 */
        USER("user"),

        /** `~/.ja/skills` 的 Ja 用户 Skill。 */
        JA("ja"),

        /** 可信工作区 `.agents/skills` 的项目 Skill。 */
        PROJECT("project");

        private final String prefix;

        /** 将稳定配置前缀保存在枚举内部，调用方不再维护另一套字符串映射。 */
        Source(String prefix) {
            this.prefix = prefix;
        }

        /** 返回配置中使用的稳定来源前缀。 */
        public String prefix() {
            return prefix;
        }

        /** 只接受当前三类可持久化来源，未知前缀必须失败关闭。 */
        static Source fromPrefix(String value) {
            for (Source source : values()) if (source.prefix.equals(value)) return source;
            throw new IllegalArgumentException("skill source is unsupported");
        }
    }

    /**
     * 规范化来源名称并拒绝控制符及分隔符，确保字符串引用可无歧义地往返 TOML、RPC 与 Prompt。
     */
    public SkillReference {
        Objects.requireNonNull(source, "source");
        if (name == null || name.isBlank() || name.length() > 512 || name.indexOf(':') >= 0
                || name.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("skill name is invalid");
        }
    }

    /** 将结构化授权编码为唯一可写入配置和传递给 Renderer 的稳定标识。 */
    public String identifier() {
        return source.prefix() + ":" + name;
    }

    /** 解析严格来源前缀，禁止旧 `skill_*` 身份或无来源名称回流。 */
    public static SkillReference parse(String value) {
        if (value == null || value.length() > 520) throw new IllegalArgumentException("skill reference is invalid");
        int delimiter = value.indexOf(':');
        if (delimiter <= 0 || delimiter != value.lastIndexOf(':') || delimiter == value.length() - 1) {
            throw new IllegalArgumentException("skill reference is invalid");
        }
        return new SkillReference(Source.fromPrefix(value.substring(0, delimiter)), value.substring(delimiter + 1));
    }

}
