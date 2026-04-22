{% include "ffi/RustBufferTemplate.kt" %}

// RustBuffer for Kotlin/Wasm.
//
// In Rust the layout is `{ capacity: u32, len: u32, data: *mut u8 }` (12 bytes).
// We follow T0.B Decision 2:
//   * `RustBuffer` = a 1-i32 value class wrapping the i32 pointer to that
//     12-byte struct in *Rust* linear memory.
//   * `RustBufferByValue` = a Kotlin data class of the three i32 fields
//     (re-typed to `Long` for byte-for-byte API compat with JVM/Native),
//     used for direct-passed `RustBuffer` arguments (uniffi often passes
//     RustBuffers by value, struct-flattened across the FFI).
//
// `capacity` and `len` are exposed as `Long` for byte-for-byte API
// compatibility with the JVM/Native templates (which sign-extend u64 to
// `Long`). On Wasm they're really u32, so we mask off the high bits when
// converting from `Long` back to `Int` for the underlying wasm calls.

/** Pointer width on wasm32; revisit if memory64 ever ships. */
internal const val WASM_POINTER_SIZE_BYTES: Int = 4

// `value class` is sufficient on Kotlin/Wasm — `@kotlin.jvm.JvmInline` is an
// `@OptionalExpectation` that only resolves on JVM/Android source sets.
//
// The companion takes the same visibility as `RustBuffer` itself: the
// `RustBuffer.Companion.from(...)` extension below is also emitted with
// `{{ visibility() }}`, and a `public` extension on an `internal` receiver
// trips "'public' member exposes its 'internal' receiver type 'Companion'".
{{ visibility() }}value class RustBuffer(internal val ptr: Pointer) {
    {{ visibility() }}companion object {
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

// `asByteBuffer` produces a *read/write* cursor over Rust memory at this
// buffer's `data` pointer with `limit = len`. NOT a copy — both reads and
// writes against the returned `ByteBuffer` go straight through
// `WasmMemoryView` and land in Rust linear memory. This is the
// zero-copy path on the *Rust → Kotlin* direction (Kotlin reads bytes
// out of the buffer cursor; the JS shim copies one primitive at a time
// into Kotlin's stack/locals — strictly cheaper than allocating a
// Kotlin `ByteArray` mirror).
//
// LIFETIME: the returned ByteBuffer shares memory with this RustBuffer. If
// the underlying RustBuffer is freed (RustBufferHelper.free), the ByteBuffer
// becomes a dangling view into reused/zeroed Rust linear memory. Do not
// retain past RustBufferHelper.free().
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

// `from(bb)` — the *Kotlin → Rust* 2-copy bridge (T0.B Decision 1).
//
// Currently no callers; reserved for future call sites that allocate
// Kotlin-side ByteArray and need a zero-friction RustBuffer wrap. The
// String lower path uses allocValue + asByteBuffer().put() instead
// (see templates/ffi/StringHelper.kt:22-28).
//
// The two copies are:
//   1. Kotlin `ByteArray` (in Kotlin/Wasm linear memory) → JS `Uint8Array`
//      view (one `setInt8` per byte through `WasmMemoryView.setBytes`).
//   2. JS `Uint8Array` view → Rust linear memory (same call — the JS
//      shim writes directly into the `DataView` over `rustExports.memory`).
//
// Step 2 is collapsed into step 1 by the JS shim: `setBytes(ptr, src)`
// loops `setInt8(ptr+i, src[i])`, which is one JS hop per byte that
// lands in Rust memory directly. Net cost: one JS hop per byte, no
// intermediate buffer in JS land. T0.C.6 may swap to a typed-array
// memcpy fast path once the Kotlin/Wasm `ByteArray ↔ Uint8Array` ABI
// is settled.
{{ visibility() }}fun RustBuffer.Companion.from(bytes: ByteArray): RustBufferByValue {
    val rbuf = RustBufferHelper.allocValue(bytes.size.toULong())
    val dataPtr = rbuf.data
        ?: throw RuntimeException("RustBuffer.from: alloc returned null data pointer")
    if (bytes.isNotEmpty()) {
        WasmMemoryView.setBytes(dataPtr, bytes)
    }
    return RustBufferByValue(
        capacity = rbuf.capacity,
        len = bytes.size.toLong(),
        data = rbuf.data,
    )
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
