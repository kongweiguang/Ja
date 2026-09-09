# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

function Write-BrokerResult {
    <# 将每次操作压成单行 JSON，避免测试进程把剪贴板内容或 CLR 对象写入日志。 #>
    param([Parameter(Mandatory)] [hashtable] $Value)
    [Console]::Out.WriteLine(($Value | ConvertTo-Json -Compress -Depth 5))
    [Console]::Out.Flush()
}

function Invoke-ClipboardWrite {
    <# Windows 剪贴板可能被输入法或 WebView 短暂占用；只在 120ms 有界窗口内重试。 #>
    param([Parameter(Mandatory)] [scriptblock] $Operation)
    $delays = @(0, 15, 35, 70)
    $last = $null
    foreach ($delay in $delays) {
        if ($delay -gt 0) { Start-Sleep -Milliseconds $delay }
        try {
            & $Operation
            return
        }
        catch [System.Runtime.InteropServices.ExternalException] {
            $last = $_.Exception
        }
    }
    throw $last
}

function Copy-ClipboardSnapshot {
    <#
    在首次写入前物化所有原始格式，并复制流为 broker 自有资源。截图工具可能同时暴露
    可读取的 CF_DIB 与无法兑现的 Bitmap 别名；此时保留真实 DIB，避免空别名阻断验收，
    但其它无法物化的格式仍失败关闭，防止用不完整快照覆盖用户剪贴板。
    #>
    param([System.Collections.Generic.List[System.IDisposable]] $Resources)
    $source = [System.Windows.Forms.Clipboard]::GetDataObject()
    $snapshot = [System.Windows.Forms.DataObject]::new()
    if ($null -eq $source) { return $snapshot }
    $formats = @($source.GetFormats($false))
    $dib = $null
    if ([System.Windows.Forms.DataFormats]::Dib -in $formats) {
        $dib = $source.GetData([System.Windows.Forms.DataFormats]::Dib, $false)
    }
    foreach ($format in $formats) {
        $value = $source.GetData($format, $false)
        if ($null -eq $value -and $format -eq [System.Windows.Forms.DataFormats]::Bitmap) {
            $value = [System.Windows.Forms.Clipboard]::GetImage()
        }
        if ($null -eq $value -and $format -eq [System.Windows.Forms.DataFormats]::Bitmap -and $null -ne $dib) {
            continue
        }
        if ($null -eq $value) { throw "clipboard format cannot be materialized: $format" }
        if ($format -eq [System.Windows.Forms.DataFormats]::Bitmap -and $value -is [System.Drawing.Image]) {
            $value = [System.Drawing.Bitmap]::new([System.Drawing.Image]$value)
            $Resources.Add([System.IDisposable]$value)
        }
        elseif ($value -is [System.IO.MemoryStream]) {
            $value = [System.IO.MemoryStream]::new(([System.IO.MemoryStream]$value).ToArray(), $false)
            $Resources.Add([System.IDisposable]$value)
        }
        $snapshot.SetData($format, $false, $value)
    }
    return $snapshot
}

function New-HtmlClipboardValue {
    <# CF_HTML 的字节偏移必须按 UTF-8 计算，确保 WebView2 只观察到 text/html 而没有派生纯文本。 #>
    param([Parameter(Mandatory)] [string] $Marker)
    $fragment = "<p>$([System.Net.WebUtility]::HtmlEncode($Marker))</p>"
    $body = "<html><body><!--StartFragment-->$fragment<!--EndFragment--></body></html>"
    $template = "Version:0.9`r`nStartHTML:{0:D10}`r`nEndHTML:{1:D10}`r`nStartFragment:{2:D10}`r`nEndFragment:{3:D10}`r`n"
    $placeholder = [string]::Format($template, 0, 0, 0, 0)
    $startHtml = [Text.Encoding]::UTF8.GetByteCount($placeholder)
    $startFragment = $startHtml + [Text.Encoding]::UTF8.GetByteCount('<html><body><!--StartFragment-->')
    $endFragment = $startFragment + [Text.Encoding]::UTF8.GetByteCount($fragment)
    $endHtml = $startHtml + [Text.Encoding]::UTF8.GetByteCount($body)
    return [string]::Format($template, $startHtml, $endHtml, $startFragment, $endFragment) + $body
}

