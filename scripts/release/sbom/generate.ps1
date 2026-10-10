# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path,
    [string]$OutputDirectory = 'release\sbom',
    [string]$MavenBomPath = 'ja-agent\target\ja-app-server-bom.json',
    [string]$CargoToolchain = '1.98.0',
    [string]$CorrespondingSourcePath = '',
    [string[]]$ArtifactPath = @(),
    [switch]$FailOnBlocker
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\lib\diagnostics.ps1')

function Resolve-RepositoryPath {
    <#
    .SYNOPSIS
    解析调用方提供的路径，并禁止报告路径越出仓库。

    .DESCRIPTION
    报告应能在干净检出中复现。将生成路径限制在仓库内，可防止意外收录开发者主目录或凭据；
    同时仍允许仅按名称将显式来源或产物路径标记为外部输入。
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
    .SYNOPSIS
    将现有仓库路径转换为稳定的斜杠分隔报告路径。

    .DESCRIPTION
    Windows 绝对路径与具体机器绑定，可能泄露用户名。因此，仓库内的证据都记录为相对检出目录的路径，
    使相同源码树在不同 runner 上生成可比较的来源信息。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BasePath
    )

    $base = [IO.Path]::GetFullPath($BasePath).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) {
        return [IO.Path]::GetFileName($full)
    }
    return [IO.Path]::GetRelativePath($BasePath, $full).Replace('\', '/')
}

function Invoke-ExternalCommand {
    <#
    .SYNOPSIS
    运行一个成熟的依赖工具并捕获退出码，不持久化原始日志。

    .DESCRIPTION
    许可证和 SBOM 工具失败时可能输出环境路径或意外回显的敏感内容。
    只解析完整 stdout，stderr 在独立的内存流中保留，避免诊断插入 JSON 后破坏许可证证据。
    Application 解析在 Windows 固定使用 pnpm.cmd，排除 PowerShell shim 的脚本输出语义；
    独立 runspace 使用结构化参数并保留真实原生退出码，不拼接 Shell 命令或持久化原始日志。
    pnpm/Cargo 的机器 JSON 固定为 UTF-8；Windows 无控制台子进程可能默认按 ANSI 解码，
    因此只在此调用内固定解码并恢复调用方编码，避免多字节内容损坏 JSON 的引号边界。
    非零退出的 stderr 必须进入既有脱敏诊断通道；恢复缺失环境时显式传 NullString，保留原有空值语义。
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
    $pipeline = $null
    $runspace = $null
    $priorConsoleEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
        $command = Get-Command -Name $FilePath -CommandType Application -ErrorAction Stop | Select-Object -First 1
        $runspace = [Management.Automation.Runspaces.RunspaceFactory]::CreateRunspace()
        $runspace.Open()
        $null = $runspace.SessionStateProxy.Path.SetLocation((Get-Location).ProviderPath)
        $pipeline = [Management.Automation.PowerShell]::Create()
        $pipeline.Runspace = $runspace
        $null = $pipeline.AddCommand($command.Source)
        foreach ($argument in $Arguments) { $null = $pipeline.AddArgument($argument) }
        $output = @($pipeline.Invoke() | ForEach-Object { [string]$_.PSObject.BaseObject })
        $standardError = @($pipeline.Streams.Error | ForEach-Object { [string]$_ })
        $null = $pipeline.Commands.Clear()
        $pipeline.Streams.Error.Clear()
        $null = $pipeline.AddScript('$LASTEXITCODE')
        $exitCodes = @($pipeline.Invoke())
        if ($exitCodes.Count -ne 1 -or $null -eq $exitCodes[0].PSObject.BaseObject) {
            throw 'native command exit code is unavailable'
        }
        $exitCode = [int]$exitCodes[0].PSObject.BaseObject
        if ($exitCode -ne 0 -and $standardError.Count -gt 0) {
            [Console]::Error.WriteLine((ConvertTo-SafeDiagnosticText -Value ($standardError -join [Environment]::NewLine) -RepositoryRoot $root))
        }
        return [PSCustomObject]@{
            ExitCode = $exitCode
            Output = $output
            StandardError = $standardError
        }
    } finally {
        if ($null -ne $pipeline) { $pipeline.Dispose() }
        if ($null -ne $runspace) { $runspace.Dispose() }
        [Console]::OutputEncoding = $priorConsoleEncoding
        foreach ($name in $Environment.Keys) {
            if ($null -eq $oldEnvironment[$name]) {
                [Environment]::SetEnvironmentVariable($name, [System.Management.Automation.Language.NullString]::Value, 'Process')
            } else {
                [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
            }
        }
    }
}

