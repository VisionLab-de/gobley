# T0.A — Gobley codegen recon (Kotlin/Wasm backend prep)

Read-only audit. No design. Phase T0.B will design the Kotlin/Wasm backend on top of these findings.

## 1. Codegen architecture

- **Entry point**: `crates/gobley-uniffi-bindgen/src/main.rs` defines a clap CLI; instantiates `KotlinBindingGenerator` (in `lib.rs`) and dispatches to `uniffi_bindgen::library_mode::generate_bindings` or `generate_external_bindings`.
- **Trait surface**: only one — `uniffi_bindgen::BindingGenerator` (upstream). Gobley's impl in `lib.rs` returns a single `Config` and writes per-target files via `write_bindings_target(target, content)`. There is **no per-language `Backend` trait** inside Gobley; the per-target split lives entirely in askama templates + a few wrapper structs (see below).
- **Render pipeline** (`gen_kotlin_multiplatform/mod.rs`):
  1. `generate_bindings(config, ci)` → `MultiplatformBindings { common, jvm?, android?, native?, stub?, header? }`.
  2. Each variant is one askama-rendered `String`. Variant is gated by `Config.kotlin_targets: Vec<ConfigKotlinTarget>` — the enum has only `Jvm | Android | Native | Stub`.
  3. Five `kotlin_wrapper!`-generated structs (`CommonKotlinWrapper`, `AndroidJvmKotlinWrapper`, `NativeKotlinWrapper`, `StubKotlinWrapper`, `HeadersKotlinWrapper`) point at one askama template root each (`common/wrapper.kt`, `android+jvm/wrapper.kt`, `native/wrapper.kt`, `stub/wrapper.kt`, `headers/wrapper.h`).
  4. Templates share one set of askama filters defined in `mod::filters` (type names, FFI conversion fns, async helpers, header escaping, etc.).
- **Type model**: `KotlinCodeOracle` + `CodeType` trait (`type_label`, `canonical_name`, `ffi_converter_name`, `literal`, `initialization_fn`). `AsCodeType for T: AsType` is the single mega-match that maps every `uniffi::Type` → `Box<dyn CodeType>` (`primitives`, `enum_`, `record`, `object`, `compounds` (Optional/Sequence/Map), `callback_interface`, `custom`, `miscellany`).
- **File layout written to disk** (`write_bindings_target`):
  - `<out_dir>/{target}Main/kotlin/<package_path>/<namespace>.{target}.kt` per target (when `kotlin_multiplatform=true`).
  - Header file: `<out_dir>/nativeInterop/cinterop/headers/<namespace>/<namespace>.h` (Kotlin/Native cinterop).
