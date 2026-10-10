// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.in.web.dto;

/** Notes 查询只接受 Thread 标识，服务端 owner 由 CurrentActorProvider 决定。
 *
 * @param threadId 要读取的 Thread 标识
 */
public record NotesQuery(String threadId) {
    /** 拒绝无作用域的笔记读取，避免宽泛 Map 接受看似可控的租户或用户字段。 */
    public NotesQuery {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("thread required");
        }
    }
}
