
// Kotlin/Wasm pointer model.
//
// Cross-module pointers carry as wasm `i32` (T0.B Decision 1).
// `Pointer` is a typealias for `Int`; nullability uses Kotlin's
// nullable-`Int` semantics (`Pointer?`). `0` is reserved as the
// null-pointer sentinel — matches Rust's `null_mut()` and the
// `__indirect_function_table[0] = null` from the T0.C.1 spike.
//
// `toLong()` / `toPointer()` adapters preserve the Long-keyed HandleMap
// API (kept identical across JVM/Native/Wasm so shared FFI templates
// don't fork). The high 32 bits stay zero on Wasm; conversion is
// lossless either way.

internal typealias Pointer = Int
internal val NullPointer: Pointer? = null

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
internal fun Pointer.toLong(): Long = (this as Int).toLong() and 0xFFFFFFFFL

internal fun kotlin.Long.toPointer(): Pointer = this.toInt()

// Pointer arithmetic helper. Used by `ByteBuffer.slice()`-style call sites
// and the FFI-struct extension accessors (each field offset is a constant).
@Suppress("NOTHING_TO_INLINE")
internal inline fun Pointer.share(offset: Int): Pointer = this + offset

// JS-glue side memory accessors.
//
// `WasmMemoryView` is the single bridge between Kotlin/Wasm linear
// memory and the Rust wasm32-unknown-unknown module's linear memory.
// All actual byte access happens on the JS side via a `DataView` over
// `rustExports.memory.buffer`, which is acquired fresh on every call —
// memory growth invalidates the underlying `ArrayBuffer` reference
// (T0.B Decision 1 risk #3). The single-call refresh keeps the policy
// trivially correct at the cost of one indirection per primitive read;
// the JS shim is hot-path JIT-friendly so the cost is negligible
// compared to the i32-marshalling already on the wire.
//
// Wire format is BIG_ENDIAN to match JVM (`order(BIG_ENDIAN)`) and
// Native (manual shifts) — required for byte-for-byte compatibility
// with Rust's `to_be_bytes()` writes inside `RustBuffer` payloads.
//
// The `external fun` declarations below import host functions from a
// JS module named `<crate>_wasmjs_helpers.mjs`. T0.C.6 generates that
// module per-bindgen-run; bindings emitted by T0.C.3 just declare the
// surface.

@JsFun("(addr) => globalThis.__gobleyWasmMemory.getInt8(addr)")
internal external fun __gobley_wasm_get_byte(addr: Int): Byte

@JsFun("(addr, value) => globalThis.__gobleyWasmMemory.setInt8(addr, value)")
internal external fun __gobley_wasm_set_byte(addr: Int, value: Byte)

@JsFun("(addr) => globalThis.__gobleyWasmMemory.getInt32(addr, false)")
internal external fun __gobley_wasm_get_int(addr: Int): Int

@JsFun("(addr, value) => globalThis.__gobleyWasmMemory.setInt32(addr, value, false)")
internal external fun __gobley_wasm_set_int(addr: Int, value: Int)

@JsFun("(addr) => globalThis.__gobleyWasmMemory.getBigInt64(addr, false)")
internal external fun __gobley_wasm_get_long(addr: Int): Long

@JsFun("(addr, value) => globalThis.__gobleyWasmMemory.setBigInt64(addr, value, false)")
internal external fun __gobley_wasm_set_long(addr: Int, value: Long)

@JsFun("(addr) => globalThis.__gobleyWasmMemory.getFloat32(addr, false)")
internal external fun __gobley_wasm_get_float(addr: Int): Float

@JsFun("(addr, value) => globalThis.__gobleyWasmMemory.setFloat32(addr, value, false)")
internal external fun __gobley_wasm_set_float(addr: Int, value: Float)

@JsFun("(addr) => globalThis.__gobleyWasmMemory.getFloat64(addr, false)")
internal external fun __gobley_wasm_get_double(addr: Int): Double

@JsFun("(addr, value) => globalThis.__gobleyWasmMemory.setFloat64(addr, value, false)")
internal external fun __gobley_wasm_set_double(addr: Int, value: Double)

// Bulk byte transfer between Kotlin `ByteArray` and Rust linear memory.
// This is the *Kotlin → Rust* direction of the 2-copy boundary
// (T0.B Decision 1): Kotlin reads its `ByteArray` element-by-element
// (or via the shim helper below) and the JS side pokes each byte into
// the Rust `Uint8Array`. The shim is responsible for re-acquiring the
// view if Rust memory grew between the previous call and this one.
//
// For now the bulk paths route through per-byte `setInt8`/`getInt8`.
// T0.C.6 may replace the shim with a `Uint8Array.set(...)` fast path
// once we settle the Kotlin/Wasm `ByteArray ↔ JS typed array` ABI;
// that's a perf optimization, not a correctness fix.
internal object WasmMemoryView {
    fun getByte(addr: Pointer): Byte = __gobley_wasm_get_byte(addr)

    fun setByte(addr: Pointer, value: Byte): Unit = __gobley_wasm_set_byte(addr, value)

    fun getInt(addr: Pointer): Int = __gobley_wasm_get_int(addr)

    fun setInt(addr: Pointer, value: Int): Unit = __gobley_wasm_set_int(addr, value)

    fun getLong(addr: Pointer): Long = __gobley_wasm_get_long(addr)

    fun setLong(addr: Pointer, value: Long): Unit = __gobley_wasm_set_long(addr, value)

    fun getFloat(addr: Pointer): Float = __gobley_wasm_get_float(addr)

    fun setFloat(addr: Pointer, value: Float): Unit = __gobley_wasm_set_float(addr, value)

    fun getDouble(addr: Pointer): Double = __gobley_wasm_get_double(addr)

    fun setDouble(addr: Pointer, value: Double): Unit = __gobley_wasm_set_double(addr, value)

    fun getBytes(addr: Pointer, dst: ByteArray) {
        // Per-byte copy. T0.C.6 may swap to a single typed-array `subarray`
        // memcpy on the JS side once the ABI is fixed.
        for (i in dst.indices) {
            dst[i] = __gobley_wasm_get_byte(addr + i)
        }
    }

    fun setBytes(addr: Pointer, src: ByteArray) {
        for (i in src.indices) {
            __gobley_wasm_set_byte(addr + i, src[i])
        }
    }
}
