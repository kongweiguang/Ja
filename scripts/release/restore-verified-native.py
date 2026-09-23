# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Restore a Native Image proven by a successful main CI artifact.

The release workflow owns the assertion that ``verified-run-id`` is a successful
main run.  This helper only checks the downloaded files and report identity,
then copies the already-proven executable without compiling it again.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
from typing import Any


SUPPORTED_TARGETS = {
    ("windows", "x86_64"),
    ("macos", "x86_64"),
    ("macos", "arm64"),
}
EXPECTED_REPORT_NAME = "build-report.json"
EXPECTED_SBOM_NAME = "ja-app-server.json"
WINDOWS_NATIVE_NAME = "ja-app-server.exe"
MACOS_NATIVE_NAME = "ja-app-server"
NATIVE_EXECUTABLE_MAX_BYTES = 120 * 1024 * 1024
MAX_SCAN_ENTRIES = 4096
MAX_SCAN_DEPTH = 32
HEX_40 = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)
HEX_64 = re.compile(r"^[0-9a-f]{64}$", re.IGNORECASE)
RUN_ID = re.compile(r"^[1-9][0-9]*$")


class RestoreError(RuntimeError):
    """表示输入、报告或输出边界不满足恢复契约的可预期失败。"""


def sha256(path: Path) -> str:
    """以有界块读取文件，避免 Native Image 大文件被一次性载入内存。"""

    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as failure:
        raise RestoreError(f"无法读取文件: {path}") from failure
    return digest.hexdigest()


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    """拒绝重复 JSON key，防止验证和后续消费者看到不同的报告语义。"""

    document: dict[str, Any] = {}
    for key, value in pairs:
        if key in document:
            raise RestoreError(f"JSON 含重复字段: {key}")
        document[key] = value
    return document


def _reject_non_finite(value: str) -> None:
    """禁止 NaN/Infinity 进入机器报告，保持 JSON 身份字段可移植。"""

    raise RestoreError(f"JSON 含非有限数字: {value}")


def load_json(path: Path) -> dict[str, Any]:
    """严格读取一个 UTF-8 对象报告，同时保留原文件字节供证据复制。"""

    try:
        document = json.loads(
            path.read_bytes().decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite,
        )
    except RestoreError:
        raise
    except (OSError, UnicodeDecodeError, ValueError) as failure:
        raise RestoreError(f"JSON 无法解析: {path.name}") from failure
    if not isinstance(document, dict):
        raise RestoreError(f"JSON 根值必须是对象: {path.name}")
    return document


def _path_is_within(child: Path, parent: Path) -> bool:
    """用解析后的路径判断输出是否落入输入树，避免恢复过程改写下载证据。"""

    try:
        child.relative_to(parent)
    except ValueError:
        return False
    return True


def _ensure_no_symlink_entry(path: Path) -> None:
    """拒绝输出对象本身的符号链接，但允许 macOS `/var` 等系统合法祖先别名。"""

    try:
        if os.path.lexists(path) and path.is_symlink():
            raise RestoreError(f"输出路径包含符号链接: {path}")
    except RestoreError:
        raise
    except OSError as failure:
        raise RestoreError(f"无法检查输出路径: {path}") from failure


def _resolved_input_root(path: Path) -> Path:
    """确认下载目录是普通目录并解析一次，后续扫描统一使用这个安全根。"""

    try:
        if path.is_symlink() or not path.is_dir():
            raise RestoreError(f"输入目录不是普通目录: {path}")
        return path.resolve(strict=True)
    except RestoreError:
        raise
    except OSError as failure:
        raise RestoreError(f"无法解析输入目录: {path}") from failure


