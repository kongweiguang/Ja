// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import errorCatalog from "../../../../../contracts/ja-rpc/v2/error-catalog.json";
import {
  JA_ERROR_CODES,
  JaError,
  mapProtocolError,
  mapRpcError,
  mapTransportError,
  mapValidationError,
} from "@/api/protocol/errors";

describe("JA-RPC v2 error boundary", () => {
  /** 将生产 mapper 锁定到仓库内唯一的 code/errorCode/category/retryable 目录。 */
  it("matches every frozen typed error tuple", () => {
    expect(errorCatalog.schemaVersion).toBe(2);
    expect(errorCatalog.errors).toHaveLength(58);
    expect(
      Object.entries(JA_ERROR_CODES).map(([errorCode, code]) => ({ code, errorCode })),
    ).toEqual(errorCatalog.errors.map(({ code, errorCode }) => ({ code, errorCode })));
    for (const [index, entry] of errorCatalog.errors.entries()) {
      const errorId = `err_${index.toString(16).padStart(32, "0")}`;
      expect(
        mapRpcError({
          code: entry.code,
          message: "catalog error",
          data: {
            errorCode: entry.errorCode,
            category: entry.category,
            retryable: entry.retryable,
            errorId,
          },
        }),
      ).toMatchObject(entry);
      expect(
        mapRpcError({
          code: entry.code,
          message: "drift",
          data: {
            errorCode: entry.errorCode,
            category: entry.category,
            retryable: !entry.retryable,
            errorId,
          },
        }).errorCode,
      ).toBe("INTERNAL_ERROR");
    }
  });

  /** 保留目录批准的退避与 errorId，且不为无退避错误合成默认值。 */
  it("maps the strict catalog entry and preserves retry semantics", () => {
    const error = mapRpcError({
      code: -32053,
      message: "model unavailable",
      data: {
        errorCode: "MODEL_UNAVAILABLE",
        category: "unavailable",
        retryable: true,
        errorId: "err_00000000000000000000000000000001",
        retryAfterMs: 250,
      },
    });
    expect(error).toMatchObject({
      code: JA_ERROR_CODES.MODEL_UNAVAILABLE,
      errorCode: "MODEL_UNAVAILABLE",
      category: "unavailable",
      retryable: true,
      errorId: "err_00000000000000000000000000000001",
      retryAfterMs: 250,
    });
  });

  /** 未知字段、目录漂移以及无效退避都必须 fail closed。 */
  it("fails closed on catalog drift, unknown fields, and secret error fields", () => {
    const errorId = "err_00000000000000000000000000000002";
    expect(
      mapRpcError({
        code: -32008,
        message: "queue",
        data: {
          errorCode: "QUEUE_FULL",
          category: "capacity",
          retryable: true,
          errorId,
          secret: "REDACTED",
        },
      }).errorCode,
    ).toBe("INTERNAL_ERROR");
    expect(
      mapRpcError({
        code: -32008,
        message: "queue",
        data: { errorCode: "MODEL_UNAVAILABLE", category: "capacity", retryable: true, errorId },
      }).errorCode,
    ).toBe("INTERNAL_ERROR");
    expect(
      mapRpcError({
        code: -32080,
        message: "redacted",
        data: {
          errorCode: "INTERNAL_ERROR",
          category: "internal",
          retryable: false,
          errorId,
          retryAfterMs: 1,
        },
      }).errorCode,
    ).toBe("INTERNAL_ERROR");
  });

  /** 本地错误映射不得保留路径、token 或原始 cause。 */
  it("redacts transport, validation, and protocol diagnostics", () => {
    const token = "0123456789abcdef0123456789abcdef";
    const errors = [
      mapTransportError(new Error(`C:\\private\\${token}`)),
      mapValidationError(new Error(`provider ${token}`)),
      mapProtocolError("response", `method_${token}`),
      new JaError(`provider ${token}`, { details: { observed: token, ordinary: "safe" } }),
    ];
    for (const error of errors) {
      expect(JSON.stringify(error)).not.toContain(token);
      expect("cause" in error).toBe(false);
    }
    expect(mapTransportError(new Error("C:\\private\\token.txt")).errorCode).toBe(
      "TRANSPORT_ERROR",
    );
    expect(mapValidationError(new Error("bad params")).errorCode).toBe("VALIDATION_ERROR");
  });
});
