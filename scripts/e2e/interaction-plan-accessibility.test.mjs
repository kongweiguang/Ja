// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  captureInteractionPlanAccessibility,
  InteractionPlanAccessibilityError,
  parseInteractionPlanAccessibilityTree,
} from "./interaction-plan-accessibility.mjs";

/** 创建带题目容器、问题组和稳定控件状态的合成 AX tree。 */
function validTree({ groupRole = "radiogroup", controlRole = "radio", required = true } = {}) {
  return [
    {
      nodeId: "root",
      role: { value: "RootWebArea" },
      childIds: ["card"],
    },
    {
      nodeId: "card",
      parentId: "root",
      role: { value: "region" },
      name: { value: "需要确认：正文不得进入证据" },
      childIds: ["group"],
    },
    {
      nodeId: "group",
      parentId: "card",
      role: { value: groupRole },
      name: { value: "选择范围：正文不得进入证据" },
      properties: [{ name: "required", value: { value: required } }],
      childIds: ["first", "second"],
    },
    {
      nodeId: "first",
      parentId: "group",
      role: { value: controlRole },
      name: { value: "选项 A：回答正文不得进入证据" },
      properties: [
        { name: "checked", value: { value: true } },
        { name: "required", value: { value: required } },
      ],
    },
    {
      nodeId: "second",
      parentId: "group",
      role: { value: controlRole },
      name: { value: "选项 B：回答正文不得进入证据" },
      properties: [
        { name: "checked", value: { value: false } },
        { name: "required", value: { value: required } },
      ],
    },
  ];
}

/** 断言辅助技术错误使用稳定 code，而不是依赖错误正文或页面内容。 */
function expectAccessibilityCode(action, code) {
  assert.throws(
    action,
    (error) => error instanceof InteractionPlanAccessibilityError && error.code === code,
  );
}

test("解析 radio AX tree 时只保留精简可访问性证据", () => {
  const evidence = parseInteractionPlanAccessibilityTree(validTree());
  assert.deepEqual(evidence.question, {
    label: { present: true, length: 13 },
    groupLabel: { present: true, length: 13 },
    groupRole: "radiogroup",
    required: true,
  });
  assert.deepEqual(evidence.roleCounts, { radio: 2, checkbox: 0 });
  assert.deepEqual(
    evidence.controls.map(({ role, accessibleName, checked, required }) => ({
      role,
      accessibleName,
      checked,
      required,
    })),
    [
      {
        role: "radio",
        accessibleName: { present: true, length: 15 },
        checked: true,
        required: true,
      },
      {
        role: "radio",
        accessibleName: { present: true, length: 15 },
        checked: false,
        required: true,
      },
    ],
  );
  const serialized = JSON.stringify(evidence);
  assert.doesNotMatch(serialized, /需要确认|选择范围|选项 A|回答正文/u);
  assert.doesNotMatch(serialized, /nodes|questionText|answerText|pageBody/u);
});

test("解析 checkbox AX tree 并允许明确的 optional required=false", () => {
  const evidence = parseInteractionPlanAccessibilityTree(
    validTree({ groupRole: "group", controlRole: "checkbox", required: false }),
  );
  assert.equal(evidence.question.groupRole, "group");
  assert.equal(evidence.question.required, false);
  assert.deepEqual(evidence.roleCounts, { radio: 0, checkbox: 2 });
  assert.deepEqual(
    evidence.controls.map((control) => control.checked),
    [true, false],
  );
});

test("不把 checkbox 默认 required=false 汇总为题目 optional，并支持 group description", () => {
  const tree = validTree({ groupRole: "group", controlRole: "checkbox", required: false });
  tree[2].properties = [];
  tree[2].description = { value: "必填" };
  tree[3].properties = [
    { name: "checked", value: { value: true } },
    { name: "required", value: { value: false } },
  ];
  tree[4].properties = [
    { name: "checked", value: { value: false } },
    { name: "required", value: { value: false } },
  ];

  const evidence = parseInteractionPlanAccessibilityTree(tree, { expectedRequired: true });
  assert.equal(evidence.question.required, true);
  assert.deepEqual(
    evidence.controls.map((control) => control.required),
    [false, false],
  );
});

test("required 期望与明确 group 语义冲突时失败关闭", () => {
  expectAccessibilityCode(
    () =>
      parseInteractionPlanAccessibilityTree(validTree({ required: false }), {
        expectedRequired: true,
      }),
    "AX_REQUIRED_CONFLICT",
  );
});

