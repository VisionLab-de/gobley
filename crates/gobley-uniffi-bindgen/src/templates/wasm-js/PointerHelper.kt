
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

// JS-glue side memory accessor.
//
// Holds the cached `DataView` / `Int8Array` over the Rust wasm module's
// `exports.memory.buffer`. Re-acquired after every allocating Rust call
// (memory growth invalidates the underlying ArrayBuffer reference) —
// see T0.B Decision 1 risk #3.
//
// T0.C.3 wires the actual JS shim via `@JsFun(...)` declarations and a
// hand-written JS module. T0.C.2 ships a TODO-only surface so the rest
// of the generated Kotlin can resolve symbols.
internal object WasmMemoryView {
    fun getByte(addr: Pointer): Byte {
        TODO("WasmMemoryView wired up in T0.C.3")
    }

    fun setByte(addr: Pointer, value: Byte) {
        TODO("WasmMemoryView wired up in T0.C.3")
    }

    fun getInt(addr: Pointer): Int {
        TODO("WasmMemoryView wired up in T0.C.3")
    }

    fun setInt(addr: Pointer, value: Int) {
        TODO("WasmMemoryView wired up in T0.C.3")
    }

    fun getBytes(addr: Pointer, dst: ByteArray) {
        TODO("WasmMemoryView wired up in T0.C.3")
    }

    fun setBytes(addr: Pointer, src: ByteArray) {
        TODO("WasmMemoryView wired up in T0.C.3")
    }
}
