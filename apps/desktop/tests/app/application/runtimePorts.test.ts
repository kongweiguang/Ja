// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";

import { normalizeRuntimeApplicationError } from "@/app/application/runtimePorts";

describe("runtime application error catalog", () => {
  /** 顺序冲突可在前序 Turn 收口后重试，应用层不得覆盖共享合同的 retryable=true。 */
  it("keeps turn resume order conflicts retryable", () => {
    expect(normalizeRuntimeApplicationError({ code: "TURN_RESUME_ORDER_CONFLICT" })).toMatchObject({
      code: "TURN_RESUME_ORDER_CONFLICT",
      retryable: true,
      message: "请先处理更早中断的运行",
    });
  });
});
