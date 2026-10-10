// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { join, relative } from "node:path";
import { TextDecoder } from "node:util";
import { describe, expect, it } from "vitest";
import { ReadyHandshake } from "../../apps/desktop/src/api/protocol/handshake";
import { JA_ERROR_CATALOG, mapRpcError } from "../../apps/desktop/src/api/protocol/errors";
import { parseMethodParams, parseMethodResult } from "../../apps/desktop/src/api/protocol/methods";
import {
  parseEvent,
  parseInitializedNotification,
  parseRequest,
  parseResponse,
} from "../../apps/desktop/src/api/protocol/protocol";

const GOLDEN = process.env.JA_GOLDEN_PATH ?? "";

type JsonObject = Record<string, unknown>;

interface ConsumptionState {
  readonly pending: Map<string, PendingRequest>;
  readonly handshake: ReadyHandshake;
  readonly observedMethods: Set<string>;
  readonly observedEvents: Set<string>;
  requestCount: number;
  responseCount: number;
  readyToken?: string;
  readyObserved: boolean;
  kernelIdentity: boolean;
}

interface PendingRequest {
  readonly method: string;
  readonly params: JsonObject;
}

/** 递归选取与 schema、Java 和 Rust 校验端相同的 JSON 输入，确保各端验证同一份语料。 */
function corpusFiles(invalid: boolean): string[] {
  const output: string[] = [];
  const visit = (directory: string): void => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(path);
      } else if (entry.isFile() && (path.endsWith(".json") || path.endsWith(".jsonl"))) {
        const parts = relative(GOLDEN, path).split(/[\\/]/);
        if (parts.includes("invalid") === invalid) output.push(path);
      }
    }
  };
  visit(GOLDEN);
  return output.sort((left, right) =>
    relative(GOLDEN, left).localeCompare(relative(GOLDEN, right)),
  );
}

/** 保留逐行解析失败，使语法负例进入封闭帧校验；正例遇到该标记仍会失败。 */
function documents(path: string): JsonObject[] {
  const source = readFileSync(path, "utf8");
  const records = path.endsWith(".jsonl") ? source.split(/\r?\n/) : [source];
  return records
    .filter((record) => record.trim().length > 0)
    .map((record) => {
      try {
        return hasDuplicateObjectKey(record)
          ? { __duplicateKey: true }
          : (JSON.parse(record) as JsonObject);
      } catch {
        return { __invalidJson: true };
      }
    });
}

/** 在 JSON.parse 合并同名属性前检测重复键，避免无效输入被解析过程掩盖。 */
function hasDuplicateObjectKey(source: string): boolean {
  let index = 0;
  const skipWhitespace = (): void => {
    while (/\s/u.test(source[index] ?? "")) index += 1;
  };
  const parseString = (): string => {
    const start = index;
    index += 1;
    while (index < source.length) {
      if (source[index] === "\\") {
        index += 2;
      } else if (source[index] === '"') {
        index += 1;
        return JSON.parse(source.slice(start, index)) as string;
      } else {
        index += 1;
      }
    }
    throw new Error("unterminated JSON string");
  };
  const parseValue = (): boolean => {
    skipWhitespace();
    if (source[index] === '"') {
      parseString();
      return false;
    }
    if (source[index] === "{") {
      index += 1;
      const keys = new Set<string>();
      skipWhitespace();
      if (source[index] === "}") {
        index += 1;
        return false;
      }
      while (index < source.length) {
        skipWhitespace();
        if (source[index] !== '"') throw new Error("object key must be a string");
        const key = parseString();
        if (keys.has(key)) return true;
        keys.add(key);
        skipWhitespace();
        if (source[index] !== ":") throw new Error("object key separator is missing");
        index += 1;
        if (parseValue()) return true;
        skipWhitespace();
        if (source[index] === "}") {
          index += 1;
          return false;
        }
        if (source[index] !== ",") throw new Error("object item separator is missing");
        index += 1;
      }
      throw new Error("unterminated JSON object");
    }
    if (source[index] === "[") {
      index += 1;
      skipWhitespace();
      if (source[index] === "]") {
        index += 1;
        return false;
      }
      while (index < source.length) {
        if (parseValue()) return true;
        skipWhitespace();
        if (source[index] === "]") {
          index += 1;
          return false;
        }
        if (source[index] !== ",") throw new Error("array item separator is missing");
        index += 1;
      }
      throw new Error("unterminated JSON array");
    }
    while (index < source.length && !",]}".includes(source[index] ?? "")) index += 1;
    return false;
  };
  return parseValue();
}