test("expectedRequired=false 不能把缺失语义当成 optional 通过", () => {
  const tree = validTree({ required: false });
  tree[2].properties = [];
  tree[3].properties = [
    { name: "checked", value: { value: true } },
    { name: "required", value: { value: false } },
  ];
  tree[4].properties = [
    { name: "checked", value: { value: false } },
    { name: "required", value: { value: false } },
  ];

  expectAccessibilityCode(
    () => parseInteractionPlanAccessibilityTree(tree, { expectedRequired: false }),
    "AX_REQUIRED_MISSING",
  );
});

test("标准 required 缺失时只接受问题卡内明确的必填可访问标记", () => {
  const tree = validTree();
  tree[2].properties = [];
  tree[3].properties = [{ name: "checked", value: { value: true } }];
  tree[4].properties = [{ name: "checked", value: { value: false } }];
  tree[1].childIds.push("required-marker");
  tree.push({
    nodeId: "required-marker",
    parentId: "card",
    role: { value: "staticText" },
    name: { value: "必填" },
  });
  const evidence = parseInteractionPlanAccessibilityTree(tree);
  assert.equal(evidence.question.required, true);
  assert.doesNotMatch(JSON.stringify(evidence), /必填/u);
});

test("缺少 AX tree、question label、group label、role、name、checked 或 required 时失败关闭", () => {
  expectAccessibilityCode(() => parseInteractionPlanAccessibilityTree([]), "AX_TREE_MISSING");
  const mutations = [
    ["AX_QUESTION_LABEL_MISSING", (tree) => (tree[1].name = { value: "" })],
    ["AX_QUESTION_GROUP_LABEL_MISSING", (tree) => (tree[2].name = { value: "" })],
    [
      "AX_CONTROL_ROLE_MISSING",
      (tree) => {
        tree[3].role = { value: "button" };
        tree[4].role = { value: "button" };
      },
    ],
    ["AX_CONTROL_NAME_MISSING", (tree) => (tree[3].name = { value: "" })],
    [
      "AX_CHECKED_MISSING",
      (tree) => (tree[3].properties = [{ name: "required", value: { value: true } }]),
    ],
    [
      "AX_REQUIRED_MISSING",
      (tree) => {
        tree[2].properties = [];
        tree[3].properties = [{ name: "checked", value: { value: true } }];
        tree[4].properties = [{ name: "checked", value: { value: false } }];
      },
    ],
  ];
  for (const [code, mutate] of mutations) {
    const tree = structuredClone(validTree());
    mutate(tree);
    expectAccessibilityCode(() => parseInteractionPlanAccessibilityTree(tree), code);
  }
});

test("capture 只调用 Accessibility.getFullAXTree，释放自有 CDP session 且不操作页面", async () => {
  const calls = [];
  let detached = 0;
  const client = {
    async send(method) {
      calls.push(method);
      return { nodes: validTree() };
    },
    async detach() {
      detached += 1;
    },
  };
  let contextCalls = 0;
  const page = {
    context() {
      contextCalls += 1;
      return { newCDPSession: async () => client };
    },
  };
  const evidence = await captureInteractionPlanAccessibility(page);
  assert.equal(contextCalls, 1);
  assert.deepEqual(calls, ["Accessibility.getFullAXTree"]);
  assert.equal(detached, 1);
  assert.equal(evidence.surface, "webview2_cdp");
});

test("外部注入的 CDP session 不被 helper 关闭，供只读规则测试复用", async () => {
  let detached = 0;
  const client = {
    async send() {
      return { nodes: validTree({ groupRole: "group", controlRole: "checkbox" }) };
    },
    async detach() {
      detached += 1;
    },
  };
  const evidence = await captureInteractionPlanAccessibility({}, { cdpSession: client });
  assert.equal(evidence.roleCounts.checkbox, 2);
  assert.equal(detached, 0);
});

test("CDP 读取失败返回稳定错误且不泄漏底层正文", async () => {
  const page = {
    context() {
      return {
        newCDPSession: async () => ({
          async send() {
            throw new Error("页面正文 secret-provider-answer");
          },
          async detach() {},
        }),
      };
    },
  };
  await assert.rejects(
    () => captureInteractionPlanAccessibility(page),
    (error) =>
      error instanceof InteractionPlanAccessibilityError &&
      error.code === "AX_TREE_UNAVAILABLE" &&
      !error.message.includes("secret-provider-answer"),
  );
});
