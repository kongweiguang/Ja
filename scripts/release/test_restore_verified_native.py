# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

"""Regression tests for restoring an already-verified Native Image artifact."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("restore-verified-native.py")
SPEC = importlib.util.spec_from_file_location("restore_verified_native", SCRIPT)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import failure is environmental
    raise RuntimeError("restore script is unavailable")
RESTORE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESTORE)


SOURCE_COMMIT = "a" * 40
NIK_VERSION = "25.0.3-11.0"
NIK_JAVA_VERSION = "25.0.3"
NIK_SHA256 = "b" * 64
RUN_ID = "1234567890"


def _facts(path: Path) -> dict[str, object]:
    """按生产报告格式计算夹具文件事实，避免测试复制摘要实现细节。"""

    data = path.read_bytes()
    return {
        "fileName": path.name,
        "sizeBytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def _write_json(path: Path, value: object) -> None:
    """以稳定 UTF-8 JSON 写入临时 artifact，模拟 Actions 下载出的报告文件。"""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")


def make_fixture(parent: Path, platform: str, arch: str) -> dict[str, object]:
    """创建真实嵌套 download-artifact 目录及与 make-build-report 一致的最小报告。"""

    root = parent / "download"
    native_name = "ja-app-server.exe" if platform == "windows" else "ja-app-server"
    native_path = root / "app-server" / "target" / native_name
    report_path = root / "native-evidence" / "build-report.json"
    sbom_path = root / "native-evidence" / "ja-app-server.json"
    native_path.parent.mkdir(parents=True, exist_ok=True)
    native_path.write_bytes(b"verified native image bytes\x00" + platform.encode("ascii"))
    native_facts = _facts(native_path)
    sbom_path.parent.mkdir(parents=True, exist_ok=True)
    sbom_path.write_bytes(b'{"bomFormat":"CycloneDX","components":[]}\n')
    smoke = {
        "status": "passed",
        "executable": {
            "sizeBytes": native_facts["sizeBytes"],
            "sha256": native_facts["sha256"],
            "mtimeNs": 1,
            "expectedIdentityMatched": True,
        },
    }
    report = {
        "schemaVersion": 1,
        "product": "Ja",
        "sourceCommit": SOURCE_COMMIT,
        "target": {"platform": platform, "arch": arch, "runner": "fixture"},
        "toolchain": {
            "distribution": "BellSoft Liberica Native Image Kit",
            "nikVersion": NIK_VERSION,
            "javaVersion": NIK_JAVA_VERSION,
            "java": "java 25.0.3",
            "nativeImage": "native-image 25.0.3",
            "maven": "Apache Maven 3.9.9",
            "nikArchiveSha256": NIK_SHA256,
            "nativeImageOnly": True,
            "noFallback": True,
        },
        "artifacts": {
            "nativeExecutable": native_facts,
            "sbom": _facts(sbom_path),
        },
        "smoke": smoke,
        "security": {
            "credentialsRedacted": True,
            "jvmEnvironmentRemoved": True,
            "stdoutProtocolOnly": True,
        },
    }
    _write_json(report_path, report)
    return {
        "root": root,
        "native": native_path,
        "report": report_path,
        "sbom": sbom_path,
        "reportValue": report,
    }


class RestoreVerifiedNativeTest(unittest.TestCase):
    """验证恢复只接受完整身份链，并且失败时不覆盖调用方已有文件。"""

    def run_restore(
        self,
        fixture: dict[str, object],
        *,
        platform: str,
        arch: str,
        source_commit: str = SOURCE_COMMIT,
        nik_version: str = NIK_VERSION,
        nik_java_version: str = NIK_JAVA_VERSION,
        nik_sha256: str = NIK_SHA256,
    ) -> tuple[dict[str, object], Path, Path]:
        """使用隔离目标路径调用公共恢复函数，保持每个测试的输出边界独立。"""

        native_name = "ja-app-server.exe" if platform == "windows" else "ja-app-server"
        destination = self.temp_root / "workspace" / "app-server" / "target" / native_name
        evidence = self.temp_root / "evidence-out"
        result = RESTORE.restore_verified_native(
            Path(str(fixture["root"])),
            destination,
            evidence,
            source_commit,
            platform,
            arch,
            nik_version,
            nik_java_version,
            nik_sha256,
            RUN_ID,
        )
        return result, destination, evidence

    def setUp(self) -> None:
        """为真实临时目录测试提供不会污染仓库的输入和输出根。"""

        self._temporary = tempfile.TemporaryDirectory(prefix="ja-restore-native-")
        self.temp_root = Path(self._temporary.name)

    def tearDown(self) -> None:
        """清理每个测试创建的 Native artifact 与证据目录。"""

        self._temporary.cleanup()

    def test_windows_success_copies_native_and_original_report(self) -> None:
        """Windows 恢复保留 .exe 字节，并把原始报告另存为复用证据。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        result, destination, evidence = self.run_restore(fixture, platform="windows", arch="x86_64")
        self.assertEqual(Path(str(fixture["native"])).read_bytes(), destination.read_bytes())
        self.assertEqual(Path(str(fixture["report"])).read_bytes(), (evidence / "reused-native-build-report.json").read_bytes())
        reuse = json.loads((evidence / "native-reuse.json").read_text(encoding="utf-8"))
        self.assertEqual(RUN_ID, reuse["verifiedRunId"])
        self.assertEqual(SOURCE_COMMIT, reuse["sourceCommit"])
        self.assertEqual({"platform": "windows", "arch": "x86_64"}, reuse["target"])
        self.assertEqual(result["sha256"], reuse["sha256"])

    def test_macos_success_restores_executable_permission(self) -> None:
        """macOS 恢复显式补回执行位，因为下载归档不应依赖源文件 mode 保存。"""

        fixture = make_fixture(self.temp_root, "macos", "arm64")
        Path(str(fixture["native"])).chmod(stat.S_IRUSR | stat.S_IWUSR)
        if os.name == "nt":
            self.skipTest("Windows 文件系统不提供 POSIX 执行位语义")
        _result, destination, _evidence = self.run_restore(fixture, platform="macos", arch="arm64")
        self.assertTrue(destination.stat().st_mode & stat.S_IXUSR)

    def test_wrong_nik_sha_is_rejected(self) -> None:
        """NIK archive 摘要错配时必须在任何目标写入前失败。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64", nik_sha256="c" * 64)

    def test_wrong_architecture_is_rejected(self) -> None:
        """Windows 不在固定支持矩阵中提供 arm64 Native artifact。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="arm64")

    def test_wrong_nik_version_is_rejected(self) -> None:
        """发布调用方传入的 NIK 版本必须与成功 main 报告完全一致。"""

        fixture = make_fixture(self.temp_root, "macos", "x86_64")
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="macos", arch="x86_64", nik_version="25.0.2")

    def test_wrong_java_version_is_rejected(self) -> None:
        """NIK 中的 Java 版本独立校验，避免只验证发行包大版本。"""

        fixture = make_fixture(self.temp_root, "macos", "x86_64")
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="macos", arch="x86_64", nik_java_version="21.0.8")

    def test_wrong_native_hash_is_rejected(self) -> None:
        """报告摘要被篡改时，实际二进制摘要校验不能被文件大小掩盖。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        report = dict(fixture["reportValue"])
        report["artifacts"] = dict(report["artifacts"])
        report["artifacts"]["nativeExecutable"] = dict(report["artifacts"]["nativeExecutable"])
        report["artifacts"]["nativeExecutable"]["sha256"] = "d" * 64
        _write_json(Path(str(fixture["report"])), report)
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64")

    def test_wrong_native_size_is_rejected(self) -> None:
        """报告大小被篡改时，恢复必须拒绝半成品身份。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        report = dict(fixture["reportValue"])
        report["artifacts"] = dict(report["artifacts"])
        report["artifacts"]["nativeExecutable"] = dict(report["artifacts"]["nativeExecutable"])
        report["artifacts"]["nativeExecutable"]["sizeBytes"] += 1
        _write_json(Path(str(fixture["report"])), report)
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64")

    def test_source_commit_mismatch_is_rejected_without_outputs(self) -> None:
        """请求提交与报告提交不一致时，不能把已验证二进制借给另一份发布来源。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        destination = self.temp_root / "workspace" / "app-server" / "target" / "ja-app-server.exe"
        evidence = self.temp_root / "evidence-out"
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64", source_commit="b" * 40)
        self.assertFalse(destination.exists())
        self.assertFalse(evidence.exists())

    def test_sbom_byte_mutation_is_rejected_without_outputs(self) -> None:
        """SBOM 字节被修改时，报告摘要校验必须阻止 Native 复制和证据写入。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        Path(str(fixture["sbom"])).write_bytes(b"tampered sbom\n")
        destination = self.temp_root / "workspace" / "app-server" / "target" / "ja-app-server.exe"
        evidence = self.temp_root / "evidence-out"
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64")
        self.assertFalse(destination.exists())
        self.assertFalse(evidence.exists())

    def test_smoke_identity_size_and_hash_are_rejected_when_mismatched(self) -> None:
        """smoke 不重复文件名，但其真实大小和摘要仍必须与 Native artifact 一致。"""

        for field, value in (("sizeBytes", 1), ("sha256", "e" * 64)):
            with self.subTest(field=field):
                fixture = make_fixture(self.temp_root, "windows", "x86_64")
                report = dict(fixture["reportValue"])
                report["smoke"] = dict(report["smoke"])
                report["smoke"]["executable"] = dict(report["smoke"]["executable"])
                report["smoke"]["executable"][field] = value
                _write_json(Path(str(fixture["report"])), report)
                with self.assertRaises(RESTORE.RestoreError):
                    self.run_restore(fixture, platform="windows", arch="x86_64")

    def test_non_numeric_verified_run_id_is_rejected(self) -> None:
        """来源 run id 使用 GitHub 数字标识，拒绝把任意文本写进复用证据。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        with self.assertRaises(RESTORE.RestoreError):
            RESTORE.restore_verified_native(
                Path(str(fixture["root"])),
                self.temp_root / "workspace" / "app-server.exe",
                self.temp_root / "evidence-out",
                SOURCE_COMMIT,
                "windows",
                "x86_64",
                NIK_VERSION,
                NIK_JAVA_VERSION,
                NIK_SHA256,
                "run-123",
            )

    def test_missing_sbom_is_rejected(self) -> None:
        """缺失 SBOM 时不允许只凭 Native executable 继续发布。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        Path(str(fixture["sbom"])).unlink()
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64")

    def test_duplicate_report_basename_is_rejected(self) -> None:
        """同名报告在不同嵌套目录出现时不能任取一个通过。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        duplicate = Path(str(fixture["root"])) / "duplicate" / "build-report.json"
        duplicate.parent.mkdir()
        shutil.copy2(Path(str(fixture["report"])), duplicate)
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64")

    def test_failed_smoke_is_rejected(self) -> None:
        """Native executable 即使摘要正确，也不能绕过失败的生产 smoke。"""

        fixture = make_fixture(self.temp_root, "macos", "x86_64")
        report = dict(fixture["reportValue"])
        report["smoke"] = {"status": "failed", "executable": {"expectedIdentityMatched": True}}
        _write_json(Path(str(fixture["report"])), report)
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="macos", arch="x86_64")

    def test_symlink_is_rejected_when_platform_allows_creation(self) -> None:
        """支持创建符号链接的平台必须拒绝链接输入，避免下载目录逃逸。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        outside = self.temp_root / "outside.json"
        outside.write_text("{}", encoding="utf-8")
        link = Path(str(fixture["root"])) / "link-to-outside"
        try:
            os.symlink(outside, link)
        except (OSError, NotImplementedError):
            self.skipTest("当前平台不允许创建符号链接")
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64")

    def test_preexisting_destination_is_not_overwritten(self) -> None:
        """目标已存在时在写入前失败，并保留调用方原有字节。"""

        fixture = make_fixture(self.temp_root, "windows", "x86_64")
        destination = self.temp_root / "workspace" / "app-server" / "target" / "ja-app-server.exe"
        destination.parent.mkdir(parents=True)
        original = b"existing release input"
        destination.write_bytes(original)
        with self.assertRaises(RESTORE.RestoreError):
            self.run_restore(fixture, platform="windows", arch="x86_64")
        self.assertEqual(original, destination.read_bytes())


if __name__ == "__main__":
    unittest.main()