/** 为每份样例建立独立的握手和待响应状态，避免不同文件互相提供授权。 */
function newState(): ConsumptionState {
  const handshake = new ReadyHandshake();
  handshake.start();
  return {
    pending: new Map<string, PendingRequest>(),
    handshake,
    observedMethods: new Set<string>(),
    observedEvents: new Set<string>(),
    requestCount: 0,
    responseCount: 0,
    readyObserved: false,
    kernelIdentity: false,
  };
}

/**
 * Node 校验端独立复核 Rust IPC 边界负责的摘要与严格 UTF-8 校验；浏览器同步 schema
 * 只检查 Base64 格式和解码长度，不能据此声称正文已通过验证。
 */
function validateChangeSetArtifactResult(result: unknown): void {
  const artifact = result as {
    readonly byteLength: number;
    readonly sha256: string;
    readonly contentBase64: string;
  };
  const bytes = Buffer.from(artifact.contentBase64, "base64");
  if (bytes.byteLength !== artifact.byteLength) throw new Error("artifact byte length mismatch");
  if (createHash("sha256").update(bytes).digest("hex") !== artifact.sha256)
    throw new Error("artifact digest mismatch");
  new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

/** 使用桌面端真实解析器及对应方法的 schema 校验客户端帧，避免测试另建一套协议逻辑。 */
function consumeFrame(frame: JsonObject, state: ConsumptionState): void {
  const method = typeof frame.method === "string" ? frame.method : undefined;
  const id = typeof frame.id === "string" ? frame.id : undefined;
  if (method !== undefined && id !== undefined) {
    const request = parseRequest(frame);
    const params = parseMethodParams(method as never, request.params) as JsonObject;
    state.observedMethods.add(method);
    state.requestCount += 1;
    state.pending.set(id, { method, params });
    return;
  }
  if (method === "runtime/initialized") {
    const initialized = parseInitializedNotification(frame);
    state.readyToken = initialized.params.readyToken;
    state.handshake.acceptInitialized(initialized.params.readyToken);
    return;
  }
  if (method !== undefined) {
    const event = parseEvent(frame, { expectedReadyToken: state.readyToken });
    state.observedEvents.add(event.method);
    if (event.method === "runtime/status-changed") {
      state.handshake.acceptRuntimeStatus(event.params.status, event.params.readyToken);
      if (event.params.status === "ready") state.readyObserved = true;
    }
    return;
  }
  if (id === undefined) throw new Error("response id is missing");
  const pending = state.pending.get(id);
  if (pending === undefined) throw new Error("response is not correlated");
  const response = parseResponse(frame, pending.method as never);
  if ("result" in response) {
    const result = parseMethodResult(pending.method as never, response.result);
    validateCorrelatedResult(pending.method, pending.params, result as JsonObject);
    if (pending.method === "turn/change-set/read") validateChangeSetArtifactResult(result);
    if (pending.method === "runtime/initialize") {
      const runtime = (result as { runtime?: { engine?: string; engineVersion?: string } }).runtime;
      state.kernelIdentity =
        runtime?.engine === "ja-kernel" &&
        typeof runtime.engineVersion === "string" &&
        runtime.engineVersion.length > 0;
    }
  } else {
    const mapped = mapRpcError(response.error);
    if (mapped.errorCode === "UNKNOWN_ERROR") throw new Error("unknown RPC error catalog");
  }
  state.pending.delete(id);
  state.responseCount += 1;
}

/** JSON Schema 无法把响应绑定到先前参数；此处复核 Rust/Java 在请求上下文中执行的身份与状态约束。 */
function validateCorrelatedResult(method: string, request: JsonObject, result: JsonObject): void {
  if (method.startsWith("process/")) {
    validateProcessResult(method, request, result);
    return;
  }
  if (method.startsWith("review/")) {
    validateReviewResult(method, request, result);
    return;
  }
  if (method === "thread/fork") {
    const source = result["source"] as JsonObject;
    const thread = result["thread"] as JsonObject;
    if (
      source["threadId"] !== request["sourceThreadId"] ||
      source["itemId"] !== request["sourceItemId"] ||
      source["threadRevision"] !== request["expectedThreadRevision"] ||
      thread["threadId"] === source["threadId"] ||
      thread["status"] !== "active" ||
      thread["latestTurnStatus"] !== null
    ) {
      throw new Error("thread fork result is not bound to a fresh source snapshot");
    }
  }
  if (method === "operation/read" && result["status"] === "committed") {
    const isFork = result["method"] === "thread/fork";
    if (request["sourceThreadId"] !== undefined) {
      const fork = result["result"] as JsonObject;
      const source = fork["source"] as JsonObject | undefined;
      if (
        !isFork ||
        result["threadId"] !== request["sourceThreadId"] ||
        source?.["threadId"] !== request["sourceThreadId"]
      ) {
        throw new Error("operation/read fork receipt crossed its source owner");
      }
    } else if (isFork) {
      throw new Error("operation/read fork receipt omitted its source owner query");
    }
  }
}

/** 旧代际进程只允许作为终止或中断事实回读，写入与停止必须命中当前代际。 */
function validateProcessResult(method: string, request: JsonObject, result: JsonObject): void {
  if (
    result["workspaceId"] !== request["workspaceId"] ||
    result["threadId"] !== request["threadId"]
  ) {
    throw new Error("process result crossed its workspace or thread owner");
  }
  if (method === "process/list") {
    const currentGeneration = result["generation"] as number;
    const sessions = result["sessions"] as JsonObject[];
    for (const session of sessions) {
      if (
        session["workspaceId"] !== request["workspaceId"] ||
        session["threadId"] !== request["threadId"] ||
        (session["generation"] as number) > currentGeneration ||
        (request["startedCallId"] !== undefined &&
          session["startedCallId"] !== request["startedCallId"]) ||
        ((session["generation"] as number) < currentGeneration &&
          (session["state"] === "running" || session["state"] === "stopping"))
      ) {
        throw new Error("process list contains a foreign or live old-generation session");
      }
    }
    return;
  }
  if (
    result["sessionId"] !== request["sessionId"] ||
    ((method === "process/write" || method === "process/stop") &&
      result["generation"] !== request["generation"]) ||
    (result["generation"] as number) > (request["generation"] as number) ||
    ((result["generation"] as number) < (request["generation"] as number) &&
      (result["state"] === "running" || result["state"] === "stopping")) ||
    (result["nextSequence"] as number) < (request["afterSequence"] as number) ||
    (result["nextSequence"] as number) > (result["outputSequence"] as number)
  ) {
    throw new Error("process result crossed its session, generation, or output cursor");
  }
  if (
    method === "process/stop" &&
    (result["output"] !== "" || result["outputTruncated"] !== false)
  ) {
    throw new Error("process stop result contains unacknowledged output");
  }
}

/** 审查报告必须属于指定 Turn，且每条发现都能定位到冻结快照实际覆盖的文件。 */
function validateReviewResult(method: string, request: JsonObject, result: JsonObject): void {
  if (result["threadId"] !== request["threadId"]) {
    throw new Error("review result crossed its thread owner");
  }
  if (method === "review/start") {
    if ((result["threadRevision"] as number) < (request["expectedThreadRevision"] as number)) {
      throw new Error("review start result regressed its thread revision");
    }
    return;
  }
  if (result["turnId"] !== request["turnId"]) {
    throw new Error("review result crossed its turn identity");
  }
  const report = result["report"] as JsonObject | null;
  if (report === null) return;
  const coverage = report["coverage"] as JsonObject;
  const filesReviewed = coverage["filesReviewed"] as JsonObject[];
  const partialReasons = coverage["partialReasons"] as unknown[];
  if (
    coverage["complete"] !== (partialReasons.length === 0) ||
    filesReviewed.length > (coverage["files"] as number) ||
    coverage["reviewComplete"] !==
      (coverage["complete"] === true && filesReviewed.length === (coverage["files"] as number))
  ) {
    throw new Error("review coverage completeness is inconsistent");
  }
  const coveredPaths = new Set<string>();
  const coverageIdentities = new Set<string>();
  for (const file of filesReviewed) {
    const path = file["path"] as string;
    const identity = JSON.stringify([path, file["layer"]]);
    if (coverageIdentities.has(identity))
      throw new Error("review coverage repeats a file identity");
    coverageIdentities.add(identity);
    coveredPaths.add(identity);
  }
  const findingIds = new Set<string>();
  for (const finding of report["findings"] as JsonObject[]) {
    const findingId = finding["findingId"] as string;
    if (
      findingIds.has(findingId) ||
      !coveredPaths.has(JSON.stringify([finding["path"], finding["layer"]])) ||
      (finding["endLine"] as number) < (finding["startLine"] as number)
    ) {
      throw new Error("review finding is duplicate or outside covered evidence");
    }
    findingIds.add(findingId);
  }
}

/** 正例不得包含通用的不支持标记；检查规则也不保留已删除引擎或协议结构的名称。 */
function containsUnsupportedVocabulary(source: string): boolean {
  const lowered = source.toLowerCase();
  return [
    "unsupported_engine",
    "unsupported_model_api",
    "unsupported_event_method",
    "unsupported_compatibility_field",
  ].some((marker) => lowered.includes(marker));
}

describe("JA-RPC shared golden corpus", () => {
  /** 按与 Python 门禁相同的规则统计物理记录，避免手工维护固定数量。 */
  function recordCount(invalid: boolean): number {
    return corpusFiles(invalid).reduce((total, file) => {
      const records = readFileSync(file, "utf8")
        .split(/\r?\n/)
        .filter((record) => record.trim().length > 0);
      return total + records.length;
    }, 0);
  }

  /** TypeScript 错误映射、JSON Schema 和冻结目录必须逐项使用相同的稳定错误 tuple。 */
  it("keeps the TypeScript and JSON Schema error catalogs aligned", () => {
    const contractDirectory = join(GOLDEN, "..", "ja-rpc", "v2");
    const catalog = JSON.parse(
      readFileSync(join(contractDirectory, "error-catalog.json"), "utf8"),
    ) as {
      errors: Array<{
        code: number;
        errorCode: string;
        category: string;
        retryable: boolean;
      }>;
    };
    const typescriptCatalog = Object.fromEntries(
      catalog.errors.map(({ errorCode, ...entry }) => [errorCode, entry]),
    );
    expect(typescriptCatalog).toEqual(JA_ERROR_CATALOG);

    const schema = JSON.parse(
      readFileSync(join(contractDirectory, "schema", "ja-rpc-v2.schema.json"), "utf8"),
    ) as {
      $defs: {
        rpcErrorData: { properties: { errorCode: { enum: string[] } } };
      };
    };
    expect(schema.$defs.rpcErrorData.properties.errorCode.enum).toEqual(
      catalog.errors.map(({ errorCode }) => errorCode),
    );

    for (const [index, entry] of catalog.errors.entries()) {
      const mapped = mapRpcError({
        code: entry.code,
        message: "catalog failure",
        data: {
          errorCode: entry.errorCode,
          category: entry.category,
          retryable: entry.retryable,
          errorId: `err_${index.toString(16).padStart(32, "0")}`,
          ...(entry.retryable ? { retryAfterMs: 250 } : {}),
        },
      });
      expect(mapped).toMatchObject({
        code: entry.code,
        errorCode: entry.errorCode,
        category: entry.category,
        retryable: entry.retryable,
      });
    }
  });

  /** 验证每个正例帧都进入生产解析器，并严格检查身份和请求响应关联。 */
  it("consumes every positive frame", () => {
    expect(GOLDEN.length).toBeGreaterThan(0);
    let frames = 0;
    let readyObserved = false;
    let kernelIdentity = false;
    const observedMethods = new Set<string>();
    const observedEvents = new Set<string>();
    for (const file of corpusFiles(false)) {
      expect(containsUnsupportedVocabulary(readFileSync(file, "utf8"))).toBe(false);
      const state = newState();
      for (const frame of documents(file)) {
        consumeFrame(frame, state);
        frames += 1;
      }
      /** 标准样例可以只是部分通信记录，文件末尾的请求可能尚未收到响应；
       * 但每个响应仍须关联之前的请求，并符合对应方法的结果 schema。 */
      expect(state.pending.size).toBe(state.requestCount - state.responseCount);
      readyObserved ||= state.readyObserved;
      kernelIdentity ||= state.kernelIdentity;
      state.observedMethods.forEach((method) => observedMethods.add(method));
      state.observedEvents.forEach((event) => observedEvents.add(event));
    }
    expect(frames).toBe(recordCount(false));
    expect(readyObserved).toBe(true);
    expect(kernelIdentity).toBe(true);
    for (const method of [
      "thread/seen",
      "turn/input/enqueue",
      "turn/input/prioritize",
      "turn/input/update",
      "turn/input/delete",
      "turn/change-set/read",
      "task/create",
      "task/list",
      "task/read",
      "task/observe",
      "task/unobserve",
      "task/seen",
      "thread/message/send",
      "task/followup",
      "task/cancel",
      "task/tree/delete",
      "task/close",
      "plan/read",
      "plan/revisions/list",
      "plan/current/read",
      "plan/create",
      "plan/draft/save",
      "plan/draft/discard",
      "plan/propose",
      "plan/execute",
      "plan/observe",
      "plan/unobserve",
      "plan/events/read",
      "plan/evidence/list",
      "plan/pause",
      "plan/resume",
      "plan/stop",
      "plan/reject",
      "interaction/read",
      "interaction/observe",
      "interaction/unobserve",
      "interaction/draft/save",
      "interaction/respond",
      "interaction/cancel",
    ]) {
      expect(observedMethods.has(method), method).toBe(true);
    }
    for (const event of [
      "turn/input-queue-changed",
      "turn/input-consumed",
      "turn/messages_received",
      "task/activity",
      "task/progress",
      "task/mailbox-changed",
      "task/closed",
      "interaction/changed",
      "plan/changed",
    ]) {
      expect(observedEvents.has(event), event).toBe(true);
    }
  });

  /**
   * 普通负例逐帧失败；correlated 负例先准入合法请求，再要求方法相关的非法响应失败，
   * 避免把建立 pending correlation 的前置请求误判成非法合同。
   */
  it("rejects every negative frame", () => {
    let frames = 0;
    for (const file of corpusFiles(true)) {
      const correlated = file.includes(join("invalid", "correlated"));
      const state = newState();
      let correlatedFailure = false;
      for (const frame of documents(file)) {
        const isRequest = typeof frame.method === "string" && typeof frame.id === "string";
        if (correlated && isRequest) {
          expect(() => consumeFrame(frame, state)).not.toThrow();
          frames += 1;
          continue;
        }
        expect(
          () => consumeFrame(frame, state),
          `${relative(GOLDEN, file)}:${String(frame.id ?? frame.method ?? "unknown")}`,
        ).toThrow();
        if (correlated) correlatedFailure = true;
        frames += 1;
      }
      if (correlated) expect(correlatedFailure, relative(GOLDEN, file)).toBe(true);
    }
    expect(frames).toBe(recordCount(true));
  });

  /** Thread item 分页以持久 item identity 和 Unicode code point offset 连续读取同一正文。 */
  it("keeps item content pages stable across Unicode code point offsets", () => {
    interface ItemContentParams {
      threadId: string;
      itemId: string;
      offsetCharacters: number;
      limitCharacters: number;
    }
    interface ItemContentResult {
      itemId: string;
      offsetCharacters: number;
      nextOffsetCharacters: number | null;
      totalCharacters: number;
      truncated: boolean;
      content: string;
    }
    const pending = new Map<string, ItemContentParams>();
    const pages: Array<{ params: ItemContentParams; result: ItemContentResult }> = [];
    for (const frame of documents(join(GOLDEN, "v2", "valid", "thread-item-content.jsonl"))) {
      const id = typeof frame.id === "string" ? frame.id : undefined;
      if (frame.method === "thread/item-content/read" && id !== undefined) {
        pending.set(id, parseMethodParams(frame.method, frame.params) as ItemContentParams);
        continue;
      }
      if (id === undefined || !("result" in frame)) {
        throw new Error("item content corpus contains an uncorrelated frame");
      }
      const params = pending.get(id);
      if (params === undefined) throw new Error("item content response lost its request");
      pending.delete(id);
      const result = parseMethodResult(
        "thread/item-content/read",
        frame.result,
      ) as ItemContentResult;
      const end = params.offsetCharacters + [...result.content].length;
      expect(result.itemId).toBe(params.itemId);
      expect(result.offsetCharacters).toBe(params.offsetCharacters);
      expect(result.truncated).toBe(result.nextOffsetCharacters !== null);
      expect(result.nextOffsetCharacters ?? result.totalCharacters).toBe(end);
      pages.push({ params, result });
    }

    pages.sort((left, right) => left.params.offsetCharacters - right.params.offsetCharacters);
    expect(pending.size).toBe(0);
    expect(pages.length).toBeGreaterThanOrEqual(2);
    expect(
      pages.some(({ result }) =>
        [...result.content].some((character) => (character.codePointAt(0) ?? 0) > 0xffff),
      ),
    ).toBe(true);
    const [first, ...remaining] = pages;
    for (const page of remaining) {
      expect(page.params.threadId).toBe(first.params.threadId);
      expect(page.params.itemId).toBe(first.params.itemId);
      expect(first.result.totalCharacters).toBe(page.result.totalCharacters);
      expect(first.result.nextOffsetCharacters).toBe(page.params.offsetCharacters);
    }
    expect(remaining.at(-1)?.result.nextOffsetCharacters).toBeNull();
    expect(remaining.at(-1)?.result.truncated).toBe(false);
  });
});