def scan_input(root: Path, expected_native_name: str) -> dict[str, Path]:
    """有限递归枚举下载目录，只接受三个契约文件且不跟随任何链接。"""

    resolved_root = _resolved_input_root(root)
    expected_names = {expected_native_name, EXPECTED_REPORT_NAME, EXPECTED_SBOM_NAME}
    matches: dict[str, Path] = {}
    entries_seen = 0
    stack: list[tuple[Path, int]] = [(resolved_root, 0)]

    while stack:
        directory, depth = stack.pop()
        try:
            with os.scandir(directory) as children:
                for entry in children:
                    entries_seen += 1
                    if entries_seen > MAX_SCAN_ENTRIES:
                        raise RestoreError("输入目录条目过多，拒绝继续扫描")
                    candidate = Path(entry.path)
                    try:
                        if entry.is_symlink():
                            raise RestoreError(f"输入目录含符号链接: {candidate}")
                        resolved = candidate.resolve(strict=True)
                        if not _path_is_within(resolved, resolved_root):
                            raise RestoreError(f"输入路径逃逸下载目录: {candidate}")
                        if entry.is_dir(follow_symlinks=False):
                            if depth + 1 > MAX_SCAN_DEPTH:
                                raise RestoreError("输入目录层级过深，拒绝继续扫描")
                            stack.append((resolved, depth + 1))
                            continue
                        if not entry.is_file(follow_symlinks=False):
                            raise RestoreError(f"输入目录含不支持的特殊文件: {candidate.name}")
                    except RestoreError:
                        raise
                    except OSError as failure:
                        raise RestoreError(f"无法检查输入路径: {candidate}") from failure

                    if candidate.name not in expected_names:
                        raise RestoreError(f"输入目录含未声明文件: {candidate.name}")
                    if candidate.name in matches:
                        raise RestoreError(f"输入目录含重复 basename: {candidate.name}")
                    matches[candidate.name] = resolved
        except RestoreError:
            raise
        except OSError as failure:
            raise RestoreError(f"无法扫描输入目录: {directory}") from failure

    missing = expected_names.difference(matches)
    if missing:
        raise RestoreError(f"输入目录缺少文件: {', '.join(sorted(missing))}")
    return matches


def _file_facts(path: Path, label: str, *, maximum_bytes: int | None = None) -> dict[str, Any]:
    """读取非空普通文件的大小和摘要，并对 Native 二进制保留既有体积上限。"""

    try:
        details = path.stat()
    except OSError as failure:
        raise RestoreError(f"{label} 无法读取: {path.name}") from failure
    if not stat.S_ISREG(details.st_mode) or details.st_size <= 0:
        raise RestoreError(f"{label} 缺失、为空或不是普通文件: {path.name}")
    if maximum_bytes is not None and details.st_size > maximum_bytes:
        raise RestoreError(f"{label} 超过允许大小: {path.name}")
    digest = sha256(path)
    return {"fileName": path.name, "sizeBytes": details.st_size, "sha256": digest}


def _require_text(value: Any, label: str) -> str:
    """把身份字段限制为单行非空文本，避免路径、换行或类型混淆进入证据。"""

    if not isinstance(value, str) or not value or "\r" in value or "\n" in value:
        raise RestoreError(f"{label} 无效")
    return value


def _require_bool(value: Any, label: str) -> None:
    """严格要求布尔门禁，避免 Python 将整数 1 误当作已验证的 True。"""

    if type(value) is not bool or value is not True:
        raise RestoreError(f"{label} 未通过")


def _require_fact(report_fact: Any, actual: dict[str, Any], label: str) -> None:
    """同时比对报告中的文件名、字节数和摘要，拒绝半成品或错配证据。"""

    if not isinstance(report_fact, dict):
        raise RestoreError(f"{label} 证据缺失")
    file_name = report_fact.get("fileName")
    size = report_fact.get("sizeBytes")
    digest = report_fact.get("sha256")
    if file_name != actual["fileName"]:
        raise RestoreError(f"{label} 文件名不匹配")
    if type(size) is not int or size <= 0 or size != actual["sizeBytes"]:
        raise RestoreError(f"{label} 大小不匹配")
    if not isinstance(digest, str) or not HEX_64.fullmatch(digest) or digest.lower() != actual["sha256"]:
        raise RestoreError(f"{label} SHA-256 不匹配")


