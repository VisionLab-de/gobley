
// Define FFI callback types as Kotlin function-type aliases.
// Kotlin/Wasm doesn't have raw function pointers like JNA `Callback` or
// Native `staticCFunction`. The Kotlin side stores a `(...)->...`
// closure in a HandleMap; the actual `__indirect_function_table` index
// the Rust side needs lives on the Rust side and is queried at init via
// per-interface `vtable_index(method_id)` exports (T0.C.1 spike).

{%- for def in ci.ffi_definitions() %}
{%- match def %}
{%- when FfiDefinition::CallbackFunction(callback) %}
internal typealias {{ callback.name()|ffi_callback_name }} = (
    {%- for arg in callback.arguments() -%}
    {{ arg.type_().borrow()|ffi_type_name_by_value(ci) }},
    {%- endfor -%}
    {%- if callback.has_rust_call_status_arg() -%}
    UniffiRustCallStatus,
    {%- endif -%}
) -> {%- match callback.return_type() -%}
{%- when Some(return_type) -%}
{{ return_type|ffi_type_name_by_value(ci) }}
{%- when None -%}
Unit
{%- endmatch %}

{%- when FfiDefinition::Struct(ffi_struct) %}
// FFI struct `{{ ffi_struct.name() }}` — backing layout lives in Rust
// linear memory; Kotlin side carries a pointer + extension accessors.
//
// Field offsets are computed in `gen_kotlin_multiplatform::wasm_layout`
// per the wasm32 `#[repr(C)]` ABI (RustBuffer = 24 bytes align 8;
// RustCallStatus = 32 bytes align 8; primitives align to size). See
// the `wasm_field_offset/getter/setter` askama filters in
// `gen_kotlin_multiplatform::filters`.
//
// `value class` only — `@kotlin.jvm.JvmInline` is an `@OptionalExpectation`
// that only resolves on JVM/Android source sets, so emitting it on the
// wasmJs target trips "Declaration annotated with '@OptionalExpectation'
// can only be used in common module sources".
internal value class {{ ffi_struct.name()|ffi_struct_name }}(internal val ptr: Pointer)

{%- for field in ffi_struct.fields() %}
internal var {{ ffi_struct.name()|ffi_struct_name }}.{{ field.name()|var_name }}: {{ field.type_().borrow()|ffi_type_name_for_ffi_struct(ci) }}
    get() = {{ field|wasm_field_getter(ffi_struct, ci) }}
    set(value) {
        {{ field|wasm_field_setter(ffi_struct) }}
    }
{%- endfor %}

internal fun {{ ffi_struct.name()|ffi_struct_name }}.uniffiSetValue(other: {{ ffi_struct.name()|ffi_struct_name }}) {
    {%- for field in ffi_struct.fields() %}
    {{ field.name()|var_name }} = other.{{ field.name()|var_name }}
    {%- endfor %}
}
internal fun {{ ffi_struct.name()|ffi_struct_name }}.uniffiSetValue(other: {{ ffi_struct.name()|ffi_struct_name }}UniffiByValue) {
    {%- for field in ffi_struct.fields() %}
    {{ field.name()|var_name }} = other.{{ field.name()|var_name }}
    {%- endfor %}
}

// `data class` constructor is sufficient — its auto-generated primary
// constructor already fulfils the `FooUniffiByValue(field1, field2, ...)`
// call shape that callers expect. Emitting an extra top-level
// `internal fun FooUniffiByValue(...)` factory with the same parameter
// list collides with the synthesised constructor ("Conflicting overloads"
// / "Overload resolution ambiguity") on Kotlin/Wasm.
internal data class {{ ffi_struct.name()|ffi_struct_name }}UniffiByValue(
    {%- for field in ffi_struct.fields() %}
    internal val {{ field.name()|var_name }}: {{ field.type_().borrow()|ffi_type_name_for_ffi_struct(ci) }},
    {%- endfor %}
)

{%- when FfiDefinition::Function(_) %}
{# functions are handled below #}
{%- endmatch %}
{%- endfor %}

// External Rust wasm exports (`extern "C"` symbols of the cdylib).
//
// Per T0.B Decision 1: Kotlin/Wasm and the Rust wasm32-unknown-unknown
// module live in two separate `WebAssembly.Module` instances joined by
// JS glue. We declare the Rust exports here as `external` Kotlin
// functions imported through a JS module; the JS shim resolves them
// against `rustInstance.exports.<name>` at instantiation time.
//
// `@JsName` matches the Rust `#[no_mangle]` symbol exactly. Kotlin/Wasm
// 2.0+ re-exports these as plain JS functions on the imported module.
//
// All ABI is i32-clean per the spike: pointers + RustBuffer triples
// flatten to multiple i32 args. `Long` corresponds to wasm i64 and
// rides JS BigInt (Kotlin/Wasm 2.1+ handles the conversion).

{%- for func in ci.iter_ffi_function_definitions() %}
{{ func|wasm_js_import_decl(ci) }}

{%- endfor %}

internal object UniffiLib {
    init {
        {%- for init_fn in self.initialization_fns(ci) %}
            {{ init_fn }}
        {%- endfor %}
    }

    {%- if ci.contains_object_types() %}
    // The Cleaner for the whole library
    internal val CLEANER: UniffiCleaner by lazy {
        UniffiCleaner.create()
    }
    {%- endif %}

    {% for func in ci.iter_ffi_function_definitions() -%}
    // Imported from Rust wasm module's exports.
    fun {{ func.name() }}(
        {%- call kt::arg_list_ffi_decl(func, 8) %}
    ): {% match func.return_type() -%}
    {%- when Some(return_type) -%}
    {{- return_type.borrow()|ffi_type_name_by_value(ci) -}}
    {%- when None -%}
    Unit
    {%- endmatch %} {
        {{ func|wasm_js_function_body(ci) }}
    }
    {% endfor %}
}

{{ visibility() }}fun uniffiEnsureInitialized() {
    UniffiLib
}

// Async-safe variant for use from a Kotlin/Wasm `suspend fun main`.
// T0.C.3 fills in the actual `WebAssembly.instantiate` Promise await.
// Both sync + async variants exist per T0.B Issue #9: Kotlin/Wasm browsers
// prohibit synchronous `WebAssembly.instantiate` for modules >4KB, so async
// is mandatory in browser hosts; sync stays for non-browser hosts (Node, JVM
// embedders) where the cdylib is already instantiable synchronously.
{{ visibility() }}suspend fun uniffiEnsureInitializedAsync() {
    // TODO(T0.C.3): await `WebAssembly.instantiate(rustBytes, imports)`,
    // bind exports to UniffiLib, query each callback interface's
    // `vtable_index(method_id)` exports and cache them on the Kotlin side.
    uniffiEnsureInitialized()
}
