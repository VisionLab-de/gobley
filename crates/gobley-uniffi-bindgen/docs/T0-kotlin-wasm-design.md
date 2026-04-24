# T0.B — Kotlin/Wasm bindgen backend design

Design doc. T0.C prototypes from this. No code, no template sketches.

Scope: `wasmJs()` Kotlin target. `wasmWasi()` deferred (Issue #9: needs wasm-lld).
Net-new `KotlinWrapper` slotted into the askama oracle alongside common / android+jvm /
native / stub / headers. Reuses every shared file under `templates/common/` and
`templates/ffi/` unchanged.

---

## Decision 1 — Memory model

Three options from paxbun (Issue #9 comment 2):

**(a) Two WASM modules + JS glue.** Each module owns its `Memory`. Marshalling crosses
via host JS using `Int8Array` views. paxbun: *"We still don't have a concrete method to
make the Kotlin WASM access the internal alias stack pointer inside the Rust WASM."*
Two-copy boundary for `RustBuffer` payloads. Feasible today (Issue #9 comment 3 PoC
proves loader). Medium complexity: JS shim layer routed via `@JsFun`/`external`.
Constrains async init and forces every callback through JS.

**(b) Shared imported `Memory`.** One `WebAssembly.Memory` passed as `imports` to both.
Zero-copy `RustBuffer`. **Not feasible today** — Kotlin/Wasm's runtime allocator owns
its memory pages and does not accept an external `Memory` as backing. Wasm-GC reference
types live outside the linear memory model entirely.

**(c) wasm-lld static link.** paxbun: *"We don't know what happens when LLVM's WASM heap
implementation interops with Kotlin's… I can't find a way to invoke a third-party linker
during Kotlin build."* Kotlin/Wasm 2.0+ emits Wasm-GC bytecode; wasm-lld cannot link
GC-typed modules against linear-memory wasm32-unknown-unknown output. Requires upstream
JetBrains cooperation.

**Choice: (a).**

- Generated Kotlin emits `external interface UniffiWasmExports` with `@JsName` matching
  Rust `extern "C"` symbols; loader resolves via `WebAssembly.instantiate` and casts
  `exports`.
- Memory access mediated by a thin generated JS shim (one helper per package) holding
  `DataView`/`Int8Array` over `exports.memory.buffer`, exposed to Kotlin via `@JsFun`.
  View must be re-acquired after every Rust allocation (memory growth invalidates the
  `buffer` reference).
- Cross-module pointers carry as wasm `i32`. Kotlin side: `Int`.

---

## Decision 2 — RustBuffer implementation

Native uses `CPointer<RustBuffer>`; JVM uses JNA `Structure(capacity, len, data)`.
Kotlin/Wasm has neither.

**Choice:** `RustBuffer` is a value class wrapping one `Int` (i32 pointer to the 12-byte
struct in Rust memory: `{ capacity: u32, len: u32, data: *mut u8 }`). `RustBufferByValue`
is a data class of three `Int`s for direct-passed cases.

- Generated `WasmMemoryView` Kotlin object exposes `getInt/setInt/getBytes/setBytes`,
  each delegating to `@JsFun`-defined JS that reacquires a fresh `DataView` over
  `exports.memory.buffer` (memory-growth-safe).
- Big-endian wire format preserved exactly. `ByteBuffer.kt` reuses Native's shift-and-mask
  logic, but cell access goes through `WasmMemoryView` instead of `pointer[i]`.
- `RustBufferHelper.allocValue` calls `UniffiLib.uniffi_<ns>_rustbuffer_alloc` — already
  exported by every uniffi cdylib; free identical.

---

## Decision 3 — Callback interface vtable

Native registers a vtable of `staticCFunction` pointers via `nativeHeap.alloc`. JVM does
the same via JNA callback objects. Both require writing a Kotlin closure into a
function pointer Rust can `call_indirect` on.

In two-module mode, callbacks must round-trip via JS: Rust calls an *imported* host
function → JS dispatches into Kotlin via `@JsExport` shim → Kotlin reads args from Rust
memory through `WasmMemoryView` → executes the user's trait impl → return path writes
back into Rust memory and returns the i32 result through JS.

**Choice:** Vtable lives entirely on the Kotlin side. One `@JsExport` shim per vtable
method (`uniffiCallbackInterface<Name>_<method>`) with primitive-only signatures —
`RustBuffer` passes as i32 pointer; types wider than i32 are split. JS glue declares these
as imports under module name `gobley_callbacks` when instantiating the Rust wasm:
`WebAssembly.instantiate(rustBytes, { gobley_callbacks: { ...shims... } })`.

- HandleMap ports as-is from Native (`HashMap<Long, T>` + `Long` counter). Kotlin/Wasm
  has no GlobalRef problem — the map *is* the GlobalRef equivalent (objects stay alive
  while map entry exists).
- `register(lib)` calls `lib.uniffi_<ns>_init_callback_<iface>(vtablePtr)` where
  `vtablePtr` is the i32 address of a small Rust-side struct of function-table indices,
  written into Rust memory at init via the alloc primitive. Indices resolved from
  `instance.exports.__indirect_function_table` after Rust instantiation.
- Async callback methods (Kt → Rust) reuse `templates/ffi/Async.kt` — `GlobalScope.launch`
  works on Kotlin/Wasm.

---

## Decision 4 — Async fn lowering

Existing `Async.kt` (ffi/) uses `suspendCancellableCoroutine` + `withContext(Dispatchers.IO)`
+ poll loop driven by Rust's continuation callback. Kotlin/Wasm runs single-threaded JS
event loop — no `Dispatchers.IO`, but `kotlinx-coroutines-core-wasm-js` provides
`Dispatchers.Default` and `suspendCancellableCoroutine` unchanged.

**Choice:** Reuse `templates/ffi/Async.kt` body verbatim. Substitute
`Dispatchers.IO → Dispatchers.Default` in `wasmJs/Async.kt`. Continuation callback is a
`@JsExport` function (same mechanism as Decision 3).

- `uniffiContinuationHandleMap` stays a `UniffiHandleMap<CancellableContinuation<Byte>>`
  identical to Native's. Single-threaded resume works fine.
- husker-dev's async-init suggestion: expose `suspend fun uniffiEnsureInitialized()` per
  binding. Compiler-plugin auto-injection is **out of scope for T0.C**. Markus's SDK
  calls it explicitly from the wasm entry point.

---

## Decision 5 — Eight new templates + `WasmJsKotlinWrapper`

New template root: `crates/gobley-uniffi-bindgen/src/templates/wasmJs/`. Source-set
`wasmJsMain` per KMP convention — already supported by the writer's `"{target}Main"`
naming.

| File | Responsibility |
|---|---|
| `wrapper.kt` | Top-level askama include of all below + `ffi/*` shared files; package + opt-ins |
| `PointerHelper.kt` | `typealias Pointer = Int`, `NullPointer = 0`, conversions for handle compat |
| `ByteBuffer.kt` | Wraps Rust-memory pointer + capacity + position; same big-endian read/write API; backed by `WasmMemoryView` |
| `RustBufferTemplate.kt` | `value class RustBuffer(val ptr: Int)`, `data class RustBufferByValue`, alloc/free helpers |
| `NamespaceLibraryTemplate.kt` | `external interface UniffiWasmExports` (one fn per `iter_ffi_function_definitions`); `internal object UniffiLib` lazy-bound after `uniffiEnsureInitialized()`; FFI struct typealiases as i32 pointers + extension accessors via `WasmMemoryView` |
| `CallbackInterfaceImpl.kt` | Per Decision 3: HandleMap lookup, `@JsExport` shim per method, builds vtable pointer struct, calls `lib.uniffi_..._init_callback_<iface>` |
| `Async.kt` | Per Decision 4: continuation `@JsExport` shim + foreign-future-free shim; reuses `ffi/Async.kt` body |
| `ObjectCleanerHelper.kt` | Stub `UniffiCleaner` — Kotlin/Wasm has no automatic finalizer. Mandatory `Disposable.use {}`/explicit `close()`; debug assert on dropped instances |
| `HandleMap.kt` | Same shape as Native; plain `HashMap<Long, T>` + `Long` counter (no lock — single-threaded event loop) |

**`WasmJsKotlinWrapper` struct.** Defined in `gen_kotlin_multiplatform/mod.rs` via the
existing `kotlin_wrapper!` macro. Fields inherited (`module_name`, `visibility`,
`config`, `ci`, `type_helper_code`, `type_imports`); responsibilities identical to
siblings. New enum variant `ConfigKotlinTarget::WasmJs` joins `Jvm | Android | Native |
Stub`. `MultiplatformBindings` gains `pub wasm_js: Option<String>`. `generate_bindings`
adds a `run_with_target` block mirroring the four existing.

---

## Decision 6 — Gradle plugin integration

**Cargo side (already works):** `RustWasmTarget` + `CargoWasmBuild` cross-compile to
`wasm32-unknown-unknown`; `TransformWasmTask` runs `gobley-wasm-transformer`. Today's
transformer emits Kotlin/JS; T0.C adds a sibling `KotlinWasmJsRenderer` selected by a
new `--target wasmJs` CLI flag. Existing rewrite passes (`inject_stack_pointer_shim`,
`inject_function_imports`, wasm-bindgen JS transpilation) stay shared.

**UniFFI plugin side:**

- `UniFfiPlugin.kt:142-150` — drop the `RustWasmTarget not available for UniFFI` throw.
- `UniFfiPlugin.kt:186-188` — drop the JS/Wasm target warning.
- `UniFfiPlugin.kt:253-264` — add `KotlinWasmJsTarget → "wasmJs"` mapping
  (Kotlin Gradle DSL exposes `KotlinWasmJsTargetDsl`).
- Wire `TransformWasmTask` output (`gobley.wasm.<crate>.kt`) into `wasmJsMain` alongside
  bindgen's `<namespace>.wasmJs.kt`. Both files in `<out_dir>/wasmJsMain/kotlin/<pkg>/`.
- Cargo profile selection unchanged; honor existing `cargo { builds.wasm { ... } }`.

- Add `cargo { builds.wasm { variants { transformWasmProvider.configure { target =
  "wasmJs" } } } }` knob; default `js` (back-compat).
- The bindgen-emitted file references `gobley.wasm.<crate>` package symbols via plain
  `import` — same coupling as today's JS path.
- No `BindingGenerator` trait change; `lib.rs:write_bindings_target` already routes by
  string.

---

## Risks & open questions blocking T0.C

1. **Function-table registration.** When we instantiate Rust wasm with
   `imports = { gobley_callbacks: { foo, bar } }`, do those imports get stable
   `__indirect_function_table` indices Rust can `call_indirect` against? If not, fall
   back to a generated Rust-side dispatcher `match shim_id { 0 => ... }` driven by an
   i32 tag instead of a real wasm function-table entry. **1-day spike before T0.C
   commits to vtable shape.**

2. **`@JsExport` semantics on Kotlin/Wasm 2.0+.** Confirm primitive-only signatures
   export without wrappers and that JS can invoke them synchronously during a Rust
   `call_indirect`. If exports are async-only, the entire callback path collapses.

3. **Memory growth invalidation.** Every `RustBuffer.alloc` may grow Rust's `Memory`,
   invalidating any cached JS `DataView`. Generated code must re-acquire after every
   allocating Rust call. Easiest: re-acquire unconditionally per call. Perf cost
   unmeasured; matters for our SDK's hot stream paths.

4. **i64 marshalling.** Wasm `i64` ↔ JS `BigInt`; Kotlin/Wasm `Long` is native wasm
   `i64`. `Handle = i64` in uniffi must round-trip without `BigInt` cost. Verify
   Kotlin/Wasm's calling convention to imported JS handles `Long ↔ BigInt`
   automatically (as of Kotlin 2.1, yes — confirm).

5. **No automatic finalization.** Markus's SDK already uses `Closeable.use {}`. Confirm
   stub-with-debug-assert is acceptable, or do we need a `WeakRef` polyfill?

6. **Source-set wiring ordering.** Bindgen output references transformer output —
   confirm Gradle task ordering: bindgen depends on transform, not vice versa.

7. **Async-init ergonomics.** Compiler-plugin auto-injection deferred. Confirm
   `suspend fun main() { uniffiEnsureInitialized(); ... }` shape acceptable for now.
