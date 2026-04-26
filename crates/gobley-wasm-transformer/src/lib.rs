/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

pub mod import;
mod stack;
mod wasm_bindgen;

use std::collections::BTreeSet;

use askama::Template;
use base64::Engine;
use walrus::{
    ir::Value, ConstExpr, ElementItems, ElementKind, Export, ExportItem, Function, Global,
    GlobalKind, Import, ImportKind, Module, ModuleGlobals, ValType,
};

use self::import::WasmFunctionImport;

#[derive(Debug)]
pub struct Transformer {
    module: Module,
    function_imports: Vec<WasmFunctionImport>,
    global_entities: Vec<GlobalEntity>,
    wasm_bindgen_js_modules: Vec<WasmBindgenJsModules>,
}

#[derive(Debug, Clone)]
struct GlobalEntity {
    pub modifier: String,
    pub name: String,
    pub expr: String,
    pub ty: String,
    pub lang: GlobalEntityLang,
}

#[derive(Debug, Clone)]
struct WasmBindgenJsModules {
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
enum GlobalEntityLang {
    JavaScript,
    Kotlin,
}

// TODO: Make this private in the next version
#[derive(Template)]
#[template(syntax = "kt", escape = "none", path = "js.kt")]
pub struct KotlinJsRenderer<'a> {
    package_name: Option<&'a str>,
    base64: &'a str,
    module: &'a Module,
    global_entities: &'a [GlobalEntity],
    wasm_bindgen_js_modules: &'a [WasmBindgenJsModules],
}

impl<'a> KotlinJsRenderer<'a> {
    fn import_modules(&self) -> Vec<&str> {
        let import_modules = self
            .module
            .imports
            .iter()
            .map(|i| &i.module)
            .filter(|wasm_module_name| {
                self.wasm_bindgen_js_modules
                    .iter()
                    .all(|js_module| **wasm_module_name != js_module.name)
            })
            .collect::<BTreeSet<_>>();

        import_modules.iter().map(|i| i.as_str()).collect()
    }

    fn imports_from_module<'b>(
        &'b self,
        module: impl AsRef<str> + 'b,
    ) -> impl Iterator<Item = &'a Import> + 'b {
        self.module
            .imports
            .iter()
            .filter(move |i| i.module == module.as_ref())
    }

    fn import_to_kt_signature(&self, import: &Import) -> String {
        match import.kind {
            ImportKind::Function(id) => self.function_to_kt_signature(self.module.funcs.get(id)),
            ImportKind::Table(_) => "WebAssembly.Table".to_string(),
            ImportKind::Memory(_) => "WebAssembly.Memory".to_string(),
            ImportKind::Global(id) => Self::global_to_kt_signature(self.module.globals.get(id)),
        }
    }

    fn import_to_function_table_entry_idx(&self, import: &Import) -> Option<usize> {
        let ImportKind::Function(function_id) = import.kind else {
            return None;
        };

        let Ok(Some(main_function_table)) = self.module.tables.main_function_table() else {
            return None;
        };

        for element in self.module.elements.iter() {
            let ElementItems::Functions(function_ids) = &element.items else {
                continue;
            };
            let Some(offset) = function_ids.iter().position(|id| *id == function_id) else {
                continue;
            };
            let ElementKind::Active {
                table,
                offset: element_offset,
            } = &element.kind
            else {
                continue;
            };
            if main_function_table != *table {
                continue;
            }

            fn get_usize_from_constexpr(
                globals: &ModuleGlobals,
                expr: &ConstExpr,
            ) -> Option<usize> {
                Some(match expr {
                    ConstExpr::Value(value) => match value {
                        Value::I32(i32) => *i32 as usize,
                        Value::I64(i64) => *i64 as usize,
                        Value::F32(f32) => *f32 as usize,
                        Value::F64(f64) => *f64 as usize,
                        Value::V128(v128) => *v128 as usize,
                    },
                    ConstExpr::Global(id) => {
                        return match &globals.get(*id).kind {
                            GlobalKind::Local(expr) => get_usize_from_constexpr(globals, expr),
                            _ => None,
                        }
                    }
                    _ => return None,
                })
            }

            let Some(element_offset) =
                get_usize_from_constexpr(&self.module.globals, element_offset)
            else {
                continue;
            };

            return Some(offset + element_offset);
        }

        None
    }

    fn exports(&self) -> impl Iterator<Item = &Export> {
        self.module.exports.iter()
    }

    fn export_to_kt_signature(&self, export: &Export) -> String {
        match export.item {
            ExportItem::Function(id) => self.function_to_kt_signature(self.module.funcs.get(id)),
            ExportItem::Table(_) => "WebAssembly.Table".to_string(),
            ExportItem::Memory(_) => "WebAssembly.Memory".to_string(),
            ExportItem::Global(id) => Self::global_to_kt_signature(self.module.globals.get(id)),
        }
    }

    fn function_to_kt_signature(&self, function: &Function) -> String {
        let ty = self.module.types.get(function.ty());
        let mut output = String::new();
        let mut first = true;
        output.push('(');

        for param_str in ty.params().iter().map(Self::map_val_type_to_kt) {
            if !first {
                output.push_str(", ");
            }
            first = false;
            output.push_str(param_str);
        }

        output.push_str(") -> ");

        if let Some(result) = ty.results().first() {
            output.push_str(Self::map_val_type_to_kt(result));
        } else {
            output.push_str("Unit");
        }

        output
    }

    fn global_to_kt_signature(global: &Global) -> String {
        let inner_ty = Self::map_val_type_to_kt(&global.ty);
        format!("WebAssembly.Global<{inner_ty}>")
    }

    fn map_val_type_to_kt(ty: &ValType) -> &'static str {
        match ty {
            ValType::I32 => "Int",
            ValType::F32 => "Float",
            ValType::F64 => "Double",
            _ => "Any",
        }
    }

    fn global_entities(&self) -> &[GlobalEntity] {
        self.global_entities
    }

    fn wasm_bindgen_js_modules(&self) -> &[WasmBindgenJsModules] {
        self.wasm_bindgen_js_modules
    }
}

