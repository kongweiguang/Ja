# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""以根目录 package.json 为依据，同步 Ja 产品版本的各处投影。"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
import tomllib
import xml.etree.ElementTree as ElementTree


PRODUCT_VERSION_PATTERN = re.compile(
    r"^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)"
    r"(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$"
)
LOCAL_CARGO_WORKSPACE_PACKAGES = (
    ("apps/desktop/src-tauri", "desktop_cargo", "ja-desktop"),
    ("crates/ja-runtime", "runtime_cargo", "ja-runtime"),
    ("crates/ja-review", "review_cargo", "ja-review"),
    ("apps/cli", "cli_cargo", "ja-cli"),
)
LOCAL_CARGO_PACKAGES = tuple(package_name for _, _, package_name in LOCAL_CARGO_WORKSPACE_PACKAGES)
MAVEN_MODULE_POMS = (
    ("maven_core_pom", "ja-agent/ja-agent-core/pom.xml"),
    ("maven_store_local_pom", "ja-agent/extensions/ja-agent-store-local/pom.xml"),
    ("maven_store_pg_pom", "ja-agent/extensions/ja-agent-store-pg/pom.xml"),
    ("maven_skills_local_pom", "ja-agent/extensions/ja-agent-skills-local/pom.xml"),
    ("maven_skills_pg_pom", "ja-agent/extensions/ja-agent-skills-pg/pom.xml"),
    ("maven_example_pom", "ja-agent/example/pom.xml"),
    ("maven_app_server_pom", "ja-agent/ja-app-server/pom.xml"),
    ("maven_example_spring_boot_pom", "ja-agent/example/ja-agent-example-springboot/pom.xml"),
)
MAVEN_MODULE_PARENTS = {
    "maven_example_spring_boot_pom": "ja-agent-example",
}
FILES = {
    "package_json": "package.json",
    "npm_package_json": "packages/ja-npm/package.json",
    "cargo_workspace": "Cargo.toml",
    "desktop_cargo": "apps/desktop/src-tauri/Cargo.toml",
    "runtime_cargo": "crates/ja-runtime/Cargo.toml",
    "review_cargo": "crates/ja-review/Cargo.toml",
    "cli_cargo": "apps/cli/Cargo.toml",
    "cargo_lock": "Cargo.lock",
    "tauri_config": "apps/desktop/src-tauri/tauri.conf.json",
    "maven_pom": "ja-agent/pom.xml",
    "maven_core_pom": "ja-agent/ja-agent-core/pom.xml",
    "maven_store_local_pom": "ja-agent/extensions/ja-agent-store-local/pom.xml",
    "maven_store_pg_pom": "ja-agent/extensions/ja-agent-store-pg/pom.xml",
    "maven_skills_local_pom": "ja-agent/extensions/ja-agent-skills-local/pom.xml",
    "maven_skills_pg_pom": "ja-agent/extensions/ja-agent-skills-pg/pom.xml",
    "maven_example_pom": "ja-agent/example/pom.xml",
    "maven_app_server_pom": "ja-agent/ja-app-server/pom.xml",
    "maven_example_spring_boot_pom": "ja-agent/example/ja-agent-example-springboot/pom.xml",
    "golden_core": "contracts/golden/v2/valid/core.jsonl",
    "java_version_resource": "ja-agent/ja-app-server/src/main/version/ja-build.properties",
    "native_resource_config": (
        "ja-agent/ja-app-server/src/main/resources/META-INF/native-image/"
        "io.github.kongweiguang/ja-app-server/resource-config.json"
    ),
    "java_handshake": (
        "ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/"
        "transport/rpc/handler/HandshakeHandler.java"
    ),
    "rust_supervisor": "crates/ja-runtime/src/app_server_process/lifecycle/supervisor.rs",
    "typescript_protocol": "apps/desktop/src/api/protocol/protocol.ts",
}


