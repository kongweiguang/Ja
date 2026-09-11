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
import { useId, useMemo, type ChangeEvent, type ReactElement } from "react";
import type { InteractionController } from "../../application/useInteractionController";
import type {
  InteractionAnswer,
  InteractionOption,
  InteractionQuestion,
} from "../../application/interactionPort";
import "./interaction.css";

interface InteractionCardProps {
  controller: InteractionController;
}

/** 依据当前问题定义渲染摘要，避免把服务端不存在的展示字段当成协议事实。 */
function answerLabel(question: InteractionQuestion, answer: InteractionAnswer | undefined): string {
  if (answer === undefined || answer.skipped) return "未回答";
  const labels = (question.options ?? [])
    .filter((option) => answer.optionIds.includes(option.optionId))
    .map((option) => option.label);
  if (answer.freeText !== null && answer.freeText.trim() !== "") labels.push(answer.freeText);
  return labels.join("、") || "未回答";
}

/** 将结构化问题投影为非模态卡片；状态、CAS、重试和 Thread 隔离全部由 controller 拥有。 */
export function InteractionCard({ controller }: InteractionCardProps): ReactElement | null {
  const titleId = useId();
  const requiredDescriptionId = `${titleId}-required`;
  const { request, answers, pageIndex, collapsed } = controller;
  const question = request?.questions[pageIndex];
  const validation = useMemo(() => {
    if (question === undefined) return undefined;
    const answer = answers[question.questionId];
    if (answer === undefined || answer.skipped)
      return question.required ? "请回答此问题。" : undefined;
    if (question.type === "text" && !answer.freeText?.trim()) return "请回答此问题。";
    if (
      question.type !== "text" &&
      answer.optionIds.length === 0 &&
      (!question.allowFreeText || !answer.freeText?.trim())
    )
      return "请选择一项或填写其他答案。";
    return undefined;
  }, [answers, question]);

  if (request === null && controller.error !== undefined) {
    return (
      <section className="ja-interaction-card" aria-label="问题加载失败">
        <p role="alert">{controller.error}</p>
        <button type="button" onClick={() => void controller.refresh()}>
          重新加载问题
        </button>
      </section>
    );
  }
  if (request === null || request.status === "cancelled" || request.status === "superseded")
    return null;
  if (request.status === "answered" || controller.answered) {
    return (
      <section
        className="ja-interaction-card is-collapsed"
        data-interaction-card="true"
        data-interaction-thread-id={request.threadId}
        data-interaction-status="answered"
        data-request-id={request.requestId}
        aria-label="已回答的问题"
      >
        <div className="ja-interaction-card__summary-heading">
          <Check aria-hidden="true" />
          <strong>
            {controller.resumeState === "waiting_to_resume" ? "回答已保存，等待继续" : "问题已回答"}
          </strong>
          <button
            type="button"
            className="ja-interaction-card__icon-button"
            aria-label={collapsed ? "展开回答" : "收起回答"}
            aria-expanded={!collapsed}
            onClick={() => controller.setCollapsed(!collapsed)}
          >
            <ChevronRight aria-hidden="true" />
          </button>
        </div>
        {!collapsed && request.questions.length ? (
          <ul className="ja-interaction-card__summary-list">
            {request.questions.map((item) => (
              <li key={item.questionId}>
                <span>{item.prompt}</span>
                <strong>{answerLabel(item, controller.answers[item.questionId])}</strong>
              </li>
            ))}
          </ul>
        ) : !collapsed ? (
          <p className="ja-interaction-card__muted">已提交回答。</p>
        ) : null}
      </section>
    );
  }
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
  /** “其他答案”保持自由文本语义，不伪造服务端不存在的 option ID。 */
  const setOther = (value: string): void =>
    controller.setAnswer(question.questionId, {
      questionId: question.questionId,
      optionIds: question.type === "multiple" ? selectedOptionIds : [],
      freeText: value,
      skipped: false,
    });
  /**
   * 单选替换当前选择并清理互斥的 Other 文本；多选只增删稳定 option ID，保留用户已经填写的自由文本。
   */
  const selectOption = (option: InteractionOption, checked: boolean): void => {
    if (question.type === "single")
      controller.setAnswer(question.questionId, {
        questionId: question.questionId,
        optionIds: [option.optionId],
        freeText: null,
        skipped: false,
      });
    else
      controller.setAnswer(question.questionId, {
        questionId: question.questionId,
        optionIds: checked
          ? selectedOptionIds.filter((id) => id !== option.optionId)
          : [...selectedOptionIds, option.optionId],
        freeText: answer?.freeText ?? null,
        skipped: false,
      });
  };

  return (
    <section
      className="ja-interaction-card"
      data-interaction-card="true"
      data-interaction-thread-id={request.threadId}
      data-interaction-status={collapsed ? "pending-collapsed" : "pending"}
      data-request-id={request.requestId}
      data-question-id={question.questionId}
      tabIndex={0}
      aria-labelledby={titleId}
    >
      <header className="ja-interaction-card__header">
        <div>
          <p className="ja-interaction-card__eyebrow">
            需要你的选择 · {pageIndex + 1} / {request.questions.length}
          </p>
          <h2 id={titleId}>需要确认</h2>
        </div>
        <button
          type="button"
          className="ja-interaction-card__icon-button"
          onClick={() => controller.setCollapsed(true)}
          aria-label="收起问题"
          title="收起问题"
        >
          <Minimize2 aria-hidden="true" />
        </button>
      </header>
      <div className="ja-interaction-card__body">
        <h3>
          {question.prompt}
          {question.required ? (
            <span id={requiredDescriptionId} aria-label="必填">
              {" *"}
            </span>
          ) : null}
        </h3>
        {question.type === "text" ? (
          <textarea
            className="ja-interaction-card__text-input"
            value={answer?.freeText ?? ""}
            placeholder="请输入"
            aria-label={question.prompt}
            aria-required={question.required}
            required={question.required}
            aria-describedby={question.required ? requiredDescriptionId : undefined}
            onChange={(event: ChangeEvent<HTMLTextAreaElement>) =>
              controller.setAnswer(question.questionId, {
                questionId: question.questionId,
                optionIds: [],
                freeText: event.target.value,
                skipped: false,
              })
            }
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
                  <span className="ja-interaction-card__option-index">{index + 1}</span>
                  <span className="ja-interaction-card__option-copy">
                    <strong>{option.label}</strong>
                    {option.description ? <small>{option.description}</small> : null}
                  </span>
                  {option.recommended ? (
                    <span className="ja-interaction-card__recommended">推荐</span>
                  ) : null}
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
                    onChange={() =>
                      question.type === "single"
                        ? setOther("")
                        : controller.setAnswer(question.questionId, {
                            questionId: question.questionId,
                            optionIds: selectedOptionIds,
                            freeText: otherSelected ? null : "",
                            skipped: false,
                          })
                    }
                  />
                  <span className="ja-interaction-card__option-index">
                    {(question.options?.length ?? 0) + 1}
                  </span>
                  <span className="ja-interaction-card__option-copy">
                    <strong>其他答案</strong>
                    <small>用自己的话说明偏好</small>
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
        {validation && controller.error !== undefined ? (
          <p className="ja-interaction-card__validation" role="alert">
            {validation}
          </p>
        ) : null}
      </div>
      <footer className="ja-interaction-card__footer">
        <button
          type="button"
          className="ja-interaction-card__text-button"
          disabled={pageIndex === 0}
          onClick={controller.previous}
        >
          <ChevronLeft aria-hidden="true" />
          上一题
        </button>
        <span className="ja-interaction-card__footer-spacer" />
        {!question.required ? (
          <button
            type="button"
            className="ja-interaction-card__text-button"
            onClick={() => {
              controller.setAnswer(question.questionId, {
                questionId: question.questionId,
                optionIds: [],
                freeText: null,
                skipped: true,
              });
              if (pageIndex < request.questions.length - 1) controller.next();
            }}
          >
            跳过
          </button>
        ) : null}
        {pageIndex === request.questions.length - 1 ? (
          <button
            type="button"
            className="ja-interaction-card__primary-button"
            disabled={controller.submitting}
            onClick={() => void controller.submit()}
          >
            {controller.submitting ? (
              <LoaderCircle className="ja-interaction-card__spin" aria-hidden="true" />
            ) : (
              <Send aria-hidden="true" />
            )}
            提交
          </button>
        ) : (
          <button
            type="button"
            className="ja-interaction-card__primary-button"
            onClick={controller.next}
          >
            下一题
            <ChevronRight aria-hidden="true" />
          </button>
        )}
        {controller.error ? (
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
