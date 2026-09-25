// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { CircleAlert, FileCode2, Trash2 } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { toast } from "sonner";
import type { SettingsPorts } from "../application/ports";
import type { SkillProjection } from "../domain/types";
import { SectionHeader, settingsMutationErrorMessage, sourceLabels, SwitchField } from "./shared";
import "./skills-about.css";

type SkillScope = "user" | "project";

/** 来源顺序与 App Server 发现顺序一致，界面只用它解释覆盖而不决定运行时路由。 */
function priority(source: SkillProjection["source"]): number {
  return source === "project" ? 3 : source === "ja" ? 2 : 1;
}

/** 全局和所选项目分别展示可管理项，开关始终写入条目所属配置。 */
export function SkillsSection({
  globalSkills,
  projectSkills,
  projectAvailable,
  projectUnavailableMessage = "选择可信项目后管理其 Skills",
  projectPicker,
  disabled = false,
  onBusyChange,
  onToggleSkill,
}: {
  globalSkills: SkillProjection[];
  projectSkills?: SkillProjection[];
  projectAvailable: boolean;
  projectUnavailableMessage?: string;
  projectPicker?: ReactNode;
  disabled?: boolean;
  onBusyChange?: (busy: boolean) => void;
  onToggleSkill: SettingsPorts["onToggleSkill"];
}): React.ReactElement {
  const [pending, setPending] = useState<string>();
  const [feedback, setFeedback] = useState<string>();
  const interactionDisabled = disabled || pending !== undefined;
  /** 项目筛选在写入与权威回读完成前保持锁定。 */
  useEffect(() => {
    onBusyChange?.(pending !== undefined);
    return () => onBusyChange?.(false);
  }, [onBusyChange, pending]);
  const allSkills = [...globalSkills, ...(projectSkills ?? [])];

  /** 保存和权威回读期间锁定同页开关，失败后保留原投影供重试。 */
  const update = async (
    skill: SkillProjection,
    enabled: boolean,
    scope: SkillScope,
  ): Promise<void> => {
    setPending(scope + ":" + skill.id);
    setFeedback(undefined);
    try {
      await onToggleSkill(skill.id, enabled, scope);
      toast.success(skill.name + " 已" + (enabled ? "启用" : "停用"));
    } catch (error) {
      const message = settingsMutationErrorMessage(error, "Skill 状态修改失败。");
      setFeedback(message);
      toast.error(message);
    } finally {
      setPending(undefined);
    }
  };

  /** 移除缺失文件的停用记录等价于重新启用；文件以后出现时将按默认规则可用。 */
  const removeRecord = async (skill: SkillProjection, scope: SkillScope): Promise<void> => {
    await update(skill, true, scope);
  };

  /** 两组使用同一行结构，但事件携带明确作用域，避免筛选切换后写错配置。 */
  const renderList = (skills: SkillProjection[], scope: SkillScope): React.ReactElement =>
    skills.length === 0 ? (
      <div className="ja-skill-empty" role="status">
        {scope === "user" ? "未发现全局 Skills" : "未发现项目 Skills"}
      </div>
    ) : (
      <div className="ja-skill-list" aria-label={scope === "user" ? "全局 Skills" : "项目 Skills"}>
        {skills.map((skill) => {
          const winner = allSkills.find(
            (other) =>
              other.name === skill.name &&
              other.enabled &&
              !other.missing &&
              priority(other.source) > priority(skill.source),
          );
          return (
            <article
              className="ja-skill-row"
              key={skill.id}
              data-setting-id={"skill-" + scope + "-" + skill.id}
              data-setting-search={
                skill.name + " " + skill.description + " " + sourceLabels[skill.source] + " Skill"
              }
            >
              <span className="ja-skill-row-icon" aria-hidden="true">
                <FileCode2 size={16} />
              </span>
              <div className="ja-skill-row-copy">
                <div className="ja-skill-row-title">
                  <h3>{skill.name}</h3>
                  <span>{sourceLabels[skill.source]}</span>
                </div>
                {skill.description.trim() !== "" ? <p>{skill.description}</p> : null}
                {skill.enabled && winner !== undefined ? (
                  <p role="status">当前由{sourceLabels[winner.source]}同名 Skill 覆盖</p>
                ) : null}
                {skill.missing ? (
                  <p className="ja-skill-missing" role="status">
                    <CircleAlert size={14} aria-hidden="true" />
                    文件已移除，停用记录仍保留
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
                    aria-label={"移除 " + skill.name + " 的停用记录"}
                    title="移除停用记录"
                    onClick={() => void removeRecord(skill, scope)}
                    disabled={interactionDisabled}
                  >
                    <Trash2 size={15} aria-hidden="true" />
                    <span>移除记录</span>
                  </button>
                ) : (
                  <SwitchField
                    id={"skill-toggle-" + scope + "-" + skill.id}
                    label={skill.name + "：" + (skill.enabled ? "已启用" : "已停用")}
                    checked={skill.enabled}
                    onCheckedChange={(checked) => void update(skill, checked, scope)}
                    disabled={interactionDisabled}
                    hideLabel
                  />
                )}
              </div>
            </article>
          );
        })}
      </div>
    );

  return (
    <div className="ja-settings-section">
      <SectionHeader title="Skills" description="发现后默认可用，需要时由 Ja 读取完整指令。" />
      <section className="ja-capability-group" aria-label="全局 Skills">
        <div className="ja-capability-group-header">
          <h3>全局</h3>
        </div>
        {renderList(globalSkills, "user")}
      </section>
      <section className="ja-capability-group" aria-label="项目 Skills">
        <div className="ja-capability-group-header">
          <div className="ja-capability-group-title">
            <h3>项目</h3>
            {projectPicker}
          </div>
        </div>
        {projectAvailable ? (
          renderList(projectSkills ?? [], "project")
        ) : (
          <div className="ja-skill-empty" role="status">
            {projectUnavailableMessage}
          </div>
        )}
      </section>
      {feedback === undefined ? null : (
        <p className="ja-settings-feedback" role="status">
          {feedback}
        </p>
      )}
    </div>
  );
}
