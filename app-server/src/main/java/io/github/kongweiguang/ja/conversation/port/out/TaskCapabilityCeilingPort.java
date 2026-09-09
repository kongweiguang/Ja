// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.util.Optional;

/**
 * 在 Task 领域与运行时目录之间传递版本化能力上限；通用能力 Catalog 不解释 Task seed。
 */
public interface TaskCapabilityCeilingPort {
    /** Root 返回空，Child 返回创建时持久化的不可变 ceiling。 */
    Optional<JsonObject> read(String threadId);

    /** 使用最终请求目录身份创建后代 ceiling，调用方不得自行拼接 Task JSON。 */
    JsonObject create(ThreadPreferences preferences, String configGeneration,
                      AgentCapability.CatalogIdentity catalogIdentity);
}
