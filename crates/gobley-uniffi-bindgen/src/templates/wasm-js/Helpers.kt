{% include "ffi/Helpers.kt" %}

// Rust-stack scratch-space helpers.
//
// `gobley-wasm-transformer` exports `__gobley_add_to_stack_pointer(delta)`
// from the Rust module so Kotlin/Wasm can carve out temporary slots inside
// Rust linear memory for ABI patterns like status out-pointers and sret
// return buffers.
@JsFun("(delta) => globalThis.__gobleyRustExports.__gobley_add_to_stack_pointer(delta)")
internal external fun __gobley_wasm_add_to_stack_pointer(delta: Int): Int

internal inline fun <T> withWasmStackFrame(size: Int, block: (Pointer) -> T): T {
    val framePtr = __gobley_wasm_add_to_stack_pointer(-size)
    try {
        return block(framePtr)
    } finally {
        __gobley_wasm_add_to_stack_pointer(size)
    }
}

internal fun zeroWasmMemory(ptr: Pointer, size: Int) {
    for (i in 0 until size) {
        WasmMemoryView.setByte(ptr + i, 0)
    }
}

internal fun readRustBufferByValue(ptr: Pointer): RustBufferByValue {
    return RustBufferByValue(
        capacity = WasmMemoryView.getLong(ptr + RustBuffer.OFFSET_CAPACITY),
        len = WasmMemoryView.getLong(ptr + RustBuffer.OFFSET_LEN),
        data = WasmMemoryView.getInt(ptr + RustBuffer.OFFSET_DATA).let { if (it == 0) null else it },
    )
}

internal fun readForeignBytesByValue(ptr: Pointer): ForeignBytesByValue {
    return ForeignBytesByValue(
        len = WasmMemoryView.getInt(ptr),
        data = WasmMemoryView.getInt(ptr + WASM_POINTER_SIZE_BYTES).let { if (it == 0) null else it },
    )
}

internal fun readUniffiRustCallStatusByValue(ptr: Pointer): UniffiRustCallStatusByValue {
    return UniffiRustCallStatusByValue(
        code = WasmMemoryView.getByte(ptr + UNIFFI_RUST_CALL_STATUS_OFFSET_CODE),
        errorBuf = readRustBufferByValue(ptr + UNIFFI_RUST_CALL_STATUS_OFFSET_ERROR_BUF),
    )
}


// Resolve the async continuation callback's indirect function table index.
// The callback is injected into the Rust module via function-import injection
// (gobley-wasm-transformer's inject_function_imports) and bound to the
// Kotlin @JsExport function by the .mjs helper during init().
// After init(), it's in the table — scan for the matching function ref.
@JsFun("() => { const table = globalThis.__gobleyIndirectFunctionTable; const cb = globalThis.__gobleyKotlinExports?.gobley_{{ ci.namespace() }}_async_continuation_callback; if (table == null || cb == null) { throw new Error('gobley: async callback not available — call uniffiEnsureInitializedAsync() first'); } for (let i = 0; i < table.length; i++) { try { if (table.get(i) === cb) return i; } catch(e) {} } throw new Error('gobley: async continuation callback not found in indirect function table'); }")
internal external fun __gobley_async_continuation_callback_index(): Int

internal val UNIFFI_RUST_FUTURE_CONTINUATION_CALLBACK_INDEX: Int by lazy {
    __gobley_async_continuation_callback_index()
}

internal fun uniffiRustFutureContinuationCallbackIndex(
    callback: UniffiRustFutureContinuationCallback,
): Int {
    val ignoredCallback = callback
    return UNIFFI_RUST_FUTURE_CONTINUATION_CALLBACK_INDEX
}

// Identity map for FFI-struct-field callbacks (opaque dispatch model).
//
// Rust stores an i32 __indirect_function_table index in every FFI-struct
// callback field (e.g. UniffiForeignFuture.free). The i32 index is what
// travels Rust → Kotlin → Rust unchanged. Kotlin never invokes the
// callback; it only needs to return the same index Rust wrote when a
// getter-then-setter roundtrips via uniffiSetValue. To do that, the
// getter binds a throwing sentinel lambda to the index in this map, and
// the setter recovers the index from that lambda's identity.
//
// Bound: one entry per callback-field getter call site emitted in the
// generated bindings for the namespace (roughly FFI-struct × callback-field).
// Sentinel lambdas are non-capturing — Kotlin/Wasm lowers them to
// per-call-site singletons, so repeated getter invocations reuse the
// same key and do not grow the map. No eviction — acceptable because
// the call-site set is frozen at uniffi codegen time and lifetime equals
// the wasm module's lifetime.
//
// Single-threaded JS event loop (wasm-js target) — no synchronization
// required. Revisit if this template is ever reused for a threaded
// target (e.g. SharedArrayBuffer worker pool).
private val uniffiOpaqueCallbackIndices: MutableMap<Any, Int> = mutableMapOf()

internal fun <T> uniffiRememberOpaqueCallback(callback: T, index: Int): T {
    if (callback != null) {
        uniffiOpaqueCallbackIndices[callback as Any] = index
    }
    return callback
}

internal fun uniffiOpaqueCallbackIndex(callback: Any?): Int? {
    return callback?.let { uniffiOpaqueCallbackIndices[it] }
}

{%- if ci.has_async_callback_interface_definition() %}
// Resolve the function-table index for the exported
// `gobley_foreign_future_free` shim that Rust stores in
// `UniffiForeignFuture.free`.
@JsFun("() => { const table = globalThis.__gobleyIndirectFunctionTable; const callback = globalThis.__gobleyKotlinExports?.gobley_{{ ci.namespace() }}_foreign_future_free ?? globalThis.gobley_{{ ci.namespace() }}_foreign_future_free; if (table == null || callback == null) { throw new Error('gobley wasmJs foreign-future free index unavailable before initialization'); } for (let i = 0; i < table.length; i++) { if (table.get(i) === callback) return i; } throw new Error('gobley wasmJs foreign-future free callback not found in __indirect_function_table'); }")
internal external fun __gobley_foreign_future_free_index(): Int

