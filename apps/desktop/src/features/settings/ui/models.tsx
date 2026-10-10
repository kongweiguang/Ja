// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as AlertDialog from "@radix-ui/react-alert-dialog";
import {
  ArrowDown,
  ArrowUp,
  Check,
  ChevronRight,
  Download,
  Eye,
  EyeOff,
  MoreHorizontal,
  Pencil,
  Play,
  Plus,
  Search,
  Server,
  Trash2,
  X,
} from "lucide-react";
import {
  useEffect,
  useEffectEvent,
  useLayoutEffect,
  useRef,
  useState,
  type ReactElement,
} from "react";
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
import { isSafeHttpUrl } from "@/shared/settings/validation";
import type {
  ProjectProviderRuntimeOverride,
  ProviderProjection,
  SettingsSnapshot,
} from "../domain/types";
import type { ModelDiscoveryResult, SettingsPorts } from "../application/ports";
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
  if (!isSafeHttpUrl(draft.baseUrl.trim())) return "请输入无凭据参数的 HTTP 或 HTTPS 地址";
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
 * 统一 sheet 中的模型行。全局与项目范围共享能力编辑，新增声明保守默认关闭并随模型草稿一并保存，
 * 高频标识与预算直接可见，高级终答与思考能力渐进展开，
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
        enabled && level === "medium" && model.defaultReasoningLevel === null
          ? "medium"
          : model.defaultReasoningLevel !== null &&
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
          <Field
            id={`model-output-${model.modelId}`}
            label="单次最大输出 Tokens"
            hint="达到此上限后，Ja 会自动继续当前回复。"
          >
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
          高级能力 <span>结构化终答与思考档位</span>
        </summary>
        <fieldset className="ja-settings-choice-group">
          <legend>终答能力</legend>
          <label>
            <input
              type="checkbox"
              aria-label="支持结构化终答"
              checked={model.capabilities.supportsOutputSchema}
              disabled={disabled}
              onChange={(event) =>
                updateCapabilities({ supportsOutputSchema: event.target.checked })
              }
            />
            支持结构化终答 Schema
          </label>
        </fieldset>
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
  projectScope,
  projectModelOverride,
  isGlobalModel,
  onInheritProjectModel,
  onHideProjectModel,
}: {
  providerId: string;
  model: ProviderModelSave;
  index: number;
  total: number;
  selected: boolean;
  replacementChoices: ModelChoice[];
  ports: SettingsPorts;
  onEdit: (modelId?: string) => void;
  projectScope: boolean;
  projectModelOverride: boolean;
  isGlobalModel: boolean;
  onInheritProjectModel?: SettingsPorts["onInheritProjectModelOverride"];
  onHideProjectModel?: SettingsPorts["onHideProjectModel"];
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
        aria-label={`${projectScope ? "编辑项目模型" : "编辑模型"} ${draft.model} ${draft.name !== draft.model ? draft.name : ""}`}
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
        {projectScope && projectModelOverride && onInheritProjectModel !== undefined ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={() => void mutateModel(() => onInheritProjectModel(providerId, model.modelId))}
          >
            继承全局模型
          </Button>
        ) : null}
        {projectScope && isGlobalModel && onHideProjectModel !== undefined ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={busy || selected}
            title={selected ? "请先将当前项目默认模型切换到其他模型" : undefined}
            onClick={() => void mutateModel(() => onHideProjectModel(providerId, model.modelId))}
          >
            从当前项目隐藏
          </Button>
        ) : null}
        {!projectScope ? (
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
        ) : null}
        {!projectScope ? (
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
        ) : null}
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
  projectScope,
  projectProviderOverride,
  globalProvider,
  onInheritProjectModel,
  onInheritProjectModels,
  onRestoreProjectModel,
  onHideProjectModel,
}: {
  provider: ProviderProjection;
  index: number;
  total: number;
  defaultSelection: DefaultModelSelection | null;
  replacementChoices: ModelChoice[];
  ports: SettingsPorts;
  onEdit: (modelId?: string) => void;
  projectScope: boolean;
  projectProviderOverride: ProjectProviderRuntimeOverride | undefined;
  globalProvider: ProviderProjection | undefined;
  onInheritProjectModel?: SettingsPorts["onInheritProjectModelOverride"];
  onInheritProjectModels?: SettingsPorts["onInheritProjectProviderModels"];
  onRestoreProjectModel?: SettingsPorts["onRestoreProjectModel"];
  onHideProjectModel?: SettingsPorts["onHideProjectModel"];
}): ReactElement {
  const providerChoices = replacementChoices.filter(
    (choice) => choice.selection.providerId !== provider.providerId,
  );
  const [busy, setBusy] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const deleteOpener = useRef<HTMLButtonElement | null>(null);
  /** 项目模型目录操作保留原视图状态，失败时用就地 Toast 提供明确重试反馈。 */
  const mutateProjectCatalog = async (action: () => Promise<unknown>): Promise<void> => {
    setBusy(true);
    try {
      await action();
    } catch (error) {
      toast.error(settingsMutationErrorMessage(error, "项目模型目录未保存，请重试"));
    } finally {
      setBusy(false);
    }
  };
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
          </div>
        </div>
        <div className="ja-settings-card-actions">
          {projectScope ? (
            <>
              <span className="ja-models-hint">连接使用全局设置</span>
              {projectProviderOverride?.models !== undefined ||
              (projectProviderOverride?.removedModelIds?.length ?? 0) > 0 ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={busy || onInheritProjectModels === undefined}
                  onClick={() => void onInheritProjectModels?.(provider.providerId)}
                >
                  继承全局模型目录
                </Button>
              ) : null}
            </>
          ) : (
            <>
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
            </>
          )}
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
        <p className="ja-models-hint">
          {projectScope
            ? "点击模型可编辑当前项目覆盖；未覆盖模型继承全局。项目默认模型用于本项目的新对话。"
            : "选择模型名称可编辑配置，默认模型用于新对话。"}
        </p>
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
                projectScope={projectScope}
                projectModelOverride={
                  projectProviderOverride?.models?.some(
                    (item) => item.modelId === model.modelId,
                  ) === true
                }
                isGlobalModel={
                  globalProvider?.models.some((item) => item.modelId === model.modelId) === true
                }
                onInheritProjectModel={onInheritProjectModel}
                onHideProjectModel={onHideProjectModel}
              />
            ))
          )}
        </div>
      </section>
      {projectScope && (projectProviderOverride?.removedModelIds?.length ?? 0) > 0 ? (
        <section className="ja-settings-model-list" aria-label={`${provider.name} 项目隐藏模型`}>
          <div className="ja-settings-subsection-heading">
            <h3>本项目已隐藏</h3>
          </div>
          <div className="ja-models-group">
            {(projectProviderOverride?.removedModelIds ?? []).map((modelId) => {
              const hiddenModel = globalProvider?.models.find((item) => item.modelId === modelId);
              return hiddenModel === undefined ? null : (
                <div className="ja-models-row" key={modelId}>
                  <span className="ja-models-identity">
                    <strong>{hiddenModel.model}</strong>
                    {hiddenModel.name !== hiddenModel.model ? (
                      <small>{hiddenModel.name}</small>
                    ) : null}
                  </span>
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    disabled={busy || onRestoreProjectModel === undefined}
                    onClick={() =>
                      void mutateProjectCatalog(async () => {
                        await onRestoreProjectModel?.(provider.providerId, modelId);
                      })
                    }
                  >
                    恢复模型
                  </Button>
                </div>
              );
            })}
          </div>
        </section>
      ) : null}
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

