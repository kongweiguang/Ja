// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";

/** 保留供应商原生工具调用身份，同时仅开放 128 字节安全 ASCII 标识符。 */
export const ToolCallIdSchema = z
  .string()
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/)
  .max(128);
