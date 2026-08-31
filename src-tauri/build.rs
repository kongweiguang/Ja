// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use std::fs;

/// Tracks every staged sidecar file explicitly because Tauri's directory resource declaration
/// does not make Cargo rerun this build script when an existing binary is replaced in place.
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

/// Lets Tauri generate platform context during the build while also binding incremental builds
/// to the exact staged Native Image inputs that will be copied into the development target.
fn main() {
    track_staged_sidecars();
    tauri_build::build()
}
