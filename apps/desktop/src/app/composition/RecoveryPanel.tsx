// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useState, type ReactElement } from "react";
import { Button } from "@/shared/ui/primitives/Button";
import { useRuntimeLifecycle, useRuntimeState } from "../RuntimeProvider";

/**
 * Recovery 是 runtime lifecycle 中唯一需要人工确认的破坏性决策，因此保留独立视图；
 * 用户确认前绝不清除 gate，失败只显示稳定错误而不泄漏 native 诊断。
 */
export function RecoveryPanel(): ReactElement {
  const { recovery } = useRuntimeState();
  const { acknowledgeRecovery } = useRuntimeLifecycle();
  const [pending, setPending] = useState<string>();
  const [error, setError] = useState<string>();

  /** 宿主只有取得人工二次确认后才能清除恢复 gate，取消属于正常无副作用路径。 */
  const acknowledge = async (reason: "SystemRestarted" | "ExternallyCleaned"): Promise<void> => {
    const label = reason === "SystemRestarted" ? "系统已重启" : "外部进程已清理";
    if (!window.confirm(`确认“${label}”？Ja 将清除当前恢复门禁。`)) return;
    setPending(reason);
    setError(undefined);
    try {
      await acknowledgeRecovery(reason);
    } catch {
      setError("恢复确认失败，请重新读取状态后重试。");
    } finally {
      setPending(undefined);
    }
  };

  return (
    <section className="ja-recovery-card" role="alert" aria-labelledby="ja-recovery-title">
      <p className="ja-kicker">需要确认</p>
      <h2 id="ja-recovery-title">运行时需要人工恢复</h2>
      <p>上一次关闭尚未确认 Ja App Server 已清理。完成确认前不会启动新的进程。</p>
      <div className="ja-recovery-actions">
        <Button
          type="button"
          variant="secondary"
          disabled={pending !== undefined || recovery?.acknowledgeable !== true}
          onClick={() => void acknowledge("SystemRestarted")}
        >
          {pending === "SystemRestarted" ? "确认中…" : "系统已重启"}
        </Button>
        <Button
          type="button"
          variant="ghost"
          disabled={pending !== undefined || recovery?.acknowledgeable !== true}
          onClick={() => void acknowledge("ExternallyCleaned")}
        >
          {pending === "ExternallyCleaned" ? "确认中…" : "外部进程已清理"}
        </Button>
      </div>
      {error === undefined ? null : <p className="ja-inline-error">{error}</p>}
    </section>
  );
}
