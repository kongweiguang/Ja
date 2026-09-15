# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Stage the pinned fd and ripgrep releases used by Ja workspace discovery.

The runtime must never turn a missing search executable into a first-use network
download.  This build-time adapter therefore accepts only the release assets and
SHA-256 values recorded below, extracts them into the Tauri resource directory,
and writes a portable manifest beside the binaries.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zipfile import ZipFile


DOWNLOAD_TIMEOUT_SECONDS = 120
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
COMMIT_PATTERN = re.compile(r"^[0-9a-fA-F]{7,64}$")
ARCHIVE_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]+\.(?:zip|tar\.gz)$")


@dataclass(frozen=True)
class Asset:
    """固定 release 资产事实；URL 和摘要来自对应 GitHub release API 快照。"""

    file_name: str
    url: str
    sha256: str
    size_bytes: int


@dataclass(frozen=True)
class ToolSpec:
    """描述一个可跨当前 Tauri 矩阵落盘的搜索工具及其许可文件。"""

    name: str
    version: str
    repository: str
    release_tag: str
    binary_name: str
    license_names: tuple[str, ...]
    license_sha256: dict[tuple[str, str], dict[str, str]]
    assets: dict[tuple[str, str], Asset]


def _asset(file_name: str, url: str, sha256: str, size_bytes: int) -> Asset:
    """集中构造不可变资产记录，防止同一版本的 URL、摘要和尺寸彼此漂移。"""

    if not ARCHIVE_NAME_PATTERN.fullmatch(file_name):
        raise ValueError(f"invalid fixed archive name: {file_name}")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError(f"invalid fixed SHA-256 for {file_name}")
    if size_bytes <= 0:
        raise ValueError(f"invalid fixed size for {file_name}")
    return Asset(file_name, url, sha256, size_bytes)


