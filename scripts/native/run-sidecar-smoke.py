# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""通过有界激活握手运行生产 Native Image sidecar。

这是一道仅用于 CI 的 smoke 门禁。脚本沿用当前协议夹具形状，不另实现协议客户端：门禁会确认已构建的
可执行文件能够启动、通过生产组合图发布 Ja Kernel 身份、初始化全新的 SQLite 和 Skills、仅执行本机
Provider/MCP 探针，并正常关闭。
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
import uuid
from typing import Any, Callable

import jsonschema


MAX_OUTPUT_BYTES = 4 * 1024 * 1024
MAX_FAILURE_EVIDENCE_STDERR_BYTES = 64 * 1024
MAX_FAILURE_EVIDENCE_FRAMES = 64
ITEM_CONTENT_PAGE_LIMIT = 65_536
READY_TOKEN = "0123456789abcdef0123456789abcdef"
EXPECTED_ENGINE_VERSION = json.loads(
    (Path(__file__).parents[2] / "package.json").read_text(encoding="utf-8")
)["version"]
PROTOCOL_SCHEMA_PATH = (
    Path(__file__).parents[2] / "contracts" / "ja-rpc" / "v2" / "schema" / "ja-rpc-v2.schema.json"
)
PROTOCOL_GOLDEN_PATH = (
    Path(__file__).parents[2] / "contracts" / "golden" / "v2" / "valid" / "core.jsonl"
)


def load_capability_names(
    schema_path: Path = PROTOCOL_SCHEMA_PATH,
    golden_path: Path = PROTOCOL_GOLDEN_PATH,
) -> tuple[list[str], list[str]]:
    """使用 golden 握手顺序；名称与权威 schema 不匹配时失败关闭。"""

    try:
        definitions = json.loads(schema_path.read_text(encoding="utf-8"))["$defs"]
        schema_methods = definitions["methodName"]["enum"]
        event_names = definitions["eventName"]["enum"]
        golden_line = next(
            line for line in golden_path.read_text(encoding="utf-8").splitlines() if line.strip()
        )
        golden_frame = json.loads(golden_line)
        if golden_frame.get("jsonrpc") != "2.0" or golden_frame.get("method") != "runtime/initialize":
            raise ValueError("golden first frame is not runtime/initialize")
        golden_capabilities = golden_frame["params"]["capabilities"]
        methods = golden_capabilities["methods"]
        events = golden_capabilities["events"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError, StopIteration, ValueError) as failure:
        raise RuntimeError("JA-RPC v2 capability enums are unavailable") from failure
    for label, values in (
        ("methodName", schema_methods),
        ("eventName", event_names),
        ("golden methods", methods),
        ("golden events", events),
    ):
        if (
            not isinstance(values, list)
            or not values
            or any(not isinstance(value, str) or not value for value in values)
            or len(values) != len(set(values))
        ):
            raise RuntimeError(f"JA-RPC v2 {label} enum is invalid")
    if event_names.count("runtime/initialized") != 1:
        raise RuntimeError("JA-RPC v2 runtime/initialized event is missing or duplicated")
    # schema 的 eventName 包含此握手帧，但 Java 只会公布后续事件。
    schema_events = [name for name in event_names if name != "runtime/initialized"]
    if set(methods) != set(schema_methods) or set(events) != set(schema_events):
        raise RuntimeError("JA-RPC golden capability sets differ from the v2 schema")
    return list(methods), list(events)


METHODS, EVENTS = load_capability_names()
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
SCHEMA_PATH = Path(__file__).parents[2] / "contracts" / "ja-rpc" / "v2" / "schema" / "ja-rpc-v2.schema.json"
PROVIDER_ID = "provider_native_smoke"
MODEL_ID = "model_native_smoke"
CREDENTIAL_ID = "cred_native_smoke"
MCP_ID = "mcp_native_smoke"
SKILL_ID = "skill_native_workspace"
SKILL_NAME = "native-smoke"
SKILL_DESCRIPTION = "Workspace Skill used by the Native activation gate"
SMOKE_TEXT = "JA_NATIVE_PROVIDER_TEXT"
def shell_cancellation_command(platform_name: str) -> str:
    """使用生产宿主的原生方言保持进程存活，避免 POSIX 将 PowerShell 命令立即执行失败。"""

    return "Start-Sleep -Seconds 30" if platform_name == "nt" else "sleep 30"


SHELL_COMMAND = shell_cancellation_command(os.name)
SHELL_CANCEL_CALL_ID = "call_shell_cancel"
SHELL_STDIN_EOF_CALL_ID = "call_shell_stdin_eof"
# 进程接口发送 UTF-8 bytes；探针显式解码，不依赖宿主 PowerShell 的 Console code page。
SHELL_STDIN_EOF_COMMAND = (
    "$jaProbeReader=[IO.StreamReader]::new([Console]::OpenStandardInput(),"
    "[Text.UTF8Encoding]::new($false,$true)); $jaProbeInput=$jaProbeReader.ReadToEnd(); "
    "if ($jaProbeInput -ne 'Ja-input-中文') { exit 9 }; Write-Output 'JA_NATIVE_STDIN_EOF'"
) if os.name == "nt" else "test \"$(cat)\" = 'Ja-input-中文' && printf 'JA_NATIVE_STDIN_EOF\\n'"

