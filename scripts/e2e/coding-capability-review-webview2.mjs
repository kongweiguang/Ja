// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { readFile, realpath, stat, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { providerStream } from "./media-schema-loopback.mjs";
import {
  assertOwnedTemporaryPath,
  parseArguments,
  runPreflight,
  runProduction,
} from "./review-redesign-production.mjs";
import {
  readRuntimeEndpointMetadata,
  requestRunScopedRuntimeShutdown,
} from "./windows-desktop-smoke.mjs";
import {
  CODING_REVIEW_AI_NARROW_MATRIX,
  CODING_REVIEW_AI_PALETTES,
  CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX,
  captureCodingReviewAiNarrowMatrix,
  captureCodingReviewAiPaletteMatrix,
  installReviewInvokeProbe,
  normalizeDevicePixelRatio,
  requireVisibleReviewScopeTrigger,
  runReviewRedesignWebView2,
  selectReviewScopeOption,
  waitForCodingReviewDiffReadiness,
} from "./review-redesign-webview2-driver.mjs";

export { normalizeDevicePixelRatio };

/** 仅保留 runner/Playwright 调用帧，定位失败 wait 而不把测试数据或本机绝对路径写入诊断。 */
export function summarizeCodingReviewFailure(error, workspaceDirectory = process.cwd()) {
  const chain = [];
  let current = error;
  while (current !== null && current !== undefined && chain.length < 4) {
    const name =
      typeof current.name === "string" && /^[A-Za-z][A-Za-z0-9]{0,63}$/u.test(current.name)
        ? current.name
        : "Error";
    const stackFrames =
      typeof current.stack === "string"
        ? current.stack
            .split(/\r?\n/u)
            .slice(1)
            .filter((line) =>
              /coding-capability-review-webview2\.mjs|review-redesign-webview2-driver\.mjs|playwright(?:-core)?[\\/].*\.m?js/iu.test(
                line,
              ),
            )
            .slice(0, 20)
            .map((line) =>
              line.replaceAll(workspaceDirectory, "<workspace>").replaceAll("\\", "/").trim(),
            )
        : [];
    const message = typeof current.message === "string" ? current.message : "";
    const timeout = message.match(/Timeout (\d+)ms exceeded/u);
    const operation = message.match(
      /^(page\.waitForFunction|locator\.(?:waitFor|click)|page\.reload):/u,
    );
    chain.push({
      name,
      kind: timeout ? "timeout" : name === "AssertionError" ? "assertion" : "other",
      operation: operation?.[1] ?? null,
      timeoutMs: timeout ? Number(timeout[1]) : null,
      stackFrames,
    });
    current = current.cause;
  }
  const primary = chain.find((item) => item.kind === "timeout") ?? chain[0];
  return {
    name: chain[0]?.name ?? "Error",
    kind: primary?.kind ?? "other",
    operation: primary?.operation ?? null,
    timeoutMs: primary?.timeoutMs ?? null,
    stackFrames: [...new Set(chain.flatMap((item) => item.stackFrames))].slice(0, 24),
    causes: chain.slice(1),
  };
}

/** 明确核对 Workbench 注入范围菜单的可访问名称，避免把显示文字误当来源状态。 */
export function validateUncommittedReviewScopeLabel(name) {
  assert.equal(name, "审阅范围：未提交", "Workbench Review must select the uncommitted source");
  return name;
}

/**
 * capture 请求绑定 Git metadata revision，返回 revision 属于冻结 artifact；分别核对 source fence 与 start/read 报告，
 * 避免把两种 revision 混为一谈或只凭附件 ID 宣称审查输入闭环。
 */
export function validateReviewSnapshotStartBindings({
  selectedSnapshot,
  reviewSnapshots,
  nativeCalls,
}) {
  assert.equal(selectedSnapshot?.sourceKind, "uncommitted");
  assert.ok(selectedSnapshot?.workspaceId, "resolved Git snapshot must identify its Workspace");
  assert.ok(selectedSnapshot?.revision, "resolved Git snapshot must identify its revision");
  assert.ok(Array.isArray(reviewSnapshots));
  assert.ok(Array.isArray(nativeCalls));

  const resolvedSnapshots = reviewSnapshots.filter(
    (call) =>
      call.command === "ja_review_snapshot" &&
      call.phase === "resolved" &&
      call.source?.kind === "uncommitted" &&
      typeof call.workspaceId === "string" &&
      typeof call.resultRevision === "string",
  );
  assert.ok(
    resolvedSnapshots.some(
      (call) =>
        call.workspaceId === selectedSnapshot.workspaceId &&
        call.resultRevision === selectedSnapshot.revision,
    ),
    "selected snapshot identity must match a resolved native Git snapshot",
  );

  const captures = nativeCalls.filter((call) => call.command === "ja_review_capture_snapshot");
  const starts = nativeCalls.filter((call) => call.command === "ja_ai_review_start");
  const reads = nativeCalls.filter((call) => call.command === "ja_ai_review_read");
  assert.ok(captures.length > 0, "AI Review must capture a frozen snapshot");
  assert.ok(starts.length > 0, "AI Review must start from a frozen snapshot attachment");
  assert.ok(reads.length > 0, "AI Review must read a report from an accepted Turn");

  const capturesByAttachment = new Map();
  for (const capture of captures) {
    assert.equal(capture.phase, "resolved", "AI Review must use a successful snapshot capture");
    assert.ok(capture.attachmentId, "captured snapshot must return its imported attachment ID");
    assert.equal(
      capturesByAttachment.has(capture.attachmentId),
      false,
      "capture attachment IDs must be unique",
    );
    capturesByAttachment.set(capture.attachmentId, capture);
    assert.equal(capture.source?.kind, "uncommitted");
    assert.equal(
      capture.workspaceId,
      selectedSnapshot.workspaceId,
      "captured snapshot must belong to the selected Workspace",
    );
    assert.ok(
      resolvedSnapshots.some(
        (snapshot) =>
          snapshot.workspaceId === capture.workspaceId &&
          snapshot.source?.kind === capture.source.kind &&
          snapshot.resultRevision === capture.expectedSnapshotRevision,
      ),
      "capture request source fence must match a resolved Git metadata snapshot",
    );
    assert.match(
      capture.snapshotId ?? "",
      /^rev_[0-9a-f]{24}$/u,
      "capture must return its frozen snapshot ID",
    );
    assert.match(
      capture.snapshotHash ?? "",
      /^[0-9a-f]{64}$/u,
      "capture must return its frozen snapshot hash",
    );
    assert.ok(typeof capture.resultRevision === "string" && capture.resultRevision.length > 0);
    assert.equal(
      capture.revision,
      capture.resultRevision,
      "capture revision must come from the frozen response",
    );
  }

  const acceptedStarts = [];
  for (const start of starts) {
    assert.ok(start.snapshotAttachmentId, "AI Review start must name its frozen attachment");
    const capture = capturesByAttachment.get(start.snapshotAttachmentId);
    assert.ok(capture, "AI Review start attachment must resolve to a captured snapshot");
    if (start.phase === "resolved") acceptedStarts.push({ start, capture });
  }
  assert.ok(acceptedStarts.length > 0, "AI Review must have an accepted start");

  let reportBindingsChecked = 0;
  for (const { start, capture } of acceptedStarts) {
    assert.ok(start.acceptedThreadId, "accepted AI Review start must identify its Thread");
    assert.ok(start.turnId, "accepted AI Review start must identify its Turn");
    const turnReports = reads.filter(
      (read) =>
        read.phase === "resolved" &&
        read.reportPresent === true &&
        read.threadId === start.acceptedThreadId &&
        read.resultThreadId === start.acceptedThreadId &&
        read.turnId === start.turnId &&
        read.resultTurnId === start.turnId,
    );
    assert.ok(turnReports.length > 0, "accepted Review Turn must be read back with its report");
    for (const read of turnReports) {
      assert.deepEqual(
        read.reportSnapshot,
        {
          snapshotId: capture.snapshotId,
          snapshotHash: capture.snapshotHash,
          revision: capture.resultRevision,
        },
        "read report must retain the captured frozen snapshot identity",
      );
      reportBindingsChecked += 1;
    }
  }

  return {
    selectedSnapshot: {
      workspaceId: selectedSnapshot.workspaceId,
      revision: selectedSnapshot.revision,
      sourceKind: selectedSnapshot.sourceKind,
    },
    capturesChecked: captures.length,
    startsChecked: starts.length,
    reportBindingsChecked,
    allStartsBoundToResolvedSnapshots: true,
  };
}

const reviewPath = "src/domain/entity/modified.ts";
const originalLine = "export const state = 'after';";
const repairedLine = "export const state = 'fixed';";
const preparationPrompt = "JA_CODING_REVIEW_PREPARATION_TURN";
const preparationReply = "JA_CODING_REVIEW_PREPARATION_DONE";

/** 只把 runner 生成的协议身份压成稳定短摘要，避免写入任何 Provider 正文或参数。 */
function identityFingerprint(value) {
  return createHash("sha256").update(value).digest("hex").slice(0, 16);
}

/** 汇总本机 fixture 的 ID 重用情况，同时保持诊断不含上游请求正文和 Tool 参数。 */
function summarizeIdentityOccurrences(values) {
  const counts = new Map();
  for (const value of values) {
    if (typeof value === "string" && value.length > 0) {
      counts.set(value, Number(counts.get(value) ?? 0) + 1);
    }
  }
  const repeated = [...counts.entries()]
    .filter(([, count]) => count > 1)
    .map(([value, occurrences]) => ({ fingerprint: identityFingerprint(value), occurrences }));
  const totalCount = [...counts.values()].reduce((total, count) => total + count, 0);
  return {
    totalCount,
    uniqueCount: counts.size,
    duplicateCount: totalCount - counts.size,
    repeated,
  };
}

/** 为一次失败诊断保留请求/回复阶段、ID 唯一性与终态事实，不复制任何上下文载荷。 */
export function summarizeReviewProviderDiagnostics(records) {
  assert.ok(Array.isArray(records), "Provider diagnostic records must be an array");
  const stageCounts = {};
  const toolNameCounts = {};
  for (const record of records) {
    if (typeof record?.stage === "string") {
      stageCounts[record.stage] = Number(stageCounts[record.stage] ?? 0) + 1;
    }
    if (typeof record?.toolName === "string" && /^[a-z0-9_]{1,80}$/u.test(record.toolName)) {
      toolNameCounts[record.toolName] = Number(toolNameCounts[record.toolName] ?? 0) + 1;
    }
  }
  const requestIds = summarizeIdentityOccurrences(records.map((record) => record?.requestId));
  const toolCallIds = summarizeIdentityOccurrences(records.map((record) => record?.callId));
  const responseIds = summarizeIdentityOccurrences(records.map((record) => record?.responseId));
  return {
    requestCount: records.length,
    uniqueRequestIdCount: requestIds.uniqueCount,
    duplicateRequestIdCount: requestIds.duplicateCount,
    repeatedRequestIds: requestIds.repeated,
    parsedRequestCount: records.filter((record) => record?.requestParsed === true).length,
    completedEventCount: records.filter((record) => record?.terminalEventEmitted === true).length,
    finishedResponseCount: records.filter((record) => record?.responseFinished === true).length,
    rejectedRequestCount: records.filter((record) => record?.httpStatus === 400).length,
    stageCounts,
    toolNameCounts,
    toolCallCount: toolCallIds.totalCount,
    uniqueToolCallIdCount: toolCallIds.uniqueCount,
    duplicateToolCallIdCount: toolCallIds.duplicateCount,
    repeatedToolCallIds: toolCallIds.repeated,
    uniqueResponseIdCount: responseIds.uniqueCount,
    duplicateResponseIdCount: responseIds.duplicateCount,
    repeatedResponseIds: responseIds.repeated,
    requests: records.map((record) => ({
      requestIdFingerprint:
        typeof record?.requestId === "string" ? identityFingerprint(record.requestId) : null,
      stage:
        typeof record?.stage === "string" && /^[a-z0-9_]{1,80}$/u.test(record.stage)
          ? record.stage
          : null,
      httpStatus: Number.isSafeInteger(record?.httpStatus) ? record.httpStatus : null,
      responseCompleted: record?.terminalEventEmitted === true,
      responseFinished: record?.responseFinished === true,
      toolName:
        typeof record?.toolName === "string" && /^[a-z0-9_]{1,80}$/u.test(record.toolName)
          ? record.toolName
          : null,
      callIdFingerprint:
        typeof record?.callId === "string" ? identityFingerprint(record.callId) : null,
      responseIdFingerprint:
        typeof record?.responseId === "string" ? identityFingerprint(record.responseId) : null,
      errorCode:
        typeof record?.errorCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(record.errorCode)
          ? record.errorCode
          : null,
    })),
  };
}

/** 汇总项目启动诊断中的状态与计数；路径、配置内容、调用 ID 和错误消息都不进入报告。 */
export function summarizeProjectBootstrapDiagnostics(evidence) {
  const count = (value) => (Number.isSafeInteger(value) && value >= 0 ? value : 0);
  const phase = (value) =>
    value === "started" || value === "resolved" || value === "rejected" ? value : null;
  const triState = (value) => (typeof value === "boolean" ? value : null);
  const errorCode = (value) =>
    (typeof value === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(value)) ||
    Number.isSafeInteger(value)
      ? value
      : null;
  const runtimeStatus = (value) => {
    if (value === null || typeof value !== "object" || Array.isArray(value)) return null;
    const status = [
      "starting",
      "ready",
      "busy",
      "stopped",
      "failed",
      "degraded",
      "incompatible",
      "recovery_required",
    ].includes(value.status)
      ? value.status
      : null;
    const storageRecoveryPhase = ["ready", "rebuilding", "blocked"].includes(
      value.storageRecoveryPhase,
    )
      ? value.storageRecoveryPhase
      : null;
    return {
      status,
      generation: count(value.generation),
      serverInstanceIdPresent: triState(value.serverInstanceIdPresent),
      storageRecoveryPhase,
    };
  };
  const lifecycleCall = (value) => {
    const statuses = Array.isArray(value?.observedStatuses) ? value.observedStatuses : [];
    return {
      started: count(value?.started),
      resolved: count(value?.resolved),
      rejected: count(value?.rejected),
      lastPhase: phase(value?.lastPhase),
      lastErrorCode: errorCode(value?.lastErrorCode),
      observedStatuses: [...new Set(statuses)].filter((status) =>
        [
          "starting",
          "ready",
          "busy",
          "stopped",
          "failed",
          "degraded",
          "incompatible",
          "recovery_required",
        ].includes(status),
      ),
      lastStatus: runtimeStatus(value?.lastStatus),
      recoveryRequired: triState(value?.recoveryRequired),
    };
  };
  const readSnapshot = (value) => {
    if (value === null || typeof value !== "object" || Array.isArray(value)) return null;
    return {
      started: count(value.started),
      resolved: count(value.resolved),
      rejected: count(value.rejected),
      lastPhase: phase(value.lastPhase),
      lastErrorCode: errorCode(value.lastErrorCode),
    };
  };
  const configuration = evidence?.configurationRead ?? {};
  const project = evidence?.projectSelection ?? {};
  const workspaceOpen = project.workspaceOpen ?? {};
  return {
    runtimeLifecycle: {
      start: lifecycleCall(evidence?.runtimeLifecycle?.start),
      state: lifecycleCall(evidence?.runtimeLifecycle?.state),
      recoveryState: lifecycleCall(evidence?.runtimeLifecycle?.recoveryState),
    },
    configurationRead: {
      started: count(configuration.started),
      resolved: count(configuration.resolved),
      rejected: count(configuration.rejected),
      lastPhase: phase(configuration.lastPhase),
      lastErrorCode: errorCode(configuration.lastErrorCode),
    },
    projectSelection: {
      buttonFound: triState(project.buttonFound),
      buttonDisabled: triState(project.buttonDisabled),
      clicked: triState(project.clicked),
      clickReturned: triState(project.clickReturned),
      selected: triState(project.selected),
      configurationReadAtClick: readSnapshot(project.configurationReadAtClick),
      scopeReadyInferredAtClick: triState(project.scopeReadyInferredAtClick),
      turnAdmissionReadyInferredAtClick: triState(project.turnAdmissionReadyInferredAtClick),
      readinessWaitCompleted: triState(project.readinessWaitCompleted),
      workspaceOpen: {
        started: count(workspaceOpen.started),
        resolved: count(workspaceOpen.resolved),
        rejected: count(workspaceOpen.rejected),
        directoryPassedToNative: triState(workspaceOpen.directoryPassedToNative),
        workspaceIdReturned: triState(workspaceOpen.workspaceIdReturned),
        lastPhase: phase(workspaceOpen.lastPhase),
      },
    },
  };
}

