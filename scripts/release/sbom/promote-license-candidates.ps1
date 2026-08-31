# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
    [Parameter(Mandatory = $true)][string]$CandidateDirectory,
    [string]$SpdxCommit = 'c4a7237ec8f4654e867546f9f409749300f1bf4c',
    [switch]$AllowNetwork,
    [switch]$ValidateOnly,
    [switch]$ConfirmSourceReview,
    [switch]$MarkApproved
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-RepositoryPath {
    <#
    Resolves a repository-relative path and rejects missing inputs before any archive mutation.
    Keeping this boundary local prevents a candidate from copying arbitrary cache files into the
    checked-in license directory.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BasePath,
        [switch]$RequireExisting
    )

    $resolved = if ([IO.Path]::IsPathRooted($Path)) {
        [IO.Path]::GetFullPath($Path)
    } else {
        [IO.Path]::GetFullPath((Join-Path $BasePath $Path))
    }
    if ($RequireExisting -and -not (Test-Path -LiteralPath $resolved)) {
        throw "path does not exist: $Path"
    }
    return $resolved
}

function Get-RelativePath {
    <#
    Serializes archive paths with forward slashes so the provenance remains stable across Windows
    and macOS release runners.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BasePath
    )

    return ([IO.Path]::GetRelativePath($BasePath, $Path)).Replace('\', '/')
}

function Get-Sha256 {
    <#
    Hashes an exact byte file; the hash is the archive identity and prevents silent text changes
    while a candidate is promoted.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-EvidenceHash {
    <#
    Recomputes candidate input identities with the declared mode. CycloneDX run metadata is removed
    because a fresh Maven invocation must not invalidate an otherwise identical dependency graph.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][ValidateSet('raw-bytes', 'cyclonedx-dependency-graph-v1')][string]$HashMode
    )

    if ($HashMode -eq 'raw-bytes') {
        return Get-Sha256 -Path $Path
    }
    $bom = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    if ([string]$bom.bomFormat -ne 'CycloneDX') {
        throw 'Maven BOM is not a CycloneDX document'
    }
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
    $canonicalBytes = [Text.UTF8Encoding]::new($false).GetBytes(($bom | ConvertTo-Json -Depth 100 -Compress))
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ($algorithm.ComputeHash($canonicalBytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $algorithm.Dispose()
    }
}

function Write-JsonFile {
    <#
    Writes deterministic UTF-8 JSON for machine review and later release evidence regeneration.
    #>
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $json = $Value | ConvertTo-Json -Depth 30
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Resolve-SpdxIds {
    <#
    Converts only the small set of explicit SPDX expressions present in the candidate manifest to
    IDs. This is not a license classifier: an unknown expression fails closed instead of being
    guessed from package names.
    #>
    param([Parameter(Mandatory = $true)][string]$Expression)

    $normalized = $Expression.Trim()
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return @()
    }
    $normalized = $normalized -replace 'GNU Lesser General Public License', 'LGPL-2.1-or-later'
    $normalized = $normalized -replace '/', ' OR '
    $parts = @($normalized -split '(?i)\s+OR\s+' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $known = @{
        'Apache-2.0' = $true
        'BSD-2-Clause' = $true
        'BSD-3-Clause' = $true
        'EPL-2.0' = $true
        'ISC' = $true
        'LGPL-2.1-or-later' = $true
        'MIT' = $true
        'MIT-0' = $true
        'MPL-2.0' = $true
        'Zlib' = $true
    }
    foreach ($part in $parts) {
        if (-not $known.ContainsKey($part)) {
            throw "candidate expression is not an explicitly supported SPDX expression: $Expression"
        }
    }
    return @($parts | Select-Object -Unique)
}

function Get-SpdxText {
    <#
    Retrieves the SPDX canonical text at a pinned commit only when the caller explicitly enables
    network access; the commit and response hash are recorded in the resulting provenance.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][string]$TemporaryDirectory,
        [Parameter(Mandatory = $true)][switch]$NetworkEnabled
    )

    if (-not $NetworkEnabled) {
        throw "missing SPDX text $Id; rerun with -AllowNetwork after reviewing the pinned source"
    }
    $url = "https://raw.githubusercontent.com/spdx/license-list-data/$Commit/text/$Id.txt"
    $download = Join-Path $TemporaryDirectory "$Id.txt"
    Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30 -OutFile $download
    if (-not (Test-Path -LiteralPath $download -PathType Leaf)) {
        throw "SPDX source did not produce a file: $url"
    }
    return [PSCustomObject][ordered]@{
        id = $Id
        url = $url
        sha256 = Get-Sha256 -Path $download
        bytes = [IO.File]::ReadAllBytes($download)
    }
}

