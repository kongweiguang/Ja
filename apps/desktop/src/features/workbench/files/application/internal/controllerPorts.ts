// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 协作者只通过 current 读取主 hook 持有的事实，不能替代 React 成为状态 owner。 */
export interface ControllerRef<T> {
  current: T;
}

/** 状态写端口保留 React functional update 语义，但内部模块不依赖 React。 */
export type StateWriter<T> = (value: T | ((current: T) => T)) => void;

/**
 * 保存 coordinator 通过此可替换回调端口访问最新 controller 用例；端口只保存函数，
 * 不保存草稿、revision 或队列事实，因此不会形成第二状态 owner。
 */
export class DocumentSaveRuntimePort {
  private saveHandler: (path: string) => Promise<boolean> = async () => true;
  private continueHandler: (path: string) => boolean = () => false;

  /** commit 后一次性替换两条回调，避免 coordinator 在 render 阶段读取 React refs。 */
  update(
    saveHandler: (path: string) => Promise<boolean>,
    continueHandler: (path: string) => boolean,
  ): void {
    this.saveHandler = saveHandler;
    this.continueHandler = continueHandler;
  }

  /** 保存请求只转发给最新 handler，不缓存路径或结果。 */
  saveOnce(path: string): Promise<boolean> {
    return this.saveHandler(path);
  }

  /** 是否继续队列由主 hook 最新文档投影判断，本端口不复制状态。 */
  shouldContinue(path: string): boolean {
    return this.continueHandler(path);
  }
}