/** 从 Responses 当前用户项切开历史，避免前一份 Review 的 Tool identity 推进新一轮。 */
function currentTurnInput(payload) {
  const input = Array.isArray(payload?.input) ? payload.input : [];
  let latestUser = -1;
  for (let index = input.length - 1; index >= 0; index -= 1) {
    if (input[index]?.role === "user") {
      latestUser = index;
      break;
    }
  }
  return input.slice(latestUser < 0 ? 0 : latestUser);
}

/** 读取当前普通用户提示，用于把 Review 与用户点击“修复”后的实施 Turn 分流。 */
function latestUserText(payload) {
  if (typeof payload?.input === "string") return payload.input;
  const current = currentTurnInput(payload);
  const user = current.find((item) => item?.role === "user");
  const parts = [];
  const visit = (value) => {
    if (typeof value === "string") parts.push(value);
    else if (Array.isArray(value)) value.forEach(visit);
    else if (value !== null && typeof value === "object") {
      if (typeof value.text === "string") parts.push(value.text);
      else Object.values(value).forEach(visit);
    }
  };
  visit(user?.content);
  return parts.join(" ");
}

/** 读取 Responses function tool catalog 的原生名称，拒绝 fixture 与模型能力错配。 */
function responseToolNames(payload) {
  return new Set(
    (Array.isArray(payload?.tools) ? payload.tools : [])
      .map((tool) => tool?.function?.name ?? tool?.name)
      .filter((name) => typeof name === "string"),
  );
}

/** 解析 Responses function_call arguments；畸形历史项不能被误认成已完成的本轮 Tool。 */
function responseToolArguments(item) {
  if (item?.arguments !== null && typeof item?.arguments === "object") return item.arguments;
  if (typeof item?.arguments !== "string") return undefined;
  try {
    const parsed = JSON.parse(item.arguments);
    return parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed
      : undefined;
  } catch {
    return undefined;
  }
}

/** 只接受当前用户项后的 function_call 与同 ID output 配对，防止历史调用推进本轮阶段。 */
function currentTurnCompletedToolCall(payload, toolName, matchesArguments = () => true) {
  const current = currentTurnInput(payload);
  const completedIds = new Set(
    current
      .filter((item) => item?.type === "function_call_output" && typeof item.call_id === "string")
      .map((item) => item.call_id),
  );
  return current.find(
    (item) =>
      item?.type === "function_call" &&
      item.name === toolName &&
      typeof item.call_id === "string" &&
      completedIds.has(item.call_id) &&
      matchesArguments(responseToolArguments(item)),
  );
}

/** 每个 Provider HTTP 请求生成新 Tool identity，供 Responses SSE 与后续 output 一一配对。 */
function reviewToolCall(name, arguments_) {
  return { callId: randomUUID(), name, arguments: arguments_ };
}

/** 用真实 Responses 流调用冻结快照读取工具，不把快照正文写入 runner 状态。 */
function reviewReadCall(side) {
  return {
    callId: randomUUID(),
    name: "review_snapshot_read",
    arguments: {
      path: reviewPath,
      layer: "unstaged",
      side,
      offsetCharacters: 0,
      maxCharacters: 4096,
    },
  };
}

/** Responses 历史会保留 Java 的完整性尾注；校验字符数与摘要后再解析，避免把尾注误当快照 JSON。 */
function requireSnapshotReadOutput(payload, callId, reviewNumber, side) {
  const outputItem = currentTurnInput(payload).find(
    (item) => item?.type === "function_call_output" && item.call_id === callId,
  );
  assert.ok(outputItem, "Review snapshot read result is missing for " + side);
  assert.equal(
    typeof outputItem.output,
    "string",
    "Review snapshot read result must be encoded JSON",
  );
  let content = outputItem.output;
  const artifactFooter = /\n\[characters=(\d+) sha256=([a-f0-9]{64})\]$/u.exec(content);
  if (artifactFooter !== null) {
    content = content.slice(0, artifactFooter.index);
    const expectedCharacters = Number(artifactFooter[1]);
    if (
      !Number.isSafeInteger(expectedCharacters) ||
      Array.from(content).length !== expectedCharacters
    ) {
      const failure = new Error("Review snapshot read artifact character count is invalid");
      failure.code = "FIXTURE_OUTPUT_CHARACTERS";
      throw failure;
    }
    const actualHash = createHash("sha256").update(content, "utf8").digest("hex");
    if (actualHash !== artifactFooter[2]) {
      const failure = new Error("Review snapshot read artifact digest is invalid");
      failure.code = "FIXTURE_OUTPUT_SHA256";
      throw failure;
    }
  }
  let result;
  try {
    result = JSON.parse(content);
  } catch {
    const failure = new Error("Review snapshot read result is not valid JSON for " + side);
    failure.code = "FIXTURE_OUTPUT_JSON";
    throw failure;
  }
  assert.equal(result.path, reviewPath);
  assert.equal(result.layer, "unstaged");
  assert.equal(result.side, side);
  assert.equal(result.offsetCharacters, 0);
  assert.ok(typeof result.totalCharacters === "number" && result.totalCharacters > 0);
  assert.ok(typeof result.content === "string" && result.content.length > 0);

  const currentLine = reviewNumber > 1 ? repairedLine : originalLine;
  if (side === "base") {
    assert.ok(result.content.includes("export const state = 'before';"));
  } else if (side === "current") {
    assert.ok(result.content.includes(currentLine));
  } else {
    assert.ok(result.content.includes("-export const state = 'before';"));
    assert.ok(result.content.includes("+" + currentLine));
  }
  return result;
}

/** 自动标题按无 Tool 的专用 prompt 分流；实施阶段只接受当前 user 夹具，写入仍经 Java edit Tool。 */
function nextModelAction(payload, state) {
  const names = responseToolNames(payload);
  const current = currentTurnInput(payload);
  const currentJson = JSON.stringify(current);
  if (
    names.size === 0 &&
    typeof payload?.instructions === "string" &&
    payload.instructions.startsWith("你为桌面对话生成简洁标题。") &&
    latestUserText(payload).includes("<user_request>")
  ) {
    return { stage: "title_final" };
  }
  if (names.has("review_snapshot_list")) {
    const reportCall = currentTurnCompletedToolCall(payload, "review_report_submit");
    if (reportCall !== undefined) {
      state.activeReview = false;
      return { stage: "review_final" };
    }
    if (!state.activeReview) {
      state.reviewNumber += 1;
      state.activeReview = true;
    }
    const number = state.reviewNumber;
    const listCall = currentTurnCompletedToolCall(payload, "review_snapshot_list");
    if (listCall === undefined) {
      return {
        stage: "review_list",
        call: reviewToolCall("review_snapshot_list", {}),
      };
    }
    for (const side of ["base", "current", "patch"]) {
      const readCall = currentTurnCompletedToolCall(
        payload,
        "review_snapshot_read",
        (arguments_) => arguments_?.side === side,
      );
      if (readCall === undefined)
        return { stage: "review_read_" + side, call: reviewReadCall(side) };
      requireSnapshotReadOutput(payload, readCall.call_id, number, side);
    }

    const repaired = number > 1;
    const evidence = repaired ? repairedLine : originalLine;
    return {
      stage: "review_report",
      call: reviewToolCall("review_report_submit", {
        summary: repaired ? "复审基于修复后的新快照，未发现原问题。" : "发现一处可定位的问题。",
        filesReviewed: [{ path: reviewPath, layer: "unstaged" }],
        findings: repaired
          ? []
          : [
              {
                severity: "P2",
                title: "状态值未按目标收敛",
                body: "当前状态值仍保持修改前内容，后续分支会继续读取旧状态。",
                path: reviewPath,
                layer: "unstaged",
                startLine: 1,
                endLine: 1,
                side: "current",
                evidence,
              },
            ],
      }),
    };
  }

  const repairPrompt = latestUserText(payload);
  if (
    names.has("read") &&
    names.has("edit") &&
    repairPrompt.includes("请检查并修复以下代码审查发现")
  ) {
    const readCall = currentTurnCompletedToolCall(
      payload,
      "read",
      (arguments_) => arguments_?.path === reviewPath,
    );
    if (readCall === undefined) {
      return {
        stage: "repair_read",
        call: reviewToolCall("read", { path: reviewPath, offset: 1, limit: 20 }),
      };
    }
    const editCall = currentTurnCompletedToolCall(
      payload,
      "edit",
      (arguments_) => arguments_?.path === reviewPath,
    );
    if (editCall === undefined) {
      assert.ok(
        currentJson.includes(originalLine),
        "repair must read the current file before editing",
      );
      return {
        stage: "repair_edit",
        call: reviewToolCall("edit", {
          path: reviewPath,
          edits: [{ oldText: originalLine, newText: repairedLine }],
        }),
      };
    }
    return { stage: "repair_final" };
  }
  if (latestUserText(payload).includes(preparationPrompt)) return { stage: "preparation_final" };
  return { stage: "ordinary_final" };
}

/**
 * 在 loopback 端只保留合成请求身份、Tool identity 与流终态；上下文正文和工具参数只存在于请求处理内存。
 */
export async function startReviewCapabilityProvider() {
  const state = { reviewNumber: 0, activeReview: false, observations: [], requests: [] };
  const server = createServer(async (request, response) => {
    response.setHeader("cache-control", "no-store");
    response.setHeader("connection", "close");
    const record = {
      requestId: `provider_request_${randomUUID()}`,
      requestParsed: false,
      responseFinished: false,
      terminalEventEmitted: false,
    };
    state.requests.push(record);
    response.once("finish", () => {
      record.responseFinished = true;
    });
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      record.httpStatus = 404;
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    try {
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 8 * 1024 * 1024) throw new Error("loopback request exceeded 8 MiB");
        chunks.push(chunk);
      }
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      record.requestParsed = true;
      const action = nextModelAction(payload, state);
      record.stage = action.stage;
      state.observations.push(action.stage);
      const finalText =
        action.stage === "preparation_final"
          ? preparationReply
          : action.stage === "title_final"
            ? "日常编码审查"
            : "审查结果已保存。";
      const call = action.call;
      record.toolName = call?.name ?? null;
      record.callId = call?.callId ?? null;
      record.responseId = call === undefined ? `resp_${randomUUID()}` : `resp_${call.callId}`;
      const stream = providerStream("openai_responses", record.responseId, call, finalText);
      record.httpStatus = 200;
      record.terminalEventEmitted = stream.includes("event: response.completed");
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(stream);
    } catch (error) {
      const code = typeof error?.code === "string" ? error.code : "";
      record.httpStatus = 400;
      record.errorCode = /^[A-Z][A-Z0-9_]{0,63}$/u.test(code) ? code : "FIXTURE_REQUEST_FAILED";
      response.writeHead(400, { "content-type": "application/json" });
      response.end('{"error":"fixture_request_rejected"}');
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  return {
    endpoint: `http://127.0.0.1:${address.port}/v1`,
    observations: state.observations,
    /** 以白名单事实汇总本次请求，调用方不接触 Provider 原始 payload。 */
    diagnostics() {
      return summarizeReviewProviderDiagnostics(state.requests);
    },
    /** 关闭 fixture 唯一的本机 listener，避免验收残留端口。 */
    async close() {
      server.closeAllConnections?.();
      await new Promise((resolvePromise, rejectPromise) => {
        server.close((error) => (error ? rejectPromise(error) : resolvePromise()));
      });
    },
  };
}

/** 将 full-access 只写入本轮隔离配置，使修复通过真实 edit Tool 而不是 runner 直接写文件。 */
async function prepareFullAccessProfile({ home }) {
  const path = join(home, "config.toml");
  const config = await readFile(path, "utf8");
  assert.ok(config.includes('default_access_mode = "approval_required"'));
  await writeFile(
    path,
    config.replace(
      'default_access_mode = "approval_required"',
      'default_access_mode = "full_access"',
    ),
    "utf8",
  );
}

/** 记录 Review invoke；首个 start 在委托前注入一次拒绝，验证失败重试沿用冻结身份。 */
async function installAiReviewProbe(page) {
  await page.evaluate(() => {
    const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    globalThis.__JA_CODING_REVIEW_COUNTS__ = Object.create(null);
    globalThis.__JA_CODING_REVIEW_START_ATTEMPTS__ = [];
    globalThis.__JA_CODING_REVIEW_START_FAILURE_USED__ = false;
    globalThis.__JA_CODING_REVIEW_NATIVE_CALLS__ = [];
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
      const command = request?.command;
      const trackedCommands = [
        "ja_review_capture_snapshot",
        "ja_ai_review_start",
        "ja_ai_review_read",
      ];
      if (trackedCommands.includes(command)) {
        const counts = globalThis.__JA_CODING_REVIEW_COUNTS__;
        counts[command] = Number(counts[command] ?? 0) + 1;
      }
      const input = request?.args?.input;
      const tracked = trackedCommands.includes(command)
        ? {
            command,
            phase: "started",
            workspaceId: typeof input?.workspaceId === "string" ? input.workspaceId : null,
            source:
              input?.source !== null && typeof input?.source === "object"
                ? { ...input.source }
                : null,
            expectedSnapshotRevision:
              typeof input?.expectedSnapshotRevision === "string"
                ? input.expectedSnapshotRevision
                : null,
            operationId: typeof input?.operationId === "string" ? input.operationId : null,
            threadId: typeof input?.threadId === "string" ? input.threadId : null,
            turnId: typeof input?.turnId === "string" ? input.turnId : null,
            expectedThreadRevision: Number.isSafeInteger(input?.expectedThreadRevision)
              ? input.expectedThreadRevision
              : null,
            operationKey: typeof input?.operationKey === "string" ? input.operationKey : null,
            snapshotAttachmentId:
              typeof input?.snapshotAttachmentId === "string" ? input.snapshotAttachmentId : null,
          }
        : undefined;
      if (tracked !== undefined) globalThis.__JA_CODING_REVIEW_NATIVE_CALLS__.push(tracked);
      if (command === "ja_ai_review_start") {
        globalThis.__JA_CODING_REVIEW_START_ATTEMPTS__.push({
          operationKey: typeof input?.operationKey === "string" ? input.operationKey : null,
          snapshotAttachmentId:
            typeof input?.snapshotAttachmentId === "string" ? input.snapshotAttachmentId : null,
        });
        if (!globalThis.__JA_CODING_REVIEW_START_FAILURE_USED__) {
          globalThis.__JA_CODING_REVIEW_START_FAILURE_USED__ = true;
          if (tracked !== undefined) {
            tracked.phase = "rejected";
            tracked.errorCode = "E2E_INJECTED";
          }
          throw new Error("E2E injected one-shot AI Review start rejection");
        }
      }
      try {
        const result = await (previous === undefined ? delegate() : previous(request, delegate));
        if (tracked !== undefined) {
          tracked.phase = "resolved";
          if (command === "ja_review_capture_snapshot") {
            tracked.snapshotId = typeof result?.snapshotId === "string" ? result.snapshotId : null;
            tracked.snapshotHash =
              typeof result?.snapshotHash === "string" ? result.snapshotHash : null;
            tracked.attachmentId =
              typeof result?.attachmentId === "string" ? result.attachmentId : null;
            tracked.resultRevision = typeof result?.revision === "string" ? result.revision : null;
            tracked.revision = tracked.resultRevision;
          } else if (command === "ja_ai_review_start") {
            tracked.queued = typeof result?.queued === "boolean" ? result.queued : null;
            tracked.acceptedThreadId =
              typeof result?.threadId === "string" ? result.threadId : null;
            tracked.turnId = typeof result?.turnId === "string" ? result.turnId : null;
            tracked.threadRevision = Number.isSafeInteger(result?.threadRevision)
              ? result.threadRevision
              : null;
          } else {
            tracked.status = typeof result?.status === "string" ? result.status : null;
            tracked.reportPresent = result?.report !== null && result?.report !== undefined;
            tracked.resultThreadId = typeof result?.threadId === "string" ? result.threadId : null;
            tracked.resultTurnId = typeof result?.turnId === "string" ? result.turnId : null;
            tracked.reportSnapshot =
              result?.report !== null && typeof result?.report === "object"
                ? {
                    snapshotId:
                      typeof result.report.snapshotId === "string"
                        ? result.report.snapshotId
                        : null,
                    snapshotHash:
                      typeof result.report.snapshotHash === "string"
                        ? result.report.snapshotHash
                        : null,
                    revision:
                      typeof result.report.revision === "string" ? result.report.revision : null,
                  }
                : null;
          }
        }
        return result;
      } catch (error) {
        if (tracked !== undefined && tracked.phase !== "rejected") {
          const code = typeof error?.code === "string" ? error.code : "";
          tracked.phase = "rejected";
          tracked.errorCode = /^[A-Z][A-Z0-9_]{0,63}$/u.test(code) ? code : "INVOKE_FAILED";
        }
        throw error;
      }
    };
  });
}