def _require_smoke_identity(smoke_fact: Any, actual: dict[str, Any]) -> None:
    """比对 smoke 实际记录的大小和摘要；真实 smoke schema 不重复写入文件名。"""

    if not isinstance(smoke_fact, dict):
        raise RestoreError("smoke executable 身份证据缺失")
    size = smoke_fact.get("sizeBytes")
    digest = smoke_fact.get("sha256")
    if type(size) is not int or size <= 0 or size != actual["sizeBytes"]:
        raise RestoreError("smoke executable 大小不匹配")
    if not isinstance(digest, str) or not HEX_64.fullmatch(digest) or digest.lower() != actual["sha256"]:
        raise RestoreError("smoke executable SHA-256 不匹配")


def validate_report(
    report_path: Path,
    native_path: Path,
    sbom_path: Path,
    source_commit: str,
    platform: str,
    arch: str,
    nik_version: str,
    nik_java_version: str,
    nik_sha256: str,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], bytes]:
    """完整校验 Native 报告、smoke 身份和两个实际文件，返回可复制的原始证据。"""

    if (platform, arch) not in SUPPORTED_TARGETS:
        raise RestoreError(f"不支持的平台架构组合: {platform}/{arch}")
    if not HEX_40.fullmatch(source_commit):
        raise RestoreError("source commit 必须是 40 位十六进制 SHA")
    if not HEX_64.fullmatch(nik_sha256):
        raise RestoreError("NIK SHA-256 必须是 64 位十六进制值")
    expected_native_name = WINDOWS_NATIVE_NAME if platform == "windows" else MACOS_NATIVE_NAME
    native = _file_facts(native_path, "Native executable", maximum_bytes=NATIVE_EXECUTABLE_MAX_BYTES)
    sbom = _file_facts(sbom_path, "SBOM")
    report_bytes = report_path.read_bytes()
    report = load_json(report_path)

    if type(report.get("schemaVersion")) is not int or report.get("schemaVersion") != 1:
        raise RestoreError("build report schemaVersion 不支持")
    if report.get("product") != "Ja":
        raise RestoreError("build report product 不是 Ja")
    report_commit = report.get("sourceCommit")
    if not isinstance(report_commit, str) or report_commit.lower() != source_commit.lower():
        raise RestoreError("build report sourceCommit 不匹配")

    target = report.get("target")
    if not isinstance(target, dict) or target.get("platform") != platform or target.get("arch") != arch:
        raise RestoreError("build report target 不匹配")

    toolchain = report.get("toolchain")
    if not isinstance(toolchain, dict):
        raise RestoreError("build report toolchain 缺失")
    if toolchain.get("nikVersion") != nik_version:
        raise RestoreError("NIK version 不匹配")
    if toolchain.get("javaVersion") != nik_java_version:
        raise RestoreError("NIK Java version 不匹配")
    report_nik_sha256 = toolchain.get("nikArchiveSha256")
    if (
        not isinstance(report_nik_sha256, str)
        or not HEX_64.fullmatch(report_nik_sha256)
        or report_nik_sha256.lower() != nik_sha256.lower()
    ):
        raise RestoreError("NIK archive SHA-256 不匹配")
    _require_bool(toolchain.get("nativeImageOnly"), "nativeImageOnly")
    _require_bool(toolchain.get("noFallback"), "noFallback")

    smoke = report.get("smoke")
    if not isinstance(smoke, dict) or smoke.get("status") != "passed":
        raise RestoreError("smoke.status 未通过")
    executable_smoke = smoke.get("executable")
    if not isinstance(executable_smoke, dict):
        raise RestoreError("smoke executable 身份证据缺失")
    _require_bool(executable_smoke.get("expectedIdentityMatched"), "smoke.expectedIdentityMatched")

    artifacts = report.get("artifacts")
    if not isinstance(artifacts, dict):
        raise RestoreError("build report artifacts 缺失")
    _require_fact(artifacts.get("nativeExecutable"), native, "nativeExecutable")
    if native["fileName"] != expected_native_name:
        raise RestoreError("Native executable 文件名不符合平台")
    _require_smoke_identity(executable_smoke, native)
    _require_fact(artifacts.get("sbom"), sbom, "sbom")
    if sbom["fileName"] != EXPECTED_SBOM_NAME:
        raise RestoreError("SBOM 文件名不符合契约")

    return report, native, sbom, report_bytes


