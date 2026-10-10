// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  actualSizeAttachmentZoom,
  attachmentPreviewTargetKey,
  fitAttachmentZoom,
  stepAttachmentZoom,
} from "@/features/workbench/preview/domain/attachmentPreviewModel";

describe("attachmentPreviewModel", () => {
  it("缩放保持 25%-400%，适应与实际尺寸是显式模式", () => {
    expect(stepAttachmentZoom({ mode: "scale", percent: 25 }, -1)).toEqual({
      mode: "scale",
      percent: 25,
    });
    expect(stepAttachmentZoom({ mode: "scale", percent: 400 }, 1)).toEqual({
      mode: "scale",
      percent: 400,
    });
    expect(stepAttachmentZoom(fitAttachmentZoom(), 1)).toEqual({
      mode: "scale",
      percent: 125,
    });
    expect(actualSizeAttachmentZoom()).toEqual({ mode: "scale", percent: 100 });
  });

  it("target key 区分原生授权的 draft 与 thread", () => {
    expect(
      attachmentPreviewTargetKey({
        attachmentId: "att_1",
        displayName: "a.txt",
        mediaKind: "text",
        authorization: { kind: "draft" },
      }),
    ).toBe("draft:att_1");
    expect(
      attachmentPreviewTargetKey({
        attachmentId: "att_1",
        displayName: "a.txt",
        mediaKind: "text",
        authorization: { kind: "thread", threadId: "thread-1" },
      }),
    ).toBe("thread:thread-1:att_1");
  });
});
