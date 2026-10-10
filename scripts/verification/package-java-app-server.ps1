# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$OutputDirectory = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = [System.IO.Path]::GetFullPath($RepositoryRoot)
$pom = Join-Path $root 'ja-agent/pom.xml'
$target = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $root 'ja-agent/ja-app-server\target'
} else {
    [System.IO.Path]::GetFullPath($OutputDirectory)
}
$jar = Join-Path $target 'ja-app-server.jar'
$moduleBuildDirectory = 'target-ja-app-package-' + [Guid]::NewGuid().ToString('N')

# 在 Maven 生成薄 JAR 中间产物前，确认目标文件可以原子替换。
function Test-ArtifactReplaceAdmission {
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { return $true }
    try {
        $stream = [System.IO.File]::Open($jar, [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        $stream.Dispose()
        return $true
    } catch [System.IO.IOException] {
        return $false
    }
}

# 检查 Solon 可执行 JAR 的结构，不接受 Maven 重打包前的普通薄 JAR。
function Test-FatJarLayout {
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { return $false }
    try {
        Add-Type -AssemblyName System.IO.Compression
        $archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
        try {
            $hasManifest = $false
            $nestedJars = 0
            foreach ($entry in $archive.Entries) {
                if ($entry.FullName -eq 'META-INF/MANIFEST.MF') { $hasManifest = $true }
                if ($entry.FullName.EndsWith('.jar', [System.StringComparison]::OrdinalIgnoreCase)) {
                    $nestedJars++
                }
            }
            return $hasManifest -and $nestedJars -gt 0
        } finally {
            $archive.Dispose()
        }
    } catch {
        return $false
    }
}

if (-not (Test-ArtifactReplaceAdmission)) {
    [Console]::Error.WriteLine('Ja JAR is in use; stop the owning desktop/sidecar before packaging')
    exit 2
}

& mvn.cmd -B -ntp -f $pom -pl :ja-app-server -am -DskipTests "-Dja.build.directory=$moduleBuildDirectory" "-Dja.app-server.build.directory=$target" package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not (Test-FatJarLayout)) {
    [Console]::Error.WriteLine('Solon package completed without a verified executable fat JAR layout')
    exit 1
}

Write-Output 'JAVA_APP_SERVER_PACKAGE_OK layout=fat-jar'
