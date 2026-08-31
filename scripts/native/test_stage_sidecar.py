# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Regression tests for the App Server sidecar staging name contract."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).with_name("stage-sidecar.py")
SPEC = importlib.util.spec_from_file_location("stage_sidecar", SCRIPT)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import failure is environmental
    raise RuntimeError("sidecar staging module is unavailable")
STAGING = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = STAGING
SPEC.loader.exec_module(STAGING)


class AppServerSidecarNameTest(unittest.TestCase):
    """Locks Tauri's platform-specific resource names to the App Server identity."""

    def test_windows_name_includes_executable_extension(self) -> None:
        """Windows resources require the target triple and native executable suffix."""

        self.assertEqual(
            "ja-app-server-x86_64-pc-windows-msvc.exe",
            STAGING.sidecar_file_name("x86_64-pc-windows-msvc"),
        )

    def test_macos_name_has_no_extension(self) -> None:
        """macOS sidecars stay extensionless while retaining the same process prefix."""

        self.assertEqual(
            "ja-app-server-aarch64-apple-darwin",
            STAGING.sidecar_file_name("aarch64-apple-darwin"),
        )


if __name__ == "__main__":
    unittest.main()