function Get-ToolVersion {
    <#
    .SYNOPSIS
    记录精简的工具身份信息，避免保留完整诊断输出。

    .DESCRIPTION
    工具的准确身份属于来源信息，但完整版本横幅可能包含本机安装路径。保留第一条非空行即可关联审计，
    也能避免报告包含机器专属信息。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $result = Invoke-ExternalCommand -FilePath $FilePath -Arguments $Arguments
    if ($result.ExitCode -ne 0) {
        return [PSCustomObject]@{ tool = $FilePath; status = 'unavailable'; exitCode = $result.ExitCode }
    }
    $line = @($result.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    return [PSCustomObject]@{
        tool = $FilePath
        status = if ($line.Count -gt 0) { 'available' } else { 'unknown' }
        version = if ($line.Count -gt 0) { $line[0].Trim() } else { '' }
    }
}

function Convert-JsonCommandOutput {
    <#
    .SYNOPSIS
    严格解析 CLI 完整 stdout 的单个 JSON 对象。

    .DESCRIPTION
    stdout 是成熟工具的机器契约；前缀、后缀或多个文档必须失败，不能通过截取花括号掩盖输出污染。
    诊断仅来自独立 stderr；此处沿用成熟 JSON 解析器，不增加宽松提取或自定义解析规则。
    #>
    param(
        [Parameter(Mandatory = $true)][string[]]$Lines
    )

    $text = ($Lines -join [Environment]::NewLine).Trim()
    if (-not $text.StartsWith('{') -or -not $text.EndsWith('}')) {
        throw 'tool did not emit a JSON object'
    }
    return ($text | ConvertFrom-Json)
}

function Write-StableJson {
    <#
    .SYNOPSIS
    写入 UTF-8 JSON，明确添加换行且不使用依赖时间戳的格式。

    .DESCRIPTION
    锁文件和工具输出未变化时，发布证据的哈希必须保持一致。因此 schema 不包含时间戳，并集中处理序列化，
    使所有报告文件使用相同的深度、编码和换行规则。
    #>
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $json = $Value | ConvertTo-Json -Depth 40
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Get-FileEvidence {
    <#
    .SYNOPSIS
    计算现有文件的 SHA-256 和大小。

    .DESCRIPTION
    SHA-256 是产物清单合同要求的跨平台校验和。直接计算实际文件，而不是依赖名称或锁文件声明，
    可避免把过期报告误当作其他输出的来源证明。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $file = Get-Item -LiteralPath $Path -Force
    if (-not $file.PSIsContainer -and $file.Length -ge 0) {
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        return [PSCustomObject]@{
            path = Get-RelativeRepositoryPath -Path $file.FullName -BasePath $RepositoryRoot
            sizeBytes = [int64]$file.Length
            sha256 = $hash
        }
    }
    throw "expected a file: $Path"
}

function Get-InputEvidence {
    <#
    .SYNOPSIS
    对决定报告内容的固定依赖输入计算哈希。

    .DESCRIPTION
    输入集合有意只列出软件包清单、锁文件和法务入口；排除生成目录及用户配置，
    以保证来源信息可复现，且不会意外收录凭据或工作区中无关的未提交文件。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $relativePaths = @(
        'package.json',
        'pnpm-lock.yaml',
        'Cargo.toml',
        'apps/desktop/src-tauri/Cargo.toml',
        'Cargo.lock',
        'ja-agent/pom.xml',
        'ja-agent/ja-agent-core/pom.xml',
        'ja-agent/extensions/ja-agent-store-local/pom.xml',
        'ja-agent/extensions/ja-agent-store-pg/pom.xml',
        'ja-agent/extensions/ja-agent-skills-local/pom.xml',
        'ja-agent/extensions/ja-agent-skills-pg/pom.xml',
        'ja-agent/example/pom.xml',
        'ja-agent/example/ja-agent-example-springboot/pom.xml',
        'ja-agent/ja-app-server/pom.xml',
        'LICENSE'
    )
    $facts = @()
    foreach ($relativePath in $relativePaths) {
        $path = Resolve-RepositoryPath -Path $relativePath -BasePath $RepositoryRoot -RequireExisting
        $facts += Get-FileEvidence -Path $path -RepositoryRoot $RepositoryRoot
    }
# 可选的声明和审查入口若存在就作为证据；若缺失，则交由专门的归档审计处理，不提前中止报告生成。
    foreach ($relativePath in @('THIRD_PARTY_NOTICES.md', 'legal/third-party/manifest.json', 'LICENSES/README.md')) {
        $entry = Resolve-RepositoryPath -Path $relativePath -BasePath $RepositoryRoot
        if (Test-Path -LiteralPath $entry -PathType Leaf) {
            $facts += Get-FileEvidence -Path $entry -RepositoryRoot $RepositoryRoot
        }
    }
    return @($facts | Sort-Object path)
}

function Get-GitProvenance {
    <#
    .SYNOPSIS
    记录源码提交和工作区是否有改动，但不记录变更文件名。

    .DESCRIPTION
    发布报告需要区分可复现的提交和开发者工作区。dirty 标记足以作出判断；列出路径会增加无关用户信息，
    还可能在准备分发的报告中暴露密钥。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $commitResult = Invoke-ExternalCommand -FilePath 'git' -Arguments @('-C', $RepositoryRoot, 'rev-parse', 'HEAD')
    if ($commitResult.ExitCode -ne 0 -or @($commitResult.Output).Count -eq 0) {
        throw 'unable to resolve git source commit'
    }
    $statusResult = Invoke-ExternalCommand -FilePath 'git' -Arguments @('-C', $RepositoryRoot, 'status', '--porcelain')
    if ($statusResult.ExitCode -ne 0) {
        throw 'unable to resolve git worktree status'
    }
    return [PSCustomObject]@{
        commit = $commitResult.Output[0].Trim()
        dirty = (@($statusResult.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -gt 0)
    }
}

function Get-NodeLicenseInventory {
    <#
    .SYNOPSIS
    将 pnpm 成熟的许可证清单整理为不含本机路径且结果确定的证据文件。

    .DESCRIPTION
    `pnpm licenses list` 仍是 npm 软件包许可证元数据的权威来源；Ja 只删除机器路径并排序记录。
    通过 npm_config_offline 强制使用离线模式，避免发布报告悄然解析出另一套软件包树。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $result = Invoke-ExternalCommand -FilePath 'pnpm' -Arguments @('licenses', 'list', '--json') -Environment @{ npm_config_offline = 'true' }
    if ($result.ExitCode -ne 0) {
        throw "pnpm license inventory failed with exit code $($result.ExitCode)"
    }
    $licenseGroups = Convert-JsonCommandOutput -Lines $result.Output
    $entries = @()
    foreach ($group in $licenseGroups.PSObject.Properties) {
        foreach ($entry in @($group.Value)) {
            $author = if ($entry.PSObject.Properties['author']) { [string]$entry.author } else { '' }
            $homepage = if ($entry.PSObject.Properties['homepage']) { [string]$entry.homepage } else { '' }
            $entries += [PSCustomObject][ordered]@{
                name = [string]$entry.name
                versions = @($entry.versions | ForEach-Object { [string]$_ } | Sort-Object)
                license = [string]$entry.license
                author = $author
                homepage = $homepage
            }
        }
    }
    $sortedEntries = @($entries | Sort-Object name, license, @{ Expression = { $_.versions -join ',' } })
    $missing = @($sortedEntries | Where-Object { [string]::IsNullOrWhiteSpace($_.license) })
    return [PSCustomObject][ordered]@{
        source = 'pnpm licenses list'
        offline = $true
        packageVersionCount = [int](@($sortedEntries | ForEach-Object { $_.versions }).Count)
        entryCount = [int]$sortedEntries.Count
        missingLicenseCount = [int]$missing.Count
        entries = $sortedEntries
    }
}

function Get-CargoLicenseInventory {
    <#
    .SYNOPSIS
    使用 Cargo 锁定的离线元数据作为 Rust 依赖和许可证来源。

    .DESCRIPTION
    Cargo 已从锁文件解析软件包来源、校验和及声明的 SPDX 表达式；此处只保留审计字段并省略 manifest/target 绝对路径。
    不执行自定义许可证分类，因此非标准表达式仍会显示给审查者。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$Toolchain
    )

    $manifest = Resolve-RepositoryPath -Path 'apps/desktop/src-tauri/Cargo.toml' -BasePath $RepositoryRoot -RequireExisting
    $result = Invoke-ExternalCommand -FilePath 'cargo' -Arguments @("+$Toolchain", 'metadata', '--manifest-path', $manifest, '--locked', '--offline', '--format-version', '1')
    if ($result.ExitCode -ne 0) {
        throw "cargo metadata failed with exit code $($result.ExitCode)"
    }
    $metadata = Convert-JsonCommandOutput -Lines $result.Output
    $packages = @($metadata.packages | ForEach-Object {
        [PSCustomObject][ordered]@{
            name = [string]$_.name
            version = [string]$_.version
            license = [string]$_.license
            source = [string]$_.source
            repository = [string]$_.repository
        }
    } | Sort-Object name, version, source)
    $missing = @($packages | Where-Object { [string]::IsNullOrWhiteSpace($_.license) })
    $lockPath = Resolve-RepositoryPath -Path 'Cargo.lock' -BasePath $RepositoryRoot -RequireExisting
    $lockText = Get-Content -Raw -LiteralPath $lockPath
    return [PSCustomObject][ordered]@{
        source = 'cargo metadata'
        toolchain = $Toolchain
        offline = $true
        packageCount = [int]$packages.Count
        missingLicenseCount = [int]$missing.Count
        registryChecksumCount = [int]([regex]::Matches($lockText, '(?m)^checksum =').Count)
        gitSourceCount = [int]([regex]::Matches($lockText, '(?m)^source = "git\\+').Count)
        pathSourceCount = [int]([regex]::Matches($lockText, '(?m)^source = "path\\+').Count)
        packages = $packages
    }
}

function Normalize-MavenBom {
    <#
    .SYNOPSIS
    移除相同输入的多次运行中有意保持不确定的 CycloneDX 字段。

    .DESCRIPTION
    CycloneDX 成熟的 Maven 插件会输出 UUID 和生成时间戳。这些值标识的是一次运行，而非依赖关系图，
    因此从归档证据中移除，同时原样保留组件许可证、哈希、purl 和外部引用。规范允许省略的集合保持省略，
    不在规范化时补造元数据；缺失事实留给审计生成阻塞项。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path
    )

    $bom = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    if ([string]$bom.bomFormat -ne 'CycloneDX') {
        throw 'Maven BOM is not a CycloneDX document'
    }
    if ($bom.PSObject.Properties['serialNumber']) {
        $bom.PSObject.Properties.Remove('serialNumber')
    }
    if ($bom.PSObject.Properties['metadata'] -and $bom.metadata -and $bom.metadata.PSObject.Properties['timestamp']) {
        $bom.metadata.PSObject.Properties.Remove('timestamp')
    }
    if ($bom.PSObject.Properties['components'] -and $bom.components) {
        $bom.components = @($bom.components | Sort-Object { [string]$_.'bom-ref' })
    }
    if ($bom.PSObject.Properties['dependencies'] -and $bom.dependencies) {
        foreach ($dependency in @($bom.dependencies)) {
            if ($dependency.PSObject.Properties['dependsOn'] -and $dependency.dependsOn) {
                $dependency.dependsOn = @($dependency.dependsOn | Sort-Object)
            }
        }
        $bom.dependencies = @($bom.dependencies | Sort-Object { [string]$_.ref })
    }
    return $bom
}

function Get-EvidenceHash {
    <#
    使用明确算法重新计算已批准输入的身份。仓库原始输入保留字节级身份；对于 CycloneDX，
    只忽略不会改变发布负责人所审依赖关系图的运行元数据和顺序。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][ValidateSet('raw-bytes', 'cyclonedx-dependency-graph-v1')][string]$HashMode
    )

    if ($HashMode -eq 'raw-bytes') {
        return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $bom = Normalize-MavenBom -Path $Path
    $canonicalBytes = [Text.UTF8Encoding]::new($false).GetBytes(($bom | ConvertTo-Json -Depth 100 -Compress))
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ($algorithm.ComputeHash($canonicalBytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $algorithm.Dispose()
    }
}

function Get-MavenBomAudit {
    <#
    .SYNOPSIS
    在不重新解析依赖的情况下校验离线 CycloneDX Maven BOM。

    .DESCRIPTION
    CycloneDX Maven 2.9.1 当前即使产物已缓存，也会将 Maven 标记为在线模式。因此报告接受此前生成的 BOM 作为已确认输入边界，
    并离线检查其许可证、哈希和引用完整性；绝不悄然改用手写许可证扫描器。可选字段缺省与空集合均计为缺失，
    使 StrictMode 下仍能生成原有失败关闭的阻塞报告，而不是在披露缺失前中断。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path
    )

    $bom = Normalize-MavenBom -Path $Path
    $components = @(if ($bom.PSObject.Properties['components'] -and $bom.components) { $bom.components })
    $missingLicenses = @($components | Where-Object {
        -not $_.PSObject.Properties['licenses'] -or $null -eq $_.licenses -or @($_.licenses).Count -eq 0
    })
    $missingHashes = @($components | Where-Object {
        -not $_.PSObject.Properties['hashes'] -or $null -eq $_.hashes -or
            @($_.hashes | Where-Object { $_.PSObject.Properties['alg'] -and [string]$_.alg -eq 'SHA-256' }).Count -eq 0
    })
    $missingReferences = @($components | Where-Object {
        -not $_.PSObject.Properties['externalReferences'] -or $null -eq $_.externalReferences -or @($_.externalReferences).Count -eq 0
    })
    $rootLicense = @()
    if ($bom.PSObject.Properties['metadata'] -and $bom.metadata -and
        $bom.metadata.PSObject.Properties['component'] -and $bom.metadata.component -and
        $bom.metadata.component.PSObject.Properties['licenses']) {
        foreach ($license in @($bom.metadata.component.licenses)) {
            if ($null -eq $license -or -not $license.PSObject.Properties['license'] -or $null -eq $license.license) { continue }
            if ($license.license.PSObject.Properties['id'] -and $license.license.id) { $rootLicense += [string]$license.license.id }
            elseif ($license.license.PSObject.Properties['name'] -and $license.license.name) { $rootLicense += [string]$license.license.name }
        }
    }
# CycloneDX 可能会将 SPDX GPL-3.0-or-later 规范化为等价标记 ``GPL-3.0+``。
    $rootExpected = @($rootLicense | Where-Object { $_ -match '(?i)GPL-3\.0(?:-or-later|\+)|GNU General Public License.*3' }).Count -gt 0
    return [PSCustomObject][ordered]@{
        bomFormat = [string]$bom.bomFormat
        specVersion = [string]$bom.specVersion
        componentCount = [int]$components.Count
        missingLicenseCount = [int]$missingLicenses.Count
        missingSha256Count = [int]$missingHashes.Count
        missingExternalReferenceCount = [int]$missingReferences.Count
        rootLicense = @($rootLicense | Sort-Object -Unique)
        rootLicenseMatchesJa = $rootExpected
        normalizedBom = $bom
    }
}

