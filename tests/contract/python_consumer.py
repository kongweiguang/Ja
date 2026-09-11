# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later
"""以 Python 独立消费 Agent 过程、终态修改记录与输入队列的 breaking JA-RPC v1 语料。"""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
GOLDEN = Path(os.environ.get("JA_GOLDEN_PATH", ROOT / "contracts" / "golden"))
SCHEMA_PATH = ROOT / "contracts" / "ja-rpc" / "v1" / "schema" / "ja-rpc-v1.schema.json"
VALID = GOLDEN / "v1" / "valid" / "agent-process.jsonl"
INVALID = GOLDEN / "v1" / "invalid" / "agent-presentation.jsonl"
CHANGE_RESULT_INVALID = GOLDEN / "v1" / "invalid" / "correlated" / "change-set-results.jsonl"
QUEUE_VALID = GOLDEN / "v1" / "valid" / "input-queue.jsonl"
PATH_SEARCH_VALID = GOLDEN / "v1" / "valid" / "workspace-path-search.jsonl"
PATH_SEARCH_INVALID = GOLDEN / "v1" / "invalid" / "correlated" / "workspace-path-search-results.jsonl"
THREAD_VALID = GOLDEN / "v1" / "valid" / "lists.jsonl"
THREAD_INVALID = GOLDEN / "v1" / "invalid" / "correlated" / "thread-seen-results.jsonl"
TASK_VALID = GOLDEN / "v1" / "valid" / "task-threads.jsonl"
TASK_INVALID = GOLDEN / "v1" / "invalid" / "task-threads.jsonl"
TASK_RESULT_INVALID = GOLDEN / "v1" / "invalid" / "correlated" / "task-thread-results.jsonl"
GOAL_VALID = GOLDEN / "v1" / "valid" / "plan-goals.jsonl"
GOAL_INVALID = GOLDEN / "v1" / "invalid" / "plan-goals.jsonl"
GOAL_RESULT_INVALID = GOLDEN / "v1" / "invalid" / "correlated" / "plan-goal-results.jsonl"
RESULT_DEFS = {
    "thread/list": "threadListResult",
    "thread/search": "threadPageResult",
    "thread/rename": "threadResult",
    "thread/preferences/update": "threadResult",
    "thread/pin": "threadResult",
    "thread/seen": "threadResult",
    "thread/archive": "threadResult",
    "thread/restore": "threadResult",
    "thread/read": "threadReadResult",
    "tool/artifact/read": "toolArtifactReadResult",
    "turn/change-set/read": "changeSetArtifactReadResult",
    "turn/input/enqueue": "turnInputMutationResult",
    "turn/input/prioritize": "turnInputMutationResult",
    "turn/input/update": "turnInputMutationResult",
    "turn/input/delete": "turnInputMutationResult",
    "workspace/path/search": "workspacePathSearchResult",
    "task/create": "taskCreateResult",
    "task/list": "taskListResult",
    "task/read": "taskReadResult",
    "task/observe": "taskObserveResult",
    "task/unobserve": "taskAcceptedResult",
    "task/seen": "taskMutationResult",
    "thread/message/send": "taskMessageResult",
    "task/followup": "taskFollowupResult",
    "task/cancel": "taskMutationResult",
    "task/tree/delete": "taskTreeDeleteResult",
    "task/close": "taskCloseResult",
    "goal/read": "goalProjectionResult",
    "goal/events/read": "goalEventsResult",
    "goal/observe": "goalObserveResult",
    "goal/unobserve": "taskAcceptedResult",
    "plan/read": "planProjection",
    "plan/revisions/list": "planRevisionsResult",
    "goal/evidence/list": "goalEvidenceResult",
    "goal/create": "goalProjectionResult",
    "goal/plan/attach": "goalProjectionResult",
    "goal/plan/detach": "goalProjectionResult",
    "goal/pause": "goalProjectionResult",
    "goal/resume": "goalProjectionResult",
    "goal/stop": "goalProjectionResult",
    "plan/create": "planProjection",
    "plan/draft/save": "planProjection",
    "plan/draft/discard": "planProjection",
    "plan/propose": "planProjection",
    "plan/execute": "planProjection",
    "interaction/read": "interactionSnapshot",
    "interaction/observe": "interactionObserveResult",
    "interaction/unobserve": "interactionAcceptedResult",
    "interaction/draft/save": "interactionSnapshot",
    "interaction/respond": "interactionSnapshot",
    "interaction/cancel": "interactionSnapshot",
    "plan/observe": "planObserveResult",
    "plan/unobserve": "taskAcceptedResult",
    "plan/events/read": "planEventsResult",
    "plan/evidence/list": "planEvidenceResult",
    "plan/pause": "planProjection",
    "plan/resume": "planProjection",
    "plan/stop": "planProjection",
    "plan/reject": "planProjection",
}
INVALID_RESULT_DEFS = {
    "c:history-value": "threadReadResult",
    "c:history-old-kind": "threadReadResult",
    "c:history-progress-round": "threadReadResult",
    "c:history-final-round": "threadReadResult",
}


