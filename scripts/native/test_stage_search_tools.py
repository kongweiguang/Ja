# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Deterministic tests for fixed search-tool resource staging contracts."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("stage-search-tools.py")
SPEC = importlib.util.spec_from_file_location("stage_search_tools", SCRIPT)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import failure is environmental
    raise RuntimeError("search-tool staging module is unavailable")
STAGING = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = STAGING
SPEC.loader.exec_module(STAGING)


class SearchToolStagingTest(unittest.TestCase):
    """锁定当前发布矩阵、固定资产摘要和路径安全边界。"""

    def test_current_matrix_maps_to_official_assets(self) -> None:
        """当前三条 Tauri target triple 必须映射到固定的官方归档名。"""

        self.assertEqual(
            "fd-v10.5.0-x86_64-pc-windows-msvc.zip",
            STAGING.asset_for("fd", "windows", "x86_64").file_name,
        )
        self.assertEqual(
            "ripgrep-15.2.0-aarch64-apple-darwin.tar.gz",
            STAGING.asset_for("rg", "macos", "arm64").file_name,
        )
        self.assertEqual(
            "x86_64-pc-windows-msvc",
            STAGING.target_spec("windows", "amd64", "x86_64-pc-windows-msvc")[2],
        )

    def test_unsupported_matrix_is_rejected(self) -> None:
        """未发布的架构或不匹配 triple 不得静默回退到另一份二进制。"""

        with self.assertRaises(ValueError):
            STAGING.target_spec("windows", "arm64", "x86_64-pc-windows-msvc")
        with self.assertRaises(ValueError):
            STAGING.asset_for("fd", "linux", "x86_64")

    def test_asset_digest_mismatch_fails_closed(self) -> None:
        """下载内容只要尺寸或摘要任一漂移就必须拒绝 staging。"""

        asset = STAGING.asset_for("fd", "windows", "x86_64")
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / asset.file_name
            path.write_bytes(b"x" * asset.size_bytes)
            with self.assertRaisesRegex(RuntimeError, "asset SHA-256 mismatch"):
                STAGING.verify_asset(path, asset)

    def test_archive_member_paths_cannot_escape_extraction_root(self) -> None:
        """归档中的父级、盘符、绝对路径和反斜杠成员都必须失败关闭。"""

        for member in ("../outside", "/absolute", "C:/outside", "nested\\outside"):
            with self.subTest(member=member), self.assertRaises(RuntimeError):
                STAGING.safe_archive_member(member)

    def test_contained_path_rejects_external_destination(self) -> None:
        """输出路径检查必须阻止通过 .. 逃逸到 staging 根外。"""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            root.mkdir()
            with self.assertRaisesRegex(RuntimeError, "escaped"):
                STAGING.contained_path(root, root / ".." / "outside")

    def test_manifest_declares_all_license_files(self) -> None:
        """两个工具的许可文件名必须完整列出，防止 bundle 只带可执行文件。"""

        self.assertEqual(
            ("LICENSE-APACHE", "LICENSE-MIT"),
            STAGING.TOOLS["fd"].license_names,
        )
        self.assertEqual(
            ("COPYING", "LICENSE-MIT", "UNLICENSE"),
            STAGING.TOOLS["rg"].license_names,
        )


if __name__ == "__main__":
    unittest.main()