function Set-FixtureClipboard {
    <# 每个 case 明确构造格式集合，禁止 Windows Forms 自动补出会改变 Composer 优先级的文本格式。 #>
    param([Parameter(Mandatory)] [pscustomobject] $Command)
    $data = [System.Windows.Forms.DataObject]::new()
    $resources = [System.Collections.Generic.List[System.IDisposable]]::new()
    switch ([string]$Command.kind) {
        'text' {
            $data.SetData([System.Windows.Forms.DataFormats]::UnicodeText, $false, [string]$Command.marker)
        }
        'file_drop' {
            $path = [IO.Path]::GetFullPath([string]$Command.path)
            if (-not [IO.File]::Exists($path)) { throw 'clipboard fixture file is unavailable' }
            $files = [Collections.Specialized.StringCollection]::new()
            [void]$files.Add($path)
            $data.SetFileDropList($files)
        }
        'bitmap' {
            $bitmap = [Drawing.Bitmap]::new(12, 12)
            $resources.Add($bitmap)
            $graphics = [Drawing.Graphics]::FromImage($bitmap)
            try { $graphics.Clear([Drawing.Color]::FromArgb(255, 34, 122, 255)) } finally { $graphics.Dispose() }
            $data.SetData([System.Windows.Forms.DataFormats]::Bitmap, $false, $bitmap)
        }
        'text_image' {
            $data.SetData([System.Windows.Forms.DataFormats]::UnicodeText, $false, [string]$Command.marker)
            $bitmap = [Drawing.Bitmap]::new(12, 12)
            $resources.Add($bitmap)
            $graphics = [Drawing.Graphics]::FromImage($bitmap)
            try { $graphics.Clear([Drawing.Color]::FromArgb(255, 52, 199, 89)) } finally { $graphics.Dispose() }
            $data.SetData([System.Windows.Forms.DataFormats]::Bitmap, $false, $bitmap)
        }
        'html' {
            $data.SetData([System.Windows.Forms.DataFormats]::Html, $false, (New-HtmlClipboardValue -Marker ([string]$Command.marker)))
        }
        default { throw 'unsupported clipboard fixture kind' }
    }
    Invoke-ClipboardWrite { [System.Windows.Forms.Clipboard]::SetDataObject($data, $true) }
    return $resources
}

$snapshot = $null
$snapshotResources = [System.Collections.Generic.List[System.IDisposable]]::new()
$fixtureResources = [System.Collections.Generic.List[System.IDisposable]]::new()
$restored = $false
try {
    $snapshot = Invoke-ClipboardWrite { Copy-ClipboardSnapshot -Resources $snapshotResources }
    Write-BrokerResult @{ status = 'ready'; formatCount = @($snapshot.GetFormats($false)).Count }
    while (($line = [Console]::In.ReadLine()) -ne $null) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $command = $line | ConvertFrom-Json
        if ($command.action -eq 'set') {
            foreach ($resource in $fixtureResources) { $resource.Dispose() }
            $fixtureResources.Clear()
            $created = Set-FixtureClipboard -Command $command
            foreach ($resource in $created) { $fixtureResources.Add($resource) }
            Write-BrokerResult @{ status = 'set'; kind = [string]$command.kind }
            continue
        }
        if ($command.action -eq 'restore') {
            Invoke-ClipboardWrite { [System.Windows.Forms.Clipboard]::SetDataObject($snapshot, $true) }
            $restored = $true
            $actual = @(([System.Windows.Forms.Clipboard]::GetDataObject()).GetFormats($false))
            $expected = @($snapshot.GetFormats($false))
            $same = $actual.Count -eq $expected.Count -and @($expected | Where-Object { $_ -notin $actual }).Count -eq 0
            Write-BrokerResult @{ status = 'restored'; formatsMatch = $same; formatCount = $actual.Count }
            if (-not $same) { exit 5 }
            break
        }
        throw 'unsupported clipboard broker action'
    }
}
finally {
    if ($null -ne $snapshot -and -not $restored) {
        try { Invoke-ClipboardWrite { [System.Windows.Forms.Clipboard]::SetDataObject($snapshot, $true) } } catch { }
    }
    foreach ($resource in $fixtureResources) { $resource.Dispose() }
    foreach ($resource in $snapshotResources) { $resource.Dispose() }
}
