// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useRef, useState } from "react";

/** 长内容只在用户动作后读取；稳定消息身份隔离迟到结果，并让查看与复制共用一次请求。 */
export function useFullPublicText(
  itemId: string | undefined,
  readFullText: (() => Promise<string>) | undefined,
): {
  text: string | undefined;
  loading: boolean;
  error: boolean;
  load: () => Promise<string>;
} {
  const [loaded, setLoaded] = useState<{ itemId: string; text: string }>();
  const [loadingItemId, setLoadingItemId] = useState<string>();
  const [errorItemId, setErrorItemId] = useState<string>();
  const pendingRef = useRef<{ itemId: string; promise: Promise<string> } | undefined>(undefined);
  const text = loaded !== undefined && loaded.itemId === itemId ? loaded.text : undefined;
  const loading = loadingItemId !== undefined && loadingItemId === itemId;
  const error = errorItemId !== undefined && errorItemId === itemId;

  /** 同一条消息的查看和复制共用在途分页；旧消息回包仅更新带身份的缓存，不污染当前内容。 */
  const load = useCallback(async (): Promise<string> => {
    if (text !== undefined) return text;
    if (readFullText === undefined || itemId === undefined) throw new Error("完整内容暂时不可用。");
    if (pendingRef.current?.itemId === itemId) return pendingRef.current.promise;
    setLoadingItemId(itemId);
    setErrorItemId(undefined);
    const request = readFullText()
      .then((content) => {
        setLoaded({ itemId, text: content });
        return content;
      })
      .catch((failure: unknown) => {
        setErrorItemId(itemId);
        throw failure;
      })
      .finally(() => {
        if (pendingRef.current?.promise === request) pendingRef.current = undefined;
        setLoadingItemId((current) => (current === itemId ? undefined : current));
      });
    pendingRef.current = { itemId, promise: request };
    return request;
  }, [itemId, readFullText, text]);

  return { text, loading, error, load };
}
