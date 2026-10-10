// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::time::{SystemTime, UNIX_EPOCH};

/// 参数拒绝使用真实可执行文件，验证用户看到的退出码并确保没有触及运行时 Home。
fn run_cli(arguments: &[String], home: &Path) -> Output {
    Command::new(env!("CARGO_BIN_EXE_ja"))
        .args(arguments)
        .env("JA_HOME", home)
        .output()
        .expect("launch ja")
}

/// 每次测试使用独立且不存在的目录，避免把 CLI 解析错误与后台状态混在一起。
fn unused_home() -> PathBuf {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_nanos();
    std::env::temp_dir().join(format!("ja-cli-args-{}-{stamp}", std::process::id()))
}

/// 管理面板先检查交互终端；脚本误调用不得启动后台或创建用户配置。
#[test]
fn setup_requires_terminal_before_initializing_runtime() {
    let home = unused_home();
    let output = run_cli(&["setup".into()], &home);
    assert_eq!(output.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&output.stderr).contains("需要交互终端"));
    assert!(!home.exists());
}

/// 新增 headless 参数的本地错误必须在解析阶段拒绝，避免无效调用创建 Thread。
#[test]
fn exec_rejects_invalid_options_before_initializing_runtime() {
    let home = unused_home();
    let cases = [
        (
            vec!["exec", "--json", "--json", "task"],
            "--json 只能指定一次",
        ),
        (vec!["exec", "--model"], "--model 需要模型ID"),
        (vec!["exec", "--model", "   ", "task"], "--model 需要模型ID"),
        (
            vec!["exec", "--permissions", "read-only", "task"],
            "仅支持 approval-required 或 full-access",
        ),
        (
            vec!["exec", "--output-schema", "", "task"],
            "--output-schema 需要文件路径",
        ),
        (vec!["exec", "first", "second"], "任务文本只能是一个参数"),
    ];

    for (arguments, expected) in cases {
        let arguments = arguments.into_iter().map(str::to_owned).collect::<Vec<_>>();
        let output = run_cli(&arguments, &home);
        assert_eq!(output.status.code(), Some(2), "{arguments:?}");
        assert!(
            String::from_utf8_lossy(&output.stderr).contains(expected),
            "{arguments:?}: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    assert!(
        !home.exists(),
        "invalid arguments must not initialize Ja Home"
    );
}

/// JSON 模式在 schema 读取失败时仍返回完整 v2 错误对象，且不启动 App Server。
#[test]
fn exec_schema_error_has_stable_json_shape_without_initializing_runtime() {
    let home = unused_home();
    let schema = unused_home().with_extension("schema.json");
    fs::write(&schema, b"[]").expect("write invalid schema fixture");
    let arguments = [
        "exec".to_owned(),
        "--json".to_owned(),
        "--output-schema".to_owned(),
        schema.to_string_lossy().into_owned(),
        "task".to_owned(),
    ];

    let output = run_cli(&arguments, &home);
    assert_eq!(output.status.code(), Some(2));
    let record: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("parse JSONL error record");
    assert_eq!(
        record,
        serde_json::json!({
            "version": 2,
            "type": "error",
            "serverInstanceId": null,
            "threadId": null,
            "turnId": null,
            "code": null,
            "errorCode": null,
            "message": "终答 Schema 根节点必须是 JSON object",
            "exitCode": 2,
            "nextAction": null
        })
    );
    assert!(!home.exists(), "schema errors must not initialize Ja Home");
    fs::remove_file(schema).expect("remove invalid schema fixture");
}

/// resume、queue 与 review 的互斥目标和必填值在建立后台连接前固定拒绝。
#[test]
fn session_commands_reject_ambiguous_targets_before_initializing_runtime() {
    let home = unused_home();
    let cases = [
        (vec!["resume", "--last", "--all"], "resume 只能指定一个目标"),
        (
            vec!["resume", "thread-a", "thread-b"],
            "resume 只能指定一个 thread-id 或目标选项",
        ),
        (vec!["resume", "   "], "resume thread-id 不能为空"),
        (vec!["queue", "thread-a"], "queue 需要内容"),
        (vec!["queue", "   ", "text"], "queue 需要 thread-id"),
        (
            vec!["queue", "thread-a", "--kind", "steering"],
            "queue 需要内容",
        ),
        (
            vec!["queue", "thread-a", "--turn-id", "turn-a", "text"],
            "--turn-id 与 --operation-key 必须同时指定",
        ),
        (
            vec![
                "queue",
                "thread-a",
                "--turn-id",
                "turn-a",
                "--operation-key",
                "invalid",
                "text",
            ],
            "--operation-key 格式必须为 op_ 加 32 位小写十六进制",
        ),
        (
            vec!["export-answer", "thread-a", "turn-a"],
            "export-answer 需要输出文件",
        ),
        (
            vec!["review", "--base", "main", "--commit", "abc123"],
            "review 只能指定一种来源",
        ),
        (vec!["review", "--base"], "--base 需要 ref"),
    ];

    for (arguments, expected) in cases {
        let arguments = arguments.into_iter().map(str::to_owned).collect::<Vec<_>>();
        let output = run_cli(&arguments, &home);
        assert_eq!(output.status.code(), Some(2), "{arguments:?}");
        assert!(
            String::from_utf8_lossy(&output.stderr).contains(expected),
            "{arguments:?}: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    assert!(
        !home.exists(),
        "invalid arguments must not initialize Ja Home"
    );
}

/// 多图输入的上限在 argv 层立即执行，阻止无界附件导入与部分后台副作用。
#[test]
fn exec_rejects_too_many_images_before_initializing_runtime() {
    let home = unused_home();
    let mut arguments = vec!["exec".to_owned()];
    for index in 0..11 {
        arguments.extend(["--image".to_owned(), format!("image-{index}.png")]);
    }
    arguments.push("task".to_owned());

    let output = run_cli(&arguments, &home);
    assert_eq!(output.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&output.stderr).contains("最多添加 10 张图片"));
    assert!(
        !home.exists(),
        "invalid arguments must not initialize Ja Home"
    );
}

/// 每条命令帮助都必须由真实 CLI binary 成功展示，且不能创建 Home 或连接 App Server。
#[test]
fn command_help_short_circuits_before_runtime_initialization() {
    let home = unused_home();
    let cases = [
        (vec!["--help"], "--output-last-message"),
        (vec!["exec", "--help"], "JSONL v2"),
        (vec!["exec", "-h"], "--output-schema"),
        (vec!["resume", "--help"], "新的用户 Turn"),
        (vec!["resume", "-h"], "thread-id"),
        (vec!["queue", "--help"], "follow_up"),
        (vec!["queue", "thread-a", "-h"], "--operation-key"),
        (vec!["export-answer", "--help"], "不创建 Thread 或 Turn"),
        (vec!["review", "--help"], "--uncommitted"),
        (vec!["review", "-h"], "差异来源"),
    ];

    for (arguments, expected) in cases {
        let arguments = arguments.into_iter().map(str::to_owned).collect::<Vec<_>>();
        let output = run_cli(&arguments, &home);
        assert!(
            output.status.success(),
            "{arguments:?}: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(
            String::from_utf8_lossy(&output.stdout).contains(expected),
            "{arguments:?}: {}",
            String::from_utf8_lossy(&output.stdout)
        );
    }
    assert!(!home.exists(), "help must not initialize Ja Home");
}
