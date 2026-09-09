# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Run the production Native Image sidecar through a bounded activation handshake.

This is a CI-only smoke gate. It deliberately uses the current protocol fixture shape instead of
implementing a second protocol client: the gate proves that the built executable starts, publishes
the Ja Kernel identity, opens fresh SQLite and Skills through the production graph, exercises only
host-local Provider/MCP probes, and shuts down cleanly.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
from queue import Empty, Queue
import re
import signal
import stat
import shutil
import subprocess
import tempfile
import threading
import time
from typing import Any

import jsonschema


MAX_OUTPUT_BYTES = 4 * 1024 * 1024
READY_TOKEN = "0123456789abcdef0123456789abcdef"
EXPECTED_ENGINE_VERSION = json.loads(
    (Path(__file__).parents[2] / "package.json").read_text(encoding="utf-8")
)["version"]
METHODS = [
    "runtime/initialize", "runtime/health", "runtime/shutdown", "workspace/open", "workspace/open-general", "workspace/list",
    "workspace/path/search", "workspace/set-trust", "workspace/unregister", "thread/create", "thread/list", "thread/search",
    "thread/read", "thread/rename", "thread/pin", "thread/seen", "thread/preferences/update", "thread/archive",
    "thread/restore", "thread/delete", "thread/compact",
    "goal/read", "goal/events/read", "goal/observe", "goal/unobserve", "plan/read", "plan/revisions/list",
    "goal/evidence/list", "goal/create", "goal/plan/attach", "goal/plan/detach", "goal/pause", "goal/resume",
    "goal/stop", "goal/input/respond", "plan/create", "plan/draft/save", "plan/draft/discard", "plan/propose",
    "plan/approve", "plan/execute", "plan/reject",
    "task/create", "task/list", "task/read", "task/observe", "task/unobserve", "task/seen",
    "task/message/send", "task/followup", "task/cancel", "task/tree/delete",
    "attachment/import", "attachment/discard", "attachment/preview/open", "attachment/preview/read",
    "attachment/preview/close", "turn/start", "turn/resume", "turn/cancel", "turn/input/enqueue",
    "turn/input/prioritize", "turn/input/update", "turn/input/delete", "turn/change-set/read",
    "approval/respond", "configuration/read", "configuration/patch", "configuration/replace",
    "configuration/reset", "credential/set", "credential/delete", "skill/list", "mcp/list",
    "mcp/test", "model/test", "mcp/list-tools", "tool/artifact/read",
]
EVENTS = [
    "runtime/status-changed", "turn/state-changed", "turn/input-queue-changed", "turn/input-consumed",
    "assistant/model-step-committed",
    "assistant/text-delta", "assistant/reasoning-summary-delta", "tool/started", "tool/batch-committed",
    "approval/requested", "approval/resolved", "context/compaction-started", "context/compacted",
    "context/compaction-failed",
    "workspace/dirty", "turn/terminal",
    "thread/metadata-changed", "configuration/changed",
    "task/activity", "task/progress", "task/mailbox-changed",
    "goal/changed", "goal/activity", "goal/input-requested",
]
SECRET_NAME_PARTS = (
    "API_KEY",
    "TOKEN",
    "SECRET",
    "PASSWORD",
    "BEARER",
    "CREDENTIAL",
)
# 协议目录合法包含 credential/set 等公开标识；这里只匹配带值的凭据形态，精确 smoke secret 另行检查。
LEAK_PATTERN = re.compile(
    r"api[_ -]?key[\"']?\s*[:=]\s*[\"']?[^\s,}\"]{8,}"
    r"|bearer\s+[a-z0-9._-]{8,}|sk-[a-z0-9_-]{8,}|github_token\s*[:=]",
    re.IGNORECASE,
)
SMOKE_SECRET = "ja-native-smoke-placeholder-secret"
SCHEMA_PATH = Path(__file__).parents[2] / "contracts" / "ja-rpc" / "v1" / "schema" / "ja-rpc-v1.schema.json"
PROVIDER_ID = "provider_native_smoke"
MODEL_ID = "model_native_smoke"
CREDENTIAL_ID = "cred_native_smoke"
MCP_ID = "mcp_native_smoke"
SKILL_ID = "skill_native_workspace"
SKILL_NAME = "native-smoke"
SKILL_DESCRIPTION = "Workspace Skill used by the Native activation gate"
SMOKE_TEXT = "JA_NATIVE_PROVIDER_TEXT"
SHELL_COMMAND = "Start-Sleep -Seconds 30"
SHELL_CANCEL_CALL_ID = "call_shell_cancel"
SHELL_STDIN_EOF_CALL_ID = "call_shell_stdin_eof"
SHELL_STDIN_EOF_COMMAND = (
    "$input=[Console]::In.ReadToEnd(); "
    "if ($input.Length -ne 0) { exit 9 }; Write-Output 'JA_NATIVE_STDIN_EOF'"
) if os.name == "nt" else "test -z \"$(cat)\" && printf 'JA_NATIVE_STDIN_EOF\\n'"

# Keep this closure deliberately explicit.  A Native smoke report is a release gate, so adding a
# capability without adding it here would silently turn an unverified feature into a green result.
REQUIRED_SUBGATES = (
    "jsonSchema",
    "configAuth",
    "okhttpSse",
    "mcp",
    "shellCancellation",
    "shellStdinEof",
    "sqlite",
    "recovery",
    "networknt",
)


def executable_identity(executable: Path) -> dict[str, Any]:
    """Hash the exact launched artifact so freshness evidence can correlate smoke to the build."""

    digest = hashlib.sha256()
    with executable.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    metadata = executable.stat()
    return {
        "sizeBytes": metadata.st_size,
        "sha256": digest.hexdigest(),
        "mtimeNs": metadata.st_mtime_ns,
    }


def require_expected_identity(
    actual: dict[str, Any],
    expected_sha256: str | None,
    expected_size: int | None,
    expected_mtime_ns: int | None,
) -> bool:
    """Fail closed when a caller supplies an incomplete or mismatched build-artifact identity."""

    expected = (expected_sha256, expected_size, expected_mtime_ns)
    if all(value is None for value in expected):
        return False
    if any(value is None for value in expected):
        raise RuntimeError("native executable expected identity is incomplete")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256 or ""):
        raise RuntimeError("native executable expected identity is invalid")
    if expected_size is None or expected_size < 1 or expected_mtime_ns is None or expected_mtime_ns < 1:
        raise RuntimeError("native executable expected identity is invalid")
    if actual != {
        "sizeBytes": expected_size,
        "sha256": expected_sha256,
        "mtimeNs": expected_mtime_ns,
    }:
        raise RuntimeError("native executable identity mismatch")
    return True


def evaluate_required_subgates(subgates: Any) -> dict[str, Any]:
    """Require every declared Native capability to report an explicit passed status.

    The smoke intentionally does not manufacture evidence for capabilities implemented by other
    owners.  Missing and blocked entries therefore remain visible and fail the top-level report;
    an unknown or malformed status is treated as a failed assertion rather than a permissive skip.
    """

    missing: list[str] = []
    blocked: list[str] = []
    failed: list[str] = []
    if not isinstance(subgates, dict):
        missing.extend(REQUIRED_SUBGATES)
    else:
        for name in REQUIRED_SUBGATES:
            entry = subgates.get(name)
            if not isinstance(entry, dict) or "status" not in entry:
                missing.append(name)
                continue
            status = entry.get("status")
            if status == "passed":
                # A producer may include a boolean assertion as a second field.  If present it
                # must agree with status so a mutated report cannot masquerade as a pass.
                if "passed" in entry and entry.get("passed") is not True:
                    failed.append(name)
            elif status in {"blocked", "skipped"}:
                blocked.append(name)
            else:
                failed.append(name)
    if failed:
        status = "failed"
    elif missing or blocked:
        status = "blocked"
    else:
        status = "passed"
    return {
        "status": status,
        "passed": not missing and not blocked and not failed,
        "required": list(REQUIRED_SUBGATES),
        "missing": missing,
        "blocked": blocked,
        "failed": failed,
    }


def initialize_frame() -> dict[str, Any]:
    """Build the smallest client capability document that exercises the real handshake.

    The production runtime must remain startable before a Provider/model selection is activated. The smoke
    sends the complete current v1 capability vocabulary because the server rejects negotiation
    subsets. No configuration or credential is sent, so this gate cannot call a provider.
    """

    return {
        "jsonrpc": "2.0",
        "id": "c:init",
        "method": "runtime/initialize",
        "params": {
            "protocolMajor": 1,
            "protocolMinor": 0,
            "clientVersion": "ja-native-ci",
            "capabilities": {
                "methods": METHODS,
                "events": EVENTS,
                "accessModes": ["approval_required", "full_access"],
                "collaborationModes": ["default", "plan"],
                "features": ["task_threads_v1", "plan_goal_v1"],
            },
            "limits": {
                "maxFrameBytes": 4194304,
                "maxInFlightRequests": 64,
                "maxInboundQueueFrames": 256,
                "maxControlOutboundQueueFrames": 64,
                "maxDataOutboundQueueFrames": 1024,
                "maxConcurrentTurns": 8,
                "maxAdmittedTurns": 64,
                "maxThreadQueuedTurns": 8,
                "maxTurnQueuedInputs": 8,
                "maxTurnQueuedInputBytes": 524_288,
                "maxSnapshotPageItems": 200,
                "maxToolBatchConcurrency": 8,
            },
        },
    }