def documents(path: Path) -> list[dict[str, Any]]:
    """逐行解析 JSONL，避免把多个 wire frame 合并成一个宽松文档。"""
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def definition_validator(schema: dict[str, Any], name: str) -> Draft202012Validator:
    """复用根 `$defs` 校验一个命名投影，响应无需伪造 method 字段。"""
    return Draft202012Validator(
        {"$schema": schema["$schema"], "$defs": schema["$defs"], "$ref": f"#/$defs/{name}"},
        format_checker=FormatChecker(),
    )


def require_valid(validator: Draft202012Validator, value: Any, label: str) -> None:
    """将首个 schema 漂移压缩成稳定 probe 身份，不打印可能敏感的 payload。"""
    if next(validator.iter_errors(value), None) is not None:
        raise RuntimeError(f"valid projection rejected: {label}")


def require_invalid(validator: Draft202012Validator, value: Any, label: str) -> None:
    """负例必须在对应 production shape 失败，不能只依赖 orphan response correlation。"""
    if next(validator.iter_errors(value), None) is None:
        raise RuntimeError(f"invalid projection accepted: {label}")


def validate_artifact_result(method: str, result: dict[str, Any]) -> None:
    """分别锁定 Tool 字符分页与冻结文件完整 Base64 的语义。"""
    if method == "tool/artifact/read":
        offset = result["offsetCharacters"]
        end = result["totalCharacters"] if result["nextOffsetCharacters"] is None \
            else result["nextOffsetCharacters"]
        if len(result["content"]) != end - offset:
            raise RuntimeError("tool artifact character span mismatch")
        return
    content = result.get("contentBase64")
    try:
        decoded = base64.b64decode(content, validate=True)
    except (binascii.Error, TypeError, ValueError) as error:
        raise RuntimeError("change-set artifact is not standard Base64") from error
    if base64.b64encode(decoded).decode("ascii") != content:
        raise RuntimeError("change-set artifact Base64 is not canonical")
    try:
        decoded.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise RuntimeError("change-set artifact is not UTF-8") from error
    if len(decoded) != result.get("byteLength"):
        raise RuntimeError("change-set artifact byte length mismatch")
    if hashlib.sha256(decoded).hexdigest() != result.get("sha256"):
        raise RuntimeError("change-set artifact digest mismatch")


def compact_json_bytes(value: Any) -> int:
    """按 wire 使用的紧凑 UTF-8 JSON 计费，避免多字节引用与结构开销绕过容量上限。"""
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def validate_content(value: list[dict[str, Any]]) -> None:
    """锁定四类 content 的顺序、去重和发送门禁；真实引用可用性仍由 App Server 校验。"""
    phase = 0
    sendable = False
    text_count = 0
    attachment_ids: set[str] = set()
    workspace_ids: set[str] = set()
    workspace_references: set[tuple[str, str]] = set()
    skill_ids: set[str] = set()
    for block in value:
        block_type = block["type"]
        if block_type == "workspace_reference":
            if phase != 0:
                raise RuntimeError("workspace reference order mismatch")
            workspace_id = block["workspaceId"]
            path = block["relativePath"]
            workspace_ids.add(workspace_id)
            if len(workspace_ids) != 1 or (workspace_id, path) in workspace_references:
                raise RuntimeError("workspace reference identity mismatch")
            workspace_references.add((workspace_id, path))
            sendable = True
        elif block_type == "skill_reference":
            if phase != 0 or block["skillId"] in skill_ids:
                raise RuntimeError("skill reference order or identity mismatch")
            skill_ids.add(block["skillId"])
        elif block_type == "attachment":
            if phase > 1 or block["attachmentId"] in attachment_ids:
                raise RuntimeError("attachment order or identity mismatch")
            phase = 1
            attachment_ids.add(block["attachmentId"])
            sendable = True
        elif block_type == "text":
            phase = 2
            text_count += 1
            if text_count != 1:
                raise RuntimeError("content contains multiple text blocks")
            sendable = True
        else:
            raise RuntimeError("unknown content block")
    if not sendable:
        raise RuntimeError("skill-only content is not sendable")


