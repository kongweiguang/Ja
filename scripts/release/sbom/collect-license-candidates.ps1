# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
    [string]$OutputDirectory = 'release\sbom\license-candidate',
    [string]$MavenBomPath = 'ja-agent\target\ja-app-server-bom.json',
    [string]$CargoToolchain = '1.98.0',
    [switch]$ReplaceExisting
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\lib\diagnostics.ps1')

function Resolve-RepositoryPath {
    <#
    解析仓库相对路径，并确保生成的证据留在当前检出目录中。
    这样可避免本机软件包缓存或用户选择的输出路径意外被复制到候选归档。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BasePath,
        [switch]$RequireExisting
    )

    $candidate = if ([IO.Path]::IsPathRooted($Path)) {
        [IO.Path]::GetFullPath($Path)
    } else {
        [IO.Path]::GetFullPath((Join-Path $BasePath $Path))
    }
    if ($RequireExisting -and -not (Test-Path -LiteralPath $candidate)) {
        throw "path does not exist: $Path"
    }
    return $candidate
}

function Get-RelativeRepositoryPath {
    <#
    将仓库管理的路径转换为稳定的斜杠分隔名称；清单使用生态系统坐标记录软件包，因此绝对缓存路径不会写入清单。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BasePath
    )

    return ([IO.Path]::GetRelativePath($BasePath, $Path)).Replace('\', '/')
}

function Get-EvidenceHash {
    <#
    对原始 manifest 和 lockfile 逐字节计算哈希，对 CycloneDX 则基于规范化 JSON 计算哈希。
    依赖关系图未变化时，Maven 插件仍会生成新的时间戳，因此不能让运行身份导致已审查的依赖集合过期；
    组件和依赖的顺序也会被规范化。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][ValidateSet('raw-bytes', 'cyclonedx-dependency-graph-v1')][string]$HashMode
    )

    if ($HashMode -eq 'raw-bytes') {
        return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
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

function Clear-GeneratedCandidateOutput {
    <#
    只替换结构与生成的候选目录完全一致的目录。这样可以在最终重建 lockfile 时避免宽泛的递归删除，
    并拒绝处理可能属于用户的未知文件、嵌套目录或未按哈希寻址的文本。
    #>
    param([Parameter(Mandatory = $true)][string]$OutputRoot)

    $allowedTopLevel = @('README.md', 'manifest.json', 'text')
    foreach ($entry in @(Get-ChildItem -LiteralPath $OutputRoot -Force)) {
        if ($entry.Name -notin $allowedTopLevel) {
            throw "candidate output contains an unknown entry and cannot be replaced: $($entry.Name)"
        }
        if ($entry.Name -eq 'text') {
            if (-not $entry.PSIsContainer) {
                throw 'candidate text entry is not a directory'
            }
            foreach ($textEntry in @(Get-ChildItem -LiteralPath $entry.FullName -Force)) {
                if ($textEntry.PSIsContainer -or $textEntry.Name -notmatch '^[0-9a-f]{64}\.txt$') {
                    throw "candidate text directory contains an unknown entry: $($textEntry.Name)"
                }
                Remove-Item -LiteralPath $textEntry.FullName -Force
            }
            Remove-Item -LiteralPath $entry.FullName -Force
            continue
        }
        if ($entry.PSIsContainer) {
            throw "candidate generated file is unexpectedly a directory: $($entry.Name)"
        }
        Remove-Item -LiteralPath $entry.FullName -Force
    }
    Remove-Item -LiteralPath $OutputRoot -Force
}

function Get-FileEvidence {
    <#
    对一项固定候选输入计算哈希，以便后续晋级时证明收集后 lockfile/BOM 依赖图没有变化；
    路径保持为仓库相对形式，不含工作站信息。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $resolved = Resolve-RepositoryPath -Path $Path -BasePath $RepositoryRoot -RequireExisting
    $hashMode = if ($Role -eq 'maven-bom') { 'cyclonedx-dependency-graph-v1' } else { 'raw-bytes' }
    return [PSCustomObject][ordered]@{
        role = $Role
        path = Get-RelativeRepositoryPath -Path $resolved -BasePath $RepositoryRoot
        hashMode = $hashMode
        sha256 = Get-EvidenceHash -Path $resolved -HashMode $hashMode
    }
}

function Invoke-JsonCommand {
    <#
    离线运行依赖命令并提取唯一的 JSON 文档。只容忍外围对象之前的工具警告；输出格式错误或为空仍会导致硬失败。
    stderr 独立保留并在失败时复用共享脱敏规则，只写错误通道以保持 stdout JSON；缺失环境用 NullString 删除，不能恢复成新的空值。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [hashtable]$Environment = @{}
    )

    $oldEnvironment = @{}
    foreach ($name in $Environment.Keys) {
        $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name, [string]$Environment[$name], 'Process')
    }
    try {
        $standardError = [Collections.Generic.List[string]]::new()
        $lines = @(& $FilePath @Arguments 2>&1 | ForEach-Object {
            if ($_ -is [Management.Automation.ErrorRecord]) { $standardError.Add([string]$_) } else { [string]$_ }
        })
        if ($LASTEXITCODE -ne 0) {
            $exitCode = $LASTEXITCODE
            if ($standardError.Count -gt 0) {
                [Console]::Error.WriteLine((ConvertTo-SafeDiagnosticText -Value ($standardError -join [Environment]::NewLine) -RepositoryRoot $root))
            }
            throw "$FilePath failed with exit code $exitCode"
        }
    } finally {
        foreach ($name in $Environment.Keys) {
            if ($null -eq $oldEnvironment[$name]) {
                [Environment]::SetEnvironmentVariable($name, [System.Management.Automation.Language.NullString]::Value, 'Process')
            } else {
                [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
            }
        }
    }

    $text = ($lines -join [Environment]::NewLine).Trim()
    $start = $text.IndexOf('{')
    $end = $text.LastIndexOf('}')
    if ($start -lt 0 -or $end -le $start) {
        throw "$FilePath did not emit a JSON object"
    }
    return ($text.Substring($start, $end - $start + 1) | ConvertFrom-Json)
}

