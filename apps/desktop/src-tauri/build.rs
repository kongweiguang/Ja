// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use std::fs;

/// 显式跟踪每个暂存的伴随进程文件，因为 Tauri 的目录资源声明不会让 Cargo 在已有二进制文件被原位替换时重新运行此构建脚本。
fn track_staged_sidecars() {
    println!("cargo:rerun-if-changed=sidecar-manifest.json");
    let Ok(entries) = fs::read_dir("sidecars") else {
        println!("cargo:rerun-if-changed=sidecars");
        return;
    };
    for entry in entries.flatten() {
        if entry.file_type().is_ok_and(|file_type| file_type.is_file()) {
            println!("cargo:rerun-if-changed={}", entry.path().display());
        }
    }
}

/// 构建时让 Tauri 生成平台上下文，同时将增量构建绑定到要复制到开发目标目录的确切 Native Image 输入。
fn main() {
    track_staged_sidecars();
    tauri_build::build()
}
