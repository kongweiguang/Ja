// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application.gateway;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 受约束文件 Tool 使用的 Workspace ACL；调用方只取得已校验的路径和不透明 mutation guard，
 * 不依赖 NIO 边界实现或其内部身份字段。
 */
public interface WorkspaceBoundaryPort {
    /** 返回已校验的物理工作区根。 */
    Path root();

    /** 解析并校验已存在的工作区相对路径。 */
    Path existing(String input) throws IOException;

    /** 解析并校验写入目标及其已存在祖先。 */
    Path target(String input) throws IOException;

    /** 提交前重新校验目标父目录。 */
    void revalidateParent(Path target) throws IOException;

    /** 创建一次 mutation 的不透明父目录身份凭证。 */
    MutationGuard mutationGuard(Path target) throws IOException;

    /** 在写入边界重新验证 mutation 凭证。 */
    void revalidateMutationGuard(MutationGuard guard) throws IOException;

    /** 将已校验路径映射为稳定工作区相对路径。 */
    String relative(Path path) throws IOException;

    /** 有界枚举工作区目录。 */
    WalkResult walkExisting(Path start, int maximumDepth, int maximumEntries) throws IOException;

    /** 重新校验调用方持有的已存在路径。 */
    Path revalidateExisting(Path path) throws IOException;

    /** 不向跨域调用方暴露文件系统身份的 mutation 凭证。 */
    interface MutationGuard {
    }

    /**
     * 已校验的有界遍历结果。
     *
     * @param entries   已通过 Workspace 边界检查的路径
     * @param truncated 是否因数量上限截断
     */
    record WalkResult(List<Path> entries,
                      boolean truncated) {
        /** 冻结列表，避免调用方注入未校验路径。 */
        public WalkResult {
            entries = List.copyOf(entries);
        }
    }
}
