// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactElement } from "react";
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/shared/ui/primitives";

export interface ProjectRebindConfirmation {
  readonly workspaceId: string;
  readonly documents: readonly { path: string; status: string }[];
}

/** 汇总所有 Thread 的草稿，但不复制正文；取消只撤回此次目录更新，不关闭编辑弹窗。 */
export function ProjectRebindDialog({
  confirmation,
  onChoose,
}: {
  readonly confirmation: ProjectRebindConfirmation | undefined;
  readonly onChoose: (choice: "save" | "discard" | "cancel") => void;
}): ReactElement {
  return (
    <Dialog
      open={confirmation !== undefined}
      onOpenChange={(open) => {
        if (!open) onChoose("cancel");
      }}
    >
      <DialogContent className="ja-project-rebind-dialog">
        <DialogTitle>处理未保存文件</DialogTitle>
        <DialogDescription>更改目录前，请处理这个项目各会话中的未保存文件。</DialogDescription>
        <ul className="ja-project-rebind-documents">
          {confirmation?.documents.map((document, index) => (
            <li key={`${document.path}:${index}`} title={document.path}>
              {document.path}
            </li>
          ))}
        </ul>
        <div className="ja-project-rebind-actions">
          <Button type="button" onClick={() => onChoose("cancel")}>
            取消
          </Button>
          <Button type="button" onClick={() => onChoose("discard")}>
            放弃修改
          </Button>
          <Button type="button" onClick={() => onChoose("save")}>
            保存并继续
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
