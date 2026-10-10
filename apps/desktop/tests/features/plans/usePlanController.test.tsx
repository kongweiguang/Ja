// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  usePlanController,
  type PlanEvent,
  type PlanPort,
  type PlanReadModel,
} from "@/features/plans";
import { evidence, planModel, planRevision } from "./planFixtures";

/** 测试替身会将请求的所有者和目标写入权威快照，因此创建确认会保留真实身份。 */
function planPort(initial?: PlanReadModel) {
  let current = initial;
  const listeners = new Set<(event: PlanEvent) => void>();
  const read = vi.fn(async () => {
    if (current === undefined) throw new Error("plan absent");
    return current;
  });
  const create = vi.fn(async (input: Parameters<PlanPort["createPlan"]>[0]) => {
    const created = planModel("draft");
    current = {
      ...created,
      plan: {
        ...created.plan,
        ownerThreadId: input.ownerThreadId,
        objective: input.objective,
      },
    };
    return current;
  });
  const execute = vi.fn(async () => {
    current = planModel("executing");
    return current;
  });
  const observe = vi.fn(async () => ({ observationId: "observe_1", plan: current! }));
  const unobserve = vi.fn(async () => undefined);
  const port: PlanPort = {
    readPlan: read,
    currentPlan: vi.fn(async () => current),
    observePlan: observe,
    unobservePlan: unobserve,
    planRevisions: vi.fn(async () => ({ items: [planRevision] })),
    readPlanEvidence: vi.fn(async () => ({ items: [evidence] })),
    createPlan: create,
    saveDraft: vi.fn(async () => current!),
    discardDraft: vi.fn(async () => current!),
    propose: vi.fn(async () => current!),
    execute,
    pausePlan: vi.fn(async () => current!),
    resumePlan: vi.fn(async () => current!),
    stopPlan: vi.fn(async () => current!),
    reject: vi.fn(async () => current!),
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
  return {
    port,
    read,
    create,
    execute,
    observe,
    unobserve,
    setCurrent(next: PlanReadModel) {
      current = next;
    },
    emit(event: PlanEvent) {
      for (const listener of listeners) listener(event);
    },
  };
}

describe("usePlanController", () => {
  afterEach(cleanup);

  it("restores and observes a Plan only while its thread is visible", async () => {
    const fake = planPort(planModel("executing"));
    const { result, rerender, unmount } = renderHook(
      ({ visible }) => usePlanController({ ownerThreadId: "thr_1", visible, port: fake.port }),
      { initialProps: { visible: false } },
    );
    expect(fake.port.currentPlan).not.toHaveBeenCalled();
    rerender({ visible: true });
    await waitFor(() => expect(result.current.planModel?.plan.planId).toBe("plan_1"));
    await waitFor(() => expect(fake.observe).toHaveBeenCalledOnce());
    unmount();
    await waitFor(() =>
      expect(fake.unobserve).toHaveBeenCalledWith({ observationId: "observe_1" }),
    );
  });

  it("ignores stale events and hydrates a newly proposed revision", async () => {
    const fake = planPort(planModel());
    const { result } = renderHook(() =>
      usePlanController({ ownerThreadId: "thr_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.planModel?.eventSequence).toBe(3));
    const next = {
      ...planModel("awaiting_approval"),
      plan: {
        ...planModel("awaiting_approval").plan,
        revision: 4,
        activePlanRevisionId: "planrev_3",
      },
      eventSequence: 4,
    };
    fake.setCurrent(next);
    act(() =>
      fake.emit({
        method: "plan/changed",
        planId: "plan_1",
        ownerThreadId: "thr_1",
        planRevision: 4,
        eventSequence: 4,
        plan: next.plan,
        progress: next.progress,
        occurredAt: "2026-09-04T10:11:00Z",
      }),
    );
    await waitFor(() =>
      expect(result.current.planModel?.plan.activePlanRevisionId).toBe("planrev_3"),
    );
    act(() =>
      fake.emit({
        method: "plan/changed",
        planId: "plan_1",
        ownerThreadId: "thr_1",
        planRevision: 3,
        eventSequence: 2,
        plan: planModel().plan,
        progress: planModel().progress,
        occurredAt: "2026-09-04T10:10:00Z",
      }),
    );
    expect(result.current.planModel?.plan.revision).toBe(4);
    await waitFor(() => expect(fake.read).toHaveBeenCalled());
  });

  it("reuses the create idempotency key after a lost ACK", async () => {
    const fake = planPort();
    fake.create.mockRejectedValueOnce(new Error("connection lost"));
    const { result } = renderHook(() =>
      usePlanController({ ownerThreadId: "thr_1", visible: true, port: fake.port }),
    );
    await act(async () => {
      expect(await result.current.createPlan("thr_1", "交付计划", 1)).toBe(false);
    });
    await act(async () => {
      expect(await result.current.createPlan("thr_1", "交付计划", 1)).toBe(true);
    });
    expect(fake.create.mock.calls[0]?.[0].operationKey).toBe(
      fake.create.mock.calls[1]?.[0].operationKey,
    );
  });

  it("executes only the frozen Plan revision and hash", async () => {
    const fake = planPort(planModel("approved"));
    const { result } = renderHook(() =>
      usePlanController({ ownerThreadId: "thr_1", visible: true, port: fake.port }),
    );
    await waitFor(() => expect(result.current.planModel).toBeDefined());
    await act(async () => {
      expect(await result.current.execute()).toBe(true);
    });
    expect(fake.execute).toHaveBeenCalledWith(
      expect.objectContaining({
        planId: "plan_1",
        planRevisionId: "planrev_2",
        planHash: "a".repeat(64),
        expectedPlanRevision: 3,
      }),
    );
  });

  it("loads revisions and evidence only when Plan details are visible", async () => {
    const fake = planPort(planModel("executing"));
    const { result, rerender } = renderHook(
      ({ detailsVisible }) =>
        usePlanController({
          ownerThreadId: "thr_1",
          visible: true,
          detailsVisible,
          port: fake.port,
        }),
      { initialProps: { detailsVisible: false } },
    );
    await waitFor(() => expect(result.current.planModel).toBeDefined());
    expect(fake.port.readPlanEvidence).not.toHaveBeenCalled();
    rerender({ detailsVisible: true });
    await waitFor(() => expect(result.current.evidence).toEqual([evidence]));
  });
});
