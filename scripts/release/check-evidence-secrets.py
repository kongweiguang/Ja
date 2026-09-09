# @author kongweiguang
"""检查证据中的真实凭据形态；公开协议名和依赖名称不是秘密。"""

from pathlib import Path
import re
import sys

PATTERN = re.compile(
    r'api[_ -]?key["\x27]?\s*[:=]\s*["\x27]?[^\s,}\"]{8,}'
    r'|bearer\s+[a-z0-9._-]{8,}|sk-[a-z0-9_-]{20,}|github_token\s*[:=]\s*["\x27]?[a-z0-9_]{12,}',
    re.IGNORECASE,
)


def main() -> int:
    """只报告是否命中，不输出可能敏感的原文；逐文件限制文本读取大小。"""
    root = Path(sys.argv[1])
    for path in root.rglob("*"):
        if path.is_file() and path.suffix.lower() in {".json", ".txt", ".log", ".xml", ".md"}:
            if path.stat().st_size > 64 * 1024 * 1024:
                raise SystemExit("evidence text exceeds scan budget")
            if PATTERN.search(path.read_text(encoding="utf-8", errors="replace")):
                raise SystemExit("credential-shaped value found in evidence")
    print("EVIDENCE_SECRET_SCAN_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
