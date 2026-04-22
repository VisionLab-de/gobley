{%- let trait_impl=format!("uniffiCallbackInterface{}", name) %}
{%- let vtable_indices_obj=format!("UniffiVtableIndices{}", name) %}

// Callback interface dispatcher (Kotlin → Rust round-trip).
//
// Per T0.B Decision 3 + T0.C.1 spike:
//   * Vtable lives on the Kotlin side as a HandleMap entry.
//   * Each vtable method is exposed to JS via `@JsExport` top-level
//     functions named `gobley_callback_<iface>_<method>`. JS glue
//     declares these as imports under module `gobley_callbacks` when
//     instantiating the Rust wasm module.
//   * `@JsExport` is incompatible with `internal` visibility, so the
//     dispatchers live at file scope (public). The internal object
//     below holds the Rust-side vtable allocation + cache only.
//   * Rust calls `call_indirect <cached_index>` against
//     `__indirect_function_table`. Indices are queried at instance
//     bring-up via per-interface
//     `uniffi_<ns>_callback_<iface>_vtable_index(method_id)`
//     exports and cached — never baked in (wasm-ld reorders alphabetically).
//   * `RustBuffer` and any args wider than i32 travel as i32 pointers
//     into Rust linear memory; Kotlin reads/writes via `WasmMemoryView`
//     (the 2-copy boundary from Decision 1).

// Cached `__indirect_function_table` indices for this interface's
// vtable methods (plus uniffi_free). Populated from per-interface
// `__gobley_vtable_index_<iface>(methodId)` Rust exports the first
// time `register(lib)` runs. Single-threaded JS event loop means
// the `loaded` flag needs no synchronization.
internal object {{ vtable_indices_obj }} {
    // -1 = uncached. Valid indices are checked via the `loaded` flag, not the value.
    {%- for (_, meth) in vtable_methods.iter() %}
    internal var {{ meth.name()|var_name }}: Int = -1
    {%- endfor %}
    internal var uniffiFree: Int = -1
    internal var loaded: Boolean = false
}

// Rust-imported vtable-index lookup.
//
// One export on the Rust side per callback interface:
//
//     #[no_mangle]
//     pub extern "C" fn uniffi_<ns>_callback_<iface>_vtable_index(method: u32) -> u32 { ... }
//
// where `method` is `0..methods.len()` for the trait methods and
// `methods.len()` for `uniffi_free`. The JS shim layer (T0.C.6) wires
// this `external` declaration to `rustExports.uniffi_<ns>_callback_<iface>_vtable_index`.
//
// IMPORTANT: requires the Rust crate to be built with
// `-C link-arg=--export-table` (T0.C.1 spike, "Required linker flag").
// Without that, `__indirect_function_table` stays internal — Rust-side
// `call_indirect` still works, but JS cannot introspect the table to
// verify entries.
//
// Scaffolding gap: as of uniffi 0.29.5 the proc-macro/UDL expander does
// NOT emit per-interface `vtable_index` exports. The Kotlin/Wasm path
// assumes a small custom Rust shim is generated alongside the standard
// scaffolding (planned for a follow-up phase outside T0.C.4 — flagged
// in the design doc).
@JsFun(
    "(method) => globalThis.__gobleyRustExports.uniffi_{{ ci.namespace() }}_callback_{{ name }}_vtable_index(method)"
)
internal external fun __gobley_vtable_index_{{ name }}(method: Int): Int

internal object {{ trait_impl }} {
    {%- for (ffi_callback, meth) in vtable_methods.iter() %}
    // Internal dispatcher for `{{ meth.name() }}`. The `@JsExport`
    // top-level wrapper below
    // (`gobley_callback_{{ name }}_{{ meth.name()|var_name_raw }}`)
    // calls into this. Same shape as JVM's anonymous-object-callback
    // pattern — `uniffiOutReturn.setValue(...)` dispatches to
    // `RustBuffer.setValue` (shared FFI template) for `RustBuffer`
    // returns, or to the `*ByReference.setValue` extensions in
    // `HandleMap.kt` for primitives.
    internal fun {{ meth.name()|var_name }}(
        {%- call kt::arg_list_ffi_decl(ffi_callback, 8) %}
    )
    {%- if let Some(return_type) = ffi_callback.return_type() -%}
        : {{ return_type|ffi_type_name_by_value(ci) }}
    {%- endif %} {
        val uniffiObj = {{ ffi_converter_name }}.handleMap.get(uniffiHandle)
        val makeCall = {% if meth.is_async() %}suspend {% endif %}{ ->
            uniffiObj.{{ meth.name()|fn_name() }}(
                {%- for arg in meth.arguments() %}
                {%- if arg|as_ffi_type|ref|need_non_null_assertion %}
                {{ arg|lift_fn }}({{ arg.name()|var_name }}!!),
                {%- else %}
                {{ arg|lift_fn }}({{ arg.name()|var_name }}),
                {%- endif -%}
                {%- endfor %}
            )
        }
        {%- if !meth.is_async() %}

        {%- match meth.return_type() %}
        {%- when Some(return_type) %}
        val writeReturn = { uniffiResultValue: {{ return_type|type_name(ci) }} ->
            uniffiOutReturn.setValue({{ return_type|lower_fn }}(uniffiResultValue))
        }
        {%- when None %}
        val writeReturn = { _: Unit ->
            @Suppress("UNUSED_EXPRESSION")
            uniffiOutReturn
            Unit
        }
        {%- endmatch %}

        {%- match meth.throws_type() %}
        {%- when None %}
        uniffiTraitInterfaceCall(uniffiCallStatus, makeCall, writeReturn)
        {%- when Some(error_type) %}
        uniffiTraitInterfaceCallWithError(
            uniffiCallStatus,
            makeCall,
            writeReturn,
        ) { e: {{error_type|type_name(ci) }} -> {{ error_type|lower_fn }}(e) }
        {%- endmatch %}

        {%- else %}
        // TODO(T0.C.5): foreign-future async callback path. Mirror
        // native/Async.kt structure (continuation handle in HandleMap +
        // `uniffiFutureCallback` invocation) once the foreign-future
        // FFI struct write helpers exist.
        TODO("Async callback path filled in at T0.C.5")
        {%- endif %}
    }
    {% endfor %}