/** 在 E2E invoke 边界采集 Runtime admission 与项目打开状态，不保留配置、路径或异常正文。 */
function installWorkspaceBootstrapProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  const lifecycleCall = () => ({
    started: 0,
    resolved: 0,
    rejected: 0,
    lastPhase: null,
    lastErrorCode: null,
    observedStatuses: [],
    lastStatus: null,
    recoveryRequired: null,
  });
  const evidence = {
    runtimeLifecycle: {
      start: lifecycleCall(),
      state: lifecycleCall(),
      recoveryState: lifecycleCall(),
    },
    configurationRead: {
      started: 0,
      resolved: 0,
      rejected: 0,
      lastPhase: null,
      lastErrorCode: null,
    },
    projectSelection: {
      buttonFound: null,
      buttonDisabled: null,
      clicked: false,
      clickReturned: null,
      selected: false,
      turnAdmissionReadyInferredAtClick: null,
      readinessWaitCompleted: null,
      workspaceOpen: {
        started: 0,
        resolved: 0,
        rejected: 0,
        directoryPassedToNative: null,
        workspaceIdReturned: null,
        lastPhase: null,
      },
    },
  };
  globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__ = evidence;
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = request?.command;
    const isConfigurationRead = command === "ja_configuration_read";
    const isWorkspaceOpen = command === "ja_runtime_workspace_open";
    const lifecycleTarget =
      command === "ja_runtime_start"
        ? evidence.runtimeLifecycle.start
        : command === "ja_runtime_state"
          ? evidence.runtimeLifecycle.state
          : command === "ja_runtime_recovery_state"
            ? evidence.runtimeLifecycle.recoveryState
            : undefined;
    if (!isConfigurationRead && !isWorkspaceOpen && lifecycleTarget === undefined) {
      return previous === undefined ? delegate() : previous(request, delegate);
    }

    const target = isConfigurationRead
      ? evidence.configurationRead
      : isWorkspaceOpen
        ? evidence.projectSelection.workspaceOpen
        : lifecycleTarget;
    target.started += 1;
    target.lastPhase = "started";
    if (isWorkspaceOpen) {
      const input = request?.args?.input;
      target.directoryPassedToNative =
        typeof input?.cwd === "string" && input.cwd.trim().length > 0;
    }

    try {
      const result = await (previous === undefined ? delegate() : previous(request, delegate));
      target.resolved += 1;
      target.lastPhase = "resolved";
      if (isWorkspaceOpen) target.workspaceIdReturned = typeof result?.workspaceId === "string";
      if (lifecycleTarget !== undefined) {
        const status = result?.status;
        if (typeof status === "string" && !target.observedStatuses.includes(status)) {
          target.observedStatuses.push(status);
        }
        target.lastStatus = {
          status: typeof status === "string" ? status : null,
          generation: Number.isSafeInteger(result?.generation) ? result.generation : null,
          serverInstanceIdPresent: typeof result?.serverInstanceId === "string",
          storageRecoveryPhase:
            typeof result?.storageRecovery?.phase === "string"
              ? result.storageRecovery.phase
              : null,
        };
        if (command === "ja_runtime_recovery_state") {
          target.recoveryRequired = typeof result?.required === "boolean" ? result.required : null;
        }
      }
      return result;
    } catch (error) {
      target.rejected += 1;
      target.lastPhase = "rejected";
      const code = error?.code;
      target.lastErrorCode =
        (typeof code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(code)) ||
        Number.isSafeInteger(code)
          ? code
          : null;
      throw error;
    }
  };
}

/** 经真实 Settings 选择器保存闭集 palette，并核对应用到 document root 的实际值。 */
async function verifyPaletteMatrix(page, deadline) {
  const paletteFacts = [];
  await page.getByRole("button", { name: "设置", exact: true }).click({ timeout: deadline });
  const settings = page.getByRole("region", { name: "设置页面", exact: true });
  await settings.getByRole("tab", { name: "外观", exact: true }).click({ timeout: deadline });
  for (const palette of CODING_REVIEW_AI_PALETTES) {
    const control = settings.getByRole("combobox", { name: "配色主题", exact: true });
    await control.click({ timeout: deadline });
    await page
      .getByRole("option", { name: palette.label, exact: true })
      .click({ timeout: deadline });
    await page.waitForFunction(
      (value) => globalThis.document.documentElement.getAttribute("data-palette") === value,
      palette.value,
      { timeout: deadline },
    );
    paletteFacts.push({
      requested: palette.value,
      applied: await page.locator("html").getAttribute("data-palette"),
    });
  }
  await settings
    .getByRole("button", { name: "返回应用", exact: true })
    .click({ timeout: deadline });
  assert.deepEqual(
    paletteFacts,
    CODING_REVIEW_AI_PALETTES.map(({ value }) => ({ requested: value, applied: value })),
  );
  return paletteFacts;
}

/** 轮询小型隔离 fixture 文件以核对真实 edit 提交完成，不把文件正文写入产物报告。 */
async function waitForFileLine(path, expected, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await readFile(path, "utf8").catch(() => "")).includes(expected)) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error("review repair did not update the fixture file before the deadline");
}

/** 保留 Review WebView2 全局截止时间，避免 Composer 准备失败后叠加新的等待预算。 */
function deadlineTimeout(deadline) {
  return Math.max(1, Math.min(30_000, deadline - Date.now()));
}

/** 只从真实选中导航行读取 Thread ID；草稿状态返回 null，不合成身份。 */
async function selectedThreadId(page) {
  const selected = page.locator(
    '[aria-label="最近对话列表"] button[data-thread-id][aria-current="page"]',
  );
  if ((await selected.count()) !== 1) return null;
  return selected.getAttribute("data-thread-id");
}

/** 等待导航与生产 Timeline 的 Thread ID 一致，防止按旧行或本地 draft 继续验收。 */
async function waitForCurrentTimeline(page, threadId, deadline) {
  assert.match(threadId, /^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u);
  await page.waitForFunction(
    (expected) => {
      const selected = globalThis.document.querySelector(
        '[aria-label="最近对话列表"] button[data-thread-id][aria-current="page"]',
      );
      return (
        selected?.getAttribute("data-thread-id") === expected &&
        [...globalThis.document.querySelectorAll(".ja-chat-timeline[data-thread-id]")].some(
          (timeline) => timeline.getAttribute("data-thread-id") === expected,
        )
      );
    },
    threadId,
    { timeout: deadlineTimeout(deadline) },
  );
}

/** loopback 准备 Turn 必须穿过真实 Provider 请求并显示 completed assistant 消息。 */
async function waitForProviderStage(observations, startIndex, expectedStage, deadline) {
  while (Date.now() < deadline) {
    if (observations.slice(startIndex).includes(expectedStage)) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`loopback Provider did not observe ${expectedStage}`);
}

/**
 * generation 是进程内计数；跨进程重启以新 serverInstanceId 与合法 generation 共同核验，不能要求数值递增。
 * 校验 Runtime 身份、隐藏 IO 与历史证据形成同一条验收链；报告只保留布尔证据，
 * 避免把内部 Thread、Runtime 或冻结 Snapshot 身份扩散到验收产物；两种关闭方式都须恢复焦点。
 */
export function validateReviewRestartHistoryEvidence(evidence) {
  assert.equal(evidence?.runtime?.before?.status, "ready");
  assert.equal(evidence?.runtime?.stopped?.status, "stopped");
  assert.equal(evidence?.runtime?.after?.status, "ready");
  assert.ok(Number.isSafeInteger(evidence.runtime.before.generation));
  assert.ok(Number.isSafeInteger(evidence.runtime.after.generation));
  assert.ok(evidence.runtime.before.generation > 0, "before runtime generation must be positive");
  assert.ok(evidence.runtime.after.generation > 0, "after runtime generation must be positive");
  assert.equal(
    evidence.runtime.shutdown?.acknowledged,
    true,
    "server shutdown must be acknowledged",
  );
  assert.equal(
    evidence.runtime.shutdown.endpointRemoved,
    true,
    "old server endpoint must be removed",
  );
  assert.ok(typeof evidence.runtime.before.serverInstanceId === "string");
  assert.ok(evidence.runtime.before.serverInstanceId.length > 0);
  assert.ok(typeof evidence.runtime.after.serverInstanceId === "string");
  assert.ok(evidence.runtime.after.serverInstanceId.length > 0);
  assert.notEqual(
    evidence.runtime.after.serverInstanceId,
    evidence.runtime.before.serverInstanceId,
    "App Server restart must issue a new serverInstanceId",
  );
  assert.equal(evidence.thread.sameSelectedThread, true);
  assert.equal(evidence.thread.serverHistoryRestored, true);
  assert.equal(evidence.review.hiddenDuringRestart, true);
  assert.equal(evidence.review.hiddenSnapshotDelta, 0);
  assert.equal(
    evidence.review.currentSnapshotRestored,
    true,
    "current Workspace must restore a readable Git snapshot after restart",
  );
  assert.equal(evidence.review.reportsRetained, 2);
  assert.equal(evidence.review.selectedHistoricalLabel, "历史审查 1 · 已完成");
  assert.equal(evidence.review.historicalReportReadBack, true);
  assert.equal(evidence.review.historicalSummary, "发现一处可定位的问题。");
  assert.equal(evidence.review.repairActionVisible, false);
  assert.equal(evidence.review.frozenEvidence.path, reviewPath);
  assert.equal(evidence.review.frozenEvidence.startLine, 1);
  assert.equal(evidence.review.frozenEvidence.contentMatches, true);
  assert.equal(evidence.review.frozenEvidence.snapshotIdPresent, true);
  assert.equal(evidence.review.frozenEvidence.headerInsetVerified, true, "dialog header inset");
  assert.equal(
    evidence.review.frozenEvidence.explicitCloseFocusReturned,
    true,
    "explicit close must return focus",
  );
  assert.equal(evidence.review.frozenEvidence.focusReturned, true);
  return {
    appServerRestarted: true,
    runtimeShutdownAcknowledged: true,
    oldEndpointRemoved: true,
    generationAdvanced: evidence.runtime.after.generation > evidence.runtime.before.generation,
    serverInstanceChanged: true,
    sameThreadRestored: true,
    reviewHiddenDuringRestart: true,
    hiddenSnapshotDelta: 0,
    currentSnapshotRestored: true,
    reportsRetained: 2,
    selectedHistoricalLabel: evidence.review.selectedHistoricalLabel,
    historicalReportReadBack: true,
    historicalRepairUnavailable: true,
    frozenEvidence: {
      path: reviewPath,
      startLine: 1,
      contentMatches: true,
      snapshotIdPresent: true,
      headerInsetVerified: true,
      explicitCloseFocusReturned: true,
      focusReturned: true,
    },
  };
}

/** 经生产 History adapter 读取 Thread 的服务端尾页；内部 turn ID 只用于本轮身份比较。 */
async function readServerThreadSnapshot(page, threadId) {
  return page.evaluate(async (selectedThreadId) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const snapshot = await createHistoryAdapter().threadRead({
      threadId: selectedThreadId,
      tail: true,
    });
    return {
      threadId: snapshot.threadId,
      turnIds: snapshot.turns.map((turn) => turn.turnId),
    };
  }, threadId);
}

/**
 * 只读本轮 Temp 内的普通 endpoint，按原 Runtime 身份完成现有认证 shutdown；凭据只驻留内存。
 * Host.stop 仅断开客户端，因此须等待旧 endpoint 消失再启动，防止复用旧 daemon 冒充重启。
 */
async function shutdownIsolatedReviewServer(runtimeDirectory, runtimeBefore, deadline) {
  const ownedDirectory = assertOwnedTemporaryPath(runtimeDirectory, "Review runtime directory");
  assert.equal(
    await realpath(ownedDirectory),
    ownedDirectory,
    "runtime directory cannot cross a link",
  );
  const directories = { runtime: ownedDirectory };
  const endpoint = await readRuntimeEndpointMetadata(directories);
  assert.equal(endpoint?.serverInstanceId, runtimeBefore.serverInstanceId);
  assert.equal(endpoint?.runtimeGeneration, runtimeBefore.generation);
  const document = JSON.parse(
    await readFile(join(ownedDirectory, "app-server.endpoint.json"), "utf8"),
  );
  const reread = await readRuntimeEndpointMetadata(directories);
  assert.deepEqual(reread, endpoint, "endpoint identity changed before shutdown");
  assert.equal(document.serverInstanceId, endpoint.serverInstanceId);
  assert.equal(document.runtimeGeneration, endpoint.runtimeGeneration);
  const shutdown = await requestRunScopedRuntimeShutdown(
    endpoint,
    document.token,
    AbortSignal.timeout(deadlineTimeout(deadline)),
  );
  assert.equal(shutdown.status, "acknowledged");
  assert.equal(shutdown.socketClose.status, "closed");
  while (Date.now() < deadline) {
    const exists = await stat(join(ownedDirectory, "app-server.endpoint.json")).then(
      () => true,
      (error) => {
        if (error.code === "ENOENT") return false;
        throw error;
      },
    );
    if (!exists) return { acknowledged: true, endpointRemoved: true };
    await new Promise((done) => setTimeout(done, 100));
  }
  throw new Error("old Review App Server endpoint did not disappear after shutdown");
}

