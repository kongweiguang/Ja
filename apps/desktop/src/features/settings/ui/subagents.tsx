// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/shared/ui/primitives";
import type { ReasoningLevel } from "@/shared/settings/types";
import type { SettingsPorts } from "../application/ports";
import type { SettingsSnapshot, SubagentSettings } from "../domain/types";
import {
  Field,
  SectionHeader,
  SettingsGroup,
  settingsMutationErrorMessage,
  SettingsGroupedSelect,
  SettingsSelect,
  SwitchField,
} from "./shared";

const FOLLOW_PARENT = "__follow_parent__";
const reasoningLevels: ReadonlyArray<{ value: ReasoningLevel; label: string }> = [
  { value: "off", label: "关闭 (off)" },
  { value: "minimal", label: "最少 (minimal)" },
  { value: "low", label: "低 (low)" },
  { value: "medium", label: "中 (medium)" },
  { value: "high", label: "高 (high)" },
  { value: "xhigh", label: "极高 (xhigh)" },
  { value: "max", label: "最大 (max)" },
];

/** 用不可碰撞的内部值表达跟随父任务，避免把 null 传入 Radix Select。 */
function selectionValue(settings: SubagentSettings): string {
  return settings.providerId === null || settings.modelId === null
    ? FOLLOW_PARENT
    : `${settings.providerId}:${settings.modelId}`;
}

/** Select 只会提交已渲染值；非法值直接丢弃，不能静默改成跟随父任务。 */
function parseSelection(
  value: string,
): Pick<SubagentSettings, "providerId" | "modelId"> | undefined {
  if (value === FOLLOW_PARENT) return { providerId: null, modelId: null };
  const separator = value.indexOf(":");
  if (separator <= 0 || separator === value.length - 1) return undefined;
  return { providerId: value.slice(0, separator), modelId: value.slice(separator + 1) };
}

/** 只展示当前模型声明支持的档位；模型默认项用 null 表达而不伪造上游值。 */
function reasoningOptions(
  model: SettingsSnapshot["providers"][number]["models"][number] | undefined,
): ReadonlyArray<{ value: string; label: string }> {
  if (model === undefined) return [];
  return [
    { value: "default", label: "模型默认" },
    ...reasoningLevels.filter((item) => model.reasoningLevelMap[item.value] !== undefined),
  ];
}

/**
 * 子智能体设置只编辑用户级策略；保存由 controller 统一做 CAS 和引用校验，
 * 组件不通过本地乐观状态掩盖失败，也因此能在冲突后直接显示服务端快照。
 */
