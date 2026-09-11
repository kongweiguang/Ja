// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.in;

import java.util.concurrent.CompletionStage;

/** Plan 观察连接的窄出口；事件不是恢复来源，重连必须重新读取权威 snapshot。 */
public interface PlanEventSink {
    /** 只接收已提交的 Plan projection，背压失败由 registry 保留给调用方。 */
    CompletionStage<Void> publish(PlanEvent event);
}
