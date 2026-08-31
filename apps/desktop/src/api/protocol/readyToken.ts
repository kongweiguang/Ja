// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

const READY_TOKEN_LENGTH = 32;
export const READY_TOKEN_PATTERN = /^[0-9a-f]{32}$/;
const MAX_READY_TOKEN_SCAN_LENGTH = 4_194_304;

/**
 * 有界且确定的指纹允许 UI 比较 challenge，同时不在 Zustand、devtools
 * 或错误对象中保留原始 token 历史。
 */
export function fingerprintReadyToken(token: string): string {
  let left = 0x9e3779b1n;
  let right = 0xc2b2ae35n;
  const mask = 0xffffffffffffffffn;
  const normalized = token.toLowerCase();
  for (let index = 0; index < normalized.length; index += 1) {
    const code = BigInt(normalized.charCodeAt(index));
    left = ((left ^ code) * 0x100000001b3n) & mask;
    right = ((right + code + BigInt(index)) * 0x9e3779b185ebca87n) & mask;
    right ^= left >> 29n;
  }
  return `${left.toString(16).padStart(16, "0")}${right.toString(16).padStart(16, "0")}`;
}

/**
 * 扫描有界字符串窗口，避免嵌入诊断文本或对象键的 token 绕过仅等值泄漏检查。
 */
export function forEachReadyTokenCandidate(
  value: string,
  visit: (candidate: string) => boolean,
): boolean {
  if (value.length > MAX_READY_TOKEN_SCAN_LENGTH) {
    throw new Error("string is too large for ready-token inspection");
  }
  if (value.length < READY_TOKEN_LENGTH) {
    return false;
  }
  for (let start = 0; start <= value.length - READY_TOKEN_LENGTH; start += 1) {
    const candidate = value.slice(start, start + READY_TOKEN_LENGTH);
    if (READY_TOKEN_PATTERN.test(candidate.toLowerCase()) && visit(candidate.toLowerCase())) {
      return true;
    }
  }
  return false;
}

/**
 * 即使当前 challenge 不可用，错误字段也按形状脱敏，防止 token 形状文本
 * 成为诊断泄漏。
 */
export function containsTokenShapedText(value: string): boolean {
  return forEachReadyTokenCandidate(value, () => true);
}
