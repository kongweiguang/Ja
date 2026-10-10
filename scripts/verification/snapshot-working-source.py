# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Bind local verification to the current files, including uncommitted source."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def git_paths(root, *arguments):
    """Use Git's ignore rules so build outputs and developer data stay outside evidence."""
    output = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z", *arguments])
    return {entry.decode("utf-8") for entry in output.split(b"\0") if entry}


def admitted_path(root, relative):
    """Reject links and local runtime data rather than following them outside the checkout."""
    path = root / relative
    parts = Path(relative).parts
    forbidden = {".git", ".tmp", "node_modules", ".ja", "userdata", "__pycache__"}
    if any(part in forbidden or part == "target" or part.startswith("target-") for part in parts):
        return None
    if path.suffix.lower() in {".log", ".db", ".sqlite", ".sqlite3", ".pem", ".key", ".pfx"}:
        return None
    if path.name in {"auth.json", ".env", ".env.local"}:
        return None
    if not path.is_file():
        return None
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(root) or resolved != path.absolute():
        raise ValueError(f"source path resolves through a link: {relative}")
    return path


def source_records(root, extra_files):
    """Hash exact working bytes and preserve tracked deletions in the same identity."""
    tracked = git_paths(root, "--cached")
    discovered = tracked | git_paths(root, "--others", "--exclude-standard")
    for extra in extra_files:
        relative = Path(extra).as_posix()
        if Path(relative).is_absolute() or ".." in Path(relative).parts:
            raise ValueError("extra source paths must be repository-relative")
        discovered.add(relative)
    records = []
    for relative in sorted(discovered):
        path = admitted_path(root, relative)
        if path is None:
            continue
        digest = hashlib.sha256()
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
        records.append({"path": relative, "bytes": path.stat().st_size, "sha256": digest.hexdigest()})
    deleted = sorted(relative for relative in tracked if not (root / relative).exists())
    identity = {"files": records, "deletedTrackedPaths": deleted}
    encoded = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {"schemaVersion": 1, "sourceSha256": hashlib.sha256(encoded).hexdigest(), **identity}


def create_archive(root, manifest, destination):
    """Read each source again while archiving and fail if it changed after fingerprinting."""
    temporary = destination.with_suffix(destination.suffix + ".partial")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for record in manifest["files"]:
            digest = hashlib.sha256()
            with (root / record["path"]).open("rb") as source, archive.open(record["path"], "w") as target:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk)
                    target.write(chunk)
            if digest.hexdigest() != record["sha256"]:
                raise ValueError(f"source changed during archive: {record['path']}")
        archive.writestr("VERIFICATION-SOURCE-MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    os.replace(temporary, destination)


def main():
    """Capture before a gate, then compare after it without silently accepting source drift."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--extra-file", action="append", default=[])
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    root = args.repository.resolve(strict=True)
    manifest = source_records(root, args.extra_file)
    if args.verify:
        expected = json.loads(args.manifest.read_text(encoding="utf-8"))
        if manifest != expected:
            before = {record["path"]: record for record in expected["files"]}
            after = {record["path"]: record for record in manifest["files"]}
            changed = sorted(path for path in before.keys() | after.keys() if before.get(path) != after.get(path))
            raise SystemExit("WORKING_SOURCE_CHANGED " + json.dumps(changed, ensure_ascii=False))
    else:
        if args.manifest.exists() or (args.archive and args.archive.exists()):
            raise ValueError("source evidence already exists; choose fresh paths")
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        if args.archive:
            args.archive.parent.mkdir(parents=True, exist_ok=True)
            create_archive(root, manifest, args.archive)
        args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"WORKING_SOURCE_OK files={len(manifest['files'])} sha256={manifest['sourceSha256']}")


if __name__ == "__main__":
    main()