- **Template engine**: askama (`syntax="kt"`, escape="none"`); shared macros in `templates/macros.kt`; `templates/ffi/` and `templates/common/` are language-target-agnostic includes pulled into both `android+jvm/wrapper.kt` and `native/wrapper.kt`.

## 2. Per-target backend table

Five emitted artifacts per `ComponentInterface`. JS is **not** in the bindgen tool today; the only existing Kotlin/JS plumbing is the `gobley-wasm-transformer` crate plus a hand-rolled fixture.

| Aspect                 | common (expect)                              | android+jvm (JVM/JNA)                                                            | native (K/N cinterop)                                                          | stub (TODO()-only)                              | js (NOT IN BINDGEN)               |
|------------------------|----------------------------------------------|----------------------------------------------------------------------------------|--------------------------------------------------------------------------------|-------------------------------------------------|-----------------------------------|
| Entry template         | `common/wrapper.kt`                          | `android+jvm/wrapper.kt`                                                         | `native/wrapper.kt`                                                            | `stub/wrapper.kt`                               | n/a                               |
| Pointer model          | `expect class Pointer`                       | `typealias Pointer = com.sun.jna.Pointer`                                        | `typealias Pointer = CPointer<out CPointed>`                                   | n/a                                             | n/a                               |
| FFI binding            | `expect object UniffiLib`                    | JNA: `Native.register(UniffiLib::class.java, libname)` + `external fun`s, or JNA interface mapping (companion object via `loadIndirect`) | cinterop wrappers: `UniffiLib.foo() = ns.cinterop.foo(...)`                    | empty                                           | n/a                               |
| Library load           | n/a                                          | `findLibraryName()` + JNA `Native.load`/`register`; jar-extraction fallback for transitive `.dylib`/`.so` | implicit (cinterop linkage at compile)                                         | n/a                                             | n/a                               |
| Integrity / checksum   | call from common                             | `IntegrityCheckingUniffiLib` separate JNA interface (works around method-too-large) | `UniffiLib.init {}` calls scaffolding                                          | none                                            | n/a                               |
| Type mapping (prims)   | typealiases `expect`                         | `Byte/Short/Int/Long/Float/Double` (signed even for unsigned — see comment in `KotlinCodeOracle.ffi_type_label`) | same Kotlin types, but backing FFI is `kotlinx.cinterop.*Var`                  | uses common's expect names                      | n/a                               |
| RustBuffer             | `expect class RustBuffer`                    | `@Structure.FieldOrder` JNA `Structure(capacity, len, data)`; `asByteBuffer` wraps JNA `Pointer.getByteBuffer` | `CPointer<ns.cinterop.RustBuffer>` with extension props; `cValue<>` for ByValue | n/a                                             | n/a                               |
| ByteBuffer (wire)      | `expect class ByteBuffer`                    | `@JvmInline value class` over `java.nio.ByteBuffer` forced to `BIG_ENDIAN`        | hand-rolled `class ByteBuffer(pointer, capacity, position)` with manual big-endian shifts | n/a                                             | n/a                               |
| Records / enums / errors | `expect`/`actual` data classes via Types.kt; ErrorTemplate, EnumTemplate live in `common/` | actual stays in common (no per-target override needed for plain values)          | same                                                                            | n/a                                             | n/a                               |
| Object handles         | `expect class FooImpl(NoPointer)` etc.       | actual: pointer-backed; cleaner = `JavaLangRefCleaner` (or `AndroidSystemCleaner` API 34+, fallback `UniffiJnaCleaner`) | actual: pointer-backed; cleaner uses Kotlin `Cleaner` from atomicfu/kotlin-stdlib (`ObjectCleanerHelper.kt`) | `actual class { TODO() }` on every method       | n/a                               |
| HandleMap              | n/a                                          | `ConcurrentHashMap<Long,T>` + `atomicfu.AtomicLong`                              | `HashMap<Long,T>` + `atomicfu.locks.ReentrantLock`                              | n/a                                             | n/a                               |
| Callback interface vtable | declared in common                        | `internal object impl: UniffiCallback { override fun callback(...) }` — JNA-callable | `staticCFunction { ... }` allocated on `nativeHeap`; vtable pointer cast fixups | empty                                           | n/a                               |
| Async (Rust → Kt)      | `ffi/Async.kt` provides `uniffiRustCallAsync` (suspendCancellableCoroutine + `withContext(Dispatchers.IO)`) | wraps in JNA `UniffiRustFutureContinuationCallback` interface                    | wraps in `staticCFunction` continuation                                         | empty                                           | n/a                               |
| Async (Kt → Rust)      | `uniffiTraitInterfaceCallAsync` via `GlobalScope.launch` (DelicateCoroutinesApi) | uses object-callback, writes `UniffiForeignFutureUniffiByValue`                  | uses `cValue<...>` and writes via `rawPtr`                                       | empty                                           | n/a                               |
| String / Vec<u8>       | helpers in `ffi/StringHelper.kt`, `ByteArrayHelper.kt` (UTF-8 lower/lift via RustBuffer) | uses `String.toByteArray(Charsets.UTF_8)` + `java.nio.ByteBuffer`                 | uses kotlinx UTF-8 + manual write to `pointer`                                  | none                                            | n/a                               |
| Optional / Sequence / Map | `ffi/OptionalTemplate.kt`, `SequenceTemplate.kt`, `MapTemplate.kt` (write tag byte / length prefix into RustBuffer) | shared via include                                                               | shared via include                                                              | none                                            | n/a                               |
| Endianness             | network big-endian — every multi-byte put/get is BE | enforced via `inner.order(BIG_ENDIAN)`                                            | enforced manually via shift/and ops                                            | n/a                                             | n/a                               |

`stub` exists exclusively to satisfy `actual` declarations in `expect` source sets that the gradle plugin doesn't recognize (see `UniFfiPlugin.kt:254-263` — anything that isn't `KotlinJvmTarget`, `KotlinAndroidTarget`, `KotlinNativeTarget`, or `KotlinMetadataTarget` is mapped to `"stub"`). Today JS and Wasm targets get a stub-warning at config time and a `TODO()` runtime surface — no UniFFI bindings exist for them.

## 3. wasm-transformer role

`crates/gobley-wasm-transformer` (v0.3.7) is a separate binary + library:

- **Input**: a `.wasm` cdylib (e.g., `cargo build --target wasm32-unknown-unknown`) plus optional `function-imports.txt` listing host imports the wasm expects.
- **Pipeline** (`Transformer::transform` → `render_into_kt`):
  1. `inject_stack_pointer_shim()` — exports `__gobley_add_to_stack_pointer` so Kotlin can manage the wasm shadow stack for struct-by-pointer args (the Comment-2 `paxbun` issue: wasm-bindgen normally provides this; we need it for non-bindgen Rust too).
  2. `inject_function_imports()` — adds host import declarations into the module's import section + appends them to the function table so they can be invoked through `call_indirect` (function-pointer support).
  3. `transform_using_wasm_bindgen()` — when the wasm contains `__wasm_bindgen` custom sections, runs `wasm_bindgen_cli_support::Bindgen` programmatically with `bundler(true)`, captures the JS modules + snippets, and transpiles each via swc into a single CommonJS-style factory function. Each factory becomes a `private val wbgFactoryN: WasmBindgenJsModuleFactory = js("""<transpiled JS>""")` global in the emitted Kotlin.
  4. `render_into_kt()` — emits ONE `.kt` file from `templates/js.kt`. Module bytes are base64-embedded in a `private const val BASE64`. Synchronously instantiated via `WebAssembly.Module(buffer)` + `WebAssembly.Instance(module, imports)`.
- **Output type system** (`map_val_type_to_kt`): only `i32→Int`, `f32→Float`, `f64→Double`, everything else (incl. `i64`, `v128`, ref types) → `Any`. This is wasm-level, not UniFFI-level.
- **Generated Kotlin shape**: `external interface RustWebAssemblyExports`, `external class WebAssembly`, `class RustWebAssemblyImports(...)` per-import-module nested class, `createInstance(imports)`. Uses Kotlin/JS `dynamic`, `js("...")`, `@JsName`. Targets **only Kotlin/JS** (legacy IR — see `dynamic`/`js("...")` usage which doesn't exist in Kotlin/Wasm).
- **Gradle wiring**: `TransformWasmTask` (`build-logic/gobley-gradle-cargo/.../tasks/TransformWasmTask.kt`) shells out to the binary, writes to `gobley.wasm.<crate_name>.kt` per cargo build variant; configurable via `cargo { builds.wasm { variants { transformWasmProvider.configure { functionImportsFile = ... } } } }`.

For Kotlin/Wasm reuse, the wasm rewrite passes (stack-pointer shim, function-import injection, wasm-bindgen JS transpilation to factory closures) are largely target-agnostic. Only the **Kotlin renderer** (`templates/js.kt` + `KotlinJsRenderer`) is Kotlin/JS-specific.

## 4. Gradle integration

- **Plugins**: `dev.gobley.cargo` (cross-compiles Rust per Kotlin target ABI) + `dev.gobley.uniffi` (runs `gobley-uniffi-bindgen`, copies generated Kotlin into the right source sets, hooks tasks into KMP compilation).
- **Target → source set mapping** (`UniFfiPlugin.kt:253-264`): KMP target → bindgen `kotlin_targets` config entry:
  - `KotlinJvmTarget` / `KotlinWithJavaTarget` → `"jvm"` → `jvmMain`
  - `KotlinAndroidTarget` → `"android"` → `androidMain`
  - `KotlinNativeTarget` → `"native"` → `nativeMain`
  - `KotlinMetadataTarget` → null (skipped)
  - everything else (incl. JS, Wasm) → `"stub"` → `<target>Main` (TODO-only actuals)
- **Generated source paths** (`lib.rs:107-110`): `<out_dir>/{target}Main/kotlin/<pkg_path>/<namespace>.{target}.kt` plus `<out_dir>/commonMain/kotlin/...` for `expect`s. Header file goes to `<out_dir>/nativeInterop/cinterop/headers/...`.
- **Rust cross-compile**: `CargoExtension` handles per-target Rust toolchains (Android NDK, iOS, JVM hosts, `wasm32-unknown-unknown` via `RustWasmTarget`). `CargoWasmBuild` + `HasWasmVariant` handle the wasm-specific build pipeline; output `.wasm` is post-processed by `TransformWasmTask`.
- **Block at the gate** (`UniFfiPlugin.kt:142-150, 186-188`): UniFFI plugin **explicitly throws** `"$RustWasmTarget not available for UniFFI"` if you try to use wasm as the bindgen build target, and **logs a warning** if any JS or Wasm Kotlin targets are configured. The cargo plugin does emit wasm + transform it — but the bindgen plugin refuses to consume it.

## 5. Issue #9 — maintainer guidance (paxbun)

Open since 2025-03-04. Maintainer's architecture reasoning (verbatim summary from comments 2, 6, 7):

- **Kotlin/JS** (`js()`): "easier to implement". Embed wasm in the resulting library, load from JS side. Two known problems:
  - Synchronous WASM instantiation impossible in browsers → "expose an asynchronous function that loads the WASM binary".
  - No access to the wasm-bindgen-style alias (shadow) stack pointer → must export heap allocation/reallocation OR a stack-pointer shim. (This is what `gobley-wasm-transformer`'s `inject_stack_pointer_shim` already solves.)
- **Kotlin/Wasm** (`wasmJs()`): "harder". Two options:
  1. Link two wasm binaries with `wasm-lld` — risky: unknown how LLVM's wasm heap interops with Kotlin/Wasm's; unclear how to invoke a third-party linker from Kotlin's build. Also unknown whether Kotlin/Wasm uses an alias stack of its own.
  2. **Keep them as two separate binaries linked via JS glue** — pass the Kotlin wasm `exports` as the Rust wasm `imports` argument at instantiation. "We still don't have a concrete method to make the Kotlin WASM access the internal alias stack pointer inside the Rust WASM." (i.e., paxbun has not solved how to write into the Rust shadow stack from Kotlin/Wasm without a JS intermediary.)
- **wasmWasi**: only viable via wasm-lld linking.
- **Husker-dev / paxbun consensus** (Aug 2025, comments 4-7): current sync-load approach is *transient*. Plan is to add `suspend fun ensureWasmLoaded()` to the bindings and possibly a Kotlin compiler plugin that scans dependencies and injects loader calls into `main`. Tracked at #39. Current `tests/gradle/js-only` is the placeholder.
- Comment 3 has a working PoC of synchronous WASM load on Node.js for the `coverall` fixture — useful as a starting reference.

## 6. Gap analysis — Kotlin/Wasm vs reusable kotlin-js code

Kotlin/Wasm (`wasmJs()`) does NOT support `dynamic`, `js("...")` as a literal in arbitrary positions, or legacy JS-IR external classes the way Kotlin/JS does. It uses `@JsFun`, `@JsExport`, and `external` with stricter semantics. So the existing "kotlin-js" output from wasm-transformer is **not** lift-and-shift to Kotlin/Wasm.

| Aspect                          | Net-new for Kotlin/Wasm?                                          | Reuse from existing code                                                                                |
|---------------------------------|-------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Bindgen backend `KotlinWrapper` | NEW — add `JsKotlinWrapper` and/or `WasmJsKotlinWrapper` + `ConfigKotlinTarget::Js`/`WasmJs`, route in `generate_bindings` and `write_bindings` | architecture is already cleanly per-target; pattern is mechanical                                       |
| Templates root (per-target)     | NEW `templates/js/` (or `wasmJs/`) tree                           | `templates/common/` and `templates/ffi/` (FfiConverter, EnumTemplate, RecordTemplate, OptionalTemplate, SequenceTemplate, MapTemplate, StringHelper, Async ffi helpers, ErrorTemplate, CustomTypeTemplate) — language-agnostic, used as-is |
| Pointer model                   | NEW — Kotlin/Wasm has no `CPointer`/`com.sun.jna.Pointer`. Likely `Int` (wasm i32 address) typealias | none                                                                                                   |
| RustBuffer / ByteBuffer         | NEW — must read/write the wasm `Memory.buffer` via `Int8Array`/`DataView` (in JS) or via Kotlin/Wasm linear memory APIs; preserve big-endian convention | wire format, allocation-size logic, `FfiConverter` interface, lift/lower function shapes — reusable    |
| FFI library load                | NEW — async `WebAssembly.instantiate(...)`. Not the JNA `Library` model. Needs `ensureWasmLoaded()` story per Issue #9 / #39 | wasm-transformer already produces the loader scaffold (see comment 6 plan)                              |
| Integrity / checksum            | reuse logic; emit as plain `wasmExports.uniffiCheckContractApiVersion()` calls after async init | template logic already exists                                                                          |
| Object handles / cleaner        | NEW — Kotlin/Wasm has no `java.lang.ref.Cleaner` and no JNA cleaner; needs a custom finalizer or an explicit `close()` story (no GC hook) | HandleMap pattern reusable; cleaner abstraction needs a Wasm-specific impl                              |
| Callback interface vtable       | NEW — wasm function table + indirect call. The transformer's `inject_function_imports` already exposes `tblIdx_<name>` constants. Need a Kotlin-side mechanism to register a Kt function as a wasm import (Kotlin/Wasm has `@JsFun` for JS bridging; raw wasm function tables are different) | structure of vtable struct + `register(lib)` flow reusable                                              |
| Async (Rust → Kt)               | NEW — replace `Dispatchers.IO` (no IO dispatcher on JS/Wasm) with `Dispatchers.Default` or `Dispatchers.Main`; keep `suspendCancellableCoroutine` pattern | `ffi/Async.kt` body almost-as-is                                                                       |
| Async (Kt → Rust)               | NEW callback registration mechanism; `GlobalScope.launch` works on JS but pattern around `cValue<>` doesn't | helper signatures + lifetime story portable                                                            |
| Kotlin function-name mangling   | Possibly NEW — Kotlin/Wasm export names get mangled differently; backticks for reserved names already handled by oracle | reuse                                                                                                  |
| Big-endian wire format          | reuse exactly — keep network byte order convention                | both existing impls confirm BE                                                                         |
| String / Vec<u8> helpers        | rewrite memory access; UTF-8 logic identical                      | algorithms reusable                                                                                    |
| Stub target                     | once `JsKotlinWrapper` exists, stub usage for JS/Wasm goes away   | `stub/` stays for any future unmapped target                                                            |
| Gradle plugin wiring            | NEW: extend `UniFfiPlugin.checkKotlinTargets` and `kotlinTargets.set { ... }` mapping (currently maps everything unknown to `"stub"`); remove the `RustWasmTarget` block at line 186-188; thread the wasm-transformer output into the bindgen flow so the bindings can reference `RustWebAssemblyExports` | existing `TransformWasmTask` + cargo wasm pipeline reusable                                            |
| wasm-transformer renderer       | NEW Kotlin/Wasm renderer (`templates/wasmJs.kt` or similar) — current `js.kt` template uses `dynamic`/`js("...")` which Kotlin/Wasm doesn't have | `Transformer` + `inject_stack_pointer_shim` + `inject_function_imports` + wasm-bindgen JS transpilation reusable as-is |

## 7. Open questions for design phase (T0.B)

1. **wasm-bindgen interop**: do we run wasm-bindgen at all for the new Kotlin/Wasm target, or do we require pure `extern "C"` Rust (no `#[wasm_bindgen]`)? UniFFI scaffolding is already C-ABI. The transformer's wasm-bindgen path exists for the manual `js-only` fixture — does it carry forward, and if so, how do snippets get loaded under Kotlin/Wasm (no `js("...")`)?
2. **Single binary or two**: per Issue #9, do we (a) run Rust `wasm32-unknown-unknown` as a separate module loaded from Kotlin/Wasm via JS glue, or (b) attempt wasm-lld merging? (a) seems decided (paxbun's preferred path) — confirm.
3. **Shadow stack ownership**: how does Kotlin/Wasm code call Rust functions taking `&BigStruct` when both binaries have separate linear memories? The transformer's `__gobley_add_to_stack_pointer` shim assumes shared memory or ability to write into Rust's memory. Crossing two wasm modules requires either (i) marshalling via host JS using `Int8Array` views over each module's `Memory.buffer`, or (ii) a shared `Memory` import. Which?
4. **Async loading API**: should `expect fun uniffiEnsureInitialized()` become `expect suspend fun uniffiEnsureInitialized()` for all targets, or do we add a Wasm-only `ensureWasmLoaded()` and keep the sync API on JVM/Native? Affects every binding signature.
5. **Cleaner story**: Kotlin/Wasm has no automatic finalization. Mandatory `Disposable.use {}` pattern, or rely on `kotlin.js.WeakRef` / wasm-gc finalizers (when stable)?
6. **Callback interface mechanism**: how do we register a Kotlin closure as a wasm function-table entry in Kotlin/Wasm? `@JsFun` produces JS functions, not raw wasm functions. Needs a JS-glue shim, or wait on a Kotlin/Wasm feature?
7. **i64 across the boundary**: wasm-transformer maps `i64 → Any`. Kotlin/Wasm has native `Long` over wasm `i64`. Confirm the mapping changes (and whether the `Handle = i64` FFI type needs special handling).
8. **wasmJs vs wasmWasi**: scope is `wasmJs` only? wasmWasi requires linker work per Issue #9.
9. **Source-set name**: the `kotlinTargets` value `"wasmJs"` needs to map to source-set `wasmJsMain` per KMP convention — verify gradle plugin's `write_bindings_target` source-set naming logic still holds (currently `"{target}Main"` — yields `wasmJsMain`, fine).
10. **Checksum / contract version call site**: must run after async load completes. Where does the integrity check fit in the new init flow?

---

**Summary**

- Biggest architectural unknown: **how the Kotlin/Wasm binary and the Rust wasm32 binary share memory and a function table**. Per Issue #9 paxbun has not solved how Kotlin/Wasm code accesses Rust's shadow stack pointer; the choice between (a) two modules linked via JS glue with copy-marshalling across separate `Memory` instances vs (b) a shared imported `Memory` vs (c) wasm-lld static link cascades into every other decision (RustBuffer impl, callback vtable, async init shape, whether wasm-bindgen carries forward).
- Biggest reuse opportunity: **the entire `templates/common/` + `templates/ffi/` tree plus the `KotlinCodeOracle` / `CodeType` machinery**. Type mapping, FfiConverter interface, Optional/Sequence/Map serialization, Enum/Record/Error templates, async coroutine helpers (`uniffiRustCallAsync`, `uniffiTraitInterfaceCallAsync`), HandleMap pattern, big-endian wire format — all language-target-agnostic and used as-is by both existing JVM and Native backends. A Kotlin/Wasm backend only needs to write target-specific equivalents of the four `android+jvm`/`native` files: `PointerHelper.kt`, `ByteBuffer.kt`, `RustBufferTemplate.kt`, `NamespaceLibraryTemplate.kt` (plus `CallbackInterfaceImpl.kt`, `Async.kt`, `ObjectCleanerHelper.kt`, `HandleMap.kt`). The `gobley-wasm-transformer` rewrite passes (stack-pointer shim, function-import injection, wasm-bindgen JS transpilation) are already production-grade and target-agnostic — only the `KotlinJsRenderer` needs a sibling for Kotlin/Wasm.
