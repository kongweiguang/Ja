// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useRef, useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ProjectEditDialog, type ProjectEditDialogProps } from "@/features/navigation";
import { WorkspaceProjectUpdateError, type WorkspaceProjection } from "@/features/workspace";

const PROJECT: WorkspaceProjection = {
  kind: "project",
  workspaceId: "ws_project",
  rootPath: "C:\\work\\demo",
  displayName: "demo",
  trust: "trusted",
  revision: 7,
};

/** 为每个 UI 场景提供真实 revision 与独立回调，避免状态断言依赖空壳投影。 @author kongweiguang */
function dialogProps(overrides: Partial<ProjectEditDialogProps> = {}): ProjectEditDialogProps {
  return {
    project: PROJECT,
    busy: false,
    onClose: vi.fn(),
    onSave: vi.fn(async () => undefined),
    onRecheck: vi.fn(async () => PROJECT),
    onChooseDirectory: vi.fn(async () => null),
    ...overrides,
  };
}

/** 使用控制器的真实错误类型覆盖 CAS 冲突与写入结果不明的显式复核流程。 @author kongweiguang */
function projectUpdateError(
  reason: "revision_conflict" | "result_unknown",
  currentWorkspace?: WorkspaceProjection,
): WorkspaceProjectUpdateError {
  return new WorkspaceProjectUpdateError(
    reason,
    "项目资料已变化，请重新检查后再保存。",
    currentWorkspace,
  );
}

/** 模拟 composition 接收关闭请求后卸载弹窗，并提供侧栏项目行作为真实焦点归还目标。 @author kongweiguang */
function StatefulDialogFixture(props: ProjectEditDialogProps): ReactElement {
  const [open, setOpen] = useState(props.project !== undefined);
  const originRef = useRef<HTMLButtonElement>(null);
  const close = (): void => {
    props.onClose();
    setOpen(false);
  };
  /** 重开同一身份用于验证前一 Dialog session 的原生异步结果不会污染新草稿。 @author kongweiguang */
  const reopen = (): void => setOpen(true);
  const restoreFocus = (): void => {
    props.onRestoreFocus?.();
    originRef.current?.focus();
  };

  return (
    <>
      <button ref={originRef} type="button">
        项目行
      </button>
      <button type="button" onClick={reopen}>
        重新打开编辑
      </button>
      <ProjectEditDialog
        {...props}
        project={open ? props.project : undefined}
        onClose={close}
        onRestoreFocus={restoreFocus}
      />
    </>
  );
}