/// Renderer for the per-crate Kotlin file that surfaces the Rust
/// `wasm32-unknown-unknown` cdylib bytes to a Kotlin/Wasm host (the
/// `wasmJs` Kotlin target). Sibling to `KotlinJsRenderer`; both consume
/// the same transformed WASM module but emit very different surfaces.
///
/// `KotlinJsRenderer` (Kotlin/JS path) emits a self-contained loader:
/// `external WebAssembly` class hierarchy, base64-decode helpers,
/// nested `external interface RustWebAssemblyExports`, per-module
/// `Import_<name>` nested classes with `tblIdx_<name>` constants, and
/// the `createInstance(...)` factory. That surface relies on a slew of
/// Kotlin/JS-only features that Kotlin/Wasm rejects: `kotlin.Any`
/// parameters in `external fun`, `org.khronos.webgl.ArrayBuffer`,
/// nested classes inside interfaces, and `dynamic`. T0.C.7.C found 200+
/// errors when the JS file was fed straight to Kotlin/Wasm 2.1.10.
///
/// `KotlinWasmJsRenderer` (this struct) emits a minimal, Kotlin/Wasm-
/// compatible file: package declaration, the `GOBLEY_WASM_BASE64`
/// chunks, and a small `gobleyWasmBytes()` decoder. Actual
/// `WebAssembly.instantiate` happens on the JavaScript side via the
/// `gobley_<crate>_wasmjs_helpers.mjs` shim emitted by
/// `KotlinWasmJsHelpersRenderer` (T0.C.6). The bindgen-emitted
/// `wasm-js/NamespaceLibraryTemplate.kt` calls into the .mjs shim's
/// `init(...)` once T0.C.3.b lands; until then the Kotlin file just
/// needs to compile cleanly so the `wasmJsMain` source set is healthy.
///
/// Marked `pub(crate)`: external CLI consumers go through
/// `Transformer::render_into_wasmjs_kt`, mirroring the
/// `KotlinWasmJsHelpersRenderer` / `render_into_mjs` pairing.
#[derive(Template)]
#[template(syntax = "kt", escape = "none", path = "wasmjs.kt")]
pub(crate) struct KotlinWasmJsRenderer<'a> {
    package_name: Option<&'a str>,
    base64: &'a str,
    helpers_base64: &'a str,
}

