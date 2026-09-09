// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as AlertDialog from "@radix-ui/react-alert-dialog";
import { ArrowDown, ArrowUp, Pencil, Play, Plus, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/shared/ui/primitives";
import type {
  DefaultModelSelection,
  ProviderApi,
  ProviderModelSave,
  ProviderSave,
  ReasoningLevel,
} from "@/shared/settings/types";
import { modelSelectionId } from "@/shared/settings/types";
import type { ProviderProjection, SettingsSnapshot } from "../domain/types";
import type { SettingsPorts } from "../application/ports";
import {
  apiOptions,
  canonicalRevision,
  emptyProviderDraft,
  emptyProviderModelDraft,
  Field,
  SectionHeader,
  settingsMutationErrorMessage,
  SettingsSelect,
} from "./shared";
import { CredentialVaultEditor } from "./CredentialVaultEditor";

interface ProviderEditorState {
  providerId: string;
  modelId: string;
  name: string;
  api: ProviderApi;
  baseUrl: string;
  credentialId: string;
  modelName: string;
  model: string;
}

interface ModelChoice {
  selection: DefaultModelSelection;
  label: string;
}

/** 创建空编辑器状态；Secret 不进入任何 React state，只由非受控 Vault 输入持有。 */
function emptyEditorState(): ProviderEditorState {
  const provider = emptyProviderDraft();
  return {
    providerId: canonicalRevision("provider"),
    modelId: canonicalRevision("model"),
    name: provider.name,
    api: provider.api,
    baseUrl: provider.baseUrl,
    credentialId: provider.credentialId,
    modelName: "",
    model: "",
  };
}

/** 把已保存 Provider 投影成编辑状态；模型由独立子列表维护，不在 Provider 保存时被覆盖。 */
function stateForProvider(provider: ProviderProjection): ProviderEditorState {
  return {
    providerId: provider.providerId,
    modelId: canonicalRevision("model"),
    name: provider.name,
    api: provider.api,
    baseUrl: provider.baseUrl,
    credentialId: provider.credentialId,
    modelName: "",
    model: "",
  };
}

/** 为新增模型使用预分配稳定 ID，失败重试不会创建另一个逻辑模型。 */
function newModel(modelId: string, name: string, model: string): ProviderModelSave {
  const draft = emptyProviderModelDraft();
  return {
    modelId,
    name: name.trim(),
    model: model.trim(),
    capabilities: draft.capabilities,
    reasoningLevelMap: draft.reasoningLevelMap,
    defaultReasoningLevel: draft.defaultReasoningLevel,
  };
}

const reasoningLevels: ReadonlyArray<{ value: ReasoningLevel; label: string }> = [
  { value: "off", label: "关闭" },
  { value: "minimal", label: "最少" },
  { value: "low", label: "低" },
  { value: "medium", label: "中" },
  { value: "high", label: "高" },
  { value: "xhigh", label: "极高" },
  { value: "max", label: "最大" },
];

/** 单模型编辑器保存预算和上游思考映射；输入能力由 App Server 模型目录拥有。 */
function ModelEditor({
  providerId,
  model,
  index,
  total,
  selected,
  replacementChoices,
  ports,
}: {
  providerId: string;
  model: ProviderModelSave;
  index: number;
  total: number;
  selected: boolean;
  replacementChoices: ModelChoice[];
  ports: SettingsPorts;
}): React.ReactElement {
  const [draft, setDraft] = useState<ProviderModelSave>(() => ({
    ...model,
    capabilities: { ...model.capabilities },
    reasoningLevelMap: { ...model.reasoningLevelMap },
  }));
  const [editOpen, setEditOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [testOpen, setTestOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [replacementId, setReplacementId] = useState(
    replacementChoices[0] === undefined
      ? ""
      : modelSelectionId(
          replacementChoices[0].selection.providerId,
          replacementChoices[0].selection.modelId,
        ),
  );
  const [testResult, setTestResult] = useState<string>();

  useEffect(
    () =>
      setDraft({
        ...model,
        capabilities: { ...model.capabilities },
        reasoningLevelMap: { ...model.reasoningLevelMap },
      }),
    [model],
  );

  /** 保存前维持默认思考档位属于支持集合，底层 controller 再做同一不变量校验。 */
  const save = async (): Promise<boolean> => {
    setBusy(true);
    try {
      await ports.onSaveModel(providerId, draft);
      toast.success("模型能力已保存");
      return true;
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, "模型能力保存失败"));
      return false;
    } finally {
      setBusy(false);
    }
  };

  /** 真实验证必须由确认动作触发，结果只显示 App Server 返回的脱敏模型名和耗时。 */
  const testModel = async (): Promise<void> => {
    setBusy(true);
    setTestResult(undefined);
    try {
      const result = await ports.onTestModel(providerId, model.modelId);
      setTestResult(`${result.responseModel} · ${result.latencyMs} ms`);
      toast.success("模型验证通过");
      setTestOpen(false);
    } catch {
      toast.error("模型验证失败");
    } finally {
      setBusy(false);
    }
  };

  /** 删除默认模型前固定用户显式选择的替代身份，失败时保留确认弹层便于恢复。 */
  const removeModel = async (event: React.MouseEvent<HTMLButtonElement>): Promise<void> => {
    event.preventDefault();
    const replacement =
      replacementChoices.find(
        (choice) =>
          modelSelectionId(choice.selection.providerId, choice.selection.modelId) === replacementId,
      )?.selection ?? null;
    setBusy(true);
    try {
      await ports.onDeleteModel(providerId, model.modelId, selected ? replacement : null);
      setDeleteOpen(false);
      toast.success(`${model.name} 已删除`);
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, `${model.name} 删除失败`));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      className="ja-settings-provider-note"
      data-setting-id={`model-${providerId}-${model.modelId}`}
      data-setting-search={`${draft.name} ${draft.model} ${Object.keys(draft.reasoningLevelMap).join(" ")}`}
      tabIndex={-1}
    >
      <button
        type="button"
        className="ja-settings-model-summary"
        onClick={() => setEditOpen(true)}
        aria-label={`编辑模型 ${draft.name}`}
      >
        <span>
          <strong>{draft.name}</strong>
          <small>{draft.model}</small>
        </span>
        <span>
          {selected
            ? "当前默认"
            : `${draft.capabilities.contextWindowTokens.toLocaleString()} tokens`}
        </span>
      </button>
      <div className="ja-settings-card-actions">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={busy}
          onClick={() => setTestOpen(true)}
        >
          <Play size={14} aria-hidden="true" />
          验证模型
        </Button>
        <Button
          type="button"
          variant={selected ? "primary" : "secondary"}
          size="sm"
          disabled={busy || selected}
          onClick={() =>
            void ports.onDefaultSelectionChange({
              providerId,
              modelId: model.modelId,
              reasoningLevel: model.defaultReasoningLevel,
            })
          }
        >
          {selected ? "当前默认" : "设为默认"}
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={busy || index === 0}
          onClick={() => void ports.onMoveModel(providerId, model.modelId, -1)}
          aria-label="上移模型"
        >
          <ArrowUp aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={busy || index === total - 1}
          onClick={() => void ports.onMoveModel(providerId, model.modelId, 1)}
          aria-label="下移模型"
        >
          <ArrowDown aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={busy || total === 1}
          onClick={() => setDeleteOpen(true)}
          aria-label="删除模型"
        >
          <Trash2 aria-hidden="true" />
        </Button>
      </div>
      {testResult === undefined ? null : <p className="ja-settings-feedback">{testResult}</p>}
      <Dialog modal open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent
          className="ja-settings-sheet"
          overlayClassName="ja-settings-dialog-overlay"
          aria-describedby={`${model.modelId}-edit-description`}
        >
          <div className="ja-settings-dialog-header">
            <div>
              <DialogTitle className="ja-settings-dialog-title">编辑模型</DialogTitle>
              <DialogDescription
                id={`${model.modelId}-edit-description`}
                className="ja-settings-dialog-description"
              >
                配置上游标识、Token 预算与 Provider 支持的思考档位。
              </DialogDescription>
            </div>
            <DialogClose asChild>
              <Button type="button" variant="ghost" size="sm" aria-label="关闭模型编辑">
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </div>
          <div className="ja-settings-sheet-body">
            <div className="ja-settings-form-grid">
              <Field id={`${model.modelId}-name`} label="显示名称">
                <input
                  id={`${model.modelId}-name`}
                  className="ja-settings-input"
                  value={draft.name}
                  onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                />
              </Field>
              <Field id={`${model.modelId}-model`} label="上游模型">
                <input
                  id={`${model.modelId}-model`}
                  className="ja-settings-input"
                  value={draft.model}
                  onChange={(event) => setDraft({ ...draft, model: event.target.value })}
                />
              </Field>
              <Field id={`${model.modelId}-context`} label="上下文 Tokens">
                <input
                  id={`${model.modelId}-context`}
                  className="ja-settings-input"
                  type="number"
                  min={4_096}
                  value={draft.capabilities.contextWindowTokens}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      capabilities: {
                        ...draft.capabilities,
                        contextWindowTokens: Number(event.target.value),
                      },
                    })
                  }
                />
              </Field>
              <Field id={`${model.modelId}-output`} label="最大输出 Tokens">
                <input
                  id={`${model.modelId}-output`}
                  className="ja-settings-input"
                  type="number"
                  min={1}
                  value={draft.capabilities.maxOutputTokens}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      capabilities: {
                        ...draft.capabilities,
                        maxOutputTokens: Number(event.target.value),
                      },
                    })
                  }
                />
              </Field>
            </div>
            <fieldset className="ja-settings-choice-group">
              <legend>思考档位</legend>
              {reasoningLevels.map((item) => (
                <label key={item.value}>
                  <input
                    type="checkbox"
                    checked={draft.reasoningLevelMap[item.value] !== undefined}
                    onChange={(event) => {
                      const reasoningLevelMap = { ...draft.reasoningLevelMap };
                      if (event.target.checked) reasoningLevelMap[item.value] = item.value;
                      else delete reasoningLevelMap[item.value];
                      setDraft({
                        ...draft,
                        reasoningLevelMap,
                        defaultReasoningLevel:
                          draft.defaultReasoningLevel !== null &&
                          reasoningLevelMap[draft.defaultReasoningLevel] === undefined
                            ? null
                            : draft.defaultReasoningLevel,
                      });
                    }}
                  />
                  {item.label}
                </label>
              ))}
            </fieldset>
            {Object.keys(draft.reasoningLevelMap).length > 0 ? (
              <Field id={`${model.modelId}-default-reasoning`} label="默认思考">
                <SettingsSelect
                  id={`${model.modelId}-default-reasoning`}
                  value={draft.defaultReasoningLevel ?? "none"}
                  options={[
                    { value: "none", label: "不指定" },
                    ...reasoningLevels.filter(
                      (item) => draft.reasoningLevelMap[item.value] !== undefined,
                    ),
                  ]}
                  onValueChange={(value) =>
                    setDraft({
                      ...draft,
                      defaultReasoningLevel: value === "none" ? null : (value as ReasoningLevel),
                    })
                  }
                />
              </Field>
            ) : null}
            <Button
              type="button"
              variant="primary"
              size="sm"
              loading={busy}
              onClick={() => {
                void save().then((saved) => {
                  if (saved) setEditOpen(false);
                });
              }}
            >
              保存模型能力
            </Button>
          </div>
        </DialogContent>
      </Dialog>
      <AlertDialog.Root open={testOpen} onOpenChange={setTestOpen}>
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="ja-settings-dialog-overlay" />
          <AlertDialog.Content className="ja-settings-confirm-dialog">
            <AlertDialog.Title>验证 {model.name}？</AlertDialog.Title>
            <AlertDialog.Description>
              将发送一次无历史、无工具、严格限额的真实推理请求，可能产生少量费用。回答不会保存。
            </AlertDialog.Description>
            <div className="ja-settings-form-actions">
              <AlertDialog.Cancel asChild>
                <Button variant="ghost">取消</Button>
              </AlertDialog.Cancel>
              <AlertDialog.Action asChild>
                <Button variant="primary" loading={busy} onClick={() => void testModel()}>
                  验证
                </Button>
              </AlertDialog.Action>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
      <AlertDialog.Root open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="ja-settings-dialog-overlay" />
          <AlertDialog.Content className="ja-settings-confirm-dialog">
            <AlertDialog.Title>删除 {model.name}？</AlertDialog.Title>
            <AlertDialog.Description>
              删除模型不会清除 Provider 凭据。此操作保存后立即生效。
            </AlertDialog.Description>
            {selected ? (
              replacementChoices.length === 0 ? (
                <p className="ja-settings-error" role="alert">
                  没有可用替代模型。
                </p>
              ) : (
                <Field id={`${model.modelId}-replacement`} label="替代默认模型">
                  <SettingsSelect
                    id={`${model.modelId}-replacement`}
                    value={replacementId}
                    options={replacementChoices.map((choice) => ({
                      value: modelSelectionId(
                        choice.selection.providerId,
                        choice.selection.modelId,
                      ),
                      label: choice.label,
                    }))}
                    onValueChange={setReplacementId}
                  />
                </Field>
              )
            ) : null}
            <div className="ja-settings-form-actions">
              <AlertDialog.Cancel asChild>
                <Button variant="ghost">取消</Button>
              </AlertDialog.Cancel>
              <AlertDialog.Action asChild>
                <Button
                  variant="danger"
                  loading={busy}
                  disabled={selected && replacementChoices.length === 0}
                  onClick={(event) => void removeModel(event)}
                >
                  删除模型
                </Button>
              </AlertDialog.Action>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
    </div>
  );
}

