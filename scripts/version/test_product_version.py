# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Regression tests for the first-release product-version governance."""

from __future__ import annotations

import json
import unittest

from scripts.version.product_version import (
    inspect_product_version_sources,
    synchronize_product_version_sources,
)


def _fixture(**overrides: str) -> dict[str, str]:
    """保留 Ja 版本投影与协议样例，并固定桌面 crate 的专属包名。"""
    sources = {
        "package_json": '{"name":"ja","version":"0.1.0"}\n',
        "npm_package_json": (
            '{"name":"@kongweiguang/ja","version":"9.9.9","private":true}\n'
        ),
        "tauri_config": '{\n  "productName": "Ja",\n  "version": "9.9.9"\n}\n',
        "cargo_workspace": (
            '[workspace]\nmembers = []\n\n[workspace.package]\nversion = "9.9.9"\n'
        ),
        "desktop_cargo": '[package]\nname = "ja-desktop"\nversion.workspace = true\n',
        "runtime_cargo": '[package]\nname = "ja-runtime"\nversion.workspace = true\n',
        "cargo_lock": (
            'version = 4\n\n[[package]]\nname = "ja-desktop"\nversion = "9.9.9"\n\n'
            '[[package]]\nname = "ja-runtime"\nversion = "9.9.9"\n\n'
            '[[package]]\nname = "serde"\nversion = "1.0.229"\n'
        ),
        "maven_pom": (
            '<?xml version="1.0"?><project><parent><version>4.0.6</version></parent>'
            '<artifactId>ja-app-server</artifactId><version>9.9.9</version>'
            '<dependencies><dependency><version>7.0.0</version></dependency></dependencies>'
            "</project>\n"
        ),
        "golden_core": (
            '{"jsonrpc":"2.0","id":"c:init","method":"runtime/initialize",'
            '"params":{"clientVersion":"0.1.0"}}\n'
            '{"jsonrpc":"2.0","id":"c:init","result":{"runtime":'
            '{"engine":"ja-kernel","engineVersion":"9.9.9"}}}\n'
        ),
        "java_version_resource": (
            "# @author kongweiguang\nproduct.version=${project.version}\n"
        ),
        "native_resource_config": (
            '{"resources":{"includes":[{"pattern":"ja-build\\\\.properties"}]}}\n'
        ),
        "java_handshake": (
            "import io.github.kongweiguang.ja.foundation.runtime.ProductVersion;\n"
            'runtime.put("engineVersion", ProductVersion.current());\n'
        ),
        "rust_supervisor": (
            'runtime.get("engineVersion").and_then(Value::as_str) '
            '!= Some(env!("CARGO_PKG_VERSION"))\n'
        ),
        "typescript_protocol": (
            'import packageJson from "../../../../../package.json";\n'
            "const schema = { engineVersion: z.literal(packageJson.version) };\n"
        ),
    }
    sources.update(overrides)
    return sources


class ProductVersionTest(unittest.TestCase):
    """Prove synchronization scope and fail-closed identity derivation."""

    def test_sync_preserves_dependency_versions(self) -> None:
        """Only Ja's parsed projections change when the root authority is synchronized."""
        updated = synchronize_product_version_sources(_fixture())

        self.assertEqual(inspect_product_version_sources(updated)[1], [])
        npm_package = json.loads(updated["npm_package_json"])
        self.assertEqual(npm_package["name"], "@kongweiguang/ja")
        self.assertEqual(npm_package["version"], "0.1.0")
        self.assertTrue(npm_package["private"])
        self.assertIn("<parent><version>4.0.6</version></parent>", updated["maven_pom"])
        self.assertIn("<dependency><version>7.0.0</version>", updated["maven_pom"])
        self.assertIn('name = "serde"\nversion = "1.0.229"', updated["cargo_lock"])

    def test_sync_updates_golden_runtime_version_without_changing_client_version(self) -> None:
        """Sync the response identity only so request examples retain their independent client version."""
        updated = synchronize_product_version_sources(_fixture())
        frames = [json.loads(line) for line in updated["golden_core"].splitlines()]

        self.assertEqual(frames[0]["params"]["clientVersion"], "0.1.0")
        self.assertEqual(frames[1]["result"]["runtime"]["engineVersion"], "0.1.0")

    def test_check_reports_golden_runtime_version_drift(self) -> None:
        """Check must fail on a stale executable response even when other identity sources are valid."""
        sources = _fixture()
        sources["tauri_config"] = sources["tauri_config"].replace(
            '"version": "9.9.9"', '"version": "0.1.0"'
        )
        sources["cargo_workspace"] = sources["cargo_workspace"].replace(
            'version = "9.9.9"', 'version = "0.1.0"'
        )
        sources["cargo_lock"] = sources["cargo_lock"].replace(
            'version = "9.9.9"', 'version = "0.1.0"'
        )
        sources["maven_pom"] = sources["maven_pom"].replace(
            '<version>9.9.9</version>', '<version>0.1.0</version>'
        )
        _, drift = inspect_product_version_sources(sources)

        self.assertTrue(
            any(
                entry.startswith(
                    "contracts/golden/v1/valid/core.jsonl runtime.engineVersion:"
                )
                for entry in drift
            )
        )

    def test_check_reports_all_drift_and_bad_tag(self) -> None:
        """Read-only checks aggregate ecosystem drift and immutable release-tag mismatch."""
        version, drift = inspect_product_version_sources(_fixture(), "v0.2.0")

        self.assertEqual(version, "0.1.0")
        self.assertTrue(any(entry.startswith("release tag:") for entry in drift))
        self.assertTrue(any(entry.startswith("app-server/pom.xml") for entry in drift))
        self.assertTrue(any(entry.startswith("Cargo.lock ja-runtime") for entry in drift))
        self.assertTrue(
            any(entry.startswith("packages/ja-npm/package.json version:") for entry in drift)
        )

    def test_check_requires_cargo_workspace_inheritance(self) -> None:
        """Crate manifests cannot reintroduce separately maintained product versions."""
        sources = _fixture(
            desktop_cargo='[package]\nname = "ja-desktop"\nversion = "0.1.0"\n'
        )

        with self.assertRaisesRegex(ValueError, "package.version.workspace"):
            inspect_product_version_sources(sources)

    def test_check_rejects_independent_engine_constants(self) -> None:
        """Protocol consumers must derive identity instead of matching by coincidence."""
        sources = _fixture(
            java_handshake='runtime.put("engineVersion", "0.1.0");\n',
            rust_supervisor=(
                'runtime.get("engineVersion").and_then(Value::as_str) != Some("0.1.0")\n'
            ),
            typescript_protocol=(
                'const schema = { engineVersion: z.literal("0.1.0") };\n'
            ),
        )

        _, drift = inspect_product_version_sources(sources)
        self.assertTrue(any(entry.startswith("Java engineVersion") for entry in drift))
        self.assertTrue(any(entry.startswith("Rust engineVersion") for entry in drift))
        self.assertTrue(any(entry.startswith("TypeScript engineVersion") for entry in drift))

    def test_sync_rejects_non_semantic_authority(self) -> None:
        """Invalid root versions fail before any synchronized source can be produced."""
        with self.assertRaisesRegex(ValueError, "supported semantic version"):
            synchronize_product_version_sources(
                _fixture(package_json='{"name":"ja","version":"latest"}\n')
            )


if __name__ == "__main__":
    unittest.main()
