# gobley-uniffi-bindgen test fixtures

Hand-built binary artefacts that exercise `read_exports(&Path) -> Result<Vec<String>>`
across the four object-file formats UniFFI shared libraries can come in. The
function reads exported symbols and uses the `ffi_<crate>_uniffi_contract_version`
markers to auto-derive the bindgen crate allowlist (replaces the manual
`--crates` flag introduced in commit `bd8aaf398`).

## Contents

| File         | Format            | Target triple              | Built on          |
| ------------ | ----------------- | -------------------------- | ----------------- |
| `dummy.wasm` | WebAssembly       | `wasm32-unknown-unknown`   | any host          |
| `dummy.dylib`| Mach-O dylib      | `aarch64-apple-darwin`     | macOS host        |
| `dummy.so`   | ELF shared object | `x86_64-unknown-linux-gnu` | macOS via x86_64-unknown-linux-gnu cross-toolchain / Linux host |
| `dummy.dll`  | PE32+ DLL         | `x86_64-pc-windows-gnu`    | macOS via mingw / Linux host |

All four are produced from the same source — the `dummy_crate/` workspace —
and therefore export the same symbols.

## Source

```
dummy_crate/
├── Cargo.toml          ← workspace root, separate from gobley's workspace
├── rust-toolchain.toml ← pins stable (>= 1.85, needed by uniffi 0.29 deps)
├── dummy_a/            ← rlib, calls uniffi::setup_scaffolding!("dummy_a")
├── dummy_b/            ← rlib, calls uniffi::setup_scaffolding!("dummy_b")
└── dummy_combined/     ← cdylib, links both leaves and re-exports their
                          UniFFI scaffolding via uniffi_reexport_scaffolding!
```

`uniffi::setup_scaffolding!()` derives its symbol prefix from the calling
crate's name (`mod_path()` → `CARGO_CRATE_NAME`), so two distinct rlib
crates are required to get two distinct prefixes in the same shared object.
The `dummy_combined` cdylib is the artefact we ship; it exists purely to
force the linker to keep both crates' scaffolding symbols in the dynamic
export table.

## Marker symbols

The minimum set `read_exports` cares about:

```
ffi_dummy_a_uniffi_contract_version
ffi_dummy_b_uniffi_contract_version
```

Each artefact also carries the rest of UniFFI's per-crate scaffolding —
`ffi_<crate>_rustbuffer_alloc`, the 13×4 `rust_future_*` matrix, etc. — but
only the `*_uniffi_contract_version` exports are used as the allowlist
seed. There are 57 such scaffolding exports per crate (114 total in the
combined cdylib), so a regression that drops one will almost certainly
drop the marker too.

## Inspection

```bash
# Mach-O
nm -gU dummy.dylib | grep ffi_dummy.*contract_version

# ELF
llvm-nm -D dummy.so | grep ffi_dummy.*contract_version
# (or: nm -D dummy.so on a Linux host)

# PE
x86_64-w64-mingw32-objdump -p dummy.dll | grep ffi_dummy.*contract_version

# Wasm
wasm-tools dump dummy.wasm | grep "Export.*ffi_dummy.*contract_version"
```

Expected output for each: two lines, one per dummy crate.

## Regenerating

Run `./build.sh` from this directory. See the script header for required
toolchains and per-target overrides. Re-run only when:

- the dummy crate workspace changes
- the pinned `uniffi` version changes
- the marker symbol layout changes upstream

The artefacts are committed because building them at test time would
require every contributor to install the four target toolchains, which is
not a reasonable test prerequisite.
