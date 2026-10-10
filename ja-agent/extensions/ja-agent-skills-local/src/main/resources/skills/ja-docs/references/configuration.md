<!-- @author kongweiguang -->

# Ja 配置参考

## 文件与覆盖顺序

默认 Ja Home 为 `~/.ja`（Windows 为 `%USERPROFILE%\.ja`）；用户配置是其中的 `config.toml`。可信项目可在 `<workspace>/.ja/config.toml` 提供稀疏覆盖。生效顺序为内置默认值、用户配置、可信项目配置。普通对象逐字段合并，Provider、模型和 MCP 分别按稳定的 `provider_id`、`model_id`、`mcp_id` 合并。项目未写的字段继承用户值；显示名称不是身份。

格式为 TOML，当前规范化文档是 `schema_version = 2`。读取时允许省略版本字段；设置保存可能写出版本 2。不要为调整一个字段而生成整份项目配置。桌面与 CLI 设置保存会检查版本并按稳定 ID 合并目标改动，保留未触及的未知字段与坏条目；直接编辑文件绕过该并发检查，务必先读取现有内容并在 Ja 中回读生效结果。CLI 使用 `ja setup` 管理全局模型，`/model` 切换会话模型或进入管理。

凭据、Workspace 信任、数据库位置和宿主启动参数只归用户/宿主，不接受项目覆盖。`auth.json` 保存凭据，配置中只存 `credential_id` 引用；不要读取、打印或手工编辑 `auth.json`。

## 根字段

| 字段                                      | 含义                                                                                                         |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `default_access_mode`                     | `full_access` 或 `approval_required`，用于新会话默认执行确认模式。                                           |
| `default_provider_id`、`default_model_id` | 默认连接和模型的稳定 ID。                                                                                    |
| `default_reasoning_level`                 | 模型支持的 `off`、`minimal`、`low`、`medium`、`high`、`xhigh`、`max` 之一。                                  |
| `disabled_skills`                         | 按来源限定的停用列表。用户层支持 `user:名称`、`ja:名称`；项目层还支持 `project:名称`。不能用它停用内置来源。 |
| `[interaction] clarification_enabled`     | 普通对话是否允许 Agent 在确实缺少必要信息时提问。                                                            |
| `[subagents]`                             | `enabled`、`provider_id`、`model_id`、`reasoning_level`；空模型选择表示跟随父任务。                          |

TOML 不能直接表示 null，Ja 在需要显式保存空值时使用 `{ __ja_null = true }`。项目层字段缺失表示继承。项目若显式设置 `disabled_skills`，整张列表替换用户层列表，包括空列表。不要把用户全量文档复制进项目。

一个只改项目权限的示例：

```toml
schema_version = 2
default_access_mode = "approval_required"
```

## Provider 与模型

每个 `[[providers]]` 包含 `provider_id`、`name`、`api`、`base_url`、`credential_id`、`network_timeouts`、`agent_defaults` 和至少一个 `models`。`api` 只支持 `openai_responses`、`anthropic_messages`、`openai_chat_completions`。`base_url` 是不含用户名密码、查询参数和片段的 HTTP(S) URL。每个 Provider 使用独立凭据引用，密钥须通过 Ja 桌面设置或 CLI 模型设置保存。

每个 `[[providers.models]]` 包含稳定 `model_id`、显示用 `name`、真实上游 `model`、`reasoning_level_map`、`default_reasoning_level`，以及 `capabilities.context_window_tokens` 和 `capabilities.max_output_tokens`。实际请求使用上游 `model`，不能拿 Provider 名或显示别名充当模型标识。`network_timeouts.connect_timeout_ms` 和 `request_timeout_ms` 单位为毫秒；`agent_defaults.context.auto_compact` 是布尔值。

以下是用户配置中一个 Provider 的字段形状，不是可直接连接的服务；实际 `base_url`、上游模型名及凭据引用须与用户的服务一致，`cred_example` 对应的密钥必须先通过 Ja 保存：

```toml
[[providers]]
provider_id = "provider_example"
name = "示例服务"
api = "openai_responses"
base_url = "https://api.example.com"
credential_id = "cred_example"

[providers.network_timeouts]
connect_timeout_ms = 10000
request_timeout_ms = 120000

[providers.agent_defaults.context]
auto_compact = true

[[providers.models]]
model_id = "model_example"
name = "示例模型"
model = "example-model"
reasoning_level_map = { medium = "medium" }
default_reasoning_level = "medium"

[providers.models.capabilities]
context_window_tokens = 128000
max_output_tokens = 8192
```

可信项目可稀疏覆盖已有 Provider 与模型。若更换连接目标，须一并显式提供合法的 API 规范、地址和已保存的凭据引用；项目文件不能创建或保存密钥。删除仍被默认设置引用的模型或 Provider 时，先在 Ja 的确认流程选择替代项。

## MCP 服务

`[[mcp_servers]]` 使用 `mcp_id`、`name`、`transport`、`endpoint`、`args`、`env`、`headers`、`auth` 和 `enabled`。`transport` 为 `stdio` 或 `streamable_http`。`auth` 可以是 `{ kind = "none" }`；`bearer`、`env`、`header` 方式还需 `credential_id`，后两种还需 `name`。密钥不要直接写入 URL、`env`、`headers` 或 `auth`。可信项目可用相同 `mcp_id` 覆盖全局定义；服务保存成功不等于连接或工具调用成功。
