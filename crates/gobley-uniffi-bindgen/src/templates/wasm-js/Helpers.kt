{% include "ffi/Helpers.kt" %}

// UniffiRustCallStatus for Kotlin/Wasm.
//
// Layout in Rust (uniffi scaffolding):
//   #[repr(C)]
//   struct RustCallStatus { code: i8, error_buf: RustBuffer }
// Padding for natural alignment makes the actual struct 16 bytes:
//   offset 0  : code (i8) + 3 bytes pad
//   offset 4  : capacity (u32)
//   offset 8  : len (u32)
//   offset 12 : data (*mut u8)
//
// Same accessor surface as JVM/Native (`code`, `errorBuf`,
// `UniffiRustCallStatusByValue`, `UniffiRustCallStatusHelper`) so the
// shared `ffi/Helpers.kt` (already included above) keeps compiling.

internal const val UNIFFI_RUST_CALL_STATUS_SIZE_BYTES: Int = 16
internal const val UNIFFI_RUST_CALL_STATUS_OFFSET_CODE: Int = 0
internal const val UNIFFI_RUST_CALL_STATUS_OFFSET_ERROR_BUF: Int = 4

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
            capacity = WasmMemoryView.getInt(base + RustBuffer.OFFSET_CAPACITY).toLong() and 0xFFFFFFFFL,
            len = WasmMemoryView.getInt(base + RustBuffer.OFFSET_LEN).toLong() and 0xFFFFFFFFL,
            data = WasmMemoryView.getInt(base + RustBuffer.OFFSET_DATA).let { if (it == 0) null else it },
        )
    }
    set(value) {
        val base = ptr + UNIFFI_RUST_CALL_STATUS_OFFSET_ERROR_BUF
        WasmMemoryView.setInt(base + RustBuffer.OFFSET_CAPACITY, value.capacity.toInt())
        WasmMemoryView.setInt(base + RustBuffer.OFFSET_LEN, value.len.toInt())
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

    internal fun <U> withReference(block: (UniffiRustCallStatus) -> U): U {
        // TODO(T0.C.3): allocate UNIFFI_RUST_CALL_STATUS_SIZE_BYTES via the
        // Rust-exported `uniffi_<ns>_rustbuffer_alloc` (or a dedicated stack
        // bump allocator export), call `block(UniffiRustCallStatus(ptr))`,
        // then free. Single-threaded JS event loop means a per-thread bump
        // arena is sufficient.
        TODO("WasmMemoryView allocator wired up in T0.C.3")
    }
}