def _parse_json(source: str, label: str) -> dict[str, object]:
    """要求输入为 JSON 对象，避免查询版本时无提示地转换格式错误的文档。"""
    try:
        document = json.loads(source)
    except json.JSONDecodeError as error:
        raise ValueError(f"{label} is not valid JSON") from error
    if not isinstance(document, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return document


def _parse_toml(source: str, label: str) -> dict[str, object]:
    """精确回写源码前，先使用 Python 符合标准的 TOML 解析器验证文档。"""
    try:
        return tomllib.loads(source)
    except tomllib.TOMLDecodeError as error:
        raise ValueError(f"{label} is not valid TOML") from error


def _nested_value(document: object, path: tuple[str, ...], label: str) -> object:
    """按精确的解析路径读取字段，清单结构变化时拒绝继续处理。"""
    current = document
    for segment in path:
        if not isinstance(current, dict) or segment not in current:
            raise ValueError(f"{label} is missing {'.'.join(path)}")
        current = current[segment]
    return current


def _maven_project_version(source: str) -> str:
    """读取多模块父工程直接声明的版本，确保 Maven 产品版本只有一个权威来源。"""
    try:
        root = ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        raise ValueError("ja-agent/pom.xml is not valid XML") from error
    namespace = root.tag.removesuffix("project")
    if root.tag != f"{namespace}project":
        raise ValueError("ja-agent/pom.xml root must be project")
    artifact = root.find(f"{namespace}artifactId")
    if artifact is None or artifact.text != "ja-parent":
        raise ValueError("ja-agent/pom.xml must be the ja-parent reactor")
    versions = root.findall(f"{namespace}version")
    if len(versions) != 1 or not versions[0].text or not versions[0].text.strip():
        raise ValueError("ja-agent/pom.xml must have one direct project.version")
    return versions[0].text.strip()


def _maven_module_parent_version(
    source: str, label: str, expected_parent_artifact: str = "ja-parent"
) -> str:
    """校验直接及嵌套示例的父工程，不允许模块独立声明产品版本。"""
    try:
        root = ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        raise ValueError(f"{label} is not valid XML") from error
    namespace = root.tag.removesuffix("project")
    parents = root.findall(f"{namespace}parent")
    if len(parents) != 1:
        raise ValueError(f"{label} must have one Ja reactor parent")
    artifact = parents[0].find(f"{namespace}artifactId")
    version = parents[0].find(f"{namespace}version")
    if artifact is None or artifact.text != expected_parent_artifact:
        raise ValueError(f"{label} must inherit {expected_parent_artifact}")
    if version is None or not version.text or not version.text.strip():
        raise ValueError(f"{label} Ja reactor parent has no version")
    if root.findall(f"{namespace}version"):
        raise ValueError(f"{label} must inherit its artifact version from ja-parent")
    return version.text.strip()


def _authoritative_version(sources: dict[str, str]) -> str:
    """读取唯一权威版本，拒绝发布工具无法一致表示的版本格式。"""
    version = _parse_json(sources["package_json"], "package.json").get("version")
    if not isinstance(version, str) or not PRODUCT_VERSION_PATTERN.fullmatch(version):
        raise ValueError("package.json.version must be a supported semantic version")
    return version


def _replace_toml_assignment(source: str, table: str, key: str, value: str) -> str:
    """替换已解析表中的单个赋值，不重新格式化清单的其他内容。"""
    _parse_toml(source, f"TOML document containing {table}.{key}")
    header_pattern = re.compile(rf"(?m)^[ \t]*\[{re.escape(table)}\][ \t]*(?:#.*)?$")
    headers = list(header_pattern.finditer(source))
    if len(headers) != 1:
        raise ValueError(f"expected one TOML table [{table}], found {len(headers)}")
    next_header = re.search(r"(?m)^[ \t]*\[\[?[^\r\n]+", source[headers[0].end() :])
    table_end = (
        headers[0].end() + next_header.start() if next_header is not None else len(source)
    )
    table_source = source[headers[0].end() : table_end]
    assignment_pattern = re.compile(
        rf"(?m)^(?P<prefix>[ \t]*{re.escape(key)}[ \t]*=[ \t]*)"
        r"(?P<value>[^#\r\n]*?)(?P<suffix>[ \t]*(?:#.*)?)$"
    )
    assignments = list(assignment_pattern.finditer(table_source))
    if len(assignments) != 1:
        raise ValueError(f"expected one TOML assignment {table}.{key}, found {len(assignments)}")
    assignment = assignments[0]
    start = headers[0].end() + assignment.start("value")
    end = headers[0].end() + assignment.end("value")
    return f"{source[:start]}{json.dumps(value)}{source[end:]}"


def _replace_cargo_lock_versions(source: str, version: str) -> str:
    """更新已解析的本地包配置块，不修改包仓库依赖的版本。"""
    document = _parse_toml(source, "Cargo.lock")
    packages = document.get("package")
    if not isinstance(packages, list):
        raise ValueError("Cargo.lock must contain package entries")
    for package_name in LOCAL_CARGO_PACKAGES:
        matches = [entry for entry in packages if entry.get("name") == package_name]
        if len(matches) != 1:
            raise ValueError(
                f"expected one Cargo.lock package named {package_name}, found {len(matches)}"
            )

    block_pattern = re.compile(
        r"(?ms)^[ \t]*\[\[package\]\][ \t]*\r?\n.*?(?=^[ \t]*\[\[package\]\]|\Z)"
    )
    replacements: list[tuple[int, int]] = []
    for block_match in block_pattern.finditer(source):
        block = block_match.group(0)
        parsed_block = _parse_toml(block, "Cargo.lock package block")["package"][0]
        if parsed_block.get("name") not in LOCAL_CARGO_PACKAGES:
            continue
        version_match = re.search(
            r'(?m)^[ \t]*version[ \t]*=[ \t]*"(?P<version>[^"\r\n]+)"', block
        )
        if version_match is None:
            raise ValueError(f"Cargo.lock package {parsed_block['name']} has no string version")
        replacements.append(
            (
                block_match.start() + version_match.start("version"),
                block_match.start() + version_match.end("version"),
            )
        )
    if len(replacements) != len(LOCAL_CARGO_PACKAGES):
        raise ValueError("Cargo.lock local package source blocks are incomplete")
    updated = source
    for start, end in reversed(replacements):
        updated = f"{updated[:start]}{version}{updated[end:]}"
    return updated


def _replace_maven_project_version(source: str, version: str) -> str:
    """仅更新父 POM 直接声明的版本，保留依赖的固定版本。"""
    current = _maven_project_version(source)
    pattern = re.compile(
        r"(?s)(<artifactId>\s*ja-parent\s*</artifactId>\s*<version>\s*)"
        rf"{re.escape(current)}"
        r"(\s*</version>)"
    )
    updated, count = pattern.subn(rf"\g<1>{version}\g<2>", source)
    if count != 1:
        raise ValueError(f"expected one writable Maven project.version, found {count}")
    return updated


def _replace_maven_module_parent_version(
    source: str,
    version: str,
    label: str,
    expected_parent_artifact: str = "ja-parent",
) -> str:
    """同步各模块的直接父工程版本，同时保留依赖的固定版本。"""
    current = _maven_module_parent_version(source, label, expected_parent_artifact)
    pattern = re.compile(
        rf"(?s)(<parent>.*?<artifactId>\s*{re.escape(expected_parent_artifact)}"
        r"\s*</artifactId>\s*<version>\s*)"
        rf"{re.escape(current)}"
        r"(\s*</version>)"
    )
    updated, count = pattern.subn(rf"\g<1>{version}\g<2>", source)
    if count != 1:
        raise ValueError(f"{label} must contain one writable {expected_parent_artifact} parent version")
    return updated


def _update_json_version(source: str, version: str, label: str) -> str:
    """更新已解析的 JSON，保持固定缩进及原有换行约定。"""
    document = _parse_json(source, label)
    if document.get("version") == version:
        return source
    document["version"] = version
    newline = "\r\n" if "\r\n" in source else "\n"
    return json.dumps(document, ensure_ascii=False, indent=2).replace("\n", newline) + newline


def _read_jsonl_documents(source: str, label: str) -> list[tuple[int, dict[str, object]]]:
    """逐帧解析 JSONL，确保标准协议样例持续通过结构校验。"""
    documents: list[tuple[int, dict[str, object]]] = []
    for line_number, raw_line in enumerate(source.splitlines(), start=1):
        if not raw_line.strip():
            continue
        try:
            document = json.loads(raw_line)
        except json.JSONDecodeError as error:
            raise ValueError(f"{label} line {line_number} is not valid JSON") from error
        if not isinstance(document, dict):
            raise ValueError(f"{label} line {line_number} must contain a JSON object")
        documents.append((line_number, document))
    return documents


def _find_golden_runtime_response(
    source: str, label: str
) -> tuple[int, dict[str, object]]:
    """按 RPC 请求 ID 定位唯一初始化响应，不修改 clientVersion 请求示例。"""
    documents = _read_jsonl_documents(source, label)
    initialize_ids = [
        document.get("id")
        for _, document in documents
        if document.get("method") == "runtime/initialize"
    ]
    if len(initialize_ids) != 1 or initialize_ids[0] is None:
        raise ValueError(f"{label} must contain one runtime/initialize request with an id")

    rpc_request_id = initialize_ids[0]
    matches: list[tuple[int, dict[str, object]]] = []
    for line_number, document in documents:
        if document.get("id") != rpc_request_id or "result" not in document:
            continue
        result = document["result"]
        if not isinstance(result, dict):
            continue
        runtime = result.get("runtime")
        if not isinstance(runtime, dict) or "engineVersion" not in runtime:
            continue
        if not isinstance(runtime["engineVersion"], str):
            raise ValueError(f"{label} runtime.engineVersion must be a string")
        matches.append((line_number, document))

    if len(matches) != 1:
        raise ValueError(
            f"{label} must contain one runtime/initialize response with runtime.engineVersion"
        )
    return matches[0]


def _replace_golden_engine_version(source: str, version: str, label: str) -> str:
    """仅改写已验证的响应帧，保留 JSONL 的记录顺序和换行符。"""
    line_number, document = _find_golden_runtime_response(source, label)
    result = document["result"]
    if not isinstance(result, dict) or not isinstance(result.get("runtime"), dict):
        raise ValueError(f"{label} runtime response has an invalid shape")
    result["runtime"]["engineVersion"] = version

    lines = source.splitlines(keepends=True)
    if line_number > len(lines):
        raise ValueError(f"{label} response line is outside the source")
    raw_line = lines[line_number - 1]
    content = raw_line.rstrip("\r\n")
    line_ending = raw_line[len(content) :]
    lines[line_number - 1] = (
        json.dumps(document, ensure_ascii=False, separators=(",", ":")) + line_ending
    )
    return "".join(lines)


def inspect_product_version_sources(
    sources: dict[str, str], tag: str | None = None
) -> tuple[str, list[str]]:
    """统一报告所有版本投影偏差，包括可执行的标准响应样例。"""
    version = _authoritative_version(sources)
    drift: list[str] = []

    def expect(label: str, actual: object, expected: object) -> None:
        """汇总所有不一致项，使 CI 一次报告全部需要修正的内容。"""
        if actual != expected:
            drift.append(f"{label}: expected {expected}, found {actual}")

    npm_package = _parse_json(
        sources["npm_package_json"], "packages/ja-npm/package.json"
    )
    expect(
        "packages/ja-npm/package.json name",
        npm_package.get("name"),
        "@kongweiguang/ja",
    )
    expect(
        "packages/ja-npm/package.json version",
        npm_package.get("version"),
        version,
    )
    expect(
        "packages/ja-npm/package.json private",
        npm_package.get("private"),
        True,
    )

    tauri = _parse_json(sources["tauri_config"], "apps/desktop/src-tauri/tauri.conf.json")
    expect("apps/desktop/src-tauri/tauri.conf.json version", tauri.get("version"), version)

    cargo_workspace = _parse_toml(sources["cargo_workspace"], "Cargo.toml")
    expect(
        "Cargo workspace version",
        _nested_value(cargo_workspace, ("workspace", "package", "version"), "Cargo.toml"),
        version,
    )
    expected_members = {path for path, _, _ in LOCAL_CARGO_WORKSPACE_PACKAGES}
    actual_members = _nested_value(cargo_workspace, ("workspace", "members"), "Cargo.toml")
    if not isinstance(actual_members, list) or set(actual_members) != expected_members:
        raise ValueError("Cargo workspace members differ from the managed Ja package set")
    for path, key, package_name in LOCAL_CARGO_WORKSPACE_PACKAGES:
        label = f"{path}/Cargo.toml"
        manifest = _parse_toml(sources[key], label)
        expect(
            f"{label} package.name",
            _nested_value(manifest, ("package", "name"), label),
            package_name,
        )
        expect(
            f"{label} package version inheritance",
            _nested_value(manifest, ("package", "version", "workspace"), label),
            True,
        )

    cargo_lock = _parse_toml(sources["cargo_lock"], "Cargo.lock")
    packages = cargo_lock.get("package", [])
    for package_name in LOCAL_CARGO_PACKAGES:
        matches = [entry for entry in packages if entry.get("name") == package_name]
        if len(matches) != 1:
            raise ValueError(
                f"expected one Cargo.lock package named {package_name}, found {len(matches)}"
            )
        expect(f"Cargo.lock {package_name} version", matches[0].get("version"), version)

    expect("ja-agent/pom.xml project.version", _maven_project_version(sources["maven_pom"]), version)
    for key, label in MAVEN_MODULE_POMS:
        expected_parent = MAVEN_MODULE_PARENTS.get(key, "ja-parent")
        expect(
            f"{label} parent.version",
            _maven_module_parent_version(sources[key], label, expected_parent),
            version,
        )
    _, golden_response = _find_golden_runtime_response(
        sources["golden_core"], "contracts/golden/v2/valid/core.jsonl"
    )
    golden_result = golden_response["result"]
    golden_runtime = golden_result["runtime"]
    expect(
        "contracts/golden/v2/valid/core.jsonl runtime.engineVersion",
        golden_runtime["engineVersion"],
        version,
    )
    version_lines = {line.strip() for line in sources["java_version_resource"].splitlines()}
    if "product.version=${project.version}" not in version_lines:
        drift.append("ja-build.properties must derive product.version from Maven project.version")

    resource_config = _parse_json(
        sources["native_resource_config"], "Native Image resource-config.json"
    )
    includes = _nested_value(
        resource_config,
        ("resources", "includes"),
        "Native Image resource-config.json",
    )
    patterns = [entry.get("pattern") for entry in includes if isinstance(entry, dict)]
    if r"ja-build\.properties" not in patterns:
        drift.append("Native Image resources must include ja-build.properties")

    if (
        "import io.github.kongweiguang.ja.foundation.runtime.ProductVersion;"
        not in sources["java_handshake"]
        or 'put("engineVersion", ProductVersion.current())'
        not in sources["java_handshake"]
    ):
        drift.append("Java engineVersion must derive from ProductVersion.current()")
    rust_identity = re.compile(
        r'engineVersion"\)\.and_then\(Value::as_str\)\s*'
        r'!=\s*Some\(env!\("CARGO_PKG_VERSION"\)\)'
    )
    if not rust_identity.search(sources["rust_supervisor"]):
        drift.append("Rust engineVersion admission must derive from CARGO_PKG_VERSION")
    if (
        'from "../../../../../package.json"' not in sources["typescript_protocol"]
        or "engineVersion: z.literal(packageJson.version)"
        not in sources["typescript_protocol"]
    ):
        drift.append("TypeScript engineVersion admission must derive from package.json.version")

    if tag:
        expect("release tag", tag.strip(), f"v{version}")
    return version, drift


def synchronize_product_version_sources(sources: dict[str, str]) -> dict[str, str]:
    """校验发布标识前，先同步产品版本投影和私有 npm 模板。"""
    version = _authoritative_version(sources)
    updated = dict(sources)
    updated["npm_package_json"] = _update_json_version(
        sources["npm_package_json"], version, "packages/ja-npm/package.json"
    )
    updated["tauri_config"] = _update_json_version(
        sources["tauri_config"], version, "apps/desktop/src-tauri/tauri.conf.json"
    )
    updated["cargo_workspace"] = _replace_toml_assignment(
        sources["cargo_workspace"], "workspace.package", "version", version
    )
    updated["cargo_lock"] = _replace_cargo_lock_versions(sources["cargo_lock"], version)
    updated["maven_pom"] = _replace_maven_project_version(sources["maven_pom"], version)
    for key, label in MAVEN_MODULE_POMS:
        expected_parent = MAVEN_MODULE_PARENTS.get(key, "ja-parent")
        updated[key] = _replace_maven_module_parent_version(
            sources[key], version, label, expected_parent
        )
    updated["golden_core"] = _replace_golden_engine_version(
        sources["golden_core"], version, "contracts/golden/v2/valid/core.jsonl"
    )
    _, drift = inspect_product_version_sources(updated)
    if drift:
        raise ValueError("synchronization remained incomplete:\n" + "\n".join(drift))
    return updated


def _read_sources(repository_root: Path) -> dict[str, str]:
    """为所有受管理的版本投影和运行时标识使用方读取一次文件系统快照。"""
    return {
        key: (repository_root / relative_path).read_text(encoding="utf-8")
        for key, relative_path in FILES.items()
    }


def check_product_version(repository_root: Path, tag: str | None = None) -> str:
    """执行本地开发与 CI 共用的只读一致性门禁。"""
    version, drift = inspect_product_version_sources(_read_sources(repository_root), tag)
    if drift:
        raise ValueError("product version drift detected:\n- " + "\n- ".join(drift))
    return version


def synchronize_product_version(repository_root: Path) -> str:
    """仅写入发生变化的投影，package.json 和运行时使用方源码保持只读。"""
    sources = _read_sources(repository_root)
    updated = synchronize_product_version_sources(sources)
    for key, relative_path in FILES.items():
        if key == "package_json" or updated[key] == sources[key]:
            continue
        (repository_root / relative_path).write_text(updated[key], encoding="utf-8", newline="")
    return check_product_version(repository_root)


def _parse_arguments(arguments: list[str]) -> argparse.Namespace:
    """限制 CLI 的操作范围，避免 CI 意外调用会修改文件的同步模式。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("check", "sync"))
    parser.add_argument("--tag")
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    """执行请求的版本操作，并输出一个固定的成功标记。"""
    options = _parse_arguments(sys.argv[1:] if arguments is None else arguments)
    repository_root = Path(__file__).resolve().parents[2]
    environment_tag = (
        os.environ.get("GITHUB_REF_NAME")
        if os.environ.get("GITHUB_REF_TYPE") == "tag"
        else None
    )
    version = (
        synchronize_product_version(repository_root)
        if options.command == "sync"
        else check_product_version(repository_root, options.tag or environment_tag)
    )
    print(f"JA_PRODUCT_VERSION_OK version={version} mode={options.command}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from error
