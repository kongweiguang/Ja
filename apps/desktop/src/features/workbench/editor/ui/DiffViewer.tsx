// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { basicSetup } from "codemirror";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { MergeView } from "@codemirror/merge";
import { useEffect, useRef, type ReactElement } from "react";
import { languageExtension } from "../domain/language";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import "./Editor.css";

export interface DiffViewerProps {
  filePath: string;
  original: string;
  modified: string;
  language?: string;
  revision?: string | number;
  onCopyText?: (text: string) => Promise<void>;
}

/**
 * 实际 Diff Algorithm 使用 CodeMirror MergeView；两侧 Document 保持只读，
 * 并且只从权威外部 Revision 更新。
 */
export function DiffViewer({
  filePath,
  original,
  modified,
  language,
  revision,
  onCopyText,
}: DiffViewerProps): ReactElement {
  const hostRef = useRef<HTMLDivElement>(null);
  const mergeRef = useRef<MergeView | undefined>(undefined);
  const initialDocuments = useRef({ original, modified });
  useEffect(() => {
    initialDocuments.current = { original, modified };
  }, [filePath, original, modified]);
  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return undefined;
    const readOnly = [
      basicSetup,
      EditorState.readOnly.of(true),
      EditorView.editable.of(false),
      EditorView.lineWrapping,
    ];
    const extension = languageExtension(filePath, language);
    if (extension !== undefined) {
      readOnly.push(extension);
    }
    const merge = new MergeView({
      parent: host,
      orientation: "a-b",
      highlightChanges: true,
      gutter: true,
      collapseUnchanged: { margin: 3, minSize: 5 },
      a: { doc: initialDocuments.current.original, extensions: readOnly },
      b: { doc: initialDocuments.current.modified, extensions: readOnly },
    });
    mergeRef.current = merge;
    return () => {
      merge.destroy();
      mergeRef.current = undefined;
    };
  }, [filePath, language]);
  useEffect(() => {
    const merge = mergeRef.current;
    if (merge === undefined) return;
    replaceDocument(merge.a, original, `external.${String(revision ?? "original")}`);
    replaceDocument(merge.b, modified, `external.${String(revision ?? "modified")}`);
  }, [original, modified, revision]);
  return (
    <section
      className="ja-editor-diff"
      data-file-path={filePath}
      aria-label={`只读 Diff ${filePath}`}
    >
      <div className="ja-editor-toolbar">
        {onCopyText === undefined ? null : (
          <CopyTextButton
            text={() => formatDiffClipboard(filePath, original, modified)}
            label="复制 Diff"
            onCopyText={onCopyText}
          />
        )}
      </div>
      <div className="ja-editor-diff__host" ref={hostRef} />
    </section>
  );
}

/** 按需生成显式双边 Review Payload；它不冒充生成的 Unified Patch，并精确保留用户所见内容。 */
function formatDiffClipboard(filePath: string, original: string, modified: string): string {
  return `文件：${filePath}\n\n--- 原始内容\n${original}\n\n+++ 修改后内容\n${modified}`;
}

/**
 * 只有 Runtime Revision 变化时才分发完整 Replacement，避免重建 MergeView，
 * 并保留其已测量的 Diff Decoration。
 */
function replaceDocument(view: EditorView, content: string, userEvent: string): void {
  if (view.state.doc.toString() === content) return;
  view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: content }, userEvent });
}
