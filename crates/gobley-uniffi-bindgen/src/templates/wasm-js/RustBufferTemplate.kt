{% include "ffi/RustBufferTemplate.kt" %}

// RustBuffer for Kotlin/Wasm.
//
// In Rust the layout is `{ capacity: u32, len: u32, data: *mut u8 }` (12 bytes).
// We follow T0.B Decision 2:
//   * `RustBuffer` = a 1-i32 value class wrapping the i32 pointer to that
//     12-byte struct in *Rust* linear memory.
//   * `RustBufferByValue` = a Kotlin data class of the three i32 fields,
//     used for direct-passed `RustBuffer` arguments (uniffi often passes
//     RustBuffers by value, struct-flattened across the FFI).
//
// `capacity` and `len` are exposed as `Long` for byte-for-byte API
// compatibility with the JVM/Native templates (which sign-extend u64 to
// Long). On Wasm they're really u32, so we mask off the high bits when
// converting from `Long` back to `Int` for the underlying wasm calls.

@kotlin.jvm.JvmInline
{{ visibility() }}value class RustBuffer(internal val ptr: Pointer) {
    internal companion object {
        internal const val SIZE_BYTES: Int = 12
        internal const val OFFSET_CAPACITY: Int = 0
        internal const val OFFSET_LEN: Int = 4
        internal const val OFFSET_DATA: Int = 8
    }
}

{{ visibility() }}var RustBuffer.capacity: Long
    get() = WasmMemoryView.getInt(ptr + RustBuffer.OFFSET_CAPACITY).toLong() and 0xFFFFFFFFL
    set(value) { WasmMemoryView.setInt(ptr + RustBuffer.OFFSET_CAPACITY, value.toInt()) }

{{ visibility() }}var RustBuffer.len: Long
    get() = WasmMemoryView.getInt(ptr + RustBuffer.OFFSET_LEN).toLong() and 0xFFFFFFFFL
    set(value) { WasmMemoryView.setInt(ptr + RustBuffer.OFFSET_LEN, value.toInt()) }

{{ visibility() }}var RustBuffer.data: Pointer?
    get() {
        val raw = WasmMemoryView.getInt(ptr + RustBuffer.OFFSET_DATA)
        return if (raw == 0) null else raw
    }
    set(value) { WasmMemoryView.setInt(ptr + RustBuffer.OFFSET_DATA, value ?: 0) }

{{ visibility() }}fun RustBuffer.asByteBuffer(): ByteBuffer? {
    {% call kt::check_rust_buffer_length("len") %}
    val dataPtr = data ?: return null
    return ByteBuffer(dataPtr, len.toInt())
}

// `RustBufferByValue` — the same three fields but materialized Kotlin-side.
// Used when the FFI passes `RustBuffer` by value (struct-flattened).
{{ visibility() }}data class RustBufferByValue(
    {{ visibility() }}val capacity: Long,
    {{ visibility() }}val len: Long,
    {{ visibility() }}val data: Pointer?,
)

{{ visibility() }}fun RustBufferByValue.asByteBuffer(): ByteBuffer? {
    {% call kt::check_rust_buffer_length("len") %}
    val dataPtr = data ?: return null
    return ByteBuffer(dataPtr, len.toInt())
}

// `ForeignBytes` — Kotlin → Rust borrowed slice. Same triple shape as
// uniffi expects; on Wasm the `data` pointer points into Rust memory
// after a Kotlin → Rust copy (the 2-copy boundary from Decision 1).
internal data class ForeignBytes(
    internal val len: Int,
    internal val data: Pointer?,
)

internal data class ForeignBytesByValue(
    internal val len: Int,
    internal val data: Pointer?,
)
