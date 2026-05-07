//! `dummy_b` — second UniFFI scaffolding crate for bindgen fixtures.
//!
//! Pair to `dummy_a`. Emits `ffi_dummy_b_uniffi_contract_version` so the
//! fixture has at least two distinct UniFFI namespaces, which is the
//! minimum interesting input for a crate-allowlist auto-deriver.

uniffi::setup_scaffolding!("dummy_b");