function Get-OptionalPropertyString {
    <#
    读取可选元数据，不让 StrictMode 因缺少 homepage/repository 字段而使归档运行失败；
    清单中会将缺失字段明确记录为空字符串。
    #>
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($Object.PSObject.Properties[$Name]) {
        return [string]$Object.$Name
    }
    return ''
}

function Get-LicenseFiles {
    <#
    查找单项依赖提供的许可证或声明文件，但不进入嵌套的软件包安装目录。复制时保持字节不变；此处不推断 SPDX 表达式。
    #>
    param([Parameter(Mandatory = $true)][string]$PackageRoot)

    $pattern = '^(LICENSE|LICENCE|COPYING|NOTICE)([._-].*)?$'
    $files = @(Get-ChildItem -LiteralPath $PackageRoot -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $pattern })
    foreach ($directoryName in @('LICENSES', 'licenses')) {
        $directory = Join-Path $PackageRoot $directoryName
        if (Test-Path -LiteralPath $directory -PathType Container) {
            $files += @(Get-ChildItem -LiteralPath $directory -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match $pattern })
        }
    }
    return @($files | Sort-Object FullName -Unique)
}

function New-LicenseCollector {
    <#
    创建收集器状态。按哈希寻址的文本会对相同的 MIT/Apache 等文件去重，清单则保留法务审查所需的每项软件包到文本映射。
    #>
    param([Parameter(Mandatory = $true)][string]$OutputRoot)

    $textRoot = Join-Path $OutputRoot 'text'
    [IO.Directory]::CreateDirectory($textRoot) | Out-Null
    return [PSCustomObject]@{
        OutputRoot = $OutputRoot
        TextRoot = $textRoot
        Blobs = @{}
        Records = [System.Collections.Generic.List[object]]::new()
        Missing = [System.Collections.Generic.List[object]]::new()
    }
}

