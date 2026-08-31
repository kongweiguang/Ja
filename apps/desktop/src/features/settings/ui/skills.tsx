// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { CircleAlert, FileCode2, Sparkles } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/shared/ui/primitives";
import type { SkillProjection, SkillSource } from "../domain/types";
import type { SettingsPorts } from "../application/ports";
import { SectionHeader, settingsMutationErrorMessage, sourceLabels, SwitchField } from "./shared";

/**
 * Skills 只投影 Ja Kernel Repository，并仅暴露真实 Toggle 能力，不暗示 Installer 或 Marketplace。
 */
export function SkillsSection({
  skills,
  onToggleSkill,
  projectMode = false,
}: {
  skills: SkillProjection[];
  onToggleSkill: SettingsPorts["onToggleSkill"];
  projectMode?: boolean;
}): React.ReactElement {
  const [pending, setPending] = useState<string>();
  const [feedback, setFeedback] = useState<string>();
  const grouped = useMemo(
    () =>
      (Object.keys(sourceLabels) as SkillSource[])
        .map((source) => ({
          source,
          skills: skills.filter(
            (skill) => skill.source === source && (!projectMode || skill.globallyEnabled === true),
          ),
        }))
        .filter((group) => group.skills.length > 0),
    [projectMode, skills],
  );

  /** 可见启用状态必须与原生 Toggle 结果一致，不能保留前端乐观漂移。 */
  const toggle = async (skill: SkillProjection, enabled: boolean): Promise<void> => {
    setPending(skill.id);
    setFeedback(undefined);
    try {
      await onToggleSkill(skill.id, enabled);
      toast.success(`${skill.name} 已${enabled ? "启用" : "停用"}`);
    } catch (error) {
      const message = settingsMutationErrorMessage(error, "Skill 状态修改失败。");
      setFeedback(message);
      toast.error(message);
    } finally {
      setPending(undefined);
    }
  };

  return (
    <div className="ja-settings-section">
      <SectionHeader title={projectMode ? "项目 Skills" : "Skills"} />
      {projectMode ? null : (
        <div className="ja-settings-callout">
          <Sparkles size={16} aria-hidden="true" />
          <span>当前支持内置、用户和工作区来源；不会在后台执行未知安装脚本。</span>
        </div>
      )}
      <div className="ja-settings-skill-groups">
        {grouped.map(({ source, skills: sourceSkills }) => (
          <section
            key={source}
            className="ja-settings-subsection"
            aria-labelledby={`skill-source-${source}`}
          >
            <div className="ja-settings-subheading">
              <h3 id={`skill-source-${source}`}>{sourceLabels[source]}</h3>
              <span>{sourceSkills.length} 项</span>
            </div>
            <div className="ja-settings-skill-list">
              {sourceSkills.map((skill) => (
                <article
                  className="ja-settings-skill-card"
                  data-setting-id={`skill-${skill.id}`}
                  data-setting-search={`${skill.name} ${skill.description} ${sourceLabels[skill.source]} skill 技能 ${skill.enabled ? "启用" : "停用"}`}
                  key={skill.id}
                >
                  <div className="ja-settings-skill-main">
                    <span className="ja-settings-file-icon">
                      <FileCode2 size={16} aria-hidden="true" />
                    </span>
                    <div>
                      <h4>{skill.name}</h4>
                      <p>{skill.description}</p>
                      <span className={`ja-settings-status-text is-${skill.status}`}>
                        {skill.status === "ready"
                          ? "已加载"
                          : skill.status === "disabled"
                            ? "已停用"
                            : skill.status === "reloading"
                              ? "重新加载中"
                              : "加载失败"}
                        {skill.lastGood === undefined ? "" : ` · 最近成功 ${skill.lastGood}`}
                      </span>
                      {skill.error === undefined ? null : (
                        <p className="ja-settings-error" role="alert">
                          <CircleAlert size={14} aria-hidden="true" />
                          {skill.error}
                        </p>
                      )}
                    </div>
                  </div>
                  <div className="ja-settings-skill-actions">
                    {projectMode && skill.projectOverridden ? (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        disabled={pending === skill.id}
                        onClick={() => void toggle(skill, true)}
                      >
                        恢复继承
                      </Button>
                    ) : null}
                    <SwitchField
                      id={`skill-toggle-${skill.id}`}
                      label={
                        projectMode
                          ? skill.enabled
                            ? "继承全局"
                            : "项目停用"
                          : skill.enabled
                            ? "已启用"
                            : "已停用"
                      }
                      checked={skill.enabled}
                      onCheckedChange={(checked) => void toggle(skill, checked)}
                      disabled={pending === skill.id}
                    />
                  </div>
                </article>
              ))}
            </div>
          </section>
        ))}
      </div>
      {feedback === undefined ? null : (
        <p className="ja-settings-feedback" role="status">
          {feedback}
        </p>
      )}
    </div>
  );
}
