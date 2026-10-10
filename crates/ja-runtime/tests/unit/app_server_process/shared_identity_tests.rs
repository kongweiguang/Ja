// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::NativeClientIdentity;

/// Host token must be fresh per logical lifetime, clone-stable for reconnects, and absent from Debug output.
#[test]
fn native_client_identity_is_private_and_lifetime_scoped() {
    let first = NativeClientIdentity::new_current_process().expect("native identity");
    let reconnect = first.clone();
    let next_lifetime = NativeClientIdentity::new_current_process().expect("next identity");

    assert_eq!(first, reconnect);
    assert_ne!(first, next_lifetime);
    let diagnostic = format!("{first:?}");
    assert!(!diagnostic.contains("native_"));
}
