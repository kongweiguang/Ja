// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactElement } from "react";
import {
  SharedMarkdown,
  type MarkdownFileTarget,
  type SharedMarkdownProps,
} from "@/shared/ui/markdown/SharedMarkdown";

export type { MarkdownFileTarget };
export type MarkdownMessageProps = Omit<SharedMarkdownProps, "basePath">;

/** Conversation Markdown 没有文档目录基准；共享安全 renderer 保持路径相对当前 Workspace。 */
export function MarkdownMessage(props: MarkdownMessageProps): ReactElement {
  return <SharedMarkdown {...props} />;
}
