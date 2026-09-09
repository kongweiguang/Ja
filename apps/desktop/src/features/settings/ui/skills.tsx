// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { CircleAlert, FileCode2 } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import type { SkillProjection, SkillSource } from "../domain/types";
import type { SettingsPorts } from "../application/ports";
import { SectionHeader, settingsMutationErrorMessage, sourceLabels, SwitchField } from "./shared";

const skillSources: ReadonlyArray<{ source: SkillSource; location: string }> = [
  { source: "builtin", location: "随 Ja 提供" },
  { source: "user", location: "~/.agents/skills" },
  { source: "ja", location: "~/.ja/skills" },
  { source: "project", location: ".agents/skills" },
];

/**
 * Skills 只投影 Ja Kernel Repository，并仅暴露真实 Toggle 能力，不暗示 Installer 或 Marketplace。
 */
export function SkillsSection({
  skills,
  onToggleSkill,
}: {
  skills: SkillProjection[];
  onToggleSkill: SettingsPorts["onToggleSkill"];
}): React.ReactElement {
  const [pending, setPending] = useState<string>();
  const [feedback, setFeedback] = useState<string>();
  const grouped = useMemo(
    () =>
      skillSources.map(({ source, location }) => ({
        source,
        location,
        skills: skills.filter((skill) => skill.source === source),
      })),
    [skills],
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
      <SectionHeader title="Skills" />
      <div className="ja-settings-skill-groups">
        {grouped.map(({ source, location, skills: sourceSkills }) => (
          <section
            key={source}
            className="ja-settings-subsection"
            aria-labelledby={`skill-source-${source}`}
          >
            <div className="ja-settings-subheading">
              <div className="ja-settings-skill-source-title">
                <h3 id={`skill-source-${source}`}>{sourceLabels[source]}</h3>
                <span>{location}</span>
              </div>
              <span className="ja-settings-skill-count">{sourceSkills.length}</span>
            </div>
            {sourceSkills.length === 0 ? (
              <div className="ja-settings-skill-empty">暂无 Skills</div>
            ) : (
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
                        {skill.status === "reloading" || skill.status === "error" ? (
                          <span className={`ja-settings-status-text is-${skill.status}`}>
                            {skill.status === "reloading" ? "重新加载中" : "加载失败"}
                            {skill.lastGood === undefined ? "" : ` · 最近成功 ${skill.lastGood}`}
                          </span>
                        ) : null}
                        {skill.error === undefined ? null : (
                          <p className="ja-settings-error" role="alert">
                            <CircleAlert size={14} aria-hidden="true" />
                            {skill.error}
                          </p>
                        )}
                      </div>
                    </div>
                    <div className="ja-settings-skill-actions">
                      <SwitchField
                        id={`skill-toggle-${skill.id}`}
                        label={skill.enabled ? "已启用" : "已停用"}
                        checked={skill.enabled}
                        onCheckedChange={(checked) => void toggle(skill, checked)}
                        disabled={pending === skill.id}
                      />
                    </div>
                  </article>
                ))}
              </div>
            )}
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
