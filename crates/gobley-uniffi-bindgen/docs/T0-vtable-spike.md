# T0.C.1 — vtable feasibility spike (Kotlin/Wasm callbacks)

## Verdict

**CLEAN PATH FEASIBLE.** Rust `extern "C"` host imports become real
`__indirect_function_table` entries when their address is taken in Rust
source, and `call_indirect` against those indices works with no
signature-mismatch trap. Indices are stable across re-instantiation of the
same wasm bytes.

Spike crate: `crates/gobley-wasm-vtable-spike/` (`wasm32-unknown-unknown`,
`no_std`, `panic="abort"`). Built with rustflag `link-arg=--export-table` so
the JS harness can introspect the table.

## Evidence

`wasm-tools print` — linker placed all three imports into the function table
and lowered `(CALLBACKS[i])(...)` to `call_indirect`:

```
(import "env" "on_error"    (func $on_error    (type 0)))
(import "env" "on_complete" (func $on_complete (type 0)))
(import "env" "on_data"     (func $on_data     (type 0)))
(table 0 4 4 funcref)
(elem (i32.const 1) func $on_error $on_complete $on_data)

(func $dispatch (param i32 i32 i32 i32)
  ; load CALLBACKS[method] from .rodata, then:
  call_indirect (type 0))
```

Static `[on_data, on_error, on_complete]` lowered to
`.rodata = "\03\00\00\00\01\00\00\00\02\00\00\00"` — table indices
`[3, 1, 2]`.

`node test.mjs`, two independent instances of the same bytes:

```
vtable_index per method (instance A): [ 3, 1, 2 ]
vtable_index per method (instance B): [ 3, 1, 2 ]
table[0]=<null>  table[1..3]=<funcref>

-- indirect dispatch --
A:on_data(1, 11, 12)
A:on_error(2, 21, 22)
A:on_complete(3, 31, 32)

indices stable across re-instantiation: true
```

Same JS callback fires whether dispatched via `call $on_data` (control) or
`call_indirect` (under test).

## Surprise: linker reorders imports

Source declares `[on_data, on_error, on_complete]`. wasm-ld assigns
`[3, 1, 2]` (index 0 reserved as NULL funcref; remainder appears
alphabetical-ish by symbol). Implication: codegen **cannot** bake table
indices into Kotlin constants. Indices must be discovered at instance
bring-up via per-interface `vtable_index(method_id)` exports, called once
after instantiation and cached on the Kotlin side. Within one Rust crate
build the indices are deterministic, but a recompile may shuffle them —
never bake them in.

## Memory growth caveat (orthogonal)

Decision 1 holds: callback args wider than i32 still travel through Rust
linear memory; `WasmMemoryView` must re-acquire its `DataView` after every
allocating call (design doc risk #3). Spike payload is i32-only so growth
never triggers. Vtable feasibility is independent.

## Required linker flag

`-C link-arg=--export-table` (in the spike's `.cargo/config.toml`). Without
it `__indirect_function_table` stays internal — Rust-side `call_indirect`
still works, but JS cannot introspect or splice entries. Two routes for
production: (a) document the flag and require it in
`cargo { builds.wasm { … } }`, or (b) post-process via
`gobley-wasm-transformer` (which already manipulates the import section).
Decide in T0.C.2.

## Codegen impact for rs-social-store callbacks

Six listener interfaces (`ChannelStreamListener`, `PostStreamListener`,
`CommentStreamListener`, `ActivityStreamListener`, `UploadProgressListener`,
plus connectivity if any). Each gets one `[i32; N]` vtable and one
`register(handle, vtablePtr)` call — no central tag dispatcher, no
`match shim_id` arm per callback. Estimated savings vs the ugly fallback:
~30 lines per Rust-side trampoline × 6 interfaces × ~20 methods total ≈ 600
lines of generated Rust shim avoided, plus a Kotlin `when` table per
interface.

## Recommendation for T0.C.2+

1. Adopt the clean path. Vtable = `[i32; N]` of table indices. Index
   discovery via per-interface
   `uniffi_<ns>_callback_<iface>_vtable_index(method_id) -> i32` exports
   generated alongside the callback shims.
2. Require `-C link-arg=--export-table` OR add the table export in
   `gobley-wasm-transformer` post-hoc — pick in T0.C.2.
3. Confirm Kotlin/Wasm `@JsExport` callbacks survive a synchronous
   re-entrant `call_indirect` inside the same task tick (design doc risk #2)
   in the next prototype slice.
4. Keep the i32-only callback ABI from the spike: `RustBuffer` passes as a
   single i32 pointer; widen via Rust-side helpers, never via wasm
   multivalue returns (Kotlin/Wasm 2.x JS interop is mono-result-friendlier).

## Files

- `crates/gobley-wasm-vtable-spike/Cargo.toml`
- `crates/gobley-wasm-vtable-spike/.cargo/config.toml`
- `crates/gobley-wasm-vtable-spike/src/lib.rs`
- `crates/gobley-wasm-vtable-spike/test.mjs`