TOOLS: dict[str, ToolSpec] = {
    "fd": ToolSpec(
        name="fd",
        version="10.5.0",
        repository="sharkdp/fd",
        release_tag="v10.5.0",
        binary_name="fd",
        license_names=("LICENSE-APACHE", "LICENSE-MIT"),
        license_sha256={
            ("windows", "x86_64"): {
                "LICENSE-APACHE": "5406ff8da3fb52d20c70938295dc6d8178de6fc4ff1ae163bd4990eacc2c1451",
                "LICENSE-MIT": "7255d831d8873121ca5bcf278d8d5485d1221612b02742529703432e8134e87b",
            },
            ("macos", "x86_64"): {
                "LICENSE-APACHE": "73c83c60d817e7df1943cb3f0af81e4939a8352c9a96c2fd00451b1116fa635c",
                "LICENSE-MIT": "322cfc7aa0c774d0eca3b2610f1d414de3ddbd7d8dd4b9dea941a13a6eb07455",
            },
            ("macos", "arm64"): {
                "LICENSE-APACHE": "73c83c60d817e7df1943cb3f0af81e4939a8352c9a96c2fd00451b1116fa635c",
                "LICENSE-MIT": "322cfc7aa0c774d0eca3b2610f1d414de3ddbd7d8dd4b9dea941a13a6eb07455",
            },
        },
        assets={
            ("windows", "x86_64"): _asset(
                "fd-v10.5.0-x86_64-pc-windows-msvc.zip",
                "https://github.com/sharkdp/fd/releases/download/v10.5.0/fd-v10.5.0-x86_64-pc-windows-msvc.zip",
                "a227701b8551c35a9931d9f6da75503cf86d88e182d71fb849a70864c5d57cd7",
                1_535_063,
            ),
            ("macos", "x86_64"): _asset(
                "fd-v10.5.0-x86_64-apple-darwin.tar.gz",
                "https://github.com/sharkdp/fd/releases/download/v10.5.0/fd-v10.5.0-x86_64-apple-darwin.tar.gz",
                "7e31028c62c6955877735d0406807aa484c2a5e6f86235a59e26c29c301da590",
                1_426_858,
            ),
            ("macos", "arm64"): _asset(
                "fd-v10.5.0-aarch64-apple-darwin.tar.gz",
                "https://github.com/sharkdp/fd/releases/download/v10.5.0/fd-v10.5.0-aarch64-apple-darwin.tar.gz",
                "b67e1836c468e42e411984b56e52fa7abec08c2bd22c867398e7cc134aac5e12",
                1_334_374,
            ),
        },
    ),
    "rg": ToolSpec(
        name="rg",
        version="15.2.0",
        repository="BurntSushi/ripgrep",
        release_tag="15.2.0",
        binary_name="rg",
        license_names=("COPYING", "LICENSE-MIT", "UNLICENSE"),
        license_sha256={
            ("windows", "x86_64"): {
                "COPYING": "dfe7d0a6134a17d3de7409762e08dc02133303912875cab40a74ba07a390f85a",
                "LICENSE-MIT": "970813655a1bf777d2ead189cef71ab73ab92ce04dc864027d61a45de03e6728",
                "UNLICENSE": "640514163b17f977adc997cb16f51871122cfb0555ebad1a3f01e167b7ba8857",
            },
            ("macos", "x86_64"): {
                "COPYING": "01c266bced4a434da0051174d6bee16a4c82cf634e2679b6155d40d75012390f",
                "LICENSE-MIT": "0f96a83840e146e43c0ec96a22ec1f392e0680e6c1226e6f3ba87e0740af850f",
                "UNLICENSE": "7e12e5df4bae12cb21581ba157ced20e1986a0508dd10d0e8a4ab9a4cf94e85c",
            },
            ("macos", "arm64"): {
                "COPYING": "01c266bced4a434da0051174d6bee16a4c82cf634e2679b6155d40d75012390f",
                "LICENSE-MIT": "0f96a83840e146e43c0ec96a22ec1f392e0680e6c1226e6f3ba87e0740af850f",
                "UNLICENSE": "7e12e5df4bae12cb21581ba157ced20e1986a0508dd10d0e8a4ab9a4cf94e85c",
            },
        },
        assets={
            ("windows", "x86_64"): _asset(
                "ripgrep-15.2.0-x86_64-pc-windows-msvc.zip",
                "https://github.com/BurntSushi/ripgrep/releases/download/15.2.0/ripgrep-15.2.0-x86_64-pc-windows-msvc.zip",
                "71b2fef860abe467217a538ff31de02f5258807c0129f771846f87bd029aafc5",
                1_789_611,
            ),
            ("macos", "x86_64"): _asset(
                "ripgrep-15.2.0-x86_64-apple-darwin.tar.gz",
                "https://github.com/BurntSushi/ripgrep/releases/download/15.2.0/ripgrep-15.2.0-x86_64-apple-darwin.tar.gz",
                "af7825fcc69a2afc7a7aea55fc9af90e26421d8f20fe59df32e233c0b8a231c1",
                1_878_284,
            ),
            ("macos", "arm64"): _asset(
                "ripgrep-15.2.0-aarch64-apple-darwin.tar.gz",
                "https://github.com/BurntSushi/ripgrep/releases/download/15.2.0/ripgrep-15.2.0-aarch64-apple-darwin.tar.gz",
                "3750b2e93f37e0c692657da574d7019a101c0084da05a790c83fd335bad973e4",
                1_764_284,
            ),
        },
    ),
}


TARGET_TRIPLES: dict[tuple[str, str], str] = {
    ("windows", "x86_64"): "x86_64-pc-windows-msvc",
    ("macos", "x86_64"): "x86_64-apple-darwin",
    ("macos", "arm64"): "aarch64-apple-darwin",
}


def normalize_architecture(value: str) -> str:
    """把 CI、Rust 和操作系统常见别名收敛到固定资产清单的架构键。"""

    aliases = {
        "x86_64": "x86_64",
        "amd64": "x86_64",
        "x64": "x86_64",
        "arm64": "arm64",
        "aarch64": "arm64",
    }
    normalized = aliases.get(value.lower())
    if normalized is None:
        raise ValueError(f"unsupported search-tool architecture: {value}")
    return normalized


def target_spec(platform: str, architecture: str, target_triple: str) -> tuple[str, str, str]:
    """校验平台、架构和 target triple 必须属于当前 Tauri 发布矩阵。"""

    if platform not in {"windows", "macos"}:
        raise ValueError(f"unsupported search-tool platform: {platform}")
    normalized_architecture = normalize_architecture(architecture)
    expected = TARGET_TRIPLES.get((platform, normalized_architecture))
    if expected is None:
        raise ValueError(f"unsupported search-tool target: {platform}/{normalized_architecture}")
    if target_triple != expected:
        raise ValueError(f"target triple does not match {platform}/{normalized_architecture}: {target_triple}")
    return platform, normalized_architecture, expected