function Get-LicenseArchiveFileSetAudit {
    <#
    验证每份归档文本都以哈希寻址，在集合中恰好被引用一次，且字节内容与文件名中的哈希一致。
    这样可避免状态字段错误批准过期或多余文本。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$ArchiveRoot,
        [Parameter(Mandatory = $true)]$Manifest
    )

    $textRoot = Join-Path $ArchiveRoot 'text'
    $actualFiles = if (Test-Path -LiteralPath $textRoot -PathType Container) {
        @(Get-ChildItem -LiteralPath $textRoot -File | Sort-Object Name)
    } else { @() }
    $referenced = [System.Collections.Generic.List[string]]::new()
    foreach ($record in @($Manifest.mappings) + @($Manifest.missingReview)) {
        if (-not $record.PSObject.Properties['archiveFiles']) {
            return [PSCustomObject][ordered]@{ status = 'invalid'; reason = 'archive record has no archiveFiles'; files = $actualFiles }
        }
        foreach ($archiveFile in @($record.archiveFiles)) {
            $path = ([string]$archiveFile).Replace('\', '/')
            if ($path -notmatch '^text/(?<hash>[0-9a-f]{64})\.txt$') {
                return [PSCustomObject][ordered]@{ status = 'invalid'; reason = 'archiveFile is not a hash-addressed text path'; files = $actualFiles }
            }
            $full = Join-Path $ArchiveRoot ($path.Replace('/', [IO.Path]::DirectorySeparatorChar))
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
                return [PSCustomObject][ordered]@{ status = 'invalid'; reason = 'archiveFile is missing'; files = $actualFiles }
            }
            $actualHash = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actualHash -ne $Matches.hash) {
                return [PSCustomObject][ordered]@{ status = 'invalid'; reason = 'archiveFile hash does not match bytes'; files = $actualFiles }
            }
            $referenced.Add($path)
        }
    }
    $actualRelative = @($actualFiles | ForEach-Object { "text/$($_.Name)" } | Sort-Object -Unique)
    $referencedRelative = @($referenced | Sort-Object -Unique)
    if (($actualRelative -join "`n") -ne ($referencedRelative -join "`n")) {
        return [PSCustomObject][ordered]@{ status = 'invalid'; reason = 'archive text set differs from manifest references'; files = $actualFiles }
    }
    return [PSCustomObject][ordered]@{ status = 'valid'; reason = $null; files = $actualFiles }
}

