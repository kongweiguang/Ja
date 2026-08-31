// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 负责 auth.json 凭据生命周期及 Windows ACL、句柄固定和安全原子发布。
 *
 * <p>本包禁止解释业务配置、选择 Provider/Model 或管理 generation；平台实现只接受已确定的路径与
 * 字节所有权，并以失败关闭策略维护 Secret 文件边界。</p>
 */
package io.github.kongweiguang.ja.configuration.adapter.out.security.windows;
