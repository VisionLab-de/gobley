//! T0.C.1 vtable feasibility spike for Kotlin/Wasm callbacks.
//!
//! Question under test: when JS instantiates this wasm with three imported
//! callback functions, can Rust call them via `call_indirect` against the
//! `__indirect_function_table` indices observed at runtime?
//!
//! Strategy:
//!   - Declare the three imports under the default `extern "C"` module name
//!     (`env` per Rust/wasm-ld convention) with the callback ABI we expect for
//!     stream listeners (`i32, i32, i32`). Production binding selects a
//!     dedicated module name (e.g. `gobley_callbacks`) via `#[link(wasm_import_module = ...)]`;
//!     the spike uses the default to keep the test minimal — module name does
//!     not affect feasibility.
//!   - Take the address of each import in a `#[used] static [CallbackFn; 3]`.
//!     The wasm-ld linker reacts by appending each import to the
//!     `__indirect_function_table` (the "table-relocs" path); Rust code can
//!     then reach them through `call_indirect` rather than a direct call.
//!   - Expose three exports:
//!         * `vtable_index(method: u32) -> u32`
//!           Returns the table index assigned to each import. JS uses this to
//!           cross-check `instance.exports.__indirect_function_table.get(idx)`.
//!         * `dispatch(method: u32, handle: u32, a: u32, b: u32) -> ()`
//!           Forces a `call_indirect` through the stored function pointer.
//!           If the host import is reachable via the table, the JS callback
//!           runs and we observe arguments + side effects.
//!         * `dispatch_direct(handle: u32, a: u32, b: u32) -> ()`
//!           Control: direct `call $on_data` to confirm the import binding
//!           itself works regardless of indirect dispatch.
//!
//! No allocator is needed; we never heap-allocate. `panic = "abort"` keeps
//! `core::panicking` shims out of the binary so the wasm stays small enough
//! (~640 bytes) to eyeball with `wasm-tools print`.

#![no_std]

use core::panic::PanicInfo;

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    // Trap deterministically so the JS harness can detect it as a wasm trap
    // rather than silently looping.
    core::arch::wasm32::unreachable()
}

/// ABI of every callback in the spike — matches the listener shape from the
/// design doc (handle plus two payload words). Concrete listener methods will
/// pick narrower variants in production.
type CallbackFn = unsafe extern "C" fn(handle: u32, a: u32, b: u32);

extern "C" {
    #[link_name = "on_data"]
    fn cb_on_data(handle: u32, a: u32, b: u32);

    #[link_name = "on_error"]
    fn cb_on_error(handle: u32, a: u32, b: u32);

    #[link_name = "on_complete"]
    fn cb_on_complete(handle: u32, a: u32, b: u32);
}

/// Force the linker to materialise table indices for each import by observing
/// its address. `#[used]` keeps the static alive through dead-code elimination
/// even though only the exports below read it.
#[used]
static CALLBACKS: [CallbackFn; 3] = [cb_on_data, cb_on_error, cb_on_complete];

/// Echo the `__indirect_function_table` index of each callback so the JS
/// harness can cross-check against `table.get(idx)`.
///
/// Casts are intentionally `usize -> u32`: wasm32 pointers are 32-bit, and
/// table indices fit in `u32` by spec.
#[no_mangle]
pub extern "C" fn vtable_index(method: u32) -> u32 {
    let ptr = match method {
        0 => CALLBACKS[0] as usize,
        1 => CALLBACKS[1] as usize,
        2 => CALLBACKS[2] as usize,
        _ => return u32::MAX,
    };
    ptr as u32
}

/// Trigger a `call_indirect` through the stored function pointer.
///
/// Rust lowers `(CALLBACKS[i])(...)` to `call_indirect (type $sig)` against
/// `__indirect_function_table`. If the linker has not placed the import in
/// the table, instantiation will fail; if the signature index disagrees with
/// the host shim, the call traps with `RuntimeError: indirect call signature
/// mismatch`.
#[no_mangle]
pub extern "C" fn dispatch(method: u32, handle: u32, a: u32, b: u32) {
    if (method as usize) >= CALLBACKS.len() {
        return;
    }
    unsafe {
        (CALLBACKS[method as usize])(handle, a, b);
    }
}

/// Direct call of `on_data` for control comparison — proves the import
/// binding itself works regardless of table dispatch. JS sees the same
/// callback fire but via `call $import` rather than `call_indirect`.
#[no_mangle]
pub extern "C" fn dispatch_direct(handle: u32, a: u32, b: u32) {
    unsafe { cb_on_data(handle, a, b) }
}
