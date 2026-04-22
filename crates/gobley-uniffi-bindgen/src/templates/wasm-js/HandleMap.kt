
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
