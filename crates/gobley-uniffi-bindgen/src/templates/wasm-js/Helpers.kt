{% include "ffi/Helpers.kt" %}

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
    //
    // RUNTIME CAVEAT (T0.C.3.b.1):
    //   This currently piggy-backs on `RustBufferHelper.allocValue` to
    //   obtain a 32-byte Rust-linear-memory slab. That helper itself goes
    //   through `uniffiRustCall { … withReference { … } }`, so the *first*
    //   call from foreign code to Rust will infinite-recurse.
    //
    //   Fix is scoped to T0.C.3.b.4 (a dedicated `uniffi_<ns>_alloc(size)`
    //   export — or a JS-side bump arena — that doesn't itself need a
    //   status struct). The signature here is what the rest of the wasm-js
    //   binding needs to compile against; the runtime path is wired up by
    //   .b.2 (`UniffiLib.<fn>` bodies) and .b.4 (scratch allocator).
    //
    // We initialize the slab to all-zero (`code = 0 = UNIFFI_CALL_SUCCESS`,
    // `error_buf` zero) before handing it to `block` — `RustBufferHelper.allocValue`
    // returns capacity-N zero-filled memory, which already covers this.
    internal inline fun <U> withReference(block: (UniffiRustCallStatus) -> U): U {
        val slab = RustBufferHelper.allocValue(UNIFFI_RUST_CALL_STATUS_SIZE_BYTES.toULong())
        val slabPtr = slab.data
            ?: throw RuntimeException("UniffiRustCallStatusHelper: alloc returned null data pointer")
        // Zero the struct: `RustBufferHelper.allocValue` allocates a Vec<u8>
        // capacity-N but len=0, so the bytes are uninitialized from Rust's
        // perspective. Zero the first 32 bytes explicitly.
        for (i in 0 until UNIFFI_RUST_CALL_STATUS_SIZE_BYTES) {
            WasmMemoryView.setByte(slabPtr + i, 0)
        }
        try {
            return block(UniffiRustCallStatus(slabPtr))
        } finally {
            RustBufferHelper.free(slab)
        }
    }
}