/** 目录选择只维护临时筛选和勾选，关闭后丢弃，避免未确认的发现结果污染 Provider 草稿。 */
function ModelCatalogPicker({
  items,
  truncated,
  emptyDirectory,
  onClose,
  onAdd,
  onCloseAutoFocus,
}: {
  items: readonly string[];
  truncated: boolean;
  emptyDirectory: boolean;
  onClose: () => void;
  onAdd: (items: string[]) => void;
  onCloseAutoFocus: (event: Event) => void;
}): ReactElement {
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<Set<string>>(() => new Set(items));
  const searchRef = useRef<HTMLInputElement>(null);
  const visibleItems = items.filter((item) =>
    item.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()),
  );
  const allVisibleSelected =
    visibleItems.length > 0 && visibleItems.every((item) => selected.has(item));
  /** 批量操作仅作用于当前搜索结果，保留筛选之外的选择以免搜索时意外丢失。 */
  const toggleVisible = (): void => {
    setSelected((current) => {
      const next = new Set(current);
      for (const item of visibleItems) {
        if (allVisibleSelected) next.delete(item);
        else next.add(item);
      }
      return next;
    });
  };
  /** 单项勾选使用上游原始 ID 作为身份，不依赖列表索引或显示顺序。 */
  const toggleItem = (item: string, checked: boolean): void => {
    setSelected((current) => {
      const next = new Set(current);
      if (checked) next.add(item);
      else next.delete(item);
      return next;
    });
  };
  return (
    <Dialog
      modal
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent
        className="ja-settings-model-picker"
        overlayClassName="ja-settings-dialog-overlay ja-settings-model-picker-overlay"
        aria-describedby="model-picker-description"
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          searchRef.current?.focus();
        }}
        onCloseAutoFocus={onCloseAutoFocus}
      >
        <div className="ja-settings-dialog-header">
          <div>
            <DialogTitle className="ja-settings-dialog-title">选择要添加的模型</DialogTitle>
            <DialogDescription
              id="model-picker-description"
              className="ja-settings-dialog-description"
            >
              从上游目录选择模型，添加后仍需保存供应商。
            </DialogDescription>
          </div>
          <IconButton
            type="button"
            label="关闭模型选择"
            className="ja-model-picker-close"
            onClick={onClose}
          >
            <X aria-hidden="true" />
          </IconButton>
        </div>
        <div className="ja-model-picker-controls">
          <div className="ja-model-picker-search">
            <Search size={17} aria-hidden="true" />
            <input
              ref={searchRef}
              type="search"
              aria-label="搜索模型"
              placeholder="搜索模型"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </div>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={visibleItems.length === 0}
            onClick={toggleVisible}
          >
            {allVisibleSelected ? "取消全选" : "全选"}
          </Button>
        </div>
        <div className="ja-model-picker-list" role="group" aria-label="可添加模型">
          {items.length === 0 ? (
            <p className="ja-model-picker-empty">
              {emptyDirectory
                ? "上游目录没有返回模型。"
                : truncated
                  ? "前 200 项均已添加。"
                  : "上游模型均已添加。"}
            </p>
          ) : visibleItems.length === 0 ? (
            <p className="ja-model-picker-empty">没有匹配的模型。</p>
          ) : (
            visibleItems.map((item) => (
              <label key={item} className="ja-model-picker-option">
                <input
                  type="checkbox"
                  checked={selected.has(item)}
                  onChange={(event) => toggleItem(item, event.target.checked)}
                />
                <span>{item}</span>
              </label>
            ))
          )}
        </div>
        <div className="ja-model-picker-footer">
          <span role="status" aria-live="polite">
            已选 {selected.size} 个{truncated ? " · 仅显示前 200 项" : ""}
          </span>
          <div className="ja-model-picker-actions">
            <Button type="button" variant="secondary" size="sm" onClick={onClose}>
              取消
            </Button>
            <Button
              type="button"
              variant="primary"
              size="sm"
              disabled={selected.size === 0}
              onClick={() => onAdd(items.filter((item) => selected.has(item)))}
            >
              添加所选
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
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
  projectScope,
  projectOverrideModelIds,
  onOpenChange,
  onSaved,
  onPartialSaved,
}: {
  open: boolean;
  provider?: ProviderProjection;
  initialModelId?: string;
  ports: SettingsPorts;
  projectScope: boolean;
  projectOverrideModelIds: readonly string[];
  onOpenChange: (open: boolean) => void;
  onSaved: (providerId: string) => void;
  onPartialSaved: (providerId: string) => void;
}): ReactElement {
  const isNew = provider === undefined;
  const providerId = provider?.providerId;
  const [draft, setDraft] = useState<ProviderEditorDraft>(() =>
    provider === undefined ? emptyProviderEditorDraft() : draftForProvider(provider),
  );
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [catalogFeedback, setCatalogFeedback] = useState<
    { message: string; tone: "success" | "error" } | undefined
  >();
  const [catalogResult, setCatalogResult] = useState<ModelDiscoveryResult>();
  const [secret, setSecret] = useState("");
  const [secretVisible, setSecretVisible] = useState(false);
  const [secretLoading, setSecretLoading] = useState(!isNew && !projectScope);
  const savedSecretRef = useRef("");
  const savedProviderRef = useRef<ProviderSave | null>(null);
  const openerRef = useRef(document.activeElement);
  const pendingModelFocusRef = useRef<string | null>(null);
  const pendingProviderNameFocusRef = useRef(false);
  const pendingProviderNameFocusOriginRef = useRef<Element | null>(null);
  const pendingSaveFailureFocusRef = useRef(false);
  const catalogFocusAfterConfirmRef = useRef<string | null>(null);
  const discoverButtonId = `provider-model-discover-${draft.providerId}`;
  /**
   * 上游目录只可使用 App Server 已保存的连接，任何协议、端点或 API Key 草稿变化都必须先显式
   * 保存，避免用户误以为本地草稿已经生效，且不把秘密作为目录请求参数。
   */
  const canDiscoverModels =
    !projectScope &&
    provider !== undefined &&
    provider.credentialConfigured &&
    !secretLoading &&
    draft.api === provider.api &&
    draft.baseUrl.trim() === provider.baseUrl.trim() &&
    secret === savedSecretRef.current;
  const catalogReadinessMessage = isNew
    ? "保存供应商后可从上游获取模型。"
    : projectScope
      ? "此处的模型改动只覆盖当前项目；连接与凭据仍继承全局设置。"
      : secretLoading
        ? "正在读取已保存的连接信息。"
        : !provider?.credentialConfigured
          ? "请先保存 API Key 后再从上游获取。"
          : !canDiscoverModels
            ? "连接信息已修改，保存后可再次从上游获取。"
            : "从上游获取后选择模型，保存后生效。";
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
          : projectScope
            ? [...projectOverrideModelIds]
            : [],
      ),
  );
  /** API Key 只留在当前编辑 sheet；关闭、卸载或切换 Provider 时不写入全局状态。 */
  const resetSecret = (): void => {
    savedSecretRef.current = "";
    setSecret("");
    setSecretVisible(false);
  };
  /** 最新端口只承担真实回显能力，端口对象刷新不能重新初始化同一编辑会话或覆盖 Secret 草稿。 */
  const revealProviderCredential = useEffectEvent((requestedProviderId: string) =>
    ports.onRevealProviderCredential(requestedProviderId),
  );
  /**
   * 凭据仅在 Sheet 开启、Provider 身份或作用域变化时初始化；配置保存后的同身份投影刷新不能
   * 回显旧 Key 覆盖待重试 Secret。加载期间禁止保存，关闭或切换后丢弃旧读取的迟到结果。
   */
  useEffect(() => {
    if (!open || isNew || providerId === undefined || projectScope) {
      savedSecretRef.current = "";
      setSecret("");
      setSecretLoading(false);
      return;
    }
    let active = true;
    setSecretLoading(true);
    void revealProviderCredential(providerId)
      .then((value) => {
        if (!active) return;
        const revealed = value ?? "";
        savedSecretRef.current = revealed;
        setSecret(revealed);
      })
      .catch((error: unknown) => {
        if (!active) return;
        const message = settingsMutationErrorMessage(error, "API Key 读取失败");
        setFeedback(message);
        toast.error(message);
      })
      .finally(() => {
        if (active) setSecretLoading(false);
      });
    return () => {
      active = false;
    };
  }, [isNew, open, projectScope, providerId]);
  /**
   * 编辑供应商时连接字段会随凭据读取暂时禁用；解禁后仅在焦点仍停在弹窗初始落点时转向名称字段，
   * 避免异步读取完成后抢走用户已经主动移动的焦点。
   */
  useEffect(() => {
    if (
      !open ||
      projectScope ||
      initialModelId !== undefined ||
      secretLoading ||
      !pendingProviderNameFocusRef.current
    )
      return;
    pendingProviderNameFocusRef.current = false;
    const focusOrigin = pendingProviderNameFocusOriginRef.current;
    pendingProviderNameFocusOriginRef.current = null;
    const nameInput = document.getElementById("provider-name");
    if (nameInput instanceof HTMLInputElement && document.activeElement === focusOrigin)
      nameInput.focus();
  }, [initialModelId, open, projectScope, secretLoading]);
  /** busy 解除后只修复失焦或失效焦点；弹窗内仍可操作的用户选择保持原位。 */
  useEffect(() => {
    if (!open || busy || !pendingSaveFailureFocusRef.current) return;
    pendingSaveFailureFocusRef.current = false;
    const active = document.activeElement;
    const hasOperableFocus =
      active instanceof HTMLElement &&
      active.closest(".ja-settings-provider-sheet") !== null &&
      active.matches("button, input, select, textarea") &&
      !active.matches(":disabled");
    if (!hasOperableFocus) document.getElementById("provider-editor-save")?.focus();
  }, [busy, open]);
  /** 忙时禁止关闭；显式关闭时清理仅驻留本 sheet 的 Secret 与临时发现结果。 */
  const changeOpen = (nextOpen: boolean): void => {
    if (!nextOpen && busy) return;
    if (!nextOpen) {
      resetSecret();
      setCatalogResult(undefined);
    }
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
  /**
   * 凭据与配置分属不同持久化端口；记住已提交的纯配置快照，使凭据失败后的原样重试不重复写配置，
   * 而用户改过草稿时仍先提交新配置。Secret 留在本组件直到成功或取消关闭。失败后仅当 busy 造成焦点失效时恢复保存按钮，
   * 弹窗内仍有效的用户焦点不被覆盖。
   */
  const save = async (): Promise<void> => {
    if (busy) return;
    const validation = validateProviderDraft(draft);
    if (validation !== undefined) {
      setFeedback(validation);
      toast.error(validation);
      return;
    }
    if (isNew && secret.length === 0) {
      const message = "请输入 API key / token";
      setFeedback(message);
      toast.error(message);
      return;
    }
    setFeedback(undefined);
    setBusy(true);
    let next: ProviderSave | undefined;
    try {
      next = buildProvider();
      const savedProvider = savedProviderRef.current;
      const configurationAlreadySaved =
        savedProvider !== null && JSON.stringify(savedProvider) === JSON.stringify(next);
      if (isNew && savedProvider === null) {
        await ports.onCreateProvider(next, secret);
      } else {
        if (!configurationAlreadySaved) {
          await ports.onSaveProvider(next);
          savedProviderRef.current = next;
        }
        if (!projectScope && (isNew || secret !== savedSecretRef.current)) {
          try {
            if (secret === "") await ports.onClearCredential(next.credentialId);
            else await ports.onReplaceCredential(next.credentialId, secret);
          } catch (error) {
            throw Object.assign(
              error instanceof Error ? error : new Error("credential update failed"),
              {
                code: "provider_saved_credential_failed",
              },
            );
          }
        }
      }
      onSaved(next.providerId);
      if (!projectScope) {
        toast.success(isNew ? "供应商和模型已保存" : "供应商与模型已保存");
      }
      resetSecret();
      onOpenChange(false);
    } catch (error) {
      const code =
        error !== null && typeof error === "object"
          ? (error as { code?: unknown }).code
          : undefined;
      const message =
        code === "provider_saved_credential_failed"
          ? "供应商配置已保存，密钥操作未完成。输入已保留，可再次保存重试。"
          : settingsMutationErrorMessage(error, isNew ? "服务商添加失败" : "服务商配置保存失败");
      setFeedback(message);
      toast.error(message);
      pendingSaveFailureFocusRef.current = true;
      if (code === "provider_saved_credential_failed") {
        if (next !== undefined) savedProviderRef.current = next;
        setRemovableModelIds(new Set());
        onPartialSaved(next?.providerId ?? draft.providerId);
      }
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
  /** 发现请求只打开选择弹框；用户未确认前既不生成模型 ID，也不修改供应商草稿。 */
  const discoverModels = async (): Promise<void> => {
    if (provider === undefined || !canDiscoverModels || busy) return;
    setBusy(true);
    setCatalogFeedback(undefined);
    try {
      const result = await ports.onDiscoverModels(provider.providerId);
      setCatalogResult(result);
    } catch (error) {
      const message = settingsMutationErrorMessage(
        error,
        "获取上游模型失败，请检查已保存的连接信息",
      );
      setCatalogFeedback({ message, tone: "error" });
      toast.error(message);
    } finally {
      setBusy(false);
    }
  };
  /** 确认时按当前草稿再次去重，避免重复的上游标识占用新模型身份。 */
  const addDiscoveredModels = (selectedItems: string[]): void => {
    const existing = new Set(draft.models.map((model) => model.model.trim()));
    const additions = selectedItems
      .filter((item) => {
        if (existing.has(item)) return false;
        existing.add(item);
        return true;
      })
      .map((upstream) => {
        const recommendation = getModelBudgetRecommendation(upstream, draft.baseUrl);
        return {
          ...createModelDraft(),
          name: upstream,
          model: upstream,
          capabilities: {
            contextWindowTokens: recommendation.contextWindowTokens,
            maxOutputTokens: recommendation.maxOutputTokens,
            supportsOutputSchema: false,
          },
        };
      });
    if (additions.length === 0) return;
    catalogFocusAfterConfirmRef.current = additions[0]!.modelId;
    setDraft((current) => ({ ...current, models: [...current.models, ...additions] }));
    setRemovableModelIds(
      (current) => new Set([...current, ...additions.map((model) => model.modelId)]),
    );
    setCatalogFeedback({
      message: `已添加 ${additions.length} 个上游模型，保存后生效。`,
      tone: "success",
    });
    setCatalogResult(undefined);
  };
  return (
    <Dialog modal open={open} onOpenChange={changeOpen}>
      <DialogContent
        className="ja-settings-sheet ja-settings-provider-sheet"
        overlayClassName="ja-settings-dialog-overlay"
        aria-describedby="provider-editor-description"
        onOpenAutoFocus={(event) => {
          /** 新增/编辑先落在供应商名称；从模型进入则保留该模型的直接编辑上下文。 */
          const targetId =
            initialModelId === undefined ? "provider-name" : `model-id-${initialModelId}`;
          const input = document.getElementById(targetId);
          if (!(input instanceof HTMLInputElement)) return;
          if (input.matches(":disabled")) {
            if (initialModelId === undefined && !projectScope) {
              pendingProviderNameFocusRef.current = true;
              pendingProviderNameFocusOriginRef.current = document.activeElement;
            }
            event.preventDefault();
            const focusFallback =
              document.querySelector<HTMLElement>('[aria-label="关闭供应商编辑"]');
            focusFallback?.focus();
            if (pendingProviderNameFocusRef.current && focusFallback !== null)
              pendingProviderNameFocusOriginRef.current = focusFallback;
            return;
          }
          event.preventDefault();
          input.focus();
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
              {isNew ? "新增供应商" : projectScope ? "编辑项目模型" : "编辑供应商"}
            </DialogTitle>
            <DialogDescription
              id="provider-editor-description"
              className="ja-settings-dialog-description"
            >
              {projectScope
                ? "模型能力仅覆盖当前项目；连接和凭据保持全局。"
                : "连接一次，添加多个模型。"}
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
          {projectScope ? (
            <section className="ja-provider-connection-section" aria-label="项目模型覆盖范围">
              <div className="ja-provider-section-heading">
                <div>
                  <h3>当前项目的模型覆盖</h3>
                  <p>连接地址、协议和凭据仍由全局设置管理。</p>
                </div>
              </div>
            </section>
          ) : (
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
              <fieldset disabled={busy || secretLoading} className="ja-provider-connection-fields">
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
                  <Field id="provider-secret" label="API Key">
                    <div className="ja-provider-api-key-control">
                      <input
                        id="provider-secret"
                        className="ja-settings-input"
                        type={secretVisible ? "text" : "password"}
                        autoComplete={isNew ? "new-password" : "current-password"}
                        value={secret}
                        placeholder="输入 API Key"
                        onChange={(event) => setSecret(event.target.value)}
                      />
                      <IconButton
                        type="button"
                        label={secretVisible ? "隐藏 API Key" : "显示 API Key"}
                        className="ja-provider-api-key-toggle"
                        disabled={secretLoading}
                        onClick={() => setSecretVisible((visible) => !visible)}
                      >
                        {secretVisible ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                      </IconButton>
                    </div>
                  </Field>
                </div>
              </fieldset>
            </section>
          )}
          <section className="ja-provider-models-section" aria-labelledby="provider-models-heading">
            <div className="ja-provider-section-heading ja-provider-models-heading">
              <div>
                <div className="ja-provider-models-title-line">
                  <h3 id="provider-models-heading">模型</h3>
                  <span
                    className="ja-provider-model-count"
                    aria-label={`已配置 ${draft.models.length} 个模型`}
                  >
                    {draft.models.length}
                  </span>
                </div>
                <p>使用供应商提供的模型标识，额度可分别调整。</p>
              </div>
              <div className="ja-provider-model-actions" role="group" aria-label="模型操作">
                {!projectScope ? (
                  <Button
                    id={discoverButtonId}
                    type="button"
                    variant="primary"
                    size="sm"
                    loading={busy}
                    disabled={busy || !canDiscoverModels}
                    title={
                      canDiscoverModels
                        ? "从已保存的上游供应商获取模型"
                        : "请先保存连接信息和 API Key 后再获取"
                    }
                    onClick={() => void discoverModels()}
                  >
                    <Download aria-hidden="true" />
                    从上游获取
                  </Button>
                ) : null}
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={addModel}
                  disabled={busy}
                >
                  <Plus aria-hidden="true" />
                  手动添加
                </Button>
              </div>
            </div>
            <p
              className={`ja-provider-model-catalog-status${
                catalogFeedback?.tone === "error" ? " is-error" : ""
              }`}
              role={catalogFeedback?.tone === "error" ? "alert" : "status"}
            >
              {catalogFeedback?.message ?? catalogReadinessMessage}
            </p>
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
        {catalogResult !== undefined ? (
          <ModelCatalogPicker
            items={catalogResult.items.filter(
              (item) => !draft.models.some((model) => model.model.trim() === item),
            )}
            truncated={catalogResult.truncated}
            emptyDirectory={catalogResult.items.length === 0}
            onClose={() => setCatalogResult(undefined)}
            onAdd={addDiscoveredModels}
            onCloseAutoFocus={(event) => {
              event.preventDefault();
              const modelId = catalogFocusAfterConfirmRef.current;
              catalogFocusAfterConfirmRef.current = null;
              if (modelId !== null) document.getElementById(`model-id-${modelId}`)?.focus();
              else document.getElementById(discoverButtonId)?.focus();
            }}
          />
        ) : null}
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
            id="provider-editor-save"
            type="button"
            variant="primary"
            size="sm"
            loading={busy}
            onClick={() => void save()}
          >
            {isNew ? "保存供应商" : projectScope ? "保存项目模型" : "保存更改"}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** 主从选择保留供应商上下文，新增和编辑沿用同一草稿路由，不增加配置写入来源。 */
export function ModelsSection({
  providers,
  globalProviders = providers,
  defaultSelection,
  focusRequest,
  ports,
  projectScope = false,
  projectProviderOverrides = [],
}: {
  providers: SettingsSnapshot["providers"];
  globalProviders?: SettingsSnapshot["providers"];
  defaultSelection: SettingsSnapshot["defaultSelection"];
  snapshotRevision: number;
  focusRequest?: { providerId: string; modelId?: string; requestId: number };
  ports: SettingsPorts;
  projectScope?: boolean;
  projectProviderOverrides?: readonly ProjectProviderRuntimeOverride[];
}): ReactElement {
  const [selectedProviderId, setSelectedProviderId] = useState<string | undefined>(
    defaultSelection?.providerId ?? providers[0]?.providerId,
  );
  const [sheetProviderId, setSheetProviderId] = useState<string | null>(null);
  const [sheetModelId, setSheetModelId] = useState<string>();
  const [projectSaveNotice, setProjectSaveNotice] = useState<string>();
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
  /** 项目保存反馈留在页面内短暂展示，避免全局 toast 遮挡设置页标题与搜索入口。 */
  useEffect(() => {
    if (projectSaveNotice === undefined) return;
    const timeoutId = window.setTimeout(() => setProjectSaveNotice(undefined), 5000);
    return () => window.clearTimeout(timeoutId);
  }, [projectSaveNotice]);
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
  /** 保存成功或凭据部分成功后保留 Provider 选择；项目保存结果以内联状态就地反馈。 */
  const handleSaved = (providerId: string): void => {
    setSelectedProviderId(providerId);
    setProjectSaveNotice(projectScope ? "项目模型已保存" : undefined);
    setSheetProviderId(null);
  };
  return (
    <div className="ja-settings-section ja-models-page">
      <SectionHeader
        title="模型与供应商"
        description={
          projectScope
            ? "编辑当前项目的模型能力与默认模型；连接和凭据由全局设置管理。"
            : "连接供应商，选择适合你的模型。"
        }
        action={
          projectScope ? undefined : (
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
          )
        }
      />
      {projectSaveNotice !== undefined ? (
        <p className="ja-settings-feedback" role="status" aria-live="polite">
          {projectSaveNotice}
        </p>
      ) : null}
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
              projectScope={projectScope}
              projectProviderOverride={projectProviderOverrides.find(
                (entry) => entry.providerId === selectedProvider.providerId,
              )}
              globalProvider={globalProviders.find(
                (entry) => entry.providerId === selectedProvider.providerId,
              )}
              onInheritProjectModel={ports.onInheritProjectModelOverride}
              onInheritProjectModels={ports.onInheritProjectProviderModels}
              onRestoreProjectModel={ports.onRestoreProjectModel}
              onHideProjectModel={ports.onHideProjectModel}
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
        projectScope={projectScope}
        projectOverrideModelIds={
          projectProviderOverrides
            .find((entry) => entry.providerId === sheetProviderId)
            ?.models?.map((model) => model.modelId) ?? []
        }
        onOpenChange={(open) => {
          if (!open) setSheetProviderId(null);
        }}
        onSaved={handleSaved}
        onPartialSaved={(providerId) => setSelectedProviderId(providerId)}
      />
    </div>
  );
}
