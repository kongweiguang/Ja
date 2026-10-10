# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""针对 App Server sidecar 暂存命名契约的回归测试。"""

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
    """锁定 Tauri 各平台资源名称与 App Server 身份的一致性。"""

    def test_windows_name_includes_executable_extension(self) -> None:
        """Windows 资源名必须包含 target triple 和原生可执行文件后缀。"""

        self.assertEqual(
            "ja-app-server-x86_64-pc-windows-msvc.exe",
            STAGING.sidecar_file_name("x86_64-pc-windows-msvc"),
        )

    def test_macos_name_has_no_extension(self) -> None:
        """macOS sidecar 不带扩展名，但保留相同的进程名前缀。"""

        self.assertEqual(
            "ja-app-server-aarch64-apple-darwin",
            STAGING.sidecar_file_name("aarch64-apple-darwin"),
        )


if __name__ == "__main__":
    unittest.main()