/** 新实例须恢复当前 Git 与旧报告；冻结行号排除隐藏测量行，显式关闭与 Escape 均须回到定位动作。 */
async function verifyReviewRestartHistory(
  page,
  threadId,
  workspaceId,
  evidenceDirectory,
  runtimeDirectory,
) {
  const deadline = Date.now() + 120_000;
  const inspector = page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
  await inspector.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  const runtimeBefore = await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    return createRuntimeHostAdapter().state();
  });
  assert.equal(runtimeBefore.status, "ready");
  assert.equal(await selectedThreadId(page), threadId);
  const threadHistoryBefore = await readServerThreadSnapshot(page, threadId);
  assert.equal(threadHistoryBefore.threadId, threadId);
  assert.ok(threadHistoryBefore.turnIds.length >= 3);
  const resolvedSnapshotsBeforeRestart = await page.evaluate(() =>
    Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_snapshot?.resolved ?? 0),
  );

  await inspector.getByRole("button", { name: "收起右侧栏", exact: true }).click({
    timeout: deadlineTimeout(deadline),
  });
  await page.waitForFunction(
    () =>
      globalThis.document
        .querySelector(
          '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
        )
        ?.getAttribute("data-visible") !== "true",
    undefined,
    { timeout: deadlineTimeout(deadline) },
  );
  const hiddenSnapshotBefore = await page.evaluate(() =>
    Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_snapshot?.start ?? 0),
  );

  const stopped = await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    return createRuntimeHostAdapter().stop();
  });
  assert.equal(stopped.status, "stopped");
  const shutdown = await shutdownIsolatedReviewServer(runtimeDirectory, runtimeBefore, deadline);
  const transition = await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const runtime = createRuntimeHostAdapter();
    const started = await runtime.start();
    const after = await runtime.state();
    return { started, after };
  });
  assert.equal(transition.started.status, "ready");
  assert.equal(transition.after.status, "ready");
  assert.ok(
    Number.isSafeInteger(transition.started.generation) && transition.started.generation > 0,
  );
  assert.notEqual(transition.started.serverInstanceId, runtimeBefore.serverInstanceId);
  await page.waitForFunction(
    async ({ generation, serverInstanceId }) => {
      const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
      const state = await createRuntimeHostAdapter().state();
      return (
        state.status === "ready" &&
        state.generation === generation &&
        state.serverInstanceId === serverInstanceId
      );
    },
    {
      generation: transition.started.generation,
      serverInstanceId: transition.started.serverInstanceId,
    },
    { timeout: deadlineTimeout(deadline) },
  );
  await waitForCurrentTimeline(page, threadId, deadline);
  const threadHistoryAfter = await readServerThreadSnapshot(page, threadId);
  const serverHistoryRestored =
    threadHistoryAfter.threadId === threadId &&
    threadHistoryBefore.turnIds.every((turnId) => threadHistoryAfter.turnIds.includes(turnId));
  const hiddenSnapshotAfter = await page.evaluate(() =>
    Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_snapshot?.start ?? 0),
  );
  const hiddenDuringRestart = (await inspector.getAttribute("data-visible")) !== "true";
  const hiddenSnapshotDelta = hiddenSnapshotAfter - hiddenSnapshotBefore;

  await page.getByRole("button", { name: "显示工作区面板", exact: true }).click({
    timeout: deadlineTimeout(deadline),
  });
  await waitForCurrentTimeline(page, threadId, deadline);
  const review = currentReviewShell(page);
  await review.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await page.waitForFunction(
    ({ prior, expectedWorkspace }) => {
      const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
      const current = calls.findLast(
        (call) => call.command === "ja_review_snapshot" && call.workspaceId === expectedWorkspace,
      );
      return (
        Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_snapshot?.resolved ?? 0) >
          prior &&
        current?.phase === "resolved" &&
        typeof current.resultRevision === "string"
      );
    },
    { prior: resolvedSnapshotsBeforeRestart, expectedWorkspace: workspaceId },
    { timeout: deadlineTimeout(deadline) },
  );
  assert.equal(await review.getByRole("alert").count(), 0, "restored Review must clear its error");
  const more = review.getByRole("button", { name: "审查更多操作", exact: true });
  await more.click({ timeout: deadlineTimeout(deadline) });
  await page.getByRole("menuitem", { name: "审查记录", exact: true }).focus();
  await page.keyboard.press("ArrowRight");
  const historicalOption = page.getByRole("menuitemradio", {
    name: "历史审查 1 · 已完成",
    exact: true,
  });
  await historicalOption.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  assert.equal(
    await page.getByRole("menuitemradio", { name: /^历史审查 \d+ · 已完成$/u }).count(),
    2,
    "App Server history must restore exactly the two completed Review reports",
  );
  const readsBeforeSelectingOlderReport = await page.evaluate(() =>
    Number(globalThis.__JA_CODING_REVIEW_COUNTS__?.ja_ai_review_read ?? 0),
  );
  await historicalOption.click({ timeout: deadlineTimeout(deadline) });
  await more.click({ timeout: deadlineTimeout(deadline) });
  await page.getByRole("menuitem", { name: "审查记录", exact: true }).focus();
  await page.keyboard.press("ArrowRight");
  await historicalOption.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  assert.equal(await historicalOption.getAttribute("aria-checked"), "true");
  const selectedHistoricalLabel = (await historicalOption.innerText()).trim();
  assert.equal(selectedHistoricalLabel, "历史审查 1 · 已完成");
  await page.keyboard.press("Escape");
  await page.keyboard.press("Escape");
  await page.waitForFunction(
    () => globalThis.document.activeElement?.getAttribute("aria-label") === "审查更多操作",
    undefined,
    { timeout: deadlineTimeout(deadline) },
  );

  const finding = review.locator(".ja-review-ai-finding").first();
  await review
    .getByText("发现一处可定位的问题。", { exact: true })
    .waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await page.waitForFunction(
    (prior) => Number(globalThis.__JA_CODING_REVIEW_COUNTS__?.ja_ai_review_read ?? 0) > prior,
    readsBeforeSelectingOlderReport,
    { timeout: deadlineTimeout(deadline) },
  );
  await review
    .getByText("历史审查只读。定位展示冻结发现证据行，不代表完整 Diff 或当前文件。", {
      exact: true,
    })
    .waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  const repairActionVisible =
    (await finding.getByRole("button", { name: "修复", exact: true }).count()) > 0;
  const locate = finding.getByRole("button", { name: "定位", exact: true });
  await locate.click({ timeout: deadlineTimeout(deadline) });
  const dialog = page.getByRole("dialog", { name: "冻结发现证据", exact: true });
  await dialog.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  const viewer = dialog.getByRole("region", {
    name: `只读文件 ${reviewPath}`,
    exact: true,
  });
  await viewer.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  const codeLine = viewer.locator(".cm-content .cm-line").first();
  await codeLine.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  const frozenLineText = (await codeLine.innerText()).trim();
  const frozenLineNumber = Number(
    (await viewer.locator(".cm-lineNumbers .cm-gutterElement:visible").first().innerText()).trim(),
  );
  const metadata = await dialog.locator(".ja-review-frozen-evidence-meta").innerText();
  const snapshotIdPresent = /快照\s+\S+/u.test(metadata);
  /** 实测真实弹窗的标题边距，避免仅靠组件和 CSS 源码推断最终布局。 */
  const headerInsetVerified = await dialog.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    const title = element.querySelector("h2")?.getBoundingClientRect();
    return title !== undefined && title.left - bounds.left >= 8 && title.top - bounds.top >= 8;
  });
  const explicitClose = dialog.getByRole("button", { name: "关闭冻结证据", exact: true });
  await explicitClose.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await page.screenshot({
    path: join(evidenceDirectory, "coding-review-frozen-evidence.png"),
    animations: "disabled",
  });
  await explicitClose.click({ timeout: deadlineTimeout(deadline) });
  await dialog.waitFor({ state: "hidden", timeout: deadlineTimeout(deadline) });
  await locate.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await page.waitForFunction(
    /** Radix 关闭后既有 RAF 回程必须落在原定位按钮，不得停留在已卸载的图标动作。 */
    () => globalThis.document.activeElement?.textContent?.trim() === "定位",
    undefined,
    { timeout: deadlineTimeout(deadline) },
  );
  const explicitCloseFocusReturned = await locate.evaluate(
    (element) => element === globalThis.document.activeElement,
  );
  await locate.click({ timeout: deadlineTimeout(deadline) });
  await dialog.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await page.keyboard.press("Escape");
  await dialog.waitFor({ state: "hidden", timeout: deadlineTimeout(deadline) });
  await page.waitForFunction(
    () => {
      const findingNode = globalThis.document.querySelector(".ja-review-ai-finding");
      const trigger = [...(findingNode?.querySelectorAll("button") ?? [])].find((button) =>
        button.textContent?.includes("定位"),
      );
      return trigger !== undefined && globalThis.document.activeElement === trigger;
    },
    undefined,
    { timeout: deadlineTimeout(deadline) },
  );
  const focusReturned = await locate.evaluate(
    (element) => element === globalThis.document.activeElement,
  );
  return validateReviewRestartHistoryEvidence({
    runtime: {
      before: runtimeBefore,
      stopped,
      shutdown,
      after: transition.after,
    },
    thread: {
      sameSelectedThread: (await selectedThreadId(page)) === threadId,
      serverHistoryRestored,
    },
    review: {
      hiddenDuringRestart,
      hiddenSnapshotDelta,
      currentSnapshotRestored: true,
      reportsRetained: 2,
      selectedHistoricalLabel,
      historicalReportReadBack: true,
      historicalSummary: (
        await review.getByText("发现一处可定位的问题。", { exact: true }).innerText()
      ).trim(),
      repairActionVisible,
      frozenEvidence: {
        path: reviewPath,
        startLine: frozenLineNumber,
        contentMatches: frozenLineText === originalLine,
        snapshotIdPresent,
        headerInsetVerified,
        explicitCloseFocusReturned,
        focusReturned,
      },
    },
  });
}

/** 返回当前可见 Thread Review shell，避免同项目保留 Host 混入审查控件查找。 */
function currentReviewShell(page) {
  return page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"][data-visible="true"] .ja-workbench:visible [data-ja-review-shell]',
  );
}

/** 空草稿只通过 Composer 建立 Thread，并以真实最终答复证明普通准备 Turn 已完成。 */
async function prepareCurrentThread(page, providerObservations, deadline) {
  const existingThreadId = await selectedThreadId(page);
  const existingTimeline =
    existingThreadId !== null &&
    (await page.evaluate(
      (expected) =>
        [...globalThis.document.querySelectorAll(".ja-chat-timeline[data-thread-id]")].some(
          (timeline) => timeline.getAttribute("data-thread-id") === expected,
        ),
      existingThreadId,
    ));
  if (existingThreadId !== null && existingTimeline) {
    await waitForCurrentTimeline(page, existingThreadId, deadline);
    return {
      outcome: "existing_thread",
      currentThreadIdentityVerified: true,
      completedPreparationTurn: false,
    };
  }

  const observationStart = providerObservations.length;
  const composer = page.locator(".ja-conversation .ja-composer").last();
  const input = composer.getByRole("textbox", { name: "消息", exact: true });
  await input.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await input.fill(preparationPrompt);
  await composer.getByRole("button", { name: "发送", exact: true }).click({
    timeout: deadlineTimeout(deadline),
  });
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: preparationPrompt })
    .waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: preparationReply })
    .last()
    .waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await waitForProviderStage(providerObservations, observationStart, "preparation_final", deadline);

  const threadId = await selectedThreadId(page);
  assert.ok(threadId, "真实 Composer 准备 Turn 后必须出现服务端 Thread identity");
  await waitForCurrentTimeline(page, threadId, deadline);
  return {
    outcome: "completed",
    method: "real_composer",
    provider: "isolated_loopback",
    providerStage: "preparation_final",
    currentThreadIdentityVerified: true,
    completedPreparationTurn: true,
  };
}