# 有意在此显式列出完整能力集合。Native smoke 报告是发布门禁；若新增能力却不在此补充，未验证的功能就可能被误判为通过。
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
    """计算实际启动产物的摘要，使新鲜度证据能够与构建结果对应。"""

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
    """调用方提供的构建产物身份不完整或不匹配时失败关闭。"""

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
    """要求每项声明的 Native 能力都明确报告通过状态。

    Smoke 不会替其他能力所有者伪造证据。缺失或受阻项目仍保留在报告中，并导致顶层报告失败；未知或格式错误的
    状态按断言失败处理，不会宽松跳过。
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
                # 生成方可能会在第二个字段中附带布尔断言；若存在，该值必须与 status 一致，避免被篡改的报告伪装成通过。
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
    """构造执行真实握手所需的完整客户端能力文档。

    生产运行时必须能在启用 Provider/模型选择前启动。Smoke 发送完整的 v2 能力词表，因为服务端拒绝协商
    子集。请求不包含配置或凭据，因此此门禁不会调用 Provider。
    """

    return {
        "jsonrpc": "2.0",
        "id": "c:init",
        "method": "runtime/initialize",
        "params": {
            "protocolMajor": 2,
            "protocolMinor": 0,
            "clientVersion": "ja-native-ci",
            "capabilities": {
                "methods": METHODS,
                "events": EVENTS,
                "accessModes": ["approval_required", "full_access"],
                "collaborationModes": ["default", "plan"],
                "features": ["task_threads", "plan", "interaction"],
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
    """返回运行时发布 ready 前所需的 challenge 响应。"""

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
    """返回正常关闭请求，以便 smoke 验证优雅退出路径。"""

    return {"jsonrpc": "2.0", "id": "c:shutdown", "method": "runtime/shutdown", "params": {}}


def workspace_open_frame(workspace: Path) -> dict[str, Any]:
    """在组合图检查文件或工具配置前绑定隔离的工作区。"""

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
    """信任服务端实际创建的 Workspace，然后项目 Skills 才能进入 Turn 快照。"""

    return {
        "jsonrpc": "2.0",
        "id": "c:workspace-trust",
        "method": "workspace/set-trust",
        "params": {"workspaceId": workspace_id, "trust": "trusted"},
    }


def skill_list_frame(workspace_id: str | None = None) -> dict[str, Any]:
    """请求真实的 Skill 目录，同时用不透明身份隐藏项目根目录。"""

    params = {} if workspace_id is None else {"workspaceId": workspace_id}
    return {"jsonrpc": "2.0", "id": "c:skills", "method": "skill/list", "params": params}


def health_read_frame() -> dict[str, Any]:
    """ready 屏障之后读取实时 SQLite 和 Kernel 组件状态。"""

    return {"jsonrpc": "2.0", "id": "c:health", "method": "runtime/health", "params": {}}


def config_read_frame() -> dict[str, Any]:
    """修改 smoke home 前读取两层配置和全局 credential CAS。"""

    return {"jsonrpc": "2.0", "id": "c:config-read", "method": "configuration/read", "params": {}}


def configuration_document(provider_endpoint: str, mcp_endpoint: str) -> dict[str, Any]:
    """构造运行时探针使用的有界本地 loopback 文档。

    文档不包含凭据字节，且只指向此 smoke 进程拥有的服务器。将 Provider 和模型与 MCP 描述符放在同一 v2
    批次中，可验证可执行文件解析的是单一不可变代际，而不是依赖仅供测试使用的 Provider hook。Agent 默认值
    只保留 schema 支持的 context policy；turn limit 不是配置字段。v2 文档通过 disabled_skills 记录
    skills 禁用状态，已发现的 skills 仍留在工作区。
    """

    return {
        "schema_version": 2,
        "config_revision": 0,
        "default_access_mode": "approval_required",
        "interaction": {"clarification_enabled": True},
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
        "disabled_skills": [],
        "subagents": {"enabled": False, "provider_id": None, "model_id": None, "reasoning_level": None},
    }


def write_workspace_skill(workspace: Path) -> Path:
    """创建一个真实项目 Skill，避免使用内置夹具测试 Native 发现行为。"""

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
    """通过 v2 replace CAS 构造严格的本地探针配置文档。

    无法提供 loopback 探针的调用方使用合同测试所需的空目录；生产 smoke 在发送前必须同时
    提供 Provider 与 MCP endpoint。空技能禁用集合仍显式传递，符合当前 v2 配置要求。
    """

    document = configuration_document(provider_endpoint, mcp_endpoint) \
        if provider_endpoint and mcp_endpoint else {
            "schema_version": 2,
            "config_revision": 0,
            "default_access_mode": "full_access",
            "interaction": {"clarification_enabled": True},
            "default_provider_id": None,
            "default_model_id": None,
            "default_reasoning_level": None,
            "providers": [],
            "mcp_servers": [],
            "disabled_skills": [],
            "subagents": {"enabled": False, "provider_id": None, "model_id": None, "reasoning_level": None},
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
    """持久化一个隔离的探针 Thread，同时由 Java 保留规范的工作区身份。

    不同的探针身份可防止先前的 Tool 结果改变后续生命周期探针所选择的确定性 loopback Provider 路由。
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
    """请求供传输探针使用的脱敏 MCP 目录投影。"""

    return {"jsonrpc": "2.0", "id": "c:mcp-list", "method": "mcp/list", "params": {}}


