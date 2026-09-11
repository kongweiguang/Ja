// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  useGoalController,
  type GoalEvent,
  type GoalPort,
  type GoalReadModel,
  type PlanReadModel,
} from "@/features/goals";
import { planProgressFromRevision } from "@/features/goals";
import { goalModel } from "./goalFixtures";

interface Deferred<T> {
  readonly promise: Promise<T>;
  resolve(value: T): void;
}

/** 用可控 ACK 验证单飞，不依赖计时器或 React 调度速度。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

/** 模拟 WebView adapter 的结构化错误，不依赖跨 realm 的 Error prototype。 */
function goalPortError(code: string): Error & { readonly code: string } {
  return Object.assign(new Error(code), { code });
}

/** Goal fake 保留一个权威快照与事件入口，避免测试自行拼接 controller state。 */
function goalPort(initial: GoalReadModel = goalModel()): {
  readonly port: GoalPort;
  setCurrent(next: GoalReadModel): void;
  emit(event: GoalEvent): void;
} {
  let current = initial;
  const fallbackPlan = goalModel();
  const listeners = new Set<(event: GoalEvent) => void>();
  const same = async (): Promise<GoalReadModel> => current;
  /** Plan fake 从同一 fixture 提取独立投影，但不把 Plan mutation 伪装成 Goal ACK。 */
  const samePlan = async (): Promise<PlanReadModel> => ({
    plan: current.planState ?? fallbackPlan.planState!,
    progress: planProgressFromRevision(current.plan ?? fallbackPlan.plan),
    revision: current.plan ?? fallbackPlan.plan,
    revisionHydrationRequired: false,
    draft: current.planState === null ? fallbackPlan.draft : current.draft,
    approvedPlanRevisionId:
      (current.plan ?? fallbackPlan.plan)?.approvedAt === null
        ? null
        : ((current.plan ?? fallbackPlan.plan)?.planRevisionId ?? null),
    eventSequence:
      current.planEventSequence ?? current.planState?.revision ?? fallbackPlan.planState!.revision,
  });
  const port: GoalPort = {
    read: vi.fn(async () => current),
    readPlan: vi.fn(samePlan),
    currentPlan: vi.fn(async () => undefined),
    observePlan: vi.fn(async ({ planId }) => ({
      observationId: `observe_${planId}_12345678`,
      plan: await samePlan(),
    })),
    unobservePlan: vi.fn(async () => undefined),
    observe: vi.fn(async ({ goalId }) => ({
      observationId: `observe_${goalId}`,
      goalRevision: current.goal.revision,
    })),
    unobserve: vi.fn(async () => undefined),
    revisions: vi.fn(async () => ({ items: current.plan === null ? [] : [current.plan] })),
    planRevisions: vi.fn(async () => ({ items: current.plan === null ? [] : [current.plan] })),
    readPlanEvidence: vi.fn(async () => ({ items: [] })),
    evidence: vi.fn(async () => ({ items: [] })),
    create: vi.fn(same),
    createPlan: vi.fn(samePlan),
    attachPlan: vi.fn(same),
    detachPlan: vi.fn(same),
    pause: vi.fn(same),
    resume: vi.fn(same),
    stop: vi.fn(same),
    saveDraft: vi.fn(samePlan),
    discardDraft: vi.fn(samePlan),
    propose: vi.fn(samePlan),
    pausePlan: vi.fn(samePlan),
    resumePlan: vi.fn(samePlan),
    stopPlan: vi.fn(samePlan),
    execute: vi.fn(samePlan),
    reject: vi.fn(samePlan),
    subscribe: vi.fn((listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    }),
  };
  return {
    port,
    setCurrent: (next) => {
      current = next;
    },
    emit: (event) => listeners.forEach((listener) => listener(event)),
  };
}