def _validate_output_paths(input_root: Path, artifact: Path, evidence_dir: Path) -> tuple[Path, Path, Path]:
    """在写入前锁定三个输出位置，拒绝覆盖和把证据目录放回下载树。"""

    resolved_input = input_root.resolve(strict=True)
    artifact_resolved = artifact.resolve(strict=False)
    evidence_resolved = evidence_dir.resolve(strict=False)
    if _path_is_within(artifact_resolved, resolved_input) or _path_is_within(evidence_resolved, resolved_input):
        raise RestoreError("输出路径不能位于输入下载目录内")
    _ensure_no_symlink_entry(artifact)
    _ensure_no_symlink_entry(evidence_dir)
    if os.path.lexists(artifact):
        raise RestoreError(f"目标文件已存在，拒绝覆盖: {artifact}")
    if os.path.lexists(evidence_dir) and not evidence_dir.is_dir():
        raise RestoreError(f"evidence-dir 不是目录: {evidence_dir}")
    evidence_report = evidence_dir / "reused-native-build-report.json"
    reuse_manifest = evidence_dir / "native-reuse.json"
    for path in (evidence_report, reuse_manifest):
        if os.path.lexists(path):
            raise RestoreError(f"证据文件已存在，拒绝覆盖: {path}")
    return artifact_resolved, evidence_report.resolve(strict=False), reuse_manifest.resolve(strict=False)


def _copy_new_file(source: Path, destination: Path) -> None:
    """以独占创建复制文件，防止并发调用覆盖已有构建输入。"""

    created = False
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        if hasattr(os, "O_BINARY"):
            flags |= os.O_BINARY
        with source.open("rb") as input_stream, os.fdopen(os.open(destination, flags, 0o644), "wb") as output_stream:
            created = True
            shutil.copyfileobj(input_stream, output_stream, length=1024 * 1024)
    except FileExistsError as failure:
        raise RestoreError(f"目标文件已存在，拒绝覆盖: {destination}") from failure
    except OSError as failure:
        if created:
            try:
                destination.unlink()
            except OSError:
                pass
        raise RestoreError(f"复制文件失败: {destination}") from failure


def _write_new_bytes(destination: Path, content: bytes) -> None:
    """以独占创建写入 JSON 证据，让失败不会静默覆盖另一轮恢复结果。"""

    created = False
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        if hasattr(os, "O_BINARY"):
            flags |= os.O_BINARY
        with os.fdopen(os.open(destination, flags, 0o644), "wb") as stream:
            created = True
            stream.write(content)
    except FileExistsError as failure:
        raise RestoreError(f"证据文件已存在，拒绝覆盖: {destination}") from failure
    except OSError as failure:
        if created:
            try:
                destination.unlink()
            except OSError:
                pass
        raise RestoreError(f"写入证据失败: {destination}") from failure


