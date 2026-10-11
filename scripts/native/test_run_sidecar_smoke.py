# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""针对 Native Image smoke 客户端的无 Provider 合同测试。"""

from __future__ import annotations

import http.client
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from queue import Queue
from types import SimpleNamespace
import unittest

import jsonschema


SCRIPT = Path(__file__).with_name("run-sidecar-smoke.py")
FIXTURE = Path(__file__).parents[1] / "e2e" / "fixtures" / "v2-smoke-mock-sidecar.mjs"
SCHEMA = Path(__file__).parents[2] / "contracts" / "ja-rpc" / "v2" / "schema" / "ja-rpc-v2.schema.json"
GOLDEN = Path(__file__).parents[2] / "contracts" / "golden" / "v2" / "valid" / "core.jsonl"
SPEC = importlib.util.spec_from_file_location("run_sidecar_smoke", SCRIPT)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import machinery failure is environmental
    raise RuntimeError("native smoke module is unavailable")
SMOKE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SMOKE
SPEC.loader.exec_module(SMOKE)


class NativeSmokeV2Test(unittest.TestCase):
    """验证精确的 v2 帧和完整的 mock-sidecar 生命周期。"""

    def test_capability_names_follow_golden_order_and_schema_set(self) -> None:
        """沿用 Java 共用的 golden 顺序，同时由 schema 封闭校验成员集合。"""

        document = json.loads(SCHEMA.read_text(encoding="utf-8"))
        definitions = document["$defs"]
        methods, events = SMOKE.load_capability_names(SCHEMA)
        golden_params = json.loads(GOLDEN.read_text(encoding="utf-8").splitlines()[0])["params"]

        self.assertEqual(golden_params["capabilities"]["methods"], methods)
        self.assertEqual(golden_params["capabilities"]["events"], events)
        self.assertEqual(set(definitions["methodName"]["enum"]), set(methods))
        self.assertEqual(
            set(name for name in definitions["eventName"]["enum"] if name != "runtime/initialized"),
            set(events),
        )
        self.assertIn("runtime/context/register", methods)
        self.assertIn("runtime/client/close", methods)
        self.assertIn("operation/read", methods)
        self.assertIn("thread/observe", methods)
        self.assertIn("thread/unobserve", methods)
        self.assertIn("thread/item-content/read", methods)

    def test_capability_loader_tracks_schema_additions(self) -> None:
        """golden 新成员决定 offer 顺序，schema 同时必须声明完全相同的闭集。"""

        document = json.loads(SCHEMA.read_text(encoding="utf-8"))
        definitions = document["$defs"]
        methods = definitions["methodName"]["enum"]
        events = definitions["eventName"]["enum"]
        methods.append("smoke/test-operation")
        events.append("smoke/test-event")
        golden_frame = json.loads(GOLDEN.read_text(encoding="utf-8").splitlines()[0])
        golden_capabilities = golden_frame["params"]["capabilities"]
        golden_capabilities["methods"].insert(0, "smoke/test-operation")
        golden_capabilities["events"].append("smoke/test-event")
        with tempfile.TemporaryDirectory(prefix="ja-native-capability-schema-") as directory:
            schema_path = Path(directory) / "schema.json"
            golden_path = Path(directory) / "core.jsonl"
            schema_path.write_text(json.dumps(document), encoding="utf-8")
            golden_path.write_text(json.dumps(golden_frame) + "\n", encoding="utf-8")
            loaded_methods, loaded_events = SMOKE.load_capability_names(schema_path, golden_path)
            self.assertEqual("smoke/test-operation", loaded_methods[0])
            self.assertEqual("smoke/test-event", loaded_events[-1])

            definitions["methodName"]["enum"].append("smoke/unlisted-operation")
            schema_path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "capability sets differ"):
                SMOKE.load_capability_names(schema_path, golden_path)

    def test_rpc_rejection_preserves_only_bounded_diagnostics(self) -> None:
        """错误操作与机器码可定位失败，任意消息和路径不得进入脱敏异常。"""
        with self.assertRaises(SMOKE.NativeRpcRejection) as raised:
            SMOKE.require_success({"error": {"message": "private-content", "data": {"errorCode": "STORAGE_UNAVAILABLE"}}}, "workspace binding")
        self.assertEqual("workspace binding", raised.exception.operation)
        self.assertEqual("STORAGE_UNAVAILABLE", raised.exception.error_code)
        self.assertNotIn("private-content", str(raised.exception))
        invalid = SMOKE.NativeRpcRejection("/private/path", "secret/value")
        self.assertEqual("unknown operation", invalid.operation)
        self.assertEqual("UNCLASSIFIED_RPC_ERROR", invalid.error_code)

    def test_secret_scan_distinguishes_public_protocol_names_from_values(self) -> None:
        """公开 credential 方法名必须可观测，但带值的 API key、Bearer 与 smoke secret 仍须被拦截。"""

        self.assertIsNone(SMOKE.LEAK_PATTERN.search('"method":"credential/set"'))
        self.assertIsNotNone(SMOKE.LEAK_PATTERN.search('"apiKey":"abcdefgh12345678"'))
        self.assertIsNotNone(SMOKE.LEAK_PATTERN.search("Bearer " + "abcdefgh12345678"))
        self.assertIn(SMOKE.SMOKE_SECRET, f"payload={SMOKE.SMOKE_SECRET}")

    def test_initialize_is_exact_v2_and_configuration_free(self) -> None:
        """方法与事件沿用合同顺序；Native scoped close 与 Task/Plan 必须共同进入生产闭集。"""

        params = SMOKE.initialize_frame()["params"]
        golden_params = json.loads(GOLDEN.read_text(encoding="utf-8").splitlines()[0])["params"]
        schema_definitions = json.loads(SCHEMA.read_text(encoding="utf-8"))["$defs"]
        methods = params["capabilities"]["methods"]
        events = params["capabilities"]["events"]
        schema_methods = set(schema_definitions["methodName"]["enum"])
        schema_events = set(
            name
            for name in schema_definitions["eventName"]["enum"]
            if name != "runtime/initialized"
        )
        self.assertEqual(2, params["protocolMajor"])
        self.assertEqual(0, params["protocolMinor"])
        self.assertEqual(
            {"protocolMajor", "protocolMinor", "clientVersion", "capabilities", "limits"},
            set(params),
        )
        self.assertNotIn("configSnapshot", params)
        self.assertNotIn("apiKey", str(params))
        self.assertNotIn("runtime/configure", methods)
        self.assertEqual(golden_params["capabilities"]["methods"], methods)
        self.assertEqual(golden_params["capabilities"]["events"], events)
        self.assertEqual(schema_methods, set(methods))
        self.assertEqual(schema_events, set(events))
        self.assertIn("runtime/context/register", methods)
        self.assertIn("runtime/client/close", methods)
        self.assertIn("operation/read", methods)
        self.assertIn("thread/observe", methods)
        self.assertIn("thread/unobserve", methods)
        self.assertIn("thread/compact", methods)
        self.assertIn("thread/pin", methods)
        self.assertIn("thread/seen", methods)
        self.assertIn("thread/restore", methods)
        self.assertIn("thread/preferences/update", methods)
        self.assertIn("workspace/path/search", methods)
        self.assertIn("task/create", methods)
        self.assertIn("task/tree/delete", methods)
        self.assertIn("task/closed", events)
        self.assertEqual(
            {
                "plan/read",
                "plan/revisions/list",
                "plan/current/read",
                "plan/events/read",
                "plan/evidence/list",
                "plan/observe",
                "plan/unobserve",
                "plan/pause",
                "plan/resume",
                "plan/stop",
                "plan/create",
                "plan/draft/save",
                "plan/draft/discard",
                "plan/propose",
                "plan/execute",
                "plan/reject",
            },
            {method for method in methods if method.startswith("plan/")},
        )
        self.assertFalse(any(method.startswith("goal/") for method in methods))
        self.assertIn("plan/create", methods)
        self.assertIn("plan/draft/save", methods)
        self.assertNotIn("plan/approve", methods)
        self.assertIn("interaction/respond", methods)
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
        self.assertEqual(len(methods), len(set(methods)))
        self.assertNotIn("runtime/initialized", events)
        self.assertIn("context/compaction-started", events)
        self.assertIn("context/compaction-failed", events)
        self.assertIn("thread/metadata-changed", events)
        self.assertIn("task/activity", events)
        self.assertFalse(any(event.startswith("goal/") for event in events))
        self.assertIn("interaction/changed", events)
        self.assertIn("plan/changed", events)
        self.assertNotIn("turn/change-preview-updated", events)
        self.assertEqual(["default", "plan"], params["capabilities"]["collaborationModes"])
        self.assertEqual(
            ["task_threads", "plan", "interaction"],
            params["capabilities"]["features"],
        )
        self.assertEqual(["approval_required", "full_access"], params["capabilities"]["accessModes"])
        for capability in ("accessModes", "collaborationModes", "features"):
            self.assertEqual(golden_params["capabilities"][capability], params["capabilities"][capability])
        self.assertEqual(golden_params["limits"], params["limits"])

    def test_initialize_identity_requires_current_product_version(self) -> None:
        """真实 Native 门从根版本源校验 Kernel 身份，避免过期 sidecar 通过启动级探针。"""

        result = {
            "runtime": {"engine": "ja-kernel", "engineVersion": SMOKE.EXPECTED_ENGINE_VERSION}
        }
        self.assertIs(result, SMOKE.require_initialize_identity(result, SMOKE.EXPECTED_ENGINE_VERSION))
        with self.assertRaisesRegex(RuntimeError, "expected Kernel engine version"):
            SMOKE.require_initialize_identity(
                {"runtime": {"engine": "ja-kernel", "engineVersion": "2.0.0"}},
                SMOKE.EXPECTED_ENGINE_VERSION,
            )

    def test_workspace_and_thread_use_cwd_without_client_identity(self) -> None:
        """要求 workspaceId 由 Java 分配，不接受客户端计算的身份。"""

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
        """锁定唯一 App Server 组合图所接受的四个目录参数。"""

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
            ["--home-dir-base64", "--data-dir-base64", "--run-dir-base64", "--log-dir-base64", "--ja-runtime-generation"],
            [argument.split("=", 1)[0] for argument in command[2:]],
        )
        self.assertEqual(7, len(command))
        self.assertEqual("--ja-runtime-generation=1", command[-1])

    def test_emitted_requests_validate_against_the_current_v2_schema(self) -> None:
        """直接检查权威 schema，避免与处理器一致的构造器悄然偏离契约。"""

        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        frames = [
            SMOKE.initialize_frame(),
            SMOKE.workspace_open_frame(Path(tempfile.gettempdir()) / "ja-合同-workspace"),
            SMOKE.workspace_trust_frame("ws_demo"),
            SMOKE.thread_create_frame(Path(tempfile.gettempdir()) / "ja-合同-workspace"),
            SMOKE.thread_item_content_read_frame("thr_demo", "item_demo"),
            SMOKE.thread_rename_frame("thr_demo", 1),
            SMOKE.configuration_replace_frame("cfg_missing"),
            SMOKE.approval_response_frame("appr_demo", "turn_demo", 1),
            SMOKE.turn_start_frame("thr_demo", "current content contract"),
            SMOKE.turn_cancel_frame("turn_demo"),
            SMOKE.skill_list_frame("ws_demo"),
            SMOKE.health_read_frame(),
            SMOKE.shutdown_frame(),
        ]
        for frame in frames:
            jsonschema.Draft202012Validator(schema).validate(frame)

    def test_item_content_read_frame_uses_the_v2_unicode_page_contract(self) -> None:
        """正文 smoke 使用 code point 游标和当前 Thread/item 双重身份。"""

        frame = SMOKE.thread_item_content_read_frame("thr_demo", "item_demo")
        self.assertEqual("thread/item-content/read", frame["method"])
        self.assertEqual(
            {
                "threadId": "thr_demo",
                "itemId": "item_demo",
                "offsetCharacters": 0,
                "limitCharacters": SMOKE.ITEM_CONTENT_PAGE_LIMIT,
            },
            frame["params"],
        )

    def test_turn_cancellation_frame_uses_only_the_current_v2_identity(self) -> None:
        """防止已移除的 CAS 字段重新进入生产取消请求并只在 Native CI 暴露。"""

        frame = SMOKE.turn_cancel_frame("turn_demo")
        self.assertEqual({"turnId": "turn_demo"}, frame["params"])

    def test_turn_start_frame_has_a_fresh_operation_key(self) -> None:
        """每次 smoke admission 使用独立回执键，满足当前 JA-RPC 幂等边界。"""

        first = SMOKE.turn_start_frame("thr_demo", "first")["params"]
        second = SMOKE.turn_start_frame("thr_demo", "second")["params"]
        self.assertRegex(first["operationKey"], r"^op_[0-9a-f]{32}$")
        self.assertRegex(second["operationKey"], r"^op_[0-9a-f]{32}$")
        self.assertNotEqual(first["operationKey"], second["operationKey"])

    def test_turn_start_frame_uses_only_schema_owned_request_fields(self) -> None:
        """Deadline 由 smoke harness 限制，不能扩展服务端 admission 的严格 v2 字段集。"""

        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        frame = SMOKE.turn_start_frame("thr_demo", "schema-owned request")
        self.assertEqual({"threadId", "content", "operationKey"}, set(frame["params"]))
        jsonschema.Draft202012Validator(schema).validate(frame)

    def test_approval_response_frame_has_a_fresh_operation_key(self) -> None:
        """每次审批裁决都用独立回执键，避免重复提交串扰另一条 smoke 任务。"""

        first = SMOKE.approval_response_frame("appr_demo", "turn_demo", 1)["params"]
        second = SMOKE.approval_response_frame("appr_demo", "turn_demo", 2)["params"]
        self.assertRegex(first["operationKey"], r"^op_[0-9a-f]{32}$")
        self.assertRegex(second["operationKey"], r"^op_[0-9a-f]{32}$")
        self.assertNotEqual(first["operationKey"], second["operationKey"])

    def test_loopback_configuration_document_is_exact_v2(self) -> None:
        """锁定当前 v2 用户配置字段，禁用列表与 workspace 中发现的 Skill 分开表达。"""

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
        self.assertEqual(2, document["schema_version"])
        self.assertEqual({"clarification_enabled": True}, document["interaction"])
        self.assertEqual([], document["disabled_skills"])
        self.assertNotIn("skills", document)
        self.assertEqual({"context"}, set(provider["agent_defaults"]))
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
        """移除内置 Skills 后，仍通过真实工作区包覆盖 Native Skill 行为。"""

        with tempfile.TemporaryDirectory(prefix="ja-native-skill-test-") as parent:
            workspace = Path(parent) / "workspace"
            workspace.mkdir()
            document = SMOKE.write_workspace_skill(workspace)
            content = document.read_text(encoding="utf-8")
            self.assertEqual(workspace / ".agents" / "skills" / SMOKE.SKILL_NAME / "SKILL.md", document)
            self.assertIn(f"name: {SMOKE.SKILL_NAME}\n", content)
            self.assertIn(f"description: {SMOKE.SKILL_DESCRIPTION}.\n", content)

    def test_loopback_provider_rejects_retired_responses_token_count(self) -> None:
        """确保已废弃的计数路由不存在，使生产预检调用能够令 smoke 失败。"""

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
        """拒绝未知或关联错误的 Usage，避免 Native smoke 将其报告为持久化成功。"""

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
        """复制当前 v2 mock 到临时目录，验证四个 Unicode 根目录与受控清理。"""

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
            # 此夹具有意不提供 Windows ACL、MCP、shell 取消、恢复及 networknt 证据。进程生命周期仍会运行并清理，
            # 但面向发布的报告必须失败关闭，不能误报通过。
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
        """避免新鲜度检查调用方只比较一个可变产物属性而不报错。"""

        actual = {"sha256": "0" * 64, "sizeBytes": 1, "mtimeNs": 1}
        with self.assertRaisesRegex(RuntimeError, "incomplete"):
            SMOKE.require_expected_identity(actual, "0" * 64, None, None)
        with self.assertRaisesRegex(RuntimeError, "mismatch"):
            SMOKE.require_expected_identity(actual, "1" * 64, 1, 1)

    def test_each_artifact_identity_field_is_immutable_for_smoke(self) -> None:
        """即使其他身份字段匹配，摘要、大小或时间戳发生变化时也必须拒绝。"""

        expected = {"sha256": "0" * 64, "sizeBytes": 1, "mtimeNs": 1}
        for field, value in (("sha256", "1" * 64), ("sizeBytes", 2), ("mtimeNs", 2)):
            mutated = dict(expected)
            mutated[field] = value
            with self.assertRaisesRegex(RuntimeError, "mismatch"):
                SMOKE.require_expected_identity(mutated, expected["sha256"], expected["sizeBytes"], expected["mtimeNs"])

    def test_required_subgates_reject_blocked_and_missing_entries(self) -> None:
        """缺失或受阻的能力不能被解释为可选检查。"""

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
        """将未知状态和相互矛盾的通过标记视为硬失败。"""

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
        """补充一条纯规则的正向用例，避免失败关闭辅助逻辑只验证自身。"""

        complete = {name: {"status": "passed", "passed": True} for name in SMOKE.REQUIRED_SUBGATES}
        result = SMOKE.evaluate_required_subgates(complete)
        self.assertTrue(result["passed"])
        self.assertEqual("passed", result["status"])
        self.assertEqual([], result["missing"])
        self.assertEqual([], result["blocked"])
        self.assertEqual([], result["failed"])

    def test_turn_wait_fails_fast_when_terminal_precedes_approval(self) -> None:
        """避免失败的 Tool Turn 耗尽 smoke 全局时限并掩盖实际阶段。"""

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

    def test_shell_cancellation_fixture_uses_host_dialect(self) -> None:
        """同时锁定 Windows 与 POSIX 的长命令，防止跨平台门禁以命令失败替代取消。"""

        self.assertEqual("Start-Sleep -Seconds 30", SMOKE.shell_cancellation_command("nt"))
        self.assertEqual("sleep 30", SMOKE.shell_cancellation_command("posix"))
        self.assertEqual(SMOKE.shell_cancellation_command(SMOKE.os.name), SMOKE.SHELL_COMMAND)
        self.assertNotEqual(SMOKE.SHELL_CANCEL_CALL_ID, SMOKE.SHELL_STDIN_EOF_CALL_ID)

    def test_cancellation_waits_for_running_process_from_exact_tool(self) -> None:
        """模拟 started 投影先于真实启动，要求 runner 持续查询同一个调用身份再取消。"""

        lines: Queue[bytes | None] = Queue()
        collector = SimpleNamespace(lines=lines, error=None, overflow=False)
        sent = []

        def send(frame: dict[str, object]) -> None:
            """第一次没有进程，第二次才发布真实 running 身份，保留请求关联和过滤条件。"""

            sent.append(frame)
            sessions = [] if len(sent) == 1 else [{
                "startedTurnId": "turn_fixture", "startedCallId": SMOKE.SHELL_CANCEL_CALL_ID,
                "state": "running", "sessionId": "proc_fixture",
            }]
            lines.put(json.dumps({"jsonrpc": "2.0", "id": frame["id"],
                                  "result": {"sessions": sessions}}).encode("utf-8") + b"\n")

        session = SMOKE.await_running_shell(
            send, collector, [], time.monotonic() + 1, None,
            "ws_fixture", "thr_fixture", "turn_fixture",
        )
        self.assertEqual("proc_fixture", session)
        self.assertEqual(2, len(sent))
        for frame in sent:
            self.assertEqual("process/list", frame["method"])
            self.assertEqual(SMOKE.SHELL_CANCEL_CALL_ID, frame["params"]["startedCallId"])

    def test_cancellation_rejects_settled_or_mismatched_process(self) -> None:
        """立即退出或其它调用的进程均不能充当运行中取消证据。"""

        for changes, failure in (({"state": "exited"}, "settled"),
                                 ({"startedTurnId": "turn_other"}, "identity"),
                                 ({"startedCallId": "call_other"}, "identity")):
            with self.subTest(changes=changes):
                lines: Queue[bytes | None] = Queue()
                collector = SimpleNamespace(lines=lines, error=None, overflow=False)

                def send(frame: dict[str, object]) -> None:
                    """返回关联 RPC 响应，同时把被篡改的进程事实保留给严格身份断言。"""

                    session = {"startedTurnId": "turn_fixture", "startedCallId": SMOKE.SHELL_CANCEL_CALL_ID,
                               "state": "running", "sessionId": "proc_fixture", **changes}
                    lines.put(json.dumps({"jsonrpc": "2.0", "id": frame["id"],
                                          "result": {"sessions": [session]}}).encode("utf-8") + b"\n")

                with self.assertRaisesRegex(RuntimeError, failure):
                    SMOKE.await_running_shell(
                        send, collector, [], time.monotonic() + 1, None,
                        "ws_fixture", "thr_fixture", "turn_fixture",
                    )

    def test_cancellation_stops_waiting_when_turn_finishes_before_process_registration(self) -> None:
        """不存在真实进程时，已经完成的 Turn 应立即失败，不能等待耗尽整个 smoke deadline。"""

        lines: Queue[bytes | None] = Queue()
        collector = SimpleNamespace(lines=lines, error=None, overflow=False)

        def send(frame: dict[str, object]) -> None:
            """交错投递 Turn 终止和空列表响应，复现事件先于 RPC 回执的真实顺序。"""

            for response in ({"jsonrpc": "2.0", "method": "turn/terminal",
                              "params": {"turnId": "turn_fixture", "state": "completed"}},
                             {"jsonrpc": "2.0", "id": frame["id"], "result": {"sessions": []}}):
                lines.put(json.dumps(response).encode("utf-8") + b"\n")

        with self.assertRaisesRegex(RuntimeError, "Turn settled"):
            SMOKE.await_running_shell(
                send, collector, [], time.monotonic() + 1, None,
                "ws_fixture", "thr_fixture", "turn_fixture",
            )

    @unittest.skipUnless(SMOKE.os.name == "nt" and shutil.which("pwsh"), "requires Windows PowerShell 7")
    def test_stdin_eof_probe_decodes_utf8_independently_of_console_code_page(self) -> None:
        """用真实重定向管道验证中文与 EOF，避免 GB2312 宿主误判 UTF-8 传输失败。"""

        process = subprocess.run(
            [shutil.which("pwsh"), "-Command", SMOKE.SHELL_STDIN_EOF_COMMAND],
            input="Ja-input-中文".encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=20,
            check=False,
        )
        self.assertEqual(0, process.returncode)
        self.assertIn(b"JA_NATIVE_STDIN_EOF", process.stdout)

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
                "tools": [{"type": "function", "name": "exec_command", "parameters": {}}],
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
        """在不复制 Provider 或 Tool 错误文本到 smoke 证据的前提下保留稳定诊断信息。"""

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
        """锁定 HTTP/1.1 分帧行为，因为 MCP 客户端会等待通知传输完成。"""

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

    def test_failure_stage_identifies_last_rpc_without_response_body(self) -> None:
        """失败现场只区分请求与等待阶段，不把正文、参数或结果文本写入诊断。"""

        sent = [{"method": "turn/continue", "id": "c:continue"}]
        documents = [{
            "id": "c:continue",
            "result": {"state": "accepted", "privateText": "do-not-copy"},
        }]
        stage = SMOKE.failure_stage_summary(sent, documents)
        trace = SMOKE.failure_protocol_trace(sent, documents)
        self.assertEqual("awaiting_followup_frame", stage["kind"])
        self.assertEqual("turn/continue", stage["requestMethod"])
        self.assertTrue(stage["rpcResponseReceived"])
        self.assertNotIn("do-not-copy", json.dumps(trace))
        self.assertEqual("c:continue", trace["server"][0]["id"])

    def test_failure_diagnostics_redact_stderr_and_list_only_profile_files(self) -> None:
        """用明确 mock 值验证失败证据过滤凭据及绝对路径，不依赖真实令牌形状。"""

        with tempfile.TemporaryDirectory(prefix="ja-native-diagnostics-") as directory:
            profile = Path(directory) / "profile"
            profile.mkdir()
            (profile / "logs").mkdir()
            (profile / "data.sqlite").write_bytes(b"isolated database")
            (profile / "logs" / "app-server.log").write_text(
                "native log line\n", encoding="utf-8"
            )
            secret_path = profile / "private" / "logs"
            stderr = (
                f"{SMOKE.SMOKE_SECRET}\n{secret_path}\\app-server.log\n"
                "Authorization: Bearer mock-bearer\napi_key='mock-api-key'\n"
            ).encode("utf-8")
            diagnostic_ref = SMOKE.write_failure_diagnostics(
                profile,
                [{"method": "turn/continue", "id": "c:continue"}],
                [{"id": "c:continue", "result": {"text": "private body"}}],
                stderr,
                [profile],
            )
            diagnostic = json.loads((profile / "failure-diagnostics.json").read_text(encoding="utf-8"))
            encoded = json.dumps(diagnostic, ensure_ascii=False)
            self.assertEqual("profile", diagnostic_ref["profileDirectory"])
            self.assertEqual("awaiting_followup_frame", diagnostic["stage"]["kind"])
            self.assertEqual(["data.sqlite"], [item["path"] for item in diagnostic["databaseFiles"]])
            self.assertIn("[redacted-secret]", diagnostic["stderrRedacted"])
            self.assertNotIn(SMOKE.SMOKE_SECRET, encoded)
            self.assertNotIn("mock-bearer", encoded)
            self.assertNotIn("mock-api-key", encoded)
            self.assertNotIn("private body", encoded)
            self.assertNotIn(str(profile), encoded)

    def test_run_failure_returns_profile_pointer_through_report_sink(self) -> None:
        """异常清理会保留有限失败目录，并通过报告通道回传相对定位信息。"""

        executable = Path(sys.executable)
        identity = SMOKE.executable_identity(executable)
        evidence: dict[str, object] = {}
        with tempfile.TemporaryDirectory(prefix="ja-native-failure-sink-") as directory:
            with self.assertRaisesRegex(RuntimeError, "non-JSON stdout"):
                SMOKE.run_smoke(
                    executable,
                    Path(directory),
                    10.0,
                    ["-c", "import time; print('not-json', flush=True); time.sleep(5)"],
                    identity["sha256"],
                    identity["sizeBytes"],
                    identity["mtimeNs"],
                    keep_failed_profile=True,
                    failure_evidence_sink=evidence,
                )
            profile = Path(directory) / str(evidence["profileDirectory"])
            self.assertTrue((profile / "failure-diagnostics.json").is_file())
            self.assertEqual("awaiting_rpc_response", evidence["stage"]["kind"])

    def test_cli_report_contains_failure_evidence_pointer(self) -> None:
        """命令行异常路径把失败 profile 的相对指针写进报告，不要求读者猜临时目录。"""

        executable = Path(sys.executable)
        identity = SMOKE.executable_identity(executable)
        with tempfile.TemporaryDirectory(prefix="ja-native-report-sink-") as directory:
            root = Path(directory)
            output = root / "report.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT.resolve()),
                    "--executable", str(executable),
                    "--data-dir", str(root / "data"),
                    "--timeout-seconds", "10",
                    "--expected-sha256", identity["sha256"],
                    "--expected-size", str(identity["sizeBytes"]),
                    "--expected-mtime-ns", str(identity["mtimeNs"]),
                    "--keep-failed-profile",
                    "--output", str(output),
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=15,
            )
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(1, completed.returncode)
            self.assertEqual("failed", report["status"])
            self.assertIn("failureEvidence", report)
            profile = root / "data" / report["failureEvidence"]["profileDirectory"]
            self.assertTrue((profile / report["failureEvidence"]["diagnosticsFile"]).is_file())


if __name__ == "__main__":
    unittest.main()
