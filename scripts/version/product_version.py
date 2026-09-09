# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Synchronize Ja product-version projections from the root package.json."""

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
LOCAL_CARGO_PACKAGES = ("ja", "ja-runtime")
FILES = {
    "package_json": "package.json",
    "cargo_workspace": "Cargo.toml",
    "desktop_cargo": "src-tauri/Cargo.toml",
    "runtime_cargo": "crates/ja-runtime/Cargo.toml",
    "cargo_lock": "Cargo.lock",
    "tauri_config": "src-tauri/tauri.conf.json",
    "maven_pom": "app-server/pom.xml",
    "java_version_resource": "app-server/src/main/version/ja-build.properties",
    "native_resource_config": (
        "app-server/src/main/resources/META-INF/native-image/"
        "io.github.kongweiguang/ja-app-server/resource-config.json"
    ),
    "java_handshake": (
        "app-server/src/main/java/io/github/kongweiguang/ja/"
        "transport/rpc/handler/HandshakeHandler.java"
    ),
    "rust_supervisor": "crates/ja-runtime/src/app_server_process/lifecycle/supervisor.rs",
    "typescript_protocol": "apps/desktop/src/api/protocol/protocol.ts",
}


def _parse_json(source: str, label: str) -> dict[str, object]:
    """Require a JSON object so version lookups cannot silently coerce malformed documents."""
    try:
        document = json.loads(source)
    except json.JSONDecodeError as error:
        raise ValueError(f"{label} is not valid JSON") from error
    if not isinstance(document, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return document


def _parse_toml(source: str, label: str) -> dict[str, object]:
    """Use Python's standards-compliant TOML parser before any precise source writeback."""
    try:
        return tomllib.loads(source)
    except tomllib.TOMLDecodeError as error:
        raise ValueError(f"{label} is not valid TOML") from error


def _nested_value(document: object, path: tuple[str, ...], label: str) -> object:
    """Read an exact parsed path and fail closed when a manifest changes shape."""
    current = document
    for segment in path:
        if not isinstance(current, dict) or segment not in current:
            raise ValueError(f"{label} is missing {'.'.join(path)}")
        current = current[segment]
    return current


def _maven_project_version(source: str) -> str:
    """Read only Maven project's direct version child, excluding parent and dependency versions."""
    try:
        root = ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        raise ValueError("app-server/pom.xml is not valid XML") from error
    namespace = root.tag.removesuffix("project")
    if root.tag != f"{namespace}project":
        raise ValueError("app-server/pom.xml root must be project")
    versions = root.findall(f"{namespace}version")
    if len(versions) != 1 or not versions[0].text or not versions[0].text.strip():
        raise ValueError("app-server/pom.xml must have one direct project.version")
    return versions[0].text.strip()


def _authoritative_version(sources: dict[str, str]) -> str:
    """Read the sole authority and reject versions release tooling cannot represent consistently."""
    version = _parse_json(sources["package_json"], "package.json").get("version")
    if not isinstance(version, str) or not PRODUCT_VERSION_PATTERN.fullmatch(version):
        raise ValueError("package.json.version must be a supported semantic version")
    return version


def _replace_toml_assignment(source: str, table: str, key: str, value: str) -> str:
    """Replace one parsed table assignment without reformatting the surrounding manifest."""
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
    """Update parsed local package blocks without touching registry dependency versions."""
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
    """Preserve POM formatting while updating its ElementTree-verified project node."""
    current = _maven_project_version(source)
    pattern = re.compile(
        r"(?s)(<artifactId>\s*ja-app-server\s*</artifactId>\s*<version>\s*)"
        rf"{re.escape(current)}"
        r"(\s*</version>)"
    )
    updated, count = pattern.subn(rf"\g<1>{version}\g<2>", source)
    if count != 1:
        raise ValueError(f"expected one writable Maven project.version, found {count}")
    return updated


def _update_json_version(source: str, version: str, label: str) -> str:
    """Update parsed JSON with stable indentation and its original newline convention."""
    document = _parse_json(source, label)
    if document.get("version") == version:
        return source
    document["version"] = version
    newline = "\r\n" if "\r\n" in source else "\n"
    return json.dumps(document, ensure_ascii=False, indent=2).replace("\n", newline) + newline


def inspect_product_version_sources(
    sources: dict[str, str], tag: str | None = None
) -> tuple[str, list[str]]:
    """Return all projection drift at once so CI exposes a complete remediation set."""
    version = _authoritative_version(sources)
    drift: list[str] = []

    def expect(label: str, actual: object, expected: object) -> None:
        """Aggregate mismatches so CI reports every required correction together."""
        if actual != expected:
            drift.append(f"{label}: expected {expected}, found {actual}")

    tauri = _parse_json(sources["tauri_config"], "src-tauri/tauri.conf.json")
    expect("src-tauri/tauri.conf.json version", tauri.get("version"), version)

    cargo_workspace = _parse_toml(sources["cargo_workspace"], "Cargo.toml")
    expect(
        "Cargo workspace version",
        _nested_value(cargo_workspace, ("workspace", "package", "version"), "Cargo.toml"),
        version,
    )
    for label, key in (
        ("src-tauri/Cargo.toml", "desktop_cargo"),
        ("crates/ja-runtime/Cargo.toml", "runtime_cargo"),
    ):
        manifest = _parse_toml(sources[key], label)
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

    expect(
        "app-server/pom.xml project.version",
        _maven_project_version(sources["maven_pom"]),
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
    """Build a fully validated in-memory synchronization result before any filesystem write."""
    version = _authoritative_version(sources)
    updated = dict(sources)
    updated["tauri_config"] = _update_json_version(
        sources["tauri_config"], version, "src-tauri/tauri.conf.json"
    )
    updated["cargo_workspace"] = _replace_toml_assignment(
        sources["cargo_workspace"], "workspace.package", "version", version
    )
    updated["cargo_lock"] = _replace_cargo_lock_versions(sources["cargo_lock"], version)
    updated["maven_pom"] = _replace_maven_project_version(sources["maven_pom"], version)
    _, drift = inspect_product_version_sources(updated)
    if drift:
        raise ValueError("synchronization remained incomplete:\n" + "\n".join(drift))
    return updated


def _read_sources(repository_root: Path) -> dict[str, str]:
    """Read one filesystem snapshot of every governed projection and runtime identity consumer."""
    return {
        key: (repository_root / relative_path).read_text(encoding="utf-8")
        for key, relative_path in FILES.items()
    }


def check_product_version(repository_root: Path, tag: str | None = None) -> str:
    """Perform the read-only consistency gate used by local development and CI."""
    version, drift = inspect_product_version_sources(_read_sources(repository_root), tag)
    if drift:
        raise ValueError("product version drift detected:\n- " + "\n- ".join(drift))
    return version


def synchronize_product_version(repository_root: Path) -> str:
    """Write only changed projections; package.json and runtime consumer source remain read-only."""
    sources = _read_sources(repository_root)
    updated = synchronize_product_version_sources(sources)
    for key, relative_path in FILES.items():
        if key == "package_json" or updated[key] == sources[key]:
            continue
        (repository_root / relative_path).write_text(updated[key], encoding="utf-8", newline="")
    return check_product_version(repository_root)


def _parse_arguments(arguments: list[str]) -> argparse.Namespace:
    """Keep the CLI intentionally narrow so CI cannot accidentally invoke the mutating sync mode."""
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("check", "sync"))
    parser.add_argument("--tag")
    return parser.parse_args(arguments)


def main(arguments: list[str] | None = None) -> int:
    """Run the requested version operation and emit one stable success marker."""
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
