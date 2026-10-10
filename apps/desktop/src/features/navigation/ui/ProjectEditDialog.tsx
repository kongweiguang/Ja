// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type MutableRefObject,
  type ReactElement,
} from "react";
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/shared/ui/primitives";
import { WorkspaceProjectUpdateError, type WorkspaceProjection } from "@/features/workspace";
import "./projectEditDialog.css";

export interface ProjectEditDialogProps {
  project: WorkspaceProjection | undefined;
  busy: boolean;
  onClose(): void;
  onSave(input: ProjectEditInput): Promise<void>;
  onRecheck(workspaceId: string): Promise<WorkspaceProjection>;
  onChooseDirectory(initialDirectory: string): Promise<string | null>;
  /** 因弹窗由应用级组合层托管而没有 DialogTrigger，关闭后须显式归还原项目行焦点。 */
  onRestoreFocus?: () => void;
}

/** CAS 更新始终携带打开弹窗时读取到的 revision，防止并发编辑覆盖目录或名称。 */
export interface ProjectEditInput {
  workspaceId: string;
  expectedRevision: number;
  displayName: string;
  cwd: string;
}

/** 用户取消未保存文件确认属于正常流程，不应将留在原 Dialog 的草稿标成错误。 */
function isUserCancelled(error: unknown): boolean {
  return (
    error !== null && typeof error === "object" && "name" in error && error.name === "AbortError"
  );
}

/**
 * 项目编辑 Dialog 由 composition 持有目标身份，卸载侧栏或收起窄窗口不会丢弃用户草稿。
 * @author kongweiguang
 */
export function ProjectEditDialog({
  project,
  busy,
  onClose,
  onSave,
  onRecheck,
  onChooseDirectory,
  onRestoreFocus,
}: ProjectEditDialogProps): ReactElement {
  const pendingRef = useRef(false);

  /** 保存期间忽略 Escape/遮罩关闭，失败时仍保留草稿供用户修正。 */
  const handleOpenChange = (open: boolean): void => {
    if (!open && !pendingRef.current) onClose();
  };

  return (
    <Dialog open={project !== undefined} onOpenChange={handleOpenChange}>
      {project === undefined ? null : (
        <ProjectEditSession
          key={project.workspaceId}
          project={project}
          busy={busy}
          pendingRef={pendingRef}
          onClose={onClose}
          onSave={onSave}
          onRecheck={onRecheck}
          onChooseDirectory={onChooseDirectory}
          onRestoreFocus={onRestoreFocus}
        />
      )}
    </Dialog>
  );
}

interface ProjectEditSessionProps extends Omit<ProjectEditDialogProps, "project"> {
  project: WorkspaceProjection;
  pendingRef: MutableRefObject<boolean>;
}

/**
 * 编辑 session 在 Dialog 打开期间独占草稿；更新失败保留用户输入，避免丢失长路径或名称。
 * @author kongweiguang
 */