def initialized_frame() -> dict[str, Any]:
    """Return the challenge response required before the runtime can publish ready."""

    return {
        "jsonrpc": "2.0",
        "method": "runtime/initialized",
        "params": {"readyToken": READY_TOKEN},
    }


def require_initialize_identity(result: Any, expected_engine_version: str) -> dict[str, Any]:
    """严格绑定 Kernel 名称和协议版本，防止 smoke 对过期 Native 产物误报通过。"""

    if not isinstance(result, dict):
        raise RuntimeError("native sidecar did not acknowledge initialize")
    runtime = result.get("runtime")
    if not isinstance(runtime, dict) or runtime.get("engine") != "ja-kernel":
        raise RuntimeError("initialize did not report the Ja Kernel runtime")
    if runtime.get("engineVersion") != expected_engine_version:
        raise RuntimeError("initialize did not report the expected Kernel engine version")
    return result


def shutdown_frame() -> dict[str, Any]:
    """Return the normal close request so the smoke observes the graceful path."""

    return {"jsonrpc": "2.0", "id": "c:shutdown", "method": "runtime/shutdown", "params": {}}


def workspace_open_frame(workspace: Path) -> dict[str, Any]:
    """Bind an isolated workspace before graph construction can inspect files or tools config."""

    return {
        "jsonrpc": "2.0",
        "id": "c:workspace",
        "method": "workspace/open",
        "params": {
            "cwd": str(workspace.resolve()),
            "displayName": "Native activation smoke",
        },
    }


def workspace_trust_frame(workspace_id: str) -> dict[str, Any]:
    """Trust the exact server-owned Workspace before project Skills may enter a Turn snapshot."""

    return {
        "jsonrpc": "2.0",
        "id": "c:workspace-trust",
        "method": "workspace/set-trust",
        "params": {"workspaceId": workspace_id, "trust": "trusted"},
    }


def skill_list_frame(workspace_id: str | None = None) -> dict[str, Any]:
    """Request the real Skill catalog while keeping project roots behind an opaque identity."""

    params = {} if workspace_id is None else {"workspaceId": workspace_id}
    return {"jsonrpc": "2.0", "id": "c:skills", "method": "skill/list", "params": params}


def health_read_frame() -> dict[str, Any]:
    """Read live SQLite and Kernel component state after the ready barrier."""

    return {"jsonrpc": "2.0", "id": "c:health", "method": "runtime/health", "params": {}}


def config_read_frame() -> dict[str, Any]:
    """Read both configuration layers and the global credential CAS before mutating the smoke home."""

    return {"jsonrpc": "2.0", "id": "c:config-read", "method": "configuration/read", "params": {}}


def configuration_document(provider_endpoint: str, mcp_endpoint: str) -> dict[str, Any]:
    """Build the bounded local-loopback document used by runtime probes.

    The document contains no credential bytes and points only at servers owned by this smoke
    process. Keeping Provider and models in the same v1 batch as the MCP descriptor proves that the
    executable resolves one immutable generation instead of relying on a test-only provider hook.
    """

    return {
        "schema_version": 1,
        "config_revision": 0,
        "default_access_mode": "approval_required",
        "default_provider_id": PROVIDER_ID,
        "default_model_id": MODEL_ID,
        "default_reasoning_level": None,
        "providers": [{
            "provider_id": PROVIDER_ID,
            "name": "Native local probe",
            "api": "openai_responses",
            "base_url": provider_endpoint,
            "credential_id": CREDENTIAL_ID,
            "network_timeouts": {"connect_timeout_ms": 2_000, "request_timeout_ms": 10_000},
            "agent_defaults": {
                "context": {"auto_compact": True},
                "turn_limits": {
                    "max_model_rounds": 8,
                    "max_tool_calls": 8,
                    "wall_timeout_ms": 30_000,
                },
            },
            "models": [{
                "model_id": MODEL_ID,
                "name": "Native smoke model",
                "model": "native-smoke-model",
                "capabilities": {
                    "context_window_tokens": 128_000,
                    "max_output_tokens": 8_192,
                },
                "reasoning_level_map": {},
                "default_reasoning_level": None,
            }],
        }],
        "mcp_servers": [{
            "mcp_id": MCP_ID,
            "name": "Native local MCP",
            "transport": "streamable_http",
            "endpoint": mcp_endpoint,
            "args": [],
            "env": {},
            "headers": {},
            "auth": {"kind": "none"},
            "enabled": True,
        }],
        "skills": [{
            "skill_id": SKILL_ID,
            "name": SKILL_NAME,
            "scope": "project",
            "enabled": True,
            "description": SKILL_DESCRIPTION,
        }],
    }


def write_workspace_skill(workspace: Path) -> Path:
    """Create one real project Skill so Native discovery is tested without bundled fixtures."""

    skill_root = workspace / ".agents" / "skills" / SKILL_NAME
    skill_root.mkdir(parents=True)
    document = skill_root / "SKILL.md"
    document.write_text(
        "---\n"
        f"name: {SKILL_NAME}\n"
        f"description: {SKILL_DESCRIPTION}.\n"
        "---\n"
        "<!-- @author kongweiguang -->\n\n"
        "# Native smoke Skill\n\n"
        "Do not perform a model turn.\n",
        encoding="utf-8",
        newline="\n",
    )
    return document


def configuration_replace_frame(
    expected_version: str,
    provider_endpoint: str | None = None,
    mcp_endpoint: str | None = None,
) -> dict[str, Any]:
    """通过 v1 replace CAS 构造严格的本地探针配置文档。

    无法提供 loopback 探针的调用方使用合同测试所需的空目录；生产 smoke 在发送前必须同时
    提供 Provider 与 MCP endpoint。
    """

    document = configuration_document(provider_endpoint, mcp_endpoint) \
        if provider_endpoint and mcp_endpoint else {
            "schema_version": 1,
            "config_revision": 0,
            "default_access_mode": "full_access",
            "default_provider_id": None,
            "default_model_id": None,
            "default_reasoning_level": None,
            "providers": [],
            "mcp_servers": [],
            "skills": [],
        }

    return {
        "jsonrpc": "2.0",
        "id": "c:config-write",
        "method": "configuration/replace",
        "params": {
            "scope": "user",
            "expectedVersion": expected_version,
            "document": document,
        },
    }


def credential_set_frame(expected_version: str) -> dict[str, Any]:
    """使用确定性的测试 Secret 验证单向 credential/set 边界。"""

    return {
        "jsonrpc": "2.0",
        "id": "c:credential-set",
        "method": "credential/set",
        "params": {"credentialId": CREDENTIAL_ID, "secret": SMOKE_SECRET, "expectedVersion": expected_version},
    }


def credential_delete_frame(expected_version: str) -> dict[str, Any]:
    """关闭前使用 credential/set 返回的 CAS 版本删除 smoke 凭据。"""

    return {
        "jsonrpc": "2.0",
        "id": "c:credential-delete",
        "method": "credential/delete",
        "params": {"credentialId": CREDENTIAL_ID, "expectedVersion": expected_version},
    }


def thread_create_frame(
    workspace: Path,
    *,
    frame_id: str = "c:thread",
    title: str = "Native activation smoke",
) -> dict[str, Any]:
    """Persist an isolated probe Thread while Java retains canonical workspace identity.

    Distinct probe identities prevent an earlier Tool result from changing the deterministic
    loopback Provider route selected for a later lifecycle probe.
    """

    params: dict[str, Any] = {
        "cwd": str(workspace.resolve()),
        "title": title,
        "providerId": PROVIDER_ID,
        "modelId": MODEL_ID,
        "reasoningLevel": None,
        "accessMode": "approval_required",
        "collaborationMode": "default",
    }
    return {
        "jsonrpc": "2.0",
        "id": frame_id,
        "method": "thread/create",
        "params": params,
    }


def mcp_list_frame() -> dict[str, Any]:
    """Request the redacted MCP catalog projection used as the transport probe."""

    return {"jsonrpc": "2.0", "id": "c:mcp-list", "method": "mcp/list", "params": {}}


def mcp_test_frame() -> dict[str, Any]:
    """Run the bounded MCP initialize/tools-list probe through the production catalog."""

    return {"jsonrpc": "2.0", "id": "c:mcp-test", "method": "mcp/test", "params": {"mcpId": MCP_ID}}


def mcp_tools_read_frame() -> dict[str, Any]:
    """Read one MCP schema page so networknt validation is exercised by the live generation."""

    return {
        "jsonrpc": "2.0", "id": "c:mcp-tools", "method": "mcp/list-tools",
        "params": {"mcpId": MCP_ID},
    }


def turn_start_frame(thread_id: str, text: str) -> dict[str, Any]:
    """Start a bounded workspace Turn through the public v1 RPC rather than a private test hook."""

    return {
        "jsonrpc": "2.0", "id": "c:turn-start", "method": "turn/start",
        "params": {
            "threadId": thread_id, "content": [{"type": "text", "text": text}],
            "deadlineMs": 15_000,
        },
    }


def turn_cancel_frame(turn_id: str, expected_revision: int) -> dict[str, Any]:
    """Cancel one admitted Turn with its observed revision so cancellation remains CAS-bound."""

    return {
        "jsonrpc": "2.0", "id": "c:turn-cancel", "method": "turn/cancel",
        "params": {"turnId": turn_id, "expectedThreadRevision": expected_revision},
    }


def approval_response_frame(approval_id: str, turn_id: str, expected_revision: int) -> dict[str, Any]:
    """Approve exactly the fixture shell call before immediately exercising cancellation."""

    return {
        "jsonrpc": "2.0", "id": "c:approval", "method": "approval/respond",
        "params": {
            "approvalId": approval_id, "turnId": turn_id, "decision": "approve",
            "expectedThreadRevision": expected_revision,
        },
    }


