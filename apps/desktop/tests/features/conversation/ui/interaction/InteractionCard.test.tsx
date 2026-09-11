// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { InteractionCard } from "@/features/conversation/ui/interaction/InteractionCard";
import { useInteractionController } from "@/features/conversation/application/useInteractionController";
import type {
  InteractionAnswer,
  InteractionPort,
  InteractionSnapshot,
} from "@/features/conversation/application/interactionPort";

const REQUEST = {
  requestId: "req_demo",
  threadId: "thr_one",
  turnId: "turn_one",
  toolCallId: "call_one",
  questions: [
    {
      questionId: "scope",
      prompt: "第一版采用哪种配置范围？",
      type: "single" as const,
      required: true,
      allowFreeText: true,
      options: [
        { optionId: "global", label: "全局统一", description: "所有任务共用", recommended: true },
        { optionId: "project", label: "按项目覆盖" },
      ],
    },
    {
      questionId: "roles",
      prompt: "需要哪些角色？",
      type: "multiple" as const,
      required: false,
      allowFreeText: true,
      options: [
        { optionId: "reviewer", label: "审阅者" },
        { optionId: "builder", label: "执行者" },
      ],
    },
    {
      questionId: "notes",
      prompt: "还有什么限制？",
      type: "text" as const,
      required: false,
      allowFreeText: true,
    },
  ],
  answers: [],
  status: "pending" as const,
  revision: 1,
  createdAt: "2026-09-10T00:00:00Z",
  updatedAt: "2026-09-10T00:00:00Z",
};

/** 通过 port 边界模拟服务端快照，不把状态偷渡到组件内部。 */
function createPort(initial?: Partial<InteractionSnapshot>): InteractionPort {
  let snapshot: InteractionSnapshot = {
    threadId: "thr_one",
    eventSequence: 1,
    request: REQUEST,
    draft: { revision: 1, answers: [] },
    ...initial,
  };
  return {
    read: vi.fn(async () => snapshot),
    subscribe: vi.fn(() => () => undefined),
    saveDraft: vi.fn(async ({ answers }: { answers: readonly InteractionAnswer[] }) => {
      snapshot = {
        ...snapshot,
        draft: { revision: (snapshot.draft?.revision ?? 1) + 1, answers },
        eventSequence: snapshot.eventSequence + 1,
      };
      return snapshot;
    }),
    submit: vi.fn(async ({ answers }: { answers: readonly InteractionAnswer[] }) => {
      snapshot = {
        ...snapshot,
        request: snapshot.request === null ? null : { ...snapshot.request, status: "answered" },
        draft: { revision: (snapshot.draft?.revision ?? 1) + 1, answers },
        eventSequence: snapshot.eventSequence + 1,
      };
      return snapshot;
    }),
    cancel: vi.fn(async () => snapshot),
  };
}

/** 让 UI 测试复用与生产相同的 port/controller 注入边界。 */
function Harness({ port }: { port: InteractionPort }) {
  return (
    <InteractionCard
      controller={useInteractionController({ threadId: "thr_one", visible: true, port })}
    />
  );
}

