# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

[CmdletBinding()]
param(
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$javaRoot = Join-Path $repositoryRoot 'ja-agent/ja-app-server\src\main\java'
$violations = [System.Collections.Generic.List[object]]::new()
$observations = [System.Collections.Generic.List[object]]::new()

# 按 Maven 声明枚举模块；拒绝循环、越界路径及外部实体，防止异常 POM 扩大扫描范围。
function Get-ReactorModuleRoots {
    param(
        [Parameter(Mandatory)][string]$PomPath,
        [Parameter(Mandatory)][string]$ReactorRoot,
        [Parameter(Mandatory)][AllowEmptyCollection()][System.Collections.Generic.HashSet[string]]$Visited
    )

    $normalizedPom = [System.IO.Path]::GetFullPath($PomPath)
    $normalizedRoot = [System.IO.Path]::GetFullPath($ReactorRoot)
    $moduleRoot = [System.IO.Path]::GetDirectoryName($normalizedPom)
    $relativeModule = [System.IO.Path]::GetRelativePath($normalizedRoot, $moduleRoot)
    $parentPrefix = '..' + [System.IO.Path]::DirectorySeparatorChar
    $escapesRoot = $relativeModule -eq '..' -or
        $relativeModule.StartsWith($parentPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::IsPathRooted($relativeModule)
    if ($escapesRoot) {
        throw "Declared Java module escapes reactor root: $normalizedPom"
    }
    if (-not $Visited.Add($normalizedPom)) {
        throw "Declared Java module is repeated or cyclic: $normalizedPom"
    }
    if (-not (Test-Path -LiteralPath $normalizedPom -PathType Leaf)) {
        throw "Declared Java module has no pom.xml: $normalizedPom"
    }

    $settings = [System.Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [System.Xml.XmlReader]::Create($normalizedPom, $settings)
    $pomDocument = [System.Xml.XmlDocument]::new()
    $pomDocument.XmlResolver = $null
    try {
        $pomDocument.Load($reader)
    } finally {
        $reader.Dispose()
    }
    $moduleRoot
    foreach ($module in $pomDocument.SelectNodes("/*[local-name()='project']/*[local-name()='modules']/*[local-name()='module']")) {
        $relativePath = $module.InnerText.Trim()
        if ([string]::IsNullOrWhiteSpace($relativePath)) {
            throw "Declared Java module path is empty: $normalizedPom"
        }
        $childPom = [System.IO.Path]::GetFullPath((Join-Path (Join-Path $moduleRoot $relativePath) 'pom.xml'))
        Get-ReactorModuleRoots -PomPath $childPom -ReactorRoot $normalizedRoot -Visited $Visited
    }
}

# 只输出仓库相对路径，审查证据不得泄露本机用户目录。
function Get-RepositoryRelativePath {
    param([Parameter(Mandatory)][string]$Path)

    return [System.IO.Path]::GetRelativePath($repositoryRoot, $Path).Replace('\', '/')
}

# 违规证据仅记录定位信息，不把源码和用户数据写入报告。
function Add-ArchitectureViolation {
    param(
        [Parameter(Mandatory)][string]$Rule,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Detail,
        [int]$Line = 0
    )

    $violations.Add([ordered]@{
        rule = $Rule
        path = Get-RepositoryRelativePath -Path $Path
        line = $Line
        detail = $Detail
    })
}

# 文件长度只提示责任审查，不凭行数判定设计违规或强制拆类。
function Add-ArchitectureObservation {
    param(
        [Parameter(Mandatory)][string]$Rule,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Detail,
        [int]$Line = 0
    )

    $observations.Add([ordered]@{
        rule = $Rule
        path = Get-RepositoryRelativePath -Path $Path
        line = $Line
        detail = $Detail
    })
}

# 仅扫描显式生产根；精确排除也必须仍在扫描根内并实际命中规则，不能整包跳过或遗留旧路径。
function Find-ProductionPattern {
    param(
        [Parameter(Mandatory)][string]$Rule,
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string]$Detail,
        [string]$Root = $javaRoot,
        [string[]]$ExcludedRelativePaths = @()
    )

    # 白名单随文件移动或代码删除立即失效，避免失去行为依据的旧豁免继续静默存在。
    $normalizedRoot = [System.IO.Path]::GetFullPath($Root)
    foreach ($excludedRelativePath in $ExcludedRelativePaths) {
        $excludedPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $excludedRelativePath))
        $relativeToRoot = [System.IO.Path]::GetRelativePath($normalizedRoot, $excludedPath)
        $parentPrefix = '..' + [System.IO.Path]::DirectorySeparatorChar
        if ($relativeToRoot -eq '..' -or $relativeToRoot.StartsWith($parentPrefix, [StringComparison]::Ordinal)) {
            Add-ArchitectureViolation -Rule 'invalid-exclusion' -Path $excludedPath `
                -Detail "$Rule exclusion is outside its scanned production source root"
            continue
        }
        if (-not (Test-Path -LiteralPath $excludedPath -PathType Leaf)) {
            Add-ArchitectureViolation -Rule 'stale-exclusion' -Path $excludedPath `
                -Detail "$Rule exclusion references a missing production source"
            continue
        }
        $stillMatches = $false
        foreach ($line in [System.IO.File]::ReadLines($excludedPath)) {
            if ($line -match $Pattern) {
                $stillMatches = $true
                break
            }
        }
        if (-not $stillMatches) {
            Add-ArchitectureViolation -Rule 'stale-exclusion' -Path $excludedPath `
                -Detail "$Rule exclusion no longer matches its production pattern"
        }
    }

    Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.java' | ForEach-Object {
        $relativePath = Get-RepositoryRelativePath -Path $_.FullName
        if ($ExcludedRelativePaths -contains $relativePath) {
            return
        }
        $lineNumber = 0
        foreach ($line in [System.IO.File]::ReadLines($_.FullName)) {
            $lineNumber++
            if ($line -match $Pattern) {
                Add-ArchitectureViolation -Rule $Rule -Path $_.FullName -Line $lineNumber -Detail $Detail
                break
            }
        }
    }
}

# 以源码包段冻结 domain 与 foundation 的最内边界；共享身份仅组合 Core domain 值和两个持久化身份键，
# 不开放 AgentStore 端口或其它 application 类型，正负边界由 Java AST 夹具同时覆盖。
function Find-InnerLayerBoundary {
    param(
        [Parameter(Mandatory)][string]$Root
    )

    $domainNames = @('conversation', 'configuration', 'workspace', 'catalog', 'plan')
    Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.java' | ForEach-Object {
        $file = $_
        $relative = [System.IO.Path]::GetRelativePath($Root, $file.FullName).Replace('\', '/')
        $sourceKind = if ($relative -match '^(?<domain>conversation|configuration|workspace|catalog|plan)/domain/') {
            "domain:$($Matches.domain)"
        } elseif ($relative -match '^foundation/') {
            'foundation'
        } else {
            return
        }

        $lineNumber = 0
        foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
            $lineNumber++
            if ($line -notmatch '^\s*import\s+(?:static\s+)?(?<dependency>[A-Za-z_][A-Za-z0-9_.]*)(?:;|\s)') {
                continue
            }
            $dependency = $Matches.dependency
            $allowed = ($dependency.StartsWith('java.', [StringComparison]::Ordinal) `
                -or $dependency.StartsWith('javax.', [StringComparison]::Ordinal))
            if ($sourceKind -eq 'foundation') {
                $allowed = $allowed -or $dependency -eq 'io.github.kongweiguang.ja.foundation'
                $allowed = $allowed -or $dependency.StartsWith('io.github.kongweiguang.ja.foundation.', [StringComparison]::Ordinal)
                $allowed = $allowed -or $dependency -eq 'io.github.kongweiguang.ja.agent.domain'
                $allowed = $allowed -or $dependency.StartsWith('io.github.kongweiguang.ja.agent.domain.', [StringComparison]::Ordinal)
                $allowed = $allowed -or $dependency -eq 'io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey'
                $allowed = $allowed -or $dependency -eq 'io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionKey'
                if (-not $allowed) {
                    Add-ArchitectureViolation -Rule 'foundation-public-tool-boundary' -Path $file.FullName -Line $lineNumber `
                        -Detail 'foundation 只能依赖 JDK、foundation、Core domain 与会话/执行身份键'
                }
                continue
            }

            $domain = $sourceKind.Substring($sourceKind.IndexOf(':') + 1)
            $sameDomain = "io.github.kongweiguang.ja.$domain.domain."
            $allowed = $allowed -or $dependency.StartsWith($sameDomain, [StringComparison]::Ordinal)
            $allowed = $allowed -or $dependency -eq 'io.github.kongweiguang.ja.foundation'
            $allowed = $allowed -or $dependency.StartsWith('io.github.kongweiguang.ja.foundation.', [StringComparison]::Ordinal)
            $allowed = $allowed -or $dependency -eq 'io.github.kongweiguang.ja.agent.domain'
            $allowed = $allowed -or $dependency.StartsWith('io.github.kongweiguang.ja.agent.domain.', [StringComparison]::Ordinal)
            if (-not $allowed -and $dependency.StartsWith('io.github.kongweiguang.ja.', [StringComparison]::Ordinal)) {
                $dependencyDomain = $dependency.Substring('io.github.kongweiguang.ja.'.Length).Split('.')[0]
                $rule = if ($domainNames -contains $dependencyDomain -and $dependencyDomain -ne $domain) {
                    'cross-domain-boundary'
                } else {
                    'domain-layer-boundary'
                }
                Add-ArchitectureViolation -Rule $rule -Path $file.FullName -Line $lineNumber `
                        -Detail 'domain 只能依赖 JDK、foundation 与本域 domain'
            }
        }
    }
}

# 标准业务上下文固定 interfaces/application/domain/infrastructure/bootstrap；跨域类型只可由消费者 gateway 合同或本域 ACL 转成公开 API。
function Find-StandardLayerAndAclBoundary {
    param(
        [Parameter(Mandatory)][string]$Root
    )

    $domains = @('conversation', 'configuration', 'workspace', 'catalog', 'plan', 'attachment', 'collaboration')
    $standardLayers = @('interfaces', 'application', 'domain', 'infrastructure', 'bootstrap')
    $domainPattern = ($domains -join '|')
    Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.java' | ForEach-Object {
        $file = $_
        $sourcePackage = $null
        $lineNumber = 0
        foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
            $lineNumber++
            if ($line -match '^\s*package\s+(?<package>[A-Za-z_][A-Za-z0-9_.]*);') {
                $sourcePackage = $Matches.package
                if ($sourcePackage -match "^io\.github\.kongweiguang\.ja\.(?<businessDomain>$domainPattern)\.(?<remainder>[^;]+)$") {
                    $layer = $Matches.remainder.Split('.')[0]
                    if ($standardLayers -notcontains $layer) {
                        Add-ArchitectureViolation -Rule 'standard-layer-package' -Path $file.FullName -Line $lineNumber `
                            -Detail 'business contexts must use interfaces/application/domain/infrastructure/bootstrap package layers'
                    }
                }
                if ($sourcePackage -match '^io\.github\.kongweiguang\.ja\.(task|goal)(\.|$)') {
                    Add-ArchitectureViolation -Rule 'removed-task-goal-package' -Path $file.FullName -Line $lineNumber `
                        -Detail 'removed task/Goal package is present in production source'
                }
                continue
            }
            if ($line -notmatch '^\s*import\s+(?:static\s+)?(?<dependency>[A-Za-z_][A-Za-z0-9_.]*)(?:;|\s)') {
                continue
            }
            $dependency = $Matches.dependency
            if ($dependency -match '^io\.github\.kongweiguang\.ja\.(task|goal)(\.|$)') {
                Add-ArchitectureViolation -Rule 'removed-task-goal-package' -Path $file.FullName -Line $lineNumber `
                    -Detail 'removed task/Goal package is imported by production source'
                continue
            }
            if ($null -eq $sourcePackage -or $sourcePackage -notmatch "^io\.github\.kongweiguang\.ja\.(?<sourceDomain>$domainPattern)(\.|$)") {
                continue
            }
            $sourceDomain = $Matches.sourceDomain
            if ($dependency -notmatch "^io\.github\.kongweiguang\.ja\.(?<dependencyDomain>$domainPattern)(\.|$)") {
                continue
            }
            $dependencyDomain = $Matches.dependencyDomain
            if ($dependencyDomain -eq $sourceDomain) {
                continue
            }
            $compositionRoot = $sourcePackage -match '^io\.github\.kongweiguang\.ja\.(bootstrap|transport|infrastructure|platform)(\.|$)'
            $acl = $sourcePackage -match '\.infrastructure\.acl(\.|$)'
            $publicApi = $dependency -match '\.(port\.in|port\.out|interfaces|application\.(api|gateway))(\.|$)'
            $consumerGateway = $sourcePackage -match '\.application\.(api|gateway)(\.|$)'
            $publicContract = $dependency -match '\.(application\.(api|gateway)|domain\.model)(\.|$)'
            # 适配器和 ACL 的职责就是把提供方公开契约转换成消费者模型；只要依赖的是
            # application.api/gateway 或 domain.model，就不需要为了一层类型转换再造无意义的 facade。
            # application.api/application.gateway/domain.model 是上下文明确发布的不可变语言。
            # 应用编排可以消费这类契约；实现类、技术包和 admission 注册表仍必须走消费者自己的
            # Gateway/ACL。这样门禁约束依赖方向，同时不迫使跨域 DTO 为了形式再包一层空壳。
            $adapterBoundary = ($sourcePackage -match '\.(infrastructure|interfaces)(\.|$)') -and $publicContract
            $publicValueBoundary = $publicContract -and ($sourcePackage -notmatch '\.domain(\.|$)')
            # admission 是 Conversation 对运行时事务协调器公布的专用应用合同；它只允许被
            # 组合根和 ACL 消费，禁止业务 application service 直接依赖该临时注册表。
            $admissionBoundary = ($dependency -match '\.application\.admission\.') -and
                ($sourcePackage -match '\.(bootstrap|infrastructure)(\.|$)')
            $allowedAclBoundary = ($acl -and $publicApi) -or ($adapterBoundary) -or $publicValueBoundary -or
                ($consumerGateway -and $publicContract) -or $admissionBoundary
            if (-not $compositionRoot -and -not $allowedAclBoundary) {
                Add-ArchitectureViolation -Rule 'cross-domain-acl' -Path $file.FullName -Line $lineNumber `
                    -Detail 'cross-domain dependency must use a consumer application gateway contract or infrastructure.acl'
            }
        }
    }
}

