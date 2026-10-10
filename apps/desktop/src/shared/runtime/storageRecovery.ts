// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

type StorageRecoveryReason =
  | "SCHEMA_VALIDATION_FAILED"
  | "INITIALIZATION_FAILED"
  | "CORRUPT_DATABASE"
  | "DISK_FULL"
  | "PERMISSION_DENIED"
  | "INSTANCE_LOCKED"
  | "IO_ERROR";

/** Java 保留触发恢复的原因；首次成功初始化没有失败原因，只有 ready 能携带 null。 */
export type StorageRecovery = {
  id: string;
  historyAvailable: boolean;
  backupAvailable: boolean;
  rebuilt: boolean;
} & (
  | { phase: "rebuilding" | "blocked"; reason: StorageRecoveryReason }
  | { phase: "ready"; reason: StorageRecoveryReason | null }
);

/** Schema 不兼容无法通过重复初始化修复，其他阻断允许用户调整环境后再试。 */
export function canRetryStorageRecovery(recovery: StorageRecovery): boolean {
  return recovery.phase === "blocked" && recovery.reason !== "SCHEMA_VALIDATION_FAILED";
}

/** 文案只陈述当前初始化与服务端确认的历史可用性，不推断旧库可迁移或已保留。 */
export function storageRecoveryMessage(recovery: StorageRecovery): string {
  if (recovery.phase === "rebuilding") return "正在初始化本地数据…";
  if (recovery.phase === "ready")
    return recovery.historyAvailable
      ? "本地数据已就绪，历史记录可用"
      : "本地数据已就绪，历史记录暂不可用";
  switch (recovery.reason) {
    case "SCHEMA_VALIDATION_FAILED":
      return "本地数据结构不兼容，初始化已停止";
    case "INITIALIZATION_FAILED":
      return "本地数据初始化失败，请检查后重试";
    case "CORRUPT_DATABASE":
      return "本地数据库无法读取，初始化已停止";
    case "DISK_FULL":
      return "存储空间不足，请释放空间后重试";
    case "PERMISSION_DENIED":
      return "无法写入数据目录，请恢复目录权限后重试";
    case "INSTANCE_LOCKED":
      return "本地数据正在被另一实例使用，请稍后重试";
    default:
      return "本地数据暂不可用，请查看详情后重试";
  }
}
