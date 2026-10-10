// @author kongweiguang
import { useEffect, useRef, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import {
  BookOpen,
  Braces,
  Check,
  ChevronRight,
  Clock3,
  FileText,
  ListOrdered,
  LoaderCircle,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Server,
  Sparkles,
  StickyNote,
  Trash2,
  Wrench,
  X,
} from "lucide-react";
import { postChat, postExperience, readAttachmentMetadata } from "./api";
import { AttachmentPreview } from "./AttachmentPreview";
import { getSession } from "./state";
import type { AppState } from "./state";
import type {
  AttachmentMetadata,
  ChatTurn,
  HistoryContent,
  MessageContent,
  NoteView,
  WorkbenchTab,
} from "./types";

interface WorkbenchPanelProps {
  state: AppState;
  selectedSkills: string[];
  onClose: () => void;
  onTab: (tab: WorkbenchTab) => void;
  onReload: () => void | Promise<void>;
  onLoadMoreHistory: (threadId: string) => void | Promise<void>;
  onNotice: (text: string, tone?: "info" | "error" | "success") => void;
  onSkillsChange: (skills: string[]) => void;
}

interface SkillDescriptor {
  name: string;
  description?: string;
  sourceId?: string;
  version: number;
}

interface SkillEditor {
  name: string;
  description: string;
  body: string;
  version: number;
  isNew: boolean;
}

interface SkillDetail {
  name: string;
  description: string;
  body: string;
  version: number;
}

interface QueueEntry {
  inputId: string;
  kind: string;
  status: string;
  inputRevision: number;
  content: HistoryContent;
}

const tabs: Array<{ id: WorkbenchTab; label: string; icon: typeof Sparkles }> = [
  { id: "skills", label: "Skills", icon: BookOpen },
  { id: "mcp", label: "MCP", icon: Server },
  { id: "tools", label: "工具", icon: Wrench },
  { id: "queue", label: "队列", icon: ListOrdered },
  { id: "records", label: "记录", icon: FileText },
];

/** 按需展示服务端能力目录，并让记录读取随当前 Thread 与面板生命周期结束。 */
export function WorkbenchPanel(props: WorkbenchPanelProps) {
  const { state } = props;
  const [skillSearch, setSkillSearch] = useState("");
  const [editor, setEditor] = useState<SkillEditor | null>(null);
  const [editorBusy, setEditorBusy] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<SkillDescriptor | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [queueBusy, setQueueBusy] = useState("");
  const [queueEditor, setQueueEditor] = useState<{ inputId: string; text: string } | null>(null);
  const [attachmentPreview, setAttachmentPreview] = useState<{
    threadId: string;
    attachment: AttachmentMetadata;
    trigger: HTMLElement;
  }>();
  const [usageLoading, setUsageLoading] = useState(false);
  const usageRequest = useRef<AbortController | null>(null);
  const activeTab = tabs.find((tab) => tab.id === state.workbenchTab) ?? tabs[0]!;
  const selectedThread = state.selectedThreadId
    ? getSession(state, state.selectedThreadId)
    : undefined;
  const cached = state.workbenchData[state.workbenchTab];

  /** 切换 Thread、标签或关闭面板时取消 Usage 请求，释放当前唯一读取槽位。 */
  useEffect(() => {
    usageRequest.current?.abort();
    usageRequest.current = null;
    setUsageLoading(false);
    return () => usageRequest.current?.abort();
  }, [state.selectedThreadId, state.workbenchOpen, state.workbenchTab]);

  /** 只读取展开 Turn 的有限 Usage 批次；下一批由用户继续请求，不设总记录上限。 */
  const loadUsagePage = async (threadId: string, turnId: string, recordIds: string[]) => {
    if (usageRequest.current || recordIds.length === 0) return undefined;
    const controller = new AbortController();
    usageRequest.current = controller;
    setUsageLoading(true);
    try {
      return await Promise.all(
        recordIds.map((recordId) =>
          postChat<unknown>("usage", { threadId, turnId, recordId }, controller.signal),
        ),
      );
    } finally {
      if (usageRequest.current === controller) {
        usageRequest.current = null;
        setUsageLoading(false);
      }
    }
  };

  /** 仅在明确编辑时打开 Skill 文档，目录列表保持轻量读取。 */
  const editSkill = async (skill: SkillDescriptor) => {
    try {
      const detail = await postExperience<SkillDetail>("skills/read", { name: skill.name });
      setEditor({
        name: detail.name,
        description: detail.description,
        body: detail.body,
        version: detail.version,
        isNew: false,
      });
    } catch (error) {
      props.onNotice(`读取 Skill 失败：${errorMessage(error)}`, "error");
    }
  };

  /** 使用从服务端读取的版本保存，使并发编辑能够显式报告冲突。 */
  const saveSkill = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editor) return;
    setEditorBusy(true);
    try {
      await postExperience("skills/write", {
        name: editor.name.trim(),
        description: editor.description.trim(),
        body: editor.body,
        expectedVersion: editor.version,
      });
      setEditor(null);
      props.onNotice(`Skill「${editor.name.trim()}」已保存。`, "success");
      await props.onReload();
    } catch (error) {
      props.onNotice(`保存 Skill 失败：${errorMessage(error)}`, "error");
    } finally {
      setEditorBusy(false);
    }
  };

  /** 仅在用户完成可见的二次确认后删除，并清除会话对此 Skill 的选择。 */
  const deleteSkill = async () => {
    if (!deleteTarget) return;
    setDeleteBusy(true);
    try {
      await postExperience("skills/delete", {
        name: deleteTarget.name,
        expectedVersion: deleteTarget.version,
      });
      props.onSkillsChange(
        props.selectedSkills.filter((reference) => reference !== skillReference(deleteTarget)),
      );
      props.onNotice(`Skill「${deleteTarget.name}」已删除。`, "success");
      setDeleteTarget(null);
      await props.onReload();
    } catch (error) {
      props.onNotice(`删除 Skill 失败：${errorMessage(error)}`, "error");
    } finally {
      setDeleteBusy(false);
    }
  };

  /** 队列操作携带版本号，每次取得结果后重新加载权威投影。 */
  const mutateQueue = async (
    entry: QueueEntry,
    action: "DELETE" | "PRIORITIZE" | "UPDATE",
    text = entry.content.text,
  ) => {
    if (!state.selectedThreadId || !selectedThread?.turns.length) return;
    setQueueBusy(`${entry.inputId}:${action}`);
    try {
      await postChat("queue/mutate", {
        threadId: state.selectedThreadId,
        turnId: selectedThread.turns.at(-1)!.turnId,
        action,
        inputId: entry.inputId,
        kind: entry.kind,
        expectedInputRevision: entry.inputRevision,
        operationKey: `queue_${randomId()}`,
        content:
          action === "UPDATE"
            ? ({
                text,
                skillIds: entry.content.skillIds,
                attachmentIds: entry.content.attachments.map((attachment) => attachment.id),
              } satisfies MessageContent)
            : undefined,
      });
      props.onNotice(
        action === "DELETE"
          ? "队列消息已移除。"
          : action === "UPDATE"
            ? "队列消息已更新。"
            : "队列消息已置顶。",
        "success",
      );
      setQueueEditor(null);
      await props.onReload();
    } catch (error) {
      props.onNotice(`队列状态未更新：${errorMessage(error)}`, "error");
      await props.onReload();
    } finally {
      setQueueBusy("");
    }
  };

  const skillRows = rows<Record<string, unknown>>(state.workbenchData.skills)
    .flatMap((skill) =>
      typeof skill["name"] === "string" && typeof skill["sourceId"] === "string"
        ? [
            {
              name: skill["name"],
              description: stringValue(skill["description"], ""),
              sourceId: skill["sourceId"],
              version: typeof skill["version"] === "number" ? skill["version"] : 0,
            },
          ]
        : [],
    )
    .filter(
      (skill) =>
        skill.name.toLowerCase().includes(skillSearch.trim().toLowerCase()) ||
        skill.description.toLowerCase().includes(skillSearch.trim().toLowerCase()),
    );

  return (
    <>
      <button className="scrim workbench-scrim" aria-label="关闭能力面板" onClick={props.onClose} />
      <aside className="workbench-panel" aria-label="能力面板">
        <header className="workbench-heading">
          <div className="workbench-title">
            <span className="workbench-title-mark">
              <Sparkles />
            </span>
            <div>
              <h2>能力面板</h2>
              <span>{activeTab.label}</span>
            </div>
          </div>
          <div className="workbench-heading-actions">
            <button
              className="icon-button"
              title="刷新当前页"
              aria-label="刷新当前页"
              onClick={() => void props.onReload()}
              disabled={state.workbenchLoading}
            >
              <RefreshCw className={state.workbenchLoading ? "spin" : ""} />
            </button>
            <button
              className="icon-button"
              title="关闭能力面板"
              aria-label="关闭能力面板"
              onClick={props.onClose}
            >
              <X />
            </button>
          </div>
        </header>

        <nav className="workbench-tabs" role="tablist" aria-label="能力分类">
          {tabs.map(({ id, label, icon: Icon }) => (
            <button
              className={`workbench-tab ${state.workbenchTab === id ? "selected" : ""}`}
              key={id}
              id={`workbench-tab-${id}`}
              type="button"
              role="tab"
              aria-selected={state.workbenchTab === id}
              aria-controls="workbench-content"
              onClick={() => props.onTab(id)}
            >
              <Icon aria-hidden="true" />
              <span>{label}</span>
            </button>
          ))}
        </nav>

        <section
          className="workbench-content"
          id="workbench-content"
          role="tabpanel"
          aria-labelledby={`workbench-tab-${state.workbenchTab}`}
        >
          {state.workbenchLoading && cached === undefined ? (
            <div className="workbench-loading">
              <LoaderCircle className="spin" />
              正在读取
            </div>
          ) : state.workbenchTab === "skills" ? (
            <div className="workbench-section">
              <div className="section-intro">
                <div>
                  <h3>可用 Skills</h3>
                  <p>选择后会用于当前会话的新消息。</p>
                </div>
                <button
                  className="small-primary-button"
                  onClick={() =>
                    setEditor({ name: "", description: "", body: "", version: 0, isNew: true })
                  }
                >
                  <Plus />
                  新建
                </button>
              </div>
              <label className="panel-search">
                <Search />
                <input
                  value={skillSearch}
                  onChange={(event) => setSkillSearch(event.target.value)}
                  placeholder="筛选 Skills"
                  aria-label="筛选 Skills"
                />
              </label>
              {skillRows.length ? (
                <div className="capability-list">
                  {skillRows.map((skill) => (
                    <article className="skill-row" key={skill.name}>
                      <label className="skill-choice">
                        <input
                          type="checkbox"
                          checked={props.selectedSkills.includes(skillReference(skill))}
                          onChange={(event) => {
                            const reference = skillReference(skill);
                            const next = event.target.checked
                              ? [...props.selectedSkills, reference]
                              : props.selectedSkills.filter((selected) => selected !== reference);
                            props.onSkillsChange([...new Set(next)]);
                          }}
                        />
                        <span className="capability-copy">
                          <strong>{skill.name}</strong>
                          <span>{skill.description || "暂无描述"}</span>
                          <small>版本 {skill.version}</small>
                        </span>
                      </label>
                      <div className="row-actions">
                        <button
                          className="icon-button"
                          title={`编辑 ${skill.name}`}
                          aria-label={`编辑 ${skill.name}`}
                          onClick={() => void editSkill(skill)}
                        >
                          <Pencil />
                        </button>
                        <button
                          className="icon-button danger-icon"
                          title={`删除 ${skill.name}`}
                          aria-label={`删除 ${skill.name}`}
                          onClick={() => setDeleteTarget(skill)}
                        >
                          <Trash2 />
                        </button>
                      </div>
                    </article>
                  ))}
                </div>
              ) : (
                <EmptyState
                  icon={<BookOpen />}
                  title={skillSearch ? "没有匹配的 Skill" : "暂无 Skills"}
                  detail={
                    skillSearch ? "尝试其他关键词。" : "创建 Skill 后，可在 Composer 中选择。"
                  }
                />
              )}
            </div>
          ) : state.workbenchTab === "mcp" ? (
            <McpContent value={state.workbenchData.mcp} loading={state.workbenchLoading} />
          ) : state.workbenchTab === "tools" ? (
            <ToolsContent value={state.workbenchData.tools} />
          ) : state.workbenchTab === "queue" ? (
            <QueueContent
              threadId={state.selectedThreadId}
              value={state.workbenchData.queue}
              busy={queueBusy}
              editor={queueEditor}
              onEdit={(entry) =>
                setQueueEditor({ inputId: entry.inputId, text: entry.content.text })
              }
              onEditText={(inputId, text) => setQueueEditor({ inputId, text })}
              onCancelEdit={() => setQueueEditor(null)}
              onMutate={(entry, action, text) => void mutateQueue(entry, action, text)}
              onPreviewAttachment={(attachment, trigger) =>
                state.selectedThreadId &&
                setAttachmentPreview({ threadId: state.selectedThreadId, attachment, trigger })
              }
            />
          ) : (
            <RecordsContent
              key={state.selectedThreadId ?? ""}
              value={state.workbenchData.records}
              threadId={state.selectedThreadId}
              turns={selectedThread?.turns ?? []}
              historyHasMore={selectedThread?.historyHasMore ?? false}
              historyLoading={selectedThread?.loadingHistory ?? false}
              usageLoading={usageLoading}
              onLoadMoreHistory={props.onLoadMoreHistory}
              onLoadUsage={loadUsagePage}
              onPreviewAttachment={(attachment, trigger) =>
                state.selectedThreadId &&
                setAttachmentPreview({ threadId: state.selectedThreadId, attachment, trigger })
              }
            />
          )}
        </section>

        {editor && (
          <div
            className="panel-dialog-backdrop"
            role="presentation"
            onMouseDown={(event) => {
              if (event.currentTarget === event.target && !editorBusy) setEditor(null);
            }}
          >
            <form
              className="skill-editor-dialog"
              onSubmit={(event) => void saveSkill(event)}
              aria-labelledby="skill-editor-title"
            >
              <div className="dialog-heading">
                <h2 id="skill-editor-title">{editor.isNew ? "新建 Skill" : "编辑 Skill"}</h2>
                <button
                  className="icon-button"
                  type="button"
                  title="关闭"
                  aria-label="关闭"
                  disabled={editorBusy}
                  onClick={() => setEditor(null)}
                >
                  <X />
                </button>
              </div>
              <label className="field-label" htmlFor="skill-name">
                名称
              </label>
              <input
                id="skill-name"
                autoFocus
                required
                maxLength={80}
                value={editor.name}
                disabled={!editor.isNew || editorBusy}
                onChange={(event) => setEditor({ ...editor, name: event.target.value })}
              />
              <label className="field-label" htmlFor="skill-description">
                描述
              </label>
              <input
                id="skill-description"
                maxLength={500}
                value={editor.description}
                disabled={editorBusy}
                onChange={(event) => setEditor({ ...editor, description: event.target.value })}
              />
              <label className="field-label" htmlFor="skill-body">
                指令正文
              </label>
              <textarea
                id="skill-body"
                className="skill-body-field"
                required
                maxLength={32_768}
                value={editor.body}
                disabled={editorBusy}
                onChange={(event) => setEditor({ ...editor, body: event.target.value })}
              />
              <div className="dialog-actions">
                <button
                  className="text-button"
                  type="button"
                  disabled={editorBusy}
                  onClick={() => setEditor(null)}
                >
                  取消
                </button>
                <button
                  className="primary-button"
                  type="submit"
                  disabled={editorBusy || !editor.name.trim() || !editor.body.trim()}
                >
                  {editorBusy ? (
                    <>
                      <LoaderCircle className="spin" />
                      保存中
                    </>
                  ) : (
                    <>
                      <Check />
                      保存
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>
        )}

        {deleteTarget && (
          <div
            className="panel-dialog-backdrop"
            role="presentation"
            onMouseDown={(event) => {
              if (event.currentTarget === event.target && !deleteBusy) setDeleteTarget(null);
            }}
          >
            <div
              className="confirm-dialog"
              role="alertdialog"
              aria-labelledby="delete-skill-title"
              aria-describedby="delete-skill-description"
            >
              <div className="dialog-heading">
                <h2 id="delete-skill-title">删除 Skill</h2>
                <button
                  className="icon-button"
                  title="关闭"
                  aria-label="关闭"
                  disabled={deleteBusy}
                  onClick={() => setDeleteTarget(null)}
                >
                  <X />
                </button>
              </div>
              <p id="delete-skill-description">
                删除「{deleteTarget.name}」后，后续消息将无法选择它。
              </p>
              <div className="dialog-actions">
                <button
                  className="text-button"
                  disabled={deleteBusy}
                  onClick={() => setDeleteTarget(null)}
                >
                  取消
                </button>
                <button
                  className="danger-button"
                  disabled={deleteBusy}
                  onClick={() => void deleteSkill()}
                >
                  {deleteBusy ? (
                    <>
                      <LoaderCircle className="spin" />
                      删除中
                    </>
                  ) : (
                    <>
                      <Trash2 />
                      确认删除
                    </>
                  )}
                </button>
              </div>
            </div>
          </div>
        )}
        {attachmentPreview && (
          <AttachmentPreview
            key={`${attachmentPreview.threadId}:${attachmentPreview.attachment.id}`}
            threadId={attachmentPreview.threadId}
            attachment={attachmentPreview.attachment}
            returnFocusTo={attachmentPreview.trigger}
            onClose={() => setAttachmentPreview(undefined)}
          />
        )}
      </aside>
    </>
  );
}

/** 展示发现的 MCP 服务，不暴露连接头或配置值。 */
function McpContent({ value, loading }: { value: unknown; loading: boolean }) {
  const servers = rows<Record<string, unknown>>(value);
  if (!servers.length)
    return (
      <EmptyState
        icon={<Server />}
        title={loading ? "正在发现 MCP" : "没有可用 MCP 服务"}
        detail={loading ? "正在读取真实目录。" : "MCP 服务由示例启动配置提供。"}
      />
    );
  return (
    <div className="workbench-section">
      <div className="section-intro">
        <div>
          <h3>MCP 服务</h3>
          <p>目录来自本次实际握手结果。</p>
        </div>
      </div>
      <div className="capability-list">
        {servers.map((server, index) => {
          const tools = rows<Record<string, unknown>>(server["tools"]);
          const available = server["status"] === "AVAILABLE";
          return (
            <article className="mcp-server-row" key={String(server["id"] ?? index)}>
              <div className="mcp-server-heading">
                <span className={`status-pill ${available ? "status-good" : "status-bad"}`}>
                  <span className="status-dot" />
                  {available ? "可用" : "不可用"}
                </span>
                <strong>{stringValue(server["id"], "MCP 服务")}</strong>
              </div>
              <p>
                {stringValue(server["transport"], "未知传输")} · {tools.length} 个工具
              </p>
              {tools.length > 0 && (
                <details className="schema-detail">
                  <summary>
                    <ChevronRight />
                    查看工具目录
                  </summary>
                  <div className="nested-list">
                    {tools.map((tool, toolIndex) => (
                      <CapabilitySchema
                        key={String(tool["name"] ?? toolIndex)}
                        name={stringValue(tool["name"], "未命名工具")}
                        description={stringValue(tool["description"], "暂无说明")}
                        schema={tool["inputSchema"]}
                      />
                    ))}
                  </div>
                </details>
              )}
            </article>
          );
        })}
      </div>
    </div>
  );
}

/** 将扩展注册表的 schema 作为静态数据展示，面板不直接执行工具。 */
function ToolsContent({ value }: { value: unknown }) {
  const tools = rows<Record<string, unknown>>(value);
  if (!tools.length)
    return (
      <EmptyState
        icon={<Wrench />}
        title="没有已注册工具"
        detail="工具列表来自当前 Engine 注册表。"
      />
    );
  const supportsNotes = tools.some((tool) => tool["name"] === "save_note");
  return (
    <div className="workbench-section">
      <div className="section-intro">
        <div>
          <h3>注册工具</h3>
          <p>工具仍由 Agent 执行并遵循对应审批规则。</p>
        </div>
        <span className="count-label">{tools.length}</span>
      </div>
      {supportsNotes && (
        <div className="document-policy-summary">
          <strong>
            <Check />
            资料归档策略
          </strong>
          <span>笔记参数规范化后请求用户审批；保存结果与来源资料在同一事务提交。</span>
        </div>
      )}
      <div className="capability-list">
        {tools.map((tool, index) => (
          <CapabilitySchema
            key={String(tool["name"] ?? index)}
            name={stringValue(tool["name"], "未命名工具")}
            description={stringValue(tool["description"], "暂无说明")}
            schema={tool["inputSchema"]}
          />
        ))}
      </div>
    </div>
  );
}

/** 展示当前会话队列，修改时携带核心模块返回的记录版本。 */
function QueueContent({
  threadId,
  value,
  busy,
  editor,
  onEdit,
  onEditText,
  onCancelEdit,
  onMutate,
  onPreviewAttachment,
}: {
  threadId?: string;
  value: unknown;
  busy: string;
  editor: { inputId: string; text: string } | null;
  onEdit: (entry: QueueEntry) => void;
  onEditText: (inputId: string, text: string) => void;
  onCancelEdit: () => void;
  onMutate: (entry: QueueEntry, action: "DELETE" | "PRIORITIZE" | "UPDATE", text?: string) => void;
  onPreviewAttachment: (attachment: AttachmentMetadata, trigger: HTMLElement) => void;
}) {
  const queue = record(value);
  const entries = rows<Record<string, unknown>>(queue?.["entries"]).flatMap(
    (value): QueueEntry[] => {
      const content = record(value["content"]);
      if (typeof value["inputId"] !== "string" || !content) return [];
      return [
        {
          inputId: value["inputId"],
          kind: stringValue(value["kind"], "MESSAGE"),
          status: stringValue(value["status"], "UNKNOWN"),
          inputRevision: typeof value["inputRevision"] === "number" ? value["inputRevision"] : 0,
          content: {
            text: stringValue(content["text"], ""),
            skillIds: rows<string>(content["skillIds"]),
            attachments: rows<AttachmentMetadata>(content["attachments"]),
          },
        },
      ];
    },
  );
  if (!queue)
    return (
      <EmptyState
        icon={<ListOrdered />}
        title="当前没有执行队列"
        detail="执行中的补充消息会显示在这里。"
      />
    );
  if (!entries.length)
    return (
      <EmptyState
        icon={<ListOrdered />}
        title="队列为空"
        detail="执行期间追加的消息会在这里等待处理。"
      />
    );
  return (
    <div className="workbench-section">
      <div className="section-intro">
        <div>
          <h3>待处理消息</h3>
          <p>变更按每条记录的服务端版本提交。</p>
        </div>
        <span className="count-label">{entries.length}</span>
      </div>
      <div className="queue-list">
        {entries.map((entry) => (
          <article className="queue-row" key={entry.inputId}>
            <div className="queue-row-heading">
              <span className="queue-kind">{queueKind(entry.kind)}</span>
              <span className="queue-status">{queueStatus(entry.status)}</span>
            </div>
            {editor?.inputId === entry.inputId ? (
              <div className="queue-editor">
                <textarea
                  aria-label="编辑队列消息"
                  maxLength={100_000}
                  value={editor.text}
                  onChange={(event) => onEditText(entry.inputId, event.target.value)}
                />
                <div>
                  <button className="text-button" disabled={Boolean(busy)} onClick={onCancelEdit}>
                    取消
                  </button>
                  <button
                    className="small-primary-button"
                    disabled={Boolean(busy) || !editor.text.trim()}
                    onClick={() => onMutate(entry, "UPDATE", editor.text)}
                  >
                    {busy === `${entry.inputId}:UPDATE` ? (
                      <LoaderCircle className="spin" />
                    ) : (
                      <Check />
                    )}
                    保存
                  </button>
                </div>
              </div>
            ) : (
              <p>
                {entry.content.text ||
                  (entry.content.attachments.length ? "（仅附件）" : "（空消息）")}
              </p>
            )}
            {entry.content.skillIds.length > 0 && (
              <div className="queue-skills">
                {entry.content.skillIds.map((skill) => (
                  <span key={skill}>{skill}</span>
                ))}
              </div>
            )}
            {entry.content.attachments.length > 0 && (
              <div className="queue-attachments" aria-label="队列附件">
                {entry.content.attachments.map((attachment) => (
                  <button
                    key={attachment.id}
                    type="button"
                    disabled={!threadId}
                    onClick={(event) => onPreviewAttachment(attachment, event.currentTarget)}
                  >
                    <FileText />
                    {attachment.fileName}
                  </button>
                ))}
              </div>
            )}
            <div className="queue-actions">
              {editor?.inputId !== entry.inputId && (
                <button
                  className="text-button"
                  disabled={Boolean(busy)}
                  onClick={() => onEdit(entry)}
                >
                  <Pencil />
                  编辑
                </button>
              )}
              <button
                className="text-button"
                disabled={Boolean(busy)}
                onClick={() => onMutate(entry, "PRIORITIZE")}
              >
                {busy === `${entry.inputId}:PRIORITIZE` ? (
                  <LoaderCircle className="spin" />
                ) : (
                  <ChevronRight />
                )}
                置顶
              </button>
              <button
                className="text-button danger-text"
                disabled={Boolean(busy)}
                onClick={() => onMutate(entry, "DELETE")}
              >
                {busy === `${entry.inputId}:DELETE` ? (
                  <LoaderCircle className="spin" />
                ) : (
                  <Trash2 />
                )}
                移除
              </button>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}

/** 延迟读取记录细节，并将业务结果与可回看的技术证据分层，避免默认暴露内部标识。 */
function RecordsContent({
  value,
  threadId,
  turns,
  historyHasMore,
  historyLoading,
  usageLoading,
  onLoadMoreHistory,
  onLoadUsage,
  onPreviewAttachment,
}: {
  value: unknown;
  threadId?: string;
  turns: ChatTurn[];
  historyHasMore: boolean;
  historyLoading: boolean;
  usageLoading: boolean;
  onLoadMoreHistory: (threadId: string) => void | Promise<void>;
  onLoadUsage: (
    threadId: string,
    turnId: string,
    recordIds: string[],
  ) => Promise<unknown[] | undefined>;
  onPreviewAttachment: (attachment: AttachmentMetadata, trigger: HTMLElement) => void;
}) {
  const pageSize = 20;
  const [visibleCount, setVisibleCount] = useState(pageSize);
  const [usageByTurn, setUsageByTurn] = useState<
    Record<string, { recordId: string; value: Record<string, unknown> }[]>
  >({});
  const [usageOffsetByTurn, setUsageOffsetByTurn] = useState<Record<string, number>>({});
  const [usageErrorByTurn, setUsageErrorByTurn] = useState<Record<string, string>>({});
  const [policyFactsByTurn, setPolicyFactsByTurn] = useState<
    Record<string, Record<string, unknown>[]>
  >({});
  const [policyFactErrors, setPolicyFactErrors] = useState<Record<string, string>>({});
  const [policyFactLoading, setPolicyFactLoading] = useState<Record<string, boolean>>({});
  const policyFactRequests = useRef(new Set<string>());
  const [expandedTurnId, setExpandedTurnId] = useState<string>();
  const data = record(value);
  const notes = rows<NoteView>(data?.["notes"]);
  const [noteAttachments, setNoteAttachments] = useState<Record<string, AttachmentMetadata>>({});
  const [noteAttachmentError, setNoteAttachmentError] = useState("");
  const [noteAttachmentLoading, setNoteAttachmentLoading] = useState(false);
  const [noteAttachmentReload, setNoteAttachmentReload] = useState(0);
  const noteAttachmentKey = JSON.stringify([
    ...new Set(
      notes.flatMap((note) =>
        rows<unknown>(note["sourceAttachmentIds"]).filter(
          (id): id is string => typeof id === "string",
        ),
      ),
    ),
  ]);
  const firstVisibleTurn = Math.max(0, turns.length - visibleCount);
  const visibleTurns = turns.slice(firstVisibleTurn).reverse();
  const canShowLoadedTurns = visibleCount < turns.length;

  /** 只物化笔记引用的元数据，不读取文件正文；切换或重试会取消旧请求。 */
  useEffect(() => {
    const ids = JSON.parse(noteAttachmentKey) as string[];
    if (!threadId || ids.length === 0) {
      setNoteAttachments({});
      setNoteAttachmentError("");
      setNoteAttachmentLoading(false);
      return;
    }
    const controller = new AbortController();
    setNoteAttachments({});
    setNoteAttachmentError("");
    setNoteAttachmentLoading(true);
    void readAttachmentMetadata(threadId, ids, controller.signal)
      .then((items) => {
        if (controller.signal.aborted) return;
        setNoteAttachments(Object.fromEntries(items.map((item) => [item.id, item])));
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setNoteAttachmentError(errorMessage(error));
      })
      .finally(() => {
        if (!controller.signal.aborted) setNoteAttachmentLoading(false);
      });
    return () => controller.abort();
  }, [threadId, noteAttachmentKey, noteAttachmentReload]);

  if (!data)
    return (
      <EmptyState
        icon={<Clock3 />}
        title="暂无执行记录"
        detail="会话中的模型用量与工具结果会在这里汇总。"
      />
    );

  /** 先展开已读取的本地页；全部显示后再沿服务端游标继续读取，保留长会话的完整访问路径。 */
  const showEarlierRecords = () => {
    if (canShowLoadedTurns) {
      setVisibleCount((count) => Math.min(turns.length, count + pageSize));
      return;
    }
    if (threadId && historyHasMore && !historyLoading) {
      setVisibleCount((count) => count + pageSize);
      void onLoadMoreHistory(threadId);
    }
  };

  /** 每批最多读取八条 Usage；失败保留原游标以便重试，成功后仍可继续读取剩余记录。 */
  const loadUsage = async (turn: ChatTurn) => {
    if (!threadId || usageLoading) return;
    const offset = usageOffsetByTurn[turn.turnId] ?? 0;
    const recordIds = turn.usageRecordIds.slice(offset, offset + 8);
    if (!recordIds.length) return;
    setUsageErrorByTurn((current) => ({ ...current, [turn.turnId]: "" }));
    try {
      const page = await onLoadUsage(threadId, turn.turnId, recordIds);
      if (!page) return;
      const loaded = page.flatMap((item, index) => {
        const value = record(item);
        const recordId = recordIds[index];
        return value && recordId ? [{ recordId, value }] : [];
      });
      setUsageByTurn((current) => ({
        ...current,
        [turn.turnId]: [...(current[turn.turnId] ?? []), ...loaded],
      }));
      setUsageOffsetByTurn((current) => ({ ...current, [turn.turnId]: offset + recordIds.length }));
    } catch (error) {
      if (!isAbortError(error))
        setUsageErrorByTurn((current) => ({ ...current, [turn.turnId]: errorMessage(error) }));
    }
  };

  /** 按 Turn 读取该 Thread 的全部已提交策略事实，使用 Core 序号直到服务端报告结束。 */
  const loadPolicyFacts = async (turnId: string, retry = false) => {
    if (
      !threadId ||
      policyFactRequests.current.has(turnId) ||
      (!retry && policyFactsByTurn[turnId])
    )
      return;
    policyFactRequests.current.add(turnId);
    setPolicyFactLoading((current) => ({ ...current, [turnId]: true }));
    setPolicyFactErrors((current) => ({ ...current, [turnId]: "" }));
    try {
      const facts: Record<string, unknown>[] = [];
      let afterSequence = 0;
      while (true) {
        const page = await postExperience<{
          facts?: unknown[];
          nextSequence?: number;
          hasMore?: boolean;
        }>("policy/facts", {
          threadId,
          turnId,
          afterSequence,
          limit: 100,
        });
        facts.push(...rows<Record<string, unknown>>(page.facts));
        const nextSequence = page.nextSequence ?? afterSequence;
        if (!page.hasMore || nextSequence <= afterSequence) break;
        afterSequence = nextSequence;
      }
      setPolicyFactsByTurn((current) => ({ ...current, [turnId]: facts }));
    } catch (error) {
      setPolicyFactErrors((current) => ({ ...current, [turnId]: errorMessage(error) }));
    } finally {
      policyFactRequests.current.delete(turnId);
      setPolicyFactLoading((current) => ({ ...current, [turnId]: false }));
    }
  };

  return (
    <div className="workbench-section records-section">
      <div className="section-intro">
        <div>
          <h3>执行记录</h3>
          <p>来自当前会话的持久历史与供应商用量。</p>
        </div>
        <span className="count-label">{turns.length} 条</span>
      </div>
      <section className="record-group">
        <h4>
          <Clock3 />
          对话执行{" "}
          <span>
            {Math.min(visibleCount, turns.length)} / {turns.length}
          </span>
        </h4>
        {visibleTurns.length ? (
          visibleTurns.map((turn) => (
            <details
              className="record-row"
              key={turn.turnId}
              open={expandedTurnId === turn.turnId}
              onToggle={(event) => {
                if (event.currentTarget.open) {
                  setExpandedTurnId(turn.turnId);
                  void loadPolicyFacts(turn.turnId);
                } else if (expandedTurnId === turn.turnId) setExpandedTurnId(undefined);
              }}
            >
              <summary>
                <ChevronRight />
                <span>{turn.userText || "压缩上下文"}</span>
                <em>{stateLabel(turn.state)}</em>
              </summary>
              <div className="record-detail">
                <details className="record-technical">
                  <summary>技术详情</summary>
                  <p>
                    Turn ID <code>{turn.turnId}</code>
                  </p>
                </details>
                {turn.answer && <p>{turn.answer}</p>}
                {turn.error && <p className="record-error">{turn.error}</p>}
                <p>
                  {turn.items.filter((item) => item.kind === "tool").length} 次工具调用 ·{" "}
                  {turn.usageRecordIds.length} 条用量记录
                </p>
                {turn.usageRecordIds.length > 0 && (
                  <div className="record-usage">
                    <div className="section-intro">
                      <div>
                        <strong>
                          <Braces />
                          模型用量 {usageByTurn[turn.turnId]?.length ?? 0} /{" "}
                          {turn.usageRecordIds.length}
                        </strong>
                      </div>
                      {(usageOffsetByTurn[turn.turnId] ?? 0) < turn.usageRecordIds.length && (
                        <button
                          className="small-primary-button"
                          type="button"
                          disabled={usageLoading}
                          onClick={() => void loadUsage(turn)}
                        >
                          {usageLoading ? (
                            <>
                              <LoaderCircle className="spin" />
                              读取中
                            </>
                          ) : (
                            <>
                              <Braces />
                              {usageOffsetByTurn[turn.turnId] ? "继续读取用量" : "读取用量"}
                            </>
                          )}
                        </button>
                      )}
                    </div>
                    {usageErrorByTurn[turn.turnId] && (
                      <p className="record-error" role="alert">
                        用量读取失败：{usageErrorByTurn[turn.turnId]}
                      </p>
                    )}
                    {(usageByTurn[turn.turnId] ?? []).map(({ recordId, value }) => (
                      <UsageRow key={recordId} value={value} />
                    ))}
                  </div>
                )}
                <section className="record-policy-facts">
                  <div>
                    <strong>资料策略事实</strong>
                    <span>{policyFactsByTurn[turn.turnId]?.length ?? 0}</span>
                  </div>
                  {policyFactLoading[turn.turnId] && <p role="status">正在读取策略事实</p>}
                  {policyFactErrors[turn.turnId] && (
                    <p className="record-error" role="alert">
                      读取失败：{policyFactErrors[turn.turnId]}{" "}
                      <button
                        className="text-button"
                        type="button"
                        onClick={() => void loadPolicyFacts(turn.turnId, true)}
                      >
                        重试
                      </button>
                    </p>
                  )}
                  {(policyFactsByTurn[turn.turnId] ?? []).map((fact, index) => (
                    <details
                      className="policy-fact-row"
                      key={`${stringValue(fact["type"], "fact")}:${index}`}
                    >
                      <summary>
                        <span>{policyFactLabel(fact)}</span>
                        <small>技术详情</small>
                      </summary>
                      <div className="record-technical">
                        <p>
                          事实类型 <code>{stringValue(fact["type"], "未知")}</code>
                        </p>
                        <p>
                          命名空间 <code>{stringValue(fact["namespace"], "未知")}</code>
                        </p>
                        {typeof fact["schemaVersion"] === "number" && (
                          <p>Schema 版本 {fact["schemaVersion"]}</p>
                        )}
                        <p>
                          记录时间 <time>{stringValue(fact["occurredAt"], "未知")}</time>
                        </p>
                        <pre>{jsonText(fact["payload"])}</pre>
                      </div>
                    </details>
                  ))}
                  {!policyFactLoading[turn.turnId] &&
                    !policyFactErrors[turn.turnId] &&
                    policyFactsByTurn[turn.turnId]?.length === 0 && (
                      <p className="muted-empty">此轮没有资料策略事实。</p>
                    )}
                </section>
              </div>
            </details>
          ))
        ) : (
          <p className="muted-empty">暂无对话执行。</p>
        )}
        {(canShowLoadedTurns || historyHasMore) && (
          <button
            className="text-button"
            type="button"
            disabled={historyLoading}
            onClick={showEarlierRecords}
          >
            {historyLoading ? (
              <>
                <LoaderCircle className="spin" />
                读取中
              </>
            ) : (
              <>
                <ChevronRight />
                {canShowLoadedTurns ? "显示更早记录" : "加载更早记录"}
              </>
            )}
          </button>
        )}
      </section>
      <section className="record-group">
        <h4>
          <StickyNote />
          笔记 <span>{notes.length}</span>
        </h4>
        {noteAttachmentLoading && (
          <p className="muted-empty" role="status">
            正在读取笔记来源资料。
          </p>
        )}
        {noteAttachmentError && (
          <p className="record-error" role="alert">
            来源资料读取失败：{noteAttachmentError}{" "}
            <button
              className="text-button"
              type="button"
              onClick={() => setNoteAttachmentReload((value) => value + 1)}
            >
              重试
            </button>
          </p>
        )}
        {notes.length ? (
          notes.map((note) => (
            <article className="note-row" key={note.callKey}>
              <h5>{note.title}</h5>
              <p>{note.body}</p>
              {note.sourceAttachmentIds.length > 0 && (
                <div className="queue-attachments" aria-label="笔记来源资料">
                  {note.sourceAttachmentIds.map((attachmentId) => {
                    const attachment = noteAttachments[attachmentId];
                    return attachment && threadId ? (
                      <button
                        key={attachmentId}
                        type="button"
                        onClick={(event) => onPreviewAttachment(attachment, event.currentTarget)}
                      >
                        <FileText />
                        {attachment.fileName}
                      </button>
                    ) : (
                      <span key={attachmentId}>
                        <FileText />
                        {noteAttachmentLoading ? "正在读取" : "资料不可用"}
                      </span>
                    );
                  })}
                </div>
              )}
              <details className="record-technical">
                <summary>技术详情</summary>
                <pre>
                  {jsonText({
                    callKey: note.callKey,
                    turnId: note.turnId,
                    createdAt: note.createdAt,
                  })}
                </pre>
              </details>
            </article>
          ))
        ) : (
          <p className="muted-empty">当前会话没有笔记。</p>
        )}
      </section>
    </div>
  );
}

/** 展示实测 token 总量，不向界面投影供应商配置或内部请求配置。 */
function UsageRow({ value }: { value: Record<string, unknown> }) {
  const usage = record(value["usage"]);
  return (
    <article className="usage-row">
      <div>
        <strong>{stringValue(value["purpose"], "模型请求")}</strong>
        <span>{stringValue(value["certainty"], "")}</span>
      </div>
      <p>
        {usage
          ? `${numberValue(usage["inputTokens"])} 输入 · ${numberValue(usage["outputTokens"])} 输出 · ${numberValue(usage["totalTokens"])} 合计`
          : "用量未知"}
      </p>
      {typeof value["estimatedContextTokens"] === "number" && (
        <small>上下文估算 {value["estimatedContextTokens"].toLocaleString()} tokens</small>
      )}
    </article>
  );
}

/** 将切页或关闭造成的取消视为正常结束，避免误报 Usage 查询失败。 */
function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

/** 按需展示 schema，使默认工具和服务列表保持紧凑。 */
function CapabilitySchema({
  name,
  description,
  schema,
}: {
  name: string;
  description: string;
  schema: unknown;
}) {
  return (
    <article className="tool-catalog-row">
      <div className="tool-catalog-title">
        <Wrench />
        <div>
          <strong>{name}</strong>
          <span>{description}</span>
        </div>
      </div>
      {schema !== undefined && (
        <details className="schema-detail">
          <summary>
            <ChevronRight />
            输入结构
          </summary>
          <pre>{jsonText(schema)}</pre>
        </details>
      )}
    </article>
  );
}

/** 延迟加载的各能力页面共用一致的空状态与不可用状态。 */
function EmptyState({ icon, title, detail }: { icon: ReactNode; title: string; detail: string }) {
  return (
    <div className="capability-empty">
      <span className="capability-empty-icon">{icon}</span>
      <strong>{title}</strong>
      <p>{detail}</p>
    </div>
  );
}

/** 访问字段前将未知 JSON 内容规范为普通对象。 */
function record(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

/** 将格式错误的列表视为空，避免部分服务端错误破坏面板渲染。 */
function rows<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : [];
}

/** 保留可用的服务端字符串，拒绝对象及其他非预期 JSON 值。 */
function stringValue(value: unknown, fallback: string): string {
  return typeof value === "string" ? value : fallback;
}

/** 只把后端实际写入的 outcome 映射为业务结果，未知值中性展示且不推断审批。 */
function policyFactLabel(fact: Record<string, unknown>): string {
  const payload = record(fact["payload"]);
  const outcome = stringValue(payload?.["outcome"], "");
  const title = stringValue(payload?.["title"], "").trim();
  const titleSuffix = title ? `：${title}` : "";

  switch (fact["type"]) {
    case "document.note.prepared":
      return outcome === "prepared" ? `准备待审批笔记${titleSuffix}` : "资料策略记录";
    case "document.policy.decision":
      if (outcome === "SUCCEEDED") return `保存处理结局：执行成功${titleSuffix}`;
      if (outcome === "FAILED") return `保存处理结局：执行失败${titleSuffix}`;
      if (outcome === "CANCELLED") return `保存处理结局：已取消${titleSuffix}`;
      return "资料策略记录";
    case "document.note.saved":
      return outcome === "saved" ? `已保存笔记${titleSuffix}` : "资料策略记录";
    case "document.delivery.completed":
      return outcome === "delivered" ? `已交付结果${titleSuffix}` : "资料策略记录";
    default:
      return "资料策略记录";
  }
}

/** 格式化可选数值事实，供应商数据缺失时不编造 token 数量。 */
function numberValue(value: unknown): string {
  return typeof value === "number" && Number.isFinite(value) ? value.toLocaleString() : "未知";
}

/** 展示 schema JSON，不将其中的任何字符串当作标记语言执行。 */
function jsonText(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return "无法显示输入结构。";
  }
}

/** 将队列类型转换为简短标签，保留未知枚举值供诊断。 */
function queueKind(value: string): string {
  switch (value) {
    case "STEERING":
      return "即时指导";
    case "FOLLOW_UP":
      return "后续消息";
    case "MESSAGE":
      return "普通消息";
    default:
      return value || "消息";
  }
}

/** 展示服务端执行状态，避免将待处理记录误认为工作已完成。 */
function queueStatus(value: string): string {
  switch (value) {
    case "PENDING":
      return "等待处理";
    case "PROCESSING":
      return "正在处理";
    case "CONSUMED":
      return "已消费";
    case "CANCELLED":
      return "已取消";
    default:
      return value || "状态未知";
  }
}

/** 使用与对话标题相同的简洁措辞展示执行状态。 */
function stateLabel(value: string): string {
  switch (value) {
    case "QUEUED":
    case "RUNNING":
      return "正在工作";
    case "WAITING_APPROVAL":
      return "等待确认";
    case "WAITING_INPUT":
      return "等待回答";
    case "SUSPENDED":
      return "已暂停";
    case "COMPLETED":
      return "已完成";
    case "FAILED":
      return "失败";
    case "CANCELLED":
      return "已取消";
    default:
      return "历史记录";
  }
}

/** 队列修改使用不可预测且唯一的操作键。 */
function randomId(): string {
  return (
    globalThis.crypto?.randomUUID?.().replaceAll("-", "") ??
    `${Date.now()}${Math.random().toString(16).slice(2)}`
  );
}

/** 使用 SkillReferenceContent 要求的稳定目录标识，不发送显示名称。 */
function skillReference(skill: Pick<SkillDescriptor, "name" | "sourceId">): string {
  return `${skill.sourceId}:${skill.name}`;
}

/** 将 HTTP 与传输故障统一转换为用户可读的简洁说明。 */
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "操作失败。";
}
