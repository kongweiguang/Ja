# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""根据真实 CI 输出生成可复现的 Native Image 构建报告。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from typing import Any


VERSION_PATTERN = re.compile(r"(?:\d+\.){2}\d+(?:[+.-][A-Za-z0-9.-]+)?")


def sha256(path: Path) -> str:
    """分块计算真实产物摘要，避免生成报告时占用过多内存。"""

    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def command_version(command: str, *arguments: str) -> str:
    """只提取版本行，排除安装路径和任意构建输出。"""

    candidates = [command]
    if command in {"native-image", "mvn"} and not command.endswith(".cmd"):
        candidates.append(f"{command}.cmd")
    executable = next((shutil.which(candidate) for candidate in candidates if shutil.which(candidate)), None)
    if executable is None:
        return "unavailable"
    try:
        completed = subprocess.run(
            [executable, *arguments],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=20,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return "unavailable"
    lines = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    for line in lines:
        if VERSION_PATTERN.search(line):
            return line[:240]
    return lines[0][:240] if lines else "unavailable"


def file_entry(path: Path) -> dict[str, Any]:
    """仅用可移植文件名、大小和内容摘要描述生成文件。"""

    if not path.is_file():
        raise RuntimeError(f"required report input is missing: {path.name}")
    return {"fileName": path.name, "sizeBytes": path.stat().st_size, "sha256": sha256(path)}


def parse_args() -> argparse.Namespace:
    """解析报告输入，避免将任意主机路径写入序列化报告。"""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", required=True, type=Path)
    parser.add_argument("--sbom", required=True, type=Path)
    parser.add_argument("--smoke", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--checksum-output", required=True, type=Path)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--arch", required=True)
    parser.add_argument("--runner", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--nik-version", required=True)
    parser.add_argument("--nik-java-version", required=True)
    parser.add_argument("--nik-sha256", required=True)
    parser.add_argument("--no-fallback", action="store_true")
    return parser.parse_args()


def main() -> int:
    """校验三项证据输入并输出一条机器可读的构建记录。"""

    args = parse_args()
    if not args.no_fallback:
        raise SystemExit("--no-fallback is required")
    try:
        smoke = json.loads(args.smoke.read_text(encoding="utf-8"))
        if smoke.get("status") != "passed":
            raise RuntimeError("smoke evidence is not marked passed")
        artifact = file_entry(args.artifact)
        sbom = file_entry(args.sbom)
    except (OSError, ValueError, RuntimeError) as failure:
        raise SystemExit(f"cannot create native build report: {failure}") from failure

    report = {
        "schemaVersion": 1,
        "product": "Ja",
        "sourceCommit": args.source_commit,
        "target": {"platform": args.platform, "arch": args.arch, "runner": args.runner},
        "toolchain": {
            "distribution": "BellSoft Liberica Native Image Kit",
            "nikVersion": args.nik_version,
            "javaVersion": args.nik_java_version,
            "java": command_version("java", "-version"),
            "nativeImage": command_version("native-image", "--version"),
            "maven": command_version("mvn", "--version"),
            "nikArchiveSha256": args.nik_sha256.lower(),
            "nativeImageOnly": True,
            "noFallback": True,
        },
        "artifacts": {"nativeExecutable": artifact, "sbom": sbom},
        "smoke": smoke,
        "security": {
            "credentialsRedacted": True,
            "jvmEnvironmentRemoved": True,
            "stdoutProtocolOnly": True,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    args.checksum_output.write_text(
        f"{artifact['sha256']}  {artifact['fileName']}\n", encoding="ascii", newline="\n"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
