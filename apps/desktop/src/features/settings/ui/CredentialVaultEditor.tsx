// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { KeyRound, ShieldCheck, Trash2, X } from "lucide-react";
import { useId, useRef, useState, type ReactElement } from "react";
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/shared/ui/primitives";
import { CREDENTIAL_REF_PATTERN } from "./shared";
import type { SettingsPorts } from "../application/ports";

interface CredentialVaultEditorProps {
  reference: string;
  configured?: boolean;
  onReplaceCredential: SettingsPorts["onReplaceCredential"];
  onClearCredential: SettingsPorts["onClearCredential"];
}

/** 只读取脱敏稳定 code 生成可操作反馈，不展示 native message 或底层存储诊断。 */
function credentialSaveFailureMessage(error: unknown): string {
  const code =
    error !== null && typeof error === "object" ? (error as { code?: unknown }).code : undefined;
  if (code === "revision_conflict") return "配置版本仍有冲突，请重新加载设置后再试。";
  if (code === "storage_unavailable") return "系统凭据库暂时不可用，请稍后重试。";
  if (code === "invalid_input") return "密钥或凭据引用无效，请检查后重试。";
  if (code === "invalid_response") return "凭据配置异常，请先恢复设置后再保存。";
  return "密钥保存失败，请检查系统凭据库后重试。";
}

/**
 * Secret 只停留在非受控密码输入框，并在每次尝试后清空；这样 Credential 可以进入原生 Vault，
 * 却不会成为 React 状态、持久化 Settings 文档或可复用反馈文本。
 */
export function CredentialVaultEditor({
  reference,
  configured,
  onReplaceCredential,
  onClearCredential,
}: CredentialVaultEditorProps): ReactElement {
  const secretRef = useRef<HTMLInputElement>(null);
  const instanceId = useId();
  const inputId = `${instanceId}-credential-secret`;
  const statusId = `${instanceId}-credential-status`;
  const deleteDescriptionId = `${instanceId}-credential-delete-description`;
  const [pending, setPending] = useState<"save" | "delete">();
  const [feedback, setFeedback] = useState<string>();
  const [deleteConfirmationOpen, setDeleteConfirmationOpen] = useState(false);
  const deleteOpenerRef = useRef<HTMLButtonElement | null>(null);
  const normalizedReference = reference.trim();
  const referenceValid = CREDENTIAL_REF_PATTERN.test(normalizedReference);

  /** 写入一次 Secret 后立即清空 DOM 字段，即使原生 Vault 拒绝也不让敏感值继续驻留。 */
  const saveCredential = async (): Promise<void> => {
    const secret = secretRef.current?.value ?? "";
    setFeedback(undefined);
    if (!referenceValid) {
      setFeedback("请先填写有效的 credential ref。");
      return;
    }
    if (secret.length === 0) {
      setFeedback("请输入要保存到系统凭据库的密钥。");
      return;
    }
    setPending("save");
    try {
      await onReplaceCredential(normalizedReference, secret);
      setFeedback("密钥已保存到系统凭据库；界面不会回显密钥内容。");
    } catch (error) {
      setFeedback(credentialSaveFailureMessage(error));
    } finally {
      if (secretRef.current !== null) secretRef.current.value = "";
      setPending(undefined);
    }
  };

  /** 删除唯一原生 Secret 可能让 Provider 失效；返回结果供确认框只在成功后关闭。 */
  const deleteCredential = async (): Promise<boolean> => {
    setFeedback(undefined);
    if (!referenceValid) {
      setFeedback("请先填写有效的 credential ref。");
      return false;
    }
    setPending("delete");
    try {
      await onClearCredential(normalizedReference);
      setFeedback("系统凭据已删除；credential ref 仍保留在配置中。");
      return true;
    } catch {
      setFeedback("删除失败，请确认该凭据存在并重试。");
      return false;
    } finally {
      setPending(undefined);
    }
  };

  /** 删除确认取消或完成后回到原入口，保证嵌套在 sheet 中的键盘路径连续。 */
  const requestDelete = (trigger: HTMLButtonElement): void => {
    setFeedback(undefined);
    deleteOpenerRef.current = trigger;
    if (referenceValid) setDeleteConfirmationOpen(true);
  };

  return (
    <section
      className="ja-settings-credential-vault"
      aria-label="系统凭据"
      data-setting-search="系统凭据库 credential secret API key token 密钥"
    >
      <div className="ja-settings-credential-heading">
        <span className="ja-settings-credential-icon">
          <ShieldCheck aria-hidden="true" />
        </span>
        <div>
          <strong>系统凭据库</strong>
          <p id={statusId}>
            {configured === undefined
              ? "密钥不会回显"
              : configured
                ? "已配置 · 密钥不会回显"
                : "未配置"}
          </p>
        </div>
      </div>
      <div className="ja-settings-credential-controls">
        <label className="ja-settings-field" htmlFor={inputId}>
          <span className="ja-settings-label">API key / token</span>
          <input
            ref={secretRef}
            id={inputId}
            className="ja-settings-input"
            type="password"
            autoComplete="new-password"
            placeholder="输入后保存，不会回显"
            aria-describedby={statusId}
            disabled={pending !== undefined}
          />
        </label>
        <div className="ja-settings-credential-actions">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            loading={pending === "save"}
            disabled={pending !== undefined}
            onClick={() => void saveCredential()}
          >
            <KeyRound aria-hidden="true" />
            保存或替换密钥
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={pending !== undefined || !referenceValid || configured === false}
            onClick={(event) => requestDelete(event.currentTarget)}
          >
            <Trash2 aria-hidden="true" />
            删除凭据
          </Button>
        </div>
      </div>
      {feedback === undefined || deleteConfirmationOpen ? null : (
        <p className="ja-settings-feedback" role="status">
          {feedback}
        </p>
      )}
      <Dialog
        modal
        open={deleteConfirmationOpen}
        onOpenChange={(open) => {
          if (!open && pending === "delete") return;
          setDeleteConfirmationOpen(open);
        }}
      >
        <DialogContent
          className="ja-settings-confirm-dialog ja-settings-credential-confirm-dialog"
          overlayClassName="ja-settings-dialog-overlay ja-settings-credential-dialog-overlay"
          aria-describedby={deleteDescriptionId}
          onCloseAutoFocus={(event) => {
            const opener = deleteOpenerRef.current;
            if (opener?.isConnected) {
              event.preventDefault();
              opener.focus();
            }
          }}
        >
          <div className="ja-settings-dialog-header">
            <div>
              <DialogTitle className="ja-settings-dialog-title">删除系统凭据</DialogTitle>
              <DialogDescription
                id={deleteDescriptionId}
                className="ja-settings-dialog-description"
              >
                确认删除 {normalizedReference} 对应的密钥？配置中的 credential ref 会保留。
              </DialogDescription>
            </div>
            <DialogClose asChild>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                aria-label="关闭删除确认"
                disabled={pending === "delete"}
              >
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </div>
          {feedback === undefined ? null : (
            <p className="ja-settings-error" role="alert">
              {feedback}
            </p>
          )}
          <div className="ja-settings-confirm-actions">
            <DialogClose asChild>
              <Button type="button" variant="secondary" size="sm" disabled={pending === "delete"}>
                取消
              </Button>
            </DialogClose>
            <Button
              type="button"
              variant="danger"
              size="sm"
              loading={pending === "delete"}
              disabled={pending !== undefined}
              onClick={async () => {
                if (await deleteCredential()) setDeleteConfirmationOpen(false);
              }}
            >
              确认删除
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </section>
  );
}
