# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Verify removed compatibility entries are absent from fresh Java artifacts."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile


OLD_CLASS_STEMS = (
    "io/github/kongweiguang/ja/agent/infrastructure/persistence/ExecutionRecordCodec",
    "io/github/kongweiguang/ja/agent/domain/ClientOperationReceipt",
    "io/github/kongweiguang/ja/agent/infrastructure/runtime/StandardAgentLoopFactory",
)
OLD_CLASS_PATTERNS = tuple(
    re.compile(r"(?:^|/)" + re.escape(stem) + r"(?:\$[^/]*)?\.class$")
    for stem in OLD_CLASS_STEMS
)
OLD_MIGRATION_PATTERN = re.compile(r"(?:^|/)db/migration/V(?:[2-9]|1[0-8])__[^/]+\.sql$")


def artifact_fingerprint(path):
    """Fingerprint complete bytes before and after inspection so a rebuild cannot change acceptance."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    stat = path.stat()
    return {"sha256": digest.hexdigest(), "bytes": stat.st_size, "mtimeNs": stat.st_mtime_ns}


def removed_entry(name):
    """Match exact former packages and their inner classes without rejecting the current codec or App receipt."""
    normalized = name.replace("\\", "/")
    return any(pattern.search(normalized) for pattern in OLD_CLASS_PATTERNS) or bool(
        OLD_MIGRATION_PATTERN.search(normalized)
    )


def inspect_archive(source, location):
    """Walk nested fat-JAR dependencies as well as top-level entries; stale SDK classes may live in either."""
    findings = []
    archives = 1
    entries = 0
    with zipfile.ZipFile(source) as archive:
        for entry in archive.infolist():
            entries += 1
            member_location = f"{location}!/{entry.filename}"
            if removed_entry(entry.filename):
                findings.append(member_location)
            if entry.filename.lower().endswith(".jar") and not entry.is_dir():
                nested = inspect_archive(io.BytesIO(archive.read(entry)), member_location)
                findings.extend(nested["findings"])
                archives += nested["archives"]
                entries += nested["entries"]
    return {"findings": findings, "archives": archives, "entries": entries}


def verify_artifacts(repository, paths, source_manifest):
    """Bind explicitly selected current artifacts to the working-source identity, avoiding historical target scans."""
    manifest = json.loads(source_manifest.read_text(encoding="utf-8"))
    source_sha256 = manifest.get("sourceSha256")
    if not isinstance(source_sha256, str) or not re.fullmatch(r"[a-f0-9]{64}", source_sha256):
        raise ValueError("source manifest has no valid working-source identity")
    artifacts = []
    for path in paths:
        actual = path.resolve(strict=True)
        relative = actual.relative_to(repository).as_posix()
        before = artifact_fingerprint(actual)
        inspection = inspect_archive(actual, relative)
        after = artifact_fingerprint(actual)
        stable = before == after
        artifacts.append({
            "path": relative,
            **after,
            **inspection,
            "unchangedDuringInspection": stable,
            "passed": stable and not inspection["findings"],
        })
    return {
        "schemaVersion": 1,
        "sourceSha256": source_sha256,
        "passed": bool(artifacts) and all(artifact["passed"] for artifact in artifacts),
        "removedClassStems": list(OLD_CLASS_STEMS),
        "removedMigrationVersions": list(range(2, 19)),
        "artifacts": artifacts,
    }


def main():
    """Write reviewable evidence for explicit fresh JARs and fail when any removed entry or byte drift remains."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--source-manifest", required=True, type=Path)
    parser.add_argument("--jar", required=True, action="append", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    result = verify_artifacts(arguments.repository.resolve(strict=True), arguments.jar, arguments.source_manifest)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"JAVA_ARTIFACT_BASELINE passed={result['passed']} artifacts={len(result['artifacts'])}")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
