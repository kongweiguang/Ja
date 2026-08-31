// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Check, Copy, LoaderCircle, TriangleAlert } from "lucide-react";
import { useEffect, useRef, useState, type MouseEvent, type ReactElement } from "react";
import { cn } from "./primitives/cn";
import "./CopyTextButton.css";

export interface CopyTextButtonProps {
  text: string | (() => string);
  label: string;
  onCopyText: (text: string) => Promise<void>;
  className?: string;
}

type CopyState = "idle" | "copying" | "copied" | "failed";

/** 为消息与 editor 提供一个可访问的真实复制动作；只在点击时解析文本，避免渲染期间复制大型 Diff。 */
export function CopyTextButton({
  text,
  label,
  onCopyText,
  className,
}: CopyTextButtonProps): ReactElement {
  const [state, setState] = useState<CopyState>("idle");
  const resetTimer = useRef<number | undefined>(undefined);

  useEffect(
    () => () => {
      if (resetTimer.current !== undefined) {
        window.clearTimeout(resetTimer.current);
      }
    },
    [],
  );

  /** 阻止父 disclosure handler，并将复制反馈限制在发起控件内；剪贴板内容不进入 React 状态。 */
  const copy = async (event: MouseEvent<HTMLButtonElement>): Promise<void> => {
    event.stopPropagation();
    if (state === "copying") {
      return;
    }
    setState("copying");
    try {
      await onCopyText(typeof text === "function" ? text() : text);
      setState("copied");
    } catch {
      setState("failed");
    }
    if (resetTimer.current !== undefined) {
      window.clearTimeout(resetTimer.current);
    }
    resetTimer.current = window.setTimeout(() => setState("idle"), 1_600);
  };

  const accessibleLabel =
    state === "copied" ? "已复制" : state === "failed" ? `${label}失败，重试` : label;
  const Icon =
    state === "copied"
      ? Check
      : state === "failed"
        ? TriangleAlert
        : state === "copying"
          ? LoaderCircle
          : Copy;
  return (
    <button
      type="button"
      className={cn("ja-copy-text-button", state === "copying" && "is-pending", className)}
      aria-label={accessibleLabel}
      title={accessibleLabel}
      disabled={state === "copying"}
      onClick={(event) => void copy(event)}
    >
      <Icon aria-hidden="true" />
    </button>
  );
}
