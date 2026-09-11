// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as AlertDialog from "@radix-ui/react-alert-dialog";
import {
  ArrowDown,
  ArrowUp,
  Check,
  ChevronRight,
  MoreHorizontal,
  Pencil,
  Play,
  Plus,
  Server,
  Trash2,
  X,
} from "lucide-react";
import { useLayoutEffect, useRef, useState, type ReactElement } from "react";
import { toast } from "sonner";
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuSeparator,
  MenuTrigger,
} from "@/shared/ui/primitives";
import type {
  DefaultModelSelection,
  ProviderApi,
  ProviderModelSave,
  ProviderSave,
  ReasoningLevel,
} from "@/shared/settings/types";
import { modelSelectionId } from "@/shared/settings/types";
import { isSafeProviderUrl } from "@/shared/settings/validation";
import type { ProviderProjection, SettingsSnapshot } from "../domain/types";
import type { SettingsPorts } from "../application/ports";
import { getModelBudgetRecommendation } from "../domain/modelBudgetRecommendations";
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
import "./provider-editor.css";
import "./models-overview.css";

/** 低频排序与危险操作集中到对象菜单，保留键盘导航并避免每行堆叠无效箭头。 */
function ObjectActions({
  label,
  index,
  total,
  busy,
  canDelete = true,
  onMove,
  onDelete,
}: {
  label: string;
  index: number;
  total: number;
  busy: boolean;
  canDelete?: boolean;
  onMove: (direction: -1 | 1) => void;
  onDelete: (trigger: HTMLButtonElement | null) => void;
}): ReactElement {
  const triggerRef = useRef<HTMLButtonElement>(null);
  return (
    <Menu>
      <MenuTrigger asChild>
        <IconButton
          ref={triggerRef}
          label={`${label}更多操作`}
          disabled={busy}
          className="ja-models-more"
        >
          <MoreHorizontal size={18} aria-hidden="true" />
        </IconButton>
      </MenuTrigger>
      <MenuContent align="end" className="ja-models-action-menu">
        <MenuItem disabled={index === 0} onSelect={() => onMove(-1)}>
          <ArrowUp size={16} aria-hidden="true" />
          上移
        </MenuItem>
        <MenuItem disabled={index === total - 1} onSelect={() => onMove(1)}>
          <ArrowDown size={16} aria-hidden="true" />
          下移
        </MenuItem>
        <MenuSeparator className="ja-models-menu-separator" />
        <MenuItem
          disabled={!canDelete}
          onSelect={() => onDelete(triggerRef.current)}
          className="ja-models-delete"
        >
          <Trash2 size={16} aria-hidden="true" />
          删除
        </MenuItem>
        {!canDelete ? <p className="ja-models-menu-note">供应商至少保留一个模型</p> : null}
      </MenuContent>
    </Menu>
  );
}

interface ModelChoice {
  selection: DefaultModelSelection;
  label: string;
}

interface ProviderEditorDraft {
  providerId: string;
  name: string;
  api: ProviderApi;
  baseUrl: string;
  credentialId: string;
  models: ProviderModelSave[];
}

/** 在搜索切换供应商后，把用户带回真实的模型编辑入口，避免只高亮不可见目标。 */
function focusModelSearchTarget(providerId: string, modelId?: string): void {
  const settingId =
    modelId === undefined ? "provider-" + providerId : "model-" + providerId + "-" + modelId;
  const target = Array.from(document.querySelectorAll<HTMLElement>("[data-setting-id]")).find(
    (element) => element.dataset["settingId"] === settingId,
  );
  if (target === undefined) return;
  target.scrollIntoView({ behavior: "auto", block: "center" });
  const focusTarget =
    modelId === undefined
      ? target.querySelector<HTMLElement>('[aria-label="编辑供应商"]')
      : target.querySelector<HTMLElement>(".ja-models-summary");
  focusTarget?.focus({ preventScroll: true });
}

const reasoningLevels: ReadonlyArray<{ value: ReasoningLevel; label: string }> = [
  { value: "off", label: "关闭 (off)" },
  { value: "minimal", label: "最少 (minimal)" },
  { value: "low", label: "低 (low)" },
  { value: "medium", label: "中 (medium)" },
  { value: "high", label: "高 (high)" },
  { value: "xhigh", label: "极高 (xhigh)" },
  { value: "max", label: "最大 (max)" },
];
const contextPresets = [128_000, 256_000, 512_000, 1_000_000] as const;
const minContextTokens = 4_096;
const maxContextTokens = 4_000_000;
const maxOutputTokens = 1_000_000;

/** 默认模型预算只代表 Ja 的安全起步值，稳定 ID 在打开草稿时分配以支持原地重试。 */
function createModelDraft(modelId = canonicalRevision("model")): ProviderModelSave {
  const draft = emptyProviderModelDraft();
  return {
    modelId,
    name: draft.name,
    model: draft.model,
    capabilities: { ...draft.capabilities },
    reasoningLevelMap: { ...draft.reasoningLevelMap },
    defaultReasoningLevel: draft.defaultReasoningLevel,
  };
}

