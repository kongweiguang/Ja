// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { basicSetup } from "codemirror";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { useEffect, useRef, type ReactElement } from "react";
import { languageExtension } from "../domain/language";
import "./Editor.css";

export interface EditorRevealPosition {
  line: number;
  column?: number;
}

export interface CodeEditorProps {
  filePath: string;
  content: string;
  language?: string;
  revision?: string | number;
  readOnly?: boolean;
  ariaLabel?: string;
  reveal?: EditorRevealPosition;
  onChange?: (content: string) => void;
  onSave?: () => void | Promise<void>;
  onBlur?: () => void;
}

/**
 * 每个 Document Path 只维持一个 CodeMirror View。Parent State 通过 Listener 接收 Draft Text，
 * 外部 Revision 带有显式 Annotation，避免被误认成用户编辑或触发 Autosave。
 */
export function CodeEditor({
  filePath,
  content,
  language,
  revision,
  readOnly = false,
  ariaLabel = `编辑文件 ${filePath}`,
  reveal,
  onChange,
  onSave,
  onBlur,
}: CodeEditorProps): ReactElement {
  const hostRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | undefined>(undefined);
  const initialContentRef = useRef(content);
  const externalUpdateRef = useRef(false);
  const onChangeRef = useRef(onChange);
  const onSaveRef = useRef(onSave);
  const onBlurRef = useRef(onBlur);
  const revealLine = reveal?.line;
  const revealColumn = reveal?.column;

  onChangeRef.current = onChange;
  onSaveRef.current = onSave;
  onBlurRef.current = onBlur;

  useEffect(() => {
    initialContentRef.current = content;
  }, [filePath, content]);

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return undefined;
    /** 只转发用户变更；外部 Replacement 已带 Annotation，必须忽略。 */
    const updateListener = EditorView.updateListener.of((update) => {
      if (!update.docChanged || externalUpdateRef.current || readOnly) return;
      onChangeRef.current?.(update.state.doc.toString());
    });
    const extensions = [
      basicSetup,
      lineNumbers(),
      EditorState.readOnly.of(readOnly),
      EditorView.editable.of(!readOnly),
      EditorView.lineWrapping,
      updateListener,
      keymap.of([
        {
          key: "Mod-s",
          run: () => {
            void onSaveRef.current?.();
            return true;
          },
        },
      ]),
      EditorView.contentAttributes.of({ "aria-label": ariaLabel }),
    ];
    const extension = languageExtension(filePath, language);
    if (extension !== undefined) extensions.push(extension);
    const view = new EditorView({
      state: EditorState.create({ doc: initialContentRef.current, extensions }),
      parent: host,
    });
    /** 允许 Controller 在 Blur 时 Flush，而无需重建 CodeMirror View。 */
    const handleBlur = (): void => onBlurRef.current?.();
    view.dom.addEventListener("blur", handleBlur, true);
    viewRef.current = view;
    return () => {
      view.dom.removeEventListener("blur", handleBlur, true);
      view.destroy();
      viewRef.current = undefined;
    };
  }, [ariaLabel, filePath, language, readOnly]);

  useEffect(() => {
    const view = viewRef.current;
    if (view === undefined || view.state.doc.toString() === content) return;
    externalUpdateRef.current = true;
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: content },
      userEvent: `external.${String(revision ?? "update")}`,
    });
    externalUpdateRef.current = false;
  }, [content, revision]);

  useEffect(() => {
    const view = viewRef.current;
    if (view === undefined || revealLine === undefined) return;
    const lineNumber = Math.max(1, revealLine);
    const line = view.state.doc.line(Math.min(lineNumber, view.state.doc.lines));
    const column = Math.max(0, Math.min((revealColumn ?? 1) - 1, line.length));
    const anchor = line.from + column;
    view.dispatch({ selection: { anchor }, scrollIntoView: true });
    view.focus();
  }, [filePath, revealColumn, revealLine]);

  return (
    <section className="ja-code-editor" data-file-path={filePath} aria-label={ariaLabel}>
      <div className="ja-code-editor__host" ref={hostRef} />
    </section>
  );
}
