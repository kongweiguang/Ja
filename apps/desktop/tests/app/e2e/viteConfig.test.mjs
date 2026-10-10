// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { resolve } from "node:path";
import process from "node:process";
import test, { afterEach } from "node:test";

const originalEnvironment = new Map(
  ["JA_E2E_DEV_PORT", "JA_E2E_RUNTIME_ROOT"].map((key) => [key, process.env[key]]),
);
let configurationSequence = 0;

/** Node 24 直接加载真实配置；不同 import 身份模拟 launcher 各自冻结的环境。 */
async function readConfig() {
  const url = new URL("../../../vite.config.ts", import.meta.url);
  url.searchParams.set("testIdentity", String(++configurationSequence));
  const { default: config } = await import(url.href);
  assert.equal(typeof config, "function");
  return config({ command: "serve", mode: "test" });
}

/** 归还宿主环境，配置测试不触碰 DOM 或 React 的共享测试环境。 */
afterEach(() => {
  for (const [key, value] of originalEnvironment) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
});

/** 普通开发和构建仍使用 Vite 原生缓存规则。 */
test("normal development keeps the default Vite cache", async () => {
  delete process.env.JA_E2E_DEV_PORT;
  process.env.JA_E2E_RUNTIME_ROOT = resolve("fixture", "runtime");
  assert.equal((await readConfig()).cacheDir, undefined);
});

/** 预热与重启使用相同目录，优化结果不会进入数据 owner 目录。 */
test("phases of one isolated launcher share a cache outside the runtime directory", async () => {
  const root = resolve("fixture", "launch-a");
  process.env.JA_E2E_DEV_PORT = "14500";
  process.env.JA_E2E_RUNTIME_ROOT = resolve(root, "runtime");
  assert.equal((await readConfig()).cacheDir, resolve(root, "vite-cache"));
  assert.equal((await readConfig()).cacheDir, resolve(root, "vite-cache"));
});

/** 端口复用不能把两套临时 profile 的依赖优化结果混到一起。 */
test("isolated launchers on the same port have different caches", async () => {
  process.env.JA_E2E_DEV_PORT = "14500";
  process.env.JA_E2E_RUNTIME_ROOT = resolve("fixture", "launch-a", "runtime");
  const first = await readConfig();
  process.env.JA_E2E_RUNTIME_ROOT = resolve("fixture", "launch-b", "runtime");
  const second = await readConfig();
  assert.notEqual(second.cacheDir, first.cacheDir);
});
