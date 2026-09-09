# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Regression tests for the first-release product-version governance."""

from __future__ import annotations

import unittest

from scripts.version.product_version import (
    inspect_product_version_sources,
    synchronize_product_version_sources,
)


def _fixture(**overrides: str) -> dict[str, str]:
    """Cover Maven parent/dependency versions and registry packages that sync must preserve."""
    sources = {
        "package_json": '{"name":"ja","version":"0.1.0"}\n',
        "tauri_config": '{\n  "productName": "Ja",\n  "version": "9.9.9"\n}\n',
        "cargo_workspace": (
            '[workspace]\nmembers = []\n\n[workspace.package]\nversion = "9.9.9"\n'
        ),
        "desktop_cargo": '[package]\nname = "ja"\nversion.workspace = true\n',
        "runtime_cargo": '[package]\nname = "ja-runtime"\nversion.workspace = true\n',
        "cargo_lock": (
            'version = 4\n\n[[package]]\nname = "ja"\nversion = "9.9.9"\n\n'
            '[[package]]\nname = "ja-runtime"\nversion = "9.9.9"\n\n'
            '[[package]]\nname = "serde"\nversion = "1.0.229"\n'
        ),
        "maven_pom": (
            '<?xml version="1.0"?><project><parent><version>4.0.6</version></parent>'
            '<artifactId>ja-app-server</artifactId><version>9.9.9</version>'
            '<dependencies><dependency><version>7.0.0</version></dependency></dependencies>'
            "</project>\n"
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
        self.assertIn("<parent><version>4.0.6</version></parent>", updated["maven_pom"])
        self.assertIn("<dependency><version>7.0.0</version>", updated["maven_pom"])
        self.assertIn('name = "serde"\nversion = "1.0.229"', updated["cargo_lock"])

    def test_check_reports_all_drift_and_bad_tag(self) -> None:
        """Read-only checks aggregate ecosystem drift and immutable release-tag mismatch."""
        version, drift = inspect_product_version_sources(_fixture(), "v0.2.0")

        self.assertEqual(version, "0.1.0")
        self.assertTrue(any(entry.startswith("release tag:") for entry in drift))
        self.assertTrue(any(entry.startswith("app-server/pom.xml") for entry in drift))
        self.assertTrue(any(entry.startswith("Cargo.lock ja-runtime") for entry in drift))

    def test_check_requires_cargo_workspace_inheritance(self) -> None:
        """Crate manifests cannot reintroduce separately maintained product versions."""
        sources = _fixture(
            desktop_cargo='[package]\nname = "ja"\nversion = "0.1.0"\n'
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