internal val UNIFFI_FOREIGN_FUTURE_FREE_INDEX: Int by lazy {
    __gobley_foreign_future_free_index()
}

internal fun uniffiForeignFutureFreeCallbackForIndex(
    index: Int,
): UniffiForeignFutureFree? {
    return when (index) {
        0 -> null
        UNIFFI_FOREIGN_FUTURE_FREE_INDEX -> uniffiForeignFutureFreeImpl
        else -> throw InternalException(
            "Unexpected foreign future free callback index: $index",
        )
    }
}

internal fun uniffiIndexForForeignFutureFreeCallback(
    callback: UniffiForeignFutureFree?,
): Int {
    return when {
        callback == null -> 0
        callback === uniffiForeignFutureFreeImpl -> UNIFFI_FOREIGN_FUTURE_FREE_INDEX
        else -> throw InternalException(
            "Unsupported foreign future free callback instance: $callback",
        )
    }
}
{%- endif %}

// UniffiRustCallStatus for Kotlin/Wasm.
//
// Layout in Rust (uniffi_core 0.29.5 `ffi/rustcalls.rs:42-53`):
//   #[repr(C)] struct RustCallStatus { code: i8, error_buf: RustBuffer }
//
// On wasm32 the C ABI lays this out as:
//   * offset  0 : code (i8) — 1 byte
//   * offset  1 : 7 bytes pad (RustBuffer aligns to 8 because of u64 capacity)
//   * offset  8 : error_buf (24 bytes — see RustBufferTemplate.kt header)
//   * total: 32 bytes
//
// Same accessor surface as JVM/Native (`code`, `errorBuf`,
// `UniffiRustCallStatusByValue`, `UniffiRustCallStatusHelper`) so the
// shared `ffi/Helpers.kt` (already included above) keeps compiling.

internal const val UNIFFI_RUST_CALL_STATUS_SIZE_BYTES: Int = 32
internal const val UNIFFI_RUST_CALL_STATUS_OFFSET_CODE: Int = 0
internal const val UNIFFI_RUST_CALL_STATUS_OFFSET_ERROR_BUF: Int = 8

// `value class` is sufficient on Kotlin/Wasm. `@JvmInline` is an
// `@OptionalExpectation` that only resolves on JVM/Android source sets;
// emitting it here triggers "Declaration annotated with '@OptionalExpectation'
// can only be used in common module sources" on the wasmJs target.
internal value class UniffiRustCallStatus(internal val ptr: Pointer)

internal var UniffiRustCallStatus.code: Byte
    get() = WasmMemoryView.getByte(ptr + UNIFFI_RUST_CALL_STATUS_OFFSET_CODE)
    set(value) { WasmMemoryView.setByte(ptr + UNIFFI_RUST_CALL_STATUS_OFFSET_CODE, value) }

internal var UniffiRustCallStatus.errorBuf: RustBufferByValue
    get() {
        val base = ptr + UNIFFI_RUST_CALL_STATUS_OFFSET_ERROR_BUF
        return RustBufferByValue(
            capacity = WasmMemoryView.getLong(base + RustBuffer.OFFSET_CAPACITY),
            len = WasmMemoryView.getLong(base + RustBuffer.OFFSET_LEN),
            data = WasmMemoryView.getInt(base + RustBuffer.OFFSET_DATA).let { if (it == 0) null else it },
        )
    }
    set(value) {
        val base = ptr + UNIFFI_RUST_CALL_STATUS_OFFSET_ERROR_BUF
        WasmMemoryView.setLong(base + RustBuffer.OFFSET_CAPACITY, value.capacity)
        WasmMemoryView.setLong(base + RustBuffer.OFFSET_LEN, value.len)
        WasmMemoryView.setInt(base + RustBuffer.OFFSET_DATA, value.data ?: 0)
    }

internal data class UniffiRustCallStatusByValue(
    internal val code: Byte,
    internal val errorBuf: RustBufferByValue,
)

internal object UniffiRustCallStatusHelper {
    internal fun allocValue(): UniffiRustCallStatusByValue =
        UniffiRustCallStatusByValue(
            code = UNIFFI_CALL_SUCCESS,
            errorBuf = RustBufferByValue(0L, 0L, null),
        )

    // Allocate a 32-byte status struct in Rust linear memory, hand it to
    // `block`, then free. Mirrors the JVM/Native `withReference` contract
    // (`crates/.../android+jvm/Helpers.kt:29` and `.../native/Helpers.kt:28`)
    // which carry the `code` write-back expectation: callers (the shared
    // `uniffiRustCall` / `uniffiRustCallWithError` in `ffi/Helpers.kt`)
    // read `status.code` and `status.errorBuf` *after* the rust call writes
    // to that pointer. So we must hand `block` a real pointer to memory
    // the rust side can write to, not a Kotlin-side struct.
    // The wasm transformer exports `__gobley_add_to_stack_pointer`, so we
    // can reserve a temporary 32-byte slot on Rust's own stack instead of
    // recursing through `RustBufferHelper.allocValue`.
    internal inline fun <U> withReference(block: (UniffiRustCallStatus) -> U): U {
        return withWasmStackFrame(UNIFFI_RUST_CALL_STATUS_SIZE_BYTES) { slabPtr ->
            zeroWasmMemory(slabPtr, UNIFFI_RUST_CALL_STATUS_SIZE_BYTES)
            block(UniffiRustCallStatus(slabPtr))
        }
    }
}
