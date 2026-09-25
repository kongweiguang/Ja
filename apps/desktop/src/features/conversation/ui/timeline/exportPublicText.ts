// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

interface WritableTextFile {
  write(chunk: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort?: () => Promise<void>;
}

interface SavedTextFile {
  createWritable(): Promise<WritableTextFile>;
}

type SaveTextFilePicker = (options: {
  suggestedName: string;
  types: { description: string; accept: Record<string, string[]> }[];
}) => Promise<SavedTextFile>;

/** 只在用户明确点击时使用浏览器原生保存面板；取消是正常结果，路径不进入 Ja 状态或 IPC。 */
export async function exportPublicText(
  text: string,
  suggestedName: string,
): Promise<"saved" | "cancelled" | "unsupported"> {
  const picker = (window as Window & { showSaveFilePicker?: SaveTextFilePicker })
    .showSaveFilePicker;
  if (picker === undefined) return "unsupported";
  let file: SavedTextFile;
  try {
    file = await picker.call(window, {
      suggestedName,
      types: [{ description: "纯文本", accept: { "text/plain": [".txt"] } }],
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") return "cancelled";
    throw error;
  }
  const writable = await file.createWritable();
  const encoder = new TextEncoder();
  try {
    for (let start = 0; start < text.length; ) {
      let end = Math.min(text.length, start + 32_768);
      if (end < text.length && splitsSurrogatePair(text, end)) end -= 1;
      await writable.write(encoder.encode(text.slice(start, end)));
      start = end;
    }
    await writable.close();
    return "saved";
  } catch (error) {
    try {
      await writable.abort?.();
    } catch {
      // 失败时仍以原写入错误反馈，浏览器自行清理未提交的临时文件。
    }
    throw error;
  }
}

/** UTF-16 分片不能把代理对拆给两个 TextEncoder 调用，否则导出会以替换字符污染正文。 */
function splitsSurrogatePair(text: string, end: number): boolean {
  const before = text.charCodeAt(end - 1);
  const after = text.charCodeAt(end);
  return before >= 0xd800 && before <= 0xdbff && after >= 0xdc00 && after <= 0xdfff;
}