def _reuse_manifest(
    verified_run_id: str,
    source_commit: str,
    platform: str,
    arch: str,
    native: dict[str, Any],
) -> dict[str, Any]:
    """生成明确标注复用来源的记录，不把复用动作伪装成新的编译报告。"""

    return {
        "schemaVersion": 1,
        "product": "Ja",
        "sourceCommit": source_commit.lower(),
        "verifiedRunId": verified_run_id,
        "source": {
            "type": "successful-main-native-artifact",
            "fileName": native["fileName"],
        },
        "target": {"platform": platform, "arch": arch},
        "nativeExecutable": native,
        "sha256": native["sha256"],
        "reuse": {"status": "reused", "buildPerformed": False},
    }


def restore_verified_native(
    input_root: Path,
    artifact_destination: Path,
    evidence_dir: Path,
    source_commit: str,
    platform: str,
    arch: str,
    nik_version: str,
    nik_java_version: str,
    nik_sha256: str,
    verified_run_id: str,
) -> dict[str, Any]:
    """验证成功 main CI 的 Native artifact 后复制 executable 并保存原始报告。"""

    if platform not in {"windows", "macos"}:
        raise RestoreError(f"不支持的平台: {platform}")
    if not RUN_ID.fullmatch(verified_run_id):
        raise RestoreError("verified run id 无效")
    _require_text(nik_version, "NIK version")
    _require_text(nik_java_version, "NIK Java version")
    expected_native_name = WINDOWS_NATIVE_NAME if platform == "windows" else MACOS_NATIVE_NAME
    paths = scan_input(input_root, expected_native_name)
    report, native, _sbom, report_bytes = validate_report(
        paths[EXPECTED_REPORT_NAME],
        paths[expected_native_name],
        paths[EXPECTED_SBOM_NAME],
        source_commit,
        platform,
        arch,
        nik_version,
        nik_java_version,
        nik_sha256,
    )
    artifact_path, evidence_report, reuse_manifest_path = _validate_output_paths(
        input_root, artifact_destination, evidence_dir
    )

    created_paths: list[Path] = []
    try:
        _copy_new_file(paths[expected_native_name], artifact_path)
        created_paths.append(artifact_path)
        if platform == "macos":
            os.chmod(artifact_path, artifact_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        copied_native = _file_facts(artifact_path, "恢复后的 Native executable", maximum_bytes=NATIVE_EXECUTABLE_MAX_BYTES)
        if copied_native != native:
            raise RestoreError("恢复后的 Native executable 身份不匹配")

        _write_new_bytes(evidence_report, report_bytes)
        created_paths.append(evidence_report)
        reuse = _reuse_manifest(verified_run_id, source_commit, platform, arch, native)
        _write_new_bytes(
            reuse_manifest_path,
            (json.dumps(reuse, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
        )
        created_paths.append(reuse_manifest_path)
    except (OSError, RestoreError):
        for path in reversed(created_paths):
            try:
                path.unlink()
            except OSError:
                pass
        raise

    result = dict(reuse)
    result["report"] = {
        "fileName": report["artifacts"]["nativeExecutable"]["fileName"],
        "path": str(evidence_report),
    }
    return result


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """解析唯一的恢复 CLI，避免脚本偷偷接受会改变验证边界的额外输入。"""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--artifact", required=True, type=Path)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--platform", required=True, choices=("windows", "macos"))
    parser.add_argument("--arch", required=True, choices=("x86_64", "arm64"))
    parser.add_argument("--nik-version", required=True)
    parser.add_argument("--nik-java-version", required=True)
    parser.add_argument("--nik-sha256", required=True)
    parser.add_argument("--verified-run-id", required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """执行一次封闭恢复并仅把机器可读结果输出到 stdout。"""

    args = parse_args(argv)
    try:
        result = restore_verified_native(
            args.input,
            args.artifact,
            args.evidence_dir,
            args.source_commit,
            args.platform,
            args.arch,
            args.nik_version,
            args.nik_java_version,
            args.nik_sha256,
            args.verified_run_id,
        )
    except (OSError, RestoreError) as failure:
        raise SystemExit(f"cannot restore verified Native Image: {failure}") from failure
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