function Add-LicenseBytes {
    <#
    按 SHA-256 保存一段精确字节序列，并追加软件包映射。已有哈希会复用，因此候选内容保持精简，且不修改上游许可证文本。
    #>
    param(
        [Parameter(Mandatory = $true)]$Collector,
        [Parameter(Mandatory = $true)][byte[]]$Bytes,
        [Parameter(Mandatory = $true)][string]$Ecosystem,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$DeclaredLicense,
        [Parameter(Mandatory = $true)][string]$SourceFile,
        [string]$Repository = '',
        [string]$Homepage = ''
    )

    $sha = ([Security.Cryptography.SHA256]::Create().ComputeHash($Bytes) |
        ForEach-Object { $_.ToString('x2') }) -join ''
    if (-not $Collector.Blobs.ContainsKey($sha)) {
        $target = Join-Path $Collector.TextRoot "$sha.txt"
        [IO.File]::WriteAllBytes($target, $Bytes)
        $Collector.Blobs[$sha] = $target
    }
    $Collector.Records.Add([PSCustomObject][ordered]@{
        ecosystem = $Ecosystem
        name = $Name
        version = $Version
        declaredLicense = $DeclaredLicense
        repository = $Repository
        homepage = $Homepage
        sourceFile = $SourceFile
        archiveFile = "text/$sha.txt"
        sha256 = $sha
        sizeBytes = [int64]$Bytes.Length
    })
}