/** 只在未提交来源快照完成且 AI Review 满足真实 ready 条件后继续，等待复用 driver 原始截止时间。 */
async function waitForReviewSnapshotReady(page, deadline, setStage = () => {}) {
  setStage("flow:uncommitted-snapshot-rpc");
  const sourceTrigger = await requireVisibleReviewScopeTrigger(page, deadline);
  validateUncommittedReviewScopeLabel(await sourceTrigger.getAttribute("aria-label"));

  const snapshotState = await page.waitForFunction(
    () => {
      const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
      const latestSnapshot = calls
        .filter(
          (call) => call.command === "ja_review_snapshot" && call.source?.kind === "uncommitted",
        )
        .at(-1);
      const pendingRead = calls.some(
        (call) =>
          ["ja_review_catalog", "ja_review_snapshot"].includes(call.command) &&
          call.phase === "started",
      );
      if (pendingRead || latestSnapshot === undefined || latestSnapshot.phase === "started")
        return false;
      return {
        sourceKind: latestSnapshot.source.kind,
        phase: latestSnapshot.phase,
        workspaceId: latestSnapshot.workspaceId,
        revision: latestSnapshot.resultRevision,
      };
    },
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  const settledSnapshot = await snapshotState.jsonValue();
  assert.equal(settledSnapshot.sourceKind, "uncommitted");
  assert.equal(
    settledSnapshot.phase,
    "resolved",
    "current uncommitted Review snapshot did not resolve successfully",
  );
  assert.ok(settledSnapshot.workspaceId, "current Review snapshot has no Workspace identity");
  assert.ok(settledSnapshot.revision, "current Review snapshot has no revision identity");

  setStage("flow:ai-review-toolbar-ready");
  await page.waitForFunction(
    () => {
      const session = globalThis.document.querySelector(
        ".ja-thread-workbench-session:not([hidden])",
      );
      const inspector = session?.querySelector(
        '.ja-inspector[aria-label="工作区面板"][data-visible="true"]',
      );
      const workbench = inspector?.querySelector(".ja-workbench");
      const button = workbench?.querySelector(
        '[data-ja-review-shell] .ja-review-shell-navigation button[aria-label="AI 审查"]',
      );
      return button instanceof globalThis.HTMLButtonElement && !button.disabled;
    },
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  return settledSnapshot;
}

/** 只读取可访问控件身份和焦点，不主动移动焦点或触发任何 Review 操作。 */
async function readReviewFocusFacts(page) {
  return page.evaluate(() => {
    const active = globalThis.document.activeElement;
    const activeElement = active
      ? {
          tagName: active.tagName,
          role: active.getAttribute("role"),
          ariaLabel: active.getAttribute("aria-label")?.slice(0, 120) ?? null,
          className: typeof active.className === "string" ? active.className.slice(0, 160) : null,
        }
      : null;
    return {
      activeElement,
      visibleMenuCount: [...globalThis.document.querySelectorAll('[role="menu"]')].filter(
        (menu) =>
          menu.getClientRects().length > 0 &&
          globalThis.getComputedStyle(menu).visibility === "visible",
      ).length,
      currentSource: (() => {
        const source = globalThis.document
          .querySelector(".ja-review-source-trigger[aria-label]")
          ?.getAttribute("aria-label");
        if (source === "审阅范围：未提交") return "uncommitted";
        if (source === "审阅范围：最后一轮") return "last_turn";
        if (source?.startsWith("审阅范围：提交 ")) return "commit";
        return source?.startsWith("审阅范围：") ? "comparison_ref" : "unknown";
      })(),
      refreshButtons: [...globalThis.document.querySelectorAll('button[aria-label="重新读取审阅"]')]
        .filter(
          (button) =>
            button.getClientRects().length > 0 &&
            globalThis.getComputedStyle(button).visibility === "visible",
        )
        .map((button) => ({ disabled: button.disabled })),
    };
  });
}

/**
 * 显式来源重试会卸载错误 notice 按钮；等待 React 读完目录与快照后只观察稳定顶部控件的焦点回程，
 * 不用脚本补焦点掩盖生产界面的恢复行为。
 */
async function waitForReviewRefreshFocus(page, review, deadline) {
  const refreshButton = review
    .locator(".ja-review-shell-navigation")
    .getByRole("button", { name: "重新读取审阅", exact: true });
  await refreshButton.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  assert.equal(await refreshButton.count(), 1, "Review toolbar refresh target must be unique");
  const refreshElement = await refreshButton.elementHandle();
  assert.ok(refreshElement, "Review toolbar refresh target must remain mounted");
  try {
    try {
      const enabled = await page.waitForFunction(
        (element) =>
          element.isConnected &&
          element instanceof globalThis.HTMLButtonElement &&
          !element.disabled,
        refreshElement,
        { timeout: deadlineTimeout(deadline) },
      );
      await enabled.dispose();
      const focused = await page.waitForFunction(
        (element) => element.isConnected && element === globalThis.document.activeElement,
        refreshElement,
        { timeout: deadlineTimeout(deadline) },
      );
      await focused.dispose();
    } catch (error) {
      const focusFacts = await refreshElement.evaluate((element) => {
        const active = globalThis.document.activeElement;
        const visibleSourceTriggers = [
          ...globalThis.document.querySelectorAll(".ja-review-source-trigger"),
        ].filter(
          (node) =>
            node.getClientRects().length > 0 &&
            globalThis.getComputedStyle(node).visibility === "visible",
        );
        return {
          refreshConnected: element.isConnected,
          refreshDisabled: element instanceof globalThis.HTMLButtonElement && element.disabled,
          activeIsRefresh: active === element,
          activeIsSourceTrigger: visibleSourceTriggers.some((node) => node === active),
          activeElement: active
            ? {
                tagName: active.tagName,
                role: active.getAttribute("role"),
                ariaLabel: active.getAttribute("aria-label"),
                className: typeof active.className === "string" ? active.className : null,
              }
            : null,
          visibleSourceTriggerCount: visibleSourceTriggers.length,
        };
      });
      throw new Error(
        `Review toolbar refresh focus did not return: ${JSON.stringify(focusFacts)}`,
        { cause: error },
      );
    }
  } finally {
    await refreshElement.dispose();
  }
  const focusReturned = await refreshButton.evaluate(
    (element) => element === globalThis.document.activeElement,
  );
  assert.equal(focusReturned, true, "completed explicit Review retry must return toolbar focus");
  return focusReturned;
}

/** 三类来源只接受真实 catalog 身份；比较层必须可读且只读，失败恢复必须来自真实错误 notice 点击。 */
export function validateReviewSourceSwitchEvidence(evidence) {
  assert.equal(evidence?.sources?.length, 3, "source switch must cover base, local and commit");
  assert.deepEqual(
    evidence.sources.map(({ sourceKind, refKind }) => ({ sourceKind, refKind })),
    [
      { sourceKind: "branch", refKind: "base" },
      { sourceKind: "branch", refKind: "local" },
      { sourceKind: "commit", refKind: "commit" },
    ],
  );
  for (const source of evidence.sources) {
    assert.ok(source.identity, "source identity must be present");
    assert.equal(source.snapshotResolved, true);
    assert.equal(source.snapshotSourceBound, true);
    assert.equal(source.snapshotWorkspaceBound, true);
    assert.ok(source.snapshotRevision, "source snapshot must retain its resolved revision");
    assert.equal(source.selectorFocusReturned, true);
    assert.deepEqual(source.capabilities, {
      present: true,
      stage: false,
      unstage: false,
      revert: false,
    });
    assert.equal(source.readOnly, true, "comparison sources must not expose write actions");
  }
  assert.ok(
    evidence.sources.some((source) => source.comparisonDiffRead === true),
    "at least one comparison-layer Diff must be read from the selected source",
  );
  assert.equal(evidence.sources[2].comparisonDiffRead, true, "commit comparison Diff must load");
  assert.equal(
    evidence.sources[2].comparisonRevisionBound,
    true,
    "commit comparison Diff must match the selected snapshot revision",
  );
  const { focusTrace, ...retryFacts } = evidence.errorRetry;
  assert.deepEqual(retryFacts, {
    sourceKind: "branch",
    refKind: "local",
    sourceIdentity: evidence.sources[1].identity,
    failedSnapshotSourceBound: true,
    failedSnapshotWorkspaceBound: true,
    injectedOnce: true,
    errorVisible: true,
    manualRefreshAvailable: true,
    retryNoticeClicked: true,
    refreshEnabledAfterSettle: true,
    recovered: true,
    recoveredSnapshotSourceBound: true,
    recoveredSnapshotWorkspaceBound: true,
    recoveredRevisionPresent: true,
    refreshFocusReturned: true,
  });
  assert.ok(focusTrace?.beforeClick);
  assert.ok(focusTrace?.afterClick);
  assert.ok(focusTrace?.whileSnapshotPending);
  assert.ok(focusTrace?.afterSnapshotResolve);
  assert.ok(focusTrace?.afterNoticeHidden);
  assert.deepEqual(evidence.restoredUncommitted, {
    snapshotResolved: true,
    snapshotSourceBound: true,
    snapshotWorkspaceBound: true,
    revisionPresent: true,
    selectorFocusReturned: true,
  });
  assert.equal(evidence.restoreFocusReturned, true);
  return evidence;
}

/** full 真窗只有真实捕获加载、可重试错误与恢复后来源入口证据才可宣称菜单闭环。 */
export function validateCatalogMenuReadinessEvidence(evidence) {
  assert.equal(evidence?.loadingStatusVisible, true);
  assert.equal(evidence?.initiallyEmptyOfGitGroups, true);
  assert.equal(evidence?.catalogFailureInjected, true);
  assert.equal(evidence?.catalogErrorVisible, true);
  assert.equal(evidence?.retryMenuItemAvailable, true);
  assert.equal(evidence?.frozenTurnSelectedBeforeRetry, evidence?.frozenTurnAvailable);
  assert.equal(evidence?.retryFocusReturned, true);
  assert.equal(evidence?.catalogRetryResolved, true);
  assert.equal(evidence?.selectedScopePreserved, true);
  assert.equal(evidence?.gitGroupsAvailableAfterRetry, true);
  assert.equal(evidence?.catalogStartDelta, 1);
  assert.ok(Number.isSafeInteger(evidence?.snapshotStartDelta) && evidence.snapshotStartDelta >= 0);
  assert.ok(Number.isSafeInteger(evidence?.diffStartDelta) && evidence.diffStartDelta >= 0);
  if (evidence.frozenTurnSelectedBeforeRetry) {
    assert.deepEqual(
      {
        catalogStartDelta: evidence.catalogStartDelta,
        snapshotStartDelta: evidence.snapshotStartDelta,
        diffStartDelta: evidence.diffStartDelta,
      },
      { catalogStartDelta: 1, snapshotStartDelta: 0, diffStartDelta: 0 },
    );
  }
  assert.deepEqual(evidence?.screenshots, [
    "coding-review-source-catalog-loading.png",
    "coding-review-source-catalog-error.png",
    "coding-review-source-catalog-ready.png",
  ]);
  return evidence;
}

/** 经真实 Workbench 菜单读取 base/local/commit；commit 使用有正文的 fixture 文件，避免把合法二进制空态误判为 Diff 未就绪。 */
async function verifyReviewSourceSwitches(
  page,
  evidenceDirectory,
  deadline,
  expectedWorkspaceId,
  setStage = () => {},
  { stopAfterRetry = false } = {},
) {
  const review = currentReviewShell(page);
  await review.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await requireVisibleReviewScopeTrigger(page, deadline);
  const catalog = await page.evaluate(() => {
    const calls = globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [];
    const latest = calls
      .filter((call) => call.command === "ja_review_catalog" && call.phase === "resolved")
      .at(-1);
    return latest?.catalogSources ?? null;
  });
  assert.ok(catalog, "Review source selector requires a real resolved catalog");
  const baseRef = catalog.refs.find((ref) => ref.kind === "base");
  const localRef = catalog.refs.find((ref) => ref.kind === "local");
  assert.ok(catalog.headCommitId, "Review catalog must identify the current HEAD commit");
  const commitId = catalog.commitIds.find((candidate) => candidate !== catalog.headCommitId);
  assert.ok(baseRef, "Review catalog must expose a base ref");
  assert.ok(localRef, "Review catalog must expose a local ref");
  assert.ok(commitId, "Review catalog must expose a commit");

  const injectionInstalled = await page.evaluate(() => {
    const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    if (typeof previous !== "function") return false;
    const state = { armed: false, used: false };
    const wrapped = async (request, delegate) => {
      if (request?.command === "ja_review_snapshot" && state.armed) {
        state.armed = false;
        state.used = true;
        return previous(request, async () => {
          const error = new Error("E2E injected one-shot source snapshot failure");
          error.code = "E2E_SOURCE_SNAPSHOT_FAILURE";
          throw error;
        });
      }
      return previous(request, delegate);
    };
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = wrapped;
    globalThis.__JA_SOURCE_SWITCH_INJECTION__ = { previous, wrapped, state };
    return true;
  });
  assert.equal(
    injectionInstalled,
    true,
    "source failure injection requires the existing invoke probe",
  );

  const sourceCases = [
    {
      source: { kind: "branch", refId: baseRef.refId },
      groupLabel: "比较",
      optionLabel: baseRef.label,
      sourceKind: "branch",
      refKind: "base",
      identity: baseRef.refId,
      diffPath: "src/conflict.ts",
    },
    {
      source: { kind: "branch", refId: localRef.refId },
      groupLabel: "比较",
      optionLabel: localRef.label,
      sourceKind: "branch",
      refKind: "local",
      identity: localRef.refId,
      diffPath: "src/conflict.ts",
      injectFailure: true,
    },
    {
      source: { kind: "commit", commitId },
      groupLabel: "比较",
      optionLabel: `提交 ${commitId.slice(0, 8)}`,
      sourceKind: "commit",
      refKind: "commit",
      identity: commitId,
      diffPath: "src/conflict.ts",
    },
  ];
  const selectedSourceCases = stopAfterRetry ? [sourceCases[1]] : sourceCases;
  const sources = [];
  let errorRetry;
  let sourceComparisonScreenshot = false;
  const applyCallsBefore = await page.evaluate(() =>
    Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_apply?.start ?? 0),
  );

  /** 等待本次来源选择发起的最终 snapshot RPC，不接受旧来源或旧请求回包。 */
  async function waitForSnapshot(source, afterCallIndex, { capturePendingFocus = false } = {}) {
    setStage(`source_switch:${source.kind === "branch" ? "branch" : "commit"}:snapshot`);
    const snapshotHandle = await page.waitForFunction(
      ({ expectedSource, startIndex, captureFocus }) => {
        const matches = (candidate) =>
          candidate?.kind === expectedSource.kind &&
          (expectedSource.kind === "branch"
            ? candidate.refId === expectedSource.refId
            : candidate.commitId === expectedSource.commitId);
        const call = (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [])
          .slice(startIndex)
          .filter((entry) => entry.command === "ja_review_snapshot" && matches(entry.source))
          .at(-1);
        if (captureFocus && call?.phase === "started") {
          const active = globalThis.document.activeElement;
          const trace = globalThis.__JA_SOURCE_SWITCH_FOCUS_TRACE__ ?? {};
          const visibleRefreshButtons = [
            ...globalThis.document.querySelectorAll('button[aria-label="重新读取审阅"]'),
          ].filter(
            (button) =>
              button.getClientRects().length > 0 &&
              globalThis.getComputedStyle(button).visibility === "visible",
          );
          trace.whileSnapshotPending = {
            activeElement: active
              ? {
                  tagName: active.tagName,
                  role: active.getAttribute("role"),
                  ariaLabel: active.getAttribute("aria-label")?.slice(0, 120) ?? null,
                  className:
                    typeof active.className === "string" ? active.className.slice(0, 160) : null,
                }
              : null,
            currentSource: (() => {
              const label = globalThis.document
                .querySelector(".ja-review-source-trigger[aria-label]")
                ?.getAttribute("aria-label");
              if (label === "审阅范围：未提交") return "uncommitted";
              if (label === "审阅范围：最后一轮") return "last_turn";
              if (label?.startsWith("审阅范围：提交 ")) return "commit";
              return label?.startsWith("审阅范围：") ? "comparison_ref" : "unknown";
            })(),
            refreshButtons: visibleRefreshButtons.map((button) => ({
              disabled: button instanceof globalThis.HTMLButtonElement && button.disabled,
            })),
          };
          globalThis.__JA_SOURCE_SWITCH_FOCUS_TRACE__ = trace;
        }
        return call === undefined || call.phase === "started" ? false : call;
      },
      {
        expectedSource: source,
        startIndex: afterCallIndex,
        captureFocus: capturePendingFocus,
      },
      { timeout: deadlineTimeout(deadline) },
    );
    const call = await snapshotHandle.jsonValue();
    await snapshotHandle.dispose();
    return call;
  }

  try {
    for (const selected of selectedSourceCases) {
      setStage(`source_switch:${selected.refKind}:select`);
      const beforeSelection = await page.evaluate(
        () => (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? []).length,
      );
      if (selected.injectFailure) {
        await page.evaluate(() => {
          globalThis.__JA_SOURCE_SWITCH_INJECTION__.state.armed = true;
        });
      }
      const selection = await selectReviewScopeOption(
        page,
        selected.groupLabel,
        selected.optionLabel,
        deadline,
      );
      const selectorFocusReturned = selection.focusReturned;
      assert.equal(selectorFocusReturned, true, "source selection must return keyboard focus");
      assert.equal(
        selection.scopeLabel,
        `审阅范围：${selected.optionLabel}`,
        "the selected source label must settle",
      );

      let snapshot = await waitForSnapshot(selected.source, beforeSelection);
      let errorFacts;
      if (selected.injectFailure) {
        assert.equal(snapshot.phase, "rejected");
        assert.equal(snapshot.errorCode, "E2E_SOURCE_SNAPSHOT_FAILURE");
        const failedSnapshot = snapshot;
        const notice = review.locator(".ja-review-notice.is-error");
        await notice.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
        assert.equal(await notice.count(), 1, "Review error notice retry target must be unique");
        assert.equal(await notice.isEnabled(), true, "Review error notice retry must be enabled");
        await page.screenshot({
          path: join(evidenceDirectory, "coding-review-source-error.png"),
          animations: "disabled",
        });
        const beforeRetry = await page.evaluate(
          () => (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? []).length,
        );
        const focusTrace = { beforeClick: await readReviewFocusFacts(page) };
        await notice.click({ timeout: deadlineTimeout(deadline) });
        const retryNoticeClicked = true;
        focusTrace.afterClick = await readReviewFocusFacts(page);
        snapshot = await waitForSnapshot(selected.source, beforeRetry, {
          capturePendingFocus: true,
        });
        focusTrace.whileSnapshotPending = await page.evaluate(
          () => globalThis.__JA_SOURCE_SWITCH_FOCUS_TRACE__?.whileSnapshotPending ?? null,
        );
        focusTrace.afterSnapshotResolve = await readReviewFocusFacts(page);
        await notice.waitFor({ state: "hidden", timeout: deadlineTimeout(deadline) });
        focusTrace.afterNoticeHidden = await readReviewFocusFacts(page);
        const refreshFocusReturned = await waitForReviewRefreshFocus(page, review, deadline);
        errorFacts = {
          sourceKind: selected.sourceKind,
          refKind: selected.refKind,
          sourceIdentity: selected.identity,
          failedSnapshotSourceBound:
            JSON.stringify(failedSnapshot.source) === JSON.stringify(selected.source),
          failedSnapshotWorkspaceBound: failedSnapshot.workspaceId === expectedWorkspaceId,
          injectedOnce: true,
          errorVisible: true,
          manualRefreshAvailable: true,
          retryNoticeClicked,
          refreshEnabledAfterSettle: true,
          recovered: snapshot.phase === "resolved",
          recoveredSnapshotSourceBound:
            JSON.stringify(snapshot.source) === JSON.stringify(selected.source),
          recoveredSnapshotWorkspaceBound: snapshot.workspaceId === expectedWorkspaceId,
          recoveredRevisionPresent:
            typeof snapshot.resultRevision === "string" && snapshot.resultRevision.length > 0,
          refreshFocusReturned,
          focusTrace,
        };
        assert.equal(errorFacts.recovered, true);
        if (stopAfterRetry) {
          const injectionUsed = await page.evaluate(
            () => globalThis.__JA_SOURCE_SWITCH_INJECTION__?.state.used === true,
          );
          assert.equal(injectionUsed, true, "diagnostic retry must consume its one-shot failure");
          assert.equal(errorFacts.failedSnapshotSourceBound, true);
          assert.equal(errorFacts.failedSnapshotWorkspaceBound, true);
          assert.equal(errorFacts.recoveredSnapshotSourceBound, true);
          assert.equal(errorFacts.recoveredSnapshotWorkspaceBound, true);
          assert.equal(errorFacts.recoveredRevisionPresent, true);
          assert.equal(errorFacts.refreshFocusReturned, true);
          return {
            scope: "source_retry_focus_diagnostic",
            verdict: "DIAGNOSTIC_COMPLETE",
            sourceRetry: {
              sourceKind: errorFacts.sourceKind,
              refKind: errorFacts.refKind,
              sourceIdentityPresent: true,
              failedSnapshotSourceBound: errorFacts.failedSnapshotSourceBound,
              failedSnapshotWorkspaceBound: errorFacts.failedSnapshotWorkspaceBound,
              injectedOnce: injectionUsed,
              retryNoticeClicked: errorFacts.retryNoticeClicked,
              recovered: errorFacts.recovered,
              recoveredSnapshotSourceBound: errorFacts.recoveredSnapshotSourceBound,
              recoveredSnapshotWorkspaceBound: errorFacts.recoveredSnapshotWorkspaceBound,
              recoveredRevisionPresent: errorFacts.recoveredRevisionPresent,
              refreshEnabledAfterSettle: errorFacts.refreshEnabledAfterSettle,
              refreshFocusReturned: errorFacts.refreshFocusReturned,
              focusTrace: errorFacts.focusTrace,
            },
          };
        }
      }

      assert.equal(snapshot.phase, "resolved", "selected Review source snapshot must resolve");
      assert.ok(snapshot.resultRevision, "source snapshot must carry its revision");
      assert.ok(Number.isSafeInteger(snapshot.comparisonFileCount));
      assert.equal(snapshot.workspaceId, expectedWorkspaceId);
      const snapshotSourceBound =
        JSON.stringify(snapshot.source) === JSON.stringify(selected.source);
      const snapshotWorkspaceBound = snapshot.workspaceId === expectedWorkspaceId;
      assert.equal(snapshotSourceBound, true, "resolved snapshot must match the selected source");
      assert.equal(
        snapshotWorkspaceBound,
        true,
        "resolved snapshot must match the active Workspace",
      );

      const comparisonFileCount = snapshot.comparisonFileCount;
      let comparisonDiffRead = false;
      let comparisonRevisionBound = false;
      if (comparisonFileCount > 0) {
        const row = review.getByRole("treeitem", {
          name: `查看 ${selected.diffPath} 的比较变更`,
          exact: true,
        });
        await row.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
        assert.equal(await row.count(), 1, "comparison 正文验收文件必须唯一");
        const fileId = await row.getAttribute("data-review-file-id");
        assert.ok(fileId, "comparison file must have a stable identity");
        await row.click({ timeout: deadlineTimeout(deadline) });
        const expectedDiff = {
          scopeLabel: `审阅范围：${selected.optionLabel}`,
          source: selected.source,
        };
        const diff = await waitForCodingReviewDiffReadiness(page, deadline, expectedDiff);
        assert.equal(diff.fileId, fileId, "comparison Diff must show the selected file");
        assert.equal(
          diff.snapshotRevision,
          snapshot.resultRevision,
          "comparison Diff must use the selected source snapshot revision",
        );
        assert.equal(diff.layer, "comparison");
        comparisonRevisionBound = diff.snapshotRevision === snapshot.resultRevision;
        comparisonDiffRead = true;
        if (!sourceComparisonScreenshot) {
          await page.screenshot({
            path: join(evidenceDirectory, "coding-review-source-comparison.png"),
            animations: "disabled",
          });
          sourceComparisonScreenshot = true;
        }
      }
      await review
        .getByRole("button", { name: "审查更多操作", exact: true })
        .click({ timeout: deadlineTimeout(deadline) });
      const readOnly =
        snapshot.capabilities?.present === true &&
        snapshot.capabilities?.stage === false &&
        snapshot.capabilities?.unstage === false &&
        snapshot.capabilities?.revert === false &&
        (await page.getByRole("menuitem", { name: /暂存|撤销/u }).count()) === 0;
      assert.equal(readOnly, true, "comparison source must not expose write actions");
      await page.keyboard.press("Escape");
      sources.push({
        sourceKind: selected.sourceKind,
        refKind: selected.refKind,
        identity: selected.identity,
        snapshotResolved: true,
        snapshotSourceBound,
        snapshotWorkspaceBound,
        snapshotRevision: snapshot.resultRevision,
        capabilities: snapshot.capabilities,
        comparisonFileCount,
        comparisonDiffRead,
        comparisonRevisionBound,
        readOnly,
        selectorFocusReturned,
      });
      if (errorFacts !== undefined) errorRetry = errorFacts;
    }

    const injectionUsed = await page.evaluate(
      () => globalThis.__JA_SOURCE_SWITCH_INJECTION__?.state.used === true,
    );
    assert.equal(injectionUsed, true, "source error path must be exercised exactly once");
    const beforeRestore = await page.evaluate(
      () => (globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? []).length,
    );
    const restoreSelection = await selectReviewScopeOption(page, "未提交", "全部", deadline);
    const restoreFocusReturned = restoreSelection.focusReturned;
    assert.equal(restoreFocusReturned, true);
    assert.equal(restoreSelection.scopeLabel, "审阅范围：未提交");
    const restored = await waitForSnapshot({ kind: "uncommitted" }, beforeRestore);
    assert.equal(restored.phase, "resolved");
    const restoredUncommitted = {
      snapshotResolved: restored.phase === "resolved",
      snapshotSourceBound:
        JSON.stringify(restored.source) === JSON.stringify({ kind: "uncommitted" }),
      snapshotWorkspaceBound: restored.workspaceId === expectedWorkspaceId,
      revisionPresent:
        typeof restored.resultRevision === "string" && restored.resultRevision.length > 0,
      selectorFocusReturned: restoreFocusReturned,
    };
    const applyCallsAfter = await page.evaluate(() =>
      Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_apply?.start ?? 0),
    );
    assert.equal(applyCallsAfter, applyCallsBefore, "source navigation must not write Git changes");
    const evidence = validateReviewSourceSwitchEvidence({
      sources,
      errorRetry,
      restoredUncommitted,
      restoreFocusReturned,
      screenshot: sourceComparisonScreenshot ? "coding-review-source-comparison.png" : null,
    });
    return evidence;
  } finally {
    await page
      .evaluate(() => {
        const injection = globalThis.__JA_SOURCE_SWITCH_INJECTION__;
        if (
          injection !== undefined &&
          globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ === injection.wrapped
        ) {
          globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = injection.previous;
        }
        delete globalThis.__JA_SOURCE_SWITCH_INJECTION__;
      })
      .catch(() => undefined);
  }
}

