# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

# 验证与 SBOM 共用同一脱敏规则，避免诊断标准漂移；仓库根目录由调用方明确传入，不依赖 cwd。
# 函数只返回诊断文本，调用方决定写入证据或 stderr，避免污染原生命令的 stdout JSON。
# 保留既有凭据与路径替换及 16 KiB 首尾预算，使失败定位信息有界且不持久化敏感输入。
function ConvertTo-SafeDiagnosticText {
    param(
        [AllowNull()][object]$Value,
        [Parameter(Mandatory)][string]$RepositoryRoot
    )

    $text = [string]($Value ?? '')
    $sensitiveEnvironmentNames = 'API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH(?:ORIZATION)?|PASSWORD|SECRET|CREDENTIAL|PRIVATE[_-]?KEY'
    foreach ($entry in Get-ChildItem Env: -ErrorAction SilentlyContinue) {
        if ($entry.Name -match "(?i)$sensitiveEnvironmentNames" -and -not [string]::IsNullOrEmpty($entry.Value)) {
            $text = $text.Replace([string]$entry.Value, '<redacted>')
        }
    }

    $text = $text.Replace($RepositoryRoot, '<repo>')
    $userProfile = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    if (-not [string]::IsNullOrWhiteSpace($userProfile)) {
        $text = $text.Replace($userProfile, '<user>')
    }
    $text = [regex]::Replace($text, '(?im)(api[_ -]?key|access[_ -]?token|authorization|password|client[_ -]?secret|credential|private[_ -]?key)(\s*[:=]\s*|\s+)[^\s;,}]+', '$1=<redacted>')
    $text = [regex]::Replace($text, '(?i)\b(?:bearer\s+|sk-[a-z0-9_-]{8,}|gh[pousr]_[a-z0-9_-]{8,}|secret[-_][a-z0-9_-]+|token[-_][a-z0-9_-]+|password[-_][a-z0-9_-]+)\b', '<redacted>')
    $text = [regex]::Replace($text, '(?i)(?<![A-Za-z])(?:[A-Za-z]:\\|\\\\)[^\r\n\s]+', '<path>')
    if ($text.Length -gt 16384) {
        # 构建工具通常在输出末尾给出动态结论，启动失败则通常出现在开头。脱敏后保留首尾两个有界片段，避免遗漏任一类信息。
        $headLength = 8192
        $tailLength = 8192
        $text = $text.Substring(0, $headLength) + "`n<output-truncated>`n" `
            + $text.Substring($text.Length - $tailLength)
    }
    return $text
}
