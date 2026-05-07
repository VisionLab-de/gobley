//! Read the export symbol table of a cdylib/staticlib/wasm artefact.
//!
//! Used by the bindgen to auto-derive the crate allowlist: every UniFFI crate
//! emits an `ffi_<crate>_uniffi_contract_version` symbol via
//! `uniffi::setup_scaffolding!()`, so the set of crates whose bindings are
//! actually exposed by a shared library can be recovered by grepping that
//! library's export table.
//!
//! Format dispatch is by magic bytes: `\0asm` selects the wasm path
//! (`wasmparser`), every other format goes through the `object` crate which
//! handles ELF, Mach-O, and PE. The wasm reader in `object` 0.36 returns no
//! entries for cdylib `.wasm` artefacts, hence the dedicated path.
//!
//! Output is normalised across formats: Mach-O's leading-underscore symbol
//! decoration is stripped so callers can match `ffi_<crate>_…` literals
//! regardless of host platform.

use std::path::Path;

use anyhow::{Context, Result};
use object::Object;

const WASM_MAGIC: &[u8; 4] = b"\0asm";

/// Returns the export symbol names of the binary at `path`.
///
/// The returned vector contains the canonical (un-mangled) symbol names. An
/// empty vector means the binary parsed but had no exports — not an error.
/// Errors are returned for missing files, unrecognised formats, and parse
/// failures.
pub fn read_exports(path: &Path) -> Result<Vec<String>> {
    let bytes =
        std::fs::read(path).with_context(|| format!("reading binary at {}", path.display()))?;

    if bytes.starts_with(WASM_MAGIC) {
        return read_wasm_exports(&bytes)
            .with_context(|| format!("parsing wasm exports from {}", path.display()));
    }

    read_object_exports(&bytes)
        .with_context(|| format!("parsing object exports from {}", path.display()))
}

fn read_wasm_exports(bytes: &[u8]) -> Result<Vec<String>> {
    let mut out = Vec::new();
    for payload in wasmparser::Parser::new(0).parse_all(bytes) {
        if let wasmparser::Payload::ExportSection(reader) = payload? {
            for export in reader {
                out.push(export?.name.to_string());
            }
        }
    }
    Ok(out)
}

fn read_object_exports(bytes: &[u8]) -> Result<Vec<String>> {
    let file = object::File::parse(bytes)?;
    let strip_underscore = matches!(file.format(), object::BinaryFormat::MachO);

    let mut out = Vec::new();
    for export in file.exports()? {
        let name = String::from_utf8_lossy(export.name());
        let normalised = if strip_underscore {
            name.strip_prefix('_').unwrap_or(&name).to_string()
        } else {
            name.into_owned()
        };
        out.push(normalised);
    }
    Ok(out)
}
