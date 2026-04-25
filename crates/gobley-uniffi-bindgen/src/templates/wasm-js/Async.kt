{% include "ffi/Async.kt" %}

// Kotlin/Wasm async support — T0.B Decision 4 + T0.C.5.
//
// Single-threaded JS event loop, no `Thread`, no `runBlocking`.
// `kotlinx-coroutines-core-wasm-js` provides `Dispatchers.Default`,
// `suspendCancellableCoroutine`, `GlobalScope`, and `Job` — used unchanged
// by the shared `ffi/Async.kt` body included above.
//
// Three pieces of glue live in this file:
//
//   1. `Dispatchers.IO` shim — the shared `ffi/Async.kt` body wraps the
//      poll loop in `withContext(Dispatchers.IO)`. Kotlin/Wasm has no IO
//      dispatcher; we surface `IO` as an extension property aliasing to
//      `Default`. Conventional pattern for Wasm-only ports of JVM-shaped
//      coroutine APIs (T0.B Decision 4 chose option (b)).
//
//   2. Continuation callback shim — Rust's `rust_future_poll_<T>(handle,
//      callback_fn_ptr, callback_data)` invokes `callback_fn_ptr` via
//      `call_indirect` once the Rust future is ready. The actual table
//      entry the Rust side dispatches against is the JS-host import
//      `gobley_async_continuation_callback`, declared `@JsExport` below.
//      The Kotlin-typed `uniffiRustFutureContinuationCallbackCallback`
//      value carries the same body for Kotlin-internal callers; the JS
//      shim layer (T0.C.6) ignores the value passed across the FFI and
//      routes Rust's `call_indirect` to the cached `@JsExport` table
//      index.
//
//   3. Foreign-future free shim (gated by
//      `has_async_callback_interface_definition`) — Rust calls back into
//      the host when it drops a foreign future, so the Kotlin
//      `GlobalScope.launch` job can be cancelled. Same dual-form
//      (Kotlin closure + `@JsExport` shim) as the continuation callback.

// `Dispatchers.IO` shim. `withContext(Dispatchers.IO)` in shared
// `ffi/Async.kt` resolves to `Dispatchers.Default` on Wasm. There is no
// thread switch on Wasm — both dispatchers run on the JS event loop —
// so this is purely a name alias kept for source compatibility with the
// shared template.
internal val Dispatchers.IO: kotlinx.coroutines.CoroutineDispatcher
    get() = Default

// Kotlin-side typealias-typed continuation value.
//
// On JVM/Native this is the actual function pointer Rust receives. On
// Wasm the JS shim layer ignores this value when forwarding the
// `pollFunc` call into the Rust import — it always routes to the
// `gobley_async_continuation_callback` `@JsExport` below. The body is
// kept identical so any Kotlin-internal call site stays correct, and so
// the typealias contract remains satisfiable.
internal val uniffiRustFutureContinuationCallbackCallback: UniffiRustFutureContinuationCallback =
    { data: Long, pollResult: Byte ->
        uniffiContinuationHandleMap.remove(data).resume(pollResult)
    }
// `@JsExport` continuation callback.
//
// This is the function Rust's `__indirect_function_table` actually
// invokes when a future signals readiness. The JS glue (T0.C.6) imports
// it under module `gobley_callbacks` (same convention as per-interface
// callback dispatchers — see `CallbackInterfaceImpl.kt`). One single
// function suffices for the whole binding because the continuation ABI
// is `(callback_data: u64, poll_result: u8) -> ()` for every async fn.
@JsExport
public fun gobley_{{ ci.namespace() }}_async_continuation_callback(data: Long, pollResult: Byte) {
    uniffiContinuationHandleMap.remove(data).resume(pollResult)
}

{%- if ci.has_async_callback_interface_definition() %}

// Kotlin-side typealias-typed foreign-future-free value. See the
// `uniffiRustFutureContinuationCallbackCallback` notes above for why
// both a Kotlin closure and a `@JsExport` shim coexist.
internal val uniffiForeignFutureFreeImpl: UniffiForeignFutureFree =
    { handle: Long ->
        val job = uniffiForeignFutureHandleMap.remove(handle)
        if (!job.isCompleted) {
            job.cancel()
        }
    }

// `@JsExport` foreign-future-free callback. Invoked by Rust when it
// drops a foreign future the Kotlin side launched. Cancels the Kotlin
// `Job` if the coroutine is still running.
@JsExport
public fun gobley_{{ ci.namespace() }}_foreign_future_free(handle: Long) {
    val job = uniffiForeignFutureHandleMap.remove(handle)
    if (!job.isCompleted) {
        job.cancel()
    }
}

{%- endif %}