def thread_read_frame(thread_id: str) -> dict[str, Any]:
    """Read the durable Thread projection before or after a process restart."""

    return {"jsonrpc": "2.0", "id": "c:thread-read", "method": "thread/read", "params": {"threadId": thread_id}}


def thread_rename_frame(thread_id: str, expected_revision: int) -> dict[str, Any]:
    """Take manual title ownership so the Provider request-count probe has exactly one producer."""

    return {
        "jsonrpc": "2.0",
        "id": "c:thread-rename",
        "method": "thread/rename",
        "params": {
            "threadId": thread_id,
            "title": "Native activation smoke",
            "expectedThreadRevision": expected_revision,
        },
    }


def require_known_context_usage(
    snapshot: dict[str, Any], expected_turn_id: str, expected_model_round: int = 1,
) -> dict[str, Any]:
    """Require the latest exact request Usage so an older KNOWN record cannot mask current state."""

    usage = snapshot.get("contextUsage")
    expected = {
        "turnId": expected_turn_id,
        "modelRound": expected_model_round,
        "purpose": "assistant",
        "certainty": "known",
        "inputTokens": 5,
        "outputTokens": 5,
        "totalTokens": 10,
    }
    if not isinstance(usage, dict) or any(usage.get(key) != value for key, value in expected.items()):
        raise RuntimeError("Thread recovery did not return the exact persisted Provider Usage")
    measured_at = usage.get("measuredAt")
    if not isinstance(measured_at, str) or not measured_at:
        raise RuntimeError("persisted Provider Usage has no measurement timestamp")
    return usage


def sanitized_environment() -> dict[str, str]:
    """Remove credential-like variables and Java discovery variables from the child environment.

    A native executable must not depend on a developer JDK or inherit model credentials. Retaining
    ordinary platform variables preserves Windows loader and macOS process behavior while the
    name-based filter prevents CI secrets from entering the smoke process.
    """

    environment = dict(os.environ)
    for key in list(environment):
        upper = key.upper()
        if key == "JAVA_HOME" or any(part in upper for part in SECRET_NAME_PARTS):
            environment.pop(key, None)
    environment["JA_NATIVE_SMOKE"] = "1"
    return environment


class BinaryStreamCollector:
    """Collect one child pipe without allowing a noisy sidecar to exceed the smoke bound."""

    def __init__(self, stream: Any) -> None:
        """Keep one binary pipe's bounded capture and queue separate from the handshake state."""

        self._stream = stream
        self.lines = Queue()
        self.chunks: list[bytes] = []
        self.total_bytes = 0
        self.overflow = False
        self.error: BaseException | None = None
        self._thread = threading.Thread(target=self._consume, daemon=True)

    def start(self) -> None:
        """Start a dedicated reader because Windows does not support select() on anonymous pipes."""

        self._thread.start()

    def _consume(self) -> None:
        """Read bounded binary lines and publish them to the handshake reader in arrival order."""

        try:
            while True:
                line = self._stream.readline(MAX_OUTPUT_BYTES + 1)
                if not line:
                    break
                self.total_bytes += len(line)
                if self.total_bytes > MAX_OUTPUT_BYTES:
                    self.overflow = True
                    break
                self.chunks.append(line)
                self.lines.put(line)
        except BaseException as failure:  # pragma: no cover - platform pipe failures are external
            self.error = failure
        finally:
            self.lines.put(None)

    def join(self, timeout: float) -> None:
        """Join the reader only for the remaining smoke deadline so pipe cleanup cannot hang CI."""

        self._thread.join(max(0.0, timeout))

    def bytes(self) -> bytes:
        """Return the captured bytes after the reader has reached EOF or the hard cap."""

        return b"".join(self.chunks)


