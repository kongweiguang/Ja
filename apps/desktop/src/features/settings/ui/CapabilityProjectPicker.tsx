// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Check, ChevronsUpDown, Search } from "lucide-react";
import { useMemo, useRef, useState, type ReactElement } from "react";
import { Button, Popover, PopoverContent, PopoverTrigger } from "@/shared/ui/primitives";
import type { WorkspaceProjection } from "@/features/workspace";

/** 项目选择只发布已登记 ID；搜索与展开状态不会触碰当前会话工作区。 */
export function CapabilityProjectPicker({
  projects,
  selectedProjectId,
  disabled = false,
  onSelectProject,
}: {
  projects: readonly WorkspaceProjection[];
  selectedProjectId: string | undefined;
  disabled?: boolean;
  onSelectProject: (workspaceId: string) => void;
}): ReactElement {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const searchRef = useRef<HTMLInputElement>(null);
  const selected = projects.find((project) => project.workspaceId === selectedProjectId);
  const selectionLabel =
    selected?.displayName ?? (selectedProjectId === undefined ? "选择项目" : "项目不可用");
  const filtered = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    return projects.filter(
      (project) =>
        project.kind === "project" &&
        (needle === "" ||
          `${project.displayName} ${project.rootPath}`.toLocaleLowerCase().includes(needle)),
    );
  }, [projects, query]);

  /** 弹层结束时清理搜索，但保留由外层持有的项目筛选身份。 */
  const updateOpen = (next: boolean): void => {
    setOpen(next);
    if (!next) setQuery("");
  };

  return (
    <Popover open={open} onOpenChange={updateOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          className="ja-capability-project-trigger"
          aria-label={`选择设置项目：${selectionLabel}`}
          title={selected?.rootPath}
          disabled={disabled}
        >
          <span>{selectionLabel}</span>
          <ChevronsUpDown size={14} aria-hidden="true" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        className="ja-capability-project-popover"
        align="end"
        sideOffset={6}
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          searchRef.current?.focus();
        }}
      >
        <label className="ja-capability-project-search">
          <Search size={15} aria-hidden="true" />
          <input
            ref={searchRef}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            aria-label="搜索已有项目"
            placeholder="搜索项目或路径"
            onKeyDown={(event) => {
              if (event.key !== "ArrowDown") return;
              event.preventDefault();
              event.currentTarget.parentElement?.parentElement
                ?.querySelector<HTMLButtonElement>(".ja-capability-project-option")
                ?.focus();
            }}
          />
        </label>
        <div
          className="ja-capability-project-options"
          role="listbox"
          aria-label="已有项目"
          onKeyDown={(event) => {
            if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
            const buttons = Array.from(
              event.currentTarget.querySelectorAll<HTMLButtonElement>("button"),
            );
            const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
            const next = Math.max(
              0,
              Math.min(buttons.length - 1, index + (event.key === "ArrowDown" ? 1 : -1)),
            );
            event.preventDefault();
            buttons[next]?.focus();
          }}
        >
          {filtered.length === 0 ? (
            <p className="ja-capability-project-empty">没有匹配的项目</p>
          ) : (
            filtered.map((project) => (
              <button
                type="button"
                role="option"
                aria-selected={project.workspaceId === selectedProjectId}
                className="ja-capability-project-option"
                key={project.workspaceId}
                title={project.rootPath}
                onClick={() => {
                  onSelectProject(project.workspaceId);
                  updateOpen(false);
                }}
              >
                <span className="ja-capability-project-name">{project.displayName}</span>
                <span className="ja-capability-project-path">{project.rootPath}</span>
                {project.workspaceId === selectedProjectId ? (
                  <Check size={15} aria-hidden="true" />
                ) : null}
              </button>
            ))
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
