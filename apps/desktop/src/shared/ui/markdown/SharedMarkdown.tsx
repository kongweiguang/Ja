// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import remarkGfm from "remark-gfm";
import ReactMarkdown, { type Components } from "react-markdown";
import {
  isValidElement,
  useRef,
  useState,
  useMemo,
  type KeyboardEvent,
  type MouseEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { Copy, Eye, ExternalLink } from "lucide-react";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import { MenuItem, MenuSeparator, PointerContextMenu } from "@/shared/ui/primitives";

export interface MarkdownFileTarget {
  path: string;
  line?: number;
  column?: number;
}

export interface SharedMarkdownProps {
  content: string;
  className?: string;
  /** Markdown 文件预览传入源文件路径；相对链接因此以文档目录为基准。 */
  basePath?: string;
  /** Web URL 交给 Host 的当前 Thread Preview；来源只用于失败时恢复键盘焦点。 */
  onOpenLink?: (url: string, source?: HTMLElement) => void | Promise<void>;
  /** 点击才解析文件；Ctrl+点击把同一目标交给原生资源管理器入口。 */
  onOpenFile?: (
    target: MarkdownFileTarget,
    source: HTMLElement,
    mode?: "explorer" | "browser",
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

/** 盘符先转为 file URI 避免被过滤；行列后缀独立保留，防止编码为文件名后失去定位语义。 */
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
          const location = fragmentIndex < 0 ? pathWithSeparators.match(/:\d+(?::\d+)?$/u) : null;
          const path =
            location === null
              ? pathWithSeparators
              : pathWithSeparators.slice(0, -location[0].length);
          let decodedPath: string;
          try {
            decodedPath = decodeURIComponent(path);
          } catch {
            decodedPath = path;
          }
          const normalizedPath = decodedPath.replace(/\\/gu, "/");
          const drive = normalizedPath.slice(0, 2);
          const components = normalizedPath.slice(3).split("/");
          node.url = `file:///${drive}/${components.map((part) => encodeURIComponent(part)).join("/")}${location?.[0] ?? ""}${fragment}`;
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

/** 相对引用保持相对形式，只以前置文档目录确定基准；真实存在与权限仍由 Rust resolver 判定。 */
function resolveMarkdownTargetPath(path: string, basePath?: string): string {
  if (
    basePath === undefined ||
    basePath.length === 0 ||
    path.startsWith("file://") ||
    /^[a-z]:[\\/]/iu.test(path) ||
    path.startsWith("/") ||
    path.startsWith("\\\\")
  )
    return path;
  const separator = Math.max(basePath.lastIndexOf("/"), basePath.lastIndexOf("\\"));
  if (separator < 0) return path;
  const parent = basePath.slice(0, separator);
  const joiner = basePath[separator] === "\\" ? "\\" : "/";
  return `${parent}${joiner}${path}`
    .split(/[\\/]/u)
    .filter((segment) => segment !== ".")
    .join(joiner);
}

interface MarkdownFileReferenceProps {
  target: MarkdownFileTarget;
  className: string;
  children: ReactNode;
  onOpenFile: NonNullable<SharedMarkdownProps["onOpenFile"]>;
  onCopyText?: SharedMarkdownProps["onCopyText"];
}

/** 短标签通过悬停提示暴露完整目标；打开、预览、Explorer 和复制始终使用同一受控路径。 */
function MarkdownFileReference({
  target,
  className,
  children,
  onOpenFile,
  onCopyText,
}: MarkdownFileReferenceProps): ReactElement {
  const buttonRef = useRef<HTMLButtonElement>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number }>();
  const canPreviewInBrowser = /\.(?:html?|xhtml)$/iu.test(target.path);

  /** 菜单项沿用当前 button 作为来源；失败时恢复焦点供用户重新打开。 */
  function open(mode?: "explorer" | "browser"): void {
    const source = buttonRef.current;
    if (source === null) return;
    try {
      const opening =
        mode === undefined ? onOpenFile(target, source) : onOpenFile(target, source, mode);
      void Promise.resolve(opening).catch(() => focusSourceAfterFailure(source));
    } catch {
      focusSourceAfterFailure(source);
    }
  }

  /** 右键位置由 Radix 负责碰撞和视口避让；键盘菜单锚在路径按钮下方。 */
  function showContextMenu(x: number, y: number): void {
    setContextMenu({ x, y });
  }

  /** Windows Ctrl+Enter 保留快速 Explorer 行为，Shift+F10/菜单键显示完整动作。 */
  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>): void {
    if (event.key === "Enter" && event.ctrlKey) {
      event.preventDefault();
      event.stopPropagation();
      open("explorer");
      return;
    }
    if (event.key !== "ContextMenu" && !(event.key === "F10" && event.shiftKey)) return;
    event.preventDefault();
    event.stopPropagation();
    const bounds = event.currentTarget.getBoundingClientRect();
    showContextMenu(bounds.left, bounds.bottom);
  }

  /** 阻止 Chromium 原生菜单，避免绕过本地路径 resolver 和 host capability。 */
  function handleContextMenu(event: MouseEvent<HTMLButtonElement>): void {
    event.preventDefault();
    event.stopPropagation();
    showContextMenu(event.clientX, event.clientY);
  }

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className={className}
        data-file-reference={target.path}
        aria-label={`在 Ja 中打开路径 ${target.path}`}
        aria-description="按 Ctrl+Enter 在文件资源管理器中打开所在位置；右键可选择其他打开方式"
        aria-keyshortcuts="Control+Enter"
        title={`${target.path}\n点击在 Ja 中打开；Ctrl+点击或 Ctrl+Enter 打开资源管理器；右键显示更多操作`}
        onClick={(event) => open(event.ctrlKey ? "explorer" : undefined)}
        onKeyDown={handleKeyDown}
        onContextMenu={handleContextMenu}
      >
        {children}
      </button>
      {contextMenu === undefined ? null : (
        <PointerContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          label={`${target.path} 路径操作`}
          onOpenChange={(openMenu) => {
            if (!openMenu) setContextMenu(undefined);
          }}
          onRestoreFocus={() => buttonRef.current?.focus()}
        >
          <MenuItem onSelect={() => open()}>
            <ExternalLink aria-hidden="true" />
            <span>在 Ja 中打开</span>
          </MenuItem>
          {canPreviewInBrowser ? (
            <MenuItem onSelect={() => open("browser")}>
              <Eye aria-hidden="true" />
              <span>在浏览器中预览</span>
            </MenuItem>
          ) : null}
          <MenuItem onSelect={() => open("explorer")}>
            <ExternalLink aria-hidden="true" />
            <span>在文件资源管理器中打开</span>
          </MenuItem>
          <MenuSeparator />
          <MenuItem
            disabled={onCopyText === undefined}
            onSelect={() => {
              if (onCopyText === undefined) return;
              const source = buttonRef.current;
              void onCopyText(target.path).catch(() => {
                if (source !== null) focusSourceAfterFailure(source);
              });
            }}
          >
            <Copy aria-hidden="true" />
            <span>复制路径</span>
          </MenuItem>
        </PointerContextMenu>
      )}
    </>
  );
}