def mcp_test_frame() -> dict[str, Any]:
    """通过生产目录执行有界的 MCP initialize/tools-list 探针。"""

    return {"jsonrpc": "2.0", "id": "c:mcp-test", "method": "mcp/test", "params": {"mcpId": MCP_ID}}


def mcp_tools_read_frame() -> dict[str, Any]:
    """读取一个 MCP schema 页，以便通过实时代际执行 networknt 校验。"""

    return {
        "jsonrpc": "2.0", "id": "c:mcp-tools", "method": "mcp/list-tools",
        "params": {"mcpId": MCP_ID},
    }


def turn_start_frame(thread_id: str, text: str) -> dict[str, Any]:
    """将超时控制权留给 harness；v2 帧携带内容和独立操作回执键。"""

    return {
        "jsonrpc": "2.0", "id": "c:turn-start", "method": "turn/start",
        "params": {
            "threadId": thread_id, "content": [{"type": "text", "text": text}],
            "operationKey": f"op_{uuid.uuid4().hex}",
        },
    }


def turn_cancel_frame(turn_id: str) -> dict[str, Any]:
    """使用 v2 取消契约取消一个已准入的 Turn。

    取消请求绑定身份但不绑定 CAS：Kernel 负责终止状态转换，并以幂等方式接受延迟的取消。因此，携带 UI 观察到的
    Thread 修订号会使本来有效的停止请求变成无效的 v2 帧。
    """

    return {
        "jsonrpc": "2.0", "id": "c:turn-cancel", "method": "turn/cancel",
        "params": {"turnId": turn_id},
    }


def approval_response_frame(approval_id: str, turn_id: str, expected_revision: int) -> dict[str, Any]:
    """在验证夹具调用结果前，使用全新的幂等键批准该调用。"""

    return {
        "jsonrpc": "2.0", "id": "c:approval", "method": "approval/respond",
        "params": {
            "approvalId": approval_id, "turnId": turn_id, "decision": "approve",
            "expectedThreadRevision": expected_revision, "operationKey": f"op_{uuid.uuid4().hex}",
        },
    }


def thread_read_frame(thread_id: str) -> dict[str, Any]:
    """在进程重启前后读取持久化的 Thread 投影。"""

    return {"jsonrpc": "2.0", "id": "c:thread-read", "method": "thread/read", "params": {"threadId": thread_id}}


def thread_item_content_read_frame(thread_id: str, item_id: str) -> dict[str, Any]:
    """按终态公开 item 身份读取首个正文页，验证持久化文本读取闭环。"""

    return {
        "jsonrpc": "2.0",
        "id": "c:item-content-read",
        "method": "thread/item-content/read",
        "params": {
            "threadId": thread_id,
            "itemId": item_id,
            "offsetCharacters": 0,
            "limitCharacters": ITEM_CONTENT_PAGE_LIMIT,
        },
    }


def thread_rename_frame(thread_id: str, expected_revision: int) -> dict[str, Any]:
    """取得手动标题控制权，使 Provider 请求计数探针仅有一个写入方。"""

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
    """要求最新的精确请求 Usage，避免较早的 KNOWN 记录掩盖当前状态。"""

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
    """从子进程环境中移除类似凭据的变量和 Java 发现变量。

    原生可执行文件不得依赖开发者 JDK，也不得继承模型凭据。保留普通平台变量，以维持 Windows loader 和
    macOS 进程行为；按名称过滤可阻止 CI secrets 进入 smoke 子进程。
    """

    environment = dict(os.environ)
    for key in list(environment):
        upper = key.upper()
        if key == "JAVA_HOME" or any(part in upper for part in SECRET_NAME_PARTS):
            environment.pop(key, None)
    environment["JA_NATIVE_SMOKE"] = "1"
    return environment


class BinaryStreamCollector:
    """收集一个子进程管道，同时限制嘈杂 sidecar 的输出不超过 smoke 上限。"""

    def __init__(self, stream: Any) -> None:
        """分别保存一个二进制管道的有界捕获内容和队列，避免与握手状态混杂。"""

        self._stream = stream
        self.lines = Queue()
        self.chunks: list[bytes] = []
        self.total_bytes = 0
        self.overflow = False
        self.error: BaseException | None = None
        self._thread = threading.Thread(target=self._consume, daemon=True)

    def start(self) -> None:
        """启动专用读取线程，因为 Windows 不支持对匿名管道使用 select()。"""

        self._thread.start()

    def _consume(self) -> None:
        """分块读取有界二进制行，并按到达顺序交给握手读取器。"""

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
        """只在剩余 smoke 时限内等待读取线程，避免清理管道时卡住 CI。"""

        self._thread.join(max(0.0, timeout))

    def bytes(self) -> bytes:
        """读取线程到达 EOF 或输出达到硬上限后，返回已捕获的字节。"""

        return b"".join(self.chunks)


