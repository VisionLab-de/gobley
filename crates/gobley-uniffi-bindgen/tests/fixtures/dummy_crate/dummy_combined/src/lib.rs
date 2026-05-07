//! `dummy_combined` — cdylib that links `dummy_a` and `dummy_b`.
//!
//! This is the artefact we ship as the bindgen-bindgen fixture
//! (`dummy.wasm` / `.so` / `.dylib` / `.dll`). It exists purely to force the
//! linker to keep both crates' UniFFI scaffolding symbols in the dynamic
//! export table.
//!
//! `uniffi_reexport_scaffolding!()` plants a small `extern "C"` thunk that
//! references each leaf crate's reexport hack, which is what convinces
//! `rustc` (and downstream toolchains) not to drop the rlib at link time —
//! see the comment in `uniffi_macros::setup_scaffolding`.

dummy_a::uniffi_reexport_scaffolding!();
dummy_b::uniffi_reexport_scaffolding!();
