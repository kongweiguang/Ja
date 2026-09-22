// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as Tabs from "@radix-ui/react-tabs";
import { CircleAlert, FileCode2, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import type { SettingsPorts } from "../application/ports";
import type { SkillProjection } from "../domain/types";
import { SectionHeader, settingsMutationErrorMessage, sourceLabels, SwitchField } from "./shared";
import "./skills-about.css";

type SkillScope = "user" | "project";

/**
 * Skills 只展示当前选中作用域可实际管理的发现元数据；缺失记录不再伪造成可读资源。
 */
export function SkillsSection({
  globalSkills,
  projectSkills,
  projectAvailable,
  disabled = false,
  onToggleSkill,
}: {
  globalSkills: SkillProjection[];
  projectSkills?: SkillProjection[];
  projectAvailable: boolean;
  disabled?: boolean;
  onToggleSkill: SettingsPorts["onToggleSkill"];
}): React.ReactElement {
  const [scope, setScope] = useState<SkillScope>("user");
  const [pending, setPending] = useState<string>();
  const [feedback, setFeedback] = useState<string>();
  const skills = scope === "project" ? (projectSkills ?? []) : globalSkills;
  // 一次写入会覆盖保存与权威回读两个阶段；锁住整个子面可防止用户在两种作用域间误写。
  const interactionDisabled = disabled || pending !== undefined;

  /** 工作区失信或切换时立即回退全局页，不能把上一项目的引用提交到新项目。 */
  useEffect(() => {
    if (!projectAvailable) setScope("user");
  }, [projectAvailable]);

  /**
   * 服务端回读成功前不乐观更新；失败保留旧值并给出同一控制件可再次操作的恢复路径。
   */
  const update = async (skill: SkillProjection, enabled: boolean): Promise<void> => {
    setPending(skill.id);
    setFeedback(undefined);
    try {
      await onToggleSkill(skill.id, enabled, scope);
      toast.success(`${skill.name} 已${enabled ? "启用" : "停用"}`);
    } catch (error) {
      const message = settingsMutationErrorMessage(error, "Skill 状态修改失败。");
      setFeedback(message);
      toast.error(message);
    } finally {
      setPending(undefined);
    }
  };

  /**
   * 删除记录根据其来源收敛为停用项目引用或撤销项目禁用，避免额外的“清理”持久化通道。
   */
  const removeRecord = async (skill: SkillProjection): Promise<void> => {
    const restoreProjectGlobal = scope === "project" && !skill.id.startsWith("project:");
    await update(skill, restoreProjectGlobal);
  };

  return (
    <div className="ja-settings-section">
      <SectionHeader title="Skills" />
      <Tabs.Root
        className="ja-skill-scope"
        value={scope}
        onValueChange={(value) => setScope(value as SkillScope)}
      >
        <Tabs.List className="ja-skill-scope-tabs" aria-label="Skill 作用域">
          <Tabs.Trigger className="ja-skill-scope-tab" value="user" disabled={interactionDisabled}>
            全局
          </Tabs.Trigger>
          {projectAvailable ? (
            <Tabs.Trigger
              className="ja-skill-scope-tab"
              value="project"
              disabled={interactionDisabled}
            >
              当前项目
            </Tabs.Trigger>
          ) : null}
        </Tabs.List>
        <Tabs.Content className="ja-skill-scope-content" value={scope} forceMount>
          {skills.length === 0 ? (
            <div className="ja-skill-empty" role="status">
              暂无 Skills
            </div>
          ) : (
            <div
              className="ja-skill-list"
              aria-label={scope === "user" ? "全局 Skills" : "当前项目 Skills"}
            >
              {skills.map((skill) => (
                <article
                  className="ja-skill-row"
                  data-setting-id={`skill-${scope}-${skill.id}`}
                  data-setting-search={`${skill.name} ${skill.description} ${sourceLabels[skill.source]} Skill ${skill.missing ? "文件已移除 移除记录" : skill.enabled ? "启用" : "停用"}`}
                  key={skill.id}
                >
                  <span className="ja-skill-row-icon" aria-hidden="true">
                    <FileCode2 size={16} />
                  </span>
                  <div className="ja-skill-row-copy">
                    <div className="ja-skill-row-title">
                      <h3>{skill.name}</h3>
                      <span>{sourceLabels[skill.source]}</span>
                    </div>
                    {skill.description.trim().length > 0 ? <p>{skill.description}</p> : null}
                    {skill.missing ? (
                      <p className="ja-skill-missing" role="status">
                        <CircleAlert size={14} aria-hidden="true" />
                        文件已移除
                      </p>
                    ) : null}
                    {skill.error !== undefined && !skill.missing ? (
                      <p className="ja-settings-error" role="alert">
                        <CircleAlert size={14} aria-hidden="true" />
                        {skill.error}
                      </p>
                    ) : null}
                  </div>
                  <div className="ja-skill-row-actions">
                    {skill.missing ? (
                      <button
                        type="button"
                        className="ja-skill-remove"
                        aria-label={`移除 ${skill.name} 的记录`}
                        title="移除记录"
                        onClick={() => void removeRecord(skill)}
                        disabled={interactionDisabled}
                      >
                        <Trash2 size={15} aria-hidden="true" />
                        <span>移除记录</span>
                      </button>
                    ) : (
                      <SwitchField
                        id={`skill-toggle-${scope}-${skill.id}`}
                        label={`${skill.name}：${skill.enabled ? "已启用" : "已停用"}`}
                        checked={skill.enabled}
                        onCheckedChange={(checked) => void update(skill, checked)}
                        disabled={interactionDisabled}
                        hideLabel
                      />
                    )}
                  </div>
                </article>
              ))}
            </div>
          )}
        </Tabs.Content>
      </Tabs.Root>
      {feedback === undefined ? null : (
        <p className="ja-settings-feedback" role="status">
          {feedback}
        </p>
      )}
    </div>
  );
}
