// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.lifecycle;

/**
 * 创建平台进程观察器的最小生命周期扩展点；平台 provider 由具体应用在需要时通过 ServiceLoader 装配。
 * @author kongweiguang
 */
@FunctionalInterface
public interface NativeProcessObservationProvider {
    /** 打开指定进程的观察器，失败时必须显式报告，不能降级到不同身份语义。 */
    NativeProcessObservation open(long processId);
}
