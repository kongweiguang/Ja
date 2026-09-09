# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate the breaking JA RPC v1 schema, golden frames, and Secret boundary."""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import re
from pathlib import Path
from typing import Any, Iterable

from jsonschema import Draft202012Validator, FormatChecker


CONTRACTS = Path(__file__).resolve().parents[1]
GOLDEN = Path(__file__).resolve().parent
V1_GOLDEN = GOLDEN / "v1"
SCHEMA_PATH = CONTRACTS / "ja-rpc" / "v1" / "schema" / "ja-rpc-v1.schema.json"
ERROR_CATALOG_PATH = CONTRACTS / "ja-rpc" / "v1" / "error-catalog.json"
SECRET_KEYS = {"secret", "secretvalue", "credentialvalue", "token", "tokenvalue", "password", "authorization", "apikey", "api_key"}


class CorpusError(RuntimeError):
    """表示可复核的语料失败，错误文本不展开 fixture 的敏感值。"""


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    """拒绝重复 key，避免 Java/Rust/TypeScript 选择不同胜出值。"""
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CorpusError("duplicate JSON key")
        result[key] = value
    return result


def parse_json(raw: str) -> dict[str, Any]:
    """严格解析一条 JSON frame，不允许 scalar 或重复字段。"""
    try:
        value = json.loads(raw, object_pairs_hook=reject_duplicate_keys)
    except (UnicodeError, json.JSONDecodeError, CorpusError) as error:
        raise CorpusError("invalid JSON") from error
    if not isinstance(value, dict):
        raise CorpusError("frame is not an object")
    return value


def raw_documents(path: Path) -> Iterable[dict[str, Any]]:
    """按 JSON/JSONL 边界逐条读取 corpus。"""
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".json":
        yield parse_json(text)
        return
    for line in text.splitlines():
        if line.strip():
            yield parse_json(line)


def corpus_files(invalid: bool) -> list[Path]:
    """只选择 v1 子目录，目录外的历史 fixture 不会成为当前协议输入。"""
    if not V1_GOLDEN.is_dir():
        return []
    return sorted(
        path for path in V1_GOLDEN.rglob("*")
        if path.is_file() and path.suffix in {".json", ".jsonl"}
        and (("invalid" in path.relative_to(V1_GOLDEN).parts) == invalid)
    )


