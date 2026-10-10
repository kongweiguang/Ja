// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.port.out;

import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * 附件持久化端口；只有明确内容读取会返回原始文件字节。
 */
public interface AttachmentRepository {
    /**
     * 幂等写入尚未被用户输入引用的不可变文件。
     */
    AttachmentMetadata upload(CurrentActor actor, String threadId, String operationKey,
                              String fileName, String mediaType, String sha256, byte[] content);

    /**
     * 按一个 Thread 与当前 Actor 读取元数据，不包含 bytea 正文。
     */
    List<AttachmentMetadata> metadata(CurrentActor actor, String threadId, List<String> attachmentIds);

    /**
     * 按当前 Actor 读取一个附件的元数据，不包含正文。
     */
    AttachmentMetadata metadata(CurrentActor actor, String attachmentId);

    /**
     * 为明确的预览请求物化单个文件。
     */
    AttachmentContent content(CurrentActor actor, String threadId, String attachmentId);

    /**
     * 幂等删除尚未被持久输入引用的草稿附件。
     */
    List<String> discard(CurrentActor actor, String threadId, List<String> attachmentIds);

    /**
     * 在 Core Store 的现有 Connection 中原子记录附件已被输入引用。
     */
    void reference(Connection connection, AgentIdentity identity, Collection<String> attachmentIds)
            throws SQLException;

    /**
     * 在 Core Store 的现有 Connection 中校验笔记来源附件归属与已引用状态。
     */
    List<AttachmentMetadata> requireOwned(Connection connection, AgentIdentity identity,
                                          Collection<String> attachmentIds) throws SQLException;

    /**
     * 按 Core Reader 的有界请求读取一个经授权的字节切片。
     */
    ReadSlice read(ManagedAttachmentReader.ReadRequest request);

    /**
     * 删除超过期限且从未进入持久输入的附件草稿。
     */
    void deleteExpiredDrafts();

    /**
     * content endpoint 明确请求返回的原始附件字节。
     *
     * @param metadata 与字节绑定的权威附件元数据
     * @param content 仅在显式内容读取时物化的原始文件字节
     */
    record AttachmentContent(AttachmentMetadata metadata,
                             byte[] content) {
        /**
         * 防止 JDBC 缓冲区被调用方改写。
         */
        public AttachmentContent {
            content = content.clone();
        }

        /**
         * 返回防御性副本，保证文件不可变。
         */
        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * Reader 返回的数据库 range 和权威元数据。
     *
     * @param metadata 已验证且限定范围的附件元数据
     * @param content 当前读取范围内的原始文件字节
     */
    record ReadSlice(AttachmentMetadata metadata,
                     byte[] content) {
        /**
         * 防止范围缓冲区在 Core 编码前被修改。
         */
        public ReadSlice {
            content = content == null ? new byte[0] : content.clone();
        }

        /**
         * 返回防御性副本，避免共享数据库切片数组。
         */
        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
