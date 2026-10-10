# @author kongweiguang
[CmdletBinding()]
param(
    [string]$JdkHome = 'C:\Users\24052\.jdks\liberica-25.0.2',
    [ValidateRange(1, 65535)][int]$Port = 8087,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
& java -version
if ($LASTEXITCODE -ne 0) { throw 'Java is unavailable.' }
$javaRelease = Get-Content (Join-Path $JdkHome 'release') -Raw
if ($javaRelease -notmatch 'JAVA_VERSION="25[.]') { throw 'The example requires JDK 25.' }
$targetDirectory = Join-Path $PSScriptRoot 'target'
if ($Build) {
    $mavenVersion = & mvn.cmd -version 2>&1
    $mavenVersion | ForEach-Object { Write-Output $_ }
    if ($LASTEXITCODE -ne 0) { throw 'Maven is unavailable.' }
    if (($mavenVersion -join "`n") -notmatch 'Java version: 25[.]') { throw 'Maven must run on JDK 25.' }
    $buildDirectory = "target-spring-demo-$([guid]::NewGuid().ToString('N'))"
    & mvn.cmd -B -ntp -f (Join-Path $root 'ja-agent/pom.xml') -pl :ja-agent-example-springboot -am "-Dja.build.directory=$buildDirectory" package
    if ($LASTEXITCODE -ne 0) { throw 'Example build failed.' }
    $targetDirectory = Join-Path $PSScriptRoot $buildDirectory
}
if ($PSBoundParameters.ContainsKey('Port')) { $env:JA_EXAMPLE_PORT = "$Port" }
$jar = Get-ChildItem (Join-Path $targetDirectory '*-exec.jar') | Select-Object -First 1
if (-not $jar) { throw 'Build the Spring Boot example first.' }
# 使用临时运行副本，避免后续构建覆盖运行中的 JAR；Spring 配置来自 YAML，Agent 配置来自 PG 表。
$runtimeJar = Join-Path ([System.IO.Path]::GetTempPath()) "ja-agent-chat-$([guid]::NewGuid().ToString('N')).jar"
Copy-Item -LiteralPath $jar.FullName -Destination $runtimeJar
try {
    & java --enable-native-access=ALL-UNNAMED -jar $runtimeJar
    if ($LASTEXITCODE -ne 0) { throw 'Example server stopped with an error.' }
} finally {
    Remove-Item -LiteralPath $runtimeJar -ErrorAction SilentlyContinue
}
