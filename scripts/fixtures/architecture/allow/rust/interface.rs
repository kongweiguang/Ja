// @author kongweiguang
// 允许：Tauri command 位于精确 interface 层。
#[tauri::command]
/// 为什么只在 interface 暴露 command：领域层不应获得桌面运行时能力。
fn execute() {}
