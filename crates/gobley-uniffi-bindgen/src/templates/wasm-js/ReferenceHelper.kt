
// `*ByReference` value classes for the Kotlin/Wasm callback runtime.
//
// On JVM these come from `com.sun.jna.ptr.*`. On Native they come from
// `crates/.../native/ReferenceHelper.kt`. This file is the wasm-js
// equivalent — extracted from `HandleMap.kt` at T0.C.5 to mirror Native's
// layout (housekeeping flagged in T0.C.4).
//
// `wrapper.kt` includes this file exactly once (right after `HandleMap.kt`
// and before `Types.kt`/`CallbackInterfaceImpl.kt`), so the singleton
// helpers are available to every per-callback dispatcher without
// collisions across multiple callback interfaces in one binding (e.g. the
// `callbacks` fixture's `ForeignGetters` + `StoredForeignStringifier`).
//
// Each `*ByReference` wraps a `Pointer` (= `Int` on Wasm) into Rust
// linear memory. `setValue` writes through `WasmMemoryView`, which
// re-acquires its `DataView` per primitive (memory-growth safety —
// T0.B Decision 1 risk #3).
//
// `RustBuffer.setValue(value: RustBufferByValue)` is already defined in
// the shared `ffi/RustBufferTemplate.kt` and works on Wasm without
// modification — its three-field write goes through the
// `var RustBuffer.{capacity,len,data}` setters in `RustBufferTemplate.kt`,
// each of which delegates to `WasmMemoryView`.
//
// All `*ByReference` classes below use plain `value class` (no `@JvmInline`):
// `@kotlin.jvm.JvmInline` is an `@OptionalExpectation` that only resolves on
// JVM/Android, so emitting it on the wasmJs source set triggers a compile
// error. The `value class` keyword alone is sufficient on Kotlin/Wasm.

internal value class ByteByReference(internal val ptr: Pointer)

internal fun ByteByReference.setValue(value: Byte): Unit =
    WasmMemoryView.setByte(ptr, value)

internal fun ByteByReference.getValue(): Byte =
    WasmMemoryView.getByte(ptr)

internal value class ShortByReference(internal val ptr: Pointer)

// `Short` (i16) emulated via two big-endian bytes — `WasmMemoryView` only
// exposes byte/int/long/float/double primitives in T0.C.3. Same pattern
// the wasm-js `ByteBuffer.kt` uses for its `getShort/putShort`.
internal fun ShortByReference.setValue(value: Short) {
    WasmMemoryView.setByte(ptr, ((value.toInt() ushr 8) and 0xff).toByte())
    WasmMemoryView.setByte(ptr + 1, (value.toInt() and 0xff).toByte())
}

internal fun ShortByReference.getValue(): Short {
    val hi = WasmMemoryView.getByte(ptr).toInt() and 0xff
    val lo = WasmMemoryView.getByte(ptr + 1).toInt() and 0xff
    return ((hi shl 8) or lo).toShort()
}

internal value class IntByReference(internal val ptr: Pointer)

internal fun IntByReference.setValue(value: Int): Unit =
    WasmMemoryView.setInt(ptr, value)

internal fun IntByReference.getValue(): Int =
    WasmMemoryView.getInt(ptr)

internal value class LongByReference(internal val ptr: Pointer)

internal fun LongByReference.setValue(value: Long): Unit =
    WasmMemoryView.setLong(ptr, value)

internal fun LongByReference.getValue(): Long =
    WasmMemoryView.getLong(ptr)

internal value class FloatByReference(internal val ptr: Pointer)

internal fun FloatByReference.setValue(value: Float): Unit =
    WasmMemoryView.setFloat(ptr, value)

internal fun FloatByReference.getValue(): Float =
    WasmMemoryView.getFloat(ptr)

internal value class DoubleByReference(internal val ptr: Pointer)

internal fun DoubleByReference.setValue(value: Double): Unit =
    WasmMemoryView.setDouble(ptr, value)

internal fun DoubleByReference.getValue(): Double =
    WasmMemoryView.getDouble(ptr)

internal value class PointerByReference(internal val ptr: Pointer)

// `Pointer` is `Int` (cross-module wasm i32 address). The pointee slot
// is an i32, written through `WasmMemoryView.setInt`. `null` lowers to
// the 0 sentinel matching `PointerHelper.NullPointer`.
internal fun PointerByReference.setValue(value: Pointer?): Unit =
    WasmMemoryView.setInt(ptr, value ?: 0)

internal fun PointerByReference.getValue(): Pointer? {
    val raw = WasmMemoryView.getInt(ptr)
    return if (raw == 0) null else raw
}