impl KotlinWasmJsRenderer<'_> {
    fn chunk_groups(base64: &str) -> Vec<Vec<&str>> {
        const CHUNK_SIZE: usize = 16 * 1024;
        const CHUNKS_PER_GROUP: usize = 256;

        let chunks = base64
            .as_bytes()
            .chunks(CHUNK_SIZE)
            .map(|chunk| {
                std::str::from_utf8(chunk).expect("base64 encoder should only emit valid ASCII")
            })
            .collect::<Vec<_>>();

        chunks
            .chunks(CHUNKS_PER_GROUP)
            .map(|group| group.to_vec())
            .collect()
    }

    fn base64_chunk_groups(&self) -> Vec<Vec<&str>> {
        Self::chunk_groups(self.base64)
    }

    fn helpers_base64_chunk_groups(&self) -> Vec<Vec<&str>> {
        Self::chunk_groups(self.helpers_base64)
    }
}

/// Well-known import module that gobley-bindgen-emitted Kotlin/Wasm
/// expects to bind for callback-interface dispatch and async-future
/// continuations. Documented in `crates/gobley-uniffi-bindgen/src/templates/wasm-js/CallbackInterfaceImpl.kt`
/// and `wasm-js/Async.kt`.
const GOBLEY_CALLBACKS_MODULE: &str = "gobley_callbacks";

/// Description of a single Rust-side import the JS shim must bind to a
/// Kotlin `@JsExport` function. Currently always sourced from imports
/// in the `gobley_callbacks` module — see `Transformer::rust_callback_imports`.
#[derive(Debug, Clone)]
struct WasmJsCallbackBinding {
    module: String,
    name: String,
}

/// Renderer for the per-crate JavaScript shim that bridges the Rust
/// `wasm32-unknown-unknown` cdylib and the Kotlin/Wasm bindings.
///
/// Distinct from `KotlinJsRenderer` (which targets Kotlin/JS plain-`js()`
/// loaders): the wasmJs renderer emits ES module JavaScript (`.mjs`) and
/// is consumed at runtime by the Kotlin/Wasm app via `import("...")`.
///
/// The shim's responsibilities are documented in the template
/// (`templates/wasmjs_helpers.mjs`):
///   * `WebAssembly.instantiate` the Rust module with an import
///     dictionary mapping `gobley_callbacks.<name>` → Kotlin `@JsExport`
///     `fun <name>(...)`.
///   * Install a memory-growth-safe `globalThis.__gobleyWasmMemory`
///     `DataView` that the inline `@JsFun` accessors in
///     `wasm-js/PointerHelper.kt` read on every primitive call.
///   * Expose `globalThis.__gobleyRustExports` for inline `@JsFun`
///     bodies that reach into Rust exports directly (vtable index
///     lookups in `wasm-js/CallbackInterfaceImpl.kt`).
#[derive(Template)]
#[template(syntax = "kt", escape = "none", path = "wasmjs_helpers.mjs")]
pub struct KotlinWasmJsHelpersRenderer<'a> {
    crate_name: &'a str,
    callback_bindings: &'a [WasmJsCallbackBinding],
    wasm_bindgen_modules: &'a [WasmBindgenJsModules],
    global_entities: &'a [GlobalEntity],
}

