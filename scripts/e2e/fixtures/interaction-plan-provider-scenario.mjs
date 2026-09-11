// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Interaction/Plan 真窗验收使用的 loopback Provider 语料。
 * 该模块只描述 Provider 的确定性输出；请求、答案、暂停和恢复事实仍由 Java/SQLite 持有。
 */

export const interactionPlanProviderScenario = Object.freeze({
  id: "interaction_plan",
  prompt: "Interaction Plan 真窗验收：请通过结构化问题确认实施范围。",
  interactionPrompts: Object.freeze([
    "Interaction Plan 真窗验收：请通过结构化问题确认实施范围。",
    "Interaction Plan 真窗验收：验证规划阶段拒绝 Shell。",
    "Interaction Plan 真窗验收：验证规划阶段拒绝工作区写入。",
    "Interaction Plan 真窗验收：验证规划阶段拒绝外部写入。",
    "Interaction Plan 真窗验收：验证规划阶段拒绝子代理写入。",
    "Interaction Plan 真窗验收：验证显式跳过不会采用推荐答案。",
    "Interaction Plan 真窗验收：验证取消不会恢复 Turn。",
    "Interaction Plan 真窗验收：验证并发回答只有一个胜者。",
  ]),
  readonlyToolCalls: Object.freeze([
    Object.freeze({ name: "shell", arguments: Object.freeze({ command: "Write-Output JA_PLAN_READONLY_SHELL" }) }),
    Object.freeze({ name: "write", arguments: Object.freeze({ path: "readonly-fixture.txt", content: "must-not-write" }) }),
    Object.freeze({ name: "mcp_write", arguments: Object.freeze({ target: "external-fixture", content: "must-not-write" }) }),
    Object.freeze({ name: "spawn_agent", arguments: Object.freeze({ taskName: "readonly-child", accessMode: "full_access" }) }),
  ]),
  reply: "Interaction Plan 结构化提问已完成。",
  questions: Object.freeze([
    Object.freeze({
      questionId: "question_scope",
      prompt: "本次实施范围？请结合当前项目，选择优先覆盖的部分。界面与交互包含单选、多选、其他答案、键盘操作、输入法、草稿保存与恢复；接口与状态包含稳定身份、并发版本检查、重复提交和断线重试。请选择真正符合本次目标的范围，推荐选项不会自动替你确认。",
      type: "single",
      required: true,
      allowFreeText: false,
      options: Object.freeze([
        Object.freeze({ optionId: "option_ui", label: "界面与交互", description: "验证真实卡片、键盘和恢复。", recommended: true }),
        Object.freeze({ optionId: "option_api", label: "接口与状态", description: "验证 RPC、CAS 和幂等。", recommended: false }),
      ]),
    }),
    Object.freeze({
      questionId: "question_targets",
      prompt: "需要覆盖哪些目标？",
      type: "multiple",
      required: true,
      allowFreeText: false,
      options: Object.freeze([
        Object.freeze({ optionId: "option_plan", label: "Plan", description: "覆盖计划版本与执行。", recommended: true }),
        Object.freeze({ optionId: "option_goal", label: "Goal", description: "验证 Goal 保持独立。", recommended: false }),
      ]),
    }),
    Object.freeze({
      questionId: "question_note",
      prompt: "补充一个验收备注（可选）",
      type: "text",
      required: false,
      allowFreeText: true,
      options: Object.freeze([]),
    }),
  ]),
});

function responseEnvelope(responseId, output) {
  return {
    id: responseId,
    created_at: 0,
    model: "ja-title-loopback-model",
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status: "completed",
    usage: {
      input_tokens: 5,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 5,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 10,
    },
  };
}

function event(type, sequence, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequence, ...payload })}\n\n`;
}

/** 生成真实 Responses function_call；参数含稳定 question/option ID，不含显示文案推导逻辑。 */
export function interactionPlanToolStream(requestNumber) {
  const item = {
    id: `item_interaction_plan_${requestNumber}`,
    type: "function_call",
    call_id: `call_interaction_plan_${requestNumber}`,
    name: "request_user_input",
    arguments: JSON.stringify({ questions: interactionPlanProviderScenario.questions }),
  };
  return [
    event("response.output_item.added", 0, { output_index: 0, item: { ...item, arguments: "" } }),
    event("response.function_call_arguments.done", 1, {
      item_id: item.id,
      output_index: 0,
      arguments: item.arguments,
    }),
    event("response.output_item.done", 2, { output_index: 0, item }),
    event("response.completed", 3, {
      response: responseEnvelope(`resp_interaction_plan_${requestNumber}`, [item]),
    }),
  ].join("");
}

/** 生成规划阶段故意越权的原生 function_call；服务端必须以 ToolResult 拒绝，不能执行副作用。 */
export function interactionPlanReadonlyToolStream(toolName, argumentsValue, requestNumber) {
  const item = {
    id: `item_interaction_plan_readonly_${requestNumber}`,
    type: "function_call",
    call_id: `call_interaction_plan_readonly_${requestNumber}`,
    name: toolName,
    arguments: JSON.stringify(argumentsValue),
  };
  return [
    event("response.output_item.added", 0, { output_index: 0, item: { ...item, arguments: "" } }),
    event("response.function_call_arguments.done", 1, {
      item_id: item.id,
      output_index: 0,
      arguments: item.arguments,
    }),
    event("response.output_item.done", 2, { output_index: 0, item }),
    event("response.completed", 3, {
      response: responseEnvelope(`resp_interaction_plan_readonly_${requestNumber}`, [item]),
    }),
  ].join("");
}

/** 结构化回答回传后结束当前 Turn，避免 fixture 重复发起问题。 */
export function interactionPlanTextStream(requestNumber) {
  const text = interactionPlanProviderScenario.reply;
  const itemId = `message_interaction_plan_${requestNumber}`;
  const item = {
    id: itemId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, { response: { ...responseEnvelope(`resp_interaction_plan_${requestNumber}`, []), status: "in_progress" } }),
    event("response.output_text.delta", 1, { content_index: 0, delta: text, item_id: itemId, output_index: 0 }),
    event("response.output_text.done", 2, { content_index: 0, item_id: itemId, output_index: 0, text }),
    event("response.completed", 3, {
      response: responseEnvelope(`resp_interaction_plan_${requestNumber}`, [item]),
    }),
  ].join("");
}