# Example 是独立 Spring 组合根；其 application/domain 只能依赖本域合同、领域模型和 Core
# 公开 domain/API，不能把 Core 的 persistence/json 实现或 example 的 adapter/infrastructure
# 反向带入用例和领域。附件是独立业务上下文，identity 只承载可替换主体入口；
# adapter 与 bootstrap 才是持久化/Provider 技术实现的合法落点。
function Find-ExampleLayerAndDependencyBoundary {
    param(
        [Parameter(Mandatory)][string]$Root
    )

    $packagePrefix = 'io.github.kongweiguang.ja.agent.example.springboot'
    $contexts = @('agent', 'conversation', 'experience', 'attachment')
    $topLevelPackages = @('bootstrap', 'identity')
    $layers = @('application', 'domain', 'port', 'adapter', 'infrastructure', 'bootstrap')
    Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.java' | ForEach-Object {
        $file = $_
        $sourcePackage = $null
        $sourceLayer = $null
        $sourceContext = $null
        $lineNumber = 0
        foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
            $lineNumber++
            if ($line -match '^\s*package\s+(?<package>[A-Za-z_][A-Za-z0-9_.]*);') {
                $sourcePackage = $Matches.package
                $remainder = if ($sourcePackage.StartsWith($packagePrefix + '.', [StringComparison]::Ordinal)) {
                    $sourcePackage.Substring(($packagePrefix + '.').Length)
                } else { $null }
                if ($null -ne $remainder) {
                    $segments = $remainder.Split('.')
                    $sourceContext = $segments[0]
                    if ($contexts -contains $sourceContext -and $segments.Count -gt 1) {
                        $sourceLayer = $segments[1]
                        if ($layers -notcontains $sourceLayer) {
                            Add-ArchitectureViolation -Rule 'example-layer-package' -Path $file.FullName -Line $lineNumber `
                                -Detail 'example context must use application/domain/port/adapter/infrastructure/bootstrap layers'
                        }
                    } elseif (($contexts -notcontains $sourceContext) -and ($topLevelPackages -notcontains $sourceContext)) {
                        Add-ArchitectureViolation -Rule 'example-context-package' -Path $file.FullName -Line $lineNumber `
                            -Detail 'example production package must belong to agent, conversation, experience or attachment context, or the identity/composition boundary'
                    }
                }
                continue
            }
            if ($line -notmatch '^\s*import\s+(?:static\s+)?(?<dependency>[A-Za-z_][A-Za-z0-9_.]*)(?:;|\s)') {
                continue
            }
            $dependency = $Matches.dependency
            if ($null -eq $sourceLayer -or ($sourceLayer -ne 'application' -and $sourceLayer -ne 'domain')) {
                continue
            }
            $forbiddenCoreInfrastructure = $dependency -match '^io\.github\.kongweiguang\.ja\.agent\.infrastructure(?:\.|$)'
            $forbiddenExampleImplementation = $dependency -match '^' + [Regex]::Escape($packagePrefix) + '\.(?:agent|conversation|experience|attachment)\.(?:adapter|infrastructure)(?:\.|$)'
            if ($forbiddenCoreInfrastructure -or $forbiddenExampleImplementation) {
                Add-ArchitectureViolation -Rule 'example-inner-layer-boundary' -Path $file.FullName -Line $lineNumber `
                    -Detail 'example application/domain must not depend on Core infrastructure or example adapter/infrastructure implementations'
            }
            if ($sourceLayer -eq 'domain' -and $dependency -match '^(org\.|jakarta\.|javax\.sql|java\.sql)') {
                Add-ArchitectureViolation -Rule 'example-domain-purity' -Path $file.FullName -Line $lineNumber `
                    -Detail 'example domain must remain free of framework and database dependencies'
            }
        }
    }
}

