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

// Resolve callback function table indices by scanning __gobleyIndirectFunctionTable
// for the injected `gobley_callbacks` imports. The wasm transformer injects each
// callback method as an import and places it in the indirect function table.
// After init(), __gobleyKotlinExports has the matching Kotlin @JsExport functions.
{%- for (_, meth) in vtable_methods.iter() %}
@JsFun("() => { const table = globalThis.__gobleyIndirectFunctionTable; const cb = globalThis.__gobleyKotlinExports?.gobley_callback_{{ name }}_{{ meth.name()|var_name_raw }}; if (!table || !cb) throw new Error('gobley: callback {{ name }}.{{ meth.name() }} not available'); for (let i = 0; i < table.length; i++) { try { if (table.get(i) === cb) return i; } catch(e) {} } throw new Error('gobley: callback {{ name }}.{{ meth.name() }} not in table'); }")
internal external fun __gobley_callback_index_{{ name }}_{{ meth.name()|var_name_raw }}(): Int
{%- endfor %}

@JsFun("() => { const table = globalThis.__gobleyIndirectFunctionTable; const cb = globalThis.__gobleyKotlinExports?.gobley_callback_{{ name }}_uniffi_free; if (!table || !cb) throw new Error('gobley: callback {{ name }}.uniffi_free not available'); for (let i = 0; i < table.length; i++) { try { if (table.get(i) === cb) return i; } catch(e) {} } throw new Error('gobley: callback {{ name }}.uniffi_free not in table'); }")
internal external fun __gobley_callback_index_{{ name }}_uniffi_free(): Int

internal object {{ trait_impl }} {
    {%- for (ffi_callback, meth) in vtable_methods.iter() %}
    // Internal dispatcher for `{{ meth.name() }}`. The `@JsExport`
    // top-level wrapper below
    // (`gobley_callback_{{ name }}_{{ meth.name()|var_name_raw }}`)
    // calls into this. Same shape as JVM's anonymous-object-callback
    // pattern — `uniffiOutReturn.setValue(...)` dispatches to
    // `RustBuffer.setValue` (shared FFI template) for `RustBuffer`
    // returns, or to the `*ByReference.setValue` extensions in
    // `ReferenceHelper.kt` for primitives.
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
        // Async callback dispatch — mirrors the JVM/Native foreign-future
        // pattern in those targets' `CallbackInterfaceImpl.kt`. The
        // shared `ffi/Async.kt` provides `uniffiTraitInterfaceCallAsync`
        // / `uniffiTraitInterfaceCallAsyncWithError`, which `launch` the
        // suspending `makeCall` on `GlobalScope` (single-threaded JS
        // event loop — no thread switch), register the resulting `Job`
        // in `uniffiForeignFutureHandleMap`, and return a
        // `UniffiForeignFutureUniffiByValue` whose `free` slot points to
        // `uniffiForeignFutureFreeImpl` (see `Async.kt`).
        //
        // `uniffiFutureCallback` is a Kotlin function-type alias on
        // Wasm (vs. `Callback.callback()` on JVM / `invoke()` on
        // Native), so we invoke it with plain call syntax.
        // `uniffiFutureCallback` (a function reference) travels through
        // the FFI as an i32 table index; the Kotlin side stores the
        // typealias-typed value and the JS shim forwards the actual
        // `call_indirect` dispatch via the cached table index — same
        // mechanism as `uniffiRustFutureContinuationCallbackCallback`.
        val uniffiHandleSuccess = { {% if meth.return_type().is_some() %}returnValue{% else %}_{% endif %}: {% match meth.return_type() %}{%- when Some(return_type) %}{{ return_type|type_name(ci) }}{%- when None %}Unit{% endmatch %} ->
            val uniffiResult = {{ meth.foreign_future_ffi_result_struct().name()|ffi_struct_name }}UniffiByValue(
                {%- if let Some(return_type) = meth.return_type() %}
                {{ return_type|lower_fn }}(returnValue),
                {%- endif %}
                UniffiRustCallStatusHelper.allocValue(),
            )
            uniffiFutureCallback(uniffiCallbackData, uniffiResult)
        }
        val uniffiHandleError = { callStatus: UniffiRustCallStatusByValue ->
            uniffiFutureCallback(
                uniffiCallbackData,
                {{ meth.foreign_future_ffi_result_struct().name()|ffi_struct_name }}UniffiByValue(
                    {%- if let Some(return_type) = meth.return_type() %}
                    {{ return_type.into()|ffi_default_value }},
                    {%- endif %}
                    callStatus,
                ),
            )
        }