describe("ProjectEditDialog", () => {
  afterEach(cleanup);

  /** picker 的 null 表示取消；保存失败时名称和长路径草稿都留在原 Dialog 内。 */
  it("keeps the editable draft when folder selection is cancelled or save fails", async () => {
    const user = userEvent.setup();
    const props = dialogProps({
      onSave: vi.fn(async () => {
        throw new Error("项目版本已变化，请重新检查后保存。");
      }),
    });
    render(<ProjectEditDialog {...props} />);

    const name = screen.getByRole("textbox", { name: "项目名称" });
    const path = screen.getByRole("textbox", { name: "项目目录" });
    await user.clear(name);
    await user.type(name, "新的显示名");
    await user.clear(path);
    await user.type(path, "D:\\资料 目录\\项目");
    await user.click(screen.getByRole("button", { name: "选择文件夹" }));

    expect(props.onChooseDirectory).toHaveBeenCalledExactlyOnceWith("D:\\资料 目录\\项目");
    expect(path).toHaveValue("D:\\资料 目录\\项目");

    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "项目版本已变化，请重新检查后保存。",
    );
    expect(name).toHaveValue("新的显示名");
    expect(path).toHaveValue("D:\\资料 目录\\项目");
    expect(props.onSave).toHaveBeenCalledExactlyOnceWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: PROJECT.revision,
      displayName: "新的显示名",
      cwd: "D:\\资料 目录\\项目",
    });
    expect(props.onClose).not.toHaveBeenCalled();
  });

  /** pending 同步 ref 防连点；保存完成前 Escape 不关闭 Dialog，以便错误时保留编辑内容。 */
  it("blocks duplicate saves and prevents closing while the request is pending", async () => {
    const user = userEvent.setup();
    let finishSave!: () => void;
    const save = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishSave = resolve;
        }),
    );
    const props = dialogProps({ onSave: save });
    render(<ProjectEditDialog {...props} />);

    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(save).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "保存中…" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("heading", { name: "编辑项目" })).toBeVisible();
    expect(props.onClose).not.toHaveBeenCalled();

    finishSave();
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
  });

  /** 一个 session 同时只允许一个原生 chooser，返回路径后回填一次且期间不能保存或改草稿。 @author kongweiguang */
  it("blocks duplicate directory pickers and applies the selected folder once", async () => {
    let finishPicker!: (path: string | null) => void;
    const chooseDirectory = vi.fn(
      () =>
        new Promise<string | null>((resolve) => {
          finishPicker = resolve;
        }),
    );
    const props = dialogProps({ onChooseDirectory: chooseDirectory });
    render(<ProjectEditDialog {...props} />);
    const name = screen.getByRole("textbox", { name: "项目名称" });
    const path = screen.getByRole("textbox", { name: "项目目录" });
    const picker = screen.getByRole("button", { name: "选择文件夹" });

    fireEvent.click(picker);
    fireEvent.click(picker);

    expect(chooseDirectory).toHaveBeenCalledExactlyOnceWith(PROJECT.rootPath);
    expect(screen.getByRole("button", { name: "选择中…" })).toBeDisabled();
    expect(name).toBeDisabled();
    expect(path).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
    fireEvent.submit(path.closest("form")!);
    expect(props.onSave).not.toHaveBeenCalled();

    await act(async () => finishPicker("D:\\new folder\\project"));
    expect(path).toHaveValue("D:\\new folder\\project");
    expect(screen.getByRole("button", { name: "选择文件夹" })).toBeEnabled();
  });

  /** CAS 冲突后必须显式复核；仅修改名称时采用远端目录和 revision，避免回写旧 cwd。 @author kongweiguang */
  it("requires recheck after conflict and merges a dirty name with the authoritative directory", async () => {
    const user = userEvent.setup();
    const current = {
      ...PROJECT,
      displayName: "他人更新的名称",
      rootPath: "D:\\authoritative project",
      revision: 8,
    };
    let saveAttempts = 0;
    const onSave = vi.fn(async () => {
      saveAttempts += 1;
      if (saveAttempts === 1) throw projectUpdateError("revision_conflict", current);
    });
    const onRecheck = vi.fn(async () => current);
    const props = dialogProps({ onSave, onRecheck });
    render(<ProjectEditDialog {...props} />);

    const name = screen.getByRole("textbox", { name: "项目名称" });
    const path = screen.getByRole("textbox", { name: "项目目录" });
    await user.clear(name);
    await user.type(name, "我的名称草稿");
    await user.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("项目资料已变化");
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
    expect(screen.getByText("最新目录：D:\\authoritative project")).toBeVisible();
    fireEvent.submit(path.closest("form")!);
    expect(onSave).toHaveBeenCalledOnce();

    await user.click(screen.getByRole("button", { name: "重新检查" }));
    expect(await screen.findByRole("status")).toHaveTextContent(
      "已读取最新资料，已保留你的修改，请核对后保存。",
    );
    expect(name).toHaveValue("我的名称草稿");
    expect(path).toHaveValue(current.rootPath);
    expect(onRecheck).toHaveBeenCalledExactlyOnceWith(PROJECT.workspaceId);

    await user.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(props.onClose).toHaveBeenCalledOnce());
    expect(onSave).toHaveBeenLastCalledWith({
      workspaceId: PROJECT.workspaceId,
      expectedRevision: current.revision,
      displayName: "我的名称草稿",
      cwd: current.rootPath,
    });
  });

  /** 用户只改目录时保留该 cwd 草稿，同时把未改名称更新到权威版本。 @author kongweiguang */
  it("keeps a dirty directory and adopts the latest untouched display name", async () => {
    const user = userEvent.setup();
    const current = {
      ...PROJECT,
      displayName: "他人更新的名称",
      rootPath: "D:\\latest root",
      revision: 10,
    };
    const onSave = vi.fn(async () => {
      throw projectUpdateError("result_unknown");
    });
    const props = dialogProps({ onSave, onRecheck: vi.fn(async () => current) });
    render(<ProjectEditDialog {...props} />);

    const name = screen.getByRole("textbox", { name: "项目名称" });
    const path = screen.getByRole("textbox", { name: "项目目录" });
    await user.clear(path);
    await user.type(path, "E:\\my directory draft");
    await user.click(screen.getByRole("button", { name: "保存" }));
    await screen.findByRole("alert");
    await user.click(screen.getByRole("button", { name: "重新检查" }));
    await screen.findByRole("status");

    expect(name).toHaveValue(current.displayName);
    expect(path).toHaveValue("E:\\my directory draft");
  });

  /** 复核读取失败时不丢字段、不解除旧 revision 门禁，允许再次读取权威资料。 @author kongweiguang */
  it("preserves the draft and save lock when recheck fails, then allows a retry", async () => {
    const user = userEvent.setup();
    const current = { ...PROJECT, rootPath: "F:\\current root", revision: 11 };
    const onSave = vi.fn(async () => {
      throw projectUpdateError("result_unknown");
    });
    const onRecheck = vi
      .fn<() => Promise<WorkspaceProjection>>()
      .mockRejectedValueOnce(new Error("项目资料暂时不可读取。"))
      .mockResolvedValueOnce(current);
    const props = dialogProps({ onSave, onRecheck });
    render(<ProjectEditDialog {...props} />);

    const name = screen.getByRole("textbox", { name: "项目名称" });
    const path = screen.getByRole("textbox", { name: "项目目录" });
    await user.clear(name);
    await user.type(name, "保留的名称");
    await user.click(screen.getByRole("button", { name: "保存" }));
    await screen.findByRole("alert");
    await user.click(screen.getByRole("button", { name: "重新检查" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("项目资料暂时不可读取。");
    expect(name).toHaveValue("保留的名称");
    expect(path).toHaveValue(PROJECT.rootPath);
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
    expect(onSave).toHaveBeenCalledOnce();

    await user.click(screen.getByRole("button", { name: "重新检查" }));
    await screen.findByRole("status");
    expect(name).toHaveValue("保留的名称");
    expect(path).toHaveValue(current.rootPath);
  });

  /** 文件 flush 对话框的取消以 AbortError 回传；弹窗保留草稿且不展示成保存错误。 */
  it("keeps the draft when the directory rebind confirmation is cancelled", async () => {
    const user = userEvent.setup();
    const props = dialogProps({
      onSave: vi.fn(async () => {
        throw new DOMException("cancelled", "AbortError");
      }),
    });
    render(<ProjectEditDialog {...props} />);
    const name = screen.getByRole("textbox", { name: "项目名称" });
    await user.clear(name);
    await user.type(name, "待保留名称");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "保存" })).toBeEnabled());
    expect(screen.getByRole("heading", { name: "编辑项目" })).toBeVisible();
    expect(name).toHaveValue("待保留名称");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(props.onClose).not.toHaveBeenCalled();
  });

  /** Escape 关闭由 composition 控制的弹窗后，焦点显式返回触发编辑的项目行。 */
  it("restores the project row focus after Escape closes the editor", async () => {
    const user = userEvent.setup();
    const restoreFocus = vi.fn();
    const props = dialogProps({ onRestoreFocus: restoreFocus });
    render(<StatefulDialogFixture {...props} />);

    await user.keyboard("{Escape}");

    await waitFor(() => expect(restoreFocus).toHaveBeenCalledOnce());
    expect(screen.getByRole("button", { name: "项目行" })).toHaveFocus();
    expect(props.onClose).toHaveBeenCalledOnce();
  });

  /** 取消按钮和 Escape 使用同一 Dialog close-autofocus 路径，键盘焦点不丢失。 */
  it("restores the project row focus after Cancel closes the editor", async () => {
    const user = userEvent.setup();
    const restoreFocus = vi.fn();
    const props = dialogProps({ onRestoreFocus: restoreFocus });
    render(<StatefulDialogFixture {...props} />);

    await user.click(screen.getByRole("button", { name: "取消" }));

    await waitFor(() => expect(restoreFocus).toHaveBeenCalledOnce());
    expect(screen.getByRole("button", { name: "项目行" })).toHaveFocus();
    expect(props.onClose).toHaveBeenCalledOnce();
  });

  /** 关闭 picker 所属 Dialog 后再开同项目时，旧 native 结果不能覆盖新 session 的用户输入。 */
  it("ignores a late folder-picker result after the editor closes and reopens", async () => {
    const user = userEvent.setup();
    let finishPicker!: (path: string | null) => void;
    const props = dialogProps({
      onChooseDirectory: vi.fn(
        () =>
          new Promise<string | null>((resolve) => {
            finishPicker = resolve;
          }),
      ),
    });
    render(<StatefulDialogFixture {...props} />);

    await user.click(screen.getByRole("button", { name: "选择文件夹" }));
    await user.click(screen.getByRole("button", { name: "取消" }));
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "编辑项目" })).not.toBeInTheDocument(),
    );
    await user.click(screen.getByRole("button", { name: "重新打开编辑" }));

    const path = screen.getByRole("textbox", { name: "项目目录" });
    await user.clear(path);
    await user.type(path, "E:\\new draft");
    await act(async () => finishPicker("D:\\late picker result"));

    expect(path).toHaveValue("E:\\new draft");
    expect(screen.getByRole("heading", { name: "编辑项目" })).toBeVisible();
  });

  /** 关闭后到达的权威复核响应只能结束旧 session，不能更新同项目新打开的草稿。 @author kongweiguang */
  it("ignores a late authoritative recheck after the editor closes and reopens", async () => {
    const user = userEvent.setup();
    let finishRecheck!: (project: WorkspaceProjection) => void;
    const onRecheck = vi.fn(
      () =>
        new Promise<WorkspaceProjection>((resolve) => {
          finishRecheck = resolve;
        }),
    );
    const onSave = vi.fn(async () => {
      throw projectUpdateError("revision_conflict");
    });
    const props = dialogProps({ onSave, onRecheck });
    render(<StatefulDialogFixture {...props} />);

    await user.click(screen.getByRole("button", { name: "保存" }));
    await screen.findByRole("alert");
    await user.click(screen.getByRole("button", { name: "重新检查" }));
    expect(screen.getByRole("button", { name: "检查中…" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "取消" }));
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "编辑项目" })).not.toBeInTheDocument(),
    );
    await user.click(screen.getByRole("button", { name: "重新打开编辑" }));

    const name = screen.getByRole("textbox", { name: "项目名称" });
    await user.clear(name);
    await user.type(name, "新 session 草稿");
    await act(async () =>
      finishRecheck({ ...PROJECT, displayName: "迟到的权威名称", revision: 9 }),
    );

    expect(name).toHaveValue("新 session 草稿");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "编辑项目" })).toBeVisible();
  });
});
