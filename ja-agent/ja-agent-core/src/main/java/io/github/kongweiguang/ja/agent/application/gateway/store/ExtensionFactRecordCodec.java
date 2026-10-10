// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;

import java.time.Instant;

/**
 * 扩展事实的应用层存储合同；JSON 信封的具体编码留给基础设施，避免用例依赖编解码库。
 */
public interface ExtensionFactRecordCodec {
    /**
     * 所有扩展事实共用 envelope row type，持久化实现与恢复 reducer 必须共享此标识。
     */
    String RECORD_TYPE = "agent.extension.fact";

    /**
     * 外层协议版本由应用合同冻结，具体 codec 只能实现该版本的严格读写。
     */
    int ENVELOPE_VERSION = 1;

    /**
     * 将扩展事实编码为同一执行事务内的普通记录，recordId 由用例按身份规则生成。
     */
    AgentStore.RecordWrite encode(String recordId, AgentTool.ExtensionFact fact, Instant occurredAt);

    /**
     * 严格解码扩展信封，未知类型、版本或字段必须失败关闭而不能静默丢弃。
     */
    AgentTool.ExtensionFact decode(AgentStore.StoredRecord record);
}
