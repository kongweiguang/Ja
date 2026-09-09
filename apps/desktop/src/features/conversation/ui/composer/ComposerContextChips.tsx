// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { File, Folder, Sparkles, X } from "lucide-react";
import type { ReactElement } from "react";
import type { ConversationContextReference } from "../../domain/userContent";
import { splitFileName } from "./filePresentation";

export interface ComposerContextChipsProps {
  references: readonly ConversationContextReference[];
  onRemove?: (reference: ConversationContextReference) => void;
  onOpenWorkspaceReference?: (
    reference: Extract<ConversationContextReference, { type: "workspace_reference" }>,
    source: HTMLButtonElement,
  ) => void;
  label?: string;
  compact?: boolean;
}

/** 长路径只在 Chip 主标签显示末段，完整身份留在 title 与读屏名称中。 */
function referencePresentation(reference: ConversationContextReference): {
  label: string;
  detail: string;
} {
  if (reference.type === "skill_reference") {
    return {
      label: reference.name?.trim() || "Skill 不可用",
      detail: reference.description?.trim() || "该 Skill 已停用、删除或不属于当前配置代际",
    };
  }
  const segments = reference.relativePath.split("/");
  return {
    label: segments.at(-1) || reference.relativePath,
    detail: reference.relativePath,
  };
}

/** Context 栏仅展示已选择身份；移除按钮不会读取文件、展开目录或停用全局 Skill。 */
export function ComposerContextChips({
  references,
  onRemove,
  onOpenWorkspaceReference,
  label = "消息上下文",
  compact = false,
}: ComposerContextChipsProps): ReactElement | null {
  if (references.length === 0) return null;
  return (
    <ul className={`ja-composer-context-list${compact ? " is-compact" : ""}`} aria-label={label}>
      {references.map((reference) => {
        const presentation = referencePresentation(reference);
        const fileName =
          reference.type === "workspace_reference" && reference.kind === "file"
            ? splitFileName(presentation.label)
            : undefined;
        const identity =
          reference.type === "workspace_reference"
            ? `${reference.workspaceId}:${reference.relativePath}`
            : reference.skillId;
        const canOpenWorkspaceReference =
          !compact &&
          reference.type === "workspace_reference" &&
          onOpenWorkspaceReference !== undefined;
        const content = (
          <>
            <span className="ja-composer-context__icon" aria-hidden="true">
              {reference.type === "skill_reference" ? (
                <Sparkles />
              ) : reference.kind === "directory" ? (
                <Folder />
              ) : (
                <File />
              )}
            </span>
            <span className="ja-composer-context__copy" title={presentation.detail}>
              <strong>
                {fileName === undefined ? (
                  presentation.label
                ) : (
                  <>
                    <span>{fileName.stem}</span>
                    {fileName.extension === undefined ? null : <span>{fileName.extension}</span>}
                  </>
                )}
              </strong>
              {compact ? null : <small>{presentation.detail}</small>}
            </span>
          </>
        );
        return (
          <li
            key={`${reference.type}:${identity}`}
            className="ja-composer-context__chip"
            data-reference-type={reference.type === "workspace_reference" ? "workspace" : "skill"}
            data-reference-kind={
              reference.type === "workspace_reference" ? reference.kind : undefined
            }
            data-available={
              reference.type === "skill_reference" ? reference.available !== false : undefined
            }
            data-openable={canOpenWorkspaceReference || undefined}
          >
            {canOpenWorkspaceReference && reference.type === "workspace_reference" ? (
              <button
                type="button"
                className="ja-composer-context__open"
                aria-label={
                  reference.kind === "directory"
                    ? `在文件中定位 ${presentation.label}`
                    : `在文件中预览 ${presentation.label}`
                }
                onClick={(event) => onOpenWorkspaceReference(reference, event.currentTarget)}
              >
                {content}
              </button>
            ) : (
              content
            )}
            {onRemove === undefined ? null : (
              <button
                type="button"
                className="ja-composer-context__remove"
                aria-label={`移除上下文 ${presentation.label}`}
                title="移除"
                onClick={() => onRemove(reference)}
              >
                <X aria-hidden="true" />
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}