function Copy-HashAddressedText {
    <#
    Copies one candidate or canonical text blob into the hash-addressed archive, refusing a hash
    collision whose bytes differ.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$TextDirectory
    )

    $sha = Get-Sha256 -Path $SourcePath
    $target = Join-Path $TextDirectory "$sha.txt"
    if (Test-Path -LiteralPath $target -PathType Leaf) {
        if ((Get-Sha256 -Path $target) -ne $sha) {
            throw "archive hash collision: $sha"
        }
    } else {
        Copy-Item -LiteralPath $SourcePath -Destination $target -Force
    }
    return [PSCustomObject][ordered]@{
        archiveFile = "text/$sha.txt"
        sha256 = $sha
        sizeBytes = [int64](Get-Item -LiteralPath $target).Length
    }
}

function Copy-CanonicalText {
    <#
    Writes a downloaded SPDX byte sequence through the same hash-addressed path used for cached
    candidate files, preserving one canonical copy for all packages that share a license.
    #>
    param(
        [Parameter(Mandatory = $true)][byte[]]$Bytes,
        [Parameter(Mandatory = $true)][string]$TextDirectory
    )

    $sha = ([Security.Cryptography.SHA256]::Create().ComputeHash($Bytes) |
        ForEach-Object { $_.ToString('x2') }) -join ''
    $target = Join-Path $TextDirectory "$sha.txt"
    if (Test-Path -LiteralPath $target -PathType Leaf) {
        if ((Get-Sha256 -Path $target) -ne $sha) {
            throw "archive hash collision: $sha"
        }
    } else {
        [IO.File]::WriteAllBytes($target, $Bytes)
    }
    return [PSCustomObject][ordered]@{
        archiveFile = "text/$sha.txt"
        sha256 = $sha
        sizeBytes = [int64]$Bytes.Length
    }
}

function Confirm-CandidateInputs {
    <#
    Re-hashes every role-bound candidate input before promotion.  This prevents a reviewed text
    set from being attached to lockfiles or a Maven BOM that changed after collection.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]$Candidate
    )

    if ([int]$Candidate.schemaVersion -ne 3) {
        throw 'candidate schemaVersion must be 3'
    }
    $inputs = @($Candidate.inputs)
    $requiredRoles = @(
        'node-manifest', 'node-lock', 'cargo-workspace-manifest', 'cargo-package-manifest',
        'cargo-lock', 'maven-manifest', 'maven-bom'
    )
    if ($inputs.Count -ne $requiredRoles.Count -or
        @($inputs | ForEach-Object { [string]$_.role } | Sort-Object -Unique).Count -ne $requiredRoles.Count) {
        throw 'candidate inputs do not form the required unique role set'
    }
    foreach ($role in $requiredRoles) {
        $input = @($inputs | Where-Object { [string]$_.role -eq $role })
        if ($input.Count -ne 1) {
            throw "candidate input role is missing or duplicated: $role"
        }
        $full = Resolve-RepositoryPath -Path ([string]$input[0].path) -BasePath $Root -RequireExisting
        $expectedHashMode = if ($role -eq 'maven-bom') { 'cyclonedx-dependency-graph-v1' } else { 'raw-bytes' }
        if ([string]$input[0].hashMode -ne $expectedHashMode) {
            throw "candidate input hash mode is invalid: $role"
        }
        $actualHash = Get-EvidenceHash -Path $full -HashMode $expectedHashMode
        if ($actualHash -ne [string]$input[0].sha256) {
            throw "candidate input changed after collection: $role"
        }
    }
    return @($inputs | Sort-Object role | ForEach-Object {
        [PSCustomObject][ordered]@{
            role = [string]$_.role
            path = [string]$_.path
            hashMode = [string]$_.hashMode
            sha256 = [string]$_.sha256
        }
    })
}

