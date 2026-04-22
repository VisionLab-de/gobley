{% include "ffi/RustBufferTemplate.kt" %}

// RustBuffer for Kotlin/Wasm.
//
// In Rust the layout (uniffi_core 0.29.5 `ffi/rustbuffer.rs:51-62`) is:
//
//   #[repr(C)] struct RustBuffer { capacity: u64, len: u64, data: *mut u8 }
//
// On wasm32 the C ABI lays this out as:
//   * offset  0: capacity (i64)               — 8 bytes
//   * offset  8: len      (i64)               — 8 bytes
//   * offset 16: data     (i32 wasm pointer)  — 4 bytes
//   * offset 20: tail pad (4 bytes — struct alignment is 8 because of u64)
//   * total: 24 bytes
//
// Verified by `wasm-tools print` of `ffi_<crate>_rustbuffer_alloc` (the
// outer trampoline writes `i64.store offset=0/8`, `i32.store offset=16/20`
// to the caller's sret slot and the inner `uniffi_rustbuffer_alloc` reads
// `i64.load offset=8/16` + `i32.load offset=24/28` from its own sret slot).
//
// We follow T0.B Decision 2:
//   * `RustBuffer` = a 1-i32 value class wrapping the i32 pointer to that
//     24-byte struct in *Rust* linear memory.
//   * `RustBufferByValue` = a Kotlin data class of the three logical fields
//     (capacity/len as `Long` for byte-for-byte API compat with JVM/Native;
//     data as nullable `Pointer`), used for direct-passed `RustBuffer`
//     arguments (uniffi often passes RustBuffers by value, struct-flattened
//     across the FFI).
//
// `capacity` and `len` are exposed as `Long` for byte-for-byte API
// compatibility with the JVM/Native templates (which sign-extend u64 to
// `Long`). The accessors below read/write the full i64 via
// `WasmMemoryView.getLong/setLong`.

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
        internal const val SIZE_BYTES: Int = 24
        internal const val OFFSET_CAPACITY: Int = 0
        internal const val OFFSET_LEN: Int = 8
        internal const val OFFSET_DATA: Int = 16
    }
}

{{ visibility() }}var RustBuffer.capacity: Long
    get() = WasmMemoryView.getLong(ptr + RustBuffer.OFFSET_CAPACITY)
    set(value) { WasmMemoryView.setLong(ptr + RustBuffer.OFFSET_CAPACITY, value) }

{{ visibility() }}var RustBuffer.len: Long
    get() = WasmMemoryView.getLong(ptr + RustBuffer.OFFSET_LEN)
    set(value) { WasmMemoryView.setLong(ptr + RustBuffer.OFFSET_LEN, value) }

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
