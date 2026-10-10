// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 安全点使用的不可变能力租约；含凭据的配置只留在进程内。
 *
 * @param modelConfiguration 模型配置
 * @param model 模型
 * @param summaryModels 摘要模型列表
 * @param extensions 扩展集合
 * @param promptSession 提示词会话
 * @param bindingFingerprint 绑定指纹
 * @param transactionParticipant 参与同一存储事务的业务结算组件
 * @param attachments 附件列表
 * @param outputLimits 工具结果投影的容量约束
 * @param modelIdentityAttributes 模型请求的身份属性
 * @param release 释放
 */
public record AgentRequestRuntime(ModelPort.ModelConfiguration modelConfiguration,
                                  ModelPort model,
                                  SummaryModel.Factory summaryModels,
                                  ExtensionRegistry.Snapshot extensions,
                                  AgentPromptSession promptSession,
                                  String bindingFingerprint,
                                  io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant transactionParticipant,
                                  ManagedAttachmentReader attachments,
                                  ToolProjectionLimits outputLimits,
                                  Map<String, String> modelIdentityAttributes,
                                  AutoCloseable release) implements AutoCloseable {
    /**
     * 模型、Prompt、附件读取、投影预算、审计属性与事务参与者必须属于同一短期请求租约。
     */
    public AgentRequestRuntime {
        Objects.requireNonNull(modelConfiguration, "modelConfiguration");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(summaryModels, "summaryModels");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(promptSession, "promptSession");
        if (bindingFingerprint == null || !bindingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid runtime binding fingerprint");
        }
        Objects.requireNonNull(transactionParticipant, "transactionParticipant");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(outputLimits, "outputLimits");
        modelIdentityAttributes = immutableIdentityAttributes(modelIdentityAttributes);
        release = new OnceCloseable(Objects.requireNonNull(release, "release"));
    }

    /**
     * 冻结宿主希望随实际模型请求审计的非敏感元数据；排序后持久化以稳定恢复摘要，Core 不解释业务键。
     */
    private static Map<String, String> immutableIdentityAttributes(Map<String, String> source) {
        Objects.requireNonNull(source, "modelIdentityAttributes");
        if (source.size() > 32) {
            throw new IllegalArgumentException("too many model identity attributes");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        source.forEach((key, value) -> {
            String checkedKey = ContractChecks.identifier(key, "modelIdentityAttributeKey");
            String checkedValue = ContractChecks.text(value, "modelIdentityAttributeValue", 1_024, false);
            if (sorted.putIfAbsent(checkedKey, checkedValue) != null) {
                throw new IllegalArgumentException("duplicate model identity attribute");
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    /**
     * 释放解析器明确创建的资源，不关闭调用方拥有的模型、工具或存储。
     */
    @Override
    public void close() {
        try {
            release.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("request runtime release failed", failure);
        }
    }

    /**
     * 租约可能被取消、完成和 Engine 关闭竞争释放，因此底层资源只接收一次 close。
     */
    final class OnceCloseable implements AutoCloseable {
        private final AutoCloseable delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 只包装解析器给出的释放动作，不扩大底层资源所有权。
         */
        private OnceCloseable(AutoCloseable delegate) {
            this.delegate = delegate;
        }

        /**
         * 原子化关闭竞争，首个调用负责执行并传播失败，其余调用保持幂等。
         */
        @Override
        public void close() throws Exception {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
            }
        }
    }
}
