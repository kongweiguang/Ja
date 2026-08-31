// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

/**
 * 表达与具体 JSON 库无关的严格值闭集，使领域端口不再暴露 Map、Object 或 JsonNode。
 */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonText, JsonNumber, JsonBoolean, JsonNull {
}
