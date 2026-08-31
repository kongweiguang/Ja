// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 将类型化 adapter 拒绝分类为并发冲突，未知错误不得误报为可恢复 CAS 冲突。 */
export function isConflictError(error: unknown): boolean {
  if (typeof error !== "object" || error === null || !("code" in error)) return false;
  const code = (error as { code?: unknown }).code;
  return (
    code === "REVISION_CONFLICT" ||
    code === "CONFLICT" ||
    code === "CAS_MISMATCH" ||
    code === "WORKSPACE_CONFLICT"
  );
}

/** 只识别稳定的回收站不可用错误码，避免把任意 native 失败解释成系统设置问题。 */
export function isRecycleUnavailableError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { code?: unknown }).code === "RECYCLE_UNAVAILABLE"
  );
}

/** 只接受 Rust workspace mutation 的稳定恢复码，普通 IO/CAS 失败不能误触发全局写保护。 */
export function isWorkspaceRecoveryRequiredError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { code?: unknown }).code === "WORKSPACE_RECOVERY_REQUIRED"
  );
}
