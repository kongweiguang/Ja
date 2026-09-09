# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Provider-free contract tests for the Native Image smoke client."""

from __future__ import annotations

import http.client
import importlib.util
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path
from queue import Queue
from types import SimpleNamespace
import unittest

import jsonschema


SCRIPT = Path(__file__).with_name("run-sidecar-smoke.py")
FIXTURE = Path(__file__).parents[1] / "e2e" / "fixtures" / "v1-smoke-mock-sidecar.mjs"
SCHEMA = Path(__file__).parents[2] / "contracts" / "ja-rpc" / "v1" / "schema" / "ja-rpc-v1.schema.json"
GOLDEN = Path(__file__).parents[2] / "contracts" / "golden" / "v1" / "valid" / "core.jsonl"
SPEC = importlib.util.spec_from_file_location("run_sidecar_smoke", SCRIPT)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import machinery failure is environmental
    raise RuntimeError("native smoke module is unavailable")
SMOKE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SMOKE
SPEC.loader.exec_module(SMOKE)


class NativeSmokeV1Test(unittest.TestCase):
    """Verifies exact v1 frames and the complete mock-sidecar lifecycle."""

    def test_secret_scan_distinguishes_public_protocol_names_from_values(self) -> None:
        """公开 credential 方法名必须可观测，但带值的 API key、Bearer 与 smoke secret 仍须被拦截。"""

        self.assertIsNone(SMOKE.LEAK_PATTERN.search('"method":"credential/set"'))
        self.assertIsNotNone(SMOKE.LEAK_PATTERN.search('"apiKey":"abcdefgh12345678"'))
        self.assertIsNotNone(SMOKE.LEAK_PATTERN.search("Bearer " + "abcdefgh12345678"))
        self.assertIn(SMOKE.SMOKE_SECRET, f"payload={SMOKE.SMOKE_SECRET}")

    def test_initialize_is_exact_v1_and_configuration_free(self) -> None:
        """将 Native offer 绑定到 golden v1 闭集，防止发布脚本滞后于三端合同。"""

        params = SMOKE.initialize_frame()["params"]
        golden_params = json.loads(GOLDEN.read_text(encoding="utf-8").splitlines()[0])["params"]
        methods = params["capabilities"]["methods"]
        self.assertEqual(1, params["protocolMajor"])
        self.assertEqual(0, params["protocolMinor"])
        self.assertEqual(
            {"protocolMajor", "protocolMinor", "clientVersion", "capabilities", "limits"},
            set(params),
        )
        self.assertNotIn("configSnapshot", params)
        self.assertNotIn("apiKey", str(params))
        self.assertNotIn("runtime/configure", methods)
        self.assertIn("thread/compact", methods)
        self.assertIn("thread/pin", methods)
        self.assertIn("thread/seen", methods)
        self.assertIn("thread/restore", methods)
        self.assertIn("thread/preferences/update", methods)
        self.assertIn("workspace/path/search", methods)
        self.assertIn("task/create", methods)
        self.assertIn("task/tree/delete", methods)
        self.assertIn("goal/read", methods)
        self.assertIn("goal/create", methods)
        self.assertIn("goal/plan/attach", methods)
        self.assertIn("goal/plan/detach", methods)
        self.assertIn("plan/create", methods)
        self.assertIn("plan/draft/save", methods)
        self.assertIn("plan/approve", methods)
        self.assertIn("plan/execute", methods)
        self.assertIn("attachment/import", methods)
        self.assertIn("attachment/preview/open", methods)
        self.assertIn("turn/resume", methods)
        self.assertIn("turn/input/enqueue", methods)
        self.assertIn("turn/input/prioritize", methods)
        self.assertIn("model/test", methods)
        self.assertIn("turn/change-set/read", methods)
        self.assertNotIn("turn/change-preview/open", methods)
        self.assertNotIn("turn/change-set/commit", methods)
        self.assertEqual(78, len(methods))
        self.assertEqual(len(methods), len(set(methods)))
        self.assertIn("context/compaction-started", params["capabilities"]["events"])
        self.assertIn("context/compaction-failed", params["capabilities"]["events"])
        self.assertIn("thread/metadata-changed", params["capabilities"]["events"])
        self.assertIn("task/activity", params["capabilities"]["events"])
        self.assertIn("goal/changed", params["capabilities"]["events"])
        self.assertIn("goal/activity", params["capabilities"]["events"])
        self.assertIn("goal/input-requested", params["capabilities"]["events"])
        self.assertNotIn("turn/change-preview-updated", params["capabilities"]["events"])
        self.assertEqual(["default", "plan"], params["capabilities"]["collaborationModes"])
        self.assertEqual(
            ["task_threads_v1", "plan_goal_v1"],
            params["capabilities"]["features"],
        )
        self.assertEqual(["approval_required", "full_access"], params["capabilities"]["accessModes"])
        self.assertEqual(golden_params["capabilities"], params["capabilities"])
        self.assertEqual(golden_params["limits"], params["limits"])

    def test_initialize_identity_requires_current_product_version(self) -> None:
        """真实 Native 门从根版本源校验 Kernel 身份，避免过期 sidecar 通过启动级探针。"""

        result = {"runtime": {"engine": "ja-kernel", "engineVersion": "0.1.0"}}
        self.assertIs(result, SMOKE.require_initialize_identity(result, SMOKE.EXPECTED_ENGINE_VERSION))
        with self.assertRaisesRegex(RuntimeError, "expected Kernel engine version"):
            SMOKE.require_initialize_identity(
                {"runtime": {"engine": "ja-kernel", "engineVersion": "2.0.0"}},
                SMOKE.EXPECTED_ENGINE_VERSION,
            )

    def test_workspace_and_thread_use_cwd_without_client_identity(self) -> None:
        """Requires Java to assign workspaceId instead of accepting a client-computed identity."""

        workspace = Path(tempfile.gettempdir()) / "ja-合同-workspace"
        opened = SMOKE.workspace_open_frame(workspace)["params"]
        trusted = SMOKE.workspace_trust_frame("ws_demo")["params"]
        created = SMOKE.thread_create_frame(workspace)["params"]
        self.assertEqual({"cwd", "displayName"}, set(opened))
        self.assertEqual({"workspaceId": "ws_demo", "trust": "trusted"}, trusted)
        self.assertEqual(
            {
                "cwd", "title", "providerId", "modelId", "reasoningLevel", "accessMode",
                "collaborationMode",
            },
            set(created),
        )
        self.assertEqual("default", created["collaborationMode"])
        self.assertNotIn("reasoningEffort", created)
        self.assertNotIn("workspaceId", created)
        self.assertNotIn("configRevision", created)
        self.assertNotIn("profileId", created)

    def test_lifecycle_probes_can_create_an_isolated_thread(self) -> None:
        """EOF 探针必须使用独立请求与 Thread，避免继承取消探针的 Tool 结果。"""

        workspace = Path(tempfile.gettempdir()) / "ja-isolated-probe-workspace"
        frame = SMOKE.thread_create_frame(
            workspace,
            frame_id="c:eof-thread",
            title="Native stdin EOF smoke",
        )
        self.assertEqual("c:eof-thread", frame["id"])
        self.assertEqual("Native stdin EOF smoke", frame["params"]["title"])
        self.assertEqual(str(workspace.resolve()), frame["params"]["cwd"])

    def test_sidecar_command_is_the_exact_current_cli(self) -> None:
        """Locks the four directory arguments accepted by the single App Server composition graph."""

        root = Path(tempfile.gettempdir()) / "ja-合同-root"
        command = SMOKE.sidecar_command(
            Path("ja-app-server"),
            ["fixture.mjs"],
            root / "home",
            root / "data",
            root / "run",
            root / "log",
        )
        self.assertEqual(["ja-app-server", "fixture.mjs"], command[:2])
        self.assertEqual(
            ["--home-dir-base64", "--data-dir-base64", "--run-dir-base64", "--log-dir-base64"],
            [argument.split("=", 1)[0] for argument in command[2:]],
        )
        self.assertEqual(6, len(command))

    def test_emitted_requests_validate_against_the_current_v1_schema(self) -> None:
        """Checks the authoritative schema directly so handler-aligned builders cannot drift silently."""

        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        frames = [
            SMOKE.initialize_frame(),
            SMOKE.workspace_open_frame(Path(tempfile.gettempdir()) / "ja-合同-workspace"),
            SMOKE.workspace_trust_frame("ws_demo"),
            SMOKE.thread_create_frame(Path(tempfile.gettempdir()) / "ja-合同-workspace"),
            SMOKE.thread_rename_frame("thr_demo", 1),
            SMOKE.configuration_replace_frame("cfg_missing"),
            SMOKE.turn_start_frame("thr_demo", "current content contract"),
            SMOKE.skill_list_frame("ws_demo"),
            SMOKE.health_read_frame(),
            SMOKE.shutdown_frame(),
        ]
        for frame in frames:
            jsonschema.Draft202012Validator(schema).validate(frame)

    def test_loopback_configuration_document_is_exact_v1(self) -> None:
        """Locks the production smoke fixture to v1 and rejects every removed configuration field."""

        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        frame = SMOKE.configuration_replace_frame(
            "cfg_missing",
            "http://127.0.0.1:41001/v1",
            "http://127.0.0.1:41002/mcp",
        )
        jsonschema.Draft202012Validator(schema).validate(frame)

        document = frame["params"]["document"]
        provider = document["providers"][0]
        model = provider["models"][0]
        self.assertEqual(1, document["schema_version"])
        self.assertEqual({"context", "turn_limits"}, set(provider["agent_defaults"]))
        self.assertEqual(
            {"context_window_tokens", "max_output_tokens"},
            set(model["capabilities"]),
        )
        self.assertEqual({}, model["reasoning_level_map"])
        self.assertIsNone(model["default_reasoning_level"])
        encoded = json.dumps(document, sort_keys=True)
        for removed_field in (
            "default_reasoning_effort",
            "reasoning_efforts",
            "input_modalities",
            "skill_ids",
            "mcp_ids",
        ):
            self.assertNotIn(removed_field, encoded)

    def test_workspace_skill_fixture_matches_configured_identity(self) -> None:
        """Keeps Native Skill coverage on a real workspace package after bundled Skills were removed."""

        with tempfile.TemporaryDirectory(prefix="ja-native-skill-test-") as parent:
            workspace = Path(parent) / "workspace"
            workspace.mkdir()
            document = SMOKE.write_workspace_skill(workspace)
            content = document.read_text(encoding="utf-8")
            configured = SMOKE.configuration_document(
                "http://127.0.0.1:41001/v1",
                "http://127.0.0.1:41002/mcp",
            )["skills"][0]

            self.assertEqual(workspace / ".agents" / "skills" / SMOKE.SKILL_NAME / "SKILL.md", document)
            self.assertIn(f"name: {SMOKE.SKILL_NAME}\n", content)
            self.assertIn(f"description: {SMOKE.SKILL_DESCRIPTION}.\n", content)
            self.assertEqual(SMOKE.SKILL_NAME, configured["name"])
        self.assertEqual("project", configured["scope"])

    def test_loopback_provider_rejects_retired_responses_token_count(self) -> None:
        """Keeps the retired count route absent so a production preflight call breaks the smoke."""

        provider = SMOKE.LoopbackProvider()
        try:
            connection = http.client.HTTPConnection("127.0.0.1", provider.server.server_port, timeout=2)
            connection.request(
                "POST",
                "/v1/responses/input_tokens",
                body=b"{}",
                headers={"Content-Type": "application/json"},
            )
            response = connection.getresponse()
            self.assertEqual(11, response.version)
            self.assertEqual(404, response.status)
            response.read()
            self.assertEqual(0, provider.request_count)
            connection.close()
        finally:
            provider.close()

    def test_known_context_usage_requires_exact_durable_provider_accounting(self) -> None:
        """Reject unknown or mis-correlated Usage before Native smoke can report persistence success."""

        usage = {
            "turnId": "turn_expected",
            "modelRound": 1,
            "purpose": "assistant",
            "certainty": "known",
            "inputTokens": 5,
            "outputTokens": 5,
            "totalTokens": 10,
            "measuredAt": "2026-09-02T00:00:00Z",
        }
        self.assertEqual(
            usage,
            SMOKE.require_known_context_usage({"contextUsage": usage}, "turn_expected"),
        )
        with self.assertRaisesRegex(RuntimeError, "exact persisted Provider Usage"):
            SMOKE.require_known_context_usage(
                {"contextUsage": {**usage, "certainty": "unknown", "inputTokens": None}},
                "turn_expected",
            )
        with self.assertRaisesRegex(RuntimeError, "exact persisted Provider Usage"):
            SMOKE.require_known_context_usage({"contextUsage": usage}, "turn_other")

    @unittest.skipIf(shutil.which("node") is None, "Node.js is required for the mock sidecar")
    def test_mock_sidecar_exercises_four_unicode_roots_and_cleans_temp_home(self) -> None:
        """复制当前 v1 mock 到临时目录，验证四个 Unicode 根目录与受控清理。"""

        node = Path(shutil.which("node") or "")
        with tempfile.TemporaryDirectory(prefix="ja-native-fixture-") as fixture_parent, \
                tempfile.TemporaryDirectory(prefix="ja-native-smoke-test-") as parent:
            fixture_source = FIXTURE.read_text(encoding="utf-8")
            runtime_fixture = Path(fixture_parent) / FIXTURE.name
            runtime_fixture.write_text(
                fixture_source,
                encoding="utf-8",
                newline="\n",
            )
            identity = SMOKE.executable_identity(node)
            report = SMOKE.run_smoke(
                node,
                Path(parent),
                15.0,
                [str(runtime_fixture)],
                identity["sha256"],
                identity["sizeBytes"],
                identity["mtimeNs"],
            )
            # The fixture intentionally lacks Windows ACL, MCP, shell-cancel, recovery and
            # networknt evidence.  The process lifecycle still runs and cleans up, but the
            # release-facing report must fail closed instead of presenting a false green.
            self.assertEqual("failed", report["status"])
            self.assertFalse(report["passed"])
            self.assertTrue(report["executable"]["expectedIdentityMatched"])
            self.assertTrue(report["runtimeConfiguration"]["configured"])
            self.assertEqual("passed", report["subgates"]["jsonSchema"]["status"])
            self.assertEqual("blocked", report["subgates"]["configAuth"]["status"])
            self.assertEqual("blocked", report["subgates"]["okhttpSse"]["status"])
            self.assertEqual("blocked", report["subgates"]["mcp"]["status"])
            self.assertEqual("blocked", report["subgates"]["shellCancellation"]["status"])
            self.assertEqual("blocked", report["subgates"]["shellStdinEof"]["status"])
            self.assertEqual(
                SMOKE.REQUIRED_SUBGATES,
                tuple(report["requiredSubgates"]["required"]),
            )
            self.assertFalse(report["requiredSubgates"]["passed"])
            self.assertIn("okhttpSse", report["requiredSubgates"]["blocked"])
            self.assertEqual([], list(Path(parent).iterdir()))

    def test_expected_identity_is_all_or_none_and_fail_closed(self) -> None:
        """Prevents freshness callers from silently comparing only one mutable artifact attribute."""

        actual = {"sha256": "0" * 64, "sizeBytes": 1, "mtimeNs": 1}
        with self.assertRaisesRegex(RuntimeError, "incomplete"):
            SMOKE.require_expected_identity(actual, "0" * 64, None, None)
        with self.assertRaisesRegex(RuntimeError, "mismatch"):
            SMOKE.require_expected_identity(actual, "1" * 64, 1, 1)

    def test_each_artifact_identity_field_is_immutable_for_smoke(self) -> None:
        """Rejects a hash, size or timestamp mutation even when the other identity fields match."""

        expected = {"sha256": "0" * 64, "sizeBytes": 1, "mtimeNs": 1}
        for field, value in (("sha256", "1" * 64), ("sizeBytes", 2), ("mtimeNs", 2)):
            mutated = dict(expected)
            mutated[field] = value
            with self.assertRaisesRegex(RuntimeError, "mismatch"):
                SMOKE.require_expected_identity(mutated, expected["sha256"], expected["sizeBytes"], expected["mtimeNs"])

    def test_required_subgates_reject_blocked_and_missing_entries(self) -> None:
        """Keeps an omitted or blocked capability from being interpreted as an optional check."""

        complete = {name: {"status": "passed"} for name in SMOKE.REQUIRED_SUBGATES}
        blocked = dict(complete)
        blocked["mcp"] = {"status": "blocked", "reason": "fixture"}
        blocked_result = SMOKE.evaluate_required_subgates(blocked)
        self.assertFalse(blocked_result["passed"])
        self.assertEqual("blocked", blocked_result["status"])
        self.assertEqual(["mcp"], blocked_result["blocked"])

        missing = dict(complete)
        del missing["networknt"]
        missing_result = SMOKE.evaluate_required_subgates(missing)
        self.assertFalse(missing_result["passed"])
        self.assertEqual("blocked", missing_result["status"])
        self.assertEqual(["networknt"], missing_result["missing"])

    def test_required_subgates_reject_failed_or_mutated_status(self) -> None:
        """Treats an unknown status and a contradictory passed flag as hard failures."""

        unknown = {name: {"status": "passed"} for name in SMOKE.REQUIRED_SUBGATES}
        unknown["networknt"] = {"status": "changed"}
        unknown_result = SMOKE.evaluate_required_subgates(unknown)
        self.assertFalse(unknown_result["passed"])
        self.assertEqual("failed", unknown_result["status"])
        self.assertEqual(["networknt"], unknown_result["failed"])

        mutated = {name: {"status": "passed"} for name in SMOKE.REQUIRED_SUBGATES}
        mutated["configAuth"] = {"status": "passed", "passed": False}
        mutated_result = SMOKE.evaluate_required_subgates(mutated)
        self.assertFalse(mutated_result["passed"])
        self.assertEqual("failed", mutated_result["status"])
        self.assertEqual(["configAuth"], mutated_result["failed"])

    def test_required_subgates_pass_only_when_the_closed_set_passes(self) -> None:
        """Provides one positive pure-rule case so the fail-closed helper is not tautological."""

        complete = {name: {"status": "passed", "passed": True} for name in SMOKE.REQUIRED_SUBGATES}
        result = SMOKE.evaluate_required_subgates(complete)
        self.assertTrue(result["passed"])
        self.assertEqual("passed", result["status"])
        self.assertEqual([], result["missing"])
        self.assertEqual([], result["blocked"])
        self.assertEqual([], result["failed"])

    def test_turn_wait_fails_fast_when_terminal_precedes_approval(self) -> None:
        """Keeps a failed Tool Turn from consuming the smoke-wide deadline and hiding its real phase."""

        lines: Queue[bytes | None] = Queue()
        lines.put(json.dumps({
            "jsonrpc": "2.0",
            "method": "turn/terminal",
            "params": {"turnId": "turn_fixture", "state": "failed"},
        }).encode("utf-8") + b"\n")
        collector = SimpleNamespace(lines=lines, error=None, overflow=False)
        with self.assertRaisesRegex(RuntimeError, "settled as failed before approval/requested"):
            SMOKE.read_until_turn_event(
                collector,
                [],
                time.monotonic() + 1.0,
                "turn_fixture",
                "approval/requested",
            )

    def test_shell_cancellation_fixture_uses_declared_powershell_dialect(self) -> None:
        """锁定 Tool 只提交 PowerShell 7 命令文本，不在参数中选择或猜测可执行文件。"""

        self.assertEqual("Start-Sleep -Seconds 30", SMOKE.SHELL_COMMAND)
        self.assertNotEqual(SMOKE.SHELL_CANCEL_CALL_ID, SMOKE.SHELL_STDIN_EOF_CALL_ID)

    def test_loopback_provider_requires_advertised_shell_capability(self) -> None:
        """自动标题可包含相同用户文本，但没有 Tool 能力时不得被夹具误路由为 Shell。"""

        server = SMOKE.LoopbackProvider()
        connection = http.client.HTTPConnection("127.0.0.1", server.server.server_port, timeout=1.0)
        try:
            title_request = json.dumps({
                "input": [{"role": "user", "content": "stdin EOF"}],
                "tools": [],
            }).encode("utf-8")
            connection.request(
                "POST",
                "/v1/responses",
                body=title_request,
                headers={"Content-Type": "application/json"},
            )
            title_response = connection.getresponse()
            title_stream = title_response.read().decode("utf-8")
            self.assertEqual(200, title_response.status)
            self.assertIn(SMOKE.SMOKE_TEXT, title_stream)
            self.assertNotIn(SMOKE.SHELL_STDIN_EOF_CALL_ID, title_stream)

            tool_request = json.dumps({
                "input": [{"role": "user", "content": "stdin EOF"}],
                "tools": [{"type": "function", "name": "shell", "parameters": {}}],
            }).encode("utf-8")
            connection.request(
                "POST",
                "/v1/responses",
                body=tool_request,
                headers={"Content-Type": "application/json"},
            )
            tool_response = connection.getresponse()
            tool_stream = tool_response.read().decode("utf-8")
            self.assertEqual(200, tool_response.status)
            self.assertIn(SMOKE.SHELL_STDIN_EOF_CALL_ID, tool_stream)
            self.assertIn("JA_NATIVE_STDIN_EOF", tool_stream)
        finally:
            connection.close()
            server.close()

    def test_terminal_failure_label_excludes_message(self) -> None:
        """Allows stable diagnosis without copying provider or Tool failure text into smoke evidence."""

        event = {"params": {"state": "failed", "errorCode": "PROFILE_NOT_FOUND", "errorMessage": "secret"}}
        label = SMOKE.terminal_failure_label(event)
        self.assertEqual("settled as failed (PROFILE_NOT_FOUND)", label)
        self.assertNotIn("secret", label)

    def test_tool_failure_diagnostic_exposes_only_stable_code(self) -> None:
        """只提取有界 errorCode，禁止把 Tool 内容或参数复制到 Native 证据。"""

        documents = [{
            "method": "tool/batch-committed",
            "params": {
                "turnId": "turn_fixture",
                "results": [{
                    "errorCode": "shell_windows_process_launch_failed_2",
                    "content": "private output",
                }],
            },
        }]
        self.assertEqual(
            "shell_windows_process_launch_failed_2",
            SMOKE.latest_tool_error_code(documents, "turn_fixture"),
        )
        documents[0]["params"]["results"][0]["errorCode"] = "bad/path"
        self.assertIsNone(SMOKE.latest_tool_error_code(documents, "turn_fixture"))

    def test_mcp_notification_response_is_self_delimiting(self) -> None:
        """Locks HTTP/1.1 framing because the MCP client waits for notification transport completion."""

        server = SMOKE.LoopbackMcp()
        connection = http.client.HTTPConnection("127.0.0.1", server.server.server_port, timeout=1.0)
        try:
            payload = json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}).encode("utf-8")
            connection.request("POST", "/mcp", body=payload, headers={"Content-Type": "application/json"})
            response = connection.getresponse()
            self.assertEqual(202, response.status)
            self.assertEqual("0", response.getheader("Content-Length"))
            self.assertEqual("close", response.getheader("Connection"))
            self.assertEqual(b"", response.read())
        finally:
            connection.close()
            server.close()

    def test_mcp_initialize_uses_the_native_mcp_method_and_required_result(self) -> None:
        """锁定 MCP 原生 initialize 方法，避免误用 JA-RPC 名称后由 SDK 生成空结果告警。"""

        server = SMOKE.LoopbackMcp()
        connection = http.client.HTTPConnection("127.0.0.1", server.server.server_port, timeout=1.0)
        try:
            payload = json.dumps({
                "jsonrpc": "2.0",
                "id": "mcp:init",
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "fixture", "version": "1"},
                },
            }).encode("utf-8")
            connection.request("POST", "/mcp", body=payload, headers={"Content-Type": "application/json"})
            response = connection.getresponse()
            document = json.loads(response.read())
            self.assertEqual(200, response.status)
            self.assertEqual("2025-06-18", document["result"]["protocolVersion"])
            self.assertEqual({"tools": {}}, document["result"]["capabilities"])
            self.assertEqual("native-smoke-mcp", document["result"]["serverInfo"]["name"])
        finally:
            connection.close()
            server.close()


if __name__ == "__main__":
    unittest.main()