class LoopbackProvider:
    """Serve one deterministic OpenAI Responses stream without any external network access."""

    def __init__(self) -> None:
        """Bind an ephemeral IPv4 loopback listener so provider evidence cannot leave the host."""

        owner = self

        class Handler(BaseHTTPRequestHandler):
            """Keep HTTP logging and request-body retention out of the smoke process."""

            protocol_version = "HTTP/1.1"

            def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                """Serve only the bounded generation path so remote token counting fails visibly."""

                if self.path != "/v1/responses":
                    self.send_error(404)
                    return
                length = int(self.headers.get("Content-Length", "0"))
                if length < 0 or length > 16 * 1024 * 1024:
                    self.send_error(413)
                    return
                try:
                    request = json.loads(self.rfile.read(length))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    self.send_error(400)
                    return
                if not isinstance(request, dict):
                    self.send_error(400)
                    return
                owner.request_count += 1
                input_items = request.get("input")
                tools = request.get("tools")
                has_tool_result = isinstance(input_items, list) and any(
                    isinstance(item, dict) and item.get("type") == "function_call_output"
                    for item in input_items
                )
                advertises_shell = isinstance(tools, list) and any(
                    isinstance(tool, dict) and tool.get("name") == "shell"
                    for tool in tools
                )
                # Dynamic guidance now lives in Provider instructions, so input contains only
                # durable conversation messages. Automatic title requests may contain the same
                # user text but advertise no tools, so capability presence is also required.
                prompt = json.dumps(input_items or [], ensure_ascii=False)
                if has_tool_result:
                    stream = owner.text_stream("JA_NATIVE_SHELL_CANCELLED")
                elif advertises_shell and "stdin eof" in prompt.casefold():
                    stream = owner.shell_stream(SHELL_STDIN_EOF_COMMAND, SHELL_STDIN_EOF_CALL_ID)
                elif advertises_shell and "shell" in prompt.casefold():
                    stream = owner.shell_stream(SHELL_COMMAND, SHELL_CANCEL_CALL_ID)
                else:
                    stream = owner.text_stream(SMOKE_TEXT)
                payload = stream.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                for offset in range(0, len(payload), 7):
                    self.wfile.write(payload[offset:offset + 7])
                    self.wfile.flush()

            def log_message(self, format: str, *args: Any) -> None:
                """Suppress BaseHTTPRequestHandler diagnostics so paths and headers cannot leak."""

                del format, args

        self.request_count = 0
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def endpoint(self) -> str:
        """Return the provider base path consumed by the Ja Responses adapter."""

        return f"http://127.0.0.1:{self.server.server_port}/v1"

    @staticmethod
    def _response(response_id: str, status: str, output: list[dict[str, Any]], usage: bool) -> dict[str, Any]:
        """Build the typed Responses envelope with exact non-negative token accounting."""

        response: dict[str, Any] = {
            "id": response_id,
            "created_at": 0.0,
            "model": "native-smoke-model",
            "object": "response",
            "output": output,
            "parallel_tool_calls": True,
            "tool_choice": "auto",
            "tools": [],
            "status": status,
        }
        if usage:
            response["usage"] = {
                "input_tokens": 5,
                "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                "output_tokens": 5,
                "output_tokens_details": {"reasoning_tokens": 0},
                "total_tokens": 10,
            }
        return response

    @staticmethod
    def _event(event_type: str, sequence: int, payload: dict[str, Any]) -> str:
        """Encode one strict SSE event while retaining no provider request or credential bytes."""

        body = {"type": event_type, "sequence_number": sequence, **payload}
        return f"event: {event_type}\ndata: {json.dumps(body, ensure_ascii=False, separators=(',', ':'))}\n\n"

    def text_stream(self, text: str) -> str:
        """Return a complete public text stream for the provider HTTP/SSE gate."""

        response_id = f"resp_native_{self.request_count}"
        final_item = {
            "id": "message_native",
            "type": "message",
            "role": "assistant",
            "status": "completed",
            "content": [{"type": "output_text", "text": text, "annotations": [], "logprobs": []}],
        }
        return "".join((
            self._event("response.created", 0, {"response": self._response(response_id, "in_progress", [], False)}),
            self._event("response.output_text.delta", 1, {
                "content_index": 0, "delta": text, "item_id": "message_native",
                "logprobs": [], "output_index": 0,
            }),
            self._event("response.output_text.done", 2, {
                "content_index": 0, "item_id": "message_native", "output_index": 0, "text": text,
            }),
            self._event("response.completed", 3, {
                "response": self._response(response_id, "completed", [final_item], True),
            }),
        ))

    def shell_stream(self, command: str, call_id: str) -> str:
        """Return one globally unique shell Tool identity for a deterministic lifecycle probe."""

        response_id = f"resp_native_{self.request_count}"
        item_id = f"item_{call_id}"
        arguments = json.dumps(
            {"command": command}, separators=(",", ":")
        )
        item = {"id": item_id, "type": "function_call", "call_id": call_id,
                "name": "shell", "arguments": arguments}
        return "".join((
            self._event("response.created", 0, {"response": self._response(response_id, "in_progress", [], False)}),
            self._event("response.output_item.added", 1, {
                "output_index": 0, "item": {**item, "arguments": ""},
            }),
            self._event("response.function_call_arguments.delta", 2, {
                "item_id": item_id, "delta": arguments, "output_index": 0,
            }),
            self._event("response.function_call_arguments.done", 3, {
                "item_id": item_id, "name": "shell", "arguments": arguments, "output_index": 0,
            }),
            self._event("response.output_item.done", 4, {"output_index": 0, "item": item}),
            self._event("response.completed", 5, {
                "response": self._response(response_id, "completed", [item], True),
            }),
        ))

    def close(self) -> None:
        """Stop the loopback listener and wait briefly so no fixture thread survives the gate."""

        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class LoopbackMcp:
    """Serve one bounded Streamable HTTP MCP catalog used by mcp and networknt probes."""

    def __init__(self) -> None:
        """Bind only to IPv4 loopback and keep the MCP response vocabulary minimal."""

        owner = self

        class Handler(BaseHTTPRequestHandler):
            """Handle JSON-RPC MCP requests without logging headers or request bodies."""

            protocol_version = "HTTP/1.1"

            def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                """Return initialize, tools/list, and tools/call responses under a byte cap."""

                if self.path != "/mcp":
                    self.send_error(404)
                    return
                length = int(self.headers.get("Content-Length", "0"))
                if length < 0 or length > 2 * 1024 * 1024:
                    self.send_error(413)
                    return
                try:
                    request = json.loads(self.rfile.read(length))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    self.send_error(400)
                    return
                if not isinstance(request, dict):
                    self.send_error(400)
                    return
                owner.request_count += 1
                # Keep only method shape and correlation presence; bodies and headers may carry
                # credentials or workspace data and must never enter smoke diagnostics.
                owner.request_methods.append((str(request.get("method")), "id" in request))
                if "id" not in request:
                    self._send_empty(202)
                    return
                method = request.get("method")
                result: dict[str, Any]
                if method == "initialize":
                    result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                              "serverInfo": {"name": "native-smoke-mcp", "version": "1"}}
                elif method == "tools/list":
                    result = {"tools": [{"name": "echo", "description": "bounded local echo",
                                         "inputSchema": {"type": "object", "properties": {
                                             "text": {"type": "string", "minLength": 1}},
                                             "required": ["text"], "additionalProperties": False}}]}
                elif method == "tools/call":
                    result = {"content": [{"type": "text", "text": "ok"}], "isError": False}
                else:
                    result = {}
                payload = json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result},
                                     separators=(",", ":")).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("MCP-Protocol-Version", "2025-06-18")
                self.send_header("Mcp-Session-Id", "native-smoke-session")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_DELETE(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                """Accept the client-owned session close without retaining session identifiers."""

                if self.path != "/mcp":
                    self.send_error(404)
                    return
                self._send_empty(204)

            def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                """Reject optional reconnect streams explicitly so the fixture cannot hang a probe."""

                self._send_empty(405)

            def _send_empty(self, status: int) -> None:
                """Frame empty HTTP/1.1 responses explicitly so OkHttp never waits for an ambiguous EOF."""

                self.send_response(status)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.close_connection = True
                self.end_headers()

            def log_message(self, format: str, *args: Any) -> None:
                """Suppress HTTP server logging because it can contain loopback paths or headers."""

                del format, args

        self.request_count = 0
        self.request_methods: list[tuple[str, bool]] = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def endpoint(self) -> str:
        """Return the streamable HTTP endpoint embedded in the Java-owned MCP document."""

        return f"http://127.0.0.1:{self.server.server_port}/mcp"

    def close(self) -> None:
        """Stop the MCP listener and join its accept thread within the smoke deadline."""

        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def probe_passed(**evidence: Any) -> dict[str, Any]:
    """Convert observed runtime facts into the only green production-verification subgate shape."""

    return {"status": "passed", "passed": True, **evidence}


def probe_blocked(reason: str, **evidence: Any) -> dict[str, Any]:
    """Record a dynamically unavailable probe without treating it as an optional capability."""

    return {"status": "blocked", "passed": False, "reason": reason, **evidence}


def probe_failed(reason: str, **evidence: Any) -> dict[str, Any]:
    """Record a reached probe contradiction as a hard failed subgate."""

    return {"status": "failed", "passed": False, "reason": reason, **evidence}


def terminate_process(process: subprocess.Popen[Any]) -> None:
    """Terminate the whole smoke process group after a deadline without leaving a child behind."""

    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        if os.name != "nt":
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
    process.kill()
    process.wait(timeout=5)


def sidecar_command(
    executable: Path,
    command_prefix: list[str] | None,
    home_directory: Path,
    runtime_data_directory: Path,
    run_directory: Path,
    log_directory: Path,
) -> list[str]:
    """Build the only production App Server command without a retired runtime selector.

    The App Server now has one composition graph, so carrying a production/fake selector would
    recreate a compatibility surface and makes the current Native Image reject an otherwise valid
    smoke invocation. Directory arguments remain canonical unpadded Base64URL values so Windows
    Unicode paths never depend on the active console encoding.
    """

    def encoded_path(directory: Path) -> str:
        """Encode one canonical Unicode root through the unpadded Base64URL argv contract."""

        return base64.urlsafe_b64encode(str(directory.resolve()).encode("utf-8")).decode(
            "ascii"
        ).rstrip("=")

    return [
        str(executable),
        *(command_prefix or []),
        f"--home-dir-base64={encoded_path(home_directory)}",
        f"--data-dir-base64={encoded_path(runtime_data_directory)}",
        f"--run-dir-base64={encoded_path(run_directory)}",
        f"--log-dir-base64={encoded_path(log_directory)}",
    ]


def load_schema_validator() -> jsonschema.Draft202012Validator:
    """Load the repository-owned v1 schema once so every outgoing smoke request is contract-checked."""

    try:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        return jsonschema.Draft202012Validator(schema)
    except (OSError, json.JSONDecodeError, jsonschema.SchemaError) as failure:
        raise RuntimeError("v1 JSON Schema is unavailable or malformed") from failure


def send_frame(
    process: subprocess.Popen[Any],
    frame: dict[str, Any],
    validator: jsonschema.Draft202012Validator | None = None,
) -> None:
    """Validate and write one UTF-8 JSONL frame so schema drift cannot hide behind a live sidecar."""

    if validator is not None:
        try:
            validator.validate(frame)
        except jsonschema.ValidationError as failure:
            raise RuntimeError("native smoke emitted a frame outside the v1 schema") from failure

    if process.stdin is None:
        raise RuntimeError("native sidecar stdin is unavailable")
    payload = json.dumps(frame, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
    try:
        process.stdin.write(payload)
        process.stdin.flush()
    except (BrokenPipeError, OSError) as failure:
        raise RuntimeError("native sidecar stdin closed during handshake") from failure


def next_line(collector: BinaryStreamCollector, deadline: float) -> bytes | None:
    """Read one binary JSONL line with the shared process deadline and bounded queue."""

    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise RuntimeError("native sidecar smoke exceeded its deadline")
    try:
        line = collector.lines.get(timeout=remaining)
    except Empty as failure:
        raise RuntimeError("native sidecar smoke exceeded its deadline") from failure
    if line is None and collector.error is not None:
        raise RuntimeError("native sidecar stdout reader failed") from collector.error
    if line is None and collector.overflow:
        raise RuntimeError("native sidecar smoke output exceeded the hard limit")
    return line


def decode_frame(
    line: bytes,
    validator: jsonschema.Draft202012Validator | None = None,
) -> dict[str, Any]:
    """Decode one UTF-8 object and validate server frames before any event can affect a gate."""

    try:
        document = json.loads(line.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise RuntimeError("native sidecar emitted a non-JSON stdout line") from failure
    if not isinstance(document, dict):
        raise RuntimeError("native sidecar emitted a non-object JSON frame")
    if validator is not None:
        try:
            validator.validate(document)
        except jsonschema.ValidationError as failure:
            method = document.get("method") if isinstance(document.get("method"), str) else "response"
            location = "/".join(str(part) for part in failure.absolute_path) or "root"
            params = document.get("params")
            keys = ",".join(sorted(params)) if isinstance(params, dict) else "none"
            rules = ",".join(
                f"{item.validator}@{'/'.join(str(part) for part in item.absolute_path) or 'root'}"
                for item in failure.context[:6]
            ) or str(failure.validator)
            raise RuntimeError(
                f"native sidecar emitted an invalid {method} frame at {location}; params={keys}; rules={rules}"
            ) from failure
    return document


def read_until(
    collector: BinaryStreamCollector,
    documents: list[dict[str, Any]],
    deadline: float,
    *,
    validator: jsonschema.Draft202012Validator | None = None,
    frame_id: str | None = None,
    method: str | None = None,
    predicate: Any = None,
) -> dict[str, Any]:
    """Read frames until one correlated response or predicate match arrives, retaining events."""

    for _ in range(128):
        line = next_line(collector, deadline)
        if line is None:
            raise RuntimeError("native sidecar closed stdout before handshake completed")
        document = decode_frame(line, validator)
        documents.append(document)
        matches_id = frame_id is None or document.get("id") == frame_id
        matches_method = method is None or document.get("method") == method
        matches_predicate = predicate is None or predicate(document)
        if matches_id and matches_method and matches_predicate:
            return document
    raise RuntimeError("native sidecar emitted too many frames before the expected handshake frame")


def read_until_turn_event(
    collector: BinaryStreamCollector,
    documents: list[dict[str, Any]],
    deadline: float,
    turn_id: str,
    expected_method: str,
    *,
    validator: jsonschema.Draft202012Validator | None = None,
) -> dict[str, Any]:
    """Stop on the expected Turn event or its terminal, preventing a failed Turn from consuming the global budget."""

    event = read_until(
        collector,
        documents,
        deadline,
        validator=validator,
        predicate=lambda frame: frame.get("method") in (expected_method, "turn/terminal")
        and isinstance(frame.get("params"), dict)
        and frame["params"].get("turnId") == turn_id,
    )
    if event.get("method") == "turn/terminal":
        raise RuntimeError(f"Turn {terminal_failure_label(event)} before {expected_method}")
    return event


def terminal_failure_label(event: dict[str, Any]) -> str:
    """Expose only schema-bounded state/errorCode while keeping terminal messages out of smoke output."""

    params = event.get("params")
    if not isinstance(params, dict):
        return "settled as unknown"
    state = params.get("state")
    stable_state = state if isinstance(state, str) else "unknown"
    error_code = params.get("errorCode")
    suffix = f" ({error_code})" if isinstance(error_code, str) else ""
    return f"settled as {stable_state}{suffix}"


def latest_tool_error_code(documents: list[dict[str, Any]], turn_id: str) -> str | None:
    """Return only a bounded stable Tool error code for one Turn, never Tool content or arguments."""

    for document in reversed(documents):
        if document.get("method") != "tool/batch-committed":
            continue
        params = document.get("params")
        if not isinstance(params, dict) or params.get("turnId") != turn_id:
            continue
        results = params.get("results")
        if not isinstance(results, list):
            return None
        for result in results:
            error_code = result.get("errorCode") if isinstance(result, dict) else None
            if isinstance(error_code, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,127}", error_code):
                return error_code
        return None
    return None


def drain_stdout(
    collector: BinaryStreamCollector,
    documents: list[dict[str, Any]],
    deadline: float,
    validator: jsonschema.Draft202012Validator | None = None,
) -> None:
    """Drain trailing JSONL events after shutdown so the reader thread and child pipe fully close."""

    while True:
        line = next_line(collector, deadline)
        if line is None:
            return
        document = decode_frame(line, validator)
        documents.append(document)


def ready_frame(document: dict[str, Any]) -> bool:
    """Identify only the ready event carrying the exact challenge issued by the smoke client."""

    params = document.get("params")
    return (
        document.get("method") == "runtime/status-changed"
        and isinstance(params, dict)
        and params.get("status") == "ready"
        and params.get("readyToken") == READY_TOKEN
        and isinstance(params.get("serverInstanceId"), str)
        and isinstance(params.get("eventId"), str)
        and isinstance(params.get("occurredAt"), str)
        and isinstance(params.get("generation"), int)
        and params["generation"] > 0
    )


class NativeRpcRejection(RuntimeError):
    """RPC 失败仅保留固定操作名和机器码，不能把远端消息或数据路径带进 CI 日志。"""

    def __init__(self, operation: str, error_code: Any) -> None:
        """即使调用方绕过 schema 验证，也只接受有界且不含路径/控制字符的诊断字段。"""
        self.operation = operation if re.fullmatch(r"[A-Za-z][A-Za-z0-9 -]{0,63}", operation) else "unknown operation"
        self.error_code = error_code if isinstance(error_code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", error_code) else "UNCLASSIFIED_RPC_ERROR"
        super().__init__(f"native sidecar rejected {self.operation} ({self.error_code})")


def require_success(document: dict[str, Any], operation: str) -> dict[str, Any]:
    """仅暴露 Schema 已验证的稳定 errorCode，避免错误载荷进入 Native 诊断。"""

    result = document.get("result")
    if isinstance(result, dict):
        return result
    error = document.get("error")
    data = error.get("data") if isinstance(error, dict) else None
    error_code = data.get("errorCode") if isinstance(data, dict) else None
    raise NativeRpcRejection(operation, error_code)


def sanitized_documents(documents: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Remove workspace paths before scanning captured output for leaks."""

    sanitized = json.loads(json.dumps(documents, ensure_ascii=False))
    for document in sanitized:
        if document.get("id") == "c:workspace":
            result = document.get("result")
            if isinstance(result, dict):
                result.pop("root", None)
    return sanitized


def native_probe_diagnostics(documents: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """只保留状态、结果与 Usage 数值，便于定位门禁失败且不泄露身份或业务内容。"""

    diagnostics: list[dict[str, Any]] = []
    for document in documents:
        method = document.get("method")
        params = document.get("params")
        occurred_at = params.get("occurredAt") if isinstance(params, dict) else None
        if method == "tool/batch-committed" and isinstance(params, dict):
            results = params.get("results")
            diagnostics.append({
                "method": method,
                "occurredAt": occurred_at,
                "outcomes": [
                    result.get("outcome")
                    for result in results
                    if isinstance(result, dict) and isinstance(result.get("outcome"), str)
                ] if isinstance(results, list) else [],
            })
        elif method == "turn/terminal" and isinstance(params, dict):
            diagnostics.append({
                "method": method,
                "occurredAt": occurred_at,
                "state": params.get("state"),
                "errorCode": params.get("errorCode"),
            })
        elif method in ("turn/state-changed", "approval/requested", "approval/resolved", "tool/started") \
                and isinstance(params, dict):
            diagnostics.append({
                "method": method,
                "occurredAt": occurred_at,
                "state": params.get("state"),
            })
        elif document.get("id") == "c:thread-read":
            result = document.get("result")
            usage = result.get("contextUsage") if isinstance(result, dict) else None
            diagnostics.append({
                "method": "thread/read",
                "usage": {
                    key: usage.get(key)
                    for key in (
                        "requestOrdinal", "modelRound", "purpose", "certainty",
                        "inputTokens", "outputTokens", "totalTokens",
                    )
                } if isinstance(usage, dict) else None,
            })
    return diagnostics[-32:]


def auth_acl_evidence(auth_path: Path) -> dict[str, Any]:
    """按平台验证真实权限：Windows 检查 DACL，POSIX 检查普通文件、owner 与精确 0600。"""

    if os.name != "nt":
        try:
            attributes = auth_path.lstat()
            owner_only = stat.S_ISREG(attributes.st_mode) and stat.S_IMODE(attributes.st_mode) == 0o600 \
                and attributes.st_uid == os.getuid()
            return {
                "status": "passed" if owner_only else "blocked",
                "currentUserOnly": owner_only,
                "mode": oct(stat.S_IMODE(attributes.st_mode)),
                "reason": None if owner_only else "POSIX auth permissions are not owner-only 0600",
            }
        except OSError:
            return {"status": "blocked", "reason": "POSIX auth permissions could not be inspected"}
    if not auth_path.is_file():
        return {"status": "blocked", "reason": "auth.json was not produced by the credential lifecycle"}
    try:
        identity = subprocess.run(
            ["whoami"], capture_output=True, text=True, check=False, timeout=5,
        ).stdout.strip()
        acl = subprocess.run(
            ["icacls", str(auth_path)], capture_output=True, text=True, check=False, timeout=5,
        )
        if acl.returncode != 0:
            return {"status": "blocked", "reason": "icacls could not inspect auth.json"}
        lines = [line.strip() for line in f"{acl.stdout}\n{acl.stderr}".splitlines() if line.strip()]
        # icacls prints the first ACE on the target-path line and indents only later entries.
        ace_lines = [line for line in lines if ":(" in line]
        inherited = any("(I)" in line for line in ace_lines)
        current_user_ace = bool(identity) and any(identity.casefold() in line.casefold() for line in ace_lines)
        only_current_user = len(ace_lines) == 1 and current_user_ace
        return {
            "status": "passed" if not inherited and only_current_user else "blocked",
            "inheritanceRemoved": not inherited,
            "currentUserOnly": only_current_user,
            "reason": None if not inherited and only_current_user else "DACL is not provably current-user-only",
        }
    except (OSError, subprocess.SubprocessError):
        return {"status": "blocked", "reason": "Windows ACL inspection failed closed"}


def run_smoke(
    executable: Path,
    data_directory: Path,
    timeout_seconds: float,
    command_prefix: list[str] | None = None,
    expected_sha256: str | None = None,
    expected_size: int | None = None,
    expected_mtime_ns: int | None = None,
) -> dict[str, Any]:
    """Execute the v1 lifecycle and every reachable local probe with bounded cleanup.

    The real executable path receives loopback Provider/MCP servers so transport, schema, cancel,
    and recovery gates exercise production RPC.  A supplied command prefix denotes a contract
    fixture; it is intentionally reported as dynamically blocked for probes that fixture cannot
    expose, keeping unit smoke useful without turning a mock into Native evidence.
    """

    if not executable.is_file():
        raise RuntimeError("native executable is missing")
    artifact = executable_identity(executable)
    expected_identity_matched = require_expected_identity(
        artifact, expected_sha256, expected_size, expected_mtime_ns
    )
    schema_validator = load_schema_validator()
    data_directory.mkdir(parents=True, exist_ok=True)
    owned_root = Path(tempfile.mkdtemp(prefix="ja-native-验证-", dir=data_directory))
    home_directory = owned_root / "home-家"
    runtime_data_directory = owned_root / "data-数据"
    run_directory = owned_root / "run-运行"
    log_directory = owned_root / "log-日志"
    workspace = owned_root / "workspace-工作区"
    for directory in (
        home_directory, runtime_data_directory, run_directory, log_directory, workspace
    ):
        directory.mkdir()
    agent_instructions = workspace / "AGENTS.md"
    if not agent_instructions.exists():
        # Harness intentionally warns when the workspace has no agent contract. Supplying a tiny
        # smoke-owned contract keeps stderr meaningful without weakening production logging.
        agent_instructions.write_text(
            "# Native smoke workspace\n\nDo not perform a model turn.\n",
            encoding="utf-8",
            newline="\n",
        )
    write_workspace_skill(workspace)

    provider_probe = LoopbackProvider() if command_prefix is None else None
    mcp_probe = LoopbackMcp() if command_prefix is None else None
    provider_endpoint = provider_probe.endpoint if provider_probe is not None else None
    mcp_endpoint = mcp_probe.endpoint if mcp_probe is not None else None

    creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    command = sidecar_command(
        executable,
        command_prefix,
        home_directory,
        runtime_data_directory,
        run_directory,
        log_directory,
    )
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
            env=sanitized_environment(),
            creationflags=creationflags,
            start_new_session=os.name != "nt",
        )
    except BaseException:
        if provider_probe is not None:
            provider_probe.close()
        if mcp_probe is not None:
            mcp_probe.close()
        shutil.rmtree(owned_root)
        raise
    stdout_collector = BinaryStreamCollector(process.stdout)
    stderr_collector = BinaryStreamCollector(process.stderr)
    stdout_collector.start()
    stderr_collector.start()
    documents: list[dict[str, Any]] = []
    sent_frames: list[dict[str, Any]] = []
    deadline = time.monotonic() + timeout_seconds

    def send(frame: dict[str, Any]) -> None:
        """Keep schema-validated outbound frames countable without retaining secret-bearing responses."""

        sent_frames.append({"method": frame.get("method"), "id": frame.get("id")})
        send_frame(process, frame, schema_validator)

    try:
        send(initialize_frame())
        initialize = read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:init")
        result = require_initialize_identity(initialize.get("result"), EXPECTED_ENGINE_VERSION)
        runtime = result["runtime"]

        send(initialized_frame())
        read_until(stdout_collector, documents, deadline, validator=schema_validator, predicate=ready_frame)

        send(config_read_frame())
        config_response = read_until(
            stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:config-read"
        )
        config = require_success(config_response, "configuration read")
        cas = config.get("cas") if isinstance(config, dict) else None
        expected_config_version = cas.get("userVersion") if isinstance(cas, dict) else None
        expected_credential_version = cas.get("credentialVersion") if isinstance(cas, dict) else None
        if expected_config_version != "cfg_missing" or expected_credential_version != "cfg_missing":
            raise RuntimeError("fresh native smoke home did not report cfg_missing CAS versions")

        send(configuration_replace_frame(expected_config_version, provider_endpoint, mcp_endpoint))
        configuration_replace = require_success(
            read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:config-write"),
            "configuration replace",
        )
        if configuration_replace.get("accepted") is not True or configuration_replace.get("scope") != "user" \
                or not isinstance(configuration_replace.get("version"), str):
            raise RuntimeError("configuration replace returned an invalid v1 projection")

        send(credential_set_frame(expected_credential_version))
        credential_set = require_success(
            read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:credential-set"),
            "credential set",
        )
        if credential_set.get("accepted") is not True or credential_set.get("configured") is not True \
                or credential_set.get("credentialId") != CREDENTIAL_ID:
            raise RuntimeError("credential set returned an invalid redacted projection")
        acl = auth_acl_evidence(home_directory / "auth.json")

        send(workspace_open_frame(workspace))
        workspace_response = read_until(
            stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:workspace"
        )
        workspace_result = require_success(workspace_response, "workspace binding")
        workspace_id = workspace_result.get("workspaceId")
        if not isinstance(workspace_id, str) or not workspace_id.startswith("ws_"):
            raise RuntimeError("native sidecar returned an invalid workspace identity")

        send(workspace_trust_frame(workspace_id))
        workspace_trust = require_success(
            read_until(
                stdout_collector,
                documents,
                deadline,
                validator=schema_validator,
                frame_id="c:workspace-trust",
            ),
            "workspace trust",
        )
        if workspace_trust.get("accepted") is not True:
            raise RuntimeError("native sidecar did not accept workspace trust")

        send(health_read_frame())
        health_response = read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:health")
        health = require_success(health_response, "health read")
        components = health.get("components")
        component_states = {
            item.get("name"): item.get("status")
            for item in components if isinstance(item, dict)
        } if isinstance(components, list) else {}
        sqlite_ready = health.get("status") == "ready" and component_states.get("sqlite") == "healthy"
        kernel_ready = health.get("status") == "ready" and component_states.get("kernel") == "healthy"
        if not sqlite_ready or not kernel_ready:
            raise RuntimeError("native sidecar SQLite and Kernel health is not ready")

        send(skill_list_frame(workspace_id))
        skills_response = read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:skills")
        skills = require_success(skills_response, "Skill discovery").get("items")
        if not isinstance(skills, list) or not any(
            isinstance(skill, dict) and skill.get("name") == SKILL_NAME for skill in skills
        ):
            raise RuntimeError("native sidecar did not discover the workspace smoke Skill")

        mcp_evidence: dict[str, Any]
        networknt_evidence: dict[str, Any]
        if mcp_probe is None:
            mcp_evidence = probe_blocked("the supplied mock fixture has no mcp/list probe")
            networknt_evidence = probe_blocked("the supplied mock fixture has no MCP schema probe")
        else:
            try:
                send(mcp_list_frame())
                mcp_list = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:mcp-list"),
                    "MCP list",
                )
                servers = mcp_list.get("items")
                listed = [server for server in servers if isinstance(server, dict)] \
                    if isinstance(servers, list) else []
                if not any(server.get("mcpId") == MCP_ID for server in listed):
                    mcp_evidence = probe_blocked("the production generation did not expose the local MCP descriptor")
                    networknt_evidence = probe_blocked("the production generation exposed no MCP schema to validate")
                else:
                    send(mcp_test_frame())
                    mcp_test = require_success(
                        read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:mcp-test"),
                        "MCP test",
                    )
                    send(mcp_tools_read_frame())
                    mcp_tools = require_success(
                        read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:mcp-tools"),
                        "MCP tools read",
                    )
                    tools = mcp_tools.get("items")
                    valid_tools = [tool for tool in tools if isinstance(tool, dict)] \
                        if isinstance(tools, list) else []
                    mcp_ok = mcp_test.get("mcpId") == MCP_ID and mcp_test.get("status") == "available" \
                        and mcp_test.get("toolCount", 0) >= 1 and bool(valid_tools)
                    mcp_evidence = probe_passed(
                        listed=True, tested=mcp_ok, toolCount=len(valid_tools),
                    ) if mcp_ok else probe_failed("MCP test returned an incomplete health projection")
                    schema_ok = all(isinstance(tool.get("inputSchema"), dict) for tool in valid_tools)
                    networknt_evidence = probe_passed(
                        validatedToolSchemas=len(valid_tools),
                    ) if schema_ok and valid_tools else probe_failed(
                        "MCP discovery returned no networknt-validated Tool schema",
                    )
            except RuntimeError as failure:
                reason = str(failure)
                if "METHOD_NOT_FOUND" in reason or "mcp" in reason.casefold() and "not" in reason.casefold():
                    mcp_evidence = probe_blocked(f"production MCP probe is unavailable: {reason}")
                    networknt_evidence = probe_blocked("production MCP schema probe is unavailable")
                else:
                    mcp_evidence = probe_failed(f"production MCP probe failed: {reason}")
                    networknt_evidence = probe_failed("networknt probe could not complete after MCP failure")

        send(thread_create_frame(workspace))
        thread_response = read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:thread")
        thread = require_success(thread_response, "Thread persistence")
        if not isinstance(thread, dict) or not str(thread.get("threadId", "")).startswith("thr_"):
            raise RuntimeError("native sidecar did not persist a fresh-schema Thread")
        if thread.get("workspaceId") != workspace_id:
            raise RuntimeError("thread/create did not retain Java's workspace identity")
        if provider_probe is not None:
            thread_revision = thread.get("revision")
            if not isinstance(thread_revision, int):
                raise RuntimeError("thread/create did not return a revision")
            send(thread_rename_frame(thread["threadId"], thread_revision))
            thread = require_success(
                read_until(stdout_collector, documents, deadline, validator=schema_validator,
                           frame_id="c:thread-rename"),
                "manual Thread title ownership",
            )

        recovery_usage_thread_id: str | None = None
        recovery_usage_turn_id: str | None = None
        recovery_usage_model_round = 1
        if provider_probe is None:
            okhttp_evidence = probe_blocked("the supplied mock fixture has no Provider HTTP/SSE probe")
            shell_cancel_evidence = probe_blocked(
                "the supplied mock fixture has no shell approval/cancellation probe",
            )
            shell_stdin_eof_evidence = probe_blocked(
                "the supplied mock fixture has no shell stdin EOF probe",
            )
        else:
            try:
                send(turn_start_frame(thread["threadId"], "Reply with exactly JA_NATIVE_PROVIDER_TEXT."))
                text_accept = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:turn-start"),
                    "Provider text turn",
                )
                text_turn_id = text_accept.get("turnId")
                if not isinstance(text_turn_id, str) or not text_turn_id.startswith("turn_"):
                    raise RuntimeError("Provider text Turn returned an invalid identity")
                text_terminal = read_until(
                    stdout_collector, documents, deadline,
                    validator=schema_validator,
                    predicate=lambda frame: frame.get("method") == "turn/terminal"
                    and isinstance(frame.get("params"), dict)
                    and frame["params"].get("turnId") == text_turn_id,
                )
                text_events = [
                    frame for frame in documents
                    if frame.get("method") == "assistant/text-delta"
                    and isinstance(frame.get("params"), dict)
                    and frame["params"].get("turnId") == text_turn_id
                ]
                text_ok = text_terminal.get("params", {}).get("state") == "completed" \
                    and any(frame["params"].get("text") == SMOKE_TEXT for frame in text_events)
                text_turn_requests = provider_probe.request_count
                if not text_ok or text_turn_requests != 1:
                    raise RuntimeError(f"Provider text Turn {terminal_failure_label(text_terminal)}")
                send(thread_read_frame(thread["threadId"]))
                persisted_thread = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator,
                               frame_id="c:thread-read"),
                    "Provider Usage persistence",
                )
                persisted_usage = require_known_context_usage(persisted_thread, text_turn_id)
                recovery_usage_thread_id = thread["threadId"]
                recovery_usage_turn_id = text_turn_id
                okhttp_evidence = probe_passed(
                    requests=text_turn_requests,
                    estimateHttpRequests=0,
                    textTurn=text_turn_id,
                    usage={key: persisted_usage[key] for key in (
                        "certainty", "inputTokens", "outputTokens", "totalTokens",
                    )},
                )
            except RuntimeError as failure:
                reason = str(failure)
                if "METHOD_NOT_FOUND" in reason or "provider" in reason.casefold() and "not" in reason.casefold():
                    okhttp_evidence = probe_blocked(f"production Provider probe is unavailable: {reason}")
                else:
                    okhttp_evidence = probe_failed(f"production Provider probe failed: {reason}")

            shell_turn_id: str | None = None
            try:
                send(turn_start_frame(
                    thread["threadId"],
                    "Use the shell tool with the exact command and wait for approval, then report completion.",
                ))
                shell_accept = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:turn-start"),
                    "Provider shell turn",
                )
                shell_turn_id = shell_accept.get("turnId")
                if not isinstance(shell_turn_id, str) or not shell_turn_id.startswith("turn_"):
                    raise RuntimeError("Provider shell Turn returned an invalid identity")
                recovery_usage_thread_id = thread["threadId"]
                recovery_usage_turn_id = shell_turn_id
                approval = read_until_turn_event(
                    stdout_collector, documents, deadline,
                    shell_turn_id,
                    "approval/requested",
                    validator=schema_validator,
                )
                approval_params = approval.get("params", {})
                approval_id = approval_params.get("approvalId")
                approval_revision = approval_params.get("threadRevision")
                if not isinstance(approval_id, str) or not approval_id.startswith("appr_") \
                        or not isinstance(approval_revision, int):
                    raise RuntimeError("shell approval projection is incomplete")
                send(approval_response_frame(approval_id, shell_turn_id, approval_revision))
                approval_result = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:approval"),
                    "shell approval",
                )
                if not isinstance(approval_result.get("threadRevision"), int):
                    raise RuntimeError("approval response did not return a committed revision")
                started = read_until_turn_event(
                    stdout_collector, documents, deadline,
                    shell_turn_id,
                    "tool/started",
                    validator=schema_validator,
                )
                started_params = started.get("params", {})
                cancel_revision = started_params.get("threadRevision")
                if started_params.get("callId") != SHELL_CANCEL_CALL_ID \
                        or started_params.get("ordinal") != 0 \
                        or not isinstance(cancel_revision, int):
                    raise RuntimeError("shell started projection is incomplete")
                send(turn_cancel_frame(shell_turn_id, cancel_revision))
                cancel_result = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:turn-cancel"),
                    "shell cancellation",
                )
                terminal = read_until(
                    stdout_collector, documents, deadline,
                    validator=schema_validator,
                    predicate=lambda frame: frame.get("method") == "turn/terminal"
                    and isinstance(frame.get("params"), dict)
                    and frame["params"].get("turnId") == shell_turn_id,
                )
                state = terminal.get("params", {}).get("state")
                shell_cancel_evidence = probe_passed(
                    turnId=shell_turn_id, approvalId=approval_id,
                    startedRevision=cancel_revision,
                    cancelAccepted=cancel_result.get("accepted") is True,
                    terminalState=state,
                ) if cancel_result.get("accepted") is True and state == "cancelled" else probe_failed(
                    "shell approval/cancel did not settle the Turn as cancelled",
                )
            except RuntimeError as failure:
                reason = str(failure)
                tool_error = latest_tool_error_code(documents, shell_turn_id) \
                    if shell_turn_id is not None else None
                if tool_error is not None:
                    reason = f"{reason}; toolError={tool_error}"
                if "METHOD_NOT_FOUND" in reason or "provider" in reason.casefold() and "not" in reason.casefold():
                    shell_cancel_evidence = probe_blocked(
                        f"production shell cancellation probe is unavailable: {reason}",
                    )
                else:
                    shell_cancel_evidence = probe_failed(
                        f"production shell cancellation probe failed: {reason}",
                    )

            eof_turn_id: str | None = None
            try:
                send(thread_create_frame(
                    workspace,
                    frame_id="c:eof-thread",
                    title="Native stdin EOF smoke",
                ))
                eof_thread = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator,
                               frame_id="c:eof-thread"),
                    "shell stdin EOF Thread persistence",
                )
                if not isinstance(eof_thread, dict) \
                        or not str(eof_thread.get("threadId", "")).startswith("thr_") \
                        or eof_thread.get("workspaceId") != workspace_id \
                        or eof_thread.get("threadId") == thread["threadId"]:
                    raise RuntimeError("shell stdin EOF probe did not persist an isolated Thread")
                send(turn_start_frame(
                    eof_thread["threadId"],
                    "Use the shell tool to run the stdin EOF probe, then report completion.",
                ))
                eof_accept = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator,
                               frame_id="c:turn-start"),
                    "Provider shell stdin EOF turn",
                )
                eof_turn_id = eof_accept.get("turnId")
                if not isinstance(eof_turn_id, str) or not eof_turn_id.startswith("turn_"):
                    raise RuntimeError("Provider shell stdin EOF Turn returned an invalid identity")
                eof_approval = read_until_turn_event(
                    stdout_collector, documents, deadline,
                    eof_turn_id,
                    "approval/requested",
                    validator=schema_validator,
                )
                eof_approval_params = eof_approval.get("params", {})
                eof_approval_id = eof_approval_params.get("approvalId")
                eof_approval_revision = eof_approval_params.get("threadRevision")
                if not isinstance(eof_approval_id, str) or not eof_approval_id.startswith("appr_") \
                        or not isinstance(eof_approval_revision, int):
                    raise RuntimeError("shell stdin EOF approval projection is incomplete")
                send(approval_response_frame(eof_approval_id, eof_turn_id, eof_approval_revision))
                require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator,
                               frame_id="c:approval"),
                    "shell stdin EOF approval",
                )
                eof_started = read_until_turn_event(
                    stdout_collector, documents, deadline,
                    eof_turn_id,
                    "tool/started",
                    validator=schema_validator,
                )
                eof_batch = read_until_turn_event(
                    stdout_collector, documents, deadline,
                    eof_turn_id,
                    "tool/batch-committed",
                    validator=schema_validator,
                )
                eof_terminal = read_until(
                    stdout_collector, documents, deadline,
                    validator=schema_validator,
                    predicate=lambda frame: frame.get("method") == "turn/terminal"
                    and isinstance(frame.get("params"), dict)
                    and frame["params"].get("turnId") == eof_turn_id,
                )
                eof_started_params = eof_started.get("params", {})
                eof_results = eof_batch.get("params", {}).get("results", [])
                eof_result = eof_results[0] if len(eof_results) == 1 else {}
                eof_ok = eof_started_params.get("callId") == SHELL_STDIN_EOF_CALL_ID \
                    and eof_started_params.get("ordinal") == 0 \
                    and isinstance(eof_result, dict) \
                    and eof_result.get("callId") == SHELL_STDIN_EOF_CALL_ID \
                    and eof_result.get("ordinal") == 0 \
                    and eof_result.get("outcome") == "succeeded" \
                    and eof_terminal.get("params", {}).get("state") == "completed"
                recovery_usage_thread_id = eof_thread["threadId"]
                recovery_usage_turn_id = eof_turn_id
                recovery_usage_model_round = 2
                shell_stdin_eof_evidence = probe_passed(
                    turnId=eof_turn_id,
                    started=True,
                    toolOutcome=eof_result.get("outcome"),
                    terminalState=eof_terminal.get("params", {}).get("state"),
                ) if eof_ok else probe_failed(
                    "shell stdin EOF probe did not start, settle and complete in order",
                )
            except RuntimeError as failure:
                reason = str(failure)
                tool_error = latest_tool_error_code(documents, eof_turn_id) \
                    if eof_turn_id is not None else None
                if tool_error is not None:
                    reason = f"{reason}; toolError={tool_error}"
                if "METHOD_NOT_FOUND" in reason or "provider" in reason.casefold() and "not" in reason.casefold():
                    shell_stdin_eof_evidence = probe_blocked(
                        f"production shell stdin EOF probe is unavailable: {reason}",
                    )
                else:
                    shell_stdin_eof_evidence = probe_failed(
                        f"production shell stdin EOF probe failed: {reason}",
                    )

        send(credential_delete_frame(credential_set.get("version", "")))
        credential_delete = require_success(
            read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:credential-delete"),
            "credential delete",
        )
        credential_delete_ok = credential_delete.get("accepted") is True \
            and credential_delete.get("configured") is False \
            and credential_delete.get("credentialId") == CREDENTIAL_ID
        if not credential_delete_ok:
            raise RuntimeError("credential delete returned an invalid redacted projection")

        send(shutdown_frame())
        shutdown = read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:shutdown")
        if not isinstance(shutdown.get("result"), dict) or shutdown["result"].get("accepted") is not True \
                or shutdown["result"].get("status") != "shutting_down":
            raise RuntimeError("native sidecar did not acknowledge shutdown")

        if process.stdin is not None:
            process.stdin.close()
        drain_stdout(stdout_collector, documents, deadline, schema_validator)
        remaining = max(0.0, deadline - time.monotonic())
        try:
            process.wait(timeout=remaining)
        except subprocess.TimeoutExpired as failure:
            raise RuntimeError("native sidecar smoke exceeded its deadline") from failure
        stderr_collector.join(max(0.0, deadline - time.monotonic()))
        if stdout_collector.overflow or stderr_collector.overflow:
            raise RuntimeError("native sidecar smoke output exceeded the hard limit")
        if stdout_collector.error is not None or stderr_collector.error is not None:
            raise RuntimeError("native sidecar pipe reader failed")

        stdout = stdout_collector.bytes()
        stderr = stderr_collector.bytes()
        if process.returncode != 0:
            raise RuntimeError(f"native sidecar exited with status {process.returncode}")
        if process.stdout is not None:
            process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()

        if mcp_probe is None:
            recovery_evidence = probe_blocked("the supplied mock fixture cannot be restarted with durable state")
        elif recovery_usage_thread_id is None or recovery_usage_turn_id is None:
            recovery_evidence = probe_failed(
                "the production restart probe has no persisted Provider Usage identity",
            )
        else:
            recovery_evidence = probe_failed("the production restart probe did not complete")
            try:
                process = subprocess.Popen(
                    command,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    bufsize=0,
                    env=sanitized_environment(),
                    creationflags=creationflags,
                    start_new_session=os.name != "nt",
                )
                stdout_collector = BinaryStreamCollector(process.stdout)
                stderr_collector = BinaryStreamCollector(process.stderr)
                stdout_collector.start()
                stderr_collector.start()
                send(initialize_frame())
                restarted_initialize = read_until(
                    stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:init",
                )
                restarted_result = require_success(restarted_initialize, "restart initialize")
                send(initialized_frame())
                read_until(stdout_collector, documents, deadline, validator=schema_validator, predicate=ready_frame)
                send(thread_read_frame(recovery_usage_thread_id))
                recovered_thread = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:thread-read"),
                    "Thread recovery",
                )
                recovered_usage = require_known_context_usage(
                    recovered_thread, recovery_usage_turn_id, recovery_usage_model_round,
                )
                recovery_ok = recovered_thread.get("threadId") == recovery_usage_thread_id \
                    and isinstance(recovered_thread.get("revision"), int) \
                    and isinstance(restarted_result.get("runtime"), dict)
                send(shutdown_frame())
                restart_shutdown = require_success(
                    read_until(stdout_collector, documents, deadline, validator=schema_validator, frame_id="c:shutdown"),
                    "restart shutdown",
                )
                if process.stdin is not None:
                    process.stdin.close()
                drain_stdout(stdout_collector, documents, deadline, schema_validator)
                process.wait(timeout=max(0.0, deadline - time.monotonic()))
                stderr_collector.join(max(0.0, deadline - time.monotonic()))
                if process.returncode != 0 or restart_shutdown.get("accepted") is not True:
                    recovery_ok = False
                if stdout_collector.overflow or stderr_collector.overflow:
                    recovery_ok = False
                stdout += stdout_collector.bytes()
                stderr += stderr_collector.bytes()
                recovery_evidence = probe_passed(
                    threadId=recovered_thread.get("threadId"),
                    revision=recovered_thread.get("revision"),
                    usage={key: recovered_usage[key] for key in (
                        "certainty", "inputTokens", "outputTokens", "totalTokens",
                    )},
                ) if recovery_ok else probe_failed(
                    "restart returned an incomplete durable Thread projection",
                )
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as failure:
                recovery_evidence = probe_failed(f"production recovery probe failed: {failure}")

        if executable_identity(executable) != artifact:
            raise RuntimeError("native executable changed during smoke")
        stderr_text = stderr.decode("utf-8", errors="replace")
        sanitized_stdout = json.dumps(
            sanitized_documents(documents), ensure_ascii=False, separators=(",", ":")
        )
        if LEAK_PATTERN.search(sanitized_stdout) or LEAK_PATTERN.search(stderr_text) \
                or SMOKE_SECRET in sanitized_stdout or SMOKE_SECRET in stderr_text:
            raise RuntimeError("native sidecar smoke output contains a credential marker")
        private_root = str(owned_root.resolve())
        if private_root in sanitized_stdout:
            raise RuntimeError("native sidecar smoke output contains the private data path")
        if stderr:
            raise RuntimeError("native sidecar wrote unexpected stderr")
        runtime_log = log_directory / "app-server.log"
        if not runtime_log.is_file() or runtime_log.stat().st_size == 0:
            raise RuntimeError("native sidecar runtime log was not persisted")

        config_auth_evidence = probe_passed(
            configurationReplace=configuration_replace.get("accepted") is True,
            credentialSet=credential_set.get("configured") is True,
            credentialDelete=credential_delete_ok,
            authAcl=acl,
        ) if configuration_replace.get("accepted") is True and credential_delete_ok \
            and acl.get("status") == "passed" else probe_blocked(
                "platform current-user-only auth permission evidence was not available",
                configurationReplace=configuration_replace.get("accepted") is True,
                credentialSet=credential_set.get("configured") is True,
                credentialDelete=credential_delete_ok,
                authAcl=acl,
            )
        sqlite_evidence = probe_passed(component="sqlite") if sqlite_ready else probe_failed(
            "runtime/health did not prove a healthy SQLite component",
        )
        subgates = {
            "jsonSchema": probe_passed(validatedFrames=len(sent_frames)),
            "configAuth": config_auth_evidence,
            "okhttpSse": okhttp_evidence,
            "mcp": mcp_evidence,
            "shellCancellation": shell_cancel_evidence,
            "shellStdinEof": shell_stdin_eof_evidence,
            "sqlite": sqlite_evidence,
            "recovery": recovery_evidence,
            "networknt": networknt_evidence,
        }
        required_subgates = evaluate_required_subgates(subgates)
        return {
            "status": "passed" if required_subgates["passed"] else "failed",
            "passed": required_subgates["passed"],
            "failureCode": None if required_subgates["passed"] else "required-subgate-failed",
            "returnCode": process.returncode,
            "frameCount": len(documents),
            "methods": [doc.get("method") for doc in documents if isinstance(doc.get("method"), str)],
            "diagnostics": native_probe_diagnostics(documents),
            "runtime": runtime,
            "executable": {**artifact, "expectedIdentityMatched": expected_identity_matched},
            "runtimeConfiguration": {
                "configured": True,
                "providerRequestIssued": provider_probe is not None and provider_probe.request_count > 0,
            },
            "subgates": subgates,
            "requiredSubgates": required_subgates,
            "stdoutBytes": len(stdout),
            "stderrBytes": len(stderr),
            "logBytes": runtime_log.stat().st_size,
            "environment": {"javaHomeRemoved": True, "credentialLikeVariablesRemoved": True},
        }
    except RuntimeError as failure:
        if process.poll() not in (None, 0):
            raise RuntimeError(f"native sidecar exited with status {process.returncode}") from failure
        raise
    finally:
        if process.stdin is not None:
            try:
                process.stdin.close()
            except OSError:
                pass
        if process.poll() is None:
            terminate_process(process)
        stdout_collector.join(5)
        stderr_collector.join(5)
        if process.stdout is not None:
            process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()
        if provider_probe is not None:
            provider_probe.close()
        if mcp_probe is not None:
            mcp_probe.close()
        shutil.rmtree(owned_root)


def parse_args() -> argparse.Namespace:
    """Parse the narrow CI interface so callers cannot accidentally select a different runtime."""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--executable", required=True, type=Path)
    parser.add_argument("--data-dir", required=True, type=Path)
    parser.add_argument("--timeout-seconds", type=float, default=45.0)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--expected-size", type=int)
    parser.add_argument("--expected-mtime-ns", type=int)
    return parser.parse_args()


def stable_failure_code(failure: BaseException) -> str:
    """Map controlled failures to bounded codes without persisting paths or child output."""

    message = str(failure).lower()
    mappings = (
        ("closed stdout before handshake", "handshake-stdout-closed"),
        ("did not acknowledge initialize", "initialize-not-acknowledged"),
        ("rejected", "rpc-rejected"),
        ("exceeded its deadline", "deadline-exceeded"),
        ("credential", "credential-boundary-failed"),
        ("identity", "artifact-identity-mismatch"),
        ("private data path", "path-redaction-failed"),
        ("unexpected stderr", "unexpected-stderr"),
        ("missing", "required-input-missing"),
    )
    for marker, code in mappings:
        if marker in message:
            return code
    return "native-smoke-runtime-failed" if isinstance(failure, RuntimeError) else "native-smoke-os-failed"


def main() -> int:
    """Run the smoke gate and write only sanitized structured evidence."""

    args = parse_args()
    try:
        report = run_smoke(
            args.executable,
            args.data_dir,
            args.timeout_seconds,
            expected_sha256=args.expected_sha256,
            expected_size=args.expected_size,
            expected_mtime_ns=args.expected_mtime_ns,
        )
    except (OSError, RuntimeError) as failure:
        report = {
            "status": "failed",
            "failureCode": stable_failure_code(failure),
            "failureType": type(failure).__name__,
        }
        if isinstance(failure, NativeRpcRejection):
            report["operation"] = failure.operation
            report["rpcErrorCode"] = failure.error_code
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8", newline="\n")
    print(encoded, end="")
    return 0 if report.get("status") == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