export function SubagentsSection({
  settings,
  providers,
  onChange,
  onOpenModels,
}: {
  settings: SettingsSnapshot["subagents"];
  providers: SettingsSnapshot["providers"];
  onChange: SettingsPorts["onSubagentSettingsChange"];
  onOpenModels: () => void;
}): React.ReactElement {
  const [pending, setPending] = useState(false);
  const [failedSettings, setFailedSettings] = useState<SubagentSettings>();
  const modelGroups = useMemo(
    () =>
      providers.map((provider) => ({
        label: provider.name,
        options: provider.models.map((model) => ({
          value: `${provider.providerId}:${model.modelId}`,
          label: (
            <span className="ja-settings-model-option">
              <strong>{model.model}</strong>
              {model.name.trim() === model.model.trim() ? null : <small>{model.name}</small>}
            </span>
          ),
        })),
      })),
    [providers],
  );
  const selectedValue = selectionValue(settings);
  const hasAvailableModels = modelGroups.some((group) => group.options.length > 0);
  const hasSelectedModel =
    selectedValue === FOLLOW_PARENT ||
    modelGroups.some((group) => group.options.some((option) => option.value === selectedValue));
  const selectedModel =
    settings.providerId === null || settings.modelId === null
      ? undefined
      : providers
          .find((provider) => provider.providerId === settings.providerId)
          ?.models.find((model) => model.modelId === settings.modelId);
  const selectedReasoningOptions = reasoningOptions(selectedModel);

  /** 所有控件共享同一提交锁，防止连续切换以旧快照覆盖新策略。 */
  const save = async (next: SubagentSettings): Promise<void> => {
    setPending(true);
    setFailedSettings(undefined);
    try {
      await onChange(next);
      toast.success("子智能体设置已保存");
    } catch (error) {
      setFailedSettings(next);
      toast.error(settingsMutationErrorMessage(error, "子智能体设置保存失败"));
    } finally {
      setPending(false);
    }
  };

  return (
    <div
      className="ja-settings-section ja-subagents-section"
      data-setting-id="subagents"
      data-setting-search="子智能体 subagent agent spawn 启用 模型 跟随父任务 思考等级 reasoning"
      tabIndex={-1}
    >
      <SectionHeader title="子智能体" description="允许助手将工作分配给子智能体。" />
      <SettingsGroup
        title="助手行为"
        description={
          settings.enabled
            ? "仅对保存后新建的会话生效。"
            : "当前已关闭；这里的模型会作为以后重新启用时的新会话预设。"
        }
      >
        <SwitchField
          id="subagents-enabled"
          label="启用子智能体"
          checked={settings.enabled}
          onCheckedChange={(enabled) => void save({ ...settings, enabled })}
          disabled={pending}
          settingId="subagents-enabled"
        />
        <Field
          id="subagents-model"
          label="子智能体模型"
          hint="跟随父任务使用与父任务相同的模型；指定模型使用该模型的默认思考设置。"
          layout="row"
        >
          <div className="ja-subagents-model-control">
            {!hasAvailableModels ? (
              <div className="ja-settings-empty ja-subagents-empty">
                <span>尚未配置可用模型。</span>
                <Button type="button" variant="secondary" size="sm" onClick={onOpenModels}>
                  前往模型设置
                </Button>
              </div>
            ) : null}
            <SettingsGroupedSelect
              id="subagents-model"
              value={selectedValue}
              groups={[
                {
                  label: "父任务模型",
                  options: [{ value: FOLLOW_PARENT, label: "跟随父任务" }],
                },
                ...modelGroups,
              ]}
              onValueChange={(value) => {
                const selection = parseSelection(value);
                if (selection === undefined) return;
                if (selection.providerId === null || selection.modelId === null) {
                  void save({ ...settings, ...selection, reasoningLevel: null });
                  return;
                }
                const nextModel = providers
                  .find((provider) => provider.providerId === selection.providerId)
                  ?.models.find((model) => model.modelId === selection.modelId);
                if (nextModel === undefined) return;
                const nextReasoningLevel =
                  settings.reasoningLevel !== null &&
                  nextModel.reasoningLevelMap[settings.reasoningLevel] !== undefined
                    ? settings.reasoningLevel
                    : null;
                void save({ ...settings, ...selection, reasoningLevel: nextReasoningLevel });
              }}
              ariaLabel="子智能体模型"
              ariaDescribedBy="subagents-model-hint"
              disabled={pending}
            />
          </div>
        </Field>
        <div data-setting-id="subagents-reasoning" tabIndex={-1}>
          <Field
            id="subagents-reasoning"
            label="思考等级"
            hint="跟随父任务时沿用父任务的思考等级；指定模型时可选择该模型支持的档位。"
            layout="row"
          >
            {settings.providerId === null || settings.modelId === null ? (
              <span className="ja-settings-readonly" id="subagents-reasoning">
                沿用父任务的思考等级
              </span>
            ) : (
              <SettingsSelect
                id="subagents-reasoning"
                value={settings.reasoningLevel ?? "default"}
                options={selectedReasoningOptions}
                onValueChange={(value) =>
                  void save({
                    ...settings,
                    reasoningLevel: value === "default" ? null : (value as ReasoningLevel),
                  })
                }
                ariaLabel="子智能体思考等级"
                disabled={pending || selectedModel === undefined}
              />
            )}
          </Field>
        </div>
      </SettingsGroup>
      {failedSettings === undefined ? null : (
        <div className="ja-settings-form-actions">
          <p className="ja-settings-feedback" role="status">
            子智能体设置保存失败，请重试。
          </p>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => void save(failedSettings)}
            disabled={pending}
          >
            重试
          </Button>
        </div>
      )}
      {!hasSelectedModel ? (
        <p className="ja-settings-feedback" role="alert">
          当前指定模型已不可用，请在此重新选择模型或前往模型设置。
        </p>
      ) : null}
    </div>
  );
}