describe("useGoalController", () => {
  afterEach(cleanup);

  /** 隐藏或未选中 Plan capability 时只保留 Goal 轻量观察，详情 IO 必须严格为零。 */
  it("loads Plan details only while the Plan capability is visible", async () => {
    const full = goalModel();
    const fake = goalPort({
      ...full,
      planState: null,
      planEventSequence: undefined,
      plan: null,
      draft: null,
    });
    const { rerender, result, unmount } = renderHook(
      ({ detailsVisible }: { detailsVisible: boolean }) =>
        useGoalController({
          goalId: "goal_1",
          ownerThreadId: "thr_1",
          visible: true,
          detailsVisible,
          port: fake.port,
        }),
      { initialProps: { detailsVisible: false } },
    );

    await waitFor(() => expect(result.current.model?.goal.goalId).toBe("goal_1"));
    await waitFor(() => expect(fake.port.observe).toHaveBeenCalledOnce());
    expect(fake.port.readPlan).not.toHaveBeenCalled();
    expect(fake.port.planRevisions).not.toHaveBeenCalled();
    expect(fake.port.evidence).not.toHaveBeenCalled();

    rerender({ detailsVisible: true });
    await waitFor(() => expect(fake.port.readPlan).toHaveBeenCalledOnce());
    expect(fake.port.planRevisions).toHaveBeenCalledOnce();
    expect(fake.port.evidence).toHaveBeenCalledWith({
      goalId: "goal_1",
      goalDefinitionRevision: 1,
      planRevisionId: "planrev_2",
    });
    expect(fake.port.observe).toHaveBeenCalledOnce();
    expect(fake.port.unobserve).not.toHaveBeenCalled();

    rerender({ detailsVisible: false });
    await waitFor(() => expect(result.current.revisions).toEqual([]));
    expect(fake.port.observe).toHaveBeenCalledOnce();
    expect(fake.port.unobserve).not.toHaveBeenCalled();

    unmount();
    expect(fake.port.unobserve).toHaveBeenCalledOnce();
  });

  /** Goal-only 详情只读取当前 definition 的无 Plan 证据，不借 fallback Plan 制造额外 IO。 */
  it("loads Goal-only evidence with an explicit nullable Plan binding", async () => {
    const full = goalModel();
    const goalOnly = {
      ...full,
      goal: {
        ...full.goal,
        activePlanId: null,
        activePlanRevisionId: null,
        activePlanHash: null,
        currentStepId: null,
        completedRequiredSteps: 0,
        totalRequiredSteps: 0,
      },
      planState: null,
      planEventSequence: undefined,
      plan: null,
      draft: null,
    };
    const fake = goalPort(goalOnly);
    renderHook(() =>
      useGoalController({
        goalId: "goal_1",
        ownerThreadId: "thr_1",
        visible: true,
        detailsVisible: true,
        port: fake.port,
      }),
    );

    await waitFor(() =>
      expect(fake.port.evidence).toHaveBeenCalledWith({
        goalId: "goal_1",
        goalDefinitionRevision: 1,
        planRevisionId: null,
      }),
    );
    expect(fake.port.readPlan).not.toHaveBeenCalled();
    expect(fake.port.planRevisions).not.toHaveBeenCalled();
  });

  it("reads before observing and treats events as identities that trigger authoritative refresh", async () => {
    const fake = goalPort();
    const { result } = renderHook(() =>
      useGoalController({ goalId: "goal_1", visible: true, port: fake.port }),
    );

    await waitFor(() => expect(result.current.model?.goal.goalId).toBe("goal_1"));
    expect(fake.port.observe).toHaveBeenCalledWith({ goalId: "goal_1", expectedGoalRevision: 7 });
    const next = {
      ...goalModel("working"),
      goal: { ...goalModel("working").goal, revision: 8 },
    };
    fake.setCurrent(next);
    act(() => {
      fake.emit({
        method: "goal/activity",
        goalId: "goal_1",
        goalRevision: 8,
        eventSequence: 9,
        occurredAt: "2026-09-04T10:20:00+08:00",
      });
    });
    await waitFor(() => expect(result.current.model?.goal.revision).toBe(8));
  });

  it("merges a lightweight Plan event without rereading immutable details", async () => {
    const fake = goalPort();
    const { result } = renderHook(() =>
      useGoalController({
        goalId: "goal_1",
        ownerThreadId: "thr_1",
        visible: true,
        detailsVisible: false,
        port: fake.port,
      }),
    );

    await waitFor(() => expect(result.current.planModel?.plan.status).toBe("awaiting_approval"));
    const readsBeforeEvent = vi.mocked(fake.port.readPlan).mock.calls.length;
    const initialPlan = result.current.planModel!.plan;
    act(() => {
      fake.emit({
        method: "plan/changed",
        planId: initialPlan.planId,
        ownerThreadId: initialPlan.ownerThreadId,
        planRevision: initialPlan.revision,
        planEventSequence: result.current.planModel!.eventSequence + 1,
        plan: { ...initialPlan, status: "paused", revision: initialPlan.revision + 1 },
        progress: {
          currentStepId: "step_contract",
          currentStepTitle: "冻结契约",
          completedRequiredSteps: 2,
          totalRequiredSteps: 2,
        },
        goalRevision: 7,
        eventSequence: 8,
        occurredAt: "2026-09-04T10:21:00+08:00",
      });
    });

    await waitFor(() => expect(result.current.planModel?.plan.status).toBe("paused"));
    expect(result.current.planModel?.progress).toEqual({
      currentStepId: "step_contract",
      currentStepTitle: "冻结契约",
      completedRequiredSteps: 2,
      totalRequiredSteps: 2,
    });
    expect(fake.port.readPlan).toHaveBeenCalledTimes(readsBeforeEvent);
  });

  /** 提案尚未获准时 active identity 仍为空，轻量事件不能吞掉同 revision 的完整 ACK。 */
  it("hydrates a proposed revision when the event arrives before its mutation ACK", async () => {
    const initial = goalModel();
    const fake = goalPort(initial);
    const draft: PlanReadModel = {
      plan: { ...initial.planState!, status: "draft", activePlanRevisionId: null },
      progress: planProgressFromRevision(null),
      revision: null,
      revisionHydrationRequired: false,
      draft: null,
      approvedPlanRevisionId: null,
      eventSequence: 3,
    };
    vi.mocked(fake.port.currentPlan).mockResolvedValue(draft);
    vi.mocked(fake.port.observePlan).mockResolvedValue({
      observationId: "observe_plan_1_12345678",
      plan: draft,
    });
    const ack = deferred<PlanReadModel>();
    vi.mocked(fake.port.propose).mockReturnValue(ack.promise);
    vi.mocked(fake.port.readPlan).mockReturnValue(new Promise(() => {}));
    const { result } = renderHook(() =>
      useGoalController({ ownerThreadId: "thr_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.planModel?.plan.status).toBe("draft"));
    const proposed: PlanReadModel = {
      ...draft,
      plan: { ...draft.plan, status: "awaiting_approval", revision: draft.plan.revision + 1 },
      revision: initial.plan,
      eventSequence: 4,
    };
    let completed: Promise<boolean>;
    act(() => {
      completed = result.current.propose();
    });
    act(() =>
      fake.emit({
        method: "plan/changed",
        planId: draft.plan.planId,
        ownerThreadId: "thr_1",
        plan: proposed.plan,
        planEventSequence: 4,
        goalRevision: 0,
        eventSequence: 4,
        occurredAt: "2026-09-10T00:00:00Z",
      }),
    );
    await act(async () => {
      ack.resolve(proposed);
      await completed;
    });
    expect(result.current.planModel?.revision?.planRevisionId).toBe(initial.plan!.planRevisionId);
    expect(result.current.planModel?.revisionHydrationRequired).toBe(false);
  });

  it("sends Plan pause with the active run and Plan revision CAS", async () => {
    const initial = goalModel("working", "approved");
    const running = {
      ...initial,
      planState: {
        ...initial.planState!,
        status: "executing" as const,
        activePlanRevisionId: "planrev_2",
        activeRunId: "run_1",
      },
    };
    const fake = goalPort(running);
    const { result } = renderHook(() =>
      useGoalController({
        goalId: "goal_1",
        ownerThreadId: "thr_1",
        visible: true,
        port: fake.port,
      }),
    );

    await waitFor(() => expect(result.current.planModel?.plan.activeRunId).toBe("run_1"));
    await act(async () => {
      await expect(result.current.pausePlan()).resolves.toBe(true);
    });
    expect(fake.port.pausePlan).toHaveBeenCalledWith(
      expect.objectContaining({
        ownerThreadId: "thr_1",
        planId: "plan_1",
        runId: "run_1",
        expectedPlanRevision: 3,
        idempotencyKey: expect.stringMatching(/^goal-ui-pause-/),
      }),
    );
  });

  it("observes the visible Plan independently and releases only its own handle", async () => {
    const fake = goalPort();
    const { result, unmount } = renderHook(() =>
      useGoalController({
        goalId: "goal_1",
        ownerThreadId: "thr_1",
        visible: true,
        port: fake.port,
      }),
    );

    await waitFor(() => expect(result.current.planModel?.plan.planId).toBe("plan_1"));
    await waitFor(() => expect(fake.port.observePlan).toHaveBeenCalledOnce());
    expect(fake.port.observePlan).toHaveBeenCalledWith({
      ownerThreadId: "thr_1",
      planId: "plan_1",
    });

    unmount();
    await waitFor(() =>
      expect(fake.port.unobservePlan).toHaveBeenCalledWith({
        observationId: "observe_plan_1_12345678",
      }),
    );
  });

  it("discovers and observes a standalone Plan when the Thread has no Goal", async () => {
    const fake = goalPort();
    const standalone = {
      ...(await fake.port.readPlan({ ownerThreadId: "thr_1", planId: "plan_1" })),
    };
    vi.mocked(fake.port.currentPlan).mockResolvedValue(standalone);
    const { result } = renderHook(() =>
      useGoalController({ ownerThreadId: "thr_1", visible: true, port: fake.port }),
    );

    await waitFor(() => expect(result.current.planModel?.plan.planId).toBe("plan_1"));
    expect(fake.port.currentPlan).toHaveBeenCalledOnce();
    await waitFor(() => expect(fake.port.observePlan).toHaveBeenCalledOnce());
  });

  it("clears an old revision and hydrates the new immutable body even when details are hidden", async () => {
    const initial = goalModel();
    const updatedRevision = {
      ...initial.plan!,
      planRevisionId: "planrev_new",
      revisionNumber: 3,
    };
    const updated = {
      ...initial,
      planState: {
        ...initial.planState!,
        activePlanRevisionId: "planrev_new",
        revision: 4,
      },
      plan: updatedRevision,
      planEventSequence: 4,
    };
    const fake = goalPort(initial);
    const { result } = renderHook(() =>
      useGoalController({
        goalId: "goal_1",
        ownerThreadId: "thr_1",
        visible: true,
        detailsVisible: false,
        port: fake.port,
      }),
    );

    await waitFor(() =>
      expect(result.current.planModel?.revision?.planRevisionId).toBe("planrev_2"),
    );
    const readsBeforeEvent = vi.mocked(fake.port.readPlan).mock.calls.length;
    fake.setCurrent(updated);
    act(() => {
      fake.emit({
        method: "plan/changed",
        planId: "plan_1",
        ownerThreadId: "thr_1",
        planEventSequence: 4,
        planRevision: 4,
        plan: {
          ...result.current.planModel!.plan,
          activePlanRevisionId: "planrev_new",
          revision: 4,
        },
        progress: result.current.planModel!.progress,
        goalRevision: 7,
        eventSequence: 8,
        occurredAt: "2026-09-04T10:21:00+08:00",
      });
    });

    await waitFor(() => expect(result.current.planModel?.revisionHydrationRequired).toBe(false));
    expect(fake.port.readPlan).toHaveBeenCalledTimes(readsBeforeEvent + 1);
    expect(result.current.planModel?.revision?.planRevisionId).toBe("planrev_new");
  });

  it("releases mutation single-flight after ACK without waiting for auxiliary enrichment", async () => {
    const fake = goalPort();
    const ack = deferred<GoalReadModel>();
    const enrichment = deferred<{ items: never[] }>();
    vi.mocked(fake.port.pause).mockImplementation(() => ack.promise);
    const { result } = renderHook(() =>
      useGoalController({ goalId: "goal_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.model).toBeDefined());

    let first!: Promise<boolean>;
    act(() => {
      first = result.current.pause();
    });
    await expect(result.current.pause()).resolves.toBe(false);
    expect(fake.port.pause).toHaveBeenCalledOnce();

    const paused = {
      ...goalModel("paused"),
      goal: { ...goalModel("paused").goal, revision: 8 },
    };
    fake.setCurrent(paused);
    vi.mocked(fake.port.revisions).mockImplementation(() => enrichment.promise);
    ack.resolve(paused);
    await expect(first).resolves.toBe(true);
    await waitFor(() => expect(result.current.model?.goal.phase).toBe("paused"));

    await act(async () => {
      await result.current.resume();
    });
    expect(fake.port.resume).toHaveBeenCalledWith(
      expect.objectContaining({ expectedGoalRevision: 8 }),
    );
    enrichment.resolve({ items: [] });
  });

  it("keeps the resume ACK when a delayed pause event points to an older projection", async () => {
    const paused = {
      ...goalModel("paused"),
      goal: { ...goalModel("paused").goal, revision: 6 },
      eventSequence: 6,
    };
    const active = {
      ...goalModel("working"),
      goal: { ...goalModel("working").goal, revision: 7 },
      eventSequence: 8,
    };
    const stalePauseProjection = {
      ...paused,
      goal: { ...paused.goal, revision: 7 },
    };
    const fake = goalPort(paused);
    const { result } = renderHook(() =>
      useGoalController({ goalId: "goal_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.model?.goal.phase).toBe("paused"));

    fake.setCurrent(active);
    await act(async () => {
      await result.current.resume();
    });
    await waitFor(() => expect(result.current.model?.goal.phase).toBe("working"));

    fake.setCurrent(stalePauseProjection);
    const readsBeforeDelayedEvent = vi.mocked(fake.port.read).mock.calls.length;
    await act(async () => {
      fake.emit({
        method: "goal/changed",
        goalId: "goal_1",
        goalRevision: 6,
        eventSequence: 6,
        occurredAt: "2026-09-04T10:20:00+08:00",
      });
      await Promise.resolve();
    });

    expect(fake.port.read).toHaveBeenCalledTimes(readsBeforeDelayedEvent);
    expect(result.current.model).toMatchObject({
      goal: { revision: 7, phase: "working" },
      eventSequence: 8,
    });
  });

  it("rejects a regressive snapshot returned while reconciling an event gap", async () => {
    const active = {
      ...goalModel("working"),
      eventSequence: 8,
    };
    const stalePauseProjection = {
      ...goalModel("paused"),
      eventSequence: 7,
    };
    const fake = goalPort(active);
    const { result } = renderHook(() =>
      useGoalController({ goalId: "goal_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.model?.goal.phase).toBe("working"));

    fake.setCurrent(stalePauseProjection);
    const readsBeforeGap = vi.mocked(fake.port.read).mock.calls.length;
    act(() => {
      fake.emit({
        method: "goal/activity",
        goalId: "goal_1",
        goalRevision: 8,
        eventSequence: 10,
        occurredAt: "2026-09-04T10:21:00+08:00",
      });
    });

    await waitFor(() => expect(fake.port.read).toHaveBeenCalledTimes(readsBeforeGap + 1));
    expect(result.current.model).toMatchObject({
      goal: { revision: 7, phase: "working" },
      eventSequence: 8,
    });
  });

  it("reuses the idempotency key only for the same failed UI intent", async () => {
    const fake = goalPort();
    vi.mocked(fake.port.pause).mockRejectedValue(new Error("offline"));
    const { result } = renderHook(() =>
      useGoalController({ goalId: "goal_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.model).toBeDefined());

    await act(async () => {
      await result.current.pause();
    });
    await act(async () => {
      await result.current.pause();
    });

    const firstKey = vi.mocked(fake.port.pause).mock.calls[0]?.[0].idempotencyKey;
    const secondKey = vi.mocked(fake.port.pause).mock.calls[1]?.[0].idempotencyKey;
    expect(firstKey).toBeDefined();
    expect(secondKey).toBe(firstKey);
  });

  /** 活动 Goal 的 run 替换必须先暂停并等待收口，关联 ACK 后才可恢复。 */
  it.each(["approved", "awaiting_approval"] as const)(
    "pauses, attaches %s with one key, and resumes an active Goal",
    async (planStatus) => {
      const initial = goalModel("working", planStatus);
      const paused = {
        ...goalModel("paused", "approved"),
        goal: { ...goalModel("paused", "approved").goal, revision: 8 },
        eventSequence: 8,
      };
      const attached = {
        ...paused,
        goal: { ...paused.goal, revision: 9 },
        eventSequence: 9,
      };
      const resumed = {
        ...initial,
        goal: { ...initial.goal, revision: 10 },
        eventSequence: 10,
      };
      const fake = goalPort(initial);
      const order: string[] = [];
      vi.mocked(fake.port.pause).mockImplementation(async () => {
        order.push("pause");
        fake.setCurrent(paused);
        return paused;
      });
      vi.mocked(fake.port.attachPlan)
        .mockImplementationOnce(async () => {
          order.push("attach:settling");
          throw goalPortError("GOAL_INVALID_STATE");
        })
        .mockImplementationOnce(async () => {
          order.push("attach");
          fake.setCurrent(attached);
          return attached;
        });
      vi.mocked(fake.port.resume).mockImplementation(async () => {
        order.push("resume");
        fake.setCurrent(resumed);
        return resumed;
      });
      const { result } = renderHook(() =>
        useGoalController({ goalId: "goal_1", visible: true, port: fake.port }),
      );
      await waitFor(() => expect(result.current.model).toBeDefined());

      await act(async () => {
        await expect(result.current.attachPlan()).resolves.toBe(true);
      });

      expect(order).toEqual(["pause", "attach:settling", "attach", "resume"]);
      const mutationCalls = vi.mocked(fake.port.attachPlan).mock.calls;
      const mutationKey = mutationCalls[0]?.[0].idempotencyKey;
      expect(mutationCalls[1]?.[0].idempotencyKey).toBe(mutationKey);
      expect(mutationCalls[0]?.[0].expectedGoalRevision).toBe(8);
      expect(vi.mocked(fake.port.pause).mock.calls[0]?.[0]).toMatchObject({
        expectedGoalRevision: 7,
        idempotencyKey: `${mutationKey}:pause`,
      });
      expect(vi.mocked(fake.port.resume).mock.calls[0]?.[0]).toMatchObject({
        expectedGoalRevision: 9,
        idempotencyKey: `${mutationKey}:resume`,
      });
      expect(result.current.model?.goal).toMatchObject({ revision: 10, status: "active" });
    },
  );

  /** 已暂停或 needs_attention 的 Goal 不由关联动作擅自恢复。 */
  it("attaches directly and remains paused when the Goal was already paused", async () => {
    const initial = goalModel("paused", "approved");
    const attached = {
      ...initial,
      goal: { ...initial.goal, revision: 8 },
      eventSequence: 8,
    };
    const fake = goalPort(initial);
    vi.mocked(fake.port.attachPlan).mockImplementation(async () => {
      fake.setCurrent(attached);
      return attached;
    });
    const { result } = renderHook(() =>
      useGoalController({ goalId: "goal_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.model).toBeDefined());

    await act(async () => {
      await expect(result.current.attachPlan()).resolves.toBe(true);
    });

    expect(fake.port.pause).not.toHaveBeenCalled();
    expect(fake.port.resume).not.toHaveBeenCalled();
    expect(fake.port.attachPlan).toHaveBeenCalledWith(
      expect.objectContaining({ expectedGoalRevision: 7 }),
    );
    expect(result.current.model?.goal.status).toBe("paused");
  });

  /** pause 已确认后若关联被业务规则拒绝，界面保留 paused 权威投影供用户恢复处理。 */
  it("keeps the acknowledged paused projection when attach fails after pausing", async () => {
    const initial = goalModel("working", "approved");
    const paused = {
      ...goalModel("paused", "approved"),
      goal: { ...goalModel("paused", "approved").goal, revision: 8 },
      eventSequence: 8,
    };
    const fake = goalPort(initial);
    vi.mocked(fake.port.pause).mockImplementation(async () => {
      fake.setCurrent(paused);
      return paused;
    });
    vi.mocked(fake.port.attachPlan).mockRejectedValue(goalPortError("PLAN_APPROVAL_STALE"));
    const { result } = renderHook(() =>
      useGoalController({ goalId: "goal_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.model).toBeDefined());

    await act(async () => {
      await expect(result.current.attachPlan()).resolves.toBe(false);
    });

    expect(fake.port.resume).not.toHaveBeenCalled();
    expect(result.current.model?.goal).toMatchObject({ revision: 8, status: "paused" });
    expect(result.current.error).toBe("计划未能用于当前目标，请确认当前版本仍可用。");
  });

  it("publishes the acknowledged Goal identity for the first Plan turn", async () => {
    const created = goalModel("working", "draft");
    const fake = goalPort(created);
    const ack = deferred<GoalReadModel>();
    vi.mocked(fake.port.create).mockImplementation(() => ack.promise);
    const { result } = renderHook(() =>
      useGoalController({
        ownerThreadId: "thr_owner",
        visible: true,
        port: fake.port,
      }),
    );

    let creation!: Promise<boolean>;
    act(() => {
      creation = result.current.create("thr_owner", "实现版本化计划");
    });
    expect(result.current.busyAction).toBe("create");
    const acknowledged = {
      ...created,
      goal: { ...created.goal, ownerThreadId: "thr_owner" },
    };
    fake.setCurrent(acknowledged);
    ack.resolve(acknowledged);
    await expect(creation).resolves.toBe(true);
    await waitFor(() => expect(result.current.model?.goal.goalId).toBe("goal_1"));
    expect(fake.port.create).toHaveBeenCalledWith(
      expect.objectContaining({
        ownerThreadId: "thr_owner",
        objective: "实现版本化计划",
        expectedGoalRevision: 0,
        idempotencyKey: expect.stringMatching(/^goal-ui-create-/),
      }),
    );
  });

  /** 侧边 controller 只切换 owner kind，Goal identity 仍由服务端 ACK 决定。 */
  it("creates a Goal owned by an independent side-task Thread", async () => {
    const created = {
      ...goalModel("working", "draft"),
      goal: {
        ...goalModel("working", "draft").goal,
        ownerThreadId: "thr_side",
      },
    };
    const fake = goalPort(created);
    const { result } = renderHook(() =>
      useGoalController({
        ownerThreadId: "thr_side",
        ownerKind: "independent_task",
        visible: true,
        port: fake.port,
      }),
    );

    await act(async () => {
      await expect(result.current.create("thr_side", "侧边任务目标")).resolves.toBe(true);
    });

    expect(fake.port.create).toHaveBeenCalledWith({
      ownerThreadId: "thr_side",
      ownerKind: "independent_task",
      objective: "侧边任务目标",
      expectedGoalRevision: 0,
      idempotencyKey: expect.stringMatching(/^goal-ui-create-/),
    });
  });

  /** 独立 Plan 创建只回传 artifact identity，不预建或激活 Goal。 */
  it("creates a standalone Plan without manufacturing Goal state", async () => {
    const initial = goalModel("working", "draft");
    const fake = goalPort({
      ...initial,
      planState: { ...initial.planState!, ownerThreadId: "thr_owner" },
    });
    const { result } = renderHook(() =>
      useGoalController({ ownerThreadId: "thr_owner", visible: true, port: fake.port }),
    );

    await act(async () => {
      await expect(result.current.createPlan("thr_owner", "形成可批准计划", 4)).resolves.toBe(true);
    });
    expect(fake.port.createPlan).toHaveBeenCalledWith({
      ownerThreadId: "thr_owner",
      objective: "形成可批准计划",
      expectedThreadRevision: 4,
      idempotencyKey: expect.stringMatching(/^goal-ui-create_plan-/),
    });
    expect(fake.port.create).not.toHaveBeenCalled();
    expect(result.current.model).toBeUndefined();
    expect(result.current.planModel?.plan.planId).toBe("plan_1");
  });

  it("discovers a newly created Goal from the owner-scoped revision zero event", async () => {
    const created = {
      ...goalModel("working", "draft"),
      goal: {
        ...goalModel("working", "draft").goal,
        ownerThreadId: "thr_owner",
        revision: 0,
      },
    };
    const fake = goalPort(created);
    const { result } = renderHook(() =>
      useGoalController({ ownerThreadId: "thr_owner", visible: true, port: fake.port }),
    );

    act(() => {
      fake.emit({
        method: "goal/changed",
        goalId: "goal_1",
        goalRevision: 0,
        eventSequence: 1,
        occurredAt: "2026-09-04T10:20:00+08:00",
        ownerThreadId: "thr_owner",
      });
    });

    await waitFor(() => expect(result.current.model?.goal.goalId).toBe("goal_1"));
    expect(fake.port.read).toHaveBeenCalledWith({ goalId: "goal_1" });
    expect(fake.port.observe).toHaveBeenCalledWith({ goalId: "goal_1", expectedGoalRevision: 0 });
  });
});