if (-not (Test-Path -LiteralPath $javaRoot -PathType Container)) {
    throw 'Java production source root is missing.'
}

$reactorRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'ja-agent'))
$visitedModules = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$moduleRoots = @(Get-ReactorModuleRoots -PomPath (Join-Path $reactorRoot 'pom.xml') `
    -ReactorRoot $reactorRoot -Visited $visitedModules)
$productionRoots = @($moduleRoots | ForEach-Object {
    $sourceRoot = Join-Path $_ 'src/main/java'
    if (Test-Path -LiteralPath $sourceRoot -PathType Container) { $sourceRoot }
})
$productionFiles = @($productionRoots | ForEach-Object {
    Get-ChildItem -LiteralPath $_ -Recurse -File -Filter '*.java' | Sort-Object FullName
})
foreach ($file in $productionFiles) {
    $head = (Get-Content -LiteralPath $file.FullName -TotalCount 12) -join "`n"
    if ($head -notmatch '@author\s+kongweiguang') {
        Add-ArchitectureViolation -Rule 'author' -Path $file.FullName -Detail 'production source is missing @author kongweiguang'
    }

    $lineCount = ([System.IO.File]::ReadLines($file.FullName) | Measure-Object).Count
    if ($lineCount -gt 500) {
        Add-ArchitectureObservation -Rule 'responsibility-size-review' -Path $file.FullName -Line $lineCount -Detail 'production source exceeds the review threshold; inspect responsibilities instead of treating line count as an automatic architecture failure'
    }
}