def asset_for(tool: str, platform: str, architecture: str) -> Asset:
    """返回固定工具资产，并在调用边界拒绝未审查的平台组合。"""

    spec = TOOLS.get(tool)
    if spec is None:
        raise ValueError(f"unsupported search tool: {tool}")
    normalized_architecture = normalize_architecture(architecture)
    asset = spec.assets.get((platform, normalized_architecture))
    if asset is None:
        raise ValueError(f"no fixed asset for {tool} on {platform}/{normalized_architecture}")
    return asset


def sha256_file(path: Path) -> str:
    """分块计算文件摘要，避免把归档或 Native 资源完整装入内存。"""

    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_asset(path: Path, asset: Asset) -> None:
    """同时校验归档大小与 SHA-256，阻止错误或被替换的 release 资产进入 bundle。"""

    if not path.is_file():
        raise RuntimeError(f"search-tool asset is missing: {path}")
    actual_size = path.stat().st_size
    if actual_size != asset.size_bytes:
        raise RuntimeError(f"asset size mismatch for {asset.file_name}: {actual_size} != {asset.size_bytes}")
    actual_hash = sha256_file(path)
    if actual_hash != asset.sha256:
        raise RuntimeError(f"asset SHA-256 mismatch for {asset.file_name}")


def contained_path(root: Path, child: Path) -> Path:
    """解析目标并证明它仍在本次 staging 根目录下，防止路径穿越或符号链接逃逸。"""

    root_resolved = root.resolve()
    child_resolved = child.resolve()
    try:
        child_resolved.relative_to(root_resolved)
    except ValueError as failure:
        raise RuntimeError("search-tool staging path escaped the output directory") from failure
    return child_resolved


def safe_archive_member(name: str) -> Path:
    """把归档成员限制为相对 POSIX 路径，并拒绝 Windows 盘符、反斜杠和父级段。"""

    if not name or "\x00" in name or "\\" in name:
        raise RuntimeError("archive contains an unsafe member path")
    if re.match(r"^[A-Za-z]:", name):
        raise RuntimeError("archive contains a drive-qualified member path")
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise RuntimeError("archive contains a path traversal member")
    parts = tuple(part for part in path.parts if part not in {"", "."})
    if not parts:
        raise RuntimeError("archive contains an empty member path")
    return Path(*parts)