        uniffiOutReturn.uniffiSetValue(
            {%- match meth.throws_type() %}
            {%- when None %}
            uniffiTraitInterfaceCallAsync(
                makeCall,
                uniffiHandleSuccess,
                uniffiHandleError,
            )
            {%- when Some(error_type) %}
            uniffiTraitInterfaceCallAsyncWithError(
                makeCall,
                uniffiHandleSuccess,
                uniffiHandleError,
            ) { e: {{error_type|type_name(ci) }} -> {{ error_type|lower_fn }}(e) }
            {%- endmatch %}
        )
        {%- endif %}
    }
    {% endfor %}

    {%- for (ffi_callback, meth) in vtable_methods.iter() %}
    internal val {{ meth.name()|var_name_raw }}Callback: {{ ffi_callback.name()|ffi_callback_name }} = {
        {%- for arg in ffi_callback.arguments() %}
        {{ arg.name()|var_name }},
        {%- endfor %}
        {%- if ffi_callback.has_rust_call_status_arg() %}
        uniffiCallStatus,
        {%- endif %}
        -> {{ trait_impl }}.{{ meth.name()|var_name }}(
            {%- for arg in ffi_callback.arguments() %}
            {{ arg.name()|var_name }},
            {%- endfor %}
            {%- if ffi_callback.has_rust_call_status_arg() %}
            uniffiCallStatus,
            {%- endif %}
        )
    }

    internal fun callbackFor{{ meth.name()|class_name(ci) }}Index(index: Int): {{ ffi_callback.name()|ffi_callback_name }}? {
        return when (index) {
            0 -> null
            {{ vtable_indices_obj }}.{{ meth.name()|var_name }} -> {{ meth.name()|var_name_raw }}Callback
            else -> throw InternalException(
                "Unexpected callback index for {{ name }}.{{ meth.name() }}: $index",
            )
        }
    }

    internal fun indexFor{{ meth.name()|class_name(ci) }}Callback(callback: {{ ffi_callback.name()|ffi_callback_name }}?): Int {
        return when {
            callback == null -> 0
            !{{ vtable_indices_obj }}.loaded -> throw InternalException(
                "Vtable indices for {{ name }} not loaded before storing {{ meth.name() }} callback",
            )
            callback === {{ meth.name()|var_name_raw }}Callback -> {{ vtable_indices_obj }}.{{ meth.name()|var_name }}
            else -> throw InternalException(
                "Unsupported callback instance for {{ name }}.{{ meth.name() }}: $callback",
            )
        }
    }
    {%- endfor %}

    internal fun uniffiFree(handle: Long) {
        {{ ffi_converter_name }}.handleMap.remove(handle)
    }

    internal val uniffiFreeCallback: UniffiCallbackInterfaceFree = { handle ->
        {{ trait_impl }}.uniffiFree(handle)
    }

    internal fun callbackForUniffiFreeIndex(index: Int): UniffiCallbackInterfaceFree? {
        return when (index) {
            0 -> null
            {{ vtable_indices_obj }}.uniffiFree -> uniffiFreeCallback
            else -> throw InternalException(
                "Unexpected callback index for {{ name }}.uniffiFree: $index",
            )
        }
    }

    internal fun indexForUniffiFreeCallback(callback: UniffiCallbackInterfaceFree?): Int {
        return when {
            callback == null -> 0
            !{{ vtable_indices_obj }}.loaded -> throw InternalException(
                "Vtable indices for {{ name }} not loaded before storing uniffiFree callback",
            )
            callback === uniffiFreeCallback -> {{ vtable_indices_obj }}.uniffiFree
            else -> throw InternalException(
                "Unsupported callback instance for {{ name }}.uniffiFree: $callback",
            )
        }
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
            {%- for (_, meth) in vtable_methods.iter() %}
            {{ vtable_indices_obj }}.{{ meth.name()|var_name }} =
                __gobley_callback_index_{{ name }}_{{ meth.name()|var_name_raw }}()
            {%- endfor %}
            {{ vtable_indices_obj }}.uniffiFree = __gobley_callback_index_{{ name }}_uniffi_free()
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
    {{ arg.name()|var_name_raw }}Ptr: Pointer,
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
        readRustBufferByValue({{ arg.name()|var_name_raw }}Ptr),
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