class LoopbackProvider:
    """提供确定性的 OpenAI Responses 流，不访问外部网络。"""

    def __init__(self) -> None:
        """绑定临时 IPv4 loopback 监听器，确保 Provider 证据不会离开本机。"""

        owner = self

        class Handler(BaseHTTPRequestHandler):
            """不记录 HTTP 日志，也不在 smoke 进程中保留请求正文。"""

            protocol_version = "HTTP/1.1"

            def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                """只提供有界生成路径，使远程 token count 请求明确失败。"""

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
                    isinstance(tool, dict) and tool.get("name") == "exec_command"
                    for tool in tools
                )
                # 动态指引现由 Provider instructions 提供，因此输入只包含持久化的对话消息。自动标题请求也可能包含相同的用户文本，
                # 但不会声明任何工具，所以还必须检查是否存在对应能力。
                prompt = json.dumps(input_items or [], ensure_ascii=False)
                if has_tool_result:
                    stream = owner.text_stream("JA_NATIVE_SHELL_CANCELLED")
                elif advertises_shell and "stdin eof" in prompt.casefold():
                    stream = owner.shell_stream(SHELL_STDIN_EOF_COMMAND, SHELL_STDIN_EOF_CALL_ID)
                elif advertises_shell and "exec_command" in prompt.casefold():
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
                """禁止 BaseHTTPRequestHandler 输出诊断信息，避免泄露路径和请求头。"""

                del format, args

        self.request_count = 0
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def endpoint(self) -> str:
        """返回 Ja Responses adapter 使用的 Provider base path。"""

        return f"http://127.0.0.1:{self.server.server_port}/v1"

    @staticmethod
    def _response(response_id: str, status: str, output: list[dict[str, Any]], usage: bool) -> dict[str, Any]:
        """构造强类型 Responses 信封，并提供精确的非负 token 统计。"""

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
        """编码一个严格的 SSE 事件，不保留 Provider 请求或凭据字节。"""

        body = {"type": event_type, "sequence_number": sequence, **payload}
        return f"event: {event_type}\ndata: {json.dumps(body, ensure_ascii=False, separators=(',', ':'))}\n\n"

    def text_stream(self, text: str) -> str:
        """为 Provider HTTP/SSE 门禁返回完整的公开文本流。"""

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
        """返回一个全局唯一的 exec_command Tool 身份，用于确定性生命周期探针。"""

        response_id = f"resp_native_{self.request_count}"
        item_id = f"item_{call_id}"
        arguments = json.dumps(
            {"cmd": command, "yield_time_ms": 10000}, separators=(",", ":")
        )
        item = {"id": item_id, "type": "function_call", "call_id": call_id,
                "name": "exec_command", "arguments": arguments}
        return "".join((
            self._event("response.created", 0, {"response": self._response(response_id, "in_progress", [], False)}),
            self._event("response.output_item.added", 1, {
                "output_index": 0, "item": {**item, "arguments": ""},
            }),
            self._event("response.function_call_arguments.delta", 2, {
                "item_id": item_id, "delta": arguments, "output_index": 0,
            }),
            self._event("response.function_call_arguments.done", 3, {
                "item_id": item_id, "name": "exec_command", "arguments": arguments, "output_index": 0,
            }),
            self._event("response.output_item.done", 4, {"output_index": 0, "item": item}),
            self._event("response.completed", 5, {
                "response": self._response(response_id, "completed", [item], True),
            }),
        ))

    def close(self) -> None:
        """停止 loopback 监听器并短暂等待，确保夹具线程不会遗留在门禁之后。"""

        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class LoopbackMcp:
    """提供有界的 Streamable HTTP MCP 目录，供 mcp 和 networknt 探针使用。"""

    def __init__(self) -> None:
        """仅绑定 IPv4 loopback，并保持 MCP 响应词表精简。"""

        owner = self

        class Handler(BaseHTTPRequestHandler):
            """处理 JSON-RPC MCP 请求，不记录请求头或请求正文。"""

            protocol_version = "HTTP/1.1"

            def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                """在字节上限内返回 initialize、tools/list 和 tools/call 响应。"""

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
                # 只保留方法形态和关联标识是否存在；请求体与请求头可能包含凭据或工作区数据，绝不能写入 smoke 诊断。
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
                """接受由客户端发起的会话关闭请求，不保留会话标识。"""

                if self.path != "/mcp":
                    self.send_error(404)
                    return
                self._send_empty(204)

            def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                """明确拒绝可选的重连流，避免夹具令探针一直等待。"""

                self._send_empty(405)

            def _send_empty(self, status: int) -> None:
                """明确标记空 HTTP/1.1 响应的分帧方式，避免 OkHttp 等待含糊的 EOF。"""

                self.send_response(status)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.close_connection = True
                self.end_headers()

            def log_message(self, format: str, *args: Any) -> None:
                """禁止 HTTP 服务日志输出，因为其中可能包含 loopback 路径或请求头。"""

                del format, args

        self.request_count = 0
        self.request_methods: list[tuple[str, bool]] = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def endpoint(self) -> str:
        """返回写入 Java 管理的 MCP 文档中的 Streamable HTTP endpoint。"""

        return f"http://127.0.0.1:{self.server.server_port}/mcp"

    def close(self) -> None:
        """停止 MCP 监听器，并在 smoke 时限内等待其接受连接的线程退出。"""

        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def probe_passed(**evidence: Any) -> dict[str, Any]:
    """将观测到的运行时事实转换为生产验证子门禁唯一允许的通过格式。"""

    return {"status": "passed", "passed": True, **evidence}


def probe_blocked(reason: str, **evidence: Any) -> dict[str, Any]:
    """记录动态不可用的探针，但不将其视为可选能力。"""

    return {"status": "blocked", "passed": False, "reason": reason, **evidence}


def probe_failed(reason: str, **evidence: Any) -> dict[str, Any]:
    """将已执行探针发现的矛盾记录为硬失败子门禁。"""

    return {"status": "failed", "passed": False, "reason": reason, **evidence}