def _extract_zip(archive: Path, destination: Path) -> None:
    """安全解压 zip，并拒绝链接条目与所有可能逃逸 staging 根的成员。"""

    with ZipFile(archive) as source:
        for info in source.infolist():
            relative = safe_archive_member(info.filename)
            if info.is_dir():
                continue
            mode = (info.external_attr >> 16) & 0o170000
            if mode == stat.S_IFLNK:
                raise RuntimeError("archive contains a symbolic link member")
            target = contained_path(destination, destination / relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(info, "r") as input_stream, target.open("wb") as output_stream:
                shutil.copyfileobj(input_stream, output_stream, length=1024 * 1024)


def _extract_tar(archive: Path, destination: Path) -> None:
    """安全解压 tar.gz，仅允许普通文件和目录，拒绝链接及特殊设备条目。"""

    with tarfile.open(archive, mode="r:gz") as source:
        for info in source.getmembers():
            relative = safe_archive_member(info.name)
            if info.isdir():
                continue
            if not info.isfile():
                raise RuntimeError("archive contains a non-regular member")
            target = contained_path(destination, destination / relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            input_stream = source.extractfile(info)
            if input_stream is None:
                raise RuntimeError(f"archive member cannot be read: {info.name}")
            with input_stream, target.open("wb") as output_stream:
                shutil.copyfileobj(input_stream, output_stream, length=1024 * 1024)


def extract_archive(archive: Path, destination: Path) -> None:
    """按固定后缀选择安全解包器，并把 archive 格式错误转成可诊断失败。"""

    if archive.name.endswith(".zip"):
        _extract_zip(archive, destination)
    elif archive.name.endswith(".tar.gz"):
        _extract_tar(archive, destination)
    else:
        raise RuntimeError(f"unsupported search-tool archive: {archive.name}")


def find_unique_member(root: Path, name: str) -> Path:
    """在已安全解包的目录中定位唯一目标文件，避免模糊选择错误版本或许可文本。"""

    candidates = [path for path in root.rglob(name) if path.is_file() and not path.is_symlink()]
    if len(candidates) != 1:
        raise RuntimeError(f"expected one {name} in archive, found {len(candidates)}")
    return candidates[0]


def _download_asset(asset: Asset, destination: Path) -> None:
    """构建阶段从固定 GitHub URL 流式下载归档，并在落盘前校验大小和摘要。"""

    request = Request(asset.url, headers={"User-Agent": "Ja-native-search-tools-staging"})
    try:
        with urlopen(request, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response, destination.open("wb") as output:
            length = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                length += len(chunk)
                if length > MAX_ARCHIVE_BYTES:
                    raise RuntimeError(f"search-tool asset is larger than {MAX_ARCHIVE_BYTES} bytes")
                output.write(chunk)
    except (HTTPError, URLError, OSError) as failure:
        raise RuntimeError(f"failed to download {asset.file_name}: {failure}") from failure
    verify_asset(destination, asset)


def _archive_path(asset: Asset, archive_dir: Path | None, scratch: Path) -> Path:
    """选择本地预缓存归档或在 staging 临时目录准备网络下载的归档。"""

    if archive_dir is not None:
        candidate = contained_path(archive_dir.resolve(), archive_dir / asset.file_name)
        verify_asset(candidate, asset)
        return candidate
    destination = contained_path(scratch, scratch / asset.file_name)
    _download_asset(asset, destination)
    return destination


def _copy_checked(source: Path, target: Path) -> dict[str, Any]:
    """复制单个工具资源并返回不含绝对路径的事实，供 manifest 和后续审计复用。"""

    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    if target.name in {"fd", "rg"}:
        target.chmod(target.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return {"fileName": target.name, "sizeBytes": target.stat().st_size, "sha256": sha256_file(target)}


def _verify_binary_version(binary: Path, version: str) -> str:
    """启动刚落盘的二进制并确认 --version 包含固定版本，防止归档内容错配。"""

    try:
        result = subprocess.run(
            [str(binary), "--version"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
            encoding="utf-8",
            errors="replace",
        )
    except (OSError, subprocess.SubprocessError) as failure:
        raise RuntimeError(f"cannot run {binary.name} --version: {failure}") from failure
    output = (result.stdout + result.stderr).strip()
    if result.returncode != 0 or version not in output:
        raise RuntimeError(f"{binary.name} --version did not report {version}: {output[:200]}")
    return output.splitlines()[0] if output else version


def _staged_license_name(tool: str, source_name: str) -> str:
    """给许可文件加工具前缀，避免 fd 与 ripgrep 的同名 LICENSE-MIT 相互覆盖。"""

    return f"{tool}-{source_name}"


def _stage_tool(
    tool: str,
    platform: str,
    architecture: str,
    staging_tools: Path,
    archive_dir: Path | None,
) -> dict[str, Any]:
    """解包一个固定工具、复制二进制与许可文件，并返回其完整 manifest 条目。"""

    spec = TOOLS[tool]
    asset = asset_for(tool, platform, architecture)
    expected_license_hashes = spec.license_sha256[(platform, architecture)]
    binary_name = f"{spec.binary_name}.exe" if platform == "windows" else spec.binary_name
    with tempfile.TemporaryDirectory(prefix=f".{tool}-extract-", dir=str(staging_tools.parent)) as temporary:
        scratch = Path(temporary)
        archive = _archive_path(asset, archive_dir, scratch)
        extracted = scratch / "extracted"
        extracted.mkdir()
        extract_archive(archive, extracted)
        binary_source = find_unique_member(extracted, spec.binary_name + (".exe" if platform == "windows" else ""))
        binary_target = contained_path(staging_tools, staging_tools / binary_name)
        binary_fact = _copy_checked(binary_source, binary_target)
        version_output = _verify_binary_version(binary_target, spec.version)
        licenses: list[dict[str, Any]] = []
        for license_name in spec.license_names:
            license_source = find_unique_member(extracted, license_name)
            actual_hash = sha256_file(license_source)
            if actual_hash != expected_license_hashes[license_name]:
                raise RuntimeError(f"license SHA-256 mismatch for {tool}/{license_name}")
            target_name = _staged_license_name(tool, license_name)
            license_target = contained_path(staging_tools, staging_tools / target_name)
            fact = _copy_checked(license_source, license_target)
            licenses.append({"sourceName": license_name, "relativePath": f"tools/{target_name}", **fact})
    return {
        "name": tool,
        "version": spec.version,
        "repository": spec.repository,
        "releaseTag": spec.release_tag,
        "asset": {
            "fileName": asset.file_name,
            "url": asset.url,
            "sha256": asset.sha256,
            "sizeBytes": asset.size_bytes,
        },
        "binary": {"relativePath": f"tools/{binary_name}", "versionOutput": version_output, **binary_fact},
        "licenses": licenses,
    }


def _write_json(path: Path, document: dict[str, Any]) -> None:
    """原子写入工具 manifest，避免 Tauri 构建读取半写入 JSON。"""

    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    temporary.replace(path)


def build_manifest(
    platform: str,
    architecture: str,
    target_triple: str,
    source_commit: str | None,
    tools: Iterable[dict[str, Any]],
    staging_mode: str,
) -> dict[str, Any]:
    """构造确定性工具 manifest，明确目标矩阵、版本、来源资产及实际文件摘要。"""

    document: dict[str, Any] = {
        "schemaVersion": 1,
        "product": "Ja",
        "stagingMode": staging_mode,
        "target": {"platform": platform, "arch": architecture, "targetTriple": target_triple},
        "tools": list(tools),
    }
    if source_commit is not None:
        document["sourceCommit"] = source_commit
    return document


def parse_args() -> argparse.Namespace:
    """暴露构建期资源准备所需的最小参数，避免脚本隐式猜测平台或输出目录。"""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--arch", required=True)
    parser.add_argument("--target-triple", required=True)
    parser.add_argument("--source-commit")
    parser.add_argument("--archive-dir", type=Path, help="use pre-downloaded fixed archives instead of the network")
    parser.add_argument("--dry-run", action="store_true", help="validate the target and print fixed assets only")
    return parser.parse_args()


def main() -> int:
    """准备当前目标的两个搜索工具资源，失败时不留下半成品目录。"""

    args = parse_args()
    try:
        platform, architecture, target_triple = target_spec(args.platform, args.arch, args.target_triple)
        if args.source_commit is not None and not COMMIT_PATTERN.fullmatch(args.source_commit):
            raise ValueError("source commit must be a short or full hexadecimal commit id")
        assets = [asset_for(tool, platform, architecture) for tool in ("fd", "rg")]
        if args.dry_run:
            entries = [
                {
                    "name": tool,
                    "version": TOOLS[tool].version,
                    "repository": TOOLS[tool].repository,
                    "releaseTag": TOOLS[tool].release_tag,
                    "asset": {
                        "fileName": asset.file_name,
                        "url": asset.url,
                        "sha256": asset.sha256,
                        "sizeBytes": asset.size_bytes,
                    },
                }
                for tool, asset in zip(("fd", "rg"), assets)
            ]
            print(json.dumps(build_manifest(platform, architecture, target_triple, args.source_commit, entries, "dry-run"), ensure_ascii=False, indent=2))
            return 0

        output_root = args.output_dir.resolve()
        output_root.mkdir(parents=True, exist_ok=True)
        sidecars_root = contained_path(output_root, output_root / "sidecars")
        tools_root = contained_path(sidecars_root, sidecars_root / "tools")
        if tools_root.exists() or tools_root.is_symlink():
            raise RuntimeError("sidecars/tools already exists; use a fresh staging directory")
        sidecars_root.mkdir(parents=True, exist_ok=True)
        temporary_root = Path(tempfile.mkdtemp(prefix=".tools-stage-", dir=str(sidecars_root)))
        try:
            entries = [
                _stage_tool(tool, platform, architecture, temporary_root, args.archive_dir.resolve() if args.archive_dir else None)
                for tool in ("fd", "rg")
            ]
            manifest = build_manifest(platform, architecture, target_triple, args.source_commit, entries, "copy")
            _write_json(temporary_root / "tools-manifest.json", manifest)
            temporary_root.replace(tools_root)
        except BaseException:
            shutil.rmtree(temporary_root, ignore_errors=True)
            raise
    except (OSError, RuntimeError, ValueError) as failure:
        raise SystemExit(f"cannot stage search tools: {failure}") from failure

    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
