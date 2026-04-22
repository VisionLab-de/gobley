
// HandleMap for Kotlin/Wasm.
//
// Same `Long`-keyed shape as JVM and Native so the shared `ffi/Async.kt`,
// `ffi/CallbackInterfaceRuntime.kt`, and `FfiConverterCallbackInterface`
// stay target-agnostic. Single-threaded JS event loop on Wasm means no
// concurrent access — plain `HashMap` + plain `Long` counter, no locks.
//
// Handles start at 1L (matches Native) so a returned 0 is unambiguously
// "null" / "uninitialized". Used for callback objects, async
// continuations (`uniffiContinuationHandleMap`), and foreign futures
// (`uniffiForeignFutureHandleMap`) — all defined in shared FFI templates.

internal class UniffiHandleMap<T : Any> {
    private val map = HashMap<Long, T>()
    private var counter: Long = 1L

    internal val size: Int
        get() = map.size

    // Insert a new object into the handle map and get a handle for it
    internal fun insert(obj: T): Long {
        val handle = counter
        counter += 1
        map[handle] = obj
        return handle
    }

    // Get an object from the handle map
    internal fun get(handle: Long): T =
        map[handle] ?: throw InternalException("UniffiHandleMap.get: Invalid handle")

    // Remove an entry from the handlemap and get the Kotlin object back
    internal fun remove(handle: Long): T =
        map.remove(handle) ?: throw InternalException("UniffiHandleMap.remove: Invalid handle")
}

// `*ByReference` value classes for the Kotlin/Wasm callback runtime.
//
// On JVM these come from `com.sun.jna.ptr.*`. On Native they come from
// `crates/.../native/ReferenceHelper.kt` (which the wasm-js wrapper does
// not include). Per-callback `CallbackInterfaceImpl.kt` is included once
// per callback interface, so any type defined there would collide on a
// crate with two or more callback interfaces (e.g. the `callbacks`
// fixture has `ForeignGetters` + `StoredForeignStringifier`). HandleMap.kt
// is included exactly once from `wrapper.kt`, so the singleton helpers
// live here. T0.C.5 may extract a sibling `wasm-js/ReferenceHelper.kt`
// to mirror Native's layout — kept here for now to stay within T0.C.4
// scope.
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

@kotlin.jvm.JvmInline
internal value class ByteByReference(internal val ptr: Pointer)

internal fun ByteByReference.setValue(value: Byte): Unit =
    WasmMemoryView.setByte(ptr, value)

internal fun ByteByReference.getValue(): Byte =
    WasmMemoryView.getByte(ptr)

@kotlin.jvm.JvmInline
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

@kotlin.jvm.JvmInline
internal value class IntByReference(internal val ptr: Pointer)

internal fun IntByReference.setValue(value: Int): Unit =
    WasmMemoryView.setInt(ptr, value)

internal fun IntByReference.getValue(): Int =
    WasmMemoryView.getInt(ptr)

@kotlin.jvm.JvmInline
internal value class LongByReference(internal val ptr: Pointer)

internal fun LongByReference.setValue(value: Long): Unit =
    WasmMemoryView.setLong(ptr, value)

internal fun LongByReference.getValue(): Long =
    WasmMemoryView.getLong(ptr)

@kotlin.jvm.JvmInline
internal value class FloatByReference(internal val ptr: Pointer)

internal fun FloatByReference.setValue(value: Float): Unit =
    WasmMemoryView.setFloat(ptr, value)

internal fun FloatByReference.getValue(): Float =
    WasmMemoryView.getFloat(ptr)

@kotlin.jvm.JvmInline
internal value class DoubleByReference(internal val ptr: Pointer)

internal fun DoubleByReference.setValue(value: Double): Unit =
    WasmMemoryView.setDouble(ptr, value)

internal fun DoubleByReference.getValue(): Double =
    WasmMemoryView.getDouble(ptr)

@kotlin.jvm.JvmInline
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