describe("InteractionCard", () => {
  afterEach(() => cleanup());

  it("exposes required semantics on a single-choice group without requiring each radio", async () => {
    render(<Harness port={createPort()} />);
    const group = await screen.findByRole("radiogroup", { name: /第一版采用哪种配置范围？/ });

    expect(group).toHaveAttribute("aria-required", "true");
    const describedBy = group.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy ?? "")).toHaveAttribute("aria-label", "必填");
    for (const radio of screen.getAllByRole("radio")) expect(radio).not.toHaveAttribute("required");
  });

  it("associates a required multi-choice question with its explanation, not each checkbox", async () => {
    const request = {
      ...REQUEST,
      questions: [{ ...REQUEST.questions[1]!, required: true as const }, REQUEST.questions[2]!],
    };
    render(<Harness port={createPort({ request })} />);
    const group = await screen.findByRole("group", { name: /需要哪些角色？/ });

    const describedBy = group.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy ?? "")).toHaveAttribute("aria-label", "必填");
    for (const checkbox of screen.getAllByRole("checkbox"))
      expect(checkbox).not.toHaveAttribute("required");
  });

  it("exposes required semantics on free-text questions", async () => {
    const request = {
      ...REQUEST,
      questions: [{ ...REQUEST.questions[2]!, required: true as const }],
    };
    render(<Harness port={createPort({ request })} />);
    const textarea = await screen.findByRole("textbox", { name: /还有什么限制？/ });

    expect(textarea).toHaveAttribute("aria-required", "true");
    expect(textarea).toHaveAttribute("required");
    const describedBy = textarea.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(document.getElementById(describedBy ?? "")).toHaveAttribute("aria-label", "必填");
  });

  it("does not preselect recommended answers and submits stable answer DTOs", async () => {
    const user = userEvent.setup();
    const port = createPort();
    render(<Harness port={port} />);
    expect(await screen.findByRole("radio", { name: /全局统一/ })).not.toBeChecked();
    await user.click(screen.getByRole("radio", { name: /全局统一/ }));
    await user.click(screen.getByRole("button", { name: "下一题" }));
    await user.click(screen.getByRole("button", { name: "跳过" }));
    await user.click(screen.getByRole("button", { name: "跳过" }));
    await user.click(screen.getByRole("button", { name: "提交" }));
    await waitFor(() =>
      expect(port.submit).toHaveBeenCalledWith(
        expect.objectContaining({
          answers: expect.arrayContaining([
            { questionId: "scope", optionIds: ["global"], freeText: null, skipped: false },
            { questionId: "roles", optionIds: [], freeText: null, skipped: true },
          ]),
        }),
      ),
    );
  });

  it("keeps an optional other answer and renders the answered summary", async () => {
    const user = userEvent.setup();
    const port = createPort();
    render(<Harness port={port} />);
    await user.click(await screen.findByRole("radio", { name: /项目覆盖/ }));
    await user.click(screen.getByRole("button", { name: "下一题" }));
    await user.click(screen.getByRole("checkbox", { name: /其他答案/ }));
    await user.type(screen.getByRole("textbox", { name: "其他答案" }), "仅当前仓库");
    await waitFor(() =>
      expect(port.saveDraft).toHaveBeenCalledWith(
        expect.objectContaining({
          answers: expect.arrayContaining([
            { questionId: "scope", optionIds: ["project"], freeText: null, skipped: false },
          ]),
        }),
      ),
    );
    await user.click(screen.getByRole("button", { name: "下一题" }));
    await user.click(screen.getByRole("button", { name: "跳过" }));
    await user.click(screen.getByRole("button", { name: "提交" }));
    await waitFor(() =>
      expect(screen.getByRole("region", { name: "已回答的问题" })).toBeInTheDocument(),
    );
    expect(port.submit).toHaveBeenCalledWith(
      expect.objectContaining({
        answers: expect.arrayContaining([
          { questionId: "roles", optionIds: [], freeText: "仅当前仓库", skipped: false },
        ]),
      }),
    );
  });

  it("preserves other text when a multiple-choice option is selected", async () => {
    const user = userEvent.setup();
    const port = createPort();
    render(<Harness port={port} />);
    await user.click(await screen.findByRole("radio", { name: /项目覆盖/ }));
    await user.click(screen.getByRole("button", { name: "下一题" }));
    await user.click(screen.getByRole("checkbox", { name: /其他答案/ }));
    const otherInput = screen.getByRole("textbox", { name: "其他答案" });
    await user.type(otherInput, "需要兼容旧版");
    await user.click(screen.getByRole("checkbox", { name: /审阅者/ }));

    expect(screen.getByRole("checkbox", { name: /审阅者/ })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /其他答案/ })).toBeChecked();
    expect(otherInput).toHaveValue("需要兼容旧版");
    await waitFor(() =>
      expect(port.saveDraft).toHaveBeenCalledWith(
        expect.objectContaining({
          answers: expect.arrayContaining([
            {
              questionId: "roles",
              optionIds: ["reviewer"],
              freeText: "需要兼容旧版",
              skipped: false,
            },
          ]),
        }),
      ),
    );
  });

  it("collapses a pending request without presenting it as answered or cancelling it", async () => {
    const user = userEvent.setup();
    const port = createPort();
    render(<Harness port={port} />);
    await user.click(await screen.findByRole("button", { name: "收起问题" }));
    expect(screen.getByRole("region", { name: "待回答的问题" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "已回答的问题" })).not.toBeInTheDocument();
    expect(port.cancel).not.toHaveBeenCalled();
  });

  it("按钮获得焦点时按 Enter 保留原生按钮行为，不触发全局分页提交", async () => {
    const user = userEvent.setup();
    const port = createPort();
    render(<Harness port={port} />);
    await user.click(await screen.findByRole("button", { name: "下一题" }));
    const skip = screen.getByRole("button", { name: "跳过" });
    skip.focus();
    await user.keyboard("{Enter}");
    expect(screen.getByText("还有什么限制？")).toBeInTheDocument();
    expect(port.submit).not.toHaveBeenCalled();
  });
});