def validate_attachment_summaries(content: list[dict[str, Any]], summaries: Any) -> None:
    """摘要只能逐项描述同一消息中的附件，禁止错序或借合法元数据替换资源 identity。"""
    attachment_ids = [block["attachmentId"] for block in content if block["type"] == "attachment"]
    if not isinstance(summaries, list) or len(summaries) != len(attachment_ids):
        raise RuntimeError("attachment summary count mismatch")
    if any(not isinstance(summary, dict) or summary.get("attachmentId") != attachment_id
           for attachment_id, summary in zip(attachment_ids, summaries, strict=True)):
        raise RuntimeError("attachment summary identity or order mismatch")


def validate_input_queue(value: dict[str, Any]) -> None:
    """独立锁定结构化内容、唯一身份、优先区顺序与紧凑 JSON 总预算。"""
    turn_id = value["turnId"]
    identifiers: set[str] = set()
    follow_up_seen = False
    queued_bytes = 0
    for item in value["items"]:
        if item["turnId"] != turn_id or item["inputId"] in identifiers:
            raise RuntimeError("input queue identity mismatch")
        identifiers.add(item["inputId"])
        if item["kind"] == "follow_up":
            follow_up_seen = True
        elif follow_up_seen:
            raise RuntimeError("steering item followed a follow-up")
        validate_content(item["content"])
        validate_attachment_summaries(item["content"], item.get("attachments"))
        queued_bytes += compact_json_bytes(item["content"])
    if queued_bytes > 524288:
        raise RuntimeError("input queue exceeded its UTF-8 byte budget")


def validate_queue_contract(schema: dict[str, Any], root: Draft202012Validator) -> int:
    """消费完整队列 transcript，并冻结方法、事件、结果及容量常量的共同理解。"""
    pending: dict[str, str] = {}
    observed: set[str] = set()
    frames = documents(QUEUE_VALID)
    for frame in frames:
        require_valid(root, frame, str(frame.get("method", frame.get("id", "response"))))
        method = frame.get("method")
        if isinstance(method, str) and "id" in frame:
            pending[frame["id"]] = method
            observed.add(method)
        elif isinstance(method, str):
            observed.add(method)
            validate_input_queue(frame["params"]["inputQueue"])
        elif "result" in frame:
            correlated = pending.pop(frame["id"])
            definition = RESULT_DEFS.get(correlated)
            if definition is not None:
                require_valid(definition_validator(schema, definition), frame["result"], correlated)
            queue = frame["result"].get("inputQueue")
            if queue is not None:
                validate_input_queue(queue)
        elif "error" in frame:
            pending.pop(frame["id"])
    expected = {"turn/input/enqueue", "turn/input/prioritize", "turn/input/update", "turn/input/delete",
                "turn/input-queue-changed", "turn/input-consumed", "thread/read"}
    if not expected <= observed or pending:
        raise RuntimeError("positive corpus lacks the complete input queue contract")
    definitions = schema["$defs"]
    content_parts = definitions["turnContentPart"]["oneOf"]
    text_part = next(part for part in content_parts if part["properties"]["type"].get("const") == "text")
    if text_part["properties"]["text"].get("maxLength") != 4000000 \
            or definitions["turnContent"].get("maxItems") != 64 \
            or definitions["inputQueue"]["properties"]["items"].get("maxItems") != 8 \
            or definitions["limits"]["properties"]["maxTurnQueuedInputs"].get("const") != 8 \
            or definitions["limits"]["properties"]["maxTurnQueuedInputBytes"].get("const") != 524288:
        raise RuntimeError("input queue capacity constants drifted")
    return len(frames)


