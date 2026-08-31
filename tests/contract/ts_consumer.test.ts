// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";
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
    requestCount: 0,
    responseCount: 0,
    readyObserved: false,
    kernelIdentity: false,
  };
}

/** Routes one client frame through the actual desktop parser and its method-aware schema. */
function consumeFrame(frame: JsonObject, state: ConsumptionState): void {
  const method = typeof frame.method === "string" ? frame.method : undefined;
  const id = typeof frame.id === "string" ? frame.id : undefined;
  if (method !== undefined && id !== undefined) {
    const request = parseRequest(frame);
    parseMethodParams(method as never, request.params);
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
    }
    expect(frames).toBe(recordCount(false));
    expect(readyObserved).toBe(true);
    expect(kernelIdentity).toBe(true);
  });

  /** Requires all schema-negative frames to fail the same production consumer boundary. */
  it("rejects every negative frame", () => {
    let frames = 0;
    for (const file of corpusFiles(true)) {
      for (const frame of documents(file)) {
        const state = newState();
        expect(
          () => consumeFrame(frame, state),
          `${relative(GOLDEN, file)}:${String(frame.id ?? frame.method ?? "unknown")}`,
        ).toThrow();
        frames += 1;
      }
    }
    expect(frames).toBe(recordCount(true));
  });
});
