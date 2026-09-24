// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import remarkGfm from "remark-gfm";
import ReactMarkdown, { type Components } from "react-markdown";
import {
  isValidElement,
  useMemo,
  type KeyboardEvent,
  type MouseEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";

export interface MarkdownFileTarget {
  path: string;
  line?: number;
  column?: number;
}

interface MarkdownMessageProps {
  content: string;
  className?: string;
  /** Web URL 交给 Host 的当前 Thread Preview；来源只用于失败时恢复键盘焦点。 */
  onOpenLink?: (url: string, source?: HTMLElement) => void | Promise<void>;
  /** 点击才解析文件；Ctrl+点击把同一目标交给原生资源管理器入口。 */
  onOpenFile?: (
    target: MarkdownFileTarget,
    source: HTMLElement,
    mode?: "explorer",
  ) => void | Promise<void>;
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

const WINDOWS_DRIVE_PROTOCOLS = [..."abcdefghijklmnopqrstuvwxyz"];
const MARKDOWN_SANITIZE_SCHEMA = {
  ...defaultSchema,
  protocols: {
    ...defaultSchema.protocols,
    href: [
      ...new Set([
        ...(defaultSchema.protocols?.["href"] ?? []),
        "file",
        ...WINDOWS_DRIVE_PROTOCOLS,
      ]),
    ],
  },
};

interface MarkdownAstNode {
  type?: string;
  url?: string;
  children?: MarkdownAstNode[];
}

const FILE_EXTENSION =
  /\.(?:c|cc|cpp|cs|css|csv|go|h|hpp|htm|html|java|js|jsx|json|jsonc|kt|md|mdx|mjs|mts|pdf|php|png|jpe?g|gif|webp|avif|svg|rs|rst|scss|sh|sql|toml|ts|tsx|txt|wav|webm|mp3|mp4|yaml|yml|xml)$/iu;

/** 仅把有路径特征或已知文件扩展名的文本当引用，避免把普通代码标识符变成按钮。 */
function looksLikeFileReference(value: string): boolean {
  const candidate = value.trim().replace(/[),.;]+$/u, "");
  return (
    candidate.startsWith("file://") ||
    /^[a-z]:[\\/]/iu.test(candidate) ||
    candidate.startsWith("/") ||
    candidate.startsWith("./") ||
    candidate.startsWith("../") ||
    /[\\/]/u.test(candidate) ||
    FILE_EXTENSION.test(candidate.replace(/#L\d+(?:C\d+)?$/iu, "").replace(/:\d+(?::\d+)?$/u, ""))
  );
}

/** 保留显式本地 Markdown 目标，让自定义 anchor renderer 接管点击且不给主 WebView 导航机会。 */
function isLocalMarkdownDestination(value: string): boolean {
  const candidate = value.trim();
  if (
    candidate === "" ||
    candidate.startsWith("#") ||
    candidate.startsWith("?") ||
    candidate.startsWith("//")
  )
    return false;
  if (candidate.startsWith("file://") || /^[a-z]:[\\/]/iu.test(candidate)) return true;
  if (/^[a-z][a-z0-9+.-]*:/iu.test(candidate)) return false;
  return true;
}

/** react-markdown 默认在组件映射前剥离 file: 与盘符 scheme，因此只保留显式本地引用及 HTTP(S)。 */
function transformMarkdownUrl(value: string, key: string): string {
  if (key === "href") {
    const webUrl = safeHttpUrl(value);
    if (webUrl !== undefined) return webUrl;
    if (isLocalMarkdownDestination(value)) return value;
  }
  return "";
}

/** Normalize explicit Windows-drive Markdown destinations before sanitizing, which otherwise treats `C:` as a URI scheme. */
function remarkWindowsDriveLinks(): (tree: MarkdownAstNode) => void {
  return (tree: MarkdownAstNode): void => {
    const pending = [tree];
    while (pending.length > 0) {
      const node = pending.pop();
      if (node === undefined) continue;
      if (node.type === "link" && typeof node.url === "string") {
        const destination = node.url;
        const drivePath = destination.match(/^([a-z]):[\\/](.*)$/iu);
        if (drivePath !== null) {
          const fragmentIndex = destination.indexOf("#");
          const pathWithSeparators =
            fragmentIndex < 0 ? destination : destination.slice(0, fragmentIndex);
          const fragment = fragmentIndex < 0 ? "" : destination.slice(fragmentIndex);
          let decodedPath: string;
          try {
            decodedPath = decodeURIComponent(pathWithSeparators);
          } catch {
            decodedPath = pathWithSeparators;
          }
          const normalizedPath = decodedPath.replace(/\\/gu, "/");
          const drive = normalizedPath.slice(0, 2);
          const components = normalizedPath.slice(3).split("/");
          node.url = `file:///${drive}/${components.map((part) => encodeURIComponent(part)).join("/")}${fragment}`;
        }
      }
      if (node.children !== undefined) pending.push(...node.children);
    }
  };
}

/** 从新旧消息两种位置格式提取打开目标；路径解析和文件 IO 延迟到点击后的 Host。 */
function parseFileReference(
  value: string,
  allowLineFragment: boolean,
  allowUnknownExtension = false,
): MarkdownFileTarget | undefined {
  let candidate = value.trim().replace(/^[`<]|[`>,.;]+$/gu, "");
  if (candidate === "" || candidate.length > 4_096) return undefined;

  let line: number | undefined;
  let column: number | undefined;
  if (allowLineFragment) {
    const location = candidate.match(/#L(\d+)(?:C(\d+))?$/iu);
    if (location !== null) {
      line = Number(location[1]);
      column = location[2] === undefined ? undefined : Number(location[2]);
      if (
        !Number.isSafeInteger(line) ||
        line < 1 ||
        (column !== undefined && (!Number.isSafeInteger(column) || column < 1))
      )
        return undefined;
      candidate = candidate.slice(0, location.index);
    }
  }
  if (allowUnknownExtension && candidate.includes("#")) {
    candidate = candidate.slice(0, candidate.indexOf("#"));
  }
  if (line === undefined) {
    const location = candidate.match(/:(\d+)(?::(\d+))?$/u);
    if (location !== null) {
      line = Number(location[1]);
      column = location[2] === undefined ? undefined : Number(location[2]);
      if (
        !Number.isSafeInteger(line) ||
        line < 1 ||
        (column !== undefined && (!Number.isSafeInteger(column) || column < 1))
      )
        return undefined;
      candidate = candidate.slice(0, location.index);
    }
  }
  if (
    candidate === "" ||
    candidate.includes("?") ||
    (!allowUnknownExtension && !looksLikeFileReference(candidate)) ||
    (allowUnknownExtension && !isLocalMarkdownDestination(candidate))
  )
    return undefined;

  if (/^file:/iu.test(candidate)) {
    try {
      const url = new URL(candidate);
      if (url.protocol !== "file:") return undefined;
      const decodedPath = decodeURIComponent(url.pathname);
      if (url.hostname !== "" && url.hostname !== "localhost")
        candidate = `\\\\${url.hostname}${decodedPath.replace(/\//gu, "\\")}`;
      else {
        candidate = decodedPath;
        if (/^\/[a-z]:\//iu.test(candidate)) candidate = candidate.slice(1);
      }
    } catch {
      return undefined;
    }
  } else if (allowUnknownExtension && /%[0-9a-f]{2}/iu.test(candidate)) {
    try {
      candidate = decodeURIComponent(candidate);
    } catch {
      return undefined;
    }
  }
  return {
    path: candidate,
    ...(line === undefined ? {} : { line }),
    ...(column === undefined ? {} : { column }),
  };
}

/** 错误不会恢复为主 WebView 导航；同时让键盘用户仍可回到引用位置重试。 */
function focusSourceAfterFailure(source: HTMLElement): void {
  source.focus();
}

/** 两种文件引用共用同一操作语义，确保键盘入口和修饰键点击都经过原生路径核验。 */
function fileReferenceInteractions(
  target: MarkdownFileTarget,
  onOpenFile: NonNullable<MarkdownMessageProps["onOpenFile"]>,
): {
  onClick: (event: MouseEvent<HTMLButtonElement>) => void;
  onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => void;
} {
  /** 统一恢复失败后的原引用焦点，避免异步打开错误让用户迷失位置。 */
  function open(source: HTMLButtonElement, mode?: "explorer"): void {
    try {
      const opened =
        mode === undefined ? onOpenFile(target, source) : onOpenFile(target, source, mode);
      void Promise.resolve(opened).catch(() => focusSourceAfterFailure(source));
    } catch {
      focusSourceAfterFailure(source);
    }
  }

  return {
    onClick: (event) => open(event.currentTarget, event.ctrlKey ? "explorer" : undefined),
    onKeyDown: (event) => {
      if (event.key !== "Enter" || !event.ctrlKey) return;
      event.preventDefault();
      event.stopPropagation();
      open(event.currentTarget, "explorer");
    },
  };
}

/**
 * 在 Renderer 边界替换 Markdown Anchor/Image，使 Model Content 不能导航主 WebView
 * 或加载任意远程资源。
 */
function createSafeMarkdownComponents(
  onOpenLink: MarkdownMessageProps["onOpenLink"],
  onOpenFile: MarkdownMessageProps["onOpenFile"],
  onCopyText: MarkdownMessageProps["onCopyText"],
): Components {
  return {
    a: ({ href, children }: { href?: string; children?: ReactNode }) => {
      const url = safeHttpUrl(href);
      if (url !== undefined && onOpenLink !== undefined) {
        /** Button semantics prevent auxiliary clicks, context menus, and keyboard defaults from navigating the shell. */
        const handleClick = (event: MouseEvent<HTMLButtonElement>): void => {
          const source = event.currentTarget;
          try {
            void Promise.resolve(onOpenLink(url, source)).catch(() =>
              focusSourceAfterFailure(source),
            );
          } catch {
            focusSourceAfterFailure(source);
          }
        };
        return (
          <button
            type="button"
            className="ja-markdown__file-link ja-markdown__web-link"
            data-web-reference={url}
            aria-label={`在 Ja 浏览器中打开 ${url}`}
            onClick={handleClick}
          >
            {children}
          </button>
        );
      }

      // 只有保留下来的显式 href 能表达链接目标；被 sanitizer 剥离的 href 不能从 label 猜本地路径。
      const fileTarget = href === undefined ? undefined : parseFileReference(href, true, true);
      if (fileTarget !== undefined && onOpenFile !== undefined) {
        return (
          <button
            type="button"
            className="ja-markdown__file-link"
            data-file-reference={fileTarget.path}
            aria-label={`在 Ja 中打开文件 ${fileTarget.path}`}
            aria-description="按 Ctrl+Enter 在文件资源管理器中打开所在文件夹并选中文件"
            aria-keyshortcuts="Control+Enter"
            title="点击在 Ja 中打开；Ctrl+点击或 Ctrl+Enter 打开所在文件夹并选中文件"
            {...fileReferenceInteractions(fileTarget, onOpenFile)}
          >
            {children}
          </button>
        );
      }
      return <span className="ja-markdown__plain-link">{children}</span>;
    },
    code: ({ className, children }: { className?: string; children?: ReactNode }) => {
      const text = textFromMarkdownNode(children).replace(/\n$/u, "");
      const fileTarget = parseFileReference(text, true);
      if (fileTarget !== undefined && onOpenFile !== undefined) {
        return (
          <button
            type="button"
            className="ja-markdown__file-link ja-markdown__inline-file-link"
            data-file-reference={fileTarget.path}
            aria-label={`在 Ja 中打开文件 ${fileTarget.path}`}
            aria-description="按 Ctrl+Enter 在文件资源管理器中打开所在文件夹并选中文件"
            aria-keyshortcuts="Control+Enter"
            title="点击在 Ja 中打开；Ctrl+点击或 Ctrl+Enter 打开所在文件夹并选中文件"
            {...fileReferenceInteractions(fileTarget, onOpenFile)}
          >
            <code className={className}>{children}</code>
          </button>
        );
      }
      return <code className={className}>{children}</code>;
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
          <pre>
            <code>{code}</code>
          </pre>
        </div>
      );
    },
    table: ({ children }: { children?: ReactNode }) => (
      <div className="ja-markdown__table-wrap">
        <table>{children}</table>
      </div>
    ),
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
  onOpenFile,
  onCopyText,
}: MarkdownMessageProps): ReactElement {
  // delta 只替换 Markdown 内容；稳定组件映射可避免每个 24ms 批次都让 ReactMarkdown 重建渲染器。
  const components = useMemo(
    () => createSafeMarkdownComponents(onOpenLink, onOpenFile, onCopyText),
    [onCopyText, onOpenFile, onOpenLink],
  );
  return (
    <div className={className === undefined ? "ja-markdown" : `ja-markdown ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkWindowsDriveLinks]}
        rehypePlugins={[[rehypeSanitize, MARKDOWN_SANITIZE_SCHEMA]]}
        urlTransform={transformMarkdownUrl}
        components={components}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