def validate_workspace_path_search_contract(schema: dict[str, Any], root: Draft202012Validator) -> int:
    """独立消费搜索请求/响应，并确认绝对路径与响应扩展字段在方法相关投影被拒绝。"""
    frames = documents(PATH_SEARCH_VALID)
    if len(frames) != 2:
        raise RuntimeError("workspace path search transcript is incomplete")
    request, response = frames
    require_valid(root, request, "workspace/path/search request")
    if request.get("id") != response.get("id") or "result" not in response:
        raise RuntimeError("workspace path search response lost correlation")
    result_validator = definition_validator(schema, "workspacePathSearchResult")
    require_valid(result_validator, response["result"], "workspace/path/search result")

    invalid_frames = documents(PATH_SEARCH_INVALID)
    if len(invalid_frames) % 2 != 0:
        raise RuntimeError("workspace path search invalid transcript is not correlated")
    for index in range(0, len(invalid_frames), 2):
        invalid_request, invalid_response = invalid_frames[index:index + 2]
        require_valid(root, invalid_request, "workspace/path/search invalid precondition")
        if invalid_request.get("id") != invalid_response.get("id") or "result" not in invalid_response:
            raise RuntimeError("workspace path search invalid response lost correlation")
        require_invalid(result_validator, invalid_response["result"], "workspace/path/search")
    return len(frames) + len(invalid_frames)


def validate_thread_seen_contract(schema: dict[str, Any], root: Draft202012Validator) -> int:
    """独立消费 Thread transcript，锁定 seen 请求、完整结果与无 Turn 时的已读不变量。"""
    pending: dict[str, str] = {}
    seen_result = False
    frames = documents(THREAD_VALID)
    for frame in frames:
        require_valid(root, frame, str(frame.get("method", frame.get("id", "response"))))
        method = frame.get("method")
        if isinstance(method, str) and "id" in frame:
            pending[frame["id"]] = method
        elif "result" in frame:
            correlated = pending.pop(frame["id"])
            definition = RESULT_DEFS.get(correlated)
            if definition is not None:
                require_valid(definition_validator(schema, definition), frame["result"], correlated)
            if correlated == "thread/seen":
                if frame["result"].get("latestTurnSeen") is not True:
                    raise RuntimeError("thread/seen did not persist the latest Turn boundary")
                seen_result = True
    if not seen_result:
        raise RuntimeError("positive corpus lacks thread/seen result coverage")

    invalid_frames = documents(THREAD_INVALID)
    if len(invalid_frames) % 2 != 0:
        raise RuntimeError("thread/seen invalid transcript is not correlated")
    for index in range(0, len(invalid_frames), 2):
        request, response = invalid_frames[index:index + 2]
        require_valid(root, request, "thread/seen invalid precondition")
        if request.get("id") != response.get("id") or "result" not in response:
            raise RuntimeError("thread/seen invalid response lost correlation")
        require_invalid(definition_validator(schema, "threadResult"), response["result"], "thread/seen")
    return len(frames) + len(invalid_frames)


def validate_task_threads_contract(schema: dict[str, Any], root: Draft202012Validator) -> int:
    """独立消费完整 Task Thread transcript，锁定十个方法、三个事件及严格结果闭集。"""
    pending: dict[str, str] = {}
    observed_methods: set[str] = set()
    observed_events: set[str] = set()
    frames = documents(TASK_VALID)
    for frame in frames:
        require_valid(root, frame, str(frame.get("method", frame.get("id", "response"))))
        method = frame.get("method")
        if isinstance(method, str) and "id" in frame:
            pending[frame["id"]] = method
            observed_methods.add(method)
        elif isinstance(method, str):
            observed_events.add(method)
        elif "result" in frame:
            correlated = pending.pop(frame["id"])
            require_valid(definition_validator(schema, RESULT_DEFS[correlated]), frame["result"], correlated)
    expected_methods = {
        "task/create", "task/list", "task/read", "task/observe", "task/unobserve", "task/seen",
        "thread/message/send", "task/followup", "task/cancel", "task/tree/delete", "task/close",
    }
    expected_events = {"task/activity", "task/progress", "task/mailbox-changed"}
    if observed_methods != expected_methods or observed_events != expected_events or pending:
        raise RuntimeError("task thread corpus does not cover the exact v1 surface")

    for frame in documents(TASK_INVALID):
        schema_rejected = next(root.iter_errors(frame), None) is not None
        confirmation_rejected = frame.get("method") == "task/tree/delete" \
            and frame.get("params", {}).get("confirmTaskThreadId") != frame.get("params", {}).get("taskThreadId")
        if not schema_rejected and not confirmation_rejected:
            raise RuntimeError(f"invalid projection accepted: {frame.get('method', 'task invalid')}")

    invalid_results = documents(TASK_RESULT_INVALID)
    if len(invalid_results) % 2 != 0:
        raise RuntimeError("task result invalid transcript is not correlated")
    for index in range(0, len(invalid_results), 2):
        request, response = invalid_results[index:index + 2]
        require_valid(root, request, "task invalid result precondition")
        if request.get("id") != response.get("id") or "result" not in response:
            raise RuntimeError("task invalid result lost correlation")
        require_invalid(
            definition_validator(schema, RESULT_DEFS[request["method"]]),
            response["result"],
            request["method"],
        )
    return len(frames) + len(documents(TASK_INVALID)) + len(invalid_results)


