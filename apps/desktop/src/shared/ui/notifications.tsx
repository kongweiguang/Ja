// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useId, useRef, type ReactElement, type ReactNode } from "react";
import { X } from "lucide-react";
import { toast, Toaster } from "sonner";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";
import { IconButton } from "./primitives";
import "./notifications.css";

/** 全局与会话提示共用一个队列、位置和主题，排队及计时沿用 Sonner。 */
export function NotificationCenter(): ReactElement {
  const theme = useUiPreferencesStore((state) => state.themeMode);
  return (
    <Toaster
      className="ja-toaster"
      theme={theme}
      position="top-right"
      offset={{
        top: "calc(var(--ja-titlebar-height, 36px) + 3rem + 0.75rem + env(safe-area-inset-top, 0px))",
        right: "0.75rem",
        bottom: "0.75rem",
        left: "0.75rem",
      }}
      mobileOffset={{
        top: "calc(var(--ja-titlebar-height, 36px) + 3rem + 0.5rem + env(safe-area-inset-top, 0px))",
        right: "0.5rem",
        bottom: "0.5rem",
        left: "0.5rem",
      }}
      visibleToasts={Infinity}
      gap={8}
      expand
      closeButton
      icons={{ close: <X aria-hidden="true" /> }}
      toastOptions={{ closeButtonAriaLabel: "关闭提示" }}
      containerAriaLabel="提示"
    />
  );
}

interface NotificationProps {
  readonly scope: string;
  readonly message: string;
  readonly tone?: "info" | "warning" | "error" | "success";
  readonly dismissible?: boolean;
  readonly enabled?: boolean;
  readonly dismissLabel?: string;
  readonly onDismiss?: () => void;
  readonly children?: ReactNode;
}

/**
 * 受控提示只投影 owner 的状态，不另存业务事实；同作用域原位更新，停用或卸载立即撤回。
 * 关闭仅隐藏当前消息，父组件重渲染不能重新弹出；内容变化后才再次通知。
 * @author kongweiguang
 */
export function Notification({
  scope,
  message,
  tone = "info",
  dismissible = true,
  enabled = true,
  dismissLabel = "关闭提示",
  onDismiss,
  children,
}: NotificationProps): null {
  const instanceId = useId();
  const id = `ja-notice:${instanceId}:${scope}`;
  const identity = `${id}:${tone}:${message}`;
  const activeId = useRef<string | undefined>(undefined);
  const activeGeneration = useRef(0);
  const dismissedIdentity = useRef<string | undefined>(undefined);

  /** 清理先撤销回调权限，避免 Sonner 的退出动画迟到后修改已离开的会话。 */
  useEffect(() => {
    activeId.current = id;
    return () => {
      activeId.current = undefined;
      activeGeneration.current += 1;
      toast.dismiss(id);
    };
  }, [id]);

  /** 隐藏 owner 时沿用 toast ID 退出；快速恢复复用该 ID，旧动画回调不能碰新 owner。 @author kongweiguang */
  useEffect(() => {
    if (!enabled) {
      activeId.current = undefined;
      activeGeneration.current += 1;
      toast.dismiss(id);
      return;
    }
    const generation = ++activeGeneration.current;
    activeId.current = id;
    if (dismissedIdentity.current === identity) return;
    /** 用户关闭与程序清理共用 Sonner 退出流程，但只有活动提示可通知 owner。 */
    const dismiss = (): void => {
      if (
        activeId.current !== id ||
        activeGeneration.current !== generation ||
        dismissedIdentity.current === identity
      )
        return;
      dismissedIdentity.current = identity;
      onDismiss?.();
    };
    toast[tone](
      <div className="ja-notification-content" role={tone === "error" ? "alert" : "status"}>
        <div>{message}</div>
        {children === undefined ? null : <div className="ja-notification-actions">{children}</div>}
        {dismissible ? (
          <IconButton
            className="ja-notification-close"
            label={dismissLabel}
            onClick={() => {
              dismiss();
              toast.dismiss(id);
            }}
          >
            <X aria-hidden="true" />
          </IconButton>
        ) : null}
      </div>,
      { id, duration: Infinity, dismissible, closeButton: false, onDismiss: dismiss },
    );
  }, [children, dismissible, dismissLabel, enabled, id, identity, message, onDismiss, tone]);
  return null;
}