def terminate_process(process: subprocess.Popen[Any]) -> None:
    """超过时限后终止整个 smoke 进程组，避免遗留子进程。"""

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
    """构造唯一的生产 App Server 命令，不接受已废弃的 runtime selector。

    App Server 现在只有一张组合图，因此保留 production/fake selector 会重建兼容接口，并使当前 Native Image
    拒绝原本有效的 smoke 调用。目录参数保持为规范且无填充的 Base64URL 值，避免 Windows Unicode 路径依赖当前
    控制台编码。
    """

    def encoded_path(directory: Path) -> str:
        """按照无填充的 Base64URL argv 契约编码一个规范 Unicode 根目录。"""

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
        "--ja-runtime-generation=1",
    ]


def load_schema_validator() -> jsonschema.Draft202012Validator:
    """只加载一次仓库内的 v2 schema，供每个发出的 smoke 请求进行合同校验。"""

    try:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        return jsonschema.Draft202012Validator(schema)
    except (OSError, json.JSONDecodeError, jsonschema.SchemaError) as failure:
        raise RuntimeError("v2 JSON Schema is unavailable or malformed") from failure


def send_frame(
    process: subprocess.Popen[Any],
    frame: dict[str, Any],
    validator: jsonschema.Draft202012Validator | None = None,
) -> None:
    """校验并写入一个 UTF-8 JSONL 帧，避免实时 sidecar 掩盖 schema 漂移。"""

    if validator is not None:
        try:
            validator.validate(frame)
        except jsonschema.ValidationError as failure:
            raise RuntimeError("native smoke emitted a frame outside the v2 schema") from failure

    if process.stdin is None:
        raise RuntimeError("native sidecar stdin is unavailable")
    payload = json.dumps(frame, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
    try:
        process.stdin.write(payload)
        process.stdin.flush()
    except (BrokenPipeError, OSError) as failure:
        raise RuntimeError("native sidecar stdin closed during handshake") from failure


def next_line(collector: BinaryStreamCollector, deadline: float) -> bytes | None:
    """使用共享进程时限和有界队列读取一行二进制 JSONL。"""

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
    """解码一个 UTF-8 对象并校验服务端帧，避免未验证事件影响门禁。"""

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
    """保留事件，直到收到关联响应或匹配条件的帧。"""

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
    """等待预期 Turn 事件或其终止状态，避免失败的 Turn 耗尽全局时限。"""

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
    """只公开 schema 限定的 state/errorCode，不将终止消息写入 smoke 输出。"""

    params = event.get("params")
    if not isinstance(params, dict):
        return "settled as unknown"
    state = params.get("state")
    stable_state = state if isinstance(state, str) else "unknown"
    error_code = params.get("errorCode")
    suffix = f" ({error_code})" if isinstance(error_code, str) else ""
    return f"settled as {stable_state}{suffix}"


def latest_tool_error_code(documents: list[dict[str, Any]], turn_id: str) -> str | None:
    """只返回一个 Turn 内有界且稳定的 Tool error code，不返回 Tool 内容或参数。"""

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
    """关闭后排空剩余 JSONL 事件，使读取线程和子进程管道彻底结束。"""

    while True:
        line = next_line(collector, deadline)
        if line is None:
            return
        document = decode_frame(line, validator)
        documents.append(document)


def ready_frame(document: dict[str, Any]) -> bool:
    """只识别携带 smoke 客户端所发精确 challenge 的 ready 事件。"""

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


def await_running_shell(
    send: Callable[[dict[str, Any]], None],
    collector: BinaryStreamCollector,
    documents: list[dict[str, Any]],
    deadline: float,
    validator: Any,
    workspace_id: str,
    thread_id: str,
    turn_id: str,
) -> str:
    """Tool started 仅表示投影已提交；按真实调用身份等到进程启动，才允许验证运行中的取消。"""

    while time.monotonic() < deadline:
        frame_id = f"c:process-running-{uuid.uuid4().hex}"
        send({"jsonrpc": "2.0", "id": frame_id, "method": "process/list", "params": {
            "workspaceId": workspace_id, "threadId": thread_id, "limit": 200,
            "cursor": None, "startedCallId": SHELL_CANCEL_CALL_ID,
        }})
        page = require_success(
            read_until(collector, documents, deadline, validator=validator, frame_id=frame_id),
            "running shell probe",
        )
        if any(frame.get("method") == "turn/terminal"
               and isinstance(frame.get("params"), dict)
               and frame["params"].get("turnId") == turn_id for frame in documents):
            raise RuntimeError("shell cancellation Turn settled before its running process was verified")
        sessions = page.get("sessions", [])
        if sessions:
            if len(sessions) != 1 or sessions[0].get("startedTurnId") != turn_id \
                    or sessions[0].get("startedCallId") != SHELL_CANCEL_CALL_ID:
                raise RuntimeError("shell cancellation process identity did not match its Tool")
            session = sessions[0]
            if session.get("state") != "running":
                raise RuntimeError("shell cancellation process settled before cancellation")
            return session["sessionId"]
        time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
    raise RuntimeError("shell cancellation process did not start before its deadline")


def verify_persistent_stdin(
    send: Callable[[dict[str, Any]], None],
    collector: BinaryStreamCollector,
    documents: list[dict[str, Any]],
    deadline: float,
    validator: Any,
    workspace_id: str,
    thread_id: str,
    turn_id: str,
) -> dict[str, Any]:
    """在 Turn 结束后投递 stdin 并关闭输入，以真实退出和输出证明后台会话仍可使用。"""

    def request(method: str, params: dict[str, Any]) -> dict[str, Any]:
        """每次请求使用独立关联 ID，等待时保留异步事件并服从同一 smoke deadline。"""

        frame_id = f"c:process-{uuid.uuid4().hex}"
        send({"jsonrpc": "2.0", "id": frame_id, "method": method, "params": params})
        return require_success(
            read_until(collector, documents, deadline, validator=validator, frame_id=frame_id),
            "persistent process control",
        )

    page = request("process/list", {
        "workspaceId": workspace_id, "threadId": thread_id, "limit": 200,
        "cursor": None, "startedCallId": SHELL_STDIN_EOF_CALL_ID,
    })
    sessions = page.get("sessions", [])
    if len(sessions) != 1 or sessions[0].get("startedTurnId") != turn_id \
            or sessions[0].get("state") != "running":
        raise RuntimeError("stdin process did not survive its completed Turn")
    session = sessions[0]
    scope = {
        "workspaceId": workspace_id, "threadId": thread_id,
        "generation": page["generation"], "sessionId": session["sessionId"],
    }
    snapshot = request("process/write", {
        **scope, "operationKey": f"op_{uuid.uuid4().hex}", "input": "Ja-input-中文",
        "closeInput": True, "afterSequence": 0,
    })
    output = snapshot.get("output", "")
    while snapshot.get("state") in ("running", "stopping"):
        if time.monotonic() >= deadline:
            raise RuntimeError("persistent stdin process did not exit before its deadline")
        time.sleep(0.05)
        snapshot = request("process/read", {
            **scope, "afterSequence": snapshot["nextSequence"], "maxBytes": 65_536,
        })
        output += snapshot.get("output", "")
    if snapshot.get("state") != "exited" or snapshot.get("exitCode") != 0 \
            or "JA_NATIVE_STDIN_EOF" not in output:
        raise RuntimeError("persistent stdin process exited without its verified output")
    return {
        "sessionId": session["sessionId"], "survivedTurn": True,
        "stdinDelivered": True, "inputClosed": True, "exitCode": 0, "markerVerified": True,
    }


def sanitized_documents(documents: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """扫描捕获输出是否泄露前，先移除工作区路径。"""

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
                        "modelRequestOrdinal", "modelRound", "purpose", "certainty",
                        "inputTokens", "outputTokens", "totalTokens",
                    )
                } if isinstance(usage, dict) else None,
            })
    return diagnostics[-32:]