/** 复制嵌套模型字段，避免表单编辑通过共享引用修改快照。 */
function cloneModel(model: ProviderModelSave): ProviderModelSave {
  return {
    ...model,
    capabilities: { ...model.capabilities },
    reasoningLevelMap: { ...model.reasoningLevelMap },
  };
}

/** 首次配置预置一行模型，新增和编辑都走同一个多模型表单。 */
function emptyProviderEditorDraft(): ProviderEditorDraft {
  const provider = emptyProviderDraft();
  return {
    providerId: canonicalRevision("provider"),
    name: provider.name,
    api: provider.api,
    baseUrl: provider.baseUrl,
    credentialId: provider.credentialId,
    models: [createModelDraft()],
  };
}

/** 把保存快照转换为非敏感本地草稿，同时保留所有稳定模型身份。 */
function draftForProvider(provider: ProviderProjection): ProviderEditorDraft {
  return {
    providerId: provider.providerId,
    name: provider.name,
    api: provider.api,
    baseUrl: provider.baseUrl,
    credentialId: provider.credentialId,
    models: provider.models.map(cloneModel),
  };
}

/** 数值输入为空时返回 NaN，让统一保存校验给出可恢复错误而非写入隐式零值。 */
function parseInteger(value: string): number {
  return value.trim() === "" ? Number.NaN : Number(value);
}

/** 保存前校验模型身份、预算与默认思考档位，避免重复或非法配置进入 App Server。 */
function validateProviderDraft(draft: ProviderEditorDraft): string | undefined {
  if (draft.name.trim() === "") return "请填写供应商名称";
  if (draft.baseUrl.trim() === "") return "请填写 Base URL";
  if (!isSafeProviderUrl(draft.baseUrl.trim()))
    return "请输入无凭据参数的 HTTPS 地址，或本机回环 HTTP 地址";
  if (draft.models.length === 0) return "至少保留一个模型";
  const seen = new Set<string>();
  for (const model of draft.models) {
    const upstream = model.model.trim();
    if (upstream === "") return "请填写每个模型的上游标识";
    if (seen.has(upstream)) return `上游模型“${upstream}”重复，请保留一行`;
    seen.add(upstream);
    const context = model.capabilities.contextWindowTokens;
    const output = model.capabilities.maxOutputTokens;
    if (!Number.isInteger(context) || context < minContextTokens || context > maxContextTokens)
      return `模型“${model.name.trim() || upstream}”的上下文预算无效`;
    if (!Number.isInteger(output) || output < 1 || output > maxOutputTokens || output >= context)
      return `模型“${model.name.trim() || upstream}”的最大输出预算无效`;
    if (
      model.defaultReasoningLevel !== null &&
      model.reasoningLevelMap[model.defaultReasoningLevel] === undefined
    )
      return `模型“${model.name.trim() || upstream}”的默认思考档位不可用`;
  }
  return undefined;
}

/**
 * 统一 sheet 中的模型行。高频标识与预算直接可见，高级思考档位渐进展开，
 * 只修改父级草稿以保证供应商和多个模型作为一个完整聚合提交。
 */
