// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ChevronDown, CircleAlert, FileCode2 } from "lucide-react";
import { useCallback, useLayoutEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import type { SkillProjection, SkillSource } from "../domain/types";
import type { SettingsPorts } from "../application/ports";
import { SectionHeader, settingsMutationErrorMessage, sourceLabels, SwitchField } from "./shared";
import "./skills-about.css";

const skillSources: ReadonlyArray<{ source: SkillSource; location: string }> = [
  { source: "builtin", location: "随 Ja 提供" },
  { source: "user", location: "~/.agents/skills" },
  { source: "ja", location: "~/.ja/skills" },
  { source: "project", location: ".agents/skills" },
];

/** 只有真实折叠溢出时才显示展开入口，并持续监听布局变化避免无效操作。 */
function SkillDescription({
  skillId,
  description,
}: {
  skillId: string;
  description: string;
}): React.ReactElement {
  const [expanded, setExpanded] = useState(false);
  const [canExpand, setCanExpand] = useState(false);
  const paragraphRef = useRef<HTMLParagraphElement>(null);

  /** 仅测量折叠态；展开态的高度不能反过来误判为无需收起。 */
  const measureOverflow = useCallback((): void => {
    const paragraph = paragraphRef.current;
    if (paragraph === null || expanded) return;
    const nextCanExpand = paragraph.scrollHeight > paragraph.clientHeight;
    setCanExpand((current) => (current === nextCanExpand ? current : nextCanExpand));
  }, [expanded]);

  useLayoutEffect(() => {
    measureOverflow();
    const paragraph = paragraphRef.current;
    if (paragraph === null || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measureOverflow);
    observer.observe(paragraph);
    return () => observer.disconnect();
  }, [description, measureOverflow]);

  /** 阅读状态留在组件内，收起后的布局提交会重新验证当前宽度是否仍截断。 */
  const toggleExpanded = (): void => {
    setExpanded((current) => !current);
  };

  return (
    <>
      <p
        ref={paragraphRef}
        id={`skill-description-${skillId}`}
        className={expanded ? "ja-skill-description is-expanded" : "ja-skill-description"}
      >
        {description}
      </p>
      {description.trim().length > 0 && (canExpand || expanded) ? (
        <button
          type="button"
          className="ja-skill-description-toggle"
          aria-expanded={expanded}
          aria-controls={`skill-description-${skillId}`}
          onClick={toggleExpanded}
        >
          <span>{expanded ? "收起描述" : "查看完整描述"}</span>
          <ChevronDown
            size={14}
            aria-hidden="true"
            className={expanded ? "is-expanded" : undefined}
          />
        </button>
      ) : null}
    </>
  );
}

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
      <div className="ja-settings-skill-groups ja-skill-groups">
        {grouped.map(({ source, location, skills: sourceSkills }) => (
          <section
            key={source}
            className={`ja-settings-subsection ja-skill-group${sourceSkills.length === 0 ? " is-empty" : ""}`}
            aria-labelledby={`skill-source-${source}`}
          >
            <div className="ja-settings-subheading ja-skill-group-heading">
              <div className="ja-settings-skill-source-title">
                <h3 id={`skill-source-${source}`}>{sourceLabels[source]}</h3>
                <span className="ja-skill-source-location">{location}</span>
              </div>
              <span
                className="ja-settings-skill-count"
                aria-label={`${sourceSkills.length} 个 Skill`}
              >
                {sourceSkills.length}
              </span>
              {sourceSkills.length === 0 ? (
                <span className="ja-skill-empty-inline">暂无 Skills</span>
              ) : null}
            </div>
            {sourceSkills.length === 0 ? null : (
              <div className="ja-settings-skill-list ja-skill-list">
                {sourceSkills.map((skill) => (
                  <article
                    className="ja-settings-skill-card ja-skill-card"
                    data-setting-id={`skill-${skill.id}`}
                    data-setting-search={`${skill.name} ${skill.description} ${sourceLabels[skill.source]} skill 技能 ${skill.enabled ? "启用" : "停用"}`}
                    key={skill.id}
                  >
                    <div className="ja-settings-skill-main ja-skill-main">
                      <span className="ja-settings-file-icon">
                        <FileCode2 size={16} aria-hidden="true" />
                      </span>
                      <div>
                        <h4>{skill.name}</h4>
                        <SkillDescription skillId={skill.id} description={skill.description} />
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
                    <div className="ja-settings-skill-actions ja-skill-actions">
                      <SwitchField
                        id={`skill-toggle-${skill.id}`}
                        label={`${skill.name}：${skill.enabled ? "已启用" : "已停用"}`}
                        checked={skill.enabled}
                        onCheckedChange={(checked) => void toggle(skill, checked)}
                        disabled={pending === skill.id}
                        hideLabel
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
