# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$env:PYTHONDONTWRITEBYTECODE = "1"
Push-Location $root
try {
    # 合同只能由三端共同通过；单独 schema 结果不足以证明生产 parser 没有漂移。
    $runner = Join-Path $root "tests/contract/run.py"
    & python -B $runner
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
