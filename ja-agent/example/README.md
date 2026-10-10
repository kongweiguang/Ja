<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja Agent 接入示例

`ja-agent-example-springboot` 使用 Java 25、真实 PostgreSQL Store 和内置 Provider Adapter，
独立依赖 Core / PG，提供图片/PDF原生输入、自定义企业Middleware与同事务笔记归档，
以及MVC对话、Skills、HTTP MCP、原生审批/澄清、输入队列、恢复和压缩体验。
示例不读取 Ja 配置或用户凭据，不启动数据库。

## 准备与构建

准备 PostgreSQL 数据库和具备建表权限的用户。Store 和 Skill 来源初始化自己的 schema。
模型、上下文与 HTTP MCP 配置由示例的 PostgreSQL 配置表提供。

在仓库根目录执行，所有 Maven 命令须显式使用 JDK 25：

```powershell
$env:JAVA_HOME = 'C:\Users\24052\.jdks\liberica-25.0.2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn.cmd -version
mvn.cmd -B -ntp -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am package
```

## Spring Boot

独立 Spring MVC 对话示例，按 agent、identity、attachment、conversation、experience 业务边界组织 domain/application/port/adapter，
bootstrap 负责显式 Bean 装配。
身份统一由可替换的CurrentActorProvider提供；上传、输入、审批、保存、来源回看贯通同一Thread。
端口与数据源使用 application.yml；Agent 配置使用 PostgreSQL 表。
配置、代码入口、完整 HTTP 调用顺序与验证方法见 [Spring Boot 对话接入说明](ja-agent-example-springboot/README.md)。

## 验证

测试位于 `ja-agent-example-springboot/src/test`，验证对话、能力管理与真实 FastMCP HTTP 接入。
FastMCP 测试需要 uv 与已同步的 Python 依赖。
在仓库根目录、JDK 25 下运行：

```powershell
mvn.cmd -B -ntp -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am verify
```

实际通过范围以 [公开验证边界](../README.md#当前验证边界) 和当前测试运行结果为准。
