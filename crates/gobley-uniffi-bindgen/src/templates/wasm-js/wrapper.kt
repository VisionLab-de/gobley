{%- call kt::docstring_value(ci.namespace_docstring(), 0) %}

@file:Suppress("RemoveRedundantBackticks")
@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package {{ config.package_name() }}

// Common helper code (Kotlin/Wasm target).
//
// Mirrors android+jvm and native template trees but emits Kotlin/Wasm-only
// idioms: `external fun` for imports of the Rust wasm module, `@JsExport`
// for Kotlin → JS exposure (callback dispatchers), and `js("...")` for
// inline JS expressions touching the Rust module's linear memory.
//
// This file deliberately keeps the same symbol surface (`Pointer`,
// `RustBuffer`, `ByteBuffer`, `UniffiLib`, `UniffiHandleMap`,
// `UniffiCleaner`, `uniffiEnsureInitialized`) the rest of the bindgen
// expects, so all of `templates/ffi/*` and the `Types.kt` mega-match
// keep working unchanged across JVM/Native/Wasm.
//
// Memory model is the two-WASM-modules + JS-glue + 2-copy boundary path
// from T0.B Decision 1 / T0.C.1 spike. Vtable indices are queried at
// runtime from per-interface `vtable_index(method_id)` Rust exports
// (wasm-ld reorders imports alphabetically — never bake indices in).
//
// T0.C.2 scaffold: structural API surface only. Heavy lifting (JS shim,
// callback dispatch, RustBuffer copy paths, async polling) is filled in
// by phases T0.C.3 (WasmMemoryView), T0.C.4 (callbacks), T0.C.5 (async).

{%- for req in self.imports() %}
{{ req.render() }}
{%- endfor %}

{% include "PointerHelper.kt" %}

{% include "ByteBuffer.kt" %}
{% include "RustBufferTemplate.kt" %}
{% include "ffi/FfiConverterTemplate.kt" %}
{% include "Helpers.kt" %}
{% include "HandleMap.kt" %}

// Contains loading, initialization code,
// and the FFI Function declarations.
{% include "NamespaceLibraryTemplate.kt" %}

// Public interface members begin here.
{{ type_helper_code }}

{% import "macros.kt" as kt %}

{%- for func in ci.function_definitions() %}
{%- include "ffi/TopLevelFunctionTemplate.kt" %}
{%- endfor %}

// Async support
{%- if ci.has_async_fns() %}
{% include "Async.kt" %}
{%- endif %}
