// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as AlertDialog from "@radix-ui/react-alert-dialog";
import { CircleAlert, MoreHorizontal, Pencil, Play, Plus, Trash2, X } from "lucide-react";
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
  onCloseMcp,
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
  // 默认先展示列表；创建 Server 必须由用户显式发起，避免进入页面就产生草稿状态。
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingServer, setEditingServer] = useState<McpServerProjection>();
  const [deleteTarget, setDeleteTarget] = useState<McpServerProjection>();
  const lastSnapshotRevision = useRef(snapshotRevision);
  const {
    register,
    control,
    handleSubmit,
    reset,
    setError,
    clearErrors,
    setValue,
    watch,
    formState,
  } = useForm<McpServerDraft>({ defaultValues: EMPTY_MCP_DRAFT, mode: "onBlur" });
  const transport = watch("transport");

  /** 新建和编辑共用一个受控草稿；投影健康字段绝不进入持久化表单。 */
  const openEditor = (server?: McpServerProjection): void => {
    setEditingServer(server);
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
      toast.success("MCP Server 配置已保存");
    } catch (error) {
      const message = settingsMutationErrorMessage(
        error,
        "MCP Server 保存失败，请检查 sidecar 状态。",
      );
      setFeedback(message);
      toast.error(message);
    }
  };

  /** 启停只更新同一 MCP 定义，停用项因此可原位重新启用且不丢失连接信息。 */
  const toggleServer = async (server: McpServerProjection, enabled: boolean): Promise<void> => {
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
        status === "connected" ? `${server.name} 已连接。` : `${server.name} 状态：${status}。`,
      );
      if (status === "connected") toast.success(`${server.name} 已连接`);
      else toast.error(`${server.name} 状态：${status}`);
    } catch {
      setFeedback(`${server.name} 测试失败。`);
      toast.error(`${server.name} 测试失败`);
    } finally {
      setPending(undefined);
    }
  };

  /** 关闭动作把进程清理委托给 Ja App Server 的 MCP Runtime，前端不持有进程生命周期。 */
  const close = async (server: McpServerProjection): Promise<void> => {
    setPending(server.id);
    setFeedback(undefined);
    try {
      await onCloseMcp(server.id);
    } catch {
      setFeedback(`${server.name} 关闭失败。`);
    } finally {
      setPending(undefined);
    }
  };

  return (
    <div className="ja-settings-section">
      <SectionHeader
        title="MCP 工具"
        action={
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => {
              openEditor();
            }}
          >
            <Plus size={14} aria-hidden="true" />
            新增 Server
          </Button>
        }
      />
      <div className="ja-settings-mcp-list">
        {servers.length === 0 ? (
          <p className="ja-settings-empty">还没有 MCP Server。</p>
        ) : (
          servers.map((server) => (
            <article
              className="ja-settings-mcp-card"
              data-setting-id={`mcp-${server.id}`}
              data-setting-search={`${server.name} ${server.endpoint} mcp server ${server.transport}`}
              key={server.id}
            >
              <div className="ja-settings-mcp-heading">
                <div>
                  <h3>{server.name}</h3>
                  <p>
                    <span className={`ja-settings-status-text is-${server.status}`}>
                      {server.status === "connected"
                        ? "已连接"
                        : server.status === "disabled"
                          ? "已停用"
                          : server.status === "testing"
                            ? "检查中"
                            : server.status === "unknown"
                              ? "未检查"
                              : "错误"}
                    </span>{" "}
                    · {server.transport === "stdio" ? "stdio" : "Streamable HTTP"}
                  </p>
                </div>
                <span className="ja-settings-tool-count">{server.tools.length} 个工具</span>
              </div>
              <code className="ja-settings-endpoint">{server.endpoint}</code>
              <dl className="ja-settings-facts">
                <div>
                  <dt>认证</dt>
                  <dd>{server.auth.kind === "none" ? "无认证" : server.auth.kind}</dd>
                </div>
                <div>
                  <dt>启用</dt>
                  <dd>{server.enabled ? "是" : "否"}</dd>
                </div>
              </dl>
              {server.lastError === undefined ? null : (
                <p className="ja-settings-error" role="alert">
                  <CircleAlert size={14} aria-hidden="true" />
                  {server.lastError}
                </p>
              )}
              <div className="ja-settings-mcp-tools">
                {server.tools.map((tool) => (
                  <span className="ja-settings-chip" key={tool.name}>
                    {tool.name} · {tool.policy}
                  </span>
                ))}
              </div>
              <div className="ja-settings-card-actions">
                <SwitchField
                  id={`mcp-enabled-${server.id}`}
                  label={server.enabled ? "已启用" : "已停用"}
                  checked={server.enabled}
                  disabled={pending === server.id}
                  onCheckedChange={(enabled) => void toggleServer(server, enabled)}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => void testServer(server)}
                  disabled={pending === server.id}
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
                    >
                      <MoreHorizontal size={16} aria-hidden="true" />
                    </Button>
                  </MenuTrigger>
                  <MenuContent align="end">
                    <MenuItem onSelect={() => openEditor(server)}>
                      <Pencil size={14} aria-hidden="true" />
                      编辑
                    </MenuItem>
                    <MenuItem onSelect={() => void close(server)} disabled={pending === server.id}>
                      <X size={14} aria-hidden="true" />
                      关闭连接
                    </MenuItem>
                    <MenuItem className="is-danger" onSelect={() => setDeleteTarget(server)}>
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
      <Dialog modal open={editorOpen} onOpenChange={setEditorOpen}>
        <DialogContent
          className="ja-settings-dialog"
          overlayClassName="ja-settings-dialog-overlay"
          aria-describedby="ja-mcp-dialog-description"
          onInteractOutside={(event) => event.preventDefault()}
        >
          <div className="ja-settings-dialog-header">
            <div>
              <DialogTitle className="ja-settings-dialog-title">
                {editingServer === undefined ? "新增 MCP Server" : `编辑 ${editingServer.name}`}
              </DialogTitle>
              <DialogDescription
                id="ja-mcp-dialog-description"
                className="ja-settings-dialog-description"
              >
                只保存标准配置，不自动执行工具；令牌通过系统凭据库管理。
              </DialogDescription>
            </div>
            <DialogClose asChild>
              <Button type="button" variant="ghost" size="sm" aria-label="关闭 MCP 编辑">
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </div>
          <form
            className="ja-settings-mcp-form"
            onSubmit={(event) => void handleSubmit(save)(event)}
            noValidate
          >
            <div className="ja-settings-form-grid">
              <Field id="mcp-name" label="Server 名称" error={fieldError("name")}>
                <input
                  id="mcp-name"
                  className="ja-settings-input"
                  aria-invalid={fieldError("name") !== undefined}
                  {...register("name")}
                  autoComplete="off"
                />
              </Field>
              <Controller
                control={control}
                name="transport"
                render={({ field, fieldState }) => (
                  <Field id="mcp-transport" label="Transport" error={fieldState.error?.message}>
                    <SettingsSelect
                      id="mcp-transport"
                      value={field.value}
                      options={transportOptions}
                      onValueChange={(value) => {
                        field.onChange(value);
                        setValue("authKind", "none", { shouldDirty: true });
                        setValue("authName", "", { shouldDirty: true });
                        setValue("credentialRef", "", { shouldDirty: true });
                      }}
                      ariaDescribedBy={
                        fieldState.error === undefined ? undefined : "mcp-transport-error"
                      }
                      ariaInvalid={fieldState.invalid}
                    />
                  </Field>
                )}
              />
              <Field
                id="mcp-endpoint"
                label={transport === "stdio" ? "Executable" : "URL"}
                hint={
                  transport === "stdio"
                    ? "不能包含 token、password 或 authorization 明文。"
                    : "必须是无 userinfo、query、fragment 的 HTTP/HTTPS URL。"
                }
                error={fieldError("endpoint")}
              >
                <input
                  id="mcp-endpoint"
                  className="ja-settings-input"
                  aria-invalid={fieldError("endpoint") !== undefined}
                  {...register("endpoint")}
                  autoComplete="off"
                />
              </Field>
              {transport === "stdio" ? (
                <>
                  <Field
                    id="mcp-args"
                    label="参数"
                    hint="每行一个参数。"
                    error={fieldError("argsText")}
                  >
                    <textarea
                      id="mcp-args"
                      className="ja-settings-input ja-settings-textarea"
                      {...register("argsText")}
                    />
                  </Field>
                  <Field
                    id="mcp-env"
                    label="环境变量"
                    hint="每行 KEY=VALUE；敏感值请使用凭据引用。"
                    error={fieldError("envText")}
                  >
                    <textarea
                      id="mcp-env"
                      className="ja-settings-input ja-settings-textarea"
                      {...register("envText")}
                    />
                  </Field>
                </>
              ) : (
                <Field
                  id="mcp-headers"
                  label="Headers"
                  hint="每行 Header=Value；Authorization 请使用认证设置。"
                  error={fieldError("headersText")}
                >
                  <textarea
                    id="mcp-headers"
                    className="ja-settings-input ja-settings-textarea"
                    {...register("headersText")}
                  />
                </Field>
              )}
              <Controller
                control={control}
                name="authKind"
                render={({ field, fieldState }) => (
                  <Field id="mcp-auth-kind" label="认证" error={fieldState.error?.message}>
                    <SettingsSelect
                      id="mcp-auth-kind"
                      value={field.value}
                      options={
                        transport === "stdio"
                          ? [
                              { value: "none", label: "无" },
                              { value: "env", label: "环境变量凭据" },
                            ]
                          : [
                              { value: "none", label: "无" },
                              { value: "bearer", label: "Bearer" },
                              { value: "header", label: "自定义 Header" },
                            ]
                      }
                      onValueChange={field.onChange}
                    />
                  </Field>
                )}
              />
              {["header", "env"].includes(watch("authKind")) ? (
                <Field
                  id="mcp-auth-name"
                  label={watch("authKind") === "env" ? "环境变量名" : "Header 名"}
                  error={fieldError("authName")}
                >
                  <input
                    id="mcp-auth-name"
                    className="ja-settings-input"
                    {...register("authName")}
                    autoComplete="off"
                  />
                </Field>
              ) : null}
              {watch("authKind") === "none" ? null : (
                <Field
                  id="mcp-credential-ref"
                  label="Credential ref"
                  hint="必须匹配 cred_...。"
                  error={fieldError("credentialRef")}
                >
                  <input
                    id="mcp-credential-ref"
                    className="ja-settings-input"
                    aria-invalid={fieldError("credentialRef") !== undefined}
                    {...register("credentialRef")}
                    autoComplete="off"
                    spellCheck="false"
                  />
                </Field>
              )}
            </div>
            {watch("authKind") === "none" ? null : (
              <CredentialVaultEditor
                reference={watch("credentialRef")}
                configured={false}
                onReplaceCredential={onReplaceCredential}
                onClearCredential={onClearCredential}
              />
            )}
            {formState.errors.root?.message === undefined ? null : (
              <p className="ja-settings-error" role="alert">
                <CircleAlert size={14} aria-hidden="true" />
                {formState.errors.root.message}
              </p>
            )}
            <div className="ja-settings-form-actions">
              {feedback === undefined ? null : (
                <p className="ja-settings-feedback" role="status">
                  {feedback}
                </p>
              )}
              <Button
                type="submit"
                variant="secondary"
                disabled={formState.isSubmitting}
                loading={formState.isSubmitting}
              >
                <Plus size={14} aria-hidden="true" />
                保存 Server
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
            <AlertDialog.Title>删除 MCP Server？</AlertDialog.Title>
            <AlertDialog.Description>
              将删除 {deleteTarget?.name} 的配置，但不会清除其凭据。
            </AlertDialog.Description>
            <div className="ja-settings-form-actions">
              <AlertDialog.Cancel asChild>
                <Button variant="ghost">取消</Button>
              </AlertDialog.Cancel>
              <AlertDialog.Action asChild>
                <Button variant="danger" onClick={() => void removeServer()}>
                  删除
                </Button>
              </AlertDialog.Action>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
    </div>
  );
}
