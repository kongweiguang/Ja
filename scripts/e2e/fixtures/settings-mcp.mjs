// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createInterface } from "node:readline";

/** 最小 MCP fixture 不做磁盘或网络 IO，使设置验收只观察真实 STDIO 握手与目录发现。 */
function respond(line) {
  const request = JSON.parse(line);
  if (request.id === undefined) return;
  const result =
    request.method === "initialize"
      ? {
          protocolVersion: "2025-06-18",
          capabilities: { tools: {} },
          serverInfo: { name: "ja-settings-stdio", version: "1" },
        }
      : request.method === "tools/list"
        ? {
            tools: [
              {
                name: "settings_echo",
                description: "本地连接验收",
                inputSchema: { type: "object", properties: {} },
              },
            ],
          }
        : {};
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id: request.id, result })}\n`);
}

createInterface({ input: process.stdin }).on("line", respond);
