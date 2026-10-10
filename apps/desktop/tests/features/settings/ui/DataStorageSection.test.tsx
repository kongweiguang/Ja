// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { DataStorageSection } from "@/features/settings/ui/storage";

afterEach(cleanup);

/** 目录查询失败不能遮住服务端已签发的阻断原因和恢复动作。 */
it("keeps blocked recovery actionable without storage directory details", () => {
  render(
    <DataStorageSection
      recovery={{
        id: "recovery_without_directory",
        phase: "blocked",
        reason: "CORRUPT_DATABASE",
        historyAvailable: false,
        backupAvailable: false,
        rebuilt: false,
      }}
      error="存储信息读取失败，请重试"
      onRetry={vi.fn()}
    />,
  );
  expect(screen.getByText("本地数据库无法读取，初始化已停止")).toBeVisible();
  expect(screen.getByRole("alert")).toHaveTextContent("存储信息读取失败，请重试");
  expect(screen.getByRole("button", { name: "重试" })).toBeEnabled();
});

/** 原生操作被拒绝时，恢复流程不能报告成功；待处理反馈可避免重复点击。 */
it("keeps a failed recovery actionable and never reports successful restoration", async () => {
  const retry = vi.fn(async () => {
    throw new Error("opaque");
  });
  render(
    <DataStorageSection
      info={{
        dataPath: "C:\\fixture",
        logPath: null,
        storageRecovery: {
          id: "11111111-1111-1111-1111-111111111111",
          phase: "blocked",
          reason: "DISK_FULL",
          historyAvailable: true,
          backupAvailable: false,
          rebuilt: false,
        },
      }}
      onRetry={retry}
    />,
  );
  expect(screen.getByText("存储空间不足，请释放空间后重试")).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "重试" }));
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("仍未恢复"));
  expect(retry).toHaveBeenCalledTimes(1);
  expect(screen.queryByText("已恢复使用")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "打开备份" })).not.toBeInTheDocument();
});

/** 不兼容结构必须失败关闭，且不能提供不会改变结果的重复重试。 */
it("reports incompatible old data without claiming recovery or a backup", () => {
  render(
    <DataStorageSection
      info={{
        dataPath: "C:\\fixture",
        logPath: null,
        storageRecovery: {
          id: "11111111-1111-1111-1111-111111111111",
          phase: "blocked",
          reason: "SCHEMA_VALIDATION_FAILED",
          historyAvailable: false,
          backupAvailable: false,
          rebuilt: false,
        },
      }}
      onRetry={vi.fn()}
    />,
  );
  expect(screen.getByText("本地数据结构不兼容，初始化已停止")).toBeVisible();
  expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
  expect(screen.queryByText("已恢复使用")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "打开备份" })).not.toBeInTheDocument();
});

/** 备份操作只携带 Java 的不透明身份；历史可用性按服务端当前状态展示。 */
it("opens the backup and reports unavailable history without inferring retained data", async () => {
  const open = vi.fn(async () => undefined);
  render(
    <DataStorageSection
      info={{
        dataPath: "C:\\fixture",
        logPath: null,
        storageRecovery: {
          id: "11111111-1111-1111-1111-111111111111",
          phase: "ready",
          reason: "CORRUPT_DATABASE",
          historyAvailable: false,
          backupAvailable: true,
          rebuilt: true,
        },
      }}
      onOpenBackup={open}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "打开备份" }));
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("已打开备份目录"));
  expect(open).toHaveBeenCalledWith("11111111-1111-1111-1111-111111111111");
  expect(screen.getByText("历史记录")).toBeVisible();
  expect(screen.getByText("暂不可用")).toBeVisible();
});
