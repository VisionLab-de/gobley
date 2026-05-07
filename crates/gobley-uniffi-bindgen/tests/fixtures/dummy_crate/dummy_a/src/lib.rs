//! `dummy_a` — minimal UniFFI scaffolding crate for bindgen fixtures.
//!
//! Calling `uniffi::setup_scaffolding!()` emits the marker symbol
//! `ffi_dummy_a_uniffi_contract_version` plus the standard rustbuffer /
//! rust_future scaffolding. The marker is what `read_exports` uses to
//! auto-derive the bindgen crate allowlist.

uniffi::setup_scaffolding!("dummy_a");
