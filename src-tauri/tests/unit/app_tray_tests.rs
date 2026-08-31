// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::*;

/// 固定托盘菜单 id 解析，显示文案变化不得改变受信任动作集合。
#[test]
fn tray_menu_ids_only_accept_the_ja_namespace() {
    assert_eq!(TrayMenuAction::from_menu_id("ja:tray:show"), Some(TrayMenuAction::Show));
    assert_eq!(TrayMenuAction::from_menu_id("ja:tray:hide"), Some(TrayMenuAction::Hide));
    assert_eq!(TrayMenuAction::from_menu_id("ja:tray:quit"), Some(TrayMenuAction::Quit));
    assert_eq!(TrayMenuAction::from_menu_id("other:tray:quit"), None);
    assert_eq!(TrayMenuAction::from_menu_id("ja:tray:unknown"), None);
}

/// 验证退出握手 single-flight、renderer-ready 分支以及 commit/cancel 的重试边界。
#[test]
fn app_exit_coordinator_requires_one_pending_tray_request() {
    let coordinator = AppExitCoordinator::default();
    assert_eq!(coordinator.begin_exit(), ExitDispatch::Native);
    assert_eq!(coordinator.begin_exit(), ExitDispatch::AlreadyPending);
    assert!(coordinator.take_pending());
    assert!(!coordinator.take_pending());

    coordinator.set_renderer_ready(true);
    assert_eq!(coordinator.begin_exit(), ExitDispatch::Renderer);
    coordinator.cancel();
    assert_eq!(coordinator.begin_exit(), ExitDispatch::Renderer);
}