function Get-LicenseArchiveInputAudit {
    <#
    将已晋级且绑定角色的输入哈希与当前锁文件、清单及本次提供的 Maven BOM 对比；生成的 BOM 路径可以不同，但角色必须一致。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$MavenBomPath,
        [Parameter(Mandatory = $true)]$Manifest
    )

    $expectedPaths = [ordered]@{
        'node-manifest' = 'package.json'
        'node-lock' = 'pnpm-lock.yaml'
        'cargo-workspace-manifest' = 'Cargo.toml'
        'cargo-package-manifest' = 'apps/desktop/src-tauri/Cargo.toml'
        'cargo-lock' = 'Cargo.lock'
        'maven-parent-manifest' = 'ja-agent/pom.xml'
        'maven-core-manifest' = 'ja-agent/ja-agent-core/pom.xml'
        'maven-store-local-manifest' = 'ja-agent/extensions/ja-agent-store-local/pom.xml'
        'maven-store-pg-manifest' = 'ja-agent/extensions/ja-agent-store-pg/pom.xml'
        'maven-skills-local-manifest' = 'ja-agent/extensions/ja-agent-skills-local/pom.xml'
        'maven-skills-pg-manifest' = 'ja-agent/extensions/ja-agent-skills-pg/pom.xml'
        'maven-example-manifest' = 'ja-agent/example/pom.xml'
        'maven-example-spring-boot-manifest' = 'ja-agent/example/ja-agent-example-springboot/pom.xml'
        'maven-app-server-manifest' = 'ja-agent/ja-app-server/pom.xml'
        'maven-bom' = $MavenBomPath
    }
    $inputs = @($Manifest.inputs)
    foreach ($role in $expectedPaths.Keys) {
        $records = @($inputs | Where-Object { [string]$_.role -eq $role })
        if ($records.Count -ne 1) {
            return [PSCustomObject][ordered]@{ status = 'invalid'; reason = "input role is missing or duplicated: $role" }
        }
        $path = Resolve-RepositoryPath -Path ([string]$expectedPaths[$role]) -BasePath $RepositoryRoot
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            return [PSCustomObject][ordered]@{ status = 'missing'; reason = "current input is missing: $role" }
        }
        $expectedHashMode = if ($role -eq 'maven-bom') { 'cyclonedx-dependency-graph-v1' } else { 'raw-bytes' }
        if ([string]$records[0].hashMode -ne $expectedHashMode) {
            return [PSCustomObject][ordered]@{ status = 'invalid'; reason = "input hash mode is invalid: $role" }
        }
        $actualHash = Get-EvidenceHash -Path $path -HashMode $expectedHashMode
        if ($actualHash -ne [string]$records[0].sha256) {
            return [PSCustomObject][ordered]@{ status = 'stale'; reason = "current input differs from approved archive: $role" }
        }
    }
    if (@($inputs | ForEach-Object { [string]$_.role } | Sort-Object -Unique).Count -ne $expectedPaths.Count) {
        return [PSCustomObject][ordered]@{ status = 'invalid'; reason = 'archive contains an unknown or duplicate input role' }
    }
    return [PSCustomObject][ordered]@{ status = 'current'; reason = $null }
}

