# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later
"""以 Python 独立消费 Agent 过程与 ChangeSet 的 breaking JA-RPC v2 语料。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
GOLDEN = Path(os.environ.get("JA_GOLDEN_PATH", ROOT / "contracts" / "golden"))
SCHEMA_PATH = ROOT / "contracts" / "ja-rpc" / "v2" / "schema" / "ja-rpc-v2.schema.json"
VALID = GOLDEN / "v2" / "valid" / "agent-process.jsonl"
INVALID = GOLDEN / "v2" / "invalid" / "agent-presentation.jsonl"
RESULT_DEFS = {
    "thread/read": "threadReadResult",
    "tool/artifact/read": "toolArtifactReadResult",
    "turn/change-set/commit": "turnChangeSetCommitResult",
    "turn/change-set/read": "changeSetArtifactReadResult",
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


def validate_change_set(value: dict[str, Any]) -> None:
    """交叉校验 JSON Schema 无法表达的统计、路径唯一性和 Diff 内容身份。"""
    files = value["files"]
    stats = value["stats"]
    if stats["files"] != len(files) or stats["binaryFiles"] != sum(item["binary"] for item in files):
        raise RuntimeError("change-set aggregate mismatch")
    if len({item["path"] for item in files}) != len(files):
        raise RuntimeError("change-set path identity mismatch")
    known_additions = sum(item.get("additions", 0) for item in files)
    known_deletions = sum(item.get("deletions", 0) for item in files)
    if stats["additions"] < known_additions or stats["deletions"] < known_deletions:
        raise RuntimeError("change-set line aggregate mismatch")
    artifact = value.get("artifact")
    if artifact is not None:
        encoded = artifact["unifiedDiff"].encode("utf-8")
        if len(encoded) > 2 * 1024 * 1024 or artifact["byteLength"] != len(encoded) \
                or artifact["sha256"] != hashlib.sha256(encoded).hexdigest():
            raise RuntimeError("change-set artifact identity mismatch")


def validate_artifact_result(method: str, result: dict[str, Any]) -> None:
    """锁定字符分页与 UTF-8 byte 分页的不同跨度语义。"""
    if method == "tool/artifact/read":
        offset = result["offsetCharacters"]
        end = result["totalCharacters"] if result["nextOffsetCharacters"] is None \
            else result["nextOffsetCharacters"]
        if len(result["content"]) != end - offset:
            raise RuntimeError("tool artifact character span mismatch")
        return
    offset = result["offsetBytes"]
    end = result["byteLength"] if result["nextOffsetBytes"] is None else result["nextOffsetBytes"]
    if len(result["content"].encode("utf-8")) != end - offset:
        raise RuntimeError("change-set artifact byte span mismatch")


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
            if frame["method"] == "turn/change-set/commit":
                validate_change_set(frame["params"])
        elif "method" in frame:
            observed.add(frame["method"])
        elif "result" in frame:
            method = pending.pop(frame["id"])
            require_valid(definition_validator(schema, RESULT_DEFS[method]), frame["result"], method)
            if method in {"tool/artifact/read", "turn/change-set/read"}:
                validate_artifact_result(method, frame["result"])
    expected = {"assistant/model-step-committed", "tool/batch-committed", "thread/read",
                "tool/artifact/read", "turn/change-set/commit", "turn/change-set/read"}
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
    print(f"PYTHON_CONSUMER_OK positiveFrames={len(positive)} invalidFrames={len(negative)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
