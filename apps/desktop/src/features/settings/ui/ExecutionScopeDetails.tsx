// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Info } from "lucide-react";
import type { ReactElement } from "react";
import { describeExecutionScope, type ExecutionScope } from "../domain/executionScope";

/**
 * 只读呈现执行确认的来源与生效范围；说明完全由父级快照驱动，保存后随新快照
 * 重渲染，不保留第二份本地权限状态。
 */
export function ExecutionScopeDetails({ scope }: { scope: ExecutionScope }): ReactElement {
  const lines = describeExecutionScope(scope);

  return (
    <aside
      className="ja-settings-callout ja-settings-callout-neutral"
      data-setting-id="permission-scope"
      data-setting-search="执行确认 生效范围 来源 全局默认 项目限制 会话选择"
      role="note"
      aria-label="执行确认生效范围"
      tabIndex={-1}
    >
      <Info size={16} aria-hidden="true" />
      <span>
        {lines.map((line, index) => (
          <span key={line}>
            {index === 0 ? null : <br />}
            {line}
          </span>
        ))}
      </span>
    </aside>
  );
}
