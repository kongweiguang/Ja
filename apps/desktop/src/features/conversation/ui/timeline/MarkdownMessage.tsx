// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import rehypeSanitize from "rehype-sanitize";
import remarkGfm from "remark-gfm";
import ReactMarkdown, { type Components } from "react-markdown";
import { isValidElement, useMemo, type MouseEvent, type ReactElement, type ReactNode } from "react";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";

interface MarkdownMessageProps {
  content: string;
  className?: string;
  /** 安全 URL 由 Host 决定在 Preview 还是外部 Opener 中打开。 */
  onOpenLink?: (url: string) => void | Promise<void>;
  /** Clipboard Write 始终是 Host Capability，不回退为浏览器隐式能力。 */
  onCopyText?: (text: string) => Promise<void>;
}

/** 只接受绝对 Web URL，防止相对链接或自定义协议导航主 Shell。 */
function safeHttpUrl(value: string | undefined): string | undefined {
  if (value === undefined || value.trim() === "") {
    return undefined;
  }
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:" ? url.toString() : undefined;
  } catch {
    return undefined;
  }
}

/**
 * 在 Renderer 边界替换 Markdown Anchor/Image，使 Model Content 不能导航主 WebView
 * 或加载任意远程资源。
 */
function createSafeMarkdownComponents(
  onOpenLink: MarkdownMessageProps["onOpenLink"],
  onCopyText: MarkdownMessageProps["onCopyText"],
): Components {
  return {
    a: ({ href, children }: { href?: string; children?: ReactNode }) => {
      const url = safeHttpUrl(href);
      if (url === undefined || onOpenLink === undefined) {
        return <span className="ja-markdown__plain-link">{children}</span>;
      }
      const handleClick = (event: MouseEvent<HTMLAnchorElement>): void => {
        event.preventDefault();
        try {
          void Promise.resolve(onOpenLink(url)).catch(() => undefined);
        } catch {
          // Host Callback 失败也不能恢复浏览器导航，否则会绕过安全边界。
        }
      };
      return (
        <a href={url} onClick={handleClick}>
          {children}
        </a>
      );
    },
    img: ({ alt, src }: { alt?: string; src?: string }) => (
      <span className="ja-markdown__plain-image">{alt?.trim() || src?.trim() || "图片"}</span>
    ),
    pre: ({ children }: { children?: ReactNode }) => {
      const code = textFromMarkdownNode(children).replace(/\n$/u, "");
      return (
        <div className="ja-markdown__code-block">
          {onCopyText === undefined || code === "" ? null : (
            <CopyTextButton
              text={code}
              label="复制代码"
              onCopyText={onCopyText}
              className="ja-markdown__copy-code"
            />
          )}
          <pre>{children}</pre>
        </div>
      );
    },
  };
}

/** 只从已净化 Markdown Node Tree 提取渲染文本，确保代码复制不序列化 React Props、Raw HTML
 * 或可执行 Markup。 */
function textFromMarkdownNode(node: ReactNode): string {
  if (typeof node === "string" || typeof node === "number") {
    return String(node);
  }
  if (Array.isArray(node)) {
    return node.map(textFromMarkdownNode).join("");
  }
  if (isValidElement<{ children?: ReactNode }>(node)) {
    return textFromMarkdownNode(node.props.children);
  }
  return "";
}

/**
 * 在 Render 阶段净化既保留 Model 编写 Markdown 的可用性，又让 Script、Event Handler、
 * Unsafe URL 与 Embedded Object Payload 失去执行能力；组件映射按 Host Callback 缓存，避免真实 delta
 * 高频更新时反复重建 Markdown Renderer。
 */
export function MarkdownMessage({
  content,
  className,
  onOpenLink,
  onCopyText,
}: MarkdownMessageProps): ReactElement {
  // delta 只替换 Markdown 内容；稳定组件映射可避免每个 24ms 批次都让 ReactMarkdown 重建渲染器。
  const components = useMemo(
    () => createSafeMarkdownComponents(onOpenLink, onCopyText),
    [onCopyText, onOpenLink],
  );
  return (
    <div className={className === undefined ? "ja-markdown" : `ja-markdown ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeSanitize]}
        components={components}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