function Get-LicenseArchiveAudit {
    <#
    .SYNOPSIS
    检查仓库中是否包含已批准且以哈希寻址的第三方许可证归档。

    .DESCRIPTION
    归档清单是审查边界。若目录非空但源码审查仍待处理，就不能悄然关闭发布门禁；
    因此除了文本文件数量，还必须检查状态。本函数只验证证据结构，不分类许可证。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$MavenBomPath
    )

# 发布负责人审查候选项之前，归档可能确实不存在；将其保留为零项证据，
# 以便生成失败关闭的阻塞项清单，而不是提前抛出异常。
    $licenseRoot = Resolve-RepositoryPath -Path 'LICENSES' -BasePath $RepositoryRoot
    $textRoot = Join-Path $licenseRoot 'approved\text'
    $files = @()
    if (Test-Path -LiteralPath $textRoot -PathType Container) {
        $files = @(Get-ChildItem -LiteralPath $textRoot -File | Sort-Object Name)
    }
    $manifestPath = Join-Path $licenseRoot 'approved\manifest.json'
    $manifest = $null
    $manifestStatus = 'missing'
    $manifestError = $null
    $integrityStatus = 'missing'
    $integrityError = $null
    $inputStatus = 'missing'
    $inputError = $null
    if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
        try {
            $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
            $manifestStatus = [string]$manifest.status
            if ([int]$manifest.schemaVersion -ne 4) {
                throw 'manifest schemaVersion must be 4'
            }
            $fileAudit = Get-LicenseArchiveFileSetAudit -ArchiveRoot (Join-Path $licenseRoot 'approved') -Manifest $manifest
            $integrityStatus = [string]$fileAudit.status
            $integrityError = [string]$fileAudit.reason
            $inputAudit = Get-LicenseArchiveInputAudit -RepositoryRoot $RepositoryRoot -MavenBomPath $MavenBomPath -Manifest $manifest
            $inputStatus = [string]$inputAudit.status
            $inputError = [string]$inputAudit.reason
        } catch {
            $manifestStatus = 'invalid'
            $manifestError = 'manifest is malformed or violates schemaVersion 4'
        }
    }
    $mappingCount = 0
    $pendingCount = 0
    if ($null -ne $manifest) {
        if ($manifest.PSObject.Properties['mappings']) { $mappingCount = @($manifest.mappings).Count }
        if ($manifest.PSObject.Properties['missingReview']) { $pendingCount = @($manifest.missingReview).Count }
    }
    return [PSCustomObject][ordered]@{
        path = 'LICENSES'
        textFileCount = [int]$files.Count
        files = @($files | ForEach-Object { Get-RelativeRepositoryPath -Path $_.FullName -BasePath $RepositoryRoot })
        manifest = if (Test-Path -LiteralPath $manifestPath -PathType Leaf) { Get-RelativeRepositoryPath -Path $manifestPath -BasePath $RepositoryRoot } else { $null }
        manifestStatus = $manifestStatus
        manifestError = $manifestError
        integrityStatus = $integrityStatus
        integrityError = $integrityError
        inputStatus = $inputStatus
        inputError = $inputError
        mappingCount = [int]$mappingCount
        pendingReviewCount = [int]$pendingCount
    }
}