def corpus_digest(paths: list[Path]) -> str:
    """按全局相对路径顺序哈希原始 bytes，确保 validator 与三端 gate 回传同一 digest。"""
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda value: value.relative_to(GOLDEN).as_posix()):
        digest.update(path.relative_to(GOLDEN).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def load_catalog() -> dict[int, tuple[str, str, bool]]:
    """从错误目录读取唯一 code/category 闭集，并校验每个 tuple 的结构与唯一性。"""
    value = parse_json(ERROR_CATALOG_PATH.read_text(encoding="utf-8"))
    categories = value.get("categories")
    if set(value) != {"schemaVersion", "categories", "errors"} \
            or value.get("schemaVersion") != 2 or not isinstance(categories, list) \
            or not categories or len(categories) != len(set(categories)) \
            or any(not isinstance(category, str) or re.fullmatch(r"[a-z][a-z_]{1,31}", category) is None
                   for category in categories) \
            or not isinstance(value.get("errors"), list):
        raise CorpusError("invalid v1 error catalog")
    result: dict[int, tuple[str, str, bool]] = {}
    names: set[str] = set()
    for item in value["errors"]:
        if set(item) != {"code", "errorCode", "category", "retryable"}:
            raise CorpusError("invalid error tuple")
        code, name = item["code"], item["errorCode"]
        category, retryable = item["category"], item["retryable"]
        if type(code) is not int or code in result or not isinstance(name, str) or name in names \
                or re.fullmatch(r"[A-Z][A-Z0-9_]{1,63}", name) is None \
                or category not in categories or type(retryable) is not bool:
            raise CorpusError("invalid error tuple")
        result[code] = (name, category, retryable)
        names.add(name)
    return result


def sub_validator(schema: dict[str, Any], definition: str) -> Draft202012Validator:
    """Validates one named method shape against the schema's shared definitions."""
    return Draft202012Validator({"$schema": schema["$schema"], "$defs": schema["$defs"],
                                 "$ref": f"#/$defs/{definition}"}, format_checker=FormatChecker())


PARAM_DEFS = {
    "runtime/initialize": "initializeParams", "runtime/health": "emptyParams", "runtime/shutdown": "emptyParams",
    "workspace/open": "workspaceOpenParams", "workspace/open-general": "emptyParams",
    "workspace/path/search": "workspacePathSearchParams",
    "workspace/set-trust": "workspaceTrustParams",
    "workspace/unregister": "workspaceUnregisterParams", "thread/create": "threadCreateParams",
    "thread/read": "threadReadParams", "thread/seen": "threadMutationParams",
    "thread/archive": "threadMutationParams", "thread/restore": "threadMutationParams",
    "thread/delete": "threadMutationParams", "thread/compact": "threadCompactParams",
    "goal/read": "goalReadParams", "plan/read": "planReadParams",
    "goal/events/read": "goalPageParams", "plan/revisions/list": "planPageParams",
    "goal/observe": "goalObserveParams", "goal/unobserve": "goalUnobserveParams",
    "goal/evidence/list": "goalEvidenceListParams", "goal/create": "goalCreateParams",
    "goal/plan/attach": "goalPlanAttachParams", "goal/plan/detach": "goalMutationParams",
    "goal/pause": "goalMutationParams", "goal/resume": "goalMutationParams",
    "goal/stop": "goalMutationParams", "goal/input/respond": "goalInputRespondParams",
    "plan/create": "planCreateParams", "plan/draft/save": "planDraftSaveParams",
    "plan/draft/discard": "planMutationParams", "plan/propose": "planMutationParams",
    "plan/approve": "planApprovalBindingParams", "plan/execute": "planApprovalBindingParams",
    "plan/reject": "planRejectParams",
    "attachment/import": "attachmentImportParams", "attachment/discard": "attachmentDiscardParams",
    "turn/start": "turnStartParams", "turn/resume": "turnResumeParams",
    "turn/cancel": "turnCancelParams", "turn/input/enqueue": "turnInputEnqueueParams",
    "turn/input/prioritize": "turnInputPrioritizeParams", "turn/input/update": "turnInputUpdateParams",
    "turn/input/delete": "turnInputDeleteParams", "turn/change-set/read": "changeSetArtifactReadParams",
    "approval/respond": "approvalRespondParams",
    "configuration/read": "configurationReadParams", "configuration/patch": "configurationPatchParams",
    "configuration/replace": "configurationReplaceParams", "configuration/reset": "configurationResetParams",
    "credential/set": "credentialSetParams", "credential/delete": "credentialDeleteParams",
}
EVENT_PARAM_DEFS: dict[str, str] = {
    "turn/state-changed": "turnStateChangedParams",
    "turn/input-queue-changed": "turnInputQueueChangedParams",
    "turn/input-consumed": "turnInputConsumedParams",
    "tool/started": "toolStartedParams",
    "context/compaction-started": "contextCompactionStartedParams",
    "context/compacted": "contextCompactedParams",
    "context/compaction-failed": "contextCompactionFailedParams",
    "turn/terminal": "turnTerminalParams",
    "task/activity": "taskActivityParams",
    "task/progress": "taskProgressParams",
    "task/mailbox-changed": "taskMailboxChangedParams",
    "goal/changed": "goalChangedParams",
    "goal/activity": "goalActivityParams",
    "goal/input-requested": "goalInputRequestedParams",
}

RESULT_DEFS = {
    "workspace/list": "workspacePageResult",
    "workspace/path/search": "workspacePathSearchResult",
    "thread/list": "threadPageResult", "thread/search": "threadPageResult",
    "thread/create": "threadResult", "thread/rename": "threadResult",
    "thread/preferences/update": "threadResult", "thread/pin": "threadResult",
    "thread/seen": "threadResult", "thread/archive": "threadResult",
    "thread/restore": "threadResult",
    "thread/read": "threadReadResult",
    "goal/read": "goalProjectionResult",
    "goal/events/read": "goalEventsResult",
    "goal/observe": "goalObserveResult",
    "goal/unobserve": "taskAcceptedResult",
    "plan/read": "planProjection",
    "plan/revisions/list": "planRevisionsResult",
    "goal/evidence/list": "goalEvidenceResult",
    "goal/create": "goalProjectionResult", "goal/plan/attach": "goalProjectionResult",
    "goal/plan/detach": "goalProjectionResult", "goal/pause": "goalProjectionResult",
    "goal/resume": "goalProjectionResult", "goal/stop": "goalProjectionResult",
    "goal/input/respond": "goalProjectionResult", "plan/create": "planProjection",
    "plan/draft/save": "planProjection", "plan/draft/discard": "planProjection",
    "plan/propose": "planProjection", "plan/approve": "planProjection",
    "plan/execute": "planProjection", "plan/reject": "planProjection",
    "task/create": "taskCreateResult",
    "task/list": "taskListResult",
    "task/read": "taskReadResult",
    "task/observe": "taskObserveResult",
    "task/unobserve": "taskAcceptedResult",
    "task/seen": "taskMutationResult",
    "task/message/send": "taskMessageResult",
    "task/followup": "taskFollowupResult",
    "task/cancel": "taskMutationResult",
    "task/tree/delete": "taskTreeDeleteResult",
    "skill/list": "skillPageResult",
    "mcp/list": "mcpPageResult",
    "mcp/list-tools": "mcpToolsResult",
    "workspace/open-general": "workspaceResult",
    "thread/compact": "threadCompactResult",
    "attachment/import": "attachmentResult", "attachment/discard": "attachmentResult",
    "turn/start": "turnAcceptedResult", "turn/resume": "turnResumeResult",
    "turn/cancel": "turnCancelResult",
    "turn/input/enqueue": "turnInputMutationResult",
    "turn/input/prioritize": "turnInputMutationResult",
    "turn/input/update": "turnInputMutationResult",
    "turn/input/delete": "turnInputMutationResult",
    "turn/change-set/read": "changeSetArtifactReadResult",
    "configuration/read": "configReadResult",
    "configuration/patch": "configMutationResult",
    "configuration/replace": "configMutationResult",
    "configuration/reset": "configMutationResult",
    "credential/set": "credentialMutationResult",
    "credential/delete": "credentialMutationResult",
}


def has_secret(value: Any, allowed: bool = False) -> bool:
    """递归检查 Secret 名称，只有 credential/set.secret 请求可进入该边界。"""
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = key.lower()
            if normalized in SECRET_KEYS:
                if not (allowed and normalized == "secret"):
                    return True
            if has_secret(child, allowed):
                return True
    elif isinstance(value, list):
        return any(has_secret(child, allowed) for child in value)
    return False


def validate_secret_boundary(frame: dict[str, Any]) -> None:
    """禁止协议帧泄漏 Secret，同时区分附件预览的严格资源授权判别对象。"""
    method = frame.get("method")
    allowed = method == "credential/set" and isinstance(frame.get("params"), dict)
    params = frame.get("params")
    if method == "attachment/preview/open" and isinstance(params, dict):
        params = {key: value for key, value in params.items() if key != "authorization"}
    if has_secret(params, allowed):
        raise CorpusError("secret crossed v1 boundary")
    if method == "configuration/patch" and isinstance(frame.get("params"), dict):
        path = str(frame["params"].get("path", "")).lower()
        if any(token in path for token in ("secret", "token", "password", "authorization", "api_key", "apikey")):
            raise CorpusError("secret-shaped config path crossed boundary")


def contract_vocabulary(schema: dict[str, Any], definition: str) -> set[str]:
    """只从 schema 的命名闭集读取方法或事件，避免 validator 维护第二份词汇表。"""
    values = schema.get("$defs", {}).get(definition, {}).get("enum")
    if not isinstance(values, list) or not values or len(values) != len(set(values)) \
            or any(not isinstance(value, str) for value in values):
        raise CorpusError(f"invalid schema vocabulary: {definition}")
    return set(values)


def validate_frame(frame: dict[str, Any], schema: dict[str, Any],
                   catalog: dict[int, tuple[str, str, bool]], methods: set[str], events: set[str]) -> None:
    """执行 schema、方向闭集、host body、v1 cwd/credential 边界和错误目录校验。"""
    errors = list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(frame))
    if errors:
        raise CorpusError("schema rejection")
    validate_secret_boundary(frame)
    if "method" in frame:
        method = frame["method"]
        if method not in methods and method not in events:
            raise CorpusError("method outside v1 closure")
        if "id" in frame:
            identifier = frame["id"]
            if not identifier.startswith("c:"):
                raise CorpusError("client request must use c namespace")
        params = frame.get("params", {})
        if method in PARAM_DEFS:
            if list(sub_validator(schema, PARAM_DEFS[method]).iter_errors(params)):
                raise CorpusError("method params rejected")
        if method in EVENT_PARAM_DEFS:
            if list(sub_validator(schema, EVENT_PARAM_DEFS[method]).iter_errors(params)):
                raise CorpusError("event params rejected")
        if method == "task/tree/delete" \
                and params.get("confirmTaskThreadId") != params.get("taskThreadId"):
            raise CorpusError("task tree confirmation identity mismatch")
        if method in {"turn/input-queue-changed", "turn/input-consumed"}:
            validate_input_event(method, params)
        if method == "runtime/initialize" and (
            params.get("protocolMajor") != 1 or params.get("protocolMinor") != 0
        ):
            raise CorpusError("wrong protocol major")
        if method == "turn/start" and any(key in params for key in ("cwd", "profileId", "configRevision")):
            raise CorpusError("turn override crossed v1 boundary")
        if method == "turn/start":
            validate_turn_content(params.get("content"))
        if method in {"turn/input/enqueue", "turn/input/update"}:
            validate_turn_content(params.get("content"), queued=True)
        if method == "configuration/replace":
            validate_config_document(params.get("document"))
        if method == "configuration/changed":
            if "cwd" in params:
                raise CorpusError("config change leaked cwd")
            if params.get("scope") == "user" and "workspaceId" in params:
                raise CorpusError("user config change carried workspace identity")
            if params.get("scope") == "project" and "workspaceId" not in params:
                raise CorpusError("project config change missed workspace identity")
        if method == "tool/batch-committed" and isinstance(params, dict):
            if any("fileChanges" in result for result in params.get("results", []) if isinstance(result, dict)):
                raise CorpusError("fileChanges projection was removed")
        if method == "context/compacted" and params["inputTokensAfter"] >= params["inputTokensBefore"]:
            raise CorpusError("context compaction event did not reduce input tokens")
    elif "error" in frame:
        error = frame["error"]
        if not isinstance(error, dict) or not isinstance(error.get("code"), int):
            raise CorpusError("invalid error envelope")
        data = error.get("data", {})
        expected = catalog.get(error["code"])
        if expected is None or not isinstance(data, dict) \
                or (data.get("errorCode"), data.get("category"), data.get("retryable")) != expected:
            raise CorpusError("error outside v1 catalog")


