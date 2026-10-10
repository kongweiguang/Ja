# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Protect final artifact acceptance against stale nested classes and migration resources."""

import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "java_artifact_baseline", Path(__file__).with_name("check-java-artifact-baseline.py")
)
BASELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASELINE)


def jar_bytes(entries):
    """Create physical ZIP entries so acceptance exercises archive traversal, not a precomputed name list."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)
    return buffer.getvalue()


class JavaArtifactBaselineTest(unittest.TestCase):
    """Keep exact removed packages distinct from the replacement API and App-owned receipt."""

    def test_accepts_current_entries_with_nested_sdk(self):
        """The legitimate new codec and receipt must remain allowed inside executable package layouts."""
        current_codec = "io/github/kongweiguang/ja/agent/application/gateway/store/ExecutionRecordCodec.class"
        current_receipt = "io/github/kongweiguang/ja/conversation/domain/model/ClientOperationReceipt.class"
        nested = jar_bytes({current_codec: b"current SDK", current_receipt: b"current App"})
        result = BASELINE.inspect_archive(io.BytesIO(jar_bytes({
            "BOOT-INF/lib/ja-agent-core.jar": nested,
            "BOOT-INF/classes/db/migration/V1__kernel.sql": b"fresh baseline",
        })), "app.jar")
        self.assertEqual([], result["findings"])
        self.assertEqual(2, result["archives"])

    def test_rejects_old_inner_class_and_nested_migration(self):
        """A clean top-level App cannot hide an old SDK alias or removed SQL in a nested dependency."""
        alias = BASELINE.OLD_CLASS_STEMS[0] + "$Legacy.class"
        old_receipt = BASELINE.OLD_CLASS_STEMS[1] + ".class"
        old_factory = BASELINE.OLD_CLASS_STEMS[2] + ".class"
        nested = jar_bytes({alias: b"old alias", "db/migration/V18__old.sql": b"old chain"})
        result = BASELINE.inspect_archive(io.BytesIO(jar_bytes({
            "lib/ja-agent-core.jar": nested,
            "BOOT-INF/classes/" + old_receipt: b"old receipt",
            old_factory: b"old factory",
        })), "app.jar")
        self.assertEqual(4, len(result["findings"]))
        self.assertTrue(any("!/lib/ja-agent-core.jar!/" + alias in finding for finding in result["findings"]))

    def test_report_binds_real_bytes_and_working_source(self):
        """Review evidence must identify the inspected file and source without accepting an empty artifact set."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            manifest = root / "source.json"
            manifest.write_text(json.dumps({"sourceSha256": "a" * 64}), encoding="utf-8")
            artifact = root / "fresh.jar"
            artifact.write_bytes(jar_bytes({"META-INF/MANIFEST.MF": b"fresh"}))
            result = BASELINE.verify_artifacts(root, [artifact], manifest)
            self.assertTrue(result["passed"])
            self.assertEqual("a" * 64, result["sourceSha256"])
            self.assertEqual(BASELINE.artifact_fingerprint(artifact)["sha256"], result["artifacts"][0]["sha256"])
            self.assertFalse(BASELINE.verify_artifacts(root, [], manifest)["passed"])


if __name__ == "__main__":
    unittest.main()
