// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { basicSetup } from "codemirror";
import { EditorState } from "@codemirror/state";
import { EditorView, lineNumbers } from "@codemirror/view";
import { useEffect, useRef, type ReactElement } from "react";
import { languageExtension } from "../domain/language";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import "./Editor.css";

export interface CodeViewerProps {
  filePath: string;
  content: string;
  language?: string;
  revision?: string | number;
  onCopyText?: (text: string) => Promise<void>;
}

/**
 * 每个选中文件创建一个不可变 CodeMirror View，并在 Unmount 时销毁；切换文件后不能遗留
 * Editor DOM 或 Event Listener。
 */
export function CodeViewer({
  filePath,
  content,
  language,
  revision,
  onCopyText,
}: CodeViewerProps): ReactElement {
  const hostRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | undefined>(undefined);
  const initialContent = useRef(content);
  useEffect(() => {
    initialContent.current = content;
  }, [filePath, content]);
  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return undefined;
    const extensions = [
      basicSetup,
      lineNumbers(),
      EditorState.readOnly.of(true),
      EditorView.editable.of(false),
      EditorView.lineWrapping,
    ];
    const extension = languageExtension(filePath, language);
    if (extension !== undefined) extensions.push(extension);
    const view = new EditorView({
      state: EditorState.create({ doc: initialContent.current, extensions }),
      parent: host,
    });
    viewRef.current = view;
    return () => {
      view.destroy();
      viewRef.current = undefined;
    };
  }, [filePath, language]);
  useEffect(() => {
    const view = viewRef.current;
    if (view === undefined || view.state.doc.toString() === content) return;
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: content },
      userEvent: `external.${String(revision ?? "update")}`,
    });
  }, [content, revision]);
  return (
    <section
      className="ja-editor-viewer"
      data-file-path={filePath}
      aria-label={`只读文件 ${filePath}`}
    >
      <div className="ja-editor-toolbar">
        {onCopyText === undefined ? null : (
          <CopyTextButton text={content} label="复制代码" onCopyText={onCopyText} />
        )}
      </div>
      <div className="ja-editor-viewer__host" ref={hostRef} />
    </section>
  );
}