impl KotlinWasmJsHelpersRenderer<'_> {
    /// Names of `@JsExport` Kotlin functions the host must surface to
    /// the shim. We derive this from the Rust import set rather than a
    /// separate registry: every `gobley_callbacks.<name>` import in the
    /// Rust module corresponds to a Kotlin `@JsExport public fun <name>`.
    fn kotlin_callback_exports(&self) -> Vec<&str> {
        let mut names: Vec<&str> = self
            .callback_bindings
            .iter()
            .map(|b| b.name.as_str())
            .collect();
        names.sort_unstable();
        names.dedup();
        names
    }

    fn wasm_bindgen_module_names(&self) -> Vec<&str> {
        self.wasm_bindgen_modules
            .iter()
            .map(|m| m.name.as_str())
            .collect()
    }

    fn wasm_bindgen_factories(&self) -> Vec<(&str, &str)> {
        self.global_entities
            .iter()
            .filter(|e| e.lang == GlobalEntityLang::JavaScript)
            .map(|e| (e.name.as_str(), e.expr.as_str()))
            .collect()
    }

    /// Rust-side imports the shim must bind to Kotlin `@JsExport`
    /// functions. Currently sourced from the `gobley_callbacks` module
    /// only; see `GOBLEY_CALLBACKS_MODULE`.
    fn rust_callback_imports(&self) -> &[WasmJsCallbackBinding] {
        self.callback_bindings
    }
}

impl Transformer {
    pub fn new(input: &[u8], function_imports: Vec<WasmFunctionImport>) -> anyhow::Result<Self> {
        Ok(Self {
            module: Module::from_buffer(input)?,
            function_imports,
            global_entities: vec![],
            wasm_bindgen_js_modules: vec![],
        })
    }

    fn transform(&mut self) -> anyhow::Result<()> {
        self.inject_stack_pointer_shim()?;
        self.inject_function_imports();
        if self.needs_wasm_bindgen() {
            self.transform_using_wasm_bindgen()?;
        }
        // Must run AFTER wasm-bindgen: transform_using_wasm_bindgen replaces
        // self.module via mem::swap, discarding any exports added earlier.
        self.export_indirect_function_table();
        Ok(())
    }

    /// Run the full transformation pipeline (public entry point for
    /// callers that need the `.mjs` renderer to see all post-transform
    /// state: injected imports, wasm-bindgen modules, exported table).
    pub fn transform_all(&mut self) -> anyhow::Result<()> {
        self.transform()
    }

    fn export_indirect_function_table(&mut self) {
        use walrus::RefType;
        // main_function_table() returns None when multiple funcref tables
        // exist. Fall back to scanning all tables for Funcref.
        let table_id = match self.module.tables.main_function_table() {
            Ok(Some(id)) => Some(id),
            _ => self
                .module
                .tables
                .iter()
                .find(|t| t.element_ty == RefType::Funcref)
                .map(|t| t.id()),
        };
        if let Some(table_id) = table_id {
            // Check by NAME, not just table ID — wasm-bindgen may export the
            // same table under a different name (e.g. __wbindgen_export_2).
            let has_correct_name = self.module.exports.iter().any(|e| {
                e.name == "__indirect_function_table"
                    && matches!(e.item, walrus::ExportItem::Table(t) if t == table_id)
            });
            if !has_correct_name {
                self.module
                    .exports
                    .add("__indirect_function_table", table_id);
            }
        }
    }

    pub fn render_into_kt(mut self, package_name: Option<&str>) -> anyhow::Result<String> {
        use base64::prelude::BASE64_STANDARD;

        self.transform()?;

        let wasm = self.module.emit_wasm();
        let wasm_base64 = BASE64_STANDARD.encode(&wasm);
        let module = Module::from_buffer(&wasm)?;
        let renderer = KotlinJsRenderer {
            package_name,
            base64: &wasm_base64,
            module: &module,
            global_entities: &self.global_entities,
            wasm_bindgen_js_modules: &self.wasm_bindgen_js_modules,
        };
        Ok(renderer.render()?)
    }