function Add-LicenseFile {
    <#
    以字节形式读取本地许可证或声明文件，并交由哈希寻址收集器处理。
    直接读取字节而不规范化文本，可保留上游换行和声明内容。
    #>
    param(
        [Parameter(Mandatory = $true)]$Collector,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Ecosystem,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$DeclaredLicense,
        [string]$Repository = '',
        [string]$Homepage = ''
    )

    Add-LicenseBytes -Collector $Collector -Bytes ([IO.File]::ReadAllBytes($Path) `
        ) -Ecosystem $Ecosystem -Name $Name -Version $Version `
        -DeclaredLicense $DeclaredLicense -SourceFile ([IO.Path]::GetFileName($Path)) `
        -Repository $Repository -Homepage $Homepage
}

function Add-MissingRecord {
    <#
    记录本地缓存中没有许可证或声明字节的软件包。保留缺失记录可确保候选信息真实，
    并为受控的源码或法务审查提供明确的后续清单。
    #>
    param(
        [Parameter(Mandatory = $true)]$Collector,
        [Parameter(Mandatory = $true)][string]$Ecosystem,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$DeclaredLicense,
        [string]$Repository = '',
        [string]$Homepage = '',
        [string]$Reason = 'no local license or notice file found'
    )

    $Collector.Missing.Add([PSCustomObject][ordered]@{
        ecosystem = $Ecosystem
        name = $Name
        version = $Version
        declaredLicense = $DeclaredLicense
        repository = $Repository
        homepage = $Homepage
        reason = $Reason
    })
}

function Get-DeclaredLicenses {
    <#
    将 CycloneDX 许可证数组转换为候选清单中的一个稳定表达式；组件详细记录仍以原始 BOM 为准。
    #>
    param([Parameter(Mandatory = $true)]$Licenses)

    return (@($Licenses | ForEach-Object {
        if (-not $_.PSObject.Properties['license']) { return }
        if ($_.license.PSObject.Properties['id'] -and $_.license.id) { [string]$_.license.id }
        elseif ($_.license.PSObject.Properties['name'] -and $_.license.name) { [string]$_.license.name }
    } | Sort-Object -Unique) -join ' OR ')
}

function Add-MavenArchiveEntries {
    <#
    直接从 Maven 缓存 JAR 中读取许可证或声明条目。若 JAR 中缺少条目则报告缺失，不猜测 SPDX 文本来替代，
    因为仅靠 POM 元数据不足以证明组件的确切声明义务。
    #>
    param(
        [Parameter(Mandatory = $true)]$Collector,
        [Parameter(Mandatory = $true)]$Component,
        [Parameter(Mandatory = $true)][string]$MavenRepository
    )

    $purl = [string]$Component.purl
    if ($purl -notmatch '^pkg:maven/(?<group>[^/]+)/(?<artifact>[^@]+)@(?<version>[^?]+)') {
        Add-MissingRecord -Collector $Collector -Ecosystem 'maven' -Name ([string]$Component.name) `
            -Version ([string]$Component.version) -DeclaredLicense (Get-DeclaredLicenses $Component.licenses) `
            -Reason 'CycloneDX component has no parseable Maven purl'
        return
    }
    $group = [Uri]::UnescapeDataString($Matches.group)
    $artifact = [Uri]::UnescapeDataString($Matches.artifact)
    $version = [Uri]::UnescapeDataString($Matches.version)
    $jarPath = Join-Path $MavenRepository (($group -replace '\.', '\') + "\$artifact\$version\$artifact-$version.jar")
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        Add-MissingRecord -Collector $Collector -Ecosystem 'maven' -Name $artifact -Version $version `
            -DeclaredLicense (Get-DeclaredLicenses $Component.licenses) -Reason 'cached Maven JAR not found'
        return
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entries = @($zip.Entries | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_.Name) -and
            $_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE)([._-].*)?$'
        })
        if ($entries.Count -eq 0) {
            Add-MissingRecord -Collector $Collector -Ecosystem 'maven' -Name $artifact -Version $version `
                -DeclaredLicense (Get-DeclaredLicenses $Component.licenses) -Reason 'Maven JAR has no license/notice entry'
            return
        }
        foreach ($entry in $entries) {
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
            Add-LicenseBytes -Collector $Collector -Bytes $memory.ToArray() -Ecosystem 'maven' `
                -Name $artifact -Version $version -DeclaredLicense (Get-DeclaredLicenses $Component.licenses) `
                -SourceFile $entry.FullName -Repository ([string]$Component.purl)
            $memory.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
}

function Write-CandidateManifest {
    <#
    写入确定性的候选清单和审查 README。状态明确标为待处理是有意设计：这些输出是供发布负责人审查的证据，并非自动法务批准。
    #>
    param(
        [Parameter(Mandatory = $true)]$Collector,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$MavenBomPath,
        [Parameter(Mandatory = $true)][string]$CargoToolchain
    )

    $manifest = [PSCustomObject][ordered]@{
        schemaVersion = 4
        status = 'candidate-review-pending'
        networkPolicy = 'offline'
        generatedBy = 'scripts/release/sbom/collect-license-candidates.ps1'
        inputs = @(
            Get-FileEvidence -Role 'node-manifest' -Path 'package.json' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'node-lock' -Path 'pnpm-lock.yaml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'cargo-workspace-manifest' -Path 'Cargo.toml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'cargo-package-manifest' -Path 'apps/desktop/src-tauri/Cargo.toml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'cargo-lock' -Path 'Cargo.lock' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-parent-manifest' -Path 'ja-agent/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-core-manifest' -Path 'ja-agent/ja-agent-core/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-store-local-manifest' -Path 'ja-agent/extensions/ja-agent-store-local/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-store-pg-manifest' -Path 'ja-agent/extensions/ja-agent-store-pg/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-skills-local-manifest' -Path 'ja-agent/extensions/ja-agent-skills-local/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-skills-pg-manifest' -Path 'ja-agent/extensions/ja-agent-skills-pg/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-example-manifest' -Path 'ja-agent/example/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-example-spring-boot-manifest' -Path 'ja-agent/example/ja-agent-example-springboot/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-app-server-manifest' -Path 'ja-agent/ja-app-server/pom.xml' -RepositoryRoot $RepositoryRoot
            Get-FileEvidence -Role 'maven-bom' -Path $MavenBomPath -RepositoryRoot $RepositoryRoot
        )
        cargoToolchain = $CargoToolchain
        summary = [PSCustomObject][ordered]@{
            uniqueTextFiles = [int]$Collector.Blobs.Count
            mappingCount = [int]$Collector.Records.Count
            missingTextCount = [int]$Collector.Missing.Count
        }
        mappings = @($Collector.Records | Sort-Object ecosystem, name, version, sourceFile, sha256)
        missing = @($Collector.Missing | Sort-Object ecosystem, name, version)
    }
    $jsonPath = Join-Path $Collector.OutputRoot 'manifest.json'
    $json = $manifest | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText($jsonPath, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    $readme = @'
<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja 第三方许可证候选归档

本目录由 `collect-license-candidates.ps1` 从当前 lockfile、CycloneDX Maven BOM 和本机
离线缓存复制原始 license/notice 字节生成。`text/<sha256>.txt` 是去重后的原文，
`manifest.json` 保留每个组件到原文的映射和缺失项。

这不是法律批准，也不是发布归档。发布前必须在 clean checkout 固定输入，补齐
`missing`，核对版权、NOTICE、GPL 兼容性、Native/Tauri 实际再分发边界，并由发布 owner
把已审计文件迁入 `LICENSES/approved/` 后再关闭供应链门。
'@
    [IO.File]::WriteAllText((Join-Path $Collector.OutputRoot 'README.md'), $readme + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    return $manifest
}

$root = [IO.Path]::GetFullPath($RepositoryRoot)
$outputRoot = Resolve-RepositoryPath -Path $OutputDirectory -BasePath $root
if (Test-Path -LiteralPath $outputRoot) {
    if (-not $ReplaceExisting) {
        throw "candidate output already exists; choose a new OutputDirectory or pass -ReplaceExisting: $OutputDirectory"
    }
    Clear-GeneratedCandidateOutput -OutputRoot $outputRoot
}
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$collector = New-LicenseCollector -OutputRoot $outputRoot

$npm = Invoke-JsonCommand -FilePath 'pnpm' -Arguments @('licenses', 'list', '--json') `
    -Environment @{ npm_config_offline = 'true' }
foreach ($group in $npm.PSObject.Properties) {
    foreach ($entry in @($group.Value)) {
        $packageRootFound = $false
        foreach ($packagePath in @($entry.paths)) {
            $files = @(Get-LicenseFiles -PackageRoot ([string]$packagePath))
            foreach ($file in $files) {
                $packageRootFound = $true
                Add-LicenseFile -Collector $collector -Path $file.FullName -Ecosystem 'npm' `
                    -Name ([string]$entry.name) -Version ((@($entry.versions) | ForEach-Object { [string]$_ }) -join ',') `
                    -DeclaredLicense ([string]$entry.license) -Homepage (Get-OptionalPropertyString -Object $entry -Name 'homepage')
            }
        }
        if (-not $packageRootFound) {
            Add-MissingRecord -Collector $collector -Ecosystem 'npm' -Name ([string]$entry.name) `
                -Version ((@($entry.versions) | ForEach-Object { [string]$_ }) -join ',') `
                -DeclaredLicense ([string]$entry.license) -Homepage (Get-OptionalPropertyString -Object $entry -Name 'homepage')
        }
    }
}

$cargoManifest = Resolve-RepositoryPath -Path 'apps/desktop/src-tauri/Cargo.toml' -BasePath $root -RequireExisting
$cargo = Invoke-JsonCommand -FilePath 'cargo' -Arguments @("+$CargoToolchain", 'metadata', '--manifest-path', $cargoManifest, '--locked', '--offline', '--format-version', '1')
foreach ($package in @($cargo.packages)) {
    $manifestPath = [string]$package.manifest_path
    $packageRoot = Split-Path -Parent $manifestPath
    $files = @(Get-LicenseFiles -PackageRoot $packageRoot)
    if ($files.Count -eq 0) {
        Add-MissingRecord -Collector $collector -Ecosystem 'cargo' -Name ([string]$package.name) `
            -Version ([string]$package.version) -DeclaredLicense ([string]$package.license) `
            -Repository (Get-OptionalPropertyString -Object $package -Name 'repository')
        continue
    }
    foreach ($file in $files) {
        Add-LicenseFile -Collector $collector -Path $file.FullName -Ecosystem 'cargo' `
            -Name ([string]$package.name) -Version ([string]$package.version) `
            -DeclaredLicense ([string]$package.license) -Repository (Get-OptionalPropertyString -Object $package -Name 'repository')
    }
}

$bom = Resolve-RepositoryPath -Path $MavenBomPath -BasePath $root -RequireExisting
$maven = Get-Content -Raw -LiteralPath $bom | ConvertFrom-Json
$mavenRepository = Join-Path $env:USERPROFILE '.m2\repository'
foreach ($component in @($maven.components)) {
    Add-MavenArchiveEntries -Collector $collector -Component $component -MavenRepository $mavenRepository
}

$manifest = Write-CandidateManifest -Collector $collector -RepositoryRoot $root `
    -MavenBomPath $MavenBomPath -CargoToolchain $CargoToolchain
Write-Output ('status={0} uniqueTextFiles={1} mappings={2} missing={3} output={4}' -f `
    $manifest.status, $manifest.summary.uniqueTextFiles, $manifest.summary.mappingCount, `
    $manifest.summary.missingTextCount, (Get-RelativeRepositoryPath -Path $outputRoot -BasePath $root))
exit 0
