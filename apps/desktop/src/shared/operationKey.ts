// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 为副作用请求生成统一身份；所属 controller 在同一意图重试时复用此值。 */
export function createOperationKey(): string {
  return `op_${globalThis.crypto.randomUUID().replaceAll("-", "")}`;
}