def validate_turn_content(value: Any, queued: bool = False) -> None:
    """锁定引用去重、块顺序与队列字节预算，避免三端各自解释同一消息。"""
    if not isinstance(value, list) or not 1 <= len(value) <= 64:
        raise CorpusError("turn content is invalid")
    attachment_ids = [item.get("attachmentId") for item in value
                      if isinstance(item, dict) and item.get("type") == "attachment"]
    if len(attachment_ids) > 10 or len(attachment_ids) != len(set(attachment_ids)):
        raise CorpusError("turn attachment limit or uniqueness is invalid")
    skill_ids = [item.get("skillId") for item in value
                 if isinstance(item, dict) and item.get("type") == "skill_reference"]
    workspace_paths = [(item.get("workspaceId"), item.get("relativePath")) for item in value
                       if isinstance(item, dict) and item.get("type") == "workspace_reference"]
    if len(skill_ids) != len(set(skill_ids)) or len(workspace_paths) != len(set(workspace_paths)):
        raise CorpusError("turn reference identity is not unique")
    if len({workspace_id for workspace_id, _ in workspace_paths}) > 1:
        raise CorpusError("turn content crosses workspace identities")
    ranks = {"workspace_reference": 0, "skill_reference": 0, "attachment": 1, "text": 2}
    kinds = [item.get("type") for item in value if isinstance(item, dict)]
    if kinds != sorted(kinds, key=lambda kind: ranks.get(kind, 99)) or kinds.count("text") > 1:
        raise CorpusError("turn content ordering is invalid")
    if not any(kind in {"text", "attachment", "workspace_reference"} for kind in kinds):
        raise CorpusError("turn content lacks a sendable block")
    if queued and len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > 524288:
        raise CorpusError("queued content byte budget exceeded")


