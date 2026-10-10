# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""首次发布时产品版本治理规则的回归测试。"""

from __future__ import annotations

import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ElementTree

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
            '[workspace]\nmembers = ["apps/desktop/src-tauri", "crates/ja-runtime", '
            '"crates/ja-review", "apps/cli"]\n\n[workspace.package]\nversion = "9.9.9"\n'
        ),
        "desktop_cargo": '[package]\nname = "ja-desktop"\nversion.workspace = true\n',
        "runtime_cargo": '[package]\nname = "ja-runtime"\nversion.workspace = true\n',
        "review_cargo": '[package]\nname = "ja-review"\nversion.workspace = true\n',
        "cli_cargo": '[package]\nname = "ja-cli"\nversion.workspace = true\n',
        "cargo_lock": (
            'version = 4\n\n[[package]]\nname = "ja-desktop"\nversion = "9.9.9"\n\n'
            '[[package]]\nname = "ja-runtime"\nversion = "9.9.9"\n\n'
            '[[package]]\nname = "ja-review"\nversion = "9.9.9"\n\n'
            '[[package]]\nname = "ja-cli"\nversion = "9.9.9"\n\n'
            '[[package]]\nname = "serde"\nversion = "1.0.229"\n'
        ),
        "maven_pom": (
            '<?xml version="1.0"?><project><artifactId>ja-parent</artifactId>'
            '<version>9.9.9</version><dependencies><dependency><version>7.0.0</version>'
            "</dependency></dependencies></project>\n"
        ),
        "maven_core_pom": _module_pom("ja-agent-core"),
        "maven_store_local_pom": _module_pom("ja-agent-store-local"),
        "maven_store_pg_pom": _module_pom("ja-agent-store-pg"),
        "maven_skills_local_pom": _module_pom("ja-agent-skills-local"),
        "maven_skills_pg_pom": _module_pom("ja-agent-skills-pg"),
        "maven_example_pom": _module_pom("ja-agent-example"),
        "maven_app_server_pom": _module_pom("ja-app-server"),
        "maven_example_spring_boot_pom": _module_pom(
            "ja-agent-example-springboot", "ja-agent-example"
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


def _module_pom(artifact_id: str, parent_artifact_id: str = "ja-parent") -> str:
    """构造直接或嵌套的 Maven 示例子工程，验证父工程版本同步。"""
    return (
        f"<project><parent><artifactId>{parent_artifact_id}</artifactId><version>9.9.9</version>"
        f"</parent><artifactId>{artifact_id}</artifactId></project>\n"
    )


class ProductVersionTest(unittest.TestCase):
    """验证同步范围，以及标识推导异常时拒绝继续的约束。"""

    def test_springboot_module_name_matches_directory(self) -> None:
        """检查真实 POM 与聚合入口，防止只改目录而遗漏 IDE 项目名或构建坐标。"""
        root = Path(__file__).resolve().parents[2]
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        example = root / "ja-agent" / "example"
        aggregator = ElementTree.parse(example / "pom.xml")
        module = aggregator.findtext("m:modules/m:module", namespaces=namespace)
        self.assertEqual(module, "ja-agent-example-springboot")
        project = ElementTree.parse(example / module / "pom.xml")
        self.assertEqual(project.findtext("m:artifactId", namespaces=namespace), module)
        self.assertEqual(project.findtext("m:name", namespaces=namespace), module)

    def test_sync_preserves_dependency_versions(self) -> None:
        """同步根目录的权威版本时，仅修改已解析的 Ja 版本投影。"""
        updated = synchronize_product_version_sources(_fixture())

        self.assertEqual(inspect_product_version_sources(updated)[1], [])
        npm_package = json.loads(updated["npm_package_json"])
        self.assertEqual(npm_package["name"], "@kongweiguang/ja")
        self.assertEqual(npm_package["version"], "0.1.0")
        self.assertTrue(npm_package["private"])
        self.assertIn("<version>0.1.0</version>", updated["maven_core_pom"])
        self.assertIn("<version>0.1.0</version>", updated["maven_example_pom"])
        self.assertIn(
            "<version>0.1.0</version>", updated["maven_example_spring_boot_pom"]
        )
        self.assertIn("<dependency><version>7.0.0</version>", updated["maven_pom"])
        for package_name in ("ja-desktop", "ja-runtime", "ja-review", "ja-cli"):
            self.assertIn(
                f'name = "{package_name}"\nversion = "0.1.0"',
                updated["cargo_lock"],
            )
        self.assertIn('name = "serde"\nversion = "1.0.229"', updated["cargo_lock"])

    def test_sync_updates_golden_runtime_version_without_changing_client_version(self) -> None:
        """仅同步响应标识，保留请求示例独立的客户端版本。"""
        updated = synchronize_product_version_sources(_fixture())
        frames = [json.loads(line) for line in updated["golden_core"].splitlines()]

        self.assertEqual(frames[0]["params"]["clientVersion"], "0.1.0")
        self.assertEqual(frames[1]["result"]["runtime"]["engineVersion"], "0.1.0")

    def test_check_reports_golden_runtime_version_drift(self) -> None:
        """即使其他标识来源有效，检查遇到过期的可执行响应样例也必须失败。"""
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
                    "contracts/golden/v2/valid/core.jsonl runtime.engineVersion:"
                )
                for entry in drift
            )
        )

    def test_check_reports_all_drift_and_bad_tag(self) -> None:
        """只读检查汇总各工具链的版本偏差和不可变发布标签的不一致。"""
        version, drift = inspect_product_version_sources(_fixture(), "v0.2.0")

        self.assertEqual(version, "0.1.0")
        self.assertTrue(any(entry.startswith("release tag:") for entry in drift))
        self.assertTrue(any(entry.startswith("ja-agent/pom.xml") for entry in drift))
        self.assertTrue(any(entry.startswith("Cargo.lock ja-runtime") for entry in drift))
        self.assertTrue(
            any(entry.startswith("packages/ja-npm/package.json version:") for entry in drift)
        )

    def test_check_requires_cargo_workspace_inheritance_for_all_local_packages(self) -> None:
        """四个 workspace crate 都不得重新引入独立维护的产品版本。"""
        for key, package_name in (
            ("desktop_cargo", "ja-desktop"),
            ("runtime_cargo", "ja-runtime"),
            ("review_cargo", "ja-review"),
            ("cli_cargo", "ja-cli"),
        ):
            with self.subTest(package=package_name):
                sources = _fixture(
                    **{key: f'[package]\nname = "{package_name}"\nversion = "0.1.0"\n'}
                )
                with self.assertRaisesRegex(ValueError, "package.version.workspace"):
                    inspect_product_version_sources(sources)

    def test_check_rejects_untracked_workspace_members(self) -> None:
        """新增本地 crate 必须进入版本投影清单，而不是漏过产品版本检查。"""
        sources = _fixture(
            cargo_workspace=(
                '[workspace]\nmembers = ["apps/desktop/src-tauri", "crates/ja-runtime", '
                '"crates/ja-review"]\n\n[workspace.package]\nversion = "9.9.9"\n'
            )
        )

        with self.assertRaisesRegex(ValueError, "workspace members differ"):
            inspect_product_version_sources(sources)

    def test_check_rejects_independent_engine_constants(self) -> None:
        """协议使用方必须推导标识，不能依赖碰巧一致的固定值。"""
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
        """根版本无效时，须在生成任何同步源码前失败。"""
        with self.assertRaisesRegex(ValueError, "supported semantic version"):
            synchronize_product_version_sources(
                _fixture(package_json='{"name":"ja","version":"latest"}\n')
            )


if __name__ == "__main__":
    unittest.main()
