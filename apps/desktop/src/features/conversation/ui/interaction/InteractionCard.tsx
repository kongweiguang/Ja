// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Check,
  ChevronLeft,
  ChevronRight,
  LoaderCircle,
  Minimize2,
  RotateCcw,
  Send,
} from "lucide-react";
import { useId, useMemo, useState, type ChangeEvent, type ReactElement } from "react";
import type { InteractionOption } from "../../application/interactionPort";
import type { InteractionController } from "../../application/useInteractionController";
import "./interaction.css";

interface InteractionCardProps {
  controller: InteractionController;
}

/**
 * 将结构化问题投影为逐题决策面板；卡片只决定输入节奏和可访问性，草稿、CAS、重试及 Thread
 * 隔离仍完全由 controller 持有，避免视觉换题产生第二份可提交状态。
 */
export function InteractionCard({ controller }: InteractionCardProps): ReactElement | null {
  const titleId = useId();
  const requiredDescriptionId = `${titleId}-required`;
  // 交互卡不会因 requestId 更新而 remount；局部校验必须绑定服务端请求，避免旧请求的失败提示
  // 在下一轮提问首次打开时泄漏出来。答案、草稿与提交幂等性仍由 controller 统一持有。
  const [validationQuestionKey, setValidationQuestionKey] = useState<string>();
  const [submitAttemptedRequestId, setSubmitAttemptedRequestId] = useState<string>();
  const { request, answers, pageIndex, collapsed } = controller;
  const question = request?.questions[pageIndex];
  const validation = useMemo(() => {
    // 留空可选题在提交边界统一结算为 skipped；界面阶段不制造伪答案，也不阻断翻题。
    if (question === undefined || !question.required) return undefined;
    const answer = answers[question.questionId];
    if (answer === undefined) return "请选择一项或填写答案。";
    if (answer.skipped) return undefined;
    if (question.type === "text" && !answer.freeText?.trim()) return "请填写答案。";
    if (
      question.type !== "text" &&
      answer.optionIds.length === 0 &&
      (!question.allowFreeText || !answer.freeText?.trim())
    )
      return "请选择一项或填写答案。";
    return undefined;
  }, [answers, question]);

  if (request === null && controller.error !== undefined) {
    return (
      <section className="ja-interaction-card" aria-label="问题加载失败">
        <p className="ja-interaction-card__error" role="alert">
          {controller.error}
        </p>
        <button
          type="button"
          className="ja-interaction-card__primary-button"
          onClick={() => void controller.refresh()}
        >
          重新加载问题
        </button>
      </section>
    );
  }
  if (request === null || request.status === "cancelled" || request.status === "superseded")
    return null;
  // 已回答事实已经由 request_user_input 的 ToolResult 进入 Timeline；Composer 只保留待回答卡，
  // 避免恢复阶段的 resumeState 更新把历史记录重新挂回输入区并造成布局抖动。
  if (request.status === "answered" || controller.answered) return null;
  if (collapsed) {
    return (
      <section
        className="ja-interaction-card is-collapsed"
        data-interaction-card="true"
        data-interaction-thread-id={request.threadId}
        data-interaction-status="pending-collapsed"
        data-request-id={request.requestId}
        aria-label="待回答的问题"
      >
        <div className="ja-interaction-card__summary-heading">
          <span className="ja-interaction-card__pending-dot" aria-hidden="true" />
          <strong>等待你的回答</strong>
          <span>{request.questions.length} 个问题</span>
          <button
            type="button"
            className="ja-interaction-card__icon-button"
            onClick={() => controller.setCollapsed(false)}
            aria-label="展开问题"
            title="展开问题"
          >
            <ChevronRight aria-hidden="true" />
          </button>
        </div>
      </section>
    );
  }
  if (controller.loading && question === undefined)
    return (
      <section
        className="ja-interaction-card"
        data-interaction-card="true"
        data-interaction-thread-id={request.threadId}
        aria-label="交互问题"
      >
        <LoaderCircle className="ja-interaction-card__spin" aria-label="正在加载问题" />
      </section>
    );
  if (question === undefined) return null;

  const answer = answers[question.questionId];
  const selectedOptionIds = answer?.optionIds ?? [];
  const otherSelected = answer?.freeText !== null && answer?.freeText !== undefined;
  const isLastQuestion = pageIndex === request.questions.length - 1;
  const questionKey = `${request.requestId}:${question.questionId}`;
  const shouldShowValidation =
    validation !== undefined &&
    (validationQuestionKey === questionKey || submitAttemptedRequestId === request.requestId);
  const needsExplicitAdvance =
    question.type !== "single" ||
    otherSelected ||
    (!question.required && selectedOptionIds.length === 0);

  /**
   * 翻页只拦截必答题，允许可选题保持真正的空草稿；最终提交再把空可选题规范为 skipped，避免
   * 为展示“跳过”而提前写入用户并未作出的答案。
   */
  const advance = (): void => {
    if (validation !== undefined) {
      setValidationQuestionKey(questionKey);
      return;
    }
    setValidationQuestionKey(undefined);
    controller.next();
  };
  /** “其他答案”保持自由文本语义，不伪造服务端不存在的 option ID。 */
  const setOther = (value: string): void => {
    setValidationQuestionKey(undefined);
    controller.setAnswer(question.questionId, {
      questionId: question.questionId,
      optionIds: question.type === "multiple" ? selectedOptionIds : [],
      freeText: value,
      skipped: false,
    });
  };
  /**
   * 单选在确认选项后直接推进以匹配逐题决策节奏，最后一题仍停留等待显式提交；多选只更新
   * 稳定 option ID，保留用户已输入的自由文本。
   */
  const selectOption = (option: InteractionOption, checked: boolean): void => {
    setValidationQuestionKey(undefined);
    if (question.type === "single") {
      controller.setAnswer(question.questionId, {
        questionId: question.questionId,
        optionIds: [option.optionId],
        freeText: null,
        skipped: false,
      });
      if (!isLastQuestion) controller.next();
      return;
    }
    controller.setAnswer(question.questionId, {
      questionId: question.questionId,
      optionIds: checked
        ? selectedOptionIds.filter((id) => id !== option.optionId)
        : [...selectedOptionIds, option.optionId],
      freeText: answer?.freeText ?? null,
      skipped: false,
    });
  };
  /** 提交前先标记当前验证状态，controller 仍负责跨题检查、CAS 与单飞提交。 */
  const submit = (): void => {
    setSubmitAttemptedRequestId(request.requestId);
    if (validation !== undefined) {
      setValidationQuestionKey(questionKey);
      return;
    }
    void controller.submit();
  };

  return (
    <section
      className="ja-interaction-card"
      data-interaction-card="true"
      data-interaction-thread-id={request.threadId}
      data-interaction-status="pending"
      data-request-id={request.requestId}
      data-question-id={question.questionId}
      tabIndex={0}
      aria-labelledby={titleId}
    >
      <header className="ja-interaction-card__header">
        <h2 id={titleId}>
          {question.prompt}
          {question.required ? (
            <span
              id={requiredDescriptionId}
              className="ja-interaction-card__required"
              aria-label="必填"
            >
              必答
            </span>
          ) : null}
        </h2>
        <nav className="ja-interaction-card__navigation" aria-label="问题导航">
          <button
            type="button"
            className="ja-interaction-card__icon-button"
            disabled={pageIndex === 0}
            onClick={controller.previous}
            aria-label="前往上一题"
            title="上一题"
          >
            <ChevronLeft aria-hidden="true" />
          </button>
          <span aria-live="polite">
            {pageIndex + 1} / {request.questions.length}
          </span>
          <button
            type="button"
            className="ja-interaction-card__icon-button"
            disabled={isLastQuestion}
            onClick={advance}
            aria-label="前往下一题"
            title="下一题"
          >
            <ChevronRight aria-hidden="true" />
          </button>
          <button
            type="button"
            className="ja-interaction-card__icon-button"
            onClick={() => controller.setCollapsed(true)}
            aria-label="收起问题"
            title="收起问题"
          >
            <Minimize2 aria-hidden="true" />
          </button>
        </nav>
      </header>
      <div className="ja-interaction-card__body" key={question.questionId}>
        {question.type === "text" ? (
          <textarea
            className="ja-interaction-card__text-input"
            value={answer?.freeText ?? ""}
            placeholder="写下你的要求"
            aria-label={question.prompt}
            aria-required={question.required}
            required={question.required}
            aria-describedby={question.required ? requiredDescriptionId : undefined}
            onChange={(event: ChangeEvent<HTMLTextAreaElement>) => setOther(event.target.value)}
          />
        ) : (
          <div
            className="ja-interaction-card__options"
            role={question.type === "single" ? "radiogroup" : "group"}
            aria-label={question.prompt}
            aria-required={question.type === "single" ? question.required : undefined}
            aria-describedby={question.required ? requiredDescriptionId : undefined}
          >
            {question.options?.map((option: InteractionOption, index: number) => {
              const checked = selectedOptionIds.includes(option.optionId);
              return (
                <label
                  className="ja-interaction-card__option"
                  data-selected={checked || undefined}
                  data-option-id={option.optionId}
                  key={option.optionId}
                >
                  <input
                    type={question.type === "single" ? "radio" : "checkbox"}
                    name={`interaction-${request.requestId}-${question.questionId}`}
                    checked={checked}
                    onChange={() => selectOption(option, checked)}
                  />
                  <span className="ja-interaction-card__option-index" aria-hidden="true">
                    {index + 1}
                  </span>
                  <span className="ja-interaction-card__option-copy">
                    <strong>{option.label}</strong>
                    {option.description ? <small>{option.description}</small> : null}
                  </span>
                  {option.recommended ? (
                    <span className="ja-interaction-card__recommended">推荐</span>
                  ) : null}
                  <span className="ja-interaction-card__option-affordance" aria-hidden="true">
                    {checked ? <Check /> : question.type === "single" ? <ChevronRight /> : null}
                  </span>
                </label>
              );
            })}
            {question.allowFreeText ? (
              <>
                <label
                  className="ja-interaction-card__option"
                  data-selected={otherSelected || undefined}
                  data-option-id="other"
                >
                  <input
                    type={question.type === "single" ? "radio" : "checkbox"}
                    name={`interaction-${request.requestId}-${question.questionId}`}
                    checked={otherSelected}
                    onChange={() => {
                      setValidationQuestionKey(undefined);
                      if (question.type === "single") {
                        setOther("");
                        return;
                      }
                      controller.setAnswer(question.questionId, {
                        questionId: question.questionId,
                        optionIds: selectedOptionIds,
                        freeText: otherSelected ? null : "",
                        skipped: false,
                      });
                    }}
                  />
                  <span className="ja-interaction-card__option-index" aria-hidden="true">
                    {(question.options?.length ?? 0) + 1}
                  </span>
                  <span className="ja-interaction-card__option-copy">
                    <strong>其他答案</strong>
                    <small>用自己的话说明偏好</small>
                  </span>
                  <span className="ja-interaction-card__option-affordance" aria-hidden="true">
                    {otherSelected ? <Check /> : null}
                  </span>
                </label>
                {otherSelected ? (
                  <input
                    className="ja-interaction-card__other-input"
                    value={answer?.freeText ?? ""}
                    placeholder="输入其他答案"
                    aria-label="其他答案"
                    onChange={(event) => setOther(event.target.value)}
                  />
                ) : null}
              </>
            ) : null}
          </div>
        )}
        {shouldShowValidation ? (
          <p className="ja-interaction-card__validation" role="alert">
            {validation}
          </p>
        ) : null}
      </div>
      <footer className="ja-interaction-card__footer">
        <span className="ja-interaction-card__footer-spacer" />
        {!isLastQuestion && needsExplicitAdvance ? (
          <button type="button" className="ja-interaction-card__primary-button" onClick={advance}>
            下一题
            <ChevronRight aria-hidden="true" />
          </button>
        ) : null}
        {isLastQuestion ? (
          <button
            type="button"
            className="ja-interaction-card__primary-button"
            disabled={controller.submitting}
            onClick={submit}
          >
            {controller.submitting ? (
              <LoaderCircle className="ja-interaction-card__spin" aria-hidden="true" />
            ) : (
              <Send aria-hidden="true" />
            )}
            提交回答
          </button>
        ) : null}
        {controller.retryAction !== undefined ? (
          <button
            type="button"
            className="ja-interaction-card__retry-button"
            onClick={() =>
              void (controller.retryAction === "draft"
                ? controller.retryDraft()
                : controller.retrySubmit())
            }
            aria-label={controller.retryAction === "draft" ? "重试保存" : "重试提交"}
            title={controller.retryAction === "draft" ? "重试保存" : "重试提交"}
          >
            <RotateCcw aria-hidden="true" />
          </button>
        ) : null}
        {controller.error ? (
          <p className="ja-interaction-card__error" role="alert">
            {controller.error}
          </p>
        ) : null}
      </footer>
    </section>
  );
}