function Get-ArtifactAudit {
    <#
    .SYNOPSIS
    对显式提供的发布产物计算哈希，绝不扫描宽泛的工作区目录。

    .DESCRIPTION
    发布输出名称和签名必须来自实际文件。要求提供明确路径，可避免把忽略的构建残留当作可分发产物，
    也让本报告能在存在未提交改动的开发工作区中安全运行。
    #>
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Paths,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $files = @()
    foreach ($pathText in $Paths) {
        $path = Resolve-RepositoryPath -Path $pathText -BasePath $RepositoryRoot -RequireExisting
        $item = Get-Item -LiteralPath $path -Force
        if ($item.PSIsContainer) {
            $files += @(Get-ChildItem -LiteralPath $item.FullName -Recurse -File | ForEach-Object { Get-FileEvidence -Path $_.FullName -RepositoryRoot $RepositoryRoot })
        } else {
            $files += Get-FileEvidence -Path $item.FullName -RepositoryRoot $RepositoryRoot
        }
    }
    return @($files | Sort-Object path, sha256 -Unique)
}

function Add-Blocker {
    <#
    .SYNOPSIS
    向报告添加一个稳定的发布门禁代码和原因。

    .DESCRIPTION
    稳定代码使 CI 断言不易失效，简短原因则让报告便于人工阅读。本函数有意不接收原始工具输出，
    避免密钥或主机路径经由错误分支进入发布证据。
    #>
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[object]]$Blockers,
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Reason
    )

    $Blockers.Add([PSCustomObject][ordered]@{ code = $Code; reason = $Reason })
}