/** 错误不会恢复为主 WebView 导航；同时让键盘用户仍可回到引用位置重试。 */
function focusSourceAfterFailure(source: HTMLElement): void {
  source.focus();
}

/**
 * 在 Renderer 边界替换 Markdown Anchor/Image，使 Model Content 不能导航主 WebView
 * 或加载任意远程资源。
 */
function createSafeMarkdownComponents(
  onOpenLink: SharedMarkdownProps["onOpenLink"],
  onOpenFile: SharedMarkdownProps["onOpenFile"],
  onCopyText: SharedMarkdownProps["onCopyText"],
  basePath?: string,
): Components {
  return {
    a: ({ href, children }: { href?: string; children?: ReactNode }) => {
      const url = safeHttpUrl(href);
      if (url !== undefined && onOpenLink !== undefined) {
        /** 按钮语义可阻止辅助点击、上下文菜单和键盘默认行为跳转到外壳窗口。 */
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
      const parsedTarget = href === undefined ? undefined : parseFileReference(href, true, true);
      const fileTarget =
        parsedTarget === undefined
          ? undefined
          : { ...parsedTarget, path: resolveMarkdownTargetPath(parsedTarget.path, basePath) };
      if (fileTarget !== undefined && onOpenFile !== undefined) {
        return (
          <MarkdownFileReference
            target={fileTarget}
            className="ja-markdown__file-link"
            onOpenFile={onOpenFile}
            onCopyText={onCopyText}
          >
            {children}
          </MarkdownFileReference>
        );
      }
      return <span className="ja-markdown__plain-link">{children}</span>;
    },
    code: ({ className, children }: { className?: string; children?: ReactNode }) => {
      const text = textFromMarkdownNode(children).replace(/\n$/u, "");
      const parsedTarget = parseFileReference(text, true);
      const fileTarget =
        parsedTarget === undefined
          ? undefined
          : { ...parsedTarget, path: resolveMarkdownTargetPath(parsedTarget.path, basePath) };
      if (fileTarget !== undefined && onOpenFile !== undefined) {
        return (
          <MarkdownFileReference
            target={fileTarget}
            className="ja-markdown__file-link ja-markdown__inline-file-link"
            onOpenFile={onOpenFile}
            onCopyText={onCopyText}
          >
            <code className={className}>{children}</code>
          </MarkdownFileReference>
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
export function SharedMarkdown({
  content,
  className,
  basePath,
  onOpenLink,
  onOpenFile,
  onCopyText,
}: SharedMarkdownProps): ReactElement {
  // delta 只替换 Markdown 内容；稳定组件映射可避免每个 24ms 批次都让 ReactMarkdown 重建渲染器。
  const components = useMemo(
    () => createSafeMarkdownComponents(onOpenLink, onOpenFile, onCopyText, basePath),
    [basePath, onCopyText, onOpenFile, onOpenLink],
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
