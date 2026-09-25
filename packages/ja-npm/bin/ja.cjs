#!/usr/bin/env node
// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* eslint-disable @typescript-eslint/no-require-imports -- npm bin ships as CommonJS for Node. */

"use strict";

const { spawn } = require("node:child_process");
const path = require("node:path");

/** npm 入口只选择经过打包验证的同平台 Rust 程序，不读取 PATH 中的同名文件。 */
function nativeExecutable() {
  if (process.platform !== "win32" || process.arch !== "x64") {
    throw new Error(`Ja npm package currently supports Windows x64 only (${process.platform}/${process.arch})`);
  }
  return path.resolve(__dirname, "..", "runtime", "bin", "ja.exe");
}

/** 保持标准流直通 TUI，同时让 npm 启动器的退出状态忠实反映原生程序。 */
function run() {
  let executable;
  try {
    executable = nativeExecutable();
  } catch (error) {
    console.error(`Ja: ${error.message}`);
    process.exitCode = 4;
    return;
  }
  const child = spawn(executable, process.argv.slice(2), {
    stdio: "inherit",
    windowsHide: false,
    shell: false,
    env: process.env,
  });
  let spawnFailed = false;
  child.once("error", (error) => {
    spawnFailed = true;
    console.error(`Ja: 无法启动原生程序：${error.message}`);
    process.exitCode = 4;
  });
  child.once("close", (code, signal) => {
    if (!spawnFailed) process.exitCode = code ?? (signal === "SIGINT" ? 130 : 1);
  });
  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.on(signal, () => {
      if (!child.killed) child.kill(signal);
    });
  }
}

run();