function ProviderModelDraftRow({
  model,
  baseUrl,
  index,
  canRemove,
  disabled,
  onChange,
  onRemove,
}: {
  model: ProviderModelSave;
  baseUrl: string;
  index: number;
  canRemove: boolean;
  disabled: boolean;
  onChange: (model: ProviderModelSave) => void;
  onRemove: () => void;
}): ReactElement {
  /** 更新当前行的非嵌套字段，模型身份始终来自稳定 modelId。 */
  const update = (patch: Partial<ProviderModelSave>): void => onChange({ ...model, ...patch });
  /** 更新预算时只替换 capabilities，避免覆盖思考档位草稿。 */
  const updateCapabilities = (patch: Partial<ProviderModelSave["capabilities"]>): void =>
    update({ capabilities: { ...model.capabilities, ...patch } });
  /** 切换思考档位时自动清除失效的默认值，避免保存后选择悬空。 */
  const updateReasoning = (level: ReasoningLevel, enabled: boolean): void => {
    const reasoningLevelMap = { ...model.reasoningLevelMap };
    if (enabled) reasoningLevelMap[level] = level;
    else delete reasoningLevelMap[level];
    update({
      reasoningLevelMap,
      defaultReasoningLevel:
        model.defaultReasoningLevel !== null &&
        reasoningLevelMap[model.defaultReasoningLevel] === undefined
          ? null
          : model.defaultReasoningLevel,
    });
  };
  return (
    <article className="ja-provider-model-row" data-model-id={model.modelId}>
      <div className="ja-provider-model-row-header">
        <span className="ja-provider-model-index">{String(index + 1).padStart(2, "0")}</span>
        <div>
          <strong>{model.model.trim() || `模型 ${index + 1}`}</strong>
        </div>
        {canRemove ? (
          <button
            type="button"
            className="ja-provider-model-remove"
            onClick={onRemove}
            disabled={disabled}
            aria-label={`移除模型 ${model.name.trim() || index + 1}`}
            title="移除未保存的模型"
          >
            <Trash2 size={15} aria-hidden="true" />
          </button>
        ) : null}
      </div>
      <div className="ja-provider-model-fields">
        <Field id={`model-id-${model.modelId}`} label="上游模型标识">
          <input
            id={`model-id-${model.modelId}`}
            className="ja-settings-input ja-provider-model-upstream"
            value={model.model}
            disabled={disabled}
            placeholder="例如：gpt-5.6-sol"
            onChange={(event) => update({ model: event.target.value })}
            required
          />
        </Field>
        <Field id={`model-name-${model.modelId}`} label="显示名称">
          <input
            id={`model-name-${model.modelId}`}
            className="ja-settings-input"
            value={model.name}
            disabled={disabled}
            placeholder="可选，例如：主力模型"
            onChange={(event) => update({ name: event.target.value })}
          />
        </Field>
      </div>
      <div className="ja-provider-budget-block">
        <div className="ja-provider-budget-fields">
          <Field id={`model-context-${model.modelId}`} label="上下文 Tokens">
            <input
              id={`model-context-${model.modelId}`}
              className="ja-settings-input"
              type="number"
              min={minContextTokens}
              max={maxContextTokens}
              step={1}
              value={
                Number.isNaN(model.capabilities.contextWindowTokens)
                  ? ""
                  : model.capabilities.contextWindowTokens
              }
              disabled={disabled}
              onChange={(event) =>
                updateCapabilities({ contextWindowTokens: parseInteger(event.target.value) })
              }
            />
          </Field>
          <Field id={`model-output-${model.modelId}`} label="最大输出 Tokens">
            <input
              id={`model-output-${model.modelId}`}
              className="ja-settings-input"
              type="number"
              min={1}
              max={maxOutputTokens}
              step={1}
              value={
                Number.isNaN(model.capabilities.maxOutputTokens)
                  ? ""
                  : model.capabilities.maxOutputTokens
              }
              disabled={disabled}
              onChange={(event) =>
                updateCapabilities({ maxOutputTokens: parseInteger(event.target.value) })
              }
            />
          </Field>
        </div>
        <div className="ja-provider-context-presets" aria-label="上下文预算快捷选项">
          {contextPresets.map((value) => (
            <button
              key={value}
              type="button"
              data-context-value={value}
              aria-pressed={model.capabilities.contextWindowTokens === value}
              className={model.capabilities.contextWindowTokens === value ? "is-selected" : ""}
              disabled={disabled}
              onClick={() => updateCapabilities({ contextWindowTokens: value })}
            >
              {value >= 1_000_000 ? `${value / 1_000_000}M` : `${value / 1_000}K`}
              {model.capabilities.contextWindowTokens === value ? (
                <Check size={13} aria-hidden="true" />
              ) : null}
            </button>
          ))}
        </div>
        {(() => {
          const recommendation = getModelBudgetRecommendation(model.model, baseUrl);
          const matches =
            model.capabilities.contextWindowTokens === recommendation.contextWindowTokens &&
            model.capabilities.maxOutputTokens === recommendation.maxOutputTokens;
          return (
            <div className="ja-provider-recommendation" data-recommendation={recommendation.label}>
              <span>
                {recommendation.label}：
                {recommendation.contextWindowTokens >= 1_000_000
                  ? `${recommendation.contextWindowTokens / 1_000_000}M`
                  : `${recommendation.contextWindowTokens / 1_000}K`}{" "}
                上下文 · {recommendation.maxOutputTokens.toLocaleString()} 输出
              </span>
              <small>{recommendation.description}</small>
              {matches ? null : (
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() =>
                    updateCapabilities({
                      contextWindowTokens: recommendation.contextWindowTokens,
                      maxOutputTokens: recommendation.maxOutputTokens,
                    })
                  }
                >
                  使用推荐值
                </button>
              )}
            </div>
          );
        })()}
      </div>
      <details className="ja-provider-advanced">
        <summary>
          高级能力 <span>思考档位</span>
        </summary>
        <fieldset className="ja-settings-choice-group">
          <legend>支持的思考档位</legend>
          {reasoningLevels.map((item) => (
            <label key={item.value}>
              <input
                type="checkbox"
                checked={model.reasoningLevelMap[item.value] !== undefined}
                disabled={disabled}
                onChange={(event) => updateReasoning(item.value, event.target.checked)}
              />
              {item.label}
            </label>
          ))}
        </fieldset>
        {Object.keys(model.reasoningLevelMap).length > 0 ? (
          <Field id={`model-default-reasoning-${model.modelId}`} label="默认思考档位">
            <SettingsSelect
              id={`model-default-reasoning-${model.modelId}`}
              value={model.defaultReasoningLevel ?? "none"}
              options={[
                { value: "none", label: "不指定" },
                ...reasoningLevels.filter(
                  (item) => model.reasoningLevelMap[item.value] !== undefined,
                ),
              ]}
              disabled={disabled}
              onValueChange={(value) =>
                update({
                  defaultReasoningLevel: value === "none" ? null : (value as ReasoningLevel),
                })
              }
            />
          </Field>
        ) : null}
      </details>
    </article>
  );
}