    internal fun uniffiFree(handle: Long) {
        {{ ffi_converter_name }}.handleMap.remove(handle)
    }

    // Cache vtable indices once and allocate the Rust-side vtable struct.
    //
    // The struct layout is `[i32; methods.len() + 1]` (one cell per
    // method, plus one for `uniffi_free`). On the Rust side this is the
    // `VTableCallbackInterface<Iface>` — uniffi's vtable_struct builder
    // produces a struct of function pointers; each function pointer is
    // 4 bytes on wasm32 (i32). We allocate the block via the standard
    // `RustBufferHelper.allocValue`, so the buffer's `data` pointer is
    // a valid 4-byte-aligned target inside Rust linear memory.
    //
    // Memory ownership: the buffer is intentionally NOT freed. The
    // Rust-side `init_callback_<iface>` reads the indices and stores
    // them in static state; nothing in the existing protocol allows
    // unregistering a callback interface during the program's lifetime,
    // so the leak is bounded by the number of callback interfaces in
    // the binary (six for rs-social-store).
    internal fun register(lib: UniffiLib) {
        if (!{{ vtable_indices_obj }}.loaded) {
            var methodId = 0
            {%- for (_, meth) in vtable_methods.iter() %}
            {{ vtable_indices_obj }}.{{ meth.name()|var_name }} =
                __gobley_vtable_index_{{ name }}(methodId)
            methodId += 1
            {%- endfor %}
            {{ vtable_indices_obj }}.uniffiFree = __gobley_vtable_index_{{ name }}(methodId)
            {{ vtable_indices_obj }}.loaded = true
        }

        val vtableSize = ({{ vtable_methods.len() }} + 1) * WASM_POINTER_SIZE_BYTES
        // INTENTIONAL LEAK: see register() docstring above — bounded by callback interface count.
        val rbuf = RustBufferHelper.allocValue(vtableSize.toULong())
        val basePtr = rbuf.data
            ?: throw InternalException(
                "Vtable alloc returned null data pointer for {{ name }}"
            )

        var offset = 0
        {%- for (_, meth) in vtable_methods.iter() %}
        WasmMemoryView.setInt(
            basePtr + offset,
            {{ vtable_indices_obj }}.{{ meth.name()|var_name }},
        )
        offset += WASM_POINTER_SIZE_BYTES
        {%- endfor %}
        WasmMemoryView.setInt(basePtr + offset, {{ vtable_indices_obj }}.uniffiFree)

        // The init_callback FFI takes the vtable struct *by value* on
        // JVM/Native (uniffi inlines the struct fields across the FFI).
        // On Wasm the struct is referenced by its base pointer — the
        // shared `ffi_type_name_by_value` filter resolves to a
        // `*ByValue` data class for FFI structs, but uniffi-Rust is
        // happy receiving the bare i32 pointer since wasm32's calling
        // convention treats by-value structs as "expanded into i32
        // args" anyway. We reuse the by-value type here so the call
        // type-checks against the existing `UniffiLib` declaration.
        lib.{{ ffi_init_callback.name() }}(
            {{ vtable|ffi_type_name(ci) }}(basePtr),
        )
    }
}

// `@JsExport` thin wrappers — these are the actual functions the JS
// glue layer (T0.C.6) imports into the Rust wasm module under module
// name `gobley_callbacks`. Names are unique per (interface, method) so
// the host-side import object can route to the right Kotlin dispatcher
// without name collisions across interfaces.
//
// `@JsExport` requires public visibility and currently rejects
// `internal` value-class parameter types on Kotlin/Wasm. The wrappers
// receive raw `Int`/`Long` for FFI pointers and call-status, then wrap
// them into the value-class types the internal dispatcher expects.
//
// Argument names use `var_name_raw` (no Kotlin backticks) here because
// `@JsExport` parameter identifiers are exposed to the JS host and
// concatenated with type-suffixes (`Capacity`, `Len`, `Data`, `Ptr`)
// for `RustBuffer` / by-reference flattening — backtick-quoted
// identifiers cannot carry suffixes.
//
// VERIFY at T0.C.6: confirm wasm-bindgen / cdylib lowering of
// init_callback_vtable_<iface>(VTableCallbackInterface<Iface>) accepts a
// single i32 ptr arg.

