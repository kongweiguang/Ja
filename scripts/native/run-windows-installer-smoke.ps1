# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

<#
.SYNOPSIS
  在私有临时目录中安装并启动一个未签名的 Ja NSIS 安装包。

.DESCRIPTION
  这只是工程 smoke，用于验证真实 Windows NSIS 安装包能够完成安装、提供预期的应用和 sidecar 文件、
  执行单实例约束并完成卸载，且无需签名凭据。只能在干净的 Windows 用户配置下运行：NSIS 使用产品级卸载和快捷方式标识。
  生成的记录不属于发布门禁证据。
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $Installer,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $Output,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{7,64}$')]
    [string] $SourceCommit,

    [ValidateRange(10, 600)]
    [int] $TimeoutSeconds = 90,

    [switch] $RequireUnsigned
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

<#
.SYNOPSIS
  在 Windows PowerShell 5.1 和 PowerShell 7 中计算相对路径。

.DESCRIPTION
  Windows PowerShell 5.1 使用的 .NET Framework 未提供 Path.GetRelativePath。
  通过 URI 回退即可保持证据格式稳定，无需仅为路径格式化更换较新 shell；它不会改变被检查或删除的文件。
#>
function Get-PortableRelativePath {
    param(
        [Parameter(Mandatory = $true)][string] $BasePath,
        [Parameter(Mandatory = $true)][string] $ChildPath
    )

    $pathType = [System.IO.Path]
    $relativeMethod = $pathType.GetMethod('GetRelativePath', [Type[]] @([string], [string]))
    if ($null -ne $relativeMethod) {
        return [System.IO.Path]::GetRelativePath($BasePath, $ChildPath)
    }

    $baseFull = [System.IO.Path]::GetFullPath($BasePath).TrimEnd('\') + '\'
    $childFull = [System.IO.Path]::GetFullPath($ChildPath)
    $baseUri = [System.Uri]::new($baseFull)
    $childUri = [System.Uri]::new($childFull)
    return [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($childUri).ToString()).Replace('/', '\')
}

<#
.SYNOPSIS
  返回一个文件的可移植信息和当前 Authenticode 状态。

.DESCRIPTION
  在卸载前收集哈希，以便报告将已安装文件与受测安装包关联，同时绝不序列化用户专属临时路径或疑似凭据内容。
#>
function Get-FileEvidence {
    param([Parameter(Mandatory = $true)][System.IO.FileInfo] $File)

    if (-not $File.Exists -or $File.Length -le 0) {
        throw "File is missing or empty: $($File.FullName)"
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $File.FullName
    return [ordered]@{
        fileName = $File.Name
        relativePath = $null
        sizeBytes = [int64] $File.Length
        sha256 = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        signingStatus = [string] $signature.Status
    }
}

<#
.SYNOPSIS
  在有界等待时间内运行安装器或卸载器，并清理其进程树。

.DESCRIPTION
  安装包被中断时，NSIS 可能遗留辅助进程。有界进程封装可让 smoke 结果稳定，并将强制清理限制在本脚本启动的进程，
  比按全局可执行文件名匹配更安全。
#>
function Invoke-BoundedProcess {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [Parameter(Mandatory = $true)][string[]] $ArgumentList,
        [Parameter(Mandatory = $true)][string] $WorkingDirectory,
        [Parameter(Mandatory = $true)][int] $TimeoutMilliseconds
    )

    $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory -PassThru -WindowStyle Hidden
    try {
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            Stop-ProcessTree -ProcessId $process.Id
            throw "Process timed out: $([System.IO.Path]::GetFileName($FilePath))"
        }
        $process.Refresh()
        return [ordered]@{
            pid = [int] $process.Id
            exitCode = [int] $process.ExitCode
        }
    }
    finally {
        $process.Dispose()
    }
}

<#
.SYNOPSIS
  只终止以 smoke 自有 PID 为根的进程树。

.DESCRIPTION
  taskkill 的 `/T` 选项是 Windows 提供的进程树操作方式。只接受由本脚本启动的进程 PID，
  因此清理不会搜索或终止其他 Ja 应用。
#>
function Stop-ProcessTree {
    param([Parameter(Mandatory = $true)][int] $ProcessId)

    if ($ProcessId -le 0) { return }
    & taskkill.exe /PID $ProcessId /T /F 1>$null 2>$null
    if ($LASTEXITCODE -notin @(0, 128)) {
        throw "Failed to reap smoke process tree: pid=$ProcessId exit=$LASTEXITCODE"
    }
}

<#
.SYNOPSIS
  通过同目录临时文件写入 JSON 证据文档。

.DESCRIPTION
  若主机在哈希或清理期间中断，原子替换可避免发布截断记录；文件路径由调用方选择，不从安装包推导。
#>
function Write-Evidence {
    param(
        [Parameter(Mandatory = $true)][System.Collections.IDictionary] $Document,
        [Parameter(Mandatory = $true)][System.IO.FileInfo] $Destination
    )

    $parent = $Destination.Directory
    if ($null -eq $parent) { throw 'Evidence output directory is invalid' }
    $parent.Create()
    $temporary = [System.IO.FileInfo]::new($Destination.FullName + '.tmp')
    $json = $Document | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($temporary.FullName, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary.FullName -Destination $Destination.FullName -Force
}

<#
.SYNOPSIS
  验证路径仍位于 smoke 私有根目录内。

.DESCRIPTION
  安装目标由本次运行生成。读取或删除文件前先检查路径包含关系，避免格式错误的路径参数将安装器 smoke 变成大范围文件清理。
#>
function Assert-ContainedPath {
    param(
        [Parameter(Mandatory = $true)][System.IO.DirectoryInfo] $Root,
        [Parameter(Mandatory = $true)][string] $Candidate
    )

    $rootPath = $Root.FullName.TrimEnd('\') + '\'
    $candidatePath = ([System.IO.Path]::GetFullPath($Candidate)).TrimEnd('\') + '\'
    if (-not $candidatePath.StartsWith($rootPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escaped the smoke temporary root: $Candidate"
    }
}

<#
.SYNOPSIS
  为 NSIS 自删除辅助进程留出一段有界短暂时间，以便移除已安装文件。

.DESCRIPTION
  NSIS 卸载器可能在自删除辅助进程完成前就返回。只轮询私有安装目录可以避免因时序产生误报，
  同时保留严格时间上限，且清理范围不会超出本次生成的根目录。
#>
function Wait-ForInstallFilesRemoved {
    param(
        [Parameter(Mandatory = $true)][string] $InstallDirectory,
        [Parameter(Mandatory = $true)][int] $TimeoutMilliseconds
    )

    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMilliseconds)
    do {
        $remaining = if (Test-Path -LiteralPath $InstallDirectory -PathType Container) {
            @(Get-ChildItem -LiteralPath $InstallDirectory -File -Recurse -ErrorAction SilentlyContinue)
        } else {
            @()
        }
        if (@($remaining).Count -eq 0) {
            return [ordered]@{
                installRootRemoved = -not (Test-Path -LiteralPath $InstallDirectory)
                remainingFiles = @()
            }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return [ordered]@{
        installRootRemoved = -not (Test-Path -LiteralPath $InstallDirectory)
        remainingFiles = @($remaining | ForEach-Object { $_.Name })
    }
}

$installerFile = Get-Item -LiteralPath $Installer
if (-not $installerFile.PSIsContainer -and $installerFile.Extension -ieq '.exe') {
    $installerFile = [System.IO.FileInfo] $installerFile
} else {
    throw "Installer must be an .exe file: $Installer"
}
# 同名 NSIS 会覆盖已有 Ja 的卸载键和快捷方式；普通开发机必须在任何安装副作用前失败。
$existingJaBinary = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) 'Programs\Ja\ja.exe'
if ((Test-Path -LiteralPath $existingJaBinary) -or
    (Test-Path -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Ja') -or
    (Test-Path -LiteralPath 'HKCU:\Software\github\Ja') -or
    (Test-Path -LiteralPath 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Ja')) {
    throw 'Windows installer smoke requires a clean user profile without an existing Ja installation'
}
$outputParent = Split-Path -Parent $Output
if ([string]::IsNullOrWhiteSpace($outputParent)) { $outputParent = (Get-Location).Path }
New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
$outputFile = [System.IO.FileInfo]::new((Resolve-Path -LiteralPath $outputParent).Path + '\' + (Split-Path -Leaf $Output))
$timeoutMilliseconds = $TimeoutSeconds * 1000
$tempBase = if ($env:RUNNER_TEMP -and (Test-Path -LiteralPath $env:RUNNER_TEMP -PathType Container)) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
$rootPath = Join-Path $tempBase ('ja-installer-smoke-' + [guid]::NewGuid().ToString('N'))
$installPath = Join-Path $rootPath 'install'
$root = [System.IO.DirectoryInfo]::new($rootPath)
$result = [ordered]@{
    schemaVersion = 1
    product = 'Ja'
    sourceCommit = $SourceCommit.ToLowerInvariant()
    engineeringSmokeOnly = $true
    releaseGate = $false
    platform = 'windows'
    architecture = 'x86_64'
    installer = $null
    installation = [ordered]@{ status = 'pending'; files = @(); app = $null; sidecar = $null }
    launch = [ordered]@{
        status = 'pending'
        pid = $null
        secondInstance = [ordered]@{ status = 'pending'; exitedBeforeDeadline = $false; exitCode = $null }
        processTreeReaped = $false
    }
    uninstall = [ordered]@{ status = 'pending'; exitCode = $null; installRootRemoved = $false; installFilesRemoved = $false; remainingFiles = @() }
    cleanup = [ordered]@{ status = 'pending'; temporaryRootRemoved = $false }
    signing = [ordered]@{ installer = 'unknown'; installedBinaries = @(); notarizationStatus = 'not-applicable' }
}
$failure = $null

try {
    New-Item -ItemType Directory -Force -Path $rootPath | Out-Null
    $root.Refresh()
    $result.installer = Get-FileEvidence -File ([System.IO.FileInfo] $installerFile)
    $result.signing.installer = $result.installer.signingStatus
    if ($RequireUnsigned -and $result.installer.signingStatus -ne 'NotSigned') {
        throw "Engineering smoke requires an unsigned installer; actual status: $($result.installer.signingStatus)"
    }

    $installResult = Invoke-BoundedProcess -FilePath $installerFile.FullName -ArgumentList @('/S', ('/D=' + $installPath)) -WorkingDirectory $rootPath -TimeoutMilliseconds $timeoutMilliseconds
    if ($installResult.exitCode -ne 0) { throw "NSIS installation failed: exit=$($installResult.exitCode)" }
    if (-not (Test-Path -LiteralPath $installPath -PathType Container)) { throw 'NSIS did not create the temporary install directory' }
    Assert-ContainedPath -Root $root -Candidate $installPath

    $installedFiles = @(Get-ChildItem -LiteralPath $installPath -File -Recurse)
    if ($installedFiles.Count -eq 0) { throw 'Install directory contains no files' }
    $evidenceFiles = foreach ($file in $installedFiles) {
        Assert-ContainedPath -Root $root -Candidate $file.FullName
        $entry = Get-FileEvidence -File ([System.IO.FileInfo] $file)
        $entry.relativePath = (Get-PortableRelativePath -BasePath $installPath -ChildPath $file.FullName).Replace('\', '/')
        $entry
    }
    $result.installation.files = @($evidenceFiles)
    $app = Get-Item -LiteralPath (Join-Path $installPath 'ja-desktop.exe') -ErrorAction Stop
    $uninstaller = Get-Item -LiteralPath (Join-Path $installPath 'uninstall.exe') -ErrorAction Stop
    $sidecarFiles = @(Get-ChildItem -LiteralPath (Join-Path $installPath 'sidecars') -Filter 'ja-app-server-*.exe' -File)
    if ($sidecarFiles.Count -ne 1) { throw "Unexpected sidecar count in install directory: $($sidecarFiles.Count)" }
    $result.installation.app = Get-FileEvidence -File ([System.IO.FileInfo] $app)
    $result.installation.sidecar = Get-FileEvidence -File ([System.IO.FileInfo] $sidecarFiles[0])
    $result.signing.installedBinaries = @($result.installation.app.signingStatus, $result.installation.sidecar.signingStatus)
    if ($RequireUnsigned -and ($result.signing.installedBinaries | Where-Object { $_ -ne 'NotSigned' })) {
        throw 'Engineering smoke requires the installed app and sidecar to be unsigned'
    }
    $result.installation.status = 'passed'

    $appProcess = Start-Process -FilePath $app.FullName -WorkingDirectory $installPath -PassThru -WindowStyle Hidden
    try {
        Start-Sleep -Milliseconds 1500
        $appProcess.Refresh()
        if ($appProcess.HasExited) { throw "Installed app exited early: exit=$($appProcess.ExitCode)" }
        $result.launch.pid = [int] $appProcess.Id

        # 第二次启动必须在首实例仍存活时有界退出；签名安装包在冷启动的 Windows Runner
        # 上可能需要超过 10 秒才能完成 single-instance 转交。该等待只属于工程 smoke，
        # 并始终受安装器的总 deadline 约束，避免把时序波动误判为产品实例策略失效。
        $secondInstanceDeadlineMilliseconds = [Math]::Min($timeoutMilliseconds, 30000)
        $secondProcess = Start-Process -FilePath $app.FullName -WorkingDirectory $installPath -PassThru -WindowStyle Hidden
        try {
            if (-not $secondProcess.WaitForExit($secondInstanceDeadlineMilliseconds)) {
                Stop-ProcessTree -ProcessId $secondProcess.Id
                throw 'Second application instance remained alive past the single-instance deadline'
            }
            $secondProcess.Refresh()
            $result.launch.secondInstance.exitedBeforeDeadline = $true
            $result.launch.secondInstance.exitCode = [int] $secondProcess.ExitCode
            $result.launch.secondInstance.status = 'passed'
        }
        finally {
            if (-not $secondProcess.HasExited) { Stop-ProcessTree -ProcessId $secondProcess.Id }
            $secondProcess.Dispose()
        }
        $result.launch.status = 'passed'
    }
    finally {
        Stop-ProcessTree -ProcessId $appProcess.Id
        $result.launch.processTreeReaped = $true
        $appProcess.Dispose()
    }

    $uninstallResult = Invoke-BoundedProcess -FilePath $uninstaller.FullName -ArgumentList @('/S') -WorkingDirectory $rootPath -TimeoutMilliseconds $timeoutMilliseconds
    $result.uninstall.exitCode = $uninstallResult.exitCode
    if ($uninstallResult.exitCode -ne 0) { throw "NSIS uninstall failed: exit=$($uninstallResult.exitCode)" }
    $removal = Wait-ForInstallFilesRemoved -InstallDirectory $installPath -TimeoutMilliseconds ([Math]::Min($timeoutMilliseconds, 10000))
    $remainingFiles = @($removal.remainingFiles)
    $result.uninstall.installRootRemoved = $removal.installRootRemoved
    $result.uninstall.installFilesRemoved = ($remainingFiles.Count -eq 0)
    $result.uninstall.remainingFiles = $remainingFiles
    if (-not $result.uninstall.installFilesRemoved) { throw "Files remain after NSIS uninstall: $($remainingFiles -join ', ')" }
    $result.uninstall.status = 'passed'
}
catch {
    $failure = $_
    $result.error = $_.Exception.Message
}
finally {
    if (Test-Path -LiteralPath $rootPath) {
        # 递归清理前再次证明随机私有根仍在真实 Temp 目录内，且未被替换为重解析点。
        $resolvedBase = (Resolve-Path -LiteralPath $tempBase).Path.TrimEnd('\') + '\'
        $resolvedRoot = (Resolve-Path -LiteralPath $rootPath).Path.TrimEnd('\')
        $rootInfo = Get-Item -LiteralPath $resolvedRoot -Force
        $baseInfo = Get-Item -LiteralPath $tempBase -Force
        $privateLeaf = [System.IO.Path]::GetFileName($resolvedRoot)
        $safeCleanup = $resolvedRoot.StartsWith($resolvedBase, [StringComparison]::OrdinalIgnoreCase) -and `
            $privateLeaf -match '^ja-installer-smoke-[0-9a-f]{32}$' -and `
            -not ($rootInfo.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -and `
            -not ($baseInfo.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
        if ($safeCleanup) {
            Remove-Item -LiteralPath $resolvedRoot -Recurse -Force -ErrorAction SilentlyContinue
        } else {
            $result.error = 'Unsafe installer smoke cleanup target'
        }
    }
    $result.cleanup.temporaryRootRemoved = -not (Test-Path -LiteralPath $rootPath)
    $result.cleanup.status = if ($result.cleanup.temporaryRootRemoved) { 'passed' } else { 'failed' }
    Write-Evidence -Document $result -Destination $outputFile
}

if ($failure -or $result.cleanup.status -ne 'passed') {
    $message = if ($failure) { $failure.Exception.Message } else { 'Temporary root cleanup failed' }
    throw $message
}

if ($result.launch.status -ne 'passed' -or $result.launch.secondInstance.status -ne 'passed' `
        -or $result.uninstall.status -ne 'passed') {
    throw 'Windows installer smoke did not complete launch, single-instance, and uninstall acceptance'
}

$result | ConvertTo-Json -Depth 8
