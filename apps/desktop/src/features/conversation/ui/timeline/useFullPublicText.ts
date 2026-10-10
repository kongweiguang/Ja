// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback } from "react";
import type { TimelineItemAdapter } from "../../domain/timelineTypes";

type FullPublicTextItem = Pick<TimelineItemAdapter, "itemId" | "bodyHydration" | "answerHydration">;

/**
 * 长内容只在用户动作后读取；TimelineStore 是正文与请求状态的唯一 owner，
 * 组件仅派生查看/复制状态，避免虚拟行卸载后丢缓存或重复持有整份正文。
 */
export function useFullPublicText(
  item: FullPublicTextItem | undefined,
  readFullText: (() => Promise<string>) | undefined,
): {
  text: string | undefined;
  loading: boolean;
  error: boolean;
  load: () => Promise<string>;
} {
  const hydration = item?.answerHydration ?? item?.bodyHydration;
  const text = hydration?.status === "complete" ? hydration.text : undefined;
  const loading = hydration?.status === "loading";
  const error = hydration?.status === "error";

  /** 同一身份的并发读取由 TimelineStore 合并；本 hook 不保留返回正文副本。 */
  const load = useCallback(async (): Promise<string> => {
    if (text !== undefined) return text;
    if (readFullText === undefined || item?.itemId === undefined)
      throw new Error("完整内容暂时不可用。");
    return readFullText();
  }, [item?.itemId, readFullText, text]);

  return { text, loading, error, load };
}
