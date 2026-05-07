//! Integration tests for `gobley_uniffi_bindgen::exports::read_exports`.
//!
//! Drives the function against the four pre-built fixture binaries that ship
//! in `tests/fixtures/` (wasm, Mach-O dylib, ELF .so, PE .dll). All four are
//! produced from the same `dummy_crate/` source and must yield the same
//! UniFFI marker symbols (`ffi_dummy_a_uniffi_contract_version`,
//! `ffi_dummy_b_uniffi_contract_version`) regardless of object-file format.

use std::path::{Path, PathBuf};

use gobley_uniffi_bindgen::exports::read_exports;

fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("fixtures")
        .join(name)
}

const MARKER_A: &str = "ffi_dummy_a_uniffi_contract_version";
const MARKER_B: &str = "ffi_dummy_b_uniffi_contract_version";

fn assert_has_markers(exports: &[String], format: &str) {
    assert!(
        exports.iter().any(|s| s == MARKER_A),
        "{format}: expected `{MARKER_A}` in exports, got {} entries (sample: {:?})",
        exports.len(),
        exports.iter().take(8).collect::<Vec<_>>()
    );
    assert!(
        exports.iter().any(|s| s == MARKER_B),
        "{format}: expected `{MARKER_B}` in exports, got {} entries (sample: {:?})",
        exports.len(),
        exports.iter().take(8).collect::<Vec<_>>()
    );
}

#[test]
fn reads_wasm_exports() {
    let exports = read_exports(&fixture("dummy.wasm")).expect("read_exports(wasm)");
    assert_has_markers(&exports, "wasm");
}

#[test]
fn reads_macho_dylib_exports() {
    let exports = read_exports(&fixture("dummy.dylib")).expect("read_exports(dylib)");
    assert_has_markers(&exports, "dylib");
    // Mach-O raw symbol names carry a leading underscore; `read_exports` must
    // strip it so callers see the canonical UniFFI marker spelling.
    assert!(
        !exports.iter().any(|s| s.starts_with("__ffi_dummy")),
        "dylib: leading underscore must be stripped"
    );
}

#[test]
fn reads_elf_so_exports() {
    let exports = read_exports(&fixture("dummy.so")).expect("read_exports(so)");
    assert_has_markers(&exports, "so");
}

#[test]
fn reads_pe_dll_exports() {
    let exports = read_exports(&fixture("dummy.dll")).expect("read_exports(dll)");
    assert_has_markers(&exports, "dll");
}

#[test]
fn marker_set_is_consistent_across_formats() {
    let collect_markers = |path: PathBuf| -> Vec<String> {
        let mut markers: Vec<String> = read_exports(&path)
            .expect("read_exports")
            .into_iter()
            .filter(|s| s.contains("uniffi_contract_version"))
            .collect();
        markers.sort();
        markers
    };

    let wasm = collect_markers(fixture("dummy.wasm"));
    let dylib = collect_markers(fixture("dummy.dylib"));
    let so = collect_markers(fixture("dummy.so"));
    let dll = collect_markers(fixture("dummy.dll"));

    assert_eq!(wasm, dylib, "wasm vs dylib marker drift");
    assert_eq!(wasm, so, "wasm vs so marker drift");
    assert_eq!(wasm, dll, "wasm vs dll marker drift");
    assert!(
        wasm.contains(&MARKER_A.to_string()) && wasm.contains(&MARKER_B.to_string()),
        "expected both dummy_a and dummy_b markers, got {wasm:?}"
    );
}

#[test]
fn returns_err_on_nonexistent_file() {
    let result = read_exports(Path::new(
        "/nonexistent/path/that/should/not/exist/dummy.wasm",
    ));
    assert!(result.is_err(), "expected Err for missing file");
}

#[test]
fn returns_err_on_garbage_bytes() {
    let dir = std::env::temp_dir().join("gobley-uniffi-bindgen-exports-test");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("garbage.bin");
    std::fs::write(&path, b"not a binary, definitely not an ELF or wasm header").unwrap();

    let result = read_exports(&path);
    assert!(result.is_err(), "expected Err for garbage bytes");
}
