// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Braces,
  ChevronDown,
  ChevronRight,
  CodeXml,
  FileBraces,
  FileCode2,
  Globe2,
  Wrench,
} from "lucide-react";
import type { ReactElement } from "react";
import {
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuLabel,
  MenuSub,
  MenuSubContent,
  MenuSubTrigger,
  MenuTrigger,
} from "@/shared/ui/primitives";
import type { WorkspaceOpenTarget, WorkspaceOpenTargetInfo } from "../../domain/openTarget";
import "./ReplyFileOpenMenu.css";

export interface ReplyFileOpenMenuProps {
  files: readonly string[];
  targets: readonly WorkspaceOpenTargetInfo[];
  discovering?: boolean;
  opening?: boolean;
  error?: string;
  onRetryDiscovery?: () => void;
  onOpen: (relativePath: string, target: WorkspaceOpenTarget) => void | Promise<void>;
}

/** 将原生 Editor Enum 映射为紧凑 Glyph，避免依赖第三方品牌资产。 */
function EditorIcon({ target }: { target: WorkspaceOpenTarget }): ReactElement {
  switch (target) {
    case "vscode":
      return <CodeXml aria-hidden="true" />;
    case "visual_studio":
      return <Braces aria-hidden="true" />;
    case "zed":
      return <FileBraces aria-hidden="true" />;
    case "pycharm":
      return <Wrench aria-hidden="true" />;
    case "webstorm":
      return <Globe2 aria-hidden="true" />;
    default:
      return <FileCode2 aria-hidden="true" />;
  }
}

/** 紧凑标签使用 Basename，同时在 Tooltip 保留完整相对路径。 */
function fileLabel(path: string): string {
  return path.split("/").at(-1) ?? path;
}

/** 只为一个权威 Reply File 渲染已安装 Editor 动作。 */
function EditorItems({
  path,
  targets,
  opening,
  onOpen,
}: Pick<ReplyFileOpenMenuProps, "targets" | "opening" | "onOpen"> & {
  path: string;
}): ReactElement {
  return (
    <>
      {targets.map((target) => (
        <MenuItem
          key={target.target}
          className="ja-reply-file-menu-item"
          disabled={opening === true}
          onSelect={() => onOpen(path, target.target)}
        >
          <EditorIcon target={target.target} />
          <span>{target.displayName}</span>
        </MenuItem>
      ))}
    </>
  );
}

/**
 * Reply File 打开入口位于 Conversation Header。多个文件使用嵌套 Editor Menu，
 * 单文件保持参考桌面设计中的单层快速 Menu。
 */
export function ReplyFileOpenMenu({
  files,
  targets,
  discovering,
  opening,
  error,
  onRetryDiscovery,
  onOpen,
}: ReplyFileOpenMenuProps): ReactElement | null {
  if (files.length === 0 || (!discovering && targets.length === 0 && error === undefined))
    return null;
  return (
    <Menu>
      <MenuTrigger asChild>
        <IconButton
          className="ja-reply-file-trigger"
          label="打开回复中的文件"
          aria-busy={discovering === true || opening === true}
        >
          <FileCode2 aria-hidden="true" />
          <ChevronDown aria-hidden="true" />
        </IconButton>
      </MenuTrigger>
      <MenuContent
        className="ja-reply-file-menu"
        align="end"
        sideOffset={6}
        aria-label="回复中的文件"
      >
        {discovering === true ? (
          <p className="ja-reply-file-status" role="status">
            正在查找可用编辑器…
          </p>
        ) : null}
        {discovering !== true && targets.length === 0 && error === undefined ? (
          <p className="ja-reply-file-status" role="status">
            没有检测到可用编辑器
          </p>
        ) : null}
        {targets.length === 0 ? null : files.length === 1 ? (
          <>
            <MenuLabel className="ja-reply-file-label" title={files[0]}>
              {fileLabel(files[0]!)}
            </MenuLabel>
            <EditorItems path={files[0]!} targets={targets} opening={opening} onOpen={onOpen} />
          </>
        ) : (
          files.map((path) => (
            <MenuSub key={path}>
              <MenuSubTrigger
                className="ja-reply-file-menu-item ja-reply-file-subtrigger"
                title={path}
              >
                <FileCode2 aria-hidden="true" />
                <span>{fileLabel(path)}</span>
                <ChevronRight aria-hidden="true" />
              </MenuSubTrigger>
              <MenuSubContent
                className="ja-reply-file-menu"
                sideOffset={6}
                alignOffset={-5}
                aria-label={`使用编辑器打开 ${path}`}
              >
                <MenuLabel className="ja-reply-file-label" title={path}>
                  {path}
                </MenuLabel>
                <EditorItems path={path} targets={targets} opening={opening} onOpen={onOpen} />
              </MenuSubContent>
            </MenuSub>
          ))
        )}
        {error === undefined ? null : (
          <div className="ja-reply-file-error" role="alert">
            <p>{error}</p>
            {onRetryDiscovery === undefined ? null : (
              <button type="button" className="ja-reply-file-retry" onClick={onRetryDiscovery}>
                重试查找编辑器
              </button>
            )}
          </div>
        )}
      </MenuContent>
    </Menu>
  );
}
