# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate the breaking JA RPC v2 schema, golden frames, and Secret boundary."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Iterable

from jsonschema import Draft202012Validator, FormatChecker


CONTRACTS = Path(__file__).resolve().parents[1]
GOLDEN = Path(__file__).resolve().parent
V2_GOLDEN = GOLDEN / "v2"
SCHEMA_PATH = CONTRACTS / "ja-rpc" / "v2" / "schema" / "ja-rpc-v2.schema.json"
ERROR_CATALOG_PATH = CONTRACTS / "ja-rpc" / "v2" / "error-catalog.json"
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
    """只选择 v2 子目录，目录外的历史 fixture 不会成为当前协议输入。"""
    if not V2_GOLDEN.is_dir():
        return []
    return sorted(
        path for path in V2_GOLDEN.rglob("*")
        if path.is_file() and path.suffix in {".json", ".jsonl"}
        and (("invalid" in path.relative_to(V2_GOLDEN).parts) == invalid)
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
        raise CorpusError("invalid v2 error catalog")
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
    "workspace/set-trust": "workspaceTrustParams",
    "workspace/unregister": "workspaceUnregisterParams", "thread/create": "threadCreateParams",
    "thread/read": "threadReadParams", "thread/archive": "threadMutationParams",
    "thread/delete": "threadMutationParams", "thread/compact": "threadCompactParams",
    "attachment/import": "attachmentImportParams", "attachment/discard": "attachmentDiscardParams",
    "turn/start": "turnStartParams",
    "turn/cancel": "turnCancelParams", "turn/steer": "turnQueuedInputParams",
    "turn/follow-up": "turnQueuedInputParams", "approval/respond": "approvalRespondParams",
    "configuration/read": "configurationReadParams", "configuration/patch": "configurationPatchParams",
    "configuration/replace": "configurationReplaceParams", "configuration/reset": "configurationResetParams",
    "credential/set": "credentialSetParams", "credential/delete": "credentialDeleteParams",
}
EVENT_PARAM_DEFS: dict[str, str] = {
    "context/compaction-started": "contextCompactionStartedParams",
    "context/compacted": "contextCompactedParams",
    "context/compaction-failed": "contextCompactionFailedParams",
}

RESULT_DEFS = {
    "workspace/list": "workspacePageResult",
    "thread/list": "threadPageResult",
    "thread/read": "threadReadResult",
    "skill/list": "skillPageResult",
    "mcp/list": "mcpPageResult",
    "mcp/list-tools": "mcpToolsResult",
    "workspace/open-general": "workspaceResult",
    "thread/compact": "threadCompactResult",
    "attachment/import": "attachmentResult", "attachment/discard": "attachmentResult",
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
    """禁止 config/profile/Turn/response/event 携带 Secret；credential/set 是唯一例外。"""
    method = frame.get("method")
    allowed = method == "credential/set" and isinstance(frame.get("params"), dict)
    if has_secret(frame.get("params"), allowed):
        raise CorpusError("secret crossed v2 boundary")
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
    """执行 schema、方向闭集、host body、v2 cwd/credential 边界和错误目录校验。"""
    errors = list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(frame))
    if errors:
        raise CorpusError("schema rejection")
    validate_secret_boundary(frame)
    if "method" in frame:
        method = frame["method"]
        if method not in methods and method not in events:
            raise CorpusError("method outside v2 closure")
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
        if method == "runtime/initialize" and params.get("protocolMajor") != 2:
            raise CorpusError("wrong protocol major")
        if method == "turn/start" and any(key in params for key in ("cwd", "profileId", "configRevision")):
            raise CorpusError("turn override crossed v2 boundary")
        if method == "turn/start":
            validate_turn_content(params.get("content"))
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
            raise CorpusError("error outside v2 catalog")


def validate_turn_content(value: Any) -> None:
    """补充 JSON Schema 难以表达的附件 subtype 数量与 identity 唯一性约束。"""
    if not isinstance(value, list) or not 1 <= len(value) <= 64:
        raise CorpusError("turn content is invalid")
    attachment_ids = [item.get("attachmentId") for item in value
                      if isinstance(item, dict) and item.get("type") == "attachment"]
    if len(attachment_ids) > 10 or len(attachment_ids) != len(set(attachment_ids)):
        raise CorpusError("turn attachment limit or uniqueness is invalid")


def validate_result(method: str, result: Any, schema: dict[str, Any]) -> None:
    """按请求方法绑定成功结果，锁定配置层与认证仓库 CAS 的返回路径及字段闭集。"""
    if method == "runtime/initialize":
        if not isinstance(result, dict) or not isinstance(result.get("capabilities"), dict):
            raise CorpusError("initialize result lacks capabilities")
    definition = RESULT_DEFS.get(method)
    if definition is None:
        return
    errors = list(sub_validator(schema, definition).iter_errors(result))
    if errors:
        raise CorpusError("result schema rejection")
    if method == "credential/set" and result.get("configured") is not True:
        raise CorpusError("credential/set result did not report configured=true")
    if method == "credential/delete" and result.get("configured") is not False:
        raise CorpusError("credential/delete result did not report configured=false")
    if method == "thread/compact":
        before = result["inputTokensBefore"]
        after = result["inputTokensAfter"]
        if result["outcome"] == "compacted" and after >= before:
            raise CorpusError("compacted result did not reduce input tokens")
        if result["outcome"] == "unchanged" and after != before:
            raise CorpusError("unchanged result altered input tokens")


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
    """执行唯一的 v2 schema/golden 验证，并显式锁定 breaking 负例与 digest。"""
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
    for path in valid_paths:
        pending: dict[str, str] = {}
        for frame in raw_documents(path):
            validate_frame(frame, schema, catalog, methods, events)
            valid_frames += 1
            saw_initialized |= frame.get("method") == "runtime/initialized"
            saw_config_changed |= frame.get("method") == "configuration/changed"
            if "method" in frame and "id" in frame:
                pending[frame["id"]] = frame["method"]
            elif "id" in frame:
                validate_response_correlation(frame, pending, schema, catalog, methods, events)
    if not valid_frames or not saw_initialized or not saw_config_changed:
        raise CorpusError("positive corpus lacks v2 handshake/config coverage")
    invalid_frames = 0
    illegal_methods_path = V2_GOLDEN / "invalid" / "illegal-methods.jsonl"
    if illegal_methods_path not in invalid_paths:
        raise CorpusError("missing illegal-method fail-closed corpus")
    illegal_methods: set[str] = set()
    for path in invalid_paths:
        for frame in raw_documents(path):
            if path == illegal_methods_path and isinstance(frame.get("method"), str):
                illegal_methods.add(frame["method"])
            try:
                validate_frame(frame, schema, catalog, methods, events)
                if "method" not in frame and "id" in frame:
                    validate_response_correlation(frame, {}, schema, catalog, methods, events)
            except CorpusError:
                invalid_frames += 1
            else:
                raise CorpusError(f"negative frame accepted: {path.relative_to(GOLDEN)}")
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
