
// Kotlin/Wasm ByteBuffer.
//
// API mirrors the JVM (`java.nio.ByteBuffer` wrapper) and Native
// (manual shift-and-mask) variants exactly so the shared
// `templates/ffi/*` lift/lower codegen stays target-agnostic.
//
// Storage model: a base `Pointer` (i32 address into the *Rust* wasm
// module's linear memory) + `capacity` + `position`. Reads and writes
// go through `WasmMemoryView` (filled in by T0.C.3) which holds the
// JS-side `DataView`/`Int8Array` over `rustExports.memory.buffer`.
// The view must be re-acquired after every Rust allocation
// (memory growth invalidates the cached `buffer`) — `WasmMemoryView`
// owns that policy.
//
// Wire format: BIG_ENDIAN. Matches JVM (`order(BIG_ENDIAN)`) and
// Native (manual shifts). Required for byte-for-byte compatibility
// with Rust's `to_be_bytes()` writes inside `RustBuffer` payloads.

{{ visibility() }}class ByteBuffer(
    internal val pointer: Pointer,
    internal val capacity: Int,
    internal var position: Int = 0,
) {
    {{ visibility() }}fun position(): Int = position

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
        // TODO(T0.C.3): WasmMemoryView.getByte(pointer + position++)
        val value = WasmMemoryView.getByte(pointer + position)
        position += 1
        return value
    }

    {{ visibility() }}fun get(bytesToRead: Int): ByteArray {
        checkRemaining(bytesToRead)
        val result = ByteArray(bytesToRead)
        if (result.isNotEmpty()) {
            // TODO(T0.C.3): WasmMemoryView.getBytes(pointer + position, result)
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
        val b0 = WasmMemoryView.getByte(pointer + position).toInt() and 0xff
        val b1 = WasmMemoryView.getByte(pointer + position + 1).toInt() and 0xff
        val b2 = WasmMemoryView.getByte(pointer + position + 2).toInt() and 0xff
        val b3 = WasmMemoryView.getByte(pointer + position + 3).toInt() and 0xff
        position += 4
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    {{ visibility() }}fun getLong(): Long {
        checkRemaining(8)
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or
                (WasmMemoryView.getByte(pointer + position + i).toLong() and 0xffL)
        }
        position += 8
        return result
    }

    {{ visibility() }}fun getFloat(): Float = Float.fromBits(getInt())

    {{ visibility() }}fun getDouble(): Double = Double.fromBits(getLong())

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
        WasmMemoryView.setByte(pointer + position, ((value ushr 24) and 0xff).toByte())
        WasmMemoryView.setByte(pointer + position + 1, ((value ushr 16) and 0xff).toByte())
        WasmMemoryView.setByte(pointer + position + 2, ((value ushr 8) and 0xff).toByte())
        WasmMemoryView.setByte(pointer + position + 3, (value and 0xff).toByte())
        position += 4
    }

    {{ visibility() }}fun putLong(value: Long) {
        checkRemaining(8)
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            WasmMemoryView.setByte(
                pointer + position + i,
                ((value ushr shift) and 0xffL).toByte(),
            )
        }
        position += 8
    }

    {{ visibility() }}fun putFloat(value: Float): Unit = putInt(value.toRawBits())

    {{ visibility() }}fun putDouble(value: Double): Unit = putLong(value.toRawBits())
}