def validate_plan_goal_contract(schema: dict[str, Any], root: Draft202012Validator) -> int:
    """独立消费 Goal/Plan transcript，锁定 21 个方法、3 个事件及每种严格结果投影。"""
    pending: dict[str, str] = {}
    observed_methods: set[str] = set()
    observed_events: set[str] = set()
    frames = documents(GOAL_VALID)
    for frame in frames:
        require_valid(root, frame, str(frame.get("method", frame.get("id", "response"))))
        method = frame.get("method")
        if isinstance(method, str) and "id" in frame:
            pending[frame["id"]] = method
            observed_methods.add(method)
        elif isinstance(method, str):
            observed_events.add(method)
        elif "result" in frame:
            correlated = pending.pop(frame["id"])
            require_valid(definition_validator(schema, RESULT_DEFS[correlated]), frame["result"], correlated)

    expected_methods = {
        "goal/read", "goal/events/read", "goal/observe", "goal/unobserve", "plan/read",
        "plan/revisions/list", "goal/evidence/list", "goal/create", "goal/plan/attach",
        "goal/plan/detach", "goal/pause", "goal/resume", "goal/stop",
        "plan/create", "plan/draft/save", "plan/draft/discard", "plan/propose", "plan/execute",
        "plan/observe", "plan/unobserve", "plan/events/read", "plan/evidence/list", "plan/pause",
        "plan/resume", "plan/stop", "plan/reject", "interaction/read", "interaction/observe",
        "interaction/unobserve", "interaction/draft/save", "interaction/respond", "interaction/cancel",
    }
    expected_events = {"goal/changed", "goal/activity", "interaction/changed", "plan/changed"}
    if observed_methods != expected_methods or observed_events != expected_events or pending:
        raise RuntimeError("plan goal corpus does not cover the exact v1 surface")

    invalid_frames = documents(GOAL_INVALID)
    for frame in invalid_frames:
        require_invalid(root, frame, str(frame.get("method", "goal invalid")))
    invalid_results = documents(GOAL_RESULT_INVALID)
    if len(invalid_results) % 2 != 0:
        raise RuntimeError("plan goal invalid result transcript is not correlated")
    for index in range(0, len(invalid_results), 2):
        request, response = invalid_results[index:index + 2]
        require_valid(root, request, str(request.get("method", "goal invalid request")))
        if request.get("id") != response.get("id") or "result" not in response:
            raise RuntimeError("plan goal invalid response lost correlation")
        require_invalid(definition_validator(schema, RESULT_DEFS[request["method"]]),
                        response["result"], request["method"])
    return len(frames) + len(invalid_frames) + len(invalid_results)