function Confirm-CandidateArchive {
    <#
    Verifies the candidate text closure and all mapping hashes without promoting or changing review
    state.  Validation is deliberately separate from source/legal approval.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$CandidateRoot,
        [Parameter(Mandatory = $true)]$Candidate
    )

    $referenced = [System.Collections.Generic.List[string]]::new()
    foreach ($mapping in @($Candidate.mappings)) {
        $relativePath = ([string]$mapping.archiveFile).Replace('\', '/')
        if ($relativePath -notmatch '^text/(?<hash>[0-9a-f]{64})\.txt$' -or
            [string]$mapping.sha256 -ne $Matches.hash) {
            throw "candidate mapping is not hash-addressed: $($mapping.ecosystem)/$($mapping.name)"
        }
        $fullPath = [IO.Path]::GetFullPath((Join-Path $CandidateRoot $relativePath))
        $relative = [IO.Path]::GetRelativePath($CandidateRoot, $fullPath)
        if ($relative.StartsWith('..' + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal) -or
            [IO.Path]::IsPathRooted($relative) -or -not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "candidate mapping path is missing or outside candidate: $relativePath"
        }
        if ((Get-Sha256 -Path $fullPath) -ne [string]$mapping.sha256) {
            throw "candidate mapping bytes changed: $($mapping.ecosystem)/$($mapping.name)"
        }
        $referenced.Add($relativePath)
    }
    $textRoot = Join-Path $CandidateRoot 'text'
    $actual = @()
    if (Test-Path -LiteralPath $textRoot -PathType Container) {
        $actual = @(Get-ChildItem -LiteralPath $textRoot -File |
            ForEach-Object { "text/$($_.Name)" } | Sort-Object -Unique)
    }
    $expected = @($referenced | Sort-Object -Unique)
    if (($actual -join "`n") -ne ($expected -join "`n")) {
        throw 'candidate text set differs from mapping references'
    }
    if ([int]$Candidate.summary.uniqueTextFiles -ne $actual.Count -or
        [int]$Candidate.summary.mappingCount -ne @($Candidate.mappings).Count -or
        [int]$Candidate.summary.missingTextCount -ne @($Candidate.missing).Count) {
        throw 'candidate summary does not match mappings, missing records, and text closure'
    }
    return [PSCustomObject][ordered]@{
        textFiles = [int]$actual.Count
        mappings = [int](@($Candidate.mappings).Count)
        missing = [int](@($Candidate.missing).Count)
    }
}