{%- for (ffi_callback, meth) in vtable_methods.iter() %}
@JsExport
public fun gobley_callback_{{ name }}_{{ meth.name()|var_name_raw }}(
    {%- for arg in ffi_callback.arguments() %}
    {%- match arg.type_().borrow() %}
    {%- when FfiType::RustBuffer(_) %}
    {{ arg.name()|var_name_raw }}Capacity: Int,
    {{ arg.name()|var_name_raw }}Len: Int,
    {{ arg.name()|var_name_raw }}Data: Pointer,
    {%- when FfiType::Struct(_) %}
    {{ arg.name()|var_name_raw }}Ptr: Pointer,
    {%- when FfiType::MutReference(inner) %}
    {{ arg.name()|var_name_raw }}Ptr: Pointer,
    {%- when FfiType::Reference(inner) %}
    {{ arg.name()|var_name_raw }}Ptr: Pointer,
    {%- when FfiType::VoidPointer %}
    {{ arg.name()|var_name_raw }}Ptr: Pointer,
    {%- else %}
    {{ arg.name()|var_name_raw }}: {{ arg.type_().borrow()|ffi_type_name_by_value(ci) }},
    {%- endmatch %}
    {%- endfor %}
    {%- if ffi_callback.has_rust_call_status_arg() %}
    uniffiCallStatusPtr: Pointer,
    {%- endif %}
)
{%- if let Some(return_type) = ffi_callback.return_type() -%}
    : {{ return_type|ffi_type_name_by_value(ci) }}
{%- endif %} {
    {%- if let Some(return_type) = ffi_callback.return_type() %}
    return {% endif %}{{ trait_impl }}.{{ meth.name()|var_name }}(
        {%- for arg in ffi_callback.arguments() %}
        {%- match arg.type_().borrow() %}
        {%- when FfiType::RustBuffer(_) %}
        RustBufferByValue(
            // u32 wire format widens to Long for byte-for-byte API
            // compat with JVM/Native — same masking convention used by
            // `var RustBuffer.capacity` in `RustBufferTemplate.kt`.
            capacity = {{ arg.name()|var_name_raw }}Capacity.toLong() and 0xFFFFFFFFL,
            len = {{ arg.name()|var_name_raw }}Len.toLong() and 0xFFFFFFFFL,
            data = if ({{ arg.name()|var_name_raw }}Data == 0) null else {{ arg.name()|var_name_raw }}Data,
        ),
        {%- when FfiType::Struct(_) %}
        {{ arg.type_().borrow()|ffi_type_name(ci) }}({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::MutReference(inner) %}
        {%- match inner.as_ref() %}
        {%- when FfiType::RustBuffer(_) %}
        RustBuffer({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::Struct(_) %}
        {{ arg.type_().borrow()|ffi_type_name(ci) }}({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::Int8 %}
        ByteByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::UInt8 %}
        ByteByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::Int16 %}
        ShortByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::UInt16 %}
        ShortByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::Int32 %}
        IntByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::UInt32 %}
        IntByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::Int64 %}
        LongByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::UInt64 %}
        LongByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::Float32 %}
        FloatByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::Float64 %}
        DoubleByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- when FfiType::RustArcPtr(_) %}
        PointerByReference({{ arg.name()|var_name_raw }}Ptr),
        {%- else %}
        // TODO(T0.C.5): unsupported MutReference inner type. Add
        // wrapper as needed.
        TODO("Unsupported MutReference inner type for {{ name }}.{{ meth.name() }}: ${ {{- arg.name()|var_name_raw }}Ptr}"),
        {%- endmatch %}
        {%- when FfiType::Reference(_) %}
        {{ arg.name()|var_name_raw }}Ptr,
        {%- when FfiType::VoidPointer %}
        {{ arg.name()|var_name_raw }}Ptr,
        {%- else %}
        {{ arg.name()|var_name_raw }},
        {%- endmatch %}
        {%- endfor %}
        {%- if ffi_callback.has_rust_call_status_arg() %}
        UniffiRustCallStatus(uniffiCallStatusPtr),
        {%- endif %}
    )
}
{% endfor %}

// `uniffi_free` dispatcher — separate from per-method ones so the JS
// glue can wire it to the dedicated `uniffi_free` slot in the vtable
// struct.
@JsExport
public fun gobley_callback_{{ name }}_uniffi_free(handle: Long) {
    {{ trait_impl }}.uniffiFree(handle)
}
