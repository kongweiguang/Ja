# @author kongweiguang
"""验证系统未签名安装包仍须携带完整更新签名证据。"""

import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "collect_bundle_evidence", Path(__file__).with_name("collect-bundle-evidence.py")
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class UpdaterEvidenceTest(unittest.TestCase):
    """聚合验签前拒绝缺文件、空文件和重复平台签名，避免产生误导证据。"""

    def test_windows_signature_requires_nonempty_paired_installer(self):
        """同名签名不能替代实际安装包；摘要必须来自真实安装字节。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Ja.exe.sig").write_text("signature", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                MODULE.updater_signature_evidence(root, "windows")
            (root / "Ja.exe").write_bytes(b"installer")
            evidence = MODULE.updater_signature_evidence(root, "windows")
            self.assertEqual(evidence["status"], "pending-aggregate-verification")
            self.assertEqual(evidence["artifact"]["sha256"], MODULE.sha256(root / "Ja.exe"))

    def test_macos_uses_app_archive_not_dmg_signature(self):
        """DMG 负责安装，更新器签名对应独立的 app.tar.gz，不能混淆两者。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dmg = root / "dmg"
            macos = root / "macos"
            dmg.mkdir()
            macos.mkdir()
            (dmg / "Ja.dmg").write_bytes(b"disk-image")
            with self.assertRaises(RuntimeError):
                MODULE.updater_signature_evidence(dmg, "macos")
            (macos / "Ja.app.tar.gz").write_bytes(b"archive")
            (macos / "Ja.app.tar.gz.sig").write_text("signature", encoding="utf-8")
            evidence = MODULE.updater_signature_evidence(dmg, "macos")
            self.assertEqual(evidence["artifact"]["fileName"], "Ja.app.tar.gz")

    def test_empty_or_duplicate_signatures_fail_closed(self):
        """不能在重复候选中任取一个，也不能把空签名记录为可发布。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Ja.exe").write_bytes(b"installer")
            (root / "Ja.exe.sig").write_bytes(b"")
            with self.assertRaises(RuntimeError):
                MODULE.updater_signature_evidence(root, "windows")
            (root / "Ja.exe.sig").write_bytes(b"signature")
            (root / "Other.exe.sig").write_bytes(b"signature")
            with self.assertRaises(RuntimeError):
                MODULE.updater_signature_evidence(root, "windows")


if __name__ == "__main__":
    unittest.main()
