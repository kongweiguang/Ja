<!-- @author kongweiguang -->

# Tool Catalog WebView2 Fixture

`tool-catalog.mjs` 是本轮内置工具目录的隔离 IPv4 loopback Provider。它按真实
`function_call` / `function_call_output` continuation 回放以下路径：

1. `grep` 使用空 `query`，验证稳定的 `tool_arguments_invalid` 字段错误；
2. `grep` 使用 `JA_TOOL_CATALOG_NEEDLE` 纠正成功；
3. `find` 只返回 `catalog/` 下匹配文件的相对路径；
4. `ls` 只列出当前目录的一层；
5. 返回最终答复。

runner 在本轮随机临时 workspace 内生成 `catalog/root.txt`、
`catalog/nested/nested.txt` 和 `catalog/nested/deep/deep.txt`。后两个文件包含仅用于断言的
正文 marker；`find` 和 `ls` 的结果不得包含这些 marker。fixture 不保存完整 Provider 请求，
不连接真实或付费 Provider。

从仓库根目录运行真实验收（需要已构建的 JDK 25 debug JAR）：

```powershell
node scripts/e2e/tool-catalog-webview2.mjs `
  --evidence-directory .tmp/tool-catalog-evidence `
  --jar .tmp/ja-app-server.jar
```

runner 使用隐藏的隔离 Tauri/WebView2 窗口，并在结束时只清理自己创建的临时进程、profile 和
workspace。`node --test scripts/e2e/tool-catalog-webview2.test.mjs` 只检查 fixture 协议和报告
验证器，不替代真实桌面验收。