$agentRoot = Join-Path $repositoryRoot 'ja-agent/ja-agent-core/src/main/java/io/github/kongweiguang/ja/agent'
# 扩展注册合同已归 application.gateway，扫描整个 application 保持相同的内层依赖约束。
foreach ($layer in @('application', 'domain')) {
    $layerRoot = Join-Path $agentRoot $layer
    Find-ProductionPattern -Rule 'agent-dependency' -Root $layerRoot -Pattern '^\s*import\s+(static\s+)?(com\.fasterxml|tools\.jackson|okhttp3|org\.apache\.ibatis|org\.noear\.solon|org\.springframework|io\.modelcontextprotocol|io\.github\.kongweiguang\.ja\.agent\.infrastructure)' -Detail 'core inner layer imports a framework or infrastructure implementation'
}
$agentDomainRoot = Join-Path $agentRoot 'domain'
Find-ProductionPattern -Rule 'domain-shared-dependency' -Root $agentDomainRoot `
    -Pattern '^\s*import\s+io\.github\.kongweiguang\.ja\.agent\.shared\.' `
    -Detail 'Agent domain must not depend on shared implementation helpers'

$appServerJavaRoot = Join-Path $repositoryRoot 'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja'
if (Test-Path -LiteralPath $appServerJavaRoot -PathType Container) {
    Find-InnerLayerBoundary -Root $appServerJavaRoot
    Find-StandardLayerAndAclBoundary -Root $appServerJavaRoot
} else {
    Add-ArchitectureViolation -Rule 'missing-app-server-source' -Path $appServerJavaRoot -Detail 'App Server Java source root is missing'
}