def failure_protocol_trace(
    sent_frames: list[dict[str, Any]], documents: list[dict[str, Any]],
) -> dict[str, list[dict[str, Any]]]:
    """只记录定位顺序所需的帧元数据，避免失败证据落入正文、参数或凭据。"""

    allowed_fields = (
        "state", "status", "scope", "outcome", "errorCode", "reasonCode", "generation",
        "revision", "eventId", "turnId", "threadId", "workspaceId", "callId", "toolCallId",
        "toolName", "occurredAt", "accepted", "configured", "toolCount",
    )
    safe_pattern = re.compile(r"[A-Za-z0-9_.:/+@-]{1,160}")

    def summarize(document: dict[str, Any]) -> dict[str, Any]:
        summary: dict[str, Any] = {}
        method = document.get("method")
        if isinstance(method, str) and safe_pattern.fullmatch(method):
            summary["method"] = method
        frame_id = document.get("id")
        if isinstance(frame_id, (int, str)) and not isinstance(frame_id, bool):
            if isinstance(frame_id, int) or safe_pattern.fullmatch(frame_id):
                summary["id"] = frame_id
        for section in ("params", "result"):
            values = document.get(section)
            if not isinstance(values, dict):
                continue
            safe_values: dict[str, Any] = {}
            for key in allowed_fields:
                value = values.get(key)
                if isinstance(value, bool) or isinstance(value, int):
                    safe_values[key] = value
                elif isinstance(value, str) and safe_pattern.fullmatch(value):
                    safe_values[key] = value
            if safe_values:
                summary[section] = safe_values
        error = document.get("error")
        data = error.get("data") if isinstance(error, dict) else None
        code = data.get("errorCode") if isinstance(data, dict) else None
        if isinstance(code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code):
            summary["errorCode"] = code
        return summary

    outbound = [
        summarize(frame) for frame in sent_frames[-MAX_FAILURE_EVIDENCE_FRAMES:]
    ]
    inbound = [
        summarize(frame) for frame in documents[-MAX_FAILURE_EVIDENCE_FRAMES:]
    ]
    return {"client": outbound, "server": inbound}


def failure_stage_summary(
    sent_frames: list[dict[str, Any]], documents: list[dict[str, Any]],
) -> dict[str, Any]:
    """用最后一个请求及其应答状态区分等待 RPC、等待后续事件和进程启动失败。"""

    if not sent_frames:
        return {"kind": "native_process_launch"}
    last_sent = sent_frames[-1]
    rpc_request_id = last_sent.get("id")
    method = last_sent.get("method")
    rpc_response_received = rpc_request_id is not None and any(
        document.get("id") == rpc_request_id for document in documents
    )
    last_server = documents[-1] if documents else None
    last_server_method = last_server.get("method") if isinstance(last_server, dict) else None
    last_server_id = last_server.get("id") if isinstance(last_server, dict) else None
    return {
        "kind": "awaiting_followup_frame" if rpc_response_received or rpc_request_id is None else "awaiting_rpc_response",
        "requestMethod": method if isinstance(method, str) else "unknown",
        "rpcRequestId": rpc_request_id if isinstance(rpc_request_id, str) else None,
        "rpcResponseReceived": rpc_response_received,
        "lastServerMethod": last_server_method if isinstance(last_server_method, str) else None,
        "lastServerId": last_server_id if isinstance(last_server_id, str) else None,
    }