$root = [IO.Path]::GetFullPath($RepositoryRoot)
$candidateRoot = Resolve-RepositoryPath -Path $CandidateDirectory -BasePath $root -RequireExisting
$candidatePath = Join-Path $candidateRoot 'manifest.json'
if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) {
    throw "candidate manifest is missing: $CandidateDirectory"
}
$candidate = Get-Content -Raw -LiteralPath $candidatePath | ConvertFrom-Json
if ([string]$candidate.status -ne 'candidate-review-pending') {
    throw "candidate status is not candidate-review-pending"
}
$inputEvidence = @(Confirm-CandidateInputs -Root $root -Candidate $candidate)
$candidateAudit = Confirm-CandidateArchive -CandidateRoot $candidateRoot -Candidate $candidate
if ($ValidateOnly) {
    Write-Output ('status=candidate-validated textFiles={0} mappings={1} missing={2}' -f `
        $candidateAudit.textFiles, $candidateAudit.mappings, $candidateAudit.missing)
    exit 0
}
if (-not $ConfirmSourceReview) {
    throw 'pass -ConfirmSourceReview only after reviewing candidate mappings and missing records'
}

$archivePath = 'LICENSES\approved'
$archiveRoot = Resolve-RepositoryPath -Path $archivePath -BasePath $root
if (Test-Path -LiteralPath $archiveRoot) {
    $existing = @(Get-ChildItem -LiteralPath $archiveRoot -Recurse -File -ErrorAction SilentlyContinue)
    if ($existing.Count -gt 0) {
        throw "refusing to overwrite non-empty license archive: $archivePath"
    }
} else {
    [IO.Directory]::CreateDirectory($archiveRoot) | Out-Null
}
$textDirectory = Join-Path $archiveRoot 'text'
$temporaryDirectory = Join-Path $archiveRoot '.download'
[IO.Directory]::CreateDirectory($textDirectory) | Out-Null
[IO.Directory]::CreateDirectory($temporaryDirectory) | Out-Null

$mappings = [System.Collections.Generic.List[object]]::new()
$canonical = @{}
foreach ($mapping in @($candidate.mappings)) {
    $candidateFile = Resolve-RepositoryPath -Path (Join-Path $candidateRoot ([string]$mapping.archiveFile)) `
        -BasePath $root -RequireExisting
    $copied = Copy-HashAddressedText -SourcePath $candidateFile -TextDirectory $textDirectory
    if ([string]$copied.sha256 -ne [string]$mapping.sha256) {
        throw "candidate hash mismatch: $($mapping.ecosystem)/$($mapping.name)"
    }
    $mappings.Add([PSCustomObject][ordered]@{
        ecosystem = [string]$mapping.ecosystem
        name = [string]$mapping.name
        version = [string]$mapping.version
        declaredLicense = [string]$mapping.declaredLicense
        repository = [string]$mapping.repository
        homepage = [string]$mapping.homepage
        sourceFile = [string]$mapping.sourceFile
        archiveFiles = @([string]$copied.archiveFile)
        provenance = 'candidate-cache-exact-bytes'
    })
}

$missingReview = [System.Collections.Generic.List[object]]::new()
foreach ($missing in @($candidate.missing)) {
    if ([string]$missing.name -eq 'ja' -and [string]$missing.ecosystem -eq 'cargo') {
        $projectLicense = Resolve-RepositoryPath -Path 'LICENSE' -BasePath $root -RequireExisting
        $copied = Copy-HashAddressedText -SourcePath $projectLicense -TextDirectory $textDirectory
        $missingReview.Add([PSCustomObject][ordered]@{
            ecosystem = [string]$missing.ecosystem
            name = [string]$missing.name
            version = [string]$missing.version
            declaredLicense = 'GPL-3.0-or-later'
            repository = 'https://github.com/kongweiguang/ja'
            homepage = 'https://github.com/kongweiguang/ja'
            archiveFiles = @([string]$copied.archiveFile)
            source = @([PSCustomObject][ordered]@{
                path = 'LICENSE'
                sha256 = [string]$copied.sha256
            })
            notice = 'project license text is archived from the checked-in repository root'
        })
        continue
    }
    $ids = @(Resolve-SpdxIds -Expression ([string]$missing.declaredLicense))
    $archiveFiles = [System.Collections.Generic.List[string]]::new()
    $sources = [System.Collections.Generic.List[object]]::new()
    foreach ($id in $ids) {
        if (-not $canonical.ContainsKey($id)) {
            $source = Get-SpdxText -Id $id -Commit $SpdxCommit -TemporaryDirectory $temporaryDirectory `
                -NetworkEnabled:$AllowNetwork
            $canonical[$id] = $source
        }
        $copied = Copy-CanonicalText -Bytes $canonical[$id].bytes -TextDirectory $textDirectory
        $archiveFiles.Add([string]$copied.archiveFile)
        $sources.Add([PSCustomObject][ordered]@{
            id = $id
            url = [string]$canonical[$id].url
            sha256 = [string]$copied.sha256
        })
    }
    if ($ids.Count -eq 0) {
        throw "missing package has no supported license expression: $($missing.name)@$($missing.version)"
    }
    $missingReview.Add([PSCustomObject][ordered]@{
        ecosystem = [string]$missing.ecosystem
        name = [string]$missing.name
        version = [string]$missing.version
        declaredLicense = [string]$missing.declaredLicense
        repository = [string]$missing.repository
        homepage = [string]$missing.homepage
        archiveFiles = @($archiveFiles)
        source = @($sources)
        notice = 'no package-supplied license/notice bytes were present in the locked local cache; canonical SPDX terms are archived and package source metadata remains in this mapping'
    })
}

$status = 'source-verified-pending-legal-review'
if ($MarkApproved) {
    $status = 'approved'
}
$manifest = [PSCustomObject][ordered]@{
    schemaVersion = 4
    status = $status
    generatedBy = 'scripts/release/sbom/promote-license-candidates.ps1'
    candidate = [PSCustomObject][ordered]@{
        path = Get-RelativePath -Path $candidatePath -BasePath $root
        sha256 = Get-Sha256 -Path $candidatePath
        status = [string]$candidate.status
    }
    spdx = [PSCustomObject][ordered]@{
        repository = 'https://github.com/spdx/license-list-data'
        commit = $SpdxCommit
        textUrlTemplate = "https://raw.githubusercontent.com/spdx/license-list-data/$SpdxCommit/text/<id>.txt"
    }
    inputs = $inputEvidence
    summary = [PSCustomObject][ordered]@{
        candidateMappingCount = @($candidate.mappings).Count
        sourceResolvedMissingCount = $missingReview.Count
        textFileCount = @(Get-ChildItem -LiteralPath $textDirectory -File).Count
    }
    mappings = @($mappings | Sort-Object ecosystem, name, version, sourceFile)
    missingReview = @($missingReview | Sort-Object ecosystem, name, version)
}
Write-JsonFile -Value $manifest -Path (Join-Path $archiveRoot 'manifest.json')

$readme = @"
<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja 第三方许可证归档

本目录由 `promote-license-candidates.ps1` 从锁定的候选清单生成。候选缓存中的原始
license/notice 字节按 SHA-256 原样复制；缺少包内正文的条目只使用固定 SPDX 数据仓库
提交 `$SpdxCommit` 的 canonical license text，并在 `manifest.json` 保留 source URL、哈希、
版本和“未发现包内 notice”的事实。

当前状态：`$status`。

`source-verified-pending-legal-review` 不是发布批准；发布 owner 必须复核 `missingReview`
中的版权/NOTICE、实际 Native/Tauri 再分发边界和 GPL 兼容性，确认后才允许显式传入
`-MarkApproved` 生成 `status=approved`。脚本拒绝覆盖已有非空归档。
"@
[IO.File]::WriteAllText((Join-Path $archiveRoot 'README.md'), $readme + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))

Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
Write-Output ('status={0} candidateMappings={1} resolvedMissing={2} textFiles={3} output={4}' -f `
    $status, $manifest.summary.candidateMappingCount, $manifest.summary.sourceResolvedMissingCount,
    $manifest.summary.textFileCount, (Get-RelativePath -Path $archiveRoot -BasePath $root))
exit 0