/**
 * Review 重试固定冻结身份；复审按真实提交摘要、发现数量与覆盖核验，不能把默认空摘要文案当作完成契约。
 * 重启后从同一服务端 Thread 回读只读报告与证据行，分别保留定位、复审和历史状态截图。
 */
async function runCodingReviewFlow({
  page,
  workspaceRoot,
  evidenceDirectory,
  isolatedRuntimeDirectory,
  providerObservations,
  setStage = () => {},
}) {
  let paletteMatrix;
  let selectedSnapshot;
  setStage("flow:review_driver");
  const visual = await runReviewRedesignWebView2({
    page,
    workspaceRoot,
    evidenceDirectory,
    scope: "git",
    verifyCatalogLoadingMenu: true,
    setStage,
    prepareThread: (currentPage, deadline) =>
      prepareCurrentThread(currentPage, providerObservations, deadline),
    afterGitReview: async (currentPage, deadline) => {
      setStage("flow:palette_matrix");
      const paletteTimeout = Math.min(60_000, Math.max(1, deadline - Date.now()));
      paletteMatrix = await verifyPaletteMatrix(currentPage, paletteTimeout);
      selectedSnapshot = await waitForReviewSnapshotReady(currentPage, deadline, setStage);
    },
  });
  setStage("flow:visual_contracts");
  assert.equal(visual.threadPreparation.currentThreadIdentityVerified, true);
  assert.ok(paletteMatrix);
  const catalogMenuReadiness = validateCatalogMenuReadinessEvidence(
    visual.gitReview.catalogMenuReadiness,
  );
  await installAiReviewProbe(page);

  setStage("flow:ai_review_start");
  const review = currentReviewShell(page);
  await review.waitFor({ state: "visible", timeout: 30_000 });
  const startButton = review.getByRole("button", { name: "AI 审查", exact: true });
  assert.equal(await startButton.count(), 1, "AI Review start target must be unique");
  const clickTarget = await startButton.evaluate((button) => {
    const rect = button.getBoundingClientRect();
    const shell = button.closest("[data-ja-review-shell]");
    const toolbar = shell?.querySelector(".ja-review-shell-header");
    const panel = shell?.querySelector(".ja-review-ai-panel");
    return {
      accessibleName: button.getAttribute("aria-label") ?? button.textContent?.trim() ?? "",
      visible: button.getClientRects().length > 0,
      disabled: button.disabled,
      ariaBusy: button.getAttribute("aria-busy"),
      bounds: { width: Math.round(rect.width), height: Math.round(rect.height) },
      reportSelectorPresent:
        toolbar?.querySelector('[role="combobox"][aria-label="审查记录"]') != null,
      resultPanelVisible: panel !== null && panel.getClientRects().length > 0,
      findingCount: panel?.querySelectorAll(".ja-review-ai-finding").length ?? 0,
    };
  });
  await page.evaluate((evidence) => {
    globalThis.__JA_CODING_REVIEW_UI_EVIDENCE__ = { beforeStartClick: evidence };
  }, clickTarget);
  assert.equal(clickTarget.visible, true);
  assert.equal(clickTarget.disabled, false, "AI Review start target was disabled");
  await startButton.click({ timeout: 60_000 });
  const failedStatus = review
    .locator(".ja-review-ai-panel .ja-review-ai-panel-status.is-error")
    .first();
  const retryButton = review.getByRole("button", { name: "重试 AI 审查", exact: true });
  await failedStatus.waitFor({ state: "visible", timeout: 60_000 });
  await retryButton.waitFor({ state: "visible", timeout: 30_000 });
  assert.notEqual((await failedStatus.innerText()).trim(), "");
  assert.equal(
    providerObservations.filter((stage) => stage === "review_list").length,
    0,
    "an unaccepted start must not dispatch a Provider review request",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "coding-review-retry-failed.png"),
    animations: "disabled",
  });
  await retryButton.click({ timeout: 30_000 });
  setStage("flow:ai_review_retry_result");
  const finding = review.locator(".ja-review-ai-finding").first();
  await finding.waitFor({ state: "visible", timeout: 120_000 });
  await failedStatus.waitFor({ state: "hidden", timeout: 30_000 });
  assert.equal(await retryButton.count(), 0, "successful retry must clear the retry action");
  const aiNarrowMatrix = await captureCodingReviewAiNarrowMatrix(
    page,
    evidenceDirectory,
    8 * 60_000,
  );
  const aiPaletteMatrix = await captureCodingReviewAiPaletteMatrix(
    page,
    evidenceDirectory,
    8 * 60_000,
  );
  const retryFacts = await page.evaluate(() => {
    const attempts = globalThis.__JA_CODING_REVIEW_START_ATTEMPTS__ ?? [];
    return {
      attempts: attempts.length,
      failureInjected: globalThis.__JA_CODING_REVIEW_START_FAILURE_USED__ === true,
      sameOperationKey:
        attempts.length >= 2 && attempts[0].operationKey === attempts[1].operationKey,
      sameSnapshotAttachment:
        attempts.length >= 2 &&
        attempts[0].snapshotAttachmentId === attempts[1].snapshotAttachmentId,
      identitiesPresent:
        typeof attempts[0]?.operationKey === "string" &&
        typeof attempts[0]?.snapshotAttachmentId === "string",
    };
  });
  assert.equal(retryFacts.attempts, 2);
  assert.equal(retryFacts.failureInjected, true);
  assert.equal(retryFacts.sameOperationKey, true);
  assert.equal(retryFacts.sameSnapshotAttachment, true);
  assert.equal(retryFacts.identitiesPresent, true);
  assert.equal(await review.locator(".ja-review-ai-finding").count(), 1);
  await finding.getByRole("button", { name: "定位", exact: true }).click({ timeout: 60_000 });
  const target = review.locator('[data-review-finding-target="true"]');
  await target.first().waitFor({ state: "visible", timeout: 30_000 });
  const findingLocation = await finding.locator(".ja-review-ai-finding-location").innerText();
  await page.screenshot({
    path: join(evidenceDirectory, "coding-review-finding-located.png"),
    animations: "disabled",
  });

  await finding.getByRole("button", { name: "修复", exact: true }).click({ timeout: 60_000 });
  setStage("flow:repair_turn");
  const repairFeedback = review.locator(".ja-review-ai-repair-feedback[role='status']");
  await repairFeedback.first().waitFor({ state: "visible", timeout: 60_000 });
  assert.match((await repairFeedback.first().innerText()).trim(), /^修复已(?:提交|排队)$/u);
  const repairedPath = join(workspaceRoot, ...reviewPath.split("/"));
  await waitForFileLine(repairedPath, repairedLine);

  const countsBeforeRefresh = await page.evaluate(() => ({
    ...globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_snapshot,
  }));
  await review
    .getByRole("button", { name: "重新读取审阅", exact: true })
    .click({ timeout: 60_000 });
  await page.waitForFunction(
    (prior) =>
      Number(globalThis.__JA_REVIEW_REDESIGN_COUNTS__?.ja_review_snapshot?.resolved ?? 0) > prior,
    Number(countsBeforeRefresh.resolved ?? 0),
    { timeout: 60_000 },
  );
  await review.getByRole("button", { name: "重新审查", exact: true }).click({ timeout: 60_000 });
  const freshReviewSummary = review.getByText("复审基于修复后的新快照，未发现原问题。", {
    exact: true,
  });
  // 修复 Turn 的提交可能推进 CAS；只允许真实 CONFLICT 经可见重试恢复，其他错误仍直接使验收失败。
  await freshReviewSummary.or(retryButton).first().waitFor({ state: "visible", timeout: 120_000 });
  let rereviewAdmissionRetried = false;
  if (await retryButton.isVisible()) {
    const rejectedStart = await page.evaluate(() =>
      (globalThis.__JA_CODING_REVIEW_NATIVE_CALLS__ ?? [])
        .filter((call) => call.command === "ja_ai_review_start")
        .at(-1),
    );
    assert.equal(rejectedStart?.phase, "rejected");
    assert.equal(rejectedStart?.errorCode, "CONFLICT");
    await failedStatus.waitFor({ state: "visible", timeout: 30_000 });
    await page.screenshot({
      path: join(evidenceDirectory, "coding-review-rereview-conflict.png"),
      animations: "disabled",
    });
    await retryButton.click({ timeout: 30_000 });
    rereviewAdmissionRetried = true;
  }
  await freshReviewSummary.waitFor({ state: "visible", timeout: 120_000 });
  const rereviewAttempts = await page.evaluate(() =>
    (globalThis.__JA_CODING_REVIEW_START_ATTEMPTS__ ?? []).slice(2),
  );
  assert.equal(rereviewAttempts.length, rereviewAdmissionRetried ? 2 : 1);
  for (const attempt of rereviewAttempts) {
    assert.equal(attempt.operationKey, rereviewAttempts[0].operationKey);
    assert.equal(attempt.snapshotAttachmentId, rereviewAttempts[0].snapshotAttachmentId);
  }
  await review
    .locator(".ja-review-ai-panel-header")
    .getByText("0 项发现", { exact: true })
    .waitFor({ state: "visible", timeout: 30_000 });
  assert.equal(await review.locator(".ja-review-ai-finding").count(), 0);
  assert.match(
    await review.locator(".ja-review-ai-coverage").innerText(),
    /^覆盖不完整：1\/\d+ 个文件/u,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "coding-review-fresh-rereview.png"),
    animations: "disabled",
  });
  // 历史项现在按需物化在 Portal 子菜单，必须实际打开并读取，不能从已移除的选择器推断保留结果。
  await review
    .getByRole("button", { name: "审查更多操作", exact: true })
    .click({ timeout: 30_000 });
  await page.getByRole("menuitem", { name: "审查记录", exact: true }).focus();
  await page.keyboard.press("ArrowRight");
  const retainedRuns = page.getByRole("menuitemradio", { name: /^审查 \d+ ·/u });
  await retainedRuns.first().waitFor({ state: "visible", timeout: 30_000 });
  assert.equal(await retainedRuns.count(), 2, "both live Review reports must be retained");
  const retainedRunLabels = (await retainedRuns.allTextContents()).join(" | ");
  await page.keyboard.press("Escape");
  await page.keyboard.press("Escape");
  const readBack = await page.evaluate(() => ({
    commands: { ...globalThis.__JA_CODING_REVIEW_COUNTS__ },
  }));
  readBack.runs = retainedRunLabels;
  assert.match(
    readBack.runs ?? "",
    /审查 2/u,
    "the new snapshot review must be retained beside the prior report",
  );
  assert.equal(
    Number(readBack.commands.ja_ai_review_start ?? 0),
    3 + Number(rereviewAdmissionRetried),
  );
  assert.ok(Number(readBack.commands.ja_ai_review_read ?? 0) >= 2);
  const threadId = await selectedThreadId(page);
  assert.ok(threadId, "Review restart history must retain the active server Thread identity");
  const sourceSwitchEvidence = await verifyReviewSourceSwitches(
    page,
    evidenceDirectory,
    Date.now() + 5 * 60_000,
    selectedSnapshot.workspaceId,
    setStage,
  );
  setStage("flow:restart_history");
  const restartHistory = await verifyReviewRestartHistory(
    page,
    threadId,
    selectedSnapshot.workspaceId,
    evidenceDirectory,
    isolatedRuntimeDirectory,
  );
  const snapshotBinding = await page.evaluate((snapshot) => {
    return {
      reviewSnapshots: globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [],
      nativeCalls: globalThis.__JA_CODING_REVIEW_NATIVE_CALLS__ ?? [],
      selectedSnapshot: snapshot,
    };
  }, selectedSnapshot);
  const snapshotStartBindings = validateReviewSnapshotStartBindings(snapshotBinding);
  const finalReadCalls = await page.evaluate(() =>
    Number(globalThis.__JA_CODING_REVIEW_COUNTS__?.ja_ai_review_read ?? 0),
  );
  assert.ok(visual.gitReview.hiddenSnapshotDelta === 0);
  assert.equal(visual.gitReview.matrix.length, 12);
  assert.deepEqual(
    visual.gitReview.matrix
      .map(({ devicePixelRatio }) => normalizeDevicePixelRatio(devicePixelRatio))
      .sort((left, right) => left - right),
    [1, 1, 1, 1, 1.25, 1.25, 1.25, 1.25, 1.5, 1.5, 1.5, 1.5],
  );
  assert.equal(aiNarrowMatrix.length, CODING_REVIEW_AI_NARROW_MATRIX.length);
  assert.deepEqual(
    aiNarrowMatrix.map(({ themeMode, devicePixelRatio }) => [
      themeMode,
      normalizeDevicePixelRatio(devicePixelRatio),
    ]),
    CODING_REVIEW_AI_NARROW_MATRIX.map(({ theme, devicePixelRatio }) => [theme, devicePixelRatio]),
  );
  await page.screenshot({
    path: join(evidenceDirectory, "coding-review-history-restarted.png"),
    animations: "disabled",
  });
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: "PASS",
    gitReview: {
      hiddenSnapshotDelta: visual.gitReview.hiddenSnapshotDelta,
      catalogMenuReadiness,
      conflictDiagnostic: visual.gitReview.conflictDiagnostic,
      representativeMatrixFrames: visual.gitReview.matrix.length,
      representativeMatrix: visual.gitReview.matrix,
      paletteChecks: paletteMatrix,
      sourceSwitch: sourceSwitchEvidence,
    },
    threadPreparation: visual.threadPreparation,
    aiReview: {
      startCalls: readBack.commands.ja_ai_review_start,
      readCalls: finalReadCalls,
      findingLocation,
      repairToolCall: "edit",
      repairVerifiedByWorkspaceRead: true,
      freshSnapshotReReview: true,
      rereviewAdmissionRetried,
      reportsRetained: 2,
      snapshotStartBindings,
      retryAttempts: retryFacts.attempts,
      failureStateShown: true,
      retryReusedOperationKey: retryFacts.sameOperationKey,
      retryReusedSnapshotAttachment: retryFacts.sameSnapshotAttachment,
      restartHistory,
      externalProviderCalls: 0,
      narrowMatrix: aiNarrowMatrix,
      paletteLayoutMatrix: aiPaletteMatrix,
    },
    visualAcceptanceLimits: {
      physicalDpi: "not_verified",
      inputMethod: "not_verified",
    },
  };
}

