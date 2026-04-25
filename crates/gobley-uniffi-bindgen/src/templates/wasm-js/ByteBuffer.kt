
// Kotlin/Wasm ByteBuffer.
//
// API mirrors the JVM (`java.nio.ByteBuffer` wrapper) and Native
// (manual shift-and-mask) variants exactly so the shared
// `templates/ffi/*` lift/lower codegen stays target-agnostic. The
// surface is intentionally minimal — only what `FfiConverterTemplate.kt`
// actually calls (`position()`, `limit()`, `hasRemaining()`, primitive
// `getX()/putX()`, `get(byte[])`, `put(byte[])`).
//
// Storage model: a base `Pointer` (i32 address into the *Rust* wasm
// module's linear memory) + `capacity` + `position`. Reads and writes
// go through `WasmMemoryView` which holds the JS-side `DataView` over
// `rustExports.memory.buffer`. The view is re-acquired on every call
// (memory growth invalidates the cached `buffer`) — `WasmMemoryView`
// owns that policy.
//
// Wire format: BIG_ENDIAN. Matches JVM (`order(BIG_ENDIAN)`) and
// Native (manual shifts). Required for byte-for-byte compatibility
// with Rust's `to_be_bytes()` writes inside `RustBuffer` payloads.
// Multi-byte reads delegate to `WasmMemoryView.getInt/getLong/...`,
// which call into a BIG_ENDIAN `DataView` — single JS hop per primitive
// instead of one hop per byte.

{{ visibility() }}class ByteBuffer(
    internal val pointer: Pointer,
    internal val capacity: Int,
    internal var position: Int = 0,
) {
    {{ visibility() }}fun position(): Int = position

    // Mirrors `java.nio.ByteBuffer.limit()`. We don't track a separate
    // limit cursor on Kotlin/Wasm — the buffer is always sized exactly
    // to its `capacity`, matching how `RustBuffer.asByteBuffer()` builds
    // it with `limit = len`.
    // Construction sites: see asByteBuffer() in wasm-js/RustBufferTemplate.kt;
    // if any future caller needs limit < capacity, this method must back a
    // real cursor field.
    {{ visibility() }}fun limit(): Int = capacity

    {{ visibility() }}fun hasRemaining(): Boolean = capacity != position

    private fun checkRemaining(bytes: Int) {
        val remaining = capacity - position
        require(bytes <= remaining) {
            "buffer is exhausted: required: $bytes, remaining: $remaining, " +
                "capacity: $capacity, position: $position"
        }
    }

    {{ visibility() }}fun get(): Byte {
        checkRemaining(1)
        val value = WasmMemoryView.getByte(pointer + position)
        position += 1
        return value
    }

    {{ visibility() }}fun get(bytesToRead: Int): ByteArray {
        checkRemaining(bytesToRead)
        val result = ByteArray(bytesToRead)
        if (result.isNotEmpty()) {
            WasmMemoryView.getBytes(pointer + position, result)
            position += bytesToRead
        }
        return result
    }

    {{ visibility() }}fun getShort(): Short {
        checkRemaining(2)
        val hi = WasmMemoryView.getByte(pointer + position).toInt() and 0xff
        val lo = WasmMemoryView.getByte(pointer + position + 1).toInt() and 0xff
        position += 2
        return ((hi shl 8) or lo).toShort()
    }

    {{ visibility() }}fun getInt(): Int {
        checkRemaining(4)
        val value = WasmMemoryView.getIntBE(pointer + position)
        position += 4
        return value
    }

    {{ visibility() }}fun getLong(): Long {
        checkRemaining(8)
        val value = WasmMemoryView.getLongBE(pointer + position)
        position += 8
        return value
    }

    {{ visibility() }}fun getFloat(): Float {
        checkRemaining(4)
        val value = WasmMemoryView.getFloatBE(pointer + position)
        position += 4
        return value
    }

    {{ visibility() }}fun getDouble(): Double {
        checkRemaining(8)
        val value = WasmMemoryView.getDoubleBE(pointer + position)
        position += 8
        return value
    }

    {{ visibility() }}fun put(value: Byte) {
        checkRemaining(1)
        WasmMemoryView.setByte(pointer + position, value)
        position += 1
    }

    {{ visibility() }}fun put(src: ByteArray) {
        checkRemaining(src.size)
        if (src.isNotEmpty()) {
            WasmMemoryView.setBytes(pointer + position, src)
            position += src.size
        }
    }

    {{ visibility() }}fun putShort(value: Short) {
        checkRemaining(2)
        WasmMemoryView.setByte(pointer + position, ((value.toInt() ushr 8) and 0xff).toByte())
        WasmMemoryView.setByte(pointer + position + 1, (value.toInt() and 0xff).toByte())
        position += 2
    }

    {{ visibility() }}fun putInt(value: Int) {
        checkRemaining(4)
        WasmMemoryView.setIntBE(pointer + position, value)
        position += 4
    }

    {{ visibility() }}fun putLong(value: Long) {
        checkRemaining(8)
        WasmMemoryView.setLongBE(pointer + position, value)
        position += 8
    }

    {{ visibility() }}fun putFloat(value: Float) {
        checkRemaining(4)
        WasmMemoryView.setFloatBE(pointer + position, value)
        position += 4
    }

    {{ visibility() }}fun putDouble(value: Double) {
        checkRemaining(8)
        WasmMemoryView.setDoubleBE(pointer + position, value)
        position += 8
    }
}