function Write-ChecksumManifest {
    <#
    .SYNOPSIS
    为生成的证据文件写入排序后的 SHA-256 清单。

    .DESCRIPTION
    清单排除自身以避免校验和循环；每个列出的文件都会在写入后计算哈希。
    按斜杠规范化的路径排序，可使相同输入在不同主机上生成完全一致的字节内容。
    #>
    param(
        [Parameter(Mandatory = $true)][string[]]$Paths,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][string]$ManifestPath
    )

    $lines = @()
    foreach ($path in $Paths) {
        $evidence = Get-FileEvidence -Path $path -RepositoryRoot $RepositoryRoot
        $lines += ('{0}  {1}' -f $evidence.sha256, $evidence.path)
    }
    [IO.File]::WriteAllText($ManifestPath, (($lines | Sort-Object) -join [Environment]::NewLine) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

$root = [IO.Path]::GetFullPath($RepositoryRoot)
$outputRoot = Resolve-RepositoryPath -Path $OutputDirectory -BasePath $root
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null

$blockers = [System.Collections.Generic.List[object]]::new()
$git = Get-GitProvenance -RepositoryRoot $root
$inputs = Get-InputEvidence -RepositoryRoot $root

$nodeInventory = Get-NodeLicenseInventory -RepositoryRoot $root
$nodePath = Join-Path $outputRoot 'node-licenses.json'
Write-StableJson -Value $nodeInventory -Path $nodePath

$cargoInventory = Get-CargoLicenseInventory -RepositoryRoot $root -Toolchain $CargoToolchain
$cargoPath = Join-Path $outputRoot 'cargo-packages.json'
Write-StableJson -Value $cargoInventory -Path $cargoPath

$mavenBomAudit = $null
$mavenOutputPath = Join-Path $outputRoot 'maven-cyclonedx.json'
$mavenInput = Resolve-RepositoryPath -Path $MavenBomPath -BasePath $root
if (Test-Path -LiteralPath $mavenInput -PathType Leaf) {
    $mavenBomAudit = Get-MavenBomAudit -Path $mavenInput
    Write-StableJson -Value $mavenBomAudit.normalizedBom -Path $mavenOutputPath
    if ($mavenBomAudit.missingLicenseCount -gt 0) { Add-Blocker -Blockers $blockers -Code 'MAVEN_BOM_LICENSES_MISSING' -Reason 'one or more Maven BOM components lack license records' }
    if ($mavenBomAudit.missingSha256Count -gt 0) { Add-Blocker -Blockers $blockers -Code 'MAVEN_BOM_SHA256_MISSING' -Reason 'one or more Maven BOM components lack SHA-256 hashes' }
    if ($mavenBomAudit.missingExternalReferenceCount -gt 0) { Add-Blocker -Blockers $blockers -Code 'MAVEN_BOM_REFERENCE_MISSING' -Reason 'one or more Maven BOM components lack source/reference URLs' }
    if (-not $mavenBomAudit.rootLicenseMatchesJa) { Add-Blocker -Blockers $blockers -Code 'PROJECT_LICENSE_METADATA_MISMATCH' -Reason 'Maven BOM root component does not declare Ja GPL-3.0-or-later' }
} else {
# 只删除脚本管理的规范化 BOM，避免把之前运行的文件误认为本轮证据。
    if (Test-Path -LiteralPath $mavenOutputPath -PathType Leaf) {
        Remove-Item -LiteralPath $mavenOutputPath -Force
    }
    Add-Blocker -Blockers $blockers -Code 'MAVEN_BOM_INPUT_MISSING' -Reason 'provide a CycloneDX Maven BOM generated by the pinned mature plugin before release'
}

$archive = Get-LicenseArchiveAudit -RepositoryRoot $root -MavenBomPath $MavenBomPath
if ($archive.textFileCount -eq 0) {
    Add-Blocker -Blockers $blockers -Code 'LICENSE_ARCHIVE_EMPTY' -Reason 'LICENSES contains no audited third-party license text files'
} elseif ($archive.manifestStatus -eq 'missing') {
    Add-Blocker -Blockers $blockers -Code 'LICENSE_ARCHIVE_MANIFEST_MISSING' -Reason 'LICENSES/approved/manifest.json is required to connect text hashes to reviewed components'
} elseif ($archive.manifestStatus -eq 'invalid') {
    Add-Blocker -Blockers $blockers -Code 'LICENSE_ARCHIVE_MANIFEST_INVALID' -Reason 'LICENSES/approved/manifest.json is malformed or violates schemaVersion 4'
} elseif ($archive.manifestStatus -ne 'approved') {
    Add-Blocker -Blockers $blockers -Code 'LICENSE_ARCHIVE_REVIEW_PENDING' -Reason ('license archive status is {0}; explicit legal/source review must produce status=approved' -f $archive.manifestStatus)
} elseif ($archive.integrityStatus -ne 'valid') {
    Add-Blocker -Blockers $blockers -Code 'LICENSE_ARCHIVE_INTEGRITY_INVALID' -Reason ('license archive integrity is {0}' -f $archive.integrityStatus)
} elseif ($archive.inputStatus -ne 'current') {
    Add-Blocker -Blockers $blockers -Code 'LICENSE_ARCHIVE_INPUT_STALE' -Reason ('license archive input status is {0}' -f $archive.inputStatus)
}

$artifacts = @(Get-ArtifactAudit -Paths $ArtifactPath -RepositoryRoot $root)
if ($artifacts.Count -eq 0) {
    Add-Blocker -Blockers $blockers -Code 'ARTIFACTS_NOT_PROVIDED' -Reason 'supply actual release bundles/installers for artifact checksum and signature evidence'
}

if ($git.dirty) {
    Add-Blocker -Blockers $blockers -Code 'GIT_TREE_DIRTY' -Reason 'release provenance must be generated from a clean source commit'
}
if ([string]::IsNullOrWhiteSpace($CorrespondingSourcePath)) {
    Add-Blocker -Blockers $blockers -Code 'CORRESPONDING_SOURCE_NOT_PROVIDED' -Reason 'record the GPL corresponding-source archive or durable source offer before distribution'
} elseif (-not (Test-Path -LiteralPath (Resolve-RepositoryPath -Path $CorrespondingSourcePath -BasePath $root))) {
    Add-Blocker -Blockers $blockers -Code 'CORRESPONDING_SOURCE_MISSING' -Reason 'the supplied corresponding-source path does not exist'
}

$toolFacts = @(
    Get-ToolVersion -FilePath 'pnpm' -Arguments @('--version')
    Get-ToolVersion -FilePath 'cargo' -Arguments @("+$CargoToolchain", '--version')
    Get-ToolVersion -FilePath 'git' -Arguments @('--version')
)

$evidencePaths = @($nodePath, $cargoPath)
if (Test-Path -LiteralPath $mavenOutputPath -PathType Leaf) { $evidencePaths += $mavenOutputPath }
$evidenceFacts = @($evidencePaths | ForEach-Object { Get-FileEvidence -Path $_ -RepositoryRoot $root })
$reportPath = Join-Path $outputRoot 'dependency-license-report.json'
$report = [PSCustomObject][ordered]@{
    schemaVersion = 1
    project = 'ja'
    source = $git
    networkPolicy = 'offline'
    tools = $toolFacts
    inputs = $inputs
    node = [PSCustomObject][ordered]@{ evidence = 'node-licenses.json'; entryCount = $nodeInventory.entryCount; packageVersionCount = $nodeInventory.packageVersionCount; missingLicenseCount = $nodeInventory.missingLicenseCount }
    rust = [PSCustomObject][ordered]@{ evidence = 'cargo-packages.json'; packageCount = $cargoInventory.packageCount; missingLicenseCount = $cargoInventory.missingLicenseCount; registryChecksumCount = $cargoInventory.registryChecksumCount; gitSourceCount = $cargoInventory.gitSourceCount; pathSourceCount = $cargoInventory.pathSourceCount }
    maven = if ($mavenBomAudit) { [PSCustomObject][ordered]@{ evidence = 'maven-cyclonedx.json'; componentCount = $mavenBomAudit.componentCount; missingLicenseCount = $mavenBomAudit.missingLicenseCount; missingSha256Count = $mavenBomAudit.missingSha256Count; missingExternalReferenceCount = $mavenBomAudit.missingExternalReferenceCount; rootLicense = $mavenBomAudit.rootLicense; rootLicenseMatchesJa = $mavenBomAudit.rootLicenseMatchesJa } } else { [PSCustomObject][ordered]@{ evidence = $null; status = 'missing' } }
    licenseArchive = $archive
    artifacts = $artifacts
    evidence = $evidenceFacts
    blockers = @($blockers)
    status = if ($blockers.Count -eq 0) { 'complete' } else { 'blocked' }
}
Write-StableJson -Value $report -Path $reportPath

$checksumPath = Join-Path $outputRoot 'SHA256SUMS'
Write-ChecksumManifest -Paths @($evidencePaths + $reportPath) -RepositoryRoot $root -ManifestPath $checksumPath

$provenancePath = Join-Path $outputRoot 'provenance.json'
$provenance = [PSCustomObject][ordered]@{
    schemaVersion = 1
    project = 'ja'
    source = $git
    networkPolicy = 'offline'
    report = Get-FileEvidence -Path $reportPath -RepositoryRoot $root
    evidence = @($evidenceFacts)
    licenseArchive = $archive
    correspondingSource = if ([string]::IsNullOrWhiteSpace($CorrespondingSourcePath)) { $null } else { Get-RelativeRepositoryPath -Path (Resolve-RepositoryPath -Path $CorrespondingSourcePath -BasePath $root) -BasePath $root }
    blockers = @($blockers)
    status = if ($blockers.Count -eq 0) { 'complete' } else { 'blocked' }
}
Write-StableJson -Value $provenance -Path $provenancePath

# 再次写入校验和清单以涵盖来源信息；清单仍排除自身，避免产生自引用哈希循环。
Write-ChecksumManifest -Paths @($evidencePaths + $reportPath + $provenancePath) -RepositoryRoot $root -ManifestPath $checksumPath

Write-Output ("status={0} blockers={1} output={2}" -f $report.status, $blockers.Count, (Get-RelativeRepositoryPath -Path $outputRoot -BasePath $root))
if ($FailOnBlocker -and $blockers.Count -gt 0) {
    exit 2
}
exit 0