/**
 * 仅准备一个真实 Thread 和未提交 Review 快照，然后执行一次拒绝重试与 AI Review；
 * 项目启动失败时在点击边界冻结配置读取和按钮状态，并用短等待避免空转完整验收期限。
 */
async function runAiReviewDiagnosticOnly({
  page,
  evidenceDirectory,
  providerObservations,
  providerDiagnostics,
}) {
  const deadline = Date.now() + 15 * 60_000;
  await page.context().addInitScript(installReviewInvokeProbe);
  await page.context().addInitScript(installWorkspaceBootstrapProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: deadlineTimeout(deadline) });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: deadlineTimeout(deadline),
  });
  await page.waitForFunction(
    async () => {
      const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
      if (typeof invoke !== "function") return false;
      try {
        const state = await invoke("ja_runtime_state", {});
        return state?.status === "ready" && Number.isSafeInteger(state?.generation);
      } catch {
        return false;
      }
    },
    undefined,
    { timeout: deadlineTimeout(deadline) },
  );

  const selectedProject = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selectedProject.count()) === 0) {
    const addProject = page.getByRole("button", { name: "添加项目", exact: true });
    const buttonCount = await addProject.count();
    if (buttonCount !== 1) {
      await page.evaluate(() => {
        const diagnostics = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__;
        if (diagnostics !== undefined) diagnostics.projectSelection.buttonFound = false;
      });
      throw new Error("AI Review diagnostic could not find the project picker button");
    }
    let readinessWaitCompleted = false;
    try {
      await page.waitForFunction(
        () => {
          const diagnostics = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__;
          const button = globalThis.document.querySelector('button[aria-label="添加项目"]');
          if (diagnostics === undefined || button === null) return false;
          const buttonDisabled = button.matches(":disabled");
          const configuration = diagnostics.configurationRead;
          const scopeReady = configuration.lastPhase === "resolved" && configuration.resolved > 0;
          const connectingNoticeVisible = [
            ...globalThis.document.querySelectorAll('[role="status"]'),
          ].some(
            (element) =>
              element.getClientRects().length > 0 &&
              element.textContent?.trim() === "正在准备本地数据…",
          );
          Object.assign(diagnostics.projectSelection, {
            buttonFound: true,
            buttonDisabled,
            scopeReadyInferredAtClick: scopeReady,
            turnAdmissionReadyInferredAtClick:
              scopeReady && !buttonDisabled ? true : connectingNoticeVisible ? false : null,
          });
          return scopeReady && !buttonDisabled;
        },
        undefined,
        { timeout: Math.min(60_000, deadlineTimeout(deadline)) },
      );
      readinessWaitCompleted = true;
    } catch {
      readinessWaitCompleted = false;
    }
    const buttonDisabled = await addProject.isDisabled();
    const configurationReadAtClick = await page.evaluate(() => {
      const read = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__?.configurationRead;
      return read === undefined
        ? { started: 0, resolved: 0, rejected: 0, lastPhase: null, lastErrorCode: null }
        : {
            started: read.started,
            resolved: read.resolved,
            rejected: read.rejected,
            lastPhase: read.lastPhase,
            lastErrorCode: read.lastErrorCode,
          };
    });
    const scopeReadyInferredAtClick =
      configurationReadAtClick.lastPhase === "resolved" && configurationReadAtClick.resolved > 0;
    const turnAdmissionReadyInferredAtClick = await page.evaluate(() => {
      const diagnostics = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__;
      return diagnostics?.projectSelection?.turnAdmissionReadyInferredAtClick ?? null;
    });
    await page.evaluate(
      ({
        found,
        disabled,
        reads,
        scopeReadyInferred,
        turnAdmissionReadyInferred,
        waitCompleted,
      }) => {
        const diagnostics = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__;
        if (diagnostics === undefined) return;
        Object.assign(diagnostics.projectSelection, {
          buttonFound: found,
          buttonDisabled: disabled,
          configurationReadAtClick: reads,
          scopeReadyInferredAtClick: scopeReadyInferred,
          turnAdmissionReadyInferredAtClick: turnAdmissionReadyInferred,
          readinessWaitCompleted: waitCompleted,
        });
      },
      {
        found: buttonCount === 1,
        disabled: buttonDisabled,
        reads: configurationReadAtClick,
        scopeReadyInferred: scopeReadyInferredAtClick,
        turnAdmissionReadyInferred: turnAdmissionReadyInferredAtClick,
        waitCompleted: readinessWaitCompleted,
      },
    );
    if (!readinessWaitCompleted || buttonDisabled !== false || !scopeReadyInferredAtClick) {
      throw new Error(
        `AI Review diagnostic did not reach boot and settings readiness before project selection: ${JSON.stringify(
          summarizeProjectBootstrapDiagnostics(
            await page.evaluate(
              () => globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__ ?? null,
            ),
          ),
        )}`,
      );
    }
    await page.evaluate(() => {
      const diagnostics = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__;
      if (diagnostics !== undefined) diagnostics.projectSelection.clicked = true;
    });
    await addProject.click({ timeout: Math.min(5_000, deadlineTimeout(deadline)) });
    await page.evaluate(() => {
      const diagnostics = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__;
      if (diagnostics !== undefined) diagnostics.projectSelection.clickReturned = true;
    });
    await page
      .waitForFunction(
        () => {
          const diagnostics = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__;
          if (diagnostics === undefined) return false;
          const selected =
            globalThis.document.querySelector(
              '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
            ) !== null;
          diagnostics.projectSelection.selected = selected;
          const open = diagnostics.projectSelection.workspaceOpen;
          return selected || open.lastPhase === "resolved" || open.lastPhase === "rejected";
        },
        undefined,
        { timeout: Math.min(5_000, deadlineTimeout(deadline)) },
      )
      .catch(() => undefined);
    const projectBootstrap = summarizeProjectBootstrapDiagnostics(
      await page.evaluate(() => globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__ ?? null),
    );
    if (
      projectBootstrap.projectSelection.workspaceOpen.started === 0 ||
      projectBootstrap.projectSelection.workspaceOpen.directoryPassedToNative !== true ||
      projectBootstrap.projectSelection.workspaceOpen.lastPhase !== "resolved"
    ) {
      throw new Error(
        `AI Review diagnostic project selection did not pass a directory to workspace/open: ${JSON.stringify(projectBootstrap)}`,
      );
    }
    if (!projectBootstrap.projectSelection.selected) {
      await selectedProject.waitFor({
        state: "visible",
        timeout: Math.min(10_000, deadlineTimeout(deadline)),
      });
    }
  }
  await page.evaluate(() => {
    const diagnostics = globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__;
    if (diagnostics !== undefined) {
      diagnostics.projectSelection.selected =
        globalThis.document.querySelector(
          '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
        ) !== null;
    }
  });
  await selectedProject.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await page
    .locator(".ja-navigation-sidebar .ja-workspace-scope-status.is-ready")
    .waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  const threadPreparation = await prepareCurrentThread(page, providerObservations, deadline);
  const threadId = await selectedThreadId(page);
  assert.ok(threadId, "AI Review diagnostic must use a real selected Thread");
  await waitForCurrentTimeline(page, threadId, deadline);

  const inspector = page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
  if ((await inspector.getAttribute("data-visible")) !== "true") {
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click({
      timeout: deadlineTimeout(deadline),
    });
  }
  await inspector.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  const workbench = inspector.locator(".ja-workbench:visible");
  await workbench.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  let reviewTab = workbench.locator('[data-workbench-tab="review"]');
  if ((await reviewTab.count()) === 0) {
    await workbench.getByRole("button", { name: "新建标签页", exact: true }).click({
      timeout: deadlineTimeout(deadline),
    });
    const menus = page.getByRole("menu");
    await menus.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
    await menus
      .getByRole("menuitem")
      .filter({ hasText: "审查" })
      .first()
      .click({
        timeout: deadlineTimeout(deadline),
      });
    reviewTab = workbench.locator('[data-workbench-tab="review"]');
  }
  await reviewTab.click({ timeout: deadlineTimeout(deadline) });
  const review = currentReviewShell(page);
  await review.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await selectReviewScopeOption(page, "未提交", "全部", deadline);
  const selectedSnapshot = await waitForReviewSnapshotReady(page, deadline);

  await installAiReviewProbe(page);
  const start = review.getByRole("button", { name: "AI 审查", exact: true });
  assert.equal(await start.count(), 1, "AI Review start target must be unique");
  await start.click({ timeout: deadlineTimeout(deadline) });
  await review
    .locator(".ja-review-ai-panel .ja-review-ai-panel-status.is-error")
    .waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  const retry = review.getByRole("button", { name: "重试 AI 审查", exact: true });
  await retry.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  assert.equal(
    providerObservations.filter((stage) => stage === "review_list").length,
    0,
    "the deliberately rejected start must not contact the Provider",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "coding-review-diagnostic-retry.png"),
    animations: "disabled",
  });
  await retry.click({ timeout: deadlineTimeout(deadline) });
  const finding = review.locator(".ja-review-ai-finding").first();
  await finding.waitFor({ state: "visible", timeout: deadlineTimeout(deadline) });
  await review
    .locator(".ja-review-ai-panel .ja-review-ai-panel-status.is-error")
    .waitFor({ state: "hidden", timeout: deadlineTimeout(deadline) });

  const nativeEvidence = await page.evaluate(() => ({
    calls: (globalThis.__JA_CODING_REVIEW_NATIVE_CALLS__ ?? []).map((call) => ({
      command: call.command,
      phase: call.phase,
      errorCode: call.errorCode ?? null,
      threadId: call.acceptedThreadId ?? call.resultThreadId ?? call.threadId ?? null,
      turnId: call.turnId ?? call.resultTurnId ?? null,
      status: call.status ?? null,
      reportPresent: call.reportPresent ?? null,
      snapshotIdPresent: typeof call.snapshotId === "string",
      snapshotAttachmentPresent: typeof call.snapshotAttachmentId === "string",
    })),
    findingCount: globalThis.document.querySelectorAll(".ja-review-ai-panel .ja-review-ai-finding")
      .length,
    retryVisible: [...globalThis.document.querySelectorAll("button")].some(
      (button) =>
        button.getClientRects().length > 0 && button.getAttribute("aria-label") === "重试",
    ),
  }));
  const acceptedStart = nativeEvidence.calls.find(
    (call) => call.command === "ja_ai_review_start" && call.phase === "resolved",
  );
  assert.ok(acceptedStart?.threadId, "accepted Review start must identify its Thread");
  assert.ok(acceptedStart?.turnId, "accepted Review start must identify its Turn");
  assert.ok(nativeEvidence.findingCount > 0, "AI Review diagnostic must show a finding");
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    scope: "ai_review_start_diagnostic",
    verdict: "DIAGNOSTIC_COMPLETE",
    threadPreparation,
    selectedSnapshot: {
      sourceKind: selectedSnapshot.sourceKind,
      workspaceIdPresent: typeof selectedSnapshot.workspaceId === "string",
      revisionPresent: typeof selectedSnapshot.revision === "string",
    },
    aiReview: {
      provider: providerDiagnostics(),
      providerStages: [...providerObservations],
      nativeCalls: nativeEvidence.calls,
      findingCount: nativeEvidence.findingCount,
      retryVisibleAfterFinding: nativeEvidence.retryVisible,
      acceptedThreadId: acceptedStart.threadId,
      acceptedTurnId: acceptedStart.turnId,
      reportReadBack: nativeEvidence.calls.some(
        (call) => call.command === "ja_ai_review_read" && call.reportPresent === true,
      ),
    },
    acceptanceScope: {
      visualMatrix: "previously_verified_not_repeated",
      repairAndReReview: "not_run",
      nativeImage: "not_verified",
      externalProvider: false,
    },
  };
}

