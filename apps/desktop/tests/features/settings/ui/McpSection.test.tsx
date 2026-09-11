// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { McpSection } from "@/features/settings/ui/mcp";
import type { McpServerProjection } from "@/features/settings/domain/types";
import type { SettingsPorts } from "@/features/settings/application/ports";

afterEach(cleanup);

const SERVER: McpServerProjection = {
  id: "mcp-files",
  mcpRevision: "mcp-files",
  name: "文件工具",
  transport: "stdio",
  endpoint: "npx",
  protocolVersion: "2025-06-18",
  args: ["-y", "@modelcontextprotocol/server-filesystem"],
  env: {},
  headers: {},
  auth: { kind: "none" },
  enabled: true,
  status: "unknown",
  tools: [],
};

/** MCP 单测只提供真实端口形状，未覆盖的动作仍明确记录为未调用。 */
function renderMcp(overrides: Partial<SettingsPorts> = {}, servers = [SERVER]): void {
  const ports: Pick<
    SettingsPorts,
    | "onSaveMcp"
    | "onDeleteMcp"
    | "onTestMcp"
    | "onCloseMcp"
    | "onReplaceCredential"
    | "onClearCredential"
  > = {
    onSaveMcp: vi.fn(async () => undefined),
    onDeleteMcp: vi.fn(async () => undefined),
    onTestMcp: vi.fn(async () => "connected" as const),
    onCloseMcp: vi.fn(async () => undefined),
    onReplaceCredential: vi.fn(async () => undefined),
    onClearCredential: vi.fn(async () => undefined),
  };
  render(<McpSection servers={servers} {...ports} {...overrides} />);
}

