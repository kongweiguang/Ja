# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Condition {
    <# 把供应链脚本契约失败收敛成稳定断言，避免测试只依赖外部命令退出码。 #>
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not $Condition) {
        throw "SBOM_SCRIPT_TEST_FAILED: $Name"
    }
}

function Write-Utf8File {
    <# 固定无 BOM UTF-8 与 LF，使 fixture 哈希不受 Windows 默认编码影响。 #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($Path, ($Content -replace "`r`n", "`n"), [Text.UTF8Encoding]::new($false))
}

function Get-Sha256 {
    <# 计算 fixture 的精确 bytes 身份，复用生产 manifest 的小写 SHA-256 契约。 #>
    param([Parameter(Mandatory = $true)][string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-CycloneDxEvidenceHash {
    <# 复现生产规范化哈希，锁定“时间戳变化不漂移、依赖内容变化必漂移”的回归契约。 #>
    param([Parameter(Mandatory = $true)][string]$Path)

    $bom = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    if ($bom.PSObject.Properties['serialNumber']) {
        $bom.PSObject.Properties.Remove('serialNumber')
    }
    if ($bom.metadata -and $bom.metadata.PSObject.Properties['timestamp']) {
        $bom.metadata.PSObject.Properties.Remove('timestamp')
    }
    if ($bom.components) {
        $bom.components = @($bom.components | Sort-Object { [string]$_.'bom-ref' })
    }
    if ($bom.dependencies) {
        foreach ($dependency in @($bom.dependencies)) {
            if ($dependency.dependsOn) {
                $dependency.dependsOn = @($dependency.dependsOn | Sort-Object)
            }
        }
        $bom.dependencies = @($bom.dependencies | Sort-Object { [string]$_.ref })
    }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($bom | ConvertTo-Json -Depth 100 -Compress))
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ($algorithm.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $algorithm.Dispose()
    }
}

function Write-JsonFile {
    <# 生成机器 fixture；测试只关心字段与哈希，不依赖 PowerShell 对象展示格式。 #>
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    Write-Utf8File -Path $Path -Content (($Value | ConvertTo-Json -Depth 20) + "`n")
}

$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureRoot = Join-Path $temporaryBase ('ja-sbom-script-test-' + [guid]::NewGuid().ToString('N'))
$promoteScript = Join-Path $PSScriptRoot 'promote-license-candidates.ps1'

try {
    [IO.Directory]::CreateDirectory($fixtureRoot) | Out-Null
    $inputByRole = [ordered]@{
        'node-manifest' = 'package.json'
        'node-lock' = 'pnpm-lock.yaml'
        'cargo-workspace-manifest' = 'Cargo.toml'
        'cargo-package-manifest' = 'src-tauri/Cargo.toml'
        'cargo-lock' = 'Cargo.lock'
        'maven-manifest' = 'app-server/pom.xml'
        'maven-bom' = 'app-server/target/ja-app-server-bom.json'
    }
    foreach ($entry in $inputByRole.GetEnumerator()) {
        $path = Join-Path $fixtureRoot $entry.Value
        if ([string]$entry.Key -eq 'maven-bom') {
            Write-JsonFile -Path $path -Value ([PSCustomObject][ordered]@{
                bomFormat = 'CycloneDX'
                specVersion = '1.6'
                serialNumber = 'urn:uuid:fixture'
                metadata = [PSCustomObject][ordered]@{ timestamp = '2026-01-01T00:00:00Z' }
                components = @([PSCustomObject][ordered]@{ 'bom-ref' = 'pkg:maven/example/fixture@1.0.0'; version = '1.0.0' })
                dependencies = @([PSCustomObject][ordered]@{ ref = 'pkg:maven/example/fixture@1.0.0'; dependsOn = @() })
            })
        } else {
            Write-Utf8File -Path $path -Content ("fixture:{0}`n" -f $entry.Key)
        }
    }

    $candidateRoot = Join-Path $fixtureRoot 'candidate'
    $licensePath = Join-Path $candidateRoot 'text/license.txt'
    Write-Utf8File -Path $licensePath -Content "Permission is hereby granted for fixture use.`n"
    $licenseHash = Get-Sha256 -Path $licensePath
    $hashAddressedLicensePath = Join-Path $candidateRoot ("text/$licenseHash.txt")
    Move-Item -LiteralPath $licensePath -Destination $hashAddressedLicensePath
    $licensePath = $hashAddressedLicensePath
    $inputs = @($inputByRole.GetEnumerator() | ForEach-Object {
        $path = Join-Path $fixtureRoot $_.Value
        $hashMode = if ([string]$_.Key -eq 'maven-bom') { 'cyclonedx-dependency-graph-v1' } else { 'raw-bytes' }
        $hash = if ($hashMode -eq 'raw-bytes') { Get-Sha256 -Path $path } else { Get-CycloneDxEvidenceHash -Path $path }
        [PSCustomObject][ordered]@{ role = [string]$_.Key; path = ([string]$_.Value).Replace('\', '/'); hashMode = $hashMode; sha256 = $hash }
    })
    $candidate = [PSCustomObject][ordered]@{
        schemaVersion = 3
        status = 'candidate-review-pending'
        inputs = $inputs
        mappings = @([PSCustomObject][ordered]@{
            ecosystem = 'npm'
            name = 'fixture-package'
            version = '1.0.0'
            declaredLicense = 'MIT'
            repository = 'https://example.invalid/fixture'
            homepage = ''
            sourceFile = 'LICENSE'
            archiveFile = "text/$licenseHash.txt"
            sha256 = $licenseHash
        })
        missing = @()
        summary = [PSCustomObject][ordered]@{
            uniqueTextFiles = 1
            mappingCount = 1
            missingTextCount = 0
        }
    }
    Write-JsonFile -Value $candidate -Path (Join-Path $candidateRoot 'manifest.json')

    Write-Utf8File -Path (Join-Path $fixtureRoot 'Cargo.lock') -Content "fixture:changed-cargo-lock`n"
    $staleOutput = @(& pwsh -NoProfile -File $promoteScript -RepositoryRoot $fixtureRoot `
        -CandidateDirectory 'candidate' -ConfirmSourceReview 2>&1 | ForEach-Object { [string]$_ })
    Assert-Condition -Condition ($LASTEXITCODE -ne 0 -and ($staleOutput -join "`n").Contains('candidate input changed after collection: cargo-lock')) -Name 'stale-input-rejected'
    Assert-Condition -Condition (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot 'LICENSES/approved'))) -Name 'stale-input-no-archive'

    Write-Utf8File -Path (Join-Path $fixtureRoot 'Cargo.lock') -Content "fixture:cargo-lock`n"
    $candidate.inputs = @($inputByRole.GetEnumerator() | ForEach-Object {
        $path = Join-Path $fixtureRoot $_.Value
        $hashMode = if ([string]$_.Key -eq 'maven-bom') { 'cyclonedx-dependency-graph-v1' } else { 'raw-bytes' }
        $hash = if ($hashMode -eq 'raw-bytes') { Get-Sha256 -Path $path } else { Get-CycloneDxEvidenceHash -Path $path }
        [PSCustomObject][ordered]@{ role = [string]$_.Key; path = ([string]$_.Value).Replace('\', '/'); hashMode = $hashMode; sha256 = $hash }
    })
    Write-JsonFile -Value $candidate -Path (Join-Path $candidateRoot 'manifest.json')

    $bomPath = Join-Path $fixtureRoot $inputByRole['maven-bom']
    $bom = Get-Content -Raw -LiteralPath $bomPath | ConvertFrom-Json
    $bom.metadata.timestamp = '2026-01-02T00:00:00Z'
    Write-JsonFile -Value $bom -Path $bomPath
    $validationOutput = @(& pwsh -NoProfile -File $promoteScript -RepositoryRoot $fixtureRoot `
        -CandidateDirectory 'candidate' -ValidateOnly 2>&1 | ForEach-Object { [string]$_ })
    Assert-Condition -Condition ($LASTEXITCODE -eq 0 -and ($validationOutput -join "`n").Contains('status=candidate-validated')) -Name ('candidate-validation-succeeded: ' + ($validationOutput -join ' '))
    $bom.components[0].version = '2.0.0'
    Write-JsonFile -Value $bom -Path $bomPath
    $bomDriftOutput = @(& pwsh -NoProfile -File $promoteScript -RepositoryRoot $fixtureRoot `
        -CandidateDirectory 'candidate' -ValidateOnly 2>&1 | ForEach-Object { [string]$_ })
    Assert-Condition -Condition ($LASTEXITCODE -ne 0 -and ($bomDriftOutput -join "`n").Contains('candidate input changed after collection: maven-bom')) -Name 'bom-content-drift-rejected'
    $bom.components[0].version = '1.0.0'
    Write-JsonFile -Value $bom -Path $bomPath
    Assert-Condition -Condition (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot 'LICENSES/approved'))) -Name 'validation-does-not-promote'
    $promotionOutput = @(& pwsh -NoProfile -File $promoteScript -RepositoryRoot $fixtureRoot `
        -CandidateDirectory 'candidate' -ConfirmSourceReview 2>&1 | ForEach-Object { [string]$_ })
    Assert-Condition -Condition ($LASTEXITCODE -eq 0) -Name ('pending-promotion-succeeded: ' + ($promotionOutput -join ' '))
    $archive = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot 'LICENSES/approved/manifest.json') | ConvertFrom-Json
    Assert-Condition -Condition ([int]$archive.schemaVersion -eq 4) -Name 'archive-schema-4'
    Assert-Condition -Condition ([string]$archive.status -eq 'source-verified-pending-legal-review') -Name 'promotion-does-not-approve'
    Assert-Condition -Condition (@($archive.inputs).Count -eq 7) -Name 'role-bound-inputs-preserved'
    $archiveText = Join-Path (Join-Path $fixtureRoot 'LICENSES/approved/text') ($candidate.mappings[0].sha256 + '.txt')
    Assert-Condition -Condition ((Get-Sha256 -Path $archiveText) -eq $candidate.mappings[0].sha256) -Name 'archive-bytes-match-hash'

    Write-Output 'SBOM_SCRIPT_TEST_OK stale=1 bomTimestampIgnored=1 bomDrift=1 pendingPromotion=1 inputs=7'
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $expectedPrefix = $temporaryBase.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedFixture.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedFixture).StartsWith('ja-sbom-script-test-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force -ErrorAction SilentlyContinue
    }
}
