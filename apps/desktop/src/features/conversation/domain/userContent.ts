// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** Workspace 与 Skill 引用只保存稳定身份，展示元数据不得进入 JA-RPC 或持久消息。 */
export type ConversationContextReference =
  | {
      type: "workspace_reference";
      workspaceId: string;
      relativePath: string;
      kind: "file" | "directory";
    }
  | {
      type: "skill_reference";
      skillId: string;
      name?: string;
      description?: string;
      scope?: "builtin" | "user" | "ja" | "project";
      available?: boolean;
    };

/** 当前 catalog 只提供 Chip 展示元数据；Skill 激活与有效性仍由 App Server 重新校验。 */
export interface ConversationSkillMetadata {
  skillId: string;
  name: string;
  description: string;
  scope: "builtin" | "user" | "ja" | "project";
}

/** JA-RPC 用户内容闭集；引用不会携带 React 专用标签或已读取正文。 */
export type UserContentBlock =
  | { type: "text"; text: string }
  | { type: "attachment"; attachmentId: string }
  | Extract<ConversationContextReference, { type: "workspace_reference" }>
  | { type: "skill_reference"; skillId: string };

/** 从结构化内容提取唯一正文；历史与队列不能再假定根对象存在 text 字段。 */
export function textFromUserContent(content: readonly UserContentBlock[]): string {
  return content
    .filter((block): block is Extract<UserContentBlock, { type: "text" }> => block.type === "text")
    .map((block) => block.text)
    .join("\n\n");
}

/** 把 Draft 引用投影为 wire block，主动剥离 Skill 展示元数据。 */
export function referenceToUserContent(reference: ConversationContextReference): UserContentBlock {
  if (reference.type === "workspace_reference") return reference;
  return { type: "skill_reference", skillId: reference.skillId };
}

/** 引用身份用于 Thread 草稿内去重；路径 kind 变化视为同一对象的新投影。 */
export function contextReferenceIdentity(reference: ConversationContextReference): string {
  return reference.type === "workspace_reference"
    ? `workspace:${reference.workspaceId}:${reference.relativePath}`
    : `skill:${reference.skillId}`;
}

/** 队列/历史只有 wire 内容时仍能渲染安全 Chip，不为 Skill 编造目录描述。 */
export function contextReferencesFromUserContent(
  content: readonly UserContentBlock[],
): ConversationContextReference[] {
  const references: ConversationContextReference[] = [];
  for (const block of content) {
    if (block.type === "workspace_reference") references.push(block);
    else if (block.type === "skill_reference") references.push({ ...block });
  }
  return references;
}

/**
 * 用当前 catalog 补全 wire 中故意未持久化的 Skill 展示信息；找不到时显示明确失效态，
 * 绝不把内部 skillId 冒充为用户可理解的 Skill 名称。
 */
export function resolveSkillReferenceMetadata(
  references: readonly ConversationContextReference[],
  skills: readonly ConversationSkillMetadata[],
): ConversationContextReference[] {
  const metadataById = new Map(skills.map((skill) => [skill.skillId, skill]));
  return references.map((reference) => {
    if (reference.type !== "skill_reference") return reference;
    const metadata = metadataById.get(reference.skillId);
    return metadata === undefined
      ? {
          type: "skill_reference",
          skillId: reference.skillId,
          name: "Skill 不可用",
          description: "该 Skill 已停用、删除或不属于当前配置代际",
          available: false,
        }
      : { type: "skill_reference", ...metadata, available: true };
  });
}