/** 模型摘要与操作保留就地入口，所有能力编辑统一交给供应商草稿，避免两套保存状态。 */
function ModelEditor({
  providerId,
  model,
  index,
  total,
  selected,
  replacementChoices,
  ports,
  onEdit,
}: {
  providerId: string;
  model: ProviderModelSave;
  index: number;
  total: number;
  selected: boolean;
  replacementChoices: ModelChoice[];
  ports: SettingsPorts;
  onEdit: (modelId?: string) => void;
}): ReactElement {
  const draft = model;
  const [busy, setBusy] = useState(false);
  const [testOpen, setTestOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const deleteOpener = useRef<HTMLButtonElement | null>(null);
  const [replacementId, setReplacementId] = useState(
    replacementChoices[0] === undefined
      ? ""
      : modelSelectionId(
          replacementChoices[0].selection.providerId,
          replacementChoices[0].selection.modelId,
        ),
  );
  const [testResult, setTestResult] = useState<string>();

  /** 即时设置也等待权威写入，失败可重试且不会把本地状态伪装为已保存。 */
  const mutateModel = async (action: () => Promise<unknown>): Promise<void> => {
    setBusy(true);
    try {
      await action();
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, "模型设置未保存，请重试"));
    } finally {
      setBusy(false);
    }
  };

  /** 验证操作由用户确认后触发，只展示脱敏响应模型和耗时。 */
  const testModel = async (): Promise<void> => {
    setBusy(true);
    setTestResult(undefined);
    try {
      const result = await ports.onTestModel(providerId, model.modelId);
      setTestResult(`${result.responseModel} · ${result.latencyMs} ms`);
      toast.success("模型验证通过");
      setTestOpen(false);
    } catch {
      setTestResult("验证失败，请检查连接信息和模型标识后重试。");
      toast.error("模型验证失败");
    } finally {
      setBusy(false);
    }
  };

  /** 删除默认模型前显式选择替代身份，避免默认选择悬空。 */
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
    <article
      className="ja-models-row"
      data-setting-id={`model-${providerId}-${model.modelId}`}
      data-setting-search={`${draft.name} ${draft.model} ${Object.keys(draft.reasoningLevelMap).join(" ")}`}
      tabIndex={-1}
    >
      <button
        type="button"
        className="ja-models-summary"
        onClick={() => onEdit(model.modelId)}
        aria-label={`编辑模型 ${draft.model} ${draft.name !== draft.model ? draft.name : ""}`}
      >
        <span className="ja-models-identity">
          <strong>{draft.model}</strong>
          {draft.name !== draft.model ? <small>{draft.name}</small> : null}
          <small>上下文 {draft.capabilities.contextWindowTokens.toLocaleString()} tokens</small>
        </span>
        <ChevronRight size={15} aria-hidden="true" />
      </button>
      <div className="ja-models-row-actions">
        {selected ? (
          <span className="ja-models-default">
            <Check size={13} aria-hidden="true" />
            当前默认
          </span>
        ) : (
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={busy}
            onClick={() =>
              void mutateModel(() =>
                ports.onDefaultSelectionChange({
                  providerId,
                  modelId: model.modelId,
                  reasoningLevel: model.defaultReasoningLevel,
                }),
              )
            }
          >
            设为默认
          </Button>
        )}
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
        <ObjectActions
          label={`模型 ${model.model} `}
          index={index}
          total={total}
          busy={busy}
          canDelete={total > 1}
          onMove={(direction) =>
            void mutateModel(() => ports.onMoveModel(providerId, model.modelId, direction))
          }
          onDelete={(trigger) => {
            deleteOpener.current = trigger;
            setDeleteOpen(true);
          }}
        />
      </div>
      {testResult === undefined ? null : (
        <p className="ja-settings-feedback" role="status">
          {testResult}
        </p>
      )}
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
                <Button variant="secondary">取消</Button>
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
          <AlertDialog.Content
            className="ja-settings-confirm-dialog"
            onCloseAutoFocus={(event) => {
              // 菜单项已卸载，取消确认时明确回到仍存在的对象菜单入口。
              if (deleteOpener.current?.isConnected) {
                event.preventDefault();
                deleteOpener.current.focus();
              }
            }}
          >
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
                <Button variant="secondary">取消</Button>
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
    </article>
  );
}

