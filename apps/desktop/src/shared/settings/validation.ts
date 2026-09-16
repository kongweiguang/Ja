// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 原生 credential 命名空间必须与 Rust `CredentialRef` 保持一致。 */
export const CREDENTIAL_REF_PATTERN = /^cred_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/;

/**
 * Provider 与 MCP 复用同一 URL 形状约束：允许任意 HTTP(S) 主机，同时禁止把凭据
 * 或请求参数嵌入地址，确保 Secret 仍只能经独立凭据边界传递。
 */
export function isSafeHttpUrl(value: string): boolean {
  try {
    const url = new URL(value.trim());
    return (
      (url.protocol === "http:" || url.protocol === "https:") &&
      url.hostname.length > 0 &&
      url.username.length === 0 &&
      url.password.length === 0 &&
      url.search.length === 0 &&
      url.hash.length === 0
    );
  } catch {
    return false;
  }
}
