{% include "ffi/ObjectCleanerHelper.kt" %}

// Kotlin/Wasm cleaner — T0.B Decision 5 / risk #5.
//
// Kotlin/Wasm has no `java.lang.ref.Cleaner` or `kotlin.native.ref.Cleaner`.
// JS `FinalizationRegistry` exists but Kotlin/Wasm 2.x doesn't expose
// it through the stdlib; we declare it `external` and bridge via JS.
//
// Behaviour: explicit-close is the contract. The registry only fires
// as a *safety net* if the user forgot to call `.close()` /
// `.use {}`. Hot SDK paths must always close explicitly — finalizers
// are not guaranteed to run, and on Wasm-GC their timing is wholly
// implementation-defined.
//
// `register(resource, disposable)` returns a `Cleanable`. Calling
// `clean()` runs the disposable and unregisters from the JS-side
// `FinalizationRegistry` (idempotent — the first invocation wins).

private external class FinalizationRegistry(callback: (JsAny) -> Unit) : JsAny {
    fun register(target: JsAny, heldValue: JsAny, unregisterToken: JsAny = definedExternally)
    fun unregister(unregisterToken: JsAny)
}

private class WasmJsCleaner : UniffiCleaner {
    // TODO(T0.C.4): wire up a single shared FinalizationRegistry whose
    // callback runs the disposable for the heldValue. Kept private so
    // the rest of the bindings get a Cleaner-shaped abstraction.
    override fun register(resource: Any, disposable: Disposable): UniffiCleaner.Cleanable =
        WasmJsCleanable(disposable)
}

private class WasmJsCleanable(
    private val disposable: Disposable,
) : UniffiCleaner.Cleanable {
    private var cleaned: Boolean = false

    override fun clean() {
        if (cleaned) return
        cleaned = true
        disposable.destroy()
    }
}

private fun UniffiCleaner.Companion.create(): UniffiCleaner = WasmJsCleaner()