/** 连接摘要和模型分组区分对象层级，配置仍由同一 sheet 保存以保持聚合一致。 */
function ProviderCard({
  provider,
  index,
  total,
  defaultSelection,
  replacementChoices,
  ports,
  onEdit,
}: {
  provider: ProviderProjection;
  index: number;
  total: number;
  defaultSelection: DefaultModelSelection | null;
  replacementChoices: ModelChoice[];
  ports: SettingsPorts;
  onEdit: (modelId?: string) => void;
}): ReactElement {
  const providerChoices = replacementChoices.filter(
    (choice) => choice.selection.providerId !== provider.providerId,
  );
  const [busy, setBusy] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const deleteOpener = useRef<HTMLButtonElement | null>(null);
  /** 排序失败保留原列表并允许重试，避免未处理的异步拒绝丢失用户反馈。 */
  const moveProvider = async (direction: -1 | 1): Promise<void> => {
    setBusy(true);
    try {
      await ports.onMoveProvider(provider.providerId, direction);
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, "供应商排序未保存，请重试"));
    } finally {
      setBusy(false);
    }
  };
  const [replacementId, setReplacementId] = useState(
    providerChoices[0] === undefined
      ? ""
      : modelSelectionId(
          providerChoices[0].selection.providerId,
          providerChoices[0].selection.modelId,
        ),
  );
  /** Provider 删除确认携带默认模型替代项，保持既有恢复语义。 */
  const removeProvider = async (event: React.MouseEvent<HTMLButtonElement>): Promise<void> => {
    event.preventDefault();
    const replacement =
      providerChoices.find(
        (choice) =>
          modelSelectionId(choice.selection.providerId, choice.selection.modelId) === replacementId,
      )?.selection ?? null;
    setBusy(true);
    try {
      await ports.onDeleteProvider(
        provider.providerId,
        defaultSelection?.providerId === provider.providerId ? replacement : null,
      );
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
      className="ja-settings-provider-editor ja-models-provider"
      data-setting-id={`provider-${provider.providerId}`}
      data-setting-search={`${provider.name} ${provider.api} ${provider.baseUrl}`}
      tabIndex={-1}
    >
      <div className="ja-settings-provider-overview">
        <div className="ja-settings-provider-title">
          <strong>{provider.name}</strong>
          <div className="ja-settings-provider-metadata">
            <span>
              {apiOptions.find((option) => option.value === provider.api)?.label ?? provider.api}
            </span>
            <span>{provider.credentialConfigured ? "凭据已配置" : "凭据未配置"}</span>
          </div>
        </div>
        <div className="ja-settings-card-actions">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            aria-label="编辑供应商"
            onClick={() => onEdit()}
          >
            <Pencil aria-hidden="true" />
            编辑供应商
          </Button>
          <ObjectActions
            label={`供应商 ${provider.name} `}
            index={index}
            total={total}
            busy={busy}
            onMove={(direction) => void moveProvider(direction)}
            onDelete={(trigger) => {
              deleteOpener.current = trigger;
              setDeleteOpen(true);
            }}
          />
        </div>
      </div>
      <section className="ja-settings-model-list" aria-label={`${provider.name} 模型`}>
        <div className="ja-settings-subsection-heading">
          <h3>
            模型 <span className="ja-models-count">{provider.models.length}</span>
          </h3>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => onEdit(provider.models[0]?.modelId)}
          >
            管理模型
            <ChevronRight size={14} aria-hidden="true" />
          </Button>
        </div>
        <p className="ja-models-hint">选择模型名称可编辑配置，默认模型用于新对话。</p>
        <div className="ja-models-group">
          {provider.models.length === 0 ? (
            <p className="ja-settings-empty">此 Provider 尚无模型</p>
          ) : (
            provider.models.map((model, modelIndex) => (
              <ModelEditor
                key={model.modelId}
                providerId={provider.providerId}
                onEdit={onEdit}
                model={model}
                index={modelIndex}
                total={provider.models.length}
                selected={
                  defaultSelection?.providerId === provider.providerId &&
                  defaultSelection.modelId === model.modelId
                }
                replacementChoices={replacementChoices.filter(
                  (choice) =>
                    choice.selection.providerId !== provider.providerId ||
                    choice.selection.modelId !== model.modelId,
                )}
                ports={ports}
              />
            ))
          )}
        </div>
      </section>
      <AlertDialog.Root open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="ja-settings-dialog-overlay" />
          <AlertDialog.Content
            className="ja-settings-confirm-dialog"
            onCloseAutoFocus={(event) => {
              // 供应商菜单与确认框跨 Portal，恢复到持久入口而不是已卸载的菜单项。
              if (deleteOpener.current?.isConnected) {
                event.preventDefault();
                deleteOpener.current.focus();
              }
            }}
          >
            <AlertDialog.Title>删除 {provider.name}？</AlertDialog.Title>
            <AlertDialog.Description>
              将删除该 Provider 及其模型，但不会清除凭据。
            </AlertDialog.Description>
            {defaultSelection?.providerId === provider.providerId ? (
              providerChoices.length === 0 ? (
                <p className="ja-settings-feedback">删除后将返回首次配置。</p>
              ) : (
                <Field id={`${provider.providerId}-replacement`} label="替代默认模型">
                  <SettingsSelect
                    id={`${provider.providerId}-replacement`}
                    value={replacementId}
                    options={providerChoices.map((choice) => ({
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
                <Button variant="secondary">取消</Button>
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

/**
 * 新增、后续添加模型和编辑连接共用一张 sheet，保存时提交完整 Provider 聚合，
 * 从而保留 defaults、网络配置与所有模型稳定 ID。
 */
function ProviderEditorSheet({
  open,
  provider,
  initialModelId,
  ports,
  onOpenChange,
  onSaved,
  onPartialSaved,
}: {
  open: boolean;
  provider?: ProviderProjection;
  initialModelId?: string;
  ports: SettingsPorts;
  onOpenChange: (open: boolean) => void;
  onSaved: (providerId: string) => void;
  onPartialSaved: (providerId: string) => void;
}): ReactElement {
  const isNew = provider === undefined;
  const [draft, setDraft] = useState<ProviderEditorDraft>(() =>
    provider === undefined ? emptyProviderEditorDraft() : draftForProvider(provider),
  );
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const secretRef = useRef<HTMLInputElement>(null);
  const openerRef = useRef(document.activeElement);
  const pendingModelFocusRef = useRef<string | null>(null);
  /** 新行挂载后直接定位真实模型输入，不让用户在长表单里寻找刚添加的项目。 */
  useLayoutEffect(() => {
    const modelId = pendingModelFocusRef.current;
    if (modelId === null) return;
    pendingModelFocusRef.current = null;
    document.getElementById(`model-id-${modelId}`)?.focus();
  }, [draft.models.length]);
  const [removableModelIds, setRemovableModelIds] = useState<Set<string>>(
    () =>
      new Set(
        provider === undefined
          ? [draft.models[0]?.modelId].filter((value): value is string => value !== undefined)
          : [],
      ),
  );
  /** Secret 仅由密码 DOM 持有，关闭和请求结束后立即清空。 */
  const resetSecret = (): void => {
    if (secretRef.current !== null) secretRef.current.value = "";
  };
  /** 忙时禁止关闭和修改，失败时保留非敏感草稿供原地重试。 */
  const changeOpen = (nextOpen: boolean): void => {
    if (!nextOpen && busy) return;
    if (!nextOpen) resetSecret();
    onOpenChange(nextOpen);
  };
  /** 构造完整 ProviderSave，编辑时从快照保留 network 与 agent defaults。 */
  const buildProvider = (): ProviderSave => {
    const source = provider ?? emptyProviderDraft();
    return {
      ...source,
      providerId: draft.providerId,
      name: draft.name.trim(),
      api: draft.api,
      baseUrl: draft.baseUrl.trim(),
      credentialId: draft.credentialId,
      models: draft.models.map((model) => ({
        ...cloneModel(model),
        name: model.name.trim() || model.model.trim(),
        model: model.model.trim(),
      })),
    };
  };
  /** 新增和编辑都由同一保存闭环处理，保留凭据部分成功错误和稳定草稿。 */
  const save = async (): Promise<void> => {
    if (busy) return;
    const validation = validateProviderDraft(draft);
    if (validation !== undefined) {
      setFeedback(validation);
      toast.error(validation);
      return;
    }
    const secret = secretRef.current?.value ?? "";
    if (isNew && secret.length === 0) {
      const message = "请输入 API key / token";
      setFeedback(message);
      toast.error(message);
      return;
    }
    setFeedback(undefined);
    setBusy(true);
    try {
      const next = buildProvider();
      if (isNew) await ports.onCreateProvider(next, secret);
      else await ports.onSaveProvider(next);
      onSaved(next.providerId);
      toast.success(isNew ? "供应商和模型已保存" : "供应商与模型已保存");
      resetSecret();
      onOpenChange(false);
    } catch (error) {
      const code =
        error !== null && typeof error === "object"
          ? (error as { code?: unknown }).code
          : undefined;
      const message =
        code === "provider_saved_credential_failed"
          ? "Provider 已保存，但密钥保存失败，请重新输入后重试"
          : settingsMutationErrorMessage(error, isNew ? "服务商添加失败" : "服务商配置保存失败");
      setFeedback(message);
      toast.error(message);
      if (code === "provider_saved_credential_failed") {
        setRemovableModelIds(new Set());
        onPartialSaved(draft.providerId);
      }
    } finally {
      resetSecret();
      setBusy(false);
    }
  };
  /** 凭据与配置共用表单忙状态，避免密钥尚在写入时关闭窗口或提交另一份模型草稿。 */
  const replaceCredential: SettingsPorts["onReplaceCredential"] = async (reference, secret) => {
    setBusy(true);
    try {
      await ports.onReplaceCredential(reference, secret);
    } finally {
      setBusy(false);
    }
  };
  /** 清除凭据保留独立确认语义，但同样保护尚未保存的供应商草稿不被导航丢弃。 */
  const clearCredential: SettingsPorts["onClearCredential"] = async (reference) => {
    setBusy(true);
    try {
      await ports.onClearCredential(reference);
    } finally {
      setBusy(false);
    }
  };
  /** 新增模型只创建本地稳定草稿，直到供应商保存成功才进入持久化聚合。 */
  const addModel = (): void => {
    const model = createModelDraft();
    pendingModelFocusRef.current = model.modelId;
    setRemovableModelIds((current) => new Set(current).add(model.modelId));
    setDraft((current) => ({ ...current, models: [...current.models, model] }));
  };
  /** 按索引替换模型行，保持行的稳定 ID 和其它模型顺序。 */
  const updateModel = (index: number, model: ProviderModelSave): void =>
    setDraft((current) => ({
      ...current,
      models: current.models.map((item, itemIndex) => (itemIndex === index ? model : item)),
    }));
  /** 仅移除本次草稿新增的模型，既有模型删除仍走主列表确认流程。 */
  const removeModel = (index: number): void => {
    const model = draft.models[index];
    if (model === undefined || !removableModelIds.has(model.modelId)) return;
    pendingModelFocusRef.current =
      draft.models[index + 1]?.modelId ?? draft.models[index - 1]?.modelId ?? null;
    setRemovableModelIds((ids) => {
      const next = new Set(ids);
      next.delete(model.modelId);
      return next;
    });
    setDraft((current) => ({
      ...current,
      models: current.models.filter((item) => item.modelId !== model.modelId),
    }));
  };
  return (
    <Dialog modal open={open} onOpenChange={changeOpen}>
      <DialogContent
        className="ja-settings-sheet ja-settings-provider-sheet"
        overlayClassName="ja-settings-dialog-overlay"
        aria-describedby="provider-editor-description"
        onOpenAutoFocus={(event) => {
          // 从模型进入时直接定位该行，避免长表单把用户送回连接设置。
          if (initialModelId === undefined) return;
          const input = document.getElementById(`model-id-${initialModelId}`);
          if (input !== null) {
            event.preventDefault();
            input.focus();
          }
        }}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          const opener = openerRef.current;
          if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
        }}
      >
        <div className="ja-settings-dialog-header">
          <div>
            <DialogTitle className="ja-settings-dialog-title">
              {isNew ? "新增供应商" : "编辑供应商"}
            </DialogTitle>
            <DialogDescription
              id="provider-editor-description"
              className="ja-settings-dialog-description"
            >
              连接一次，添加多个模型。
            </DialogDescription>
          </div>
          <DialogClose asChild>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-label="关闭供应商编辑"
              disabled={busy}
            >
              <X aria-hidden="true" />
            </Button>
          </DialogClose>
        </div>
        <div className="ja-settings-sheet-body ja-provider-editor-body">
          <section
            className="ja-provider-connection-section"
            aria-labelledby="provider-connection-heading"
          >
            <div className="ja-provider-section-heading">
              <div>
                <h3 id="provider-connection-heading">连接信息</h3>
                <p>先连接供应商，再为它添加一个或多个模型。</p>
              </div>
            </div>
            <fieldset disabled={busy} className="ja-provider-connection-fields">
              <div className="ja-settings-form-grid">
                <Field id="provider-name" label="供应商名称">
                  <input
                    id="provider-name"
                    className="ja-settings-input"
                    value={draft.name}
                    placeholder="例如：公司网关"
                    onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                    required
                  />
                </Field>
                <Field id="provider-api" label="API 规范">
                  <SettingsSelect
                    id="provider-api"
                    value={draft.api}
                    options={apiOptions}
                    onValueChange={(value) => setDraft({ ...draft, api: value as ProviderApi })}
                  />
                </Field>
                <Field id="provider-url" label="Base URL">
                  <input
                    id="provider-url"
                    className="ja-settings-input"
                    value={draft.baseUrl}
                    placeholder="https://api.example.com/v1"
                    onChange={(event) => setDraft({ ...draft, baseUrl: event.target.value })}
                    required
                  />
                </Field>
                {isNew ? (
                  <Field id="provider-secret" label="API key / token">
                    <input
                      ref={secretRef}
                      id="provider-secret"
                      className="ja-settings-input"
                      type="password"
                      autoComplete="new-password"
                      placeholder="输入后保存，不会回显"
                    />
                  </Field>
                ) : (
                  <CredentialVaultEditor
                    reference={draft.credentialId}
                    configured={provider?.credentialConfigured ?? false}
                    onReplaceCredential={replaceCredential}
                    onClearCredential={clearCredential}
                  />
                )}
              </div>
            </fieldset>
          </section>
          <section className="ja-provider-models-section" aria-labelledby="provider-models-heading">
            <div className="ja-provider-section-heading">
              <div>
                <h3 id="provider-models-heading">模型</h3>
                <p>使用供应商提供的模型标识，额度可分别调整。</p>
              </div>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={addModel}
                disabled={busy}
              >
                <Plus aria-hidden="true" />
                添加模型
              </Button>
            </div>
            <div className="ja-provider-model-list">
              {draft.models.map((model, index) => (
                <ProviderModelDraftRow
                  key={model.modelId}
                  model={model}
                  baseUrl={draft.baseUrl}
                  index={index}
                  canRemove={removableModelIds.has(model.modelId)}
                  disabled={busy}
                  onChange={(next) => updateModel(index, next)}
                  onRemove={() => removeModel(index)}
                />
              ))}
            </div>
          </section>
        </div>
        <div className="ja-settings-sheet-footer">
          {feedback === undefined ? null : (
            <p className="ja-settings-feedback ja-provider-editor-feedback" role="alert">
              {feedback}
            </p>
          )}
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => changeOpen(false)}
            disabled={busy}
          >
            取消
          </Button>
          <Button
            type="button"
            variant="primary"
            size="sm"
            loading={busy}
            onClick={() => void save()}
          >
            {isNew ? "保存供应商" : "保存更改"}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** 主从选择保留供应商上下文，新增和编辑沿用同一草稿路由，不增加配置写入来源。 */
export function ModelsSection({
  providers,
  defaultSelection,
  focusRequest,
  ports,
}: {
  providers: SettingsSnapshot["providers"];
  defaultSelection: SettingsSnapshot["defaultSelection"];
  snapshotRevision: number;
  focusRequest?: { providerId: string; modelId?: string; requestId: number };
  ports: SettingsPorts;
}): ReactElement {
  const [selectedProviderId, setSelectedProviderId] = useState<string | undefined>(
    defaultSelection?.providerId ?? providers[0]?.providerId,
  );
  const [sheetProviderId, setSheetProviderId] = useState<string | null>(null);
  const [sheetModelId, setSheetModelId] = useState<string>();
  const consumedFocusRequestRef = useRef<number | null>(null);
  const [dismissedFocusRequestId, setDismissedFocusRequestId] = useState<number>();
  const requestedProviderId =
    focusRequest !== undefined && dismissedFocusRequestId !== focusRequest.requestId
      ? focusRequest.providerId
      : undefined;
  const selectedProvider =
    providers.find(
      (provider) => provider.providerId === (requestedProviderId ?? selectedProviderId),
    ) ?? providers[0];
  const sheetProvider =
    sheetProviderId === null || sheetProviderId === "__new__"
      ? undefined
      : providers.find((provider) => provider.providerId === sheetProviderId);
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
  /** 搜索请求先切换主从选择，再在下一次布局提交后滚动和聚焦目标入口。 */
  useLayoutEffect(() => {
    if (focusRequest === undefined) return;
    if (consumedFocusRequestRef.current === focusRequest.requestId) return;
    const provider = providers.find((item) => item.providerId === focusRequest.providerId);
    if (provider === undefined) return;
    if (selectedProvider?.providerId !== provider.providerId) return;
    focusModelSearchTarget(provider.providerId, focusRequest.modelId);
    consumedFocusRequestRef.current = focusRequest.requestId;
  }, [focusRequest, providers, selectedProvider]);
  /** 保存成功或凭据部分成功后保留 Provider 选择，关闭当前 sheet。 */
  const handleSaved = (providerId: string): void => {
    setSelectedProviderId(providerId);
    setSheetProviderId(null);
  };
  return (
    <div className="ja-settings-section ja-models-page">
      <SectionHeader
        title="模型与供应商"
        description="连接供应商，选择适合你的模型。"
        action={
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => {
              setSheetModelId(undefined);
              setSheetProviderId("__new__");
            }}
          >
            <Plus aria-hidden="true" />
            新增供应商
          </Button>
        }
      />
      <div className="ja-settings-provider-layout">
        <aside className="ja-settings-provider-sidebar" aria-label="Provider 列表">
          <div className="ja-models-sidebar-heading">
            供应商 <span>{providers.length}</span>
          </div>
          {providers.map((provider) => (
            <button
              key={provider.providerId}
              type="button"
              className={`ja-settings-provider-master${provider.providerId === selectedProvider?.providerId ? " is-selected" : ""}`}
              onClick={() => {
                setSelectedProviderId(provider.providerId);
                if (focusRequest !== undefined) {
                  setDismissedFocusRequestId(focusRequest.requestId);
                }
              }}
              aria-pressed={provider.providerId === selectedProvider?.providerId}
            >
              <span className="ja-models-provider-icon">
                <Server size={17} aria-hidden="true" />
              </span>
              <span className="ja-models-provider-label">
                <strong>{provider.name}</strong>
                <small>{provider.models.length} 个模型</small>
              </span>
            </button>
          ))}
          {providers.length === 0 ? <p className="ja-settings-empty">尚未配置 Provider</p> : null}
        </aside>
        <div className="ja-settings-provider-detail">
          {selectedProvider === undefined ? (
            <div className="ja-provider-empty-state">
              <strong>从一个供应商开始</strong>
              <span>添加连接信息后，可在同一处管理多个模型和预算。</span>
            </div>
          ) : (
            <ProviderCard
              key={selectedProvider.providerId}
              provider={selectedProvider}
              index={providers.indexOf(selectedProvider)}
              total={providers.length}
              defaultSelection={defaultSelection}
              replacementChoices={modelChoices}
              ports={ports}
              onEdit={(modelId) => {
                setSheetModelId(modelId);
                setSheetProviderId(selectedProvider.providerId);
              }}
            />
          )}
        </div>
      </div>
      <ProviderEditorSheet
        key={sheetProviderId ?? "closed"}
        open={sheetProviderId !== null}
        provider={sheetProvider}
        initialModelId={sheetModelId}
        ports={ports}
        onOpenChange={(open) => {
          if (!open) setSheetProviderId(null);
        }}
        onSaved={handleSaved}
        onPartialSaved={(providerId) => setSelectedProviderId(providerId)}
      />
    </div>
  );
}
