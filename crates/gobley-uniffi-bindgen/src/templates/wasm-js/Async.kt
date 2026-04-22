{% include "ffi/Async.kt" %}

// Kotlin/Wasm async support — T0.B Decision 4.
//
// `templates/ffi/Async.kt` (included above) drives `withContext(Dispatchers.IO)`.
// Kotlin/Wasm has no IO dispatcher (single-threaded JS event loop).
// The shared template references `Dispatchers.IO` directly; for the
// Wasm target we'll need a downstream patch (T0.C.5) to either:
//   (a) substitute the dispatcher at askama-render time via a filter, or
//   (b) provide a `kotlinx.coroutines.IO` shim that resolves to
//       `Dispatchers.Default` on Wasm.
// Decision deferred to T0.C.5.

internal val uniffiRustFutureContinuationCallbackCallback: UniffiRustFutureContinuationCallback =
    { handle: Long, pollResult: Byte ->
        // TODO(T0.C.5): this closure must be reachable from the Rust
        // wasm via `call_indirect`. Add `@JsExport` shim + register its
        // table index at init.
        uniffiContinuationHandleMap.remove(handle).resume(pollResult)
    }

{%- if ci.has_async_callback_interface_definition() %}

internal val uniffiForeignFutureFreeImpl: UniffiForeignFutureFree =
    { handle: Long ->
        val job = uniffiForeignFutureHandleMap.remove(handle)
        if (!job.isCompleted) {
            job.cancel()
        }
    }

{%- endif %}
