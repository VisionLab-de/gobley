{%- let trait_impl=format!("uniffiCallbackInterface{}", name) %}

// Callback interface dispatcher (Kotlin → Rust round-trip).
//
// Per T0.B Decision 3 + T0.C.1 spike:
//   * Vtable lives on the Kotlin side as a HandleMap entry.
//   * Each vtable method is exposed to JS via `@JsExport`. JS glue
//     declares these as imports under module `gobley_callbacks` when
//     instantiating the Rust wasm module.
//   * Rust calls `call_indirect <cached_index>` against
//     `__indirect_function_table`. Indices are queried at instance
//     bring-up via per-interface `uniffi_<ns>_callback_<iface>_vtable_index(method_id)`
//     exports and cached — never baked in (wasm-ld reorders alphabetically).
//   * `RustBuffer` and any args wider than i32 travel as i32 pointers
//     into Rust linear memory; Kotlin reads/writes via `WasmMemoryView`
//     (the 2-copy boundary from Decision 1).

internal object {{ trait_impl }} {
    {%- for (ffi_callback, meth) in vtable_methods.iter() %}
    // TODO(T0.C.4): `@JsExport` annotation + JS-glue registration once
    // the function-table-binding shim is generated. For T0.C.2 the
    // method body matches the JVM/Native shape so the FfiConverter
    // glue lift/lower types check.
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
            // TODO(T0.C.4): write the lowered value into Rust memory at
            // `uniffiOutReturn` rather than through a JVM-style setter.
            uniffiOutReturnSetValue(uniffiOutReturn, {{ return_type|lower_fn }}(uniffiResultValue))
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
        // `uniffiFutureCallback` invocation) once `uniffiOutReturn`
        // memory-write helpers exist.
        TODO("Async callback path filled in at T0.C.5")
        {%- endif %}
    }
    {% endfor %}

    internal fun uniffiFree(handle: Long) {
        {{ ffi_converter_name }}.handleMap.remove(handle)
    }

    // Vtable carries the *Kotlin-side* dispatcher closures. The Rust-side
    // function-table-index struct is allocated by `register(lib)` below,
    // populated with indices read back from the wasm exports.
    internal val vtable: List<Any> = listOf(
        {%- for (ffi_callback, meth) in vtable_methods.iter() %}
        ::{{ meth.name()|var_name }},
        {%- endfor %}
        ::uniffiFree,
    )

    internal fun register(lib: UniffiLib) {
        // TODO(T0.C.4):
        //   1. Allocate `[i32; N]` in Rust memory via uniffi_<ns>_rustbuffer_alloc.
        //   2. Populate it with values from per-method
        //      `uniffi_<ns>_callback_{{ name }}_vtable_index(method_id)`
        //      exports (cached on the Kotlin side at init).
        //   3. Pass that pointer to `lib.{{ ffi_init_callback.name() }}(vtablePtr)`.
        // For T0.C.2 we keep the call-site shape so the rest of codegen
        // type-checks; the actual indirection is in T0.C.4.
        lib.{{ ffi_init_callback.name() }}(0)
    }
}

// TODO(T0.C.4): replace with WasmMemoryView write into the Rust-side
// out-pointer. Lives at file scope so all `trait_impl`s share it.
private fun uniffiOutReturnSetValue(out: Any?, value: Any?) {
    @Suppress("UNUSED_PARAMETER")
    Unit
}
