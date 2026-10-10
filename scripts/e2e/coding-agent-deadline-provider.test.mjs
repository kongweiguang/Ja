// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  codingAgentDeadlineMarkers,
  startCodingAgentDeadlineProvider,
} from "./coding-agent-deadline-provider.mjs";

/** 只提交到达真实进程编辑屏障所需的有界 Provider 续传。 */
function postReadContinuation(fixture) {
  const outputs = [
    ["call_deadline_write", "write result"],
    ["call_deadline_read", "ORIGINAL_MARKER"],
    ["call_deadline_grep", "ORIGINAL_MARKER"],
    ["call_deadline_find", "src/deadline-proof.txt"],
    ["call_deadline_ls", "deadline-proof.txt"],
  ];
  return fetch(`${fixture.baseUrl}/responses`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      input: [
        {
          role: "user",
          content: [{ type: "input_text", text: codingAgentDeadlineMarkers.deadline }],
        },
        ...outputs.map(([call_id, output]) => ({ type: "function_call_output", call_id, output })),
      ],
    }),
  });
}

/** 读取一条 loopback 响应，期限短于界面屏障的保护超时。 */
async function readProviderResponse(fixture, input) {
  const response = await fetch(`${fixture.baseUrl}/responses`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ input }),
    signal: AbortSignal.timeout(5_000),
  });
  assert.equal(response.status, 200);
  return response.text();
}

test(
  "edit continuation remains behind a deterministic UI observation barrier",
  { timeout: 35_000 },
  async () => {
    const fixture = await startCodingAgentDeadlineProvider({ holdEditResponse: true });
    try {
      const responsePromise = postReadContinuation(fixture);
      await fixture.waitForEditResponseBarrier();
      assert.equal(
        fixture.observations.at(-1)?.stage,
        "edit",
        "barrier must follow committed read/search outputs",
      );
      assert.ok(
        fixture.observations
          .at(-1)
          ?.checks?.every((item) => item.present && item.containsExpectedText),
      );

      const respondedBeforeRelease = await Promise.race([
        responsePromise.then(() => true),
        new Promise((resolve) => setTimeout(() => resolve(false), 25)),
      ]);
      assert.equal(respondedBeforeRelease, false);

      fixture.releaseEditResponseBarrier();
      const response = await responsePromise;
      assert.equal(response.status, 200);
      assert.match(await response.text(), /call_deadline_edit/u);
      assert.equal(fixture.failure(), null);
    } finally {
      fixture.releaseEditResponseBarrier();
      await fixture.close();
    }
  },
);

test(
  "default fixture completes every stage of the seven-tool Turn without waiting for UI release",
  { timeout: 15_000 },
  async () => {
    const fixture = await startCodingAgentDeadlineProvider();
    try {
      const user = {
        role: "user",
        content: [{ type: "input_text", text: codingAgentDeadlineMarkers.deadline }],
      };
      const write = {
        type: "function_call_output",
        call_id: "call_deadline_write",
        output: "write completed",
      };
      const readSearch = [
        { type: "function_call_output", call_id: "call_deadline_read", output: "ORIGINAL_MARKER" },
        { type: "function_call_output", call_id: "call_deadline_grep", output: "ORIGINAL_MARKER" },
        {
          type: "function_call_output",
          call_id: "call_deadline_find",
          output: "src/deadline-proof.txt",
        },
        {
          type: "function_call_output",
          call_id: "call_deadline_ls",
          output: "deadline-proof.txt",
        },
      ];
      const written = await readProviderResponse(fixture, [user]);
      assert.match(written, /call_deadline_write/u);

      const searched = await readProviderResponse(fixture, [user, write]);
      for (const callId of [
        "call_deadline_read",
        "call_deadline_grep",
        "call_deadline_find",
        "call_deadline_ls",
      ]) {
        assert.match(searched, new RegExp(callId, "u"));
      }

      const edited = await readProviderResponse(fixture, [user, write, ...readSearch]);
      assert.match(edited, /call_deadline_edit/u);

      const editOutput = {
        type: "function_call_output",
        call_id: "call_deadline_edit",
        output: "edited",
      };
      const checked = await readProviderResponse(fixture, [user, write, ...readSearch, editOutput]);
      assert.match(checked, /call_deadline_shell/u);

      const shellOutput = {
        type: "function_call_output",
        call_id: "call_deadline_shell",
        output: "EDITED_MARKER",
      };
      const final = await readProviderResponse(fixture, [
        user,
        write,
        ...readSearch,
        editOutput,
        shellOutput,
      ]);
      assert.match(final, /CODING_AGENT_DEADLINE_FINAL_OK/u);
      assert.deepEqual(
        fixture.observations.map((item) => item.stage),
        ["write", "read_search_batch", "edit", "exec_command", "final"],
      );
      assert.equal(fixture.failure(), null);
      assert.equal(
        await fixture.waitForEditResponseBarrier().then(
          () => true,
          () => false,
        ),
        false,
      );
    } finally {
      await fixture.close();
    }
  },
);
