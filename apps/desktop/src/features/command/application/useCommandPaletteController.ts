// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useMemo, useRef, useState } from "react";
import {
  createCommandRegistry,
  searchCommandActions,
  type CommandDescriptor,
} from "../domain/commandRegistry";
import {
  isCommandActionAvailable,
  resolveCommandActions,
  type CommandAction,
} from "./commandActions";

/** UI 只消费脱敏后的展示字段和 running 状态，不获得真实 invoke 回调。 */
export interface CommandItemViewModel extends CommandDescriptor {
  running: boolean;
}

/** Command Palette 的单一渲染投影，所有选择和执行事实都由 controller 计算。 */
export interface CommandPaletteViewModel {
  query: string;
  commands: readonly CommandItemViewModel[];
  activeCommandId?: string;
  executionError?: string;
  busy: boolean;
}

/** UI 可表达的窄意图，不允许绕过 application 直接执行 CommandAction。 */
export interface CommandPaletteActions {
  changeOpen(nextOpen: boolean): void;
  changeQuery(query: string): void;
  moveSelection(delta: number): void;
  selectCommand(commandId: string): void;
  executeCommand(commandId: string): void;
}

/** composition 使用的完整 controller 合同，viewModel 与 actions 保持显式分组。 */
export interface CommandPaletteController {
  viewModel: CommandPaletteViewModel;
  actions: CommandPaletteActions;
}

interface UseCommandPaletteControllerOptions {
  readonly commands: readonly CommandAction[];
  readonly onOpenChange: (open: boolean) => void;
  readonly onActionError?: (action: CommandAction, error: unknown) => void;
}

/**
 * application 统一拥有搜索、选择与每个 Command id 的 single-flight；关闭 Palette 不取消
 * 已开始的副作用，失败会重新打开并保留稳定错误，避免 UI 重复触发或吞掉失败。
 */
export function useCommandPaletteController({
  commands,
  onOpenChange,
  onActionError,
}: UseCommandPaletteControllerOptions): CommandPaletteController {
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const [runningCommandIds, setRunningCommandIds] = useState<ReadonlySet<string>>(() => new Set());
  const [executionError, setExecutionError] = useState<string | undefined>();
  const runningCommandIdsRef = useRef<Set<string>>(new Set());

  const registry = useMemo(() => createCommandRegistry(commands), [commands]);
  const availableCommands = useMemo(
    () => resolveCommandActions(registry).filter((command) => command.available),
    [registry],
  );
  const filteredCommands = useMemo(
    () => searchCommandActions(availableCommands, query),
    [availableCommands, query],
  );
  const resolvedActiveIndex = Math.min(activeIndex, Math.max(0, filteredCommands.length - 1));

  /** 关闭时清空瞬态查询和选择；重新打开失败态由执行 catch 单独控制，不能在此被覆盖。 */
  const changeOpen = useCallback(
    (nextOpen: boolean): void => {
      if (!nextOpen) {
        setQuery("");
        setActiveIndex(0);
        setExecutionError(undefined);
      } else {
        setExecutionError(undefined);
      }
      onOpenChange(nextOpen);
    },
    [onOpenChange],
  );

  /** 查询变化同时重置选择，避免 active-descendant 指向过滤后不存在的旧位置。 */
  const changeQuery = useCallback((nextQuery: string): void => {
    setQuery(nextQuery);
    setActiveIndex(0);
    setExecutionError(undefined);
  }, []);

  /** 在当前过滤快照内循环移动，空结果时保持零索引且不制造不可见选择。 */
  const moveSelection = useCallback(
    (delta: number): void => {
      if (filteredCommands.length === 0) return;
      setActiveIndex(
        (index) => (index + delta + filteredCommands.length) % filteredCommands.length,
      );
    },
    [filteredCommands.length],
  );

  /** 只接纳当前可见 Command id，指针事件不能选择已因状态变化而消失的动作。 */
  const selectCommand = useCallback(
    (commandId: string): void => {
      const index = filteredCommands.findIndex((command) => command.id === commandId);
      if (index >= 0) setActiveIndex(index);
    },
    [filteredCommands],
  );

  /**
   * 在同步 ref 栅栏后再关闭面板和启动 Promise，封住 React state 提交前的双击窗口；
   * finally 只释放同一 id，不影响并行执行的其它独立 Command。
   */
  const executeCommand = useCallback(
    (commandId: string): void => {
      const command = filteredCommands.find((candidate) => candidate.id === commandId);
      if (
        command === undefined ||
        runningCommandIdsRef.current.has(command.id) ||
        !isCommandActionAvailable(command)
      )
        return;

      runningCommandIdsRef.current.add(command.id);
      setRunningCommandIds((current) => new Set(current).add(command.id));
      changeOpen(false);

      void Promise.resolve()
        .then(() => command.invoke())
        .catch((error: unknown) => {
          onActionError?.(command, error);
          setExecutionError("操作执行失败，请重试。");
          onOpenChange(true);
        })
        .finally(() => {
          runningCommandIdsRef.current.delete(command.id);
          setRunningCommandIds((current) => {
            const next = new Set(current);
            next.delete(command.id);
            return next;
          });
        });
    },
    [changeOpen, filteredCommands, onActionError, onOpenChange],
  );

  const viewCommands = useMemo<readonly CommandItemViewModel[]>(
    () =>
      filteredCommands.map((command) => ({
        id: command.id,
        label: command.label,
        keywords: command.keywords,
        ...(command.shortcut === undefined ? {} : { shortcut: command.shortcut }),
        ...(command.description === undefined ? {} : { description: command.description }),
        ...(command.icon === undefined ? {} : { icon: command.icon }),
        running: runningCommandIds.has(command.id),
      })),
    [filteredCommands, runningCommandIds],
  );

  return useMemo(
    () => ({
      viewModel: {
        query,
        commands: viewCommands,
        ...(viewCommands[resolvedActiveIndex] === undefined
          ? {}
          : { activeCommandId: viewCommands[resolvedActiveIndex]?.id }),
        ...(executionError === undefined ? {} : { executionError }),
        busy: runningCommandIds.size > 0,
      },
      actions: {
        changeOpen,
        changeQuery,
        moveSelection,
        selectCommand,
        executeCommand,
      },
    }),
    [
      changeOpen,
      changeQuery,
      executeCommand,
      executionError,
      moveSelection,
      query,
      resolvedActiveIndex,
      runningCommandIds.size,
      selectCommand,
      viewCommands,
    ],
  );
}