describe("McpSection", () => {
  it("keeps the empty state quiet with one primary add action", () => {
    renderMcp({}, []);

    expect(screen.getAllByRole("button", { name: "新增服务" })).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "添加服务" })).toBeNull();
  });

  it("keeps the enable switch beside the server name and saves the real state", async () => {
    const user = userEvent.setup();
    const onSaveMcp = vi.fn(async () => undefined);
    renderMcp({ onSaveMcp });

    await user.click(screen.getByRole("switch", { name: "文件工具：已启用" }));

    await waitFor(() =>
      expect(onSaveMcp).toHaveBeenCalledWith(
        expect.objectContaining({
          mcpRevision: "mcp-files",
          enabled: false,
        }),
      ),
    );
  });

  it("uses a segmented transport choice and generates the credential reference", async () => {
    const user = userEvent.setup();
    const onReplaceCredential = vi.fn(async () => undefined);
    renderMcp({ onReplaceCredential }, []);

    await user.click(screen.getByRole("button", { name: "新增服务" }));
    await user.type(screen.getByLabelText("名称"), "远程工具");
    const httpTransport = screen.getByRole("radio", { name: "Streamable HTTP" });
    const stdioTransport = screen.getByRole("radio", { name: "本地 STDIO" });
    await user.click(httpTransport);
    await user.keyboard("{ArrowLeft}");
    expect(stdioTransport).toHaveAttribute("aria-checked", "true");
    await user.click(httpTransport);
    await user.type(screen.getByLabelText("服务地址"), "https://mcp.example.com/mcp");
    expect(screen.queryByLabelText("Credential ref")).toBeNull();

    await user.click(screen.getByRole("button", { name: "高级设置" }));
    await user.click(screen.getByRole("combobox", { name: "认证方式" }));
    await user.click(screen.getByRole("option", { name: "Bearer Token" }));
    const secret = screen.getByLabelText("API key / token");
    await user.type(secret, "token-value");
    await user.click(screen.getByRole("button", { name: "保存或替换密钥" }));

    await waitFor(() => expect(onReplaceCredential).toHaveBeenCalledTimes(1));
    expect(onReplaceCredential).toHaveBeenCalledWith(
      expect.stringMatching(/^cred_mcp_[a-z0-9]+$/u),
      "token-value",
    );
  });

  it("keeps a draft open after validation or save failure", async () => {
    const user = userEvent.setup();
    const onSaveMcp = vi.fn(async () => {
      throw new Error("sidecar unavailable");
    });
    renderMcp({ onSaveMcp }, []);

    await user.click(screen.getByRole("button", { name: "新增服务" }));
    const name = screen.getByLabelText("名称");
    await user.type(name, "稍后重试");
    await user.type(screen.getByLabelText("启动命令"), "node");
    await user.click(screen.getByRole("button", { name: "保存服务" }));

    await waitFor(() => expect(onSaveMcp).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("dialog", { name: "新增 MCP 服务" })).toBeDefined();
    expect(name).toHaveValue("稍后重试");
    expect(screen.getByRole("status")).toHaveTextContent("MCP 服务保存失败");
  });

  /** 保存期间锁住离开路径，确保异步持久化完成前草稿仍可见且不会重复提交。 */
  it("keeps the editor open while saving", async () => {
    const user = userEvent.setup();
    let release!: () => void;
    const onSaveMcp = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    renderMcp({ onSaveMcp }, []);

    await user.click(screen.getByRole("button", { name: "新增服务" }));
    const dialog = screen.getByRole("dialog", { name: "新增 MCP 服务" });
    await user.type(within(dialog).getByLabelText("名称"), "等待保存");
    await user.type(within(dialog).getByLabelText("启动命令"), "node");
    await user.click(within(dialog).getByRole("button", { name: "保存服务" }));

    await waitFor(() => expect(onSaveMcp).toHaveBeenCalledTimes(1));
    expect(within(dialog).getByRole("button", { name: "处理中…" })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "取消" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog", { name: "新增 MCP 服务" })).toBeInTheDocument();

    release();
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "新增 MCP 服务" })).toBeNull());
  });

  it("focuses the name field first and returns focus to the add action on cancel", async () => {
    const user = userEvent.setup();
    const onSaveMcp = vi.fn(async () => undefined);
    renderMcp({ onSaveMcp }, []);
    const addButton = screen.getByRole("button", { name: "新增服务" });

    await user.click(addButton);
    expect(screen.getByLabelText("名称")).toHaveFocus();
    await user.click(screen.getByRole("button", { name: "取消" }));
    await waitFor(() => expect(addButton).toHaveFocus());

    await user.click(addButton);
    await user.type(screen.getByLabelText("名称"), "保存后返回");
    await user.type(screen.getByLabelText("启动命令"), "node");
    await user.click(screen.getByRole("button", { name: "保存服务" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "新增 MCP 服务" })).toBeNull());
    expect(addButton).toHaveFocus();
  });

  it("opens the advanced section and focuses its first error after a collapsed submit", async () => {
    const user = userEvent.setup();
    renderMcp({}, []);

    await user.click(screen.getByRole("button", { name: "新增服务" }));
    await user.type(screen.getByLabelText("名称"), "带认证工具");
    await user.click(screen.getByRole("radio", { name: "Streamable HTTP" }));
    await user.type(screen.getByLabelText("服务地址"), "https://mcp.example.com/mcp");
    await user.click(screen.getByRole("button", { name: "高级设置" }));
    await user.click(screen.getByRole("combobox", { name: "认证方式" }));
    await user.click(screen.getByRole("option", { name: "自定义 Header" }));
    await user.click(screen.getByRole("button", { name: "高级设置" }));
    await user.click(screen.getByRole("button", { name: "保存服务" }));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "高级设置" })).toHaveAttribute(
        "aria-expanded",
        "true",
      ),
    );
    expect(screen.getByLabelText("Header 名称")).toHaveFocus();
  });

  /** 删除请求成功后才退出确认框，确保列表动作不会被提前视为完成。 */
  it("closes the delete confirmation after a successful delete", async () => {
    const user = userEvent.setup();
    const onDeleteMcp = vi.fn(async () => undefined);
    renderMcp({ onDeleteMcp });

    await user.click(screen.getByRole("button", { name: "文件工具 更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "删除" }));
    const dialog = screen.getByRole("alertdialog", { name: "删除 MCP 服务？" });
    await user.click(within(dialog).getByRole("button", { name: "删除" }));

    await waitFor(() => expect(onDeleteMcp).toHaveBeenCalledWith("mcp-files"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
  });

  /** 删除失败保留确认框，pending 期间锁住取消与重复删除，避免用户丢失可重试路径。 */
  it("keeps the delete confirmation open after a failed or pending delete", async () => {
    const user = userEvent.setup();
    let release!: () => void;
    const onDeleteMcp = vi.fn(
      () =>
        new Promise<void>((_, reject) => {
          release = () => reject(new Error("sidecar unavailable"));
        }),
    );
    renderMcp({ onDeleteMcp });

    await user.click(screen.getByRole("button", { name: "文件工具 更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "删除" }));
    let dialog = screen.getByRole("alertdialog", { name: "删除 MCP 服务？" });
    const confirm = within(dialog).getByRole("button", { name: "删除" });
    await user.click(confirm);
    expect(within(dialog).getByRole("button", { name: "取消" })).toBeDisabled();
    expect(confirm).toBeDisabled();
    release();

    await waitFor(() => expect(onDeleteMcp).toHaveBeenCalledTimes(1));
    dialog = screen.getByRole("alertdialog", { name: "删除 MCP 服务？" });
    expect(within(dialog).getByRole("button", { name: "删除" })).toBeEnabled();
  });
});
