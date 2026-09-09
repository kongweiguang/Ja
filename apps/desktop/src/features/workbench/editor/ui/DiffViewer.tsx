// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { basicSetup } from "codemirror";
import { Compartment, EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { MergeView } from "@codemirror/merge";
import { useEffect, useMemo, useRef, type ReactElement } from "react";
import { languageExtension } from "@/shared/syntax";
import { useResolvedTheme, useUiPalette } from "@/shared/hooks/useResolvedTheme";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import { semanticCodeMirrorThemeExtension } from "./semanticCodeMirrorTheme";
import { formatDiffClipboard } from "../domain/diffClipboard";
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
 * 并且只从权威外部 Revision 更新。两侧共享同一 Theme Compartment，避免配色或明暗切换重算 Diff。
 */
export function DiffViewer({
  filePath,
  original,
  modified,
  language,
  revision,
  onCopyText,
}: DiffViewerProps): ReactElement {
  const resolvedTheme = useResolvedTheme();
  const palette = useUiPalette();
  const hostRef = useRef<HTMLDivElement>(null);
  const mergeRef = useRef<MergeView | undefined>(undefined);
  const themeCompartment = useMemo(() => new Compartment(), []);
  const initialResolvedThemeRef = useRef(resolvedTheme);
  const initialPaletteRef = useRef(palette);
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
      themeCompartment.of(
        semanticCodeMirrorThemeExtension(
          initialPaletteRef.current,
          initialResolvedThemeRef.current,
        ),
      ),
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
  }, [filePath, language, themeCompartment]);
  useEffect(() => {
    const merge = mergeRef.current;
    if (merge === undefined) return;
    // MergeView 两侧必须在同一帧使用相同 Theme Effect，避免双栏短暂出现配色分裂。
    const effect = themeCompartment.reconfigure(
      semanticCodeMirrorThemeExtension(palette, resolvedTheme),
    );
    merge.a.dispatch({ effects: effect });
    merge.b.dispatch({ effects: effect });
  }, [palette, resolvedTheme, themeCompartment]);
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

/**
 * 只有 Runtime Revision 变化时才分发完整 Replacement，避免重建 MergeView，
 * 并保留其已测量的 Diff Decoration。
 */
function replaceDocument(view: EditorView, content: string, userEvent: string): void {
  if (view.state.doc.toString() === content) return;
  view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: content }, userEvent });
}