$exampleJavaRoots = @($productionRoots | Where-Object {
    $relative = [System.IO.Path]::GetRelativePath($reactorRoot, $_).Replace('\', '/')
    $relative -match '^example(?:/|$)'
})
if ($exampleJavaRoots.Count -eq 0) {
    Add-ArchitectureViolation -Rule 'missing-example-source' -Path (Join-Path $reactorRoot 'example') `
        -Detail 'Java example production source root is missing'
} else {
    foreach ($exampleJavaRoot in $exampleJavaRoots) {
        Find-ExampleLayerAndDependencyBoundary -Root $exampleJavaRoot
    }
}

foreach ($sourceRoot in $productionRoots) {
    # MCP SDK 自有传输使用 HttpRequest.Builder 组装请求头；只拦截业务代码自行创建 JDK HTTP 客户端。
    Find-ProductionPattern -Rule 'jdk-http' -Root $sourceRoot -Pattern '\bHttpClient\.new(HttpClient|Builder)\b' -Detail 'application code must not create an independent JDK HTTP client'
    Find-ProductionPattern -Rule 'deferred-work' -Root $sourceRoot -Pattern '\b(TODO|FIXME|HACK|XXX)\b' -Detail 'deferred implementation marker is forbidden in final production source'
    # 历史数据库迁移可识别旧表，但生产包依赖和 JA-RPC 方法不能重新引入已移除的 Goal 能力。
    Find-ProductionPattern -Rule 'removed-goal-surface' -Root $sourceRoot -Pattern '^\s*(package|import)\s+io\.github\.kongweiguang\.ja\.goal(\.|;)|"goal/[a-z]' -Detail 'removed Goal package or JA-RPC method is present in production source'
    Find-ProductionPattern -Rule 'removed-task-goal-package' -Root $sourceRoot -Pattern '^\s*(package|import)\s+io\.github\.kongweiguang\.ja\.(task|goal)(\.|;)' -Detail 'removed task/Goal package is present in production source'
}
# 仅放行启动完整性/备份及复用 AgentStore 同一事务连接的投影参与者与其拆分协作者；业务 Repository 仍强制使用 MyBatis。
Find-ProductionPattern -Rule 'direct-jdbc' -Pattern '\bDriverManager\b|\.prepareStatement\(|\.createStatement\(|\bResultSet\s+[A-Za-z_][A-Za-z0-9_]*\s*=' -Detail 'production persistence must use MyBatis mappers rather than manual JDBC execution or row mapping' -ExcludedRelativePaths @(
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/database/JaDatabase.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/database/JaSchemaContract.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaAdmissionParticipantFactory.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaConversationParticipant.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaInputProjection.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaArtifactProjection.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaInteractionProjection.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaMailboxProjection.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaMessageProjection.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaProjectionJdbc.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaToolProjection.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaTransactionParticipant.java',
    'ja-agent/ja-app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/store/JaTurnAdmissionRows.java'
)
Find-ProductionPattern -Rule 'obsolete-runtime' -Pattern '\b(StrictSseParser|RoutingModelPort|SingleWriter|SqliteSessionRepository|SqliteChangeStore|KernelRuntimeGraph|AtomicWorkspaceFiles)\b' -Detail 'obsolete production implementation remains reachable'
Find-ProductionPattern -Rule 'obsolete-wire' -Pattern '"(profile/(save|activate|read)|mcp/(save|delete)|change/revertSet|secret/resolve|diagnostics/read)"' -Detail 'removed first-release wire method remains in production source'

$sourceManifest = @($moduleRoots | ForEach-Object {
    $moduleRoot = $_
    foreach ($sourceSet in @('src/main', 'src/native', 'src/test')) {
        $sourceRoot = Join-Path $moduleRoot $sourceSet
        if (Test-Path -LiteralPath $sourceRoot -PathType Container) {
            Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | Sort-Object FullName | ForEach-Object {
                [ordered]@{
                    path = Get-RepositoryRelativePath -Path $_.FullName
                    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                }
            }
        }
    }
})

$result = [ordered]@{
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    productionFiles = $productionFiles.Count
    modules = @($moduleRoots | ForEach-Object { Get-RepositoryRelativePath -Path $_ })
    sourceManifest = $sourceManifest
    violationCount = $violations.Count
    observationCount = $observations.Count
    passed = $violations.Count -eq 0
    violations = @($violations)
    observations = @($observations)
}

$json = $result | ConvertTo-Json -Depth 8
if ($OutputPath) {
    # 保留调用方显式指定的绝对证据路径；与仓库根拼接会生成错误的嵌套路径，
    # 使本次新生成的架构结果无法与证据缺失区分。
    $resolvedOutput = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
        [System.IO.Path]::GetFullPath($OutputPath)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputPath))
    }
    $outputDirectory = Split-Path -Parent $resolvedOutput
    [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    [System.IO.File]::WriteAllText($resolvedOutput, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}
$json
if ($violations.Count -ne 0) {
    exit 1
}