    /// Render the per-crate Kotlin file for the **wasmJs** (Kotlin/Wasm)
    /// target. Sibling of `render_into_kt`, which targets Kotlin/JS.
    ///
    /// Emits a Kotlin/Wasm-compatible source: no `kotlin.Any` in
    /// `external fun`, no `org.khronos.webgl.ArrayBuffer`, no nested
    /// classes inside interfaces, no `dynamic`. The actual
    /// `WebAssembly.instantiate` call is delegated to the per-crate JS
    /// shim emitted by `render_into_mjs`; the Kotlin file carries both
    /// the WASM bytes and the shim source as chunked base64 plus a
    /// stdlib-free base64 decoder (`gobleyWasmBytes`) — Kotlin/Wasm
    /// 2.1.10's stdlib does not ship `kotlin.io.encoding.Base64` for
    /// the wasmJs target.
    ///
    /// The transformer pipeline (`transform()`) runs identically to the
    /// Kotlin/JS path so the underlying WASM bytes match exactly across
    /// targets — same stack-pointer shim, same function-imports
    /// injection, same wasm-bindgen rewrites. Only the Kotlin wrapper
    /// differs.
    pub fn render_into_wasmjs_kt(
        mut self,
        package_name: Option<&str>,
        crate_name: &str,
    ) -> anyhow::Result<String> {
        use base64::prelude::BASE64_STANDARD;

        self.transform()?;

        let wasm = self.module.emit_wasm();
        let wasm_base64 = BASE64_STANDARD.encode(&wasm);
        let helpers_source = self.render_into_mjs(crate_name)?;
        let helpers_base64 = BASE64_STANDARD.encode(helpers_source.as_bytes());
        let renderer = KotlinWasmJsRenderer {
            package_name,
            base64: &wasm_base64,
            helpers_base64: &helpers_base64,
        };
        Ok(renderer.render()?)
    }

    /// Render the per-crate Kotlin/Wasm JS shim (`.mjs`) for the
    /// already-loaded module. Idempotent: does NOT re-run the
    /// transformation pipeline. Call after `render_into_kt` on a fresh
    /// `Transformer` instance, or call standalone — both work.
    ///
    /// `crate_name` is the Cargo crate name (snake_cased version goes
    /// into the doc comment of the emitted module). The shim itself
    /// does not bake the name into runtime behavior.
    ///
    /// The shim binds Rust imports under module `gobley_callbacks` to
    /// Kotlin `@JsExport` functions of the same name. If the Rust
    /// module declares no such imports the shim still emits — the host
    /// can use it purely for `init` + memory bridging without callback
    /// support.
    pub fn render_into_mjs(&self, crate_name: &str) -> anyhow::Result<String> {
        let bindings = Self::collect_callback_bindings(&self.module);
        let renderer = KotlinWasmJsHelpersRenderer {
            crate_name,
            callback_bindings: &bindings,
            wasm_bindgen_modules: &self.wasm_bindgen_js_modules,
            global_entities: &self.global_entities,
        };
        Ok(renderer.render()?)
    }

    /// Walk the WASM imports section and collect every entry under
    /// `GOBLEY_CALLBACKS_MODULE`. Order is preserved as walrus iterates
    /// the section, which mirrors the original module's import order —
    /// keeps generated shim diffs reviewable across rebuilds.
    fn collect_callback_bindings(module: &Module) -> Vec<WasmJsCallbackBinding> {
        let mut bindings = vec![];
        for import in module.imports.iter() {
            if import.module != GOBLEY_CALLBACKS_MODULE {
                continue;
            }
            if !matches!(import.kind, ImportKind::Function(_)) {
                continue;
            }
            bindings.push(WasmJsCallbackBinding {
                module: import.module.clone(),
                name: import.name.clone(),
            });
        }
        bindings
    }
}

#[cfg(test)]
mod tests {
    //! Synth-WASM tests for the Kotlin/Wasm `.mjs` shim renderer.
    //!
    //! Locks the contract between `Transformer::render_into_mjs` and the
    //! Kotlin/Wasm bindgen output (see `wasm-js/CallbackInterfaceImpl.kt` and
    //! `wasm-js/Async.kt`): every Rust import under the well-known
    //! `gobley_callbacks` module must surface as both a `KOTLIN_CALLBACK_EXPORTS`
    //! entry and a `RUST_IMPORTS_TO_BIND` entry, and the shim must always emit
    //! the `init` entrypoint plus the optional `__indirect_function_table`
    //! pickup that the T0.C.1 spike relies on.
    //!
    //! These tests construct a minimal walrus `Module` rather than checking in
    //! a `.wasm` fixture so a wasm-tools/toolchain bump never silently breaks
    //! them. Bytes round-trip through `Module::emit_wasm` -> `Transformer::new`
    //! -> `render_into_mjs`, which is exactly the path the production
    //! `gobley-wasm-transformer` CLI takes minus the read-from-disk step.

