// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as AlertDialog from "@radix-ui/react-alert-dialog";
import {
  ChevronDown,
  CircleAlert,
  Globe2,
  KeyRound,
  MoreHorizontal,
  Pencil,
  Play,
  Plus,
  Server,
  Terminal,
  Trash2,
  X,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactElement } from "react";
import { Controller, useForm } from "react-hook-form";
import { toast } from "sonner";
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  Menu,
  MenuContent,
  MenuItem,
  MenuTrigger,
} from "@/shared/ui/primitives";
import type { McpServerDraft, McpServerProjection } from "../domain/types";
import type { SettingsPorts } from "../application/ports";
import { CredentialVaultEditor } from "./CredentialVaultEditor";
import {
  Field,
  SectionHeader,
  settingsMutationErrorMessage,
  SettingsSelect,
  SwitchField,
  toMcpSavePayload,
  transportOptions,
  validateMcpDraft,
} from "./shared";
import "./mcp.css";

const EMPTY_MCP_DRAFT: McpServerDraft = {
  name: "",
  transport: "stdio",
  endpoint: "",
  argsText: "",
  envText: "",
  headersText: "",
  authKind: "none",
  authName: "",
  credentialRef: "",
  enabled: true,
};

/** 为凭据编辑器预留稳定引用；Secret 仍由原生凭据库持有，避免进入 React 状态。 */
function createMcpCredentialRef(): string {
  const suffix = globalThis.crypto?.randomUUID?.().replaceAll("-", "") ?? Date.now().toString(36);
  return `cred_mcp_${suffix}`;
}

/** 把 runtime 状态收敛为短中文状态，列表保持可扫描且不把 unknown 误报为已连接。 */
function mcpStatusLabel(status: McpServerProjection["status"]): string {
  return status === "connected"
    ? "已连接"
    : status === "disabled"
      ? "已停用"
      : status === "testing"
        ? "检查中"
        : status === "unknown"
          ? "未检查"
          : "连接错误";
}

/**
 * MCP Settings 只暴露 Rust Host 与 Ja App Server adapter 接受的字段；Health 与 Tools
 * 始终是 Sidecar 返回的只读投影，不能混入持久配置。
 */