def redact_failure_stderr(stderr: bytes, sensitive_paths: list[Path]) -> str:
    """保留 Native 排障行但移除隔离目录、已知 smoke 密钥及通用凭据值。"""

    text = stderr.decode("utf-8", errors="replace")
    for path in sorted((str(value) for value in sensitive_paths), key=len, reverse=True):
        if path:
            text = re.sub(re.escape(path), "[private-path]", text, flags=re.IGNORECASE)
            text = re.sub(re.escape(path.replace("\\", "/")), "[private-path]", text, flags=re.IGNORECASE)
    text = text.replace(SMOKE_SECRET, "[redacted-secret]")
    text = re.sub(r"(?i)\b(bearer)\s+[^\s,;]+", r"\1 [redacted]", text)
    text = re.sub(
        r"(?i)(\b(?:api[_ -]?key|authorization|password|secret|token)\b\s*[:=]\s*)"
        r"(?:\"[^\"]*\"|'[^']*'|[^\s,;}]+)",
        r"\1[redacted]",
        text,
    )
    text = LEAK_PATTERN.sub("[redacted-credential]", text)
    return text[-MAX_FAILURE_EVIDENCE_STDERR_BYTES:]


def write_failure_diagnostics(
    profile_root: Path,
    sent_frames: list[dict[str, Any]],
    documents: list[dict[str, Any]],
    stderr: bytes,
    sensitive_paths: list[Path],
    phase: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """仅在显式排障时保存脱敏帧摘要，并让隔离 SQLite 与日志留在独立 profile 内。"""

    inventory = [
        {"path": path.relative_to(profile_root).as_posix(), "sizeBytes": path.stat().st_size}
        for path in sorted(profile_root.rglob("*"))
        if path.is_file() and path.name != "failure-diagnostics.json"
    ]
    trace = failure_protocol_trace(sent_frames, documents)
    diagnostic = {
        "schemaVersion": 1,
        "stage": phase or failure_stage_summary(sent_frames, documents),
        "frameCount": len(documents),
        "protocolFrames": trace,
        "stderrRedacted": redact_failure_stderr(stderr, sensitive_paths),
        "files": inventory[:256],
        "databaseFiles": [
            entry for entry in inventory
            if entry["path"].casefold().endswith((".db", ".sqlite", ".sqlite3", ".db-wal", ".db-shm"))
        ],
    }
    (profile_root / "failure-diagnostics.json").write_text(
        json.dumps(diagnostic, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return {
        "profileDirectory": profile_root.name,
        "diagnosticsFile": "failure-diagnostics.json",
        "databaseFileCount": len(diagnostic["databaseFiles"]),
        "protocolFrameCount": len(documents),
        "stage": diagnostic["stage"],
    }


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
        # icacls 会把第一条 ACE 打印在目标路径所在行，只有后续条目才会缩进。
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
    keep_failed_profile: bool = False,
    failure_evidence_sink: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """执行 v2 生命周期；失败现场默认清理，仅在显式保留时回传脱敏定位信息。

    真实可执行文件会收到 loopback Provider/MCP 服务，使传输、schema、取消和恢复门禁都经过生产 RPC。传入命令
    前缀表示使用合同夹具；对于夹具无法覆盖的探针，脚本会有意报告为动态受阻，既保留单元 smoke 的用途，也不把
    mock 伪装成 Native 证据。可选 sink 只在异常处理中传递相对诊断指针，不传递子进程输出。
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
        # 工作区没有 Agent 合同时，Harness 会按设计输出警告。提供一份由 smoke 管理的最小合同，
        # 可让 stderr 保持有效信息，同时不削弱生产日志要求。
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
    except BaseException as failure:
        if provider_probe is not None:
            provider_probe.close()
        if mcp_probe is not None:
            mcp_probe.close()
        if keep_failed_profile:
            try:
                evidence = write_failure_diagnostics(
                    owned_root,
                    [],
                    [],
                    b"",
                    [owned_root, home_directory, runtime_data_directory, run_directory, log_directory,
                     workspace, Path.home()],
                    {"kind": "native_process_launch"},
                )
                if failure_evidence_sink is not None:
                    failure_evidence_sink.update(evidence)
                setattr(failure, "native_smoke_failure_evidence", evidence)
            except OSError:
                evidence = {
                    "profileDirectory": owned_root.name,
                    "diagnosticsWriteFailed": True,
                }
                if failure_evidence_sink is not None:
                    failure_evidence_sink.update(evidence)
                setattr(failure, "native_smoke_failure_evidence", evidence)
        else:
            shutil.rmtree(owned_root)
        raise
    stdout_collector = BinaryStreamCollector(process.stdout)
    stderr_collector = BinaryStreamCollector(process.stderr)
    stdout_collector.start()
    stderr_collector.start()
    documents: list[dict[str, Any]] = []
    sent_frames: list[dict[str, Any]] = []
    captured_failure: BaseException | None = None
    result: dict[str, Any] | None = None
    deadline = time.monotonic() + timeout_seconds

    def send(frame: dict[str, Any]) -> None:
        """让经过 schema 校验的出站帧可计数，同时不保留含凭据的响应。"""

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
            raise RuntimeError("configuration replace returned an invalid v2 projection")

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
                final_message = text_terminal.get("params", {}).get("finalMessage")
                item_id = final_message.get("messageId") if isinstance(final_message, dict) else None
                if not isinstance(item_id, str):
                    raise RuntimeError("Provider text Turn did not return its committed item identity")
                send(thread_item_content_read_frame(thread["threadId"], item_id))
                content_page = require_success(
                    read_until(
                        stdout_collector,
                        documents,
                        deadline,
                        validator=schema_validator,
                        frame_id="c:item-content-read",
                    ),
                    "Provider final item content",
                )
                if (
                    content_page.get("itemId") != item_id
                    or content_page.get("offsetCharacters") != 0
                    or content_page.get("nextOffsetCharacters") is not None
                    or content_page.get("totalCharacters") != len(SMOKE_TEXT)
                    or content_page.get("truncated") is not False
                    or content_page.get("content") != SMOKE_TEXT
                    or final_message.get("text") != content_page.get("content")
                ):
                    raise RuntimeError("thread/item content did not reproduce the committed Provider reply")
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
                    "Use exec_command with the exact command and wait for approval, then report completion.",
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
                running_session_id = await_running_shell(
                    send, stdout_collector, documents, deadline, schema_validator,
                    workspace_id, thread["threadId"], shell_turn_id,
                )
                send(turn_cancel_frame(shell_turn_id))
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
                    runningSessionId=running_session_id,
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
                    "Use exec_command to run the stdin EOF probe, then report completion.",
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
                if not eof_ok:
                    raise RuntimeError("stdin process Tool did not settle and complete in order")
                process_evidence = verify_persistent_stdin(
                    send, stdout_collector, documents, deadline, schema_validator,
                    workspace_id, eof_thread["threadId"], eof_turn_id,
                )
                recovery_usage_thread_id = eof_thread["threadId"]
                recovery_usage_turn_id = eof_turn_id
                recovery_usage_model_round = 2
                shell_stdin_eof_evidence = probe_passed(
                    turnId=eof_turn_id,
                    started=True,
                    toolOutcome=eof_result.get("outcome"),
                    terminalState=eof_terminal.get("params", {}).get("state"),
                    **process_evidence,
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
        result = {
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
        if not required_subgates["passed"] and keep_failed_profile:
            captured_failure = RuntimeError("native sidecar smoke required subgate failed")
        return result
    except (OSError, RuntimeError) as caught_failure:
        failure: BaseException = caught_failure
        if process.poll() not in (None, 0):
            failure = RuntimeError(f"native sidecar exited with status {process.returncode}")
            failure.__cause__ = caught_failure
        captured_failure = failure
        if failure is caught_failure:
            raise
        raise failure
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
        if captured_failure is not None and keep_failed_profile:
            try:
                evidence = write_failure_diagnostics(
                    owned_root,
                    sent_frames,
                    documents,
                    stderr_collector.bytes(),
                    [owned_root, home_directory, runtime_data_directory, run_directory, log_directory,
                     workspace, Path.home()],
                )
            except OSError:
                evidence = {
                    "profileDirectory": owned_root.name,
                    "diagnosticsWriteFailed": True,
                    "stage": failure_stage_summary(sent_frames, documents),
                }
            if result is not None:
                result["failureEvidence"] = evidence
            else:
                setattr(captured_failure, "native_smoke_failure_evidence", evidence)
            if failure_evidence_sink is not None:
                failure_evidence_sink.update(evidence)
        else:
            shutil.rmtree(owned_root)


def parse_args() -> argparse.Namespace:
    """解析受限的 smoke 参数；失败现场保留须显式开启且必须有结构化报告输出。"""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--executable", required=True, type=Path)
    parser.add_argument("--data-dir", required=True, type=Path)
    parser.add_argument("--timeout-seconds", type=float, default=45.0)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--expected-size", type=int)
    parser.add_argument("--expected-mtime-ns", type=int)
    parser.add_argument("--keep-failed-profile", action="store_true")
    args = parser.parse_args()
    if args.keep_failed_profile and args.output is None:
        parser.error("--keep-failed-profile requires --output")
    return args


def stable_failure_code(failure: BaseException) -> str:
    """将可控故障映射为有界代码，不持久化路径或子进程输出。"""

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
    """运行 smoke 门禁并仅写脱敏结构化证据；失败现场只由显式参数保留。"""

    args = parse_args()
    failure_evidence: dict[str, Any] = {}
    try:
        report = run_smoke(
            args.executable,
            args.data_dir,
            args.timeout_seconds,
            expected_sha256=args.expected_sha256,
            expected_size=args.expected_size,
            expected_mtime_ns=args.expected_mtime_ns,
            keep_failed_profile=args.keep_failed_profile,
            failure_evidence_sink=failure_evidence,
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
        evidence = failure_evidence or getattr(failure, "native_smoke_failure_evidence", None)
        if isinstance(evidence, dict):
            report["failureEvidence"] = evidence
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8", newline="\n")
    print(encoded, end="")
    return 0 if report.get("status") == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
