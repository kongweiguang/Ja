// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

/** 计时器由 UI composition 注入，application 不直接绑定 window 或测试时钟。 */
export interface SaveTimerPort {
  set(delayMillis: number, callback: () => void): unknown;
  clear(handle: unknown): void;
}

export interface FilesSaveCoordinatorPorts {
  timer: SaveTimerPort;
  saveOnce: (path: string) => Promise<boolean>;
  shouldContinue: (path: string) => boolean;
}

/**
 * 集中拥有文件保存的 debounce 与 single-flight 队列。同一路径任何时刻只允许一个
 * CAS 请求；保存期间的新 flush 只记录一次继续意图，ACK 后再读取最新草稿。
 */
export class FilesSaveCoordinator {
  private readonly timers = new Map<string, unknown>();
  private readonly tasks = new Map<string, Promise<void>>();
  private readonly queuedImmediateFlush = new Set<string>();

  /** 通过窄端口接收时钟和单次 CAS，用依赖注入保持测试确定性与 adapter 可替换性。 */
  public constructor(private readonly ports: FilesSaveCoordinatorPorts) {}

  /**
   * 重置末键计时器；回调只进入 `flush`，确保 debounce、Ctrl+S 与失焦共享同一
   * 串行化入口，不产生第二套保存 owner。
   */
  public schedule(path: string, delayMillis: number): void {
    this.cancelTimer(path);
    const handle = this.ports.timer.set(delayMillis, () => {
      if (this.timers.get(path) !== handle) return;
      this.timers.delete(path);
      void this.flush(path);
    });
    this.timers.set(path, handle);
  }

  /**
   * 取消 debounce 并等待同路径唯一任务。若任务正在执行，只合并继续意图；这样
   * expectedRevision 始终来自上一次 ACK 后的最新投影。
   */
  public async flush(path: string): Promise<void> {
    this.cancelTimer(path);
    const existing = this.tasks.get(path);
    if (existing !== undefined) {
      this.queuedImmediateFlush.add(path);
      await existing;
      return;
    }

    const task = this.runQueue(path);
    this.tasks.set(path, task);
    try {
      await task;
    } finally {
      if (this.tasks.get(path) === task) this.tasks.delete(path);
    }
  }

  /** 取消尚未触发的保存和继续意图；已在 native 执行的 CAS 仍由 generation fence 丢弃晚结果。 */
  public cancel(path: string): void {
    this.cancelTimer(path);
    this.queuedImmediateFlush.delete(path);
  }

  /** 仅取消全部 debounce；workspace flush 随后仍可等待并复用已经开始的 CAS 任务。 */
  public cancelScheduled(): void {
    for (const handle of this.timers.values()) this.ports.timer.clear(handle);
    this.timers.clear();
  }

  /**
   * workspace 切换或卸载时清空当前 generation 的调度索引。任务 Promise 不会被
   * 强行取消，调用方仍依赖 generation fence 阻止旧 ACK 写回新工作区。
   */
  public reset(): void {
    this.cancelScheduled();
    this.tasks.clear();
    this.queuedImmediateFlush.clear();
  }

  /** 返回当前任务快照，供 workspace flush fence 等待，不暴露可变 Map。 */
  public pendingTasks(): readonly Promise<void>[] {
    return [...this.tasks.values()];
  }

  /** 只读判断路径是否仍有 CAS 请求，用于生成稳定的关闭错误提示。 */
  public hasTask(path: string): boolean {
    return this.tasks.has(path);
  }

  /** 串行执行一次或一次补充保存；失败和冲突会立即终止，避免无界重试。 */
  private async runQueue(path: string): Promise<void> {
    do {
      this.queuedImmediateFlush.delete(path);
      const saved = await this.ports.saveOnce(path);
      if (!saved) {
        this.queuedImmediateFlush.delete(path);
        return;
      }
    } while (this.queuedImmediateFlush.has(path) && this.ports.shouldContinue(path));
  }

  /** 释放单路径 timer handle；幂等清理让 move、reload、close 可共享该步骤。 */
  private cancelTimer(path: string): void {
    const handle = this.timers.get(path);
    if (handle !== undefined) this.ports.timer.clear(handle);
    this.timers.delete(path);
  }
}