def validate_change_set_file_boundaries(schema: dict[str, Any]) -> int:
    """独立证明完整文件结果的编码语义、2 MiB 边界和 4 MiB envelope 预算。"""
    result_validator = definition_validator(schema, "changeSetArtifactReadResult")
    utf8_content = "你好".encode("utf-8")
    valid = {
        "artifactId": "artifact_demo",
        "filePath": "src/main.ts",
        "byteLength": len(utf8_content),
        "sha256": hashlib.sha256(utf8_content).hexdigest(),
        "contentBase64": base64.b64encode(utf8_content).decode("ascii"),
    }
    require_valid(result_validator, valid, "change-set utf8 result")
    validate_artifact_result("turn/change-set/read", valid)

    malformed = [
        {**valid, "contentBase64": "eA="},
        {**valid, "contentBase64": "eB==", "byteLength": 1},
        {**valid, "contentBase64": "eA-_", "byteLength": 3},
        {**valid, "contentBase64": "eA==\n", "byteLength": 1},
        {**valid, "sha256": valid["sha256"].upper()},
    ]
    for result in malformed:
        require_invalid(result_validator, result, "malformed change-set result")

    semantic_failures = [
        {**valid, "byteLength": valid["byteLength"] + 1},
        {**valid, "sha256": "0" * 64},
        {
            **valid,
            "byteLength": 1,
            "sha256": hashlib.sha256(b"\xff").hexdigest(),
            "contentBase64": "/w==",
        },
    ]
    for result in semantic_failures:
        require_valid(result_validator, result, "semantic change-set failure fixture")
        try:
            validate_artifact_result("turn/change-set/read", result)
        except RuntimeError:
            pass
        else:
            raise RuntimeError("invalid change-set semantic file semantics were accepted")


    maximum_content = b"a" * 2_097_152
    maximum_result = {
        "artifactId": "artifact_" + "a" * 96,
        "filePath": "a" * 4_096,
        "byteLength": len(maximum_content),
        "sha256": hashlib.sha256(maximum_content).hexdigest(),
        "contentBase64": base64.b64encode(maximum_content).decode("ascii"),
    }
    if len(maximum_result["contentBase64"]) != 2_796_204:
        raise RuntimeError("2 MiB Base64 length drifted")
    require_valid(result_validator, maximum_result, "maximum change-set result")
    validate_artifact_result("turn/change-set/read", maximum_result)
    envelope = {"jsonrpc": "2.0", "id": "c:max", "result": maximum_result}
    if compact_json_bytes(envelope) >= 4 * 1_024 * 1_024:
        raise RuntimeError("maximum change-set result exceeds the frame budget")
    oversized_content = b"a" * 2_097_153
    oversized = {
        **maximum_result,
        "byteLength": len(oversized_content),
        "sha256": hashlib.sha256(oversized_content).hexdigest(),
        "contentBase64": base64.b64encode(oversized_content).decode("ascii"),
    }
    require_invalid(result_validator, oversized, "oversized change-set result")
    return 1 + len(malformed) + len(semantic_failures) + 2


def main() -> int:
    """消费正例 transcript 与专用负例，证明 Python 对新合同的独立理解。"""
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    root = Draft202012Validator(schema, format_checker=FormatChecker())
    pending: dict[str, str] = {}
    observed: set[str] = set()
    positive = documents(VALID)
    for frame in positive:
        require_valid(root, frame, str(frame.get("method", frame.get("id", "response"))))
        if "method" in frame and "id" in frame:
            pending[frame["id"]] = frame["method"]
            observed.add(frame["method"])
        elif "method" in frame:
            observed.add(frame["method"])
        elif "result" in frame:
            method = pending.pop(frame["id"])
            require_valid(definition_validator(schema, RESULT_DEFS[method]), frame["result"], method)
            if method in {"tool/artifact/read", "turn/change-set/read"}:
                validate_artifact_result(method, frame["result"])
    expected = {"assistant/model-step-committed", "tool/started", "tool/batch-committed", "thread/read",
                "tool/artifact/read", "turn/change-set/read"}
    if not expected <= observed:
        raise RuntimeError("positive corpus lacks Agent process methods")

    negative = documents(INVALID)
    for frame in negative:
        if "method" in frame:
            require_invalid(root, frame, str(frame["method"]))
        else:
            definition = INVALID_RESULT_DEFS.get(frame.get("id"))
            if definition is None:
                raise RuntimeError("invalid result lacks an explicit projection owner")
            require_invalid(definition_validator(schema, definition), frame["result"], definition)
    queue_frames = validate_queue_contract(schema, root)
    thread_frames = validate_thread_seen_contract(schema, root)
    path_search_frames = validate_workspace_path_search_contract(schema, root)
    task_frames = validate_task_threads_contract(schema, root)
    goal_frames = validate_plan_goal_contract(schema, root)
    change_set_cases = validate_change_set_file_boundaries(schema)
    print(f"PYTHON_CONSUMER_OK positiveFrames={len(positive)} invalidFrames={len(negative)} "
          f"queueFrames={queue_frames} threadFrames={thread_frames} pathSearchFrames={path_search_frames} "
          f"taskFrames={task_frames} goalFrames={goal_frames} changeSetCases={change_set_cases}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