/** Provider 主面只承载摘要与模型清单；连接、凭据和新增模型草稿进入独立弹层。 */
function ProviderCard({
  provider,
  index,
  total,
  defaultSelection,
  replacementChoices,
  ports,
}: {
  provider: ProviderProjection;
  index: number;
  total: number;
  defaultSelection: DefaultModelSelection | null;
  replacementChoices: ModelChoice[];
  ports: SettingsPorts;
}): React.ReactElement {
  const providerReplacementChoices = replacementChoices.filter(
    (choice) => choice.selection.providerId !== provider.providerId,
  );
  const [editor, setEditor] = useState(() => stateForProvider(provider));
  const [busy, setBusy] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [modelOpen, setModelOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [replacementId, setReplacementId] = useState(
    providerReplacementChoices[0] === undefined
      ? ""
      : modelSelectionId(
          providerReplacementChoices[0].selection.providerId,
          providerReplacementChoices[0].selection.modelId,
        ),
  );

  /** 权威快照变化时刷新非 Secret 字段，避免保存后的旧本地草稿覆盖远端 CAS 结果。 */
  useEffect(() => setEditor(stateForProvider(provider)), [provider]);

  /** 保存连接设置时完整保留模型与 Agent defaults，防止局部表单丢失子聚合。 */
  const saveProvider = async (): Promise<boolean> => {
    if (editor.name.trim() === "" || editor.baseUrl.trim() === "") {
      toast.error("请填写服务商名称和 Base URL");
      return false;
    }
    setBusy(true);
    try {
      const next: ProviderSave = {
        ...provider,
        name: editor.name.trim(),
        api: editor.api,
        baseUrl: editor.baseUrl.trim(),
        credentialId: editor.credentialId,
      };
      await ports.onSaveProvider(next);
      toast.success("服务商配置已保存");
      return true;
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, "服务商配置保存失败"));
      return false;
    } finally {
      setBusy(false);
    }
  };

  /** 新模型先通过严格应用端口保存，再由用户显式选择是否设为默认。 */
  const addModel = async (): Promise<boolean> => {
    if (editor.modelName.trim() === "" || editor.model.trim() === "") {
      toast.error("请填写模型名称和模型标识");
      return false;
    }
    setBusy(true);
    try {
      await ports.onSaveModel(
        provider.providerId,
        newModel(editor.modelId, editor.modelName, editor.model),
      );
      setEditor((current) => ({
        ...current,
        modelId: canonicalRevision("model"),
        modelName: "",
        model: "",
      }));
      toast.success("模型已添加");
      return true;
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, "模型添加失败"));
      return false;
    } finally {
      setBusy(false);
    }
  };

  /** Provider 删除确认显式携带替代默认模型；最后一个 Provider 则以 null 进入首次配置态。 */
  const removeProvider = async (event: React.MouseEvent<HTMLButtonElement>): Promise<void> => {
    event.preventDefault();
    const deletesDefault = defaultSelection?.providerId === provider.providerId;
    const replacement =
      providerReplacementChoices.find(
        (choice) =>
          modelSelectionId(choice.selection.providerId, choice.selection.modelId) === replacementId,
      )?.selection ?? null;
    setBusy(true);
    try {
      await ports.onDeleteProvider(provider.providerId, deletesDefault ? replacement : null);
      setDeleteOpen(false);
      toast.success(`${provider.name} 已删除`);
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, `${provider.name} 删除失败`));
    } finally {
      setBusy(false);
    }
  };

  return (
    <article
      className="ja-settings-provider-editor"
      data-setting-id={`provider-${provider.providerId}`}
      data-setting-search={`${provider.name} ${provider.api} ${provider.baseUrl}`}
      tabIndex={-1}
    >
      <div className="ja-settings-provider-overview">
        <div className="ja-settings-provider-title">
          <strong>{provider.name}</strong>
          <div className="ja-settings-provider-metadata">
            <span>{provider.api}</span>
            <span>{provider.models.length} 个模型</span>
            <span>{provider.credentialConfigured ? "凭据已配置" : "凭据未配置"}</span>
          </div>
        </div>
        <div className="ja-settings-card-actions">
          <Button type="button" variant="secondary" size="sm" onClick={() => setEditOpen(true)}>
            <Pencil aria-hidden="true" />
            编辑
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={busy || index === 0}
            onClick={() => void ports.onMoveProvider(provider.providerId, -1)}
            aria-label="上移服务商"
          >
            <ArrowUp aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={busy || index === total - 1}
            onClick={() => void ports.onMoveProvider(provider.providerId, 1)}
            aria-label="下移服务商"
          >
            <ArrowDown aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={() => setDeleteOpen(true)}
            aria-label="删除服务商"
          >
            <Trash2 aria-hidden="true" />
          </Button>
        </div>
      </div>
      <section className="ja-settings-model-list" aria-label={`${provider.name} 模型`}>
        <div className="ja-settings-subsection-heading">
          <h3>模型</h3>
          <Button type="button" variant="secondary" size="sm" onClick={() => setModelOpen(true)}>
            <Plus aria-hidden="true" />
            添加模型
          </Button>
        </div>
        {provider.models.length === 0 ? (
          <p className="ja-settings-empty">此 Provider 尚无模型</p>
        ) : null}
        {provider.models.map((model, modelIndex) => {
          const selected =
            defaultSelection?.providerId === provider.providerId &&
            defaultSelection.modelId === model.modelId;
          return (
            <ModelEditor
              key={model.modelId}
              providerId={provider.providerId}
              model={model}
              index={modelIndex}
              total={provider.models.length}
              selected={selected}
              replacementChoices={replacementChoices.filter(
                (choice) =>
                  choice.selection.providerId !== provider.providerId ||
                  choice.selection.modelId !== model.modelId,
              )}
              ports={ports}
            />
          );
        })}
      </section>
      <Dialog modal open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent
          className="ja-settings-sheet"
          overlayClassName="ja-settings-dialog-overlay"
          aria-describedby={`${provider.providerId}-edit-description`}
        >
          <div className="ja-settings-dialog-header">
            <div>
              <DialogTitle className="ja-settings-dialog-title">编辑 Provider</DialogTitle>
              <DialogDescription
                id={`${provider.providerId}-edit-description`}
                className="ja-settings-dialog-description"
              >
                连接配置与系统凭据在保存前不会影响当前会话。
              </DialogDescription>
            </div>
            <DialogClose asChild>
              <Button type="button" variant="ghost" size="sm" aria-label="关闭 Provider 编辑">
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </div>
          <div className="ja-settings-sheet-body">
            <div className="ja-settings-form-grid">
              <Field id={`${provider.providerId}-name`} label="服务商名称">
                <input
                  id={`${provider.providerId}-name`}
                  className="ja-settings-input"
                  value={editor.name}
                  onChange={(event) => setEditor({ ...editor, name: event.target.value })}
                />
              </Field>
              <Field id={`${provider.providerId}-api`} label="API 规范">
                <SettingsSelect
                  id={`${provider.providerId}-api`}
                  value={editor.api}
                  options={apiOptions}
                  onValueChange={(value) => setEditor({ ...editor, api: value as ProviderApi })}
                />
              </Field>
              <Field id={`${provider.providerId}-url`} label="Base URL">
                <input
                  id={`${provider.providerId}-url`}
                  className="ja-settings-input"
                  value={editor.baseUrl}
                  onChange={(event) => setEditor({ ...editor, baseUrl: event.target.value })}
                />
              </Field>
            </div>
            <CredentialVaultEditor
              reference={editor.credentialId}
              configured={provider.credentialConfigured}
              onReplaceCredential={ports.onReplaceCredential}
              onClearCredential={ports.onClearCredential}
            />
          </div>
          <div className="ja-settings-sheet-footer">
            <DialogClose asChild>
              <Button type="button" variant="secondary" size="sm">
                取消
              </Button>
            </DialogClose>
            <Button
              type="button"
              variant="primary"
              size="sm"
              loading={busy}
              onClick={() => {
                void saveProvider().then((saved) => {
                  if (saved) setEditOpen(false);
                });
              }}
            >
              保存 Provider
            </Button>
          </div>
        </DialogContent>
      </Dialog>
      <Dialog modal open={modelOpen} onOpenChange={setModelOpen}>
        <DialogContent
          className="ja-settings-dialog ja-settings-model-dialog"
          overlayClassName="ja-settings-dialog-overlay"
          aria-describedby={`${provider.providerId}-new-model-description`}
        >
          <div className="ja-settings-dialog-header">
            <div>
              <DialogTitle className="ja-settings-dialog-title">添加模型</DialogTitle>
              <DialogDescription
                id={`${provider.providerId}-new-model-description`}
                className="ja-settings-dialog-description"
              >
                自定义模型默认仅支持文本，保存后可继续配置 Token 与思考档位。
              </DialogDescription>
            </div>
            <DialogClose asChild>
              <Button type="button" variant="ghost" size="sm" aria-label="关闭添加模型">
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </div>
          <div className="ja-settings-form-grid">
            <Field id={`${provider.providerId}-model-name`} label="模型名称">
              <input
                id={`${provider.providerId}-model-name`}
                className="ja-settings-input"
                value={editor.modelName}
                onChange={(event) => setEditor({ ...editor, modelName: event.target.value })}
              />
            </Field>
            <Field id={`${provider.providerId}-model-id`} label="模型标识">
              <input
                id={`${provider.providerId}-model-id`}
                className="ja-settings-input"
                value={editor.model}
                onChange={(event) => setEditor({ ...editor, model: event.target.value })}
                placeholder="gpt-5.6-sol"
              />
            </Field>
          </div>
          <div className="ja-settings-form-actions">
            <DialogClose asChild>
              <Button type="button" variant="secondary" size="sm">
                取消
              </Button>
            </DialogClose>
            <Button
              type="button"
              variant="primary"
              size="sm"
              loading={busy}
              onClick={() => {
                void addModel().then((saved) => {
                  if (saved) setModelOpen(false);
                });
              }}
            >
              添加模型
            </Button>
          </div>
        </DialogContent>
      </Dialog>
      <AlertDialog.Root open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="ja-settings-dialog-overlay" />
          <AlertDialog.Content className="ja-settings-confirm-dialog">
            <AlertDialog.Title>删除 {provider.name}？</AlertDialog.Title>
            <AlertDialog.Description>
              将删除该 Provider 及其模型，但不会清除凭据。
            </AlertDialog.Description>
            {defaultSelection?.providerId === provider.providerId ? (
              providerReplacementChoices.length === 0 ? (
                <p className="ja-settings-feedback">删除后将返回首次配置。</p>
              ) : (
                <Field id={`${provider.providerId}-replacement`} label="替代默认模型">
                  <SettingsSelect
                    id={`${provider.providerId}-replacement`}
                    value={replacementId}
                    options={providerReplacementChoices.map((choice) => ({
                      value: modelSelectionId(
                        choice.selection.providerId,
                        choice.selection.modelId,
                      ),
                      label: choice.label,
                    }))}
                    onValueChange={setReplacementId}
                  />
                </Field>
              )
            ) : null}
            <div className="ja-settings-form-actions">
              <AlertDialog.Cancel asChild>
                <Button variant="ghost">取消</Button>
              </AlertDialog.Cancel>
              <AlertDialog.Action asChild>
                <Button
                  variant="danger"
                  loading={busy}
                  onClick={(event) => void removeProvider(event)}
                >
                  删除 Provider
                </Button>
              </AlertDialog.Action>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
    </article>
  );
}

