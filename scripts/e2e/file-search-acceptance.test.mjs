// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  assertFileSearchOutputs,
  fileSearchFixtureMarkers,
  parseFileSearchOutputs,
  startFileSearchFixture,
} from "./fixtures/file-search-acceptance.mjs";
import { parseArguments, validateFileSearchReport } from "./file-search-acceptance.mjs";

/** 向确定性 Provider 发送一条不含真实凭据的最小 Responses 请求。 */
async function postFixture(fixture, input, tools = [{ name: "find" }, { name: "ls" }]) {
  return fetch(`${fixture.baseUrl}/responses`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ input, tools }),
  });
}

/** 构造同一 Tool batch 的四个 function_call/function_call_output 对。 */
function continuation(outputs) {
  return fileSearchFixtureMarkers.calls.flatMap((call) => [
    {
      type: "function_call",
      call_id: call.callId,
      name: call.name,
      arguments: JSON.stringify(call.arguments),
    },
    { type: "function_call_output", call_id: call.callId, output: outputs[call.callId] },
  ]);
}

test("file search fixture requires one four-call batch and all returned results", async () => {
  const fixture = await startFileSearchFixture();
  try {
    const first = await postFixture(fixture, [
      { role: "user", content: [{ type: "input_text", text: fileSearchFixtureMarkers.user }] },
    ]);
    const firstBody = await first.text();
    assert.equal(first.status, 200);
    for (const call of fileSearchFixtureMarkers.calls) assert.match(firstBody, new RegExp(call.callId, "u"));

    const outputByCallId = Object.fromEntries([
      [fileSearchFixtureMarkers.calls[0].callId, "README.md\nAGENTS.md\n.codegraph\tdirectory"],
      [fileSearchFixtureMarkers.calls[1].callId, "README.md\nresult"],
      [fileSearchFixtureMarkers.calls[2].callId, "AGENTS.md\nresult"],
      [fileSearchFixtureMarkers.calls[3].callId, ".codegraph\nresult"],
    ]);
    const parsed = parseFileSearchOutputs({
      input: continuation(outputByCallId),
    });
    assert.deepEqual(assertFileSearchOutputs(parsed), {
      callCount: 4,
      outputCharacters: [...parsed.values()].reduce((total, value) => total + value.length, 0),
    });
    const second = await postFixture(fixture, continuation(outputByCallId));
    assert.equal(second.status, 200);
    assert.match(await second.text(), new RegExp(fileSearchFixtureMarkers.final, "u"));
    assert.deepEqual(fixture.snapshot().attempts.map((attempt) => attempt.kind), ["initial", "continuation"]);
    assert.equal(fixture.snapshot().attempts[1].outputCount, 4);
  } finally {
    await fixture.close();
  }
});

test("file search fixture answers automatic title requests without changing search phases", async () => {
  const fixture = await startFileSearchFixture();
  try {
    const title = await postFixture(fixture, [
      { role: "user", content: [{ type: "input_text", text: "<user_request>fixture</user_request>" }] },
      { role: "assistant", content: [{ type: "output_text", text: "<assistant_reply>fixture</assistant_reply>" }] },
    ]);
    assert.equal(title.status, 200);
    assert.match(await title.text(), /文件搜索验收/u);
    assert.deepEqual(fixture.snapshot().attempts.map((attempt) => attempt.kind), ["title"]);
  } finally {
    await fixture.close();
  }
});

test("file search runner rejects unbounded stress size and requires four successful durations", () => {
  assert.throws(() => parseArguments(["--stress-files", "999"]), /between 1000 and 20000/u);
  assert.equal(parseArguments(["--stress-files", "1000"]).stressFiles, 1000);
  const native = parseArguments(["--executable", "fixture-app-server.exe"]);
  assert.equal(native.executable, "fixture-app-server.exe");
  assert.equal(native.jar, undefined);
  assert.equal(parseArguments(["--strip-search-tools"]).stripSearchTools, true);
  assert.throws(
    () => parseArguments(["--jar", "fixture-app-server.jar", "--executable", "fixture-app-server.exe"]),
    /mutually exclusive/u,
  );
  assert.throws(() =>
    validateFileSearchReport({
      schemaVersion: 1,
      status: "passed",
      provider: { kind: "deterministic_loopback", externalCalls: 0 },
      tools: { resultCount: 3, allResultsReturned: false, calls: [] },
      persistence: { restartRecovered: false },
      durations: { wallDurationMs: 0 },
    }),
  );

  const validReport = {
    schemaVersion: 1,
    status: "passed",
    runtime: { launcher: "native", javaMajor: null },
    environment: {
      searchToolPathStripped: true,
      removedSearchToolPathEntries: 1,
      searchToolsAbsentFromChildPath: true,
    },
    provider: { kind: "deterministic_loopback", externalCalls: 0 },
    tools: {
      resultCount: 4,
      allResultsReturned: true,
      calls: Array.from({ length: 4 }, () => ({ outcome: "succeeded", status: "success", durationMs: 1 })),
    },
    persistence: { restartRecovered: true },
    durations: { wallDurationMs: 1 },
  };
  assert.equal(validateFileSearchReport(validReport), validReport);
  assert.throws(() =>
    validateFileSearchReport({
      ...validReport,
      environment: { ...validReport.environment, searchToolsAbsentFromChildPath: false },
    }),
  );
  assert.throws(() =>
    validateFileSearchReport({
      ...validReport,
      environment: {
        searchToolPathStripped: false,
        removedSearchToolPathEntries: 0,
        searchToolsAbsentFromChildPath: true,
      },
    }),
  );
});