    use super::*;
    use walrus::{FunctionBuilder, Module, ValType};

    /// Construct a tiny WASM cdylib analogue: one local `memory` export, three
    /// `gobley_callbacks` imports (one async-continuation plus two per-interface
    /// dispatchers, mirroring the production layout from coverall +
    /// `Async.kt`), one stray non-callback import to confirm filtering, and a
    /// single local function so emitted modules carry a code section.
    fn build_test_wasm() -> Vec<u8> {
        let mut module = Module::default();

        // `transform()` expects the first global to act as the mutable stack
        // pointer so it can synthesize __gobley_add_to_stack_pointer.
        module
            .globals
            .add_local(ValType::I32, true, false, ConstExpr::Value(Value::I32(0)));

        // Rust-side `memory` export — the .mjs shim asserts on its presence.
        let mem_id = module.memories.add_local(false, false, 1, None, None);
        module.exports.add("memory", mem_id);

        // Three callback imports under the well-known module name. Names
        // follow the bindgen convention from `wasm-js/CallbackInterfaceImpl.kt`
        // and `wasm-js/Async.kt`. Order is intentional: shuffled so we can
        // assert the renderer preserves declaration order in the emitted
        // `RUST_IMPORTS_TO_BIND` array.
        let cb_async_ty = module.types.add(&[ValType::I64, ValType::I32], &[]);
        module.add_import_func(
            GOBLEY_CALLBACKS_MODULE,
            "gobley_async_continuation_callback",
            cb_async_ty,
        );
        let cb_iface_ty = module
            .types
            .add(&[ValType::I64, ValType::I32, ValType::I32], &[]);
        module.add_import_func(
            GOBLEY_CALLBACKS_MODULE,
            "gobley_callback_Foo_bar",
            cb_iface_ty,
        );
        module.add_import_func(
            GOBLEY_CALLBACKS_MODULE,
            "gobley_callback_Foo_uniffi_free",
            cb_iface_ty,
        );

        // A non-callback import to confirm the renderer ignores anything
        // outside `gobley_callbacks` (e.g. wasi-side `env` calls).
        let noop_ty = module.types.add(&[], &[]);
        module.add_import_func("env", "should_not_appear_in_shim", noop_ty);

        // Minimal local function + export. `__indirect_function_table` is
        // intentionally NOT emitted: the .mjs shim must tolerate its absence
        // (`-C link-arg=--export-table` is documented as optional per spike).
        let mut builder = FunctionBuilder::new(&mut module.types, &[], &[]);
        builder.func_body();
        let local_id = builder.finish(vec![], &mut module.funcs);
        module.exports.add("uniffi_test_init", local_id);

        module.emit_wasm()
    }

    #[test]
    fn render_into_mjs_emits_callback_bindings() {
        let wasm = build_test_wasm();
        let transformer =
            Transformer::new(&wasm, vec![]).expect("walrus must accept the synthesized module");
        let mjs = transformer
            .render_into_mjs("synth_crate")
            .expect("render must succeed for a well-formed module");

        // Crate name flows into the doc header.
        assert!(
            mjs.contains("`synth_crate`"),
            "missing crate-name doc header: {mjs}"
        );

        // Every gobley_callbacks import must surface in both arrays.
        for name in [
            "gobley_async_continuation_callback",
            "gobley_callback_Foo_bar",
            "gobley_callback_Foo_uniffi_free",
        ] {
            assert!(
                mjs.contains(&format!("\"{name}\"")),
                "KOTLIN_CALLBACK_EXPORTS missing `{name}`: {mjs}"
            );
            assert!(
                mjs.contains(&format!("name: \"{name}\"")),
                "RUST_IMPORTS_TO_BIND missing `{name}`: {mjs}"
            );
        }

        // Non-callback imports must NOT leak into either array.
        assert!(
            !mjs.contains("should_not_appear_in_shim"),
            "non-`gobley_callbacks` import leaked into shim: {mjs}"
        );

        // Public surface the bindgen consumers depend on.
        assert!(
            mjs.contains("export async function init("),
            "missing init export"
        );
        assert!(
            mjs.contains("globalThis.__gobleyWasmMemory"),
            "missing memory view global"
        );
        assert!(
            mjs.contains("globalThis.__gobleyRustExports"),
            "missing exports global"
        );
        assert!(
            mjs.contains("__indirect_function_table"),
            "missing optional vtable table pickup (T0.C.1 spike)"
        );
        assert!(
            mjs.contains("gobley_callbacks"),
            "missing gobley_callbacks module ref"
        );
    }