/** 模型与服务商页面完整管理 v1 聚合，连接与模型能力保持同一 Provider 所有权。 */
export function ModelsSection({
  providers,
  defaultSelection,
  ports,
}: {
  providers: SettingsSnapshot["providers"];
  defaultSelection: SettingsSnapshot["defaultSelection"];
  snapshotRevision: number;
  ports: SettingsPorts;
}): React.ReactElement {
  const [editor, setEditor] = useState<ProviderEditorState>(emptyEditorState);
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [creationFeedback, setCreationFeedback] = useState<string>();
  const secretRef = useRef<HTMLInputElement>(null);
  const [selectedProviderId, setSelectedProviderId] = useState(
    defaultSelection?.providerId ?? providers[0]?.providerId,
  );
  const selectedProvider =
    providers.find((provider) => provider.providerId === selectedProviderId) ?? providers[0];
  const modelChoices: ModelChoice[] = providers.flatMap((provider) =>
    provider.models.map((model) => ({
      selection: {
        providerId: provider.providerId,
        modelId: model.modelId,
        reasoningLevel: model.defaultReasoningLevel,
      },
      label: `${provider.name} · ${model.name}`,
    })),
  );

  /** 删除或重载 Provider 后把选择收敛到真实对象，不持久化本地选择身份。 */
  useEffect(() => {
    if (adding || providers.length === 0) return;
    if (!providers.some((provider) => provider.providerId === selectedProviderId)) {
      setSelectedProviderId(defaultSelection?.providerId ?? providers[0]?.providerId);
    }
  }, [adding, defaultSelection?.providerId, providers, selectedProviderId]);

  /** 关闭创建器时清除草稿和 Secret DOM，下一次打开必须获得全新的稳定身份。 */
  const resetCreator = (): void => {
    if (secretRef.current !== null) secretRef.current.value = "";
    setEditor(emptyEditorState());
    setCreationFeedback(undefined);
  };

  /** 进行中的跨存储提交不能被关闭；其余关闭路径统一释放敏感输入与失败反馈。 */
  const changeAdding = (open: boolean): void => {
    if (!open && busy) return;
    setAdding(open);
    if (!open) resetCreator();
  };

  /**
   * 新建 Provider、首模型和独立 Secret 由一个应用用例完成；Secret 每次尝试后立即清空，
   * 凭据写入失败则保留同一 Provider/Model ID 和非敏感草稿供原地重试。
   */
  const createProvider = async (): Promise<boolean> => {
    const secret = secretRef.current?.value ?? "";
    setCreationFeedback(undefined);
    if (
      editor.name.trim() === "" ||
      editor.baseUrl.trim() === "" ||
      editor.modelName.trim() === "" ||
      editor.model.trim() === ""
    ) {
      const message = "请完整填写服务商、Base URL 和首个模型";
      if (secretRef.current !== null) secretRef.current.value = "";
      setCreationFeedback(message);
      toast.error(message);
      return false;
    }
    if (secret.length === 0) {
      const message = "请输入 API key / token";
      setCreationFeedback(message);
      toast.error(message);
      return false;
    }
    setBusy(true);
    try {
      const defaults = emptyProviderDraft();
      await ports.onCreateProvider(
        {
          ...defaults,
          providerId: editor.providerId,
          name: editor.name.trim(),
          api: editor.api,
          baseUrl: editor.baseUrl.trim(),
          credentialId: editor.credentialId,
          models: [newModel(editor.modelId, editor.modelName, editor.model)],
        },
        secret,
      );
      setSelectedProviderId(editor.providerId);
      setAdding(false);
      resetCreator();
      toast.success("服务商、模型和密钥已保存");
      return true;
    } catch (error) {
      const code =
        error !== null && typeof error === "object"
          ? (error as { code?: unknown }).code
          : undefined;
      const message =
        code === "provider_saved_credential_failed"
          ? "Provider 已保存，但密钥保存失败，请重新输入后重试"
          : settingsMutationErrorMessage(error, "服务商添加失败");
      if (code === "provider_saved_credential_failed") setSelectedProviderId(editor.providerId);
      setCreationFeedback(message);
      toast.error(message);
      return false;
    } finally {
      if (secretRef.current !== null) secretRef.current.value = "";
      setBusy(false);
    }
  };

  return (
    <div className="ja-settings-section">
      <SectionHeader
        title="模型与服务商"
        action={
          <Button type="button" variant="primary" size="sm" onClick={() => changeAdding(true)}>
            <Plus aria-hidden="true" />
            新增 Provider
          </Button>
        }
      />
      <div className="ja-settings-provider-layout">
        <aside className="ja-settings-provider-sidebar" aria-label="Provider 列表">
          {providers.map((provider) => (
            <button
              key={provider.providerId}
              type="button"
              className={`ja-settings-provider-master${!adding && provider.providerId === selectedProvider?.providerId ? " is-selected" : ""}`}
              onClick={() => {
                setSelectedProviderId(provider.providerId);
                changeAdding(false);
              }}
            >
              <strong>{provider.name}</strong>
              <small>{provider.models.length} 个模型</small>
            </button>
          ))}
          {providers.length === 0 ? <p className="ja-settings-empty">尚未配置 Provider</p> : null}
        </aside>
        <div className="ja-settings-provider-detail">
          {selectedProvider === undefined ? (
            <p className="ja-settings-empty">选择或新增 Provider</p>
          ) : (
            <ProviderCard
              provider={selectedProvider}
              index={providers.indexOf(selectedProvider)}
              total={providers.length}
              defaultSelection={defaultSelection}
              replacementChoices={modelChoices}
              ports={ports}
            />
          )}
        </div>
      </div>
      <Dialog modal open={adding} onOpenChange={changeAdding}>
        <DialogContent
          className="ja-settings-sheet"
          overlayClassName="ja-settings-dialog-overlay"
          aria-describedby="new-provider-description"
        >
          <div className="ja-settings-dialog-header">
            <div>
              <DialogTitle className="ja-settings-dialog-title">新增 Provider</DialogTitle>
              <DialogDescription
                id="new-provider-description"
                className="ja-settings-dialog-description"
              >
                首次保存会同时写入 Provider、首个模型和独立密钥。
              </DialogDescription>
            </div>
            <DialogClose asChild>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                aria-label="关闭新增 Provider"
                disabled={busy}
              >
                <X aria-hidden="true" />
              </Button>
            </DialogClose>
          </div>
          <div className="ja-settings-sheet-body">
            <div className="ja-settings-form-grid">
              <Field id="new-provider-name" label="服务商名称">
                <input
                  id="new-provider-name"
                  className="ja-settings-input"
                  value={editor.name}
                  onChange={(event) => setEditor({ ...editor, name: event.target.value })}
                />
              </Field>
              <Field id="new-provider-api" label="API 规范">
                <SettingsSelect
                  id="new-provider-api"
                  value={editor.api}
                  options={apiOptions}
                  onValueChange={(value) => setEditor({ ...editor, api: value as ProviderApi })}
                />
              </Field>
              <Field id="new-provider-url" label="Base URL">
                <input
                  id="new-provider-url"
                  className="ja-settings-input"
                  value={editor.baseUrl}
                  onChange={(event) => setEditor({ ...editor, baseUrl: event.target.value })}
                />
              </Field>
              <Field id="new-provider-secret" label="API key / token">
                <input
                  ref={secretRef}
                  id="new-provider-secret"
                  className="ja-settings-input"
                  type="password"
                  autoComplete="new-password"
                  placeholder="输入后保存，不会回显"
                  aria-describedby="new-provider-feedback"
                  disabled={busy}
                />
              </Field>
              <Field id="new-provider-model-name" label="首个模型名称">
                <input
                  id="new-provider-model-name"
                  className="ja-settings-input"
                  value={editor.modelName}
                  onChange={(event) => setEditor({ ...editor, modelName: event.target.value })}
                />
              </Field>
              <Field id="new-provider-model" label="上游模型">
                <input
                  id="new-provider-model"
                  className="ja-settings-input"
                  value={editor.model}
                  onChange={(event) => setEditor({ ...editor, model: event.target.value })}
                />
              </Field>
            </div>
            {creationFeedback === undefined ? null : (
              <p id="new-provider-feedback" className="ja-settings-feedback" role="status">
                {creationFeedback}
              </p>
            )}
          </div>
          <div className="ja-settings-sheet-footer">
            {providers.length > 0 ? (
              <DialogClose asChild>
                <Button type="button" variant="secondary" size="sm" disabled={busy}>
                  取消
                </Button>
              </DialogClose>
            ) : null}
            <Button
              type="button"
              variant="primary"
              size="sm"
              loading={busy}
              onClick={() => void createProvider()}
            >
              保存 Provider
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
