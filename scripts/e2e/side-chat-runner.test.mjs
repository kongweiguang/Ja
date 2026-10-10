// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";
import {
  assertNormalExitSideChatsPurged,
  buildTauriEnvironment,
  writeTauriOverlay,
} from "./side-chat-runner.mjs";

/** 使用现行 V1 原始 DDL，确保退出验收会拒绝 OPEN/CLOSING 残留而接受持久 Thread。 */
test("normal side-chat exit requires target and all inline lifecycle markers to be removed", async () => {
  const database = new DatabaseSync(":memory:");
  try {
    const schema = await readFile(
      new URL("../../ja-agent/ja-app-server/src/main/resources/db/migration/V1__kernel.sql", import.meta.url),
      "utf8",
    );
    database.exec(schema);
    database.prepare(
      "INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,created_at_ms,updated_at_ms) " +
      "VALUES('workspace_fixture','C:/fixture','PROJECT','Fixture','TRUSTED',1,1)",
    ).run();
    const insert = database.prepare(
      "INSERT INTO ja_conversation_thread(thread_id,owner_user_id,temporary_lifecycle_state,workspace_id,title," +
      "created_at_ms,updated_at_ms,provider_id,model_id,access_mode,title_source) " +
      "VALUES(?,'user_local',?,'workspace_fixture','Fixture',1,1,'provider_fixture','model_fixture','FULL_ACCESS','MANUAL')",
    );
    insert.run("thread_persistent", null);
    assert.doesNotThrow(() => assertNormalExitSideChatsPurged(database, "thread_removed"));
    for (const state of ["OPEN", "CLOSING"]) {
      insert.run("thread_side", state);
      assert.throws(() => assertNormalExitSideChatsPurged(database, "thread_side"), /未清除/u);
      assert.throws(() => assertNormalExitSideChatsPurged(database, "thread_removed"), /未清除/u);
      database.prepare("DELETE FROM ja_conversation_thread WHERE thread_id='thread_side'").run();
    }
    assert.throws(() => assertNormalExitSideChatsPurged(database, "thread_persistent"), /未清除/u);
    assert.throws(() => assertNormalExitSideChatsPurged(database, ""), /缺少/u);
  } finally {
    database.close();
  }
});

/** 临时配置位置不同于仓库配置；Vite hook 必须仍从仓库根启动。 */
test("side-chat overlay anchors its Vite hook to the repository root", async () => {
  const runtimeRoot = await mkdtemp(join(tmpdir(), "ja-side-chat-overlay-"));
  try {
    const configPath = await writeTauriOverlay(runtimeRoot, 41_000);
    const config = JSON.parse(await readFile(configPath, "utf8"));
    const expectedRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

    assert.equal(config.build.devUrl, "http://127.0.0.1:41000");
    assert.deepEqual(config.build.beforeDevCommand, {
      script: "pnpm dev",
      cwd: expectedRoot,
    });
  } finally {
    await rm(runtimeRoot, { recursive: true, force: true });
  }
});

/** 校验 Windows 大小写不敏感的环境块只保留一个 PATH，并继续继承宿主工具搜索目录。 */
test("side-chat Tauri environment replaces the case-duplicate Windows Path key", () => {
  const java = String.raw`C:\jdks\liberica-25\bin\java.exe`;
  const inherited = {
    Path: String.raw`C:\tools\pnpm;C:\tools\cargo`,
    USERPROFILE: String.raw`C:\Users\host`,
    PSModuleAnalysisCachePath: String.raw`C:\Users\host\analysis.cache`,
  };
  const directories = {
    appdata: String.raw`C:\Temp\side-chat\appdata`,
    localappdata: String.raw`C:\Temp\side-chat\localappdata`,
    settings: String.raw`C:\Temp\side-chat\profile`,
    runtime: String.raw`C:\Temp\side-chat\runtime`,
    workspace: String.raw`C:\Temp\side-chat\workspace`,
    webview: String.raw`C:\Temp\side-chat\webview`,
  };

  const environment = buildTauriEnvironment({
    directories,
    frontendPort: 41_000,
    cdpPort: 41_001,
    java,
    jar: String.raw`C:\Temp\ja-app-server.jar`,
    targetDirectory: String.raw`C:\Temp\side-chat\cargo`,
    baseEnvironment: inherited,
  });

  assert.equal(environment.PATH, `${dirname(java)};${inherited.Path}`);
  assert.equal("Path" in environment, false);
  assert.equal(environment.TAURI_DEV_HOST, "127.0.0.1");
  assert.deepEqual(
    Object.keys(environment).filter((name) => name.toLowerCase() === "path"),
    ["PATH"],
  );
  assert.equal(environment.PSModuleAnalysisCachePath, inherited.PSModuleAnalysisCachePath);
  assert.equal(environment.WEBVIEW2_USER_DATA_FOLDER, directories.webview);
});