    #[test]
    fn render_into_mjs_handles_zero_callback_imports() {
        // Rust crates without any callback interfaces still need the shim
        // (init + memory bridge). Verify the renderer produces a usable
        // module rather than an empty file or a syntax error.
        let mut module = Module::default();
        let mem_id = module.memories.add_local(false, false, 1, None, None);
        module.exports.add("memory", mem_id);

        let mut builder = FunctionBuilder::new(&mut module.types, &[], &[]);
        builder.func_body();
        let local_id = builder.finish(vec![], &mut module.funcs);
        module.exports.add("uniffi_no_callbacks", local_id);

        let wasm = module.emit_wasm();
        let transformer =
            Transformer::new(&wasm, vec![]).expect("walrus must accept callback-less module");
        let mjs = transformer
            .render_into_mjs("no_callbacks_crate")
            .expect("render must succeed even with zero callback imports");

        assert!(
            mjs.contains("export async function init("),
            "init still required"
        );
        assert!(
            mjs.contains("KOTLIN_CALLBACK_EXPORTS"),
            "array still declared"
        );
        assert!(mjs.contains("RUST_IMPORTS_TO_BIND"), "array still declared");
        assert!(
            mjs.contains("`no_callbacks_crate`"),
            "crate name still in header"
        );
    }

    #[test]
    fn render_into_mjs_preserves_import_order() {
        // The renderer documents that import order matches the WASM section
        // order so generated diffs stay reviewable. Lock that contract.
        let mut module = Module::default();
        let mem_id = module.memories.add_local(false, false, 1, None, None);
        module.exports.add("memory", mem_id);

        let ty = module.types.add(&[], &[]);
        for name in [
            "gobley_callback_Z_x",
            "gobley_callback_A_y",
            "gobley_callback_M_z",
        ] {
            module.add_import_func(GOBLEY_CALLBACKS_MODULE, name, ty);
        }

        let wasm = module.emit_wasm();
        let transformer = Transformer::new(&wasm, vec![]).unwrap();
        let mjs = transformer.render_into_mjs("ordered").unwrap();

        // KOTLIN_CALLBACK_EXPORTS is sorted for deterministic JS, but
        // RUST_IMPORTS_TO_BIND must reflect declaration order so the host
        // can spot regressions in the linker's import-section layout.
        let pos_z = mjs
            .find("name: \"gobley_callback_Z_x\"")
            .expect("Z_x present");
        let pos_a = mjs
            .find("name: \"gobley_callback_A_y\"")
            .expect("A_y present");
        let pos_m = mjs
            .find("name: \"gobley_callback_M_z\"")
            .expect("M_z present");
        assert!(pos_z < pos_a, "import order Z then A must be preserved");
        assert!(pos_a < pos_m, "import order A then M must be preserved");
    }

    #[test]
    fn render_into_wasmjs_kt_embeds_helpers_module_payload() {
        let wasm = build_test_wasm();
        let kotlin = Transformer::new(&wasm, vec![])
            .unwrap()
            .render_into_wasmjs_kt(Some("gobley.wasm.test"), "embedded_helpers")
            .expect("wasmJs Kotlin render must succeed");

        assert!(
            kotlin.contains("GOBLEY_WASMJS_HELPERS_BASE64_CHUNK_GROUPS"),
            "embedded helper chunks missing: {kotlin}"
        );
        assert!(
            kotlin.contains("GOBLEY_WASMJS_HELPERS_MODULE_URL"),
            "embedded helper module URL missing: {kotlin}"
        );
        assert!(
            kotlin.contains("data:text/javascript;base64,"),
            "embedded helper data URL prefix missing: {kotlin}"
        );
    }
}
