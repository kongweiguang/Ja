# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later
"""运行唯一的 JA-RPC v2 跨语言语料门禁。"""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Sequence


ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / "contracts" / "golden"
TIMEOUT_SECONDS = 900


class GateFailure(RuntimeError):
    """只携带阶段身份，让子进程诊断保持原样且便于审查。"""


def corpus_files() -> list[Path]:
    """按稳定相对路径选择全部 JSON/JSONL 字节输入，保证三端消费同一语料顺序。"""
    return sorted(path for path in GOLDEN.rglob("*") if path.is_file() and path.suffix in {".json", ".jsonl"})


def corpus_digest() -> str:
    """同时摘要相对名称与字节，使结果标记能唯一对应本次实际消费的语料。"""
    digest = hashlib.sha256()
    for path in corpus_files():
        digest.update(path.relative_to(GOLDEN).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def corpus_count(invalid: bool) -> int:
    """从物理 JSON/JSONL 记录计算帧数，严格解析器的负例行也必须计入。"""
    count = 0
    for path in corpus_files():
        is_invalid = "invalid" in path.relative_to(GOLDEN).parts
        if is_invalid != invalid:
            continue
        if path.suffix == ".json":
            count += 1
        else:
            count += sum(1 for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    return count


def executable(name: str) -> str:
    """执行阶段前解析必需工具，避免 Shell 差异造成部分阶段已执行的状态。"""
    resolved = shutil.which(name)
    if resolved is None:
        raise GateFailure(f"required tool is unavailable: {name}")
    return resolved


def run_stage(name: str, command: Sequence[str], environment: dict[str, str]) -> None:
    """继承输出且禁用 Shell 插值运行单个阶段，并用统一上限约束卡死的 consumer。"""
    print(f"CONTRACT_STAGE_START name={name}", flush=True)
    try:
        completed = subprocess.run(
            list(command),
            cwd=ROOT,
            env=environment,
            stdin=subprocess.DEVNULL,
            timeout=TIMEOUT_SECONDS,
            check=False,
        )
    except subprocess.TimeoutExpired as failure:
        raise GateFailure(f"contract stage timed out: {name}") from failure
    except OSError as failure:
        raise GateFailure(f"contract stage could not start: {name}") from failure
    if completed.returncode != 0:
        raise GateFailure(f"contract stage failed: {name} exit={completed.returncode}")
    print(f"CONTRACT_STAGE_OK name={name}", flush=True)


def main() -> int:
    """按固定顺序验证 schema 及三端生产 consumer，禁止局部成功冒充合同通过。"""
    if not GOLDEN.is_dir():
        print("CONTRACT_GATE_FAIL stage=setup reason=missing_corpus", file=sys.stderr)
        return 1
    environment = os.environ.copy()
    environment["JA_GOLDEN_PATH"] = str(GOLDEN)
    schema_stage = (
        executable("uv"),
        "run",
        "--with",
        "jsonschema[format]",
        "python",
        str(GOLDEN / "validate.py"),
    )
    stages: list[tuple[str, tuple[str, ...]]] = [
        ("schema", schema_stage),
                (
                    "python-projection",
                    (
                        executable("uv"),
                        "run",
                        "--with",
                        "jsonschema[format]",
                        "python",
                        str(ROOT / "tests" / "contract" / "python_consumer.py"),
                    ),
                ),
                (
                    "java",
                    (
                        executable("mvn"),
                        "-B",
                        "-ntp",
                        "-f",
                        str(ROOT / "app-server" / "pom.xml"),
                        "-Dja.build.directory=target-rpc-v2-contract",
                        "-Dtest=GoldenCorpusTest",
                        "test",
                    ),
                ),
                (
                    "rust",
                    (
                        executable("cargo"),
                        "test",
                        "-p",
                        "ja-runtime",
                        "--test",
                        "int_golden_corpus",
                    ),
                ),
                (
                    "typescript",
                    (
                        executable("pnpm"),
                        "exec",
                        "vitest",
                        "run",
                        "tests/contract/ts_consumer.test.ts",
                        "--config",
                        "tests/contract/vitest.config.ts",
                    ),
                ),
    ]
    try:
        for name, command in stages:
            run_stage(name, command, environment)
    except GateFailure as failure:
        print(f"CONTRACT_GATE_FAIL {failure}", file=sys.stderr)
        return 1
    print(
        "CONTRACT_GATE_OK "
        f"digest={corpus_digest()} validFrames={corpus_count(False)} "
        "invalidFrames=" f"{corpus_count(True)} consumers=java,rust,typescript"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
