// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import test from "node:test";
import { collectOwnedTcpListenerProcess, queryOwnedTcpListeners } from "./owned-tcp-listeners.mjs";
import { createServer } from "node:net";

/** 真实 Node child 使用与原生查询相同的 pipe/close 语义，避免测试替换收集器或进程状态。 */
function fixtureChild(script, options = {}) {
  return spawn(process.execPath, ["-e", script], {
    windowsHide: true,
    stdio: ["ignore", "pipe", "ignore"],
    ...options,
  });
}

/** 收集器只接受该 child 的 PID；失败返回前应已经消费 close，不能遗留诊断进程。 */
function collectFixture(child) {
  return collectOwnedTcpListenerProcess(child, new Set([child.pid]));
}

/** 分块与超原缓冲的无关表行必须保留相同 owner 的双栈 listener，不能靠全表大小失败。 */
test("collects chunked IPv4 and IPv6 listeners while streaming many unrelated connections", async () => {
  const child = fixtureChild(`
    const unowned = process.pid + 1;
    process.stdout.write('  TCP 127.0.0.');
    setTimeout(() => {
      process.stdout.write('1:49152 0.0.0.0:0 LISTENING ' + process.pid + '\\r\\n');
      for (let index=0;index<12000;index++) {
        process.stdout.write('  TCP 127.0.0.1:49153 127.0.0.1:49154 ESTABLISHED '+process.pid+'\\n');
        process.stdout.write('  TCP 127.0.0.1:49155 0.0.0.0:0 LISTENING '+unowned+'\\n');
      }
      process.stdout.write('  TCP [::1]:9222 [::]:0 LISTENING ' + process.pid);
    }, 30);
  `);
  assert.deepEqual(await collectFixture(child), [
    { LocalAddress: "127.0.0.1", LocalPort: 49152, OwningProcess: child.pid },
    { LocalAddress: "::1", LocalPort: 9222, OwningProcess: child.pid },
  ]);
  assert.equal(child.exitCode, 0);
});

/** 已归属数据的协议错误须停止该读取进程，不能当作暂时无 listener 被隐藏。 */
test("rejects malformed owned listener data and waits for its child to close", async () => {
  const child = fixtureChild(
    `process.stdout.write('TCP 127.0.0.1:65536 0.0.0.0:0 LISTENING '+process.pid+'\\n');setInterval(()=>{},1000);`,
  );
  await assert.rejects(collectFixture(child), /invalid port/u);
  assert.equal(child.killed, true);
  assert.ok(child.exitCode !== null || child.signalCode !== null);
});

/** 完整长行与未结束 carry 共享边界，避免换行恰落在同一 chunk 时绕过单行限制。 */
test("rejects an oversized completed line and reclaims only its child", async () => {
  const child = fixtureChild(
    `process.stdout.write('X'.repeat(65537)+'\\n');setInterval(()=>{},1000);`,
  );
  await assert.rejects(collectFixture(child), /line exceeds/u);
  assert.equal(child.killed, true);
  assert.ok(child.exitCode !== null || child.signalCode !== null);
});

/** 匹配结果仍使用原有 256 KiB 边界，大量归属行不能借流式查询无限增长。 */
test("rejects oversized matched JSON while unrelated output stays outside that budget", async () => {
  const child = fixtureChild(
    `for(let index=0;index<6000;index++)process.stdout.write('TCP 127.0.0.1:49152 0.0.0.0:0 LISTENING '+process.pid+'\\n');setInterval(()=>{},1000);`,
  );
  await assert.rejects(collectFixture(child), /output exceeds/u);
  assert.equal(child.killed, true);
  assert.ok(child.exitCode !== null || child.signalCode !== null);
});

/** 不采 stderr 原文不会改变退出失败的事实，也不能把诊断自由文本抛给调用方。 */
test("treats a nonzero exit as failure without exposing stderr content", async () => {
  const child = fixtureChild(
    "process.stderr.write('private fixture diagnostic');process.exitCode=7;",
  );
  await assert.rejects(
    collectFixture(child),
    (error) => error.code === 7 && !String(error).includes("private"),
  );
  assert.equal(child.exitCode, 7);
});

/** 短测试计时器检验真实 child 超时及 close 次序，正式 native 查询预算保持 15 秒。 */
test("real process timeout rejects after closing the spawned child", async () => {
  const child = fixtureChild("setInterval(()=>{},1000);", { timeout: 50 });
  await assert.rejects(collectFixture(child), /did not complete/u);
  assert.equal(child.killed, true);
  assert.ok(child.exitCode !== null || child.signalCode !== null);
});

/** 取消必须保留 AbortError 并等到进程回收，不能将其变成成功的空表。 */
test("real abort propagates and closes the spawned child", async () => {
  const controller = new AbortController();
  const child = fixtureChild("setInterval(()=>{},1000);", { signal: controller.signal });
  const collection = collectFixture(child);
  controller.abort();
  await assert.rejects(collection, (error) => error.name === "AbortError");
  assert.equal(child.killed, true);
  assert.ok(child.exitCode !== null || child.signalCode !== null);
});

/** 真实 Windows 网络表验证 PID/端口语义，与 Node fixture 的生命周期验证边界分开。 */
test(
  "native netstat observes this process's actual loopback listener with the original 15 second budget",
  {
    skip: process.platform !== "win32",
  },
  async () => {
    const server = createServer();
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", resolve);
    });
    try {
      const rows = await queryOwnedTcpListeners([process.pid], 15_000, AbortSignal.timeout(15_000));
      assert.ok(
        rows.some(
          (row) =>
            row.LocalAddress === "127.0.0.1" &&
            row.LocalPort === server.address().port &&
            row.OwningProcess === process.pid,
        ),
      );
      assert.deepEqual(
        await queryOwnedTcpListeners([2147483647], 15_000, AbortSignal.timeout(15_000)),
        [],
      );
    } finally {
      await new Promise((resolve) => server.close(resolve));
    }
  },
);
