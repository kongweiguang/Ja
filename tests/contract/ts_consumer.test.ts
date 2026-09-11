// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { join, relative } from "node:path";
import { TextDecoder } from "node:util";
import { describe, expect, it } from "vitest";
import { ReadyHandshake } from "../../apps/desktop/src/api/protocol/handshake";
import { mapRpcError } from "../../apps/desktop/src/api/protocol/errors";
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
  readonly pending: Map<string, string>;
  readonly handshake: ReadyHandshake;
  readonly observedMethods: Set<string>;
  readonly observedEvents: Set<string>;
  requestCount: number;
  responseCount: number;
  readyToken?: string;
  readyObserved: boolean;
  kernelIdentity: boolean;
}

/** Recursively selects the same JSON inputs as the schema, Java, and Rust consumers. */
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

/** Reads each JSONL line independently so line framing remains visible to every consumer. */
function documents(path: string): JsonObject[] {
  const source = readFileSync(path, "utf8");
  const records = path.endsWith(".jsonl") ? source.split(/\r?\n/) : [source];
  return records
    .filter((record) => record.trim().length > 0)
    .map((record) =>
      hasDuplicateObjectKey(record) ? { __duplicateKey: true } : (JSON.parse(record) as JsonObject),
    );
}

/** Detects duplicate JSON object keys before JSON.parse collapses them into one property. */
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

/** Builds one isolated handshake/pending state so fixture files cannot authorize each other. */
function newState(): ConsumptionState {
  const handshake = new ReadyHandshake();
  handshake.start();
  return {
    pending: new Map<string, string>(),
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
 * Node consumer 独立复核 Rust IPC 边界负责的 digest 与严格 UTF-8，浏览器同步 schema
 * 只承担 Base64 形态和 decoded 长度，不能伪装成已验证正文。
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

/** Routes one client frame through the actual desktop parser and its method-aware schema. */
function consumeFrame(frame: JsonObject, state: ConsumptionState): void {
  const method = typeof frame.method === "string" ? frame.method : undefined;
  const id = typeof frame.id === "string" ? frame.id : undefined;
  if (method !== undefined && id !== undefined) {
    const request = parseRequest(frame);
    parseMethodParams(method as never, request.params);
    state.observedMethods.add(method);
    state.requestCount += 1;
    state.pending.set(id, method);
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
  const response = parseResponse(frame);
  if (id === undefined) throw new Error("response id is missing");
  const originatingMethod = state.pending.get(id);
  if (originatingMethod === undefined) throw new Error("response is not correlated");
  state.pending.delete(id);
  if ("result" in response) {
    const result = parseMethodResult(originatingMethod as never, response.result);
    if (originatingMethod === "turn/change-set/read") validateChangeSetArtifactResult(result);
    if (originatingMethod === "runtime/initialize") {
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
  state.responseCount += 1;
}

/** Keeps generic unsupported sentinels out of positive fixtures without
 * retaining names from deleted engines or protocol shapes. */
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
  /** Counts the same physical records as the Python gate without hand-maintained constants. */
  function recordCount(invalid: boolean): number {
    return corpusFiles(invalid).reduce((total, file) => {
      const records = readFileSync(file, "utf8")
        .split(/\r?\n/)
        .filter((record) => record.trim().length > 0);
      return total + records.length;
    }, 0);
  }

  /** Proves every positive frame reaches production parsers with exact identity and correlation. */
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
      /** Golden files are partial transcripts: an accepted request may end the file before its response,
       * but every response must still correlate to a prior request and use that method's result schema. */
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
      "goal/read",
      "goal/events/read",
      "goal/observe",
      "goal/unobserve",
      "plan/read",
      "plan/revisions/list",
      "goal/evidence/list",
      "goal/create",
      "goal/plan/attach",
      "goal/plan/detach",
      "goal/pause",
      "goal/resume",
      "goal/stop",
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
      "goal/changed",
      "goal/activity",
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
});
