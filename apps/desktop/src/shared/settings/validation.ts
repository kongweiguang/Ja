// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 原生 credential 命名空间必须与 Rust `CredentialRef` 保持一致。 */
export const CREDENTIAL_REF_PATTERN = /^cred_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/;

/**
 * 在 shared 边界校验 URL，使原生 Settings 与表单使用相同 scheme、host
 * 以及 credential/query 限制。
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

/** Provider 流量必须使用 HTTPS，只有显式本地 loopback fixture 例外。 */
export function isSafeProviderUrl(value: string): boolean {
  if (!isSafeHttpUrl(value)) {
    return false;
  }
  const url = new URL(value.trim());
  if (url.protocol === "https:") {
    return true;
  }
  return (
    url.protocol === "http:" &&
    (url.hostname === "localhost" || url.hostname === "127.0.0.1" || url.hostname === "[::1]")
  );
}
