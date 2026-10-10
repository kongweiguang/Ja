// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { spawn } from "node:child_process";
import { Buffer } from "node:buffer";

const maxListenerLineBytes = 64 * 1024;
const maxOwnedListenerBytes = 256 * 1024;

/**
 * 原生表只投影 loopback LISTENING 与精确 owner PID，避免把无关连接或命令原文带入证据。
 * TCP 监听行的结构漂移须真实失败；非监听行及其他进程的合法行不占匹配结果预算。
 */
function parseNativeListenerLine(line, allowedOwners) {
  const fields = line.trim().split(/\s+/u);
  if (fields[0] !== "TCP" || !fields.includes("LISTENING")) return undefined;
  if (fields.length !== 5 || fields[3] !== "LISTENING") {
    throw new Error("Native TCP listener row has an invalid shape");
  }
  const ownerPid = Number(fields[4]);
  if (!allowedOwners.has(ownerPid)) return undefined;
  const endpoint = /^(127\.0\.0\.1|\[::1\]):(\d+)$/u.exec(fields[1]);
  if (endpoint === null) return undefined;
  const port = Number(endpoint[2]);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
    throw new Error("Native TCP listener row has an invalid port");
  }
  return {
    LocalAddress: endpoint[1] === "[::1]" ? "::1" : endpoint[1],
    LocalPort: port,
    OwningProcess: ownerPid,
  };
}

/**
 * 流式消费真实 child，单行与匹配 JSON 分别有界；全机连接数量不决定已归属 listener 的预算。
 * 出错时只结束传入的 child，等待 close 后才交还调用方，确保超时、取消和解析失败不会留进程。
 */
export function collectOwnedTcpListenerProcess(child, allowedOwners) {
  return new Promise((resolve, reject) => {
    const rows = [];
    let carry = "";
    let retainedBytes = 2;
    let failure;
    const fail = (error) => {
      if (failure !== undefined) return;
      failure = error;
      child.kill();
    };
    const consumeLine = (line) => {
      if (line.length > maxListenerLineBytes) {
        throw new Error("Native TCP listener line exceeds the byte bound");
      }
      const row = parseNativeListenerLine(line, allowedOwners);
      if (row === undefined) return;
      retainedBytes += Buffer.byteLength(JSON.stringify(row)) + (rows.length === 0 ? 0 : 1);
      if (retainedBytes > maxOwnedListenerBytes) {
        throw new Error("Owned TCP listener output exceeds the byte bound");
      }
      rows.push(row);
    };
    child.stdout.on("data", (chunk) => {
      if (failure !== undefined) return;
      try {
        // 协议、地址、状态和 PID 均为 ASCII；忽略本地化表头，不缓存整机 TCP 原文。
        carry += chunk.toString("ascii");
        const lines = carry.split(/\r?\n/u);
        carry = lines.pop() ?? "";
        if (carry.length > maxListenerLineBytes) {
          throw new Error("Native TCP listener line exceeds the byte bound");
        }
        for (const line of lines) consumeLine(line);
      } catch (error) {
        fail(error);
      }
    });
    child.stdout.on("error", fail);
    child.on("error", (error) => {
      failure ??= error;
    });
    child.on("close", (code, signal) => {
      if (failure !== undefined) {
        reject(failure);
        return;
      }
      if (code !== 0 || signal !== null) {
        const error = new Error("Native TCP listener query did not complete");
        Object.assign(error, { code, signal, killed: child.killed });
        reject(error);
        return;
      }
      try {
        if (carry !== "") consumeLine(carry);
        resolve(rows);
      } catch (error) {
        reject(error);
      }
    });
  });
}

/**
 * netstat 直接读取已 bind 的监听表，避免 CIM 全表查询耗尽原期限；预算与取消由 owner 调用方传入。
 * stderr 不采原文且非零退出必失败，实际返回值仍需调用方既有的地址、端口与 owner parser 复验。
 */
export async function queryOwnedTcpListeners(ownerPids, timeoutMs, signal) {
  const child = spawn("netstat.exe", ["-ano", "-p", "tcp"], {
    windowsHide: true,
    stdio: ["ignore", "pipe", "ignore"],
    timeout: timeoutMs,
    signal,
  });
  return collectOwnedTcpListenerProcess(child, new Set(ownerPids));
}