export function McpSection({
  servers,
  snapshotRevision = 0,
  onSaveMcp,
  onDeleteMcp,
  onTestMcp,
  onReplaceCredential,
  onClearCredential,
}: {
  servers: McpServerProjection[];
  snapshotRevision?: number;
  onSaveMcp: SettingsPorts["onSaveMcp"];
  onDeleteMcp: SettingsPorts["onDeleteMcp"];
  onTestMcp: SettingsPorts["onTestMcp"];
  onCloseMcp: SettingsPorts["onCloseMcp"];
  onReplaceCredential: SettingsPorts["onReplaceCredential"];
  onClearCredential: SettingsPorts["onClearCredential"];
}): ReactElement {
  const [feedback, setFeedback] = useState<string>();
  const [pending, setPending] = useState<string>();
  // 默认先展示列表；创建服务必须由用户显式发起，避免进入页面就产生草稿状态。
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingServer, setEditingServer] = useState<McpServerProjection>();
  const [deleteTarget, setDeleteTarget] = useState<McpServerProjection>();
  const editorReturnFocusRef = useRef<HTMLElement | null>(null);
  const lastSnapshotRevision = useRef(snapshotRevision);
  const {
    register,
    control,
    handleSubmit,
    reset,
    setError,
    clearErrors,
    setValue,
    setFocus,
    watch,
    formState,
  } = useForm<McpServerDraft>({ defaultValues: EMPTY_MCP_DRAFT, mode: "onBlur" });
  const transport = watch("transport");
  const authKind = watch("authKind");
  const credentialRef = watch("credentialRef");
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const firstErrorField = Object.keys(formState.errors)[0] as
    | keyof McpServerDraft
    | "root"
    | undefined;
  const firstErrorIsAdvanced =
    firstErrorField !== undefined &&
    ["envText", "headersText", "authKind", "authName", "credentialRef"].includes(firstErrorField);

  /** 提交期间阻止 Escape、关闭按钮和取消打断保存，避免 UI 先退出而异步结果晚到。 */
  const handleEditorOpenChange = (open: boolean): void => {
    if (!open && formState.isSubmitting) return;
    setEditorOpen(open);
  };

  /** 认证选择后才创建引用；这样无认证草稿不会无意义地产生凭据身份。 */
  useEffect(() => {
    if (authKind === "none" || credentialRef.trim() !== "") return;
    setValue("credentialRef", createMcpCredentialRef(), { shouldDirty: true });
  }, [authKind, credentialRef, setValue]);

  /** 提交后若错误来自折叠区，先展开再聚焦，避免把修复路径藏在不可见字段里。 */
  useEffect(() => {
    if (firstErrorField === undefined || firstErrorField === "root") return;
    if (firstErrorIsAdvanced && !advancedOpen) {
      setAdvancedOpen(true);
      return;
    }
    setFocus(firstErrorField);
  }, [advancedOpen, firstErrorField, firstErrorIsAdvanced, setFocus]);

  /** 新建和编辑共用一个受控草稿；投影健康字段绝不进入持久化表单。 */
  const openEditor = (server?: McpServerProjection): void => {
    setEditingServer(server);
    setAdvancedOpen(
      server !== undefined &&
        (Object.keys(server.env).length > 0 ||
          Object.keys(server.headers).length > 0 ||
          server.auth.kind !== "none"),
    );
    reset(
      server === undefined
        ? EMPTY_MCP_DRAFT
        : {
            mcpRevision: server.mcpRevision,
            name: server.name,
            transport: server.transport,
            endpoint: server.endpoint,
            argsText: server.args.join("\n"),
            envText: Object.entries(server.env)
              .map(([key, value]) => `${key}=${value}`)
              .join("\n"),
            headersText: Object.entries(server.headers)
              .map(([key, value]) => `${key}=${value}`)
              .join("\n"),
            authKind: server.auth.kind,
            authName: "name" in server.auth ? server.auth.name : "",
            credentialRef: "credentialRef" in server.auth ? server.auth.credentialRef : "",
            enabled: server.enabled,
          },
    );
    setFeedback(undefined);
    setEditorOpen(true);
  };

  useEffect(() => {
    if (snapshotRevision === lastSnapshotRevision.current) return;
    lastSnapshotRevision.current = snapshotRevision;
    if (formState.isDirty) {
      setFeedback("sidecar 有新的 MCP 设置版本，当前草稿已保留，请保存前检查冲突。 ");
      return;
    }
    reset(EMPTY_MCP_DRAFT);
    setFeedback(undefined);
  }, [formState.isDirty, reset, snapshotRevision]);

  /** 读取单个字段错误而不让表单耦合自定义校验层。 */
  const fieldError = (name: keyof McpServerDraft): string | undefined =>
    formState.errors[name]?.message;

  /** 只有共享 Zod 边界接受后才保存规范 MCP DTO，避免无效投影进入 Host。 */
  const save = async (values: McpServerDraft): Promise<void> => {
    setFeedback(undefined);
    clearErrors();
    if (!validateMcpDraft(values, setError)) return;
    const payload = toMcpSavePayload(values);
    try {
      await onSaveMcp(payload);
      reset(EMPTY_MCP_DRAFT);
      setEditingServer(undefined);
      setEditorOpen(false);
      toast.success("MCP 服务配置已保存");
    } catch (error) {
      const message = settingsMutationErrorMessage(
        error,
        "MCP 服务保存失败，请检查 sidecar 状态。",
      );
      setFeedback(message);
      toast.error(message);
    }
  };

  /** 启停只更新同一 MCP 定义，停用项因此可原位重新启用且不丢失连接信息。 */
  const toggleServer = async (server: McpServerProjection, enabled: boolean): Promise<void> => {
    // 启停改变连接语义，必须同时清除上一次测试反馈，避免停用后仍宣称已连接。
    setFeedback(undefined);
    setPending(server.id);
    try {
      await onSaveMcp({
        mcpRevision: server.mcpRevision,
        name: server.name,
        transport: server.transport,
        endpoint: server.endpoint,
        args: [...server.args],
        env: { ...server.env },
        headers: { ...server.headers },
        auth: { ...server.auth },
        enabled,
      });
    } catch (error) {
      toast.error(
        settingsMutationErrorMessage(error, `${server.name}${enabled ? "启用" : "停用"}失败`),
      );
    } finally {
      setPending(undefined);
    }
  };

  /** 删除只处理 MCP 配置，相关 Credential 必须由用户另行执行“清除凭据”。 */
  const removeServer = async (): Promise<void> => {
    if (deleteTarget === undefined) return;
    setPending(deleteTarget.id);
    try {
      await onDeleteMcp(deleteTarget.id);
      toast.success(`${deleteTarget.name} 已删除`);
      setDeleteTarget(undefined);
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, `${deleteTarget.name} 删除失败`));
    } finally {
      setPending(undefined);
    }
  };

  /** 只能通过 Host 回调执行测试；未连接界面不得自行宣称健康状态。 */
  const testServer = async (server: McpServerProjection): Promise<void> => {
    setPending(server.id);
    setFeedback(undefined);
    try {
      const status = await onTestMcp(server.id);
      setFeedback(
        status === "connected"
          ? `${server.name} 已连接。`
          : `${server.name}：${mcpStatusLabel(status)}。`,
      );
      if (status === "connected") toast.success(`${server.name} 已连接`);
      else toast.error(`${server.name}：${mcpStatusLabel(status)}`);
    } catch {
      setFeedback(`${server.name} 测试失败。`);
      toast.error(`${server.name} 测试失败`);
    } finally {
      setPending(undefined);
    }
  };

  return (
    <div className="ja-settings-section ja-mcp-section">
      <SectionHeader
        title="MCP 工具"
        description="连接外部工具，并按需启用。"
        action={
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={(event) => {
              editorReturnFocusRef.current = event.currentTarget;
              openEditor();
            }}
            disabled={pending !== undefined}
          >
            <Plus size={14} aria-hidden="true" />
            新增服务
          </Button>
        }
      />
      <div className="ja-mcp-list" aria-label="MCP 服务列表">
        {servers.length === 0 ? (
          <div className="ja-mcp-empty">
            <span className="ja-mcp-empty-icon" aria-hidden="true">
              <Server size={19} />
            </span>
            <div>
              <strong>还没有 MCP 服务</strong>
              <p>添加一个本地进程或 HTTP 服务，让 Ja 可以按需使用它的工具。</p>
            </div>
          </div>
        ) : (
          servers.map((server) => (
            <article
              className={`ja-mcp-row ${server.enabled ? "is-enabled" : "is-disabled"}`}
              data-setting-id={`mcp-${server.id}`}
              data-setting-search={`${server.name} ${server.endpoint} mcp server ${server.transport}`}
              key={server.id}
            >
              <div className="ja-mcp-row-main">
                <span className="ja-mcp-row-icon" aria-hidden="true">
                  {server.transport === "stdio" ? <Terminal size={17} /> : <Globe2 size={17} />}
                </span>
                <div className="ja-mcp-row-copy">
                  <div className="ja-mcp-row-title">
                    <h3>{server.name}</h3>
                    <span className={`ja-mcp-status is-${server.status}`}>
                      <span className="ja-mcp-status-dot" aria-hidden="true" />
                      {mcpStatusLabel(server.status)}
                    </span>
                  </div>
                  <div className="ja-mcp-row-details">
                    <span>{server.transport === "stdio" ? "本地进程" : "Streamable HTTP"}</span>
                    <code title={server.endpoint}>{server.endpoint}</code>
                    <span>{server.tools.length} 个工具</span>
                  </div>
                </div>
                <div className="ja-mcp-row-toggle">
                  <SwitchField
                    id={`mcp-enabled-${server.id}`}
                    label={server.name + "：" + (server.enabled ? "已启用" : "已停用")}
                    hideLabel
                    checked={server.enabled}
                    disabled={pending !== undefined}
                    onCheckedChange={(enabled) => void toggleServer(server, enabled)}
                  />
                </div>
              </div>
              {server.lastError === undefined ? null : (
                <p className="ja-settings-error ja-mcp-error" role="alert">
                  <CircleAlert size={14} aria-hidden="true" />
                  {server.lastError}
                </p>
              )}
              {server.tools.length === 0 ? null : (
                <div className="ja-mcp-tools">
                  {server.tools.map((tool) => (
                    <span className="ja-mcp-chip" key={tool.name}>
                      {tool.name} · {tool.policy}
                    </span>
                  ))}
                </div>
              )}
              <div className="ja-mcp-row-actions">
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => void testServer(server)}
                  disabled={pending !== undefined || !server.enabled}
                >
                  <Play size={14} aria-hidden="true" />
                  测试
                </Button>
                <Menu>
                  <MenuTrigger asChild>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      aria-label={`${server.name} 更多操作`}
                      disabled={pending !== undefined}
                      onPointerDown={(event) => {
                        editorReturnFocusRef.current = event.currentTarget;
                      }}
                    >
                      <MoreHorizontal size={16} aria-hidden="true" />
                    </Button>
                  </MenuTrigger>
                  <MenuContent align="end">
                    <MenuItem onSelect={() => openEditor(server)} disabled={pending !== undefined}>
                      <Pencil size={14} aria-hidden="true" />
                      编辑
                    </MenuItem>
                    <MenuItem
                      className="is-danger"
                      onSelect={() => setDeleteTarget(server)}
                      disabled={pending !== undefined}
                    >
                      <Trash2 size={14} aria-hidden="true" />
                      删除
                    </MenuItem>
                  </MenuContent>
                </Menu>
              </div>
            </article>
          ))
        )}
      </div>
      {feedback === undefined ? null : (
        <p className="ja-settings-feedback" role="status">
          {feedback}
        </p>
      )}
      <Dialog modal open={editorOpen} onOpenChange={handleEditorOpenChange}>
        <DialogContent
          className="ja-settings-dialog ja-mcp-dialog"
          overlayClassName="ja-settings-dialog-overlay"
          aria-describedby="ja-mcp-dialog-description"
          onInteractOutside={(event) => event.preventDefault()}
          onOpenAutoFocus={(event) => {
            // 编辑器首焦点落在名称，键盘用户可以从基本信息开始，而不是先落到关闭按钮。
            event.preventDefault();
            setFocus("name");
          }}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            editorReturnFocusRef.current?.focus();
          }}
        >
          <div className="ja-settings-dialog-header ja-mcp-dialog-header">
            <div>
              <DialogTitle className="ja-settings-dialog-title ja-mcp-dialog-title">
                {editingServer === undefined ? "新增 MCP 服务" : `编辑 ${editingServer.name}`}
              </DialogTitle>
              <DialogDescription
                id="ja-mcp-dialog-description"
                className="ja-settings-dialog-description ja-mcp-dialog-description"
              >
                连接后，Ja 可在任务中使用此服务提供的工具。
              </DialogDescription>
            </div>
            <DialogClose asChild>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                aria-label="关闭 MCP 编辑"
                disabled={formState.isSubmitting}
              >
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </div>
          <form
            className="ja-settings-mcp-form ja-mcp-form"
            onSubmit={(event) => void handleSubmit(save)(event)}
            noValidate
          >
            <div className="ja-mcp-form-body">
              <div className="ja-mcp-basic-fields">
                <Field id="mcp-name" label="名称" error={fieldError("name")}>
                  <input
                    id="mcp-name"
                    className="ja-settings-input"
                    aria-invalid={fieldError("name") !== undefined}
                    {...register("name")}
                    placeholder="例如：本地文件工具"
                    autoComplete="off"
                  />
                </Field>
                <Controller
                  control={control}
                  name="transport"
                  render={({ field, fieldState }) => (
                    <Field id="mcp-transport" label="连接方式" error={fieldState.error?.message}>
                      <div className="ja-mcp-transport" role="radiogroup" aria-label="连接方式">
                        {transportOptions.map((option) => (
                          <button
                            type="button"
                            className={`ja-mcp-transport-option ${field.value === option.value ? "is-selected" : ""}`}
                            role="radio"
                            aria-checked={field.value === option.value}
                            tabIndex={field.value === option.value ? 0 : -1}
                            key={option.value}
                            onKeyDown={(event) => {
                              if (
                                ![
                                  "ArrowLeft",
                                  "ArrowRight",
                                  "ArrowUp",
                                  "ArrowDown",
                                  "Home",
                                  "End",
                                ].includes(event.key)
                              )
                                return;
                              event.preventDefault();
                              const currentIndex = transportOptions.findIndex(
                                (item) => item.value === field.value,
                              );
                              const nextIndex =
                                event.key === "Home"
                                  ? 0
                                  : event.key === "End"
                                    ? transportOptions.length - 1
                                    : (currentIndex +
                                        (["ArrowLeft", "ArrowUp"].includes(event.key) ? -1 : 1) +
                                        transportOptions.length) %
                                      transportOptions.length;
                              const next =
                                event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>(
                                  '[role="radio"]',
                                )[nextIndex];
                              next?.focus();
                              next?.click();
                            }}
                            onClick={() => {
                              field.onChange(option.value);
                              setValue("authKind", "none", { shouldDirty: true });
                              setValue("authName", "", { shouldDirty: true });
                              setValue("credentialRef", "", { shouldDirty: true });
                              setAdvancedOpen(false);
                            }}
                          >
                            {option.value === "stdio" ? (
                              <Terminal size={15} aria-hidden="true" />
                            ) : (
                              <Globe2 size={15} aria-hidden="true" />
                            )}
                            <span>
                              {option.value === "stdio" ? "本地 STDIO" : "Streamable HTTP"}
                            </span>
                          </button>
                        ))}
                      </div>
                    </Field>
                  )}
                />
                <Field
                  id="mcp-endpoint"
                  label={transport === "stdio" ? "启动命令" : "服务地址"}
                  hint={
                    transport === "stdio"
                      ? "输入可执行文件名或路径，例如 npx、node、pwsh.exe。"
                      : "使用 HTTP/HTTPS 地址，例如 https://example.com/mcp。"
                  }
                  error={fieldError("endpoint")}
                >
                  <input
                    id="mcp-endpoint"
                    className="ja-settings-input"
                    aria-invalid={fieldError("endpoint") !== undefined}
                    {...register("endpoint")}
                    placeholder={
                      transport === "stdio" ? "例如：npx" : "例如：https://mcp.example.com/mcp"
                    }
                    autoComplete="off"
                  />
                </Field>
                {transport === "stdio" ? (
                  <Field
                    id="mcp-args"
                    label="进程参数"
                    hint="每行一个参数，例如 -y 和 npm 包名。"
                    error={fieldError("argsText")}
                  >
                    <textarea
                      id="mcp-args"
                      className="ja-settings-input ja-settings-textarea"
                      placeholder={"例如：-y\n@modelcontextprotocol/server-filesystem"}
                      {...register("argsText")}
                    />
                  </Field>
                ) : null}
              </div>
              <button
                type="button"
                className="ja-mcp-advanced-toggle"
                aria-expanded={advancedOpen}
                aria-controls="mcp-advanced-fields"
                onClick={() => setAdvancedOpen((open) => !open)}
              >
                <span>
                  <KeyRound size={15} aria-hidden="true" />
                  高级设置
                </span>
                <ChevronDown size={16} aria-hidden="true" />
              </button>
              {advancedOpen ? (
                <div className="ja-mcp-advanced-fields" id="mcp-advanced-fields">
                  {transport === "stdio" ? (
                    <Field
                      id="mcp-env"
                      label="环境变量"
                      hint="每行 KEY=VALUE；敏感值请放入凭据库。"
                      error={fieldError("envText")}
                    >
                      <textarea
                        id="mcp-env"
                        className="ja-settings-input ja-settings-textarea"
                        placeholder="例如：ROOT_DIR=C:\\workspace"
                        {...register("envText")}
                      />
                    </Field>
                  ) : (
                    <Field
                      id="mcp-headers"
                      label="请求 Headers"
                      hint="每行 Header=Value；Authorization 请使用认证设置。"
                      error={fieldError("headersText")}
                    >
                      <textarea
                        id="mcp-headers"
                        className="ja-settings-input ja-settings-textarea"
                        placeholder="例如：X-Workspace=demo"
                        {...register("headersText")}
                      />
                    </Field>
                  )}
                  <Controller
                    control={control}
                    name="authKind"
                    render={({ field, fieldState }) => (
                      <Field
                        id="mcp-auth-kind"
                        label="认证方式"
                        hint="密钥只会保存到系统凭据库，不会显示在配置中。"
                        error={fieldState.error?.message}
                      >
                        <SettingsSelect
                          id="mcp-auth-kind"
                          value={field.value}
                          options={[
                            { value: "none", label: "无需认证" },
                            ...(transport === "stdio"
                              ? [{ value: "env", label: "环境变量凭据" }]
                              : []),
                            ...(transport === "streamable_http"
                              ? [
                                  { value: "bearer", label: "Bearer Token" },
                                  { value: "header", label: "自定义 Header" },
                                ]
                              : []),
                          ]}
                          onValueChange={field.onChange}
                          ariaDescribedBy={
                            fieldState.error === undefined ? undefined : "mcp-auth-kind-error"
                          }
                          ariaInvalid={fieldState.invalid}
                        />
                      </Field>
                    )}
                  />
                  {authKind === "header" || authKind === "env" ? (
                    <Field
                      id="mcp-auth-name"
                      label={authKind === "env" ? "环境变量名" : "Header 名称"}
                      error={fieldError("authName")}
                    >
                      <input
                        id="mcp-auth-name"
                        className="ja-settings-input"
                        placeholder={authKind === "env" ? "例如：MCP_TOKEN" : "例如：X-API-Key"}
                        {...register("authName")}
                        autoComplete="off"
                      />
                    </Field>
                  ) : null}
                  {authKind === "none" ? null : (
                    <>
                      {fieldError("credentialRef") ? (
                        <p className="ja-settings-error" role="alert">
                          <CircleAlert size={14} aria-hidden="true" />
                          {fieldError("credentialRef")}
                        </p>
                      ) : null}
                      <CredentialVaultEditor
                        reference={credentialRef}
                        configured={undefined}
                        onReplaceCredential={onReplaceCredential}
                        onClearCredential={onClearCredential}
                      />
                    </>
                  )}
                </div>
              ) : null}
              {formState.errors.root?.message === undefined ? null : (
                <p className="ja-settings-error" role="alert">
                  <CircleAlert size={14} aria-hidden="true" />
                  {formState.errors.root.message}
                </p>
              )}
            </div>
            <div className="ja-settings-form-actions ja-mcp-form-actions">
              {feedback === undefined ? null : (
                <p className="ja-settings-feedback" role="status">
                  {feedback}
                </p>
              )}
              <DialogClose asChild>
                <Button type="button" variant="ghost" disabled={formState.isSubmitting}>
                  取消
                </Button>
              </DialogClose>
              <Button
                type="submit"
                variant="primary"
                disabled={formState.isSubmitting}
                loading={formState.isSubmitting}
              >
                <Plus size={14} aria-hidden="true" />
                保存服务
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
      <AlertDialog.Root
        open={deleteTarget !== undefined}
        onOpenChange={(open) => !open && setDeleteTarget(undefined)}
      >
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="ja-settings-dialog-overlay" />
          <AlertDialog.Content className="ja-settings-confirm-dialog">
            <AlertDialog.Title>删除 MCP 服务？</AlertDialog.Title>
            <AlertDialog.Description>
              将删除 {deleteTarget?.name} 的配置，但不会清除其凭据。
            </AlertDialog.Description>
            <div className="ja-settings-form-actions">
              <AlertDialog.Cancel asChild>
                <Button variant="ghost" disabled={pending !== undefined}>
                  取消
                </Button>
              </AlertDialog.Cancel>
              <Button
                type="button"
                variant="danger"
                loading={pending !== undefined}
                disabled={pending !== undefined}
                onClick={() => void removeServer()}
              >
                删除
              </Button>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
    </div>
  );
}
