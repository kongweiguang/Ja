// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配置域跨入站与出站边界共享的不可变数据模型。
 *
 * <p>模型只使用 JDK 类型，使应用服务无需复制配置树，也避免文件适配器依赖 RPC 所消费的
 * 入站端口。成员类型由端口继承后仍保持同一运行时身份，不承担旧契约兼容职责。</p>
 */
public interface ConfigurationData {
    /** 配置文档根对象；属性顺序被保留，便于生成稳定 Wire 与诊断输出。 */
    record Document(Map<String, Value> properties) {
        /** 防御性复制根属性，防止调用方在 CAS 校验后修改待写入文档。 */
        public Document {
            Objects.requireNonNull(properties, "properties");
            LinkedHashMap<String, Value> copy = new LinkedHashMap<>();
            properties.forEach((name, value) -> copy.put(
                    Objects.requireNonNull(name, "property name"),
                    Objects.requireNonNull(value, "property value")));
            properties = Collections.unmodifiableMap(copy);
        }
    }

    /** 配置文档允许的 JSON 兼容值集合；实现必须穷举处理。 */
    sealed interface Value permits ObjectValue, ArrayValue, TextValue, NumberValue, BooleanValue, NullValue {
    }

    /** 配置对象值；字段顺序被保留且映射不可修改。 */
    record ObjectValue(Map<String, Value> properties) implements Value {
        /** 防御性复制对象属性，使嵌套文档同样满足不可变快照约束。 */
        public ObjectValue {
            Objects.requireNonNull(properties, "properties");
            LinkedHashMap<String, Value> copy = new LinkedHashMap<>();
            properties.forEach((name, value) -> copy.put(
                    Objects.requireNonNull(name, "property name"),
                    Objects.requireNonNull(value, "property value")));
            properties = Collections.unmodifiableMap(copy);
        }
    }

    /** 配置数组值；元素顺序属于配置语义，因此只复制而不排序。 */
    record ArrayValue(List<Value> values) implements Value {
        /** 冻结数组元素并拒绝 null 引用；JSON null 必须显式使用 {@link NullValue}。 */
        public ArrayValue {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }

    /** 配置文本值。 */
    record TextValue(String value) implements Value {
        /** 拒绝 Java null，避免它与协议中的显式 JSON null 混淆。 */
        public TextValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /** 配置数值；BigDecimal 保留 JSON 数值精度且不依赖 Jackson 节点类型。 */
    record NumberValue(BigDecimal value) implements Value {
        /** 固定任意精度数值，避免浮点转换改变配置上限或 revision。 */
        public NumberValue {
            Objects.requireNonNull(value, "value");
        }
    }

    /** 配置布尔值。 */
    record BooleanValue(boolean value) implements Value {
    }

    /** 显式 JSON null；单例枚举避免在不可变配置树中重复分配无状态对象。 */
    enum NullValue implements Value {
        /** 唯一 JSON null 值。 */
        INSTANCE
    }

    /** 配置层的脱敏读取状态。 */
    enum LayerStatus {
        /** 权威文件尚未创建。 */
        MISSING,

        /** 文件存在且通过严格语义校验。 */
        VALID,

        /** 项目文件存在，但工作区尚未获得 Java 侧信任。 */
        UNTRUSTED,

        /** 文件可读取，但语法或严格配置语义损坏。 */
        CORRUPT,

        /** 权威文件因 IO 或安全校验失败而不可读取。 */
        IO_ERROR
    }

    /** 单个配置层的不可变脱敏投影。 */
    record Layer(ConfigurationScope scope, boolean present, boolean trusted, String version,
                 LayerStatus status, Document document) {
        /** 固定层身份与 CAS 版本；document 仅在成功解析时存在。 */
        public Layer {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(status, "status");
        }
    }

    /** 单个凭据的脱敏状态。 */
    record CredentialStatus(boolean configured) {
    }

    /** 一次读取的不可变结果，不携带规范路径或任何 Secret。 */
    record ReadResult(boolean trusted, Layer user, Layer project, Document effective,
                      Map<String, CredentialStatus> credentials, String credentialVersion,
                      List<String> diagnostics) {
        /** 冻结所有集合并校验必填值，避免消费者观察到并发修改。 */
        public ReadResult {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(effective, "effective");
            Objects.requireNonNull(credentials, "credentials");
            credentials = Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
            Objects.requireNonNull(credentialVersion, "credentialVersion");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** 成功配置写入的作用域与新 CAS 版本。 */
    record MutationResult(ConfigurationScope scope, String version) {
        /** 固定成功结果的业务身份；失败只通过 ConfigurationError 表达。 */
        public MutationResult {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(version, "version");
        }
    }

    /** 成功凭据写入的脱敏结果。 */
    record CredentialResult(String credentialId, boolean configured, String version) {
        /** 固定凭据身份和 CAS 版本，结果不允许承载 Secret。 */
        public CredentialResult {
            Objects.requireNonNull(credentialId, "credentialId");
            Objects.requireNonNull(version, "version");
        }
    }

    /** 配置子系统健康级别。 */
    enum HealthStatus {
        /** 配置文档、凭据与代际均可正常使用。 */
        HEALTHY,

        /** 至少一个阻断诊断存在，但进程仍保留修复入口。 */
        DEGRADED
    }

    /** 配置健康投影；诊断只包含稳定代码。 */
    record HealthResult(HealthStatus status, List<String> diagnostics) {
        /** 冻结有界诊断列表，禁止底层异常消息进入运行时健康响应。 */
        public HealthResult {
            Objects.requireNonNull(status, "status");
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
