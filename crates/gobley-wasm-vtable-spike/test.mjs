/**
 * T0.C.1 vtable feasibility spike — JS test harness.
 *
 * Goal: prove that host-imported functions, declared in Rust as
 * `extern "C"` and observed via `as *const ()`, are reachable from
 * Rust through `call_indirect` against `__indirect_function_table`.
 *
 * If yes:
 *   - JS instantiates the Rust wasm with three callbacks under the
 *     import module "env" (Rust default for unnamed extern blocks).
 *   - Rust's `dispatch(method, handle, a, b)` performs an indirect
 *     call through the stored function pointer; the JS callback
 *     observes the args.
 *   - `vtable_index(method)` returns the value Rust stored, which
 *     equals the wasm-ld-assigned table index. JS cross-checks via
 *     `instance.exports.__indirect_function_table.get(idx)`.
 *
 * Re-instantiation test: spin up two independent instances, confirm
 * the indices match (proves we can bake them into codegen) or differ
 * (proves we must look them up at runtime).
 */

import { readFile } from "node:fs/promises";

const wasmPath = new URL(
  "./target/wasm32-unknown-unknown/release/gobley_wasm_vtable_spike.wasm",
  import.meta.url,
);

const bytes = await readFile(wasmPath);

function makeImports(label, log) {
  return {
    env: {
      on_data: (handle, a, b) =>
        log.push(`${label}:on_data(${handle}, ${a}, ${b})`),
      on_error: (handle, a, b) =>
        log.push(`${label}:on_error(${handle}, ${a}, ${b})`),
      on_complete: (handle, a, b) =>
        log.push(`${label}:on_complete(${handle}, ${a}, ${b})`),
    },
  };
}

async function spinUp(label) {
  const log = [];
  const { instance } = await WebAssembly.instantiate(bytes, makeImports(label, log));
  return { instance, log };
}

const a = await spinUp("A");
const b = await spinUp("B");

const aIdx = [0, 1, 2].map((m) => a.instance.exports.vtable_index(m));
const bIdx = [0, 1, 2].map((m) => b.instance.exports.vtable_index(m));

console.log("vtable_index per method (instance A):", aIdx);
console.log("vtable_index per method (instance B):", bIdx);

const aTable = a.instance.exports.__indirect_function_table;
console.log("table length (A):", aTable.length);

for (let i = 0; i < aTable.length; i++) {
  const fn = aTable.get(i);
  console.log(`  table[${i}] =`, fn === null ? "<null>" : "<funcref>");
}

console.log("\n-- direct dispatch (control) --");
a.instance.exports.dispatch_direct(7, 100, 200);

console.log("\n-- indirect dispatch via call_indirect --");
a.instance.exports.dispatch(0, 1, 11, 12); // on_data
a.instance.exports.dispatch(1, 2, 21, 22); // on_error
a.instance.exports.dispatch(2, 3, 31, 32); // on_complete

console.log("\n-- log (instance A) --");
for (const line of a.log) console.log(line);

const stable =
  aIdx.length === bIdx.length && aIdx.every((v, i) => v === bIdx[i]);
console.log("\nindices stable across re-instantiation:", stable);

const expectedFromTable = [];
for (const idx of aIdx) {
  expectedFromTable.push(aTable.get(idx) !== null);
}
console.log("each vtable_index() points to a real funcref:", expectedFromTable);

// Final summary line that the spike doc quotes.
const verdict =
  stable && expectedFromTable.every(Boolean)
    ? "CLEAN PATH FEASIBLE"
    : "UGLY PATH REQUIRED";
console.log(`\nVERDICT: ${verdict}`);