function ProjectEditSession({
  project,
  busy,
  pendingRef,
  onClose,
  onSave,
  onRecheck,
  onChooseDirectory,
  onRestoreFocus,
}: ProjectEditSessionProps): ReactElement {
  const [displayName, setDisplayName] = useState(project.displayName);
  const [cwd, setCwd] = useState(project.rootPath);
  const [pending, setPending] = useState(false);
  const [choosingDirectory, setChoosingDirectory] = useState(false);
  const [rechecking, setRechecking] = useState(false);
  const [error, setError] = useState<string>();
  const [requiresRecheck, setRequiresRecheck] = useState(false);
  const [recheckNotice, setRecheckNotice] = useState(false);
  const [authoritativeProject, setAuthoritativeProject] = useState<WorkspaceProjection>();
  const baseProjectRef = useRef(project);
  const expectedRevisionRef = useRef(project.revision);
  const pickerOrRecheckPendingRef = useRef(false);
  const requiresRecheckRef = useRef(false);
  const sessionActiveRef = useRef(true);

  /** 原生 picker 可跨 Dialog 关闭返回；生命周期标记阻止旧 session 回写草稿或触发无效渲染。 @author kongweiguang */
  useEffect(() => {
    sessionActiveRef.current = true;
    return () => {
      sessionActiveRef.current = false;
    };
  }, []);

  /** picker 与权威复核共用单实例门禁；关闭后到达的旧 native 结果不得写入新 session。 @author kongweiguang */
  const chooseDirectory = async (): Promise<void> => {
    if (pendingRef.current || pickerOrRecheckPendingRef.current || busy) return;
    pickerOrRecheckPendingRef.current = true;
    setChoosingDirectory(true);
    if (!requiresRecheckRef.current) setError(undefined);
    setRecheckNotice(false);
    try {
      const selected = await onChooseDirectory(cwd || project.rootPath);
      if (!sessionActiveRef.current) return;
      if (selected !== null) setCwd(selected);
    } catch (cause: unknown) {
      if (!sessionActiveRef.current) return;
      setError(cause instanceof Error ? cause.message : "无法选择项目目录，请重试。");
    } finally {
      pickerOrRecheckPendingRef.current = false;
      if (sessionActiveRef.current) setChoosingDirectory(false);
    }
  };

  /** 先核实权威资料并完成必要恢复；保留相对旧基线修改的字段并刷新未修改字段与 CAS 版本。 @author kongweiguang */
  const recheckProject = async (): Promise<void> => {
    if (
      !requiresRecheckRef.current ||
      pendingRef.current ||
      pickerOrRecheckPendingRef.current ||
      busy
    )
      return;
    pickerOrRecheckPendingRef.current = true;
    setRechecking(true);
    try {
      const current = await onRecheck(project.workspaceId);
      if (!sessionActiveRef.current) return;
      if (
        current.workspaceId !== project.workspaceId ||
        current.kind !== "project" ||
        typeof current.revision !== "number" ||
        !Number.isSafeInteger(current.revision)
      )
        throw new Error("重新检查返回的项目资料无效，请重试。");

      const previousBase = baseProjectRef.current;
      const keepDisplayName = displayName !== previousBase.displayName;
      const keepCwd = cwd !== previousBase.rootPath;
      baseProjectRef.current = current;
      expectedRevisionRef.current = current.revision;
      setDisplayName(keepDisplayName ? displayName : current.displayName);
      setCwd(keepCwd ? cwd : current.rootPath);
      setAuthoritativeProject(current);
      requiresRecheckRef.current = false;
      setRequiresRecheck(false);
      setError(undefined);
      setRecheckNotice(true);
    } catch (cause: unknown) {
      if (!sessionActiveRef.current || isUserCancelled(cause)) return;
      setError(cause instanceof Error ? cause.message : "项目资料未能重新检查，请重试。");
    } finally {
      pickerOrRecheckPendingRef.current = false;
      if (sessionActiveRef.current) setRechecking(false);
    }
  };

  /** 只提交归一化草稿和原 revision；同步 ref 阻止保存/picker 竞态，旧 session 不关闭新 Dialog。 @author kongweiguang */
  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const normalizedName = displayName.trim();
    const normalizedCwd = cwd.trim();
    if (
      pendingRef.current ||
      pickerOrRecheckPendingRef.current ||
      requiresRecheckRef.current ||
      busy ||
      normalizedName.length === 0 ||
      normalizedCwd.length === 0
    )
      return;
    const expectedRevision = expectedRevisionRef.current;
    if (typeof expectedRevision !== "number" || !Number.isSafeInteger(expectedRevision)) {
      setError("项目版本信息不可用，请关闭后重新打开项目编辑。");
      return;
    }
    pendingRef.current = true;
    setPending(true);
    setError(undefined);
    setRecheckNotice(false);
    setAuthoritativeProject(undefined);
    void Promise.resolve()
      .then(() =>
        onSave({
          workspaceId: project.workspaceId,
          expectedRevision,
          displayName: normalizedName,
          cwd: normalizedCwd,
        }),
      )
      .then(() => {
        if (sessionActiveRef.current) onClose();
      })
      .catch((cause: unknown) => {
        if (!sessionActiveRef.current) return;
        if (isUserCancelled(cause)) return;
        if (
          cause instanceof WorkspaceProjectUpdateError &&
          (cause.reason === "revision_conflict" || cause.reason === "result_unknown")
        ) {
          requiresRecheckRef.current = true;
          setRequiresRecheck(true);
          setAuthoritativeProject(cause.currentWorkspace);
        }
        setError(cause instanceof Error ? cause.message : "项目保存失败，请重试。");
      })
      .finally(() => {
        pendingRef.current = false;
        if (sessionActiveRef.current) setPending(false);
      });
  };

  /** 名称和路径独立编辑，输入事件只清除旧错误而不改变另一字段。 @author kongweiguang */
  const changeDisplayName = (event: ChangeEvent<HTMLInputElement>): void => {
    setDisplayName(event.currentTarget.value);
    if (!requiresRecheckRef.current) setError(undefined);
    setRecheckNotice(false);
  };

  /** 直接保留用户粘贴的长路径；实际规范化和目录校验留给 authoritative update。 @author kongweiguang */
  const changeCwd = (event: ChangeEvent<HTMLInputElement>): void => {
    setCwd(event.currentTarget.value);
    if (!requiresRecheckRef.current) setError(undefined);
    setRecheckNotice(false);
  };

  /** 把异步 picker 的拒绝交给同一错误区处理，并保留页面级事件同步语义。 */
  const handleChooseDirectory = (): void => {
    void chooseDirectory();
  };

  /** 用户必须先核对当前数据库投影，不能用失效 CAS revision 直接再次提交。 @author kongweiguang */
  const handleRecheckProject = (): void => {
    void recheckProject();
  };

  /** 弹窗由 composition 托管且没有 Radix DialogTrigger；关闭后显式聚焦回原项目行。 */
  const restoreFocusAfterClose = (event: Event): void => {
    if (onRestoreFocus === undefined) return;
    event.preventDefault();
    onRestoreFocus();
  };

  const disabled = busy || pending || choosingDirectory || rechecking;
  return (
    <DialogContent
      className="ja-project-edit-dialog"
      aria-describedby="ja-project-edit-description"
      onCloseAutoFocus={restoreFocusAfterClose}
    >
      <DialogTitle>编辑项目</DialogTitle>
      <DialogDescription id="ja-project-edit-description">
        修改 Ja 中显示的名称或项目所在目录。历史对话会保留在当前项目中。
      </DialogDescription>
      <form onSubmit={submit}>
        <label>
          <span>项目名称</span>
          <input
            autoFocus
            aria-label="项目名称"
            value={displayName}
            maxLength={512}
            disabled={disabled}
            onChange={changeDisplayName}
          />
        </label>
        <label>
          <span>项目目录</span>
          <span className="ja-project-edit-path-help">
            更改目录会保留对话，并关闭旧目录的终端和本地预览。
          </span>
          <span className="ja-project-edit-path-row">
            <input
              aria-label="项目目录"
              value={cwd}
              maxLength={4_096}
              disabled={disabled}
              onChange={changeCwd}
            />
            <Button
              type="button"
              variant="secondary"
              disabled={disabled}
              onClick={handleChooseDirectory}
            >
              {choosingDirectory ? "选择中…" : "选择文件夹"}
            </Button>
          </span>
        </label>
        {error === undefined && !requiresRecheck ? null : (
          <div className="ja-project-edit-error" role="alert">
            {error ?? "项目版本需要复核后才能继续保存。"}
            {authoritativeProject === undefined ? null : (
              <span className="ja-project-edit-error-details">
                <span>最新名称：{authoritativeProject.displayName}</span>
                <span>最新目录：{authoritativeProject.rootPath}</span>
              </span>
            )}
            {!requiresRecheck ? null : (
              <Button
                type="button"
                variant="secondary"
                disabled={disabled}
                onClick={handleRecheckProject}
              >
                {rechecking ? "检查中…" : "重新检查"}
              </Button>
            )}
          </div>
        )}
        {!recheckNotice ? null : (
          <p className="ja-project-edit-recheck-notice" role="status">
            已读取最新资料，已保留你的修改，请核对后保存。
          </p>
        )}
        <div className="ja-project-edit-actions">
          <Button type="button" variant="ghost" disabled={pending} onClick={onClose}>
            取消
          </Button>
          <Button
            type="submit"
            disabled={disabled || requiresRecheck || displayName.trim() === "" || cwd.trim() === ""}
          >
            {pending ? "保存中…" : "保存"}
          </Button>
        </div>
      </form>
    </DialogContent>
  );
}