def validate_config_document(value: Any) -> None:
    """补充 JSON Schema 无法表达的 Provider credential ID 唯一性约束。"""
    if not isinstance(value, dict) or not isinstance(value.get("providers"), list):
        raise CorpusError("configuration document is invalid")
    credential_ids = [provider.get("credential_id") for provider in value["providers"]
                      if isinstance(provider, dict) and "credential_id" in provider]
    if len(credential_ids) != len(set(credential_ids)):
        raise CorpusError("provider credential identity is shared")


def validate_input_queue(value: Any, expected_turn_id: str | None = None) -> None:
    """锁定 JSON Schema 之外的队列身份、权威顺序与 UTF-8 总预算。"""
    if not isinstance(value, dict) or not isinstance(value.get("items"), list):
        raise CorpusError("input queue is invalid")
    turn_id = value.get("turnId")
    if expected_turn_id is not None and turn_id != expected_turn_id:
        raise CorpusError("input queue turn identity mismatch")
    identifiers: set[str] = set()
    follow_up_seen = False
    total_bytes = 0
    for item in value["items"]:
        if not isinstance(item, dict) or item.get("turnId") != turn_id:
            raise CorpusError("queued input turn identity mismatch")
        identifier = item.get("inputId")
        if not isinstance(identifier, str) or identifier in identifiers:
            raise CorpusError("queued input identity is not unique")
        identifiers.add(identifier)
        kind = item.get("kind")
        if kind == "follow_up":
            follow_up_seen = True
        elif kind == "steering" and follow_up_seen:
            raise CorpusError("input queue order is not authoritative")
        content = item.get("content")
        validate_turn_content(content, queued=True)
        validate_attachment_summaries(content, item.get("attachments"))
        total_bytes += len(json.dumps(content, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    if total_bytes > 524288:
        raise CorpusError("input queue byte budget exceeded")


def validate_input_event(method: str, params: dict[str, Any]) -> None:
    """校验队列事件中的前后状态与 Timeline 事实共享同一 Turn 和结构化内容。"""
    validate_input_queue(params.get("inputQueue"), params.get("turnId"))
    if method != "turn/input-consumed":
        return
    queued_input = params.get("input")
    user_item = params.get("userItem")
    if not isinstance(queued_input, dict) or not isinstance(user_item, dict) \
            or queued_input.get("turnId") != params.get("turnId") \
            or user_item.get("turnId") != params.get("turnId") \
            or queued_input.get("content") != user_item.get("content") \
            or queued_input.get("attachments") != user_item.get("attachments"):
        raise CorpusError("consumed input did not atomically become a user item")
    if any(item.get("inputId") == queued_input.get("inputId")
           for item in params["inputQueue"]["items"] if isinstance(item, dict)):
        raise CorpusError("consumed input remained queued")


def validate_attachment_summaries(content: Any, summaries: Any) -> None:
    """摘要数量与顺序必须精确对应 attachment block，避免授权另一附件的预览。"""
    if not isinstance(content, list) or not isinstance(summaries, list):
        raise CorpusError("attachment summaries are invalid")
    attachment_ids = [block.get("attachmentId") for block in content
                      if isinstance(block, dict) and block.get("type") == "attachment"]
    if len(attachment_ids) != len(summaries):
        raise CorpusError("attachment summary count mismatch")
    for attachment_id, summary in zip(attachment_ids, summaries, strict=True):
        if not isinstance(summary, dict) or summary.get("attachmentId") != attachment_id:
            raise CorpusError("attachment summary identity or order mismatch")


def validate_change_set_file(result: dict[str, Any]) -> None:
    """独立解码冻结文件，锁定 canonical Base64、UTF-8、长度与摘要是同一组 bytes。"""
    content = result.get("contentBase64")
    try:
        decoded = base64.b64decode(content, validate=True)
    except (binascii.Error, TypeError, ValueError) as error:
        raise CorpusError("change-set content is not standard Base64") from error
    if base64.b64encode(decoded).decode("ascii") != content:
        raise CorpusError("change-set content Base64 is not canonical")
    try:
        decoded.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise CorpusError("change-set content is not UTF-8") from error
    if len(decoded) != result.get("byteLength"):
        raise CorpusError("change-set content byte length mismatch")
    if hashlib.sha256(decoded).hexdigest() != result.get("sha256"):
        raise CorpusError("change-set content digest mismatch")


def validate_result(method: str, result: Any, schema: dict[str, Any]) -> None:
    """按请求方法绑定成功结果，并在漂移时保留方法与首个字段路径以缩短三端合同定位。"""
    if method == "runtime/initialize":
        if not isinstance(result, dict) or not isinstance(result.get("capabilities"), dict):
            raise CorpusError("initialize result lacks capabilities")
    definition = RESULT_DEFS.get(method)
    if definition is None:
        return
    errors = list(sub_validator(schema, definition).iter_errors(result))
    if errors:
        first = errors[0]
        location = ".".join(str(part) for part in first.absolute_path) or "<root>"
        raise CorpusError(f"result schema rejection method={method} path={location}: {first.message}")
    if method == "credential/set" and result.get("configured") is not True:
        raise CorpusError("credential/set result did not report configured=true")
    if method == "credential/delete" and result.get("configured") is not False:
        raise CorpusError("credential/delete result did not report configured=false")
    if method == "thread/read":
        queue = result.get("inputQueue")
        if queue is not None:
            validate_input_queue(queue)
            if queue["turnId"] not in {turn.get("turnId") for turn in result["turns"]
                                       if isinstance(turn, dict)}:
                raise CorpusError("thread/read input queue lacks its active Turn")
        for item in result.get("items", []):
            if isinstance(item, dict) and item.get("kind") == "user_input":
                validate_attachment_summaries(item.get("content"), item.get("attachments"))
    if method == "thread/seen" and result.get("latestTurnSeen") is not True:
        raise CorpusError("thread/seen did not advance the durable seen boundary")
    if method.startswith("turn/input/"):
        validate_input_queue(result.get("inputQueue"))
        identifiers = {item["inputId"] for item in result["inputQueue"]["items"]}
        if method == "turn/input/delete" and result.get("inputId") in identifiers:
            raise CorpusError("deleted input remained queued")
        if method != "turn/input/delete" and result.get("inputId") not in identifiers:
            raise CorpusError("mutated input is missing from queue")
    if method == "thread/compact":
        before = result["inputTokensBefore"]
        after = result["inputTokensAfter"]
        if result["outcome"] == "compacted" and after >= before:
            raise CorpusError("compacted result did not reduce input tokens")
        if result["outcome"] == "unchanged" and after != before:
            raise CorpusError("unchanged result altered input tokens")
    if method == "turn/change-set/read":
        validate_change_set_file(result)


def validate_response_correlation(frame: dict[str, Any], pending: dict[str, str], schema: dict[str, Any],
                                  catalog: dict[int, tuple[str, str, bool]], methods: set[str],
                                  events: set[str]) -> None:
    """把无 method 的 JSON-RPC response 绑定到同文件请求并锁定 c/h namespace。"""
    identifier = frame.get("id")
    if not isinstance(identifier, str) or identifier not in pending:
        raise CorpusError("response is not correlated")
    method = pending.pop(identifier)
    if not identifier.startswith("c:"):
        raise CorpusError("response must use client namespace")
    if "result" in frame:
        validate_result(method, frame["result"], schema)
    elif "error" in frame:
        validate_frame(frame, schema, catalog, methods, events)
    else:
        raise CorpusError("response has no result or error")


def main() -> int:
    """执行唯一的 v1 schema/golden 验证，并显式锁定 breaking 负例与 digest。"""
    schema = parse_json(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    catalog = load_catalog()
    methods = contract_vocabulary(schema, "methodName")
    events = contract_vocabulary(schema, "eventName")
    valid_paths = corpus_files(False)
    invalid_paths = corpus_files(True)
    valid_frames = 0
    saw_initialized = False
    saw_config_changed = False
    observed_input_contract: set[str] = set()
    observed_task_methods: set[str] = set()
    observed_task_events: set[str] = set()
    observed_goal_methods: set[str] = set()
    observed_goal_events: set[str] = set()
    for path in valid_paths:
        pending: dict[str, str] = {}
        for frame in raw_documents(path):
            validate_frame(frame, schema, catalog, methods, events)
            valid_frames += 1
            saw_initialized |= frame.get("method") == "runtime/initialized"
            saw_config_changed |= frame.get("method") == "configuration/changed"
            if isinstance(frame.get("method"), str) and frame["method"].startswith("turn/input"):
                observed_input_contract.add(frame["method"])
            if isinstance(frame.get("method"), str) and frame["method"].startswith("task/"):
                target = observed_task_methods if "id" in frame else observed_task_events
                target.add(frame["method"])
            if isinstance(frame.get("method"), str) \
                    and (frame["method"].startswith("goal/") or frame["method"].startswith("plan/")):
                target = observed_goal_methods if "id" in frame else observed_goal_events
                target.add(frame["method"])
            if "method" in frame and "id" in frame:
                pending[frame["id"]] = frame["method"]
            elif "id" in frame:
                validate_response_correlation(frame, pending, schema, catalog, methods, events)
    if not valid_frames or not saw_initialized or not saw_config_changed:
        raise CorpusError("positive corpus lacks v1 handshake/config coverage")
    required_input_contract = {"turn/input/enqueue", "turn/input/prioritize", "turn/input/update",
                               "turn/input/delete", "turn/input-queue-changed", "turn/input-consumed"}
    if not required_input_contract <= observed_input_contract:
        raise CorpusError("positive corpus lacks input queue coverage")
    required_task_methods = {"task/create", "task/list", "task/read", "task/observe", "task/unobserve",
                             "task/seen", "task/message/send", "task/followup", "task/cancel",
                             "task/tree/delete"}
    required_task_events = {"task/activity", "task/progress", "task/mailbox-changed"}
    if not required_task_methods <= observed_task_methods or not required_task_events <= observed_task_events:
        raise CorpusError("positive corpus lacks task thread coverage")
    required_goal_methods = {"goal/read", "goal/events/read", "goal/observe", "goal/unobserve",
                             "plan/read", "plan/revisions/list", "goal/evidence/list", "goal/create",
                             "goal/plan/attach", "goal/plan/detach", "goal/pause", "goal/resume",
                             "goal/stop", "goal/input/respond", "plan/create", "plan/draft/save",
                             "plan/draft/discard", "plan/propose", "plan/approve", "plan/execute",
                             "plan/reject"}
    required_goal_events = {"goal/changed", "goal/activity", "goal/input-requested"}
    if not required_goal_methods <= observed_goal_methods or not required_goal_events <= observed_goal_events:
        raise CorpusError("positive corpus lacks plan goal coverage")
    invalid_frames = 0
    illegal_methods_path = V1_GOLDEN / "invalid" / "illegal-methods.jsonl"
    if illegal_methods_path not in invalid_paths:
        raise CorpusError("missing illegal-method fail-closed corpus")
    illegal_methods: set[str] = set()
    for path in invalid_paths:
        correlated = "correlated" in path.relative_to(V1_GOLDEN).parts
        pending: dict[str, str] = {}
        for frame in raw_documents(path):
            if path == illegal_methods_path and isinstance(frame.get("method"), str):
                illegal_methods.add(frame["method"])
            if correlated and "method" in frame and "id" in frame:
                validate_frame(frame, schema, catalog, methods, events)
                pending[frame["id"]] = frame["method"]
                continue
            try:
                validate_frame(frame, schema, catalog, methods, events)
                if "method" not in frame and "id" in frame:
                    validate_response_correlation(
                        frame, pending if correlated else {}, schema, catalog, methods, events
                    )
            except CorpusError:
                invalid_frames += 1
            else:
                raise CorpusError(f"negative frame accepted: {path.relative_to(GOLDEN)}")
        if correlated and pending:
            raise CorpusError(f"correlated negative response is missing: {path.relative_to(GOLDEN)}")
    if not invalid_frames:
        raise CorpusError("negative corpus is empty")
    if len(illegal_methods - methods - events) < 4:
        raise CorpusError("illegal-method corpus lacks a representative unknown-method boundary")
    digest = corpus_digest(valid_paths + invalid_paths)
    print(f"GOLDEN_OK validFrames={valid_frames} invalidFrames={invalid_frames} "
          f"positiveFiles={len(valid_paths)} negativeFiles={len(invalid_paths)} digest={digest}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CorpusError as error:
        print(f"GOLDEN_FAIL {error}")
        raise SystemExit(1)