/**
 * 运行隔离 Provider 与真实 WebView2 production driver，并沿用现有 report 原子写入边界；
 * 预检在创建 Provider 前返回，避免“仅预检”建立 listener 或 profile；正式运行再预热新 UDF 并保留可选失败诊断。
 */
export async function runAcceptance(
  argv = process.argv.slice(2),
  { providerFactory = startReviewCapabilityProvider } = {},
) {
  if (process.platform !== "win32")
    throw new Error("coding-capability review acceptance requires Windows");
  const preserveFailedProfile = argv.includes("--preserve-failed-profile");
  const aiDiagnosticOnly = argv.includes("--ai-diagnostic-only");
  const options = parseArguments(
    argv.filter(
      (argument) => argument !== "--preserve-failed-profile" && argument !== "--ai-diagnostic-only",
    ),
  );
  options.preserveFailedProfile = preserveFailedProfile;
  options.prewarmWebview = true;
  if (options.preflightOnly) {
    const preflight = await runPreflight(options);
    process.stdout.write(
      "CODING_CAPABILITY_REVIEW_PREFLIGHT_OK " + JSON.stringify(preflight) + "\n",
    );
    return preflight;
  }
  const fixture = await providerFactory();
  options.providerBaseUrl = fixture.endpoint;
  options.prepareIsolatedHome = prepareFullAccessProfile;
  let currentPhase = "production:driver_start";
  options.driver = async (input) => {
    const setStage = (phase) => {
      if (typeof phase === "string" && /^[a-z0-9:_-]{1,80}$/u.test(phase)) {
        currentPhase = phase;
      }
    };
    let pageErrorCount = 0;
    let consoleErrorCount = 0;
    input.page.on("pageerror", () => {
      pageErrorCount += 1;
    });
    input.page.on("console", (message) => {
      if (message.type() === "error") consoleErrorCount += 1;
    });
    try {
      const flow = aiDiagnosticOnly ? runAiReviewDiagnosticOnly : runCodingReviewFlow;
      const report = await flow({
        ...input,
        setStage,
        providerObservations: fixture.observations,
        providerDiagnostics: fixture.diagnostics,
      });
      assert.equal(pageErrorCount, 0, "Review must not emit a page error");
      assert.equal(consoleErrorCount, 0, "Review must not emit a console error");
      return { ...report, browserErrors: { pageErrorCount, consoleErrorCount } };
    } catch (error) {
      if (preserveFailedProfile) {
        const pageDiagnostics = await input.page
          .evaluate(() => ({
            url: globalThis.location.href,
            title: globalThis.document.title,
            readyState: globalThis.document.readyState,
            visibleStatusCount: [...globalThis.document.querySelectorAll('[role="status"]')].filter(
              (element) => element.getClientRects().length > 0,
            ).length,
            visibleAlertCount: [...globalThis.document.querySelectorAll('[role="alert"]')].filter(
              (element) => element.getClientRects().length > 0,
            ).length,
            interactionState: (() => {
              const active = globalThis.document.activeElement;
              const visible = (element) =>
                element.getClientRects().length > 0 &&
                globalThis.getComputedStyle(element).visibility === "visible";
              const triggers = [
                ...globalThis.document.querySelectorAll(".ja-review-source-trigger[aria-label]"),
              ].filter(visible);
              const sourceLabel =
                triggers.length === 1 ? triggers[0].getAttribute("aria-label") : null;
              const currentSource =
                sourceLabel === "审阅范围：未提交"
                  ? "uncommitted"
                  : sourceLabel === "审阅范围：最后一轮"
                    ? "last_turn"
                    : sourceLabel?.startsWith("审阅范围：提交 ")
                      ? "commit"
                      : sourceLabel?.startsWith("审阅范围：")
                        ? "comparison_ref"
                        : "unknown";
              return {
                activeElement: active
                  ? {
                      tagName: active.tagName,
                      role: active.getAttribute("role"),
                      ariaLabel: active.getAttribute("aria-label")?.slice(0, 120) ?? null,
                      className:
                        typeof active.className === "string"
                          ? active.className.slice(0, 160)
                          : null,
                    }
                  : null,
                visibleSourceTriggerCount: triggers.length,
                currentSource,
                visibleMenuCount: [
                  ...globalThis.document.querySelectorAll('.ja-review-source-menu[role="menu"]'),
                ].filter(visible).length,
                topRefreshButtons: [
                  ...globalThis.document.querySelectorAll(
                    '.ja-review-shell-navigation button[aria-label="重新读取审阅"]',
                  ),
                ].map((button) => ({ visible: visible(button), disabled: button.disabled })),
              };
            })(),
            codingReview: {
              uiEvidence: globalThis.__JA_CODING_REVIEW_UI_EVIDENCE__ ?? null,
              nativeCalls: globalThis.__JA_CODING_REVIEW_NATIVE_CALLS__ ?? [],
              counts: { ...(globalThis.__JA_CODING_REVIEW_COUNTS__ ?? {}) },
              startAttempts: globalThis.__JA_CODING_REVIEW_START_ATTEMPTS__ ?? [],
              currentAiReview: (() => {
                const panel = globalThis.document.querySelector(".ja-review-ai-panel");
                return panel === null
                  ? null
                  : {
                      reportSelectorPresent:
                        globalThis.document.querySelector(
                          '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"] .ja-workbench [data-ja-review-shell] .ja-review-shell-header [role="combobox"][aria-label="审查记录"]',
                        ) !== null,
                      findingCount: panel.querySelectorAll(".ja-review-ai-finding").length,
                      visibleErrorCount: [...panel.querySelectorAll('[role="alert"]')].filter(
                        (element) => element.getClientRects().length > 0,
                      ).length,
                      resultPanelVisible: panel.getClientRects().length > 0,
                    };
              })(),
            },
            reviewRedesign: {
              nativeCalls: globalThis.__JA_REVIEW_REDESIGN_CALLS__ ?? [],
              counts: { ...(globalThis.__JA_REVIEW_REDESIGN_COUNTS__ ?? {}) },
              sourceSelectionWait: globalThis.__JA_REVIEW_SCOPE_SELECTION_WAIT__ ?? null,
              surface: {
                viewportWidth: globalThis.innerWidth,
                viewportHeight: globalThis.innerHeight,
                outerWidth: globalThis.outerWidth,
                outerHeight: globalThis.outerHeight,
                deviceScaleFactor: globalThis.devicePixelRatio,
              },
              currentDiff: (() => {
                const review = globalThis.document.querySelector(
                  '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"] .ja-workbench [data-ja-review-shell]',
                );
                const diff = review?.querySelector(".ja-review-diff");
                const alerts = [...(review?.querySelectorAll('[role="alert"]') ?? [])].filter(
                  (element) => element.getClientRects().length > 0,
                );
                const header = diff?.querySelector(".ja-review-diff-header");
                return diff === null || diff === undefined
                  ? null
                  : {
                      layout: review.getAttribute("data-review-layout"),
                      selectedFileIdPresent:
                        typeof diff.getAttribute("data-review-selected-file-id") === "string",
                      selectedLayer: diff.getAttribute("data-review-selected-layer"),
                      loading: diff.querySelector('[role="status"]') !== null,
                      visibleAlertCount: alerts.length,
                      staleNoticeVisible: alerts.some(
                        (element) =>
                          element.textContent?.trim() === "工作区已经变化，请重新读取 Review。",
                      ),
                      diffHeaderVisible: header !== null && header.getClientRects().length > 0,
                    };
              })(),
            },
          }))
          .catch(() => ({ pageUnavailable: true }));
        const bootstrapEvidence = await input.page
          .evaluate(() => globalThis.__JA_CODING_REVIEW_BOOTSTRAP_DIAGNOSTICS__ ?? null)
          .catch(() => null);
        const diagnostics = {
          pageErrorCount,
          consoleErrorCount,
          phase: currentPhase,
          failure: summarizeCodingReviewFailure(error),
          provider: fixture.diagnostics(),
          projectBootstrap: summarizeProjectBootstrapDiagnostics(bootstrapEvidence),
          page: pageDiagnostics,
        };
        const outputDirectory = resolve(input.evidenceDirectory, "..");
        await input.page
          .screenshot({
            path: join(outputDirectory, "coding-review-page-failure.png"),
            animations: "disabled",
          })
          .catch(() => {});
        await writeFile(
          join(outputDirectory, "coding-review-page-diagnostic.json"),
          `${JSON.stringify(diagnostics, null, 2)}\n`,
          "utf8",
        );
      }
      throw error;
    }
  };
  options.validateReport = (report) => {
    if (aiDiagnosticOnly) {
      assert.equal(report?.contractVersion, 1);
      assert.equal(report?.scope, "ai_review_start_diagnostic");
      assert.equal(report?.verdict, "DIAGNOSTIC_COMPLETE");
      assert.ok(report?.aiReview?.acceptedThreadId);
      assert.ok(report?.aiReview?.acceptedTurnId);
      assert.ok(report?.aiReview?.provider?.requestCount > 0);
      return;
    }
    assert.equal(report?.contractVersion, 1);
    assert.equal(report?.verdict, "PASS");
    assert.equal(report?.aiReview?.freshSnapshotReReview, true);
    assert.equal(report?.aiReview?.restartHistory?.appServerRestarted, true);
    assert.equal(report?.aiReview?.restartHistory?.sameThreadRestored, true);
    assert.equal(report?.aiReview?.restartHistory?.hiddenSnapshotDelta, 0);
    assert.equal(report?.aiReview?.restartHistory?.currentSnapshotRestored, true);
    assert.equal(report?.aiReview?.restartHistory?.historicalReportReadBack, true);
    assert.equal(report?.aiReview?.restartHistory?.frozenEvidence?.contentMatches, true);
    assert.equal(report?.threadPreparation?.currentThreadIdentityVerified, true);
    assert.equal(report?.aiReview?.narrowMatrix?.length, CODING_REVIEW_AI_NARROW_MATRIX.length);
    assert.equal(
      report?.aiReview?.paletteLayoutMatrix?.length,
      CODING_REVIEW_AI_PALETTE_LAYOUT_MATRIX.length,
    );
    assert.equal(report?.visualAcceptanceLimits?.physicalDpi, "not_verified");
    assert.equal(report?.visualAcceptanceLimits?.inputMethod, "not_verified");
    const execution = report?.execution;
    if (options.nativeExecutable !== undefined) {
      assert.equal(execution?.boundary, "native_sidecar");
      assert.equal(execution?.nativeImageVerified, true);
      assert.equal(execution?.nativeImage?.sha256, options.expectedNativeSha256);
      assert.equal(execution?.nativeImage?.sourceHashMatched, true);
      assert.equal(execution?.nativeImage?.noFallback, true);
      assert.equal(
        execution?.nativeImage?.resourceName,
        "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
      );
      assert.ok(Number.isSafeInteger(execution?.nativeImage?.sizeBytes));
    } else if (options.jar !== undefined) {
      assert.equal(
        execution?.boundary,
        options.cdpEndpoint === undefined ? "debug_jar" : "external_cdp",
      );
      assert.equal(execution?.nativeImageVerified, false);
      if (options.cdpEndpoint === undefined) assert.ok(execution?.jar?.sha256);
    } else {
      assert.equal(execution?.boundary, "external_cdp");
      assert.equal(execution?.nativeImageVerified, false);
    }
  };
  try {
    const report = await runProduction(options);
    if (aiDiagnosticOnly) {
      const provider = fixture.diagnostics();
      assert.equal(provider.uniqueRequestIdCount, provider.requestCount);
      process.stdout.write(
        "CODING_CAPABILITY_REVIEW_DIAGNOSTIC_COMPLETE " +
          JSON.stringify({ report, provider }) +
          "\n",
      );
      return report;
    }
    const expectedStageCounts = new Map([
      ["preparation_final", 1],
      ["title_final", 1],
      ["review_list", 2],
      ["review_read_base", 2],
      ["review_read_current", 2],
      ["review_read_patch", 2],
      ["review_report", 2],
      ["repair_read", 1],
      ["repair_edit", 1],
      ["repair_final", 1],
    ]);
    for (const [stage, expectedCount] of expectedStageCounts) {
      const actualCount = fixture.observations.filter((observed) => observed === stage).length;
      assert.equal(
        actualCount,
        expectedCount,
        "Provider loopback stage count mismatch for " + stage,
      );
    }
    assert.equal(fixture.observations.filter((stage) => stage === "ordinary_final").length, 0);
    process.stdout.write("CODING_CAPABILITY_REVIEW_PASS " + JSON.stringify(report) + "\n");
    return report;
  } finally {
    await fixture.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  runAcceptance().catch((error) => {
    process.stderr.write(
      "coding capability review failed: " + String(error?.message ?? error).slice(0, 2_000) + "\n",
    );
    process.exitCode = 1;
  });
}
